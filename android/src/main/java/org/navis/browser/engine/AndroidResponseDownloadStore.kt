/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.DocumentsContract
import android.util.Log
import android.webkit.URLUtil
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.navis.browser.R
import org.navis.browser.DownloadNotificationIntents
import org.navis.browser.downloads.DownloadNotificationCommand
import org.navis.browser.api.DownloadFailure
import org.navis.browser.api.DownloadId
import org.navis.browser.api.DownloadRequest
import org.navis.browser.api.DownloadResult
import org.navis.browser.api.SessionId
import org.navis.browser.engine.extensions.PausableDownloadInputStream
import org.navis.browser.engine.runtime.EngineDownloadResponse
import org.navis.browser.engine.runtime.EngineDownloadRetryMetadata
import org.navis.browser.permissions.AndroidNotificationPermissions

/**
 * Persists bytes already fetched by the engine. It never issues a second network request, so HTTP
 * cookies/authentication and non-network response bodies keep their original semantics.
 */
internal class AndroidResponseDownloadStore(
    context: Context,
    private val onRequested: (DownloadRequest) -> Unit,
    private val onFinished: (DownloadResult) -> Unit,
    private val windowIdForSession: (SessionId) -> Long? = { null },
    private val defaultDirectoryUri: () -> String = { "" },
) : AutoCloseable {
    private val applicationContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val idPreferences = applicationContext.getSharedPreferences(
        DOWNLOAD_ID_PREFERENCES,
        Context.MODE_PRIVATE,
    )
    private val idLock = Any()
    private var nextDownloadId = idPreferences.getLong(NEXT_DOWNLOAD_ID_KEY, 1L)
        .coerceIn(1L, MAX_DOWNLOAD_ID)
    private val closed = AtomicBoolean(false)
    private val tasks = ConcurrentHashMap<DownloadId, TransferTask>()
    private val observers = linkedSetOf<AndroidDownloadStoreObserver>()
    private val legacyNames = mutableSetOf<String>()
    private val legacyNameLock = Any()
    private val overwriteTargets = mutableSetOf<String>()
    private val notifications = DownloadNotifications(applicationContext)
    private val executor = ThreadPoolExecutor(
        MAX_CONCURRENT_DOWNLOADS,
        MAX_CONCURRENT_DOWNLOADS,
        THREAD_IDLE_SECONDS,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(MAX_QUEUED_DOWNLOADS),
        { runnable ->
            Thread(runnable, "NavisResponseDownload-${nextWorkerId.incrementAndGet()}").apply {
                isDaemon = true
            }
        },
        ThreadPoolExecutor.AbortPolicy(),
    ).apply {
        allowCoreThreadTimeOut(true)
    }

    fun enqueue(
        source: EngineDownloadResponse,
        onTransferFinished: ((DownloadResult) -> Unit)? = null,
    ) {
        enqueueTracked(source, onTransferFinished = onTransferFinished)
    }

    fun enqueueTracked(
        source: EngineDownloadResponse,
        options: AndroidDownloadWriteOptions = AndroidDownloadWriteOptions(),
        notifyObservers: Boolean = true,
        onTransferProgress: ((DownloadRequest, Long) -> Unit)? = null,
        onTransferFinished: ((DownloadResult) -> Unit)? = null,
    ): DownloadRequest {
        val request = source.toRequest(nextId(), options.fileName)
        dispatch {
            onRequested(request)
            if (notifyObservers) {
                synchronized(observers) {
                    observers.toList().forEach {
                        it.onTransferRequested(request)
                        it.onTransferRetryMetadata(request, source.retryMetadata)
                    }
                }
            }
        }

        if (closed.get()) {
            source.close()
            dispatchResult(
                request.failed(DownloadFailure.CANCELLED),
                notifyObservers,
                onTransferFinished,
            )
            return request
        }
        val body = source.body
        if (body == null) {
            source.close()
            dispatchResult(
                request.failed(DownloadFailure.RESPONSE_BODY_UNAVAILABLE),
                notifyObservers,
                onTransferFinished,
            )
            return request
        }

        // Both ordinary and extension downloads pause the original response stream. Delegating
        // close to its owner preserves the exactly-once close contract when cancellation races
        // the worker's finally block. Extension downloads already supply this same pause gate.
        val controlledBody = body as? PausableDownloadInputStream
            ?: PausableDownloadInputStream(object : FilterInputStream(body) {
                override fun close() = source.close()
            })

        // Download bytes never wait for a permission dialog. Keep the latest notification until
        // the foreground user's first OS decision so even a very short download can be announced.
        notifications.preparePermissionRequest()
        dispatch {
            AndroidNotificationPermissions.requestForDownload(windowIdForSession(request.sessionId), notifications::onPermissionResult)
        }

        val effectiveOptions = if (options.destinationUri == null && options.replaceExistingUri == null) {
            options.copy(directoryUri = defaultDirectoryUri().takeIf(String::isNotEmpty))
        } else options
        val task = TransferTask(
            source,
            controlledBody,
            request,
            effectiveOptions,
            request.id.value.toInt(),
            notifyObservers,
            onTransferProgress,
            onTransferFinished,
        )
        val rejected = synchronized(tasks) {
            when {
                closed.get() -> DownloadFailure.CANCELLED
                tasks.size >= MAX_CONCURRENT_DOWNLOADS + MAX_QUEUED_DOWNLOADS -> DownloadFailure.TOO_MANY_PENDING
                else -> { tasks[request.id] = task; null }
            }
        }
        if (notifyObservers) dispatch {
            synchronized(observers) {
                observers.toList().forEach { it.onTransferControl(request, controlledBody) }
            }
        }
        if (rejected != null) task.rejectBeforeStart(rejected)
        else LegacyDownloadPolicy.authorize(
            required = needsPublicStoragePermission(effectiveOptions),
            hasPermission = AndroidExtensionDownloadSaveAs.hasPublicDownloadsPermission(applicationContext),
            requestPermission = {
                AndroidExtensionDownloadSaveAs.requestPublicDownloadsPermission(windowIdForSession(request.sessionId))
            },
            start = task::schedule,
            reject = { task.rejectBeforeStart(DownloadFailure.STORAGE_UNAVAILABLE) },
        )
        return request
    }

    fun cancel(id: DownloadId): Boolean {
        val task = tasks[id] ?: return false
        task.cancel()
        return true
    }

    fun reject(source: EngineDownloadResponse, failure: DownloadFailure) {
        val request = source.toRequest(nextId(), null)
        source.close()
        dispatch {
            onRequested(request)
            synchronized(observers) { observers.toList().forEach { it.onTransferRequested(request) } }
        }
        dispatchResult(request.failed(failure), true, null)
    }

    fun addObserver(observer: AndroidDownloadStoreObserver) {
        if (!closed.get()) {
            synchronized(observers) { observers += observer }
        }
    }

    fun removeObserver(observer: AndroidDownloadStoreObserver) {
        synchronized(observers) { observers -= observer }
    }

    fun advanceNextIdPast(id: Long) {
        if (id !in 1 until MAX_DOWNLOAD_ID) return
        synchronized(idLock) {
            if (nextDownloadId <= id) {
                nextDownloadId = id + 1L
                idPreferences.edit().putLong(NEXT_DOWNLOAD_ID_KEY, nextDownloadId).apply()
            }
        }
    }

    override fun close() {
        val pending = synchronized(tasks) {
            if (!closed.compareAndSet(false, true)) return
            tasks.values.toList()
        }
        executor.shutdownNow()
        pending.forEach(TransferTask::cancel)
        synchronized(observers) { observers.clear() }
    }

    private fun nextId(): DownloadId {
        synchronized(idLock) {
            val value = nextDownloadId
            nextDownloadId = if (value == MAX_DOWNLOAD_ID) 1L else value + 1L
            idPreferences.edit().putLong(NEXT_DOWNLOAD_ID_KEY, nextDownloadId).apply()
            return DownloadId(value)
        }
    }

    private fun EngineDownloadResponse.toRequest(
        id: DownloadId,
        requestedFileName: String?,
    ): DownloadRequest {
        val mimeType = DownloadMetadata.sanitizeMimeType(contentType)
        val namingUri = uri.take(MAX_NAMING_URI_CHARS).takeUnless {
            it.startsWith("data:", ignoreCase = true) ||
                it.startsWith("blob:", ignoreCase = true)
        }.orEmpty()
        val guessedName = requestedFileName ?: suggestedFileName
            ?.substringAfterLast('/')
            ?.takeIf(String::isNotBlank) ?: runCatching {
            URLUtil.guessFileName(
                namingUri,
                contentDisposition?.take(MAX_CONTENT_DISPOSITION_CHARS),
                mimeType,
            )
        }.getOrNull()
        return DownloadRequest(
            id = id,
            sessionId = sessionId,
            sourceUri = DownloadMetadata.boundedSourceUri(uri),
            fileName = DownloadMetadata.sanitizeFileName(guessedName),
            contentType = mimeType,
            expectedBytes = contentLength?.takeIf { it > 0L },
            privateMode = privateMode,
        )
    }

    private fun createDestination(
        request: DownloadRequest,
        options: AndroidDownloadWriteOptions,
        checkActive: () -> Unit,
    ): PendingDestination {
        // The user can revoke permission after admission but before this worker runs.
        if (needsPublicStoragePermission(options) &&
            !AndroidExtensionDownloadSaveAs.hasPublicDownloadsPermission(applicationContext)) {
            throw StorageUnavailableException(IOException("Public Downloads requires Android storage permission"))
        }
        options.replaceExistingUri?.let { destination ->
            val uri = Uri.parse(destination)
            AndroidDownloadFileAccess.requireWritableTarget(applicationContext, uri)
            val file = if (uri.scheme == "file") File(requireNotNull(uri.path)).canonicalFile else null
            val key = file?.path ?: if (android.provider.DocumentsContract.isDocumentUri(applicationContext, uri)) {
                "${uri.authority}:${android.provider.DocumentsContract.getDocumentId(uri)}"
            } else uri.normalizeScheme().toString()
            synchronized(overwriteTargets) {
                check(overwriteTargets.add(key)) { "A download already owns this overwrite destination" }
            }
            try {
                val resolver = applicationContext.contentResolver
                val staged = DownloadOverwriteStaging.create(
                    stagingDirectory = File(applicationContext.cacheDir, "download-overwrite"),
                    recoveryDirectory = File(applicationContext.filesDir, "download-overwrite-recovery"),
                    expectedBytes = request.expectedBytes,
                    validate = {
                        AndroidDownloadFileAccess.requireWritableTarget(applicationContext, uri)
                        if (file != null && File(requireNotNull(uri.path)).canonicalFile != file) {
                            throw IOException("Download overwrite target changed")
                        }
                    },
                    checkActive = checkActive,
                    atomicFile = file,
                    readTarget = { resolver.openInputStream(uri) ?: throw IOException("Original download cannot be backed up") },
                    writeTarget = { resolver.openOutputStream(uri, "rwt") ?: throw IOException("Download target has no replacement stream") },
                )
                val released = AtomicBoolean(false)
                fun release() {
                    if (released.compareAndSet(false, true)) synchronized(overwriteTargets) { overwriteTargets.remove(key) }
                }
                return object : PendingDestination {
                    override fun writeFrom(input: InputStream, onProgress: (Long) -> Unit) = staged.writeFrom(input, onProgress)
                    override fun commit(): String {
                        try { staged.commit(); return uri.toString() }
                        finally { release() }
                    }
                    override fun abort() {
                        try { staged.abort() }
                        finally { release() }
                    }
                }
            } catch (error: Throwable) {
                synchronized(overwriteTargets) { overwriteTargets.remove(key) }
                throw error
            }
        }
        options.destinationUri?.let { destination ->
            return ContentUriDestination(
                applicationContext.contentResolver,
                Uri.parse(destination),
            )
        }
        options.directoryUri?.let { tree ->
            val resolver = applicationContext.contentResolver
            val treeUri = Uri.parse(tree)
            var parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
            for (component in options.relativeDirectory?.split('/').orEmpty()) {
                checkActive()
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getDocumentId(parent))
                val existing = resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { cursor ->
                    var found: Uri? = null
                    while (cursor.moveToNext()) if (cursor.getString(1) == component &&
                        cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                        found = DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(0)); break
                    }
                    found
                }
                parent = existing ?: DocumentsContract.createDocument(resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, component)
                    ?: throw IOException("The selected download folder is unavailable")
            }
            checkActive()
            val uri = DocumentsContract.createDocument(resolver, parent, request.contentType, request.fileName)
                ?: throw IOException("Could not create the download document")
            return ContentUriDestination(resolver, uri)
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStoreDestination.create(
                applicationContext.contentResolver,
                request,
                options.relativeDirectory,
                options.requireExactFileName,
            )
        } else {
            LegacyDestination.create(
                request,
                options.relativeDirectory,
                options.requireExactFileName,
                ::reserveLegacyName,
                ::releaseLegacyName,
            )
        }
    }

    private fun needsPublicStoragePermission(options: AndroidDownloadWriteOptions): Boolean =
        LegacyDownloadPolicy.needsStoragePermission(Build.VERSION.SDK_INT,
            options.replaceExistingUri?.startsWith("content://") ?: (options.destinationUri != null || options.directoryUri != null))

    private fun reserveLegacyName(directory: File, requestedName: String): String {
        synchronized(legacyNameLock) {
            var index = 0
            while (true) {
                val candidate = if (index == 0) {
                    requestedName
                } else {
                    DownloadMetadata.numberedFileName(requestedName, index)
                }
                val key = "${directory.absolutePath}\u0000$candidate"
                if (key !in legacyNames && !Files.exists(File(directory, candidate).toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    legacyNames += key
                    return candidate
                }
                index += 1
            }
        }
    }

    private fun releaseLegacyName(directory: File, fileName: String) {
        synchronized(legacyNameLock) {
            legacyNames -= "${directory.absolutePath}\u0000$fileName"
        }
    }

    private fun dispatch(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runCatching(action)
                .onFailure { Log.w(LOG_TAG, "Download callback failed", it) }
        } else {
            mainHandler.post {
                runCatching(action)
                    .onFailure { Log.w(LOG_TAG, "Download callback failed", it) }
            }
        }
    }

    private fun dispatchResult(
        result: DownloadResult,
        notifyObservers: Boolean = true,
        onTransferFinished: ((DownloadResult) -> Unit)? = null,
    ) {
        dispatch {
            onFinished(result)
            if (notifyObservers) {
                synchronized(observers) {
                    observers.toList().forEach { it.onTransferFinished(result) }
                }
            }
            onTransferFinished?.invoke(result)
        }
    }

    private inner class TransferTask(
        private val source: EngineDownloadResponse,
        private val controlledBody: PausableDownloadInputStream,
        private val request: DownloadRequest,
        private val options: AndroidDownloadWriteOptions,
        private val notificationId: Int,
        private val notifyObservers: Boolean,
        private val onTransferProgress: ((DownloadRequest, Long) -> Unit)?,
        private val onTransferFinished: ((DownloadResult) -> Unit)?,
    ) : Runnable {
        private val started = AtomicBoolean(false)
        private val finished = AtomicBoolean(false)
        private val cancelled = AtomicBoolean(false)
        private var lastNotificationAt = 0L
        private var destination: PendingDestination? = null

        fun schedule() {
            if (finished.get() || cancelled.get()) return
            try { executor.execute(this) }
            catch (_: RejectedExecutionException) {
                rejectBeforeStart(if (closed.get()) DownloadFailure.CANCELLED else DownloadFailure.TOO_MANY_PENDING)
            }
            catch (error: RuntimeException) {
                Log.w(LOG_TAG, "Could not schedule response-body download", error)
                rejectBeforeStart(DownloadFailure.TRANSFER_FAILED)
            }
        }

        fun rejectBeforeStart(failure: DownloadFailure) {
            if (!started.compareAndSet(false, true)) return
            runCatching { controlledBody.close() }
            source.close()
            tasks.remove(request.id, this)
            finish(request.failed(if (cancelled.get()) DownloadFailure.CANCELLED else failure))
        }

        override fun run() {
            if (!started.compareAndSet(false, true) || finished.get()) {
                return
            }
            try {
                transfer()
            } finally {
                runCatching { controlledBody.close() }
                source.close()
                tasks.remove(request.id, this)
            }
        }

        private fun transfer() {
            if (cancelled.get()) {
                finish(request.failed(DownloadFailure.CANCELLED))
                return
            }

            notifications.showProgress(notificationId, request, 0L)
            var bytesWritten = 0L
            try {
                val pending = createDestination(request, options) {
                    if (cancelled.get()) throw DownloadCancelledException()
                }
                destination = pending
                bytesWritten = pending.writeFrom(controlledBody) { currentBytes ->
                    // Retain bytes already written if the next read/write is interrupted.
                    bytesWritten = currentBytes
                    if (cancelled.get()) {
                        throw DownloadCancelledException()
                    }
                    maybeUpdateNotification(currentBytes)
                }
                if (cancelled.get()) {
                    throw DownloadCancelledException()
                }
                val destinationUri = pending.commit()
                destination = null
                finish(
                    DownloadResult(
                        request = request,
                        destinationUri = destinationUri,
                        bytesWritten = bytesWritten,
                        failure = null,
                    ),
                )
            } catch (_: DownloadCancelledException) {
                abortDestination()
                finish(request.failed(DownloadFailure.CANCELLED, bytesWritten))
            } catch (error: StorageUnavailableException) {
                abortDestination()
                Log.w(LOG_TAG, "Download storage is unavailable", error)
                finish(request.failed(DownloadFailure.STORAGE_UNAVAILABLE, bytesWritten))
            } catch (error: Throwable) {
                abortDestination()
                Log.w(LOG_TAG, "Response-body download failed", error)
                val failure = if (cancelled.get()) {
                    DownloadFailure.CANCELLED
                } else {
                    DownloadFailure.TRANSFER_FAILED
                }
                finish(request.failed(failure, bytesWritten))
            }
        }

        fun cancel() {
            cancelled.set(true)
            runCatching { controlledBody.cancel() }
            source.close()
            if (!started.get()) {
                tasks.remove(request.id, this)
                finish(request.failed(DownloadFailure.CANCELLED))
            }
        }

        fun finish(result: DownloadResult) {
            if (!finished.compareAndSet(false, true)) {
                return
            }
            when {
                result.successful -> notifications.showComplete(notificationId, result)
                result.failure == DownloadFailure.CANCELLED -> notifications.cancel(notificationId)
                else -> notifications.showFailure(notificationId, result)
            }
            dispatchResult(result, notifyObservers, onTransferFinished)
        }

        private fun maybeUpdateNotification(bytesWritten: Long) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastNotificationAt < NOTIFICATION_INTERVAL_MS) {
                return
            }
            lastNotificationAt = now
            notifications.showProgress(notificationId, request, bytesWritten)
            dispatch {
                if (notifyObservers) {
                    synchronized(observers) {
                        observers.toList().forEach {
                            it.onTransferProgress(request, bytesWritten)
                        }
                    }
                }
                onTransferProgress?.invoke(request, bytesWritten)
            }
        }

        private fun abortDestination() {
            runCatching { destination?.abort() }
                .onFailure { Log.w(LOG_TAG, "Could not remove partial download", it) }
            destination = null
        }
    }

    private interface PendingDestination {
        fun writeFrom(input: InputStream, onProgress: (Long) -> Unit): Long

        fun commit(): String?

        fun abort()
    }

    private class ContentUriDestination(
        private val resolver: ContentResolver,
        private val uri: Uri,
    ) : PendingDestination {
        override fun writeFrom(input: InputStream, onProgress: (Long) -> Unit): Long {
            val rawOutput = try {
                resolver.openOutputStream(uri, "w")
                    ?: throw IOException("The selected document has no output stream")
            } catch (error: Throwable) {
                throw StorageUnavailableException(error)
            }
            return rawOutput.use { output ->
                BufferedOutputStream(output, DownloadStreamCopier.BUFFER_BYTES).use { buffered ->
                    DownloadStreamCopier.copy(input, buffered, onProgress)
                }
            }
        }

        override fun commit(): String = uri.toString()

        override fun abort() {
            resolver.delete(uri, null, null)
        }
    }

    private class MediaStoreDestination private constructor(
        private val resolver: ContentResolver,
        private val uri: Uri,
    ) : PendingDestination {
        private var committed = false

        override fun writeFrom(input: InputStream, onProgress: (Long) -> Unit): Long {
            val rawOutput = try {
                resolver.openOutputStream(uri, "w")
                    ?: throw IOException("MediaStore returned no output stream")
            } catch (error: Throwable) {
                throw StorageUnavailableException(error)
            }
            return rawOutput.use { output ->
                BufferedOutputStream(output, DownloadStreamCopier.BUFFER_BYTES).use { buffered ->
                    DownloadStreamCopier.copy(input, buffered, onProgress)
                }
            }
        }

        override fun commit(): String {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            val updated = try {
                resolver.update(uri, values, null, null)
            } catch (error: Throwable) {
                throw StorageUnavailableException(error)
            }
            if (updated <= 0) {
                throw StorageUnavailableException(IOException("Could not publish MediaStore item"))
            }
            committed = true
            return uri.toString()
        }

        override fun abort() {
            if (!committed) {
                resolver.delete(uri, null, null)
            }
        }

        companion object {
            fun create(
                resolver: ContentResolver,
                request: DownloadRequest,
                relativeDirectory: String?,
                requireExactFileName: Boolean,
            ): MediaStoreDestination {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, request.fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, request.contentType)
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        buildString {
                            append(Environment.DIRECTORY_DOWNLOADS)
                            relativeDirectory?.takeIf(String::isNotBlank)?.let {
                                append('/').append(it)
                            }
                        },
                    )
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val collection = MediaStore.Downloads.getContentUri(
                    MediaStore.VOLUME_EXTERNAL_PRIMARY,
                )
                val uri = try {
                    resolver.insert(collection, values)
                        ?: throw IOException("Could not create MediaStore download")
                } catch (error: Throwable) {
                    throw StorageUnavailableException(error)
                }
                if (requireExactFileName) {
                    try {
                        val name = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
                            if (it.moveToFirst()) it.getString(0) else null
                        }
                        if (name != request.fileName) throw IOException("Download filename conflict changed the overwrite target")
                    } catch (error: Throwable) {
                        runCatching { resolver.delete(uri, null, null) }.onFailure(error::addSuppressed)
                        throw StorageUnavailableException(error)
                    }
                }
                return MediaStoreDestination(resolver, uri)
            }
        }
    }

    private class LegacyDestination private constructor(
        private val directory: File,
        private val fileName: String,
        private val partialFile: File,
        private val releaseName: (File, String) -> Unit,
    ) : PendingDestination {
        private val finalFile = File(directory, fileName)
        private var released = false

        override fun writeFrom(input: InputStream, onProgress: (Long) -> Unit): Long {
            return try {
                var bytesWritten = 0L
                FileOutputStream(partialFile).use { fileOutput ->
                    BufferedOutputStream(fileOutput, DownloadStreamCopier.BUFFER_BYTES).use { buffered ->
                        bytesWritten = DownloadStreamCopier.copy(input, buffered, onProgress)
                        buffered.flush()
                        fileOutput.fd.sync()
                    }
                }
                bytesWritten
            } catch (error: Throwable) {
                if (error is DownloadCancelledException) {
                    throw error
                }
                throw IOException("Could not write public download", error)
            }
        }

        override fun commit(): String {
            try {
                LegacyDownloadPolicy.publish(partialFile, finalFile)
                if (!partialFile.delete()) Log.w(LOG_TAG, "Could not remove completed download staging file")
                return Uri.fromFile(finalFile).toString()
            } catch (error: Throwable) {
                throw StorageUnavailableException(error)
            } finally {
                releaseReservation()
            }
        }

        override fun abort() {
            if (partialFile.exists() && !partialFile.delete()) {
                throw IOException("Could not remove ${partialFile.name}")
            }
            releaseReservation()
        }

        private fun releaseReservation() {
            if (!released) {
                released = true
                releaseName(directory, fileName)
            }
        }

        companion object {
            fun create(
                request: DownloadRequest,
                relativeDirectory: String?,
                requireExactFileName: Boolean,
                reserveName: (File, String) -> String,
                releaseName: (File, String) -> Unit,
            ): LegacyDestination {
                val root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).canonicalFile
                val directory = relativeDirectory
                    ?.takeIf(String::isNotBlank)
                    ?.let { File(root, it) }
                    ?: root
                if (!directory.canonicalFile.toPath().startsWith(root.toPath()) ||
                    directory.canonicalFile.toPath() != directory.toPath().toAbsolutePath().normalize()) {
                    throw StorageUnavailableException(IOException("Download directory escapes public Downloads"))
                }
                if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
                    throw StorageUnavailableException(
                        IOException("Could not create public Downloads directory"),
                    )
                }
                val fileName = reserveName(directory, request.fileName)
                return try {
                    if (requireExactFileName && fileName != request.fileName) {
                        throw IOException("The exact overwrite download filename is already reserved")
                    }
                    val partialFile = File.createTempFile(".navis-", ".part", directory)
                    LegacyDestination(directory, fileName, partialFile, releaseName)
                } catch (error: Throwable) {
                    releaseName(directory, fileName)
                    throw StorageUnavailableException(error)
                }
            }
        }
    }

    private class DownloadNotifications(context: Context) {
        private val applicationContext = context.applicationContext
        // Numeric download IDs restart independently in each browser process.
        private val profileId = checkNotNull(
            (applicationContext as org.navis.browser.NavisApplication).profileScope,
        ).currentId
        private val notificationTag = "navis-download:$profileId"
        private val manager = applicationContext.getSystemService(NotificationManager::class.java)
        private val pending = linkedMapOf<Int, Notification>()
        private var pendingPermissionRequests = 0

        init {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                runCatching {
                    manager.createNotificationChannel(
                        NotificationChannel(
                            CHANNEL_ID,
                            applicationContext.getString(R.string.download_notification_channel),
                            NotificationManager.IMPORTANCE_LOW,
                        ),
                    )
                }.onFailure { Log.w(LOG_TAG, "Could not create download notification channel", it) }
            }
        }

        fun showProgress(id: Int, request: DownloadRequest, bytesWritten: Long) {
            val expected = request.expectedBytes
            val builder = builder()
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(request.fileName)
                .setContentText(applicationContext.getString(R.string.download_in_progress))
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(contentIntent(request, openFile = false))
            if (expected != null && expected > 0L) {
                val progress = ((bytesWritten.toDouble() / expected.toDouble()) * 100.0)
                    .toInt()
                    .coerceIn(0, 100)
                builder.setProgress(100, progress, false)
            } else {
                builder.setProgress(0, 0, true)
            }
            notify(id, builder.build())
        }

        fun showComplete(id: Int, result: DownloadResult) {
            notify(
                id,
                builder()
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle(result.request.fileName)
                    .setContentText(applicationContext.getString(R.string.download_complete))
                    .setCategory(Notification.CATEGORY_STATUS)
                    .setAutoCancel(true)
                    .setContentIntent(contentIntent(result.request, openFile = true))
                    .build(),
            )
        }

        fun showFailure(id: Int, result: DownloadResult) {
            notify(
                id,
                builder()
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentTitle(result.request.fileName)
                    .setContentText(applicationContext.getString(R.string.download_failed))
                    .setCategory(Notification.CATEGORY_ERROR)
                    .setAutoCancel(true)
                    .setContentIntent(contentIntent(result.request, openFile = false))
                    .build(),
            )
        }

        fun cancel(id: Int) {
            synchronized(pending) {
                pending.remove(id)
                runCatching { manager.cancel(notificationTag, id) }
            }
        }

        private fun contentIntent(request: DownloadRequest, openFile: Boolean) =
            DownloadNotificationIntents.pendingIntent(applicationContext,
                DownloadNotificationCommand(profileId, request.id.value, request.privateMode, openFile))

        fun preparePermissionRequest() {
            synchronized(pending) { pendingPermissionRequests++ }
        }

        fun onPermissionResult(granted: Boolean) {
            synchronized(pending) {
                pendingPermissionRequests = (pendingPermissionRequests - 1).coerceAtLeast(0)
                if (granted && AndroidNotificationPermissions.areAllowed(applicationContext)) {
                    pending.forEach { (id, notification) -> post(id, notification) }
                    pending.clear()
                } else if (pendingPermissionRequests == 0) {
                    pending.clear()
                }
            }
        }

        @Suppress("DEPRECATION")
        private fun builder(): Notification.Builder {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(applicationContext, CHANNEL_ID)
            } else {
                Notification.Builder(applicationContext)
            }
        }

        private fun notify(id: Int, notification: Notification) {
            synchronized(pending) {
                if (!AndroidNotificationPermissions.areAllowed(applicationContext)) {
                    if (pendingPermissionRequests > 0) {
                        pending[id] = notification
                        while (pending.size > 64) pending.remove(pending.keys.first())
                    }
                    return
                }
                pending.remove(id)
                post(id, notification)
            }
        }

        private fun post(id: Int, notification: Notification) {
            runCatching { manager.notify(notificationTag, id, notification) }
                .onFailure { Log.w(LOG_TAG, "Could not post download notification", it) }
        }
    }

    private fun DownloadRequest.failed(
        failure: DownloadFailure,
        bytesWritten: Long = 0L,
    ): DownloadResult = DownloadResult(
        request = this,
        destinationUri = null,
        bytesWritten = bytesWritten,
        failure = failure,
    )

    private class StorageUnavailableException(cause: Throwable) : IOException(cause)

    private class DownloadCancelledException : IOException("Download cancelled")

    private companion object {
        const val LOG_TAG = "NavisDownloads"
        const val CHANNEL_ID = "navis-downloads"
        const val MAX_CONCURRENT_DOWNLOADS = 2
        const val MAX_QUEUED_DOWNLOADS = 8
        const val THREAD_IDLE_SECONDS = 30L
        const val NOTIFICATION_INTERVAL_MS = 500L
        const val MAX_NAMING_URI_CHARS = 4_096
        const val MAX_CONTENT_DISPOSITION_CHARS = 4_096
        const val DOWNLOAD_ID_PREFERENCES = "navis-download-ids"
        const val NEXT_DOWNLOAD_ID_KEY = "next-id"
        const val MAX_DOWNLOAD_ID = 2_147_483_647L
        val nextWorkerId = AtomicInteger(0)
    }
}

