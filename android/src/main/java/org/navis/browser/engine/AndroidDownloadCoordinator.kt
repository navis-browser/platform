/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.engine

import org.navis.browser.engine.runtime.EngineDownloadObserver
import org.navis.browser.engine.runtime.EngineDownloadPort
import org.navis.browser.engine.runtime.EngineDownloadResponse
import org.navis.browser.engine.runtime.EngineProjection
import org.navis.browser.engine.runtime.EngineProjectionMatrix
import org.navis.browser.engine.runtime.ProjectionAvailability

/** Owns the handoff from one engine response body to the Android download store. */
internal class AndroidDownloadCoordinator(
    projections: EngineProjectionMatrix,
    private val enginePort: EngineDownloadPort?,
    private val acceptResponse: (EngineDownloadResponse) -> Unit,
) : EngineDownloadObserver, AutoCloseable {
    private val available = projections.availability(EngineProjection.DOWNLOADS) ==
        ProjectionAvailability.AVAILABLE
    private var closed = false

    init {
        require(enginePort != null || !available) {
            "Available DOWNLOADS projection requires an EngineDownloadPort"
        }
        enginePort?.bind(this)
    }

    override fun onDownload(response: EngineDownloadResponse) {
        if (closed || !available) {
            response.close()
            return
        }
        try {
            acceptResponse(response)
        } catch (error: Throwable) {
            response.close()
            throw error
        }
    }

    override fun close() {
        if (closed) {
            return
        }
        closed = true
        enginePort?.unbind(this)
    }
}
