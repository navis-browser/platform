/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.engine.extensions

import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineExtensionPortContractTest {
    @Test
    fun directHostAdvertisesTheCompleteBoundedProductProjection() {
        val capabilities = EngineExtensionCapabilities.DIRECT_PRODUCT_V1_COMPLETE

        assertTrue(capabilities.hasPhaseALifecycle)
        assertTrue(capabilities.isCompleteDirectControlProjection)
        assertTrue(capabilities.isCompleteProductProjection)
        assertTrue(capabilities.privateBrowsingProjection)
        assertTrue(capabilities.actionProjection)
        assertTrue(capabilities.tabProjection)
        assertTrue(capabilities.windowProjection)
        assertTrue(capabilities.popupProjection)
        assertTrue(capabilities.optionsProjection)
        assertTrue(capabilities.contextMenuProjection)
        assertTrue(capabilities.commandProjection)
        assertTrue(capabilities.downloadProjection)
        assertTrue(capabilities.notificationProjection)
        assertTrue(capabilities.browsingDataProjection)
        assertTrue(capabilities.managementProjection)
        assertTrue(capabilities.optionalPermissionProjection)
        assertTrue(capabilities.engineApiModuleProfile)
    }

    @Test
    fun oneMissingNativeSurfaceMakesTheProductProjectionIncomplete() {
        val complete = EngineExtensionCapabilities.DIRECT_PRODUCT_V1_COMPLETE

        assertFalse(complete.copy(contextMenuProjection = false).isCompleteProductProjection)
        assertFalse(complete.copy(commandProjection = false).isCompleteProductProjection)
        assertFalse(complete.copy(downloadProjection = false).isCompleteProductProjection)
        assertFalse(complete.copy(engineApiModuleProfile = false).isCompleteProductProjection)
    }

    @Test
    fun ownedPackageTransfersItsStreamExactlyOnce() {
        val stream = TrackingInputStream()
        val source = OwnedExtensionPackage("extension.xpi", null, stream)

        assertEquals(stream, source.takeStream())
        source.close()

        assertFalse(stream.closed)
        assertThrows(IllegalStateException::class.java) { source.takeStream() }
        stream.close()
    }

    @Test
    fun closingAnUnclaimedPackageClosesTheSource() {
        val stream = TrackingInputStream()
        val source = OwnedExtensionPackage("extension.xpi", 3, stream)

        source.close()
        source.close()

        assertTrue(stream.closed)
    }

    @Test
    fun inventoryRejectsTemporaryEntriesWhenDeveloperModeIsOff() {
        val temporary = record(
            source = EngineExtensionSource.TEMPORARY,
            signature = EngineExtensionSignature.UNVERIFIED,
        )

        assertThrows(IllegalArgumentException::class.java) {
            EngineExtensionInventory(
                revision = 1,
                developerModeEnabled = false,
                processSpawningDisabled = false,
                extensions = listOf(temporary),
            )
        }
    }

    @Test
    fun inventoryRejectsDuplicateIdentityAndEnabledForeignSideLoads() {
        val managed = record()
        assertThrows(IllegalArgumentException::class.java) {
            EngineExtensionInventory(
                revision = 1,
                developerModeEnabled = true,
                processSpawningDisabled = false,
                extensions = listOf(managed, managed),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            record(source = EngineExtensionSource.BLOCKED_SIDELOAD, enabled = true)
        }
    }

    @Test
    fun onlyRegistryBuiltInsCanClaimApplicationVerification() {
        assertThrows(IllegalArgumentException::class.java) {
            record(signature = EngineExtensionSignature.APPLICATION_VERIFIED)
        }
        assertThrows(IllegalArgumentException::class.java) {
            record(
                source = EngineExtensionSource.APPLICATION_BUILT_IN,
                signature = EngineExtensionSignature.MOZILLA_SIGNED,
            )
        }
        record(
            source = EngineExtensionSource.APPLICATION_BUILT_IN,
            signature = EngineExtensionSignature.APPLICATION_VERIFIED,
        )
    }

    @Test
    fun previewBoundsTokenAndPackageSize() {
        val valid = preview(packageBytes = MAX_ENGINE_EXTENSION_PACKAGE_BYTES)
        assertFalse(valid.isUpdate)
        assertTrue(valid.copy(existingVersion = "1.0").isUpdate)

        assertThrows(IllegalArgumentException::class.java) { preview(packageBytes = 0) }
        assertThrows(IllegalArgumentException::class.java) {
            preview(packageBytes = MAX_ENGINE_EXTENSION_PACKAGE_BYTES + 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            preview(token = "product-generated-token")
        }
    }

    @Test
    fun actionProjectionRequiresAnExactKindAndAvailability() {
        assertThrows(IllegalArgumentException::class.java) {
            record().copy(hasAction = true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            record().copy(actionKind = EngineExtensionActionKind.BROWSER)
        }
        assertThrows(IllegalArgumentException::class.java) {
            record().copy(actionEnabled = true)
        }

        record().copy(
            hasAction = true,
            actionKind = EngineExtensionActionKind.PAGE,
            actionEnabled = true,
        )
    }

    @Test
    fun iconPixelsAreBoundedImmutableAndOnlyActionsExposeActionIcons() {
        val source = intArrayOf(1, 2, 3, 4)
        val icon = EngineExtensionIcon(2, 2, source)
        source.fill(0)
        assertArrayEquals(intArrayOf(1, 2, 3, 4), icon.copyArgbPixels())

        val copy = icon.copyArgbPixels()
        copy.fill(0)
        assertArrayEquals(intArrayOf(1, 2, 3, 4), icon.copyArgbPixels())
        assertEquals(EngineExtensionIcon(2, 2, intArrayOf(1, 2, 3, 4)), icon)

        assertThrows(IllegalArgumentException::class.java) {
            EngineExtensionIcon(2, 2, intArrayOf(1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            record().copy(actionIcon = icon)
        }
        record().copy(
            hasAction = true,
            actionKind = EngineExtensionActionKind.BROWSER,
            actionIcon = icon,
        )
    }

    @Test
    fun popupIdentityIncludesExactSourceTabAndPrivacyMode() {
        EngineExtensionPopup(
            extensionId = "extension@example",
            title = "Extension",
            targetToken = "00000000000000000000000000000000",
            popupUri = "moz-extension://01234567-89ab-cdef-0123-456789abcdef/popup.html",
            sourceTabId = 7,
            privateMode = true,
        )
        assertThrows(IllegalArgumentException::class.java) {
            EngineExtensionPopup(
                extensionId = "extension@example",
                title = "Extension",
                targetToken = "00000000000000000000000000000000",
                popupUri = "moz-extension://01234567-89ab-cdef-0123-456789abcdef/popup.html",
                sourceTabId = 0,
                privateMode = false,
            )
        }
    }

    @Test
    fun optionalPermissionRequestsAreBoundedAndTabCorrelated() {
        EngineExtensionOptionalPermissionRequest(
            token = "00000000000000000000000000000000",
            extensionId = "extension@example",
            extensionName = "Extension",
            extensionVersion = "1.0",
            sourceTabId = 7,
            privateMode = false,
            permissions = listOf("clipboardWrite"),
            origins = listOf("https://example.com/*"),
            dataCollectionPermissions = emptyList(),
        )
        assertThrows(IllegalArgumentException::class.java) {
            EngineExtensionOptionalPermissionRequest(
                token = "invalid",
                extensionId = "extension@example",
                extensionName = "Extension",
                extensionVersion = "1.0",
                sourceTabId = 7,
                privateMode = false,
                permissions = emptyList(),
                origins = emptyList(),
                dataCollectionPermissions = emptyList(),
            )
        }
    }

    @Test
    fun productTabCommandsRejectAmbiguousTargetsAndBrowsingModes() {
        EngineExtensionTabRequest(
            extensionId = "extension@example",
            operation = EngineExtensionTabOperation.CREATE,
            url = "https://example.com/",
            active = true,
            privateMode = false,
            index = 2,
        )
        EngineExtensionTabRequest(
            extensionId = "extension@example",
            operation = EngineExtensionTabOperation.UPDATE,
            tabId = 7,
            active = true,
        )
        EngineExtensionTabRequest(
            extensionId = "extension@example",
            operation = EngineExtensionTabOperation.REMOVE,
            tabId = 7,
        )
        EngineExtensionTabRequest(
            extensionId = "extension@example",
            operation = EngineExtensionTabOperation.MOVE,
            tabId = 7,
            index = 1,
        )

        assertThrows(IllegalArgumentException::class.java) {
            EngineExtensionTabRequest(
                extensionId = "extension@example",
                operation = EngineExtensionTabOperation.CREATE,
                tabId = 7,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            EngineExtensionTabRequest(
                extensionId = "extension@example",
                operation = EngineExtensionTabOperation.UPDATE,
                privateMode = true,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            EngineExtensionTabRequest(
                extensionId = "extension@example",
                operation = EngineExtensionTabOperation.REMOVE,
                tabId = 7,
                active = false,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            EngineExtensionTabRequest(
                extensionId = "extension@example",
                operation = EngineExtensionTabOperation.MOVE,
                tabId = 7,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            EngineExtensionTabRequest(
                extensionId = "extension@example",
                operation = EngineExtensionTabOperation.UPDATE,
                tabId = 7,
                index = 0,
            )
        }
    }

    private fun record(
        source: EngineExtensionSource = EngineExtensionSource.MANAGED_PROFILE,
        signature: EngineExtensionSignature = EngineExtensionSignature.MOZILLA_SIGNED,
        enabled: Boolean = false,
    ) = EngineExtensionRecord(
        id = "extension@example",
        name = "Extension",
        description = "",
        version = "1.0",
        source = source,
        signature = signature,
        enabled = enabled,
        privateBrowsingAllowed = false,
        privateBrowsingAvailable = source == EngineExtensionSource.MANAGED_PROFILE,
        pinnedToToolbar = false,
        canChangeEnabled = source != EngineExtensionSource.BLOCKED_SIDELOAD,
        canUninstall = source != EngineExtensionSource.APPLICATION_BUILT_IN,
        canUpdate = source == EngineExtensionSource.MANAGED_PROFILE,
        hasAction = false,
        hasOptions = false,
        requiredPermissions = emptyList(),
        requiredOrigins = emptyList(),
    )

    private fun preview(
        token: String = "00000000000000000000000000000000",
        packageBytes: Long = 1,
    ) = EngineExtensionInstallPreview(
        token = token,
        id = "extension@example",
        name = "Extension",
        description = "",
        version = "1.0",
        existingVersion = null,
        packageName = "extension.xpi",
        packageBytes = packageBytes,
        requiredPermissions = emptyList(),
        requiredOrigins = emptyList(),
        requiredDataCollectionPermissions = emptyList(),
    )

    private class TrackingInputStream : ByteArrayInputStream(byteArrayOf(1, 2, 3)) {
        var closed = false

        override fun close() {
            closed = true
            super.close()
        }
    }
}
