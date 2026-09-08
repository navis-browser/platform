/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.extensions

import java.util.concurrent.CompletableFuture
import org.navis.browser.engine.extensions.EngineExtensionOptionalPermissionRequest

/**
 * Exactly-once bridge between one engine permissions.request() call and the product prompt.
 *
 * Ownership stays with the product manager. Completing a stale transaction is harmless, while
 * replacing, navigating, closing a tab, disabling the extension, or shutting down resolves it as
 * denied so the extension background context is never stranded.
 */
internal class ExtensionOptionalPermissionTransaction(
    val uiToken: Long,
    val request: EngineExtensionOptionalPermissionRequest,
) {
    val completion = CompletableFuture<Boolean>()

    fun finish(allowed: Boolean): Boolean = completion.complete(allowed)

    fun belongsToTab(tabId: Long): Boolean = request.sourceTabId == tabId

    fun belongsToExtension(extensionId: String): Boolean = request.extensionId == extensionId
}
