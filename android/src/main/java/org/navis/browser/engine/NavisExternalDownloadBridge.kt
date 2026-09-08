/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.engine

import java.io.InputStream
import org.mozilla.geckoview.WebResponse
import org.navis.browser.api.SessionId
import org.navis.browser.engine.runtime.EngineDownloadResponse
import org.navis.browser.engine.runtime.EngineDownloadRetryMetadata

/**
 * Adapts Gecko's original WebResponse stream to the product download port.
 *
 * The callback receives ownership only on a successful return. The body is
 * never read here, buffered, or recreated through a second request.
 */
internal class NavisExternalDownloadBridge(
    private val accept: (EngineDownloadResponse) -> Unit,
) {
    fun accept(sessionId: Long, privateMode: Boolean, response: WebResponse?) {
        val body: InputStream = response?.body ?: return
        val headers = response.headers
        val contentType = headers["content-type"]
        val contentDisposition = headers["content-disposition"]
        val contentLength = headers["content-length"]?.toLongOrNull()
        val handoff = EngineDownloadResponse(
            sessionId = SessionId(sessionId),
            uri = response.uri,
            suggestedFileName = response.navisSuggestedFileName,
            contentType = contentType,
            contentDisposition = contentDisposition,
            contentLength = contentLength?.takeIf { it >= 0L },
            privateMode = privateMode,
            body = body,
            retryMetadata = if (response.navisBodylessGet && response.navisRequestMethod == "GET") {
                runCatching {
                    EngineDownloadRetryMetadata(
                        sessionId = SessionId(sessionId),
                        sourceUri = response.uri,
                        privateMode = privateMode,
                        referrer = response.navisDownloadReferrer?.takeIf(String::isNotBlank),
                        originAttributes = response.navisDownloadOriginAttributes.orEmpty(),
                    )
                }.getOrNull()
            } else null,
        )
        try {
            accept(handoff)
        } catch (error: Throwable) {
            handoff.close()
            throw error
        }
    }
}
