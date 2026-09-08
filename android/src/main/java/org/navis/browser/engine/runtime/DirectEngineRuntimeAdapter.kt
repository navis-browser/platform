/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.engine.runtime

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.DragEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.gecko.GeckoAppShell
import org.mozilla.gecko.GeckoThread
import org.mozilla.gecko.navis.NavisAndroidRuntime
import org.mozilla.gecko.navis.NavisAndroidSession
import org.navis.browser.engine.NavisExternalDownloadBridge
import org.navis.browser.engine.AndroidContextMenuItem
import org.navis.browser.engine.AndroidContextMenuPageAction
import org.navis.browser.engine.AndroidContextMenuRequest
import org.navis.browser.api.BrowserPrompt
import org.navis.browser.api.ChoicePromptMode
import org.navis.browser.api.ChoicePromptOption
import org.navis.browser.api.DateTimePromptKind
import org.navis.browser.api.FileCapture
import org.navis.browser.api.FilePickerMode
import org.navis.browser.api.FilePickerRequest
import org.navis.browser.api.LoginPromptOption
import org.navis.browser.api.MediaSourceDescriptor
import org.navis.browser.api.PlatformPermission
import org.navis.browser.api.PlatformPermissionRequest
import org.navis.browser.api.PromptResponse
import org.navis.browser.api.SitePermissionKind
import org.navis.browser.api.SitePermissionRequest
import org.navis.browser.api.SitePermissionDecision
import org.navis.browser.api.TargetRequestId
import org.navis.browser.api.SessionId
import org.navis.browser.engine.extensions.EngineExtensionPort
import org.navis.browser.engine.extensions.EngineExtensionDownloadPort
import org.navis.browser.engine.extensions.NavisAndroidExtensionPort
import org.navis.browser.engine.extensions.NavisAndroidExtensionDownloadPort

/**
 * The only product-side adapter allowed to know the engine-private Java seam.
 * All callers use [EngineRuntimePort] and [EngineSessionPort].
 */
