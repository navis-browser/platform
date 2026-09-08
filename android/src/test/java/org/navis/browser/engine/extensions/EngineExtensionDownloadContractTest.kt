/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.engine.extensions

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class EngineExtensionDownloadContractTest {
    @Test
    fun privateRequestRequiresPrivateAccess() {
        assertThrows(IllegalArgumentException::class.java) {
            request(privateMode = true, privateBrowsingAllowed = false)
        }
    }

    @Test
    fun responseBodyOwnershipTransfersExactlyOnce() {
        var closeCount = 0
        val input = object : ByteArrayInputStream(byteArrayOf(1)) {
            override fun close() {
                closeCount += 1
                super.close()
            }
        }
        val response = EngineExtensionDownloadResponse(
            finalUri = "https://example.com/file",
            statusCode = 200,
            contentType = "application/octet-stream",
            contentDisposition = null,
            contentLength = 1L,
            body = input,
        )

        assertEquals(input, response.takeBody())
        assertNull(response.takeBody())
        response.close()
        assertEquals(0, closeCount)
        input.close()
        assertEquals(1, closeCount)
    }

    private fun request(
        privateMode: Boolean,
        privateBrowsingAllowed: Boolean,
    ) = EngineExtensionDownloadRequest(
        extensionId = "fixture@example.test",
        extensionName = "Fixture",
        sourceUri = "https://example.test/file",
        suggestedRelativePath = null,
        privateMode = privateMode,
        privateBrowsingAllowed = privateBrowsingAllowed,
        saveAs = false,
        conflictAction = EngineExtensionDownloadConflictAction.UNIQUIFY,
        method = "GET",
        headers = emptyList(),
        body = null,
        allowHttpErrors = false,
    )
}
