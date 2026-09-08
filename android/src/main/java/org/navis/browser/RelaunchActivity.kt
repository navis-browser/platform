/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser

import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.Process
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.addCallback
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/**
 * Runs in a separate, non-exported Android process. It can outlive the browser without retaining
 * GeckoThread. A Binder death acknowledgement ensures the next MainActivity has a fresh Runtime.
 */
open class RelaunchActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var owner: IBinder? = null
    private var launching = false
    private var failure = false
    private val ownerDied = IBinder.DeathRecipient { handler.post(::launchFreshBrowser) }
    private val timeout = Runnable { failRelaunch() }

    override fun attachBaseContext(newBase: Context) {
        val application = newBase.applicationContext as? NavisApplication
        super.attachBaseContext(application?.localizedContext(newBase) ?: newBase)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ProfileWindows.helperSlot(this) == null ||
            ProfileWindows.helperSlot(this) != intent.getIntExtra(ProfileWindows.RESTART_SLOT, -1)) {
            finish()
            return
        }
        onBackPressedDispatcher.addCallback(this) { /* Finish the short process handoff first. */ }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val spacing = (24 * resources.displayMetrics.density).toInt()
            setPadding(spacing, spacing, spacing, spacing)
            val brandSize = (80 * resources.displayMetrics.density).toInt()
            addView(ImageView(this@RelaunchActivity).apply {
                setImageResource(R.drawable.ic_navis_brand)
                importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(brandSize, brandSize).apply { bottomMargin = spacing })
            addView(ProgressBar(this@RelaunchActivity))
            addView(TextView(this@RelaunchActivity).apply {
                setText(R.string.relaunch_in_progress)
                gravity = Gravity.CENTER
                setPadding(0, spacing, 0, 0)
            })
        })
        val binder = intent.extras?.getBinder(EXTRA_OWNER)
        if (binder == null) {
            failRelaunch()
            return
        }
        owner = binder
        try {
            binder.linkToDeath(ownerDied, 0)
            handler.postDelayed(timeout, HANDOFF_TIMEOUT_MS)
            if (!send(binder, RelaunchHandoff.REQUEST_EXIT)) failRelaunch()
        } catch (_: android.os.DeadObjectException) {
            launchFreshBrowser()
        } catch (_: Throwable) {
            failRelaunch()
        }
    }

    private fun launchFreshBrowser() {
        if (launching || failure || isFinishing) return
        launching = true
        handler.removeCallbacks(timeout)
        owner = null
        try {
            val profileId = checkNotNull(intent.getStringExtra(ProfileWindows.EXPECTED_PROFILE))
            val slot = org.navis.browser.persistence.ProfileProcessSlots.reserve(filesDir, profileId)
            startActivity(ProfileWindows.intent(this, "Browser", slot).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                putExtra(EXTRA_SAFE_MODE, intent.getBooleanExtra(EXTRA_SAFE_MODE, false))
                putExtra(ProfileWindows.EXPECTED_PROFILE, profileId)
                intent.getStringExtra(EXTRA_URL)?.let { url ->
                    val uri = android.net.Uri.parse(url)
                    if (uri.scheme in setOf("http", "https")) { action = Intent.ACTION_VIEW; data = uri }
                }
                DownloadNotificationIntents.forward(intent, this, profileId)
            })
            finishAndRemoveTask()
        } catch (_: Throwable) {
            launching = false
            failRelaunch()
        }
    }

    private fun failRelaunch() {
        if (failure || launching) return
        failure = true
        handler.removeCallbacks(timeout)
        owner?.let { binder ->
            runCatching { send(binder, RelaunchHandoff.CANCEL_EXIT) }
        }
        Toast.makeText(this, R.string.relaunch_failed, Toast.LENGTH_LONG).show()
        finishAndRemoveTask()
    }

    override fun onDestroy() {
        handler.removeCallbacks(timeout)
        owner?.let { binder -> runCatching { binder.unlinkToDeath(ownerDied, 0) } }
        super.onDestroy()
        if (!isChangingConfigurations && ProfileWindows.helperSlot(this) != null) {
            Process.killProcess(Process.myPid())
            exitProcess(0)
        }
    }

    companion object {
        private const val EXTRA_OWNER = "org.navis.browser.relaunch.OWNER"
        internal const val EXTRA_SAFE_MODE = "org.navis.browser.relaunch.SAFE_MODE"
        private const val EXTRA_URL = "org.navis.browser.relaunch.URL"
        private const val HANDOFF_TIMEOUT_MS = 10_000L

        internal fun intent(context: Context, owner: IBinder, safeMode: Boolean = false, source: Intent? = null): Intent {
            val profileId = checkNotNull((context.applicationContext as NavisApplication).profileScope).currentId
            val slot = org.navis.browser.persistence.ProfileProcessSlots.reserve(context.filesDir, profileId)
            check(slot == ProfileWindows.browserSlot(context))
            return ProfileWindows.intent(context, "Relaunch").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtras(Bundle().apply { putBinder(EXTRA_OWNER, owner) })
                putExtra(EXTRA_SAFE_MODE, safeMode)
                putExtra(ProfileWindows.RESTART_SLOT, checkNotNull(ProfileWindows.browserSlot(context)))
                putExtra(ProfileWindows.EXPECTED_PROFILE, profileId)
                if (source?.action == Intent.ACTION_VIEW && source.data?.scheme in setOf("http", "https")) {
                    putExtra(EXTRA_URL, source.dataString)
                }
                DownloadNotificationIntents.forward(source, this, profileId)
            }
        }

        private fun send(binder: IBinder, command: Int): Boolean {
            val data = Parcel.obtain()
            return try {
                data.writeInterfaceToken(RelaunchHandoff.DESCRIPTOR)
                binder.transact(command, data, null, IBinder.FLAG_ONEWAY)
            } finally {
                data.recycle()
            }
        }
    }
}

/** Same-UID capability that lets the old process stop itself after a prepared restart. */
internal class RelaunchHandoff(
    private val onExit: () -> Unit,
    private val onAbort: () -> Unit,
) : Binder() {
    private val handler = Handler(Looper.getMainLooper())
    private val active = AtomicBoolean(true)

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code != REQUEST_EXIT && code != CANCEL_EXIT) return super.onTransact(code, data, reply, flags)
        if (Binder.getCallingUid() != Process.myUid() || Binder.getCallingPid() == Process.myPid()) return false
        data.enforceInterface(DESCRIPTOR)
        if (code == CANCEL_EXIT) {
            if (active.compareAndSet(true, false)) handler.post(onAbort)
        } else {
            handler.post { if (active.compareAndSet(true, false)) onExit() }
        }
        return true
    }

    fun cancel() {
        active.set(false)
    }

    companion object {
        const val DESCRIPTOR = "org.navis.browser.RelaunchHandoff"
        const val REQUEST_EXIT = IBinder.FIRST_CALL_TRANSACTION
        const val CANCEL_EXIT = IBinder.FIRST_CALL_TRANSACTION + 1
    }
}
