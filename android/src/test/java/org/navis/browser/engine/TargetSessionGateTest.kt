/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.navis.browser.api.SessionId

class TargetSessionGateTest {
    @Test
    fun switchingFromNormalToPrivateRevokesNormalOwnership() {
        val normal = SessionId(1)
        val private = SessionId(2)
        val live = mutableSetOf(normal, private)
        val gate = TargetSessionGate(live::contains)

        assertTrue(gate.activate(normal))
        assertTrue(gate.allows(normal))
        assertTrue(gate.activate(private))
        assertFalse(gate.allows(normal))
        assertTrue(gate.allows(private))
    }

    @Test
    fun closedActiveSessionCannotCompleteARequest() {
        val session = SessionId(3)
        val live = mutableSetOf(session)
        val gate = TargetSessionGate(live::contains)

        assertTrue(gate.activate(session))
        gate.deactivate(session)
        assertFalse(gate.allows(session))

        assertTrue(gate.activate(session))
        live.remove(session)

        assertFalse(gate.allows(session))
    }

    @Test
    fun backgroundAndUnknownSessionsNeverOwnRequests() {
        val active = SessionId(4)
        val background = SessionId(5)
        val gate = TargetSessionGate(setOf(active, background)::contains)

        assertTrue(gate.activate(active))
        assertFalse(gate.allows(background))
        assertFalse(gate.activate(SessionId(999)))
        assertTrue(gate.allows(active))
    }
}
