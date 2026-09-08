/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.engine

import android.os.Handler
import android.view.DragEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.navis.browser.api.LoadingState
import org.navis.browser.api.ContentTermination
import org.navis.browser.api.PromptResponse
import org.navis.browser.api.SecurityState
import org.navis.browser.api.SessionId
import org.navis.browser.api.SessionMode
import org.navis.browser.api.TargetRequestId
import org.navis.browser.core.CoreBridge
import org.navis.browser.core.CoreNavigationCommand
import org.navis.browser.core.CoreNavigationIdentity
import org.navis.browser.core.CoreNavigationSecurity
import org.navis.browser.core.CoreNavigationSnapshot
import org.navis.browser.core.CoreNavigationStartKind
import org.navis.browser.engine.runtime.EngineProjectionMatrix
import org.navis.browser.engine.runtime.EngineRuntimePort
import org.navis.browser.engine.runtime.EngineLoadResult
import org.navis.browser.engine.runtime.EngineSecurityState
import org.navis.browser.engine.runtime.EngineSessionObserver
import org.navis.browser.engine.runtime.EngineSessionPort
import org.navis.browser.engine.runtime.EngineTargetObserver
import org.navis.browser.engine.runtime.EngineTargetPort

class AndroidSessionTest {
    @Test fun aLateSourceSnapshotCannotStealThePendingNativeToWebBranch() {
        val fixture = Fixture()
        fixture.session.open(null, "https://a.example/")
        val engine = fixture.engine.session
        engine.observer.onHostReady()
        engine.commit(listOf(1L to "https://a.example/"), 0)
        fixture.session.load("navis://history/")
        fixture.session.load("https://b.example/")
        engine.emitCurrentSnapshot()
        engine.commit(listOf(1L to "https://a.example/", 2L to "https://b.example/"), 1)
        assertEquals(listOf("https://a.example/", "navis://history/", "https://b.example/"),
            fixture.session.persistedNativeHistory()!!.entries)
        fixture.session.goBack()
        assertEquals("history", fixture.session.state().nativeRoute)
    }

    @Test fun uncorrelatedTitlesCannotOverwriteTheCurrentDocumentOrNativeIdentity() {
        val fixture = Fixture()
        fixture.session.open(null, "https://a.example/")
        val engine = fixture.engine.session
        engine.observer.onHostReady()
        engine.commit(listOf(1L to "https://a.example/"), 0)
        engine.observer.onTitleChanged("Legacy")
        engine.observer.onTitleChanged("Old document", "https://old.example/", 1)
        engine.observer.onTitleChanged("Old generation", "https://a.example/", 2)
        assertEquals("Page https://a.example/", fixture.session.state().navigation.title)
        fixture.session.load("navis://settings/")
        engine.observer.onTitleChanged("Late webpage", "https://a.example/", 1)
        assertEquals("Navis", fixture.session.state().navigation.title)
    }
    @Test fun mixedHistoryUsesOneSessionAndRealTraversalInBothDirections() {
        val fixture = Fixture()
        fixture.session.open(null, "https://a.example/")
        val engine = fixture.engine.session
        engine.observer.onHostReady()
        engine.commit(listOf(1L to "https://a.example/"), 0)
        fixture.session.load("navis://settings/")
        fixture.session.load("navis://history/")
        fixture.session.load("https://b.example/")
        assertEquals("", fixture.session.state().navigation.title)
        engine.commit(listOf(1L to "https://a.example/", 2L to "https://b.example/"), 1)
        val loads = engine.loadedUris.toList()
        fixture.session.goBack()
        assertEquals("history", fixture.session.state().nativeRoute)
        fixture.session.goBack()
        assertEquals("settings", fixture.session.state().nativeRoute)
        fixture.session.goBack()
        assertEquals(null, fixture.session.state().nativeRoute)
        assertEquals("https://a.example/", fixture.session.state().navigation.url)
        assertEquals(listOf(0), engine.realTraversals)
        fixture.session.goForward()
        assertEquals("settings", fixture.session.state().nativeRoute)
        fixture.session.goForward()
        assertEquals("history", fixture.session.state().nativeRoute)
        fixture.session.goForward()
        assertEquals("https://b.example/", fixture.session.state().navigation.url)
        assertEquals(listOf(0, 1), engine.realTraversals)
        assertEquals(loads, engine.loadedUris)
        assertEquals(0, engine.backs)
        assertEquals(0, engine.forwards)
        assertEquals(1, fixture.engine.createdSessions)
    }

    @Test fun returningFromNativeOverlayKeepsTheSameLiveWebDocument() {
        val fixture = Fixture()
        fixture.session.open(null, "https://a.example/")
        val engine = fixture.engine.session
        engine.observer.onHostReady()
        engine.commit(listOf(1L to "https://a.example/"), 0)
        val visits = fixture.owner.visits.toList()
        fixture.session.load("navis://bookmarks/")
        fixture.session.goBack()
        assertEquals("https://a.example/", fixture.session.state().navigation.url)
        assertEquals("Page https://a.example/", fixture.session.state().navigation.title)
        assertEquals(SecurityState.SECURE, fixture.session.state().navigation.security)
        assertTrue(engine.realTraversals.isEmpty())
        assertEquals(1, engine.loadedUris.size)
        assertEquals(visits, fixture.owner.visits)
    }

