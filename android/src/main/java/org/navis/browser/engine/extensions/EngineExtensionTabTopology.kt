/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.engine.extensions

internal enum class EngineExtensionWindowState { NORMAL, MINIMIZED, MAXIMIZED, FULLSCREEN }

internal enum class EngineExtensionTabTopologyEventType {
    CREATED,
    REMOVED,
    ACTIVATED,
    MOVED,
}

internal data class EngineExtensionTabTopologyEntry(
    val tabId: Long,
    val index: Int,
    val privateMode: Boolean,
    val windowId: Long = 0,
) {
    init {
        require(tabId > 0) { "Invalid extension topology tab ID" }
        require(index >= 0) { "Invalid extension topology tab index" }
        require(windowId >= 0)
    }
}

internal data class EngineExtensionWindowTopologyEntry(
    val windowId: Long,
    val activeTabId: Long?,
    val privateMode: Boolean,
    val focused: Boolean,
    val left: Int = 0,
    val top: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    val state: EngineExtensionWindowState = EngineExtensionWindowState.NORMAL,
) {
    init {
        require(windowId > 0 && width >= 0 && height >= 0)
        require(state != EngineExtensionWindowState.MINIMIZED || !focused)
    }
}

internal data class EngineExtensionTabTopologyEvent(
    val type: EngineExtensionTabTopologyEventType,
    val tabId: Long,
    val previousTabId: Long? = null,
    val isWindowClosing: Boolean = false,
) {
    init {
        require(tabId > 0) { "Invalid extension topology event tab ID" }
        require(previousTabId == null || previousTabId > 0) {
            "Invalid previous extension topology tab ID"
        }
        require(type == EngineExtensionTabTopologyEventType.ACTIVATED || previousTabId == null) {
            "Only activation events may include a previous tab"
        }
        require(!isWindowClosing || type == EngineExtensionTabTopologyEventType.REMOVED)
    }
}

/** Immutable product-owned tab order projected into Gecko's private binding. */
internal data class EngineExtensionTabTopology(
    val revision: Long,
    val windowId: Long,
    val activeTabId: Long?,
    val tabs: List<EngineExtensionTabTopologyEntry>,
    val events: List<EngineExtensionTabTopologyEvent> = emptyList(),
    val windows: List<EngineExtensionWindowTopologyEntry> = listOf(
        EngineExtensionWindowTopologyEntry(windowId, activeTabId, false, false)),
    val focusedWindowId: Long? = null,
    val lastFocusedWindowId: Long? = null,
    val focusOrder: List<Long> = listOfNotNull(lastFocusedWindowId),
) {
    init {
        require(revision > 0) { "Invalid extension topology revision" }
        require(windowId > 0 || (windowId == 0L && windows.isEmpty())) { "Invalid extension topology window ID" }
        require(windows.size <= 64 && windows.map { it.windowId }.distinct().size == windows.size)
        require(windows.count { it.focused } <= 1)
        require(focusedWindowId == windows.singleOrNull { it.focused }?.windowId)
        require(lastFocusedWindowId == null || windows.any { it.windowId == lastFocusedWindowId })
        require(focusOrder.distinct().size == focusOrder.size && focusOrder.all { id -> windows.any { it.windowId == id } })
        require(focusOrder.firstOrNull() == lastFocusedWindowId)
        require(tabs.size <= MAX_TABS) { "Extension topology is too large" }
        require(events.size <= MAX_TABS * 2) { "Extension topology event list is too large" }
        require(tabs.map(EngineExtensionTabTopologyEntry::tabId).toSet().size == tabs.size) {
            "Duplicate extension topology tab ID"
        }
        require(tabs.groupBy { it.windowId.takeIf { id -> id > 0 } ?: windowId }.all { (id, entries) ->
            windows.any { it.windowId == id } && entries.withIndex().all { (index, entry) -> entry.index == index }
        }) {
            "Extension topology indexes must be contiguous"
        }
        require(activeTabId == null || tabs.any { it.tabId == activeTabId }) {
            "Active extension tab must belong to the product window"
        }
        require(windows.all { window -> window.activeTabId == null || tabs.any {
            it.tabId == window.activeTabId && (it.windowId == window.windowId || it.windowId == 0L)
        } })
    }

    private companion object {
        const val MAX_TABS = 2048
    }
}
