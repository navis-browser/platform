/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.extensions

/** State owned by one local XPI installation attempt. */
internal class ExtensionInstallTransaction(
    val token: Long,
) {
    var sourceName: String? = null
    var existingExtensionId: String? = null

    private var permissionDenied = false

    fun recordPromptResponse(kind: ExtensionPermissionKind, allowed: Boolean) {
        if (!allowed && kind != ExtensionPermissionKind.OPTIONAL) {
            permissionDenied = true
        }
    }

    fun wasUpdate(installedExtensionId: String): Boolean =
        existingExtensionId == installedExtensionId

    fun failureNotice(
        existingNotice: ExtensionNotice?,
        fallback: ExtensionNotice,
    ): ExtensionNotice? = existingNotice ?: fallback.takeUnless { permissionDenied }
}
