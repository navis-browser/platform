/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.persistence

internal object BookmarkTreePolicy {
    /** Re-bookmarking edits the saved item; page titles never overwrite a user's label implicitly. */
    fun draftForPage(entries: List<BookmarkEntry>, url: String, title: String): BookmarkDraft {
        val address = url.trim()
        val existing = entries.firstOrNull { !it.isFolder && it.url == address }
        return if (existing == null) BookmarkDraft(title = title.ifBlank { address }, url = address)
        else BookmarkDraft(existing.id, existing.title, existing.url, existing.parentId, false)
    }

    fun availableParents(entries: List<BookmarkEntry>, movingId: String?): List<BookmarkEntry> {
        val byId = entries.associateBy { it.id }
        return entries.filter { folder ->
            if (!folder.isFolder) return@filter false
            val visited = mutableSetOf<String>()
            var current: BookmarkEntry? = folder
            while (current != null) {
                if (current.id == movingId || !visited.add(current.id)) return@filter false
                current = current.parentId?.let(byId::get)
            }
            true
        }
    }

    fun folderPath(entries: List<BookmarkEntry>, id: String): String {
        val byId = entries.associateBy { it.id }
        val visited = mutableSetOf<String>()
        val path = mutableListOf<String>()
        var current = byId[id]
        while (current != null && visited.add(current.id)) {
            path.add(current.title)
            current = current.parentId?.let(byId::get)
        }
        return path.asReversed().joinToString(" / ")
    }
}
