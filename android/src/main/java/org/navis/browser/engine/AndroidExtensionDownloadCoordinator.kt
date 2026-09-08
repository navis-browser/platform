/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.engine

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import org.json.JSONArray
import org.json.JSONObject
import org.navis.browser.api.DownloadFailure
import org.navis.browser.api.DownloadId
import org.navis.browser.api.DownloadRequest
import org.navis.browser.api.DownloadResult
import org.navis.browser.api.SessionId
import org.navis.browser.downloads.DownloadEntry
import org.navis.browser.downloads.DownloadManager
import org.navis.browser.downloads.DownloadState
import org.navis.browser.engine.extensions.EngineExtensionDownloadConflictAction
import org.navis.browser.engine.extensions.EngineExtensionDownloadDelegate
import org.navis.browser.engine.extensions.EngineExtensionDownloadDestination
import org.navis.browser.engine.extensions.EngineExtensionDownloadException
import org.navis.browser.engine.extensions.EngineExtensionDownloadInterruptReason
import org.navis.browser.engine.extensions.EngineExtensionDownloadObserver
import org.navis.browser.engine.extensions.EngineExtensionDownloadPort
import org.navis.browser.engine.extensions.EngineExtensionDownloadRequest
import org.navis.browser.engine.extensions.EngineExtensionDownloadResponse
import org.navis.browser.engine.extensions.EngineExtensionDownloadSnapshot
import org.navis.browser.engine.extensions.EngineExtensionDownloadState
import org.navis.browser.engine.extensions.PausableDownloadInputStream
import org.navis.browser.engine.runtime.EngineDownloadResponse
import org.navis.browser.engine.runtime.EngineDownloadRetryMetadata

