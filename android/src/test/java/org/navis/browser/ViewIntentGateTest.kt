/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewIntentGateTest {
    @Test
    fun acceptedDeliveryCannotBeReplayedByActivityReattachment() {
        val gate = ViewIntentGate(restoredConsumed = false)

        assertTrue(gate.shouldHandle())
        gate.markHandled()

        assertTrue(gate.consumed)
        assertFalse(gate.shouldHandle())
    }

    @Test
    fun restoredConsumptionSuppressesRecreationButNewIntentStartsFreshDelivery() {
        val gate = ViewIntentGate(restoredConsumed = true)

        assertFalse(gate.shouldHandle())
        gate.onNewDelivery()

        assertFalse(gate.consumed)
        assertTrue(gate.shouldHandle())
    }
}