    @Test fun nativeLinkAfterBackBranchesFromItsActualPredecessor() {
        val fixture = Fixture()
        fixture.session.open(null, "https://a.example/")
        val engine = fixture.engine.session
        engine.observer.onHostReady()
        engine.commit(listOf(1L to "https://a.example/"), 0)
        fixture.session.load("navis://history/")
        fixture.session.load("https://b.example/")
        engine.commit(listOf(1L to "https://a.example/", 2L to "https://b.example/"), 1)
        fixture.session.goBack()
        fixture.session.load("https://c.example/")
        assertEquals(listOf(0), engine.realTraversals)
        assertEquals(0, engine.historyIndexWhenLoaded.last())
        engine.commit(listOf(1L to "https://a.example/", 3L to "https://c.example/"), 1)
        assertEquals(listOf("https://a.example/", "navis://history/", "https://c.example/"),
            fixture.session.persistedNativeHistory()!!.entries)
        fixture.session.goBack()
        assertEquals("history", fixture.session.state().nativeRoute)
        fixture.session.goBack()
        assertEquals("https://a.example/", fixture.session.state().navigation.url)
    }

    @Test fun nativeRootNewLinkUsesEngineReplaceRatherThanRetainingAnOldFirstWebpage() {
        val fixture = Fixture()
        fixture.session.open(null, "navis://newtab/")
        val engine = fixture.engine.session
        engine.observer.onHostReady()
        fixture.session.load("https://b.example/")
        engine.commit(listOf(2L to "https://b.example/"), 0)
        fixture.session.goBack()
        fixture.session.load("https://c.example/")
        assertEquals(2, engine.rootReplacements)
        engine.commit(listOf(3L to "https://c.example/"), 0)
        assertEquals(listOf("navis://newtab/", "https://c.example/"), fixture.session.persistedNativeHistory()!!.entries)
    }

    @Test fun mixedNativeSnapshotRestoresTheBoundEngineAndRekeysOnlyMatchingHistory() {
        val original = Fixture()
        original.session.open(null, "https://a.example/")
        original.engine.session.observer.onHostReady()
        original.engine.session.commit(listOf(1L to "https://a.example/"), 0)
        original.session.load("navis://settings/")
        original.session.load("navis://history/")
        original.session.load("https://b.example/")
        original.engine.session.commit(listOf(1L to "https://a.example/", 2L to "https://b.example/"), 1)
        original.session.goBack()
        val state = original.session.persistedEngineState()!!
        val native = original.session.persistedNativeHistory()!!
        val restored = Fixture()
        restored.session.open(state, "navis://history/", native)
        assertEquals(state, restored.engine.session.restoredState)
        restored.engine.session.observer.onHostReady()
        restored.engine.session.commit(listOf(21L to "https://a.example/", 22L to "https://b.example/"), 1)
        assertEquals("history", restored.session.state().nativeRoute)
        restored.session.goBack()
        assertEquals("settings", restored.session.state().nativeRoute)
        restored.session.goBack()
        assertEquals("https://a.example/", restored.session.state().navigation.url)
        assertEquals(listOf(0), restored.engine.session.realTraversals)
        assertTrue(restored.engine.session.loadedUris.isEmpty())

        val mismatched = Fixture()
        mismatched.session.open(state.replace("a.example", "unrelated.example"), "navis://history/", native)
        assertEquals(null, mismatched.engine.session.restoredState)
        assertFalse(mismatched.session.state().navigation.canGoBack)
    }

    @Test fun privateMixedHistoryStaysWithinOnePrivateOwnerAndNeverPersists() {
        val fixture = Fixture(SessionMode.PRIVATE)
        fixture.session.open(null, "https://private.example/")
        val engine = fixture.engine.session
        engine.observer.onHostReady()
        engine.commit(listOf(1L to "https://private.example/"), 0)
        fixture.session.load("navis://bookmarks/")
        fixture.session.load("https://private-next.example/")
        engine.commit(listOf(1L to "https://private.example/", 2L to "https://private-next.example/"), 1)
        fixture.session.goBack()
        fixture.session.goBack()
        assertEquals("https://private.example/", fixture.session.state().navigation.url)
        assertEquals(null, fixture.session.persistedEngineState())
        assertEquals(null, fixture.session.persistedNativeHistory())
        assertTrue(fixture.owner.visits.isEmpty())
        assertEquals(1, fixture.engine.createdSessions)
    }

