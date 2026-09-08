/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser

import android.app.Application
import android.app.ActivityManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Handler
import android.os.Build
import android.os.Looper
import android.os.LocaleList
import android.os.Process
import android.widget.Toast
import java.util.Locale
import org.navis.browser.api.BrowserDelegate
import org.navis.browser.api.DownloadFailure
import org.navis.browser.api.DownloadRequest
import org.navis.browser.api.DownloadResult
import org.navis.browser.api.SessionId
import org.navis.browser.engine.AndroidBrowserRuntime
import org.navis.browser.settings.createAndroidSettingsHost
import org.navis.browser.settings.resolvedLanguageTag

class NavisApplication : Application(), BrowserDelegate {
    private var runtimeResult: Result<AndroidBrowserRuntime>? = null
    private var initializing = false
    private val runtimeRequests = mutableListOf<RuntimeRequest>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var localizedResources: Resources? = null
    internal var resolvedDisplayLanguageTag: String = "en-US"
        private set

    internal var profileScope: org.navis.browser.persistence.UserProfileScope.ResolvedProfile? = null
        private set
    internal var safeMode = false
    private val browserActivities = mutableSetOf<android.app.Activity>()
    internal var stopping = false
        private set
    private val idleExit = Runnable { checkIdleExit() }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        val slot = ProfileWindows.browserSlot(this)
        val helperSlot = ProfileWindows.helperSlot(this)
        if (slot == null && helperSlot == null) return
        profileScope = if (slot != null) {
            org.navis.browser.persistence.ProfileProcessSlots.bind(filesDir, slot)
        } else {
            org.navis.browser.persistence.UserProfileScope.resolve(filesDir,
                profileId = org.navis.browser.persistence.ProfileProcessSlots.profileId(filesDir, checkNotNull(helperSlot)))
        }
        profileScope?.directory?.mkdirs()
    }

    /** Navis-owned databases stay in the active profile; everything else delegates. */
    override fun getDatabasePath(name: String): java.io.File {
        val resolved = profileScope
        val directory = resolved?.directory
        if (resolved == null || directory == null ||
            name !in org.navis.browser.persistence.UserProfileScope.PROFILE_DATABASES
        ) {
            return super.getDatabasePath(name)
        }
        directory.resolve("databases").mkdirs()
        return java.io.File(directory.resolve("databases"), name)
    }

    /** Navis-owned preferences are suffixed per profile; device-level prefs stay shared. */
    override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
        val resolved = profileScope
        return super.getSharedPreferences(
            if (resolved == null) name else
                org.navis.browser.persistence.UserProfileScope.scopedPreferenceName(
                    name, resolved.currentId, resolved.legacyId,
                ),
            mode,
        )
    }

    override fun onCreate() {
        super.onCreate()
        // Gecko's isolated child services share the manifest Application class but cannot read the
        // product profile. Their locale comes from Gecko IPC, not Android SharedPreferences.
        val slot = ProfileWindows.browserSlot(this)
        if (slot == null && ProfileWindows.helperSlot(this) == null) return
        val preferences = getSharedPreferences("navis.settings.v1", Context.MODE_PRIVATE)
        if (slot != null && !preferences.contains("appearance_accent")) {
            val registry = org.navis.browser.persistence.UserProfileStore.fileBacked(filesDir).snapshot()
            registry.profiles.firstOrNull { it.id == profileScope?.currentId }?.let { user ->
                check(preferences.edit().putString("appearance_accent", user.accent).commit())
            }
        }
        val settings = createAndroidSettingsHost(this)
        try {
            resolvedDisplayLanguageTag = settings.snapshot.selectedDisplayLanguage.resolvedLanguageTag()
        } finally {
            settings.close()
        }
        Locale.setDefault(Locale.forLanguageTag(resolvedDisplayLanguageTag))
        localizedResources = localizedContext(baseContext).resources
        if (slot != null) {
            registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: android.app.Activity, state: android.os.Bundle?) {
                    if (activity is MainActivity) browserActivities += activity
                }
                override fun onActivityDestroyed(activity: android.app.Activity) {
                    browserActivities -= activity
                    if (!activity.isChangingConfigurations) scheduleIdleExit()
                }
                override fun onActivityStarted(activity: android.app.Activity) = Unit
                override fun onActivityResumed(activity: android.app.Activity) = Unit
                override fun onActivityPaused(activity: android.app.Activity) = Unit
                override fun onActivityStopped(activity: android.app.Activity) = Unit
                override fun onActivitySaveInstanceState(activity: android.app.Activity, state: android.os.Bundle) = Unit
            })
            scheduleIdleExit()
        }
    }

    override fun getResources(): Resources = localizedResources ?: super.getResources()

    /** One frozen language for this entire process; selecting a new preference does not half-switch. */
    internal fun localizedContext(base: Context): Context {
        val locale = Locale.forLanguageTag(resolvedDisplayLanguageTag)
        val override = Configuration().apply {
            setLocales(LocaleList(locale))
            setLayoutDirection(locale)
        }
        return base.createConfigurationContext(override)
    }

    internal fun runtime(callback: (Result<AndroidBrowserRuntime>) -> Unit, safeMode: Boolean = false): RuntimeRequest {
        check(ProfileWindows.browserSlot(this) != null) { "Only a profile browser process may own the Runtime" }
        val request = RuntimeRequest(callback) { pending -> runtimeRequests.remove(pending) }
        if (stopping) {
            request.deliver(Result.failure(IllegalStateException("The profile is finishing shutdown")))
            return request
        }
        val existing = runtimeResult
        if (existing != null) {
            request.deliver(existing)
            return request
        }
        runtimeRequests += request
        if (initializing) {
            return request
        }
        initializing = true
        this.safeMode = safeMode
        try {
            AndroidBrowserRuntime.createAsync(this, this) { result ->
                completeRuntimeInitialization(result)
            }
        } catch (error: Throwable) {
            completeRuntimeInitialization(Result.failure(error))
        }
        return request
    }

    private fun completeRuntimeInitialization(result: Result<AndroidBrowserRuntime>) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { completeRuntimeInitialization(result) }
            return
        }
        runtimeResult = result
        initializing = false
        val requests = runtimeRequests.toList()
        runtimeRequests.clear()
        requests.forEach { it.deliver(result) }
    }

    private fun scheduleIdleExit() {
        mainHandler.removeCallbacks(idleExit)
        mainHandler.postDelayed(idleExit, 2_000)
    }

    private fun checkIdleExit() {
        if (stopping || browserActivities.isNotEmpty()) return
        if (initializing && runtimeResult == null) { scheduleIdleExit(); return }
        val runtime = runtimeResult?.getOrNull()
        if (runtime == null) {
            Process.killProcess(Process.myPid())
            return
        }
        val completion = runtime.stopIdleProfile()
        if (completion == null) { scheduleIdleExit(); return }
        stopping = true
        completion.whenComplete { _, error ->
            if (error != null) {
                android.util.Log.e("NavisProfiles", "Could not finish profile shutdown", error)
            } else Thread({
                // Drain pending product preference writes after the engine and databases stopped.
                val persisted = org.navis.browser.persistence.UserProfileScope.PROFILE_PREFERENCES.all {
                    getSharedPreferences(it, Context.MODE_PRIVATE).edit().commit()
                }
                if (persisted) Process.killProcess(Process.myPid())
                else android.util.Log.e("NavisProfiles", "Profile preferences did not finish writing")
            }, "NavisProfileShutdown").apply { isDaemon = true }.start()
        }
    }

    override fun onDownloadRequested(request: DownloadRequest) {
        Toast.makeText(
            this,
            getString(R.string.download_started, request.fileName),
            Toast.LENGTH_SHORT,
        ).show()
    }

    override fun onDownloadFinished(result: DownloadResult) {
        if (result.failure == DownloadFailure.CANCELLED) {
            return
        }
        val message = if (result.successful) {
            getString(R.string.download_completed_toast, result.request.fileName)
        } else {
            getString(R.string.download_failed_toast, result.request.fileName)
        }
        Toast.makeText(
            this,
            message,
            if (result.successful) Toast.LENGTH_SHORT else Toast.LENGTH_LONG,
        ).show()
    }

    override fun onSessionCrashed(sessionId: SessionId) {
        Toast.makeText(this, R.string.page_crashed, Toast.LENGTH_LONG).show()
    }
}

internal fun navisProcessName(context: Context): String? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) Application.getProcessName()
    else context.getSystemService(ActivityManager::class.java)?.runningAppProcesses
        ?.firstOrNull { it.pid == Process.myPid() }?.processName

internal class RuntimeRequest(
    callback: (Result<AndroidBrowserRuntime>) -> Unit,
    private var removePending: ((RuntimeRequest) -> Unit)?,
) {
    private var callback: ((Result<AndroidBrowserRuntime>) -> Unit)? = callback

    val pending: Boolean
        get() = callback != null

    fun cancel() {
        callback = null
        removePending?.invoke(this)
        removePending = null
    }

    fun deliver(result: Result<AndroidBrowserRuntime>) {
        val activeCallback = callback ?: return
        callback = null
        removePending = null
        activeCallback(result)
    }
}
