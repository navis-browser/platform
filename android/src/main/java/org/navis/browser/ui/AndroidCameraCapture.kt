/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import org.navis.browser.api.FileCapture
import org.navis.browser.api.FilePickerRequest
import org.navis.browser.api.TargetRequestId

/** Owns camera output while a Gecko file prompt is waiting for its Android result. */
internal class AndroidCameraCapture(context: Context) {
    private val applicationContext = context.applicationContext
    private val cleanupHandler = Handler(Looper.getMainLooper())
    private val captureDirectory by lazy(::prepareCaptureDirectory)

    fun prepare(request: FilePickerRequest): Result<PendingCameraCapture> = runCatching {
        val kind = requireNotNull(request.cameraCaptureKind()) {
            "File request is not eligible for direct camera capture"
        }
        val suffix = when (kind) {
            CameraCaptureKind.IMAGE -> ".jpg"
            CameraCaptureKind.VIDEO -> ".mp4"
        }
        val file = File.createTempFile(CAPTURE_PREFIX, suffix, captureDirectory)
        try {
            val uri = FileProvider.getUriForFile(
                applicationContext,
                "${applicationContext.packageName}$FILE_PROVIDER_SUFFIX",
                file,
            )
            val intent = Intent(
                when (kind) {
                    CameraCaptureKind.IMAGE -> MediaStore.ACTION_IMAGE_CAPTURE
                    CameraCaptureKind.VIDEO -> MediaStore.ACTION_VIDEO_CAPTURE
                },
            ).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, uri)
                clipData = ClipData.newRawUri(CAPTURE_CLIP_LABEL, uri)
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
                addFacingHints(request.capture)
            }
            PendingCameraCapture(
                requestId = request.id,
                intent = intent,
                uri = uri,
                file = file,
                applicationContext = applicationContext,
                cleanupHandler = cleanupHandler,
            )
        } catch (error: Throwable) {
            file.delete()
            throw error
        }
    }

    private fun prepareCaptureDirectory(): File {
        val directory = File(applicationContext.cacheDir, CAPTURE_DIRECTORY)
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("Could not create camera capture directory")
        }
        val oldestAllowed = System.currentTimeMillis() - STALE_CAPTURE_AGE_MS
        directory.listFiles().orEmpty()
            .filter { file ->
                file.isFile &&
                    file.name.startsWith(CAPTURE_PREFIX) &&
                    file.lastModified() < oldestAllowed
            }
            .forEach(File::delete)
        return directory
    }

    internal class PendingCameraCapture(
        val requestId: TargetRequestId,
        val intent: Intent,
        val uri: Uri,
        private val file: File,
        private val applicationContext: Context,
        private val cleanupHandler: Handler,
    ) {
        private val settled = AtomicBoolean(false)

        fun complete(success: Boolean): String? {
            if (!settled.compareAndSet(false, true)) {
                return null
            }
            revokeCameraGrant()
            if (!success || !file.isFile || file.length() <= 0L) {
                file.delete()
                return null
            }
            cleanupHandler.postDelayed(
                { file.delete() },
                HANDED_OFF_RETENTION_MS,
            )
            return uri.toString()
        }

        fun cancel() {
            if (!settled.compareAndSet(false, true)) {
                return
            }
            revokeCameraGrant()
            file.delete()
        }

        private fun revokeCameraGrant() {
            runCatching {
                applicationContext.revokeUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
    }

    private companion object {
        const val CAPTURE_DIRECTORY = "navis-camera-captures"
        const val CAPTURE_PREFIX = "capture-"
        const val CAPTURE_CLIP_LABEL = "Navis camera capture"
        const val FILE_PROVIDER_SUFFIX = ".fileprovider"
        const val HANDED_OFF_RETENTION_MS = 60L * 60L * 1_000L
        const val STALE_CAPTURE_AGE_MS = 24L * 60L * 60L * 1_000L

        const val CAMERA_FACING = "android.intent.extras.CAMERA_FACING"
        const val LENS_FACING_FRONT = "android.intent.extras.LENS_FACING_FRONT"
        const val USE_FRONT_CAMERA = "android.intent.extra.USE_FRONT_CAMERA"
        const val LENS_FACING_BACK = "android.intent.extras.LENS_FACING_BACK"
        const val USE_BACK_CAMERA = "android.intent.extra.USE_BACK_CAMERA"

        fun Intent.addFacingHints(capture: FileCapture) {
            when (capture) {
                FileCapture.USER -> {
                    putExtra(CAMERA_FACING, 1)
                    putExtra(LENS_FACING_FRONT, 1)
                    putExtra(USE_FRONT_CAMERA, true)
                }
                FileCapture.ENVIRONMENT -> {
                    putExtra(CAMERA_FACING, 0)
                    putExtra(LENS_FACING_BACK, 1)
                    putExtra(USE_BACK_CAMERA, true)
                }
                FileCapture.NONE,
                FileCapture.ANY,
                -> Unit
            }
        }
    }
}
