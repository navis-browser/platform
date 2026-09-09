// SPDX-License-Identifier: MPL-2.0

package org.navis.browser.engine

import android.view.KeyEvent

/** Converts a physical Android key chord to the WebExtension shortcut grammar. */
internal fun KeyEvent.toExtensionShortcut(): String? {
    if (action != KeyEvent.ACTION_DOWN || repeatCount > 0) return null
    val modifiers = buildList {
        if (isCtrlPressed) add("Ctrl")
        if (isAltPressed) add("Alt")
        if (isShiftPressed) add("Shift")
        if (isMetaPressed) add("Command")
    }
    val key = when {
        keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z ->
            ('A'.code + keyCode - KeyEvent.KEYCODE_A).toChar().toString()
        keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 ->
            (keyCode - KeyEvent.KEYCODE_0).toString()
        keyCode in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 ->
            "F${keyCode - KeyEvent.KEYCODE_F1 + 1}"
        else -> mapOf(
            KeyEvent.KEYCODE_SPACE to "Space",
            KeyEvent.KEYCODE_COMMA to "Comma",
            KeyEvent.KEYCODE_PERIOD to "Period",
            KeyEvent.KEYCODE_ENTER to "Enter",
            KeyEvent.KEYCODE_TAB to "Tab",
            KeyEvent.KEYCODE_DEL to "Backspace",
            KeyEvent.KEYCODE_DPAD_UP to "Up",
            KeyEvent.KEYCODE_DPAD_DOWN to "Down",
            KeyEvent.KEYCODE_DPAD_LEFT to "Left",
            KeyEvent.KEYCODE_DPAD_RIGHT to "Right",
        )[keyCode]
    } ?: return null
    // Function keys are valid command keys without modifiers. Ordinary
    // characters must retain at least one modifier so Ctrl+C/V are not
    // projected as extension shortcuts.
    if (modifiers.isEmpty() && !key.startsWith("F")) return null
    return (modifiers + key).joinToString("+")
}
