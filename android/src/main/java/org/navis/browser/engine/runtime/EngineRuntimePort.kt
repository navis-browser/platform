/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.engine.runtime

import android.os.Handler
import android.view.DragEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import org.navis.browser.engine.extensions.EngineExtensionPort
import org.navis.browser.engine.extensions.EngineExtensionDownloadPort
import org.navis.browser.engine.AndroidContextMenuRequest

/** Engine capabilities visible to the Android product adapter. */
internal enum class EngineProjection {
    NAVIGATION,
    COMPOSITOR,
    SESSION_STATE,
    HISTORY_VISITS,
    TARGET_PROMPTS,
    SITE_PERMISSIONS,
    PLATFORM_PERMISSIONS,
    FILE_PICKER,
    DOWNLOADS,
    LOGIN_STORAGE,
    EXTENSIONS,
    ACCESSIBILITY,
    INPUT_METHOD,
    ASYNC_PAN_ZOOM,
    FULLSCREEN,
    NEW_WINDOW,
    CONTENT_CRASH,
    MEDIA_CAPTURE,
    PLATFORM_WEB_AUTHN,
    DEVELOPER_SETTINGS,
    IN_APP_DEVTOOLS,
}

internal enum class ProjectionAvailability {
    AVAILABLE,
    UNSUPPORTED_NATIVE_PROJECTION,
}

/**
 * One authoritative capability matrix for the direct Android runtime slice.
 *
 * Keeping unsupported projections explicit prevents product code from
 * silently reaching back to a second embedding owner when a native projection
 * has not landed yet.
 */
internal class EngineProjectionMatrix private constructor(
    private val values: Map<EngineProjection, ProjectionAvailability>,
) {
    fun availability(projection: EngineProjection): ProjectionAvailability =
        checkNotNull(values[projection]) { "Missing engine projection $projection" }

    val available: Set<EngineProjection>
        get() = values.filterValues { it == ProjectionAvailability.AVAILABLE }.keys

    val unsupported: Set<EngineProjection>
        get() = values
            .filterValues { it == ProjectionAvailability.UNSUPPORTED_NATIVE_PROJECTION }
            .keys

    companion object {
        val DIRECT_RUNTIME_V1: EngineProjectionMatrix = fromAvailable(
            EngineProjection.NAVIGATION,
            EngineProjection.COMPOSITOR,
            EngineProjection.SESSION_STATE,
            EngineProjection.HISTORY_VISITS,
            EngineProjection.TARGET_PROMPTS,
            EngineProjection.SITE_PERMISSIONS,
            EngineProjection.PLATFORM_PERMISSIONS,
            EngineProjection.FILE_PICKER,
            EngineProjection.DOWNLOADS,
            EngineProjection.LOGIN_STORAGE,
            EngineProjection.EXTENSIONS,
            EngineProjection.ACCESSIBILITY,
            EngineProjection.INPUT_METHOD,
            EngineProjection.ASYNC_PAN_ZOOM,
            EngineProjection.FULLSCREEN,
            EngineProjection.NEW_WINDOW,
            EngineProjection.CONTENT_CRASH,
            EngineProjection.MEDIA_CAPTURE,
            EngineProjection.PLATFORM_WEB_AUTHN,
            EngineProjection.DEVELOPER_SETTINGS,
            EngineProjection.IN_APP_DEVTOOLS,
        )

        fun fromAvailable(vararg projections: EngineProjection): EngineProjectionMatrix {
            val available = projections.toSet()
            return EngineProjectionMatrix(
                EngineProjection.entries.associateWith { projection ->
                    if (projection in available) {
                        ProjectionAvailability.AVAILABLE
                    } else {
                        ProjectionAvailability.UNSUPPORTED_NATIVE_PROJECTION
                    }
                },
            )
        }
    }
}

internal enum class EngineSecurityState {
    UNKNOWN,
    INSECURE,
    BROKEN,
    SECURE,
}

internal interface EngineSessionObserver {
    fun onNativeWindowReady()

    fun onHostReady()

    fun onLocationChanged(uri: String, generation: Long, sameDocument: Boolean, errorPage: Boolean)

    fun onTitleChanged(title: String)

    fun onTitleChanged(title: String, uri: String, generation: Long) = onTitleChanged(title)

    fun onFaviconChanged(pageUri: String, png: String) {}

    fun onLoadStarted(generation: Long, uri: String)

    /** One terminal top-level network result after Gecko resolves request status. */
    fun onLoadCompleted(result: EngineLoadResult)

    fun onSessionStateChanged(serializedState: String)

    fun onHistoryChanged(canGoBack: Boolean, canGoForward: Boolean)

    fun onSecurityChanged(state: EngineSecurityState)

    fun onFullscreenChanged(enabled: Boolean)

    fun onContextMenuRequested(request: AndroidContextMenuRequest) {}

