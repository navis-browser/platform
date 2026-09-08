/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.engine.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineProjectionMatrixTest {
    @Test
    fun firstDirectSliceAdvertisesOnlyImplementedProjections() {
        val matrix = EngineProjectionMatrix.DIRECT_RUNTIME_V1

        assertEquals(
            setOf(
                EngineProjection.NAVIGATION,
                EngineProjection.COMPOSITOR,
                EngineProjection.SESSION_STATE,
                EngineProjection.HISTORY_VISITS,
                EngineProjection.TARGET_PROMPTS,
                EngineProjection.SITE_PERMISSIONS,
                EngineProjection.PLATFORM_PERMISSIONS,
                EngineProjection.FILE_PICKER,
                EngineProjection.DOWNLOADS,
                EngineProjection.LOGIN_STORAGE,
                EngineProjection.EXTENSIONS,
                EngineProjection.ACCESSIBILITY,
                EngineProjection.INPUT_METHOD,
                EngineProjection.ASYNC_PAN_ZOOM,
                EngineProjection.FULLSCREEN,
                EngineProjection.NEW_WINDOW,
                EngineProjection.CONTENT_CRASH,
                EngineProjection.MEDIA_CAPTURE,
                EngineProjection.DEVELOPER_SETTINGS,
                EngineProjection.IN_APP_DEVTOOLS,
            ),
            matrix.available,
        )
        assertEquals(
            EngineProjection.entries.toSet() - matrix.available,
            matrix.unsupported,
        )
    }

    @Test
    fun everyProjectionHasAnExplicitAvailability() {
        val matrix = EngineProjectionMatrix.DIRECT_RUNTIME_V1

        EngineProjection.entries.forEach { projection ->
            assertTrue(
                matrix.availability(projection) in ProjectionAvailability.entries,
            )
        }
    }
}
