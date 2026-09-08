/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.engine.extensions

import android.content.Context
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import org.mozilla.gecko.navis.NavisAndroidExtensions

/**
 * Product-side adapter for the Navis-private Java extension transport.
 *
 * No engine object crosses this class: the only values exposed to callers are the DTOs declared by
 * [EngineExtensionPort]. The Java peer owns the EventDispatcher and the bounded staging stream;
 * this adapter only translates immutable responses and forwards ownership of [OwnedExtensionPackage].
 */
internal class NavisAndroidExtensionPort private constructor(
    private val peer: NavisAndroidExtensions,
) : EngineExtensionPort {
    override val capabilities: EngineExtensionCapabilities =
        EngineExtensionCapabilities.DIRECT_PRODUCT_V1_COMPLETE

    private val observers = linkedSetOf<EngineExtensionObserver>()
    private val popupObservers = linkedSetOf<EngineExtensionPopupObserver>()
    private val iconCache = ConcurrentHashMap<IconCacheKey, CompletionStage<EngineExtensionIcon?>>()
    private var tabDelegate: EngineExtensionTabDelegate? = null
    private var windowDelegate: EngineExtensionWindowDelegate? = null
    private var optionalPermissionDelegate: EngineExtensionOptionalPermissionDelegate? = null
    private var closed = false
    @Volatile private var processSpawningDisabled = false

    override fun initialize(): CompletionStage<EngineExtensionInventory> =
        peer.initialize().thenCompose(::inventory)

    override fun inventory(): CompletionStage<EngineExtensionInventory> =
        peer.inventory().thenCompose(::inventory)

    override fun prepareLocalInstall(
        source: OwnedExtensionPackage,
    ): CompletionStage<EngineExtensionInstallPreview> {
        return try {
            ensureOpen()
            val stream = source.takeStream()
            try {
                peer.prepareLocalInstall(
                    stream,
                    source.displayName,
                    source.declaredBytes ?: -1L,
                ).thenApply(::preview)
            } catch (error: Throwable) {
                stream.close()
                throw error
            }
        } catch (error: Throwable) {
            source.close()
            failed(error)
        }
    }

    override fun confirmLocalInstall(token: String): CompletionStage<EngineExtensionInstallResult> =
        try {
            ensureOpen()
            peer.confirmLocalInstall(token).thenCompose(::installResult)
        } catch (error: Throwable) {
            failed(error)
        }

    override fun cancelLocalInstall(token: String): CompletionStage<EngineExtensionInventory> =
        try {
            ensureOpen()
            peer.cancelLocalInstall(token).thenCompose(::inventory)
        } catch (error: Throwable) {
            failed(error)
        }

    override fun setEnabled(id: String, enabled: Boolean): CompletionStage<EngineExtensionInventory> =
        query { peer.setEnabled(id, enabled) }

    override fun setPrivateBrowsingAllowed(
        id: String,
        allowed: Boolean,
    ): CompletionStage<EngineExtensionInventory> = query {
        peer.setPrivateBrowsingAllowed(id, allowed)
    }

    override fun setPinnedToToolbar(
        id: String,
        pinned: Boolean,
    ): CompletionStage<EngineExtensionInventory> = query {
        peer.setPinnedToToolbar(id, pinned)
    }

    override fun openOptionsPage(id: String): CompletionStage<Unit> =
        try {
            ensureOpen()
            peer.openOptionsPage(id).thenApply { Unit }
        } catch (error: Throwable) {
            failed(error)
        }

    override fun uninstall(id: String): CompletionStage<EngineExtensionInventory> =
        query { peer.uninstall(id) }

    override fun invokeAction(
        id: String,
        sourceTabId: Long,
        privateMode: Boolean,
    ): CompletionStage<EngineExtensionPopup?> =
        try {
            ensureOpen()
            peer.invokeAction(id, sourceTabId, privateMode).thenApply { value ->
                value?.let {
                    EngineExtensionPopup(
                        it.extensionId,
                        it.title,
                        it.targetToken,
                        it.popupUri,
                        it.sourceTabId,
                        it.privateMode,
                    )
                }
            }
        } catch (error: Throwable) {
            failed(error)
        }

    override fun dismissPopup(): CompletionStage<Unit> =
        try {
            ensureOpen()
            peer.dismissPopup().thenApply { Unit }
        } catch (error: Throwable) {
            failed(error)
        }

    override fun notifyActiveTabChanged(): CompletionStage<Unit> =
        try {
            ensureOpen()
            peer.notifyActiveTabChanged().thenApply { Unit }
        } catch (error: Throwable) {
            failed(error)
        }

    override fun notifyTabClosed(tabId: Long): CompletionStage<Unit> =
        try {
            ensureOpen()
            peer.notifyTabClosed(tabId).thenApply { Unit }
        } catch (error: Throwable) {
            failed(error)
        }

    override fun notifyActiveTabChanged(previousTabId: Long): CompletionStage<Unit> =
        try {
            ensureOpen()
            require(previousTabId > 0)
            peer.notifyActiveTabChanged(previousTabId).thenApply { Unit }
        } catch (error: Throwable) {
            failed(error)
        }

    override fun setDeveloperMode(enabled: Boolean): CompletionStage<EngineExtensionInventory> =
        query { peer.setDeveloperMode(enabled) }

    override fun addObserver(observer: EngineExtensionObserver) {
        if (closed) return
        if (observers.contains(observer)) return
        observers += observer
    }

    override fun removeObserver(observer: EngineExtensionObserver) {
        observers -= observer
    }

    override fun addPopupObserver(observer: EngineExtensionPopupObserver) {
        if (!closed) popupObservers += observer
    }

    override fun removePopupObserver(observer: EngineExtensionPopupObserver) {
        popupObservers -= observer
    }

    override fun bindTabDelegate(delegate: EngineExtensionTabDelegate) {
        ensureOpen()
        check(tabDelegate == null || tabDelegate === delegate) { "Extension tab delegate already bound" }
        tabDelegate = delegate
    }

    override fun unbindTabDelegate(delegate: EngineExtensionTabDelegate) {
        if (tabDelegate === delegate) tabDelegate = null
    }

    override fun bindWindowDelegate(delegate: EngineExtensionWindowDelegate) {
        ensureOpen()
        check(windowDelegate == null || windowDelegate === delegate) { "Extension window delegate already bound" }
        windowDelegate = delegate
    }

    override fun unbindWindowDelegate(delegate: EngineExtensionWindowDelegate) {
        if (windowDelegate === delegate) windowDelegate = null
    }

    override fun authorizeWindowCommand(token: String, targetWindowId: Long?): CompletionStage<Boolean> =
        try { ensureOpen(); peer.authorizeWindowCommand(token, targetWindowId) }
        catch (error: Throwable) { failed(error) }

    override fun updateTabTopology(topology: EngineExtensionTabTopology): CompletionStage<Unit> =
        try {
            ensureOpen()
            peer.updateTabTopology(
                NavisAndroidExtensions.TabTopology(
                    topology.revision,
                    topology.windowId,
                    topology.activeTabId,
                    topology.tabs.map { entry ->
                        NavisAndroidExtensions.TabTopologyEntry(
                            entry.tabId,
                            entry.index,
                            entry.privateMode,
                            entry.windowId,
                        )
                    },
                    topology.events.map { event ->
                        NavisAndroidExtensions.TabTopologyEvent(
                            NavisAndroidExtensions.TabTopologyEventType.valueOf(event.type.name),
                            event.tabId,
                            event.previousTabId,
                            event.isWindowClosing,
                        )
                    },
                    topology.windows.map { window -> NavisAndroidExtensions.WindowTopologyEntry(
                        window.windowId, window.activeTabId, window.privateMode, window.focused,
                        window.left, window.top, window.width, window.height,
                        window.state.name.lowercase(java.util.Locale.ROOT),
                    ) },
                    topology.focusedWindowId,
                    topology.lastFocusedWindowId,
                    topology.focusOrder.toLongArray(),
                ),
            ).thenApply { Unit }
        } catch (error: Throwable) {
            failed(error)
        }

    override fun bindOptionalPermissionDelegate(delegate: EngineExtensionOptionalPermissionDelegate) {
        ensureOpen()
        check(optionalPermissionDelegate == null || optionalPermissionDelegate === delegate) {
            "Extension optional-permission delegate already bound"
        }
        optionalPermissionDelegate = delegate
    }

    override fun unbindOptionalPermissionDelegate(delegate: EngineExtensionOptionalPermissionDelegate) {
        if (optionalPermissionDelegate === delegate) optionalPermissionDelegate = null
    }

    override fun close() {
        if (closed) return
        closed = true
        tabDelegate = null
        windowDelegate = null
        optionalPermissionDelegate = null
        observers.clear()
        popupObservers.clear()
        iconCache.clear()
        peer.close()
    }

    private var lastInventory: EngineExtensionInventory? = null

    private fun lastInventoryWithProcessDisabled(): EngineExtensionInventory? {
        val current = lastInventory ?: return null
        return current.copy(processSpawningDisabled = true)
    }

    private fun query(
        operation: () -> CompletionStage<NavisAndroidExtensions.Inventory>,
    ): CompletionStage<EngineExtensionInventory> {
        return try {
            ensureOpen()
            operation().thenCompose(::inventory)
        } catch (error: Throwable) {
            failed(error)
        }
    }

    private fun ensureOpen() {
        check(!closed) { "Extension port is closed" }
    }

    private fun inventory(
        value: NavisAndroidExtensions.Inventory,
    ): CompletionStage<EngineExtensionInventory> {
        val records = value.extensions.map(::record).map { it.toCompletableFuture() }
        return CompletableFuture.allOf(*records.toTypedArray()).thenApply {
            acceptInventory(
                EngineExtensionInventory(
                    revision = value.revision,
                    developerModeEnabled = value.developerModeEnabled,
                    processSpawningDisabled =
                        value.processSpawningDisabled || processSpawningDisabled,
                    extensions = records.map { it.join() },
                ),
            )
        }
    }

    private fun record(
        value: NavisAndroidExtensions.ExtensionRecord,
    ): CompletionStage<EngineExtensionRecord> {
        val icon = resolveIcon(value, value.icon, "extension", 96)
        val actionIcon = resolveIcon(value, value.actionIcon, "action", 64)
        return icon.thenCombine(actionIcon) { extensionIcon, resolvedActionIcon ->
            EngineExtensionRecord(
                id = value.id,
                name = value.name,
                description = value.description,
                version = value.version,
                source = EngineExtensionSource.valueOf(value.source.name),
                signature = EngineExtensionSignature.valueOf(value.signature.name),
                enabled = value.enabled,
                privateBrowsingAllowed = value.privateBrowsingAllowed,
                privateBrowsingAvailable = value.privateBrowsingAvailable,
                pinnedToToolbar = value.pinnedToToolbar,
                canChangeEnabled = value.canChangeEnabled,
                canUninstall = value.canUninstall,
                canUpdate = value.canUpdate,
                hasAction = value.hasAction,
                actionKind = value.actionKind.takeIf(String::isNotEmpty)
                    ?.let(EngineExtensionActionKind::valueOf),
                actionEnabled = value.actionEnabled,
                hasOptions = value.hasOptions,
                requiredPermissions = value.requiredPermissions,
                requiredOrigins = value.requiredOrigins,
                actionTitle = value.actionTitle,
                icon = extensionIcon,
                actionIcon = resolvedActionIcon,
                actionBadgeText = value.actionBadgeText,
                actionBadgeTextColor = value.actionBadgeTextColor,
                actionBadgeBackgroundColor = value.actionBadgeBackgroundColor,
                actionSourceTabId = value.actionSourceTabId,
            )
        }
    }

    private fun resolveIcon(
        record: NavisAndroidExtensions.ExtensionRecord,
        source: NavisAndroidExtensions.Icon?,
        kind: String,
        targetSize: Int,
    ): CompletionStage<EngineExtensionIcon?> {
        if (source == null) return CompletableFuture.completedFuture(null)
        val key = IconCacheKey(record.id, record.version, kind, targetSize, source.sources.toMap())
        iconCache[key]?.let { return it }
        if (iconCache.size >= 1024) iconCache.clear()
        val created = peer.renderIcon(source, targetSize).handle { bitmap, _ ->
            bitmap?.let {
                EngineExtensionIcon(it.width, it.height, it.copyArgbPixels())
            }
        }
        val selected = iconCache.putIfAbsent(key, created) ?: created
        if (selected === created) {
            created.whenComplete { value, _ ->
                if (value == null) iconCache.remove(key, created)
            }
        }
        return selected
    }

    @Synchronized
    private fun acceptInventory(mapped: EngineExtensionInventory): EngineExtensionInventory {
        val current = lastInventory
        if (current != null && current.revision > mapped.revision) return current
        lastInventory = mapped
        return mapped
    }

    private fun preview(value: NavisAndroidExtensions.InstallPreview) = EngineExtensionInstallPreview(
        token = value.token,
        id = value.id,
        name = value.name,
        description = value.description,
        version = value.version,
        existingVersion = value.existingVersion.ifEmpty { null },
        packageName = value.packageName,
        packageBytes = value.packageBytes,
        requiredPermissions = value.requiredPermissions,
        requiredOrigins = value.requiredOrigins,
        requiredDataCollectionPermissions = value.requiredDataCollectionPermissions,
    )

    private fun installResult(
        value: NavisAndroidExtensions.InstallResult,
    ): CompletionStage<EngineExtensionInstallResult> = inventory(value.inventory).thenApply {
        EngineExtensionInstallResult(
            outcome = EngineExtensionInstallOutcome.valueOf(value.outcome.name),
            extensionId = value.extensionId,
            inventory = it,
        )
    }

    private data class IconCacheKey(
        val extensionId: String,
        val version: String,
        val kind: String,
        val targetSize: Int,
        val sources: Map<Int, String>,
    )

    companion object {
        fun install(context: Context): NavisAndroidExtensionPort {
            lateinit var adapter: NavisAndroidExtensionPort
            val fanout = object : NavisAndroidExtensions.Delegate {
                override fun onInventoryChanged(value: NavisAndroidExtensions.Inventory) {
                    if (!adapter.closed) {
                        adapter.inventory(value).whenComplete { mapped, error ->
                            if (!adapter.closed && error == null) {
                                adapter.observers.toList().forEach { it.onInventoryChanged(mapped) }
                            }
                        }
                    }
                }

                override fun onExtensionProcessSpawningDisabled() {
                    if (!adapter.closed) {
                        adapter.processSpawningDisabled = true
                        adapter.lastInventoryWithProcessDisabled()?.also { mapped ->
                            adapter.lastInventory = mapped
                            adapter.observers.toList().forEach { it.onInventoryChanged(mapped) }
                        }
                    }
                }

                override fun onPopupRequested(value: NavisAndroidExtensions.Popup) {
                    if (!adapter.closed) {
                        val popup = EngineExtensionPopup(
                            value.extensionId,
                            value.title,
                            value.targetToken,
                            value.popupUri,
                            value.sourceTabId,
                            value.privateMode,
                        )
                        adapter.popupObservers.toList().forEach { it.onPopupRequested(popup) }
                    }
                }

                override fun onTabCommand(
                    command: NavisAndroidExtensions.TabCommand,
                ): CompletionStage<NavisAndroidExtensions.TabResult> {
                    if (adapter.closed) {
                        return failed(IllegalStateException("Extension port is closed"))
                    }
                    val delegate = adapter.tabDelegate
                        ?: return failed(IllegalStateException("Extension tab delegate is unavailable"))
                    val request = EngineExtensionTabRequest(
                        extensionId = command.extensionId,
                        operation = EngineExtensionTabOperation.valueOf(command.operation.name),
                        tabId = command.tabId,
                        url = command.url.takeIf(String::isNotEmpty),
                        active = command.active,
                        privateMode = command.privateMode,
                        index = command.index,
                        windowId = command.windowId,
                    )
                    return delegate.onTabCommand(request).thenApply { result ->
                        NavisAndroidExtensions.TabResult(
                            result.tabId,
                            result.windowId,
                            result.index,
                            result.active,
                            result.privateMode,
                        )
                    }
                }

                override fun onWindowCommand(
                    command: NavisAndroidExtensions.WindowCommand,
                ): CompletionStage<NavisAndroidExtensions.WindowResult> {
                    if (adapter.closed) return failed(IllegalStateException("Extension port is closed"))
                    val delegate = adapter.windowDelegate
                        ?: return failed(IllegalStateException("Extension window delegate is unavailable"))
                    val request = EngineExtensionWindowRequest(
                        command.extensionId, EngineExtensionWindowOperation.valueOf(command.operation.name),
                        command.sourceWindowId, command.windowId, command.privateMode, command.urls.toList(),
                        command.focused, command.state?.let { EngineExtensionWindowState.valueOf(it.name) },
                        command.drawAttention, command.authorizationToken, command.extensionName,
                        command.left, command.top, command.width, command.height,
                        command.userActivation,
                    )
                    return delegate.onWindowCommand(request).thenApply { result ->
                        NavisAndroidExtensions.WindowResult(result.windowId, result.removed, result.attention)
                    }
                }

                override fun onOptionalPermissionRequested(
                    request: NavisAndroidExtensions.OptionalPermissionRequest,
                ): CompletionStage<Boolean> {
                    if (adapter.closed) {
                        return failed(IllegalStateException("Extension port is closed"))
                    }
                    val delegate = adapter.optionalPermissionDelegate
                        ?: return CompletableFuture.completedFuture(false)
                    val mapped = EngineExtensionOptionalPermissionRequest(
                        token = request.token,
                        extensionId = request.extensionId,
                        extensionName = request.extensionName,
                        extensionVersion = request.extensionVersion,
                        sourceTabId = request.sourceTabId,
                        privateMode = request.privateMode,
                        permissions = request.permissions,
                        origins = request.origins,
                        dataCollectionPermissions = request.dataCollectionPermissions,
                    )
                    return delegate.onOptionalPermissionRequested(mapped)
                }
            }
            val peer = NavisAndroidExtensions.install(context, fanout)
            adapter = NavisAndroidExtensionPort(peer)
            return adapter
        }

        private fun <T> failed(error: Throwable): CompletionStage<T> =
            java.util.concurrent.CompletableFuture<T>().also { it.completeExceptionally(error) }
    }
}
