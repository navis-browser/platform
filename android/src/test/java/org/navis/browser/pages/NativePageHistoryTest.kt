package org.navis.browser.pages

import org.junit.Assert.*
import org.junit.Test

class NativePageHistoryTest {
    private val a = EnginePageEntry(1, "https://a.example/")
    private val b = EnginePageEntry(2, "https://b.example/")
    private val c = EnginePageEntry(3, "https://c.example/")

    @Test fun nativeAndEngineEntriesInterleaveWithoutInventingWebEntries() {
        val history = NativePageHistory()
        history.synchronize(EnginePageHistory(listOf(a), 0), true)
        history.push("navis://settings/")
        history.push("navis://history/")
        history.synchronize(EnginePageHistory(listOf(a, b), 1), true)
        assertEquals(listOf(a.uri, "navis://settings/", "navis://history/", b.uri), history.snapshot()!!.entries)
        assertEquals("navis://history/", history.back())
        assertEquals("navis://settings/", history.back())
        assertEquals(a.uri, history.back())
        assertEquals(a, history.currentEngineEntry)
        assertEquals(0, history.enginePosition)
        assertEquals("navis://settings/", history.forward())
        assertEquals("navis://history/", history.forward())
        assertEquals(b.uri, history.forward())
        assertEquals(1, history.enginePosition)
    }

    @Test fun aNewNativeBranchDoesNotResurrectTheEnginesHiddenForwardBranch() {
        val history = NativePageHistory()
        history.synchronize(EnginePageHistory(listOf(a, b), 0), true)
        history.push("navis://bookmarks/")
        history.synchronize(EnginePageHistory(listOf(a, b), 0), false)
        assertFalse(history.canGoForward)
        assertEquals(listOf(a.uri, "navis://bookmarks/"), history.snapshot()!!.entries)
        assertEquals(listOf(1), history.snapshot()!!.suppressedSlots)
        history.synchronize(EnginePageHistory(listOf(a, c), 1), true)
        assertEquals(listOf(a.uri, "navis://bookmarks/", c.uri), history.snapshot()!!.entries)
        assertTrue(history.snapshot()!!.suppressedSlots.isEmpty())
    }

    @Test fun returningToANativePageAndBranchingDropsOnlyItsForwardSuccessor() {
        val history = NativePageHistory()
        history.synchronize(EnginePageHistory(listOf(a), 0), true)
        history.push("navis://history/")
        history.synchronize(EnginePageHistory(listOf(a, b), 1), true)
        history.back()
        assertEquals(0, history.precedingEnginePosition)
        // The real engine is first traversed to A, then loads C, replacing the B branch.
        history.synchronize(EnginePageHistory(listOf(a, b), 0), false)
        history.synchronize(EnginePageHistory(listOf(a, c), 1), true)
        assertEquals(listOf(a.uri, "navis://history/", c.uri), history.snapshot()!!.entries)
    }

    @Test fun restoredRealEntriesRekeyWithoutChangingTheSelectedNativeRoute() {
        val original = NativePageHistory()
        original.synchronize(EnginePageHistory(listOf(a), 0), true)
        original.push("navis://settings/")
        original.synchronize(EnginePageHistory(listOf(a, b), 1), true)
        original.back()
        val restored = NativePageHistory()
        assertTrue(restored.restore(original.snapshot(), "navis://settings/"))
        restored.synchronize(EnginePageHistory(listOf(a.copy(id = 21, identity = "21"), b.copy(id = 22, identity = "22")), 1), false)
        assertEquals("navis://settings/", restored.current)
        restored.back()
        assertEquals(21L, restored.currentEngineEntry!!.id)
    }