    @Test fun nativePagesNavigateRealHistoryWithoutEngineBackOrLoad() {
        val fixture = Fixture()
        fixture.session.open(null, "navis://settings/")
        fixture.session.load("navis://downloads/")
        assertTrue(fixture.session.state().navigation.canGoBack)
        fixture.session.goBack()
        assertEquals("settings", fixture.session.state().nativeRoute)
        assertTrue(fixture.session.state().navigation.canGoForward)
        fixture.session.goForward()
        assertEquals("downloads", fixture.session.state().nativeRoute)
        fixture.session.goBack()
        fixture.session.load("navis://settings/help")
        fixture.session.load("navis://support/")
        fixture.session.goBack()
        assertEquals("help", fixture.session.state().nativeRoute)
        assertEquals(emptyList<String>(), fixture.engine.session.loadedUris)
        assertEquals(0, fixture.engine.session.backs)
        assertEquals(0, fixture.engine.session.forwards)
        assertTrue(fixture.owner.visits.isEmpty())
    }

    @Test fun nativeHistoryRestoreKeepsIndexButNeverRestoresOldWebBehindIt() {
        val fixture = Fixture()
        val saved = org.navis.browser.pages.NativePageHistorySnapshot(
            listOf("navis://urls/", "navis://downloads/", "navis://bookmarks/"), 1)
        fixture.session.open("legacy-web-blob", "navis://downloads/", saved)
        assertEquals(null, fixture.engine.session.restoredState)
        assertTrue(fixture.session.state().navigation.canGoBack)
        assertTrue(fixture.session.state().navigation.canGoForward)
        fixture.session.goBack()
        assertEquals("urls", fixture.session.state().nativeRoute)
        assertEquals(0, fixture.session.persistedNativeHistory()?.index)
        assertTrue(fixture.engine.session.loadedUris.isEmpty())
    }

    @Test fun orphanNativeRootReturnsToNewTabWithoutCreatingABackLoop() {
        val fixture = Fixture()
        fixture.session.open(null, "navis://downloads/")
        fixture.session.resetToNativeNewTab()
        assertEquals("newtab", fixture.session.state().nativeRoute)
        assertFalse(fixture.session.state().navigation.canGoBack)
        assertFalse(fixture.session.state().navigation.canGoForward)
        assertTrue(fixture.engine.session.loadedUris.isEmpty())
    }

    @Test fun privateNativeHistoryWorksInMemoryButIsNeverPersisted() {
        val fixture = Fixture(SessionMode.PRIVATE)
        fixture.session.open(null, "navis://settings/")
        fixture.session.load("navis://passwords/")
        fixture.session.goBack()
        assertEquals("settings", fixture.session.state().nativeRoute)
        assertEquals(null, fixture.session.persistedNativeHistory())
        assertEquals(null, fixture.session.persistedEngineState())
    }

    @Test
    fun directCallbacksDriveTheNavisNavigationContract() {
        val fixture = Fixture()
        fixture.session.open(null, "https://example.com")
        val engine = fixture.engine.session

        engine.observer.onLoadStarted(1, "https://example.com")
        engine.observer.onLocationChanged("https://example.com/path", 1, false, false)
        engine.observer.onTitleChanged("Example", "https://example.com/path", 1)
        engine.observer.onHistoryChanged(canGoBack = true, canGoForward = false)
        engine.observer.onSecurityChanged(EngineSecurityState.SECURE)
        engine.observer.onLoadCompleted(result(1))

        val state = fixture.session.state()
        assertEquals("https://example.com/path", state.navigation.url)
        assertEquals("Example", state.navigation.title)
        assertEquals(SecurityState.SECURE, state.navigation.security)
        assertEquals(LoadingState.IDLE, state.navigation.loading)
        assertTrue(state.navigation.canGoBack)
        assertFalse(state.navigation.canGoForward)
        assertEquals(
            listOf("https://example.com/path" to "Example"),
            fixture.owner.visits,
        )
    }

    @Test
    fun failedAndDuplicateTerminalEventsDoNotCreateHistoryVisits() {
        val fixture = Fixture()
        fixture.session.open(null, "https://example.com")
        val observer = fixture.engine.session.observer

        observer.onLoadStarted(1, "https://failed.example/")
        observer.onLocationChanged("https://failed.example/", 1, false, true)
        observer.onLoadCompleted(result(1, status = 0x804b001e.toInt(), errorPage = true))
        assertTrue(fixture.owner.visits.isEmpty())
        assertEquals(0x804b001e.toInt(), fixture.session.state().navigation.failureCode)
        assertTrue(fixture.session.state().navigation.engineErrorPage)
        assertFalse(fixture.session.state().navigation.crashed)

        fixture.session.load("https://ok.example/")
        observer.onLoadStarted(2, "https://ok.example/")
        observer.onLocationChanged("https://ok.example/", 2, false, false)
        observer.onTitleChanged("OK", "https://ok.example/", 2)
        observer.onLoadCompleted(result(2))
        observer.onLoadCompleted(result(2))

        assertEquals(listOf("https://ok.example/" to "OK"), fixture.owner.visits)
    }

    @Test
    fun privateSessionNeverCreatesHistoryVisits() {
        val fixture = Fixture(SessionMode.PRIVATE)
        fixture.session.open(null, "https://private.example/")
        val observer = fixture.engine.session.observer

        observer.onLoadStarted(1, "https://private.example/")
        observer.onLocationChanged("https://private.example/", 1, false, false)
        observer.onTitleChanged("Private", "https://private.example/", 1)
        observer.onLoadCompleted(result(1))

        assertTrue(fixture.owner.visits.isEmpty())
    }

