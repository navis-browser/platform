/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.ui

import android.Manifest
import org.navis.browser.api.PlatformPermission

internal fun PlatformPermission.androidNames(): List<String> = when (this) {
    PlatformPermission.CAMERA -> listOf(Manifest.permission.CAMERA)
    PlatformPermission.MICROPHONE -> listOf(Manifest.permission.RECORD_AUDIO)
    PlatformPermission.COARSE_LOCATION -> listOf(Manifest.permission.ACCESS_COARSE_LOCATION)
    PlatformPermission.FINE_LOCATION -> listOf(Manifest.permission.ACCESS_FINE_LOCATION)
}

// Empty, partial or foreign results cannot establish the requested OS grant.
internal fun grantsAreGranted(grants: Map<String, Boolean>, expected: Set<String>): Boolean =
    expected.isNotEmpty() && grants.keys == expected && grants.values.all { it }
