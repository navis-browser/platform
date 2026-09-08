/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.navis.browser.api.BrowserPrompt
import org.navis.browser.api.FileCapture
import org.navis.browser.api.FilePickerMode
import org.navis.browser.api.FilePickerRequest
import org.navis.browser.api.PlatformPermission
import org.navis.browser.api.PlatformPermissionRequest
import org.navis.browser.api.PromptResponse
import org.navis.browser.api.SessionId
import org.navis.browser.api.LoginPromptOption
import org.navis.browser.api.MediaSourceDescriptor
import org.navis.browser.api.SitePermissionKind
import org.navis.browser.api.SitePermissionRequest
import org.navis.browser.api.SitePermissionDecision
import org.navis.browser.api.TargetRequestId
import org.navis.browser.engine.runtime.EngineProjection
import org.navis.browser.engine.runtime.EngineProjectionMatrix
import org.navis.browser.engine.runtime.EngineTargetObserver
import org.navis.browser.engine.runtime.EngineTargetPort
import org.navis.browser.persistence.SitePermissionPolicy
import org.navis.browser.persistence.SitePermissionStorage

class AndroidTargetDelegateTest {
    @Test
    fun windowRequestsAndFullscreenDoNotReplaceEachOther() {
        val port = FakeTargetPort()
        val delegate = AndroidTargetDelegate(
            isSessionLive = { true },
            projections = EngineProjectionMatrix.fromAvailable(*EngineProjection.entries.toTypedArray()),
            enginePort = port,
            windowIdForSession = { if (it.value < 10) 1L else 2L },
        )
        fun prompt(id: Long, session: Long) = BrowserPrompt.Alert(TargetRequestId(id), SessionId(session), null, "message", false)
        val first = prompt(201, 7)
        val second = prompt(202, 17)
        delegate.onPromptRequested(first)
        delegate.onPromptRequested(second)
        assertSame(first, delegate.stateForWindow(1).prompt)
        assertSame(second, delegate.stateForWindow(2).prompt)
        assertTrue(port.events.isEmpty())
        delegate.onFullscreenChanged(SessionId(7), true)
        delegate.onFullscreenChanged(SessionId(17), true)
        delegate.onSessionActivated(SessionId(18))
        assertEquals(SessionId(7), delegate.stateForWindow(1).fullscreenSessionId)
        assertNull(delegate.stateForWindow(2).fullscreenSessionId)
        delegate.cancelWindow(2)
        assertSame(first, delegate.stateForWindow(1).prompt)
        assertEquals(listOf("fullscreen-exit:17", "cancel:202"), port.events)
        delegate.respondToPrompt(first.id, PromptResponse.Accept)
        assertEquals("prompt:201:Accept", port.events.last())
        delegate.close()
        assertEquals("fullscreen-exit:7", port.events.last())
    }

    @Test
    fun lateNotificationDecisionCannotGrantAfterItsWindowCloses() {
        val port = FakeTargetPort()
        val policy = SitePermissionPolicy(MemoryPermissionStorage())
        val callbacks = mutableMapOf<SessionId, (Boolean) -> Unit>()
        val delegate = AndroidTargetDelegate(
            isSessionLive = { true },
            projections = EngineProjectionMatrix.fromAvailable(EngineProjection.SITE_PERMISSIONS),
            enginePort = port, sitePermissionStore = policy,
            requestNotificationPermission = { session, callback -> callbacks[session] = callback },
            windowIdForSession = { it.value },
        )
        val first = contentPermission(211).copy(sessionId = SessionId(1), kind = SitePermissionKind.NOTIFICATIONS)
        val second = contentPermission(212).copy(sessionId = SessionId(2), uri = "https://second.example/", kind = SitePermissionKind.NOTIFICATIONS)
        delegate.onSitePermissionRequested(first)
        delegate.onSitePermissionRequested(second)
        delegate.respondToSitePermission(first.id, SitePermissionDecision.ALLOW_ALWAYS)
        delegate.respondToSitePermission(second.id, SitePermissionDecision.ALLOW_ALWAYS)
        assertEquals(setOf(SessionId(1), SessionId(2)), callbacks.keys)
        delegate.cancelWindow(1)
        callbacks.getValue(SessionId(1))(true)
        assertNull(policy.decision(first))
        assertSame(second, delegate.stateForWindow(2).sitePermission)
        callbacks.getValue(SessionId(2))(true)
        assertEquals(SitePermissionDecision.ALLOW_ALWAYS, policy.decision(second))
        assertEquals(listOf("cancel:211", "site:212:true"), port.events)
    }

