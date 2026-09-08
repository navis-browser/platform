/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.ui

import java.net.URI
import java.util.Locale
import org.navis.browser.api.BrowserSessionState
import org.navis.browser.api.BrowserState
import org.navis.browser.api.ContentTermination
import org.navis.browser.api.LoadingState
import org.navis.browser.api.SecurityState
import org.navis.browser.api.SessionId
import org.navis.browser.api.SessionMode
import org.navis.browser.engine.SiteInformation

/** A certificate subject is untrusted display text, even when its transport is authenticated. */
internal fun sanitizeSiteOrganization(value: String): String? = buildString {
    // Bound both the work and the result; a long subject must not dominate browser chrome.
    var offset = 0
    while (offset < value.length && offset < 512) {
        val character = Character.codePointAt(value, offset)
        val count = Character.charCount(character)
        offset += count
        if (offset > 512 || length + count > 160) break
        if (Character.isISOControl(character) ||
            Character.getType(character) == Character.FORMAT.toInt() ||
            character in 0xD800..0xDFFF || character == 0x2028 || character == 0x2029) continue
        appendCodePoint(character)
    }
}.trim().takeIf(String::isNotBlank)

private fun httpsOrigin(value: String): String? = runCatching {
    val uri = URI(value)
    if (!uri.scheme.equals("https", ignoreCase = true)) return null
    val host = uri.host?.lowercase(Locale.ROOT)?.takeIf(String::isNotBlank) ?: return null
    if (uri.port !in -1..65535) return null
    "https://$host" + if (uri.port == -1 || uri.port == 443) "" else ":${uri.port}"
}.getOrNull()

/** Contains identity boundaries only: revision also changes for titles/history/progress. */
private data class ToolbarSiteIdentity(
    val sessionId: SessionId,
    val mode: SessionMode,
    val url: String,
    val security: SecurityState,
    val loading: Boolean,
    val nativeNewTab: Boolean,
    val nativeRoute: String?,
    val crashed: Boolean,
    val termination: ContentTermination,
    val failureCode: Int?,
    val engineErrorPage: Boolean,
) {
    val origin: String? get() = if (security == SecurityState.SECURE && !loading &&
        !nativeNewTab && nativeRoute == null && !crashed &&
        termination == ContentTermination.NONE && failureCode == null && !engineErrorPage) {
        httpsOrigin(url)
    } else null

    companion object {
        fun from(session: BrowserSessionState?): ToolbarSiteIdentity? = session?.let {
            ToolbarSiteIdentity(
                it.id, it.mode, it.navigation.url, it.navigation.security,
                it.navigation.loading != LoadingState.IDLE, it.nativeNewTab, it.nativeRoute,
                it.navigation.crashed, it.navigation.contentTermination,
                it.navigation.failureCode, it.navigation.engineErrorPage,
            )
        }
    }
}

/**
 * Main-thread controller. Observing every Runtime publication preserves load-start boundaries
 * even when Compose coalesces a start and completion into one frame. At most one read-only
 * identity request is issued for each eligible identity; ordinary revision changes do not
 * query again. No certificate is cached across tabs/navigation.
 */
internal class ToolbarSiteOrganizationController(
    private val currentState: () -> BrowserState,
    private val load: (SessionId, (Result<SiteInformation>) -> Unit) -> Unit,
    private val onChanged: (String?) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    private val queryAllKinds: Boolean = false,
    private val onIdentityChanged: (SiteInformation?) -> Unit = {},
) : AutoCloseable {
    private var identity: ToolbarSiteIdentity? = null
    private var generation = 0L
    private var closed = false
    private var organization: String? = null
    private var information: SiteInformation? = null

    fun observe(state: BrowserState) {
        if (closed) return
        val next = ToolbarSiteIdentity.from(state.activeSession)
        if (next == identity) return
        identity = next
        val requestGeneration = ++generation
        publish(null)
        information = null
        onIdentityChanged(null)
        if (next == null) return
        val expectedOrigin = next.origin
        if (expectedOrigin == null && (!queryAllKinds || next.loading || next.nativeNewTab ||
            next.nativeRoute != null || next.crashed || next.termination != ContentTermination.NONE ||
            next.failureCode != null || next.engineErrorPage)) return
        load(next.sessionId) { result ->
            if (closed || generation != requestGeneration ||
                identity != ToolbarSiteIdentity.from(currentState().activeSession)) return@load
            val info = result.getOrNull() ?: return@load
            information = info
            onIdentityChanged(info)
            if (info.kind != "secure" || httpsOrigin(info.origin) != expectedOrigin) return@load
            val certificate = info.certificate ?: return@load
            val start = certificate.validFrom ?: return@load
            val end = certificate.validTo ?: return@load
            if (now() !in start..end) return@load
            publish(sanitizeSiteOrganization(certificate.organization))
        }
    }

    /** Mask immediately if a caller has a newer state than the registered observer. */
    fun organizationFor(active: BrowserSessionState?): String? = organization.takeIf {
        !closed && identity == ToolbarSiteIdentity.from(active)
    }

    fun informationFor(active: BrowserSessionState?): SiteInformation? = information.takeIf {
        !closed && identity == ToolbarSiteIdentity.from(active)
    }

    private fun publish(value: String?) {
        if (value == organization) return
        organization = value
        onChanged(value)
    }

    override fun close() {
        closed = true
        ++generation
        identity = null
        organization = null
        information = null
    }
}
