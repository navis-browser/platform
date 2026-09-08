/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import org.json.JSONObject
import org.navis.browser.engine.runtime.EngineRuntimePort
import org.navis.browser.engine.runtime.EngineSecurityState
import org.navis.browser.engine.runtime.EngineSessionObserver

/** A tool view owns an engine Session but is never a website tab or an extension target. */
internal class AndroidDevToolsHost(
    engine: EngineRuntimePort,
    sessionId: Long,
    targetSessionId: Long,
    privateMode: Boolean,
    initialTool: String? = null,
    private val onClosed: () -> Unit,
) : AutoCloseable {
    val ready = CompletableFuture<Boolean>()
    private var closed = false
    private var closing: CompletableFuture<Boolean>? = null
    private val views = linkedSetOf<AndroidBrowserView>()
    private val main = Handler(Looper.getMainLooper())
    private val session = engine.createSession(sessionId, object : EngineSessionObserver {
        override fun onNativeWindowReady() = refreshViews()
        override fun onHostReady() = Unit
        override fun onLocationChanged(uri: String, generation: Long, sameDocument: Boolean, errorPage: Boolean) = Unit
        override fun onTitleChanged(title: String) = Unit
        override fun onLoadStarted(generation: Long, uri: String) = Unit
        override fun onLoadCompleted(result: org.navis.browser.engine.runtime.EngineLoadResult) = Unit
        override fun onSessionStateChanged(serializedState: String) = Unit
        override fun onHistoryChanged(canGoBack: Boolean, canGoForward: Boolean) = Unit
        override fun onSecurityChanged(state: EngineSecurityState) = Unit
        override fun onFullscreenChanged(enabled: Boolean) = Unit
        override fun onContentProcessGone(crashed: Boolean) { ready.complete(false) }
        override fun onCompositorAttached() = refreshViews()
        override fun onCompositorDetached() = Unit
        override fun onNewSurfaceRequired() = refreshViews()
        override fun onSessionError(message: String) { ready.complete(false) }
        override fun onSessionClosed() { ready.complete(false) }
    })

    init {
        try {
            session.openDevTools(privateMode, targetSessionId, initialTool).whenComplete { accepted, error ->
                if (error == null) ready.complete(accepted == true)
                else ready.completeExceptionally(error)
            }
        } catch (error: Throwable) { ready.completeExceptionally(error) }
    }

    fun createView(context: Context): AndroidBrowserView = AndroidBrowserView(context).also {
        check(!closed)
        views += it
        it.show(session)
    }

    fun releaseView(view: AndroidBrowserView) {
        views -= view
        view.release()
    }

    private fun refreshViews() {
        main.post { if (!closed) views.forEach(AndroidBrowserView::refreshSurface) }
    }

    /** Fixed chrome-host operations; never arbitrary script or a global active tab. */
    fun viewport(command: String): CompletionStage<Boolean> {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (closed || closing != null || ready.isCompletedExceptionally || ready.getNow(false) != true ||
            command !in setOf("responsive-toggle", "zoom-in", "zoom-out", "reset-zoom")) {
            return CompletableFuture.completedFuture(false)
        }
        val result = CompletableFuture<Boolean>()
        try {
            session.querySession("devtools:viewport", JSONObject().put("command", command).toString())
                .whenComplete { value, error -> main.post {
                    val accepted = error == null && !closed && runCatching {
                        val reply = JSONObject(value)
                        val zoom = reply.getDouble("zoom")
                        zoom.isFinite() && zoom in 0.25..2.0 &&
                            (command != "responsive-toggle" || reply.get("responsive") is Boolean)
                    }.getOrDefault(false)
                    result.complete(accepted)
                } }
        } catch (_: Throwable) { result.complete(false) }
        return result
    }

    /** Keep both live views until Gecko has actually removed viewport emulation. */
    fun closeAfterRestoring(): CompletionStage<Boolean> {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (closed) return CompletableFuture.completedFuture(true)
        closing?.let { return it }
        val result = CompletableFuture<Boolean>()
        closing = result
        try {
            session.querySession("devtools:close").whenComplete { value, error -> main.post {
                val restored = error == null && runCatching {
                    JSONObject(value).get("restored") == true
                }.getOrDefault(false)
                if (restored) destroySession()
                if (closing === result) closing = null
                result.complete(restored)
            } }
        } catch (_: Throwable) { closing = null; result.complete(false) }
        return result
    }

    private fun destroySession() {
        if (closed) return
        closed = true
        views.toList().forEach(::releaseView)
        try { session.close() } finally { onClosed() }
    }

    override fun close() {
        if (closed) return
        // A removed Activity cannot retain native view/input ownership. The
        // normal Back path waits above; disposal also asks Gecko to restore,
        // then closes the host even if its already-gone target rejects it.
        closeAfterRestoring().whenComplete { _, _ -> main.post(::destroySession) }
    }
}
