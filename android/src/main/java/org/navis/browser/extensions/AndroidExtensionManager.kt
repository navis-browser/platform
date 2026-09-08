/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.extensions

import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.LruCache
import android.view.View
import android.widget.FrameLayout
import java.math.BigInteger
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicLong
import org.navis.browser.engine.extensions.EngineExtensionIcon
import org.navis.browser.engine.extensions.EngineExtensionInventory
import org.navis.browser.engine.extensions.EngineExtensionInstallPreview
import org.navis.browser.engine.extensions.EngineExtensionOptionalPermissionDelegate
import org.navis.browser.engine.extensions.EngineExtensionOptionalPermissionRequest
import org.navis.browser.engine.extensions.EngineExtensionObserver
import org.navis.browser.engine.extensions.EngineExtensionPort
import org.navis.browser.engine.extensions.EngineExtensionPopup
import org.navis.browser.engine.extensions.EngineExtensionPopupObserver
import org.navis.browser.engine.extensions.EngineExtensionTabTopology
import org.navis.browser.engine.extensions.OwnedExtensionPackage

/** Immutable product identity; it is intentionally duplicated in the generated asset registry. */
internal const val UBLOCK_ID = "uBlock0@raymondhill.net"
internal const val UBLOCK_VERSION = "1.74.0"
internal const val UBLOCK_RESOURCE_URI =
    "resource://android/assets/web_extensions/ublock-origin/"
private const val INSTALL_BUSY_ID = "__local_extension_install__"

/**
 * Direct-runtime extension surface. Popup documents render in a separate
 * Navis-owned engine window and never through a GeckoView lifecycle owner.
 */