    @Test
    fun engineDrivenLinksAllocateFreshNavigationAndHistoryVisits() {
        val fixture = Fixture()
        fixture.session.open(null, "https://first.example/")
        val observer = fixture.engine.session.observer
        observer.onLoadStarted(1, "https://first.example/")
        observer.onLocationChanged("https://first.example/", 1, false, false)
        observer.onLoadCompleted(result(1))
        val first = fixture.core.navigationSnapshot(11).navigationId

        observer.onLoadStarted(2, "https://second.example/")
        observer.onLocationChanged("https://second.example/", 2, false, false)
        assertTrue(fixture.core.navigationSnapshot(11).navigationId > first)
        assertEquals(LoadingState.VISIBLE, fixture.session.state().navigation.loading)
        observer.onLoadCompleted(result(2))
        assertEquals(2, fixture.owner.visits.size)
    }

    @Test
    fun cancelledUnknownAndUncorrelatedStopsDoNotClaimSuccessOrFailure() {
        for (terminal in listOf(
            result(1, status = 0x804b0002.toInt(), cancelled = true),
            result(1, status = 0x804b0004.toInt(), cancelled = true),
            result(1, status = null), result(0), result(0, status = 0x804b001e.toInt()),
        )) {
            val fixture = Fixture()
            fixture.session.open(null, "https://example.com/")
            val observer = fixture.engine.session.observer
            observer.onLoadStarted(1, "https://example.com/")
            observer.onLoadCompleted(terminal)
            val state = fixture.session.state().navigation
            assertEquals(LoadingState.IDLE, state.loading)
            assertEquals(null, state.failureCode)
            assertFalse(state.crashed)
            assertTrue(fixture.owner.visits.isEmpty())
        }
    }

    @Test
    fun userStopAndReplacedNavigationIgnoreLateCompletions() {
        val fixture = Fixture()
        fixture.session.open(null, "https://first.example/")
        val observer = fixture.engine.session.observer
        observer.onLoadStarted(1, "https://first.example/")
        fixture.session.stop()
        observer.onLoadCompleted(result(1, status = 0x804b001e.toInt()))
        assertEquals(null, fixture.session.state().navigation.failureCode)
        assertEquals(LoadingState.IDLE, fixture.session.state().navigation.loading)

        fixture.session.load("https://second.example/")
        observer.onLoadCompleted(result(1))
        assertEquals(LoadingState.PENDING, fixture.session.state().navigation.loading)
        observer.onLoadStarted(2, "https://second.example/")
        observer.onLoadCompleted(result(1))
        assertEquals(LoadingState.VISIBLE, fixture.session.state().navigation.loading)
        observer.onLoadCompleted(result(2))
        assertEquals(1, fixture.owner.visits.size)
    }

    @Test
    fun renderedErrorDocumentIsNotAVisitEvenWhenItsOwnChannelSucceeds() {
        val fixture = Fixture()
        fixture.session.open(null, "https://failed.example/")
        val observer = fixture.engine.session.observer
        observer.onLoadStarted(1, "https://failed.example/")
        observer.onLocationChanged("https://failed.example/", 1, false, true)
        observer.onLoadCompleted(result(1, errorPage = true))
        assertTrue(fixture.session.state().navigation.engineErrorPage)
        assertTrue(fixture.owner.visits.isEmpty())
        assertEquals(null, fixture.session.state().navigation.failureCode)
    }

    @Test
    fun sameDocumentLocationDoesNotCreateASecondLoadOrLoseErrorIdentity() {
        val fixture = Fixture()
        fixture.session.open(null, "https://example.com/")
        val observer = fixture.engine.session.observer
        observer.onLoadStarted(1, "https://example.com/")
        observer.onLoadCompleted(result(1))
        val navigationId = fixture.core.navigationSnapshot(11).navigationId
        observer.onLocationChanged("https://example.com/#fragment", 1, true, false)
        assertEquals(navigationId, fixture.core.navigationSnapshot(11).navigationId)
        assertEquals(LoadingState.IDLE, fixture.session.state().navigation.loading)
        assertEquals(1, fixture.owner.visits.size)
    }

    private fun result(
        generation: Long, status: Int? = 0, cancelled: Boolean = false, errorPage: Boolean = false,
    ) = EngineLoadResult(generation, status, cancelled, errorPage, "")

    @Test
    fun onlyNormalOpenSessionsFlushRestorableEngineState() {
        val normal = Fixture(SessionMode.NORMAL)
        normal.session.open(null, null)
        normal.session.flushEngineState()
        assertEquals(1, normal.engine.session.flushes)

        val privateSession = Fixture(SessionMode.PRIVATE)
        privateSession.session.open(null, null)
        privateSession.session.flushEngineState()
        assertEquals(0, privateSession.engine.session.flushes)
    }

