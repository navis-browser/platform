/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.navis.browser.api.FileCapture
import org.navis.browser.api.FilePickerMode
import org.navis.browser.api.FilePickerRequest
import org.navis.browser.api.SessionId
import org.navis.browser.api.TargetRequestId

class FileCapturePolicyTest {
    @Test
    fun usesCameraForExplicitImageFacingMode() {
        assertEquals(
            CameraCaptureKind.IMAGE,
            request(FileCapture.USER, listOf("image/jpeg")).cameraCaptureKind(),
        )
        assertEquals(
            CameraCaptureKind.IMAGE,
            request(FileCapture.ENVIRONMENT, emptyList()).cameraCaptureKind(),
        )
    }

    @Test
    fun usesCameraForExplicitVideoFacingMode() {
        assertEquals(
            CameraCaptureKind.VIDEO,
            request(FileCapture.ENVIRONMENT, listOf("video/mp4")).cameraCaptureKind(),
        )
    }

    @Test
    fun keepsAmbiguousAndMultiSelectionOnSystemPicker() {
        assertNull(request(FileCapture.ANY, listOf("image/*")).cameraCaptureKind())
        assertNull(
            request(
                capture = FileCapture.USER,
                mimeTypes = listOf("image/*", "video/*"),
            ).cameraCaptureKind(),
        )
        assertNull(
            request(
                capture = FileCapture.USER,
                mimeTypes = listOf("image/*"),
                mode = FilePickerMode.MULTIPLE,
            ).cameraCaptureKind(),
        )
    }

    private fun request(
        capture: FileCapture,
        mimeTypes: List<String>,
        mode: FilePickerMode = FilePickerMode.SINGLE,
    ) = FilePickerRequest(
        id = TargetRequestId(1),
        sessionId = SessionId(2),
        mode = mode,
        capture = capture,
        mimeTypes = mimeTypes,
        privateMode = false,
    )
}