internal class AndroidExtensionManager(
    private val context: Context? = null,
    private val port: EngineExtensionPort = UnavailableEngineExtensionPort,
    private val activeTab: () -> Pair<Long, Boolean>?,
) : ExtensionHost, EngineExtensionOptionalPermissionDelegate, AutoCloseable {
    private val observers = linkedSetOf<ExtensionStateObserver>()
    private val windows = linkedMapOf<Long, WindowHost>()
    private var windowScoped = false
    private var callingWindow: Long? = null
    private var popupWindow: Long? = null
    private var actionWindow: Long? = null
    private var permissionWindow: Long? = null
    private var noticeWindow: Long? = null
    private var publishedOwners: List<Long?> = emptyList()
    private val iconBitmaps = object : LruCache<EngineExtensionIcon, Bitmap>(8 * 1024) {
        override fun sizeOf(key: EngineExtensionIcon, value: Bitmap): Int =
            maxOf(1, value.byteCount / 1024)
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val installTokens = AtomicLong(0)
    private val portObserver = EngineExtensionObserver { inventory ->
        onMain {
            if (!closed) {
                engineProcessUnavailable = inventory.processSpawningDisabled
                val optional = activeOptionalPermission
                if (
                    optional != null &&
                    !inventory.canContinueOptionalPermission(optional.request)
                ) {
                    cancelOptionalPermission(publishState = false)
                }
                val popup = activePopup
                val popupUnavailable = popup != null && (
                    inventory.processSpawningDisabled ||
                        inventory.extensions.none {
                            it.id == popup.extensionId && it.enabled && it.hasAction
                        }
                )
                if (popupUnavailable) {
                    closePopup(notifyEngine = false)
                }
                var next = inventory.toState(
                    loading = false,
                    popup = activePopup?.toSnapshot(),
                    resolveIcon = ::bitmapFor,
                )
                if (activeInstall != null || activeOptionalPermission != null || preparingInstall != null) {
                    next = next.copy(
                        pendingPermission = extensionState.pendingPermission,
                        busyExtensionId = extensionState.busyExtensionId,
                    )
                }
                publish(next)
            }
        }
    }
    private val popupObserver = EngineExtensionPopupObserver { popup ->
        onMain {
            if (!closed) {
                if (isCurrentPopup(popup)) {
                    closePopup(notifyEngine = false)
                    popupEpoch++
                    popupWindow = sourceWindow(popup.sourceTabId, popup.privateMode)
                    actionWindow = popupWindow
                    activePopup = popup
                    popupLoading = true
                    actionFailure = null
                    publish(extensionState.copy(popup = popup.toSnapshot(), notice = null))
                } else {
                    // A delayed observer event for a retired window must not dismiss a newer
                    // window's popup. Exact tab-close/selection cleanup retires the old grant.
                    if (activePopup?.targetToken == popup.targetToken) closePopup(notifyEngine = true)
                }
            }
        }
    }
    private var activeInstall: ActiveInstall? = null
    private var preparingInstall: Long? = null
    private var activeOptionalPermission: ExtensionOptionalPermissionTransaction? = null
    private var activePopup: org.navis.browser.engine.extensions.EngineExtensionPopup? = null
    private var popupView: AndroidExtensionPopupView? = null
    private var popupEpoch = 0L
    private var pendingActionId: String? = null
    private var popupLoading = false
    private var actionFailure: ExtensionActionFailure? = null
    private var popupFailureStage: ExtensionPopupFailureStage? = null
    private var engineProcessUnavailable = false
    private var closed = false

    override var extensionState: ExtensionManagerState = ExtensionManagerState()
        private set

    fun forWindow(
        windowId: Long,
        activeTab: () -> Pair<Long, Boolean>?,
        ownsTab: (Long) -> Boolean,
    ): ExtensionHost {
        require(windowId > 0)
        check(!closed) { "Extension manager is closed" }
        windowScoped = true
        return windows.getOrPut(windowId) { WindowHost(windowId, activeTab, ownsTab) }
    }

    fun releaseWindow(windowId: Long) {
        val window = windows.remove(windowId) ?: return
        window.observers.clear()
        if (popupWindow == windowId || actionWindow == windowId) {
            closePopup(notifyEngine = true, forceEngineCleanup = true)
            actionFailure = null
        }
        if (permissionWindow == windowId) cancelOptionalPermission()
        activeInstall?.takeIf { it.windowId == windowId }?.let { install ->
            activeInstall = null
            port.cancelLocalInstall(install.preview.token)
            publish(extensionState.copy(pendingPermission = null, busyExtensionId = null))
        }
        if (noticeWindow == windowId) publish(extensionState.copy(notice = null))
    }

    private inner class WindowHost(
        val id: Long,
        private val selectedTab: () -> Pair<Long, Boolean>?,
        private val ownsTab: (Long) -> Boolean,
    ) : ExtensionHost {
        val observers = linkedSetOf<ExtensionStateObserver>()
        var previousTab: Pair<Long, Boolean>? = selectedTab()
        val alive get() = !closed && windows[id] === this
        fun activeTab() = selectedTab()?.takeIf { ownsTab(it.first) }
        fun matches(tabId: Long, privateMode: Boolean) = alive && activeTab() == (tabId to privateMode)
        override val extensionState: ExtensionManagerState get() {
            val state = this@AndroidExtensionManager.extensionState
            val privateMode = activeTab()?.second == true
            return state.copy(
                popup = state.popup.takeIf { popupWindow == id && alive },
                pendingPermission = state.pendingPermission.takeIf { permissionWindow == id && alive },
                notice = state.notice.takeIf { (noticeWindow == null || noticeWindow == id) && alive },
                pendingActionId = state.pendingActionId.takeIf { actionWindow == id && alive },
                popupLoading = state.popupLoading && popupWindow == id && alive,
                actionFailure = state.actionFailure.takeIf { actionWindow == id && alive },
                popupFailureStage = state.popupFailureStage.takeIf { actionWindow == id && alive },
                // A record identifies the tab used to compute its action context. A different
                // window may use the extension identity, never that tab's badge/title/icon.
                actions = if (!alive) emptyList() else state.actions.mapNotNull { action ->
                    val extension = state.extensions.firstOrNull { it.id == action.extensionId }
                        ?: return@mapNotNull null
                    if (privateMode && !extension.allowedInPrivateBrowsing) return@mapNotNull null
                    if (action.sourceTabId > 0 && action.sourceTabId == activeTab()?.first) action
                    else action.copy(title = extension.name, icon = extension.icon, badgeText = "",
                        badgeTextColor = null, badgeBackgroundColor = null,
                        enabled = extension.enabled && extension.hasToolbarAction)
                },
            )
        }
        private fun run(action: () -> Unit) { if (alive) inWindow(id, action) }
        override fun addExtensionObserver(observer: ExtensionStateObserver) {
            if (alive) { observers += observer; observer.onExtensionStateChanged(extensionState) }
        }
        override fun removeExtensionObserver(observer: ExtensionStateObserver) { observers -= observer }
        override fun refreshExtensions() = run { this@AndroidExtensionManager.refreshExtensions() }
        override fun installLocalExtension(uri: Uri) = run { this@AndroidExtensionManager.installLocalExtension(uri) }
        override fun setExtensionEnabled(id: String, enabled: Boolean) = run { this@AndroidExtensionManager.setExtensionEnabled(id, enabled) }
        override fun setExtensionPrivateAccess(id: String, allowed: Boolean) = run { this@AndroidExtensionManager.setExtensionPrivateAccess(id, allowed) }
        override fun setExtensionPinned(id: String, pinned: Boolean) = run { this@AndroidExtensionManager.setExtensionPinned(id, pinned) }
        override fun openExtensionOptions(id: String, onComplete: (Boolean) -> Unit) {
            if (!alive) onComplete(false) else run { this@AndroidExtensionManager.openExtensionOptions(id, onComplete) }
        }
        override fun invokeExtensionAction(id: String) = run { this@AndroidExtensionManager.invokeExtensionAction(id) }
        override fun dismissExtensionPopup() = run {
            if (popupWindow == id || actionWindow == id) this@AndroidExtensionManager.dismissExtensionPopup()
        }
        override fun createExtensionPopupView(context: Context): View =
            if (alive && popupWindow == id) this@AndroidExtensionManager.createExtensionPopupView(context)
            else FrameLayout(context)
        override fun releaseExtensionPopupView(view: View) {
            if (alive && popupWindow == id) this@AndroidExtensionManager.releaseExtensionPopupView(view)
            else if (view !== popupView) (view as? AndroidExtensionPopupView)?.release()
        }
        override fun dismissExtensionActionFailure() = run {
            if (actionWindow == id) this@AndroidExtensionManager.dismissExtensionActionFailure()
        }
        override fun uninstallExtension(id: String) = run { this@AndroidExtensionManager.uninstallExtension(id) }
        override fun respondToExtensionPermission(token: Long, allowed: Boolean) = run {
            if (permissionWindow == id) this@AndroidExtensionManager.respondToExtensionPermission(token, allowed)
        }
        override fun dismissExtensionNotice() = run {
            if (noticeWindow == null || noticeWindow == id) this@AndroidExtensionManager.dismissExtensionNotice()
        }
    }

    private fun inWindow(windowId: Long?, action: () -> Unit) {
        val previous = callingWindow
        callingWindow = windowId
        try { action() } finally { callingWindow = previous }
    }

    private fun sourceWindow(tabId: Long, privateMode: Boolean): Long? =
        windows.values.singleOrNull { it.matches(tabId, privateMode) }?.id

    private fun sourceIsCurrent(tabId: Long, privateMode: Boolean, windowId: Long?): Boolean =
        if (windowId != null) windows[windowId]?.matches(tabId, privateMode) == true
        else !windowScoped && activeTab() == (tabId to privateMode)

    fun initialize(onComplete: (Result<Unit>) -> Unit) {
        if (closed) {
            onComplete(Result.failure(IllegalStateException("Extension manager is closed")))
            return
        }
        port.addObserver(portObserver)
        port.addPopupObserver(popupObserver)
        publish(extensionState.copy(loading = true, notice = null))
        port.initialize().whenComplete { inventory, error ->
            onMain {
                if (closed) {
                    onComplete(Result.failure(IllegalStateException("Extension manager is closed")))
                } else if (error != null) {
                    publish(extensionState.copy(loading = false, notice = extensionNoticeFor(error)))
                    onComplete(Result.failure(error))
                } else {
                    engineProcessUnavailable = inventory.processSpawningDisabled
                    publish(inventory.toState(loading = false, popup = activePopup?.toSnapshot(), resolveIcon = ::bitmapFor))
                    onComplete(Result.success(Unit))
                }
            }
        }
    }

    /** The coordinator waits for this operation before persisting developer mode. */
    fun onDeveloperModeChanged(
        enabled: Boolean,
    ): CompletionStage<Unit> {
        val completion = CompletableFuture<Unit>()
        if (closed) {
            completion.completeExceptionally(IllegalStateException("Extension manager is closed"))
            return completion
        }
        if (activeInstall != null || activeOptionalPermission != null || preparingInstall != null || extensionState.busyExtensionId != null) {
            completion.completeExceptionally(IllegalStateException("An extension operation is already pending"))
            return completion
        }
        port.setDeveloperMode(enabled).whenComplete { inventory, error ->
            onMain {
                if (closed) {
                    completion.completeExceptionally(IllegalStateException("Extension manager is closed"))
                } else if (error == null) {
                    engineProcessUnavailable = inventory.processSpawningDisabled
                    publish(inventory.toState(loading = false, popup = activePopup?.toSnapshot(), resolveIcon = ::bitmapFor))
                    completion.complete(Unit)
                } else {
                    publish(extensionState.copy(notice = extensionNoticeFor(error)))
                    completion.completeExceptionally(error)
                }
            }
        }
        return completion
    }

    override fun addExtensionObserver(observer: ExtensionStateObserver) {
        if (closed) {
            return
        }
        observers += observer
        observer.onExtensionStateChanged(extensionState)
    }

    override fun removeExtensionObserver(observer: ExtensionStateObserver) {
        observers -= observer
    }

    override fun refreshExtensions() {
        if (closed || activeInstall != null || activeOptionalPermission != null || preparingInstall != null) return
        val owner = callingWindow
        port.inventory().whenComplete { inventory, error ->
            onMain {
                if (!closed) {
                    if (error == null) {
                        engineProcessUnavailable = inventory.processSpawningDisabled
                        publish(inventory.toState(loading = false, popup = activePopup?.toSnapshot(), resolveIcon = ::bitmapFor))
                    }
                    else publish(extensionState.copy(loading = false, notice = extensionNoticeFor(error)), owner)
                }
            }
        }
    }

    override fun installLocalExtension(uri: Uri) {
        val owner = callingWindow
        if (
            closed ||
            activeInstall != null ||
            activeOptionalPermission != null ||
            preparingInstall != null ||
            extensionState.busyExtensionId != null
        ) {
            publish(extensionState.copy(notice = ExtensionNotice.OPERATION_FAILED), owner)
            return
        }
        val resolver = context?.contentResolver
        if (resolver == null) {
            publish(extensionState.copy(notice = ExtensionNotice.UNSUPPORTED_CAPABILITY), owner)
            return
        }
        var stream: java.io.InputStream? = null
        try {
            stream = resolver.openInputStream(uri)
            if (stream == null) throw java.io.IOException("Could not open extension package")
            val name = extensionDisplayName(uri)
            val bytes = queryPackageSize(uri)
            val source = OwnedExtensionPackage(name, bytes, stream)
            stream = null
            val preparation = installTokens.incrementAndGet()
            preparingInstall = preparation
            publish(extensionState.copy(loading = false, busyExtensionId = INSTALL_BUSY_ID, notice = null))
            port.prepareLocalInstall(source).whenComplete { preview, error ->
                onMain {
                    if (preparingInstall != preparation) {
                        if (preview != null) port.cancelLocalInstall(preview.token)
                        return@onMain
                    }
                    preparingInstall = null
                    if (closed || (owner != null && owner !in windows)) {
                        if (preview != null) port.cancelLocalInstall(preview.token)
                        if (!closed) publish(extensionState.copy(busyExtensionId = null))
                        return@onMain
                    }
                    if (error != null) {
                        publish(
                            extensionState.copy(
                                busyExtensionId = null,
                                notice = extensionNoticeFor(error),
                            ),
                            owner,
                        )
                    } else {
                        beginPermissionPrompt(preview, owner)
                    }
                }
            }
        } catch (error: Throwable) {
            preparingInstall = null
            stream?.close()
            publish(extensionState.copy(busyExtensionId = null, notice = extensionNoticeFor(error)), owner)
        }
    }

    override fun setExtensionEnabled(id: String, enabled: Boolean) {
        if (!enabled) cancelOptionalPermissionForExtension(id)
        mutate(id) { port.setEnabled(id, enabled) }
    }

    override fun setExtensionPrivateAccess(id: String, allowed: Boolean) {
        mutate(id) { port.setPrivateBrowsingAllowed(id, allowed) }
    }

    override fun setExtensionPinned(id: String, pinned: Boolean) {
        mutate(id) { port.setPinnedToToolbar(id, pinned) }
    }

    override fun openExtensionOptions(id: String, onComplete: (Boolean) -> Unit) {
        val owner = callingWindow
        val extension = extensionState.extensions.firstOrNull { it.id == id }
        if (
            closed ||
            activeInstall != null ||
            extensionState.busyExtensionId != null ||
            extension?.enabled != true ||
            !extension.hasOptions ||
            extension.blockedSideLoad
        ) {
            if (!closed) {
                publish(extensionState.copy(notice = ExtensionNotice.OPERATION_FAILED), owner)
            }
            onComplete(false)
            return
        }
        closePopup(notifyEngine = true)
        publish(extensionState.copy(busyExtensionId = id, notice = null))
        port.openOptionsPage(id).whenComplete { _, error ->
            onMain {
                if (closed) {
                    onComplete(false)
                } else if (error == null) {
                    publish(extensionState.copy(busyExtensionId = null, notice = null))
                    onComplete(true)
                } else {
                    publish(
                        extensionState.copy(
                            busyExtensionId = null,
                            notice = extensionNoticeFor(error),
                        ),
                        owner,
                    )
                    onComplete(false)
                }
            }
        }
    }

    override fun invokeExtensionAction(id: String) {
        if (closed) return
        val source = callingWindow?.let { windows[it]?.activeTab() } ?: activeTab().takeIf { callingWindow == null }
        val owner = callingWindow ?: source?.let { sourceWindow(it.first, it.second) }
        if (source == null) {
            actionWindow = owner
            actionFailure = ExtensionActionFailure.INVOCATION_FAILED
            publish(extensionState)
            return
        }
        // Retire the product view immediately. The engine invocation retires its own previous
        // transaction, while this epoch prevents an older asynchronous completion from resurfacing.
        closePopup(notifyEngine = false, forceEngineCleanup = true)
        actionWindow = owner
        val requestEpoch = popupEpoch
        pendingActionId = id
        actionFailure = null
        publish(extensionState)
        val invoked = try {
            port.invokeAction(id, source.first, source.second)
        } catch (error: Throwable) {
            CompletableFuture<EngineExtensionPopup?>().apply { completeExceptionally(error) }
        }
        invoked.whenComplete { popup, error ->
            onMain {
                if (closed) return@onMain
                if (requestEpoch != popupEpoch) {
                    // A late failure must not close a newer popup, either.
                    return@onMain
                }
                pendingActionId = null
                if (error != null) {
                    closePopup(notifyEngine = false)
                    actionFailure = ExtensionActionFailure.INVOCATION_FAILED
                    publish(extensionState)
                } else {
                    val acceptedPopup = popup?.takeIf {
                        it.sourceTabId == source.first && it.privateMode == source.second &&
                            sourceIsCurrent(it.sourceTabId, it.privateMode, owner) && isCurrentPopup(it)
                    }
                    if (popup != null && acceptedPopup == null) {
                        port.dismissPopup()
                        closePopup(notifyEngine = false)
                        actionFailure = ExtensionActionFailure.INVOCATION_FAILED
                    } else if (popup == null) {
                        closePopup(notifyEngine = false)
                    } else {
                        popupWindow = owner
                        activePopup = popup
                        popupLoading = true
                    }
                    publish(extensionState.copy(
                        popup = acceptedPopup?.toSnapshot(),
                        notice = null,
                    ))
                }
            }
        }
    }

    override fun dismissExtensionPopup() {
        closePopup(notifyEngine = true)
    }

    override fun createExtensionPopupView(context: Context): View {
        popupView?.release()
        popupView = null
        val popup = activePopup ?: return FrameLayout(context)
        val targetToken = popup.targetToken
        val view = runCatching { AndroidExtensionPopupView(
            context, popup,
            onDismissed = {
                onMain {
                    if (activePopup?.targetToken == targetToken) dismissExtensionPopup()
                }
            },
            onLoaded = {
                onMain {
                    if (activePopup?.targetToken == targetToken) {
                        popupLoading = false
                        publish(extensionState)
                    }
                }
            },
            onFailed = { stage ->
                onMain {
                    if (activePopup?.targetToken == targetToken) {
                        closePopup(notifyEngine = true)
                        actionFailure = ExtensionActionFailure.POPUP_LOAD_FAILED
                        popupFailureStage = stage
                        publish(extensionState)
                    }
                }
            },
        ) }.getOrElse {
            closePopup(notifyEngine = true)
            actionFailure = ExtensionActionFailure.POPUP_LOAD_FAILED
            popupFailureStage = ExtensionPopupFailureStage.VIEW_CREATION
            publish(extensionState)
            return FrameLayout(context)
        }
        // A synchronous native creation failure may already have invalidated this token.
        if (activePopup?.targetToken == targetToken) popupView = view else view.release()
        return view
    }

    override fun dismissExtensionActionFailure() {
        actionFailure = null
        publish(extensionState)
    }

    override fun releaseExtensionPopupView(view: View) {
        (view as? AndroidExtensionPopupView)?.let { released ->
            if (popupView === released) {
                if (activePopup?.targetToken == released.targetToken) {
                    closePopup(notifyEngine = true)
                } else {
                    popupView = null
                    released.release()
                }
            } else {
                released.release()
            }
        }
    }

    override fun uninstallExtension(id: String) {
        cancelOptionalPermissionForExtension(id)
        mutate(id) { port.uninstall(id) }
    }

    override fun onOptionalPermissionRequested(
        request: EngineExtensionOptionalPermissionRequest,
    ): CompletionStage<Boolean> {
        val transaction = ExtensionOptionalPermissionTransaction(
            uiToken = installTokens.incrementAndGet(),
            request = request,
        )
        onMain {
            if (!canPresentOptionalPermission(request)) {
                transaction.finish(false)
                return@onMain
            }
            cancelOptionalPermission()
            activeOptionalPermission = transaction
            permissionWindow = sourceWindow(request.sourceTabId, request.privateMode)
            val extension = extensionState.extensions.first { it.id == request.extensionId }
            publish(
                extensionState.copy(
                    pendingPermission = ExtensionPermissionRequest(
                        token = transaction.uiToken,
                        extensionName = request.extensionName.ifBlank { extension.name },
                        candidateVersion = request.extensionVersion,
                        installedVersion = null,
                        sourceName = null,
                        kind = ExtensionPermissionKind.OPTIONAL,
                        permissions = request.permissions,
                        origins = request.origins,
                        dataCollectionPermissions = request.dataCollectionPermissions,
                    ),
                    notice = null,
                ),
            )
        }
        return transaction.completion
    }

    override fun respondToExtensionPermission(token: Long, allowed: Boolean) {
        val optional = activeOptionalPermission
        if (
            optional != null &&
            optional.uiToken == token &&
            extensionState.pendingPermission?.token == token
        ) {
            val stillCurrent = canPresentOptionalPermission(optional.request)
            activeOptionalPermission = null
            publish(extensionState.copy(pendingPermission = null))
            optional.finish(allowed && stillCurrent)
            return
        }
        val install = activeInstall ?: return
        if (install.uiToken != token || extensionState.pendingPermission?.token != token) return
        install.transaction.recordPromptResponse(install.kind, allowed)
        publish(extensionState.copy(pendingPermission = null))
        if (allowed) {
            port.confirmLocalInstall(install.preview.token).whenComplete { result, error ->
                finishInstall(install, result, error)
            }
        } else {
            port.cancelLocalInstall(install.preview.token).whenComplete { inventory, error ->
                onMain {
                    if (!closed && activeInstall === install) {
                        activeInstall = null
                        val notice = install.transaction.failureNotice(
                            extensionNoticeForOrNull(error), ExtensionNotice.INSTALL_FAILED,
                        )
                        publish((inventory?.toState(
                            loading = false,
                            popup = activePopup?.toSnapshot(),
                            resolveIcon = ::bitmapFor,
                        ) ?: extensionState).copy(
                            busyExtensionId = null,
                            pendingPermission = null,
                            notice = notice,
                        ), install.windowId)
                    }
                }
            }
        }
    }

    override fun dismissExtensionNotice() {
        if (extensionState.notice != null) {
            publish(extensionState.copy(notice = null))
        }
    }

    override fun close() {
        if (closed) {
            return
        }
        closed = true
        closePopup(notifyEngine = false)
        cancelOptionalPermission(publishState = false)
        activeInstall?.let { install ->
            port.cancelLocalInstall(install.preview.token)
        }
        activeInstall = null
        preparingInstall = null
        port.removeObserver(portObserver)
        port.removePopupObserver(popupObserver)
        port.close()
        iconBitmaps.evictAll()
        extensionState = ExtensionManagerState(
            loading = false,
            notice = ExtensionNotice.UNSUPPORTED_CAPABILITY,
        )
        observers.clear()
        windows.values.forEach { it.observers.clear() }
        windows.clear()
    }

    private fun unsupported(
        notice: ExtensionNotice = ExtensionNotice.UNSUPPORTED_CAPABILITY,
    ) {
        if (!closed) {
            closePopup(notifyEngine = false)
            cancelOptionalPermission(publishState = false)
            publish(
                extensionState.copy(
                    loading = false,
                    pendingPermission = null,
                    busyExtensionId = null,
                    actions = emptyList(),
                    popup = null,
                    notice = notice,
                ),
            )
        }
    }

    /** The runtime coordinator calls this when the active tab changes. */
    fun onActiveTabChanged(windowId: Long? = null) {
        if (!closed) {
            if (windowId == null) {
                // Only the legacy, unscoped frontend can use process-wide invalidation.
                if (windowScoped) return
                cancelOptionalPermission()
                closePopup(notifyEngine = false, forceEngineCleanup = true)
                port.notifyActiveTabChanged()
            } else {
                val window = windows[windowId] ?: return
                val previous = window.previousTab
                val selected = window.activeTab()
                window.previousTab = selected
                if (previous == selected) return
                if (permissionWindow == windowId) cancelOptionalPermission()
                if (popupWindow == windowId || actionWindow == windowId) {
                    closePopup(notifyEngine = false, forceEngineCleanup = true)
                }
                previous?.let { port.notifyActiveTabChanged(it.first) }
                publish(extensionState, forceWindowUpdate = true)
            }
        }
    }

    /** Runs before the product destroys a Session so exact grants can still be revoked. */
    fun onTabClosed(tabId: Long) {
        if (closed || tabId <= 0) return
        if (activeOptionalPermission?.belongsToTab(tabId) == true) {
            cancelOptionalPermission()
        }
        if (activePopup?.sourceTabId == tabId) {
            closePopup(notifyEngine = false)
        }
        port.notifyTabClosed(tabId)
    }

    private fun canPresentOptionalPermission(
        request: EngineExtensionOptionalPermissionRequest,
    ): Boolean {
        if (
            closed ||
            engineProcessUnavailable ||
            activeInstall != null ||
            extensionState.busyExtensionId != null
        ) {
            return false
        }
        val owner = sourceWindow(request.sourceTabId, request.privateMode)
        if (!sourceIsCurrent(request.sourceTabId, request.privateMode, owner)) {
            return false
        }
        val extension = extensionState.extensions.firstOrNull { it.id == request.extensionId }
            ?: return false
        return extension.enabled &&
            !extension.blockedSideLoad &&
            extension.version == request.extensionVersion &&
            (!request.privateMode || extension.allowedInPrivateBrowsing)
    }

    private fun cancelOptionalPermissionForExtension(extensionId: String) {
        if (activeOptionalPermission?.belongsToExtension(extensionId) == true) {
            cancelOptionalPermission()
        }
    }

    private fun cancelOptionalPermission(publishState: Boolean = true) {
        val transaction = activeOptionalPermission ?: return
        activeOptionalPermission = null
        transaction.finish(false)
        if (
            publishState &&
            !closed &&
            extensionState.pendingPermission?.token == transaction.uiToken
        ) {
            publish(extensionState.copy(pendingPermission = null))
        }
    }

    private fun closePopup(
        notifyEngine: Boolean,
        forceEngineCleanup: Boolean = false,
    ) {
        val hadPopup = activePopup != null || extensionState.popup != null || pendingActionId != null
        if (hadPopup || forceEngineCleanup) {
            popupEpoch++
        }
        activePopup = null
        popupWindow = null
        pendingActionId = null
        popupLoading = false
        popupView?.release()
        popupView = null
        if (hadPopup && !closed) {
            publish(extensionState.copy(popup = null))
        }
        if (notifyEngine && (hadPopup || forceEngineCleanup) && !closed) {
            port.dismissPopup()
        }
    }

    private fun isCurrentPopup(popup: EngineExtensionPopup): Boolean {
        val owner = sourceWindow(popup.sourceTabId, popup.privateMode)
        val extension = extensionState.extensions.firstOrNull { it.id == popup.extensionId }
            ?: return false
        return extension.enabled &&
            extension.hasToolbarAction &&
            (!popup.privateMode || extension.allowedInPrivateBrowsing) &&
            sourceIsCurrent(popup.sourceTabId, popup.privateMode, owner)
    }

    private fun mutate(id: String, operation: () -> CompletionStage<EngineExtensionInventory>) {
        if (closed) return
        val owner = callingWindow
        if (activeInstall != null || activeOptionalPermission != null || preparingInstall != null || extensionState.busyExtensionId != null) {
            publish(extensionState.copy(notice = ExtensionNotice.OPERATION_FAILED), owner)
            return
        }
        publish(extensionState.copy(busyExtensionId = id, notice = null))
        try {
            operation().whenComplete { inventory, error ->
                onMain {
                    if (!closed) {
                        publish(
                            if (error == null) inventory.toState(
                                loading = false,
                                popup = activePopup?.toSnapshot(),
                                resolveIcon = ::bitmapFor,
                            )
                            else extensionState.copy(
                                notice = extensionNoticeFor(error),
                                busyExtensionId = null,
                            ),
                            owner,
                        )
                    }
                }
            }
        } catch (error: Throwable) {
            publish(
                extensionState.copy(
                    notice = extensionNoticeFor(error),
                    busyExtensionId = null,
                ),
                owner,
            )
        }
    }

    private fun beginPermissionPrompt(preview: EngineExtensionInstallPreview, windowId: Long?) {
        val kind = if (preview.isUpdate) ExtensionPermissionKind.UPDATE else ExtensionPermissionKind.INSTALL
        val transaction = ExtensionInstallTransaction(installTokens.incrementAndGet()).apply {
            existingExtensionId = preview.existingVersion?.let { preview.id }
        }
        val install = ActiveInstall(transaction.token, preview, transaction, kind, windowId)
        activeInstall = install
        permissionWindow = windowId
        publish(
            extensionState.copy(
                busyExtensionId = INSTALL_BUSY_ID,
                pendingPermission = ExtensionPermissionRequest(
                    token = install.uiToken,
                    extensionName = preview.name,
                    candidateVersion = preview.version,
                    installedVersion = preview.existingVersion,
                    sourceName = preview.packageName,
                    kind = kind,
                    permissions = preview.requiredPermissions,
                    origins = preview.requiredOrigins,
                    dataCollectionPermissions = preview.requiredDataCollectionPermissions,
                ),
                notice = null,
            ),
        )
    }

    private fun finishInstall(
        install: ActiveInstall,
        result: org.navis.browser.engine.extensions.EngineExtensionInstallResult?,
        error: Throwable?,
    ) {
        onMain {
            if (closed || activeInstall !== install) return@onMain
            activeInstall = null
            if (error != null) {
                publish(extensionState.copy(
                    busyExtensionId = null,
                    pendingPermission = null,
                    notice = install.transaction.failureNotice(
                        extensionNoticeForOrNull(error), ExtensionNotice.INSTALL_FAILED,
                    ),
                ), install.windowId)
            } else {
                val outcome = result?.outcome
                publish(result!!.inventory.toState(
                    loading = false,
                    notice = if (outcome == org.navis.browser.engine.extensions.EngineExtensionInstallOutcome.UPDATED)
                        ExtensionNotice.UPDATE_COMPLETE else ExtensionNotice.INSTALL_COMPLETE,
                    popup = activePopup?.toSnapshot(),
                    resolveIcon = ::bitmapFor,
                ), install.windowId)
            }
        }
    }

    private fun extensionDisplayName(uri: Uri): String {
        val value = uri.lastPathSegment?.substringAfterLast('/')
        return if (!value.isNullOrBlank() && value.lowercase().endsWith(".xpi")) value else "extension.xpi"
    }

    private fun queryPackageSize(uri: Uri): Long? {
        val resolver = context?.contentResolver ?: return null
        val cursor: Cursor = resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null) ?: return null
        return cursor.use {
            if (it.moveToFirst() && !it.isNull(0)) {
                it.getLong(0).takeIf { size -> size >= 0 }
            } else {
                null
            }
        }
    }

    private fun bitmapFor(icon: EngineExtensionIcon?): Bitmap? {
        if (icon == null) return null
        iconBitmaps.get(icon)?.let { return it }
        return runCatching {
            Bitmap.createBitmap(
                icon.copyArgbPixels(),
                icon.width,
                icon.height,
                Bitmap.Config.ARGB_8888,
            ).also { iconBitmaps.put(icon, it) }
        }.getOrNull()
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
    }

    private data class ActiveInstall(
        val uiToken: Long,
        val preview: EngineExtensionInstallPreview,
        val transaction: ExtensionInstallTransaction,
        val kind: ExtensionPermissionKind,
        val windowId: Long?,
    )

    private fun publish(next: ExtensionManagerState, owner: Long? = noticeWindow, forceWindowUpdate: Boolean = false) {
        if (next.notice != null) noticeWindow = if (next.notice == ExtensionNotice.BUILT_IN_UNAVAILABLE) null else owner
        val projected = next.copy(
            pendingActionId = pendingActionId,
            popupLoading = popupLoading,
            actionFailure = actionFailure,
            popupFailureStage = popupFailureStage.takeIf {
                actionFailure == ExtensionActionFailure.POPUP_LOAD_FAILED
            },
        )
        val owners = listOf(popupWindow, actionWindow, permissionWindow, noticeWindow)
        if (closed || (!forceWindowUpdate && projected == extensionState && owners == publishedOwners)) {
            return
        }
        publishedOwners = owners
        extensionState = projected
        observers.toList().forEach { it.onExtensionStateChanged(projected) }
        windows.values.toList().forEach { window ->
            val state = window.extensionState
            window.observers.toList().forEach { it.onExtensionStateChanged(state) }
        }
    }
}