    @Test
    fun pickerAndPlatformPermissionsKeepIndependentOwners() {
        val port = FakeTargetPort()
        val delegate = AndroidTargetDelegate(
            isSessionLive = { true },
            projections = EngineProjectionMatrix.fromAvailable(*EngineProjection.entries.toTypedArray()),
            enginePort = port, windowIdForSession = { it.value },
        )
        for (id in 1L..2L) {
            delegate.onFilePickerRequested(FilePickerRequest(TargetRequestId(220 + id), SessionId(id), FilePickerMode.MULTIPLE, FileCapture.NONE, listOf("image/*"), false))
            delegate.onPlatformPermissionRequested(PlatformPermissionRequest(TargetRequestId(230 + id), SessionId(id), setOf(PlatformPermission.FINE_LOCATION), false))
        }
        assertTrue(port.events.isEmpty())
        delegate.cancelActivityPrompts(1)
        assertEquals(TargetRequestId(222), delegate.stateForWindow(2).filePicker?.id)
        assertEquals(TargetRequestId(232), delegate.stateForWindow(2).platformPermission?.id)
        delegate.respondToFilePicker(TargetRequestId(222), listOf("content://second/owned"))
        delegate.respondToPlatformPermission(TargetRequestId(232), true)
        assertEquals(listOf("cancel:231", "cancel:221", "picker:222:content://second/owned", "platform:232:true"), port.events)
    }

    @Test
    fun unknownWindowIsRejectedAndRemovedSessionCanStillBeCancelled() {
        val port = FakeTargetPort()
        val owners = mutableMapOf(SessionId(7) to 1L)
        val delegate = AndroidTargetDelegate(
            isSessionLive = { true }, projections = EngineProjectionMatrix.fromAvailable(EngineProjection.TARGET_PROMPTS),
            enginePort = port, windowIdForSession = owners::get,
        )
        delegate.onPromptRequested(BrowserPrompt.Alert(TargetRequestId(240), SessionId(8), null, "missing", false))
        delegate.onPromptRequested(BrowserPrompt.Alert(TargetRequestId(241), SessionId(7), null, "closing", false))
        owners.remove(SessionId(7))
        delegate.cancelSession(SessionId(7))
        assertNull(delegate.stateForWindow(1).prompt)
        assertEquals(listOf("cancel:240", "cancel:241"), port.events)
    }

    @Test
    fun directRuntimeDoesNotManufactureUnsupportedRequests() {
        val delegate = AndroidTargetDelegate(
            isSessionLive = { true },
            projections = EngineProjectionMatrix.fromAvailable(),
            exitFullscreenCommand = {},
        )
        val observed = mutableListOf(delegate.state)
        delegate.addObserver { observed += it }

        delegate.respondToPrompt(TargetRequestId(1), PromptResponse.Accept)
        delegate.respondToSitePermission(TargetRequestId(2), allow = true)
        delegate.respondToPlatformPermission(TargetRequestId(3), granted = true)
        delegate.respondToFilePicker(TargetRequestId(4), listOf("content://example"))
        delegate.exitFullscreen()

        assertEquals(2, observed.size)
        assertEquals(observed.first(), observed.last())
        assertEquals(null, delegate.state.prompt)
        assertEquals(null, delegate.state.filePicker)
    }

