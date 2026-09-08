/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.engine

import android.content.Context
import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.lang.ref.WeakReference
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.navis.browser.api.BrowserDelegate
import org.navis.browser.api.BrowserRuntime
import org.navis.browser.api.BrowserState
import org.navis.browser.api.BrowserStateObserver
import org.navis.browser.api.BrowserTargetRuntime
import org.navis.browser.api.BrowserView
import org.navis.browser.api.PromptResponse
import org.navis.browser.api.SessionId
import org.navis.browser.api.SessionMode
import org.navis.browser.api.TargetRequestId
import org.navis.browser.api.TargetRequestObserver
import org.navis.browser.api.TargetRequestState
import org.navis.browser.core.CoreBridge
import org.navis.browser.core.NativeCoreBridge
import org.navis.browser.engine.runtime.DirectEngineRuntimeAdapter
import org.navis.browser.engine.runtime.EngineRuntimePort
import org.navis.browser.engine.extensions.EngineExtensionTabDelegate
import org.navis.browser.engine.extensions.EngineExtensionTabOperation
import org.navis.browser.engine.extensions.EngineExtensionTabRequest
import org.navis.browser.engine.extensions.EngineExtensionTabResult
import org.navis.browser.engine.extensions.EngineExtensionTabTopology
import org.navis.browser.engine.extensions.EngineExtensionTabTopologyEntry
import org.navis.browser.engine.extensions.EngineExtensionWindowTopologyEntry
import org.navis.browser.engine.extensions.EngineExtensionTabTopologyEvent
import org.navis.browser.engine.extensions.EngineExtensionTabTopologyEventType
import org.navis.browser.engine.extensions.EngineExtensionPort
import org.navis.browser.engine.extensions.EngineExtensionWindowDelegate
import org.navis.browser.engine.extensions.EngineExtensionWindowRequest
import org.navis.browser.engine.extensions.EngineExtensionWindowResult
import org.navis.browser.engine.extensions.EngineExtensionWindowOperation
import org.navis.browser.engine.extensions.EngineExtensionWindowState
import org.navis.browser.extensions.AndroidExtensionManager
import org.navis.browser.extensions.ExtensionHost
import org.navis.browser.persistence.BookmarkEntry
import org.navis.browser.persistence.BookmarkDraft
import org.navis.browser.persistence.BrowserProfileStore
import org.navis.browser.persistence.AndroidSitePermissionStore
import org.navis.browser.persistence.AndroidLoginStorageDelegate
import org.navis.browser.persistence.HistoryEntry
import org.navis.browser.persistence.PasswordEntry
import org.navis.browser.persistence.PersistedSession
import org.navis.browser.persistence.SessionPersistence
import org.navis.browser.settings.DeveloperModeChange
import org.navis.browser.settings.DeveloperModeCoordinator
import org.navis.browser.settings.DeveloperSettings
import org.navis.browser.settings.createAndroidSettingsHost
import org.navis.browser.settings.engineStartupPreferences
import org.navis.browser.downloads.DownloadManager
import org.navis.browser.pages.AndroidInternalPages
import org.navis.browser.pages.NativePageHistorySnapshot

