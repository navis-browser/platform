/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeResourceLedgerTest {
    @Test
    fun everyInitializationFailureReleasesOnlyAcquiredResourcesInReverseOrder() {
        val stages = listOf(
            "engine-runtime",
            "core",
            "profile",
            "target",
            "extensions",
            "window",
            "sessions",
        )

        for (failureIndex in 0..stages.size) {
            val released = mutableListOf<String>()
            val ledger = RuntimeResourceLedger()

            runCatching {
                stages.forEachIndexed { index, stage ->
                    if (index == failureIndex) {
                        error("injected failure at $stage")
                    }
                    ledger.own(stage) { released += stage }
                }
            }

            assertTrue(ledger.releaseAll().isEmpty())
            assertEquals(stages.take(failureIndex).reversed(), released)
            assertTrue("rollback must be idempotent", ledger.releaseAll().isEmpty())
            assertEquals(stages.take(failureIndex).reversed(), released)
        }
    }

    @Test
    fun oneBrokenCleanupDoesNotBlockEarlierResources() {
        val released = mutableListOf<String>()
        val ledger = RuntimeResourceLedger()
        ledger.own("core") { released += "core" }
        ledger.own("profile") { error("profile close failed") }
        ledger.own("target") { released += "target" }

        val failures = ledger.releaseAll()

        assertEquals(listOf("target", "core"), released)
        assertEquals(1, failures.size)
        assertEquals("profile", failures.single().resource)
        assertEquals("profile close failed", failures.single().cause.message)
    }

    @Test
    fun acquisitionAfterRollbackIsRejected() {
        val ledger = RuntimeResourceLedger()
        ledger.releaseAll()

        val failure = runCatching { ledger.own("late") {} }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
    }
}
