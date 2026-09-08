/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.downloads

import java.net.URI

/** A record identity, never a saved file URI or authority to recreate a private Session. */
internal data class DownloadNotificationCommand(
    val profileId: String,
    val downloadId: Long,
    val privateMode: Boolean,
    val openFile: Boolean,
) {
    init {
        require(PROFILE_ID.matches(profileId))
        require(downloadId in 1..Int.MAX_VALUE.toLong())
    }

    val identity: String get() =
        "navis-download://$profileId/$downloadId/${if (privateMode) "private" else "normal"}/${if (openFile) "open" else "list"}"

    companion object {
        private val PROFILE_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")

        fun parse(value: String?): DownloadNotificationCommand? = runCatching {
            require(value != null && value.length <= 160)
            val uri = URI(value)
            require(uri.scheme == "navis-download" && uri.rawAuthority == uri.host &&
                uri.rawQuery == null && uri.rawFragment == null)
            val parts = uri.rawPath.split('/')
            require(parts.size == 4 && parts[0].isEmpty() &&
                parts[2] in setOf("private", "normal") && parts[3] in setOf("open", "list"))
            DownloadNotificationCommand(uri.host, parts[1].toLong(), parts[2] == "private", parts[3] == "open")
                .also { require(it.identity == value) }
        }.getOrNull()
    }
}

/** Uses the existing download repository at click time, including after an async open failure. */
internal class DownloadNotificationDelivery(
    private val profileId: String,
    private val downloads: DownloadManager,
    private val postToUi: (() -> Unit) -> Unit,
    private val isCurrent: () -> Boolean,
    private val showDownloads: (privateMode: Boolean) -> Unit,
    private val unavailable: () -> Unit,
) {
    fun deliver(command: DownloadNotificationCommand) {
        if (!isCurrent()) return
        if (command.profileId != profileId) { unavailable(); return }
        val entry = runCatching { downloads.find(command.downloadId, command.privateMode) }.getOrNull()
        if (entry == null) { fallback(command); return }
        if (!command.openFile) { showDownloads(entry.privateMode); return }
        try {
            downloads.open(command.downloadId, command.privateMode).whenComplete { _, error ->
                if (error != null) postToUi { if (isCurrent()) fallback(command) }
            }
        } catch (_: Exception) { fallback(command) }
    }

    private fun fallback(command: DownloadNotificationCommand) {
        unavailable()
        // Private records disappear when the final private Session closes. Never resurrect that
        // Session just because a stale notification or failed async open still carries its bit.
        val privateMode = runCatching { downloads.find(command.downloadId, command.privateMode)?.privateMode }
            .getOrNull() == true
        showDownloads(privateMode)
    }
}