    @Test
    fun unsupportedTargetProjectionSetIsExplicitAndBounded() {
        val delegate = AndroidTargetDelegate(
            isSessionLive = { it == SessionId(7) },
            projections = EngineProjectionMatrix.fromAvailable(
                EngineProjection.NAVIGATION,
                EngineProjection.FULLSCREEN,
            ),
            exitFullscreenCommand = {},
        )

        assertEquals(
            setOf(
                EngineProjection.TARGET_PROMPTS,
                EngineProjection.SITE_PERMISSIONS,
                EngineProjection.PLATFORM_PERMISSIONS,
                EngineProjection.FILE_PICKER,
                EngineProjection.LOGIN_STORAGE,
                EngineProjection.MEDIA_CAPTURE,
            ),
            delegate.unsupportedProjections,
        )
        assertTrue(delegate.state.fullscreenSessionId == null)
    }

    @Test(expected = IllegalArgumentException::class)
    fun availableTargetProjectionCannotExistWithoutACompletionPort() {
        AndroidTargetDelegate(
            isSessionLive = { true },
            projections = EngineProjectionMatrix.fromAvailable(EngineProjection.TARGET_PROMPTS),
        )
    }

    @Test
    fun directFullscreenUsesTheOwningSessionExitCommand() {
        val exits = mutableListOf<SessionId>()
        val delegate = AndroidTargetDelegate(
            isSessionLive = { it == SessionId(7) },
            projections = EngineProjectionMatrix.fromAvailable(EngineProjection.FULLSCREEN),
            exitFullscreenCommand = exits::add,
        )

        delegate.onFullscreenChanged(SessionId(7), enabled = true)
        assertEquals(SessionId(7), delegate.state.fullscreenSessionId)

        delegate.exitFullscreen()

        assertEquals(listOf(SessionId(7)), exits)
        assertNull(delegate.state.fullscreenSessionId)
    }

    @Test
    fun promptResponseIsForwardedExactlyOnceAndStaleResponsesAreIgnored() {
        val port = FakeTargetPort()
        val delegate = delegateWithAllTargets(port)
        val prompt = BrowserPrompt.Confirm(
            id = TargetRequestId(11),
            sessionId = SessionId(7),
            title = "Confirm",
            message = "Continue?",
            privateMode = false,
        )

        port.observer.onPromptRequested(prompt)
        assertSame(prompt, delegate.state.prompt)

        delegate.respondToPrompt(TargetRequestId(99), PromptResponse.Reject)
        assertTrue(port.events.isEmpty())
        assertSame(prompt, delegate.state.prompt)

        delegate.respondToPrompt(prompt.id, PromptResponse.Accept)
        delegate.respondToPrompt(prompt.id, PromptResponse.Reject)

        assertNull(delegate.state.prompt)
        assertEquals(listOf("prompt:11:Accept"), port.events)
    }

    @Test
    fun permissionPickerAndFullscreenRequestsRoundTripThroughThePort() {
        val port = FakeTargetPort()
        val delegate = delegateWithAllTargets(port)
        val sessionId = SessionId(7)
        val site = SitePermissionRequest.Content(
            id = TargetRequestId(21),
            sessionId = sessionId,
            uri = "https://example.com/",
            privateMode = false,
            kind = SitePermissionKind.GEOLOCATION,
            thirdPartyOrigin = null,
        )
        val platform = PlatformPermissionRequest(
            id = TargetRequestId(22),
            sessionId = sessionId,
            permissions = setOf(PlatformPermission.FINE_LOCATION),
            privateMode = false,
        )
        val picker = FilePickerRequest(
            id = TargetRequestId(23),
            sessionId = sessionId,
            mode = FilePickerMode.MULTIPLE,
            capture = FileCapture.NONE,
            mimeTypes = listOf("image/*"),
            privateMode = false,
        )

        port.observer.onSitePermissionRequested(site)
        delegate.notifySitePermissionShown(site.id)
        delegate.respondToSitePermission(site.id, allow = true)
        port.observer.onPlatformPermissionRequested(platform)
        delegate.respondToPlatformPermission(platform.id, granted = false)
        port.observer.onFilePickerRequested(picker)
        delegate.respondToFilePicker(picker.id, listOf("content://selected/1"))
        port.observer.onFullscreenChanged(sessionId, enabled = true)
        delegate.exitFullscreen()

        assertEquals(
            listOf(
                "site-shown:21",
                "site:21:true",
                "platform:22:false",
                "picker:23:content://selected/1",
                "fullscreen-exit:7",
            ),
            port.events,
        )
        assertEquals(org.navis.browser.api.TargetRequestState(), delegate.state)
    }

