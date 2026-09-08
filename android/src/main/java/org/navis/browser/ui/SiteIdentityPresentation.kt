/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.ui

import org.navis.browser.R
import org.navis.browser.api.BrowserSessionState
import org.navis.browser.api.SecurityState

internal fun siteIdentityIcon(kind: String): Int = when (kind) {
    "extension" -> R.drawable.ic_site_extension
    "secure", "local" -> R.drawable.ic_site_controls
    "insecure", "broken", "error" -> R.drawable.ic_site_warning
    else -> R.drawable.ic_site_info
}

internal fun fallbackSiteIdentityKind(active: BrowserSessionState?): String = when {
    active == null -> "unknown"
    active.nativeNewTab || active.nativeRoute != null -> "internal"
    active.navigation.engineErrorPage || active.navigation.failureCode != null || active.navigation.crashed -> "error"
    else -> when (active.navigation.security) {
        SecurityState.SECURE -> "secure"
        SecurityState.INSECURE -> "insecure"
        SecurityState.BROKEN -> "broken"
        SecurityState.UNKNOWN -> "unknown"
    }
}
