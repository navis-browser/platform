/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import org.navis.browser.downloads.DownloadNotificationCommand
import org.navis.browser.persistence.UserProfileStore

/** Stable, non-exported Activity endpoint; slot mappings may change while notifications remain. */
class DownloadNotificationActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val command = checkNotNull(DownloadNotificationIntents.read(intent))
            check(UserProfileStore.fileBacked(filesDir).snapshot().profiles.any { it.id == command.profileId })
            ProfileWindows.launch(this, command.profileId, intent)
        } catch (_: Exception) {
            Toast.makeText(this, R.string.download_notification_profile_unavailable, Toast.LENGTH_LONG).show()
        }
        finish()
    }
}

internal object DownloadNotificationIntents {
    const val ACTION = "org.navis.browser.action.DOWNLOAD_NOTIFICATION"

    fun pendingIntent(context: Context, command: DownloadNotificationCommand): PendingIntent =
        PendingIntent.getActivity(context, 0,
            Intent(context, DownloadNotificationActivity::class.java).apply {
                action = ACTION
                data = Uri.parse(command.identity)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun read(intent: Intent?): DownloadNotificationCommand? =
        if (intent?.action == ACTION) DownloadNotificationCommand.parse(intent.dataString) else null

    /** Only trusted, non-exported Activity paths call this; Launcher discards non-web commands. */
    fun forward(source: Intent?, target: Intent, profileId: String) {
        val command = read(source) ?: return
        require(command.profileId == profileId) { "Download notification belongs to another profile" }
        target.action = ACTION
        target.data = Uri.parse(command.identity)
    }
}