internal data class AndroidDownloadWriteOptions(
    val destinationUri: String? = null,
    val relativeDirectory: String? = null,
    val fileName: String? = null,
    val replaceExistingUri: String? = null,
    val requireExactFileName: Boolean = false,
    val directoryUri: String? = null,
) {
    init {
        require(directoryUri == null || directoryUri.startsWith("content://")) { "Invalid download folder" }
        require(replaceExistingUri == null || (destinationUri == null &&
            replaceExistingUri.length <= 65536 &&
            (replaceExistingUri.startsWith("content://") || replaceExistingUri.startsWith("file://")))) {
            "Overwrite requires one exact content or app-owned file destination"
        }
        require(destinationUri == null || destinationUri.startsWith("content://")) {
            "A selected download destination must be a content URI"
        }
        require(relativeDirectory == null || safeDirectory(relativeDirectory)) {
            "Invalid relative download directory"
        }
        require(fileName == null || fileName.isNotBlank()) { "Invalid download filename" }
    }

    private companion object {
        fun safeDirectory(value: String): Boolean =
            value.length <= 3_840 &&
                !value.startsWith('/') &&
                value.split('/').all { component ->
                    component.isNotBlank() &&
                        component != "." &&
                        component != ".." &&
                        component.none { it == '\u0000' || it == '\\' || it.code < 0x20 }
                }
    }
}

internal interface AndroidDownloadStoreObserver {
    fun onTransferRequested(request: DownloadRequest)

    fun onTransferRetryMetadata(request: DownloadRequest, metadata: EngineDownloadRetryMetadata?) {}

    fun onTransferControl(request: DownloadRequest, control: PausableDownloadInputStream) {}

    fun onTransferProgress(request: DownloadRequest, bytesWritten: Long)

    fun onTransferFinished(result: DownloadResult)
}