/** Android product runtime. Engine ownership is confined to [EngineRuntimePort]. */
internal class AndroidBrowserRuntime private constructor(
    context: Context,
    private val engineRuntime: EngineRuntimePort,
    private val core: CoreBridge,
    private val delegate: BrowserDelegate,
) : BrowserRuntime, BrowserTargetRuntime, AndroidSession.Owner, EngineExtensionTabDelegate, EngineExtensionWindowDelegate {
    private val applicationContext = context.applicationContext
    private val settingsHost = createAndroidSettingsHost(applicationContext)
    internal val product = AndroidProductServices(
        settingsHost,
        { sessions[activeSessionId]?.renderSession },
        { id -> sessions[id]?.renderSession },
        { origin, privateMode -> targetDelegate.clearSitePermissions(origin, privateMode) },
    )
    private val resourceLedger = RuntimeResourceLedger()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val persistRunnable = Runnable(::persistSessions)
    private val sessions = linkedMapOf<SessionId, AndroidSession>()
    private val pendingNewWindowSessions = linkedMapOf<SessionId, AndroidSession>()
    private val devToolsHosts = linkedMapOf<Long, AndroidDevToolsHost>()
    internal val windowRegistry = AndroidWindowRegistry()
    private val windowViews = mutableMapOf<Long, WeakReference<AndroidBrowserView>>()
    private val windowFacades = mutableMapOf<Long, AndroidWindowRuntime>()
    private val windowActivities = mutableMapOf<Long, Pair<AndroidWindowLease, WeakReference<Activity>>>()
    private val windowAttention = AndroidWindowAttention(applicationContext)
    private val windowCommands = mutableMapOf<Long, String>()
    private val windowLaunches = mutableMapOf<String, Pair<Long, CompletableFuture<Long>>>()
    private val sessionHostReady = mutableMapOf<SessionId, CompletableFuture<Unit>>()
    private val pendingRestoredWindows = linkedMapOf<String, List<PersistedSession>>()
    private var operationWindowId: Long? = null
    private val reusableViewIds: ArrayDeque<Long>
        get() = windowRegistry.get(windowId).reusableViewIds
    private val observers = linkedSetOf<BrowserStateObserver>()
    private val contextMenuObservers = linkedSetOf<(AndroidContextMenuRequest) -> Unit>()
    private val shortcutSettingsObservers = linkedSetOf<(String) -> Unit>()
    private val windowShortcutSettingsObservers = linkedSetOf<(Long, String) -> Unit>()
    private var developerSettingsResource: DeveloperSettings? = null
    private var persistenceResource: SessionPersistence? = null
    private var closedTabsResource: org.navis.browser.persistence.ClosedTabsStore? = null
    private val pendingSessionCloses = mutableMapOf<SessionId, CompletableFuture<Unit>>()
    private var profileStoreResource: BrowserProfileStore? = null
    private var targetDelegateResource: AndroidTargetDelegate? = null
    private var downloadStoreResource: AndroidResponseDownloadStore? = null
    private var extensionDownloadCoordinatorResource: AndroidExtensionDownloadCoordinator? = null
    private var downloadCoordinatorResource: AndroidDownloadCoordinator? = null
    private var extensionManagerResource: AndroidExtensionManager? = null
    private var extensionPortResource: EngineExtensionPort? = null
    private val initialExtensionHostReady = CompletableFuture<Unit>()
    private val initialExtensionHostExpectedSessions = linkedSetOf<SessionId>()
    private val initialExtensionHostReadySessions = linkedSetOf<SessionId>()
    private val initialExtensionHostReadyTimeout = Runnable {
        initialExtensionHostReady.completeExceptionally(
            IllegalStateException("Timed out waiting for the initial direct extension hosts"),
        )
    }
    private var registeredWindowId: Long? = null
    private var activeSessionId: SessionId?
        get() = currentWindowId()?.let(windowRegistry::find)?.selectedSessionId
        set(value) { windowRegistry.get(windowId).selectedSessionId = value }
    private var restoredActiveSessionId: SessionId? = null
    private var boundView: WeakReference<AndroidBrowserView>
        get() = windowViews.getOrPut(windowId) { WeakReference(null) }
        set(value) { windowViews[windowId] = value }
    private var initializationStarted = false
    private var initializationCompleted = false
    private var extensionTopologyEnabled = false
    private var extensionTopologyBroken: Throwable? = null
    private var lastExtensionTopology: EngineExtensionTabTopology? = null
    private var extensionTopologyTail: CompletionStage<Unit> =
        CompletableFuture.completedFuture(Unit)
    private var developerModeTransitionInFlight = false
    private var acceptingCallbacks = false
    private var coreOwned = true
    private var engineOwned = true
    private var closed = false
    private val newWindowCoordinator = NewWindowCoordinator(
        isOpenerLive = { openerId ->
            acceptingCallbacks && !closed && sessions.containsKey(openerId)
        },
        isChildLive = { child ->
            acceptingCallbacks &&
                !closed &&
                pendingNewWindowSessions[child.id] === child &&
                child.renderSession.isOpen
        },
        scheduler = NewWindowDeadlineScheduler { delayMillis, action ->
            val runnable = Runnable(action)
            if (!mainHandler.postDelayed(runnable, delayMillis)) {
                action()
            }
            NewWindowDeadline { mainHandler.removeCallbacks(runnable) }
        },
        onAccepted = ::acceptPendingNewWindow,
        onRejected = ::discardPendingNewWindow,
    )

    private val developerSettings: DeveloperSettings
        get() = checkNotNull(developerSettingsResource) { "Developer settings are not initialized" }

    private val persistence: SessionPersistence
        get() = checkNotNull(persistenceResource) { "Session persistence is not initialized" }

    private val profileStore: BrowserProfileStore
        get() = checkNotNull(profileStoreResource) { "Profile store is not initialized" }

    private val targetDelegate: AndroidTargetDelegate
        get() = checkNotNull(targetDelegateResource) { "Target delegate is not initialized" }

    private val extensionManager: AndroidExtensionManager
        get() = checkNotNull(extensionManagerResource) { "Extension manager is not initialized" }

    private fun currentWindowId(): Long? = operationWindowId ?: windowRegistry.focusedWindowId
            ?: windowRegistry.lastFocusedWindowId
            ?: registeredWindowId?.takeIf { windowRegistry.find(it) != null }
            ?: windowRegistry.windows.firstOrNull()?.id

    private val windowId: Long get() = checkNotNull(currentWindowId()) { "Core window is not initialized" }

    internal fun windowIdForSession(sessionId: SessionId): Long? = windowRegistry.owner(sessionId)

    internal fun forWindow(id: Long): AndroidWindowRuntime {
        windowRegistry.get(id)
        return windowFacades.getOrPut(id) { AndroidWindowRuntime(this, id) }
    }

    internal fun <T> inWindow(id: Long, operation: () -> T): T {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Window commands require the UI thread" }
        windowRegistry.get(id)
        val previous = operationWindowId
        operationWindowId = id
        try { return operation() } finally { operationWindowId = previous }
    }

    private fun <T> inSessionWindow(id: SessionId, operation: () -> T): T =
        inWindow(checkNotNull(windowIdForSession(id)) { "Unknown Navis Session" }, operation)

    internal fun stateForWindow(id: Long): BrowserState {
        val window = windowRegistry.find(id)
        return BrowserState(
            sessions = window?.sessions?.mapNotNull { sessions[it]?.state() }.orEmpty(),
            activeSessionId = window?.selectedSessionId,
            developerMode = developerSettings.enabled,
            windowFullscreen = window?.fullscreenRequested == true,
        )
    }

    internal fun productForWindow(id: Long): AndroidProductServices = product.forWindow(
        activeSession = { windowRegistry.find(id)?.selectedSessionId?.let(sessions::get)?.renderSession },
        session = { sessionId -> sessions[sessionId]?.takeIf { windowIdForSession(sessionId) == id }?.renderSession },
    )

    internal fun extensionsForWindow(id: Long): ExtensionHost = extensionManager.forWindow(
        id,
        activeTab = { windowRegistry.find(id)?.selectedSessionId?.let(sessions::get)?.let {
            it.id.value to (it.mode == SessionMode.PRIVATE)
        } },
        ownsTab = { tabId -> windowIdForSession(SessionId(tabId)) == id },
    )

    internal fun targetStateForWindow(id: Long): TargetRequestState = targetDelegate.stateForWindow(id)

    internal fun addWindowShortcutSettingsObserver(observer: (Long, String) -> Unit) {
        if (!closed) windowShortcutSettingsObservers += observer
    }

    internal fun removeWindowShortcutSettingsObserver(observer: (Long, String) -> Unit) {
        windowShortcutSettingsObservers -= observer
    }

    internal fun resolveActivityWindow(
        taskId: Int,
        restoredWindowId: Long?,
        restoredPersistenceKey: String?,
        launchToken: String?,
        additional: Boolean,
    ): Long {
        requireOpen()
        if (launchToken != null) {
            val pending = windowLaunches.remove(launchToken)
                ?: error("The new-window launch has expired")
            val window = windowRegistry.get(pending.first)
            check(window.lease == null && !window.closing)
            windowLaunchCompletions[window.id] = pending.second
            return window.id
        }
        // Saved-instance data is supplied by Android, not ACTION_VIEW extras. Stable keys map
        // restored normal windows to this process's newly allocated Core IDs.
        if (restoredPersistenceKey != null) {
            windowRegistry.windows.firstOrNull { it.persistenceKey == restoredPersistenceKey }
                ?.takeIf { it.taskId == null || it.taskId == taskId }
                ?.let { return it.id }
            pendingRestoredWindows.remove(restoredPersistenceKey)?.let { entries ->
                val id = allocateProductWindow(false, restoredPersistenceKey)
                restoreWindowEntries(id, entries)
                return id
            }
        }
        if (restoredWindowId != null && restoredPersistenceKey != null) {
            windowRegistry.find(restoredWindowId)
                ?.takeIf { it.persistenceKey == restoredPersistenceKey && it.taskId == taskId }
                ?.let { return it.id }
        }
        check(!additional) { "The additional Navis window cannot be restored" }
        windowRegistry.windows.firstOrNull { it.taskId == taskId }?.let { return it.id }
        windowRegistry.windows.firstOrNull { it.taskId == null && !it.privateMode && !it.closing }
            ?.let { return it.id }
        val id = allocateProductWindow(false)
        inWindow(id) { createSession(SessionMode.NORMAL) }
        return id
    }

    private val windowLaunchCompletions = mutableMapOf<Long, CompletableFuture<Long>>()

    /** Do not expose a new window's UI before its launch transaction has really committed. */
    internal fun awaitWindowPresentation(lease: AndroidWindowLease): CompletionStage<Unit> {
        if (closed || !windowRegistry.isCurrent(lease) || windowRegistry.get(lease.windowId).closing) {
            return failedStage(IllegalStateException("Window Activity is no longer available"))
        }
        val pending = windowLaunchCompletions[lease.windowId]
        return (pending?.thenApply { Unit } ?: CompletableFuture.completedFuture(Unit)).thenCompose {
            onMainStage {
                if (!closed && windowRegistry.isCurrent(lease) && !windowRegistry.get(lease.windowId).closing) {
                    CompletableFuture.completedFuture(Unit)
                } else failedStage(IllegalStateException("Window Activity changed before presentation"))
            }
        }
    }

    internal fun bindWindowActivity(id: Long, activity: Activity): AndroidWindowLease {
        windowAttention.clear(id)
        windowMutationWaiters.remove(id)?.result?.completeExceptionally(IllegalStateException("Window Activity replaced"))
        windowRegistry.find(id)?.lease?.let(::cancelActivityPrompts)
        val lease = windowRegistry.bind(id, activity.taskId)
        windowActivities[id] = lease to WeakReference(activity)
        windowRegistry.setVisible(lease, true)
        recordWindowLifecycle(WindowLaunchStage.ACTIVITY_BIND, "ok")
        val publication = try { publish(persist = false) } catch (error: Throwable) {
            reportWindowLaunchFailure(WindowLaunchStage.TOPOLOGY, error)
            windowLaunchCompletions[id]?.completeExceptionally(error)
            throw error
        }
        windowLaunchCompletions[id]?.takeUnless { it.isDone }?.let { completion ->
            val ready = windowRegistry.get(id).sessions.mapNotNull(sessionHostReady::get)
            completeWindowLaunch(lease, CompletableFuture.allOf(*ready.toTypedArray()), publication, completion)
        }
        return lease
    }

    internal fun windowVisibilityChanged(lease: AndroidWindowLease, visible: Boolean) {
        if (windowRegistry.setVisible(lease, visible)) {
            val publication = publish(persist = false)
            acknowledgeWindowMutation(lease, publication)
        }
    }

    internal fun windowFocusChanged(lease: AndroidWindowLease, focused: Boolean) {
        if (windowRegistry.focus(lease, focused)) {
            val publication = publish(persist = false)
            if (focused) windowAttention.clear(lease.windowId)
            acknowledgeWindowMutation(lease, publication)
            if (focused) acknowledgeWindowActivityFocus(lease, publication)
            if (focused) windowFocusWaiters[lease.windowId]?.let { waiter ->
                publication.whenComplete { _, error -> onMain {
                    if (windowFocusWaiters[lease.windowId] !== waiter) return@onMain
                    windowFocusWaiters.remove(lease.windowId)
                    if (error != null) waiter.completeExceptionally(error)
                    else if (windowRegistry.isCurrent(lease) && windowRegistry.find(lease.windowId)?.focused == true) waiter.complete(Unit)
                    else waiter.completeExceptionally(IllegalStateException("Window focus changed before acknowledgement"))
                } }
            }
        }
    }

    internal fun windowBoundsChanged(lease: AndroidWindowLease, bounds: AndroidWindowBounds) {
        val previous = windowRegistry.find(lease.windowId)?.bounds
        if (previous != bounds && windowRegistry.updateBounds(lease, bounds)) {
            acknowledgeWindowMutation(lease, publish(persist = false))
        }
    }

    internal fun detachWindowActivity(lease: AndroidWindowLease, finishing: Boolean) {
        if (!windowRegistry.isCurrent(lease)) return
        windowAttention.clear(lease.windowId)
        windowMutationWaiters.remove(lease.windowId)?.result?.completeExceptionally(IllegalStateException("Window Activity detached"))
        recordWindowLifecycle(WindowLaunchStage.ACTIVITY_DETACH, if (finishing) "finishing" else "detached")
        cancelActivityPrompts(lease)
        windowRegistry.detach(lease)
        windowActivityFocusWaiters[lease.windowId]?.takeIf { it.lease == lease }?.result
            ?.completeExceptionally(IllegalStateException("Window Activity detached"))
        windowActivities.remove(lease.windowId)
        windowViews.remove(lease.windowId)?.get()?.release()
        if (finishing) closeProductWindow(lease.windowId) else publish(persist = false)
    }

    private fun allocateProductWindow(privateMode: Boolean, persistenceKey: String? = null): Long {
        val id = core.registerWindow()
        try {
            if (persistenceKey == null) windowRegistry.register(id, privateMode)
            else windowRegistry.register(id, privateMode, persistenceKey)
        } catch (error: Throwable) { core.closeWindow(id); throw error }
        return id
    }

    internal fun createProductWindow(
        sourceWindowId: Long,
        privateMode: Boolean,
        urls: List<String>,
        launchGeometry: AndroidWindowLaunchGeometry.Prepared? = null,
        reserve: (Long) -> Unit = {},
    ): CompletionStage<Long> {
        requireOpen()
        require(urls.size <= 128)
        val destinations = urls.ifEmpty { listOf("navis://newtab/") }
        val source = windowActivities[sourceWindowId]
            ?.takeIf { windowRegistry.isCurrent(it.first) }
            ?.second?.get()?.takeUnless { it.isFinishing || it.isDestroyed }
            ?: return failedStage(IllegalStateException("No live source Activity"))
        if (windowRegistry.find(sourceWindowId)?.visible != true) {
            return failedStage(IllegalStateException("Background window creation is unavailable"))
        }
        val id = allocateProductWindow(privateMode)
        val result = CompletableFuture<Long>()
        val token = java.util.UUID.randomUUID().toString()
        var stage = WindowLaunchStage.SESSION_CREATE
        recordWindowLifecycle(stage, "event")
        try {
            reserve(id)
            inWindow(id) {
                destinations.forEachIndexed { index, uri -> createSessionInternal(
                    if (privateMode) SessionMode.PRIVATE else SessionMode.NORMAL,
                    uri, null, activate = index == 0,
                    nativeNewTab = uri == "navis://newtab/",
                ) }
            }
            windowLaunches[token] = id to result
            stage = WindowLaunchStage.ACTIVITY_START
            source.startActivity(org.navis.browser.ProfileWindows.intent(source, "Additional")
                .putExtra(org.navis.browser.ProfileWindows.EXPECTED_PROFILE,
                    (source.application as org.navis.browser.NavisApplication).profileScope?.currentId)
                .putExtra(AndroidWindowLaunch.TOKEN, token)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_MULTIPLE_TASK),
                launchGeometry?.options)
            recordWindowLifecycle(WindowLaunchStage.ACTIVITY_START, "ok")
            val deadline = Runnable {
                if (!result.isDone) {
                    windowLaunches.remove(token)
                    windowLaunchCompletions.remove(id)
                    val error = java.util.concurrent.TimeoutException("Android did not finish opening the requested window")
                    reportWindowLaunchFailure(WindowLaunchStage.DEADLINE, error)
                    result.completeExceptionally(error)
                    closeProductWindow(id)
                }
            }
            mainHandler.postDelayed(deadline, 15_000)
            result.whenComplete { _, _ ->
                mainHandler.removeCallbacks(deadline)
                windowLaunchCompletions.remove(id, result)
            }
            result.whenComplete { _, error -> if (error != null) onMain {
                reportWindowLaunchFailure(WindowLaunchStage.ROLLBACK, error)
                windowLaunches.remove(token)
                windowLaunchCompletions.remove(id)
                if (windowRegistry.find(id) != null) runCatching { closeProductWindow(id) }
            } }
        } catch (error: Throwable) {
            reportWindowLaunchFailure(stage, error)
            windowLaunches.remove(token)
            runCatching { closeProductWindow(id) }.exceptionOrNull()?.let(error::addSuppressed)
            result.completeExceptionally(error)
        }
        return result
    }

    private enum class WindowLaunchStage {
        SESSION_CREATE, ACTIVITY_START, ACTIVITY_BIND, ACTIVITY_UI_BIND, ACTIVITY_DETACH,
        HOST_READY, TOPOLOGY, FOCUS_ACK, DEADLINE, ROLLBACK, CLOSE,
    }

    /** DEBUG-only bounded journal survives an OEM-hidden logcat or a later unrelated crash. */
    private fun recordWindowLifecycle(stage: WindowLaunchStage, category: String, location: String = "unavailable") {
        if (!org.navis.browser.BuildConfig.DEBUG) return
        val categories = setOf("ok", "event", "finishing", "detached", "timeout", "cancelled",
            "security", "invalid-argument", "invalid-state", "engine-query", "other")
        require(category in categories)
        fun validLocation(value: String): Boolean {
            if (value == "unavailable") return true
            val fields = value.split(':')
            return fields.size == 3 && fields[0] in windowFailureOwners.values &&
                fields[1] in windowFailureMethods && Regex("[0-9]{1,5}").matches(fields[2])
        }
        require(validLocation(location))
        runCatching {
            val preferences = applicationContext.getSharedPreferences("navis-window-launch-debug", Context.MODE_PRIVATE)
            // Accept only our closed vocabulary when retaining prior entries; never echo arbitrary
            // existing preference contents into this diagnostic channel.
            val previous = preferences.getString("events", "").orEmpty().lineSequence().filter { line ->
                val fields = line.split('|')
                fields.size == 4 && fields[0].toLongOrNull() != null &&
                    WindowLaunchStage.entries.any { it.name == fields[1] } && fields[2] in categories &&
                    validLocation(fields[3])
            }.toList().takeLast(31)
            val entry = "${System.currentTimeMillis()}|${stage.name}|$category|$location"
            val edit = preferences.edit().putString("events", (previous + entry).joinToString("\n"))
            if (stage == WindowLaunchStage.SESSION_CREATE && category == "event") edit.remove("first_failure")
            else if (category !in setOf("ok", "event", "finishing", "detached") &&
                preferences.getString("first_failure", null) == null) edit.putString("first_failure", entry)
            edit.commit()
        }
    }

    /** Only fixed Navis-owned symbols are exposed, never arbitrary exception stack text. */
    private val windowFailureOwners = mapOf(
            "org.navis.browser.engine.AndroidBrowserRuntime" to "runtime",
            "org.navis.browser.engine.AndroidWindowRegistry" to "registry",
            "org.navis.browser.engine.AndroidWindowRuntime" to "facade",
            "org.navis.browser.MainActivity" to "activity",
            "org.navis.browser.engine.AndroidSession" to "session",
            "org.navis.browser.core.NativeCoreBridge" to "core",
            "org.navis.browser.engine.extensions.NavisAndroidExtensionPort" to "extension-port",
            "org.navis.browser.engine.runtime.DirectEngineRuntimeAdapter" to "direct-engine",
            "org.mozilla.gecko.navis.NavisAndroidExtensions" to "extension-peer",
            "org.mozilla.geckoview.internal.NavisCoreBridge" to "core-native",
        )
    private val windowFailureMethods = setOf("createProductWindow", "bindWindowActivity", "completeWindowLaunch", "onMainStage",
            "awaitWindowActivityFocus", "acknowledgeWindowActivityFocus", "windowFocusChanged", "publish",
            "publishExtensionTopology", "nextExtensionTopologyRevision", "createSessionShell", "createSessionInternal",
            "registerWindow", "registerView", "createSession", "activateSession", "checkInvariants", "stateForWindow",
            "get", "bind", "ownSession", "register", "open", "setActive", "observeNavigationStart", "applyNativePage",
            "setInitialNativeNewTab", "setNavigationIdentity", "finishNavigation", "acceptRuntimeResult",
            "updateTabTopology", "queryBundle", "ensureOpen", "requireOpen", "attachView")
    private fun windowFailureLocation(error: Throwable): String {
        for (frame in error.stackTrace.take(48)) {
            val owner = windowFailureOwners[frame.className.substringBefore('$')] ?: continue
            val method = frame.methodName.removePrefix("lambda\$").substringBefore('$')
            if (method in windowFailureMethods) return "$owner:$method:${frame.lineNumber.coerceIn(0, 99999)}"
        }
        return "unavailable"
    }

    internal fun reportWindowActivityBinding(error: Throwable? = null) {
        if (error == null) recordWindowLifecycle(WindowLaunchStage.ACTIVITY_UI_BIND, "ok")
        else reportWindowLaunchFailure(WindowLaunchStage.ACTIVITY_UI_BIND, error)
    }

    /** Controlled diagnostics: never include exception messages, URLs, tokens, or user data. */
    private fun reportWindowLaunchFailure(stage: WindowLaunchStage, error: Throwable) {
        if (!org.navis.browser.BuildConfig.DEBUG) return
        var cause = error
        repeat(4) {
            if (cause is java.util.concurrent.CompletionException || cause is java.util.concurrent.ExecutionException) {
                cause = cause.cause ?: cause
            }
        }
        val category = when (cause) {
            is java.util.concurrent.TimeoutException -> "timeout"
            is java.util.concurrent.CancellationException -> "cancelled"
            is SecurityException -> "security"
            is IllegalArgumentException -> "invalid-argument"
            is IllegalStateException -> "invalid-state"
            else -> if (cause.javaClass.name == "org.mozilla.gecko.EventDispatcher\$QueryException") "engine-query" else "other"
        }
        val location = windowFailureLocation(cause).takeUnless { it == "unavailable" } ?: windowFailureLocation(error)
        recordWindowLifecycle(stage, category, location)
        Log.w(LOG_TAG, "Window launch failed: stage=${stage.name} category=$category")
    }

    private fun completeWindowLaunch(
        lease: AndroidWindowLease,
        hostReady: CompletionStage<Void>,
        publication: CompletionStage<Unit>,
        completion: CompletableFuture<Long>,
    ) {
        var stage = WindowLaunchStage.HOST_READY
        hostReady.thenCompose { onMainStage {
            check(!closed && windowRegistry.isCurrent(lease) && !windowRegistry.get(lease.windowId).closing) {
                "Window Activity changed before its host became ready"
            }
            recordWindowLifecycle(WindowLaunchStage.HOST_READY, "ok")
            stage = WindowLaunchStage.TOPOLOGY
            publication
        } }.thenCompose { onMainStage {
            recordWindowLifecycle(WindowLaunchStage.TOPOLOGY, "ok")
            stage = WindowLaunchStage.FOCUS_ACK
            awaitWindowActivityFocus(lease)
        } }.whenComplete { _, error -> onMain {
            if (completion.isDone) return@onMain
            // The launch belongs to the product window, not to one Activity instance. A
            // non-finishing recreation may retire this lease; bindWindowActivity reconnects
            // the same transaction using the replacement lease and the original deadline.
            if (!closed && !windowRegistry.isCurrent(lease) &&
                windowRegistry.find(lease.windowId)?.closing == false) return@onMain
            if (error != null) {
                reportWindowLaunchFailure(stage, error)
                completion.completeExceptionally(error)
            } else if (!closed && windowRegistry.isCurrent(lease) &&
                windowRegistry.find(lease.windowId)?.let { !it.closing && it.visible && it.focused } == true) {
                recordWindowLifecycle(WindowLaunchStage.FOCUS_ACK, "ok")
                completion.complete(lease.windowId)
            } else {
                val invalid = IllegalStateException("Window Activity changed before launch acknowledgement")
                reportWindowLaunchFailure(WindowLaunchStage.FOCUS_ACK, invalid)
                completion.completeExceptionally(invalid)
            }
        } }
    }

    private class WindowActivityFocusWaiter(val lease: AndroidWindowLease) {
        val result = CompletableFuture<Unit>()
        var acknowledging = false
    }
    private val windowActivityFocusWaiters = mutableMapOf<Long, WindowActivityFocusWaiter>()

    /** A just-launched Activity gets focus naturally; it need not be in AppTask recents yet. */
    private fun awaitWindowActivityFocus(lease: AndroidWindowLease): CompletionStage<Unit> {
        if (closed || !windowRegistry.isCurrent(lease) || windowRegistry.get(lease.windowId).closing) {
            return failedStage(IllegalStateException("Window Activity is no longer current"))
        }
        windowActivityFocusWaiters[lease.windowId]?.let { existing ->
            if (existing.lease == lease) return existing.result
            existing.result.completeExceptionally(IllegalStateException("Window Activity was replaced"))
        }
        val waiter = WindowActivityFocusWaiter(lease)
        windowActivityFocusWaiters[lease.windowId] = waiter
        val timeout = Runnable {
            waiter.result.completeExceptionally(java.util.concurrent.TimeoutException("Android did not grant window focus"))
        }
        waiter.result.whenComplete { _, _ ->
            mainHandler.removeCallbacks(timeout)
            if (windowActivityFocusWaiters[lease.windowId] === waiter) windowActivityFocusWaiters.remove(lease.windowId)
        }
        mainHandler.postDelayed(timeout, 5_000)
        // Also covers focus delivered before HostReady, including MainActivity's hasWindowFocus replay.
        acknowledgeWindowActivityFocus(lease)
        return waiter.result
    }

    private fun acknowledgeWindowActivityFocus(
        lease: AndroidWindowLease,
        publication: CompletionStage<Unit>? = null,
    ) {
        val waiter = windowActivityFocusWaiters[lease.windowId]?.takeIf { it.lease == lease } ?: return
        if (closed || !windowRegistry.isCurrent(lease) || windowRegistry.get(lease.windowId).closing) {
            waiter.result.completeExceptionally(IllegalStateException("Window Activity is no longer current"))
            return
        }
        val window = windowRegistry.get(lease.windowId)
        if (waiter.acknowledging || !window.visible || !window.focused) return
        waiter.acknowledging = true
        val confirmed = try { publication ?: publishExtensionTopology() } catch (error: Throwable) { failedStage<Unit>(error) }
        confirmed.whenComplete { _, error -> onMain {
            if (windowActivityFocusWaiters[lease.windowId] !== waiter) return@onMain
            if (error != null) waiter.result.completeExceptionally(error)
            else if (!closed && windowRegistry.isCurrent(lease) &&
                windowRegistry.find(lease.windowId)?.let { !it.closing && it.visible && it.focused } == true) {
                waiter.result.complete(Unit)
            } else waiter.result.completeExceptionally(IllegalStateException("Window focus changed before acknowledgement"))
        } }
    }

    private val pendingWindowSessionOpens = mutableMapOf<Long, CompletionStage<SessionId>>()
    internal fun openWindowSession(sourceWindowId: Long, mode: SessionMode, initialUri: String?): CompletionStage<SessionId> {
        pendingWindowSessionOpens[sourceWindowId]?.let { return it }
        val requestedPrivate = mode == SessionMode.PRIVATE
        val source = windowRegistry.get(sourceWindowId)
        if (source.privateMode == requestedPrivate) return try {
            CompletableFuture.completedFuture(inWindow(sourceWindowId) { createSession(mode, initialUri) })
        } catch (error: Throwable) { failedStage(error) }
        val existing = windowRegistry.windows.firstOrNull { it.privateMode == requestedPrivate && !it.closing && it.taskId != null }
        val opened = if (existing != null) {
            focusProductWindow(existing.id).thenApply { inWindow(existing.id) { createSession(mode, initialUri) } }
        } else {
            createProductWindow(sourceWindowId, requestedPrivate, listOf(initialUri ?: "navis://newtab/"))
                .thenApply { owner -> checkNotNull(windowRegistry.get(owner).selectedSessionId) }
        }
        pendingWindowSessionOpens[sourceWindowId] = opened
        opened.whenComplete { _, error -> if (error != null) onMain {
            android.widget.Toast.makeText(applicationContext, org.navis.browser.R.string.operation_failed,
                android.widget.Toast.LENGTH_LONG).show()
        }; onMain { pendingWindowSessionOpens.remove(sourceWindowId, opened) } }
        return opened
    }

    private val windowFocusWaiters = mutableMapOf<Long, CompletableFuture<Unit>>()
    internal fun focusProductWindow(id: Long, userActivationActivity: Activity? = null): CompletionStage<Unit> {
        val window = windowRegistry.get(id)
        if (window.focused) return CompletableFuture.completedFuture(Unit)
        val foreground = windowActivities.values.any { (lease, activity) ->
            windowRegistry.isCurrent(lease) && windowRegistry.find(lease.windowId)?.visible == true &&
                activity.get()?.let { !it.isFinishing && !it.isDestroyed } == true
        }
        val activatedByUser = userActivationActivity is org.navis.browser.WindowAttentionActivity &&
            !userActivationActivity.isFinishing && !userActivationActivity.isDestroyed
        if (!foreground && !activatedByUser) return failedStage(IllegalStateException("No foreground Activity can focus a window"))
        val taskId = window.taskId
            ?: return failedStage(IllegalStateException("The window has no Android task"))
        val task = applicationContext.getSystemService(android.app.ActivityManager::class.java)?.appTasks
            ?.firstOrNull { task -> task.taskInfo?.let { info ->
                val actualId = if (android.os.Build.VERSION.SDK_INT >= 29) info.taskId else {
                    @Suppress("DEPRECATION")
                    info.id
                }
                actualId == taskId
            } == true }
            ?: return failedStage(IllegalStateException("The window task is not available"))
        windowFocusWaiters[id]?.let { return it }
        val result = CompletableFuture<Unit>()
        windowFocusWaiters[id] = result
        try { task.moveToFront() } catch (error: Throwable) {
            windowFocusWaiters.remove(id); result.completeExceptionally(error); return result
        }
        val deadline = Runnable {
            if (windowFocusWaiters[id] === result) {
                windowFocusWaiters.remove(id)
                result.completeExceptionally(IllegalStateException("Android did not grant window focus"))
            }
        }
        mainHandler.postDelayed(deadline, 5_000)
        result.whenComplete { _, _ -> mainHandler.removeCallbacks(deadline) }
        return result
    }

    internal fun requestCloseProductWindow(id: Long): CompletionStage<Boolean> {
        val result = CompletableFuture<Boolean>()
        val window = windowRegistry.find(id)
            ?: return failedStage(IllegalStateException("Unknown product window"))
        if (window.closing) return failedStage(IllegalStateException("Window is closing"))
        val initialIds = window.sessions.toList()
        val originals = initialIds.associateWith { sessions[it] }
        val revisions = initialIds.associateWith { core.navigationSnapshot(it.value).navigationId }
        val originalSelected = window.selectedSessionId
        var expectedSelected = originalSelected
        fun unchanged() = !closed && windowRegistry.find(id) === window && !window.closing &&
            window.sessions == initialIds && window.selectedSessionId == expectedSelected &&
            initialIds.all { sessions[it] === originals[it] && core.navigationSnapshot(it.value).navigationId == revisions[it] }
        fun finish(value: Boolean, error: Throwable? = null) {
            if (result.isDone) return
            if (!value && originalSelected != null && windowIdForSession(originalSelected) == id) {
                runCatching { activateSession(originalSelected) }
            }
            if (error != null) result.completeExceptionally(error) else result.complete(value)
        }
        val deadline = Runnable { finish(false, IllegalStateException("Window close approval timed out")) }
        mainHandler.postDelayed(deadline, 30_000)
        result.whenComplete { _, _ -> mainHandler.removeCallbacks(deadline) }
        fun next(index: Int) {
            if (result.isDone) return
            if (!unchanged()) { finish(false); return }
            if (index == initialIds.size) {
                try { closeProductWindow(id); finish(true) } catch (error: Throwable) { finish(false, error) }
                return
            }
            val session = checkNotNull(originals[initialIds[index]])
            val state = session.state()
            if (((state.nativeRoute != null || state.nativeNewTab) && !session.hasBackingWebDocument()) ||
                state.navigation.contentTermination != org.navis.browser.api.ContentTermination.NONE) {
                next(index + 1); return
            }
            try {
                expectedSelected = session.id
                activateSession(session.id)
                session.renderSession.querySession("session:can-close").whenComplete { serialized, error ->
                    onMain {
                        if (result.isDone) return@onMain
                        if (error != null) { finish(false, error); return@onMain }
                        if (!unchanged()) { finish(false); return@onMain }
                        val allowed = runCatching { org.json.JSONObject(serialized).opt("allowed") == true }
                        if (allowed.getOrDefault(false)) next(index + 1) else finish(false, allowed.exceptionOrNull())
                    }
                }
            } catch (error: Throwable) { finish(false, error) }
        }
        if (initialIds.isEmpty()) next(0)
        else focusProductWindow(id).whenComplete { _, error -> onMain {
            if (error != null) finish(false, error) else next(0)
        } }
        return result
    }

    private class WindowMutationWaiter(
        val lease: AndroidWindowLease,
        val focused: Boolean?,
        val state: EngineExtensionWindowState?,
        val bounds: AndroidWindowBounds? = null,
        val boundsFields: Set<AndroidWindowBound> = AndroidWindowBound.entries.toSet(),
        val fullscreen: Boolean? = null,
    ) {
        val result = CompletableFuture<Unit>()
        var acknowledging = false
        fun matches(window: AndroidWindowRegistry.Window): Boolean =
            !window.closing && (focused == null || window.focused == focused) &&
                (state == null || window.extensionState.name == state.name) &&
                (fullscreen == null || window.fullscreenApplied == fullscreen) &&
                (bounds == null || bounds.matches(window.bounds, boundsFields))
    }
    private val windowMutationWaiters = mutableMapOf<Long, WindowMutationWaiter>()

    /** Completion follows a real Activity observation and the same published Gecko snapshot. */
    private fun acknowledgeWindowMutation(lease: AndroidWindowLease, publication: CompletionStage<Unit>? = null) {
        val waiter = windowMutationWaiters[lease.windowId]?.takeIf { it.lease == lease } ?: return
        if (closed || !windowRegistry.isCurrent(lease)) {
            waiter.result.completeExceptionally(IllegalStateException("Window Activity changed")); return
        }
        val window = windowRegistry.get(lease.windowId)
        if (waiter.acknowledging || !waiter.matches(window)) return
        waiter.acknowledging = true
        val confirmed = publication ?: publishExtensionTopology()
        confirmed.whenComplete { _, error -> onMain {
            if (windowMutationWaiters[lease.windowId] !== waiter) return@onMain
            if (error != null) waiter.result.completeExceptionally(error)
            else if (!closed && windowRegistry.isCurrent(lease) && waiter.matches(windowRegistry.get(lease.windowId))) {
                waiter.result.complete(Unit)
            } else waiter.result.completeExceptionally(IllegalStateException("Window changed before state acknowledgement"))
        } }
    }

    private fun awaitWindowMutation(
        lease: AndroidWindowLease, focused: Boolean? = null, state: EngineExtensionWindowState? = null,
        bounds: AndroidWindowBounds? = null,
        boundsFields: Set<AndroidWindowBound> = AndroidWindowBound.entries.toSet(),
        fullscreen: Boolean? = null,
        mutate: () -> Unit,
    ): CompletionStage<Unit> {
        check(windowRegistry.isCurrent(lease) && !windowRegistry.get(lease.windowId).closing)
        check(lease.windowId !in windowMutationWaiters) { "A window presentation change is already pending" }
        val waiter = WindowMutationWaiter(lease, focused, state, bounds, boundsFields, fullscreen)
        windowMutationWaiters[lease.windowId] = waiter
        val timeout = Runnable { waiter.result.completeExceptionally(
            java.util.concurrent.TimeoutException("Android did not apply the requested window state")) }
        waiter.result.whenComplete { _, _ -> onMain {
            mainHandler.removeCallbacks(timeout)
            if (windowMutationWaiters[lease.windowId] === waiter) windowMutationWaiters.remove(lease.windowId)
        } }
        mainHandler.postDelayed(timeout, 5_000)
        try { mutate(); acknowledgeWindowMutation(lease) }
        catch (error: Throwable) { waiter.result.completeExceptionally(error) }
        return waiter.result
    }

    internal fun reportWindowFullscreenApplied(lease: AndroidWindowLease, requested: Boolean) {
        if (closed || !windowRegistry.isCurrent(lease)) return
        val window = windowRegistry.get(lease.windowId)
        if (window.closing || window.fullscreenRequested != requested) return
        window.fullscreenApplied = requested
        acknowledgeWindowMutation(lease, publish(persist = false))
    }

    internal fun reportWindowTaskModeChanged(lease: AndroidWindowLease, maximized: Boolean) {
        if (closed || !windowRegistry.isCurrent(lease)) return
        val window = windowRegistry.get(lease.windowId)
        if (window.closing) return
        window.taskFullscreenRestoreTaskId = if (maximized) window.taskId else null
        acknowledgeWindowMutation(lease, publish(persist = false))
    }

    private fun setProductTaskMode(lease: AndroidWindowLease, enter: Boolean, userActivation: Boolean): CompletionStage<Unit> {
        check(userActivation) { "Task window-mode changes require a user input handler" }
        val activity = currentWindowActivity(lease) as? org.navis.browser.MainActivity
            ?: return failedStage(IllegalStateException("Task window-mode provider is unavailable"))
        return activity.requestWindowTaskMode(lease, enter).thenCompose { onMainStage {
            currentWindowActivity(lease)
            check(windowRegistry.get(lease.windowId).taskMaximized == enter) { "Task mode did not reach the requested state" }
            publishExtensionTopology().thenApply {
                check(windowRegistry.isCurrent(lease) && windowRegistry.get(lease.windowId).taskMaximized == enter) {
                    "Task mode changed before acknowledgement"
                }
                Unit
            }
        } }
    }

    internal fun exitWindowFullscreen(id: Long) {
        val window = windowRegistry.find(id) ?: return
        if (!window.fullscreenRequested) return
        windowMutationWaiters[id]?.result?.completeExceptionally(
            java.util.concurrent.CancellationException("The user exited browser fullscreen"))
        window.fullscreenRequested = false
        publish(persist = false)
    }

    private fun currentWindowActivity(lease: AndroidWindowLease): Activity {
        check(windowRegistry.isCurrent(lease) && !windowRegistry.get(lease.windowId).closing) { "Window Activity changed" }
        return checkNotNull(windowActivities[lease.windowId]?.takeIf { it.first == lease }?.second?.get()
            ?.takeUnless { it.isFinishing || it.isDestroyed }) { "Window Activity is unavailable" }
    }

    private fun <T> authorizedWindowAction(
        request: EngineExtensionWindowRequest, sourceLease: AndroidWindowLease, targetLease: AndroidWindowLease?,
        action: () -> CompletionStage<T>,
    ): CompletionStage<T> {
        val token = request.authorizationToken ?: return failedStage(SecurityException("Window command has no engine authorization"))
        val port = extensionPortResource ?: return failedStage(IllegalStateException("Extension port is unavailable"))
        return port.authorizeWindowCommand(token, targetLease?.windowId).thenCompose { allowed -> onMainStage {
            requireOpen()
            check(allowed) { "The original extension window permission is no longer valid" }
            currentWindowActivity(sourceLease)
            targetLease?.let(::currentWindowActivity)
            action()
        } }
    }

    private fun defocusProductWindow(
        lease: AndroidWindowLease, sourceLease: AndroidWindowLease,
        restoreFocus: AndroidWindowLease? = null, preserveFocus: Boolean = false,
    ): CompletionStage<Unit> {
        val window = windowRegistry.get(lease.windowId)
        if (!window.focused) return CompletableFuture.completedFuture(Unit)
        val sourcePrivate = windowRegistry.get(sourceLease.windowId).privateMode
        val replacement = if (preserveFocus) restoreFocus?.let {
            currentWindowActivity(it)
            windowRegistry.get(it.windowId).also { previous ->
                check(!previous.privateMode || sourcePrivate) { "The original focused window is inaccessible" }
            }
        } else (listOf(sourceLease.windowId) + windowRegistry.focusOrder + windowRegistry.windows.map { it.id })
            .distinct().asSequence().filter { it != lease.windowId }.mapNotNull(windowRegistry::find)
            .firstOrNull { !it.closing && it.taskId != null && it.lease != null && (!it.privateMode || sourcePrivate) }
        return awaitWindowMutation(lease, focused = false) {
            if (replacement != null) {
                val issuedWaiter = windowMutationWaiters[lease.windowId]
                val replacementLease = checkNotNull(replacement.lease)
                currentWindowActivity(replacementLease)
                focusProductWindow(replacement.id).whenComplete { _, error -> onMain {
                    val waiter = windowMutationWaiters[lease.windowId]?.takeIf { it === issuedWaiter } ?: return@onMain
                    if (error != null) waiter.result.completeExceptionally(error)
                    else if (!windowRegistry.isCurrent(replacementLease)) waiter.result.completeExceptionally(
                        IllegalStateException("Replacement window Activity changed"))
                    else acknowledgeWindowMutation(lease)
                } }
            } else {
                check(currentWindowActivity(lease).moveTaskToBack(true)) { "Android did not move the window to the background" }
            }
        }
    }

    private fun setProductWindowState(lease: AndroidWindowLease, desired: EngineExtensionWindowState): CompletionStage<Unit> {
        require(desired != EngineExtensionWindowState.MAXIMIZED) { "Task maximization requires the task-mode provider" }
        val window = windowRegistry.get(lease.windowId)
        if (desired == EngineExtensionWindowState.MINIMIZED) {
            return awaitWindowMutation(lease, focused = false, state = desired) {
                window.minimizeRequested = true
                check(currentWindowActivity(lease).moveTaskToBack(true)) { "Android did not minimize the window" }
                // If it was already behind other tasks, the observed background state is sufficient.
                if (!window.visible && !window.focused) { window.minimized = true; publish(persist = false) }
            }.whenComplete { _, error -> onMain {
                if (error != null && windowRegistry.isCurrent(lease)) window.minimizeRequested = false
            } }
        }
        val requested = desired == EngineExtensionWindowState.FULLSCREEN
        val previous = window.fullscreenRequested
        return awaitWindowMutation(lease, fullscreen = requested) {
            window.fullscreenRequested = requested
            publish(persist = false)
        }.whenComplete { _, error -> onMain {
            if (error != null && windowRegistry.isCurrent(lease) && window.fullscreenRequested == requested) {
                window.fullscreenRequested = previous
                publish(persist = false)
            }
        } }
    }

    private fun windowBoundsFields(request: EngineExtensionWindowRequest): Set<AndroidWindowBound> {
        return listOfNotNull(
            AndroidWindowBound.LEFT.takeIf { request.left != null },
            AndroidWindowBound.TOP.takeIf { request.top != null },
            AndroidWindowBound.WIDTH.takeIf { request.width != null },
            AndroidWindowBound.HEIGHT.takeIf { request.height != null },
        ).toSet()
    }

    private fun setProductWindowGeometry(
        request: EngineExtensionWindowRequest, lease: AndroidWindowLease,
    ): CompletionStage<Unit> {
        val activity = currentWindowActivity(lease)
        val window = windowRegistry.get(lease.windowId)
        check(window.visible && window.focused && window.extensionState == AndroidWindowState.NORMAL) {
            "Task geometry requires a foreground normal window"
        }
        val geometry = checkNotNull(AndroidWindowLaunchGeometry.prepareUpdate(activity,
            request.left, request.top, request.width, request.height))
        val bounds = geometry.bounds
        return awaitWindowMutation(lease, focused = true,
            bounds = AndroidWindowBounds(bounds.left, bounds.top, bounds.width(), bounds.height()),
            boundsFields = windowBoundsFields(request)) {
            currentWindowActivity(lease)
            // This public operation may reorder the same Task. No completion is inferred from
            // its void return: only the actual Activity bounds/focus and Gecko publication count.
            AndroidWindowLaunchGeometry.moveExisting(activity, geometry)
        }
    }

    private fun applyWindowUpdate(
        request: EngineExtensionWindowRequest, sourceLease: AndroidWindowLease, targetLease: AndroidWindowLease,
    ): CompletionStage<EngineExtensionWindowResult> {
        val id = targetLease.windowId
        fun step(action: () -> CompletionStage<Unit>) = authorizedWindowAction(request, sourceLease, targetLease, action)
        var chain: CompletionStage<Unit> = CompletableFuture.completedFuture(Unit)
        val geometryFields = windowBoundsFields(request)
        val changesGeometry = request.operation == EngineExtensionWindowOperation.UPDATE && geometryFields.isNotEmpty()
        val preserveFocus = changesGeometry && request.focused == null
        val originalTargetFocused = windowRegistry.get(id).focused
        val originalFocusLease = if (preserveFocus && !originalTargetFocused) windowRegistry.focusedWindowId
            ?.takeUnless { it == id }?.let(windowRegistry::find)?.takeIf {
                !it.privateMode || windowRegistry.get(sourceLease.windowId).privateMode
            }?.lease else null
        if (changesGeometry) {
            check(windowRegistry.get(sourceLease.windowId).visible) {
                "Task geometry changes require a foreground source window"
            }
        }
        val desired = request.state ?: EngineExtensionWindowState.NORMAL.takeIf { changesGeometry }
        if (desired != null) {
            val changesTaskMode = desired == EngineExtensionWindowState.MAXIMIZED ||
                (desired == EngineExtensionWindowState.NORMAL && windowRegistry.get(id).taskMaximized)
            check(!changesTaskMode || request.userActivation) { "Task window-mode changes require a user input handler" }
            chain = chain.thenCompose { step {
                val window = windowRegistry.get(id)
                if (desired != EngineExtensionWindowState.MINIMIZED && (!window.visible || window.minimized ||
                        ((changesTaskMode || changesGeometry) && !window.focused))) focusProductWindow(id)
                else CompletableFuture.completedFuture(Unit)
            } }
            if (changesTaskMode) {
                if (desired == EngineExtensionWindowState.MAXIMIZED) {
                    chain = chain.thenCompose { step { setProductWindowState(targetLease, EngineExtensionWindowState.NORMAL) } }
                        .thenCompose { step {
                            // A confirmed same-Task ENTER survives Activity recreation. Repeating it
                            // need not produce a new configuration event, so only request a change.
                            if (windowRegistry.get(id).taskMaximized) CompletableFuture.completedFuture(Unit)
                            else setProductTaskMode(targetLease, true, request.userActivation)
                        } }
                } else {
                    chain = chain.thenCompose { step { setProductTaskMode(targetLease, false, request.userActivation) } }
                        .thenCompose { step { setProductWindowState(targetLease, EngineExtensionWindowState.NORMAL) } }
                }
            } else chain = chain.thenCompose { step { setProductWindowState(targetLease, desired) } }
        }
        if (changesGeometry) chain = chain.thenCompose { step { setProductWindowGeometry(request, targetLease) } }
        val focus = request.focused ?: when {
            preserveFocus -> originalTargetFocused
            desired == EngineExtensionWindowState.MINIMIZED -> false
            request.operation == EngineExtensionWindowOperation.CREATE || request.operation == EngineExtensionWindowOperation.FOCUS -> true
            else -> null
        }
        if (focus != null) chain = chain.thenCompose { step {
            if (focus) focusProductWindow(id)
            else defocusProductWindow(targetLease, sourceLease, originalFocusLease, preserveFocus)
        } }
        return chain.thenCompose { authorizedWindowAction(request, sourceLease, targetLease) {
            val window = windowRegistry.get(id)
            if (request.drawAttention == true) {
                windowAttention.show(id, window.privateMode, request.extensionId, request.extensionName,
                    currentWindowActivity(targetLease)) { router ->
                    // The notification is a user activation, not a reusable extension capability.
                    val port = extensionPortResource ?: return@show failedStage<Unit>(IllegalStateException("Extension port is unavailable"))
                    port.inventory().thenCompose { inventory -> onMainStage {
                        currentWindowActivity(targetLease)
                        check(inventory.extensions.any { it.id == request.extensionId && it.enabled &&
                            (!window.privateMode || it.privateBrowsingAllowed) }) { "Window reminder owner is no longer authorized" }
                        focusProductWindow(id, userActivationActivity = router)
                    } }
                }
            }
            extensionTopologyTail.thenApply {
                check(windowRegistry.isCurrent(sourceLease) && windowRegistry.isCurrent(targetLease) &&
                    !window.closing && (focus == null || window.focused == focus) &&
                    (!preserveFocus || originalFocusLease == null ||
                        (windowRegistry.isCurrent(originalFocusLease) && windowRegistry.get(originalFocusLease.windowId).focused)) &&
                    (desired == null || window.extensionState.name == desired.name) &&
                    (!changesGeometry || AndroidWindowBounds(request.left ?: window.bounds.left,
                        request.top ?: window.bounds.top, request.width ?: window.bounds.width,
                        request.height ?: window.bounds.height).matches(window.bounds, geometryFields))) {
                    "Window changed before command completion"
                }
                EngineExtensionWindowResult(id, attention = request.drawAttention == true)
            }
        } }
    }

    override fun onWindowCommand(request: EngineExtensionWindowRequest): CompletionStage<EngineExtensionWindowResult> = onMainStage {
        requireOpen()
        val sourceLease = checkNotNull(windowRegistry.get(request.sourceWindowId).lease) { "Source window has no Activity" }
        val targetLease = request.windowId?.let { checkNotNull(windowRegistry.get(it).lease) { "Target window has no Activity" } }
        val key = request.windowId ?: request.sourceWindowId
        val token = checkNotNull(request.authorizationToken) { "Window command has no engine authorization" }
        check(key !in windowCommands) { "A window command is already pending" }
        windowCommands[key] = token
        var createdId: Long? = null
        val operation = authorizedWindowAction(request, sourceLease, targetLease) {
            when (request.operation) {
                EngineExtensionWindowOperation.CREATE -> {
                    val geometry = AndroidWindowLaunchGeometry.prepare(currentWindowActivity(sourceLease),
                        request.left, request.top, request.width, request.height)
                    createProductWindow(request.sourceWindowId, checkNotNull(request.privateMode), request.urls, geometry,
                        reserve = { id -> createdId = id; check(id !in windowCommands); windowCommands[id] = token }
                    ).thenCompose { id -> onMainStage {
                        val lease = checkNotNull(windowRegistry.get(id).lease)
                        val ready = if (geometry == null) CompletableFuture.completedFuture(Unit) else {
                            val bounds = geometry.bounds
                            awaitWindowMutation(lease, bounds = AndroidWindowBounds(bounds.left, bounds.top, bounds.width(), bounds.height()),
                                boundsFields = windowBoundsFields(request)) {}
                        }
                        ready.thenCompose { applyWindowUpdate(request, sourceLease, lease) }.whenComplete { _, error -> onMain {
                            if (error != null && windowRegistry.isCurrent(lease)) closeProductWindow(id)
                        } }
                    } }
                }
                EngineExtensionWindowOperation.FOCUS, EngineExtensionWindowOperation.UPDATE ->
                    applyWindowUpdate(request, sourceLease, checkNotNull(targetLease))
                EngineExtensionWindowOperation.REMOVE -> requestCloseProductWindow(checkNotNull(request.windowId)).thenApply { removed ->
                    check(removed) { "The user did not allow closing this window" }
                    EngineExtensionWindowResult(request.windowId, removed = true)
                }
            }
        }
        operation.whenComplete { _, _ -> onMain {
            windowCommands.remove(key, token)
            createdId?.let { windowCommands.remove(it, token) }
        } }
    }

    /** Internal retirement after user tab closure, task removal, or an approved windows.remove. */
    internal fun closeProductWindow(id: Long) {
        val window = windowRegistry.find(id) ?: return
        if (window.closing) return
        windowAttention.clear(id)
        windowMutationWaiters.remove(id)?.result?.completeExceptionally(IllegalStateException("Window closed"))
        window.closing = true
        recordWindowLifecycle(WindowLaunchStage.CLOSE, "event")
        windowFacades[id]?.retire()
        windowActivityFocusWaiters.remove(id)?.result?.completeExceptionally(IllegalStateException("Window closed"))
        val failures = mutableListOf<Throwable>()
        fun attempt(action: () -> Unit) { runCatching(action).exceptionOrNull()?.let(failures::add) }
        extensionManagerResource?.releaseWindow(id)
        devToolsHosts.filterKeys { windowIdForSession(SessionId(it)) == id }.values.toList()
            .forEach { host -> attempt(host::close) }
        pendingNewWindowSessions.values.filter { windowIdForSession(it.id) == id }.toList()
            .forEach { session -> attempt { discardPendingNewWindow(session) } }
        // Suppress replacement activation while closing, without disturbing other windows.
        inWindow(id) {
            activeSessionId?.let { sessions[it]?.setActive(false) }
            activeSessionId = null
            window.sessions.toList().forEach { sessionId -> attempt { closeSessionNow(sessionId) } }
        }
        attempt { targetDelegateResource?.cancelWindow(id) }
        windowViews.remove(id)?.get()?.let { attempt(it::release) }
        windowActivities.remove(id)?.let { (_, reference) ->
            reference.get()?.let { activity -> if (!activity.isFinishing) activity.finishAndRemoveTask() }
        }
        attempt { core.closeWindow(id) }
        if (failures.isEmpty()) {
            windowRegistry.remove(id)
            windowFacades.remove(id)
        }
        windowFocusWaiters.remove(id)?.completeExceptionally(IllegalStateException("Window closed"))
        publish(persist = true)
        throwFirstWithSuppressed(failures)
    }

    override var state: BrowserState = BrowserState()
        private set

    override val targetState: TargetRequestState
        get() = targetDelegate.state

    internal val extensions: ExtensionHost
        get() = extensionManager

    internal val downloads: DownloadManager
        get() = checkNotNull(extensionDownloadCoordinatorResource).downloadManager

    /** Acquires all product resources in one rollback-safe sequence. */
    private fun initialize(onComplete: (Result<Unit>) -> Unit) {
        check(!initializationStarted && !closed) { "Android Navis Runtime is already initialized" }
        initializationStarted = true

        val initializedExtensions = try {
            resourceLedger.own(RESOURCE_ENGINE, ::releaseEngine)
            resourceLedger.own(RESOURCE_CORE, ::releaseCore)
            resourceLedger.own("settings", settingsHost::close)
            org.navis.browser.uploads.AndroidUploadSelections.initialize(applicationContext)

            developerSettingsResource = DeveloperSettings(applicationContext)
            persistenceResource = SessionPersistence(applicationContext)

            val profileStore = BrowserProfileStore(applicationContext)
            profileStoreResource = profileStore
            resourceLedger.own(RESOURCE_PROFILE) { profileStore.close() }

            val extensionProfilePort = checkNotNull(engineRuntime.extensionProfilePort) {
                    "The direct Runtime does not expose its extension-profile service"
                }
            val closedTabs = org.navis.browser.persistence.ClosedTabsStore(applicationContext)
            closedTabsResource = closedTabs
            resourceLedger.own("closed-tabs", closedTabs::close)
            val extensionSessions = AndroidExtensionSessionsAdapter(closedTabs,
                restore = ::restoreClosedTab,
                rollback = { tabId ->
                    try {
                        if (sessions.containsKey(SessionId(tabId))) closeSessionNow(SessionId(tabId))
                        CompletableFuture.completedFuture(Unit)
                    } catch (error: Throwable) { failedStage(error) }
                },
                changed = { extensionProfilePort.publishSessionsChange() },
            )
            resourceLedger.own("extension-sessions", extensionSessions::close)
            val extensionProfileCoordinator = AndroidExtensionProfileCoordinator(
                profileStore, extensionProfilePort, sessions = extensionSessions,
            )
            resourceLedger.own("extension-profile", extensionProfileCoordinator::close)

            val loginStoragePort = checkNotNull(engineRuntime.loginStoragePort) {
                "The direct Runtime does not expose its login-storage service"
            }
            val loginStorageDelegate = AndroidLoginStorageDelegate(profileStore)
            loginStoragePort.bind(loginStorageDelegate)
            resourceLedger.own(RESOURCE_LOGIN_STORAGE) {
                loginStoragePort.unbind(loginStorageDelegate)
            }

            val newTargetDelegate = AndroidTargetDelegate(
                isSessionLive = ::isLive,
                projections = engineRuntime.projections,
                enginePort = engineRuntime.targetPort,
                sitePermissionStore = AndroidSitePermissionStore(applicationContext),
                windowIdForSession = ::windowIdForSession,
                activeWindowId = ::currentWindowId,
                requestNotificationPermission = { sessionId, done ->
                    val owner = windowIdForSession(sessionId)
                    if (owner == null) done(false)
                    else org.navis.browser.permissions.AndroidNotificationPermissions.requestForSite(owner, done)
                },
                notificationsAllowed = org.navis.browser.permissions.AndroidNotificationPermissions::currentPermissionGranted,
                exitFullscreenCommand = { sessionId ->
                    (sessions[sessionId] ?: pendingNewWindowSessions[sessionId])
                        ?.exitFullscreen()
                },
            )
            targetDelegateResource = newTargetDelegate
            resourceLedger.own(RESOURCE_TARGET, newTargetDelegate::close)

            val downloadStore = AndroidResponseDownloadStore(
                applicationContext,
                onRequested = delegate::onDownloadRequested,
                onFinished = delegate::onDownloadFinished,
                windowIdForSession = ::windowIdForSession,
                defaultDirectoryUri = { settingsHost.snapshot.downloadDirectory },
            )
            downloadStoreResource = downloadStore
            resourceLedger.own(RESOURCE_DOWNLOADS, downloadStore::close)

            val extensionDownloadPort = checkNotNull(engineRuntime.extensionDownloadPort) {
                "The direct Runtime does not expose its extension-download service"
            }
            val extensionDownloadCoordinator = AndroidExtensionDownloadCoordinator(
                context = applicationContext,
                port = extensionDownloadPort,
                store = downloadStore,
                windowIdForSession = ::windowIdForSession,
                activeWindowId = { windowRegistry.focusedWindowId },
                isSessionLiveAndMode = { id, privateMode ->
                    sessions[id]?.let { it.renderSession.isOpen && (it.mode == SessionMode.PRIVATE) == privateMode } == true
                },
                askBeforeSaving = { settingsHost.snapshot.askBeforeSaving },
                defaultDirectoryUri = { settingsHost.snapshot.downloadDirectory },
                deletePrivateOnExit = { settingsHost.snapshot.deletePrivateOnExit },
                openWhenComplete = { settingsHost.snapshot.openWhenComplete },
                onActionFailure = {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        android.widget.Toast.makeText(applicationContext, org.navis.browser.R.string.operation_failed,
                            android.widget.Toast.LENGTH_LONG).show()
                    }
                },
                sessionForMode = { privateMode ->
                    val requestedMode = if (privateMode) SessionMode.PRIVATE else SessionMode.NORMAL
                    activeSessionId
                        ?.let(sessions::get)
                        ?.takeIf { it.mode == requestedMode }
                        ?.id
                        ?: sessions.values.firstOrNull { it.mode == requestedMode }?.id
                },
                retryDownload = { request ->
                    val source = sessions[request.sessionId]
                    if (source == null || !source.renderSession.isOpen ||
                        (source.mode == SessionMode.PRIVATE) != request.privateMode) {
                        CompletableFuture.completedFuture(false)
                    } else {
                        source.renderSession.retryDownload(request.sourceUri, request.referrer)
                    }
                },
            )
            extensionDownloadCoordinatorResource = extensionDownloadCoordinator
            resourceLedger.own(
                RESOURCE_EXTENSION_DOWNLOADS,
                extensionDownloadCoordinator::close,
            )

            val browsingDataPort = checkNotNull(engineRuntime.browsingDataPort) {
                "The direct Runtime does not expose its product browsing-data service"
            }
            val productDataCleaner = NavisAndroidProductDataCleaner(
                profileStore = profileStore,
                downloadHistory = extensionDownloadCoordinator,
            )
            browsingDataPort.bind(productDataCleaner)
            resourceLedger.own(RESOURCE_PRODUCT_BROWSING_DATA) {
                val failures = mutableListOf<Throwable>()
                runCatching { browsingDataPort.unbind(productDataCleaner) }
                    .exceptionOrNull()
                    ?.let(failures::add)
                runCatching(productDataCleaner::close)
                    .exceptionOrNull()
                    ?.let(failures::add)
                throwFirstWithSuppressed(failures)
            }

            val downloadPreferences = AndroidDownloadPreferences(settingsHost, downloadStore, ::windowIdForSession) { id, privateMode ->
                sessions[id]?.let { it.renderSession.isOpen && (it.mode == SessionMode.PRIVATE) == privateMode } == true
            }
            resourceLedger.own("download-preferences", downloadPreferences::close)
            val downloadCoordinator = AndroidDownloadCoordinator(
                projections = engineRuntime.projections,
                enginePort = engineRuntime.downloadPort,
                acceptResponse = downloadPreferences::accept,
            )
            downloadCoordinatorResource = downloadCoordinator
            resourceLedger.own(RESOURCE_DOWNLOAD_COORDINATOR, downloadCoordinator::close)

            val extensionPort = checkNotNull(engineRuntime.extensionPort) {
                "The direct Runtime does not expose its extension service"
            }
            check(extensionPort.capabilities.isCompleteProductProjection) {
                "The direct Runtime extension service is not a complete product projection"
            }
            extensionPortResource = extensionPort
            val newExtensionManager = AndroidExtensionManager(
                context = applicationContext,
                port = extensionPort,
                activeTab = {
                    val id = activeSessionId
                    val session = id?.let(sessions::get)
                    if (id == null || session == null) {
                        null
                    } else {
                        id.value to (session.mode == SessionMode.PRIVATE)
                    }
                },
            )
            extensionManagerResource = newExtensionManager
            resourceLedger.own(RESOURCE_EXTENSIONS, newExtensionManager::close)
            extensionPort.bindTabDelegate(this)
            extensionPort.bindWindowDelegate(this)
            resourceLedger.own(RESOURCE_EXTENSION_TABS) {
                extensionPort.unbindTabDelegate(this)
                extensionPort.unbindWindowDelegate(this)
            }
            extensionPort.bindOptionalPermissionDelegate(newExtensionManager)
            resourceLedger.own(RESOURCE_EXTENSION_OPTIONAL_PERMISSIONS) {
                extensionPort.unbindOptionalPermissionDelegate(newExtensionManager)
            }

            val newWindowId = core.registerWindow()
            registeredWindowId = newWindowId
            val restoredKey = persistence.read().firstOrNull()?.windowKey
                ?: org.navis.browser.persistence.LEGACY_WINDOW_KEY
            windowRegistry.register(newWindowId, persistenceKey = restoredKey)
            resourceLedger.own(RESOURCE_WINDOW) {
                windowRegistry.windows.forEach { window ->
                    core.closeWindow(window.id)
                    windowRegistry.remove(window.id)
                }
            }
            resourceLedger.own(RESOURCE_SESSIONS, ::closeAllSessions)

            state = BrowserState(developerMode = developerSettings.enabled)
            acceptingCallbacks = true
            restoreSessions()
            if (sessions.isEmpty()) {
                createSession(SessionMode.NORMAL)
            }
            activateSession(restoredActiveSessionId ?: sessions.keys.first())
            newExtensionManager
        } catch (error: Throwable) {
            completeInitialization(Result.failure(error), onComplete)
            return
        }

        initialExtensionHostExpectedSessions += sessions.keys
        completeInitialExtensionHostReadiness()
        extensionTopologyEnabled = true
        awaitInitialExtensionHost().whenComplete { _, hostError ->
            onMain {
                if (hostError != null) {
                    completeInitialization(Result.failure(hostError), onComplete)
                    return@onMain
                }
                publishExtensionTopology().whenComplete { _, topologyError ->
                    onMain {
                        if (topologyError != null) {
                            completeInitialization(Result.failure(topologyError), onComplete)
                        } else {
                            product.initialize().whenComplete { _, productError ->
                                onMain {
                                    if (productError != null) {
                                        completeInitialization(Result.failure(productError), onComplete)
                                    } else {
                                        initializeExtensions(initializedExtensions, onComplete)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * The process-wide extension dispatcher is registered synchronously by a
     * chrome host before that host emits NavisAndroid:HostReady. A query is not
     * a queued Session command, and restored tabs need their native peers before
     * an extension can query them, so the first topology snapshot waits for all
     * Sessions present at initialization rather than racing native window startup.
     */
    private fun awaitInitialExtensionHost(): CompletionStage<Unit> {
        if (
            !initialExtensionHostReady.isDone &&
            !mainHandler.postDelayed(
                initialExtensionHostReadyTimeout,
                INITIAL_EXTENSION_HOST_TIMEOUT_MS,
            )
        ) {
            initialExtensionHostReady.completeExceptionally(
                IllegalStateException("Could not schedule the initial extension-host deadline"),
            )
        }
        initialExtensionHostReady.whenComplete { _, _ ->
            mainHandler.removeCallbacks(initialExtensionHostReadyTimeout)
        }
        return initialExtensionHostReady
    }

    private fun completeInitialExtensionHostReadiness() {
        if (
            initialExtensionHostExpectedSessions.isNotEmpty() &&
            initialExtensionHostReadySessions.containsAll(initialExtensionHostExpectedSessions)
        ) {
            initialExtensionHostReady.complete(Unit)
        }
    }

    private fun initializeExtensions(
        manager: AndroidExtensionManager,
        onComplete: (Result<Unit>) -> Unit,
    ) {
        var callbackEntered = false
        try {
            manager.initialize { result ->
                callbackEntered = true
                completeInitialization(result, onComplete)
            }
        } catch (error: Throwable) {
            if (callbackEntered) {
                throw error
            }
            completeInitialization(Result.failure(error), onComplete)
        }
    }

    private fun completeInitialization(
        result: Result<Unit>,
        onComplete: (Result<Unit>) -> Unit,
    ) {
        if (initializationCompleted) {
            return
        }
        initializationCompleted = true
        val error = result.exceptionOrNull()
        if (error == null && !closed) {
            onComplete(Result.success(Unit))
            return
        }
        onComplete(
            Result.failure(
                rollbackInitialization(
                    error ?: IllegalStateException("Android Navis Runtime closed during initialization"),
                ),
            ),
        )
    }

    override fun addObserver(observer: BrowserStateObserver) {
        observers += observer
        observer.onBrowserStateChanged(state)
    }

    override fun removeObserver(observer: BrowserStateObserver) {
        observers -= observer
    }

    override fun addTargetObserver(observer: TargetRequestObserver) {
        targetDelegate.addObserver(observer)
    }

    override fun removeTargetObserver(observer: TargetRequestObserver) {
        targetDelegate.removeObserver(observer)
    }

    internal fun addContextMenuObserver(observer: (AndroidContextMenuRequest) -> Unit) {
        if (!closed) contextMenuObservers += observer
    }

    internal fun removeContextMenuObserver(observer: (AndroidContextMenuRequest) -> Unit) {
        contextMenuObservers -= observer
    }

    internal fun addShortcutSettingsObserver(observer: (String) -> Unit) {
        if (!closed) shortcutSettingsObservers += observer
    }

    internal fun removeShortcutSettingsObserver(observer: (String) -> Unit) {
        shortcutSettingsObservers -= observer
    }

    internal fun respondToContextMenu(request: AndroidContextMenuRequest, itemId: String?): Boolean {
        val session = sessions[request.sessionId] ?: return false
        if (!isLive(request.sessionId) || activeSessionId != request.sessionId) {
            session.respondToContextMenu(request.token, null)
            return false
        }
        return session.respondToContextMenu(request.token, itemId)
    }

    internal fun createDevToolsHost(targetId: SessionId, initialTool: String? = null): AndroidDevToolsHost = inSessionWindow(targetId) {
        requireOpen()
        val target = session(targetId)
        val ownerWindow = windowRegistry.get(windowId)
        val viewId = reusableViewIds.removeFirstOrNull() ?: core.registerView(windowId)
        val toolsId = core.createSession(viewId, target.mode == SessionMode.PRIVATE)
        windowRegistry.ownSession(ownerWindow.id, SessionId(toolsId), published = false)
        engineRuntime.webAuthnPort?.setSessionWindow(SessionId(toolsId), ownerWindow.id)
        try {
            AndroidDevToolsHost(engineRuntime, toolsId, targetId.value, target.mode == SessionMode.PRIVATE, initialTool) {
                devToolsHosts.remove(toolsId)
                if (coreOwned) {
                    core.closeSession(toolsId)
                    windowRegistry.removeSession(SessionId(toolsId))
                    engineRuntime.webAuthnPort?.setSessionWindow(SessionId(toolsId), null)
                    if (!closed && windowRegistry.find(ownerWindow.id) != null) ownerWindow.reusableViewIds.addLast(viewId)
                }
            }.also { devToolsHosts[toolsId] = it }
        } catch (error: Throwable) {
            core.closeSession(toolsId)
            windowRegistry.removeSession(SessionId(toolsId))
            engineRuntime.webAuthnPort?.setSessionWindow(SessionId(toolsId), null)
            reusableViewIds.addLast(viewId)
            throw error
        }
    }

    internal fun prepareRelaunch(callback: (Result<Boolean>) -> Unit) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            onMain { prepareRelaunch(callback) }
            return
        }
        requireOpen()
        if (pendingNewWindowSessions.isNotEmpty()) {
            callback(Result.success(false))
            return
        }
        val initial = sessions.toMap()
        val initialIds = initial.keys.toList()
        val initialOpenStates = initial.mapValues { (_, session) -> session.renderSession.isOpen }
        val originalSelections = windowRegistry.windows.associate { it.id to it.selectedSessionId }
        val originalFocusedWindow = windowRegistry.focusedWindowId
        val originalActive = activeSessionId
        val navigations = initial.mapValues { (id, session) ->
            val navigation = core.navigationSnapshot(id.value)
            Triple(navigation.navigationId, navigation.url, session.state().navigation.contentTermination)
        }
        val expectedSelections = originalSelections.toMutableMap()
        var finished = false

        fun unchanged(): Boolean = !closed && acceptingCallbacks &&
            pendingNewWindowSessions.isEmpty() && initialIds == sessions.keys.toList() &&
            windowRegistry.windows.associate { it.id to it.selectedSessionId } == expectedSelections && initial.all { (id, original) ->
                sessions[id] === original && original.renderSession.isOpen == initialOpenStates[id] && runCatching {
                    val navigation = core.navigationSnapshot(id.value)
                    Triple(navigation.navigationId, navigation.url,
                        original.state().navigation.contentTermination) == navigations[id]
                }.getOrDefault(false)
            }

        fun restoreActive() {
            if (!closed && acceptingCallbacks) {
                originalSelections.forEach { (window, selected) ->
                    if (selected != null && windowIdForSession(selected) == window && sessions.containsKey(selected)) {
                        expectedSelections[window] = selected
                        activateSession(selected)
                    }
                }
            }
        }

        fun finish(result: Result<Boolean>) {
            if (finished) return
            finished = true
            val restored = runCatching(::restoreActive)
            val finalResult = restored.exceptionOrNull()?.let { Result.failure<Boolean>(it) } ?: result
            if (!closed && originalFocusedWindow != null && windowRegistry.find(originalFocusedWindow) != null &&
                windowRegistry.focusedWindowId != originalFocusedWindow) {
                focusProductWindow(originalFocusedWindow).whenComplete { _, error -> onMain {
                    callback(if (error == null) finalResult else Result.failure(error))
                } }
            } else callback(finalResult)
        }

        fun flushPreferences() {
            if (!unchanged()) { finish(Result.success(false)); return }
            // Preferences are process-wide. One live engine host supplies the acknowledgement;
            // native new-tab/settings surfaces still have a backing host for this fixed query.
            val host = initial[originalActive]?.takeIf { it.renderSession.isOpen }
                ?: initial.values.firstOrNull { it.renderSession.isOpen }
            if (host == null) { finish(Result.success(false)); return }
            val peer = host.renderSession
            try {
                peer.querySession("prefs:flush").whenComplete { serialized, error ->
                    onMain {
                        if (finished) return@onMain
                        if (!unchanged() || host.renderSession !== peer || !peer.isOpen) {
                            finish(Result.success(false))
                            return@onMain
                        }
                        if (error != null) { finish(Result.failure(error)); return@onMain }
                        try {
                            check(org.json.JSONObject(serialized).opt("flushed") == true) {
                                "The engine did not acknowledge its preference save"
                            }
                            finish(Result.success(true))
                        } catch (error: Throwable) {
                            finish(Result.failure(error))
                        }
                    }
                }
            } catch (error: Throwable) {
                finish(Result.failure(error))
            }
        }

        fun saveSnapshot() {
            if (!unchanged()) { finish(Result.success(false)); return }
            try {
                restoreActive()
                if (!unchanged()) { finish(Result.success(false)); return }
                // Sample product/Core state on the main thread. The worker receives only values.
                val snapshot = windowRegistry.windows.flatMap { window -> window.sessions.mapNotNull(sessions::get) }
                    .filter { it.mode == SessionMode.NORMAL }.map { session ->
                    val state = session.state()
                    PersistedSession(
                        uri = state.navigation.url.ifBlank { BLANK_URI },
                        title = state.navigation.title,
                        engineState = session.persistedEngineState(),
                        active = originalSelections[windowIdForSession(session.id)] == session.id,
                        nativeNewTab = state.nativeNewTab,
                        nativeHistory = session.persistedNativeHistory(),
                        windowKey = windowRegistry.get(checkNotNull(windowIdForSession(session.id))).persistenceKey,
                    )
                } + pendingRestoredWindows.values.flatten()
                mainHandler.removeCallbacks(persistRunnable)
                val sessionPersistence = persistence
                Thread({
                    val saved = runCatching { sessionPersistence.writeAndCommit(snapshot) }
                    onMain {
                        if (saved.isFailure) finish(Result.failure(checkNotNull(saved.exceptionOrNull())))
                        else flushPreferences()
                    }
                }, "NavisRelaunchSessions").apply { isDaemon = true }.start()
            } catch (error: Throwable) {
                finish(Result.failure(error))
            }
        }

        fun checkNext(index: Int) {
            if (finished) return
            if (!unchanged()) { finish(Result.success(false)); return }
            if (index == initialIds.size) { saveSnapshot(); return }
            val id = initialIds[index]
            val current = checkNotNull(initial[id])
            val state = current.state()
            if (((state.nativeRoute != null || state.nativeNewTab) && !current.hasBackingWebDocument()) ||
                state.navigation.contentTermination != org.navis.browser.api.ContentTermination.NONE
            ) {
                // A native-only root has no document; a native overlay with a backing
                // webpage still owes that document its beforeunload and exact snapshot.
                checkNext(index + 1)
                return
            }
            try {
                // The native target prompt must belong to the visible Session, including when a
                // background tab asks the user whether to leave an edited document.
                expectedSelections[checkNotNull(windowIdForSession(id))] = id
                activateSession(id)
                if (!unchanged()) { finish(Result.success(false)); return }
                focusProductWindow(checkNotNull(windowIdForSession(id))).thenCompose {
                    check(!finished && unchanged()) { "Session changed while focusing its close prompt" }
                    current.renderSession.querySession("session:can-close")
                }.whenComplete { serialized, error ->
                    onMain {
                        if (finished) return@onMain
                        if (!unchanged()) { finish(Result.success(false)); return@onMain }
                        if (error != null) { finish(Result.failure(error)); return@onMain }
                        try {
                            val reply = org.json.JSONObject(serialized)
                            if (!reply.optBoolean("allowed", false)) {
                                finish(Result.success(false))
                                return@onMain
                            }
                            if (current.mode == SessionMode.NORMAL) {
                                val engineState = reply.getString("state")
                                check(org.navis.browser.persistence.EngineSessionStatePolicy.accepts(engineState)) {
                                    "The engine could not supply a restorable session state"
                                }
                                current.onSessionStateChanged(engineState)
                            }
                            checkNext(index + 1)
                        } catch (error: Throwable) {
                            finish(Result.failure(error))
                        }
                    }
                }
            } catch (error: Throwable) {
                finish(Result.failure(error))
            }
        }
        checkNext(0)
    }

    override fun respondToPrompt(id: TargetRequestId, response: PromptResponse) {
        targetDelegate.respondToPrompt(id, response)
    }

    override fun notifySitePermissionShown(id: TargetRequestId) {
        targetDelegate.notifySitePermissionShown(id)
    }

    override fun respondToSitePermission(id: TargetRequestId, allow: Boolean) {
        targetDelegate.respondToSitePermission(id, allow)
    }

    override fun respondToSitePermission(id: TargetRequestId, decision: org.navis.browser.api.SitePermissionDecision) {
        targetDelegate.respondToSitePermission(id, decision)
    }

    override fun respondToPlatformPermission(id: TargetRequestId, granted: Boolean) {
        targetDelegate.respondToPlatformPermission(id, granted)
    }

    override fun respondToFilePicker(id: TargetRequestId, uris: List<String>) {
        targetDelegate.respondToFilePicker(id, uris)
    }

    override fun exitFullscreen() {
        targetDelegate.exitFullscreen(windowId)
    }

    internal fun cancelActivityPrompts(lease: AndroidWindowLease) {
        if (!windowRegistry.isCurrent(lease)) return
        targetDelegateResource?.cancelActivityPrompts(lease.windowId)
        unbindWebAuthn(lease)
    }

    /** Binds OS credentials only while a foreground Activity owns this Runtime. */
    internal fun bindWebAuthn(activity: Activity, lease: AndroidWindowLease) {
        if (windowRegistry.isCurrent(lease)) engineRuntime.webAuthnPort?.bindActivity(activity, lease)
    }

    internal fun unbindWebAuthn(lease: AndroidWindowLease) {
        engineRuntime.webAuthnPort?.unbindActivity(lease)
    }

    internal fun flushSessionState() {
        if (!closed) {
            sessions.values.forEach(AndroidSession::flushEngineState)
            schedulePersistence()
        }
    }

    override fun attachView(view: BrowserView) {
        requireOpen()
        val androidView = view as? AndroidBrowserView
            ?: throw IllegalArgumentException("View belongs to another Navis Platform backend")
        boundView.get()?.takeIf { it !== androidView }?.release()
        boundView = WeakReference(androidView)
        showActiveSession()
    }

    override fun detachView(view: BrowserView) {
        val androidView = view as? AndroidBrowserView ?: return
        if (boundView.get() === androidView) {
            androidView.release()
            boundView.clear()
        }
    }

    override fun createSession(mode: SessionMode, initialUri: String?): SessionId =
        createSessionInternal(mode, initialUri, null, activate = true).id

    override fun activateSession(sessionId: SessionId) = inSessionWindow(sessionId) {
        requireOpen()
        val next = sessions[sessionId] ?: error("Unknown Navis session ${sessionId.value}")
        targetDelegate.onSessionActivated(sessionId)
        if (activeSessionId == sessionId) {
            showActiveSession()
            return@inSessionWindow
        }
        if (activeSessionId != null) {
            extensionManager.onActiveTabChanged(windowId)
        }
        core.activateSession(sessionId.value)
        sessions[activeSessionId]?.setActive(false)
        activeSessionId = sessionId
        next.setActive(true)
        showActiveSession()
        publish(persist = true)
    }

    override fun moveSession(sessionId: SessionId, index: Int) = inSessionWindow(sessionId) {
        requireOpen()
        require(index >= 0) { "Invalid Navis tab index" }
        val (fromIndex, toIndex) = reorderSession(sessionId, index)
        if (fromIndex != toIndex) {
            publish(persist = true, movedTabId = sessionId.value)
        }
    }

    override fun closeSession(sessionId: SessionId) {
        closeSessionWithRecord(sessionId).whenComplete { _, error ->
            if (error != null) Log.w(LOG_TAG, "Could not close the selected Session", error)
        }
    }

    private fun closeSessionWithRecord(sessionId: SessionId): CompletionStage<Unit> {
        pendingSessionCloses[sessionId]?.let { return it }
        val session = session(sessionId)
        val owner = checkNotNull(windowIdForSession(sessionId))
        val window = windowRegistry.get(owner)
        val state = session.state()
        if (window.closing || session.mode != SessionMode.NORMAL ||
            ((state.nativeRoute != null || state.nativeNewTab) && !session.hasBackingWebDocument())) {
            closeSessionNow(sessionId)
            return CompletableFuture.completedFuture(Unit)
        }
        val result = CompletableFuture<Unit>()
        pendingSessionCloses[sessionId] = result
        val revision = core.navigationSnapshot(sessionId.value).navigationId
        val index = window.sessions.indexOf(sessionId)
        fun retire(snapshot: PersistedSession?) {
            if (pendingSessionCloses.remove(sessionId) !== result) return
            if (sessions[sessionId] !== session || windowIdForSession(sessionId) != owner) {
                result.completeExceptionally(IllegalStateException("Session changed while closing")); return
            }
            val valid = snapshot?.takeIf { !window.closing && core.navigationSnapshot(sessionId.value).navigationId == revision }
            try {
                closeSessionNow(sessionId)
                if (valid == null) result.complete(Unit)
                else checkNotNull(closedTabsResource).recordClosed(owner, index, session.mode, valid)
                    .whenComplete { _, error -> onMain {
                        if (error != null) Log.w(LOG_TAG, "Closed tab could not be retained", error)
                        result.complete(Unit)
                    } }
            } catch (error: Throwable) { result.completeExceptionally(error) }
        }
        val deadline = Runnable { retire(null) }
        mainHandler.postDelayed(deadline, 5_000)
        result.whenComplete { _, _ -> mainHandler.removeCallbacks(deadline) }
        try {
            session.renderSession.querySession("session:capture-closed").whenComplete { serialized, error -> onMain {
                if (result.isDone) return@onMain
                val snapshot = if (error == null) runCatching {
                    val value = org.json.JSONObject(serialized)
                    if (value.opt("restorable") != true) null else {
                        val state = value.getString("state")
                        val uri = value.getString("uri")
                        val native = session.persistedNativeHistory()?.takeIf {
                            org.navis.browser.pages.NativePageHistory.validated(it, uri) != null &&
                                org.navis.browser.pages.NativePageHistory.boundTo(it, state)
                        }
                        PersistedSession(uri, value.optString("title"), state, false,
                            nativeHistory = native, windowKey = window.persistenceKey)
                    }
                }.getOrNull() else null
                retire(snapshot)
            } }
        } catch (_: Throwable) { retire(null) }
        return result
    }

    private fun restoreClosedTab(record: org.navis.browser.persistence.ClosedTabRecord): CompletionStage<RestoredClosedTab> {
        requireOpen()
        val existing = record.liveWindowId?.let(windowRegistry::find)?.takeUnless { it.privateMode || it.closing }
            ?: windowRegistry.find(windowId)?.takeUnless { it.privateMode || it.closing }
            ?: windowRegistry.windows.firstOrNull { !it.privateMode && !it.closing }
        if (existing == null) return failedStage(IllegalStateException("No normal window is available for restore"))
        val result = CompletableFuture<RestoredClosedTab>()
        val entry = record.snapshot
        val session = inWindow(existing.id) { createSessionInternal(SessionMode.NORMAL, entry.uri, entry.engineState,
            activate = true, nativeNewTab = false, insertionIndex = record.index, restoredNativeHistory = entry.nativeHistory) }
        val deadline = Runnable {
            if (!result.isDone) {
                if (isLive(session.id)) runCatching { closeSessionNow(session.id) }
                result.completeExceptionally(IllegalStateException("Session restoration timed out"))
            }
        }
        mainHandler.postDelayed(deadline, 30_000)
        result.whenComplete { _, _ -> mainHandler.removeCallbacks(deadline) }
        checkNotNull(sessionHostReady[session.id]).thenCompose { onMainStage {
            check(isLive(session.id) && windowIdForSession(session.id) == existing.id)
            session.renderSession.querySession("session:restore-result")
        } }.thenCompose { serialized -> onMainStage {
            check(!result.isDone && isLive(session.id) && windowIdForSession(session.id) == existing.id)
            val reply = org.json.JSONObject(serialized)
            check(reply.opt("restored") == true) { "The engine did not acknowledge session restoration" }
            val actualState = reply.getString("state")
            val actual = checkNotNull(org.navis.browser.pages.EnginePageHistory.parse(actualState))
            val expected = checkNotNull(org.navis.browser.pages.EnginePageHistory.parse(entry.engineState))
            check(actual.index == expected.index && actual.entries.map { it.uri } == expected.entries.map { it.uri } &&
                reply.getString("uri") == expected.current?.uri) { "Restored engine history differs from the closed Session" }
            session.onSessionStateChanged(actualState)
            publishExtensionTopology()
        } }.whenComplete { _, error -> onMain {
            if (result.isDone) return@onMain
            if (error != null || !isLive(session.id) || windowIdForSession(session.id) != existing.id) {
                if (isLive(session.id)) runCatching { closeSessionNow(session.id) }
                result.completeExceptionally(error ?: IllegalStateException("Restored Session closed before readiness"))
            } else result.complete(RestoredClosedTab(session.id.value, existing.id))
        } }
        return result
    }

    private fun closeSessionNow(sessionId: SessionId) = inSessionWindow(sessionId) {
        requireOpen()
        newWindowCoordinator.cancelForOpener(sessionId)
        val session = sessions[sessionId]
            ?: error("Unknown Navis session ${sessionId.value}")
        extensionManager.onTabClosed(sessionId.value)
        sessions.remove(sessionId)
        val wasActive = activeSessionId == sessionId
        if (wasActive) {
            activeSessionId = null
        }

        val failures = mutableListOf<Throwable>()
        runCatching { targetDelegate.cancelSession(sessionId) }
            .exceptionOrNull()
            ?.let(failures::add)
        runCatching(session::close).exceptionOrNull()?.let(failures::add)
        val coreSessionClosed = runCatching { core.closeSession(sessionId.value) }
            .fold(
                onSuccess = { true },
                onFailure = { error ->
                    failures += error
                    false
                },
            )
        if (coreSessionClosed) {
            reusableViewIds.addLast(session.viewId)
            org.navis.browser.uploads.AndroidUploadSelections.releaseSession(sessionId.value)
        }
        windowRegistry.removeSession(sessionId)
        engineRuntime.webAuthnPort?.setSessionWindow(sessionId, null)
        sessionHostReady.remove(sessionId)?.completeExceptionally(IllegalStateException("Session closed"))
        runCatching(::clearPrivateDownloadsIfUnused)
            .exceptionOrNull()
            ?.let(failures::add)

        if (wasActive) {
            runCatching {
                val replacement = windowRegistry.get(windowId).sessions.lastOrNull()
                if (replacement != null) activateSession(replacement)
                else publish(persist = true)
            }.exceptionOrNull()?.let(failures::add)
        } else {
            runCatching { publish(persist = true) }.exceptionOrNull()?.let(failures::add)
        }
        throwFirstWithSuppressed(failures)
    }

    /** Product URL navigation retains the previous live webpage behind a new native-page tab. */
    internal fun navigate(uri: String) {
        val active = activeSessionId
        if (active == null) createSession(SessionMode.NORMAL, uri) else load(active, uri)
    }

    override fun load(sessionId: SessionId, uri: String) {
        session(sessionId).load(uri)
    }

    override fun reload(sessionId: SessionId) = session(sessionId).reload()

    override fun stop(sessionId: SessionId) = session(sessionId).stop()

    override fun goBack(sessionId: SessionId) {
        val current = session(sessionId)
        val state = current.state()
        if (state.nativeRoute == null || state.navigation.canGoBack) {
            current.goBack()
            return
        }
        if (!state.nativeNewTab) {
            current.resetToNativeNewTab()
        }
    }

    override fun goForward(sessionId: SessionId) = session(sessionId).goForward()

    override fun onTabCommand(
        request: EngineExtensionTabRequest,
    ): CompletionStage<EngineExtensionTabResult> {
        val result = CompletableFuture<EngineExtensionTabResult>()
        val execute = execute@{
            try {
                requireOpen()
                val owner = request.windowId ?: request.tabId?.let { windowIdForSession(SessionId(it)) }
                    ?: windowId
                if (request.tabId != null) check(windowIdForSession(SessionId(request.tabId)) == owner) {
                    "The requested tab belongs to another window"
                }
                if (request.operation == EngineExtensionTabOperation.REMOVE) {
                    val id = SessionId(checkNotNull(request.tabId))
                    val removed = session(id).toExtensionTabResult().copy(active = false)
                    closeSessionWithRecord(id).thenCompose { extensionTopologyTail }.whenComplete { _, error ->
                        if (error == null) result.complete(removed) else result.completeExceptionally(error)
                    }
                    return@execute
                }
                val commandResult = inWindow(owner) { executeTabCommand(request) }
                extensionTopologyTail.whenComplete { _, topologyError ->
                    if (topologyError == null) {
                        result.complete(commandResult)
                    } else {
                        result.completeExceptionally(topologyError)
                    }
                }
            } catch (error: Throwable) {
                result.completeExceptionally(error)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) execute() else mainHandler.post { execute() }
        return result
    }

    private fun executeTabCommand(request: EngineExtensionTabRequest): EngineExtensionTabResult =
        when (request.operation) {
            EngineExtensionTabOperation.CREATE -> {
                val mode = if (request.privateMode == true) {
                    SessionMode.PRIVATE
                } else {
                    SessionMode.NORMAL
                }
                val session = createSessionInternal(
                    mode = mode,
                    initialUri = request.url,
                    restoredState = null,
                    activate = request.active != false,
                    insertionIndex = request.index,
                )
                session.toExtensionTabResult()
            }
            EngineExtensionTabOperation.UPDATE -> {
                val session = session(SessionId(checkNotNull(request.tabId)))
                request.url?.let(session::load)
                if (request.active == true) {
                    activateSession(session.id)
                }
                session.toExtensionTabResult()
            }
            EngineExtensionTabOperation.REMOVE -> {
                val tabId = checkNotNull(request.tabId)
                val removed = session(SessionId(tabId))
                val index = windowRegistry.get(checkNotNull(windowIdForSession(removed.id))).sessions.indexOf(removed.id)
                val removedWindowId = checkNotNull(windowIdForSession(removed.id))
                val privateMode = removed.mode == SessionMode.PRIVATE
                closeSession(removed.id)
                EngineExtensionTabResult(
                    tabId = tabId,
                    windowId = removedWindowId,
                    index = index,
                    active = false,
                    privateMode = privateMode,
                )
            }
            EngineExtensionTabOperation.MOVE -> {
                val tabId = checkNotNull(request.tabId)
                moveSession(SessionId(tabId), checkNotNull(request.index))
                session(SessionId(tabId)).toExtensionTabResult()
            }
        }

    private fun AndroidSession.toExtensionTabResult() = EngineExtensionTabResult(
        tabId = id.value,
        windowId = checkNotNull(windowIdForSession(id)),
        index = windowRegistry.get(checkNotNull(windowIdForSession(id))).sessions.indexOf(id),
        active = id == windowRegistry.get(checkNotNull(windowIdForSession(id))).selectedSessionId,
        privateMode = mode == SessionMode.PRIVATE,
    )

    override fun setDeveloperMode(enabled: Boolean) {
        requireOpen()
        if (developerSettings.enabled == enabled || developerModeTransitionInFlight) {
            return
        }
        developerModeTransitionInFlight = true
        DeveloperModeCoordinator(
            currentValue = { developerSettings.enabled },
            applyToEngine = extensionManager::onDeveloperModeChanged,
            persistValue = { developerSettings.enabled = it },
            onApplied = {
                publish(persist = false)
            },
        ).setEnabled(enabled).whenComplete { result, error ->
            onMain {
                developerModeTransitionInFlight = false
                if (error != null || result == DeveloperModeChange.ENGINE_UNAVAILABLE) {
                    Log.w(
                        LOG_TAG,
                        "Live developer settings projection is unavailable",
                        error ?: IllegalStateException("The engine rejected developer mode"),
                    )
                }
            }
        }
    }

    override fun isLive(sessionId: SessionId): Boolean =
        acceptingCallbacks &&
            !closed &&
            (sessions.containsKey(sessionId) || pendingNewWindowSessions.containsKey(sessionId))

    override fun onSessionChanged(sessionId: SessionId, persist: Boolean) {
        if (sessions.containsKey(sessionId) && isLive(sessionId)) {
            publish(persist)
        }
    }

    override fun onSessionCrashed(sessionId: SessionId) {
        if (sessions.containsKey(sessionId) && isLive(sessionId)) {
            delegate.onSessionCrashed(sessionId)
        }
    }

    override fun onHistoryVisit(sessionId: SessionId, url: String, title: String) {
        if (isLive(sessionId) && sessions[sessionId]?.mode == SessionMode.NORMAL) {
            profileStore.recordVisit(url, title)
        }
    }

    override fun onHistoryTitleChanged(sessionId: SessionId, url: String, title: String) {
        if (isLive(sessionId) && sessions[sessionId]?.mode == SessionMode.NORMAL) {
            profileStore.updateHistoryTitle(url, title)
        }
    }

    override fun onRenderSurfaceRequired(sessionId: SessionId) {
        val owner = windowIdForSession(sessionId) ?: return
        if (sessionId == windowRegistry.find(owner)?.selectedSessionId && isLive(sessionId)) {
            windowViews[owner]?.get()?.refreshSurface()
        }
    }

    override fun onHostReady(sessionId: SessionId) {
        if (isLive(sessionId)) sessionHostReady[sessionId]?.complete(Unit)
        if (
            !initialExtensionHostReady.isDone &&
            sessions.containsKey(sessionId) &&
            isLive(sessionId)
        ) {
            initialExtensionHostReadySessions += sessionId
            completeInitialExtensionHostReadiness()
        }
    }

    override fun dispatchSessionTask(task: () -> Unit) = onMain(task)

    override fun onFullscreenChanged(sessionId: SessionId, enabled: Boolean) {
        if (sessions.containsKey(sessionId) && isLive(sessionId)) {
            targetDelegate.onFullscreenChanged(sessionId, enabled)
        } else if (enabled) {
            pendingNewWindowSessions[sessionId]?.exitFullscreen()
        }
    }

    override fun onContextMenuRequested(sessionId: SessionId, request: AndroidContextMenuRequest) {
        if (!isLive(sessionId) || request.sessionId != sessionId) return
        if (windowIdForSession(sessionId)?.let(windowRegistry::find)?.selectedSessionId != sessionId) {
            sessions[sessionId]?.respondToContextMenu(request.token, null)
            return
        }
        if (contextMenuObservers.isEmpty()) {
            sessions[sessionId]?.respondToContextMenu(request.token, null)
            return
        }
        contextMenuObservers.toList().forEach { observer ->
            runCatching { observer(request) }
        }
    }

    override fun onContextMenuUpdated(sessionId: SessionId, request: AndroidContextMenuRequest) {
        if (!isLive(sessionId) || request.sessionId != sessionId) return
        if (windowIdForSession(sessionId)?.let(windowRegistry::find)?.selectedSessionId != sessionId) {
            sessions[sessionId]?.respondToContextMenu(request.token, null)
            return
        }
        // Keep the original Java callback pending; this is only a same-token
        // projection refresh from an onShown listener.
        contextMenuObservers.toList().forEach { observer ->
            runCatching { observer(request) }
        }
    }

    override fun onShortcutSettingsRequested(sessionId: SessionId, extensionId: String) {
        val owner = windowIdForSession(sessionId) ?: return
        if (!isLive(sessionId) || windowRegistry.find(owner)?.selectedSessionId != sessionId || extensionId.isBlank()) return
        windowShortcutSettingsObservers.toList().forEach { observer ->
            runCatching { observer(owner, extensionId) }
        }
        shortcutSettingsObservers.toList().forEach { observer ->
            runCatching { observer(extensionId) }
        }
    }

    override fun onNewWindowRequested(
        openerSessionId: SessionId,
        uri: String,
        engineWindowToken: String,
        privateMode: Boolean,
    ): CompletionStage<Boolean> {
        val opener = sessions[openerSessionId]
            ?: return CompletableFuture.completedFuture(false)
        val childMode = if (opener.mode == SessionMode.PRIVATE || privateMode) {
            SessionMode.PRIVATE
        } else {
            SessionMode.NORMAL
        }
        return newWindowCoordinator.request(
            openerId = openerSessionId,
            engineWindowToken = engineWindowToken,
            createChild = {
                inSessionWindow(openerSessionId) {
                    createSessionShell(childMode, pendingNewWindow = true).also { child ->
                        child.prepareNewWindow(uri)
                    }
                }
            },
            openChild = { child -> child.openNewWindow(engineWindowToken) },
        )
    }

    internal fun listHistory(callback: (List<HistoryEntry>) -> Unit) {
        profileStore.listHistory(callback)
    }

    internal fun addressCandidates(query: String, includeHistory: Boolean,
        callback: (Result<List<org.navis.browser.persistence.AddressCandidate>>) -> Unit) =
        profileStore.addressCandidates(query, includeHistory, callback)

    internal fun clearHistory(onComplete: () -> Unit) {
        profileStore.clearHistory(onComplete)
    }

    internal fun listBookmarks(callback: (List<BookmarkEntry>) -> Unit) {
        profileStore.listBookmarks(callback)
    }

    internal fun observeBookmarksChanged(observer: () -> Unit): AutoCloseable {
        val observedStore = profileStore
        val routed: (org.navis.browser.persistence.BookmarkChange) -> Unit = { observer() }
        observedStore.observeBookmarks(routed)
        return AutoCloseable { observedStore.removeBookmarkObserver(routed) }
    }

    internal fun observeHistoryChanged(observer: () -> Unit): AutoCloseable {
        val observedStore = profileStore
        val routed: (org.navis.browser.persistence.HistoryChange) -> Unit = { observer() }
        observedStore.observeHistory(routed)
        return AutoCloseable { observedStore.removeHistoryObserver(routed) }
    }

    internal fun addBookmark(url: String, title: String, onComplete: () -> Unit = {}) {
        profileStore.addBookmark(url, title, onComplete)
    }

    internal fun removeBookmark(url: String, onComplete: () -> Unit) {
        profileStore.removeBookmark(url, onComplete)
    }

    internal fun listPasswords(callback: (List<PasswordEntry>) -> Unit) {
        profileStore.listPasswords(callback)
    }

    internal fun removePassword(guid: String, onComplete: () -> Unit) {
        profileStore.removePassword(guid, onComplete)
    }

    internal fun removeHistoryResult(url: String, done: (Result<Unit>) -> Unit) =
        profileStore.removeHistory(url, done)

    internal fun clearHistoryResult(done: (Result<Unit>) -> Unit) =
        profileStore.clearHistorySince(0, done)

    internal fun saveBookmark(draft: BookmarkDraft, done: (Result<Unit>) -> Unit) =
        profileStore.saveBookmark(draft, done)

    internal fun moveBookmark(id: String, parentId: String?, position: Int, done: (Result<Unit>) -> Unit) =
        profileStore.moveBookmark(id, parentId, position, done)

    internal fun deleteBookmark(id: String, done: (Result<Unit>) -> Unit) =
        profileStore.deleteBookmark(id, done)

    internal fun revealPassword(guid: String, done: (Result<String>) -> Unit) =
        profileStore.revealPassword(guid, done)

    internal fun deletePassword(guid: String, done: (Result<Unit>) -> Unit) =
        profileStore.deletePassword(guid, done)

    internal fun clearPasswords(done: (Result<Unit>) -> Unit) =
        profileStore.clearPasswordsSince(0, done)

    internal fun stopIdleProfile(): CompletionStage<Boolean>? {
        if (closed || windowRegistry.windows.any { !it.closing } ||
            extensionDownloadCoordinatorResource?.hasPendingTransfers == true) return null
        val stopped = engineRuntime.awaitStopped()
        val database = profileStoreResource?.closedCompletion ?: CompletableFuture.completedFuture(Unit)
        val closedTabs = closedTabsResource?.closedCompletion ?: CompletableFuture.completedFuture(Unit)
        val downloadHistory = extensionDownloadCoordinatorResource?.closedCompletion ?: CompletableFuture.completedFuture(Unit)
        close()
        return CompletableFuture.allOf(stopped.toCompletableFuture(), database, closedTabs, downloadHistory).thenApply { true }
    }

    override fun close() {
        windowAttention.close()
        windowMutationWaiters.values.toList().forEach { it.result.completeExceptionally(IllegalStateException("Runtime closed")) }
        if (closed) {
            return
        }
        acceptingCallbacks = false
        closed = true
        mainHandler.removeCallbacks(persistRunnable)
        mainHandler.removeCallbacks(initialExtensionHostReadyTimeout)
        initialExtensionHostReady.completeExceptionally(
            IllegalStateException("Android Navis Runtime closed before extension-host readiness"),
        )
        observers.clear()
        contextMenuObservers.clear()
        shortcutSettingsObservers.clear()
        runCatching(::persistSessions).exceptionOrNull()?.let { error ->
            Log.w(LOG_TAG, "Could not persist sessions during runtime close", error)
        }
        releaseOwnedResources().forEach { failure ->
            Log.w(LOG_TAG, "Could not release ${failure.resource}", failure.cause)
        }
    }

    private fun rollbackInitialization(error: Throwable): Throwable {
        acceptingCallbacks = false
        closed = true
        mainHandler.removeCallbacks(persistRunnable)
        mainHandler.removeCallbacks(initialExtensionHostReadyTimeout)
        initialExtensionHostReady.completeExceptionally(error)
        observers.clear()
        contextMenuObservers.clear()
        shortcutSettingsObservers.clear()
        releaseOwnedResources().forEach { failure ->
            error.addSuppressed(
                IllegalStateException(
                    "Could not release ${failure.resource} after initialization failure",
                    failure.cause,
                ),
            )
        }
        return error
    }

    private fun releaseOwnedResources(): List<RuntimeResourceLedger.ReleaseFailure> {
        val failures = resourceLedger.releaseAll().toMutableList()
        if (coreOwned) {
            runCatching(::releaseCore).exceptionOrNull()?.let { error ->
                failures += RuntimeResourceLedger.ReleaseFailure(RESOURCE_CORE, error)
            }
        }
        if (engineOwned) {
            runCatching(::releaseEngine).exceptionOrNull()?.let { error ->
                failures += RuntimeResourceLedger.ReleaseFailure(RESOURCE_ENGINE, error)
            }
        }
        return failures
    }

    private fun closeAllSessions() {
        val failures = mutableListOf<Throwable>()
        fun attempt(action: () -> Unit) {
            try {
                action()
            } catch (error: Throwable) {
                failures += error
            }
        }

        windowFacades.values.toList().forEach { attempt(it::retire) }
        windowViews.values.forEach { reference -> attempt { reference.get()?.release() }; reference.clear() }
        windowViews.clear()
        devToolsHosts.values.toList().forEach { host -> attempt(host::close) }
        devToolsHosts.clear()
        attempt(newWindowCoordinator::close)
        sessions.values.toList().forEach { session ->
            attempt { extensionManagerResource?.onTabClosed(session.id.value) }
            attempt { targetDelegateResource?.cancelSession(session.id) }
            attempt(session::close)
            attempt { core.closeSession(session.id.value) }
            attempt { org.navis.browser.uploads.AndroidUploadSelections.releaseSession(session.id.value) }
            windowRegistry.removeSession(session.id)
            engineRuntime.webAuthnPort?.setSessionWindow(session.id, null)
        }
        pendingNewWindowSessions.values.toList().forEach { session ->
            attempt { targetDelegateResource?.cancelSession(session.id) }
            attempt(session::close)
            attempt { core.closeSession(session.id.value) }
            attempt { org.navis.browser.uploads.AndroidUploadSelections.releaseSession(session.id.value) }
            windowRegistry.removeSession(session.id)
            engineRuntime.webAuthnPort?.setSessionWindow(session.id, null)
        }
        sessions.clear()
        pendingNewWindowSessions.clear()
        windowRegistry.windows.forEach { it.reusableViewIds.clear(); it.selectedSessionId = null }
        sessionHostReady.values.forEach { it.completeExceptionally(IllegalStateException("Session closed")) }
        sessionHostReady.clear()
        restoredActiveSessionId = null
        attempt(::clearPrivateDownloadsIfUnused)

        throwFirstWithSuppressed(failures)
    }

    private fun throwFirstWithSuppressed(failures: List<Throwable>) {
        failures.firstOrNull()?.let { first ->
            failures.drop(1).forEach(first::addSuppressed)
            throw first
        }
    }

    @Synchronized
    private fun releaseCore() {
        if (!coreOwned) {
            return
        }
        coreOwned = false
        closeCoreBridge(core)
    }

    @Synchronized
    private fun releaseEngine() {
        if (!engineOwned) {
            return
        }
        engineOwned = false
        engineRuntime.close()
    }

    private fun restoreSessions() {
        val initialWindow = windowId
        val groups = persistence.read().groupBy { it.windowKey }
        pendingRestoredWindows.putAll(groups.filterKeys { it != windowRegistry.get(initialWindow).persistenceKey })
        restoreWindowEntries(initialWindow, groups[windowRegistry.get(initialWindow).persistenceKey].orEmpty())
        restoredActiveSessionId = windowRegistry.get(initialWindow).selectedSessionId
    }

    private fun restoreWindowEntries(owner: Long, entries: List<PersistedSession>) = inWindow(owner) {
        entries.forEach { entry ->
            val session = restoreSession(entry) ?: return@forEach
            if (entry.active) windowRegistry.get(owner).selectedSessionId = session.id
        }
        val selected = windowRegistry.get(owner).selectedSessionId
            ?: windowRegistry.get(owner).sessions.firstOrNull()
        windowRegistry.get(owner).selectedSessionId = null
        if (selected != null) activateSession(selected)
    }

    private fun restoreSession(entry: PersistedSession): AndroidSession? = try {
        createSessionInternal(
            mode = SessionMode.NORMAL,
            initialUri = entry.uri.takeUnless { entry.nativeNewTab },
            restoredState = entry.engineState,
            activate = false,
            nativeNewTab = entry.nativeNewTab,
            restoredNativeHistory = entry.nativeHistory,
        )
    } catch (error: Exception) {
        Log.w(LOG_TAG, "Skipping one invalid persisted session entry", error)
        null
    }

    private fun createSessionInternal(
        mode: SessionMode,
        initialUri: String?,
        restoredState: String?,
        activate: Boolean,
        nativeNewTab: Boolean = initialUri == null && restoredState == null,
        insertionIndex: Int? = null,
        restoredNativeHistory: NativePageHistorySnapshot? = null,
    ): AndroidSession {
        val session = createSessionShell(mode)
        try {
            session.setInitialNativeNewTab(nativeNewTab)
            session.open(restoredState, initialUri, restoredNativeHistory)
            session.setActive(false)
            insertionIndex?.let { reorderSession(session.id, it) }
        } catch (error: Throwable) {
            discardSessionShell(session).forEach(error::addSuppressed)
            throw error
        }
        if (activate) {
            activateSession(session.id)
        } else {
            publish(persist = false)
        }
        return session
    }

    /** Rebuilds the insertion-ordered map without changing Session ownership. */
    private fun reorderSession(sessionId: SessionId, requestedIndex: Int): Pair<Int, Int> =
        windowRegistry.move(sessionId, requestedIndex)

    private fun createSessionShell(
        mode: SessionMode,
        pendingNewWindow: Boolean = false,
    ): AndroidSession {
        requireOpen()
        check(windowRegistry.get(windowId).privateMode == (mode == SessionMode.PRIVATE)) {
            "A product window cannot mix normal and private Sessions"
        }
        val viewId = reusableViewIds.removeFirstOrNull() ?: core.registerView(windowId)
        val sessionId = try {
            SessionId(core.createSession(viewId, mode == SessionMode.PRIVATE))
        } catch (error: Throwable) {
            reusableViewIds.addLast(viewId)
            throw error
        }
        val session = try {
            AndroidSession(
                id = sessionId,
                viewId = viewId,
                mode = mode,
                core = core,
                engineRuntime = engineRuntime,
                targetDelegate = targetDelegate,
                owner = this,
            )
        } catch (error: Throwable) {
            val coreSessionClosed = runCatching { core.closeSession(sessionId.value) }
                .fold(
                    onSuccess = { true },
                    onFailure = { closeError ->
                        error.addSuppressed(closeError)
                        false
                    },
                )
            if (coreSessionClosed) {
                reusableViewIds.addLast(viewId)
            }
            throw error
        }
        windowRegistry.ownSession(windowId, sessionId, published = !pendingNewWindow)
        engineRuntime.webAuthnPort?.setSessionWindow(sessionId, windowId)
        sessionHostReady[sessionId] = CompletableFuture()
        if (pendingNewWindow) {
            pendingNewWindowSessions[sessionId] = session
        } else {
            sessions[sessionId] = session
        }
        return session
    }

    private fun discardSessionShell(session: AndroidSession): List<Throwable> = inSessionWindow(session.id) {
        sessions.remove(session.id)
        pendingNewWindowSessions.remove(session.id)
        val failures = listOf<() -> Unit>(
            { targetDelegate.cancelSession(session.id) },
            session::close,
        ).mapNotNull { cleanup -> runCatching(cleanup).exceptionOrNull() }
            .toMutableList()
        val coreSessionClosed = runCatching { core.closeSession(session.id.value) }
            .fold(
                onSuccess = { true },
                onFailure = { error ->
                    failures += error
                    false
                },
            )
        if (coreSessionClosed) {
            reusableViewIds.addLast(session.viewId)
        }
        windowRegistry.removeSession(session.id)
        engineRuntime.webAuthnPort?.setSessionWindow(session.id, null)
        sessionHostReady.remove(session.id)?.completeExceptionally(IllegalStateException("Session closed"))
        runCatching(::clearPrivateDownloadsIfUnused)
            .exceptionOrNull()
            ?.let(failures::add)
        failures
    }

    private fun clearPrivateDownloadsIfUnused() {
        val privateSessionExists = sessions.values.any { it.mode == SessionMode.PRIVATE } ||
            pendingNewWindowSessions.values.any { it.mode == SessionMode.PRIVATE }
        if (!privateSessionExists) {
            extensionDownloadCoordinatorResource?.clearPrivateHistory()
        }
    }

    private fun acceptPendingNewWindow(session: AndroidSession) {
        check(pendingNewWindowSessions.remove(session.id) === session) {
            "New-window child Session is no longer pending"
        }
        check(session.renderSession.isOpen) { "New-window child engine did not open" }
        sessions[session.id] = session
        windowRegistry.publishSession(session.id)
        session.setActive(false)
        activateSession(session.id)
    }

    private fun discardPendingNewWindow(session: AndroidSession) {
        throwFirstWithSuppressed(discardSessionShell(session))
    }

    private fun session(sessionId: SessionId): AndroidSession {
        requireOpen()
        return sessions[sessionId] ?: error("Unknown Navis session ${sessionId.value}")
    }

    private fun showActiveSession() {
        val active = sessions[activeSessionId] ?: return
        boundView.get()?.show(active.renderSession)
    }

    private fun publish(
        persist: Boolean,
        movedTabId: Long? = null,
    ): CompletionStage<Unit> {
        state = currentWindowId()?.let(::stateForWindow) ?: BrowserState(developerMode = developerSettings.enabled)
        core.checkInvariants()
        val topologyPublication = publishExtensionTopology(movedTabId)
        if (persist) {
            schedulePersistence()
        }
        observers.toList().forEach { it.onBrowserStateChanged(state) }
        return topologyPublication
    }

    private fun publishExtensionTopology(
        movedTabId: Long? = null,
    ): CompletionStage<Unit> {
        if (!extensionTopologyEnabled) {
            return CompletableFuture.completedFuture(Unit)
        }
        extensionTopologyBroken?.let { error ->
            return CompletableFuture<Unit>().also { it.completeExceptionally(error) }
        }
        val port = checkNotNull(extensionPortResource) {
            "Extension topology port is unavailable"
        }
        val publishedWindows = windowRegistry.windows.filter { it.id == registeredWindowId || it.taskId != null }
        val windows = publishedWindows.map { window ->
            EngineExtensionWindowTopologyEntry(window.id, window.selectedSessionId?.value,
                window.privateMode || (window.sessions.isNotEmpty() && window.sessions.all { sessions[it]?.mode == SessionMode.PRIVATE }),
                window.focused, window.bounds.left, window.bounds.top, window.bounds.width, window.bounds.height,
                EngineExtensionWindowState.valueOf(window.extensionState.name))
        }
        val entries = publishedWindows.flatMap { window -> window.sessions.mapIndexedNotNull { index, id ->
            sessions[id]?.let { session -> EngineExtensionTabTopologyEntry(
                session.id.value, index, session.mode == SessionMode.PRIVATE, window.id) }
        } }
        val previous = lastExtensionTopology
        val currentId = currentWindowId()?.takeIf { candidate -> windows.any { it.windowId == candidate } }
            ?: windows.firstOrNull()?.windowId ?: 0L
        val nextActiveId = windowRegistry.find(currentId)?.selectedSessionId?.value
        val topologyChanged = previous == null ||
            previous.windowId != currentId || previous.windows != windows ||
            previous.focusedWindowId != windowRegistry.focusedWindowId ||
            previous.lastFocusedWindowId != windowRegistry.lastFocusedWindowId ||
            previous.focusOrder != windowRegistry.focusOrder ||
            previous.activeTabId != nextActiveId ||
            previous.tabs != entries
        if (!topologyChanged && movedTabId == null) {
            return extensionTopologyTail
        }

        val events = mutableListOf<EngineExtensionTabTopologyEvent>()
        if (previous != null) {
            val previousIds = previous.tabs.mapTo(linkedSetOf()) { it.tabId }
            val nextIds = entries.mapTo(linkedSetOf()) { it.tabId }
            previous.tabs
                .filterNot { it.tabId in nextIds }
                .forEach { entry ->
                    events += EngineExtensionTabTopologyEvent(
                        EngineExtensionTabTopologyEventType.REMOVED,
                        entry.tabId,
                        isWindowClosing = windowRegistry.find(entry.windowId)?.closing == true ||
                            windowRegistry.find(entry.windowId) == null,
                    )
                }
            entries
                .filterNot { it.tabId in previousIds }
                .forEach { entry ->
                    events += EngineExtensionTabTopologyEvent(
                        EngineExtensionTabTopologyEventType.CREATED,
                        entry.tabId,
                    )
                }
            if (movedTabId != null && movedTabId in previousIds && movedTabId in nextIds) {
                val fromIndex = previous.tabs.first { it.tabId == movedTabId }.index
                val toIndex = entries.first { it.tabId == movedTabId }.index
                if (fromIndex != toIndex) {
                    events += EngineExtensionTabTopologyEvent(
                        EngineExtensionTabTopologyEventType.MOVED,
                        movedTabId,
                    )
                }
            }
            windows.forEach { window ->
                val old = previous.windows.firstOrNull { it.windowId == window.windowId }
                if (old?.activeTabId != window.activeTabId && window.activeTabId != null) {
                    events += EngineExtensionTabTopologyEvent(EngineExtensionTabTopologyEventType.ACTIVATED,
                        window.activeTabId, previousTabId = old?.activeTabId)
                }
            }
        }

        val topology = EngineExtensionTabTopology(
            revision = nextExtensionTopologyRevision(),
            windowId = currentId,
            activeTabId = nextActiveId,
            tabs = entries,
            events = events,
            windows = windows,
            focusedWindowId = windowRegistry.focusedWindowId,
            lastFocusedWindowId = windowRegistry.lastFocusedWindowId,
            focusOrder = windowRegistry.focusOrder,
        )
        lastExtensionTopology = topology
        val publication = extensionTopologyTail.thenCompose {
            port.updateTabTopology(topology)
        }
        extensionTopologyTail = publication
        publication.whenComplete { _, error ->
            if (error != null) {
                onMain {
                    if (extensionTopologyBroken == null) {
                        extensionTopologyBroken = error
                        Log.e(LOG_TAG, "Extension tab topology publication failed closed", error)
                    }
                }
            }
        }
        return publication
    }

    private fun persistSessions() {
        persistence.write(
            windowRegistry.windows.flatMap { window -> window.sessions.mapNotNull(sessions::get) }
                .filter { it.mode == SessionMode.NORMAL }
                .map { session ->
                    val sessionState = session.state()
                    val navigation = sessionState.navigation
                    PersistedSession(
                        uri = navigation.url.ifBlank { BLANK_URI },
                        title = navigation.title,
                        engineState = session.persistedEngineState(),
                        active = windowIdForSession(session.id)?.let(windowRegistry::find)?.selectedSessionId == session.id,
                        nativeNewTab = sessionState.nativeNewTab,
                        nativeHistory = session.persistedNativeHistory(),
                        windowKey = windowRegistry.get(checkNotNull(windowIdForSession(session.id))).persistenceKey,
                    )
                } + pendingRestoredWindows.values.flatten(),
        )
    }

    private fun schedulePersistence() {
        mainHandler.removeCallbacks(persistRunnable)
        mainHandler.postDelayed(persistRunnable, PERSISTENCE_DELAY_MS)
    }

    private fun requireOpen() {
        check(acceptingCallbacks && !closed) { "Android Navis Runtime is not open" }
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
    }

    private fun <T> onMainStage(action: () -> CompletionStage<T>): CompletionStage<T> {
        val result = CompletableFuture<T>()
        onMain {
            try { action().whenComplete { value, error ->
                if (error == null) result.complete(value) else result.completeExceptionally(error)
            } } catch (error: Throwable) { result.completeExceptionally(error) }
        }
        return result
    }

    private fun <T> failedStage(error: Throwable): CompletableFuture<T> =
        CompletableFuture<T>().apply { completeExceptionally(error) }

    companion object {
        private const val LOG_TAG = "NavisRuntime"
        private const val BLANK_URI = "about:blank"
        private const val PERSISTENCE_DELAY_MS = 500L
        private const val INITIAL_EXTENSION_HOST_TIMEOUT_MS = 10_000L
        private const val RESOURCE_CORE = "core"
        private const val RESOURCE_ENGINE = "engine-runtime"
        private const val RESOURCE_PROFILE = "profile"
        private const val RESOURCE_LOGIN_STORAGE = "login-storage"
        private const val RESOURCE_TARGET = "target"
        private const val RESOURCE_DOWNLOADS = "downloads"
        private const val RESOURCE_EXTENSION_DOWNLOADS = "extension-downloads"
        private const val RESOURCE_PRODUCT_BROWSING_DATA = "product-browsing-data"
        private const val RESOURCE_DOWNLOAD_COORDINATOR = "download-coordinator"
        private const val RESOURCE_EXTENSIONS = "extensions"
        private const val RESOURCE_EXTENSION_TABS = "extension-tabs"
        private const val RESOURCE_EXTENSION_OPTIONAL_PERMISSIONS =
            "extension-optional-permissions"
        private const val RESOURCE_WINDOW = "window"
        private const val RESOURCE_SESSIONS = "sessions"
        private val NEXT_EXTENSION_TOPOLOGY_REVISION = AtomicLong(0)

        private fun nextExtensionTopologyRevision(): Long =
            NEXT_EXTENSION_TOPOLOGY_REVISION.incrementAndGet()

        fun createAsync(
            context: Context,
            delegate: BrowserDelegate,
            onReady: (Result<AndroidBrowserRuntime>) -> Unit,
        ) {
            val applicationContext = context.applicationContext
            val delivered = AtomicBoolean(false)

            val developerMode = DeveloperSettings(applicationContext).enabled
            val startupPreferences = createAndroidSettingsHost(applicationContext).use {
                it.snapshot.engineStartupPreferences()
            }.toMutableMap().apply {
                (applicationContext as? org.navis.browser.NavisApplication)?.let { application ->
                    put("intl.locale.requested", application.resolvedDisplayLanguageTag)
                }
            }
            DirectEngineRuntimeAdapter.createAsync(
                applicationContext,
                developerMode,
                startupPreferences,
            ) { engineResult ->
                val engineRuntime = engineResult.getOrElse { error ->
                    if (delivered.compareAndSet(false, true)) {
                        onReady(Result.failure(error))
                    }
                    return@createAsync
                }

                var openedCore: CoreBridge? = null
                val browserRuntime = try {
                    val core = NativeCoreBridge.open().also { openedCore = it }
                    AndroidBrowserRuntime(applicationContext, engineRuntime, core, delegate)
                } catch (error: Throwable) {
                    openedCore?.let { core ->
                        runCatching { closeCoreBridge(core) }
                            .exceptionOrNull()
                            ?.let(error::addSuppressed)
                    }
                    runCatching(engineRuntime::close).exceptionOrNull()?.let(error::addSuppressed)
                    if (delivered.compareAndSet(false, true)) {
                        onReady(Result.failure(error))
                    }
                    return@createAsync
                }

                var initializationCallbackEntered = false
                try {
                    browserRuntime.initialize { initializationResult ->
                        initializationCallbackEntered = true
                        val error = initializationResult.exceptionOrNull()
                        if (error == null) {
                            if (delivered.compareAndSet(false, true)) {
                                onReady(Result.success(browserRuntime))
                            }
                        } else if (delivered.compareAndSet(false, true)) {
                            onReady(Result.failure(error))
                        }
                    }
                } catch (error: Throwable) {
                    if (initializationCallbackEntered) {
                        throw error
                    }
                    if (delivered.compareAndSet(false, true)) {
                        onReady(Result.failure(browserRuntime.rollbackInitialization(error)))
                    }
                }
            }
        }

        private fun closeCoreBridge(core: CoreBridge) {
            val failures = mutableListOf<Throwable>()
            listOf<() -> Unit>(
                core::checkInvariants,
                { core.shutdown() },
                core::close,
            ).forEach { closeStep ->
                runCatching(closeStep).exceptionOrNull()?.let(failures::add)
            }
            failures.firstOrNull()?.let { first ->
                failures.drop(1).forEach(first::addSuppressed)
                throw first
            }
        }
    }
}
