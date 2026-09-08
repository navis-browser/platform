/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.engine

import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/** Drains accepted download-history and private-file cleanup work before profile shutdown. */
internal class DownloadHistoryQueue : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "NavisExtensionDownloadHistory").apply { isDaemon = true }
    }
    private val admission = Any()
    private var closed = false
    val closedCompletion = CompletableFuture<Unit>()

    fun execute(task: () -> Unit) = synchronized(admission) {
        if (closed) throw RejectedExecutionException("Download history queue is closed")
        executor.execute(task)
    }

    override fun close() = synchronized(admission) {
        if (closed) return@synchronized
        closed = true
        executor.execute { closedCompletion.complete(Unit) }
        executor.shutdown()
    }

    /** Constructor rollback only; a normally running profile always drains with close(). */
    fun shutdownNow() = synchronized(admission) {
        if (closed) return@synchronized
        closed = true
        executor.shutdownNow()
        closedCompletion.completeExceptionally(IllegalStateException("Download history initialization failed"))
    }
}
