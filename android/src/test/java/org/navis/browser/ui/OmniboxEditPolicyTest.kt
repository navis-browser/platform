/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OmniboxEditPolicyTest {
    @Test fun firstTapSelectsAfterTheNativeFieldPlacesItsCaret() {
        val policy = OmniboxEditPolicy()
        val previous = "http://127.0.0.1:8877/"
        val gesture = policy.beginPointerGesture()
        assertFalse(policy.focusChanged(true)) // Do not race TextField's tap/long-press handling.
        var start = 9 // The TextField main pass places the caret here.
        var end = start
        if (policy.finishPointerGesture(gesture, isTap = true, focused = true)) {
            start = 0
            end = previous.length
        }
        val replacement = "https://example.com/"
        assertEquals(replacement, previous.replaceRange(start, end, replacement))
    }

    @Test fun laterTapsWithinAnEditKeepTheUsersCursorPosition() {
        val policy = OmniboxEditPolicy()
        assertTrue(policy.focusChanged(true))
        val gesture = policy.beginPointerGesture()
        assertFalse(policy.focusChanged(true))
        assertFalse(policy.finishPointerGesture(gesture, isTap = true, focused = true))
    }

    @Test fun keyboardAndProgrammaticEntrySelectAllWithoutAPointerGesture() {
        val policy = OmniboxEditPolicy()
        assertTrue(policy.focusChanged(true))
        assertFalse(policy.focusChanged(true))
        policy.focusChanged(false)
        assertTrue(policy.focusChanged(true))
    }

    @Test fun longPressOrDragPreservesNativeSelection() {
        for (longPressOrDrag in listOf("long-press", "drag", "multi-touch")) {
            val policy = OmniboxEditPolicy()
            val gesture = policy.beginPointerGesture()
            assertFalse(longPressOrDrag, policy.focusChanged(true))
            assertFalse(longPressOrDrag, policy.finishPointerGesture(gesture, isTap = false, focused = true))
        }
    }

    @Test fun navigationOrToolboxExitInvalidatesTheOldGestureAndResetsEntry() {
        val policy = OmniboxEditPolicy()
        val oldGesture = policy.beginPointerGesture()
        policy.focusChanged(true)
        policy.endEditing()
        assertFalse(policy.finishPointerGesture(oldGesture, isTap = true, focused = true))
        val next = policy.beginPointerGesture()
        policy.focusChanged(true)
        assertTrue(policy.finishPointerGesture(next, isTap = true, focused = true))
    }

    @Test fun firstEditEntryStillSelectsAllIfTheFieldAlreadyHasNativeFocus() {
        val policy = OmniboxEditPolicy()
        policy.focusChanged(true)
        policy.endEditing()
        val gesture = policy.beginPointerGesture()
        // The native field has not emitted another focus-change callback.
        assertTrue(policy.finishPointerGesture(gesture, isTap = true, focused = true))
        val next = policy.beginPointerGesture()
        assertFalse(policy.finishPointerGesture(next, isTap = true, focused = true))
    }

    @Test fun cancelledOrStalePointerCannotSelectAfterFocusMovesAway() {
        val policy = OmniboxEditPolicy()
        val gesture = policy.beginPointerGesture()
        policy.focusChanged(true)
        policy.focusChanged(false)
        assertFalse(policy.finishPointerGesture(gesture, isTap = true, focused = false))
        val cancelled = policy.beginPointerGesture()
        policy.cancelPointerGesture(cancelled)
        assertFalse(policy.finishPointerGesture(cancelled, isTap = true, focused = true))
    }
}
