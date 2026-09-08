/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.ui

import java.util.Locale
import org.navis.browser.api.FileCapture
import org.navis.browser.api.FilePickerMode
import org.navis.browser.api.FilePickerRequest

internal enum class CameraCaptureKind {
    IMAGE,
    VIDEO,
}

/** Selects direct camera capture only when content requested a single compatible media file. */
internal fun FilePickerRequest.cameraCaptureKind(): CameraCaptureKind? {
    if (
        mode != FilePickerMode.SINGLE ||
        (capture != FileCapture.USER && capture != FileCapture.ENVIRONMENT)
    ) {
        return null
    }
    val normalizedTypes = mimeTypes
        .map { it.substringBefore(';').trim().lowercase(Locale.ROOT) }
        .filter(String::isNotEmpty)
    return when {
        normalizedTypes.isEmpty() -> CameraCaptureKind.IMAGE
        normalizedTypes.all { it == "image/*" || it.startsWith("image/") } -> {
            CameraCaptureKind.IMAGE
        }
        normalizedTypes.all { it == "video/*" || it.startsWith("video/") } -> {
            CameraCaptureKind.VIDEO
        }
        else -> null
    }
}
