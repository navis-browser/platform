package org.navis.browser.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.navis.browser.persistence.BookmarkEntry

class SpeedDialPolicyTest {
    private fun bookmark(id: String, parent: String? = null, position: Int = 0, folder: Boolean = false) =
        BookmarkEntry("https://$id.example/", id, 0, id, parent, position, folder)

    @Test fun rootsPreservePersistedOrderAndFolders() {
        val entries = listOf(bookmark("second", position = 1), bookmark("folder", folder = true),
            bookmark("nested", parent = "folder"))
        assertEquals(listOf("folder", "second"), SpeedDialPolicy.children(entries, null).map { it.id })
    }

    @Test fun enlargedSystemFontsGetWiderCellsRatherThanFixedThreeColumns() {
        assertEquals(96f, SpeedDialPolicy.minimumCellWidthDp(1f), 0f)
        assertEquals(144f, SpeedDialPolicy.minimumCellWidthDp(1.5f), 0f)
        assertEquals(192f, SpeedDialPolicy.minimumCellWidthDp(2f), 0f)
        // With 20dp gutters and 12dp gaps, a 360dp phone starts at three columns.
        fun columns(width: Float, fontScale: Float) =
            ((width - 40f + 12f) / (SpeedDialPolicy.minimumCellWidthDp(fontScale) + 12f)).toInt().coerceAtLeast(1)
        assertEquals(3, columns(360f, 1f))
        assertEquals(2, columns(360f, 1.5f))
        assertEquals(1, columns(360f, 2f))
        assertEquals(6, columns(720f, 1f))
        assertEquals(3, columns(720f, 2f))
    }

    @Test fun smallerOrInvalidFontScaleCannotShrinkTouchCells() {
        listOf(0.85f, 0f, -1f, Float.NaN, Float.POSITIVE_INFINITY).forEach {
            assertEquals(96f, SpeedDialPolicy.minimumCellWidthDp(it), 0f)
        }
    }

    @Test fun nestedFoldersExposeOnlyTheirOwnChildren() {
        val entries = listOf(bookmark("root", folder = true), bookmark("nested", "root", folder = true),
            bookmark("link", "nested"), bookmark("other"))
        assertEquals(listOf("nested"), SpeedDialPolicy.children(entries, "root").map { it.id })
        assertEquals(listOf("link"), SpeedDialPolicy.children(entries, "nested").map { it.id })
        assertEquals("root", SpeedDialPolicy.folder(entries, "nested")?.parentId)
    }

    @Test fun deletedOrNonFolderSelectionReturnsToRootWithoutLosingEntries() {
        val entries = listOf(bookmark("root"), bookmark("nested", "deleted"))
        assertEquals(listOf("root"), SpeedDialPolicy.children(entries, "deleted").map { it.id })
        assertEquals(listOf("root"), SpeedDialPolicy.children(entries, "root").map { it.id })
        assertNull(SpeedDialPolicy.folder(entries, "root"))
    }

    @Test fun gridHasNoNineItemOrScreenfulLimit() {
        val entries = (0 until 40).map { bookmark("link$it", position = it) }
        assertEquals(entries, SpeedDialPolicy.children(entries.reversed(), null))
    }

    @Test fun monogramsHandleUnicodeHostFallbackAndMissingTitle() {
        assertEquals("中", SpeedDialPolicy.monogram(bookmark("a").copy(title = " 中文")))
        assertEquals("💙", SpeedDialPolicy.monogram(bookmark("a").copy(title = "💙 Navis")))
        assertEquals("E", SpeedDialPolicy.monogram(bookmark("a").copy(title = "", url = "https://www.example.com/")))
        assertEquals("?", SpeedDialPolicy.monogram(bookmark("a").copy(title = "", url = "not a URI")))
    }
}
