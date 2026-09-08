/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.engine

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import org.json.JSONObject
import org.navis.browser.api.BrowserSessionState
import org.navis.browser.api.ContentTermination
import org.navis.browser.api.LoadingState
import org.navis.browser.api.NavigationState
import org.navis.browser.api.SecurityState
import org.navis.browser.api.SessionId
import org.navis.browser.api.SessionMode
import org.navis.browser.core.CoreBridge
import org.navis.browser.core.CoreNavigationCommand
import org.navis.browser.core.CoreNavigationIdentity
import org.navis.browser.core.CoreNavigationSecurity
import org.navis.browser.core.CoreNavigationSnapshot
import org.navis.browser.core.CoreNavigationStartKind
import org.navis.browser.engine.runtime.EngineRuntimePort
import org.navis.browser.engine.runtime.EngineLoadResult
import org.navis.browser.engine.runtime.EngineSecurityState
import org.navis.browser.engine.runtime.EngineSessionObserver
import org.navis.browser.engine.runtime.EngineSessionPort
import org.navis.browser.persistence.EngineSessionStatePolicy
import org.navis.browser.pages.AndroidInternalPages
import org.navis.browser.pages.NativePageHistory
import org.navis.browser.pages.NativePageHistorySnapshot
import org.navis.browser.pages.EnginePageHistory

