/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.engine

import org.navis.browser.api.SessionId
import java.util.UUID

/** A lease identifies an Activity instance, not merely its persistent product window. */
internal data class AndroidWindowLease(val windowId: Long, val generation: Long)
internal enum class AndroidWindowState { NORMAL, MINIMIZED, MAXIMIZED, FULLSCREEN }
internal enum class AndroidWindowBound { LEFT, TOP, WIDTH, HEIGHT }

internal data class AndroidWindowBounds(val left: Int, val top: Int, val width: Int, val height: Int) {
    init { require(width >= 0 && height >= 0) }

    fun matches(actual: AndroidWindowBounds, fields: Set<AndroidWindowBound>): Boolean = fields.all { field ->
        when (field) {
            AndroidWindowBound.LEFT -> left == actual.left
            AndroidWindowBound.TOP -> top == actual.top
            AndroidWindowBound.WIDTH -> width == actual.width
            AndroidWindowBound.HEIGHT -> height == actual.height
        }
    }
}

/** Main-thread product topology. Activity recreation never allocates a second Core window. */
internal class AndroidWindowRegistry {
    internal class Window(
        val id: Long,
        val privateMode: Boolean,
        val persistenceKey: String,
    ) {
        val sessions = mutableListOf<SessionId>()
        val reusableViewIds = ArrayDeque<Long>()
        var selectedSessionId: SessionId? = null
            internal set
        var lease: AndroidWindowLease? = null
            internal set
        var taskId: Int? = null
            internal set
        var visible: Boolean = false
            internal set
        var focused: Boolean = false
            internal set
        var bounds: AndroidWindowBounds = AndroidWindowBounds(0, 0, 0, 0)
            internal set
        /** Requested product chrome presentation is independent of any page's DOM fullscreen. */
        var fullscreenRequested: Boolean = false
            internal set
        var fullscreenApplied: Boolean = false
            internal set
        /** Only a confirmed public ENTER result grants EXIT for this same Android task. */
        var taskFullscreenRestoreTaskId: Int? = null
            internal set
        val taskMaximized: Boolean get() = taskFullscreenRestoreTaskId != null && taskFullscreenRestoreTaskId == taskId
        var minimized: Boolean = false
            internal set
        var minimizeRequested: Boolean = false
            internal set
        var closing: Boolean = false
            internal set

        val extensionState: AndroidWindowState
            get() = when {
                minimized && !visible && !focused -> AndroidWindowState.MINIMIZED
                fullscreenApplied -> AndroidWindowState.FULLSCREEN
                taskMaximized -> AndroidWindowState.MAXIMIZED
                else -> AndroidWindowState.NORMAL
            }
    }

    private val entries = linkedMapOf<Long, Window>()
    private val sessionWindows = mutableMapOf<SessionId, Long>()
    private var nextGeneration = 1L
    private val focusHistory = mutableListOf<Long>()
    val focusOrder: List<Long> get() = focusHistory.toList()
    var focusedWindowId: Long? = null
        private set
    var lastFocusedWindowId: Long? = null
        private set

    val windows: List<Window> get() = entries.values.toList()

    fun register(id: Long, privateMode: Boolean = false, persistenceKey: String = UUID.randomUUID().toString()): Window {
        require(id > 0 && id !in entries) { "Duplicate or invalid product window" }
        require(persistenceKey.isNotBlank() && persistenceKey.length <= 128)
        require(entries.values.none { it.persistenceKey == persistenceKey }) { "Duplicate persistent window identity" }
        return Window(id, privateMode, persistenceKey).also { entries[id] = it }
    }

    fun get(id: Long): Window = entries[id] ?: throw IllegalStateException("Unknown Navis window $id")
    fun find(id: Long): Window? = entries[id]
    fun owner(sessionId: SessionId): Long? = sessionWindows[sessionId]