internal class DirectEngineRuntimeAdapter private constructor(
    private val runtimePeer: NavisAndroidRuntime,
    override val extensionPort: EngineExtensionPort,
    override val extensionDownloadPort: EngineExtensionDownloadPort,
    override val browsingDataPort: EngineBrowsingDataPort,
    override val loginStoragePort: EngineLoginStoragePort,
    override val extensionProfilePort: EngineExtensionProfilePort,
) : EngineRuntimePort, EngineTargetPort, EngineDownloadPort {
    private val sessions = linkedSetOf<DirectEngineSessionAdapter>()
    private val externalDownloadBridge = NavisExternalDownloadBridge(::deliverExternalDownload)
    private var closed = false

    override val projections: EngineProjectionMatrix = EngineProjectionMatrix.DIRECT_RUNTIME_V1
    override val targetPort: EngineTargetPort = this
    override val downloadPort: EngineDownloadPort = this
    override val webAuthnPort: EngineWebAuthnPort = object : EngineWebAuthnPort {
        override fun bindActivity(activity: Activity, lease: org.navis.browser.engine.AndroidWindowLease) =
            runtimePeer.bindWebAuthnActivity(activity, lease.windowId, lease.generation)
        override fun unbindActivity(lease: org.navis.browser.engine.AndroidWindowLease) =
            runtimePeer.unbindWebAuthnActivity(lease.windowId, lease.generation)
        override fun setSessionWindow(sessionId: org.navis.browser.api.SessionId, windowId: Long?) =
            runtimePeer.setWebAuthnSessionWindow(sessionId.value, windowId ?: 0L)
        override fun availability(windowId: Long?): EngineWebAuthnAvailability = runCatching {
            EngineWebAuthnAvailability.valueOf(runtimePeer.webAuthnAvailability(windowId ?: 0L))
        }.getOrDefault(EngineWebAuthnAvailability.SYSTEM_UNAVAILABLE)
    }
    private var targetObserver: EngineTargetObserver? = null
    private var downloadObserver: EngineDownloadObserver? = null
    private val targetRequests = linkedMapOf<Long, DirectEngineSessionAdapter>()
    private val loginSaveRequests = mutableSetOf<Long>()

    override fun bind(observer: EngineTargetObserver) {
        check(targetObserver == null || targetObserver === observer) { "Target port already bound" }
        targetObserver = observer
    }

    override fun unbind(observer: EngineTargetObserver) {
        if (targetObserver === observer) targetObserver = null
    }

    override fun bind(observer: EngineDownloadObserver) {
        check(downloadObserver == null || downloadObserver === observer) { "Download port already bound" }
        downloadObserver = observer
    }

    override fun unbind(observer: EngineDownloadObserver) {
        if (downloadObserver === observer) downloadObserver = null
    }

    override fun respondToPrompt(id: TargetRequestId, response: PromptResponse) {
        if (id.value in loginSaveRequests && response is PromptResponse.SelectOption) {
            targetRequests[id.value]?.respondPrompt(id.value, response)
        } else {
            loginSaveRequests.remove(id.value)
            targetRequests.remove(id.value)?.respondPrompt(id.value, response)
        }
    }

    override fun notifySitePermissionShown(id: TargetRequestId) {
        targetRequests[id.value]?.notifySitePermissionShown(id.value)
    }

    override fun respondToSitePermission(id: TargetRequestId, allow: Boolean) {
        respondToSitePermission(id, if (allow) SitePermissionDecision.ALLOW_SESSION else SitePermissionDecision.DISMISS)
    }

    override fun respondToSitePermission(id: TargetRequestId, decision: SitePermissionDecision) {
        targetRequests.remove(id.value)?.respondPermission(id.value, decision)
    }

    override fun respondToPlatformPermission(id: TargetRequestId, granted: Boolean) {
        targetRequests.remove(id.value)?.respondBoolean(id.value, granted)
    }

    override fun respondToFilePicker(id: TargetRequestId, uris: List<String>) {
        targetRequests.remove(id.value)?.respondUris(id.value, uris)
    }

    override fun cancelRequest(id: TargetRequestId) {
        loginSaveRequests.remove(id.value)
        targetRequests.remove(id.value)?.cancelTarget(id.value)
    }

    override fun exitFullscreen(sessionId: SessionId) {
        sessions.firstOrNull { it.sessionId == sessionId.value }?.exitFullscreen()
    }

    private fun registerTarget(id: Long, session: DirectEngineSessionAdapter) {
        if (id > 0L) targetRequests[id] = session
    }

    private fun deliverExternalDownload(response: EngineDownloadResponse) {
        if (
            closed ||
            sessions.none { it.sessionId == response.sessionId.value && it.isOpen }
        ) {
            response.close()
            return
        }
        val observer = downloadObserver
        if (observer == null) {
            response.close()
        } else {
            runCatching { observer.onDownload(response) }
                .onFailure { response.close(); throw it }
        }
    }

    private fun onExternalResponse(
        sessionId: Long,
        privateMode: Boolean,
        response: org.mozilla.geckoview.WebResponse?,
    ) {
        externalDownloadBridge.accept(sessionId, privateMode, response)
    }

    override fun createSession(
        sessionId: Long,
        observer: EngineSessionObserver,
    ): EngineSessionPort {
        check(!closed) { "Navis Android engine runtime is closed" }
        return DirectEngineSessionAdapter(
            sessionId,
            observer,
            { sessions -= it },
            ::registerTarget,
        )
            .also(sessions::add)
    }

    override fun awaitStopped(): CompletionStage<Unit> {
        val result = CompletableFuture<Unit>()
        if (GeckoThread.isState(GeckoThread.State.EXITED)) return result.apply { complete(Unit) }
        val events = org.mozilla.gecko.EventDispatcher.getInstance()
        val listener = object : org.mozilla.gecko.util.BundleEventListener {
            override fun handleMessage(event: String, message: org.mozilla.gecko.util.GeckoBundle,
                callback: org.mozilla.gecko.util.EventCallback?) {
                if (result.complete(Unit)) events.unregisterUiThreadListener(this, "Gecko:Exited")
            }
        }
        events.registerUiThreadListener(listener, "Gecko:Exited")
        if (GeckoThread.isState(GeckoThread.State.EXITED) && result.complete(Unit)) {
            events.unregisterUiThreadListener(listener, "Gecko:Exited")
        }
        return result
    }

    override fun close() {
        if (closed) {
            return
        }
        closed = true
        val failures = mutableListOf<Throwable>()
        sessions.toList().forEach { session ->
            runCatching(session::close).exceptionOrNull()?.let(failures::add)
        }
        sessions.clear()
        targetRequests.clear()
        loginSaveRequests.clear()
        runCatching(loginStoragePort::close).exceptionOrNull()?.let(failures::add)
        runCatching(extensionProfilePort::close).exceptionOrNull()?.let(failures::add)
        runCatching(browsingDataPort::close).exceptionOrNull()?.let(failures::add)
        runCatching(extensionDownloadPort::close).exceptionOrNull()?.let(failures::add)
        runCatching(extensionPort::close).exceptionOrNull()?.let(failures::add)
        runCatching(runtimePeer::uninstall).exceptionOrNull()?.let(failures::add)
        runCatching(GeckoThread::forceQuit).exceptionOrNull()?.let(failures::add)
        failures.firstOrNull()?.let { first ->
            failures.drop(1).forEach(first::addSuppressed)
            throw first
        }
    }

    private inner class DirectEngineSessionAdapter(
        val sessionId: Long,
        private val observer: EngineSessionObserver,
        private val onClosed: (DirectEngineSessionAdapter) -> Unit,
        private val registerTarget: (Long, DirectEngineSessionAdapter) -> Unit,
    ) : EngineSessionPort, NavisAndroidSession.Delegate {
        private val peer = NavisAndroidSession(sessionId, this)
        private var open = false
        private var closed = false
        private var closeDelivered = false

        override val isOpen: Boolean
            get() = open && !closed

        override fun open(privateMode: Boolean, restoredState: String?) {
            check(!open && !closed) { "Navis Android engine session is already open or closed" }
            open = true
            try {
                peer.open(privateMode, restoredState)
            } catch (error: Throwable) {
                open = false
                throw error
            }
        }

        override fun loadUri(uri: String) {
            peer.loadUri(uri)
        }

        override fun retryDownload(uri: String, referrer: String?): CompletionStage<Boolean> =
            peer.retryDownload(uri, referrer)

        override fun openDevTools(
            privateMode: Boolean,
            targetSessionId: Long,
            tool: String?,
        ): CompletionStage<Boolean> {
            check(!open && !closed) { "Developer tools session is already open or closed" }
            check(sessions.any { it.sessionId == targetSessionId && it.isOpen }) {
                "Developer tools target session is unavailable"
            }
            open = true
            return try {
                // Window names require a bounded token; keep this sentinel private
                // to the transport. The JS host turns it back into undefined.
                peer.openDevToolsWindow(privateMode, targetSessionId, tool ?: "default")
            } catch (error: Throwable) {
                open = false
                throw error
            }
        }

        override fun querySession(operation: String, payload: String): CompletionStage<String> =
            peer.querySession(operation, payload)

        override fun flushSessionState() = peer.flushSessionState()

        override fun reload() = peer.reload()

        override fun stop() = peer.stop()

        override fun goBack() = peer.goBack()

        override fun goForward() = peer.goForward()

        override fun setActive(active: Boolean) {
            if (isOpen) {
                peer.setActive(active)
            }
        }

        override fun openNewWindow(
            privateMode: Boolean,
            engineWindowToken: String,
        ): CompletionStage<Boolean> {
            check(!open && !closed) { "Navis Android engine session is already open or closed" }
            open = true
            return try {
                peer.openNewWindow(privateMode, engineWindowToken)
            } catch (error: Throwable) {
                open = false
                throw error
            }
        }

        override fun exitFullscreen() {
            if (isOpen) {
                peer.exitFullscreen()
            }
        }

        override fun respondToContextMenu(token: String, itemId: String?): Boolean =
            if (itemId == null) peer.cancelContextMenu(token)
            else peer.respondToContextMenu(token, itemId)

        override fun dispatchHardwareShortcut(shortcut: String) {
            if (isOpen && shortcut.isNotBlank()) peer.dispatchHardwareShortcut(shortcut)
        }

        override fun isHardwareShortcutOwned(shortcut: String): Boolean =
            isOpen && peer.isHardwareShortcutOwned(shortcut)

        override fun attachSurface(
            displayId: Int,
            x: Int,
            y: Int,
            width: Int,
            height: Int,
            surface: Any,
        ) {
            if (!isOpen) {
                return
            }
            GeckoAppShell.setDisplayId(displayId)
            peer.onSurfaceChanged(x, y, width, height, surface)
        }

        override fun detachSurface() {
            if (isOpen) {
                peer.onSurfaceDestroyed()
            }
        }

        override fun updateBounds(left: Int, top: Int, width: Int, height: Int) {
            if (isOpen) {
                peer.onBoundsChanged(left, top, width, height)
            }
        }

        override fun attachInputView(inputView: View, accessibilityView: View) {
            if (isOpen) {
                peer.attachInputView(inputView, accessibilityView)
            }
        }

        override fun detachInputView() {
            if (isOpen) {
                peer.detachInputView()
            }
        }

        override fun inputConnectionHandler(defaultHandler: Handler?): Handler? =
            if (isOpen) peer.getInputConnectionHandler(defaultHandler) else defaultHandler

        override fun createInputConnection(attributes: EditorInfo): InputConnection? =
            if (isOpen) peer.onCreateInputConnection(attributes) else null

        override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean =
            isOpen && peer.onKeyPreIme(keyCode, event)

        override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
            isOpen && peer.onKeyDown(keyCode, event)

        override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
            isOpen && peer.onKeyUp(keyCode, event)

        override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean =
            isOpen && peer.onKeyLongPress(keyCode, event)

        override fun onKeyMultiple(
            keyCode: Int,
            repeatCount: Int,
            event: KeyEvent,
        ): Boolean = isOpen && peer.onKeyMultiple(keyCode, repeatCount, event)

        override fun onTouchEvent(event: MotionEvent): Boolean =
            isOpen && peer.onTouchEvent(event)

        override fun onGenericMotionEvent(event: MotionEvent): Boolean =
            isOpen && peer.onGenericMotionEvent(event)

        override fun onDragEvent(event: DragEvent): Boolean =
            isOpen && peer.onDragEvent(event)

        override fun close() {
            if (closed) {
                return
            }
            closed = true
            try {
                peer.close()
            } finally {
                deliverClosed()
            }
        }

        override fun onNativeWindowReady(sessionId: Long) {
            if (accepts(sessionId)) observer.onNativeWindowReady()
        }

        override fun onHostReady(sessionId: Long) {
            if (accepts(sessionId)) observer.onHostReady()
        }

        override fun onLocationChanged(
            sessionId: Long, uri: String, generation: Long, sameDocument: Boolean, errorPage: Boolean,
        ) {
            if (accepts(sessionId)) observer.onLocationChanged(uri, generation, sameDocument, errorPage)
        }

        override fun onTitleChanged(sessionId: Long, title: String) {
            if (accepts(sessionId)) observer.onTitleChanged(title)
        }

        override fun onTitleChanged(sessionId: Long, title: String, uri: String, generation: Long) {
            if (accepts(sessionId)) observer.onTitleChanged(title, uri, generation)
        }

        override fun onFaviconChanged(sessionId: Long, pageUri: String, png: String) {
            if (accepts(sessionId)) observer.onFaviconChanged(pageUri, png)
        }

        override fun onLoadStarted(sessionId: Long, generation: Long, uri: String) {
            if (accepts(sessionId)) observer.onLoadStarted(generation, uri)
        }

        override fun onLoadCompleted(
            sessionId: Long, generation: Long, status: Int, statusKnown: Boolean,
            cancelled: Boolean, errorPage: Boolean, uri: String,
        ) {
            if (accepts(sessionId)) observer.onLoadCompleted(
                EngineLoadResult(generation, status.takeIf { statusKnown }, cancelled, errorPage, uri),
            )
        }

        override fun onSessionStateChanged(sessionId: Long, serializedState: String) {
            if (accepts(sessionId)) observer.onSessionStateChanged(serializedState)
        }

        override fun onHistoryChanged(
            sessionId: Long,
            canGoBack: Boolean,
            canGoForward: Boolean,
        ) {
            if (accepts(sessionId)) observer.onHistoryChanged(canGoBack, canGoForward)
        }

        override fun onSecurityChanged(sessionId: Long, securityState: Int) {
            if (!accepts(sessionId)) {
                return
            }
            observer.onSecurityChanged(
                when (securityState) {
                    NavisAndroidSession.SECURITY_INSECURE -> EngineSecurityState.INSECURE
                    NavisAndroidSession.SECURITY_BROKEN -> EngineSecurityState.BROKEN
                    NavisAndroidSession.SECURITY_SECURE -> EngineSecurityState.SECURE
                    else -> EngineSecurityState.UNKNOWN
                },
            )
        }

        override fun onFullscreenChanged(sessionId: Long, enabled: Boolean) {
            if (accepts(sessionId)) observer.onFullscreenChanged(enabled)
        }

        override fun onContextMenuRequested(
            sessionId: Long,
            request: org.mozilla.gecko.navis.NavisAndroidContextMenu,
        ) {
            if (!accepts(sessionId)) return
            observer.onContextMenuRequested(
                AndroidContextMenuRequest(
                    SessionId(sessionId), request.token, request.tabId,
                    request.pageUrl, request.frameUrl, request.frameId,
                    request.inFrame, request.onLink, request.onImage,
                    request.onAudio, request.onVideo, request.isTextSelected,
                    request.editable, request.linkUrl, request.linkText,
                    request.srcUrl, request.selectionText, request.mediaType,
                    request.items.map {
                        AndroidContextMenuItem(
                            it.id, it.parentId, it.extensionId, it.extensionName,
                            it.title, it.type, it.checked, it.enabled,
                        )
                    },
                    request.pageActions.map { AndroidContextMenuPageAction(it.id, it.enabled) },
                ),
            )
        }

        override fun onContextMenuUpdated(
            sessionId: Long,
            request: org.mozilla.gecko.navis.NavisAndroidContextMenu,
        ) {
            if (!accepts(sessionId)) return
            observer.onContextMenuUpdated(
                AndroidContextMenuRequest(
                    SessionId(sessionId), request.token, request.tabId,
                    request.pageUrl, request.frameUrl, request.frameId,
                    request.inFrame, request.onLink, request.onImage,
                    request.onAudio, request.onVideo, request.isTextSelected,
                    request.editable, request.linkUrl, request.linkText,
                    request.srcUrl, request.selectionText, request.mediaType,
                    request.items.map {
                        AndroidContextMenuItem(
                            it.id, it.parentId, it.extensionId, it.extensionName,
                            it.title, it.type, it.checked, it.enabled,
                        )
                    },
                    request.pageActions.map { AndroidContextMenuPageAction(it.id, it.enabled) },
                ),
            )
        }

        override fun onShortcutSettingsRequested(sessionId: Long, extensionId: String) {
            if (accepts(sessionId)) observer.onShortcutSettingsRequested(extensionId)
        }

        override fun onContentProcessGone(sessionId: Long, crashed: Boolean) {
            if (sessionId != this.sessionId || closed || closeDelivered) {
                return
            }
            open = false
            closed = true
            observer.onContentProcessGone(crashed)
        }

        override fun onNewWindowRequested(
            openerSessionId: Long,
            uri: String,
            engineWindowToken: String,
            privateMode: Boolean,
        ): CompletionStage<Boolean> =
            if (accepts(openerSessionId)) {
                observer.onNewWindowRequested(uri, engineWindowToken, privateMode)
            } else {
                CompletableFuture.completedFuture(false)
            }

        override fun onPromptRequested(
            sessionId: Long, requestId: Long, kind: String, title: String, message: String,
            privateMode: Boolean, uri: String, username: String, password: String,
            passwordOnly: Boolean, previousAttemptFailed: Boolean, secure: Boolean,
            defaultValue: String, loginOrigin: String, loginUsername: String,
            loginIndex: Int, loginOptions: String,
        ) {
            if (!accepts(sessionId)) return cancelTarget(requestId)
            registerTarget(requestId, this)
            if (kind == "save-login") loginSaveRequests.add(requestId)
            val observer = (this@DirectEngineRuntimeAdapter.targetObserver) ?: return cancelTarget(requestId)
            val id = TargetRequestId(requestId)
            val session = SessionId(sessionId)
            val promptTitle = title.takeUnless(String::isBlank)
            val promptMessage = message.takeUnless(String::isBlank)
            val prompt = when (kind.lowercase()) {
                "alert" -> BrowserPrompt.Alert(id, session, promptTitle, promptMessage, privateMode)
                "confirm" -> BrowserPrompt.Confirm(id, session, promptTitle, promptMessage, privateMode)
                "text" -> BrowserPrompt.Text(id, session, promptTitle, promptMessage, privateMode, defaultValue)
                "authentication" -> BrowserPrompt.Authentication(id, session, promptTitle, promptMessage, privateMode, uri, username, password, passwordOnly, previousAttemptFailed, secure)
                "save-login" -> BrowserPrompt.SaveLogin(id, session, privateMode = privateMode,
                    login = LoginPromptOption(loginIndex, loginOrigin, loginUsername),
                    saveFailed = previousAttemptFailed)
                "select-login" -> BrowserPrompt.SelectLogin(id, session, privateMode = privateMode, logins = parseLogins(loginOptions))
                "choice" -> parseChoicePrompt(loginOptions)?.let { parsed ->
                    BrowserPrompt.Choice(id, session, promptTitle, promptMessage, privateMode, parsed.first, parsed.second)
                }
                "datetime" -> parseDateTimePrompt(loginOptions)?.let { parsed ->
                    BrowserPrompt.DateTime(
                        id, session, promptTitle, promptMessage, privateMode,
                        parsed.kind, parsed.value, parsed.minimum, parsed.maximum, parsed.step,
                    )
                }
                "color" -> parseColorPrompt(loginOptions)?.let { parsed ->
                    BrowserPrompt.ColorPicker(
                        id, session, promptTitle, promptMessage, privateMode,
                        parsed.first, parsed.second,
                    )
                }
                else -> null
            }
            if (prompt == null) {
                cancelTarget(requestId)
            } else {
                observer.onPromptRequested(prompt)
            }
        }

        override fun onLoginSaveCompleted(sessionId: Long, requestId: Long) {
            if (!accepts(sessionId) || targetRequests[requestId] !== this ||
                !loginSaveRequests.remove(requestId)) return
            targetRequests.remove(requestId)
            targetObserver?.onLoginSaveCompleted(SessionId(sessionId), TargetRequestId(requestId))
        }

        override fun onLoginPromptCancelled(sessionId: Long, requestId: Long) {
            if (!accepts(sessionId) || targetRequests[requestId] !== this) return
            targetRequests.remove(requestId)
            loginSaveRequests.remove(requestId)
            targetObserver?.onLoginPromptCancelled(SessionId(sessionId), TargetRequestId(requestId))
        }

        override fun onSitePermissionRequested(sessionId: Long, requestId: Long, uri: String, privateMode: Boolean, kind: String, thirdPartyOrigin: String) {
            registerTarget(requestId, this)
            val observer = this@DirectEngineRuntimeAdapter.targetObserver ?: return cancelTarget(requestId)
            val permission = runCatching { SitePermissionKind.valueOf(kind.uppercase()) }
                .getOrNull() ?: return cancelTarget(requestId)
            observer.onSitePermissionRequested(
                SitePermissionRequest.Content(
                    TargetRequestId(requestId),
                    SessionId(sessionId),
                    uri,
                    privateMode,
                    permission,
                    thirdPartyOrigin.takeUnless(String::isBlank),
                ),
            )
        }

        override fun onMediaPermissionRequested(sessionId: Long, requestId: Long, uri: String, privateMode: Boolean, videoSources: String, audioSources: String) {
            registerTarget(requestId, this)
            val observer = this@DirectEngineRuntimeAdapter.targetObserver ?: return cancelTarget(requestId)
            val video = parseSources(videoSources) ?: return cancelTarget(requestId)
            val audio = parseSources(audioSources) ?: return cancelTarget(requestId)
            if (video.isEmpty() && audio.isEmpty()) return cancelTarget(requestId)
            observer.onSitePermissionRequested(
                SitePermissionRequest.Media(
                    TargetRequestId(requestId),
                    SessionId(sessionId),
                    uri,
                    privateMode,
                    video,
                    audio,
                ),
            )
        }

        override fun onPlatformPermissionRequested(sessionId: Long, requestId: Long, privateMode: Boolean, permissions: Array<String>) {
            registerTarget(requestId, this)
            val observer = this@DirectEngineRuntimeAdapter.targetObserver ?: return cancelTarget(requestId)
            val parsed = permissions.mapNotNull {
                runCatching { PlatformPermission.valueOf(it.uppercase()) }.getOrNull()
            }.toSet()
            if (parsed.isEmpty() || parsed.size != permissions.toSet().size) {
                return cancelTarget(requestId)
            }
            observer.onPlatformPermissionRequested(
                PlatformPermissionRequest(
                    TargetRequestId(requestId),
                    SessionId(sessionId),
                    parsed,
                    privateMode,
                ),
            )
        }

        override fun onFilePickerRequested(sessionId: Long, requestId: Long, privateMode: Boolean, mode: String, capture: String, mimeTypes: Array<String>) {
            registerTarget(requestId, this)
            val observer = this@DirectEngineRuntimeAdapter.targetObserver ?: return cancelTarget(requestId)
            val pickerMode = runCatching { FilePickerMode.valueOf(mode.uppercase()) }
                .getOrNull() ?: return cancelTarget(requestId)
            val pickerCapture = runCatching { FileCapture.valueOf(capture.uppercase()) }
                .getOrNull() ?: return cancelTarget(requestId)
            observer.onFilePickerRequested(
                FilePickerRequest(
                    TargetRequestId(requestId),
                    SessionId(sessionId),
                    pickerMode,
                    pickerCapture,
                    mimeTypes.filter(String::isNotBlank).distinct(),
                    privateMode,
                ),
            )
        }

        fun respondPrompt(requestId: Long, response: PromptResponse) {
            val result = org.mozilla.gecko.util.GeckoBundle(5).apply {
                putString("type", response.javaClass.simpleName)
                putBoolean(
                    "accepted",
                    response !is PromptResponse.Dismiss && response !is PromptResponse.Reject,
                )
                when (response) {
                    is PromptResponse.TextValue -> putString("text", response.value)
                    is PromptResponse.Credentials -> {
                        putString("username", response.username)
                        putString("password", response.password)
                    }
                    is PromptResponse.SelectOption -> putInt("index", response.index)
                    is PromptResponse.ChoiceValue -> putStringArray("ids", response.ids.toTypedArray())
                    is PromptResponse.DateTimeValue -> putString("value", response.value)
                    is PromptResponse.ColorValue -> putString("value", response.value)
                    else -> Unit
                }
            }
            peer.respondToTarget(requestId, result)
        }

        fun respondBoolean(requestId: Long, value: Boolean) {
            peer.respondToTarget(requestId, org.mozilla.gecko.util.GeckoBundle().apply { putBoolean("allow", value) })
        }

        fun respondPermission(requestId: Long, decision: SitePermissionDecision) {
            peer.respondToTarget(requestId, org.mozilla.gecko.util.GeckoBundle().apply {
                putBoolean("allow", decision.allows)
                putString("decision", decision.wireValue)
            })
        }

        fun respondUris(requestId: Long, uris: List<String>) {
            peer.respondToTarget(requestId, org.mozilla.gecko.util.GeckoBundle().apply { putStringArray("uris", uris.toTypedArray()) })
        }

        fun notifySitePermissionShown(requestId: Long) = peer.notifySitePermissionShown(requestId)

        fun cancelTarget(requestId: Long) {
            peer.cancelTargetRequest(requestId)
        }

        override fun onCompositorAttached(sessionId: Long) {
            if (accepts(sessionId)) observer.onCompositorAttached()
        }

        override fun onCompositorDetached(sessionId: Long) {
            if (accepts(sessionId)) observer.onCompositorDetached()
        }

        override fun onNewSurfaceRequired(sessionId: Long) {
            if (accepts(sessionId)) observer.onNewSurfaceRequired()
        }

        override fun onSessionError(sessionId: Long, message: String) {
            if (sessionId == this.sessionId && !closeDelivered) {
                closed = true
                observer.onSessionError(message)
            }
        }

        override fun onSessionClosed(sessionId: Long) {
            if (sessionId == this.sessionId) {
                closed = true
                deliverClosed()
            }
        }

        private fun accepts(callbackSessionId: Long): Boolean =
            callbackSessionId == sessionId && !closed && !closeDelivered

        private fun deliverClosed() {
            if (closeDelivered) {
                return
            }
            closeDelivered = true
            val ownedRequests = targetRequests.filterValues { it === this }.keys.toList()
            ownedRequests.forEach { id ->
                targetRequests.remove(id)
                loginSaveRequests.remove(id)
            }
            onClosed(this)
            observer.onSessionClosed()
        }
    }

    companion object {
        private const val MAX_PROMPT_OPTIONS = 512
        private const val MAX_PROMPT_DEPTH = 8
        private const val MAX_PROMPT_TEXT_CHARS = 16 * 1024

        private data class DateTimePromptData(
            val kind: DateTimePromptKind,
            val value: String?,
            val minimum: String?,
            val maximum: String?,
            val step: String?,
        )

        private fun parseLogins(serialized: String): List<LoginPromptOption> = runCatching {
            val values = JSONArray(serialized)
            buildList {
                for (index in 0 until values.length()) {
                    val value = values.optJSONObject(index) ?: continue
                    add(LoginPromptOption(value.optInt("index", index), value.optString("origin"), value.optString("username")))
                }
            }
        }.getOrDefault(emptyList())

        private fun parseChoicePrompt(
            serialized: String,
        ): Pair<ChoicePromptMode, List<ChoicePromptOption>>? = runCatching {
            val root = JSONObject(serialized)
            val mode = when (root.optString("mode").lowercase()) {
                "menu" -> ChoicePromptMode.MENU
                "multiple" -> ChoicePromptMode.MULTIPLE
                "single" -> ChoicePromptMode.SINGLE
                else -> return null
            }
            val count = intArrayOf(0)
            val choices = parseChoiceArray(
                root.optJSONArray("choices") ?: JSONArray(),
                depth = 0,
                count = count,
            )
            mode to choices
        }.getOrNull()

        private fun parseChoiceArray(
            values: JSONArray,
            depth: Int,
            count: IntArray,
        ): List<ChoicePromptOption> {
            require(depth <= MAX_PROMPT_DEPTH) { "Choice prompt nesting is too deep" }
            return buildList {
                for (index in 0 until values.length()) {
                    require(++count[0] <= MAX_PROMPT_OPTIONS) { "Choice prompt is too large" }
                    val value = values.optJSONObject(index) ?: continue
                    val children = value.optJSONArray("children")?.let {
                        parseChoiceArray(it, depth + 1, count)
                    }.orEmpty()
                    add(
                        ChoicePromptOption(
                            id = value.optString("id").take(MAX_PROMPT_TEXT_CHARS),
                            label = value.optString("label").take(MAX_PROMPT_TEXT_CHARS),
                            selected = value.optBoolean("selected", false),
                            disabled = value.optBoolean("disabled", false),
                            separator = value.optBoolean("separator", false),
                            group = value.optBoolean("group", children.isNotEmpty()),
                            children = children,
                        ),
                    )
                }
            }
        }

        private fun parseDateTimePrompt(serialized: String): DateTimePromptData? = runCatching {
            val value = JSONObject(serialized)
            val kind = when (value.optString("mode").lowercase()) {
                "date" -> DateTimePromptKind.DATE
                "month" -> DateTimePromptKind.MONTH
                "week" -> DateTimePromptKind.WEEK
                "time" -> DateTimePromptKind.TIME
                "datetime-local" -> DateTimePromptKind.DATETIME_LOCAL
                else -> return null
            }
            fun optional(name: String): String? = value.optString(name)
                .take(MAX_PROMPT_TEXT_CHARS)
                .takeUnless(String::isEmpty)
            DateTimePromptData(
                kind = kind,
                value = optional("value"),
                minimum = optional("min"),
                maximum = optional("max"),
                step = optional("step"),
            )
        }.getOrNull()

        private fun parseColorPrompt(serialized: String): Pair<String?, List<String>>? =
            runCatching {
                val value = JSONObject(serialized)
                val colors = value.optJSONArray("predefinedValues") ?: JSONArray()
                require(colors.length() <= MAX_PROMPT_OPTIONS) { "Color prompt is too large" }
                value.optString("value").take(MAX_PROMPT_TEXT_CHARS).takeUnless(String::isEmpty) to
                    buildList {
                        for (index in 0 until colors.length()) {
                            colors.optString(index)
                                .take(MAX_PROMPT_TEXT_CHARS)
                                .takeUnless(String::isEmpty)
                                ?.let(::add)
                        }
                    }
            }.getOrNull()

        private fun parseSources(serialized: String): List<MediaSourceDescriptor>? = runCatching {
            val values = JSONArray(serialized)
            require(values.length() <= MAX_PROMPT_OPTIONS) { "Media source list is too large" }
            buildList {
                for (index in 0 until values.length()) {
                    val value = values.optJSONObject(index) ?: continue
                    val id = (value.optString("rawId").takeUnless(String::isEmpty)
                        ?: value.optString("id")).take(MAX_PROMPT_TEXT_CHARS)
                    if (id.isNotEmpty()) {
                        add(
                            MediaSourceDescriptor(
                                id,
                                value.optString("label").take(MAX_PROMPT_TEXT_CHARS),
                            ),
                        )
                    }
                }
            }
        }.getOrNull()

        fun createAsync(
            context: Context,
            developerMode: Boolean,
            startupPrefs: Map<String, Any> = emptyMap(),
            onReady: (Result<EngineRuntimePort>) -> Unit,
        ) {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                Handler(Looper.getMainLooper()).post {
                    createAsync(context, developerMode, startupPrefs, onReady)
                }
                return
            }

            val delivered = AtomicBoolean(false)
            var peer: NavisAndroidRuntime? = null
            var extensionService: EngineExtensionPort? = null
            var extensionDownloadService: EngineExtensionDownloadPort? = null
            var browsingDataService: EngineBrowsingDataPort? = null
            var loginService: EngineLoginStoragePort? = null
            var profileService: EngineExtensionProfilePort? = null
            var adapter: DirectEngineRuntimeAdapter? = null
            var launchedHere = false
            fun fail(error: Throwable) {
                if (!delivered.compareAndSet(false, true)) {
                    return
                }
                loginService?.let { service ->
                    runCatching(service::close).exceptionOrNull()?.let(error::addSuppressed)
                }
                loginService = null
                profileService?.let { service ->
                    runCatching(service::close).exceptionOrNull()?.let(error::addSuppressed)
                }
                profileService = null
                browsingDataService?.let { service ->
                    runCatching(service::close).exceptionOrNull()?.let(error::addSuppressed)
                }
                browsingDataService = null
                extensionDownloadService?.let { service ->
                    runCatching(service::close).exceptionOrNull()?.let(error::addSuppressed)
                }
                extensionDownloadService = null
                extensionService?.let { service ->
                    runCatching(service::close).exceptionOrNull()?.let(error::addSuppressed)
                }
                extensionService = null
                peer?.let { installed ->
                    runCatching(installed::uninstall).exceptionOrNull()?.let(error::addSuppressed)
                }
                if (launchedHere && GeckoThread.isLaunched()) {
                    runCatching(GeckoThread::forceQuit).exceptionOrNull()?.let(error::addSuppressed)
                }
                onReady(Result.failure(error))
            }

            try {
                val applicationContext = context.applicationContext
                GeckoAppShell.setApplicationContext(applicationContext)
                peer = NavisAndroidRuntime.install(
                    object : NavisAndroidRuntime.Delegate {
                        override fun textScaleFactor(): Float =
                            applicationContext.resources.configuration.fontScale

                        override fun usesDarkTheme(): Boolean =
                            applicationContext.resources.configuration.uiMode and
                                Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

                        override fun onOrientationLock(
                            orientation: Int,
                        ) = CompletableFuture.completedFuture(false)

                        override fun onOrientationUnlock() = Unit

                        override fun onExternalResponse(
                            sessionId: Long,
                            privateMode: Boolean,
                            response: org.mozilla.geckoview.WebResponse,
                        ) {
                            val owner = adapter
                            if (owner == null) {
                                runCatching { response.body?.close() }
                            } else {
                                owner.onExternalResponse(sessionId, privateMode, response)
                            }
                        }
                    },
                )
                val prefs = startupPrefs + mapOf<String, Any>(
                    "navis.extensions.developerMode" to developerMode,
                    "devtools.debugger.remote-enabled" to false,
                )
                val application = applicationContext as org.navis.browser.NavisApplication
                org.mozilla.gecko.process.NavisChildServiceNamespace.configureForProcess(
                    checkNotNull(org.navis.browser.navisProcessName(application)),
                )
                val arguments = mutableListOf<String>()
                checkNotNull(application.profileScope).directory?.let { directory ->
                    val engineProfile = java.io.File(directory, "gecko")
                    check(engineProfile.isDirectory || engineProfile.mkdirs()) { "Could not create engine profile" }
                    arguments += listOf("-profile", engineProfile.absolutePath)
                }
                if (application.safeMode) arguments += "-safe-mode"
                val initInfo = GeckoThread.InitInfo.builder()
                    .args(arguments.toTypedArray())
                    .extras(Bundle())
                    .flags(0)
                    .prefs(prefs)
                    .fds(intArrayOf(-1, -1))
                    .build()
                check(GeckoThread.init(initInfo)) { "Could not initialize the Gecko thread" }
                check(GeckoThread.launch()) { "Could not launch the Gecko thread" }
                launchedHere = true
                GeckoThread.waitForState(GeckoThread.State.PROFILE_READY).accept(
                    {
                        try {
                            val installed = checkNotNull(peer)
                            val extensions = NavisAndroidExtensionPort.install(applicationContext)
                                .also { extensionService = it }
                            val extensionDownloads = NavisAndroidExtensionDownloadPort.install()
                                .also { extensionDownloadService = it }
                            val browsingData = NavisAndroidBrowsingDataPort.install()
                                .also { browsingDataService = it }
                            val logins = NavisAndroidLoginStoragePort.install()
                                .also { loginService = it }
                            val profile = NavisAndroidExtensionProfilePort.install()
                                .also { profileService = it }
                            val created = DirectEngineRuntimeAdapter(
                                installed,
                                extensions,
                                extensionDownloads,
                                browsingData,
                                logins,
                                profile,
                            )
                            adapter = created
                            if (delivered.compareAndSet(false, true)) {
                                // Ownership has transferred to the Runtime. Failure cleanup must
                                // no longer close these peers independently.
                                extensionService = null
                                extensionDownloadService = null
                                browsingDataService = null
                                loginService = null
                                profileService = null
                                onReady(Result.success(created))
                            } else {
                                adapter = null
                                created.close()
                            }
                        } catch (error: Throwable) {
                            fail(error)
                        }
                    },
                    { error ->
                        fail(
                            error ?: IllegalStateException(
                                "Gecko profile readiness failed without an exception",
                            ),
                        )
                    },
                )
            } catch (error: Throwable) {
                fail(error)
            }
        }
    }
}
