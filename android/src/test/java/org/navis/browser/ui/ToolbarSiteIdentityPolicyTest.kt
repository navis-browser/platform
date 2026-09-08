/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.navis.browser.api.BrowserSessionState
import org.navis.browser.api.BrowserState
import org.navis.browser.api.ContentTermination
import org.navis.browser.api.LoadingState
import org.navis.browser.api.NavigationState
import org.navis.browser.api.SecurityState
import org.navis.browser.api.SessionId
import org.navis.browser.api.SessionMode
import org.navis.browser.engine.SiteCertificate
import org.navis.browser.engine.SiteInformation

class ToolbarSiteIdentityPolicyTest {
    private val certificate = SiteCertificate(
        "example.com", "CN=example.com,O=Example", "Example", "Issuer Organization",
        "123", "AA:BB", 1_000, 10_000,
    )
    private val information = SiteInformation(
        "secure", "https://example.com", "example.com", "navigation-token", true,
        "TLS 1.3", "cipher", certificate,
    )
    private val session = BrowserSessionState(
        SessionId(1), SessionMode.NORMAL, true,
        NavigationState(url = "https://example.com/page", security = SecurityState.SECURE),
    )

    private class Harness(initial: BrowserSessionState, queryAllKinds: Boolean = false) {
        var state = BrowserState(listOf(initial), initial.id)
        val requests = mutableListOf<Pair<SessionId, (Result<SiteInformation>) -> Unit>>()
        val changes = mutableListOf<String?>()
        val controller = ToolbarSiteOrganizationController(
            currentState = { state },
            load = { id, callback -> requests.add(id to callback) },
            onChanged = { changes.add(it) },
            now = { 5_000L },
            queryAllKinds = queryAllKinds,
        )
        val organization get() = controller.organizationFor(state.activeSession)
        fun observe(current: BrowserSessionState?) {
            state = BrowserState(listOfNotNull(current), current?.id)
            controller.observe(state)
        }
        fun complete(index: Int, information: SiteInformation) {
            requests[index].second(Result.success(information))
        }
    }

    @Test
    fun queriesCertificateWithoutOpeningSheetAndIgnoresOrdinaryRevisionUpdates() {
        val harness = Harness(session)
        harness.observe(session)
        assertEquals(1, harness.requests.size)
        assertNull(harness.organization)
        harness.complete(0, information)
        assertEquals("Example", harness.organization)
        repeat(10) { index ->
            harness.observe(session.copy(navigation = session.navigation.copy(
                revision = index.toLong() + 1, title = "Title $index", faviconPng = "Icon $index",
                canGoBack = true, canGoForward = true,
            )))
        }
        assertEquals(1, harness.requests.size)
        assertEquals(listOf("Example"), harness.changes)
    }

    @Test
    fun waitsForCompletedSecureNavigationAndDoesNotQueryOnProgress() {
        val harness = Harness(session)
        for (loading in listOf(LoadingState.PENDING, LoadingState.VISIBLE, LoadingState.SILENT)) {
            repeat(3) { index -> harness.observe(session.copy(navigation = session.navigation.copy(
                loading = loading, revision = index.toLong(),
            ))) }
        }
        assertEquals(0, harness.requests.size)
        harness.observe(session.copy(navigation = session.navigation.copy(security = SecurityState.UNKNOWN)))
        assertEquals(0, harness.requests.size)
        harness.observe(session)
        assertEquals(1, harness.requests.size)
        harness.complete(0, information)
        assertEquals("Example", harness.organization)
    }

    @Test
    fun sameUrlReloadRevokesImmediatelyAndRejectsResponseAfterLoadCompletes() {
        val harness = Harness(session)
        harness.observe(session)
        harness.complete(0, information)
        harness.observe(session.copy(navigation = session.navigation.copy(loading = LoadingState.PENDING)))
        assertNull(harness.organization)
        harness.observe(session.copy(navigation = session.navigation.copy(loading = LoadingState.VISIBLE)))
        harness.observe(session)
        assertEquals(2, harness.requests.size)
        harness.complete(0, information)
        assertNull(harness.organization)
        harness.complete(1, information.copy(certificate = certificate.copy(organization = "New Subject")))
        assertEquals("New Subject", harness.organization)
    }

    @Test
    fun sameOriginNavigationAndTabSwitchCannotReuseOrDeliverTheOldCertificate() {
        val harness = Harness(session)
        harness.observe(session)
        val next = session.copy(navigation = session.navigation.copy(url = "https://example.com/other"))
        harness.observe(next)
        harness.complete(0, information)
        assertNull(harness.organization)
        harness.complete(1, information)
        assertEquals("Example", harness.organization)
        val other = next.copy(id = SessionId(2))
        harness.observe(other)
        assertNull(harness.organization)
        harness.observe(next)
        harness.complete(2, information)
        assertNull(harness.organization)
        harness.complete(3, information)
        assertEquals("Example", harness.organization)
    }