internal fun compareExtensionVersions(candidate: String, installed: String): Int {
    val candidateParts = versionParts(candidate)
    val installedParts = versionParts(installed)
    val count = maxOf(candidateParts.size, installedParts.size)
    repeat(count) { index ->
        val left = candidateParts.getOrNull(index) ?: VersionPart.Numeric(BigInteger.ZERO)
        val right = installedParts.getOrNull(index) ?: VersionPart.Numeric(BigInteger.ZERO)
        val comparison = left.compareTo(right)
        if (comparison != 0) {
            return comparison
        }
    }
    return 0
}

private fun EngineExtensionInventory.toState(
    loading: Boolean,
    notice: ExtensionNotice? = null,
    popup: ExtensionPopupSnapshot? = null,
    resolveIcon: (EngineExtensionIcon?) -> Bitmap?,
): ExtensionManagerState {
    // Gecko permanently suppresses extension-process spawning after its crash
    // threshold. At that point the required built-in is not operational even
    // though AddonManager may still report it as enabled.
    val runtimeUnavailable = processSpawningDisabled
    return ExtensionManagerState(
        loading = loading,
        extensions = extensions.map { it.toSnapshot(resolveIcon) },
        notice = if (runtimeUnavailable) ExtensionNotice.BUILT_IN_UNAVAILABLE else notice,
        popup = popup.takeUnless { runtimeUnavailable },
        actions = if (runtimeUnavailable) {
            emptyList()
        } else {
            extensions.filter { it.hasAction && it.enabled && it.pinnedToToolbar }.map {
                ExtensionActionSnapshot(
                    extensionId = it.id,
                    extensionName = it.name,
                    kind = when (it.actionKind) {
                        org.navis.browser.engine.extensions.EngineExtensionActionKind.PAGE ->
                            ExtensionActionKind.PAGE
                        else -> ExtensionActionKind.BROWSER
                    },
                    title = it.actionTitle ?: it.name,
                    icon = resolveIcon(it.actionIcon ?: it.icon),
                    badgeText = it.actionBadgeText,
                    badgeTextColor = it.actionBadgeTextColor,
                    badgeBackgroundColor = it.actionBadgeBackgroundColor,
                    enabled = it.actionEnabled,
                    pinned = it.pinnedToToolbar,
                    sourceTabId = it.actionSourceTabId,
                )
            }
        },
    )
}

