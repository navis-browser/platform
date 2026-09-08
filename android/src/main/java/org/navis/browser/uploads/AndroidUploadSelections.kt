/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.uploads

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.OperationCanceledException
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import org.navis.browser.api.FilePickerMode
import org.navis.browser.api.FilePickerRequest
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Only SAF selections from the active OS picker enter here; no broad storage permission is used. */
internal object AndroidUploadSelections {
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "Navis-file-selection") }
    private val main = Handler(Looper.getMainLooper())
    private val jobs = mutableMapOf<Long, MutableSet<Job>>()
    // Accessed only by the worker. Successful files live until their originating tab closes.
    private val ownedDirectories = mutableMapOf<Long, MutableSet<File>>()
    private var root: File? = null

    class Job internal constructor() {
        private val cancelled = AtomicBoolean(false)
        internal val signal = CancellationSignal()
        @Volatile private var stream: InputStream? = null
        fun cancel() {
            cancelled.set(true)
            runCatching { signal.cancel() }
            runCatching { stream?.close() }
        }
        internal fun check() {
            if (cancelled.get()) throw UploadException(UploadFailure.CANCELLED)
        }
        internal fun reading(input: InputStream?) {
            stream = input
            check()
        }
    }

    class Selection internal constructor(val uris: List<String>, private val directory: File, private val sessionId: Long) {
        fun discard() { worker.execute { runCatching { directory.deleteRecursively() }; ownedDirectories[sessionId]?.remove(directory) } }
    }

    /** Main-process startup only. Never run in a Gecko isolated or relaunch-helper process. */
    fun initialize(context: Context) {
        val application = context.applicationContext
        // Startup storage pressure must not crash the process. A later selection reports it to UI.
        worker.execute { runCatching { initializeOnWorker(application) } }
    }

    fun prepare(context: Context, request: FilePickerRequest, selected: List<String>,
                callback: (Result<Selection>) -> Unit): Job {
        val job = Job()
        val session = request.sessionId.value
        synchronized(jobs) { jobs.getOrPut(session) { mutableSetOf() }.add(job) }
        val application = context.applicationContext
        worker.execute {
            var directory: File? = null
            val result = runCatching {
                job.check()
                val staging = initializeOnWorker(application)
                directory = File(staging, UUID.randomUUID().toString()).also {
                    if (!it.mkdir()) throw UploadException(UploadFailure.NO_SPACE)
                    ownedDirectories.getOrPut(session) { mutableSetOf() }.add(it)
                }
                val destination = checkNotNull(directory)
                val reader = Reader(application, job, staging)
                val uris = selected.distinct().map(Uri::parse)
                if (uris.isEmpty() || uris.any { it.scheme != "content" }) throw UploadException(UploadFailure.UNREADABLE)
                if (request.mode != FilePickerMode.MULTIPLE && uris.size != 1) throw UploadException(UploadFailure.UNREADABLE)
                val files = if (request.mode == FilePickerMode.FOLDER) {
                    if (uris.size != 1 || !DocumentsContract.isTreeUri(uris.single())) throw UploadException(UploadFailure.UNREADABLE)
                    listOf(reader.copyTree(uris.single(), destination))
                } else {
                    if (uris.size > UploadSelectionPolicy.MAX_FILES) throw UploadException(UploadFailure.FILE_COUNT)
                    uris.mapIndexed { index, uri ->
                        // Separate parents preserve two same-named selections without renaming either.
                        val parent = File(destination, index.toString())
                        if (!parent.mkdir()) throw UploadException(UploadFailure.NO_SPACE)
                        reader.copyFile(uri, parent, reader.metadata(uri))
                    }
                }
                job.check()
                Selection(files.map { Uri.fromFile(it).toString() }, destination, session)
            }.recoverCatching { error ->
                directory?.deleteRecursively()
                ownedDirectories[session]?.remove(directory)
                if (error is UploadException) throw error
                if (error is OperationCanceledException) throw UploadException(UploadFailure.CANCELLED)
                if ((root?.usableSpace ?: Long.MAX_VALUE) < UploadSelectionPolicy.MIN_FREE_BYTES) {
                    throw UploadException(UploadFailure.NO_SPACE)
                }
                throw UploadException(UploadFailure.UNREADABLE)
            }
            synchronized(jobs) { jobs[session]?.let { it.remove(job); if (it.isEmpty()) jobs.remove(session) } }
            main.post {
                val checked = runCatching { job.check(); result.getOrThrow() }
                if (checked.isFailure) result.getOrNull()?.discard()
                callback(checked)
            }
        }
        return job
    }

    /** Call after a session actually closes, including each private tab; not on vetoed beforeunload. */
    fun releaseSession(sessionId: Long) {
        synchronized(jobs) { jobs[sessionId]?.toList() }.orEmpty().forEach(Job::cancel)
        worker.execute { ownedDirectories.remove(sessionId)?.forEach { runCatching { it.deleteRecursively() } } }
    }

    private fun initializeOnWorker(context: Context): File {
        root?.let { return it }
        // GeckoLoader sets TmpD to cache/gecko_temp. Gecko independently validates this exact root.
        val staging = File(context.cacheDir, "gecko_temp/navis-upload-staging")
        if (staging.exists() && !staging.deleteRecursively()) throw UploadException(UploadFailure.NO_SPACE)
        if (!staging.mkdirs() && !staging.isDirectory) throw UploadException(UploadFailure.NO_SPACE)
        return staging.also { root = it }
    }

    private data class Metadata(val name: String, val size: Long = -1, val type: String? = null, val modified: Long = 0)

    private class Reader(context: Context, private val job: Job, private val staging: File) {
        private val resolver = context.contentResolver
        private var fileCount = 0
        private var totalBytes = 0L
        private var treeEntries = 0

        init {
            staging.walkTopDown().forEach {
                job.check()
                if (++treeEntries > UploadSelectionPolicy.MAX_TREE_ENTRIES) throw UploadException(UploadFailure.TREE_LIMIT)
                if (it.isFile) { fileCount++; totalBytes += it.length() }
            }
        }

        fun metadata(uri: Uri): Metadata = resolver.query(uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null, job.signal,
        )?.use { cursor ->
            job.check()
            if (!cursor.moveToFirst()) throw UploadException(UploadFailure.UNREADABLE)
            val name = cursor.getString(0) ?: throw UploadException(UploadFailure.INVALID_NAME)
            Metadata(UploadSelectionPolicy.checkedName(name), if (cursor.isNull(1)) -1 else cursor.getLong(1))
        } ?: throw UploadException(UploadFailure.UNREADABLE)

        fun copyFile(uri: Uri, parent: File, info: Metadata): File {
            job.check()
            if (++fileCount > UploadSelectionPolicy.MAX_FILES) throw UploadException(UploadFailure.FILE_COUNT)
            if (info.size > UploadSelectionPolicy.MAX_FILE_BYTES) throw UploadException(UploadFailure.FILE_SIZE)
            if (info.size > UploadSelectionPolicy.MAX_TOTAL_BYTES - totalBytes) throw UploadException(UploadFailure.TOTAL_SIZE)
            if (staging.usableSpace - info.size.coerceAtLeast(0) < UploadSelectionPolicy.MIN_FREE_BYTES) throw UploadException(UploadFailure.NO_SPACE)
            val target = File(parent, UploadSelectionPolicy.checkedName(info.name))
            if (!target.createNewFile()) throw UploadException(UploadFailure.INVALID_NAME)
            resolver.openAssetFileDescriptor(uri, "r", job.signal)?.use { descriptor ->
                descriptor.createInputStream().use { input ->
                    job.reading(input)
                    try {
                        target.outputStream().use { output ->
                            totalBytes += UploadSelectionPolicy.copyBounded(input, output, totalBytes, job::check, staging::getUsableSpace)
                        }
                    } finally { job.reading(null) }
                }
            } ?: throw UploadException(UploadFailure.UNREADABLE)
            if (info.modified > 0) target.setLastModified(info.modified)
            return target
        }

        fun copyTree(tree: Uri, destination: File): File {
            val rootId = DocumentsContract.getTreeDocumentId(tree)
            val rootUri = DocumentsContract.buildDocumentUriUsingTree(tree, rootId)
            val rootInfo = metadata(rootUri)
            val selectedRoot = File(destination, rootInfo.name)
            if (!selectedRoot.mkdir()) throw UploadException(UploadFailure.INVALID_NAME)
            val visited = hashSetOf(rootId)
            fun copyChildren(id: String, parent: File, depth: Int) {
                job.check()
                if (depth > UploadSelectionPolicy.MAX_TREE_DEPTH) throw UploadException(UploadFailure.TREE_LIMIT)
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
                resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                    null, null, null, job.signal)?.use { cursor ->
                    while (cursor.moveToNext()) {
                        job.check()
                        if (++treeEntries > UploadSelectionPolicy.MAX_TREE_ENTRIES) throw UploadException(UploadFailure.TREE_LIMIT)
                        val childId = cursor.getString(0) ?: throw UploadException(UploadFailure.UNREADABLE)
                        if (!visited.add(childId)) throw UploadException(UploadFailure.TREE_LIMIT)
                        val child = Metadata(UploadSelectionPolicy.checkedName(cursor.getString(1) ?: ""),
                            if (cursor.isNull(3)) -1 else cursor.getLong(3), cursor.getString(2),
                            if (cursor.isNull(4)) 0 else cursor.getLong(4))
                        if (child.type == DocumentsContract.Document.MIME_TYPE_DIR) {
                            val folder = File(parent, child.name)
                            if (!folder.mkdir()) throw UploadException(UploadFailure.INVALID_NAME)
                            copyChildren(childId, folder, depth + 1)
                        } else {
                            copyFile(DocumentsContract.buildDocumentUriUsingTree(tree, childId), parent, child)
                        }
                    }
                } ?: throw UploadException(UploadFailure.UNREADABLE)
            }
            copyChildren(rootId, selectedRoot, 0)
            return selectedRoot
        }
    }
}
