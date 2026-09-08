/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.navis.browser.api.SessionId
import org.navis.browser.downloads.DownloadEntry
import org.navis.browser.downloads.DownloadState
import org.navis.browser.engine.runtime.EngineDownloadResponse
import org.navis.browser.engine.runtime.EngineDownloadRetryMetadata

class DownloadProductPolicyTest {
    @Test
    fun unknownResponseCannotAccidentallyGainRetryCapability() {
        val response = EngineDownloadResponse(
            SessionId(7), "https://example.com/post-result", "export.pdf", "application/pdf",
            null, null, false, null,
        )
        assertNull(response.retryMetadata)
    }

    @Test
    fun retryMetadataRejectsUnreplayableOrCredentialBearingUrls() {
        listOf(
            "blob:https://example.com/id", "data:text/plain,a", "file:///data/private",
            "javascript:alert(1)", "https://user:secret@example.com/a", "https:/missing-host",
        ).forEach { uri ->
            assertTrue(runCatching { EngineDownloadRetryMetadata(SessionId(7), uri, false) }.isFailure)
        }
        assertTrue(runCatching {
            EngineDownloadRetryMetadata(SessionId(0), "https://example.com/a", false)
        }.isFailure)
        assertTrue(runCatching {
            EngineDownloadRetryMetadata(SessionId(7), "https://example.com/a", false,
                originAttributes = "^privateBrowsingId=1")
        }.isFailure)
        assertTrue(runCatching {
            EngineDownloadRetryMetadata(SessionId(7), "https://example.com/a", true,
                originAttributes = "")
        }.isFailure)
        assertTrue(runCatching {
            EngineDownloadRetryMetadata(SessionId(7), "https://example.com/a", false,
                originAttributes = "^userContextId=2")
        }.isFailure)
    }

    @Test
    fun retryContextPreservesPrivateSessionAndReferrer() {
        val metadata = EngineDownloadRetryMetadata(
            SessionId(12), "https://example.com/export", true, "https://example.com/account",
        )
        assertEquals(SessionId(12), metadata.sessionId)
        assertTrue(metadata.privateMode)
        assertEquals("https://example.com/account", metadata.referrer)
    }

    @Test
    fun completionRequiresAStillExistingFileAndProgressIsBounded() {
        val completed = entry().copy(state = DownloadState.COMPLETE, exists = true)
        assertTrue(completed.canOpen)
        assertFalse(completed.copy(exists = false).canOpen)
        assertFalse(entry().copy(exists = true).canOpen)
        assertNull(entry().copy(totalBytes = -1).progress)
        assertEquals(1f, entry().copy(bytesReceived = 150, totalBytes = 100).progress)
        assertEquals(0.5f, entry().copy(bytesReceived = 50, totalBytes = 100).progress)
    }

    @Test
    fun sourceActionDoesNotNavigateToBlobOrLocalFiles() {
        assertTrue(entry().canOpenSource)
        assertFalse(entry().copy(sourceUri = "blob:https://example.com/a").canOpenSource)
        assertFalse(entry().copy(sourceUri = "file:///data/export").canOpenSource)
        assertFalse(entry().copy(sourceUri = "data:application/octet-stream;base64,QQ==").canOpenSource)
    }

    private fun entry() = DownloadEntry(
        1, "export.pdf", "https://example.com/export", false, DownloadState.DOWNLOADING,
        0, -1, false, false, false, "2026-09-05T00:00:00.000Z",
    )
}