private fun org.navis.browser.engine.extensions.EngineExtensionRecord.toSnapshot(
    resolveIcon: (EngineExtensionIcon?) -> Bitmap?,
) =
    ExtensionSnapshot(
        id = id,
        name = name,
        version = version,
        description = description,
        icon = resolveIcon(icon),
        extensionClass = when (source) {
            org.navis.browser.engine.extensions.EngineExtensionSource.APPLICATION_BUILT_IN ->
                ExtensionClass.APPLICATION_BUILT_IN
            org.navis.browser.engine.extensions.EngineExtensionSource.TEMPORARY ->
                ExtensionClass.TEMPORARY
            else -> ExtensionClass.USER
        },
        signature = when (signature) {
            org.navis.browser.engine.extensions.EngineExtensionSignature.APPLICATION_VERIFIED ->
                ExtensionSignature.APPLICATION_VERIFIED
            org.navis.browser.engine.extensions.EngineExtensionSignature.MOZILLA_SIGNED ->
                ExtensionSignature.MOZILLA_SIGNED
            org.navis.browser.engine.extensions.EngineExtensionSignature.UNVERIFIED ->
                ExtensionSignature.UNVERIFIED
        },
        enabled = enabled,
        allowedInPrivateBrowsing = privateBrowsingAllowed,
        privateBrowsingAvailable = privateBrowsingAvailable,
        blockedSideLoad = source == org.navis.browser.engine.extensions.EngineExtensionSource.BLOCKED_SIDELOAD,
        canChangeEnabled = canChangeEnabled,
        canUninstall = canUninstall,
        canUpdate = canUpdate,
        requiredPermissions = requiredPermissions,
        requiredOrigins = requiredOrigins,
        hasOptions = hasOptions,
        hasToolbarAction = hasAction,
        pinnedToToolbar = pinnedToToolbar,
    )

