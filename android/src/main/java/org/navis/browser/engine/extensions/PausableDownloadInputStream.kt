/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.engine.extensions

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** A bounded-memory pause gate around the single response stream already fetched by Gecko. */
internal class PausableDownloadInputStream(input: InputStream) : FilterInputStream(input) {
    private val gate = ReentrantLock()
    private val resumed = gate.newCondition()
    private var paused = false
    private var cancelled = false
    private var streamClosed = false

    fun pause(): Boolean = gate.withLock {
        if (cancelled || streamClosed || paused) {
            false
        } else {
            paused = true
            true
        }
    }

    fun resumeTransfer(): Boolean = gate.withLock {
        if (cancelled || streamClosed || !paused) {
            false
        } else {
            paused = false
            resumed.signalAll()
            true
        }
    }

    fun cancel() {
        gate.withLock {
            if (cancelled) {
                return
            }
            cancelled = true
            paused = false
            resumed.signalAll()
        }
        close()
    }

    override fun read(): Int {
        awaitReadable()
        return super.read()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        awaitReadable()
        return super.read(buffer, offset, length)
    }

    override fun skip(byteCount: Long): Long {
        awaitReadable()
        return super.skip(byteCount)
    }

    override fun close() {
        val shouldClose = gate.withLock {
            if (streamClosed) {
                false
            } else {
                streamClosed = true
                paused = false
                resumed.signalAll()
                true
            }
        }
        if (shouldClose) {
            super.close()
        }
    }

    private fun awaitReadable() {
        gate.withLock {
            while (paused && !cancelled && !streamClosed) {
                try {
                    resumed.await()
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("Download transfer was interrupted", error)
                }
            }
            if (cancelled) {
                throw IOException("Download transfer was cancelled")
            }
            if (streamClosed) {
                throw IOException("Download response stream is closed")
            }
        }
    }
}
