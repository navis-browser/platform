/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.ui

import android.Manifest
import org.navis.browser.api.PlatformPermission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidPlatformRequestsPolicyTest {
    @Test
    fun cancelledPermissionResultIsDenied() {
        assertFalse(grantsAreGranted(emptyMap(), setOf("camera")))
    }

    @Test
    fun everyReturnedPermissionMustBeGranted() {
        assertTrue(grantsAreGranted(mapOf("camera" to true, "microphone" to true), setOf("camera", "microphone")))
        assertFalse(grantsAreGranted(mapOf("camera" to true, "microphone" to false), setOf("camera", "microphone")))
    }
    @Test
    fun partialOrForeignResultsCannotGrantTheRequest() {
        assertFalse(grantsAreGranted(mapOf("camera" to true), setOf("camera", "microphone")))
        assertFalse(grantsAreGranted(mapOf("camera" to true, "microphone" to true), setOf("camera")))
        assertFalse(grantsAreGranted(mapOf("microphone" to true), setOf("camera")))
        assertFalse(grantsAreGranted(emptyMap(), emptySet()))
    }

    @Test
    fun retainedPermissionsUseNativeAndroidNames() {
        assertEquals(listOf(Manifest.permission.CAMERA), PlatformPermission.CAMERA.androidNames())
        assertEquals(listOf(Manifest.permission.RECORD_AUDIO), PlatformPermission.MICROPHONE.androidNames())
        assertEquals(listOf(Manifest.permission.ACCESS_COARSE_LOCATION), PlatformPermission.COARSE_LOCATION.androidNames())
        assertEquals(listOf(Manifest.permission.ACCESS_FINE_LOCATION), PlatformPermission.FINE_LOCATION.androidNames())
    }
}