    @Test
    fun deadSessionAndReplacementRequestsCannotLeaveAnEngineRequestHanging() {
        val port = FakeTargetPort()
        val delegate = AndroidTargetDelegate(
            isSessionLive = { it == SessionId(7) },
            projections = EngineProjectionMatrix.fromAvailable(EngineProjection.TARGET_PROMPTS),
            enginePort = port,
        )
        fun prompt(id: Long, sessionId: Long = 7) = BrowserPrompt.Alert(
            id = TargetRequestId(id),
            sessionId = SessionId(sessionId),
            title = null,
            message = "message",
            privateMode = false,
        )

        port.observer.onPromptRequested(prompt(30, sessionId = 8))
        port.observer.onPromptRequested(prompt(31))
        port.observer.onPromptRequested(prompt(32))

        assertEquals(TargetRequestId(32), delegate.state.prompt?.id)
        assertEquals(listOf("cancel:30", "cancel:31"), port.events)

        delegate.cancelActivityPrompts()
        assertNull(delegate.state.prompt)
        assertEquals(listOf("cancel:30", "cancel:31", "cancel:32"), port.events)
    }

    @Test
    fun compositeLoginAndMediaRequestsRequireTheirOwnNativeProjections() {
        val port = FakeTargetPort()
        val delegate = AndroidTargetDelegate(
            isSessionLive = { it == SessionId(7) },
            projections = EngineProjectionMatrix.fromAvailable(
                EngineProjection.TARGET_PROMPTS,
                EngineProjection.SITE_PERMISSIONS,
            ),
            enginePort = port,
        )
        val saveLogin = BrowserPrompt.SaveLogin(
            id = TargetRequestId(40),
            sessionId = SessionId(7),
            privateMode = false,
            login = LoginPromptOption(
                index = 0,
                origin = "https://example.com",
                username = "user",
            ),
        )
        val media = SitePermissionRequest.Media(
            id = TargetRequestId(41),
            sessionId = SessionId(7),
            uri = "https://example.com/",
            privateMode = false,
            videoSources = listOf(MediaSourceDescriptor("camera", "Camera")),
            audioSources = listOf(MediaSourceDescriptor("microphone", "Microphone")),
        )

        port.observer.onPromptRequested(saveLogin)
        port.observer.onSitePermissionRequested(media)

        assertNull(delegate.state.prompt)
        assertNull(delegate.state.sitePermission)
        assertEquals(listOf("cancel:40", "cancel:41"), port.events)
    }

    @Test
    fun explicitPermissionIntentsRemainDistinctAndStaleAnswersAreIgnored() {
        val port = FakeTargetPort()
        val delegate = delegateWithAllTargets(port)
        SitePermissionDecision.entries.forEachIndexed { index, decision ->
            val request = contentPermission(100L + index)
            port.observer.onSitePermissionRequested(request)
            delegate.respondToSitePermission(request.id, decision)
            delegate.respondToSitePermission(request.id, SitePermissionDecision.BLOCK)
            assertEquals(decision, port.decisions.last())
        }
        assertEquals(SitePermissionDecision.entries.toList(), port.decisions)
    }

