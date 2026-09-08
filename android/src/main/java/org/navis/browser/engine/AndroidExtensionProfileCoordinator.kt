/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */
package org.navis.browser.engine

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import org.json.JSONArray
import org.json.JSONObject
import org.navis.browser.engine.runtime.EngineExtensionProfileDelegate
import org.navis.browser.engine.runtime.EngineExtensionProfilePort
import org.navis.browser.engine.runtime.EngineExtensionProfileRequest
import org.navis.browser.persistence.BookmarkChange
import org.navis.browser.persistence.BookmarkDraft
import org.navis.browser.persistence.BookmarkEntry
import org.navis.browser.persistence.BrowserProfileStore
import org.navis.browser.persistence.ExtensionBookmarkMutation

/** One profile, one queue. No current-tab dependency and no private visit collection. */
internal class AndroidExtensionProfileCoordinator(
    private val store: BrowserProfileStore,
    private val port: EngineExtensionProfilePort,
    private val sessions: AndroidExtensionSessionsAdapter? = null,
) : EngineExtensionProfileDelegate, AutoCloseable {
    private var closed = false
    private val pending = mutableSetOf<CompletableFuture<String>>()
    private val history: AndroidExtensionHistoryAdapter
    private val observer: (BookmarkChange) -> Unit = { change ->
        if (!closed) port.publishBookmarkChange(JSONObject().put("before", nodes(change.before))
            .put("after", nodes(change.after)).put("movedId", change.movedId ?: JSONObject.NULL).toString())
    }
    init {
        port.bind(this)
        store.observeBookmarks(observer)
        history = AndroidExtensionHistoryAdapter(store, port::publishHistoryChange)
    }

    override fun request(request: EngineExtensionProfileRequest): CompletionStage<String> {
        val future = CompletableFuture<String>()
        if (closed) return future.also { it.completeExceptionally(IllegalStateException("Profile service is closed")) }
        pending.add(future)
        fun complete(result: Result<String>) {
            if (!pending.remove(future)) return
            result.fold(future::complete, future::completeExceptionally)
        }
        try {
            // Both contract and Java peer reject an unauthorized private caller before touching storage.
            require(!request.privateMode || request.privateBrowsingAllowed)
            when (request.operation) {
                "sessions:list", "sessions:forget", "sessions:restore" ->
                    checkNotNull(sessions) { "Sessions service is unavailable" }.request(request.operation, request.arguments)
                        .whenComplete { value, error -> complete(if (error == null) Result.success(value) else Result.failure(error)) }
                "bookmarks:list" -> store.listBookmarksResult { result -> complete(result.mapCatching { entries ->
                    require(entries.size <= 10_000) { "Bookmark tree exceeds the API size limit" }
                    nodes(entries).toString()
                }) }
                "topSites:list" -> store.listTopSitesResult { result -> complete(result.mapCatching { entries ->
                    JSONArray().also { output -> entries.forEach { entry ->
                        output.put(JSONObject().put("url", entry.url).put("title", entry.title)
                            .put("visitCount", entry.visitCount).put("lastVisitedAt", entry.visitedAt))
                    } }.toString()
                }) }
                "history:search", "history:visits", "history:add", "history:deleteUrl", "history:deleteRange", "history:deleteAll" ->
                    history.request(request.operation, request.arguments, ::complete)
                else -> {
                    val args = JSONObject(request.arguments)
                    fun optional(key: String): String? = if (args.has(key) && !args.isNull(key)) args.getString(key) else null
                    fun parent(): String? = optional("parentId")?.takeUnless { it == "root" }
                    fun index(): Int? = if (args.has("index") && !args.isNull("index")) args.getInt("index").also {
                        require(it >= 0) { "Invalid bookmark index" }
                    } else null
                    fun id(): String = args.getString("id").also { require(it != "root" && it.isNotBlank()) }
                    val change = when (request.operation) {
                        "bookmarks:create" -> {
                            val kind = optional("type") ?: if (optional("url") == null) "folder" else "bookmark"
                            require(kind == "folder" || kind == "bookmark") { "Bookmark separators are not supported" }
                            require(kind != "folder" || optional("url") == null) { "A folder cannot have a URL" }
                            ExtensionBookmarkMutation.Create(BookmarkDraft(title = optional("title") ?: "",
                                url = optional("url") ?: "", parentId = parent(), isFolder = kind == "folder"), index())
                        }
                        "bookmarks:update" -> ExtensionBookmarkMutation.Update(id(), optional("title"), optional("url"))
                        "bookmarks:move" -> ExtensionBookmarkMutation.Move(id(), parent(), args.has("parentId"), index())
                        "bookmarks:remove", "bookmarks:removeTree" -> ExtensionBookmarkMutation.Remove(id(), request.operation.endsWith("Tree"))
                        else -> error("Unsupported extension profile operation")
                    }
                    store.mutateExtensionBookmark(change) { result -> complete(result.mapCatching { it?.let(::node)?.toString() ?: "null" }) }
                }
            }
        } catch (error: Throwable) { complete(Result.failure(error)) }
        return future
    }

    override fun close() {
        if (closed) return
        closed = true
        history.close()
        sessions?.close()
        store.removeBookmarkObserver(observer)
        port.unbind(this)
        pending.toList().forEach { it.completeExceptionally(IllegalStateException("Profile service is closed")) }
        pending.clear()
    }

    private fun nodes(entries: List<BookmarkEntry>): JSONArray = JSONArray().also { result -> entries.forEach { result.put(node(it)) } }
    private fun node(entry: BookmarkEntry): JSONObject = JSONObject().put("id", entry.id)
        .put("parentId", entry.parentId ?: "root").put("index", entry.position).put("title", entry.title)
        .put("dateAdded", entry.createdAt).put("type", if (entry.isFolder) "folder" else "bookmark")
        .also { if (!entry.isFolder) it.put("url", entry.url) }
}