private fun org.navis.browser.engine.extensions.EngineExtensionPopup.toSnapshot() =
    ExtensionPopupSnapshot(
        extensionId = extensionId,
        title = title,
        targetToken = targetToken,
        popupUri = popupUri,
        sourceTabId = sourceTabId,
        privateMode = privateMode,
    )

/** Explicit fail-closed default used until the runtime adapter is wired by the app host. */
private object UnavailableEngineExtensionPort : EngineExtensionPort {
    override val capabilities =
        org.navis.browser.engine.extensions.EngineExtensionCapabilities(
            builtInLifecycle = false,
            signedLocalPackages = false,
            temporaryInventory = false,
            privateBrowsingProjection = false,
            actionProjection = false,
            tabProjection = false,
            windowProjection = false,
            popupProjection = false,
            optionsProjection = false,
            contextMenuProjection = false,
            commandProjection = false,
            downloadProjection = false,
            notificationProjection = false,
            browsingDataProjection = false,
            managementProjection = false,
            optionalPermissionProjection = false,
            engineApiModuleProfile = false,
        )

    private fun <T> unavailable(): CompletionStage<T> =
        java.util.concurrent.CompletableFuture<T>().also {
            it.completeExceptionally(UnsupportedOperationException("Extensions are not connected"))
        }