    @Test
    fun opaqueRestoreIsOwnedByTheDirectSessionPort() {
        val fixture = Fixture()

        fixture.session.open("legacy opaque state", "https://restore.example/")

        assertTrue(fixture.engine.session.opened)
        assertEquals("legacy opaque state", fixture.engine.session.restoredState)
        assertTrue(fixture.engine.session.loadedUris.isEmpty())
        assertEquals("legacy opaque state", fixture.session.persistedEngineState())

        fixture.engine.session.observer.onSessionStateChanged("fresh opaque state")
        assertEquals("fresh opaque state", fixture.session.persistedEngineState())
    }

    @Test
    fun privateSessionStateCallbackNeverBecomesRestorableState() {
        val fixture = Fixture(SessionMode.PRIVATE)
        fixture.session.open(null, "https://private.example/")

        fixture.engine.session.observer.onSessionStateChanged("private opaque state")

        assertEquals(null, fixture.session.persistedEngineState())
    }

    @Test
    fun nativeNewTabDoesNotRestoreALegacyWebSnapshotOrFlushIt() {
        val fixture = Fixture()
        fixture.session.setInitialNativeNewTab(true)
        fixture.session.open("legacy webpage history", null)
        assertEquals(null, fixture.engine.session.restoredState)
        assertTrue(fixture.engine.session.loadedUris.isEmpty())
        assertEquals("newtab", fixture.session.state().nativeRoute)
        fixture.engine.session.observer.onSessionStateChanged("late old webpage history")
        fixture.session.flushEngineState()
        assertEquals(0, fixture.engine.session.flushes)
        assertEquals(null, fixture.session.persistedEngineState())
    }

    @Test
    fun nativeSettingsRouteWinsOverLegacyOpaqueHistoryAtOpen() {
        val fixture = Fixture()
        fixture.session.open("legacy webpage history", "navis://settings/")
        assertEquals(null, fixture.engine.session.restoredState)
        assertTrue(fixture.engine.session.loadedUris.isEmpty())
        assertEquals("settings", fixture.session.state().nativeRoute)
        assertEquals("navis://settings/", fixture.session.state().navigation.url)
        assertEquals(null, fixture.session.persistedEngineState())
    }

    @Test
    fun enteringNativePageDropsOnlyThatSessionsOldOpaqueSnapshot() {
        val fixture = Fixture()
        fixture.session.open("existing webpage history", "https://old.example/")
        fixture.session.load("navis://settings/")
        fixture.engine.session.observer.onSessionStateChanged("late webpage history")
        fixture.session.flushEngineState()
        assertEquals(0, fixture.engine.session.flushes)
        fixture.session.load("https://new.example/")
        assertEquals(null, fixture.session.persistedEngineState())
        assertEquals(listOf("https://new.example/"), fixture.engine.session.loadedUris)
        fixture.engine.session.observer.onSessionStateChanged("new webpage history")
        assertEquals("new webpage history", fixture.session.persistedEngineState())
    }

    @Test
    fun compositorSurfaceRequestsStayOnTheProductOwner() {
        val fixture = Fixture()
        fixture.session.open(null, null)

        fixture.engine.session.observer.onNativeWindowReady()
        fixture.engine.session.observer.onNewSurfaceRequired()

        assertEquals(listOf(SessionId(11), SessionId(11)), fixture.owner.surfaceRequests)
    }

    @Test
    fun chromeHostReadinessIsForwardedOnlyForALiveSession() {
        val fixture = Fixture()
        fixture.session.open(null, null)

        fixture.engine.session.observer.onHostReady()
        fixture.owner.live = false
        fixture.engine.session.observer.onHostReady()

        assertEquals(listOf(SessionId(11)), fixture.owner.hostReadySessions)
    }

    @Test
    fun unexpectedNativeErrorMarksSessionCrashedOnce() {
        val fixture = Fixture()
        fixture.session.open(null, "https://example.com")

        fixture.engine.session.observer.onSessionError("native failure")
        fixture.engine.session.observer.onSessionError("duplicate")

        assertTrue(fixture.session.state().navigation.crashed)
        assertEquals(listOf(SessionId(11)), fixture.owner.crashes)
    }

    @Test
    fun crashedReloadWaitsForCloseThenCreatesOneReplacementSession() {
        val fixture = Fixture()
        fixture.session.open(null, "https://example.com")
        val failed = fixture.engine.session
        failed.observer.onContentProcessGone(crashed = true)

        fixture.session.reload()

        assertEquals(1, fixture.engine.createdSessions)
        assertTrue(fixture.session.state().navigation.crashed)
        failed.observer.onSessionClosed()

        assertEquals(2, fixture.engine.createdSessions)
        assertFalse(fixture.session.state().navigation.crashed)
        assertEquals(ContentTermination.NONE, fixture.session.state().navigation.contentTermination)
        assertEquals(listOf("https://example.com"), fixture.engine.session.loadedUris)
        assertEquals(listOf(true, false), fixture.core.crashStates)
    }

