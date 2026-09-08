/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.engine.runtime

import android.app.Activity
import org.navis.browser.api.SessionId
import org.navis.browser.engine.AndroidWindowLease

/** AVAILABLE means an OS provider candidate, not provider approval or credential existence. */
internal enum class EngineWebAuthnAvailability {
    AVAILABLE,
    UNSUPPORTED_OS,
    SYSTEM_UNAVAILABLE,
    PROVIDER_UNAVAILABLE,
    ORIGIN_PERMISSION_MISSING,
    NO_FOREGROUND_ACTIVITY,
}

/** Native engine requests carry real credential results; Platform only owns Activity lifetime. */
internal interface EngineWebAuthnPort {
    fun bindActivity(activity: Activity, lease: AndroidWindowLease)
    fun unbindActivity(lease: AndroidWindowLease)
    fun setSessionWindow(sessionId: SessionId, windowId: Long?)
    fun availability(windowId: Long?): EngineWebAuthnAvailability
}
