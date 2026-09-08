/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.ui

import org.navis.browser.api.BrowserSessionState
import org.navis.browser.api.BrowserState
import org.navis.browser.api.SessionId
import org.navis.browser.api.SessionMode

internal enum class TabMenuAction { DUPLICATE }

/** One menu belongs to one tab/window, not whichever tab later becomes active. */
internal class TabMenuTarget private constructor(
    private val windowId: Long,
    private val targetId: SessionId,
    private val mode: SessionMode,
) {
    private var consumed = false

    private fun tabs(state: BrowserState): List<BrowserSessionState>? = state.sessions.takeIf { entries ->
        entries.any { it.id == targetId && it.mode == mode } &&
            entries.all { it.mode == mode } && entries.map { it.id }.distinct().size == entries.size
    }

    fun enabled(action: TabMenuAction, state: BrowserState): Boolean {
        if (consumed) return false
        val entries = tabs(state) ?: return false
        val index = entries.indexOfFirst { it.id == targetId }
        return when (action) {
            TabMenuAction.DUPLICATE -> entries[index].navigation.url.isNotBlank()
        }
    }

    /** True means commands were dispatched, not that asynchronous closes committed. */
    fun perform(
        action: TabMenuAction,
        currentWindowId: Long,
        state: () -> BrowserState,
        duplicate: (SessionMode, String) -> Unit,
        move: (SessionId, Int) -> Unit,
        activate: (SessionId) -> Unit,
        close: (SessionId) -> Unit,
    ): Boolean {
        if (consumed || currentWindowId != windowId) return false
        val current = state()
        if (!enabled(action, current)) return false
        val entries = checkNotNull(tabs(current))
        val index = entries.indexOfFirst { it.id == targetId }
        consumed = true
        when (action) {
            // Match Desktop's URL duplication: no cloned history, POST or form state.
            TabMenuAction.DUPLICATE -> duplicate(mode, entries[index].navigation.url)
        }
        return true
    }

    companion object {
        fun capture(windowId: Long, targetId: SessionId, state: BrowserState): TabMenuTarget? {
            if (windowId <= 0) return null
            val target = state.sessions.firstOrNull { it.id == targetId } ?: return null
            return TabMenuTarget(windowId, targetId, target.mode).takeIf { it.tabs(state) != null }
        }
    }
}
