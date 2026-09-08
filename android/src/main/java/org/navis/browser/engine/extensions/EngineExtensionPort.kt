/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.engine.extensions

import java.io.InputStream
import java.util.concurrent.CompletionStage

/** Hard limit enforced again by the private engine peer while staging a local XPI. */
internal const val MAX_ENGINE_EXTENSION_PACKAGE_BYTES: Long = 200L * 1024L * 1024L

/** The owner and persistence class of one extension known to the direct engine. */
internal enum class EngineExtensionSource {
    APPLICATION_BUILT_IN,
    MANAGED_PROFILE,
    BLOCKED_SIDELOAD,
    TEMPORARY,
}

internal enum class EngineExtensionSignature {
    APPLICATION_VERIFIED,
    MOZILLA_SIGNED,
    UNVERIFIED,
}

internal enum class EngineExtensionActionKind {
    BROWSER,
    PAGE,
}

internal class EngineExtensionIcon(
    val width: Int,
    val height: Int,
    argbPixels: IntArray,
) {
    private val pixels = argbPixels.copyOf()

    init {
        require(width in 1..128 && height in 1..128) { "Invalid extension icon dimensions" }
        require(pixels.size == width * height) { "Invalid extension icon pixel buffer" }
    }

    fun copyArgbPixels(): IntArray = pixels.copyOf()

    override fun equals(other: Any?): Boolean =
        other is EngineExtensionIcon &&
            width == other.width &&
            height == other.height &&
            pixels.contentEquals(other.pixels)

    override fun hashCode(): Int = 31 * (31 * width + height) + pixels.contentHashCode()
}

/**
 * Capabilities of the independent extension backend.
 *
 * The direct Navis host owns all extension lifecycle and UI projections. Gecko
 * implementation objects remain behind the Java/JS peer and are never exposed
 * through this DTO-only interface.
 */
internal data class EngineExtensionCapabilities(
    val builtInLifecycle: Boolean,
    val signedLocalPackages: Boolean,
    val temporaryInventory: Boolean,
    val privateBrowsingProjection: Boolean,
    val actionProjection: Boolean,
    val tabProjection: Boolean,
    val windowProjection: Boolean,
    val popupProjection: Boolean,
    val optionsProjection: Boolean,
    val contextMenuProjection: Boolean,
    val commandProjection: Boolean,
    val downloadProjection: Boolean,
    val notificationProjection: Boolean,
    val browsingDataProjection: Boolean,
    val managementProjection: Boolean,
    val optionalPermissionProjection: Boolean,
    /** Audited engine-resident APIs such as storage, cookies, scripting and webRequest. */
    val engineApiModuleProfile: Boolean,
) {
    val hasPhaseALifecycle: Boolean
        get() = builtInLifecycle && signedLocalPackages && temporaryInventory

    /** Lifecycle/control completeness only; product API coverage is gated separately. */
    val isCompleteDirectControlProjection: Boolean
        get() = hasPhaseALifecycle &&
            privateBrowsingProjection &&
            actionProjection &&
            tabProjection &&
            windowProjection &&
            popupProjection &&
            optionsProjection

    /**
     * Complete Navis 1.0 product projection. This is deliberately stronger than lifecycle/control:
     * every engine-to-Platform surface in the accepted Android extension profile must be present.
     */
    val isCompleteProductProjection: Boolean
        get() = isCompleteDirectControlProjection &&
            contextMenuProjection &&
            commandProjection &&
            downloadProjection &&
            notificationProjection &&
            browsingDataProjection &&
            managementProjection &&
            optionalPermissionProjection &&
            engineApiModuleProfile

    companion object {
        /** Complete direct-runtime product projection for the bounded Navis 1.0 API profile. */
        val DIRECT_PRODUCT_V1_COMPLETE = EngineExtensionCapabilities(
            builtInLifecycle = true,
            signedLocalPackages = true,
            temporaryInventory = true,
            privateBrowsingProjection = true,
            actionProjection = true,
            tabProjection = true,
            windowProjection = true,
            popupProjection = true,
            optionsProjection = true,
            contextMenuProjection = true,
            commandProjection = true,
            downloadProjection = true,
            notificationProjection = true,
            browsingDataProjection = true,
            managementProjection = true,
            optionalPermissionProjection = true,
            engineApiModuleProfile = true,
        )
    }
}

