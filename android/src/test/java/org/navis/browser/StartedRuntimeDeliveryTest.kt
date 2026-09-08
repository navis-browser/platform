/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StartedRuntimeDeliveryTest {
    @Test fun cachedResultDuringCreateWaitsForRealStartedEvent() {
        val accepted = mutableListOf<String>()
        var lifecycleStarted = false
        val delivery = StartedRuntimeDelivery<String> {
            if (!lifecycleStarted) false else { accepted += it.getOrThrow(); true }
        }
        // NavisApplication.runtime may deliver synchronously during Activity.onCreate.
        delivery.offer(Result.success("cached-runtime"))
        assertTrue(accepted.isEmpty())
        // Even an incorrectly early attempt must not discard the cached result.
        assertFalse(delivery.onStarted())
        assertTrue(accepted.isEmpty())
        lifecycleStarted = true
        assertTrue(delivery.onStarted())
        assertEquals(listOf("cached-runtime"), accepted)
    }

    @Test fun coldRuntimeCompletingAfterStartIsDeliveredImmediately() {
        val accepted = mutableListOf<String>()
        val delivery = StartedRuntimeDelivery<String> { accepted += it.getOrThrow(); true }
        assertFalse(delivery.onStarted())
        delivery.offer(Result.success("new-runtime"))
        assertEquals(listOf("new-runtime"), accepted)
        assertFalse(delivery.onStarted())
    }

    @Test fun resultArrivingInBackgroundWaitsForNextStart() {
        val accepted = mutableListOf<String>()
        val delivery = StartedRuntimeDelivery<String> { accepted += it.getOrThrow(); true }
        delivery.onStarted()
        delivery.onStopped()
        delivery.offer(Result.success("ready-while-stopped"))
        assertTrue(accepted.isEmpty())
        assertTrue(delivery.onStarted())
        assertEquals(listOf("ready-while-stopped"), accepted)
    }

    @Test fun destroyedOwnerIgnoresPendingAndLateResultsWhileNewOwnerCanAcceptCache() {
        val oldAccepted = mutableListOf<String>()
        val old = StartedRuntimeDelivery<String> { oldAccepted += it.getOrThrow(); true }
        old.offer(Result.success("same-runtime"))
        old.close()
        old.offer(Result.success("late"))
        assertFalse(old.onStarted())
        assertTrue(oldAccepted.isEmpty())
        val newAccepted = mutableListOf<String>()
        val replacement = StartedRuntimeDelivery<String> { newAccepted += it.getOrThrow(); true }
        replacement.offer(Result.success("same-runtime"))
        assertTrue(replacement.onStarted())
        assertEquals(listOf("same-runtime"), newAccepted)
    }

    @Test fun repeatedStartsAndDuplicateCompletionDoNotRecreateSessions() {
        var acceptCount = 0
        val delivery = StartedRuntimeDelivery<String> { acceptCount++; true }
        delivery.offer(Result.success("first"))
        delivery.offer(Result.success("duplicate"))
        assertTrue(delivery.onStarted())
        delivery.onStopped()
        assertFalse(delivery.onStarted()) // Activity can rebind foreground services separately.
        delivery.offer(Result.success("late duplicate"))
        assertEquals(1, acceptCount)
    }

    @Test fun initializationFailureIsDeliveredInsteadOfLeavingStartingScreen() {
        val error = IllegalStateException("engine initialization failed")
        var observed: Throwable? = null
        val delivery = StartedRuntimeDelivery<String> { observed = it.exceptionOrNull(); true }
        delivery.offer(Result.failure(error))
        assertTrue(delivery.onStarted())
        assertSame(error, observed)
    }

    @Test fun reentrantStartCannotConsumeTheSameCompletionTwice() {
        var count = 0
        lateinit var delivery: StartedRuntimeDelivery<String>
        delivery = StartedRuntimeDelivery {
            count++
            assertFalse(delivery.onStarted())
            true
        }
        delivery.offer(Result.success("ready"))
        assertTrue(delivery.onStarted())
        assertEquals(1, count)
    }
}
