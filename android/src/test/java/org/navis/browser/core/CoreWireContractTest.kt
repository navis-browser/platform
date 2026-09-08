/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.core

import org.junit.Assert.assertEquals
import org.junit.Test

class CoreWireContractTest {
    @Test
    fun navigationCommandsMatchTheStableCoreAbi() {
        assertEquals(listOf(0, 1, 2, 3, 4), CoreNavigationCommand.entries.map { it.wireValue })
    }

    @Test
    fun navigationSecurityMatchesTheStableCoreAbi() {
        assertEquals(listOf(0, 1, 2, 3), CoreNavigationSecurity.entries.map { it.wireValue })
    }

    @Test
    fun navigationIdentityMatchesTheStableCoreAbi() {
        assertEquals(listOf(0, 1, 2, 3, 4), CoreNavigationIdentity.entries.map { it.wireValue })
    }
}