    @Test
    fun normalTerminationIsDistinctFromCrashAndStillRecoverable() {
        val fixture = Fixture()
        fixture.session.open(null, "https://example.com")
        val terminated = fixture.engine.session

        terminated.observer.onContentProcessGone(crashed = false)

        assertEquals(
            ContentTermination.TERMINATED,
            fixture.session.state().navigation.contentTermination,
        )
        assertFalse(fixture.session.state().navigation.crashed)
        assertTrue(fixture.owner.crashes.isEmpty())

        fixture.session.reload()
        terminated.observer.onSessionClosed()

        assertEquals(ContentTermination.NONE, fixture.session.state().navigation.contentTermination)
        assertEquals(2, fixture.engine.createdSessions)
    }

    @Test
    fun fullscreenFactAndExitRemainOnTheOwningSession() {
        val fixture = Fixture()
        fixture.session.open(null, "https://example.com")

        fixture.engine.session.observer.onFullscreenChanged(enabled = true)

        assertEquals(SessionId(11), fixture.target.state.fullscreenSessionId)
        fixture.target.exitFullscreen()
        assertEquals(1, fixture.engine.session.fullscreenExits)
        assertEquals(null, fixture.target.state.fullscreenSessionId)
    }

    @Test
    fun newWindowRequestIsForwardedAsAnOpaqueEngineTransaction() {
        val fixture = Fixture()
        fixture.session.open(null, "https://example.com")
        fixture.owner.newWindowResult = CompletableFuture.completedFuture(true)

        val result = fixture.engine.session.observer.onNewWindowRequested(
            "https://child.example/",
            "00000000000000000000000000000000",
            privateMode = true,
        )

        assertTrue(result.toCompletableFuture().join())
        assertEquals(
            listOf(
                "11:https://child.example/:00000000000000000000000000000000:true",
            ),
            fixture.owner.newWindowRequests,
        )
    }

    private class Fixture(mode: SessionMode = SessionMode.NORMAL) {
        val core = FakeCoreBridge()
        val engine = FakeEngineRuntime()
        val owner = FakeOwner()
        val target = AndroidTargetDelegate(
            isSessionLive = owner::isLive,
            projections = engine.projections,
            exitFullscreenCommand = { sessionId ->
                if (sessionId == SessionId(11)) {
                    engine.session.exitFullscreen()
                }
            },
            enginePort = FakeEngineTargetPort(),
        ).also { owner.target = it }
        val session = AndroidSession(
            id = SessionId(11),
            viewId = 3,
            mode = mode,
            core = core,
            engineRuntime = engine,
            targetDelegate = target,
            owner = owner,
        )
    }

    private class FakeEngineRuntime : EngineRuntimePort {
        override val projections = EngineProjectionMatrix.DIRECT_RUNTIME_V1
        lateinit var session: FakeEngineSession
        val sessions = mutableListOf<FakeEngineSession>()
        var createdSessions = 0

        override fun createSession(
            sessionId: Long,
            observer: EngineSessionObserver,
        ): EngineSessionPort = FakeEngineSession(observer).also {
            createdSessions += 1
            session = it
            sessions += it
        }


        override fun close() = Unit
    }

    private class FakeEngineTargetPort : EngineTargetPort {
        override fun bind(observer: EngineTargetObserver) = Unit

        override fun unbind(observer: EngineTargetObserver) = Unit

        override fun respondToPrompt(id: TargetRequestId, response: PromptResponse) = Unit

        override fun notifySitePermissionShown(id: TargetRequestId) = Unit

        override fun respondToSitePermission(id: TargetRequestId, allow: Boolean) = Unit

        override fun respondToPlatformPermission(id: TargetRequestId, granted: Boolean) = Unit

        override fun respondToFilePicker(id: TargetRequestId, uris: List<String>) = Unit

        override fun cancelRequest(id: TargetRequestId) = Unit

        override fun exitFullscreen(sessionId: SessionId) = Unit
    }

