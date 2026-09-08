/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.engine.runtime

import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import org.mozilla.gecko.navis.NavisAndroidBrowsingData

/** Sole adapter between the private Gecko peer and the product data contract. */
internal class NavisAndroidBrowsingDataPort private constructor() :
    EngineBrowsingDataPort,
    NavisAndroidBrowsingData.Delegate {
    private val lock = Any()
    private var delegate: EngineProductDataDelegate? = null
    private var peer: NavisAndroidBrowsingData? = null
    private var closed = false

    override fun bind(delegate: EngineProductDataDelegate) {
        synchronized(lock) {
            check(!closed) { "Browsing-data port is closed" }
            check(this.delegate == null || this.delegate === delegate) {
                "Browsing-data port is already bound"
            }
            this.delegate = delegate
        }
    }

    override fun unbind(delegate: EngineProductDataDelegate) {
        synchronized(lock) {
            if (this.delegate === delegate) {
                this.delegate = null
            }
        }
    }

    override fun clear(
        request: NavisAndroidBrowsingData.Request,
    ): CompletionStage<Boolean> {
        val target = synchronized(lock) { delegate.takeUnless { closed } }
            ?: return failed("Navis product data cleaner is unavailable")
        val mapped = try {
            EngineProductDataClearRequest(
                extensionId = request.extensionId,
                dataType = EngineProductDataType.valueOf(
                    request.dataType.uppercase(Locale.ROOT),
                ),
                sinceUnixMillis = request.since,
                privateMode = request.privateMode,
            )
        } catch (error: Throwable) {
            return failed(error)
        }
        return try {
            target.clear(mapped)
        } catch (error: Throwable) {
            failed(error)
        }
    }

    override fun close() {
        val installed = synchronized(lock) {
            if (closed) return
            closed = true
            delegate = null
            peer.also { peer = null }
        }
        installed?.close()
    }

    companion object {
        fun install(): NavisAndroidBrowsingDataPort {
            val port = NavisAndroidBrowsingDataPort()
            port.peer = NavisAndroidBrowsingData.install(port)
            return port
        }

        private fun <T> failed(message: String): CompletionStage<T> =
            failed(IllegalStateException(message))

        private fun <T> failed(error: Throwable): CompletionStage<T> =
            CompletableFuture<T>().also { it.completeExceptionally(error) }
    }
}
