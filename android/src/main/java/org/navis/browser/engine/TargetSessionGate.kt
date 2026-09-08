/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.engine

import org.navis.browser.api.SessionId

/** Single source of truth for which Session may own user-facing target requests. */
internal class TargetSessionGate(
    private val isSessionLive: (SessionId) -> Boolean,
) {
    var activeSessionId: SessionId? = null
        private set

    fun activate(sessionId: SessionId): Boolean {
        if (!isSessionLive(sessionId)) {
            return false
        }
        activeSessionId = sessionId
        return true
    }

    fun deactivate(sessionId: SessionId) {
        if (activeSessionId == sessionId) {
            activeSessionId = null
        }
    }

    fun allows(sessionId: SessionId): Boolean =
        activeSessionId == sessionId && isSessionLive(sessionId)
}
