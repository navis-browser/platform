/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.engine.extensions

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.navis.browser.engine.DownloadStreamCopier

class PausableDownloadInputStreamTest {
    @Test
    fun pauseBlocksReadsUntilResume() {
        val stream = PausableDownloadInputStream(ByteArrayInputStream(byteArrayOf(7)))
        assertTrue(stream.pause())
        assertFalse(stream.pause())
        val entered = CountDownLatch(1)
        val finished = CountDownLatch(1)
        var value = -1
        val reader = Thread {
            entered.countDown()
            value = stream.read()
            finished.countDown()
        }
        reader.start()

        assertTrue(entered.await(1, TimeUnit.SECONDS))
        assertFalse(finished.await(50, TimeUnit.MILLISECONDS))
        assertTrue(stream.resumeTransfer())
        assertTrue(finished.await(1, TimeUnit.SECONDS))
        assertEquals(7, value)
    }

    @Test
    fun cancelIsIdempotentAndUnblocksReader() {
        var closeCount = 0
        val input = object : ByteArrayInputStream(byteArrayOf(1)) {
            override fun close() {
                closeCount += 1
                super.close()
            }
        }
        val stream = PausableDownloadInputStream(input)
        assertTrue(stream.pause())
        stream.cancel()
        stream.cancel()

        assertEquals(1, closeCount)
        assertThrows(IOException::class.java) { stream.read() }
        assertFalse(stream.resumeTransfer())
    }

    @Test
    fun pauseThenResumeCopiesTheSameResponseWithoutRepeatingBytes() {
        val payload = ByteArray(73) { it.toByte() }
        val source = object : ByteArrayInputStream(payload) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                super.read(buffer, offset, minOf(length, 8))
        }
        val stream = PausableDownloadInputStream(source)
        val output = ByteArrayOutputStream()
        val paused = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        var lastProgress = 0L
        val worker = Thread {
            try {
                DownloadStreamCopier.copy(stream, output) { bytes ->
                    lastProgress = bytes
                    if (bytes == 8L) {
                        check(stream.pause())
                        paused.countDown()
                    }
                }
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                finished.countDown()
            }
        }
        worker.start()
        try {
            assertTrue(paused.await(1, TimeUnit.SECONDS))
            assertEquals(8L, lastProgress)
            assertEquals(65, source.available())
            assertFalse(finished.await(50, TimeUnit.MILLISECONDS))
            assertTrue(stream.resumeTransfer())
            assertTrue(finished.await(1, TimeUnit.SECONDS))
            failure.get()?.let { throw AssertionError("Response copy failed", it) }
            assertEquals(73L, lastProgress)
            assertArrayEquals(payload, output.toByteArray())
        } finally {
            stream.cancel()
            worker.join(1_000)
        }
    }

    @Test
    fun cancelDuringPausedCopyUnblocksWorkerAndPreservesTransferredBytes() {
        val source = object : ByteArrayInputStream(ByteArray(73) { it.toByte() }) {
            var closes = 0
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                super.read(buffer, offset, minOf(length, 8))
            override fun close() { closes++ }
        }
        val stream = PausableDownloadInputStream(source)
        val output = ByteArrayOutputStream()
        val paused = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        var lastProgress = 0L
        val worker = Thread {
            try {
                DownloadStreamCopier.copy(stream, output) { bytes ->
                    lastProgress = bytes
                    check(stream.pause())
                    paused.countDown()
                }
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                stream.close()
                finished.countDown()
            }
        }
        worker.start()
        try {
            assertTrue(paused.await(1, TimeUnit.SECONDS))
            stream.cancel()
            assertTrue(finished.await(1, TimeUnit.SECONDS))
            assertTrue(failure.get() is IOException)
            assertEquals(8L, lastProgress)
            assertEquals(8, output.size())
            assertFalse(stream.resumeTransfer())
            assertEquals(1, source.closes)
        } finally {
            stream.cancel()
            worker.join(1_000)
        }
    }
}
