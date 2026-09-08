/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */
package org.navis.browser.engine

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import org.json.JSONArray
import org.json.JSONObject
import org.navis.browser.persistence.ClosedTabClaim
import org.navis.browser.persistence.ClosedTabRecord
import org.navis.browser.persistence.ClosedTabsStore

internal data class RestoredClosedTab(val tabId: Long, val windowId: Long)

/** A restore reservation spans the actual Runtime creation and durable record consumption. */
internal class AndroidExtensionSessionsAdapter(
    private val store: ClosedTabsStore,
    private val restore: (ClosedTabRecord) -> CompletionStage<RestoredClosedTab>,
    private val rollback: (Long) -> CompletionStage<Unit>,
    private val changed: () -> Unit,
    private val dispatch: (() -> Unit) -> Unit = mainDispatcher(),
) : AutoCloseable {
    private var closed = false
    private val pending = mutableSetOf<CompletableFuture<String>>()
    private val observer: () -> Unit = { dispatch { if (!closed) changed() } }
    init { store.observe(observer) }

    fun request(operation: String, arguments: String): CompletionStage<String> {
        val result = CompletableFuture<String>()
        if (closed) return result.also { it.completeExceptionally(IllegalStateException("Sessions adapter is closed")) }
        pending += result
        fun finish(value: String?, error: Throwable?) {
            if (!pending.remove(result)) return
            if (error == null) result.complete(checkNotNull(value)) else result.completeExceptionally(error)
        }
        try {
            val args = JSONObject(arguments)
            when (operation) {
                "sessions:list" -> store.list(args.optInt("maxResults", ClosedTabsStore.MAX_RECORDS)).whenComplete { records, error -> dispatch {
                    if (error != null) finish(null, error)
                    else finish(JSONArray().apply { records.forEach { put(summary(it)) } }.toString(), null)
                } }
                "sessions:forget" -> store.forget(args.getLong("windowId"), sessionId(args.getString("sessionId")))
                    .whenComplete { _, error -> dispatch { finish("null", error) } }
                "sessions:restore" -> {
                    val id = if (args.isNull("sessionId") || !args.has("sessionId")) null else sessionId(args.getString("sessionId"))
                    store.claim(id).whenComplete { claim, claimError -> dispatch {
                        if (claimError != null) { finish(null, claimError); return@dispatch }
                        if (closed || result.isCancelled) { store.release(claim); return@dispatch }
                        restoreClaim(claim, ::finish)
                    } }
                }
                else -> error("Unsupported sessions operation")
            }
        } catch (error: Throwable) { finish(null, error) }
        return result
    }

    private fun restoreClaim(claim: ClosedTabClaim, finish: (String?, Throwable?) -> Unit) {
        val creation = try { restore(claim.record) } catch (error: Throwable) {
            store.release(claim).whenComplete { _, _ -> dispatch { finish(null, error) } }
            return
        }
        creation.whenComplete { tab, createError -> dispatch {
            if (createError != null || tab == null || tab.tabId <= 0 || tab.windowId <= 0) {
                store.release(claim).whenComplete { _, _ -> dispatch {
                    finish(null, createError ?: IllegalStateException("Runtime did not create a live tab"))
                } }
                return@dispatch
            }
            if (closed) {
                rollbackAndRelease(tab.tabId, claim, IllegalStateException("Sessions adapter closed during restore"), finish)
                return@dispatch
            }
            store.consume(claim).whenComplete { _, consumeError -> dispatch {
                if (consumeError != null) rollbackAndRelease(tab.tabId, claim, consumeError, finish)
                else finish(JSONObject().put("tabId", tab.tabId).put("windowId", tab.windowId)
                    .put("lastModified", System.currentTimeMillis()).toString(), null)
            } }
        } }
    }

    private fun rollbackAndRelease(tabId: Long, claim: ClosedTabClaim, cause: Throwable, finish: (String?, Throwable?) -> Unit) {
        val rollbackResult = try { rollback(tabId) } catch (error: Throwable) {
            cause.addSuppressed(error)
            // A live replacement may remain: retain its claim until process exit
            // instead of permitting a second restore of the same record.
            finish(null, cause)
            return
        }
        rollbackResult.whenComplete { _, error ->
            if (error != null) {
                cause.addSuppressed(error)
                dispatch { finish(null, cause) }
                return@whenComplete
            }
            store.release(claim).whenComplete { _, releaseError -> dispatch {
                if (releaseError != null) cause.addSuppressed(releaseError)
                finish(null, cause)
            } }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        store.removeObserver(observer)
        pending.forEach { it.completeExceptionally(IllegalStateException("Sessions adapter closed")) }
        pending.clear()
    }

    companion object {
        private fun mainDispatcher(): (() -> Unit) -> Unit {
            val handler = Handler(Looper.getMainLooper())
            return { action -> if (Looper.myLooper() == Looper.getMainLooper()) action() else handler.post(action) }
        }
        private fun sessionId(value: String): String = value.also {
            require(it.matches(Regex("[A-Za-z0-9-]{1,128}"))) { "Invalid closed session ID" }
        }
        private fun summary(record: ClosedTabRecord): JSONObject = JSONObject()
            .put("sessionId", record.sessionId).put("windowId", record.windowId).put("index", record.index)
            .put("url", record.snapshot.uri).put("title", record.snapshot.title).put("closedAt", record.closedAt)
    }
}
