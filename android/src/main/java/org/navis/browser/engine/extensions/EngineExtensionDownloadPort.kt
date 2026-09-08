/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.engine.extensions

import java.io.Closeable
import java.io.InputStream
import java.util.concurrent.CompletionStage
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.navis.browser.api.SessionId

internal enum class EngineExtensionDownloadConflictAction {
    UNIQUIFY,
    OVERWRITE,
    PROMPT,
}

internal enum class EngineExtensionDownloadState {
    IN_PROGRESS,
    INTERRUPTED,
    COMPLETE,
}

internal enum class EngineExtensionDownloadInterruptReason {
    FILE_FAILED,
    FILE_ACCESS_DENIED,
    FILE_NO_SPACE,
    FILE_TRANSIENT_ERROR,
    NETWORK_FAILED,
    NETWORK_INVALID_REQUEST,
    SERVER_FAILED,
    USER_CANCELED,
    USER_SHUTDOWN,
}

internal data class EngineExtensionDownloadHeader(
    val name: String,
    val value: String,
) {
    init {
        require(name.isNotBlank() && name.length <= 256) { "Invalid download header name" }
        require(value.length <= 16 * 1024) { "Download header value is too long" }
    }
}

/** Values accepted from the privileged WebExtensions parent API. */
internal data class EngineExtensionDownloadRequest(
    val extensionId: String,
    val extensionName: String,
    val sourceUri: String,
    val suggestedRelativePath: String?,
    val privateMode: Boolean,
    val privateBrowsingAllowed: Boolean,
    val saveAs: Boolean,
    val conflictAction: EngineExtensionDownloadConflictAction,
    val method: String,
    val headers: List<EngineExtensionDownloadHeader>,
    val body: String?,
    val allowHttpErrors: Boolean,
) {
    init {
        require(extensionId.isNotBlank() && extensionId.length <= 256) {
            "Invalid download extension ID"
        }
        require(extensionName.length <= 256) { "Download extension name is too long" }
        require(sourceUri.isNotBlank() && sourceUri.length <= 64 * 1024) {
            "Invalid download source URI"
        }
        require(suggestedRelativePath == null || suggestedRelativePath.length <= 4 * 1024) {
            "Suggested download path is too long"
        }
        require(!privateMode || privateBrowsingAllowed) {
            "Private browsing access not allowed"
        }
        require(method == "GET" || method == "POST") { "Invalid download request method" }
        require(headers.size <= 64) { "Too many download request headers" }
        require(body == null || body.toByteArray(Charsets.UTF_8).size <= MAX_BODY_BYTES) {
            "Download request body is too large"
        }
    }

    companion object {
        const val MAX_BODY_BYTES = 8 * 1024 * 1024
    }
}

/** Product-selected target and real session association, chosen before network I/O starts. */
internal data class EngineExtensionDownloadDestination(
    val sessionId: SessionId,
    val destinationUri: String?,
    val replaceExistingUri: String? = null,
    val requireExactFileName: Boolean = false,
) {
    init {
        require(sessionId.value > 0) { "Extension download requires a live product session" }
        require(destinationUri == null || destinationUri.startsWith("content://")) {
            "A Save As destination must be a content URI"
        }
        require(destinationUri == null || replaceExistingUri == null) { "Conflicting download destinations" }
        require(replaceExistingUri == null || replaceExistingUri.startsWith("content://") ||
            replaceExistingUri.startsWith("file://")) { "Invalid replacement destination" }
    }
}

/** One fetched body whose ownership moves from the engine peer to the product delegate. */
internal class EngineExtensionDownloadResponse(
    val finalUri: String,
    val statusCode: Int,
    val contentType: String?,
    val contentDisposition: String?,
    val contentLength: Long?,
    body: InputStream?,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val ownedBody = AtomicReference(body)

    fun takeBody(): InputStream? {
        check(!closed.get()) { "Extension download response is closed" }
        return ownedBody.getAndSet(null)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { ownedBody.getAndSet(null)?.close() }
        }
    }
}

