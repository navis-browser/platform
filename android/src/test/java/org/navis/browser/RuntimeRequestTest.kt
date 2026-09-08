/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test
import org.navis.browser.engine.AndroidBrowserRuntime

class RuntimeRequestTest {
    @Test
    fun cancellationRemovesPendingRequestAndSuppressesDelivery() {
        var removals = 0
        var deliveries = 0
        lateinit var request: RuntimeRequest
        request = RuntimeRequest(
            callback = { deliveries += 1 },
            removePending = { pending ->
                assertSame(request, pending)
                removals += 1
            },
        )

        request.cancel()
        request.cancel()
        request.deliver(Result.failure(IllegalStateException("late result")))

        assertEquals(1, removals)
        assertEquals(0, deliveries)
        assertFalse(request.pending)
    }

    @Test
    fun successfulDeliveryConsumesCallbackExactlyOnce() {
        val expected = IllegalStateException("expected failure")
        var delivered: Throwable? = null
        var removals = 0
        val request = RuntimeRequest(
            callback = { result: Result<AndroidBrowserRuntime> ->
                delivered = result.exceptionOrNull()
            },
            removePending = { removals += 1 },
        )

        request.deliver(Result.failure(expected))
        request.deliver(Result.failure(IllegalStateException("duplicate")))
        request.cancel()

        assertSame(expected, delivered)
        assertEquals(0, removals)
        assertFalse(request.pending)
    }
}
