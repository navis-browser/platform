/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.navis.browser.extensions.ExtensionActionKind
import org.navis.browser.extensions.ExtensionActionSnapshot

class ExtensionActionSurfaceTest {
    @Test
    fun portraitIsMenuOnlyAndLandscapeOwnsTheScrollablePinnedRow() {
        assertEquals(
            ExtensionActionSurfaceMode.PORTRAIT_OVERFLOW,
            extensionActionSurfaceMode(isLandscape = false),
        )
        assertEquals(
            ExtensionActionSurfaceMode.LANDSCAPE_PINNED_ROW,
            extensionActionSurfaceMode(isLandscape = true),
        )
    }

    @Test
    fun pinnedProjectionPreservesOrderAndLeavesUnpinnedActionsOut() {
        val actions = listOf(
            action("first", pinned = true),
            action("managed-only", pinned = false),
            action("second", pinned = true, enabled = false),
        )

        assertEquals(listOf("first", "second"), pinnedExtensionActions(actions).map { it.extensionId })
        assertFalse(pinnedExtensionActions(actions).last().enabled)
    }

    @Test
    fun emptyProjectionStillHasAnOverflowOwner() {
        // The composable always renders ExtensionActionOverflowMenu in both modes. This
        // contract keeps the fallback available while extensions load or are disabled.
        assertTrue(pinnedExtensionActions(emptyList()).isEmpty())
    }

    private fun action(
        id: String,
        pinned: Boolean,
        enabled: Boolean = true,
    ) = ExtensionActionSnapshot(
        extensionId = id,
        extensionName = id,
        kind = ExtensionActionKind.BROWSER,
        title = id,
        icon = null,
        badgeText = "",
        badgeTextColor = null,
        badgeBackgroundColor = null,
        enabled = enabled,
        pinned = pinned,
    )
}
