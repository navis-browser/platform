/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.engine

import org.navis.browser.api.PromptResponse
import org.navis.browser.api.BrowserPrompt
import org.navis.browser.api.FilePickerRequest
import org.navis.browser.api.PlatformPermissionRequest
import org.navis.browser.api.SessionId
import org.navis.browser.api.SitePermissionRequest
import org.navis.browser.api.SitePermissionDecision
import org.navis.browser.api.SitePermissionKind
import org.navis.browser.api.TargetRequestId
import org.navis.browser.api.TargetRequestObserver
import org.navis.browser.api.TargetRequestState
import org.navis.browser.engine.runtime.EngineProjection
import org.navis.browser.engine.runtime.EngineProjectionMatrix
import org.navis.browser.engine.runtime.EngineTargetObserver
import org.navis.browser.engine.runtime.EngineTargetPort
import org.navis.browser.engine.runtime.ProjectionAvailability
import org.navis.browser.persistence.SitePermissionDecisions
import org.navis.browser.persistence.SitePermissionPolicy

/**
 * Product-side target request coordinator.
 *
 * Request DTOs use [EngineTargetPort]. Fullscreen is session-scoped, so its
 * exit command stays on the owning Session port and is injected separately.
 * This coordinator never manufactures a request the engine cannot complete.
 */
