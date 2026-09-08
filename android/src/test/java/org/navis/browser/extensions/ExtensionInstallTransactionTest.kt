/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.extensions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionInstallTransactionTest {
    @Test
    fun deniedInstallAndUpdatePromptsSuppressOnlyTheirOwnCancellationError() {
        for (kind in listOf(ExtensionPermissionKind.INSTALL, ExtensionPermissionKind.UPDATE)) {
            val transaction = ExtensionInstallTransaction(1)
            transaction.recordPromptResponse(kind, allowed = false)

            assertNull(transaction.failureNotice(null, ExtensionNotice.INSTALL_FAILED))
            assertEquals(
                ExtensionNotice.UNSUPPORTED_CAPABILITY,
                transaction.failureNotice(
                    ExtensionNotice.UNSUPPORTED_CAPABILITY,
                    ExtensionNotice.INSTALL_FAILED,
                ),
            )
        }
    }

    @Test
    fun optionalPermissionDenialCannotSuppressAnInstallError() {
        val transaction = ExtensionInstallTransaction(1)
        transaction.recordPromptResponse(ExtensionPermissionKind.OPTIONAL, allowed = false)

        assertEquals(
            ExtensionNotice.INSTALL_FAILED,
            transaction.failureNotice(null, ExtensionNotice.INSTALL_FAILED),
        )
    }

    @Test
    fun denialNeverLeaksIntoTheNextTransaction() {
        val rejected = ExtensionInstallTransaction(1)
        rejected.recordPromptResponse(ExtensionPermissionKind.INSTALL, allowed = false)
        val next = ExtensionInstallTransaction(2)

        assertNull(rejected.failureNotice(null, ExtensionNotice.INSTALL_FAILED))
        assertEquals(
            ExtensionNotice.INSTALL_FAILED,
            next.failureNotice(null, ExtensionNotice.INSTALL_FAILED),
        )
    }

    @Test
    fun updateIdentityIsLocalToTheTransaction() {
        val transaction = ExtensionInstallTransaction(1).apply {
            existingExtensionId = "extension@example"
        }

        assertTrue(transaction.wasUpdate("extension@example"))
        assertFalse(transaction.wasUpdate("other@example"))
    }
}
