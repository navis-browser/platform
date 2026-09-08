/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.persistence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginDomainPolicyTest {
    @Test
    fun canonicalHostnameNormalizesDnsIdnIpv4AndIpv6() {
        assertEquals("example.com", LoginDomainPolicy.canonicalHostname("Example.COM."))
        assertEquals(
            "xn--bcher-kva.example",
            LoginDomainPolicy.canonicalHostname("bücher.example"),
        )
        assertEquals("127.0.0.1", LoginDomainPolicy.canonicalHostname("127.000.0.1"))
        assertEquals(
            LoginDomainPolicy.canonicalHostname("2001:db8::1"),
            LoginDomainPolicy.canonicalHostname("[2001:0DB8:0:0:0:0:0:1]"),
        )
    }

    @Test
    fun canonicalHostnameRejectsMalformedOrAmbiguousInput() {
        assertNull(LoginDomainPolicy.canonicalHostname(""))
        assertNull(LoginDomainPolicy.canonicalHostname("example..com"))
        assertNull(LoginDomainPolicy.canonicalHostname("example.com/path"))
        assertNull(LoginDomainPolicy.canonicalHostname("256.0.0.1"))
        assertNull(LoginDomainPolicy.canonicalHostname("[2001:db8::1"))
        assertNull(LoginDomainPolicy.canonicalHostname("fe80::1%wlan0"))
    }

    @Test
    fun trustedBaseMatchesOnlyWholeDnsLabels() {
        assertTrue(LoginDomainPolicy.matchesTrustedBase("example.com", "example.com"))
        assertTrue(LoginDomainPolicy.matchesTrustedBase("www.example.com", "example.com"))
        assertTrue(LoginDomainPolicy.matchesTrustedBase("a.b.example.com", "example.com"))
        assertFalse(LoginDomainPolicy.matchesTrustedBase("notexample.com", "example.com"))
        assertFalse(LoginDomainPolicy.matchesTrustedBase("example.com.invalid", "example.com"))
    }

    @Test
    fun idnComparisonUsesAsciiLabels() {
        assertTrue(
            LoginDomainPolicy.matchesTrustedBase(
                "shop.xn--bcher-kva.example",
                "bücher.example",
            ),
        )
    }

    @Test
    fun ipAndSingleLabelHostsRequireExactMatches() {
        assertTrue(LoginDomainPolicy.matchesTrustedBase("127.0.0.1", "127.0.0.1"))
        assertFalse(LoginDomainPolicy.matchesTrustedBase("www.127.0.0.1", "127.0.0.1"))
        assertTrue(LoginDomainPolicy.matchesTrustedBase("localhost", "localhost"))
        assertFalse(LoginDomainPolicy.matchesTrustedBase("www.localhost", "localhost"))
        assertTrue(LoginDomainPolicy.matchesTrustedBase("2001:db8::1", "[2001:db8::1]"))
        assertFalse(LoginDomainPolicy.matchesTrustedBase("2001:db8::2", "2001:db8::1"))
    }
}
