/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.extensions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionVersionTest {
    @Test
    fun rejectsOlderNumericVersion() {
        assertTrue(compareExtensionVersions("1.73.9", "1.74.0") < 0)
    }

    @Test
    fun acceptsNewerNumericVersion() {
        assertTrue(compareExtensionVersions("1.74.1", "1.74.0") > 0)
    }

    @Test
    fun treatsMissingNumericSegmentsAsZero() {
        assertEquals(0, compareExtensionVersions("2.0", "2.0.0"))
    }

    @Test
    fun ordersPreReleaseBeforeFinalVersion() {
        assertTrue(compareExtensionVersions("2.0b1", "2.0") < 0)
    }

    @Test
    fun comparesNumericSegmentsWithoutOverflow() {
        assertTrue(compareExtensionVersions("999999999999999999999", "999999999999999999998") > 0)
    }
}
