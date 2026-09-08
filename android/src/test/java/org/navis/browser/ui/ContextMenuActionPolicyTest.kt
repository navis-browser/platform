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
import org.navis.browser.api.NavigationState
import org.navis.browser.api.SessionId
import org.navis.browser.api.SessionMode

class ContextMenuActionPolicyTest {
    @Test fun contentCannotOpenPrivilegedOrExternalProtocolsThroughNewTab() {
        listOf("navis://settings", "about:config", "chrome://devtools/content/", "resource://gre/",
            "file:///etc/passwd", "moz-extension://id/page.html", "javascript:alert(1)",
            "intent://other-app", "data:text/html,hello", "https://user:secret@example.com/",
            "https://example.com/\nnavis://settings", "https:example.com").forEach {
            assertNull(it, ContextMenuActionPolicy.openableWebAddress(it))
        }
        listOf("https://example.com/path?q=1", "http://127.0.0.1:8080/", "https://[::1]/").forEach {
            assertEquals(it, ContextMenuActionPolicy.openableWebAddress(it))
        }
    }

    @Test fun menuTokenCanOnlyBeConsumedOnce() {
        val gate = gate()
        var claims = 0
        assertTrue(gate.claim(state()) { claims++; true })
        assertFalse(gate.claim(state()) { claims++; true })
        assertEquals(1, claims)
    }

    @Test fun engineCancelledTokenDoesNotAuthorizeProductAction() {
        val gate = gate()
        assertFalse(gate.claim(state()) { false })
        assertFalse(gate.claim(state()) { true })
    }

    @Test fun tabChangesReloadsNavigationAndCrashInvalidateOldMenu() {
        val gate = gate()
        assertFalse(gate.matches(state(session = 2)))
        assertFalse(gate.matches(state(revision = 5)))
        assertFalse(gate.matches(state(url = "https://different.example/")))
        assertFalse(gate.matches(state(crashed = true)))
        var called = false
        assertFalse(gate.claim(state(revision = 5)) { called = true; true })
        assertFalse(called)
    }

    private fun gate() = ContextMenuActionGate(SessionId(1), "a".repeat(32), "https://example.com/", 4)

    private fun state(session: Long = 1, revision: Long = 4,
        url: String = "https://example.com/", crashed: Boolean = false): BrowserState {
        val id = SessionId(session)
        return BrowserState(listOf(BrowserSessionState(id, SessionMode.NORMAL, true,
            NavigationState(revision = revision, url = url, crashed = crashed))), id)
    }
}
