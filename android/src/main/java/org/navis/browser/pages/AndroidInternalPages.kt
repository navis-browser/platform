/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.pages

import java.net.URI
import org.navis.browser.R

/** These native pages can be selected only by a product navigation command. */
internal data class AndroidInternalPage(val route: String, val uri: String, val title: Int)

internal object AndroidInternalPages {
    val pages = listOf(
        AndroidInternalPage("newtab", "navis://newtab/", R.string.new_tab),
        AndroidInternalPage("settings", "navis://settings/", R.string.settings),
        AndroidInternalPage("settings/search", "navis://settings/search", R.string.settings_search_engine),
        AndroidInternalPage("settings/privacy", "navis://settings/privacy", R.string.settings_section_privacy),
        AndroidInternalPage("settings/appearance", "navis://settings/appearance", R.string.settings_appearance),
        AndroidInternalPage("settings/downloads", "navis://settings/downloads", R.string.download_notification_channel),
        AndroidInternalPage("help", "navis://settings/help", R.string.about_navis),
        AndroidInternalPage("history", "navis://history/", R.string.history),
        AndroidInternalPage("bookmarks", "navis://bookmarks/", R.string.bookmarks),
        AndroidInternalPage("passwords", "navis://passwords/", R.string.passwords),
        AndroidInternalPage("downloads", "navis://downloads/", R.string.download_notification_channel),
        AndroidInternalPage("extensions", "navis://extensions/", R.string.extensions),
        AndroidInternalPage("support", "navis://support/", R.string.about_support),
        AndroidInternalPage("processes", "navis://processes/", R.string.processes_title),
        AndroidInternalPage("profiles", "navis://profiles/", R.string.user_profile_manage),
        AndroidInternalPage("credits", "navis://credits/", R.string.credits_title),
        AndroidInternalPage("urls", "navis://urls/", R.string.about_internal_pages),
    )

    fun resolve(value: String): AndroidInternalPage? = runCatching {
        if (value.length > 16384 || value.any(Char::isISOControl)) return null
        val uri = URI(value)
        if (!uri.scheme.equals("navis", true) || uri.rawUserInfo != null || uri.port != -1 ||
            uri.rawQuery != null || uri.rawFragment != null || uri.rawAuthority != uri.host) return null
        pages.firstOrNull {
            val allowed = URI(it.uri)
            uri.host == allowed.host && uri.rawPath.orEmpty().trimEnd('/') == allowed.rawPath.trimEnd('/')
        }
    }.getOrNull()
}