    private class FakeEngineSession(
        val observer: EngineSessionObserver,
    ) : EngineSessionPort {
        var opened = false
        var reloads = 0
        var backs = 0
        var forwards = 0
        var restoredState: String? = null
        var flushes = 0
        var fullscreenExits = 0
        var newWindowToken: String? = null
        val loadedUris = mutableListOf<String>()
        val realTraversals = mutableListOf<Int>()
        val historyIndexWhenLoaded = mutableListOf<Int>()
        var rootReplacements = 0
        private var nativePresentation = true
        private var historyState: String? = null
        private var generation = 0L

        fun emitCurrentSnapshot() { observer.onSessionStateChanged(checkNotNull(historyState)) }

        fun commit(entries: List<Pair<Long, String>>, index: Int) {
            historyState = JSONObject().put("version", 1).put("history", JSONObject()
                .put("index", index + 1).put("entries", JSONArray().apply {
                    entries.forEach { (id, uri) -> put(JSONObject().put("ID", id).put("url", uri)) }
                })).toString()
            emitCommit()
        }

        private fun emitCommit() {
            val history = JSONObject(checkNotNull(historyState)).getJSONObject("history")
            val uri = history.getJSONArray("entries").getJSONObject(history.getInt("index") - 1).getString("url")
            ++generation
            observer.onLoadStarted(generation, uri)
            observer.onLocationChanged(uri, generation, false, false)
            observer.onTitleChanged("Page $uri", uri, generation)
            observer.onLoadCompleted(EngineLoadResult(generation, 0, false, false, uri))
            observer.onSessionStateChanged(checkNotNull(historyState))
        }

        private fun presentationReply(): JSONObject {
            val history = historyState?.let { JSONObject(it).getJSONObject("history") }
            val uri = history?.getJSONArray("entries")?.getJSONObject(history.getInt("index") - 1)?.getString("url") ?: "about:blank"
            return JSONObject().put("state", historyState ?: JSONObject.NULL).put("current", JSONObject()
                .put("uri", uri).put("title", "Page $uri").put("security", 3))
        }

        override fun querySession(operation: String, payload: String): CompletionStage<String> = try {
            val data = JSONObject(payload)
            val result = when (operation) {
                "session:presentation" -> {
                    nativePresentation = data.getBoolean("native")
                    presentationReply()
                }
                "session:history" -> presentationReply()
                "session:traverse" -> {
                    val root = JSONObject(checkNotNull(historyState))
                    val history = root.getJSONObject("history")
                    val target = data.getInt("index")
                    check(history.getJSONArray("entries").getJSONObject(target).getLong("ID") == data.getLong("entryId"))
                    nativePresentation = data.optBoolean("native")
                    val moved = history.getInt("index") - 1 != target
                    if (moved) {
                        realTraversals += target
                        history.put("index", target + 1)
                        historyState = root.toString()
                        emitCommit()
                    }
                    presentationReply().put("traversed", moved)
                }
                "session:load" -> {
                    check(nativePresentation)
                    val index = historyState?.let { JSONObject(it).getJSONObject("history").getInt("index") - 1 } ?: -1
                    if (data.optBoolean("root")) { check(index <= 0); rootReplacements++ }
                    else check(index == data.getInt("index"))
                    loadUri(data.getString("uri"))
                    JSONObject().put("loaded", true)
                }
                else -> error("Unexpected Session query $operation")
            }
            CompletableFuture.completedFuture(result.toString())
        } catch (error: Throwable) { CompletableFuture.failedFuture(error) }

        override val isOpen: Boolean
            get() = opened

        override fun open(privateMode: Boolean, restoredState: String?) {
            opened = true
            this.restoredState = restoredState
            if (org.navis.browser.pages.EnginePageHistory.parse(restoredState) != null) historyState = restoredState
        }

        override fun flushSessionState() {
            flushes += 1
        }

        override fun loadUri(uri: String) {
            loadedUris += uri
            nativePresentation = false
            historyIndexWhenLoaded += historyState?.let { JSONObject(it).getJSONObject("history").getInt("index") - 1 } ?: -1
        }

        override fun reload() {
            reloads += 1
        }

        override fun stop() = Unit

        override fun goBack() { backs++ }

        override fun goForward() { forwards++ }

        override fun setActive(active: Boolean) = Unit

        override fun openNewWindow(
            privateMode: Boolean,
            engineWindowToken: String,
        ): CompletionStage<Boolean> {
            opened = true
            newWindowToken = engineWindowToken
            return CompletableFuture.completedFuture(true)
        }

        override fun exitFullscreen() {
            fullscreenExits += 1
        }

        override fun respondToContextMenu(token: String, itemId: String?): Boolean = false

        override fun dispatchHardwareShortcut(shortcut: String) = Unit

        override fun isHardwareShortcutOwned(shortcut: String): Boolean = false

        override fun attachSurface(
            displayId: Int,
            x: Int,
            y: Int,
            width: Int,
            height: Int,
            surface: Any,
        ) = Unit

        override fun detachSurface() = Unit

        override fun updateBounds(left: Int, top: Int, width: Int, height: Int) = Unit

        override fun attachInputView(inputView: View, accessibilityView: View) = Unit

        override fun detachInputView() = Unit

        override fun inputConnectionHandler(defaultHandler: Handler?): Handler? = defaultHandler

        override fun createInputConnection(attributes: EditorInfo): InputConnection? = null

        override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean = false

        override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = false

        override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = false

        override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean = false

        override fun onKeyMultiple(
            keyCode: Int,
            repeatCount: Int,
            event: KeyEvent,
        ): Boolean = false

        override fun onTouchEvent(event: MotionEvent): Boolean = false

        override fun onGenericMotionEvent(event: MotionEvent): Boolean = false

        override fun onDragEvent(event: DragEvent): Boolean = false

        override fun close() {
            opened = false
        }
    }

    private class FakeOwner : AndroidSession.Owner {
        var live = true
        val visits = mutableListOf<Pair<String, String>>()
        val crashes = mutableListOf<SessionId>()
        val surfaceRequests = mutableListOf<SessionId>()
        val hostReadySessions = mutableListOf<SessionId>()
        val fullscreenChanges = mutableListOf<Pair<SessionId, Boolean>>()
        val newWindowRequests = mutableListOf<String>()
        var newWindowResult: CompletionStage<Boolean> = CompletableFuture.completedFuture(false)
        lateinit var target: AndroidTargetDelegate

        override fun isLive(sessionId: SessionId): Boolean = live

        override fun onSessionChanged(sessionId: SessionId, persist: Boolean) = Unit