internal data class EngineExtensionRecord(
    val id: String,
    val name: String,
    val description: String,
    val version: String,
    val source: EngineExtensionSource,
    val signature: EngineExtensionSignature,
    val enabled: Boolean,
    val privateBrowsingAllowed: Boolean,
    val privateBrowsingAvailable: Boolean,
    val pinnedToToolbar: Boolean,
    val canChangeEnabled: Boolean,
    val canUninstall: Boolean,
    val canUpdate: Boolean,
    val hasAction: Boolean,
    val actionKind: EngineExtensionActionKind? = null,
    val actionEnabled: Boolean = false,
    val hasOptions: Boolean,
    val requiredPermissions: List<String>,
    val requiredOrigins: List<String>,
    /** Action values are plain snapshots; action implementation objects never cross this port. */
    val actionTitle: String? = null,
    val icon: EngineExtensionIcon? = null,
    val actionIcon: EngineExtensionIcon? = null,
    val actionBadgeText: String = "",
    val actionBadgeTextColor: Int? = null,
    val actionBadgeBackgroundColor: Int? = null,
    val actionSourceTabId: Long = 0,
) {
    init {
        require(id.isNotBlank() && id.length <= 256) { "Invalid extension ID" }
        require(name.length <= 256) { "Extension name is too long" }
        require(description.length <= 1024) { "Extension description is too long" }
        require(version.isNotBlank() && version.length <= 128) { "Invalid extension version" }
        require(actionSourceTabId in 0..9_007_199_254_740_991L) { "Invalid action source tab" }
        require(requiredPermissions.size <= 128) { "Too many required permissions" }
        require(requiredOrigins.size <= 128) { "Too many required origins" }
        require(requiredPermissions.all { it.isNotBlank() && it.length <= 2048 }) {
            "Invalid required permission"
        }
        require(requiredOrigins.all { it.isNotBlank() && it.length <= 2048 }) {
            "Invalid required origin"
        }
        require(source != EngineExtensionSource.BLOCKED_SIDELOAD || !enabled) {
            "A blocked side-load cannot be enabled"
        }
        require(
            source != EngineExtensionSource.APPLICATION_BUILT_IN ||
                signature == EngineExtensionSignature.APPLICATION_VERIFIED,
        ) { "Application built-ins must come from the verified registry" }
        require(
            source == EngineExtensionSource.APPLICATION_BUILT_IN ||
                signature != EngineExtensionSignature.APPLICATION_VERIFIED,
        ) { "Only application built-ins may use application verification" }
        require(hasAction == (actionKind != null)) {
            "An extension action must declare its projected kind"
        }
        require(hasAction || !actionEnabled) { "An unavailable action cannot be enabled" }
        require(hasAction || actionIcon == null) { "An unavailable action cannot expose an icon" }
    }
}

/** One immutable, monotonically revisioned process-wide engine snapshot. */
internal data class EngineExtensionInventory(
    val revision: Long,
    val developerModeEnabled: Boolean,
    val processSpawningDisabled: Boolean,
    val extensions: List<EngineExtensionRecord>,
) {
    init {
        require(revision >= 0) { "Extension inventory revision must not be negative" }
        require(extensions.size <= 256) { "Extension inventory is too large" }
        require(extensions.map { it.id }.toSet().size == extensions.size) {
            "Extension inventory contains duplicate IDs"
        }
        require(
            developerModeEnabled ||
                extensions.none { it.source == EngineExtensionSource.TEMPORARY },
        ) { "Temporary extensions must be hidden outside developer mode" }
    }
}

internal data class EngineExtensionInstallPreview(
    val token: String,
    val id: String,
    val name: String,
    val description: String,
    val version: String,
    val existingVersion: String?,
    val packageName: String,
    val packageBytes: Long,
    val requiredPermissions: List<String>,
    val requiredOrigins: List<String>,
    val requiredDataCollectionPermissions: List<String>,
) {
    init {
        require(token.matches(Regex("[0-9a-f]{32}"))) { "Invalid install preview token" }
        require(id.isNotBlank() && id.length <= 256) { "Invalid extension ID" }
        require(name.length <= 256) { "Extension name is too long" }
        require(description.length <= 1024) { "Extension description is too long" }
        require(version.isNotBlank() && version.length <= 128) { "Invalid extension version" }
        require(existingVersion == null || existingVersion.length <= 128) {
            "Existing extension version is too long"
        }
        require(packageName.isNotBlank() && packageName.length <= 256) {
            "Invalid package name"
        }
        require(packageBytes in 1..MAX_ENGINE_EXTENSION_PACKAGE_BYTES) {
            "Extension package is outside the accepted size range"
        }
        require(requiredPermissions.size <= 128) { "Too many required permissions" }
        require(requiredOrigins.size <= 128) { "Too many required origins" }
        require(requiredDataCollectionPermissions.size <= 128) {
            "Too many data-collection permissions"
        }
    }

    val isUpdate: Boolean
        get() = existingVersion != null
}

