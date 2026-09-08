/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */
package org.navis.browser.engine

import org.json.JSONArray
import org.json.JSONObject
import org.navis.browser.persistence.BrowserProfileStore
import org.navis.browser.persistence.HistoryChange
import org.navis.browser.persistence.HistoryEntry
import org.navis.browser.persistence.HistorySearch

/** History-specific projection; the profile coordinator retains request lifetime ownership. */
internal class AndroidExtensionHistoryAdapter(
    private val store: BrowserProfileStore,
    private val publish: (String) -> Unit,
) : AutoCloseable {
    private var closed = false
    private val observer: (HistoryChange) -> Unit = { change ->
        if (!closed) when (change) {
            is HistoryChange.Visited -> {
                emit("onVisited", item(change.entry))
                if (change.titleChanged) emit("onTitleChanged", JSONObject().put("url", change.entry.url).put("title", change.entry.title))
            }
            is HistoryChange.Removed -> emit("onVisitRemoved", JSONObject().put("allHistory", change.allHistory)
                .put("urls", JSONArray(change.urls)))
            is HistoryChange.TitleChanged -> emit("onTitleChanged", JSONObject().put("url", change.url).put("title", change.title))
        }
    }
    init { store.observeHistory(observer) }

    fun request(operation: String, arguments: String, callback: (Result<String>) -> Unit) {
        try {
            check(!closed) { "History adapter is closed" }
            val args = JSONObject(arguments)
            fun done(result: Result<Unit>) = callback(result.map { "null" })
            when (operation) {
                "history:search" -> store.searchHistory(HistorySearch(
                    args.optString("text", ""), args.getLong("startTime"), args.getLong("endTime"),
                    args.optInt("maxResults", 100).coerceAtMost(500)),
                ) { result -> callback(result.mapCatching { entries ->
                    JSONArray().also { array -> entries.forEach { array.put(item(it)) } }.toString()
                }) }
                "history:visits" -> store.historyVisits(args.getString("url")) { result -> callback(result.mapCatching { visits ->
                    JSONArray().also { array -> visits.forEach { visit ->
                        array.put(JSONObject().put("id", visit.url).put("visitId", visit.id)
                            .put("visitTime", visit.visitedAt).put("referringVisitId", "0").put("transition", visit.transition))
                    } }.toString()
                }) }
                "history:add" -> store.addHistoryVisit(args.getString("url"), args.optString("title", ""),
                    args.getLong("visitTime"), args.optString("transition", "link"), ::done)
                "history:deleteUrl" -> store.removeHistory(args.getString("url"), ::done)
                "history:deleteRange" -> store.deleteHistoryRange(args.getLong("startTime"), args.getLong("endTime"), ::done)
                "history:deleteAll" -> store.clearHistorySince(0, ::done)
                else -> error("Unsupported history operation")
            }
        } catch (error: Throwable) { callback(Result.failure(error)) }
    }

    override fun close() {
        if (closed) return
        closed = true
        store.removeHistoryObserver(observer)
    }
    private fun emit(event: String, detail: JSONObject) { publish(JSONObject().put("event", event).put("detail", detail).toString()) }
    private fun item(entry: HistoryEntry): JSONObject = JSONObject().put("id", entry.url).put("url", entry.url)
        .put("title", entry.title).put("lastVisitTime", entry.visitedAt).put("visitCount", entry.visitCount)
        .put("typedCount", entry.typedCount)
}
