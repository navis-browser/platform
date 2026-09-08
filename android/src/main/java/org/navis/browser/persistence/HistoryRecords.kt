/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */
package org.navis.browser.persistence

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase

/** SQL runs only on BrowserProfileStore's existing serialized queue/transaction. */
internal object HistoryRecords {
    fun search(db: SQLiteDatabase, query: HistorySearch): List<HistoryEntry> {
        HistoryPolicy.range(query.start, query.end)
        require(query.limit in 1..500 && query.text.length <= 4096)
        return db.query("history", null,
            "visited_at >= ? AND visited_at <= ? AND (instr(lower(url),lower(?)) > 0 OR instr(lower(title),lower(?)) > 0)",
            arrayOf(query.start.toString(), query.end.toString(), query.text, query.text), null, null,
            "visited_at DESC, url ASC", query.limit.toString()).use { cursor ->
            buildList { while (cursor.moveToNext()) add(entry(cursor)) }
        }
    }

    fun visits(db: SQLiteDatabase, url: String): List<HistoryVisit> = db.query(
        "history_visits", arrayOf("id", "url", "visited_at", "transition"), "url = ?", arrayOf(url),
        null, null, "visited_at DESC, id DESC", "10001").use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                check(size < 10_000) { "History visits exceed the API result limit" }
                add(HistoryVisit(cursor.getLong(0).toString(), cursor.getString(1), cursor.getLong(2), cursor.getString(3)))
            }
        }
    }

    fun add(db: SQLiteDatabase, url: String, title: String, at: Long, transition: String): HistoryWrite<Unit> {
        HistoryPolicy.time(at)
        require(transition in HistoryPolicy.TRANSITIONS) { "Invalid history transition" }
        val previous = find(db, url)
        if (previous == null) {
            db.insertOrThrow("history", null, ContentValues().apply {
                put("url", url); put("title", title); put("visited_at", at); put("visit_count", 0)
            })
        } else if (title.isNotEmpty() && title != previous.title) {
            check(db.update("history", ContentValues().apply { put("title", title) }, "url = ?", arrayOf(url)) == 1)
        }
        db.insertOrThrow("history_visits", null, ContentValues().apply {
            put("url", url); put("visited_at", at); put("transition", transition)
        })
        db.execSQL(HistorySchema.RECOMPUTE_PAGES, arrayOf(url))
        val current = checkNotNull(find(db, url))
        return HistoryWrite(Unit, listOf(HistoryChange.Visited(current,
            title.isNotEmpty() && previous?.title != current.title)))
    }

    fun removeUrl(db: SQLiteDatabase, url: String): HistoryWrite<Unit> {
        val deleted = db.delete("history", "url = ?", arrayOf(url))
        return HistoryWrite(Unit, if (deleted > 0) listOf(HistoryChange.Removed(false, listOf(url))) else emptyList())
    }

    fun updateTitle(db: SQLiteDatabase, url: String, title: String): HistoryWrite<Unit> {
        val previous = find(db, url)
        if (previous == null || previous.title == title) return HistoryWrite(Unit, emptyList())
        check(db.update("history", ContentValues().apply { put("title", title) }, "url = ?", arrayOf(url)) == 1)
        return HistoryWrite(Unit, listOf(HistoryChange.TitleChanged(url, title)))
    }

    fun removeRange(db: SQLiteDatabase, start: Long, end: Long): HistoryWrite<Unit> {
        HistoryPolicy.range(start, end)
        val affected = linkedSetOf<String>()
        db.rawQuery(HistorySchema.RANGE_VISIT_URLS,
            arrayOf(start.toString(), end.toString())).use { cursor ->
            while (cursor.moveToNext()) affected.add(cursor.getString(0))
        }
        db.rawQuery(HistorySchema.LEGACY_RANGE_CANDIDATES, arrayOf(start.toString())).use { cursor ->
            while (cursor.moveToNext()) affected.add(cursor.getString(0))
        }
        db.delete("history_visits", HistorySchema.RANGE_VISIT_PREDICATE, arrayOf(start.toString(), end.toString()))
        db.execSQL(HistorySchema.CLEAR_LEGACY_RANGE, arrayOf(start))
        affected.forEach { url ->
            db.execSQL(HistorySchema.RECOMPUTE_PAGES, arrayOf(url))
            db.delete("history", "url = ? AND visit_count = 0", arrayOf(url))
        }
        return HistoryWrite(Unit, if (affected.isEmpty()) emptyList() else listOf(HistoryChange.Removed(false, affected.toList())))
    }

    fun clear(db: SQLiteDatabase): HistoryWrite<Unit> {
        // FK deletion cascades real visits; the AUTOINCREMENT sequence is not reset/reused.
        db.delete("history", null, null)
        return HistoryWrite(Unit, listOf(HistoryChange.Removed(true, emptyList())))
    }

    private fun find(db: SQLiteDatabase, url: String): HistoryEntry? = db.query("history", null,
        "url = ?", arrayOf(url), null, null, null, "1").use { cursor ->
        if (cursor.moveToFirst()) entry(cursor) else null
    }
    private fun entry(cursor: Cursor): HistoryEntry = HistoryEntry(
        cursor.getString(cursor.getColumnIndexOrThrow("url")),
        cursor.getString(cursor.getColumnIndexOrThrow("title")),
        cursor.getLong(cursor.getColumnIndexOrThrow("visited_at")),
        cursor.getInt(cursor.getColumnIndexOrThrow("visit_count")),
        cursor.getInt(cursor.getColumnIndexOrThrow("typed_count")),
    )
}