internal enum class EngineExtensionInstallOutcome {
    INSTALLED,
    UPDATED,
}

internal data class EngineExtensionInstallResult(
    val outcome: EngineExtensionInstallOutcome,
    val extensionId: String,
    val inventory: EngineExtensionInventory,
)

/** A popup render target owned by the Navis engine chrome host. */
internal data class EngineExtensionPopup(
    val extensionId: String,
    val title: String,
    val targetToken: String,
    val popupUri: String,
    val sourceTabId: Long,
    val privateMode: Boolean,
) {
    init {
        require(extensionId.isNotBlank() && extensionId.length <= 256)
        require(title.length <= 512)
        require(targetToken.matches(Regex("[0-9a-f]{32}")))
        require(popupUri.length <= 1024 && popupUri.startsWith("moz-extension://")) {
            "Invalid extension popup URI"
        }
        require(sourceTabId > 0) { "Invalid popup source tab" }
    }
}

internal enum class EngineExtensionTabOperation {
    CREATE,
    UPDATE,
    REMOVE,
    MOVE,
}

/** Trusted command emitted by Gecko's extension API and executed by the Navis product Runtime. */
internal data class EngineExtensionTabRequest(
    val extensionId: String,
    val operation: EngineExtensionTabOperation,
    val tabId: Long? = null,
    val url: String? = null,
    val active: Boolean? = null,
    val privateMode: Boolean? = null,
    val index: Int? = null,
    val windowId: Long? = null,
) {
    init {
        require(extensionId.isNotBlank() && extensionId.length <= 256) {
            "Invalid extension tab command identity"
        }
        require(tabId == null || tabId > 0) { "Invalid extension tab ID" }
        require(windowId == null || windowId > 0) { "Invalid extension window ID" }
        require(url == null || (url.isNotBlank() && url.length <= 16 * 1024)) {
            "Invalid extension tab URL"
        }
        when (operation) {
            EngineExtensionTabOperation.CREATE -> require(tabId == null) {
                "Create must not target an existing tab"
            }
            EngineExtensionTabOperation.UPDATE,
            EngineExtensionTabOperation.REMOVE,
            EngineExtensionTabOperation.MOVE -> require(tabId != null) {
                "$operation requires a target tab"
            }
        }
        require(
            operation != EngineExtensionTabOperation.REMOVE ||
                (url == null && active == null && index == null),
        ) {
            "Remove does not accept update properties"
        }
        require(operation == EngineExtensionTabOperation.CREATE || privateMode == null) {
            "Only create may select a browsing mode"
        }
        require(index == null || index >= 0) { "Invalid extension tab index" }
        require(
            operation == EngineExtensionTabOperation.CREATE ||
                operation == EngineExtensionTabOperation.MOVE || index == null,
        ) {
            "Only create and move accept an index"
        }
        require(
            operation != EngineExtensionTabOperation.MOVE ||
                (index != null && url == null && active == null),
        ) {
            "Move requires only a destination index"
        }
    }
}

internal data class EngineExtensionTabResult(
    val tabId: Long,
    val windowId: Long,
    val index: Int,
    val active: Boolean,
    val privateMode: Boolean,
) {
    init {
        require(tabId > 0) { "Invalid extension tab result" }
        require(windowId > 0) { "Invalid extension window result" }
        require(index >= 0) { "Invalid extension tab result index" }
    }
}

internal fun interface EngineExtensionTabDelegate {
    fun onTabCommand(request: EngineExtensionTabRequest): CompletionStage<EngineExtensionTabResult>
}

internal enum class EngineExtensionWindowOperation { CREATE, FOCUS, UPDATE, REMOVE }

