/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.engine

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DownloadStreamCopierTest {
    @Test
    fun streamsCompleteBodyWithBoundedBuffer() {
        val payload = ByteArray(DownloadStreamCopier.BUFFER_BYTES * 3 + 17) { index ->
            (index % 251).toByte()
        }
        val output = ByteArrayOutputStream()
        var lastProgress = 0L

        val bytesWritten = DownloadStreamCopier.copy(
            ByteArrayInputStream(payload),
            output,
        ) { bytes ->
            lastProgress = bytes
        }

        assertEquals(payload.size.toLong(), bytesWritten)
        assertEquals(bytesWritten, lastProgress)
        assertArrayEquals(payload, output.toByteArray())
    }

    @Test
    fun propagatesResponseReadFailure() {
        val failedInput = object : InputStream() {
            override fun read(): Int = throw IOException("fixture failure")

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                throw IOException("fixture failure")
        }

        assertThrows(IOException::class.java) {
            DownloadStreamCopier.copy(failedInput, ByteArrayOutputStream()) {}
        }
    }

    @Test
    fun reportsCommittedChunkBeforeTheNextReadFails() {
        val input = object : InputStream() {
            var reads = 0
            override fun read(): Int = throw AssertionError("Bulk reads are required")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (reads++ > 0) throw IOException("connection interrupted")
                repeat(8) { buffer[offset + it] = it.toByte() }
                return 8
            }
        }
        val output = ByteArrayOutputStream()
        var bytesWritten = 0L
        assertThrows(IOException::class.java) {
            bytesWritten = DownloadStreamCopier.copy(input, output) { bytesWritten = it }
        }
        assertEquals(8L, bytesWritten)
        assertEquals(8, output.size())
    }
}
