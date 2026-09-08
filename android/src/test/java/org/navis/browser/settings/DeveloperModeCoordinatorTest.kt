/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

class DeveloperModeCoordinatorTest {
    @Test
    fun unchangedValueDoesNotTouchEnginePersistenceOrUi() {
        val fixture = Fixture(initialValue = false)

        val result = fixture.coordinator.setEnabled(false).toCompletableFuture().join()

        assertEquals(DeveloperModeChange.UNCHANGED, result)
        assertTrue(fixture.engineValues.isEmpty())
        assertTrue(fixture.persistedValues.isEmpty())
        assertTrue(fixture.publishedValues.isEmpty())
    }

    @Test
    fun rejectedLiveProjectionDoesNotPersistOrPublishTheRequestedValue() {
        val fixture = Fixture(initialValue = false, engineAccepts = false)

        val result = fixture.coordinator.setEnabled(true).toCompletableFuture().join()

        assertEquals(DeveloperModeChange.ENGINE_UNAVAILABLE, result)
        assertEquals(listOf(true), fixture.engineValues)
        assertFalse(fixture.value)
        assertTrue(fixture.persistedValues.isEmpty())
        assertTrue(fixture.publishedValues.isEmpty())
    }

    @Test
    fun acceptedTransitionPersistsBeforePublishingTheNewUiState() {
        val events = mutableListOf<String>()
        var value = false
        val coordinator = DeveloperModeCoordinator(
            currentValue = { value },
            applyToEngine = {
                events += "engine:$it"
                CompletableFuture.completedFuture(Unit)
            },
            persistValue = {
                value = it
                events += "persist:$it"
            },
            onApplied = {
                events += "publish:$it:$value"
            },
        )

        val result = coordinator.setEnabled(true).toCompletableFuture().join()

        assertEquals(DeveloperModeChange.APPLIED, result)
        assertEquals(
            listOf("engine:true", "persist:true", "publish:true:true"),
            events,
        )
    }

    @Test
    fun transitionDoesNotPersistWhileTheEngineTransactionIsPending() {
        val engine = CompletableFuture<Unit>()
        val events = mutableListOf<String>()
        var value = false
        val coordinator = DeveloperModeCoordinator(
            currentValue = { value },
            applyToEngine = {
                events += "engine:$it"
                engine
            },
            persistValue = {
                value = it
                events += "persist:$it"
            },
            onApplied = { events += "publish:$it" },
        )

        val transition = coordinator.setEnabled(true).toCompletableFuture()

        assertFalse(transition.isDone)
        assertFalse(value)
        assertEquals(listOf("engine:true"), events)

        engine.complete(Unit)
        assertEquals(DeveloperModeChange.APPLIED, transition.join())
        assertEquals(listOf("engine:true", "persist:true", "publish:true"), events)
    }

    @Test
    fun productCommitFailureRollsTheEngineAndDurableStateBackBeforeCompleting() {
        val events = mutableListOf<String>()
        val rollback = CompletableFuture<Unit>()
        var value = false
        val coordinator = DeveloperModeCoordinator(
            currentValue = { value },
            applyToEngine = {
                events += "engine:$it"
                if (it) CompletableFuture.completedFuture(Unit) else rollback
            },
            persistValue = {
                value = it
                events += "persist:$it"
            },
            onApplied = {
                events += "publish:$it"
                if (it) throw IllegalStateException("publish failed")
            },
        )

        val transition = coordinator.setEnabled(true).toCompletableFuture()

        assertFalse(transition.isDone)
        assertEquals(
            listOf("engine:true", "persist:true", "publish:true", "engine:false"),
            events,
        )

        rollback.complete(Unit)
        val error = try {
            transition.join()
            throw AssertionError("Expected the failed product commit to remain observable")
        } catch (error: CompletionException) {
            error.cause
        }

        assertEquals("publish failed", error?.message)
        assertFalse(value)
        assertEquals(
            listOf(
                "engine:true",
                "persist:true",
                "publish:true",
                "engine:false",
                "persist:false",
                "publish:false",
            ),
            events,
        )
    }

    private class Fixture(
        initialValue: Boolean,
        private val engineAccepts: Boolean = true,
    ) {
        var value = initialValue
        val engineValues = mutableListOf<Boolean>()
        val persistedValues = mutableListOf<Boolean>()
        val publishedValues = mutableListOf<Boolean>()
        val coordinator = DeveloperModeCoordinator(
            currentValue = { value },
            applyToEngine = {
                engineValues += it
                if (engineAccepts) {
                    CompletableFuture.completedFuture(Unit)
                } else {
                    CompletableFuture<Unit>().also { result ->
                        result.completeExceptionally(IllegalStateException("rejected"))
                    }
                }
            },
            persistValue = {
                value = it
                persistedValues += it
            },
            onApplied = { publishedValues += it },
        )
    }
}