    @Test
    fun unsafeNativeFailedCrashedAndClosedPagesRevokeAndNeverQuery() {
        val unsafe = listOf(
            session.copy(nativeNewTab = true), session.copy(nativeRoute = "settings"),
            session.copy(navigation = session.navigation.copy(security = SecurityState.UNKNOWN)),
            session.copy(navigation = session.navigation.copy(security = SecurityState.INSECURE)),
            session.copy(navigation = session.navigation.copy(security = SecurityState.BROKEN)),
            session.copy(navigation = session.navigation.copy(url = "http://example.com")),
            session.copy(navigation = session.navigation.copy(crashed = true)),
            session.copy(navigation = session.navigation.copy(contentTermination = ContentTermination.TERMINATED)),
            session.copy(navigation = session.navigation.copy(engineErrorPage = true)),
            session.copy(navigation = session.navigation.copy(failureCode = -1)),
            null,
        )
        for (current in unsafe) {
            val harness = Harness(session)
            harness.observe(session)
            harness.complete(0, information)
            harness.observe(current)
            assertNull(harness.organization)
            assertEquals(1, harness.requests.size)
            harness.complete(0, information)
            assertNull(harness.organization)
        }
    }

    @Test
    fun rejectsWrongOriginPortUnsafeMetadataAndMissingExpiredOrBlankSubject() {
        val invalid = listOf(
            information.copy(origin = "https://elsewhere.example"),
            information.copy(origin = "https://example.com:8443"),
            information.copy(origin = "http://example.com"),
            information.copy(origin = "not an origin"),
            information.copy(kind = "broken"), information.copy(kind = "error"),
            information.copy(certificate = null),
            information.copy(certificate = certificate.copy(organization = "")),
            information.copy(certificate = certificate.copy(validFrom = null)),
            information.copy(certificate = certificate.copy(validTo = null)),
            information.copy(certificate = certificate.copy(validFrom = 5_001)),
            information.copy(certificate = certificate.copy(validTo = 4_999)),
        )
        for (info in invalid) {
            val harness = Harness(session)
            harness.observe(session)
            harness.complete(0, info)
            assertNull(harness.organization)
            harness.observe(session.copy(navigation = session.navigation.copy(revision = 1)))
            assertEquals(1, harness.requests.size)
        }
    }

    @Test
    fun canonicalizesHttpsOriginAndUsesOnlySubjectOrganization() {
        val harness = Harness(session.copy(navigation = session.navigation.copy(
            url = "HTTPS://EXAMPLE.COM:443/path?q=1#fragment",
        )))
        harness.observe(harness.state.activeSession)
        harness.complete(0, information.copy(certificate = certificate.copy(organization = "  Subject Org  ")))
        assertEquals("Subject Org", harness.organization)
    }

    @Test
    fun failureAndDisposalDoNotRetryOrPublishLateCallbacks() {
        val harness = Harness(session)
        harness.observe(session)
        harness.requests[0].second(Result.failure(IllegalStateException("No live session")))
        harness.observe(session.copy(navigation = session.navigation.copy(title = "changed", revision = 10)))
        assertEquals(1, harness.requests.size)
        assertNull(harness.organization)
        harness.controller.close()
        harness.complete(0, information)
        assertNull(harness.organization)
        assertEquals(emptyList<String?>(), harness.changes)
    }

    @Test
    fun latestRuntimeStateAndRenderStateGuardBeforeObserverDelivery() {
        val harness = Harness(session)
        harness.observe(session)
        val other = session.copy(id = SessionId(2))
        harness.state = BrowserState(listOf(other), other.id)
        harness.complete(0, information)
        assertNull(harness.organization)
        harness.observe(session)
        harness.complete(0, information)
        assertEquals("Example", harness.organization)
        assertNull(harness.controller.organizationFor(other))
    }

    @Test
    fun readonlyIdentityKindsDriveIconsAndRejectLateResultsAcrossNavigation() {
        val local = session.copy(navigation = session.navigation.copy(url = "http://localhost/", security = SecurityState.INSECURE))
        val harness = Harness(local, queryAllKinds = true)
        harness.observe(local)
        assertEquals(1, harness.requests.size)
        harness.complete(0, information.copy(kind = "local", origin = "http://localhost", certificate = null))
        assertEquals("local", harness.controller.informationFor(local)?.kind)
        assertNull(harness.organization)
        harness.observe(local.copy(navigation = local.navigation.copy(loading = LoadingState.PENDING)))
        assertNull(harness.controller.informationFor(harness.state.activeSession))
        harness.complete(0, information)
        assertNull(harness.controller.informationFor(harness.state.activeSession))
        val extension = local.copy(navigation = local.navigation.copy(url = "moz-extension://owned/page", security = SecurityState.UNKNOWN))
        harness.observe(extension)
        harness.complete(1, information.copy(kind = "extension", origin = "moz-extension://owned", certificate = null))
        assertEquals("extension", harness.controller.informationFor(extension)?.kind)
        assertNull(harness.organization)
        harness.observe(extension.copy(navigation = extension.navigation.copy(title = "Updated", revision = 9)))
        assertEquals(2, harness.requests.size)
    }

    @Test
    fun organizationTextRemovesControlsAndBidiOverridesAndBoundsInput() {
        assertEquals("Example Inc", sanitizeSiteOrganization(" \u202eExample\u202c Inc\u0000\n\t "))
        assertEquals("ExampleInc", sanitizeSiteOrganization("Example\u2028\u2029\u2066Inc\u2069"))
        assertNull(sanitizeSiteOrganization("\u0000\u202e \u2066"))
        assertEquals(160, sanitizeSiteOrganization("A".repeat(1_000))?.length)
        assertNull(sanitizeSiteOrganization("\u202e".repeat(512) + "Hidden"))
        assertEquals("A".repeat(159), sanitizeSiteOrganization("A".repeat(159) + "\uD83C\uDF10"))
        assertEquals("中文组织 🌐", sanitizeSiteOrganization(" 中文组织 🌐 "))
    }
}
