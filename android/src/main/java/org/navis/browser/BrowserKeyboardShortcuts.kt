/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser

import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent

internal sealed interface BrowserKeyboardAction {
    data object FocusAddress : BrowserKeyboardAction
    data object NewTab : BrowserKeyboardAction
    data object CloseTab : BrowserKeyboardAction
    data object Reload : BrowserKeyboardAction
    data object NextTab : BrowserKeyboardAction
    data object PreviousTab : BrowserKeyboardAction
    data object ToggleDevTools : BrowserKeyboardAction
    /** Claimed only while the product window/document is fullscreen. */
    data object ExitFullscreen : BrowserKeyboardAction
    /** 1–8 are positions, 9 selects the last tab, as on Navis Desktop. */
    data class SelectTab(val number: Int) : BrowserKeyboardAction
}

/** Only the explicitly supported browser chords are reserved. IME/pre-IME and
 * unclaimed editing/extension events continue through Android's existing chain.
 */
internal class BrowserKeyboardShortcuts : AutoCloseable {
    private class Registration(val isCurrent: () -> Boolean, val handle: (BrowserKeyboardAction) -> Boolean)
    private data class Press(val deviceId: Int, val keyCode: Int, val downTime: Long)
    private val handlers = mutableListOf<Registration>()
    private val consumed = mutableSetOf<Press>()
    private var closed = false

    fun register(isCurrent: () -> Boolean, handle: (BrowserKeyboardAction) -> Boolean): AutoCloseable {
        checkMainThread()
        check(!closed)
        val registration = Registration(isCurrent, handle)
        handlers += registration
        return AutoCloseable { checkMainThread(); handlers.remove(registration) }
    }

    fun dispatch(event: KeyEvent): Boolean {
        checkMainThread()
        if (closed || !event.isFromSource(InputDevice.SOURCE_KEYBOARD) ||
            event.flags and (KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_FALLBACK) != 0) return false
        val press = Press(event.deviceId, event.keyCode, event.downTime)
        if (event.action == KeyEvent.ACTION_UP) return consumed.remove(press)
        if (event.action != KeyEvent.ACTION_DOWN || event.isCanceled) return false
        if (press in consumed) return true // no repeated destructive actions
        if (event.repeatCount != 0) return false
        val action = action(event) ?: return false
        // A child surface owns routing while mounted: a refusal must not fall
        // back to its browser parent (notably Ctrl+L inside the Web Console).
        val owner = handlers.lastOrNull() ?: return false
        if (!owner.isCurrent() || !owner.handle(action)) return false
        consumed += press
        return true
    }

    fun clearPressed() { checkMainThread(); consumed.clear() }

    override fun close() {
        checkMainThread()
        closed = true
        handlers.clear()
        consumed.clear()
    }

    private fun checkMainThread() = check(Looper.myLooper() == Looper.getMainLooper())

    private fun action(event: KeyEvent): BrowserKeyboardAction? {
        if (event.isAltPressed || event.isMetaPressed || event.isSymPressed || event.isFunctionPressed) return null
        if (event.keyCode == KeyEvent.KEYCODE_F12 && !event.isCtrlPressed && !event.isShiftPressed) {
            return BrowserKeyboardAction.ToggleDevTools
        }
        if (event.keyCode == KeyEvent.KEYCODE_ESCAPE && !event.isCtrlPressed && !event.isShiftPressed) {
            return BrowserKeyboardAction.ExitFullscreen
        }
        if (!event.isCtrlPressed) return null
        if (event.keyCode == KeyEvent.KEYCODE_TAB) return if (event.isShiftPressed) {
            BrowserKeyboardAction.PreviousTab
        } else BrowserKeyboardAction.NextTab
        if (event.isShiftPressed) return null
        return when (event.keyCode) {
            KeyEvent.KEYCODE_L -> BrowserKeyboardAction.FocusAddress
            KeyEvent.KEYCODE_T -> BrowserKeyboardAction.NewTab
            KeyEvent.KEYCODE_W -> BrowserKeyboardAction.CloseTab
            KeyEvent.KEYCODE_R -> BrowserKeyboardAction.Reload
            in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> BrowserKeyboardAction.SelectTab(event.keyCode - KeyEvent.KEYCODE_0)
            else -> null
        }
    }
}
