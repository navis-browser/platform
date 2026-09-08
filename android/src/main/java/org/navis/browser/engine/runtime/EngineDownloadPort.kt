/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.engine.runtime

import java.io.Closeable
import java.io.InputStream
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import org.navis.browser.api.SessionId

/**
 * One engine-fetched response body offered to the product download store.
 *
 * Delivery transfers ownership to [EngineDownloadObserver]. The consumer must close the object on
 * every success, rejection, cancellation, and shutdown path. This preserves cookies, credentials,
 * POST/blob semantics, and private-session identity without issuing a second network request.
 */
internal class EngineDownloadResponse(
    val sessionId: SessionId,
    val uri: String,
    val suggestedFileName: String?,
    val contentType: String?,
    val contentDisposition: String?,
    val contentLength: Long?,
    val privateMode: Boolean,
    val body: InputStream?,
    val retryMetadata: EngineDownloadRetryMetadata? = null,
) : Closeable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { body?.close() }
        }
    }
}

/**
 * Only the engine binding may supply this, after confirming the original request was a bodyless
 * GET. Unknown methods, POST, blob/data bodies and custom authorization must not be reconstructed.
 * No credentials are stored here: the retry must use the matching live browsing context.
 */
internal data class EngineDownloadRetryMetadata(
    val sessionId: SessionId,
    val sourceUri: String,
    val privateMode: Boolean,
    val referrer: String? = null,
    val originAttributes: String = if (privateMode) "^privateBrowsingId=1" else "",
) {
    init {
        require(sessionId.value > 0) { "A retry requires its original session" }
        require(sourceUri.length <= 64 * 1024 && isHttpUri(sourceUri)) {
            "Only an HTTP(S) GET can be retried"
        }
        require(referrer == null || (referrer.length <= 64 * 1024 && isHttpUri(referrer))) {
            "Invalid download referrer"
        }
        require(originAttributes == if (privateMode) "^privateBrowsingId=1" else "") {
            "Download cookie context does not match its Session"
        }
    }

    private fun isHttpUri(value: String): Boolean = runCatching {
        URI(value).let { it.scheme in setOf("http", "https") && it.host != null && it.userInfo == null }
    }.getOrDefault(false)
}

internal fun interface EngineDownloadObserver {
    fun onDownload(response: EngineDownloadResponse)
}

/** Binding surface for the future direct DOWNLOADS projection. */
internal interface EngineDownloadPort {
    fun bind(observer: EngineDownloadObserver)

    fun unbind(observer: EngineDownloadObserver)
}
