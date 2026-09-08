/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.engine

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Test
import org.navis.browser.api.SessionId
import org.navis.browser.engine.runtime.EngineDownloadResponse

class EngineDownloadResponseTest {
    @Test
    fun closesTransferredResponseBodyExactlyOnce() {
        var closeCount = 0
        val body = object : ByteArrayInputStream(byteArrayOf(1, 2, 3)) {
            override fun close() {
                closeCount += 1
                super.close()
            }
        }
        val source = EngineDownloadResponse(
            sessionId = SessionId(7),
            uri = "blob:https://example.com/id",
            suggestedFileName = "archive.bin",
            contentType = "application/octet-stream",
            contentDisposition = null,
            contentLength = 3,
            privateMode = true,
            body = body,
        )

        source.close()
        source.close()

        assertEquals(1, closeCount)
    }
}
