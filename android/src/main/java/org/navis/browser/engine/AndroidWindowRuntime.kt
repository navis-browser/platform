/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.engine

import org.navis.browser.api.*
import org.navis.browser.persistence.BookmarkDraft
import org.navis.browser.persistence.BookmarkEntry
import org.navis.browser.persistence.HistoryEntry
import org.navis.browser.persistence.PasswordEntry

/** A product window facade, never a second Core or engine owner. */
internal class AndroidWindowRuntime(
    internal val shared: AndroidBrowserRuntime,
    val windowId: Long,
) : BrowserRuntime, BrowserTargetRuntime {
    private val observers = mutableMapOf<BrowserStateObserver, BrowserStateObserver>()
    private val targetObservers = mutableMapOf<TargetRequestObserver, TargetRequestObserver>()
    private val contextObservers = mutableMapOf<(AndroidContextMenuRequest) -> Unit, (AndroidContextMenuRequest) -> Unit>()
    private val shortcutObservers = mutableMapOf<(String) -> Unit, (Long, String) -> Unit>()
    private var retired = false
    val product by lazy { shared.productForWindow(windowId) }
    val extensions get() = shared.extensionsForWindow(windowId)
    val downloads get() = shared.downloads
    override val state get() = if (retired) BrowserState() else shared.stateForWindow(windowId)
    override val targetState get() = shared.targetStateForWindow(windowId)

    private fun <T> scoped(action: () -> T): T = shared.inWindow(windowId, action)
    private fun owns(id: SessionId) = shared.windowIdForSession(id) == windowId
    private fun <T> session(id: SessionId, action: () -> T): T {
        check(owns(id)) { "Session belongs to another Navis window" }
        return scoped(action)
    }

    override fun addObserver(observer: BrowserStateObserver) {
        if (observer in observers) return
        val bridge = BrowserStateObserver { observer.onBrowserStateChanged(state) }
        observers[observer] = bridge
        shared.addObserver(bridge)
    }
    override fun removeObserver(observer: BrowserStateObserver) {
        observers.remove(observer)?.let(shared::removeObserver)
    }
    override fun addTargetObserver(observer: TargetRequestObserver) {
        if (observer in targetObservers) return
        val bridge = TargetRequestObserver { observer.onTargetRequestStateChanged(targetState) }
        targetObservers[observer] = bridge
        shared.addTargetObserver(bridge)
    }
    override fun removeTargetObserver(observer: TargetRequestObserver) {
        targetObservers.remove(observer)?.let(shared::removeTargetObserver)
    }
    /** Closing is a real lifecycle state; a queued Compose factory must not reattach its view. */
    internal fun retire() {
        if (retired) return
        retired = true
        observers.keys.toList().forEach { it.onBrowserStateChanged(state) }
    }

    override fun attachView(view: BrowserView) {
        if (retired) {
            (view as? AndroidBrowserView)?.release()
            return
        }
        scoped { shared.attachView(view) }
    }
    override fun detachView(view: BrowserView) {
        if (retired) (view as? AndroidBrowserView)?.release()
        else if (shared.windowRegistry.find(windowId) != null) scoped { shared.detachView(view) }
    }
    override fun createSession(mode: SessionMode, initialUri: String?) = scoped { shared.createSession(mode, initialUri) }
    fun openSession(mode: SessionMode, initialUri: String? = null) = shared.openWindowSession(windowId, mode, initialUri)
    override fun activateSession(sessionId: SessionId) = session(sessionId) { shared.activateSession(sessionId) }
    override fun moveSession(sessionId: SessionId, index: Int) = session(sessionId) { shared.moveSession(sessionId, index) }
    override fun closeSession(sessionId: SessionId) = session(sessionId) { shared.closeSession(sessionId) }
    override fun load(sessionId: SessionId, uri: String) = session(sessionId) { shared.load(sessionId, uri) }
    override fun reload(sessionId: SessionId) = session(sessionId) { shared.reload(sessionId) }
    override fun stop(sessionId: SessionId) = session(sessionId) { shared.stop(sessionId) }
    override fun goBack(sessionId: SessionId) = session(sessionId) { shared.goBack(sessionId) }
    override fun goForward(sessionId: SessionId) = session(sessionId) { shared.goForward(sessionId) }
    override fun setDeveloperMode(enabled: Boolean) = scoped { shared.setDeveloperMode(enabled) }
    override fun close() { shared.closeProductWindow(windowId) }
    fun navigate(uri: String) = scoped { shared.navigate(uri) }
    fun createDevToolsHost(targetId: SessionId, initialTool: String? = null) =
        session(targetId) { shared.createDevToolsHost(targetId, initialTool) }

    override fun respondToPrompt(id: TargetRequestId, response: PromptResponse) {
        if (targetState.prompt?.id == id) shared.respondToPrompt(id, response)
    }
    override fun notifySitePermissionShown(id: TargetRequestId) {
        if (targetState.sitePermission?.id == id) shared.notifySitePermissionShown(id)
    }
    override fun respondToSitePermission(id: TargetRequestId, allow: Boolean) {
        if (targetState.sitePermission?.id == id) shared.respondToSitePermission(id, allow)
    }
    override fun respondToSitePermission(id: TargetRequestId, decision: SitePermissionDecision) {
        if (targetState.sitePermission?.id == id) shared.respondToSitePermission(id, decision)
    }
    override fun respondToPlatformPermission(id: TargetRequestId, granted: Boolean) {
        if (targetState.platformPermission?.id == id) shared.respondToPlatformPermission(id, granted)
    }
    override fun respondToFilePicker(id: TargetRequestId, uris: List<String>) {
        if (targetState.filePicker?.id == id) shared.respondToFilePicker(id, uris)
    }
    override fun exitFullscreen() = scoped { shared.exitFullscreen() }
    fun exitWindowFullscreen() = shared.exitWindowFullscreen(windowId)
    fun reportWindowFullscreenApplied(lease: AndroidWindowLease, requested: Boolean) {
        if (!retired && lease.windowId == windowId) shared.reportWindowFullscreenApplied(lease, requested)
    }
    fun addContextMenuObserver(observer: (AndroidContextMenuRequest) -> Unit) {
        if (observer in contextObservers) return
        val bridge: (AndroidContextMenuRequest) -> Unit = { if (owns(it.sessionId)) observer(it) }
        contextObservers[observer] = bridge
        shared.addContextMenuObserver(bridge)
    }
    fun removeContextMenuObserver(observer: (AndroidContextMenuRequest) -> Unit) {
        contextObservers.remove(observer)?.let(shared::removeContextMenuObserver)
    }
    fun addShortcutSettingsObserver(observer: (String) -> Unit) {
        if (observer in shortcutObservers) return
        val bridge: (Long, String) -> Unit = { owner, extension -> if (owner == windowId) observer(extension) }
        shortcutObservers[observer] = bridge
        shared.addWindowShortcutSettingsObserver(bridge)
    }
    fun removeShortcutSettingsObserver(observer: (String) -> Unit) {
        shortcutObservers.remove(observer)?.let(shared::removeWindowShortcutSettingsObserver)
    }
    fun respondToContextMenu(request: AndroidContextMenuRequest, itemId: String?): Boolean =
        owns(request.sessionId) && scoped { shared.respondToContextMenu(request, itemId) }

    fun listHistory(callback: (List<HistoryEntry>) -> Unit) = shared.listHistory(callback)
    fun addressCandidates(query: String, id: SessionId,
        callback: (Result<List<AddressSuggestion>>) -> Unit) {
        val original = state.sessions.firstOrNull { it.id == id } ?: return
        shared.addressCandidates(query, original.mode != SessionMode.PRIVATE) candidates@ { result ->
            if (!owns(id) || state.activeSessionId != id) return@candidates
            result.fold(onSuccess = { persisted ->
                val candidates = org.json.JSONArray()
                var remaining = 48 * 1024
                fun add(row: org.json.JSONObject) {
                    val size = row.toString().length + 1
                    if (size <= remaining) { candidates.put(row); remaining -= size }
                }
                // Tab identities come from this window and exactly the current browsing mode.
                state.sessions.filter { it.mode == original.mode }.take(100).forEach { tab ->
                    add(org.json.JSONObject().put("kind", "tab").put("id", tab.id.value.toString())
                        .put("title", tab.navigation.title.take(512)).put("url", tab.navigation.url))
                }
                persisted.forEach { row ->
                    add(org.json.JSONObject().put("kind", row.kind).put("id", row.id)
                        .put("title", row.title.take(512)).put("url", row.url).put("lastVisit", row.lastVisit)
                        .put("visitCount", row.visitCount))
                }
                product.localSuggestions(query, candidates, id, callback)
            }, onFailure = { callback(Result.failure(it)) })
        }
    }
    fun clearHistory(onComplete: () -> Unit) = shared.clearHistory(onComplete)
    fun listBookmarks(callback: (List<BookmarkEntry>) -> Unit) = shared.listBookmarks(callback)
    fun observeBookmarksChanged(observer: () -> Unit) = shared.observeBookmarksChanged(observer)
    fun observeHistoryChanged(observer: () -> Unit) = shared.observeHistoryChanged(observer)
    fun addBookmark(url: String, title: String, onComplete: () -> Unit = {}) = shared.addBookmark(url, title, onComplete)
    fun removeBookmark(url: String, onComplete: () -> Unit) = shared.removeBookmark(url, onComplete)
    fun listPasswords(callback: (List<PasswordEntry>) -> Unit) = shared.listPasswords(callback)
    fun removePassword(guid: String, onComplete: () -> Unit) = shared.removePassword(guid, onComplete)
    fun removeHistoryResult(url: String, done: (Result<Unit>) -> Unit) = shared.removeHistoryResult(url, done)
    fun clearHistoryResult(done: (Result<Unit>) -> Unit) = shared.clearHistoryResult(done)
    fun saveBookmark(draft: BookmarkDraft, done: (Result<Unit>) -> Unit) = shared.saveBookmark(draft, done)
    fun moveBookmark(id: String, parentId: String?, position: Int, done: (Result<Unit>) -> Unit) = shared.moveBookmark(id, parentId, position, done)
    fun deleteBookmark(id: String, done: (Result<Unit>) -> Unit) = shared.deleteBookmark(id, done)
    fun revealPassword(guid: String, done: (Result<String>) -> Unit) = shared.revealPassword(guid, done)
    fun deletePassword(guid: String, done: (Result<Unit>) -> Unit) = shared.deletePassword(guid, done)
    fun clearPasswords(done: (Result<Unit>) -> Unit) = shared.clearPasswords(done)
}
