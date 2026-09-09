// SPDX-License-Identifier: MPL-2.0

package org.navis.browser.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionRestorePolicyTest {
    @Test fun nativeNewTabDiscardsLegacyWebBlobAndFallbackNavigation() {
        assertEquals(SessionRestorePlan(null, null), SessionRestorePolicy.plan(
            "old webpage history", "https://old.example/", null, true,
        ))
    }

    @Test fun nativeSettingsDiscardsOpaqueHistoryWithoutLosingProductIdentity() {
        assertEquals(SessionRestorePlan(null, null), SessionRestorePolicy.plan(
            "old webpage history", "navis://settings/", "settings", false,
        ))
    }

    @Test fun everyNativeRouteUsesOnlyItsNativeSurface() {
        for (route in listOf("newtab", "settings", "bookmarks", "history", "extensions")) {
            assertEquals(SessionRestorePlan(null, null), SessionRestorePolicy.plan(
                "opaque web history", "navis://$route/", route, false,
            ))
        }
    }

    @Test fun ordinaryWebRestorePreservesExactBlobAndNeverAddsFallbackGet() {
        val blob = "opaque exact history including selected entry metadata"
        assertEquals(SessionRestorePlan(blob, null), SessionRestorePolicy.plan(
            blob, "https://selected.example/", null, false,
        ))
    }

    @Test fun ordinaryUrlOnlyRestoreStillLoadsAndAnEmptySessionDoesNot() {
        assertEquals(SessionRestorePlan(null, "https://example.com/"), SessionRestorePolicy.plan(
            null, "https://example.com/", null, false,
        ))
        assertEquals(SessionRestorePlan(null, null), SessionRestorePolicy.plan(null, " ", null, false))
        assertEquals(SessionRestorePlan(null, null), SessionRestorePolicy.plan(null, null, null, false))
    }
}
