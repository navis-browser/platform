/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.engine

import org.junit.Assert.*
import org.junit.Test
import org.navis.browser.downloads.*

class DownloadActionPolicyTest {
    private fun entry(id: Long = 1, state: DownloadState = DownloadState.DOWNLOADING, privateMode: Boolean = false) =
        DownloadEntry(id, "fixture.bin", "https://example.test/file", privateMode, state,
            8, 64, state == DownloadState.COMPLETE, state in setOf(DownloadState.FAILED, DownloadState.CANCELLED),
            state in setOf(DownloadState.DOWNLOADING, DownloadState.PAUSED), "2026-09-05")

    @Test fun pauseWaitsForFutureAndObservedStateThenRejectsStalePauseClick() {
        val policy = DownloadActionPolicy(false)
        policy.observe(listOf(entry()))
        val ticket = checkNotNull(policy.begin(DownloadAction.PAUSE, 1))
        assertNull(policy.begin(DownloadAction.PAUSE, 1))
        assertNull(policy.begin(DownloadAction.CANCEL, 1))
        assertTrue(policy.complete(ticket, true))
        assertTrue(1L in policy.state().busyIds)
        assertNull(policy.begin(DownloadAction.RESUME, 1))
        policy.observe(listOf(entry(state = DownloadState.PAUSED)))
        assertTrue(policy.state().busyIds.isEmpty())
        assertNull(policy.begin(DownloadAction.PAUSE, 1))
        assertNotNull(policy.begin(DownloadAction.RESUME, 1))
    }

    @Test fun observedStateAloneDoesNotFinishAnOutstandingFuture() {
        val policy = DownloadActionPolicy(false)
        policy.observe(listOf(entry(state = DownloadState.PAUSED)))
        val ticket = checkNotNull(policy.begin(DownloadAction.RESUME, 1))
        policy.observe(listOf(entry()))
        assertTrue(1L in policy.state().busyIds)
        assertTrue(policy.complete(ticket, true))
        assertTrue(policy.state().busyIds.isEmpty())
        assertNull(policy.begin(DownloadAction.RESUME, 1))
    }

    @Test fun anotherOwnerMaySupersedeSuccessBeforeTheObserverSeesItsIntermediateState() {
        for ((action, initial) in listOf(
            DownloadAction.PAUSE to DownloadState.DOWNLOADING,
            DownloadAction.RESUME to DownloadState.PAUSED,
        )) {
            val policy = DownloadActionPolicy(false)
            policy.observe(listOf(entry(state = initial), entry(2, DownloadState.COMPLETE)))
            val ticket = checkNotNull(policy.begin(action, 1))
            // The extension has already undone the command, so observer coalescing never
            // exposes its intermediate state. An observation before the future is not enough.
            policy.observe(listOf(entry(state = initial), entry(2, DownloadState.COMPLETE)))
            assertFalse(policy.requiresSnapshot(ticket))
            policy.complete(ticket, true)
            assertTrue(policy.requiresSnapshot(ticket))
            assertTrue(1L in policy.state().busyIds)
            assertNull(policy.begin(action, 1))
            assertNull(policy.begin(DownloadAction.CLEAR_FINISHED))
            // DownloadsRepository samples current records on Main, including after refresh.
            policy.observe(listOf(entry(state = initial), entry(2, DownloadState.COMPLETE)))
            assertTrue(policy.state().busyIds.isEmpty())
            assertFalse(policy.requiresSnapshot(ticket))
            assertNotNull(policy.begin(action, 1))
            assertFalse(policy.complete(ticket, true))
        }
    }

