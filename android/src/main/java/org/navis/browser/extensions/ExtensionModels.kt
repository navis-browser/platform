/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.extensions

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.view.View

internal enum class ExtensionClass {
    APPLICATION_BUILT_IN,
    USER,
    TEMPORARY,
}

internal enum class ExtensionSignature {
    APPLICATION_VERIFIED,
    MOZILLA_SIGNED,
    UNVERIFIED,
}

internal data class ExtensionSnapshot(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val icon: Bitmap?,
    val extensionClass: ExtensionClass,
    val signature: ExtensionSignature,
    val enabled: Boolean,
    val allowedInPrivateBrowsing: Boolean,
    val privateBrowsingAvailable: Boolean,
    val blockedSideLoad: Boolean,
    val canChangeEnabled: Boolean,
    val canUninstall: Boolean,
    val canUpdate: Boolean,
    val requiredPermissions: List<String>,
    val requiredOrigins: List<String>,
    val hasOptions: Boolean,
    val hasToolbarAction: Boolean,
    val pinnedToToolbar: Boolean,
)

internal enum class ExtensionActionKind {
    BROWSER,
    PAGE,
}

internal data class ExtensionActionSnapshot(
    val extensionId: String,
    val extensionName: String,
    val kind: ExtensionActionKind,
    val title: String,
    val icon: Bitmap?,
    val badgeText: String,
    val badgeTextColor: Int?,
    val badgeBackgroundColor: Int?,
    val enabled: Boolean,
    val pinned: Boolean,
    val sourceTabId: Long = 0,
)

internal data class ExtensionPopupSnapshot(
    val extensionId: String,
    val title: String,
    val targetToken: String = "",
    val popupUri: String = "",
    val sourceTabId: Long = 0,
    val privateMode: Boolean = false,
)

internal enum class ExtensionPermissionKind {
    INSTALL,
    UPDATE,
    OPTIONAL,
}

internal data class ExtensionPermissionRequest(
    val token: Long,
    val extensionName: String,
    val candidateVersion: String,
    val installedVersion: String?,
    val sourceName: String?,
    val kind: ExtensionPermissionKind,
    val permissions: List<String>,
    val origins: List<String>,
    val dataCollectionPermissions: List<String>,
)

internal enum class ExtensionNotice {
    BUILT_IN_UNAVAILABLE,
    FILE_READ_FAILED,
    FILE_TOO_LARGE,
    INSTALL_FAILED,
    SIGNATURE_REQUIRED,
    DOWNGRADE_REJECTED,
    UNSUPPORTED_CAPABILITY,
    OPERATION_FAILED,
    INSTALL_COMPLETE,
    UPDATE_COMPLETE,
    REMOVED,
}

internal enum class ExtensionActionFailure {
    INVOCATION_FAILED,
    POPUP_LOAD_FAILED,
}

internal fun extensionNoticeFor(error: Throwable): ExtensionNotice =
    extensionNoticeForOrNull(error) ?: ExtensionNotice.OPERATION_FAILED

internal fun extensionNoticeForOrNull(error: Throwable?): ExtensionNotice? {
    val chain = generateSequence(error) { it.cause }.toList()
    if (chain.any { it is UnsupportedOperationException }) {
        return ExtensionNotice.UNSUPPORTED_CAPABILITY
    }
    val codes = chain.mapNotNull { it.message?.substringBefore(':') }.toSet()
    return when {
        codes.any {
            it == "invalid-built-in-registry" ||
                it == "built-in-identity-conflict" ||
                it == "invalid-built-in"
        } -> ExtensionNotice.BUILT_IN_UNAVAILABLE
        "signature-required" in codes -> ExtensionNotice.SIGNATURE_REQUIRED
        "downgrade-rejected" in codes -> ExtensionNotice.DOWNGRADE_REJECTED
        "package-size" in codes -> ExtensionNotice.FILE_TOO_LARGE
        chain.any { it is java.io.IOException } -> ExtensionNotice.FILE_READ_FAILED
        else -> null
    }
}

internal data class ExtensionManagerState(
    val loading: Boolean = true,
    val extensions: List<ExtensionSnapshot> = emptyList(),
    val pendingPermission: ExtensionPermissionRequest? = null,
    val busyExtensionId: String? = null,
    val notice: ExtensionNotice? = null,
    val actions: List<ExtensionActionSnapshot> = emptyList(),
    val popup: ExtensionPopupSnapshot? = null,
    val pendingActionId: String? = null,
    val popupLoading: Boolean = false,
    val actionFailure: ExtensionActionFailure? = null,
    val popupFailureStage: ExtensionPopupFailureStage? = null,
)

internal fun interface ExtensionStateObserver {
    fun onExtensionStateChanged(state: ExtensionManagerState)
}

internal interface ExtensionHost {
    val extensionState: ExtensionManagerState

    fun addExtensionObserver(observer: ExtensionStateObserver)

    fun removeExtensionObserver(observer: ExtensionStateObserver)

    fun dismissExtensionActionFailure() {}

    fun refreshExtensions()

    fun installLocalExtension(uri: Uri)

    fun setExtensionEnabled(id: String, enabled: Boolean)

    fun setExtensionPrivateAccess(id: String, allowed: Boolean)

    fun setExtensionPinned(id: String, pinned: Boolean)

    fun openExtensionOptions(id: String, onComplete: (Boolean) -> Unit = {})

    fun invokeExtensionAction(id: String)

    fun dismissExtensionPopup()

    fun createExtensionPopupView(context: Context): View

    fun releaseExtensionPopupView(view: View)

    fun uninstallExtension(id: String)

    fun respondToExtensionPermission(token: Long, allowed: Boolean)

    fun dismissExtensionNotice()
}
