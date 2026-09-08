/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.persistence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.navis.browser.api.SessionId
import org.navis.browser.api.SitePermissionDecision
import org.navis.browser.api.SitePermissionKind
import org.navis.browser.api.SitePermissionRequest
import org.navis.browser.api.TargetRequestId

class SitePermissionPolicyTest {
    private fun request(uri: String = "https://example.com/a", privateMode: Boolean = false,
        thirdParty: String? = null) = SitePermissionRequest.Content(
        TargetRequestId(1), SessionId(1), uri, privateMode, SitePermissionKind.GEOLOCATION, thirdParty,
    )

    @Test fun dismissNeverBecomesBlockOrOverwritesAnExistingDecision() {
        val storage = MemoryStorage()
        val policy = SitePermissionPolicy(storage)
        policy.record(request(), SitePermissionDecision.DISMISS)
        assertNull(policy.decision(request()))
        assertTrue(storage.entries().isEmpty())
        policy.record(request(), SitePermissionDecision.ALLOW_ALWAYS)
        policy.record(request(), SitePermissionDecision.DISMISS)
        assertEquals(SitePermissionDecision.ALLOW_ALWAYS, policy.decision(request()))
    }

    @Test fun temporaryAllowsExpireWhenRuntimeClosesAndDoNotPersist() {
        val storage = MemoryStorage()
        val policy = SitePermissionPolicy(storage)
        policy.record(request(), SitePermissionDecision.ALLOW_SESSION)
        assertEquals(SitePermissionDecision.ALLOW_SESSION, policy.decision(request("https://example.com/b")))
        assertTrue(storage.entries().isEmpty())
        policy.close()
        assertNull(policy.decision(request()))
        assertNull(SitePermissionPolicy(storage).decision(request()))
    }

    @Test fun explicitAlwaysAndBlockPersistButPrivateChoicesNeverDo() {
        val storage = MemoryStorage()
        SitePermissionPolicy(storage).record(request(), SitePermissionDecision.ALLOW_ALWAYS)
        val policy = SitePermissionPolicy(storage)
        assertEquals(SitePermissionDecision.ALLOW_ALWAYS, policy.decision(request()))
        assertNull(policy.decision(request(privateMode = true)))
        policy.record(request(privateMode = true), SitePermissionDecision.BLOCK)
        assertEquals(SitePermissionDecision.ALLOW_ALWAYS, SitePermissionPolicy(storage).decision(request()))
        assertNull(SitePermissionPolicy(storage).decision(request(privateMode = true)))
        policy.record(request(), SitePermissionDecision.BLOCK)
        assertEquals(SitePermissionDecision.BLOCK, SitePermissionPolicy(storage).decision(request()))
    }

    @Test fun decisionsAreOriginKindAndRequesterBound() {
        val policy = SitePermissionPolicy(MemoryStorage())
        policy.record(request(), SitePermissionDecision.ALLOW_ALWAYS)
        assertEquals(SitePermissionDecision.ALLOW_ALWAYS, policy.decision(request("https://EXAMPLE.com:443/other")))
        assertNull(policy.decision(request("http://example.com/")))
        assertNull(policy.decision(request("https://example.com:444/")))
        assertNull(policy.decision(request(thirdParty = "https://embedded.example/")))
        assertNull(policy.decision(request().copy(kind = SitePermissionKind.NOTIFICATIONS)))
        policy.record(request("navis://settings/"), SitePermissionDecision.ALLOW_ALWAYS)
        assertNull(policy.decision(request("navis://settings/")))
    }

    @Test fun resetClearsOnlyTheAuthenticatedSiteAndItsEmbeddedRequests() {
        val policy = SitePermissionPolicy(MemoryStorage())
        policy.record(request(), SitePermissionDecision.ALLOW_ALWAYS)
        policy.record(request("https://other.example/"), SitePermissionDecision.ALLOW_ALWAYS)
        policy.record(request("https://outer.example/", thirdParty = "https://example.com"), SitePermissionDecision.BLOCK)
        policy.record(request(privateMode = true), SitePermissionDecision.ALLOW_SESSION)
        policy.clear("https://example.com/path")
        assertNull(policy.decision(request()))
        assertNull(policy.decision(request(privateMode = true)))
        assertNull(policy.decision(request("https://outer.example/", thirdParty = "https://example.com")))
        assertEquals(SitePermissionDecision.ALLOW_ALWAYS, policy.decision(request("https://other.example/")))
        policy.clear()
        assertNull(policy.decision(request("https://other.example/")))
    }

    @Test fun failedWritesDoNotAppearSuccessfulOrCreateRememberedAccess() {
        val storage = MemoryStorage()
        val policy = SitePermissionPolicy(storage)
        storage.fail = true
        assertTrue(runCatching { policy.record(request(), SitePermissionDecision.ALLOW_ALWAYS) }.isFailure)
        assertNull(policy.decision(request()))
    }

    @Test fun privateSiteResetCannotEraseNormalProfileDecisions() {
        val policy = SitePermissionPolicy(MemoryStorage())
        policy.record(request(), SitePermissionDecision.ALLOW_ALWAYS)
        policy.record(request(privateMode = true), SitePermissionDecision.BLOCK)
        policy.clear("https://example.com", privateMode = true)
        assertNull(policy.decision(request(privateMode = true)))
        assertEquals(SitePermissionDecision.ALLOW_ALWAYS, policy.decision(request()))
    }

    private class MemoryStorage : SitePermissionStorage {
        private val data = mutableMapOf<String, String>()
        var fail = false
        override fun entries() = data.toMap()
        override fun put(key: String, value: String) { check(!fail); data[key] = value }
        override fun remove(keys: Set<String>) { check(!fail); keys.forEach(data::remove) }
    }
}
