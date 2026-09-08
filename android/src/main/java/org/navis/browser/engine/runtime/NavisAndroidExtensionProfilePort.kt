/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */
package org.navis.browser.engine.runtime

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import org.mozilla.gecko.navis.NavisAndroidExtensionProfile

internal class NavisAndroidExtensionProfilePort private constructor() :
    EngineExtensionProfilePort, NavisAndroidExtensionProfile.Delegate {
    private var delegate: EngineExtensionProfileDelegate? = null
    private var peer: NavisAndroidExtensionProfile? = null
    private var closed = false

    override fun bind(delegate: EngineExtensionProfileDelegate) {
        check(!closed && (this.delegate == null || this.delegate === delegate))
        this.delegate = delegate
    }
    override fun unbind(delegate: EngineExtensionProfileDelegate) {
        if (this.delegate === delegate) this.delegate = null
    }
    override fun request(request: NavisAndroidExtensionProfile.Request): CompletionStage<String> = try {
        checkNotNull(delegate.takeUnless { closed }) { "Extension profile service is unavailable" }
            .request(EngineExtensionProfileRequest(request.extensionId, request.operation,
                request.arguments, request.privateMode, request.privateBrowsingAllowed))
    } catch (error: Throwable) {
        CompletableFuture<String>().also { it.completeExceptionally(error) }
    }
    override fun publishBookmarkChange(json: String) { if (!closed) peer?.publishBookmarkChange(json) }
    override fun publishHistoryChange(json: String) { if (!closed) peer?.publishHistoryChange(json) }
    override fun publishSessionsChange() { if (!closed) peer?.publishSessionsChange() }
    override fun close() {
        if (closed) return
        closed = true
        delegate = null
        peer?.close()
        peer = null
    }
    companion object {
        fun install(): NavisAndroidExtensionProfilePort = NavisAndroidExtensionProfilePort().also {
            it.peer = NavisAndroidExtensionProfile.install(it)
        }
    }
}