    override fun initialize() = unavailable<EngineExtensionInventory>()
    override fun inventory() = unavailable<EngineExtensionInventory>()
    override fun prepareLocalInstall(source: OwnedExtensionPackage) =
        unavailable<EngineExtensionInstallPreview>().also { source.close() }
    override fun confirmLocalInstall(token: String) = unavailable<org.navis.browser.engine.extensions.EngineExtensionInstallResult>()
    override fun cancelLocalInstall(token: String) = unavailable<EngineExtensionInventory>()
    override fun setEnabled(id: String, enabled: Boolean) = unavailable<EngineExtensionInventory>()
    override fun setPrivateBrowsingAllowed(id: String, allowed: Boolean) = unavailable<EngineExtensionInventory>()
    override fun setPinnedToToolbar(id: String, pinned: Boolean) = unavailable<EngineExtensionInventory>()
    override fun openOptionsPage(id: String) = unavailable<Unit>()
    override fun invokeAction(
        id: String,
        sourceTabId: Long,
        privateMode: Boolean,
    ) = unavailable<org.navis.browser.engine.extensions.EngineExtensionPopup?>()
    override fun dismissPopup() = unavailable<Unit>()
    override fun notifyActiveTabChanged() = unavailable<Unit>()
    override fun notifyTabClosed(tabId: Long) = unavailable<Unit>()
    override fun uninstall(id: String) = unavailable<EngineExtensionInventory>()
    override fun setDeveloperMode(enabled: Boolean) = unavailable<EngineExtensionInventory>()
    override fun addObserver(observer: EngineExtensionObserver) = Unit
    override fun removeObserver(observer: EngineExtensionObserver) = Unit
    override fun addPopupObserver(observer: EngineExtensionPopupObserver) = Unit
    override fun removePopupObserver(observer: EngineExtensionPopupObserver) = Unit
    override fun bindTabDelegate(delegate: org.navis.browser.engine.extensions.EngineExtensionTabDelegate) = Unit
    override fun unbindTabDelegate(delegate: org.navis.browser.engine.extensions.EngineExtensionTabDelegate) = Unit
    override fun bindWindowDelegate(delegate: org.navis.browser.engine.extensions.EngineExtensionWindowDelegate) = Unit
    override fun unbindWindowDelegate(delegate: org.navis.browser.engine.extensions.EngineExtensionWindowDelegate) = Unit
    override fun updateTabTopology(topology: EngineExtensionTabTopology) = unavailable<Unit>()
    override fun bindOptionalPermissionDelegate(
        delegate: org.navis.browser.engine.extensions.EngineExtensionOptionalPermissionDelegate,
    ) = Unit
    override fun unbindOptionalPermissionDelegate(
        delegate: org.navis.browser.engine.extensions.EngineExtensionOptionalPermissionDelegate,
    ) = Unit
    override fun close() = Unit
}