    @Test
    fun systemNotificationDenialDoesNotCreateAPersistentSiteDecision() {
        val port = FakeTargetPort()
        val policy = SitePermissionPolicy(MemoryPermissionStorage())
        var result: ((Boolean) -> Unit)? = null
        var systemPrompts = 0
        val delegate = AndroidTargetDelegate(
            isSessionLive = { true },
            projections = EngineProjectionMatrix.fromAvailable(EngineProjection.SITE_PERMISSIONS),
            enginePort = port, sitePermissionStore = policy,
            requestNotificationPermission = { _, callback -> systemPrompts++; result = callback }, notificationsAllowed = { false },
        )
        val request = contentPermission(120).copy(kind = SitePermissionKind.NOTIFICATIONS)
        port.observer.onSitePermissionRequested(request)
        assertEquals(0, systemPrompts)
        delegate.respondToSitePermission(request.id, SitePermissionDecision.ALLOW_ALWAYS)
        delegate.respondToSitePermission(request.id, SitePermissionDecision.ALLOW_ALWAYS)
        assertEquals(1, systemPrompts)
        assertTrue(port.decisions.isEmpty())
        result!!(false)
        assertEquals(listOf(SitePermissionDecision.DISMISS), port.decisions)
        assertNull(policy.decision(request))
    }

    @Test
    fun cachedAllowDoesNotSilentlyRequestOSPermissionOrGetOverwrittenWhenItIsDenied() {
        val port = FakeTargetPort()
        val policy = SitePermissionPolicy(MemoryPermissionStorage())
        val request = contentPermission(130).copy(kind = SitePermissionKind.NOTIFICATIONS)
        policy.record(request, SitePermissionDecision.ALLOW_ALWAYS)
        var prompts = 0
        val delegate = AndroidTargetDelegate(
            isSessionLive = { true },
            projections = EngineProjectionMatrix.fromAvailable(EngineProjection.SITE_PERMISSIONS),
            enginePort = port, sitePermissionStore = policy,
            requestNotificationPermission = { _, callback -> prompts++; callback(false) }, notificationsAllowed = { false },
        )
        port.observer.onSitePermissionRequested(request)
        assertSame(request, delegate.state.sitePermission)
        assertEquals(0, prompts)
        assertTrue(port.decisions.isEmpty())
        delegate.respondToSitePermission(request.id, SitePermissionDecision.ALLOW_ALWAYS)
        assertEquals(1, prompts)
        assertEquals(SitePermissionDecision.DISMISS, port.decisions.single())
        assertEquals(SitePermissionDecision.ALLOW_ALWAYS, policy.decision(request))
    }

    @Test
    fun cancellingBeforeTheSystemDialogReturnsCannotCreateAStaleGrant() {
        val port = FakeTargetPort()
        val policy = SitePermissionPolicy(MemoryPermissionStorage())
        var result: ((Boolean) -> Unit)? = null
        val delegate = AndroidTargetDelegate(
            isSessionLive = { true },
            projections = EngineProjectionMatrix.fromAvailable(EngineProjection.SITE_PERMISSIONS),
            enginePort = port, sitePermissionStore = policy,
            requestNotificationPermission = { _, callback -> result = callback }, notificationsAllowed = { false },
        )
        val request = contentPermission(140).copy(kind = SitePermissionKind.NOTIFICATIONS)
        port.observer.onSitePermissionRequested(request)
        delegate.respondToSitePermission(request.id, SitePermissionDecision.ALLOW_ALWAYS)
        delegate.clearSitePermissions("https://example.com", false).getOrThrow()
        result!!(true)
        assertTrue(port.decisions.isEmpty())
        assertEquals(listOf("cancel:140"), port.events)
        assertNull(policy.decision(request))
    }

