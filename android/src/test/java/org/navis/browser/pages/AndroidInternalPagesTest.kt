/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.pages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidInternalPagesTest {
    @Test
    fun everyAdvertisedRouteResolvesToItsNativePage() {
        AndroidInternalPages.pages.forEach { page ->
            assertEquals(page, AndroidInternalPages.resolve(page.uri))
        }
        assertEquals("history", AndroidInternalPages.resolve("navis://history")?.route)
        assertEquals("help", AndroidInternalPages.resolve("navis://settings/help/")?.route)
    }

    @Test
    fun contentUrlsAndAuthoritySpoofingNeverResolveToProductPages() {
        listOf(
            "https://settings/", "about:blank", "navis://unknown/",
            "navis://settings.evil/", "navis://evil@settings/", "navis://settings:443/",
            "navis://settings/?action=clear", "navis://settings/#clear",
            "navis://settings/%68elp", "navis://settings/../history", "navis://settings/\n",
        ).forEach { assertNull(it, AndroidInternalPages.resolve(it)) }
    }
}
