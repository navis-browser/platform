/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.engine.runtime

import org.navis.browser.api.SitePermissionDecision

import org.navis.browser.api.BrowserPrompt
import org.navis.browser.api.FilePickerRequest
import org.navis.browser.api.PlatformPermissionRequest
import org.navis.browser.api.PromptResponse
import org.navis.browser.api.SessionId
import org.navis.browser.api.SitePermissionRequest
import org.navis.browser.api.TargetRequestId

/** Main-thread request DTO sink implemented by the product target coordinator. */
internal interface EngineTargetObserver {
    fun onPromptRequested(request: BrowserPrompt)

    fun onLoginSaveCompleted(sessionId: SessionId, id: TargetRequestId) {}
    fun onLoginPromptCancelled(sessionId: SessionId, id: TargetRequestId) {}

    fun onSitePermissionRequested(request: SitePermissionRequest)

    fun onPlatformPermissionRequested(request: PlatformPermissionRequest)

    fun onFilePickerRequested(request: FilePickerRequest)

    fun onFullscreenChanged(sessionId: SessionId, enabled: Boolean)
}

/**
 * Completion half of the direct engine target seam.
 *
 * Request IDs are engine-issued and remain live until exactly one response or cancellation. The
 * port deliberately uses Navis DTOs, so no Gecko-owned prompt, permission, or picker object can
 * escape the private adapter.
 */
internal interface EngineTargetPort {
    fun bind(observer: EngineTargetObserver)

    fun unbind(observer: EngineTargetObserver)

    fun respondToPrompt(id: TargetRequestId, response: PromptResponse)

    fun notifySitePermissionShown(id: TargetRequestId)

    fun respondToSitePermission(id: TargetRequestId, allow: Boolean)

    fun respondToSitePermission(id: TargetRequestId, decision: SitePermissionDecision) =
        respondToSitePermission(id, decision.allows)

    fun respondToPlatformPermission(id: TargetRequestId, granted: Boolean)

    fun respondToFilePicker(id: TargetRequestId, uris: List<String>)

    fun cancelRequest(id: TargetRequestId)

    fun exitFullscreen(sessionId: SessionId)
}
