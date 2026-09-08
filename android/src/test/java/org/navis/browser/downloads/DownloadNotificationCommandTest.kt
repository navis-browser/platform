/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.downloads

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import org.junit.Assert.*
import org.junit.Test

class DownloadNotificationCommandTest {
    private val profile = "23a45103-8cd4-41ec-8219-6a8d31090710"
    private val other = "6ff018a7-2a22-4b00-a390-4fd7a0d404ba"

    private fun command(privateMode: Boolean = false, openFile: Boolean = true) =
        DownloadNotificationCommand(profile, 7, privateMode, openFile)

    private class Repository : DownloadManager {
        var entry: DownloadEntry? = null
        var finds = 0
        val opened = mutableListOf<Pair<Long, Boolean>>()
        var result = CompletableFuture.completedFuture(Unit)
        var throwOnOpen = false
        override fun find(id: Long, privateMode: Boolean): DownloadEntry? {
            finds++
            return entry?.takeIf { it.id == id && it.privateMode == privateMode }
        }
        override fun open(id: Long, privateMode: Boolean): CompletionStage<Unit> {
            opened += id to privateMode
            if (throwOnOpen) throw IllegalStateException("file unavailable")
            return result
        }
        override fun addObserver(privateMode: Boolean, observer: DownloadObserver) = Unit
        override fun removeObserver(observer: DownloadObserver) = Unit
        override fun refresh() = Unit
        override fun cancel(id: Long, privateMode: Boolean) = error("not part of notification handling")
        override fun pause(id: Long, privateMode: Boolean) = error("not part of notification handling")
        override fun resume(id: Long, privateMode: Boolean) = error("not part of notification handling")
        override fun retry(id: Long, privateMode: Boolean) = error("not part of notification handling")
        override fun remove(id: Long, privateMode: Boolean) = error("not part of notification handling")
        override fun clearFinished(privateMode: Boolean) = error("not part of notification handling")
    }

    private inner class Fixture(privateMode: Boolean = false) {
        val repository = Repository().apply {
            entry = DownloadEntry(7, "only-this-fixture.txt", "https://fixture.invalid/file", privateMode,
                DownloadState.COMPLETE, 12, 12, true, false, false, "2026-09-06")
        }
        var current = true
        var failures = 0
        val shown = mutableListOf<Boolean>()
        val pendingUi = ArrayDeque<() -> Unit>()
        val delivery = DownloadNotificationDelivery(profile, repository, pendingUi::addLast,
            { current }, shown::add, { failures++ })
        fun drain() { while (pendingUi.isNotEmpty()) pendingUi.removeFirst().invoke() }
    }

    @Test fun identityIsCanonicalUniqueAndContainsNoFileUri() {
        val values = listOf(command(), command().copy(profileId = other), command().copy(downloadId = 8),
            command(privateMode = true), command(openFile = false))
        assertEquals(values.size, values.map { it.identity }.toSet().size)
        values.forEach { assertEquals(it, DownloadNotificationCommand.parse(it.identity)) }
        val valid = command().identity
        listOf(null, "content://media/external_primary/downloads/7", "file:///sdcard/Download/file.txt",
            "$valid?uri=content://other", "$valid#anything", valid.replace("/7/", "/07/"),
            valid.replace("/7/", "/0/"), valid.replace("/7/", "/2147483648/"),
            valid.replace("/7/", "/%37/"), valid.replace(profile, "unknown"),
            valid.replace(profile, "user@$profile"), valid.replace(profile, "$profile:443"),
            valid.replace("normal", "incognito"), "$valid/", valid.replace("41ec", "51ec"))
            .forEach { assertNull(it, DownloadNotificationCommand.parse(it)) }
    }

    @Test fun completionUsesOnlyTheLiveRepositoryRecord() {
        val fixture = Fixture()
        fixture.delivery.deliver(command())
        assertEquals(listOf(7L to false), fixture.repository.opened)
        assertTrue(fixture.shown.isEmpty())
        assertEquals(0, fixture.failures)
    }

    @Test fun listActionsPreserveTheRecordModeAndNeverOpenAFile() {
        for (privateMode in listOf(false, true)) {
            val fixture = Fixture(privateMode)
            fixture.delivery.deliver(command(privateMode, openFile = false))
            assertEquals(listOf(privateMode), fixture.shown)
            assertTrue(fixture.repository.opened.isEmpty())
            assertEquals(0, fixture.failures)
        }
    }

    @Test fun expiredPrivateOrWrongModeCannotRecreatePrivateBrowsingOrOpenAFile() {
        for (privateMode in listOf(false, true)) {
            val fixture = Fixture(privateMode)
            fixture.delivery.deliver(command(!privateMode))
            assertTrue(fixture.repository.opened.isEmpty())
            assertEquals(listOf(false), fixture.shown)
            assertEquals(1, fixture.failures)
        }
        val fixture = Fixture(true)
        fixture.repository.entry = null
        fixture.delivery.deliver(command(true))
        assertTrue(fixture.repository.opened.isEmpty())
        assertEquals(listOf(false), fixture.shown)
    }

    @Test fun wrongProfileNeverEvenQueriesAnotherProfilesDownloads() {
        val fixture = Fixture()
        fixture.delivery.deliver(command().copy(profileId = other))
        assertEquals(0, fixture.repository.finds)
        assertTrue(fixture.repository.opened.isEmpty())
        assertTrue(fixture.shown.isEmpty())
        assertEquals(1, fixture.failures)
    }

    @Test fun missingFileOrHandlerHasAnExplicitSameModeListFallback() {
        for (privateMode in listOf(false, true)) {
            val fixture = Fixture(privateMode)
            fixture.repository.result = CompletableFuture.failedFuture(IllegalStateException("no handler"))
            fixture.delivery.deliver(command(privateMode))
            assertTrue(fixture.shown.isEmpty())
            fixture.drain()
            assertEquals(1, fixture.failures)
            assertEquals(listOf(privateMode), fixture.shown)
        }
    }

    @Test fun privateExpiryDuringAsyncOpenIsRecheckedBeforeFallback() {
        val fixture = Fixture(true)
        fixture.repository.result = CompletableFuture()
        fixture.delivery.deliver(command(true))
        fixture.repository.entry = null
        fixture.repository.result.completeExceptionally(IllegalStateException("session ended"))
        fixture.drain()
        assertEquals(1, fixture.failures)
        assertEquals(listOf(false), fixture.shown)
    }

    @Test fun retiredActivityCannotLaunchOrReceiveDelayedFallbackUi() {
        val fixture = Fixture()
        fixture.current = false
        fixture.delivery.deliver(command())
        assertEquals(0, fixture.repository.finds)
        fixture.current = true
        fixture.repository.result = CompletableFuture()
        fixture.delivery.deliver(command())
        fixture.current = false
        fixture.repository.result.completeExceptionally(IllegalStateException("activity closed"))
        fixture.drain()
        assertEquals(0, fixture.failures)
        assertTrue(fixture.shown.isEmpty())
    }

    @Test fun synchronousOpenFailureAlsoReportsAndFallsBack() {
        val fixture = Fixture()
        fixture.repository.throwOnOpen = true
        fixture.delivery.deliver(command())
        assertEquals(1, fixture.failures)
        assertEquals(listOf(false), fixture.shown)
    }
}
