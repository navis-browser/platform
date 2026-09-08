/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewSessionGateTest {
    @Test
    fun staleBoundSessionCannotReceivePlatformCallbacks() {
        val owner = Owner(live = true)
        val gate = ViewSessionGate<Owner>(Owner::live)

        assertNull(gate.replace(owner))
        assertSame(owner, gate.current())

        owner.live = false
        assertNull(gate.current())
        assertTrue(gate.isBoundTo(owner))
        assertSame(owner, gate.clear())
        assertFalse(gate.isBoundTo(owner))
    }

    @Test
    fun replacingOwnerReturnsOnlyThePreviousDistinctBinding() {
        val first = Owner(live = true)
        val second = Owner(live = true)
        val gate = ViewSessionGate<Owner>(Owner::live)

        gate.replace(first)
        assertNull(gate.replace(first))
        assertSame(first, gate.replace(second))
        assertSame(second, gate.current())
    }

    private class Owner(var live: Boolean)
}