/** Engine-neutral download history value returned to privileged extension code. */
internal data class EngineExtensionDownloadSnapshot(
    val id: Long,
    val sourceUri: String,
    val referrer: String,
    val filename: String,
    val privateMode: Boolean,
    val mimeType: String,
    val startTime: String,
    val endTime: String?,
    val state: EngineExtensionDownloadState,
    val paused: Boolean,
    val canResume: Boolean,
    val error: EngineExtensionDownloadInterruptReason?,
    val bytesReceived: Long,
    val totalBytes: Long,
    val fileSize: Long,
    val exists: Boolean,
    val byExtensionId: String?,
    val byExtensionName: String?,
) {
    init {
        require(id in 1..MAX_SAFE_INTEGER) { "Invalid extension download ID" }
        require(sourceUri.length <= 64 * 1024) { "Download source URI is too long" }
        require(referrer.length <= 64 * 1024) { "Download referrer is too long" }
        require(filename.length <= 64 * 1024) { "Download filename is too long" }
        require(mimeType.length <= 256) { "Download MIME type is too long" }
        require(startTime.isNotBlank() && startTime.length <= 64) { "Invalid download start time" }
        require(endTime == null || endTime.length <= 64) { "Invalid download end time" }
        require(bytesReceived >= 0L) { "Invalid downloaded byte count" }
        require(totalBytes >= -1L && fileSize >= -1L) { "Invalid download size" }
        require(state == EngineExtensionDownloadState.IN_PROGRESS || !paused) {
            "Only an in-progress download may be paused"
        }
        require(state != EngineExtensionDownloadState.COMPLETE || error == null) {
            "A completed download cannot expose an interrupt reason"
        }
    }

    companion object {
        const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
    }
}

internal interface EngineExtensionDownloadObserver {
    fun onCreated(item: EngineExtensionDownloadSnapshot)

    fun onChanged(
        previous: EngineExtensionDownloadSnapshot,
        current: EngineExtensionDownloadSnapshot,
    )

    fun onErased(id: Long, privateMode: Boolean)
}

/** Product operations invoked by the direct Android WebExtensions bridge. */
internal interface EngineExtensionDownloadDelegate {
    fun bind(observer: EngineExtensionDownloadObserver)

    fun unbind(observer: EngineExtensionDownloadObserver)

    fun prepare(
        request: EngineExtensionDownloadRequest,
    ): CompletionStage<EngineExtensionDownloadDestination>

    fun start(
        request: EngineExtensionDownloadRequest,
        destination: EngineExtensionDownloadDestination,
        response: EngineExtensionDownloadResponse,
    ): CompletionStage<EngineExtensionDownloadSnapshot>

    fun snapshots(privateBrowsingAllowed: Boolean): CompletionStage<List<EngineExtensionDownloadSnapshot>>

    fun pause(id: Long, privateBrowsingAllowed: Boolean): CompletionStage<Unit>

    fun resume(id: Long, privateBrowsingAllowed: Boolean): CompletionStage<Unit>

    fun cancel(id: Long, privateBrowsingAllowed: Boolean): CompletionStage<Unit>

    fun erase(ids: LongArray, privateBrowsingAllowed: Boolean): CompletionStage<LongArray>

    fun open(id: Long, privateBrowsingAllowed: Boolean): CompletionStage<Unit>

    fun show(id: Long, privateBrowsingAllowed: Boolean): CompletionStage<Boolean>

    fun showDefaultFolder(): CompletionStage<Unit>

    fun removeFile(id: Long, privateBrowsingAllowed: Boolean): CompletionStage<Unit>

    fun getFileIcon(id: Long, size: Int, privateBrowsingAllowed: Boolean): CompletionStage<String>
}

/** Process-wide direct download API peer; it owns no browser Runtime or Session. */
internal interface EngineExtensionDownloadPort : AutoCloseable {
    fun bind(delegate: EngineExtensionDownloadDelegate)

    fun unbind(delegate: EngineExtensionDownloadDelegate)

    fun canRetry(id: Long): Boolean = false

    fun retry(id: Long): CompletionStage<Boolean> = CompletableFuture.completedFuture(false)
}

internal class EngineExtensionDownloadException(message: String) : IllegalStateException(message)