        override fun onSessionCrashed(sessionId: SessionId) {
            crashes += sessionId
        }

        override fun onHistoryVisit(sessionId: SessionId, url: String, title: String) {
            visits += url to title
        }

        override fun onRenderSurfaceRequired(sessionId: SessionId) {
            surfaceRequests += sessionId
        }

        override fun onHostReady(sessionId: SessionId) {
            hostReadySessions += sessionId
        }

        override fun onFullscreenChanged(sessionId: SessionId, enabled: Boolean) {
            fullscreenChanges += sessionId to enabled
            target.onFullscreenChanged(sessionId, enabled)
        }

        override fun onNewWindowRequested(
            openerSessionId: SessionId,
            uri: String,
            engineWindowToken: String,
            privateMode: Boolean,
        ): CompletionStage<Boolean> {
            newWindowRequests +=
                "${openerSessionId.value}:$uri:$engineWindowToken:$privateMode"
            return newWindowResult
        }
    }

    private class FakeCoreBridge : CoreBridge {
        private var nextNavigationId = 1L
        private var snapshot = CoreNavigationSnapshot(
            revision = 0,
            navigationId = 0,
            url = "",
            title = "",
            activity = 0,
            security = 0,
            identity = 0,
            identityKey = "",
            canGoBack = false,
            canGoForward = false,
            hasFailure = false,
            failureCode = 0,
        )
        val crashStates = mutableListOf<Boolean>()

        override fun registerWindow(): Long = 1

        override fun closeWindow(windowId: Long) = Unit

        override fun registerView(windowId: Long): Long = 1

        override fun createSession(viewId: Long, privateMode: Boolean): Long = 11

        override fun activateSession(sessionId: Long): Boolean = true

        override fun closeSession(sessionId: Long) = Unit

        override fun setCrashed(sessionId: Long, crashed: Boolean) {
            crashStates += crashed
        }

        override fun beginNavigation(
            sessionId: Long,
            command: CoreNavigationCommand,
            requestedUri: String?,
        ): Long = nextNavigation(requestedUri, activity = 1)

        override fun observeNavigationStart(
            sessionId: Long,
            requestedUri: String?,
            kind: CoreNavigationStartKind,
        ): Long = if (snapshot.activity == 1 || snapshot.activity == 2) {
            snapshot = snapshot.copy(activity = 2)
            snapshot.navigationId
        } else nextNavigation(requestedUri, activity = 2)

        override fun setNavigationLocation(
            sessionId: Long,
            navigationId: Long,
            uri: String,
        ): Boolean {
            snapshot = snapshot.copy(revision = snapshot.revision + 1, url = uri)
            return true
        }

        override fun setNavigationTitle(
            sessionId: Long,
            navigationId: Long,
            title: String,
        ): Boolean {
            snapshot = snapshot.copy(revision = snapshot.revision + 1, title = title)
            return true
        }

        override fun setNavigationSecurity(
            sessionId: Long,
            navigationId: Long,
            security: CoreNavigationSecurity,
        ): Boolean {
            snapshot = snapshot.copy(revision = snapshot.revision + 1, security = security.wireValue)
            return true
        }

        override fun setNavigationIdentity(
            sessionId: Long,
            navigationId: Long,
            identity: CoreNavigationIdentity,
            identityKey: String,
        ): Boolean {
            snapshot = snapshot.copy(
                revision = snapshot.revision + 1,
                identity = identity.wireValue,
                identityKey = identityKey,
            )
            return true
        }

        override fun setNavigationHistory(
            sessionId: Long,
            navigationId: Long,
            canGoBack: Boolean,
            canGoForward: Boolean,
        ): Boolean {
            snapshot = snapshot.copy(
                revision = snapshot.revision + 1,
                canGoBack = canGoBack,
                canGoForward = canGoForward,
            )
            return true
        }

        override fun finishNavigation(
            sessionId: Long,
            navigationId: Long,
            failureCode: Int?,
        ): Boolean {
            if (navigationId != snapshot.navigationId || snapshot.activity == 0) return false
            snapshot = snapshot.copy(
                revision = snapshot.revision + 1,
                activity = 0,
                hasFailure = failureCode != null,
                failureCode = failureCode ?: 0,
            )
            return true
        }

        override fun stopNavigation(sessionId: Long, navigationId: Long): Boolean {
            if (navigationId != snapshot.navigationId || snapshot.activity == 0) return false
            snapshot = snapshot.copy(revision = snapshot.revision + 1, activity = 0)
            return true
        }

        override fun navigationSnapshot(sessionId: Long): CoreNavigationSnapshot = snapshot

        override fun checkInvariants() = Unit

        override fun shutdown(): Boolean = true

        override fun close() = Unit

        private fun nextNavigation(uri: String?, activity: Int): Long {
            val id = nextNavigationId++
            snapshot = snapshot.copy(
                revision = snapshot.revision + 1,
                navigationId = id,
                url = uri ?: snapshot.url,
                activity = activity,
                hasFailure = false,
                failureCode = 0,
                identity = 0,
            )
            return id
        }
    }
}