internal data class EngineExtensionWindowRequest(
    val extensionId: String,
    val operation: EngineExtensionWindowOperation,
    val sourceWindowId: Long,
    val windowId: Long? = null,
    val privateMode: Boolean? = null,
    val urls: List<String> = emptyList(),
    val focused: Boolean? = null,
    val state: EngineExtensionWindowState? = null,
    val drawAttention: Boolean? = null,
    val authorizationToken: String? = null,
    val extensionName: String = "",
    val left: Int? = null,
    val top: Int? = null,
    val width: Int? = null,
    val height: Int? = null,
    /** Captured from Gecko's privileged call context, never from extension option properties. */
    val userActivation: Boolean = false,
) {
    init {
        require(extensionId.isNotBlank() && extensionId.length <= 256)
        require(sourceWindowId in 1..9_007_199_254_740_991L)
        require(windowId == null || windowId in 1..9_007_199_254_740_991L)
        require(urls.size <= 128 && urls.all { it.isNotBlank() && it.length <= 16 * 1024 && !it.any(Char::isISOControl) })
        require(authorizationToken == null || authorizationToken.matches(Regex("[0-9a-f]{32}")))
        require(extensionName.length <= 256 && !extensionName.any(Char::isISOControl))
        require(state != EngineExtensionWindowState.MINIMIZED || focused != true)
        if (operation == EngineExtensionWindowOperation.CREATE) require(windowId == null && privateMode != null)
        else require(windowId != null && privateMode == null && urls.isEmpty())
        if (operation == EngineExtensionWindowOperation.REMOVE || operation == EngineExtensionWindowOperation.FOCUS) {
            require(focused == null && state == null && drawAttention == null)
        }
        if (operation == EngineExtensionWindowOperation.CREATE) require(drawAttention == null)
        if (listOf(left, top, width, height).any { it != null }) {
            require(operation in setOf(EngineExtensionWindowOperation.CREATE, EngineExtensionWindowOperation.UPDATE) &&
                (state == null || state == EngineExtensionWindowState.NORMAL))
            require(listOfNotNull(left, top).all { it in -1_000_000..1_000_000 })
            require(listOfNotNull(width, height).all { it in 100..32_768 })
        }
    }
}

internal data class EngineExtensionWindowResult(val windowId: Long, val removed: Boolean = false, val attention: Boolean = false) {
    init { require(windowId in 1..9_007_199_254_740_991L) }
}

internal fun interface EngineExtensionWindowDelegate {
    fun onWindowCommand(request: EngineExtensionWindowRequest): CompletionStage<EngineExtensionWindowResult>
}

/**
 * One user-gesture-correlated optional-permission request from the engine.
 *
 * The token is transport correlation only. The product must also revalidate the exact active
 * Session and extension identity before presenting the request and before returning the answer.
 */
internal data class EngineExtensionOptionalPermissionRequest(
    val token: String,
    val extensionId: String,
    val extensionName: String,
    val extensionVersion: String,
    val sourceTabId: Long,
    val privateMode: Boolean,
    val permissions: List<String>,
    val origins: List<String>,
    val dataCollectionPermissions: List<String>,
) {
    init {
        require(token.matches(Regex("[0-9a-f]{32}"))) { "Invalid optional-permission token" }
        require(extensionId.isNotBlank() && extensionId.length <= 256) {
            "Invalid optional-permission extension identity"
        }
        require(extensionName.length <= 256) { "Extension name is too long" }
        require(extensionVersion.isNotBlank() && extensionVersion.length <= 128) {
            "Invalid extension version"
        }
        require(sourceTabId > 0) { "Invalid optional-permission source tab" }
        require(permissions.size <= 128 && origins.size <= 128 && dataCollectionPermissions.size <= 128) {
            "Too many optional permissions"
        }
        require(
            (permissions + origins + dataCollectionPermissions).all {
                it.isNotBlank() && it.length <= 2048
            },
        ) { "Invalid optional permission" }
        require(permissions.isNotEmpty() || origins.isNotEmpty() || dataCollectionPermissions.isNotEmpty()) {
            "An optional-permission request must not be empty"
        }
    }
}

internal fun interface EngineExtensionOptionalPermissionDelegate {
    fun onOptionalPermissionRequested(
        request: EngineExtensionOptionalPermissionRequest,
    ): CompletionStage<Boolean>
}

/**
 * A local package whose stream ownership transfers to [EngineExtensionPort.prepareLocalInstall].
 *
 * The port must close the package on every path, including synchronous validation failure and
 * cancellation. Once [takeStream] succeeds, this wrapper no longer closes the transferred stream.
 */
