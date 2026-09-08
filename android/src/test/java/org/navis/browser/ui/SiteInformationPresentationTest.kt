/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.navis.browser.api.BrowserSessionState
import org.navis.browser.api.BrowserState
import org.navis.browser.api.LoadingState
import org.navis.browser.api.NavigationState
import org.navis.browser.api.SecurityState
import org.navis.browser.api.SessionId
import org.navis.browser.api.SessionMode
import org.navis.browser.engine.SiteCertificate
import org.navis.browser.engine.SiteInformation

class SiteInformationPresentationTest {
    private val certificate = SiteCertificate(
        "example.com", "CN=example.com,O=Example", "Example", "Issuer",
        "123", "AA:BB", 1_000, 10_000,
    )
    private val information = SiteInformation(
        "secure", "https://example.com", "example.com", "navigation-token", true,
        "TLS 1.3", "cipher", certificate,
    )
    private val session = BrowserSessionState(
        SessionId(1), SessionMode.NORMAL, true,
        NavigationState(url = "https://example.com", security = SecurityState.SECURE),
    )

    @Test
    fun organizationRequiresSecureConnectionAndValidCertificateDates() {
        assertEquals("Example", verifiedSiteOrganization(information, 5_000))
        assertNull(verifiedSiteOrganization(information.copy(kind = "broken"), 5_000))
        assertNull(verifiedSiteOrganization(information.copy(kind = "error"), 5_000))
        assertNull(verifiedSiteOrganization(information, 999))
        assertNull(verifiedSiteOrganization(information, 10_001))
        assertNull(verifiedSiteOrganization(information.copy(certificate = certificate.copy(validTo = null)), 5_000))
    }

    @Test
    fun internalAndErrorPagesNeverOfferSiteDataClearingEvenWithAnIncorrectFlag() {
        for (kind in listOf("internal", "error", "extension", "unknown")) {
            assertFalse(canClearSiteInformation(information.copy(kind = kind)))
        }
        assertTrue(canClearSiteInformation(information))
        assertFalse(canClearSiteInformation(information.copy(canClearData = false)))
        assertFalse(canClearSiteInformation(information.copy(navigationToken = "")))
    }

    @Test
    fun ordinaryTitleUpdatesPreserveThePanel() {
        val current = session.copy(navigation = session.navigation.copy(title = "A new title", revision = 1))
        assertTrue(siteInformationStillCurrent(session, BrowserState(listOf(current), current.id)))
    }

    @Test
    fun sameUrlReloadInvalidatesSiteDetails() {
        val current = session.copy(navigation = session.navigation.copy(revision = 1, loading = LoadingState.PENDING))
        assertFalse(siteInformationStillCurrent(session, BrowserState(listOf(current), current.id)))
    }

    @Test
    fun tabSwitchCloseAndSecurityChangesInvalidateSiteDetails() {
        val other = session.copy(id = SessionId(2))
        assertFalse(siteInformationStillCurrent(session, BrowserState(listOf(other), other.id)))
        assertFalse(siteInformationStillCurrent(session, BrowserState()))
        val broken = session.copy(navigation = session.navigation.copy(security = SecurityState.BROKEN))
        assertFalse(siteInformationStillCurrent(session, BrowserState(listOf(broken), broken.id)))
    }

    @Test
    fun nativeRouteTransitionInvalidatesSiteDetails() {
        val current = session.copy(nativeRoute = "settings")
        assertFalse(siteInformationStillCurrent(session, BrowserState(listOf(current), current.id)))
    }
}
