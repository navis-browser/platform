/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.engine

internal data class SessionRestorePlan(val engineState: String?, val initialUri: String?)

/** Native product identity wins over an incompatible legacy webpage snapshot. */
internal object SessionRestorePolicy {
    fun plan(
        savedState: String?,
        fallbackUri: String?,
        nativeRoute: String?,
        nativeNewTab: Boolean,
    ): SessionRestorePlan = when {
        nativeNewTab || nativeRoute != null -> SessionRestorePlan(null, null)
        savedState != null -> SessionRestorePlan(savedState, null)
        else -> SessionRestorePlan(null, fallbackUri?.takeIf(String::isNotBlank))
    }
}
