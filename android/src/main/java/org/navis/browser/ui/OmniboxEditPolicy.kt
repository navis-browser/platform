/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.ui

/** Separates entering address editing from normal cursor placement inside an existing edit. */
internal class OmniboxEditPolicy {
    private data class Gesture(val token: Long, val enteredEditing: Boolean)

    private var editing = false
    private var nextGesture = 0L
    private var gesture: Gesture? = null

    fun beginPointerGesture(): Long {
        val token = ++nextGesture
        gesture = Gesture(token, enteredEditing = !editing)
        return token
    }

    /** Non-pointer focus (keyboard/accessibility/new-tab search) can select immediately. */
    fun focusChanged(focused: Boolean): Boolean {
        if (!focused) {
            endEditing()
            return false
        }
        val selectAll = !editing && gesture == null
        editing = true
        return selectAll
    }

    /** Called after the TextField handled the pointer-up, so its caret cannot overwrite select-all. */
    fun finishPointerGesture(token: Long, isTap: Boolean, focused: Boolean): Boolean {
        val current = gesture?.takeIf { it.token == token } ?: return false
        gesture = null
        val selectAll = current.enteredEditing && isTap && focused
        if (selectAll) editing = true
        return selectAll
    }

    fun cancelPointerGesture(token: Long) {
        if (gesture?.token == token) gesture = null
    }

    fun endEditing() {
        editing = false
        gesture = null
    }
}