    @Test
    fun failedPermissionWriteLeavesThePromptRetryableWithoutGrantingAccess() {
        val port = FakeTargetPort()
        val storage = MemoryPermissionStorage().apply { fail = true }
        val delegate = AndroidTargetDelegate(
            isSessionLive = { true },
            projections = EngineProjectionMatrix.fromAvailable(EngineProjection.SITE_PERMISSIONS),
            enginePort = port, sitePermissionStore = SitePermissionPolicy(storage),
        )
        val request = contentPermission(150)
        port.observer.onSitePermissionRequested(request)
        delegate.respondToSitePermission(request.id, SitePermissionDecision.ALLOW_ALWAYS)
        assertTrue(delegate.state.sitePermissionSaveFailed)
        assertSame(request, delegate.state.sitePermission)
        assertTrue(port.decisions.isEmpty())
        delegate.respondToSitePermission(request.id, SitePermissionDecision.DISMISS)
        assertNull(delegate.state.sitePermission)
        assertEquals(SitePermissionDecision.DISMISS, port.decisions.single())
    }

    @Test
    fun loginSaveWaitsForCommitCanRetryAndIgnoresOtherSessionOrLateSuccess() {
        val port = FakeTargetPort()
        val delegate = AndroidTargetDelegate(
            isSessionLive = { it == SessionId(7) },
            projections = EngineProjectionMatrix.fromAvailable(
                EngineProjection.TARGET_PROMPTS, EngineProjection.LOGIN_STORAGE),
            enginePort = port,
        )
        val prompt = BrowserPrompt.SaveLogin(TargetRequestId(801), SessionId(7),
            privateMode = false, login = LoginPromptOption(0, "https://example.com", "user"))
        port.observer.onPromptRequested(prompt)
        delegate.respondToPrompt(prompt.id, PromptResponse.SelectOption(1))
        assertTrue(port.events.isEmpty())
        delegate.respondToPrompt(prompt.id, PromptResponse.SelectOption(0))
        assertTrue((delegate.state.prompt as BrowserPrompt.SaveLogin).saving)
        delegate.respondToPrompt(prompt.id, PromptResponse.SelectOption(0))
        assertEquals(1, port.events.size)
        port.observer.onLoginSaveCompleted(SessionId(8), prompt.id)
        assertNotNull(delegate.state.prompt)
        port.observer.onPromptRequested(prompt.copy(saveFailed = true))
        assertFalse((delegate.state.prompt as BrowserPrompt.SaveLogin).saving)
        assertTrue((delegate.state.prompt as BrowserPrompt.SaveLogin).saveFailed)
        delegate.respondToPrompt(prompt.id, PromptResponse.SelectOption(0))
        assertEquals(2, port.events.size)
        port.observer.onLoginSaveCompleted(SessionId(7), prompt.id)
        assertNull(delegate.state.prompt)
        val next = prompt.copy(id = TargetRequestId(802))
        port.observer.onPromptRequested(next)
        delegate.respondToPrompt(next.id, PromptResponse.SelectOption(0))
        port.observer.onLoginSaveCompleted(SessionId(7), prompt.id)
        assertEquals(next.id, delegate.state.prompt?.id)
        delegate.cancelActivityPrompts()
        port.observer.onLoginSaveCompleted(SessionId(7), next.id)
        assertNull(delegate.state.prompt)
        port.observer.onPromptRequested(next.copy(privateMode = true))
        assertNull(delegate.state.prompt)
        assertEquals("cancel:802", port.events.last())
    }

