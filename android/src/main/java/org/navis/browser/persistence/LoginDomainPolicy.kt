/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.persistence

import java.net.IDN
import java.net.Inet6Address
import java.net.InetAddress
import java.util.Locale

/**
 * Normalizes hosts stored by Navis and compares them with the engine-projected
 * trusted base hostname. The engine computes that value with its public-suffix
 * service, so Navis must not own a second PSL.
 */
internal object LoginDomainPolicy {
    fun canonicalHostname(value: String): String? {
        val raw = value.trim().takeIf {
            it.isNotEmpty() && !it.any(Char::isISOControl)
        } ?: return null
        val unwrapped = when {
            raw.startsWith('[') && raw.endsWith(']') -> raw.substring(1, raw.length - 1)
            '[' in raw || ']' in raw -> return null
            else -> raw
        }
        if (unwrapped.isEmpty() || '%' in unwrapped) {
            return null
        }
        if (':' in unwrapped) {
            return runCatching { InetAddress.getByName(unwrapped) }
                .getOrNull()
                ?.takeIf { it is Inet6Address }
                ?.hostAddress
                ?.substringBefore('%')
                ?.lowercase(Locale.ROOT)
        }

        val withoutRootDot = unwrapped.removeSuffix(".")
        if (
            withoutRootDot.isEmpty() ||
            withoutRootDot.endsWith('.') ||
            withoutRootDot.length > 253
        ) {
            return null
        }
        val ascii = runCatching {
            IDN.toASCII(withoutRootDot, IDN.USE_STD3_ASCII_RULES)
        }.getOrNull()?.lowercase(Locale.ROOT) ?: return null
        val labels = ascii.split('.')
        if (
            ascii.length > 253 ||
            labels.any { it.isEmpty() || it.length > 63 }
        ) {
            return null
        }
        if (labels.size == 4 && labels.all { label -> label.all(Char::isDigit) }) {
            val octets = labels.map { it.toIntOrNull() ?: return null }
            if (octets.any { it !in 0..255 }) {
                return null
            }
            return octets.joinToString(".")
        }
        return ascii
    }

    fun admitsSubdomains(baseHostname: String): Boolean {
        val canonical = canonicalHostname(baseHostname) ?: return false
        return '.' in canonical && !isIpLiteral(canonical)
    }

    fun matchesTrustedBase(originHostname: String, baseHostname: String): Boolean {
        val origin = canonicalHostname(originHostname) ?: return false
        val base = canonicalHostname(baseHostname) ?: return false
        if (origin == base) {
            return true
        }
        if (!admitsSubdomains(base) || isIpLiteral(origin)) {
            return false
        }
        val originLabels = origin.split('.')
        val baseLabels = base.split('.')
        return originLabels.size > baseLabels.size &&
            originLabels.takeLast(baseLabels.size) == baseLabels
    }

    private fun isIpLiteral(hostname: String): Boolean = ':' in hostname ||
        hostname.split('.').let { labels ->
            labels.size == 4 && labels.all { label ->
                label.isNotEmpty() && label.all(Char::isDigit) &&
                    (label.toIntOrNull() ?: -1) in 0..255
            }
        }
}
