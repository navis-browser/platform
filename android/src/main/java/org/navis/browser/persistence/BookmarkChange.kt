/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */
package org.navis.browser.persistence

/** A committed, ordered snapshot pair. No notification is emitted for a rolled-back write. */
internal data class BookmarkChange(
    val before: List<BookmarkEntry>,
    val after: List<BookmarkEntry>,
    val movedId: String? = null,
)

internal sealed interface ExtensionBookmarkMutation {
    data class Create(val draft: BookmarkDraft, val index: Int?) : ExtensionBookmarkMutation
    data class Update(val id: String, val title: String?, val url: String?) : ExtensionBookmarkMutation
    data class Move(val id: String, val parent: String?, val parentSpecified: Boolean, val index: Int?) : ExtensionBookmarkMutation
    data class Remove(val id: String, val recursive: Boolean) : ExtensionBookmarkMutation
}