    @Test
    fun sourceCancellationRemovesOnlyItsOwnLoginPrompt() {
        val port = FakeTargetPort()
        val delegate = AndroidTargetDelegate(
            isSessionLive = { it == SessionId(7) },
            projections = EngineProjectionMatrix.fromAvailable(
                EngineProjection.TARGET_PROMPTS, EngineProjection.LOGIN_STORAGE),
            enginePort = port,
        )
        val save = BrowserPrompt.SaveLogin(TargetRequestId(901), SessionId(7), privateMode = false,
            login = LoginPromptOption(0, "https://example.com", "user"))
        port.observer.onPromptRequested(save)
        port.observer.onLoginPromptCancelled(SessionId(8), save.id)
        assertSame(save, delegate.state.prompt)
        val next = save.copy(id = TargetRequestId(902))
        port.observer.onPromptRequested(next)
        port.observer.onLoginPromptCancelled(save.sessionId, save.id)
        assertSame(next, delegate.state.prompt)
        port.observer.onLoginPromptCancelled(next.sessionId, next.id)
        assertNull(delegate.state.prompt)
        val selection = BrowserPrompt.SelectLogin(TargetRequestId(903), SessionId(7),
            privateMode = false, logins = listOf(save.login))
        port.observer.onPromptRequested(selection)
        port.observer.onLoginPromptCancelled(selection.sessionId, selection.id)
        assertNull(delegate.state.prompt)
        val alert = BrowserPrompt.Alert(TargetRequestId(904), SessionId(7), "title", "message", false)
        port.observer.onPromptRequested(alert)
        port.observer.onLoginPromptCancelled(alert.sessionId, alert.id)
        assertSame(alert, delegate.state.prompt)
    }

    private fun contentPermission(id: Long) = SitePermissionRequest.Content(
        TargetRequestId(id), SessionId(7), "https://example.com/", false, SitePermissionKind.GEOLOCATION, null,
    )

    private class MemoryPermissionStorage : SitePermissionStorage {
        private val values = mutableMapOf<String, String>()
        var fail = false
        override fun entries() = values.toMap()
        override fun put(key: String, value: String) { check(!fail); values[key] = value }
        override fun remove(keys: Set<String>) { check(!fail); keys.forEach(values::remove) }
    }

    private fun delegateWithAllTargets(port: FakeTargetPort) = AndroidTargetDelegate(
        isSessionLive = { it == SessionId(7) },
        projections = EngineProjectionMatrix.fromAvailable(
            EngineProjection.TARGET_PROMPTS,
            EngineProjection.SITE_PERMISSIONS,
            EngineProjection.PLATFORM_PERMISSIONS,
            EngineProjection.FILE_PICKER,
            EngineProjection.FULLSCREEN,
        ),
        enginePort = port,
    )

    private class FakeTargetPort : EngineTargetPort {
        lateinit var observer: EngineTargetObserver
        val events = mutableListOf<String>()
        val decisions = mutableListOf<SitePermissionDecision>()

        override fun bind(observer: EngineTargetObserver) {
            this.observer = observer
        }

        override fun unbind(observer: EngineTargetObserver) {
            assertSame(this.observer, observer)
        }

        override fun respondToPrompt(id: TargetRequestId, response: PromptResponse) {
            events += "prompt:${id.value}:${response.javaClass.simpleName}"
        }

        override fun notifySitePermissionShown(id: TargetRequestId) {
            events += "site-shown:${id.value}"
        }

        override fun respondToSitePermission(id: TargetRequestId, allow: Boolean) {
            events += "site:${id.value}:$allow"
        }

        override fun respondToSitePermission(id: TargetRequestId, decision: SitePermissionDecision) {
            decisions += decision
            respondToSitePermission(id, decision.allows)
        }

        override fun respondToPlatformPermission(id: TargetRequestId, granted: Boolean) {
            events += "platform:${id.value}:$granted"
        }

        override fun respondToFilePicker(id: TargetRequestId, uris: List<String>) {
            events += "picker:${id.value}:${uris.joinToString()}"
        }

        override fun cancelRequest(id: TargetRequestId) {
            events += "cancel:${id.value}"
        }

        override fun exitFullscreen(sessionId: SessionId) {
            events += "fullscreen-exit:${sessionId.value}"
        }
    }
}