/** Navis-owned session state machine backed by the direct Android runtime port. */
internal class AndroidSession(
    val id: SessionId,
    val viewId: Long,
    val mode: SessionMode,
    private val core: CoreBridge,
    private val engineRuntime: EngineRuntimePort,
    private val targetDelegate: AndroidTargetDelegate,
    private val owner: Owner,
) : EngineSessionObserver {
    interface Owner {
        fun isLive(sessionId: SessionId): Boolean

        fun onSessionChanged(sessionId: SessionId, persist: Boolean)

        fun dispatchSessionTask(task: () -> Unit) = task()

        fun onSessionCrashed(sessionId: SessionId)

        fun onHistoryVisit(sessionId: SessionId, url: String, title: String)

        fun onHistoryTitleChanged(sessionId: SessionId, url: String, title: String) {}

        fun onRenderSurfaceRequired(sessionId: SessionId)

        fun onHostReady(sessionId: SessionId)

        fun onFullscreenChanged(sessionId: SessionId, enabled: Boolean)

        fun onContextMenuRequested(sessionId: SessionId, request: AndroidContextMenuRequest) {}

        fun onContextMenuUpdated(sessionId: SessionId, request: AndroidContextMenuRequest) {}

        fun onShortcutSettingsRequested(sessionId: SessionId, extensionId: String) {}

        fun onNewWindowRequested(
            openerSessionId: SessionId,
            uri: String,
            engineWindowToken: String,
            privateMode: Boolean,
        ): CompletionStage<Boolean>
    }

    private var active = false
    private var contentTermination = ContentTermination.NONE
    private var nativeNewTab = false
    private var nativeRoute: String? = null
    private val nativeHistory = NativePageHistory()
    private var hostReady = false
    private var presentationEpoch = 0L
    private var engineCanGoBack = false
    private var engineCanGoForward = false
    private var pendingNativeSource: String? = null
    private var faviconPng = ""
    private var currentNavigationId: Long? = null
    private var engineGeneration = 0L
    private var loadingNavigationId: Long? = null
    private var recordedNavigationId: Long? = null
    private var engineState: String? = null
    private var closing = false
    private var engineCloseDelivered = false
    private var recoveryRequested = false
    private var pendingRecoveryUri: String? = null
    private var engineSession = engineRuntime.createSession(id.value, this)

    val renderSession: EngineSessionPort
        get() = engineSession

    fun open(restoredEngineState: String?, fallbackUri: String?, restoredNativeHistory: NativePageHistorySnapshot? = null) {
        val selectedNativeUri = if (nativeNewTab) "navis://newtab/" else fallbackUri
        val historyRestored = selectedNativeUri != null &&
            (restoredNativeHistory?.engine == null || NativePageHistory.boundTo(restoredNativeHistory, restoredEngineState)) &&
            nativeHistory.restore(restoredNativeHistory, selectedNativeUri)
        if (historyRestored) {
            if (!showNativePage(checkNotNull(nativeHistory.current), recordHistory = false)) {
                nativeRoute = null
                nativeNewTab = false
            }
        }
        // Only a validated mixed-history binding authorizes a backing web snapshot.
        // Legacy native+opaque-web pairs remain incompatible and are ignored.
        if (nativeRoute == null && !nativeNewTab && !fallbackUri.isNullOrBlank()) {
            showNativePage(fallbackUri)
        }
        val boundHistory = historyRestored && NativePageHistory.boundTo(restoredNativeHistory, restoredEngineState)
        val restore = SessionRestorePolicy.plan(
            restoredEngineState, fallbackUri, nativeRoute.takeUnless { boundHistory }, nativeNewTab && !boundHistory,
        )
        engineState = restore.engineState
        engineCloseDelivered = false
        engineSession.open(mode == SessionMode.PRIVATE, restore.engineState)
        restore.initialUri?.let(::load)
    }

    fun setInitialNativeNewTab(value: Boolean) {
        check(!engineSession.isOpen) { "Native new-tab identity must be set before opening" }
        nativeNewTab = value
        if (value) showNativePage("navis://newtab/")
    }

    private fun showNativePage(uri: String, recordHistory: Boolean = true): Boolean {
        val page = AndroidInternalPages.resolve(uri) ?: return false
        if (recordHistory && nativeRoute == null && hostReady) {
            val epoch = ++presentationEpoch
            val peer = engineSession
            // The reply contains the exact history at the presentation boundary, including
            // pushState changes that have not reached an asynchronous state notification yet.
            peer.querySession("session:presentation", "{\"native\":true}").whenComplete { reply, error ->
                owner.dispatchSessionTask {
                    if (!isLive() || engineSession !== peer || presentationEpoch != epoch) return@dispatchSessionTask
                    if (error != null) { emit(persist = false); return@dispatchSessionTask }
                    acceptPresentationState(reply, selectWeb = true)
                    applyNativePage(page.uri, recordHistory = true)
                }
            }
            return true
        }
        ++presentationEpoch
        if (hostReady) engineSession.querySession("session:presentation", "{\"native\":true}")
        applyNativePage(page.uri, recordHistory)
        return true
    }

    private fun applyNativePage(uri: String, recordHistory: Boolean) {
        val page = checkNotNull(AndroidInternalPages.resolve(uri))
        pendingNativeSource = null
        if (recordHistory) nativeHistory.push(page.uri)
        if (nativeHistory.snapshot()?.engine == null) engineState = null
        faviconPng = ""
        loadingNavigationId = null
        currentNavigationId?.let { core.stopNavigation(id.value, it) }
        if (engineSession.isOpen) engineSession.stop()
        nativeRoute = page.route
        nativeNewTab = page.route == "newtab"
        val navigationId = core.observeNavigationStart(id.value, page.uri, CoreNavigationStartKind.INTERNAL_PAGE)
        currentNavigationId = navigationId
        core.setNavigationLocation(id.value, navigationId, page.uri)
        core.setNavigationTitle(id.value, navigationId, "Navis")
        core.setNavigationIdentity(id.value, navigationId, CoreNavigationIdentity.INTERNAL_PAGE, page.route)
        core.setNavigationHistory(id.value, navigationId, nativeHistory.canGoBack, nativeHistory.canGoForward)
        core.finishNavigation(id.value, navigationId, null)
        emit(persist = true)
    }

    fun prepareNewWindow(uri: String) {
        check(!engineSession.isOpen) { "New-window Session must still be unopened" }
        nativeNewTab = false
        if (uri.isNotBlank()) {
            val navigationId = observeNavigation(uri)
            core.setNavigationLocation(id.value, navigationId, uri)
        }
    }

    fun openNewWindow(engineWindowToken: String): CompletionStage<Boolean> {
        check(!engineSession.isOpen) { "New-window Session must still be unopened" }
        engineCloseDelivered = false
        return engineSession.openNewWindow(mode == SessionMode.PRIVATE, engineWindowToken)
    }

    fun setActive(value: Boolean) {
        active = value
        if (engineSession.isOpen) {
            engineSession.setActive(value)
        }
    }

    fun respondToContextMenu(token: String, itemId: String?): Boolean =
        engineSession.respondToContextMenu(token, itemId)

    fun load(uri: String) {
        if (showNativePage(uri)) return
        if (nativeRoute != null && hostReady && contentTermination == ContentTermination.NONE) {
            loadFromNativePage(uri)
            return
        }
        ++presentationEpoch
        nativeRoute = null
        nativeNewTab = false
        if (contentTermination != ContentTermination.NONE) {
            requestRecovery(uri)
            return
        }
        executeNavigation(CoreNavigationCommand.LOAD, uri) {
            engineSession.loadUri(uri)
            nativeNewTab = false
        }
    }

    private fun loadFromNativePage(uri: String) {
        val epoch = ++presentationEpoch
        val peer = engineSession
        val originalPage = snapshot().url
        val history = nativeHistory.snapshot()
        val anchorIndex = nativeHistory.precedingEnginePosition
        val anchor = anchorIndex?.let { history?.engine?.entries?.getOrNull(it) }
        val payload = JSONObject().put("uri", uri)
        if (anchor != null) payload.put("index", anchorIndex).put("entryId", anchor.id).put("entryIdentity", anchor.identity)
        else payload.put("root", true)

        fun startLoad() {
            if (!isLive() || engineSession !== peer || epoch != presentationEpoch) return
            nativeRoute = null
            nativeNewTab = false
            pendingNativeSource = nativeHistory.snapshot()?.engine?.current?.identity
            executeNavigation(CoreNavigationCommand.LOAD, uri) {
                peer.querySession("session:load", payload.toString()).whenComplete { _, error ->
                    owner.dispatchSessionTask {
                        if (!isLive() || engineSession !== peer || epoch != presentationEpoch) return@dispatchSessionTask
                        if (error != null) {
                            currentNavigationId?.let { core.stopNavigation(id.value, it) }
                            showNativePage(originalPage, recordHistory = false)
                        }
                    }
                }
            }
        }
        if (anchor == null) {
            startLoad()
        } else {
            // A native history entry can be visible while the backing browser is still
            // on its successor. Branch from its true predecessor, not from that successor.
            peer.querySession("session:traverse", JSONObject().put("index", anchorIndex)
                .put("entryId", anchor.id).put("entryIdentity", anchor.identity).put("native", true).toString()).whenComplete { reply, error ->
                owner.dispatchSessionTask {
                    if (!isLive() || engineSession !== peer || epoch != presentationEpoch) return@dispatchSessionTask
                    if (error != null) { emit(persist = false); return@dispatchSessionTask }
                    acceptPresentationState(reply, selectWeb = false)
                    startLoad()
                }
            }
        }
    }

    fun reload() {
        if (nativeRoute != null) { emit(persist = false); return }
        if (contentTermination != ContentTermination.NONE) {
            requestRecovery(requestedUri = null)
            return
        }
        executeNavigation(CoreNavigationCommand.RELOAD, null, engineSession::reload)
    }

    fun stop() {
        if (contentTermination != ContentTermination.NONE) {
            return
        }
        // Stop is authoritative before the asynchronous engine callback arrives.
        loadingNavigationId = null
        currentNavigationId?.let { core.stopNavigation(id.value, it) }
        engineSession.stop()
        emit(persist = false)
    }

    fun goBack() {
        if (traversePresentation(back = true)) return
        if (contentTermination == ContentTermination.NONE) {
            executeNavigation(CoreNavigationCommand.BACK, null, engineSession::goBack)
        }
    }

    fun goForward() {
        if (traversePresentation(back = false)) return
        if (contentTermination == ContentTermination.NONE) {
            executeNavigation(CoreNavigationCommand.FORWARD, null, engineSession::goForward)
        }
    }

    private fun updatePresentedHistory() {
        val ownsPresentation = nativeRoute != null || nativeHistory.hasNativeEntries
        core.setNavigationHistory(id.value, currentOrObserve(),
            if (ownsPresentation) nativeHistory.canGoBack else engineCanGoBack,
            if (ownsPresentation) nativeHistory.canGoForward else engineCanGoForward)
    }

    private fun acceptPresentationState(reply: String, selectWeb: Boolean): JSONObject {
        val value = JSONObject(reply)
        val serialized = value.optString("state").takeIf(EngineSessionStatePolicy::accepts)
        EnginePageHistory.parse(serialized)?.let { actual ->
            nativeHistory.synchronize(actual, selectWeb)
            if (mode == SessionMode.NORMAL) engineState = serialized
        }
        return value
    }

    /** Native surfaces overlay this same engine Session; only real SHEntries can be traversed. */
    private fun traversePresentation(back: Boolean): Boolean {
        if (!nativeHistory.hasNativeEntries) return nativeRoute != null
        if (contentTermination != ContentTermination.NONE) return true
        val before = snapshot()
        val previousRoute = nativeRoute
        val destination = (if (back) nativeHistory.back() else nativeHistory.forward())
            ?: return nativeRoute != null
        if (AndroidInternalPages.resolve(destination) != null) {
            showNativePage(destination, recordHistory = false)
            return true
        }
        val target = nativeHistory.currentEngineEntry
        val targetIndex = nativeHistory.enginePosition
        if (!hostReady || target == null || targetIndex == null) {
            if (back) nativeHistory.forward() else nativeHistory.back()
            return true
        }
        val epoch = ++presentationEpoch
        val peer = engineSession
        nativeRoute = null
        nativeNewTab = false
        val navigationId = core.beginNavigation(id.value,
            if (back) CoreNavigationCommand.BACK else CoreNavigationCommand.FORWARD, target.uri)
        currentNavigationId = navigationId
        core.setNavigationTitle(id.value, navigationId, "")
        faviconPng = ""
        loadingNavigationId = null
        peer.querySession("session:traverse", JSONObject()
            .put("index", targetIndex).put("entryId", target.id).put("entryIdentity", target.identity).toString()).whenComplete { reply, error ->
            owner.dispatchSessionTask {
                if (!isLive() || engineSession !== peer || epoch != presentationEpoch) return@dispatchSessionTask
                if (error != null) {
                    if (back) nativeHistory.forward() else nativeHistory.back()
                    core.stopNavigation(id.value, navigationId)
                    if (previousRoute != null) {
                        showNativePage(before.url, recordHistory = false)
                    } else {
                        core.setNavigationLocation(id.value, navigationId, before.url)
                        core.setNavigationTitle(id.value, navigationId, before.title)
                        core.setNavigationIdentity(id.value, navigationId,
                            if (before.identity == CoreNavigationIdentity.INTERNAL_ERROR.wireValue)
                                CoreNavigationIdentity.INTERNAL_ERROR else CoreNavigationIdentity.WEB, before.identityKey)
                    }
                    // The target may have been evicted or replaced. Reconcile the actual
                    // engine snapshot before exposing another back/forward attempt.
                    peer.querySession("session:history").whenComplete { state, stateError ->
                        owner.dispatchSessionTask {
                            if (isLive() && engineSession === peer && stateError == null) {
                                acceptPresentationState(state, selectWeb = nativeRoute == null)
                                updatePresentedHistory()
                                emit(persist = true)
                            }
                        }
                    }
                    emit(persist = false)
                    return@dispatchSessionTask
                }
                val result = JSONObject(reply)
                if (!result.optBoolean("traversed")) {
                    acceptPresentationState(reply, selectWeb = true)
                    // Re-show the still-live backing document. No network request, duplicate
                    // history entry, or reload is needed (its DOM and scroll position survive).
                    val current = result.getJSONObject("current")
                    core.setNavigationLocation(id.value, navigationId, current.getString("uri"))
                    core.setNavigationTitle(id.value, navigationId, current.optString("title"))
                    core.setNavigationIdentity(id.value, navigationId,
                        if (current.optBoolean("errorPage")) CoreNavigationIdentity.INTERNAL_ERROR else CoreNavigationIdentity.WEB, "")
                    val security = when (current.optInt("security")) {
                        3 -> CoreNavigationSecurity.SECURE
                        2 -> CoreNavigationSecurity.BROKEN
                        1 -> CoreNavigationSecurity.INSECURE
                        else -> CoreNavigationSecurity.UNKNOWN
                    }
                    core.setNavigationSecurity(id.value, navigationId, security)
                    core.finishNavigation(id.value, navigationId, null)
                }
                updatePresentedHistory()
                emit(persist = true)
            }
        }
        emit(persist = false)
        return true
    }

    fun exitFullscreen() {
        if (engineSession.isOpen) {
            engineSession.exitFullscreen()
        }
    }

    fun close() {
        if (closing) {
            return
        }
        closing = true
        engineSession.close()
    }

    fun flushEngineState() {
        if (engineSession.isOpen && mode == SessionMode.NORMAL &&
            (nativeRoute == null || nativeHistory.snapshot()?.engine != null)) {
            engineSession.flushSessionState()
        }
    }

    fun persistedEngineState(): String? = if (mode == SessionMode.NORMAL &&
        (nativeRoute == null || nativeHistory.snapshot()?.engine != null)) engineState else null

    fun persistedNativeHistory(): NativePageHistorySnapshot? =
        if (mode == SessionMode.NORMAL && nativeHistory.hasNativeEntries) nativeHistory.snapshot() else null

    fun hasBackingWebDocument(): Boolean = nativeHistory.snapshot()?.engine?.current?.uri?.let {
        it != "about:blank"
    } == true

    fun resetToNativeNewTab() {
        nativeHistory.clear()
        showNativePage("navis://newtab/")
    }

    fun state(): BrowserSessionState {
        val navigation = snapshot().toPublicState(contentTermination).copy(faviconPng = faviconPng)
        return BrowserSessionState(
            id = id,
            mode = mode,
            active = active,
            navigation = navigation,
            nativeNewTab = nativeNewTab,
            nativeRoute = nativeRoute,
        )
    }

    override fun onNativeWindowReady() {
        if (isLive()) {
            owner.onRenderSurfaceRequired(id)
        }
    }

    override fun onHostReady() {
        if (isLive()) {
            hostReady = true
            engineSession.querySession("session:presentation", "{\"native\":${nativeRoute != null}}")
            owner.onHostReady(id)
        }
    }

    override fun onLocationChanged(
        uri: String, generation: Long, sameDocument: Boolean, errorPage: Boolean,
    ) {
        if (!isLive() || uri.isBlank() || nativeRoute != null) {
            return
        }
        if (generation != engineGeneration || (generation == 0L && currentNavigationId != null)) return
        if (uri.substringBefore('#') != snapshot().url.substringBefore('#')) faviconPng = ""
        val navigationId = currentOrObserve(uri)
        core.setNavigationLocation(id.value, navigationId, uri)
        if (errorPage) {
            core.setNavigationIdentity(id.value, navigationId, CoreNavigationIdentity.INTERNAL_ERROR, "")
        } else if (uri.startsWith("http://") || uri.startsWith("https://")) {
            core.setNavigationIdentity(
                id.value,
                navigationId,
                CoreNavigationIdentity.WEB,
                "",
            )
        }
        emit(persist = true)
        if (sameDocument && engineSession.isOpen) engineSession.flushSessionState()
    }

    override fun onTitleChanged(title: String) = Unit // Legacy callbacks carry no document identity.

    override fun onTitleChanged(title: String, uri: String, generation: Long) {
        if (!isLive() || nativeRoute != null || contentTermination != ContentTermination.NONE ||
            generation <= 0L || generation != engineGeneration || uri != snapshot().url ||
            currentNavigationId == null) return
        core.setNavigationTitle(id.value, currentOrObserve(), title)
        emit(persist = true)
        if (mode == SessionMode.NORMAL && contentTermination == ContentTermination.NONE &&
            loadingNavigationId == null && recordedNavigationId != null && recordedNavigationId == currentNavigationId) {
            owner.onHistoryTitleChanged(id, snapshot().url, title)
        }
    }

    override fun onFaviconChanged(pageUri: String, png: String) {
        if (!isLive() || nativeRoute != null ||
            contentTermination != ContentTermination.NONE ||
            pageUri != snapshot().url ||
            !SessionFaviconPolicy.accepts(png)) return
        if (faviconPng == png) return
        faviconPng = png
        emit(persist = false)
    }

    override fun onLoadStarted(generation: Long, uri: String) {
        if (!isLive() || contentTermination != ContentTermination.NONE ||
            generation <= engineGeneration) {
            return
        }
        if (nativeRoute != null) {
            // Restoring the backing document must not overwrite the native route, but
            // its generation remains authoritative when that live document is re-shown.
            engineGeneration = generation
            return
        }
        // A new engine-driven link/navigation is not the previously finished ID.
        // Pending product commands already have their own ID; observe promotes it.
        if (loadingNavigationId == currentNavigationId) {
            loadingNavigationId?.let { core.stopNavigation(id.value, it) }
        }
        engineGeneration = generation
        faviconPng = ""
        loadingNavigationId = observeNavigation(uri.takeIf(String::isNotBlank))
        core.setNavigationTitle(id.value, checkNotNull(loadingNavigationId), "")
        emit(persist = false)
    }

    override fun onLoadCompleted(result: EngineLoadResult) {
        if (
            !isLive() ||
            nativeRoute != null ||
            contentTermination != ContentTermination.NONE
        ) {
            return
        }
        val navigationId = loadingNavigationId ?: return
        if (navigationId != currentNavigationId ||
            (result.generation != 0L && result.generation != engineGeneration)) return
        loadingNavigationId = null
        // An uncorrelated overlap/unknown result must end the spinner without
        // manufacturing a successful visit, a failed page, or a crash.
        val transitioned = if (result.generation == 0L || result.status == null || result.cancelled) {
            core.stopNavigation(id.value, navigationId)
        } else {
            if (result.errorPage) {
                core.setNavigationIdentity(id.value, navigationId, CoreNavigationIdentity.INTERNAL_ERROR, "")
            }
            core.finishNavigation(id.value, navigationId, result.failureCode)
        }
        emit(persist = true)
        if (!transitioned || !result.succeeded || mode != SessionMode.NORMAL) return
        if (recordedNavigationId == navigationId) {
            return
        }
        val completed = snapshot()
        if (!completed.url.startsWith("http://") && !completed.url.startsWith("https://")) {
            return
        }
        recordedNavigationId = navigationId
        owner.onHistoryVisit(id, completed.url, completed.title)
    }

    override fun onSessionStateChanged(serializedState: String) {
        if (
            !isLive() ||
            !EngineSessionStatePolicy.accepts(serializedState)
        ) {
            return
        }
        val history = EnginePageHistory.parse(serializedState)
        if (history != null) {
            val selectsWeb = nativeRoute == null &&
                (pendingNativeSource == null || history.current?.identity != pendingNativeSource)
            nativeHistory.synchronize(history, selectWeb = selectsWeb)
            if (selectsWeb) pendingNativeSource = null
            updatePresentedHistory()
        }
        if (mode == SessionMode.NORMAL && (nativeRoute == null || history != null)) engineState = serializedState
        emit(persist = mode == SessionMode.NORMAL)
    }

    override fun onHistoryChanged(canGoBack: Boolean, canGoForward: Boolean) {
        if (!isLive()) return
        engineCanGoBack = canGoBack
        engineCanGoForward = canGoForward
        updatePresentedHistory()
        emit(persist = false)
    }

    override fun onSecurityChanged(state: EngineSecurityState) {
        if (!isLive() || nativeRoute != null) {
            return
        }
        val security = when (state) {
            EngineSecurityState.UNKNOWN -> CoreNavigationSecurity.UNKNOWN
            EngineSecurityState.INSECURE -> CoreNavigationSecurity.INSECURE
            EngineSecurityState.BROKEN -> CoreNavigationSecurity.BROKEN
            EngineSecurityState.SECURE -> CoreNavigationSecurity.SECURE
        }
        core.setNavigationSecurity(id.value, currentOrObserve(), security)
        emit(persist = false)
    }

    override fun onFullscreenChanged(enabled: Boolean) {
        if (isLive()) {
            owner.onFullscreenChanged(id, enabled)
        }
    }

    override fun onContextMenuRequested(request: AndroidContextMenuRequest) {
        if (isLive()) owner.onContextMenuRequested(id, request)
    }

    override fun onContextMenuUpdated(request: AndroidContextMenuRequest) {
        if (isLive()) owner.onContextMenuUpdated(id, request)
    }

    override fun onShortcutSettingsRequested(extensionId: String) {
        if (isLive()) owner.onShortcutSettingsRequested(id, extensionId)
    }

    override fun onContentProcessGone(crashed: Boolean) {
        if (isLive()) {
            markContentTerminated(
                if (crashed) ContentTermination.CRASHED else ContentTermination.TERMINATED,
            )
        }
    }

    override fun onNewWindowRequested(
        uri: String,
        engineWindowToken: String,
        privateMode: Boolean,
    ): CompletionStage<Boolean> = if (isLive()) {
        owner.onNewWindowRequested(id, uri, engineWindowToken, privateMode)
    } else {
        CompletableFuture.completedFuture(false)
    }

    override fun onCompositorAttached() = Unit

    override fun onCompositorDetached() = Unit

    override fun onNewSurfaceRequired() {
        if (isLive()) {
            owner.onRenderSurfaceRequired(id)
        }
    }

    override fun onSessionError(message: String) {
        if (isLive()) {
            markContentTerminated(ContentTermination.CRASHED)
        }
    }

    override fun onSessionClosed() {
        engineCloseDelivered = true
        if (closing || !isLive()) {
            return
        }
        if (contentTermination == ContentTermination.NONE) {
            markContentTerminated(ContentTermination.CRASHED)
        }
        if (recoveryRequested) {
            recoverNow()
        }
    }

    private fun executeNavigation(
        command: CoreNavigationCommand,
        requestedUri: String?,
        action: () -> Unit,
    ) {
        val navigationId = core.beginNavigation(id.value, command, requestedUri)
        currentNavigationId = navigationId
        core.setNavigationTitle(id.value, navigationId, "")
        loadingNavigationId = null
        faviconPng = ""
        try {
            action()
        } catch (error: Throwable) {
            core.stopNavigation(id.value, navigationId)
            throw error
        }
        emit(persist = false)
    }

    private fun observeNavigation(uri: String?): Long = core.observeNavigationStart(
        id.value,
        uri,
        CoreNavigationStartKind.STANDARD,
    ).also { currentNavigationId = it }

    private fun currentOrObserve(uri: String? = null): Long =
        currentNavigationId ?: observeNavigation(uri)

    private fun snapshot(): CoreNavigationSnapshot = core.navigationSnapshot(id.value)

    private fun emit(persist: Boolean) {
        if (isLive()) {
            owner.onSessionChanged(id, persist)
        }
    }

    private fun isLive(): Boolean = !closing && owner.isLive(id)

    private fun markContentTerminated(reason: ContentTermination) {
        if (!isLive() || reason == ContentTermination.NONE) {
            return
        }
        val previous = contentTermination
        if (previous == ContentTermination.CRASHED || previous == reason) {
            return
        }
        faviconPng = ""
        contentTermination = if (reason == ContentTermination.CRASHED) {
            reason
        } else {
            previous.takeUnless { it == ContentTermination.NONE } ?: reason
        }
        targetDelegate.cancelRequests(id)
        core.setCrashed(id.value, true)
        emit(persist = false)
        if (contentTermination == ContentTermination.CRASHED) {
            owner.onSessionCrashed(id)
        }
    }

    private fun requestRecovery(requestedUri: String?) {
        recoveryRequested = true
        pendingRecoveryUri = requestedUri
        if (engineCloseDelivered) {
            recoverNow()
        }
    }

    private fun recoverNow() {
        if (!recoveryRequested || !engineCloseDelivered || !isLive()) {
            return
        }
        val previousTermination = contentTermination
        val requestedUri = pendingRecoveryUri
        val fallbackUri = requestedUri ?: snapshot().url.takeIf(String::isNotBlank)
        val restoredState = if (requestedUri == null && mode == SessionMode.NORMAL) {
            engineState
        } else {
            null
        }
        val replacement = try {
            engineRuntime.createSession(id.value, this)
        } catch (_: Throwable) {
            recoveryRequested = false
            return
        }

        engineSession = replacement
        hostReady = false
        ++presentationEpoch
        engineGeneration = 0L
        loadingNavigationId = null
        engineCloseDelivered = false
        recoveryRequested = false
        pendingRecoveryUri = null
        contentTermination = ContentTermination.NONE
        var navigationId: Long? = null
        try {
            core.setCrashed(id.value, false)
            navigationId = core.beginNavigation(
                id.value,
                CoreNavigationCommand.RESTORE,
                fallbackUri,
            )
            currentNavigationId = navigationId
            replacement.open(mode == SessionMode.PRIVATE, restoredState)
            if (restoredState == null && !fallbackUri.isNullOrBlank()) {
                replacement.loadUri(fallbackUri)
            }
            nativeNewTab = restoredState == null && fallbackUri == null
            emit(persist = false)
        } catch (_: Throwable) {
            runCatching(replacement::close)
            contentTermination = previousTermination
            core.setCrashed(id.value, true)
            navigationId?.let { core.stopNavigation(id.value, it) }
            emit(persist = false)
        }
    }

    private fun CoreNavigationSnapshot.toPublicState(
        termination: ContentTermination,
    ): NavigationState =
        NavigationState(
            revision = revision,
            url = url,
            title = title,
            loading = when (activity) {
                1 -> LoadingState.PENDING
                2 -> LoadingState.VISIBLE
                3 -> LoadingState.SILENT
                else -> LoadingState.IDLE
            },
            security = when (security) {
                1 -> SecurityState.INSECURE
                2 -> SecurityState.BROKEN
                3 -> SecurityState.SECURE
                else -> SecurityState.UNKNOWN
            },
            canGoBack = canGoBack && termination == ContentTermination.NONE,
            canGoForward = canGoForward && termination == ContentTermination.NONE,
            crashed = termination == ContentTermination.CRASHED,
            contentTermination = termination,
            failureCode = failureCode.takeIf { hasFailure },
            engineErrorPage = identity == CoreNavigationIdentity.INTERNAL_ERROR.wireValue,
        )

}
