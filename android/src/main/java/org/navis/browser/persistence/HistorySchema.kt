/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */
package org.navis.browser.persistence

/** v4 preserves old aggregates, but never invents per-visit dates from a count. */
internal object HistorySchema {
    const val CREATE_PAGES = """CREATE TABLE history (
        url TEXT PRIMARY KEY NOT NULL,
        title TEXT NOT NULL,
        visited_at INTEGER NOT NULL,
        visit_count INTEGER NOT NULL,
        legacy_visit_count INTEGER NOT NULL DEFAULT 0,
        legacy_visited_at INTEGER NOT NULL DEFAULT 0,
        typed_count INTEGER NOT NULL DEFAULT 0
    )"""
    const val CREATE_VISITS = """CREATE TABLE history_visits (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        url TEXT NOT NULL REFERENCES history(url) ON DELETE CASCADE,
        visited_at INTEGER NOT NULL CHECK(visited_at >= 0),
        transition TEXT NOT NULL
    )"""
    const val CREATE_URL_INDEX = "CREATE INDEX history_visits_url_time ON history_visits(url, visited_at, id)"
    const val CREATE_TIME_INDEX = "CREATE INDEX history_visits_time ON history_visits(visited_at)"
    const val RANGE_VISIT_URLS = "SELECT DISTINCT url FROM history_visits WHERE visited_at >= ? AND visited_at <= ?"
    const val RANGE_VISIT_PREDICATE = "visited_at >= ? AND visited_at <= ?"
    val MIGRATE_V3 = listOf(
        "ALTER TABLE history ADD COLUMN legacy_visit_count INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE history ADD COLUMN legacy_visited_at INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE history ADD COLUMN typed_count INTEGER NOT NULL DEFAULT 0",
        "UPDATE history SET legacy_visit_count = visit_count, legacy_visited_at = visited_at",
    )
    const val RECOMPUTE_PAGES = """UPDATE history SET
        visit_count = legacy_visit_count + (SELECT COUNT(*) FROM history_visits WHERE history_visits.url = history.url),
        visited_at = MAX(legacy_visited_at, COALESCE((SELECT MAX(visited_at) FROM history_visits WHERE history_visits.url = history.url), 0)),
        typed_count = (SELECT COUNT(*) FROM history_visits WHERE history_visits.url = history.url AND transition = 'typed')
        WHERE url = ?"""
    // Legacy visits may have happened at any time up to legacy_visited_at.
    // If a requested range possibly overlaps that unknown interval, remove the
    // entire legacy bucket. New individually dated visits outside the range stay.
    // This can remove extra old aggregate history; it cannot leave potentially
    // requested legacy visits behind or manufacture a precise migration.
    const val LEGACY_RANGE_CANDIDATES = "SELECT url FROM history WHERE legacy_visit_count > 0 AND legacy_visited_at >= ?"
    const val CLEAR_LEGACY_RANGE = "UPDATE history SET legacy_visit_count = 0, legacy_visited_at = 0 WHERE legacy_visit_count > 0 AND legacy_visited_at >= ?"
}

internal data class HistoryVisit(val id: String, val url: String, val visitedAt: Long, val transition: String)
internal data class HistorySearch(val text: String = "", val start: Long = 0, val end: Long = HistoryPolicy.MAX_TIME, val limit: Int = 100)
internal sealed interface HistoryChange {
    data class Visited(val entry: HistoryEntry, val titleChanged: Boolean) : HistoryChange
    data class TitleChanged(val url: String, val title: String) : HistoryChange
    data class Removed(val allHistory: Boolean, val urls: List<String>) : HistoryChange
}
internal data class HistoryWrite<T>(val value: T, val events: List<HistoryChange>)

internal object HistoryPolicy {
    const val MAX_TIME = 9_007_199_254_740_991L
    val TRANSITIONS = setOf("link", "typed", "auto_bookmark", "auto_subframe", "manual_subframe",
        "generated", "auto_toplevel", "form_submit", "reload", "keyword", "keyword_generated")
    fun time(value: Long): Long = value.also { require(it in 0..MAX_TIME) { "Invalid history timestamp" } }
    fun range(start: Long, end: Long) {
        time(start); time(end); require(start <= end) { "Invalid history range" }
    }
}