    @Test fun invalidatedSourcesNeverRebindToAnUnrelatedEntryAtTheSameIndex() {
        val history = NativePageHistory()
        history.synchronize(EnginePageHistory(listOf(a), 0), true)
        history.push("navis://settings/")
        history.synchronize(EnginePageHistory(listOf(c), 0), false)
        assertEquals("navis://settings/", history.current)
        assertFalse(history.canGoBack)
        // The replacement is real forward state, not a guessed source for Settings.
        assertNull(history.precedingEnginePosition)
    }

    @Test fun sharedTopLevelIdsForSubframeHistoryRemainDistinct() {
        val raw = """{"version":1,"history":{"index":2,"entries":[
            {"ID":1,"url":"https://frame.test/","children":[{"ID":10,"url":"https://child.test/a"}]},
            {"ID":1,"url":"https://frame.test/","children":[{"ID":11,"url":"https://child.test/b"}]}]}}"""
        val engine = EnginePageHistory.parse(raw)!!
        assertEquals(listOf("1[10]", "1[11]"), engine.entries.map { it.identity })
        val history = NativePageHistory()
        history.synchronize(engine, true)
        history.push("navis://history/")
        assertEquals(1, history.precedingEnginePosition)
        history.back()
        assertEquals(1, history.enginePosition)
        history.back()
        assertEquals(0, history.enginePosition)
    }

    @Test fun actualRoutesDetermineBothBackAndForward() {
        val history = NativePageHistory()
        listOf("navis://settings/", "navis://settings/help", "navis://support/").forEach { assertTrue(history.push(it)) }
        assertEquals("navis://settings/help", history.back())
        assertEquals("navis://settings/", history.back())
        assertNull(history.back())
        assertEquals("navis://settings/help", history.forward())
        assertEquals("navis://support/", history.forward())
        assertNull(history.forward())
    }

    @Test fun urlsNavigationDoesNotGuessSettingsAsParent() {
        val history = NativePageHistory()
        history.push("navis://urls/")
        history.push("navis://downloads/")
        assertEquals("navis://urls/", history.back())
    }

    @Test fun canonicalDuplicatesDoNotMakeLoopsAndNewBranchDropsForward() {
        val history = NativePageHistory()
        history.push("navis://settings/")
        history.push("navis://settings")
        assertFalse(history.canGoBack)
        history.push("navis://downloads/")
        history.back()
        history.push("navis://history/")
        assertFalse(history.canGoForward)
        assertEquals(listOf("navis://settings/", "navis://history/"), history.snapshot()?.entries)
    }

    @Test fun snapshotIsBoundedAndDefensivelyCopied() {
        val history = NativePageHistory()
        repeat(150) { history.push(if (it % 2 == 0) "navis://history/" else "navis://bookmarks/") }
        val saved = history.snapshot()!!
        assertEquals(NativePageHistory.MAX_ENTRIES, saved.entries.size)
        history.clear()
        assertEquals(NativePageHistory.MAX_ENTRIES, saved.entries.size)
        assertFalse(history.canGoBack)
    }

    @Test fun restoreRequiresKnownCanonicalUrisMatchingTheSelectedPage() {
        val history = NativePageHistory()
        val saved = NativePageHistorySnapshot(listOf("navis://settings", "navis://downloads/"), 1)
        assertTrue(history.restore(saved, "navis://downloads/"))
        assertEquals("navis://settings/", history.back())
        assertFalse(history.restore(saved, "navis://history/"))
        assertEquals("navis://settings/", history.current)
        for (bad in listOf("https://example.com/", "navis://unknown/", "navis://settings/?secret=x", "navis://settings/#fragment")) {
            assertFalse(history.push(bad))
            assertFalse(history.restore(NativePageHistorySnapshot(listOf(bad), 0), bad))
        }
        assertFalse(history.restore(saved.copy(index = -1), "navis://downloads/"))
        assertFalse(history.restore(saved.copy(index = 3), "navis://downloads/"))
        assertFalse(history.restore(NativePageHistorySnapshot(List(65) { "navis://settings/" }, 0), "navis://settings/"))
    }
}
