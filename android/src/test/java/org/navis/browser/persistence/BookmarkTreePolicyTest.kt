/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.persistence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BookmarkTreePolicyTest {
    private fun folder(id: String, parentId: String? = null) =
        BookmarkEntry("", id, 1L, id, parentId, 0, true)

    @Test fun movingFolderExcludesItselfAndEveryDescendant() {
        val records = listOf(folder("root"), folder("child", "root"),
            folder("grandchild", "child"), folder("other"))
        assertEquals(listOf("other"), BookmarkTreePolicy.availableParents(records, "root").map { it.id })
        assertEquals(listOf("root", "other"), BookmarkTreePolicy.availableParents(records, "child").map { it.id })
    }

    @Test fun ordinaryBookmarksCannotBecomeParents() {
        val records = listOf(folder("folder"), BookmarkEntry("https://example.com/", "page", 1L))
        assertEquals(listOf("folder"), BookmarkTreePolicy.availableParents(records, null).map { it.id })
    }

    @Test fun corruptCyclesAreExcludedAndPathTerminates() {
        val records = listOf(folder("one", "two"), folder("two", "one"))
        assertTrue(BookmarkTreePolicy.availableParents(records, null).isEmpty())
        assertEquals("two / one", BookmarkTreePolicy.folderPath(records, "one"))
    }

    @Test fun pathDistinguishesFoldersWithTheSameName() {
        val records = listOf(folder("work"), folder("personal"),
            folder("a", "work").copy(title = "Saved"), folder("b", "personal").copy(title = "Saved"))
        assertEquals("work / Saved", BookmarkTreePolicy.folderPath(records, "a"))
        assertEquals("personal / Saved", BookmarkTreePolicy.folderPath(records, "b"))
    }
}