    @Test fun itemOperationsMayRunInParallelButClearCannotOverlapThem() {
        val policy = DownloadActionPolicy(false)
        val values = listOf(entry(), entry(2), entry(3, DownloadState.COMPLETE))
        policy.observe(values)
        val first = checkNotNull(policy.begin(DownloadAction.PAUSE, 1))
        val second = checkNotNull(policy.begin(DownloadAction.CANCEL, 2))
        assertNull(policy.begin(DownloadAction.CLEAR_FINISHED))
        policy.complete(first, false); policy.complete(second, false)
        val clear = checkNotNull(policy.begin(DownloadAction.CLEAR_FINISHED))
        assertTrue(policy.state().clearing)
        assertNull(policy.begin(DownloadAction.OPEN, 3))
        assertNull(policy.begin(DownloadAction.PAUSE, 1))
        assertNull(policy.begin(DownloadAction.CLEAR_FINISHED))
        policy.complete(clear, true)
        assertTrue(policy.state().clearing)
        // A download completing after Clear began is not part of that operation's snapshot.
        policy.observe(listOf(entry(state = DownloadState.COMPLETE), entry(2)))
        assertFalse(policy.state().clearing)
        assertTrue(policy.state().canClear)
    }

    @Test fun rejectionAllowsRetryButDuplicateOrOldCompletionCannotUnlockNewAction() {
        val policy = DownloadActionPolicy(false)
        policy.observe(listOf(entry()))
        val first = checkNotNull(policy.begin(DownloadAction.PAUSE, 1))
        assertTrue(policy.complete(first, false))
        val second = checkNotNull(policy.begin(DownloadAction.PAUSE, 1))
        assertFalse(policy.complete(first, true))
        assertTrue(1L in policy.state().busyIds)
        assertTrue(policy.complete(second, true))
        assertFalse(policy.complete(second, false))
        assertTrue(1L in policy.state().busyIds)
    }

    @Test fun closeAndPrivateModeReplacementRejectLateCallbacks() {
        val normal = DownloadActionPolicy(false)
        normal.observe(listOf(entry(), entry(2, privateMode = true)))
        assertNull(normal.begin(DownloadAction.PAUSE, 2))
        val old = checkNotNull(normal.begin(DownloadAction.CANCEL, 1))
        normal.close()
        assertFalse(normal.complete(old, false))
        assertFalse(normal.observe(listOf(entry())))
        assertNull(normal.begin(DownloadAction.PAUSE, 1))
        val private = DownloadActionPolicy(true)
        private.observe(listOf(entry(), entry(2, privateMode = true)))
        assertNull(private.begin(DownloadAction.PAUSE, 1))
        assertNotNull(private.begin(DownloadAction.PAUSE, 2))
        assertFalse(private.complete(old, true))
        assertTrue(2L in private.state().busyIds)
    }

    @Test fun retryAndRemoveWaitForOriginalRecordRemoval() {
        for (action in listOf(DownloadAction.RETRY, DownloadAction.REMOVE)) {
            val policy = DownloadActionPolicy(false)
            policy.observe(listOf(entry(state = DownloadState.FAILED)))
            val ticket = checkNotNull(policy.begin(action, 1))
            policy.complete(ticket, true)
            assertTrue(1L in policy.state().busyIds)
            policy.observe(listOf(entry(2)))
            assertTrue(policy.state().busyIds.isEmpty())
        }
    }

    @Test fun terminalCompletionWhilePauseOrCancelIsPendingAlsoAcknowledgesState() {
        for (action in listOf(DownloadAction.PAUSE, DownloadAction.CANCEL)) {
            val policy = DownloadActionPolicy(false)
            policy.observe(listOf(entry()))
            val ticket = checkNotNull(policy.begin(action, 1))
            policy.observe(listOf(entry(state = DownloadState.COMPLETE)))
            policy.complete(ticket, true)
            assertTrue(policy.state().busyIds.isEmpty())
        }
    }

    @Test fun openUsesRealOperationCompletionWithoutRequiringHistoryMutation() {
        val policy = DownloadActionPolicy(false)
        policy.observe(listOf(entry(state = DownloadState.COMPLETE)))
        var calls = 0
        fun invoke() = policy.begin(DownloadAction.OPEN, 1)?.also { calls++ }
        val ticket = checkNotNull(invoke())
        assertNull(invoke())
        assertEquals(1, calls)
        assertTrue(policy.complete(ticket, true))
        assertTrue(policy.state().busyIds.isEmpty())
    }
}
