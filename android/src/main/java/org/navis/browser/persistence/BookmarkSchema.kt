/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.persistence

/** Kept as complete SQL statements so migration checks can exercise real SQLite. */
internal object BookmarkSchema {
    const val CREATE_TABLE = """CREATE TABLE bookmarks (
        id TEXT PRIMARY KEY NOT NULL,
        url TEXT,
        title TEXT NOT NULL,
        created_at INTEGER NOT NULL,
        updated_at INTEGER NOT NULL,
        parent_id TEXT REFERENCES bookmarks(id) ON DELETE CASCADE,
        position INTEGER NOT NULL DEFAULT 0,
        kind INTEGER NOT NULL DEFAULT 0 CHECK(kind IN (0, 1)),
        CHECK((kind = 0 AND url IS NOT NULL) OR (kind = 1 AND url IS NULL)),
        CHECK(parent_id IS NULL OR parent_id != id)
    )"""
    const val CREATE_PARENT_INDEX = "CREATE INDEX bookmarks_parent_idx ON bookmarks(parent_id, position)"
    const val CREATE_URL_INDEX = "CREATE INDEX bookmarks_url_idx ON bookmarks(url)"
}
