/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.navis.browser.ui.addressInputIsUrl

class AddressInputTest {
    @Test
    fun clipboardActionDistinguishesNavigationFromSearch() {
        assertTrue(addressInputIsUrl("https://example.com/?q=a"))
        assertTrue(addressInputIsUrl("localhost:8080"))
        assertTrue(addressInputIsUrl("navis://history/"))
        assertFalse(addressInputIsUrl("navis browser"))
        assertFalse(addressInputIsUrl("  "))
    }
}
