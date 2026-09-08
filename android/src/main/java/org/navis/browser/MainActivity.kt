/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.os.Process
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import org.navis.browser.api.SessionMode
import org.navis.browser.engine.AndroidBrowserRuntime
import org.navis.browser.engine.AndroidWindowRuntime
import org.navis.browser.engine.AndroidWindowLease
import org.navis.browser.engine.AndroidWindowLaunch
import org.navis.browser.engine.AndroidWindowBounds
import org.navis.browser.engine.AndroidWindowTaskModeController
import org.navis.browser.engine.AndroidExtensionDownloadSaveAs
import org.navis.browser.extensions.ExtensionNotice
import org.navis.browser.extensions.extensionNoticeForOrNull
import org.navis.browser.ui.AndroidPlatformRequests
import org.navis.browser.ui.CoreStartingScreen
import org.navis.browser.ui.CoreUnavailableScreen
import org.navis.browser.ui.NavisBrowserApp
import org.navis.browser.settings.flushAndroidSettingsForRelaunch
import kotlin.system.exitProcess

open class MainActivity : AppCompatActivity() {
    private var runtime: AndroidBrowserRuntime? = null
    private var windowRuntime: AndroidWindowRuntime? = null
    private var windowLease: AndroidWindowLease? = null
    private var windowTaskMode: AndroidWindowTaskModeController? = null
    private var topResumed = false
    private var restoredWindowId: Long? = null
    private var restoredWindowKey: String? = null
    private var runtimeRequest: RuntimeRequest? = null
    private val runtimeDelivery = StartedRuntimeDelivery<AndroidBrowserRuntime>(::acceptRuntimeResult)
    private val runtimeLifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            val acceptedNow = runtimeDelivery.onStarted()
            if (!acceptedNow && !cannotAcceptRuntime() &&
                lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            ) {
                // onStop relinquishes foreground credential UI; restore it on the next start.
                // The first handoff already binds it inside acceptRuntimeResult.
                windowLease?.let { lease ->
                    runtime?.windowVisibilityChanged(lease, true)
                    runtime?.bindWebAuthn(this@MainActivity, lease)
                    if (hasWindowFocus()) runtime?.windowFocusChanged(lease, true)
                }
            }
        }

        override fun onStop(owner: LifecycleOwner) = runtimeDelivery.onStopped()

        override fun onDestroy(owner: LifecycleOwner) = runtimeDelivery.close()
    }
    private var runtimeResult by mutableStateOf<Result<AndroidWindowRuntime>?>(null)
    private lateinit var platformRequests: AndroidPlatformRequests
    private lateinit var viewIntentGate: ViewIntentGate
    private var extensionDownloadSaveAsBinding: AndroidExtensionDownloadSaveAs.Registration? = null
    private var relaunchInProgress = false
    private var relaunchHandoff: RelaunchHandoff? = null
    private val browserKeyboardShortcuts = BrowserKeyboardShortcuts()

    /** Main and AdditionalWindowActivity bind only their own current window lease. */
    internal fun bindBrowserKeyboardShortcuts(
        facade: AndroidWindowRuntime,
        handle: (BrowserKeyboardAction) -> Boolean,
    ): AutoCloseable = browserKeyboardShortcuts.register(browserKeyboardWindowGuard(facade), handle)

    /** Capture this lease before an async action; never adopt a replacement lease. */
    internal fun browserWindowLease(facade: AndroidWindowRuntime): AndroidWindowLease? = windowLease?.takeIf { lease ->
        windowRuntime === facade && facade.windowId == lease.windowId &&
            runtime?.windowRegistry?.isCurrent(lease) == true &&
            runtime?.windowRegistry?.find(lease.windowId)?.closing == false &&
            !cannotAcceptRuntime() && !relaunchInProgress
    }

    /** Capture this lease before an async action; never adopt a replacement lease. */
    internal fun browserKeyboardWindowGuard(facade: AndroidWindowRuntime): () -> Boolean {
        val lease = windowLease
        return {
            lease != null && windowLease == lease && windowRuntime === facade &&
                facade.windowId == lease.windowId && runtime?.windowRegistry?.isCurrent(lease) == true &&
                runtime?.windowRegistry?.find(lease.windowId)?.closing == false &&
                !cannotAcceptRuntime() && !relaunchInProgress && hasWindowFocus() &&
                lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        browserKeyboardShortcuts.dispatch(event) || super.dispatchKeyEvent(event)

    override fun attachBaseContext(newBase: Context) {
        val application = newBase.applicationContext as? NavisApplication
        super.attachBaseContext(application?.localizedContext(newBase) ?: newBase)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val expectedProfile = intent.getStringExtra(ProfileWindows.EXPECTED_PROFILE)
        val actualProfile = (application as NavisApplication).profileScope?.currentId
        if (expectedProfile != null && expectedProfile != actualProfile) {
            runCatching { ProfileWindows.launch(this, expectedProfile, intent) }
                .onFailure { Toast.makeText(this, R.string.profile_operation_failed, Toast.LENGTH_LONG).show() }
            finishAndRemoveTask()
            return
        }
        if ((application as NavisApplication).stopping) {
            runCatching {
                startActivity(RelaunchActivity.intent(this, RelaunchHandoff({}, {}), source = intent))
            }.onFailure { Toast.makeText(this, R.string.relaunch_failed, Toast.LENGTH_LONG).show() }
            finishAndRemoveTask()
            return
        }
        enableEdgeToEdge()
        restoredWindowId = savedInstanceState?.getLong(AndroidWindowLaunch.SAVED_ID)?.takeIf { it > 0 }
        restoredWindowKey = savedInstanceState?.getString(AndroidWindowLaunch.SAVED_KEY)
        viewIntentGate = ViewIntentGate(
            savedInstanceState?.getBoolean(STATE_VIEW_INTENT_CONSUMED, false) == true,
        )
        platformRequests = AndroidPlatformRequests(this)
        extensionDownloadSaveAsBinding = AndroidExtensionDownloadSaveAs.register(this)
        lifecycle.addObserver(runtimeLifecycleObserver)

        runtimeRequest = (application as NavisApplication)
            .runtime(::onRuntimeReady, safeMode = intent.getBooleanExtra(RelaunchActivity.EXTRA_SAFE_MODE, false))
            .takeIf(RuntimeRequest::pending)
        setContent {
            when (val result = runtimeResult) {
                null -> CoreStartingScreen()
                else -> {
                    val activeRuntime = result.getOrNull()
                    if (activeRuntime == null) {
                        val message = if (
                            extensionNoticeForOrNull(result.exceptionOrNull()) ==
                            ExtensionNotice.BUILT_IN_UNAVAILABLE
                        ) {
                            R.string.extension_notice_builtin_unavailable
                        } else {
                            R.string.core_unavailable
                        }
                        CoreUnavailableScreen(message)
                    } else {
                        NavisBrowserApp(activeRuntime)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val expected = intent.getStringExtra(ProfileWindows.EXPECTED_PROFILE)
        if (expected != null && expected != (application as NavisApplication).profileScope?.currentId) {
            runCatching { ProfileWindows.launch(this, expected, intent) }
                .onFailure { Toast.makeText(this, R.string.profile_operation_failed, Toast.LENGTH_LONG).show() }
            return
        }
        viewIntentGate.onNewDelivery()
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::viewIntentGate.isInitialized) {
            outState.putBoolean(STATE_VIEW_INTENT_CONSUMED, viewIntentGate.consumed)
        }
        windowLease?.let { lease ->
            outState.putLong(AndroidWindowLaunch.SAVED_ID, lease.windowId)
            runtime?.windowRegistry?.find(lease.windowId)?.persistenceKey?.let {
                outState.putString(AndroidWindowLaunch.SAVED_KEY, it)
            }
        }
        super.onSaveInstanceState(outState)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) browserKeyboardShortcuts.clearPressed()
        windowLease?.let { lease -> runtime?.windowFocusChanged(lease, hasFocus) }
        if (hasFocus) reportWindowBounds()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        windowTaskMode?.onWindowModeChanged()
        window.decorView.post(::reportWindowBounds)
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        windowTaskMode?.onWindowModeChanged()
        window.decorView.post(::reportWindowBounds)
    }

    override fun onTopResumedActivityChanged(isTopResumedActivity: Boolean) {
        super.onTopResumedActivityChanged(isTopResumedActivity)
        topResumed = isTopResumedActivity
        windowTaskMode?.onTopResumedActivityChanged(isTopResumedActivity)
    }

    private fun bindWindowTaskMode(lease: AndroidWindowLease) {
        windowTaskMode?.close()
        val facade = checkNotNull(windowRuntime)
        val shared = checkNotNull(runtime)
        val controller = AndroidWindowTaskModeController(this,
            isCurrent = { browserWindowLease(facade) == lease },
            onStateChanged = { maximized -> shared.reportWindowTaskModeChanged(lease, maximized) },
        )
        windowTaskMode = controller
        controller.onTopResumedActivityChanged(topResumed)
        shared.windowRegistry.find(lease.windowId)?.taskFullscreenRestoreTaskId?.let { confirmedTask ->
            if (!controller.adoptConfirmedEntry(confirmedTask)) shared.reportWindowTaskModeChanged(lease, false)
        }
    }

    internal fun requestWindowTaskMode(lease: AndroidWindowLease, enter: Boolean): java.util.concurrent.CompletionStage<Unit> {
        check(windowLease == lease && windowRuntime?.let(::browserWindowLease) == lease) { "Window Activity changed" }
        return checkNotNull(windowTaskMode) { "Task window-mode provider is unavailable" }.request(enter)
    }

    private fun reportWindowBounds() {
        val lease = windowLease ?: return
        val bounds = if (android.os.Build.VERSION.SDK_INT >= 30) windowManager.currentWindowMetrics.bounds
        else android.graphics.Rect().also { window.decorView.getWindowVisibleDisplayFrame(it) }
        runtime?.windowBoundsChanged(lease, AndroidWindowBounds(bounds.left, bounds.top, bounds.width(), bounds.height()))
    }

    override fun onStop() {
        browserKeyboardShortcuts.clearPressed()
        runtime?.flushSessionState()
        windowLease?.let { lease ->
            runtime?.windowVisibilityChanged(lease, false)
            runtime?.cancelActivityPrompts(lease)
        }
        super.onStop()
    }

    override fun onDestroy() {
        windowTaskMode?.close()
        windowTaskMode = null
        browserKeyboardShortcuts.close()
        runtimeRequest?.cancel()
        runtimeRequest = null
        runtimeDelivery.close()
        lifecycle.removeObserver(runtimeLifecycleObserver)
        if (::platformRequests.isInitialized) platformRequests.unbind()
        extensionDownloadSaveAsBinding?.close()
        extensionDownloadSaveAsBinding = null
        windowLease?.let { lease ->
            runtime?.unbindWebAuthn(lease)
            runtime?.detachWindowActivity(lease, finishing = isFinishing && !relaunchInProgress)
        }
        windowLease = null
        windowRuntime = null
        runtime = null
        super.onDestroy()
    }

    /** A full process restart, after beforeunload approval and durable normal-session storage. */
    internal fun relaunchBrowser(safeMode: Boolean = false) {
        val activeRuntime = runtime ?: return
        if (relaunchInProgress || cannotAcceptRuntime()) return
        relaunchInProgress = true
        try {
            activeRuntime.prepareRelaunch { prepared ->
                runOnUiThread {
                    if (prepared.isFailure) {
                        reportRelaunchFailure()
                    } else if (prepared.getOrNull() != true || cannotAcceptRuntime()) {
                        relaunchInProgress = false
                    } else {
                        Thread({
                            val persisted = runCatching {
                                flushAndroidSettingsForRelaunch(applicationContext)
                            }.getOrDefault(false)
                            runOnUiThread {
                                if (!persisted) reportRelaunchFailure()
                                else if (!cannotAcceptRuntime()) beginProcessRelaunch(safeMode)
                                else relaunchInProgress = false
                            }
                        }, "NavisRelaunchPreferences").apply { isDaemon = true }.start()
                    }
                }
            }
        } catch (_: Throwable) {
            reportRelaunchFailure()
        }
    }

    /** Product tab closure uses this only after the last Session has really closed. */
    internal fun closeBrowserWindow() {
        if (!relaunchInProgress) {
            windowLease?.let { runtime?.closeProductWindow(it.windowId) }
            finishAndRemoveTask()
        }
    }

    internal fun openProfileWindow(profileId: String) {
        runCatching { ProfileWindows.launch(this, profileId) }
            .onFailure { Toast.makeText(this, R.string.profile_operation_failed, Toast.LENGTH_LONG).show() }
    }

    private fun beginProcessRelaunch(safeMode: Boolean) {
        val handoff = RelaunchHandoff(
            onExit = {
                if (relaunchInProgress) {
                    // This code executes in the old browser process, and only terminates itself.
                    Process.killProcess(Process.myPid())
                    exitProcess(0)
                }
            },
            onAbort = ::reportRelaunchFailure,
        )
        relaunchHandoff = handoff
        try {
            startActivity(RelaunchActivity.intent(this, handoff, safeMode))
        } catch (_: Throwable) {
            handoff.cancel()
            reportRelaunchFailure()
        }
    }

    private fun reportRelaunchFailure() {
        relaunchInProgress = false
        relaunchHandoff?.cancel()
        relaunchHandoff = null
        Toast.makeText(this, R.string.relaunch_failed, Toast.LENGTH_LONG).show()
    }

    private fun onRuntimeReady(result: Result<AndroidBrowserRuntime>) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread { onRuntimeReady(result) }
            return
        }
        runtimeRequest = null
        if (cannotAcceptRuntime()) {
            return
        }
        runtimeDelivery.offer(result)
    }

    private fun acceptRuntimeResult(result: Result<AndroidBrowserRuntime>): Boolean {
        if (cannotAcceptRuntime() || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            return false
        }
        val scoped = result.mapCatching { shared ->
            val token = intent?.getStringExtra(AndroidWindowLaunch.TOKEN)
            val id = shared.resolveActivityWindow(taskId, restoredWindowId, restoredWindowKey, token,
                additional = this is AdditionalWindowActivity)
            val lease = shared.bindWindowActivity(id, this)
            runtime = shared
            windowLease = lease
            intent?.removeExtra(AndroidWindowLaunch.TOKEN)
            val facade = shared.forWindow(id)
            windowRuntime = facade
            bindWindowTaskMode(lease)
            platformRequests.bind(facade, lease) { shared.windowRegistry.isCurrent(lease) }
            extensionDownloadSaveAsBinding?.bind(lease)
            shared.bindWebAuthn(this, lease)
            shared.windowFocusChanged(lease, hasWindowFocus())
            window.decorView.post(::reportWindowBounds)
            facade
        }
        if (scoped.isFailure) {
            result.getOrNull()?.reportWindowActivityBinding(scoped.exceptionOrNull())
            runtimeResult = scoped
            return true
        }
        val acceptedRuntime = checkNotNull(runtime)
        val acceptedLease = checkNotNull(windowLease)
        acceptedRuntime.reportWindowActivityBinding()
        acceptedRuntime.awaitWindowPresentation(acceptedLease).whenComplete { _, error ->
            runOnUiThread {
                if (windowLease != acceptedLease || cannotAcceptRuntime()) return@runOnUiThread
                if (error != null) {
                    runtimeResult = Result.failure(error)
                } else {
                    runtimeResult = scoped
                    handleIntent(intent)
                }
            }
        }
        return true
    }

    private fun cannotAcceptRuntime(): Boolean =
        isFinishing ||
            isDestroyed ||
            isChangingConfigurations ||
            lifecycle.currentState == Lifecycle.State.DESTROYED

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == DownloadNotificationIntents.ACTION && viewIntentGate.shouldHandle()) {
            val activeRuntime = windowRuntime ?: return
            val lease = windowLease ?: return
            if (runtimeResult?.isSuccess != true) return
            viewIntentGate.markHandled()
            setIntent(Intent(intent).apply { action = Intent.ACTION_MAIN; setDataAndType(null, null) })
            val command = DownloadNotificationIntents.read(intent)
            if (command == null) {
                Toast.makeText(this, R.string.download_notification_unavailable, Toast.LENGTH_LONG).show()
                return
            }
            org.navis.browser.downloads.DownloadNotificationDelivery(
                profileId = checkNotNull((application as NavisApplication).profileScope).currentId,
                downloads = activeRuntime.downloads,
                postToUi = { action -> runOnUiThread { action() } },
                isCurrent = { windowRuntime === activeRuntime && windowLease == lease && !cannotAcceptRuntime() &&
                    runtime?.windowRegistry?.isCurrent(lease) == true },
                showDownloads = { privateMode ->
                    val mode = if (privateMode) SessionMode.PRIVATE else SessionMode.NORMAL
                    val active = activeRuntime.state.activeSession
                    if (active != null && active.mode == mode) activeRuntime.load(active.id, "navis://downloads")
                    else activeRuntime.openSession(mode, "navis://downloads")
                },
                unavailable = {
                    Toast.makeText(this, R.string.download_notification_unavailable, Toast.LENGTH_LONG).show()
                },
            ).deliver(command)
            return
        }
        if (intent?.action != Intent.ACTION_VIEW || !viewIntentGate.shouldHandle()) {
            return
        }
        val uri = intent.dataString ?: return
        val activeRuntime = windowRuntime ?: return
        val active = activeRuntime.state.activeSession
        if (active?.mode == SessionMode.PRIVATE) {
            activeRuntime.openSession(SessionMode.NORMAL, uri)
        } else {
            val sessionId = active?.id ?: return
            activeRuntime.load(sessionId, uri)
        }
        viewIntentGate.markHandled()
        if (this.intent === intent) {
            setIntent(
                Intent(intent).apply {
                    action = Intent.ACTION_MAIN
                    setDataAndType(null, null)
                },
            )
        }
    }

    private companion object {
        const val STATE_VIEW_INTENT_CONSUMED = "navis.view_intent_consumed"
    }
}

internal class ViewIntentGate(restoredConsumed: Boolean) {
    var consumed: Boolean = restoredConsumed
        private set

    fun shouldHandle(): Boolean = !consumed

    fun onNewDelivery() {
        consumed = false
    }

    fun markHandled() {
        consumed = true
    }
}
