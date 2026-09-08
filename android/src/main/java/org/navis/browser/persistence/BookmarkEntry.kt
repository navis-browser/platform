/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.persistence

internal data class BookmarkEntry(
    val url: String,
    val title: String,
    val createdAt: Long,
    val id: String = url,
    val parentId: String? = null,
    val position: Int = 0,
    val isFolder: Boolean = false,
)

internal data class BookmarkDraft(
    val id: String? = null,
    val title: String,
    val url: String = "",
    val parentId: String? = null,
    val isFolder: Boolean = false,
)