    fun bind(id: Long, taskId: Int): AndroidWindowLease {
        require(taskId >= 0)
        val window = get(id)
        check(!window.closing) { "The Navis window is closing" }
        window.focused = false
        window.visible = false
        window.fullscreenApplied = false
        if (focusedWindowId == id) focusedWindowId = null
        val lease = AndroidWindowLease(id, nextGeneration++)
        if (window.taskId != taskId) window.taskFullscreenRestoreTaskId = null
        window.lease = lease
        window.taskId = taskId
        return lease
    }

    fun isCurrent(lease: AndroidWindowLease): Boolean = entries[lease.windowId]?.lease == lease

    fun setVisible(lease: AndroidWindowLease, visible: Boolean): Boolean {
        if (!isCurrent(lease)) return false
        val window = get(lease.windowId)
        window.visible = visible
        if (!visible) focus(lease, false)
        if (!visible && window.minimizeRequested) window.minimized = true
        return true
    }

    fun focus(lease: AndroidWindowLease, focused: Boolean): Boolean {
        if (!isCurrent(lease)) return false
        val window = get(lease.windowId)
        if (focused && (!window.visible || window.closing)) return false
        window.focused = focused
        if (focused) {
            window.minimized = false
            window.minimizeRequested = false
            entries.values.filter { it.id != window.id }.forEach { it.focused = false }
            focusedWindowId = window.id
            lastFocusedWindowId = window.id
            focusHistory.remove(window.id)
            focusHistory.add(0, window.id)
        } else if (focusedWindowId == window.id) focusedWindowId = null
        return true
    }

    fun updateBounds(lease: AndroidWindowLease, bounds: AndroidWindowBounds): Boolean {
        if (!isCurrent(lease)) return false
        get(lease.windowId).bounds = bounds
        return true
    }

    fun detach(lease: AndroidWindowLease): Boolean {
        if (!isCurrent(lease)) return false
        setVisible(lease, false)
        get(lease.windowId).lease = null
        return true
    }

    /** Pending engine shells have ownership before they become public tabs. */
    fun ownSession(windowId: Long, sessionId: SessionId, published: Boolean) {
        val window = get(windowId)
        check(!window.closing && sessionId !in sessionWindows)
        sessionWindows[sessionId] = windowId
        if (published) window.sessions += sessionId
    }

    fun publishSession(sessionId: SessionId) {
        val window = get(checkNotNull(owner(sessionId)))
        check(!window.closing)
        if (sessionId !in window.sessions) window.sessions += sessionId
    }

    fun activate(sessionId: SessionId): Boolean {
        val window = get(checkNotNull(owner(sessionId)))
        check(!window.closing && sessionId in window.sessions)
        if (window.selectedSessionId == sessionId) return false
        window.selectedSessionId = sessionId
        return true
    }

    fun move(sessionId: SessionId, index: Int): Pair<Int, Int> {
        require(index >= 0)
        val window = get(checkNotNull(owner(sessionId)))
        check(!window.closing)
        val previous = window.sessions.indexOf(sessionId)
        check(previous >= 0)
        window.sessions.removeAt(previous)
        val next = index.coerceAtMost(window.sessions.size)
        window.sessions.add(next, sessionId)
        return previous to next
    }

    fun removeSession(sessionId: SessionId): Long? {
        val window = sessionWindows.remove(sessionId)?.let(entries::get) ?: return null
        window.sessions.remove(sessionId)
        if (window.selectedSessionId == sessionId) window.selectedSessionId = null
        return window.id
    }

    fun markClosing(id: Long, closing: Boolean) { get(id).closing = closing }

    fun remove(id: Long): Window {
        val window = get(id)
        check(window.sessions.isEmpty() && sessionWindows.values.none { it == id }) {
            "Cannot remove a window that still owns Sessions"
        }
        entries.remove(id)
        focusHistory.remove(id)
        if (focusedWindowId == id) focusedWindowId = null
        if (lastFocusedWindowId == id) lastFocusedWindowId = focusHistory.firstOrNull()
        return window
    }
}
