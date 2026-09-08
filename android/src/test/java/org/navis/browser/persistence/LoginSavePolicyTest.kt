/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.persistence

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginSavePolicyTest {
    @Test
    fun exactCredentialBoundsRemainAcceptedWithoutRewriting() {
        assertTrue(
            LoginSavePolicy.accepts(
                "u".repeat(LoginSavePolicy.MAX_USERNAME_LENGTH),
                "p".repeat(LoginSavePolicy.MAX_PASSWORD_LENGTH),
            ),
        )
        assertTrue(LoginSavePolicy.accepts("", "password"))
    }

    @Test
    fun eitherOversizedFieldRejectsTheWholeCredential() {
        assertFalse(
            LoginSavePolicy.accepts(
                "u".repeat(LoginSavePolicy.MAX_USERNAME_LENGTH + 1),
                "password",
            ),
        )
        assertFalse(
            LoginSavePolicy.accepts(
                "username",
                "p".repeat(LoginSavePolicy.MAX_PASSWORD_LENGTH + 1),
            ),
        )
    }

    @Test
    fun emptyPasswordRemainsRejected() {
        assertFalse(LoginSavePolicy.accepts("username", ""))
    }
}
