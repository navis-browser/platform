/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidPlatformRequestsPolicyTest {
    @Test
    fun cancelledPermissionResultIsDenied() {
        assertFalse(grantsAreGranted(emptyMap()))
    }

    @Test
    fun everyReturnedPermissionMustBeGranted() {
        assertTrue(grantsAreGranted(mapOf("camera" to true, "microphone" to true)))
        assertFalse(grantsAreGranted(mapOf("camera" to true, "microphone" to false)))
    }
}
