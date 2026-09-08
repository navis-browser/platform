/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.engine.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EngineBrowsingDataPortTest {
    @Test
    fun productRequestPreservesBoundedIdentityTypeRangeAndPrivacy() {
        val request = EngineProductDataClearRequest(
            extensionId = "test@example.invalid",
            dataType = EngineProductDataType.HISTORY,
            sinceUnixMillis = 1234L,
            privateMode = true,
        )

        assertEquals("test@example.invalid", request.extensionId)
        assertEquals(EngineProductDataType.HISTORY, request.dataType)
        assertEquals(1234L, request.sinceUnixMillis)
        assertEquals(true, request.privateMode)
    }

    @Test
    fun invalidIdentityAndTimestampFailClosed() {
        assertThrows(IllegalArgumentException::class.java) {
            EngineProductDataClearRequest(
                extensionId = "",
                dataType = EngineProductDataType.PASSWORDS,
                sinceUnixMillis = 0L,
                privateMode = false,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            EngineProductDataClearRequest(
                extensionId = "test@example.invalid",
                dataType = EngineProductDataType.DOWNLOADS,
                sinceUnixMillis = -1L,
                privateMode = false,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            EngineProductDataClearRequest(
                extensionId = "test@example.invalid",
                dataType = EngineProductDataType.DOWNLOADS,
                sinceUnixMillis = 9_007_199_254_740_992L,
                privateMode = false,
            )
        }
    }
}
