/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.persistence

import java.net.URI
import java.util.Locale
import org.navis.browser.api.SitePermissionDecision
import org.navis.browser.api.SitePermissionRequest

internal interface SitePermissionDecisions : AutoCloseable {
    fun decision(request: SitePermissionRequest.Content): SitePermissionDecision?
    fun record(request: SitePermissionRequest.Content, decision: SitePermissionDecision)
    fun clear(uri: String? = null, privateMode: Boolean? = null)
}

/** Writes must throw on failure; an uncommitted choice must not become a remembered grant. */
internal interface SitePermissionStorage {
    fun entries(): Map<String, String>
    fun put(key: String, value: String)
    fun remove(keys: Set<String>)
}

/** Normal/private decisions and requesting origins are isolated; temporary grants live in RAM. */
internal class SitePermissionPolicy(private val storage: SitePermissionStorage) : SitePermissionDecisions {
    private val temporary = mutableMapOf<Pair<Boolean, String>, SitePermissionDecision>()

    override fun decision(request: SitePermissionRequest.Content): SitePermissionDecision? {
        val key = key(request) ?: return null
        temporary[request.privateMode to key]?.let { return it }
        if (request.privateMode) return null
        return when (storage.entries()[key]) {
            SitePermissionDecision.ALLOW_ALWAYS.wireValue -> SitePermissionDecision.ALLOW_ALWAYS
            SitePermissionDecision.BLOCK.wireValue -> SitePermissionDecision.BLOCK
            else -> null
        }
    }

    override fun record(request: SitePermissionRequest.Content, decision: SitePermissionDecision) {
        if (decision == SitePermissionDecision.DISMISS) return
        val key = key(request) ?: return
        if (decision == SitePermissionDecision.ALLOW_SESSION || request.privateMode) {
            temporary[request.privateMode to key] = if (decision.allows) {
                SitePermissionDecision.ALLOW_SESSION
            } else {
                SitePermissionDecision.BLOCK
            }
        } else {
            storage.put(key, decision.wireValue)
            temporary.remove(false to key)
        }
    }

    override fun clear(uri: String?, privateMode: Boolean?) {
        val origin = uri?.let { canonicalOrigin(it) ?: error("Invalid site permission origin") }
        fun matches(key: String) = origin == null || key.startsWith("$origin|") || key.endsWith("|$origin")
        if (privateMode != true) storage.remove(storage.entries().keys.filterTo(mutableSetOf(), ::matches))
        temporary.keys.removeAll { (privateMode == null || it.first == privateMode) && matches(it.second) }
    }

    override fun close() = temporary.clear()

    private fun key(request: SitePermissionRequest.Content): String? {
        val origin = canonicalOrigin(request.uri) ?: return null
        val requester = request.thirdPartyOrigin?.takeIf(String::isNotBlank)?.let {
            canonicalOrigin(it) ?: return null
        } ?: origin
        return "$origin|${request.kind.name}|$requester"
    }

    companion object {
        internal fun canonicalOrigin(value: String): String? = runCatching {
            val uri = URI(value)
            val scheme = uri.scheme?.lowercase(Locale.ROOT)
            val host = uri.host?.lowercase(Locale.ROOT)
            if ((scheme != "http" && scheme != "https") || host.isNullOrBlank() || uri.rawUserInfo != null) {
                null
            } else {
                val port = uri.port.takeUnless {
                    it == -1 || (it == 80 && scheme == "http") || (it == 443 && scheme == "https")
                }
                "$scheme://$host${port?.let { ":$it" }.orEmpty()}"
            }
        }.getOrNull()
    }
}
