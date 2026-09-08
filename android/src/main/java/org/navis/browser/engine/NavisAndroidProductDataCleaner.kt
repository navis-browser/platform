/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.engine

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicBoolean
import org.navis.browser.engine.runtime.EngineProductDataClearRequest
import org.navis.browser.engine.runtime.EngineProductDataDelegate
import org.navis.browser.engine.runtime.EngineProductDataType
import org.navis.browser.persistence.BrowserProfileStore

/** Product-owned clearing seam consumed by the direct browsingData peer. */
internal interface NavisDownloadHistoryClearer {
    fun clearSince(sinceUnixMillis: Long): CompletionStage<Boolean>
}

internal class NavisAndroidProductDataCleaner(
    private val profileStore: BrowserProfileStore,
    private val downloadHistory: NavisDownloadHistoryClearer?,
) : EngineProductDataDelegate, AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun clear(request: EngineProductDataClearRequest): CompletionStage<Boolean> {
        if (closed.get()) return failed("Navis product data cleaner is closed")
        if (request.privateMode) {
            // Private sessions never write to the persistent product stores.
            return CompletableFuture.completedFuture(true)
        }
        return when (request.dataType) {
            EngineProductDataType.HISTORY -> clearHistory(request.sinceUnixMillis)
            EngineProductDataType.PASSWORDS -> clearPasswords(request.sinceUnixMillis)
            EngineProductDataType.DOWNLOADS -> downloadHistory?.clearSince(request.sinceUnixMillis)
                ?: failed("Navis download history cleaner is unavailable")
        }
    }

    private fun clearHistory(since: Long): CompletionStage<Boolean> =
        callbackStage { done -> profileStore.clearHistorySince(since, done) }

    private fun clearPasswords(since: Long): CompletionStage<Boolean> =
        callbackStage { done -> profileStore.clearPasswordsSince(since, done) }

    private fun callbackStage(
        operation: (((Result<Unit>) -> Unit) -> Unit),
    ): CompletionStage<Boolean> {
        val result = CompletableFuture<Boolean>()
        try {
            operation { completion ->
                completion.fold(
                    onSuccess = { result.complete(true) },
                    onFailure = result::completeExceptionally,
                )
            }
        } catch (error: Throwable) {
            result.completeExceptionally(error)
        }
        return result
    }

    private fun failed(message: String): CompletionStage<Boolean> {
        val result = CompletableFuture<Boolean>()
        result.completeExceptionally(IllegalStateException(message))
        return result
    }

    override fun close() {
        closed.set(true)
    }
}