    /** Same-token menu update from an onShown listener; does not complete the request. */
    fun onContextMenuUpdated(request: AndroidContextMenuRequest) {}

    fun onShortcutSettingsRequested(extensionId: String) {}

    fun onCommandOwnershipChanged(shortcuts: List<String>) {}

    fun onContentProcessGone(crashed: Boolean)

    fun onNewWindowRequested(
        uri: String,
        engineWindowToken: String,
        privateMode: Boolean,
    ): CompletionStage<Boolean> = CompletableFuture.completedFuture(false)

    fun onCompositorAttached()

    fun onCompositorDetached()

    fun onNewSurfaceRequired()

    fun onSessionError(message: String)

    fun onSessionClosed()
}

/** Product-owned session contract; no historical embedding owner escapes it. */
internal interface EngineSessionPort : AutoCloseable {
    val isOpen: Boolean

    fun open(privateMode: Boolean, restoredState: String?)

    /** Opens a dedicated tool surface; null restores Gecko's last selected tool. */
    fun openDevTools(
        privateMode: Boolean,
        targetSessionId: Long,
        tool: String? = null,
    ): CompletionStage<Boolean> = CompletableFuture<Boolean>().apply {
        completeExceptionally(UnsupportedOperationException("In-app developer tools are unavailable"))
    }

    /** Fixed product operations only; this transport does not evaluate supplied JavaScript. */
    fun querySession(operation: String, payload: String = "{}"): CompletionStage<String> =
        CompletableFuture<String>().apply {
            completeExceptionally(UnsupportedOperationException("Session queries are unavailable"))
        }

    fun flushSessionState()

    fun loadUri(uri: String)

    /** Replays only an engine-approved bodyless HTTP(S) GET in its original context. */
    fun retryDownload(uri: String, referrer: String?): CompletionStage<Boolean> =
        CompletableFuture<Boolean>().apply {
            completeExceptionally(UnsupportedOperationException("Download retry is unavailable"))
        }

    fun reload()

    fun stop()

    fun goBack()

    fun goForward()

    /** Publishes product tab activation to Gecko's extension tab tracker. */
    fun setActive(active: Boolean)

    /** Opens an unopened child against one opaque engine-issued handoff token. */
    fun openNewWindow(
        privateMode: Boolean,
        engineWindowToken: String,
    ): CompletionStage<Boolean>

    fun exitFullscreen()

    fun respondToContextMenu(token: String, itemId: String?): Boolean

    /** Sends a physical keyboard shortcut to Gecko's direct command registry. */
    fun dispatchHardwareShortcut(shortcut: String)

    fun isHardwareShortcutOwned(shortcut: String): Boolean

    fun attachSurface(
        displayId: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        surface: Any,
    )

    fun detachSurface()

    fun updateBounds(left: Int, top: Int, width: Int, height: Int)

    /** Installs the product-owned input target and enclosing accessibility host. */
    fun attachInputView(inputView: View, accessibilityView: View)

    /** Removes platform delegates before the View is rebound to another session. */
    fun detachInputView()

    fun inputConnectionHandler(defaultHandler: Handler?): Handler?

    fun createInputConnection(attributes: EditorInfo): InputConnection?

    fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean

    fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean

    fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean

    fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean

    fun onKeyMultiple(keyCode: Int, repeatCount: Int, event: KeyEvent): Boolean

    fun onTouchEvent(event: MotionEvent): Boolean

    fun onGenericMotionEvent(event: MotionEvent): Boolean

    fun onDragEvent(event: DragEvent): Boolean
}

/** Process-wide direct runtime contract used by Android product code. */
internal interface EngineRuntimePort : AutoCloseable {
    /** Completes after the engine thread has actually exited, not merely accepted shutdown. */
    fun awaitStopped(): CompletionStage<Unit> = CompletableFuture<Unit>().apply {
        completeExceptionally(UnsupportedOperationException("Engine shutdown acknowledgement unavailable"))
    }

    val projections: EngineProjectionMatrix

    /** Native request seams are owned by the same Runtime as the engine Session. */
    val targetPort: EngineTargetPort?
        get() = null

    val downloadPort: EngineDownloadPort?
        get() = null

    val loginStoragePort: EngineLoginStoragePort?
        get() = null

    val webAuthnPort: EngineWebAuthnPort?
        get() = null

    /**
     * Process-wide WebExtensions service owned by this Runtime. Platform code
     * must never install a second engine peer or reach AddonManager directly.
     */
    val extensionPort: EngineExtensionPort?
        get() = null

    val extensionDownloadPort: EngineExtensionDownloadPort?
        get() = null

    val browsingDataPort: EngineBrowsingDataPort?
        get() = null

    val extensionProfilePort: EngineExtensionProfilePort?
        get() = null

    fun createSession(sessionId: Long, observer: EngineSessionObserver): EngineSessionPort

}
