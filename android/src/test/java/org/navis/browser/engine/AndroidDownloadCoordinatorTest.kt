/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.engine

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.navis.browser.api.SessionId
import org.navis.browser.engine.runtime.EngineDownloadObserver
import org.navis.browser.engine.runtime.EngineDownloadPort
import org.navis.browser.engine.runtime.EngineDownloadResponse
import org.navis.browser.engine.runtime.EngineProjection
import org.navis.browser.engine.runtime.EngineProjectionMatrix

class AndroidDownloadCoordinatorTest {
    @Test(expected = IllegalArgumentException::class)
    fun availableProjectionCannotExistWithoutAnEnginePort() {
        AndroidDownloadCoordinator(
            EngineProjectionMatrix.fromAvailable(EngineProjection.DOWNLOADS),
            enginePort = null,
            acceptResponse = {},
        )
    }

    @Test
    fun availableResponseTransfersOwnershipToTheStore() {
        val port = FakePort()
        val accepted = mutableListOf<EngineDownloadResponse>()
        AndroidDownloadCoordinator(
            EngineProjectionMatrix.fromAvailable(EngineProjection.DOWNLOADS),
            port,
            accepted::add,
        )
        val response = response()

        port.observer.onDownload(response)

        assertEquals(listOf(response), accepted)
        assertEquals(0, (response.body as CloseTrackingInputStream).closeCount)
    }

    @Test
    fun unsupportedOrFailedHandoffClosesTheBody() {
        val unsupportedPort = FakePort()
        AndroidDownloadCoordinator(
            EngineProjectionMatrix.fromAvailable(),
            unsupportedPort,
            acceptResponse = { error("must not be called") },
        )
        val unsupported = response()
        unsupportedPort.observer.onDownload(unsupported)
        assertEquals(1, (unsupported.body as CloseTrackingInputStream).closeCount)

        val failingPort = FakePort()
        AndroidDownloadCoordinator(
            EngineProjectionMatrix.fromAvailable(EngineProjection.DOWNLOADS),
            failingPort,
            acceptResponse = { throw ExpectedFailure() },
        )
        val failed = response()
        runCatching { failingPort.observer.onDownload(failed) }
        assertEquals(1, (failed.body as CloseTrackingInputStream).closeCount)
    }

    @Test
    fun closeUnbindsAndLateDeliveryIsRejected() {
        val port = FakePort()
        val coordinator = AndroidDownloadCoordinator(
            EngineProjectionMatrix.fromAvailable(EngineProjection.DOWNLOADS),
            port,
            acceptResponse = {},
        )
        val boundObserver = port.observer

        coordinator.close()
        val response = response()
        boundObserver.onDownload(response)

        assertTrue(port.unbound)
        assertEquals(1, (response.body as CloseTrackingInputStream).closeCount)
    }

    private fun response() = EngineDownloadResponse(
        sessionId = SessionId(7),
        uri = "https://example.com/archive.bin",
        suggestedFileName = "archive.bin",
        contentType = "application/octet-stream",
        contentDisposition = null,
        contentLength = 1,
        privateMode = false,
        body = CloseTrackingInputStream(),
    )

    private class CloseTrackingInputStream : ByteArrayInputStream(byteArrayOf(1)) {
        var closeCount = 0

        override fun close() {
            closeCount += 1
            super.close()
        }
    }

    private class FakePort : EngineDownloadPort {
        lateinit var observer: EngineDownloadObserver
        var unbound = false

        override fun bind(observer: EngineDownloadObserver) {
            this.observer = observer
        }

        override fun unbind(observer: EngineDownloadObserver) {
            assertSame(this.observer, observer)
            unbound = true
        }
    }

    private class ExpectedFailure : RuntimeException()
}
