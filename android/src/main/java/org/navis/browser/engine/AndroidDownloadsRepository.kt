/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.engine

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicBoolean
import org.navis.browser.downloads.DownloadManager
import org.navis.browser.downloads.DownloadObserver

/** Observes the existing history; no second set of download records or disk state. */
internal class AndroidDownloadsRepository(
    private val coordinator: AndroidExtensionDownloadCoordinator,
) : DownloadManager {
    private val handler = Handler(Looper.getMainLooper())
    private val observers = linkedMapOf<DownloadObserver, Boolean>()
    private val refreshQueued = AtomicBoolean(false)
    private val onChanged: () -> Unit = { notifyObservers() }

    override fun find(id: Long, privateMode: Boolean) =
        coordinator.productEntries().firstOrNull { it.id == id && it.privateMode == privateMode }

    override fun addObserver(privateMode: Boolean, observer: DownloadObserver) {
        synchronized(observers) {
            if (observers.isEmpty()) coordinator.addProductObserver(onChanged)
            observers[observer] = privateMode
        }
        notifyObservers()
        refresh()
    }

    override fun removeObserver(observer: DownloadObserver) {
        synchronized(observers) {
            observers.remove(observer)
            if (observers.isEmpty()) coordinator.removeProductObserver(onChanged)
        }
    }

    override fun refresh() {
        // Probe completed files off the UI thread through the existing coordinator executor.
        coordinator.snapshots(privateBrowsingAllowed = true).whenComplete { _, _ ->
            notifyObservers()
        }
    }

    override fun cancel(id: Long, privateMode: Boolean): CompletionStage<Unit> =
        action(id, privateMode) { coordinator.cancel(id, privateMode) }

    override fun pause(id: Long, privateMode: Boolean): CompletionStage<Unit> =
        action(id, privateMode) { coordinator.pause(id, privateMode) }

    override fun resume(id: Long, privateMode: Boolean): CompletionStage<Unit> =
        action(id, privateMode) { coordinator.resume(id, privateMode) }

    override fun retry(id: Long, privateMode: Boolean): CompletionStage<Unit> =
        coordinator.retryProductDownload(id, privateMode)

    override fun remove(id: Long, privateMode: Boolean): CompletionStage<Unit> =
        action(id, privateMode) { coordinator.erase(longArrayOf(id), privateMode).thenApply { Unit } }

    override fun clearFinished(privateMode: Boolean): CompletionStage<Unit> {
        val ids = coordinator.productEntries()
            .filter { it.privateMode == privateMode && !it.active }
            .map { it.id }.toLongArray()
        return coordinator.erase(ids, privateMode).thenApply { Unit }
    }

    override fun open(id: Long, privateMode: Boolean): CompletionStage<Unit> =
        action(id, privateMode) { coordinator.open(id, privateMode) }.whenComplete { _, _ -> refresh() }

    private fun action(
        id: Long,
        privateMode: Boolean,
        operation: () -> CompletionStage<Unit>,
    ): CompletionStage<Unit> = try {
        coordinator.requireProductRecord(id, privateMode)
        operation()
    } catch (error: Throwable) {
        CompletableFuture<Unit>().also { it.completeExceptionally(error) }
    }

    private fun notifyObservers() {
        if (!refreshQueued.compareAndSet(false, true)) return
        handler.post {
            refreshQueued.set(false)
            val entries = coordinator.productEntries()
            synchronized(observers) { observers.toMap() }.forEach { (observer, privateMode) ->
                observer.onDownloadsChanged(entries.filter { it.privateMode == privateMode })
            }
        }
    }
}
