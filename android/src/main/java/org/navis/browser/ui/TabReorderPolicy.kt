/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.ui

import org.navis.browser.api.BrowserState
import org.navis.browser.api.SessionId
import org.navis.browser.api.SessionMode

internal class TabReorderTarget private constructor(
    private val windowId: Long,
    val sessionId: SessionId,
    private val mode: SessionMode,
    private val sessionIds: Set<SessionId>,
) {
    fun matches(currentWindow: Long, state: BrowserState): Boolean =
        currentWindow == windowId && state.sessions.map { it.id }.toSet() == sessionIds &&
            state.sessions.size == sessionIds.size && state.sessions.all { it.mode == mode }

    fun destination(currentWindow: Long, state: BrowserState, over: SessionId): Int? {
        if (!matches(currentWindow, state) || over == sessionId) return null
        return state.sessions.indexOfFirst { it.id == over }.takeIf { it >= 0 }
    }

    companion object {
        fun capture(windowId: Long, sessionId: SessionId, state: BrowserState): TabReorderTarget? {
            if (windowId <= 0) return null
            val tab = state.sessions.firstOrNull { it.id == sessionId } ?: return null
            return TabReorderTarget(windowId, sessionId, tab.mode, state.sessions.map { it.id }.toSet())
                .takeIf { it.matches(windowId, state) }
        }
    }
}
