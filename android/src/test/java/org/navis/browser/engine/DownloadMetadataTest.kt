/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadMetadataTest {
    @Test
    fun removesPathAndControlCharactersFromFileName() {
        assertEquals("_.._report _2026_.pdf", DownloadMetadata.sanitizeFileName("/../report\u0000:2026?.pdf"))
    }

    @Test
    fun boundsUtf8FileNameWhilePreservingExtension() {
        val result = DownloadMetadata.sanitizeFileName("测".repeat(100) + ".tar")
        assertTrue(result.toByteArray(Charsets.UTF_8).size <= DownloadMetadata.MAX_FILE_NAME_BYTES)
        assertTrue(result.endsWith(".tar"))
    }

    @Test
    fun addsNumericSuffixBeforeExtension() {
        assertEquals("archive (2).zip", DownloadMetadata.numberedFileName("archive.zip", 2))
    }

    @Test
    fun acceptsOnlyBoundedMediaTypes() {
        assertEquals("text/html", DownloadMetadata.sanitizeMimeType(" Text/HTML; charset=UTF-8 "))
        assertEquals(
            DownloadMetadata.DEFAULT_MIME_TYPE,
            DownloadMetadata.sanitizeMimeType("text/plain\r\nX-Injected: true"),
        )
    }

    @Test
    fun acceptsOnlyPositiveContentLengths() {
        assertEquals(42L, DownloadMetadata.parseContentLength(" 42 "))
        assertNull(DownloadMetadata.parseContentLength("-1"))
        assertNull(DownloadMetadata.parseContentLength("not-a-number"))
    }

    @Test
    fun doesNotProjectDataPayloadIntoPublicMetadata() {
        assertEquals(
            "data:",
            DownloadMetadata.boundedSourceUri("data:text/plain;base64,VGhpcyBpcyBwcml2YXRl"),
        )
    }
}
