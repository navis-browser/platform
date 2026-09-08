/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.navis.browser.R
import org.navis.browser.engine.AndroidWindowLease

/** Process-wide OS permission gate; a foreground Activity owns the permission launcher. */
internal object AndroidNotificationPermissions {
    private var applicationContext: Context? = null
    private val owners = mutableMapOf<Long, AndroidNotificationPermissionCoordinator>()
    private val main = Handler(Looper.getMainLooper())

    fun areAllowed(context: Context): Boolean =
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** Cached website grants must also pass this live OS check, without showing an unsolicited UI. */
    fun currentPermissionGranted(): Boolean = applicationContext?.let(::areAllowed) == true

    /** Call only after the user has chosen Allow in a site's permission dialog. */
    fun requestForSite(windowId: Long?, callback: (Boolean) -> Unit) = request(windowId, true, callback)

    /** A download continues regardless of this result; the callback only updates its notification. */
    fun requestForDownload(windowId: Long?, callback: (Boolean) -> Unit) = request(windowId, false, callback)

    private fun request(windowId: Long?, userAllowedSite: Boolean, callback: (Boolean) -> Unit) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { request(windowId, userAllowedSite, callback) }
            return
        }
        if (currentPermissionGranted()) callback(true)
        else owners[windowId]?.request(userAllowedSite, callback) ?: callback(false)
    }

    internal fun bind(coordinator: AndroidNotificationPermissionCoordinator, context: Context) {
        applicationContext = context.applicationContext
        val lease = checkNotNull(coordinator.lease)
        val previous = owners[lease.windowId]
        check(previous === coordinator || previous?.lease?.generation?.let { it >= lease.generation } != true) { "Stale notification Activity lease" }
        owners[lease.windowId] = coordinator
        previous?.takeUnless { it === coordinator }?.cancel()
    }

    internal fun isCurrent(coordinator: AndroidNotificationPermissionCoordinator): Boolean =
        coordinator.lease?.let { owners[it.windowId] === coordinator } == true

    internal fun unbind(coordinator: AndroidNotificationPermissionCoordinator) {
        coordinator.lease?.let { if (owners[it.windowId] === coordinator) owners.remove(it.windowId) }
    }
}

/** Uses the same Activity Result lifecycle as camera/location permission requests. */
internal class AndroidNotificationPermissionCoordinator(
    private val activity: AppCompatActivity,
    private val canLaunch: () -> Boolean,
    private val onFinished: () -> Unit,
) {
    var lease: AndroidWindowLease? = null
        private set
    private val preferences = activity.getSharedPreferences("navis.notification-permission", Context.MODE_PRIVATE)
    private val callbacks = mutableListOf<(Boolean) -> Unit>()
    private var closed = false
    var inFlight = false
        private set

    private val launcher = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        finish(granted && AndroidNotificationPermissions.isCurrent(this) && AndroidNotificationPermissions.areAllowed(activity))
    }
    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_RESUME) launchIfReady()
        else if (event == Lifecycle.Event.ON_DESTROY) {
            closed = true
            cancel()
            unbind()
        }
    }

    init {
        activity.lifecycle.addObserver(lifecycleObserver)
    }

    /** Launchers register in onCreate; the real window lease arrives with Runtime readiness. */
    fun bind(lease: AndroidWindowLease) {
        check(!closed)
        if (this.lease == lease && AndroidNotificationPermissions.isCurrent(this)) return
        unbind()
        this.lease = lease
        AndroidNotificationPermissions.bind(this, activity)
    }

    fun unbind() {
        AndroidNotificationPermissions.unbind(this)
        lease = null
        cancel()
    }

    fun request(userAllowedSite: Boolean, callback: (Boolean) -> Unit) {
        if (closed || !AndroidNotificationPermissions.isCurrent(this)) { callback(false); return }
        if (AndroidNotificationPermissions.areAllowed(activity)) { callback(true); return }
        if (inFlight || callbacks.isNotEmpty()) {
            if (callbacks.size < 128) callbacks += callback else callback(false)
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            preferences.getBoolean(KEY_REQUESTED, false)
        ) {
            // A refusal is not an invitation to prompt again on every page or every download.
            if (userAllowedSite) {
                Toast.makeText(activity, R.string.notifications_enable_android_settings, Toast.LENGTH_LONG).show()
            }
            callback(false)
            return
        }
        if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            callback(false)
            return
        }
        callbacks += callback
        launchIfReady()
    }

    fun launchIfReady() {
        if (closed || !AndroidNotificationPermissions.isCurrent(this) || inFlight || callbacks.isEmpty() || !canLaunch() ||
            !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        ) return
        if (AndroidNotificationPermissions.areAllowed(activity)) { finish(true); return }
        inFlight = true
        preferences.edit().putBoolean(KEY_REQUESTED, true).apply()
        runCatching { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }.onFailure {
            preferences.edit().remove(KEY_REQUESTED).apply()
            finish(false)
        }
    }

    fun cancel() = finish(false)

    private fun finish(granted: Boolean) {
        val pending = callbacks.toList()
        callbacks.clear()
        inFlight = false
        pending.forEach { it(granted) }
        onFinished()
    }

    private companion object {
        const val KEY_REQUESTED = "requested-v1"
    }
}