/** One product-owned download history shared by Android UI and the WebExtensions API. */
internal class AndroidExtensionDownloadCoordinator(
    context: Context,
    private val port: EngineExtensionDownloadPort,
    private val store: AndroidResponseDownloadStore,
    private val sessionForMode: (privateMode: Boolean) -> SessionId?,
    private val retryDownload: ((EngineDownloadRetryMetadata) -> CompletionStage<Boolean>)? = null,
    private val windowIdForSession: (SessionId) -> Long? = { null },
    private val activeWindowId: () -> Long? = { null },
    private val isSessionLiveAndMode: (SessionId, Boolean) -> Boolean = { id, mode -> sessionForMode(mode) == id },
    private val askBeforeSaving: () -> Boolean = { false },
    private val defaultDirectoryUri: () -> String = { "" },
    private val deletePrivateOnExit: () -> Boolean = { false },
    private val openWhenComplete: () -> Boolean = { false },
    private val onActionFailure: () -> Unit = {},
) : EngineExtensionDownloadDelegate,
    AndroidDownloadStoreObserver,
    NavisDownloadHistoryClearer,
    AutoCloseable {
    private val applicationContext = context.applicationContext
    private val preferences = applicationContext.getSharedPreferences(
        HISTORY_PREFERENCES,
        Context.MODE_PRIVATE,
    )
    private val lock = Any()
    private val records = linkedMapOf<Long, EngineExtensionDownloadSnapshot>()
    private val controls = mutableMapOf<Long, PausableDownloadInputStream>()
    private val displayNames = mutableMapOf<Long, String>()
    // A retry context is deliberately session-only: session IDs cannot survive process restarts.
    private val retryMetadata = mutableMapOf<Long, EngineDownloadRetryMetadata>()
    private val retrying = mutableSetOf<Long>()
    private val privateDownloadsToDelete = mutableSetOf<Long>()
    private val productObservers = linkedSetOf<() -> Unit>()
    private val pendingSnapshotQueries =
        mutableSetOf<CompletableFuture<List<EngineExtensionDownloadSnapshot>>>()
    private val pendingFileQueries = mutableSetOf<CompletableFuture<*>>()
    private val historyExecutor = DownloadHistoryQueue()
    internal val closedCompletion: CompletableFuture<Unit>
        get() = historyExecutor.closedCompletion
    private var observer: EngineExtensionDownloadObserver? = null
    private var closed = false
    val downloadManager: DownloadManager = AndroidDownloadsRepository(this)
    internal val hasPendingTransfers: Boolean
        get() = synchronized(lock) {
            records.values.any { it.state == EngineExtensionDownloadState.IN_PROGRESS } ||
                controls.isNotEmpty() || retrying.isNotEmpty() || pendingFileQueries.isNotEmpty() ||
                privateDownloadsToDelete.isNotEmpty()
        }

    init {
        restoreHistory()
        records.keys.maxOrNull()?.let(store::advanceNextIdPast)
        store.addObserver(this)
        try {
            port.bind(this)
        } catch (error: Throwable) {
            store.removeObserver(this)
            historyExecutor.shutdownNow()
            throw error
        }
    }

    override fun bind(observer: EngineExtensionDownloadObserver) {
        synchronized(lock) {
            check(!closed) { "Extension download coordinator is closed" }
            check(this.observer == null || this.observer === observer) {
                "Extension download event sink is already bound"
            }
            this.observer = observer
        }
    }

    override fun unbind(observer: EngineExtensionDownloadObserver) {
        synchronized(lock) {
            if (this.observer === observer) {
                this.observer = null
            }
        }
    }

    override fun prepare(
        request: EngineExtensionDownloadRequest,
    ): CompletionStage<EngineExtensionDownloadDestination> {
        val sessionId = synchronized(lock) {
            ensureOpen()
            sessionForMode(request.privateMode)
        } ?: return failed("No matching Navis tab is available for this download")

        if (request.conflictAction == EngineExtensionDownloadConflictAction.OVERWRITE) {
            return prepareOverwrite(request, sessionId)
        }
        val requiresPicker = request.saveAs || askBeforeSaving() ||
            request.conflictAction == EngineExtensionDownloadConflictAction.PROMPT
        if (!requiresPicker) {
            return completed(EngineExtensionDownloadDestination(sessionId, null))
        }
        return AndroidExtensionDownloadSaveAs.request(
            suggestedFileName(request),
            DownloadMetadata.DEFAULT_MIME_TYPE,
            windowId = windowIdForSession(sessionId) ?: return failed("The download's originating window is no longer available"),
        ).thenApply { uri -> EngineExtensionDownloadDestination(sessionId, uri) }
    }

    private fun prepareOverwrite(
        request: EngineExtensionDownloadRequest,
        sessionId: SessionId,
    ): CompletionStage<EngineExtensionDownloadDestination> {
        if (askBeforeSaving() || defaultDirectoryUri().isNotEmpty()) return AndroidExtensionDownloadSaveAs.onUi {
            AndroidExtensionDownloadSaveAs.request(suggestedFileName(request), DownloadMetadata.DEFAULT_MIME_TYPE,
                replaceExisting = true, windowId = windowIdForSession(sessionId)
                    ?: throw EngineExtensionDownloadException("The download's originating window is no longer available"))
                .thenApply { uri -> EngineExtensionDownloadDestination(sessionId, null, uri) }
        }
        return fileStage {
        val name = DownloadMetadata.sanitizeFileName(suggestedFileName(request))
        val directory = request.suggestedRelativePath?.substringBeforeLast('/', "")?.takeIf(String::isNotEmpty)
        val candidates = synchronized(lock) {
            ensureOpen()
            records.values.filter {
                it.privateMode == request.privateMode && it.state == EngineExtensionDownloadState.COMPLETE
            }.toList()
        }
        val target = if (request.saveAs) null else candidates.asReversed().firstOrNull { item ->
            runCatching {
                val uri = Uri.parse(item.filename)
                AndroidDownloadFileAccess.isDefaultTarget(applicationContext, uri, name, directory) &&
                    run { AndroidDownloadFileAccess.requireWritableTarget(applicationContext, uri); true }
            }.getOrDefault(false)
        }
        when {
            target != null -> EngineExtensionDownloadDestination(sessionId, null, target.filename)
            !request.saveAs && !AndroidDownloadFileAccess.defaultTargetExists(applicationContext, name, directory) ->
                EngineExtensionDownloadDestination(sessionId, null, requireExactFileName = true)
            else -> null
        }
    }.thenCompose { prepared ->
        if (prepared != null) completed(prepared) else AndroidExtensionDownloadSaveAs.onUi {
            synchronized(lock) { ensureOpen() }
            // Android CREATE_DOCUMENT deliberately uniquifies; replacement requires a real existing-file grant.
            AndroidExtensionDownloadSaveAs.request(
                suggestedFileName(request), DownloadMetadata.DEFAULT_MIME_TYPE, replaceExisting = true,
                windowId = windowIdForSession(sessionId)
                    ?: throw EngineExtensionDownloadException("The download's originating window is no longer available"),
            ).thenApply { uri -> EngineExtensionDownloadDestination(sessionId, null, uri) }
        }
    }
    }

    override fun start(
        request: EngineExtensionDownloadRequest,
        destination: EngineExtensionDownloadDestination,
        response: EngineExtensionDownloadResponse,
    ): CompletionStage<EngineExtensionDownloadSnapshot> {
        val body = try {
            synchronized(lock) {
                ensureOpen()
                if (!isSessionLiveAndMode(destination.sessionId, request.privateMode)) {
                    throw EngineExtensionDownloadException("The download's original Navis tab is no longer available")
                }
            }
            response.takeBody()
                ?: throw EngineExtensionDownloadException("The download response has no body")
        } catch (error: Throwable) {
            response.close()
            return failed(error)
        }

        val controlledBody = PausableDownloadInputStream(body)
        val source = EngineDownloadResponse(
            sessionId = destination.sessionId,
            uri = request.sourceUri,
            suggestedFileName = suggestedFileName(request),
            contentType = response.contentType,
            contentDisposition = response.contentDisposition,
            contentLength = response.contentLength,
            privateMode = request.privateMode,
            body = controlledBody,
        )
        val relativePath = request.suggestedRelativePath
        val writeOptions = try {
            AndroidDownloadWriteOptions(
                destinationUri = destination.destinationUri,
                relativeDirectory = relativePath?.substringBeforeLast('/', "")
                    ?.takeUnless(String::isEmpty),
                fileName = relativePath?.substringAfterLast('/'),
                replaceExistingUri = destination.replaceExistingUri,
                requireExactFileName = destination.requireExactFileName,
            )
        } catch (error: Throwable) {
            source.close()
            return failed(error)
        }

        val deferredProgress = mutableMapOf<Long, Long>()
        val deferredResults = mutableMapOf<Long, DownloadResult>()
        val stored = try {
            store.enqueueTracked(
                source = source,
                options = writeOptions,
                notifyObservers = false,
                onTransferProgress = { download, bytes ->
                    if (!updateProgress(download.id.value, bytes)) {
                        synchronized(deferredProgress) { deferredProgress[download.id.value] = bytes }
                    }
                },
                onTransferFinished = { result ->
                    if (!finishTransfer(result)) {
                        synchronized(deferredResults) { deferredResults[result.request.id.value] = result }
                    }
                },
            )
        } catch (error: Throwable) {
            source.close()
            return failed(error)
        }

        val created = initialSnapshot(stored, request)
        val accepted = synchronized(lock) {
            if (closed) {
                false
            } else {
                records[created.id] = created
                displayNames[created.id] = stored.fileName
                controls[created.id] = controlledBody
                persistHistoryLocked()
                true
            }
        }
        if (!accepted) {
            controlledBody.cancel()
            store.cancel(stored.id)
            return failed("Extension download coordinator is closed")
        }
        publishCreated(created)
        synchronized(deferredProgress) { deferredProgress.remove(created.id) }
            ?.let { updateProgress(created.id, it) }
        synchronized(deferredResults) { deferredResults.remove(created.id) }
            ?.let(::finishTransfer)
        return completed(snapshot(created.id, request.privateBrowsingAllowed))
    }

    override fun snapshots(
        privateBrowsingAllowed: Boolean,
    ): CompletionStage<List<EngineExtensionDownloadSnapshot>> {
        val values = try {
            synchronized(lock) {
                ensureOpen()
                records.values
                    .filter { privateBrowsingAllowed || !it.privateMode }
                    .toList()
            }
        } catch (error: Throwable) {
            return failed(error)
        }
        val result = CompletableFuture<List<EngineExtensionDownloadSnapshot>>()
        synchronized(lock) {
            if (closed) return failed("Extension download coordinator is closed")
            pendingSnapshotQueries += result
        }
        result.whenComplete { _, _ ->
            synchronized(lock) { pendingSnapshotQueries -= result }
        }
        try {
            historyExecutor.execute {
                try {
                    val probes = values.map { original -> original to probeExists(original) }
                    val refreshed = synchronized(lock) {
                        ensureOpen()
                        var changed = false
                        val currentValues = mutableListOf<EngineExtensionDownloadSnapshot>()
                        probes.forEach { (original, probed) ->
                            val current = records[original.id] ?: return@forEach
                            val visible = if (current == original) {
                                if (probed != original) {
                                    records[original.id] = probed
                                    changed = true
                                }
                                probed
                            } else {
                                // Progress, completion, or erasure may race the file-system probe.
                                // Never replace a newer record with the stale snapshot that was probed.
                                current
                            }
                            if (privateBrowsingAllowed || !visible.privateMode) {
                                currentValues += visible
                            }
                        }
                        if (changed) persistHistoryLocked()
                        currentValues
                    }
                    result.complete(refreshed)
                } catch (error: Throwable) {
                    result.completeExceptionally(error)
                }
            }
        } catch (error: Throwable) {
            result.completeExceptionally(error)
        }
        return result
    }

    override fun pause(id: Long, privateBrowsingAllowed: Boolean): CompletionStage<Unit> =
        changeTransfer(id, privateBrowsingAllowed, pause = true)

    override fun resume(id: Long, privateBrowsingAllowed: Boolean): CompletionStage<Unit> =
        changeTransfer(id, privateBrowsingAllowed, pause = false)

    override fun cancel(id: Long, privateBrowsingAllowed: Boolean): CompletionStage<Unit> =
        tryStage {
            val control = synchronized(lock) {
                val item = accessibleRecord(id, privateBrowsingAllowed)
                if (item.state != EngineExtensionDownloadState.IN_PROGRESS) {
                    return@tryStage Unit
                }
                controls[id]
            }
            control?.cancel()
            // If the task has just finished, its queued completion owns the final state.
            store.cancel(DownloadId(id))
            Unit
        }

    override fun erase(
        ids: LongArray,
        privateBrowsingAllowed: Boolean,
    ): CompletionStage<LongArray> = tryStage {
        val erased = mutableListOf<Pair<Long, Boolean>>()
        val cancelled = mutableListOf<Pair<Long, PausableDownloadInputStream>>()
        synchronized(lock) {
            ensureOpen()
            ids.distinct().forEach { id ->
                val item = records[id] ?: return@forEach
                if (item.privateMode && !privateBrowsingAllowed) {
                    return@forEach
                }
                records.remove(id)
                displayNames.remove(id)
                retryMetadata.remove(id)
                retrying.remove(id)
                controls.remove(id)?.let { cancelled += id to it }
                erased += id to item.privateMode
            }
            persistHistoryLocked()
        }
        cancelled.forEach { (_, control) ->
            control.cancel()
        }
        erased.forEach { (id, privateMode) ->
            store.cancel(DownloadId(id))
            publishErased(id, privateMode)
        }
        erased.map { it.first }.toLongArray()
    }

    override fun open(id: Long, privateBrowsingAllowed: Boolean): CompletionStage<Unit> =
        tryStage {
            val original = synchronized(lock) { accessibleRecord(id, privateBrowsingAllowed) }
            val item = probeExists(original)
            synchronized(lock) {
                if (records[id] === original && item != original) {
                    records[id] = item
                    persistHistoryLocked()
                }
            }
            if (item.state != EngineExtensionDownloadState.COMPLETE || !item.exists) {
                throw EngineExtensionDownloadException("Download $id is not available")
            }
            val sourceUri = Uri.parse(item.filename)
            val openUri = if (sourceUri.scheme == "file") {
                val path = sourceUri.path
                    ?: throw EngineExtensionDownloadException("Download $id has no local file")
                FileProvider.getUriForFile(
                    applicationContext,
                    "${applicationContext.packageName}.download-fileprovider",
                    File(path),
                )
            } else {
                sourceUri
            }
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(openUri, item.mimeType)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                applicationContext.startActivity(intent)
            } catch (error: Throwable) {
                throw EngineExtensionDownloadException("No Android application can open this download")
            }
            Unit
        }

    override fun show(
        id: Long,
        privateBrowsingAllowed: Boolean,
    ): CompletionStage<Boolean> = AndroidExtensionDownloadSaveAs.onUi {
        val windowId = activeWindowId()
            ?: throw EngineExtensionDownloadException("A foreground Navis window is required")
        fileStage {
        val item = synchronized(lock) { accessibleRecord(id, privateBrowsingAllowed) }
        if (item.state != EngineExtensionDownloadState.COMPLETE || !probeExists(item).exists) {
            throw EngineExtensionDownloadException("Download $id is not available")
        }
        AndroidDownloadFileAccess.containingFolderIntent(applicationContext, Uri.parse(item.filename))
    }.thenCompose { intent -> AndroidExtensionDownloadSaveAs.onUi {
        synchronized(lock) { accessibleRecord(id, privateBrowsingAllowed) }
        AndroidExtensionDownloadSaveAs.launch(intent, windowId).thenApply { true }
    } } }

    override fun showDefaultFolder(): CompletionStage<Unit> = AndroidExtensionDownloadSaveAs.onUi {
        synchronized(lock) { ensureOpen() }
        AndroidExtensionDownloadSaveAs.launch(AndroidDownloadFileAccess.defaultFolderIntent(),
            activeWindowId() ?: throw EngineExtensionDownloadException("A foreground Navis window is required"))
    }

    override fun removeFile(id: Long, privateBrowsingAllowed: Boolean): CompletionStage<Unit> = fileStage {
        val original = synchronized(lock) { accessibleRecord(id, privateBrowsingAllowed) }
        if (original.state != EngineExtensionDownloadState.COMPLETE || !probeExists(original).exists) {
            throw EngineExtensionDownloadException("Download $id is not a completed, existing file")
        }
        AndroidDownloadFileAccess.remove(applicationContext, Uri.parse(original.filename))
        val changed = synchronized(lock) {
            val current = records[id]
            if (!closed && current != null && current.filename == original.filename) {
                val updated = current.copy(exists = false)
                records[id] = updated
                persistHistoryLocked()
                current to updated
            } else null
        }
        changed?.let { (previous, current) -> publishChanged(previous, current) }
        Unit
    }

    override fun getFileIcon(id: Long, size: Int, privateBrowsingAllowed: Boolean): CompletionStage<String> = fileStage {
        val item = synchronized(lock) { accessibleRecord(id, privateBrowsingAllowed) }
        AndroidDownloadFileAccess.icon(applicationContext, item.mimeType, size)
    }

    override fun onTransferRequested(request: DownloadRequest) {
        val created = initialSnapshot(request, extension = null as EngineExtensionDownloadRequest?)
        synchronized(lock) {
            if (closed || records.containsKey(created.id)) {
                return
            }
            records[created.id] = created
            displayNames[created.id] = request.fileName
            persistHistoryLocked()
        }
        publishCreated(created)
    }

    override fun onTransferRetryMetadata(
        request: DownloadRequest,
        metadata: EngineDownloadRetryMetadata?,
    ) {
        if (metadata == null || metadata.sessionId != request.sessionId ||
            metadata.privateMode != request.privateMode
        ) return
        synchronized(lock) {
            if (!closed && records.containsKey(request.id.value)) {
                retryMetadata[request.id.value] = metadata
            }
        }
    }

    override fun onTransferProgress(request: DownloadRequest, bytesWritten: Long) {
        updateProgress(request.id.value, bytesWritten)
    }

    override fun onTransferControl(request: DownloadRequest, control: PausableDownloadInputStream) {
        val accepted = synchronized(lock) {
            val item = records[request.id.value]
            if (closed || item?.state != EngineExtensionDownloadState.IN_PROGRESS) {
                false
            } else {
                controls[item.id] = control
                true
            }
        }
        // A queued callback can arrive after its private Session/history has been removed.
        if (accepted) publishProductChanged() else control.cancel()
    }

    override fun onTransferFinished(result: DownloadResult) {
        finishTransfer(result)
    }

    /** Called by the product Runtime when its final private Session closes. */
    fun clearPrivateHistory() {
        val removed = mutableListOf<Pair<Long, PausableDownloadInputStream?>>()
        val files = mutableListOf<String>()
        synchronized(lock) {
            if (closed) return
            records.values.filter(EngineExtensionDownloadSnapshot::privateMode).forEach { item ->
                if (deletePrivateOnExit() && item.state == EngineExtensionDownloadState.COMPLETE && item.exists) files += item.filename
                if (deletePrivateOnExit() && item.state == EngineExtensionDownloadState.IN_PROGRESS) privateDownloadsToDelete += item.id
                records.remove(item.id)
                displayNames.remove(item.id)
                retryMetadata.remove(item.id)
                retrying.remove(item.id)
                removed += item.id to controls.remove(item.id)
            }
            // Admit cleanup before close() can set closed and seal the queue.
            // Otherwise a concurrent close could drain before these files were enqueued.
            files.forEach(::deletePrivateFile)
        }
        removed.forEach { (id, control) ->
            control?.cancel()
            store.cancel(DownloadId(id))
            publishErased(id, true)
        }
    }

    private fun deletePrivateFile(uri: String) {
        runCatching {
            historyExecutor.execute {
                runCatching { AndroidDownloadFileAccess.remove(applicationContext, Uri.parse(uri)) }
                    .onFailure { onActionFailure() }
            }
        }.onFailure { onActionFailure() }
    }

    /** Removes finished normal download history without deleting downloaded files. */
    override fun clearSince(sinceUnixMillis: Long): CompletionStage<Boolean> = tryStage {
        require(sinceUnixMillis >= 0L) { "Invalid download-history timestamp" }
        val removed = mutableListOf<Long>()
        synchronized(lock) {
            ensureOpen()
            records.values
                .filter { item ->
                    !item.privateMode &&
                        item.state != EngineExtensionDownloadState.IN_PROGRESS &&
                        downloadStartMillis(item.startTime) >= sinceUnixMillis
                }
                .forEach { item ->
                    records.remove(item.id)
                    displayNames.remove(item.id)
                    retryMetadata.remove(item.id)
                    retrying.remove(item.id)
                    controls.remove(item.id)
                    removed += item.id
                }
            if (removed.isNotEmpty()) {
                persistHistoryLocked()
            }
        }
        removed.forEach { id -> publishErased(id, false) }
        true
    }

    fun productEntries(): List<DownloadEntry> = synchronized(lock) {
        if (closed) return@synchronized emptyList()
        records.values.map { item ->
            DownloadEntry(
                id = item.id,
                fileName = displayNames[item.id]
                    ?: item.filename.substringAfterLast('/').ifBlank { "download" },
                sourceUri = item.sourceUri,
                privateMode = item.privateMode,
                state = when {
                    item.state == EngineExtensionDownloadState.COMPLETE -> DownloadState.COMPLETE
                    item.state == EngineExtensionDownloadState.IN_PROGRESS -> {
                        if (item.paused) DownloadState.PAUSED else DownloadState.DOWNLOADING
                    }
                    item.error == EngineExtensionDownloadInterruptReason.USER_CANCELED ->
                        DownloadState.CANCELLED
                    else -> DownloadState.FAILED
                },
                bytesReceived = item.bytesReceived,
                totalBytes = item.totalBytes,
                exists = item.exists,
                canRetry = item.state == EngineExtensionDownloadState.INTERRUPTED &&
                    item.id !in retrying &&
                    ((item.id in retryMetadata && retryDownload != null) || port.canRetry(item.id)),
                canPause = item.id in controls,
                startedAt = item.startTime,
            )
        }.sortedByDescending(DownloadEntry::id)
    }

    fun addProductObserver(observer: () -> Unit) {
        synchronized(lock) { if (!closed) productObservers += observer }
    }

    fun removeProductObserver(observer: () -> Unit) {
        synchronized(lock) { productObservers -= observer }
    }

    fun requireProductRecord(id: Long, privateMode: Boolean) {
        synchronized(lock) {
            val item = accessibleRecord(id, privateMode)
            check(item.privateMode == privateMode) { "Download is in a different browsing mode" }
        }
    }

    fun retryProductDownload(id: Long, privateMode: Boolean): CompletionStage<Unit> {
        val metadata: EngineDownloadRetryMetadata?
        try {
            synchronized(lock) {
                requireProductRecord(id, privateMode)
                val item = accessibleRecord(id, privateMode)
                check(item.state == EngineExtensionDownloadState.INTERRUPTED) {
                    "Only a stopped download can be retried"
                }
                check(id !in retrying) { "Download retry is already in progress" }
                metadata = retryMetadata[id]
                check((metadata != null && retryDownload != null) || port.canRetry(id)) {
                    "Original request cannot safely be replayed"
                }
                retrying += id
            }
        } catch (error: Throwable) {
            return failed(error)
        }
        publishProductChanged()
        val result = CompletableFuture<Unit>()
        val operation = try {
            if (metadata != null) checkNotNull(retryDownload).invoke(metadata) else port.retry(id)
        } catch (error: Throwable) {
            failed<Boolean>(error)
        }
        operation.whenComplete { accepted, error ->
            synchronized(lock) { retrying -= id }
            if (error != null || accepted != true) {
                result.completeExceptionally(
                    error ?: EngineExtensionDownloadException("Download retry was not accepted"),
                )
            } else {
                // The new transfer owns a new record. Keep the old record if the engine rejects it.
                erase(longArrayOf(id), privateMode).whenComplete { _, eraseError ->
                    if (eraseError != null) result.completeExceptionally(eraseError)
                    else result.complete(Unit)
                }
            }
            publishProductChanged()
        }
        return result
    }

    private fun publishProductChanged() {
        synchronized(lock) { productObservers.takeUnless { closed }?.toList().orEmpty() }
            .forEach { it() }
    }

    override fun close() {
        clearPrivateHistory()
        val active: List<PausableDownloadInputStream>
        val pendingQueries: List<CompletableFuture<List<EngineExtensionDownloadSnapshot>>>
        val pendingFiles: List<CompletableFuture<*>>
        synchronized(lock) {
            if (closed) return
            closed = true
            val endedAt = isoTime(System.currentTimeMillis())
            records.keys.toList().forEach { id ->
                val item = checkNotNull(records[id])
                records[id] = if (
                    item.state == EngineExtensionDownloadState.IN_PROGRESS && !item.privateMode
                ) {
                    item.copy(
                        endTime = endedAt,
                        state = EngineExtensionDownloadState.INTERRUPTED,
                        paused = false,
                        canResume = false,
                        error = EngineExtensionDownloadInterruptReason.USER_SHUTDOWN,
                    )
                } else {
                    item
                }
            }
            records.entries.removeAll { it.value.privateMode }
            persistHistoryLocked()
            active = controls.values.toList()
            controls.clear()
            retryMetadata.clear()
            retrying.clear()
            productObservers.clear()
            pendingQueries = pendingSnapshotQueries.toList()
            pendingSnapshotQueries.clear()
            pendingFiles = pendingFileQueries.toList()
            pendingFileQueries.clear()
        }
        try {
            port.unbind(this)
            store.removeObserver(this)
            active.forEach(PausableDownloadInputStream::cancel)
            (pendingQueries + pendingFiles).forEach {
                it.completeExceptionally(
                    EngineExtensionDownloadException("Extension download coordinator is closed"),
                )
            }
        } finally {
            historyExecutor.close()
        }
    }

    private fun <T> fileStage(operation: () -> T): CompletionStage<T> {
        val result = CompletableFuture<T>()
        synchronized(lock) {
            if (closed) return failed("Extension download coordinator is closed")
            pendingFileQueries += result
        }
        result.whenComplete { _, _ -> synchronized(lock) { pendingFileQueries -= result } }
        try {
            historyExecutor.execute {
                try {
                    synchronized(lock) { ensureOpen() }
                    result.complete(operation())
                } catch (error: Throwable) {
                    result.completeExceptionally(error)
                }
            }
        } catch (error: Throwable) { result.completeExceptionally(error) }
        return result
    }

    private fun changeTransfer(
        id: Long,
        privateBrowsingAllowed: Boolean,
        pause: Boolean,
    ): CompletionStage<Unit> = tryStage {
        val changed: Pair<EngineExtensionDownloadSnapshot, EngineExtensionDownloadSnapshot>
        synchronized(lock) {
            val previous = accessibleRecord(id, privateBrowsingAllowed)
            if (previous.state != EngineExtensionDownloadState.IN_PROGRESS) {
                throw EngineExtensionDownloadException("Download $id is not active")
            }
            val control = controls[id]
                ?: throw EngineExtensionDownloadException("Download $id is not active")
            val accepted = if (pause) control.pause() else control.resumeTransfer()
            if (!accepted) {
                throw EngineExtensionDownloadException(
                    if (pause) "Download $id cannot be paused" else "Download $id cannot be resumed",
                )
            }
            val current = previous.copy(paused = pause, canResume = pause)
            records[id] = current
            persistHistoryLocked()
            changed = previous to current
        }
        publishChanged(changed.first, changed.second)
        Unit
    }

    private fun updateProgress(id: Long, bytesWritten: Long): Boolean {
        synchronized(lock) {
            val current = records[id] ?: return false
            if (current.state != EngineExtensionDownloadState.IN_PROGRESS) return true
            records[id] = current.copy(bytesReceived = bytesWritten.coerceAtLeast(0L))
        }
        publishProductChanged()
        return true
    }

    private fun finishTransfer(result: DownloadResult): Boolean {
        val changed = synchronized(lock) {
            val previous = records[result.request.id.value] ?: run {
                if (privateDownloadsToDelete.remove(result.request.id.value) && result.successful) {
                    result.destinationUri?.let(::deletePrivateFile)
                }
                return false
            }
            controls.remove(previous.id)
            val successful = result.successful
            val current = previous.copy(
                filename = result.destinationUri ?: previous.filename,
                endTime = isoTime(System.currentTimeMillis()),
                state = if (successful) {
                    EngineExtensionDownloadState.COMPLETE
                } else {
                    EngineExtensionDownloadState.INTERRUPTED
                },
                paused = false,
                canResume = false,
                error = if (successful) null else interruptReason(result.failure),
                bytesReceived = result.bytesWritten.coerceAtLeast(0L),
                fileSize = if (successful) result.bytesWritten.coerceAtLeast(0L) else -1L,
                exists = successful && result.destinationUri != null,
            )
            records[previous.id] = current
            persistHistoryLocked()
            previous to current
        }
        publishChanged(changed.first, changed.second)
        if (result.successful && openWhenComplete() &&
            isSessionLiveAndMode(result.request.sessionId, result.request.privateMode)) {
            open(result.request.id.value, result.request.privateMode).whenComplete { _, error ->
                if (error != null) onActionFailure()
            }
        }
        return true
    }

    private fun initialSnapshot(
        request: DownloadRequest,
        extension: EngineExtensionDownloadRequest?,
    ): EngineExtensionDownloadSnapshot = EngineExtensionDownloadSnapshot(
        id = request.id.value,
        sourceUri = request.sourceUri,
        referrer = extension?.headers
            ?.firstOrNull { it.name.equals("referer", ignoreCase = true) }
            ?.value.orEmpty(),
        filename = request.fileName,
        privateMode = request.privateMode,
        mimeType = request.contentType,
        startTime = isoTime(System.currentTimeMillis()),
        endTime = null,
        state = EngineExtensionDownloadState.IN_PROGRESS,
        paused = false,
        canResume = false,
        error = null,
        bytesReceived = 0L,
        totalBytes = request.expectedBytes ?: -1L,
        fileSize = -1L,
        exists = false,
        byExtensionId = extension?.extensionId,
        byExtensionName = extension?.extensionName,
    )

    private fun suggestedFileName(request: EngineExtensionDownloadRequest): String {
        val explicit = request.suggestedRelativePath?.substringAfterLast('/')
        val fromUri = runCatching { Uri.parse(request.sourceUri).lastPathSegment }
            .getOrNull()
            ?.substringAfterLast('/')
        return DownloadMetadata.sanitizeFileName(explicit ?: fromUri)
    }

    private fun snapshot(id: Long, privateBrowsingAllowed: Boolean): EngineExtensionDownloadSnapshot =
        synchronized(lock) { accessibleRecord(id, privateBrowsingAllowed) }

    private fun accessibleRecord(
        id: Long,
        privateBrowsingAllowed: Boolean,
    ): EngineExtensionDownloadSnapshot {
        ensureOpen()
        val item = records[id] ?: throw EngineExtensionDownloadException("Invalid download id $id")
        if (item.privateMode && !privateBrowsingAllowed) {
            throw EngineExtensionDownloadException("Invalid download id $id")
        }
        return item
    }

    private fun probeExists(
        item: EngineExtensionDownloadSnapshot,
    ): EngineExtensionDownloadSnapshot {
        if (item.state != EngineExtensionDownloadState.COMPLETE) return item
        val uri = runCatching { Uri.parse(item.filename) }.getOrNull() ?: return item.copy(exists = false)
        val exists = when (uri.scheme) {
            "content" -> runCatching {
                applicationContext.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true }
                    ?: false
            }.getOrDefault(false)
            "file" -> uri.path?.let(::File)?.isFile == true
            else -> false
        }
        if (exists == item.exists) return item
        return item.copy(exists = exists)
    }

    private fun interruptReason(failure: DownloadFailure?): EngineExtensionDownloadInterruptReason =
        when (failure) {
            DownloadFailure.STORAGE_UNAVAILABLE -> EngineExtensionDownloadInterruptReason.FILE_FAILED
            DownloadFailure.TOO_MANY_PENDING ->
                EngineExtensionDownloadInterruptReason.FILE_TRANSIENT_ERROR
            DownloadFailure.CANCELLED -> EngineExtensionDownloadInterruptReason.USER_CANCELED
            DownloadFailure.RESPONSE_BODY_UNAVAILABLE,
            DownloadFailure.TRANSFER_FAILED,
            null,
            -> EngineExtensionDownloadInterruptReason.NETWORK_FAILED
        }

    private fun publishCreated(item: EngineExtensionDownloadSnapshot) {
        synchronized(lock) { observer.takeUnless { closed } }?.onCreated(item)
        publishProductChanged()
    }

    private fun publishChanged(
        previous: EngineExtensionDownloadSnapshot,
        current: EngineExtensionDownloadSnapshot,
    ) {
        synchronized(lock) { observer.takeUnless { closed } }?.onChanged(previous, current)
        publishProductChanged()
    }

    private fun publishErased(id: Long, privateMode: Boolean) {
        synchronized(lock) { observer.takeUnless { closed } }?.onErased(id, privateMode)
        publishProductChanged()
    }

    private fun restoreHistory() {
        val serialized = preferences.getString(HISTORY_KEY, null) ?: return
        runCatching {
            val values = JSONArray(serialized)
            val stoppedAt = isoTime(System.currentTimeMillis())
            for (index in 0 until minOf(values.length(), MAX_HISTORY_ITEMS)) {
                val restored = snapshotFromJson(values.getJSONObject(index)) ?: continue
                val item = if (restored.state == EngineExtensionDownloadState.IN_PROGRESS) {
                    restored.copy(
                        endTime = stoppedAt,
                        state = EngineExtensionDownloadState.INTERRUPTED,
                        paused = false,
                        canResume = false,
                        error = EngineExtensionDownloadInterruptReason.USER_SHUTDOWN,
                    )
                } else {
                    restored
                }
                if (!item.privateMode) {
                    records[item.id] = item
                    values.getJSONObject(index).optionalString("displayName")
                        ?.let { displayNames[item.id] = it }
                }
            }
            persistHistoryLocked()
        }.onFailure {
            records.clear()
            displayNames.clear()
            preferences.edit().remove(HISTORY_KEY).apply()
        }
    }

    private fun persistHistoryLocked() {
        val normal = records.values.filterNot(EngineExtensionDownloadSnapshot::privateMode)
        val finishedIds = normal.filter {
            it.state != EngineExtensionDownloadState.IN_PROGRESS
        }.takeLast(MAX_HISTORY_ITEMS).mapTo(mutableSetOf(), EngineExtensionDownloadSnapshot::id)
        val retained = normal.filter {
            it.state == EngineExtensionDownloadState.IN_PROGRESS || it.id in finishedIds
        }
        if (normal.size > retained.size) {
            val retainIds = retained.mapTo(mutableSetOf(), EngineExtensionDownloadSnapshot::id)
            records.entries.removeAll { !it.value.privateMode && it.key !in retainIds }
            displayNames.keys.retainAll(records.keys)
            retryMetadata.keys.retainAll(records.keys)
        }
        val values = JSONArray()
        retained.forEach { values.put(snapshotToJson(it)) }
        preferences.edit().putString(HISTORY_KEY, values.toString()).apply()
    }

    private fun snapshotToJson(item: EngineExtensionDownloadSnapshot): JSONObject =
        JSONObject().apply {
            put("id", item.id)
            put("url", item.sourceUri)
            put("referrer", item.referrer)
            put("filename", item.filename)
            put("displayName", displayNames[item.id] ?: item.filename.substringAfterLast('/'))
            put("mime", item.mimeType)
            put("startTime", item.startTime)
            put("endTime", item.endTime ?: JSONObject.NULL)
            put("state", item.state.name)
            put("paused", item.paused)
            put("canResume", item.canResume)
            put("error", item.error?.name ?: JSONObject.NULL)
            put("bytesReceived", item.bytesReceived)
            put("totalBytes", item.totalBytes)
            put("fileSize", item.fileSize)
            put("exists", item.exists)
            put("byExtensionId", item.byExtensionId ?: JSONObject.NULL)
            put("byExtensionName", item.byExtensionName ?: JSONObject.NULL)
        }

    private fun snapshotFromJson(value: JSONObject): EngineExtensionDownloadSnapshot? = runCatching {
        EngineExtensionDownloadSnapshot(
            id = value.getLong("id"),
            sourceUri = value.getString("url"),
            referrer = value.optString("referrer"),
            filename = value.getString("filename"),
            privateMode = false,
            mimeType = value.optString("mime", DownloadMetadata.DEFAULT_MIME_TYPE),
            startTime = value.getString("startTime"),
            endTime = value.optionalString("endTime"),
            state = EngineExtensionDownloadState.valueOf(value.getString("state")),
            paused = value.optBoolean("paused"),
            canResume = value.optBoolean("canResume"),
            error = value.optionalString("error")
                ?.let(EngineExtensionDownloadInterruptReason::valueOf),
            bytesReceived = value.optLong("bytesReceived").coerceAtLeast(0L),
            totalBytes = value.optLong("totalBytes", -1L),
            fileSize = value.optLong("fileSize", -1L),
            exists = value.optBoolean("exists"),
            byExtensionId = value.optionalString("byExtensionId"),
            byExtensionName = value.optionalString("byExtensionName"),
        )
    }.getOrNull()

    private fun JSONObject.optionalString(key: String): String? =
        takeUnless { isNull(key) }?.optString(key)?.takeUnless(String::isEmpty)

    private fun ensureOpen() {
        check(!closed) { "Extension download coordinator is closed" }
    }

    private fun <T> tryStage(operation: () -> T): CompletionStage<T> = try {
        completed(operation())
    } catch (error: Throwable) {
        failed(error)
    }

    private fun <T> completed(value: T): CompletionStage<T> = CompletableFuture.completedFuture(value)

    private fun <T> failed(message: String): CompletionStage<T> =
        failed(EngineExtensionDownloadException(message))

    private fun <T> failed(error: Throwable): CompletionStage<T> =
        CompletableFuture<T>().also { it.completeExceptionally(error) }

    private companion object {
        const val HISTORY_PREFERENCES = "navis-extension-downloads"
        const val HISTORY_KEY = "normal-history-v1"
        const val MAX_HISTORY_ITEMS = 1_000

        fun isoTime(epochMillis: Long): String = SimpleDateFormat(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            Locale.ROOT,
        ).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(epochMillis))

        fun downloadStartMillis(value: String): Long = runCatching {
            SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                Locale.ROOT,
            ).apply {
                isLenient = false
                timeZone = TimeZone.getTimeZone("UTC")
            }.parse(value)?.time
        }.getOrNull() ?: Long.MAX_VALUE
    }
}
