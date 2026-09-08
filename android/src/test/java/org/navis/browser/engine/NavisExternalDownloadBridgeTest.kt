/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.engine

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.mozilla.geckoview.WebResponse
import org.navis.browser.engine.runtime.EngineDownloadResponse

class NavisExternalDownloadBridgeTest {
    private class ObservedBody : ByteArrayInputStream(byteArrayOf(1, 2, 3)) {
        var reads = 0
        var closes = 0
        override fun read(): Int { reads++; return super.read() }
        override fun close() { closes++; super.close() }
    }

    private fun builder(body: ObservedBody, filename: String?): WebResponse.Builder {
        val builder = WebResponse.Builder("https://example.com/download.txt").body(body)
        if (filename != null) {
            // Invoke the actual JNI-facing builder method, not a test DTO or synthetic header.
            WebResponse.Builder::class.java.getDeclaredMethod(
                "navisSuggestedFileName", String::class.java,
            ).apply { isAccessible = true }.invoke(builder, filename)
        }
        return builder
    }

    private fun deliver(response: WebResponse): EngineDownloadResponse {
        var result: EngineDownloadResponse? = null
        NavisExternalDownloadBridge { result = it }.accept(7, true, response)
        return checkNotNull(result)
    }

    @Test
    fun preservesHtmlSuggestionWithoutFabricatingResponseHeadersOrReadingBody() {
        val body = ObservedBody()
        val response = builder(body, "navis-parity-download.txt").build()
        val source = deliver(response)
        assertEquals("navis-parity-download.txt", source.suggestedFileName)
        assertNull(source.contentDisposition)
        assertEquals(emptyMap<String, String>(), response.headers)
        assertEquals("https://example.com/download.txt", source.uri)
        assertEquals(true, source.privateMode)
        assertEquals(7L, source.sessionId.value)
        assertSame(body, source.body)
        assertEquals(0, body.reads)
        assertEquals(0, body.closes)
        source.close()
        source.close()
        assertEquals(1, body.closes)
    }

    @Test
    fun preservesGeckoResolvedServerFilenameAndOriginalHeaderIndependently() {
        val raw = "attachment; filename*=UTF-8''server-%E4%B8%AD.txt"
        val source = deliver(builder(ObservedBody(), "server-中.txt")
            .header("Content-Disposition", raw).build())
        assertEquals("server-中.txt", source.suggestedFileName)
        assertEquals(raw, source.contentDisposition)
        source.close()
    }

    @Test
    fun leavesAbsentChannelFilenameAbsentForExistingFallbackPolicy() {
        val source = deliver(builder(ObservedBody(), null).build())
        assertNull(source.suggestedFileName)
        source.close()
    }

    @Test
    fun filenameIsStillUntrustedAndUsesExistingStoreSanitizer() {
        val source = deliver(builder(ObservedBody(), "../中\\report.txt").build())
        assertEquals("../中\\report.txt", source.suggestedFileName)
        assertEquals("_中_report.txt", DownloadMetadata.sanitizeFileName(source.suggestedFileName))
        source.close()
    }

    @Test
    fun failedHandoffStillClosesOriginalBodyExactlyOnce() {
        val body = ObservedBody()
        val failure = IllegalStateException("rejected")
        try {
            NavisExternalDownloadBridge { throw failure }.accept(7, false, builder(body, "name.txt").build())
            error("handoff should fail")
        } catch (actual: IllegalStateException) {
            assertSame(failure, actual)
        }
        assertEquals(1, body.closes)
        assertEquals(0, body.reads)
    }
}
