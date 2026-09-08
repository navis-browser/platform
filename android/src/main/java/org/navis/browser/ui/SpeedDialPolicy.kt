/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.ui

import java.util.Locale
import org.navis.browser.persistence.BookmarkEntry

/** A presentation of the existing bookmark tree, not a separate favourites store. */
internal object SpeedDialPolicy {
    /** Keep the default three-column phone grid without squeezing enlarged labels. */
    fun minimumCellWidthDp(fontScale: Float): Float =
        96f * if (fontScale.isFinite()) fontScale.coerceAtLeast(1f) else 1f

    fun folder(entries: List<BookmarkEntry>, id: String?): BookmarkEntry? =
        entries.firstOrNull { it.id == id && it.isFolder }

    fun children(entries: List<BookmarkEntry>, folderId: String?): List<BookmarkEntry> {
        val parent = folder(entries, folderId)?.id
        return entries.filter { it.parentId == parent }
            .sortedWith(compareBy<BookmarkEntry> { it.position }.thenBy { it.createdAt }.thenBy { it.id })
    }

    fun monogram(entry: BookmarkEntry): String {
        val title = entry.title.trim().ifEmpty {
            runCatching { java.net.URI(entry.url).host.orEmpty().removePrefix("www.") }
                .getOrDefault("")
        }.ifEmpty { "?" }
        return title.substring(0, title.offsetByCodePoints(0, 1)).uppercase(Locale.ROOT)
    }
}
