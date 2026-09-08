/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import org.navis.browser.persistence.ProfileProcessSlots
import org.navis.browser.persistence.UserProfileStore

/** The exported entry is only a router. Every Gecko parent owns one immutable profile. */
class LauncherActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val store = UserProfileStore.fileBacked(filesDir)
            store.ensureProfile()
            // This Activity is exported. Never forward internal notification commands supplied
            // by another app; only the existing public web-link entry may cross this boundary.
            ProfileWindows.launch(this, store.snapshot().defaultId, intent.takeIf { it.action == Intent.ACTION_VIEW })
        } catch (error: Exception) {
            android.util.Log.e("NavisProfiles", "Could not launch the default profile", error)
            Toast.makeText(this, R.string.profile_operation_failed, Toast.LENGTH_LONG).show()
        }
        finish()
    }
}

internal object ProfileWindows {
    const val EXPECTED_PROFILE = "org.navis.browser.profile.IDENTITY"
    const val RESTART_SLOT = "org.navis.browser.profile.RESTART_SLOT"

    fun browserSlot(context: Context): Int? = slot(navisProcessName(context), context.packageName, "profile")
    fun helperSlot(context: Context): Int? = slot(navisProcessName(context), context.packageName, "relaunch")

    private fun slot(name: String?, packageName: String, prefix: String): Int? =
        name?.takeIf { it.startsWith("$packageName:$prefix") }
            ?.removePrefix("$packageName:$prefix")?.takeIf { it.length == 1 }
            ?.toIntOrNull()?.takeIf { it in 0..7 }

    fun intent(context: Context, role: String, slot: Int = checkNotNull(browserSlot(context))): Intent {
        require(slot in 0..7)
        val component = when (role) {
            "Browser" -> if (slot == 0) "MainActivity" else "ProfileActivities\$Browser$slot"
            "Additional" -> if (slot == 0) "AdditionalWindowActivity" else "ProfileActivities\$Additional$slot"
            "Attention" -> if (slot == 0) "WindowAttentionActivity" else "ProfileActivities\$Attention$slot"
            "Relaunch" -> if (slot == 0) "RelaunchActivity" else "ProfileActivities\$Relaunch$slot"
            else -> error("Unknown profile component")
        }
        return Intent().setClassName(context.packageName, "org.navis.browser.$component")
    }

    fun launch(context: Context, profileId: String, source: Intent? = null) {
        val slot = ProfileProcessSlots.reserve(context.filesDir, profileId)
        context.startActivity(intent(context, "Browser", slot).apply {
            action = Intent.ACTION_MAIN
            if (source?.action == Intent.ACTION_VIEW && source.data?.scheme in setOf("http", "https")) {
                action = Intent.ACTION_VIEW
                data = source.data
            }
            DownloadNotificationIntents.forward(source, this, profileId)
            putExtra(EXPECTED_PROFILE, profileId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
}

/** Manifest identities give each profile its own task and non-isolated Gecko services. */
class ProfileActivities {
    class Browser1 : MainActivity()
    class Additional1 : AdditionalWindowActivity()
    class Attention1 : WindowAttentionActivity()
    class Relaunch1 : RelaunchActivity()
    class Browser2 : MainActivity()
    class Additional2 : AdditionalWindowActivity()
    class Attention2 : WindowAttentionActivity()
    class Relaunch2 : RelaunchActivity()
    class Browser3 : MainActivity()
    class Additional3 : AdditionalWindowActivity()
    class Attention3 : WindowAttentionActivity()
    class Relaunch3 : RelaunchActivity()
    class Browser4 : MainActivity()
    class Additional4 : AdditionalWindowActivity()
    class Attention4 : WindowAttentionActivity()
    class Relaunch4 : RelaunchActivity()
    class Browser5 : MainActivity()
    class Additional5 : AdditionalWindowActivity()
    class Attention5 : WindowAttentionActivity()
    class Relaunch5 : RelaunchActivity()
    class Browser6 : MainActivity()
    class Additional6 : AdditionalWindowActivity()
    class Attention6 : WindowAttentionActivity()
    class Relaunch6 : RelaunchActivity()
    class Browser7 : MainActivity()
    class Additional7 : AdditionalWindowActivity()
    class Attention7 : WindowAttentionActivity()
    class Relaunch7 : RelaunchActivity()
}
