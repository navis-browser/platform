/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.engine.extensions

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import org.mozilla.gecko.navis.NavisAndroidExtensionDownloads
import org.navis.browser.api.SessionId

/** Sole adapter between the private Gecko message peer and product-owned download state. */
internal class NavisAndroidExtensionDownloadPort private constructor() :
    EngineExtensionDownloadPort,
    NavisAndroidExtensionDownloads.Delegate {
    private val lock = Any()
    private var delegate: EngineExtensionDownloadDelegate? = null
    private var peer: NavisAndroidExtensionDownloads? = null
    private var closed = false
    private val retryRequests = linkedMapOf<Long, NavisAndroidExtensionDownloads.Request>()
    private val eventRelay = object : EngineExtensionDownloadObserver {
        override fun onCreated(item: EngineExtensionDownloadSnapshot) {
            synchronized(lock) { peer.takeUnless { closed } }?.publishCreated(item.toPeer())
        }

        override fun onChanged(
            previous: EngineExtensionDownloadSnapshot,
            current: EngineExtensionDownloadSnapshot,
        ) {
            synchronized(lock) { peer.takeUnless { closed } }
                ?.publishChanged(previous.toPeer(), current.toPeer())
        }

        override fun onErased(id: Long, privateMode: Boolean) {
            synchronized(lock) { retryRequests.remove(id) }
            synchronized(lock) { peer.takeUnless { closed } }?.publishErased(id, privateMode)
        }
    }

    override fun bind(delegate: EngineExtensionDownloadDelegate) {
        synchronized(lock) {
            check(!closed) { "Extension download port is closed" }
            check(this.delegate == null || this.delegate === delegate) {
                "Extension download port is already bound"
            }
            if (this.delegate === delegate) return
            delegate.bind(eventRelay)
            this.delegate = delegate
        }
    }

    override fun unbind(delegate: EngineExtensionDownloadDelegate) {
        synchronized(lock) {
            if (this.delegate === delegate) {
                this.delegate = null
                delegate.unbind(eventRelay)
            }
        }
    }

    override fun onPrepare(
        request: NavisAndroidExtensionDownloads.Request,
    ): CompletionStage<NavisAndroidExtensionDownloads.Destination> = withDelegate { target ->
        target.prepare(request.toEngine()).thenApply { destination ->
            NavisAndroidExtensionDownloads.Destination(
                destination.sessionId.value,
                destination.destinationUri,
                destination.replaceExistingUri,
                destination.requireExactFileName,
            )
        }
    }

    override fun onStart(
        request: NavisAndroidExtensionDownloads.Request,
        destination: NavisAndroidExtensionDownloads.Destination,
        response: NavisAndroidExtensionDownloads.Response,
    ): CompletionStage<NavisAndroidExtensionDownloads.Record> {
        val body = response.body
        val target = synchronized(lock) { delegate.takeUnless { closed } }
        if (target == null) {
            runCatching { body?.close() }
            return failed(EngineExtensionDownloadException("Extension downloads are unavailable"))
        }
        val transferred = EngineExtensionDownloadResponse(
            finalUri = response.finalUri,
            statusCode = response.statusCode,
            contentType = response.contentType,
            contentDisposition = response.contentDisposition,
            contentLength = response.contentLength,
            body = body,
        )
        return try {
            target.start(
                request.toEngine(),
                EngineExtensionDownloadDestination(
                    SessionId(destination.sessionId),
                    destination.destinationUri,
                    destination.replaceExistingUri,
                    destination.requireExactFileName,
                ),
                transferred,
            ).thenApply { snapshot ->
                if (request.method == "GET" && request.body == null &&
                    (request.sourceUri.startsWith("https://") || request.sourceUri.startsWith("http://"))
                ) {
                    synchronized(lock) {
                        if (!closed) {
                            retryRequests[snapshot.id] = request
                            while (retryRequests.size > 1_000) {
                                retryRequests.remove(retryRequests.keys.first())
                            }
                        }
                    }
                }
                snapshot.toPeer()
            }
        } catch (error: Throwable) {
            transferred.close()
            failed(error)
        }
    }

    override fun onList(
        privateBrowsingAllowed: Boolean,
    ): CompletionStage<Array<NavisAndroidExtensionDownloads.Record>> = withDelegate { target ->
        target.snapshots(privateBrowsingAllowed).thenApply { values ->
            values.map { snapshot -> snapshot.toPeer() }.toTypedArray()
        }
    }

    override fun onPause(
        id: Long,
        privateBrowsingAllowed: Boolean,
    ): CompletionStage<Void> = unitStage { it.pause(id, privateBrowsingAllowed) }

    override fun onResume(
        id: Long,
        privateBrowsingAllowed: Boolean,
    ): CompletionStage<Void> = unitStage { it.resume(id, privateBrowsingAllowed) }

    override fun onCancel(
        id: Long,
        privateBrowsingAllowed: Boolean,
    ): CompletionStage<Void> = unitStage { it.cancel(id, privateBrowsingAllowed) }

    override fun onErase(
        ids: LongArray,
        privateBrowsingAllowed: Boolean,
    ): CompletionStage<LongArray> = withDelegate { it.erase(ids, privateBrowsingAllowed) }

    override fun onOpen(
        id: Long,
        privateBrowsingAllowed: Boolean,
    ): CompletionStage<Void> = unitStage { it.open(id, privateBrowsingAllowed) }

    override fun onShow(
        id: Long,
        privateBrowsingAllowed: Boolean,
    ): CompletionStage<Boolean> = withDelegate { it.show(id, privateBrowsingAllowed) }

    override fun onShowDefaultFolder(): CompletionStage<Void> =
        unitStage(EngineExtensionDownloadDelegate::showDefaultFolder)

    override fun onRemoveFile(id: Long, privateBrowsingAllowed: Boolean): CompletionStage<Void> =
        unitStage { it.removeFile(id, privateBrowsingAllowed) }

    override fun onGetFileIcon(id: Long, size: Int, privateBrowsingAllowed: Boolean): CompletionStage<String> =
        withDelegate { it.getFileIcon(id, size, privateBrowsingAllowed) }

    override fun canRetry(id: Long): Boolean = synchronized(lock) {
        !closed && peer != null && id in retryRequests
    }

    override fun retry(id: Long): CompletionStage<Boolean> {
        val (installed, request) = synchronized(lock) {
            val installed = peer.takeUnless { closed }
                ?: return failed(EngineExtensionDownloadException("Extension downloads are unavailable"))
            val request = retryRequests[id]
                ?: return failed(EngineExtensionDownloadException("Original download request is unavailable"))
            installed to request
        }
        return installed.retryDownload(request)
    }

    override fun close() {
        val release = synchronized(lock) {
            if (closed) return
            closed = true
            retryRequests.clear()
            val current = delegate
            delegate = null
            current to peer.also { peer = null }
        }
        val failures = mutableListOf<Throwable>()
        release.first?.let { target ->
            runCatching { target.unbind(eventRelay) }.exceptionOrNull()?.let(failures::add)
        }
        release.second?.let { installed ->
            runCatching(installed::close).exceptionOrNull()?.let(failures::add)
        }
        failures.firstOrNull()?.let { first ->
            failures.drop(1).forEach(first::addSuppressed)
            throw first
        }
    }

    private fun <T> withDelegate(
        operation: (EngineExtensionDownloadDelegate) -> CompletionStage<T>,
    ): CompletionStage<T> {
        val target = synchronized(lock) { delegate.takeUnless { closed } }
            ?: return failed(EngineExtensionDownloadException("Extension downloads are unavailable"))
        return try {
            operation(target)
        } catch (error: Throwable) {
            failed(error)
        }
    }

    private fun unitStage(
        operation: (EngineExtensionDownloadDelegate) -> CompletionStage<Unit>,
    ): CompletionStage<Void> = withDelegate(operation).thenApply<Void> { null }

    private fun NavisAndroidExtensionDownloads.Request.toEngine() =
        EngineExtensionDownloadRequest(
            extensionId = extensionId,
            extensionName = extensionName,
            sourceUri = sourceUri,
            suggestedRelativePath = suggestedRelativePath,
            privateMode = privateMode,
            privateBrowsingAllowed = privateBrowsingAllowed,
            saveAs = saveAs,
            conflictAction = EngineExtensionDownloadConflictAction.valueOf(conflictAction),
            method = method,
            headers = headers.map { EngineExtensionDownloadHeader(it.name, it.value) },
            body = body,
            allowHttpErrors = allowHttpErrors,
        )

    private fun EngineExtensionDownloadSnapshot.toPeer() =
        NavisAndroidExtensionDownloads.Record(
            id,
            sourceUri,
            referrer,
            filename,
            privateMode,
            mimeType,
            startTime,
            endTime,
            state.name,
            paused,
            canResume,
            error?.name,
            bytesReceived,
            totalBytes,
            fileSize,
            exists,
            byExtensionId,
            byExtensionName,
        )

    companion object {
        fun install(): NavisAndroidExtensionDownloadPort {
            val port = NavisAndroidExtensionDownloadPort()
            port.peer = NavisAndroidExtensionDownloads.install(port)
            return port
        }

        private fun <T> failed(error: Throwable): CompletionStage<T> =
            CompletableFuture<T>().also { it.completeExceptionally(error) }
    }
}
