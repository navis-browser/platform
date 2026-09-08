/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.engine.extensions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EngineExtensionTabTopologyTest {
    @Test
    fun acceptsOneOrderedWindowWithExactPerTabPrivacy() {
        val topology = EngineExtensionTabTopology(
            revision = 7,
            windowId = 11,
            activeTabId = 2,
            tabs = listOf(
                EngineExtensionTabTopologyEntry(1, 0, privateMode = false),
                EngineExtensionTabTopologyEntry(2, 1, privateMode = true),
            ),
            events = listOf(
                EngineExtensionTabTopologyEvent(
                    type = EngineExtensionTabTopologyEventType.ACTIVATED,
                    tabId = 2,
                    previousTabId = 1,
                ),
            ),
        )

        assertEquals(listOf(1L, 2L), topology.tabs.map { it.tabId })
        assertEquals(2L, topology.activeTabId)
    }

    @Test
    fun rejectsDuplicateOrNonContiguousProductOrder() {
        assertThrows(IllegalArgumentException::class.java) {
            EngineExtensionTabTopology(
                revision = 1,
                windowId = 1,
                activeTabId = 1,
                tabs = listOf(
                    EngineExtensionTabTopologyEntry(1, 0, privateMode = false),
                    EngineExtensionTabTopologyEntry(1, 1, privateMode = false),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            EngineExtensionTabTopology(
                revision = 1,
                windowId = 1,
                activeTabId = 1,
                tabs = listOf(EngineExtensionTabTopologyEntry(1, 1, privateMode = false)),
            )
        }
    }

    @Test
    fun rejectsActiveTabsOutsideTheProductWindowAndAmbiguousEvents() {
        assertThrows(IllegalArgumentException::class.java) {
            EngineExtensionTabTopology(
                revision = 1,
                windowId = 1,
                activeTabId = 2,
                tabs = listOf(EngineExtensionTabTopologyEntry(1, 0, privateMode = false)),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            EngineExtensionTabTopologyEvent(
                type = EngineExtensionTabTopologyEventType.MOVED,
                tabId = 1,
                previousTabId = 2,
            )
        }
    }
}
