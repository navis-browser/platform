/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.engine

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.navis.browser.api.SessionId

class NewWindowCoordinatorTest {
    private val token = "00000000000000000000000000000000"

    @Test
    fun childIsCreatedUnpublishedAndOpenedBeforeAcceptance() {
        val fixture = Fixture()
        val engine = CompletableFuture<Boolean>()

        val result = fixture.coordinator.request(
            SessionId(1),
            token,
            createChild = {
                fixture.events += "create-unpublished"
                Child(2)
            },
            openChild = { child ->
                fixture.events += "open:${child.id}"
                child.open = true
                engine
            },
        )

        assertFalse(result.toCompletableFuture().isDone)
        engine.complete(true)

        assertTrue(result.toCompletableFuture().join())
        assertEquals(
            listOf("create-unpublished", "open:2", "accept:2"),
            fixture.events,
        )
        assertTrue(fixture.scheduler.tasks.single().cancelled)
    }

    @Test
    fun rejectedOrDeadChildNeverPublishes() {
        val fixture = Fixture()
        val engine = CompletableFuture<Boolean>()
        lateinit var child: Child
        val result = fixture.coordinator.request(
            SessionId(1),
            token,
            createChild = { Child(2).also { child = it } },
            openChild = {
                it.open = true
                engine
            },
        )
        child.open = false

        engine.complete(true)

        assertFalse(result.toCompletableFuture().join())
        assertEquals(listOf("reject:2"), fixture.events)
    }

    @Test
    fun openerCloseCancelsChildAndLateSuccessIsStale() {
        val fixture = Fixture()
        val engine = CompletableFuture<Boolean>()
        val result = fixture.coordinator.request(
            SessionId(1),
            token,
            createChild = { Child(2) },
            openChild = {
                it.open = true
                engine
            },
        )

        fixture.coordinator.cancelForOpener(SessionId(1))
        engine.complete(true)

        assertFalse(result.toCompletableFuture().join())
        assertEquals(listOf("reject:2"), fixture.events)
    }

    @Test
    fun productDeadlineWinsBeforeTheEngineDeadline() {
        val fixture = Fixture()
        val engine = CompletableFuture<Boolean>()
        val result = fixture.coordinator.request(
            SessionId(1),
            token,
            createChild = { Child(2) },
            openChild = {
                it.open = true
                engine
            },
        )

        assertEquals(8_000L, fixture.scheduler.tasks.single().delayMillis)
        fixture.scheduler.fire()
        engine.complete(true)

        assertFalse(result.toCompletableFuture().join())
        assertEquals(listOf("reject:2"), fixture.events)
    }

    @Test
    fun duplicateAndMalformedTokensFailBeforeCreatingAChild() {
        val fixture = Fixture()
        val firstEngine = CompletableFuture<Boolean>()
        var creations = 0
        fixture.coordinator.request(
            SessionId(1),
            token,
            createChild = { Child(++creations) },
            openChild = {
                it.open = true
                firstEngine
            },
        )

        val duplicate = fixture.coordinator.request(
            SessionId(1),
            token,
            createChild = { Child(++creations) },
            openChild = { CompletableFuture.completedFuture(true) },
        )
        val malformed = fixture.coordinator.request(
            SessionId(1),
            "not-a-token",
            createChild = { Child(++creations) },
            openChild = { CompletableFuture.completedFuture(true) },
        )

        assertFalse(duplicate.toCompletableFuture().join())
        assertFalse(malformed.toCompletableFuture().join())
        assertEquals(1, creations)
    }

    @Test
    fun engineExceptionRetiresTheChildAndPropagatesFailure() {
        val fixture = Fixture()
        val failure = IllegalStateException("native child failed")
        val result = fixture.coordinator.request(
            SessionId(1),
            token,
            createChild = { Child(2) },
            openChild = { throw failure },
        )

        val observed = runCatching { result.toCompletableFuture().join() }.exceptionOrNull()

        assertTrue(observed is CompletionException)
        assertEquals(failure, observed?.cause)
        assertEquals(listOf("reject:2"), fixture.events)
    }

    private class Child(val id: Int) {
        var open = false
    }

    private class Fixture {
        val scheduler = FakeScheduler()
        val events = mutableListOf<String>()
        val liveOpeners = mutableSetOf(SessionId(1))
        val coordinator = NewWindowCoordinator(
            isOpenerLive = liveOpeners::contains,
            isChildLive = Child::open,
            scheduler = scheduler,
            onAccepted = { events += "accept:${it.id}" },
            onRejected = { events += "reject:${it.id}" },
        )
    }

    private class FakeScheduler : NewWindowDeadlineScheduler {
        data class Task(
            val delayMillis: Long,
            val action: () -> Unit,
            var cancelled: Boolean = false,
        )

        val tasks = mutableListOf<Task>()

        override fun schedule(delayMillis: Long, action: () -> Unit): NewWindowDeadline {
            val task = Task(delayMillis, action)
            tasks += task
            return NewWindowDeadline { task.cancelled = true }
        }

        fun fire() {
            tasks.first { !it.cancelled }.action()
        }
    }
}
