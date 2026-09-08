/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.extensions

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.navis.browser.engine.extensions.EngineExtensionOptionalPermissionRequest

class ExtensionOptionalPermissionTransactionTest {
    @Test
    fun responseCompletesExactlyOnce() {
        val transaction = transaction()

        assertTrue(transaction.finish(true))
        assertFalse(transaction.finish(false))
        assertTrue(transaction.completion.join())
    }

    @Test
    fun lifecycleOwnershipUsesExactTabAndExtension() {
        val transaction = transaction()

        assertTrue(transaction.belongsToTab(17))
        assertFalse(transaction.belongsToTab(18))
        assertTrue(transaction.belongsToExtension("extension@example"))
        assertFalse(transaction.belongsToExtension("other@example"))
    }

    private fun transaction() = ExtensionOptionalPermissionTransaction(
        uiToken = 4,
        request = EngineExtensionOptionalPermissionRequest(
            token = "00000000000000000000000000000000",
            extensionId = "extension@example",
            extensionName = "Extension",
            extensionVersion = "1.0",
            sourceTabId = 17,
            privateMode = false,
            permissions = listOf("clipboardWrite"),
            origins = emptyList(),
            dataCollectionPermissions = emptyList(),
        ),
    )
}
