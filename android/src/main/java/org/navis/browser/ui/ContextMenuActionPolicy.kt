/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.ui

import java.net.URI
import org.navis.browser.api.BrowserState
import org.navis.browser.api.SessionId

/** Product actions never promote a document-supplied URI to a privileged load. */
internal object ContextMenuActionPolicy {
    fun openableWebAddress(value: String): String? {
        if (value.isBlank() || value.length > 16384 || value.any(Char::isISOControl)) return null
        val parsed = runCatching { URI(value) }.getOrNull() ?: return null
        return value.takeIf {
            !parsed.isOpaque && parsed.scheme?.lowercase() in setOf("http", "https") &&
                !parsed.rawAuthority.isNullOrBlank() && parsed.rawUserInfo == null
        }
    }

    fun copyableText(value: String): String? = value.takeIf {
        it.isNotEmpty() && it.length <= 16384
    }
}

/** A UI action can claim one engine token and only the page which produced it. */
internal class ContextMenuActionGate(
    private val sessionId: SessionId,
    private val token: String,
    private val pageUrl: String,
    private val navigationRevision: Long,
) {
    private var claimed = false

    fun matches(state: BrowserState): Boolean {
        val active = state.activeSession ?: return false
        return token.matches(Regex("[0-9a-f]{32}")) && active.id == sessionId &&
            active.navigation.url == pageUrl && active.navigation.revision == navigationRevision &&
            !active.navigation.crashed
    }

    fun claim(state: BrowserState, consumeNativeToken: () -> Boolean): Boolean {
        if (claimed || !matches(state)) return false
        claimed = true
        return consumeNativeToken()
    }
}
