/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.navis.browser.R
import org.navis.browser.engine.AndroidWindowLease
import org.navis.browser.api.BrowserTargetRuntime
import org.navis.browser.api.FilePickerMode
import org.navis.browser.api.FilePickerRequest
import org.navis.browser.api.PlatformPermission
import org.navis.browser.api.PlatformPermissionRequest
import org.navis.browser.api.TargetRequestId
import org.navis.browser.api.TargetRequestObserver
import org.navis.browser.api.TargetRequestState
import org.navis.browser.permissions.AndroidNotificationPermissionCoordinator
import org.navis.browser.uploads.AndroidUploadSelections
import org.navis.browser.uploads.UploadException
import org.navis.browser.uploads.UploadFailure

internal class AndroidPlatformRequests(
    private val activity: AppCompatActivity,
) {
    private var runtime: BrowserTargetRuntime? = null
    private var lease: AndroidWindowLease? = null
    private var isLeaseCurrent: () -> Boolean = { false }
    private val currentRuntime: BrowserTargetRuntime?
        get() = runtime?.takeIf { lease != null && isLeaseCurrent() }
    private class PermissionLaunch(
        val id: TargetRequestId,
        val runtime: BrowserTargetRuntime,
        val lease: AndroidWindowLease,
        val permissions: Set<String>,
        val media: Boolean,
    ) {
        var revoked = false
    }
    // Keep a canceled launch until Android returns its result. A new request
    // must not consume the old system dialog's callback after a host rebind.
    private var permissionLaunch: PermissionLaunch? = null
    private var fileRequestId: TargetRequestId? = null
    private var capturePermissionRequest: FilePickerRequest? = null
    private var pendingCameraCapture: AndroidCameraCapture.PendingCameraCapture? = null
    private var uploadJob: AndroidUploadSelections.Job? = null
    private var uploadDialog: androidx.activity.ComponentDialog? = null
    private var targetState = TargetRequestState()
    private val cameraCapture = AndroidCameraCapture(activity)
    private val observer = TargetRequestObserver(::render)
    private val notificationPermissions = AndroidNotificationPermissionCoordinator(
        activity,
        canLaunch = {
            permissionLaunch == null &&
                capturePermissionRequest == null && fileRequestId == null &&
                targetState.platformPermission == null &&
                targetState.sitePermission !is org.navis.browser.api.SitePermissionRequest.Media
        },
        onFinished = ::launchPendingRequests,
    )

    private val permissionLauncher = activity.registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        permissionLaunch?.let { completePermission(it, grants) }
    }

    private fun completePermission(launch: PermissionLaunch, grants: Map<String, Boolean>) {
        if (permissionLaunch !== launch) return
        permissionLaunch = null
        if (!launch.revoked && currentRuntime === launch.runtime && lease == launch.lease) {
            val allowed = grantsAreGranted(grants, launch.permissions)
            if (launch.media && targetState.sitePermission?.id == launch.id) {
                launch.runtime.respondToMediaPlatformPermission(launch.id, allowed)
            } else if (!launch.media && targetState.platformPermission?.id == launch.id) {
                launch.runtime.respondToPlatformPermission(launch.id, allowed)
            }
        }
        launchPendingRequests()
    }

    private val fileLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (currentRuntime == null) { clearLocalFileRequest(); return@registerForActivityResult }
        val id = fileRequestId ?: return@registerForActivityResult
        val uris = if (result.resultCode == Activity.RESULT_OK && targetState.filePicker?.id == id) {
            result.data.selectedUris()
        } else {
            emptyList()
        }
        if (uris.isEmpty()) {
            if (targetState.filePicker?.id == id) showMessage(R.string.upload_selection_cancelled)
            finishFilePicker(id, emptyList())
        } else {
            prepareFileSelection(id, uris)
        }
    }

    private val cameraPermissionLauncher = activity.registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (currentRuntime == null) { clearLocalFileRequest(); return@registerForActivityResult }
        val request = capturePermissionRequest ?: return@registerForActivityResult
        capturePermissionRequest = null
        if (fileRequestId != request.id) {
            return@registerForActivityResult
        }
        if (targetState.filePicker?.id != request.id) {
            finishFilePicker(request.id, emptyList())
        } else if (granted) {
            launchCameraCapture(request)
        } else {
            showMessage(R.string.camera_permission_denied)
            finishFilePicker(request.id, emptyList())
        }
    }

    private val cameraLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (currentRuntime == null) { clearLocalFileRequest(); return@registerForActivityResult }
        val capture = pendingCameraCapture ?: return@registerForActivityResult
        pendingCameraCapture = null
        if (fileRequestId != capture.requestId) {
            capture.cancel()
            return@registerForActivityResult
        }
        val requestIsCurrent = targetState.filePicker?.id == capture.requestId
        val uri = if (requestIsCurrent) {
            capture.complete(result.resultCode == Activity.RESULT_OK)
        } else {
            capture.cancel()
            null
        }
        if (requestIsCurrent && uri == null && result.resultCode == Activity.RESULT_OK) {
            showMessage(R.string.camera_capture_failed)
        }
        if (uri == null) finishFilePicker(capture.requestId, emptyList())
        else prepareFileSelection(capture.requestId, listOf(uri))
    }

    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_RESUME) launchPendingRequests()
        else if (event == Lifecycle.Event.ON_DESTROY) unbind()
    }

    init { activity.lifecycle.addObserver(lifecycleObserver) }

    fun bind(runtime: BrowserTargetRuntime, lease: AndroidWindowLease, isLeaseCurrent: () -> Boolean) {
        if (this.runtime === runtime && this.lease == lease) {
            return
        }
        unbind()
        this.runtime = runtime
        this.lease = lease
        this.isLeaseCurrent = isLeaseCurrent
        notificationPermissions.bind(lease)
        runtime.addTargetObserver(observer)
    }

    fun unbind() {
        val previousRuntime = runtime
        val responseRuntime = currentRuntime
        val pendingPermissionId = permissionLaunch?.takeIf {
            !it.media && it.runtime === responseRuntime && it.lease == lease
        }?.id
        permissionLaunch?.revoked = true
        runCatching { previousRuntime?.removeTargetObserver(observer) }
        runtime = null
        lease = null
        isLeaseCurrent = { false }
        listOfNotNull(pendingPermissionId, targetState.platformPermission?.id)
            .distinct()
            .forEach { id ->
                runCatching { responseRuntime?.respondToPlatformPermission(id, false) }
            }
        targetState.sitePermission?.takeIf { it is org.navis.browser.api.SitePermissionRequest.Media }?.id?.let { id ->
            runCatching { responseRuntime?.respondToSitePermission(id, false) }
        }
        listOfNotNull(fileRequestId, targetState.filePicker?.id)
            .distinct()
            .forEach { id ->
                runCatching { responseRuntime?.respondToFilePicker(id, emptyList()) }
            }
        clearLocalFileRequest()
        targetState = TargetRequestState()
        notificationPermissions.unbind()
    }

    private fun render(state: TargetRequestState) {
        if (currentRuntime == null) return
        targetState = state
        if (fileRequestId != null && state.filePicker?.id != fileRequestId) {
            uploadJob?.cancel()
            uploadDialog?.dismiss()
        }
        launchPendingRequests()
    }

    private fun launchPendingRequests() {
        if (currentRuntime == null || !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        if (notificationPermissions.inFlight) return
        if (permissionLaunch == null && capturePermissionRequest == null) {
            targetState.platformPermission?.let(::launchPermissionRequest)
        }
        if (!targetState.mediaPlatformGranted && permissionLaunch == null && capturePermissionRequest == null) {
            (targetState.sitePermission as? org.navis.browser.api.SitePermissionRequest.Media)
                ?.let(::launchMediaPermissionRequest)
        }
        if (fileRequestId == null && permissionLaunch == null) {
            targetState.filePicker?.let(::launchFilePicker)
        }
        notificationPermissions.launchIfReady()
    }

    private fun launchPermissionRequest(request: PlatformPermissionRequest) {
        val permissions = request.permissions.flatMap { it.androidNames() }.distinct()
        launchPermissions(request.id, permissions, media = false)
    }

    private fun launchMediaPermissionRequest(request: org.navis.browser.api.SitePermissionRequest.Media) {
        val permissions = buildList {
            if (request.videoSources.isNotEmpty()) add(Manifest.permission.CAMERA)
            if (request.audioSources.isNotEmpty()) add(Manifest.permission.RECORD_AUDIO)
        }.distinct()
        launchPermissions(request.id, permissions, media = true)
    }

    private fun launchPermissions(id: TargetRequestId, permissions: List<String>, media: Boolean) {
        if (permissionLaunch != null) return
        val owner = currentRuntime ?: return
        val boundLease = lease ?: return
        val launch = PermissionLaunch(id, owner, boundLease, permissions.toSet(), media)
        permissionLaunch = launch
        if (permissions.isEmpty()) {
            completePermission(launch, emptyMap())
            return
        }
        runCatching { permissionLauncher.launch(permissions.toTypedArray()) }
            .onFailure { completePermission(launch, emptyMap()) }
    }

    private fun launchFilePicker(request: FilePickerRequest) {
        if (fileRequestId != null) {
            return
        }
        fileRequestId = request.id
        if (request.cameraCaptureKind() != null) {
            if (
                ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                launchCameraCapture(request)
            } else {
                capturePermissionRequest = request
                runCatching { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) }
                    .onFailure {
                        capturePermissionRequest = null
                        showMessage(R.string.camera_permission_denied)
                        finishFilePicker(request.id, emptyList())
                    }
            }
            return
        }
        runCatching { fileLauncher.launch(request.intent()) }
            .onFailure {
                showMessage(R.string.upload_selection_unreadable)
                finishFilePicker(request.id, emptyList())
            }
    }

    private fun prepareFileSelection(id: TargetRequestId, uris: List<String>) {
        val request = targetState.filePicker?.takeIf { it.id == id && currentRuntime != null }
        if (request == null) {
            finishFilePicker(id, emptyList())
            return
        }
        uploadDialog = showUploadProgressDialog(activity) { uploadJob?.cancel() }
        uploadJob = AndroidUploadSelections.prepare(activity, request, uris) { result ->
            if (fileRequestId == id) {
                uploadDialog?.dismiss()
                uploadDialog = null
                uploadJob = null
            }
            val current = fileRequestId == id && targetState.filePicker?.id == id && currentRuntime != null
            if (!current) {
                result.getOrNull()?.discard()
                finishFilePicker(id, emptyList())
                return@prepare
            }
            result.onFailure { error ->
                showMessage(when ((error as? UploadException)?.reason) {
                    UploadFailure.CANCELLED -> R.string.upload_selection_cancelled
                    UploadFailure.FILE_COUNT -> R.string.upload_selection_file_count
                    UploadFailure.FILE_SIZE -> R.string.upload_selection_file_size
                    UploadFailure.TOTAL_SIZE -> R.string.upload_selection_total_size
                    UploadFailure.TREE_LIMIT -> R.string.upload_selection_tree_limit
                    UploadFailure.NO_SPACE -> R.string.upload_selection_no_space
                    UploadFailure.INVALID_NAME -> R.string.upload_selection_invalid_name
                    else -> R.string.upload_selection_unreadable
                })
            }
            finishFilePicker(id, result.getOrNull()?.uris.orEmpty())
        }
    }

    private fun launchCameraCapture(request: FilePickerRequest) {
        val capture = cameraCapture.prepare(request).getOrElse {
            showMessage(R.string.camera_capture_failed)
            finishFilePicker(request.id, emptyList())
            return
        }
        pendingCameraCapture = capture
        runCatching { cameraLauncher.launch(capture.intent) }
            .onFailure {
                pendingCameraCapture = null
                capture.cancel()
                showMessage(R.string.camera_unavailable)
                finishFilePicker(request.id, emptyList())
            }
    }

    private fun finishFilePicker(id: TargetRequestId, uris: List<String>) {
        if (fileRequestId != id) {
            return
        }
        fileRequestId = null
        capturePermissionRequest = null
        currentRuntime?.respondToFilePicker(id, uris)
        launchPendingRequests()
    }

    private fun clearLocalFileRequest() {
        uploadJob?.cancel()
        uploadJob = null
        uploadDialog?.dismiss()
        uploadDialog = null
        pendingCameraCapture?.cancel()
        pendingCameraCapture = null
        capturePermissionRequest = null
        fileRequestId = null
    }

    private fun showMessage(resourceId: Int) {
        if (currentRuntime == null || !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        Toast.makeText(activity, resourceId, Toast.LENGTH_LONG).show()
    }

    private companion object {
        fun FilePickerRequest.intent(): Intent {
            if (mode == FilePickerMode.FOLDER) {
                return Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            }
            val acceptedTypes = mimeTypes.filter(String::isNotBlank).distinct()
            return Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = acceptedTypes.singleOrNull() ?: "*/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, mode == FilePickerMode.MULTIPLE)
                if (acceptedTypes.size > 1) {
                    putExtra(Intent.EXTRA_MIME_TYPES, acceptedTypes.toTypedArray())
                }
            }
        }

        fun Intent?.selectedUris(): List<String> {
            if (this == null) {
                return emptyList()
            }
            val values = linkedSetOf<Uri>()
            data?.let(values::add)
            clipData?.let { clips ->
                for (index in 0 until clips.itemCount) {
                    values += clips.getItemAt(index).uri
                }
            }
            return values.map(Uri::toString)
        }
    }
}
