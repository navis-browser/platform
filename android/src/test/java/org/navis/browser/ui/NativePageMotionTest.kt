/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.ui

import org.junit.Assert.*
import org.junit.Test
import org.navis.browser.api.SessionId

class NativePageMotionTest {
    private val origin = NativePageTarget(SessionId(7), "settings/privacy", 3)

    @Test fun historyTraversalReversesMotionIncludingSettingsSubpages() {
        val parent = origin.copy(route = "settings", index = 2)
        assertEquals(PageMotion.BACK, pageMotion(origin, parent))
        assertEquals(PageMotion.FORWARD, pageMotion(parent, origin))
        assertEquals(PageMotion.BACK, pageMotion(origin, origin.copy(route = null, index = 1)))
    }
    @Test fun switchingSessionsNeverPretendsToTraverseTheirHistory() {
        assertEquals(PageMotion.TAB, pageMotion(origin, origin.copy(session = SessionId(8), index = 0)))
        assertEquals(PageMotion.NONE, pageMotion(origin.copy(route = null), origin.copy(route = null, index = 8)))
    }
}