private fun EngineExtensionInventory.canContinueOptionalPermission(
    request: EngineExtensionOptionalPermissionRequest,
): Boolean =
    !processSpawningDisabled && extensions.any {
        it.id == request.extensionId &&
            it.version == request.extensionVersion &&
            it.enabled &&
            it.source != org.navis.browser.engine.extensions.EngineExtensionSource.BLOCKED_SIDELOAD &&
            (!request.privateMode || it.privateBrowsingAllowed)
    }

private fun versionParts(version: String): List<VersionPart> =
    Regex("[0-9]+|[A-Za-z]+")
        .findAll(version)
        .map { match ->
            if (match.value.first().isDigit()) {
                VersionPart.Numeric(BigInteger(match.value))
            } else {
                VersionPart.Text(match.value.lowercase())
            }
        }
        .toList()

private sealed interface VersionPart : Comparable<VersionPart> {
    data class Numeric(val value: BigInteger) : VersionPart {
        override fun compareTo(other: VersionPart): Int = when (other) {
            is Numeric -> value.compareTo(other.value)
            is Text -> 1
        }
    }

    data class Text(val value: String) : VersionPart {
        override fun compareTo(other: VersionPart): Int = when (other) {
            is Numeric -> -1
            is Text -> value.compareTo(other.value)
        }
    }
}