internal class AndroidTargetDelegate(
    private val isSessionLive: (SessionId) -> Boolean,
    private val projections: EngineProjectionMatrix,
    private val enginePort: EngineTargetPort? = null,
    private val sitePermissionStore: SitePermissionDecisions? = null,
    private val requestNotificationPermission: (SessionId, (Boolean) -> Unit) -> Unit = { _, result -> result(false) },
    private val notificationsAllowed: () -> Boolean = { false },
    exitFullscreenCommand: ((SessionId) -> Unit)? = null,
    private val windowIdForSession: (SessionId) -> Long? = { 1L },
    private val activeWindowId: () -> Long? = { 1L },
) : EngineTargetObserver, AutoCloseable {
    private val observers = linkedSetOf<TargetRequestObserver>()
    private val fullscreenExit = exitFullscreenCommand
        ?: enginePort?.let { port -> { sessionId: SessionId -> port.exitFullscreen(sessionId) } }
    private var closed = false
    private val notificationPermissionRequests = mutableSetOf<TargetRequestId>()
    private val states = linkedMapOf<Long, TargetRequestState>()

    val state: TargetRequestState get() = activeWindowId()?.let(::stateForWindow) ?: TargetRequestState()
    fun stateForWindow(windowId: Long): TargetRequestState = states[windowId] ?: TargetRequestState()

    init {
        require(
            enginePort != null || REQUEST_PROJECTIONS.none(::isAvailable),
        ) { "Available request projections require an EngineTargetPort" }
        require(
            fullscreenExit != null || !isAvailable(EngineProjection.FULLSCREEN),
        ) { "Available fullscreen projection requires a Session exit command" }
        enginePort?.bind(this)
    }

    val unsupportedProjections: Set<EngineProjection>
        get() = TARGET_PROJECTIONS.filterTo(linkedSetOf()) { projection ->
            projections.availability(projection) ==
                ProjectionAvailability.UNSUPPORTED_NATIVE_PROJECTION
        }

    fun addObserver(observer: TargetRequestObserver) {
        if (closed) {
            return
        }
        observers += observer
        observer.onTargetRequestStateChanged(state)
    }

    fun removeObserver(observer: TargetRequestObserver) {
        observers -= observer
    }

    fun respondToPrompt(id: TargetRequestId, response: PromptResponse) {
        val (windowId, current) = findRequest { it.prompt?.id == id } ?: return
        val prompt = current.prompt
        if (prompt is BrowserPrompt.SaveLogin) {
            if (prompt.saving) return
            if (response is PromptResponse.SelectOption) {
                if (prompt.privateMode || response.index != prompt.login.index ||
                    !isSessionLive(prompt.sessionId)) return
                update(windowId, current.copy(prompt = prompt.copy(saving = true, saveFailed = false)))
                enginePort?.respondToPrompt(id, response)
                return
            }
        }
        update(windowId, current.copy(prompt = null))
        enginePort?.respondToPrompt(id, response)
    }

    override fun onLoginSaveCompleted(sessionId: SessionId, id: TargetRequestId) {
        val (windowId, current) = findRequest {
            val prompt = it.prompt
            prompt is BrowserPrompt.SaveLogin && prompt.id == id &&
                prompt.sessionId == sessionId && prompt.saving
        } ?: return
        if (!closed && isSessionLive(sessionId) && windowIdForSession(sessionId) == windowId) {
            update(windowId, current.copy(prompt = null))
        }
    }

    override fun onLoginPromptCancelled(sessionId: SessionId, id: TargetRequestId) {
        val (windowId, current) = findRequest {
            val prompt = it.prompt
            (prompt is BrowserPrompt.SaveLogin || prompt is BrowserPrompt.SelectLogin) &&
                prompt.id == id && prompt.sessionId == sessionId
        } ?: return
        update(windowId, current.copy(prompt = null))
    }

    fun notifySitePermissionShown(id: TargetRequestId) {
        if (findRequest { it.sitePermission?.id == id } != null) {
            enginePort?.notifySitePermissionShown(id)
        }
    }

    fun respondToSitePermission(id: TargetRequestId, allow: Boolean) {
        respondToSitePermission(id, if (allow) SitePermissionDecision.ALLOW_SESSION else SitePermissionDecision.DISMISS)
    }

    fun respondToSitePermission(id: TargetRequestId, decision: SitePermissionDecision) {
        val (windowId, current) = findRequest { it.sitePermission?.id == id } ?: return
        val request = current.sitePermission ?: return
        if (closed || !isSessionLive(request.sessionId)) return
        if (decision.allows && request is SitePermissionRequest.Content &&
            request.kind == SitePermissionKind.NOTIFICATIONS && !notificationsAllowed()
        ) {
            if (!notificationPermissionRequests.add(id)) return
            requestNotificationPermission(request.sessionId) { granted ->
                notificationPermissionRequests.remove(id)
                // Navigating, switching away or cancelling while the system prompt is open
                // must not persist a grant for a stale document.
                if (!closed && stateForWindow(windowId).sitePermission === request &&
                    windowIdForSession(request.sessionId) == windowId && isSessionLive(request.sessionId)) {
                    completeSitePermission(request, if (granted) decision else SitePermissionDecision.DISMISS)
                }
            }
            return
        }
        completeSitePermission(request, decision)
    }

    private fun completeSitePermission(request: SitePermissionRequest, decision: SitePermissionDecision) {
        val (windowId, current) = findRequest { it.sitePermission === request } ?: return
        if (request is SitePermissionRequest.Content &&
            runCatching { sitePermissionStore?.record(request, decision) }.isFailure
        ) {
            update(windowId, current.copy(sitePermissionSaveFailed = true))
            return // Keep the real engine request pending so the user can retry or dismiss.
        }
        notificationPermissionRequests.remove(request.id)
        update(windowId, current.copy(sitePermission = null, sitePermissionSaveFailed = false))
        enginePort?.respondToSitePermission(request.id, decision)
    }

    /** Call only after the token-bound Gecko clear succeeded; null clears all sites. */
    fun clearSitePermissions(uri: String? = null, privateMode: Boolean? = null): Result<Unit> = runCatching {
        check(!closed) { "Runtime is closed" }
        sitePermissionStore?.clear(uri, privateMode)
        val origin = uri?.let(SitePermissionPolicy::canonicalOrigin)
        states.toMap().forEach { (windowId, current) -> current.sitePermission?.takeIf { request ->
            (privateMode == null || privateMode == request.privateMode) &&
                (uri == null || origin == SitePermissionPolicy.canonicalOrigin(request.uri) ||
                (request is SitePermissionRequest.Content &&
                    origin == request.thirdPartyOrigin?.let(SitePermissionPolicy::canonicalOrigin)))
        }?.let { request ->
            notificationPermissionRequests.remove(request.id)
            update(windowId, current.copy(sitePermission = null, sitePermissionSaveFailed = false))
            cancelEngineRequest(request.id)
        } }
        Unit
    }

    fun respondToPlatformPermission(id: TargetRequestId, granted: Boolean) {
        val (windowId, current) = findRequest { it.platformPermission?.id == id } ?: return
        update(windowId, current.copy(platformPermission = null))
        enginePort?.respondToPlatformPermission(id, granted)
    }

    fun respondToFilePicker(id: TargetRequestId, uriStrings: List<String>) {
        val (windowId, current) = findRequest { it.filePicker?.id == id } ?: return
        update(windowId, current.copy(filePicker = null))
        enginePort?.respondToFilePicker(id, uriStrings)
    }

    fun exitFullscreen() {
        activeWindowId()?.let(::exitFullscreen)
    }

    fun exitFullscreen(windowId: Long) {
        val current = stateForWindow(windowId)
        current.fullscreenSessionId?.let { sessionId ->
            update(windowId, current.copy(fullscreenSessionId = null))
            fullscreenExit?.invoke(sessionId)
        }
    }

    fun onSessionActivated(sessionId: SessionId) {
        val windowId = windowIdForSession(sessionId) ?: return
        if (stateForWindow(windowId).fullscreenSessionId?.let { it != sessionId || !isSessionLive(it) } == true) {
            exitFullscreen(windowId)
        }
    }

    override fun onPromptRequested(request: BrowserPrompt) {
        if (request is BrowserPrompt.SaveLogin && request.privateMode) {
            cancelEngineRequest(request.id)
            return
        }
        if (accept(request.id, request.sessionId, *request.requiredProjections())) {
            val windowId = checkNotNull(windowIdForSession(request.sessionId))
            val current = stateForWindow(windowId)
            current.prompt?.takeUnless { it.id == request.id }?.id?.let(::cancelEngineRequest)
            update(windowId, current.copy(prompt = request))
        }
    }

    override fun onSitePermissionRequested(request: SitePermissionRequest) {
        if (!closed && request is SitePermissionRequest.Content &&
            enginePort != null &&
            isAvailable(EngineProjection.SITE_PERMISSIONS) &&
            isSessionLive(request.sessionId) && windowIdForSession(request.sessionId) != null
        ) {
            sitePermissionStore?.decision(request)?.let { decision ->
                if (request.kind != SitePermissionKind.NOTIFICATIONS || !decision.allows || notificationsAllowed()) {
                    enginePort.respondToSitePermission(request.id, decision)
                    return
                }
                // A saved site grant cannot substitute for a revoked OS permission.
                // Show the site UI again; only its explicit Allow button requests the OS grant.
            }
        }
        if (accept(request.id, request.sessionId, *request.requiredProjections())) {
            val windowId = checkNotNull(windowIdForSession(request.sessionId))
            val current = stateForWindow(windowId)
            current.sitePermission
                ?.takeUnless { it.id == request.id }
                ?.id
                ?.let(::cancelEngineRequest)
            update(windowId, current.copy(sitePermission = request, sitePermissionSaveFailed = false))
        }
    }

    override fun onPlatformPermissionRequested(request: PlatformPermissionRequest) {
        if (accept(request.id, request.sessionId, EngineProjection.PLATFORM_PERMISSIONS)) {
            val windowId = checkNotNull(windowIdForSession(request.sessionId))
            val current = stateForWindow(windowId)
            current.platformPermission
                ?.takeUnless { it.id == request.id }
                ?.id
                ?.let(::cancelEngineRequest)
            update(windowId, current.copy(platformPermission = request))
        }
    }

    override fun onFilePickerRequested(request: FilePickerRequest) {
        if (accept(request.id, request.sessionId, EngineProjection.FILE_PICKER)) {
            val windowId = checkNotNull(windowIdForSession(request.sessionId))
            val current = stateForWindow(windowId)
            current.filePicker?.takeUnless { it.id == request.id }?.id?.let(::cancelEngineRequest)
            update(windowId, current.copy(filePicker = request))
        }
    }

    override fun onFullscreenChanged(sessionId: SessionId, enabled: Boolean) {
        val windowId = windowIdForSession(sessionId)
        if (!isAvailable(EngineProjection.FULLSCREEN) || !isSessionLive(sessionId) || windowId == null) {
            if (enabled) {
                fullscreenExit?.invoke(sessionId)
            }
            return
        }
        val current = stateForWindow(windowId)
        if (enabled && current.fullscreenSessionId != sessionId) exitFullscreen(windowId)
        update(windowId,
            current.copy(
                fullscreenSessionId = if (enabled) sessionId else {
                    current.fullscreenSessionId?.takeUnless { it == sessionId }
                },
            ),
        )
    }

    fun cancelRequests(sessionId: SessionId) {
        // Scan stored ownership: Runtime may have removed the Session from topology already.
        states.toMap().forEach { (windowId, current) ->
            val cancelledIds = current.requestIdsFor(sessionId)
            update(windowId, current.copy(
                prompt = current.prompt?.takeUnless { it.sessionId == sessionId },
                sitePermission = current.sitePermission?.takeUnless { it.sessionId == sessionId },
                sitePermissionSaveFailed = current.sitePermissionSaveFailed && current.sitePermission?.sessionId != sessionId,
                platformPermission = current.platformPermission?.takeUnless { it.sessionId == sessionId },
                filePicker = current.filePicker?.takeUnless { it.sessionId == sessionId },
                fullscreenSessionId = current.fullscreenSessionId?.takeUnless { it == sessionId },
            ))
            cancelledIds.forEach(::cancelEngineRequest)
            if (current.fullscreenSessionId == sessionId) fullscreenExit?.invoke(sessionId)
        }
    }

    fun cancelSession(sessionId: SessionId) = cancelRequests(sessionId)

    fun cancelActivityPrompts() {
        activeWindowId()?.let(::cancelActivityPrompts)
    }

    fun cancelActivityPrompts(windowId: Long) {
        val current = stateForWindow(windowId)
        val cancelledIds = current.requestIdsFor(null)
        update(windowId,
            current.copy(
                prompt = null,
                sitePermission = null,
                sitePermissionSaveFailed = false,
                platformPermission = null,
                filePicker = null,
            ),
        )
        cancelledIds.forEach(::cancelEngineRequest)
    }

    fun cancelWindow(windowId: Long) {
        cancelActivityPrompts(windowId)
        exitFullscreen(windowId)
        states.remove(windowId)
    }

    override fun close() {
        if (closed) {
            return
        }
        // Engine cancellation may synchronously call back; stop accepting before draining.
        closed = true
        states.keys.toList().forEach(::cancelWindow)
        enginePort?.unbind(this)
        sitePermissionStore?.close()
        notificationPermissionRequests.clear()
        states.clear()
        observers.clear()
    }

    private fun update(windowId: Long, next: TargetRequestState) {
        if (closed || next == stateForWindow(windowId)) {
            return
        }
        if (next == TargetRequestState()) states.remove(windowId) else states[windowId] = next
        // Every facade re-reads its own window; a background-window change must also notify.
        observers.toList().forEach { it.onTargetRequestStateChanged(state) }
    }

    private fun findRequest(predicate: (TargetRequestState) -> Boolean): Pair<Long, TargetRequestState>? =
        states.entries.firstOrNull { predicate(it.value) }?.let { it.key to it.value }

    private fun accept(
        requestId: TargetRequestId,
        sessionId: SessionId,
        vararg requiredProjections: EngineProjection,
    ): Boolean {
        if (
            !closed &&
            enginePort != null &&
            requiredProjections.all(::isAvailable) &&
            isSessionLive(sessionId) && windowIdForSession(sessionId) != null
        ) {
            return true
        }
        enginePort?.cancelRequest(requestId)
        return false
    }

    private fun isAvailable(projection: EngineProjection): Boolean =
        projections.availability(projection) == ProjectionAvailability.AVAILABLE

    private fun cancelEngineRequest(id: TargetRequestId) {
        notificationPermissionRequests.remove(id)
        enginePort?.cancelRequest(id)
    }

    private fun TargetRequestState.requestIdsFor(sessionId: SessionId?): List<TargetRequestId> = listOfNotNull(
        prompt?.takeIf { sessionId == null || it.sessionId == sessionId }?.id,
        sitePermission?.takeIf { sessionId == null || it.sessionId == sessionId }?.id,
        platformPermission?.takeIf { sessionId == null || it.sessionId == sessionId }?.id,
        filePicker?.takeIf { sessionId == null || it.sessionId == sessionId }?.id,
    ).distinct()

    private fun BrowserPrompt.requiredProjections(): Array<EngineProjection> = when (this) {
        is BrowserPrompt.SaveLogin,
        is BrowserPrompt.SelectLogin ->
            arrayOf(EngineProjection.TARGET_PROMPTS, EngineProjection.LOGIN_STORAGE)
        else -> arrayOf(EngineProjection.TARGET_PROMPTS)
    }

    private fun SitePermissionRequest.requiredProjections(): Array<EngineProjection> = when (this) {
        is SitePermissionRequest.Media ->
            arrayOf(EngineProjection.SITE_PERMISSIONS, EngineProjection.MEDIA_CAPTURE)
        is SitePermissionRequest.Content -> arrayOf(EngineProjection.SITE_PERMISSIONS)
    }

    private companion object {
        val TARGET_PROJECTIONS = setOf(
            EngineProjection.TARGET_PROMPTS,
            EngineProjection.SITE_PERMISSIONS,
            EngineProjection.PLATFORM_PERMISSIONS,
            EngineProjection.FILE_PICKER,
            EngineProjection.FULLSCREEN,
            EngineProjection.LOGIN_STORAGE,
            EngineProjection.MEDIA_CAPTURE,
        )
        val REQUEST_PROJECTIONS = TARGET_PROJECTIONS - EngineProjection.FULLSCREEN
    }
}