internal class OwnedExtensionPackage(
    val displayName: String,
    val declaredBytes: Long?,
    stream: InputStream,
) : AutoCloseable {
    private var ownedStream: InputStream? = stream

    init {
        require(displayName.isNotBlank() && displayName.length <= 256) {
            "Invalid extension package name"
        }
        require(declaredBytes == null || declaredBytes >= 0) {
            "Declared package size must not be negative"
        }
    }

    @Synchronized
    fun takeStream(): InputStream {
        val stream = checkNotNull(ownedStream) { "Extension package stream ownership was consumed" }
        ownedStream = null
        return stream
    }

    @Synchronized
    override fun close() {
        ownedStream?.close()
        ownedStream = null
    }
}

internal fun interface EngineExtensionObserver {
    fun onInventoryChanged(inventory: EngineExtensionInventory)
}

/** Delivers an engine-initiated action popup without transferring popup lifecycle ownership. */
internal fun interface EngineExtensionPopupObserver {
    fun onPopupRequested(popup: EngineExtensionPopup)
}

/**
 * Product-neutral asynchronous contract for the direct Android extension backend.
 *
 * Gecko objects, Android URIs and UI objects are deliberately absent. A later adapter is the sole
 * place that may translate this contract to the private Java peer.
 */
internal interface EngineExtensionPort : AutoCloseable {
    val capabilities: EngineExtensionCapabilities

    fun initialize(): CompletionStage<EngineExtensionInventory>

    fun inventory(): CompletionStage<EngineExtensionInventory>

    /** Revalidate the original live Gecko command, not a caller-supplied permission boolean. */
    fun authorizeWindowCommand(token: String, targetWindowId: Long?): CompletionStage<Boolean> =
        java.util.concurrent.CompletableFuture.completedFuture(false)

    fun prepareLocalInstall(source: OwnedExtensionPackage): CompletionStage<EngineExtensionInstallPreview>

    fun confirmLocalInstall(token: String): CompletionStage<EngineExtensionInstallResult>

    fun cancelLocalInstall(token: String): CompletionStage<EngineExtensionInventory>

    fun setEnabled(id: String, enabled: Boolean): CompletionStage<EngineExtensionInventory>

    fun setPrivateBrowsingAllowed(
        id: String,
        allowed: Boolean,
    ): CompletionStage<EngineExtensionInventory>

    fun setPinnedToToolbar(id: String, pinned: Boolean): CompletionStage<EngineExtensionInventory>

    /** Opens the canonical manifest options document through the product-owned tab bridge. */
    fun openOptionsPage(id: String): CompletionStage<Unit>

    /** Dispatches the action only if the exact product Session is still active. */
    fun invokeAction(
        id: String,
        sourceTabId: Long,
        privateMode: Boolean,
    ): CompletionStage<EngineExtensionPopup?>

    fun dismissPopup(): CompletionStage<Unit>

    /** Revokes every action grant tied to the previously active product Session. */
    fun notifyActiveTabChanged(): CompletionStage<Unit>

    /** Window-local selection changed; other windows' active-tab grants remain valid. */
    fun notifyActiveTabChanged(previousTabId: Long): CompletionStage<Unit> =
        java.util.concurrent.CompletableFuture<Unit>().apply {
            completeExceptionally(UnsupportedOperationException("Window-local active tab cleanup is unavailable"))
        }

    /** Revokes state tied to a tab before the product destroys its native Session. */
    fun notifyTabClosed(tabId: Long): CompletionStage<Unit>

    fun uninstall(id: String): CompletionStage<EngineExtensionInventory>

    /** Completes only after temporary-extension and debugger state reaches the requested policy. */
    fun setDeveloperMode(enabled: Boolean): CompletionStage<EngineExtensionInventory>

    fun addObserver(observer: EngineExtensionObserver)

    fun removeObserver(observer: EngineExtensionObserver)

    fun addPopupObserver(observer: EngineExtensionPopupObserver)

    fun removePopupObserver(observer: EngineExtensionPopupObserver)

    fun bindTabDelegate(delegate: EngineExtensionTabDelegate)

    fun unbindTabDelegate(delegate: EngineExtensionTabDelegate)

    fun bindWindowDelegate(delegate: EngineExtensionWindowDelegate)

    fun unbindWindowDelegate(delegate: EngineExtensionWindowDelegate)

    /** Replaces Gecko's private tab read model before lifecycle events are released. */
    fun updateTabTopology(topology: EngineExtensionTabTopology): CompletionStage<Unit>

    fun bindOptionalPermissionDelegate(delegate: EngineExtensionOptionalPermissionDelegate)

    fun unbindOptionalPermissionDelegate(delegate: EngineExtensionOptionalPermissionDelegate)
}
