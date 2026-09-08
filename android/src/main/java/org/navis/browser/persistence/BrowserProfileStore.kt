/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.persistence

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class HistoryEntry(
    val url: String,
    val title: String,
    val visitedAt: Long,
    val visitCount: Int,
    val typedCount: Int = 0,
)

internal data class AddressCandidate(val kind: String, val id: String, val title: String, val url: String,
    val lastVisit: Long = 0, val visitCount: Int = 0)

internal data class PasswordEntry(
    val guid: String,
    val origin: String,
    val username: String,
    val lastUsedAt: Long,
)

/** Engine-neutral credential record used by the private native login projection. */
internal data class StoredLogin(
    val guid: String?,
    val origin: String,
    val formActionOrigin: String?,
    val httpRealm: String?,
    val username: String,
    val password: String,
)

/**
 * Android-owned persistent browser data.
 *
 * Gecko owns cookies and Web Storage in its profile. Navis owns these product
 * records so their lifecycle and UI do not depend on Fenix or Android
 * Components. All database and Android Keystore work stays off the UI thread.
 */
internal class BrowserProfileStore(context: Context) : AutoCloseable {
    internal val closedCompletion = java.util.concurrent.CompletableFuture<Unit>()
    private val database = Database(context.applicationContext)
    private val passwordCipher = PasswordCipher()
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "NavisProfileStore").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean(false)
    private val taskAdmissionLock = Any()
    private val bookmarkObservers = CopyOnWriteArraySet<(BookmarkChange) -> Unit>()
    private val historyObservers = CopyOnWriteArraySet<(HistoryChange) -> Unit>()

    fun observeBookmarks(observer: (BookmarkChange) -> Unit) { bookmarkObservers.add(observer) }
    fun removeBookmarkObserver(observer: (BookmarkChange) -> Unit) { bookmarkObservers.remove(observer) }
    fun observeHistory(observer: (HistoryChange) -> Unit) { historyObservers.add(observer) }
    fun removeHistoryObserver(observer: (HistoryChange) -> Unit) { historyObservers.remove(observer) }

    fun recordVisit(url: String, title: String) {
        val safeUrl = normalizedWebUrl(url) ?: return
        val safeTitle = title.take(MAX_TITLE_LENGTH)
        submit {
            // The existing successful-navigation callback does not expose the
            // detailed transition/referrer. Use the API's default, never infer typed.
            historyTransaction { HistoryRecords.add(it, safeUrl, safeTitle, System.currentTimeMillis(), "link") }
        }
    }

    fun listHistory(callback: (List<HistoryEntry>) -> Unit) {
        listHistoryResult { callback(it.getOrDefault(emptyList())) }
    }

    fun listHistoryResult(callback: (Result<List<HistoryEntry>>) -> Unit) {
        queryHistoryResult(false, callback)
    }

    /** Bound the persistence projection before it crosses the Core product bridge. */
    fun addressCandidates(text: String, includeHistory: Boolean,
        callback: (Result<List<AddressCandidate>>) -> Unit) = queryResult(callback) {
        require(text.length in 1..8192)
        // Fetch a bounded superset by the first token; Core ranks all tokens across title and URL.
        val firstToken = text.trim().split(Regex("\\s+"), limit = 2).first().take(4096)
        val pattern = "%${firstToken.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")}%"
        val db = database.readableDatabase
        val bookmarks = db.query(TABLE_BOOKMARKS, arrayOf(COLUMN_ID, COLUMN_TITLE, COLUMN_URL),
            "$COLUMN_KIND = 0 AND ($COLUMN_TITLE LIKE ? ESCAPE '\\' OR $COLUMN_URL LIKE ? ESCAPE '\\')",
            arrayOf(pattern, pattern), null, null, "$COLUMN_POSITION ASC, $COLUMN_ID ASC", "200").use { cursor ->
            buildList { while (cursor.moveToNext()) add(AddressCandidate("bookmark", cursor.string(COLUMN_ID),
                cursor.string(COLUMN_TITLE), cursor.string(COLUMN_URL))) }
        }
        val history = if (includeHistory) HistoryRecords.search(db, HistorySearch(text = firstToken, limit = 200)).map {
            AddressCandidate("history", it.url.hashCode().toString(), it.title, it.url, it.visitedAt, it.visitCount)
        } else emptyList()
        bookmarks + history
    }

    fun listTopSitesResult(callback: (Result<List<HistoryEntry>>) -> Unit) {
        queryHistoryResult(true, callback)
    }

    private fun queryHistoryResult(topSites: Boolean, callback: (Result<List<HistoryEntry>>) -> Unit) {
        queryResult(callback) {
            database.readableDatabase.query(
                TABLE_HISTORY,
                arrayOf(COLUMN_URL, COLUMN_TITLE, COLUMN_VISITED_AT, COLUMN_VISIT_COUNT, COLUMN_TYPED_COUNT),
                null,
                null,
                null,
                null,
                if (topSites) "$COLUMN_VISIT_COUNT DESC, $COLUMN_VISITED_AT DESC, $COLUMN_URL ASC" else "$COLUMN_VISITED_AT DESC",
                if (topSites) "500" else null,
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            HistoryEntry(
                                url = cursor.string(COLUMN_URL),
                                title = cursor.string(COLUMN_TITLE),
                                visitedAt = cursor.long(COLUMN_VISITED_AT),
                                visitCount = cursor.int(COLUMN_VISIT_COUNT),
                                typedCount = cursor.int(COLUMN_TYPED_COUNT),
                            ),
                        )
                    }
                }
            }
        }
    }

    fun clearHistory(onComplete: () -> Unit = {}) {
        clearHistorySince(0) { it.onSuccess { onComplete() } }
    }

    fun removeHistory(url: String, onComplete: (Result<Unit>) -> Unit) {
        queryResult(onComplete) {
            val safeUrl = requireNotNull(normalizedWebUrl(url)) { "History accepts HTTP(S) addresses only" }
            historyTransaction { HistoryRecords.removeUrl(it, safeUrl) }
        }
    }

    fun clearHistorySince(
        sinceUnixTimestamp: Long,
        onComplete: (Result<Unit>) -> Unit,
    ) {
        queryResult(onComplete) {
            if (sinceUnixTimestamp <= 0L) {
                historyTransaction(HistoryRecords::clear)
            } else {
                historyTransaction { HistoryRecords.removeRange(it, sinceUnixTimestamp, HistoryPolicy.MAX_TIME) }
            }
        }
    }

    fun searchHistory(query: HistorySearch, callback: (Result<List<HistoryEntry>>) -> Unit) =
        queryResult(callback) { HistoryRecords.search(database.readableDatabase, query) }

    fun historyVisits(url: String, callback: (Result<List<HistoryVisit>>) -> Unit) = queryResult(callback) {
        val safeUrl = requireNotNull(normalizedWebUrl(url)) { "History accepts HTTP(S) addresses only" }
        HistoryRecords.visits(database.readableDatabase, safeUrl)
    }

    fun addHistoryVisit(url: String, title: String, at: Long, transition: String, callback: (Result<Unit>) -> Unit) = queryResult(callback) {
        val safeUrl = requireNotNull(normalizedWebUrl(url)) { "History accepts HTTP(S) addresses only" }
        historyTransaction { HistoryRecords.add(it, safeUrl, title.take(MAX_TITLE_LENGTH), at, transition) }
    }

    fun deleteHistoryRange(start: Long, end: Long, callback: (Result<Unit>) -> Unit) = queryResult(callback) {
        historyTransaction { HistoryRecords.removeRange(it, start, end) }
    }

    fun updateHistoryTitle(url: String, title: String) {
        val safeUrl = normalizedWebUrl(url) ?: return
        submit { historyTransaction { HistoryRecords.updateTitle(it, safeUrl, title.take(MAX_TITLE_LENGTH)) } }
    }

    private fun <T> historyTransaction(operation: (SQLiteDatabase) -> HistoryWrite<T>): T {
        val db = database.writableDatabase
        db.beginTransaction()
        val committed: HistoryWrite<T>
        try {
            committed = operation(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        if (committed.events.isNotEmpty()) mainHandler.post {
            committed.events.forEach { change -> historyObservers.forEach { observer -> runCatching { observer(change) } } }
        }
        return committed.value
    }

    fun addBookmark(url: String, title: String, onComplete: () -> Unit = {}) {
        val safeUrl = normalizedBookmarkUrl(url) ?: return
        queryResult<Unit>({ it.onSuccess { onComplete() } }) {
          bookmarkTransaction { db ->
            val existing = db.query(TABLE_BOOKMARKS, arrayOf(COLUMN_ID),
                "$COLUMN_URL = ? AND $COLUMN_KIND = 0", arrayOf(safeUrl), null, null, null, "1",
            ).use { cursor -> cursor.takeIf(Cursor::moveToFirst)?.string(COLUMN_ID) }
            if (existing == null) {
                saveBookmarkRecord(db, BookmarkDraft(title = title.ifBlank { safeUrl }, url = safeUrl))
            } else {
                check(db.update(TABLE_BOOKMARKS, ContentValues().apply {
                    put(COLUMN_TITLE, title.take(MAX_TITLE_LENGTH))
                    put(COLUMN_UPDATED_AT, System.currentTimeMillis())
                }, "$COLUMN_ID = ?", arrayOf(existing)) == 1)
            }
            Unit
          }
        }
    }

    fun saveBookmark(draft: BookmarkDraft, onComplete: (Result<Unit>) -> Unit) {
        queryResult(onComplete) {
            bookmarkTransaction { db ->
                saveBookmarkRecord(db, draft)
                Unit
            }
        }
    }

    fun moveBookmark(id: String, parentId: String?, position: Int, onComplete: (Result<Unit>) -> Unit) {
        queryResult(onComplete) {
            bookmarkTransaction(id) { db ->
                validateBookmarkParent(db, id, parentId)
                check(db.update(TABLE_BOOKMARKS, ContentValues().apply {
                    put(COLUMN_PARENT_ID, parentId)
                    put(COLUMN_UPDATED_AT, System.currentTimeMillis())
                }, "$COLUMN_ID = ?", arrayOf(id)) == 1) { "Bookmark does not exist" }
                val siblings = bookmarkSiblingIds(db, parentId).filterNot { it == id }.toMutableList()
                siblings.add(position.coerceIn(0, siblings.size), id)
                siblings.forEachIndexed { index, sibling ->
                    db.update(TABLE_BOOKMARKS, ContentValues().apply { put(COLUMN_POSITION, index) },
                        "$COLUMN_ID = ?", arrayOf(sibling))
                }
                Unit
            }
        }
    }

    fun deleteBookmark(id: String, onComplete: (Result<Unit>) -> Unit) {
        queryResult(onComplete) {
          bookmarkTransaction { db ->
            check(db.delete(TABLE_BOOKMARKS, "$COLUMN_ID = ?", arrayOf(id)) == 1) {
                "Bookmark does not exist"
            }
          }
        }
    }

    fun removeBookmark(url: String, onComplete: () -> Unit = {}) {
        val safeUrl = normalizedBookmarkUrl(url) ?: return
        queryResult<Unit>({ it.onSuccess { onComplete() } }) {
          bookmarkTransaction { db ->
            db.delete(
                TABLE_BOOKMARKS,
                "$COLUMN_URL = ?",
                arrayOf(safeUrl),
            )
            Unit
          }
        }
    }

    fun listBookmarks(callback: (List<BookmarkEntry>) -> Unit) {
        listBookmarksResult { callback(it.getOrDefault(emptyList())) }
    }

    fun listBookmarksResult(callback: (Result<List<BookmarkEntry>>) -> Unit) {
        queryResult(callback) { readBookmarks(database.readableDatabase) }
    }

    private fun readBookmarks(db: SQLiteDatabase): List<BookmarkEntry> = db.query(
                TABLE_BOOKMARKS,
                arrayOf(COLUMN_ID, COLUMN_URL, COLUMN_TITLE, COLUMN_CREATED_AT,
                    COLUMN_PARENT_ID, COLUMN_POSITION, COLUMN_KIND),
                null,
                null,
                null,
                null,
                "$COLUMN_POSITION ASC, $COLUMN_CREATED_AT ASC, $COLUMN_ID ASC",
                null,
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            BookmarkEntry(
                                url = cursor.nullableString(COLUMN_URL).orEmpty(),
                                title = cursor.string(COLUMN_TITLE),
                                createdAt = cursor.long(COLUMN_CREATED_AT),
                                id = cursor.string(COLUMN_ID),
                                parentId = cursor.nullableString(COLUMN_PARENT_ID),
                                position = cursor.int(COLUMN_POSITION),
                                isFolder = cursor.int(COLUMN_KIND) == 1,
                            ),
                        )
                    }
                }
            }

    private fun <T> bookmarkTransaction(movedId: String? = null, operation: (SQLiteDatabase) -> T): T {
        val db = database.writableDatabase
        db.beginTransaction()
        val before: List<BookmarkEntry>
        val after: List<BookmarkEntry>
        val result: T
        try {
            before = readBookmarks(db)
            result = operation(db)
            // Keep API indices contiguous after removals and cross-folder moves.
            readBookmarks(db).groupBy { it.parentId }.values.forEach { children ->
                children.forEachIndexed { index, child ->
                    if (child.position != index) db.update(TABLE_BOOKMARKS,
                        ContentValues().apply { put(COLUMN_POSITION, index) }, "$COLUMN_ID = ?", arrayOf(child.id))
                }
            }
            after = readBookmarks(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        if (before != after) {
            val change = BookmarkChange(before, after, movedId)
            mainHandler.post { bookmarkObservers.forEach { observer -> runCatching { observer(change) } } }
        }
        return result
    }

    /** Strict extension writes share the product transaction and observer path. */
    fun mutateExtensionBookmark(change: ExtensionBookmarkMutation, callback: (Result<BookmarkEntry?>) -> Unit) {
        queryResult(callback) {
            bookmarkTransaction((change as? ExtensionBookmarkMutation.Move)?.id) { db ->
                fun existing(id: String): BookmarkEntry = readBookmarks(db).firstOrNull { it.id == id }
                    ?: throw IllegalArgumentException("Bookmark does not exist")
                fun reorder(id: String, parent: String?, index: Int?) {
                    val siblings = bookmarkSiblingIds(db, parent).filterNot { it == id }.toMutableList()
                    require(index == null || index in 0..siblings.size) { "Invalid bookmark index" }
                    siblings.add(index ?: siblings.size, id)
                    siblings.forEachIndexed { i, child -> db.update(TABLE_BOOKMARKS,
                        ContentValues().apply { put(COLUMN_POSITION, i) }, "$COLUMN_ID = ?", arrayOf(child)) }
                }
                val id = when (change) {
                    is ExtensionBookmarkMutation.Create -> {
                        val created = saveBookmarkRecord(db, change.draft, allowEmptyTitle = true)
                        reorder(created, change.draft.parentId, change.index)
                        created
                    }
                    is ExtensionBookmarkMutation.Update -> {
                        val old = existing(change.id)
                        require(!old.isFolder || change.url == null) { "A folder cannot have a URL" }
                        saveBookmarkRecord(db, BookmarkDraft(old.id, change.title ?: old.title,
                            change.url ?: old.url, old.parentId, old.isFolder), allowEmptyTitle = true)
                    }
                    is ExtensionBookmarkMutation.Move -> {
                        val old = existing(change.id)
                        val parent = if (change.parentSpecified) change.parent else old.parentId
                        validateBookmarkParent(db, old.id, parent)
                        db.update(TABLE_BOOKMARKS, ContentValues().apply { put(COLUMN_PARENT_ID, parent) },
                            "$COLUMN_ID = ?", arrayOf(old.id))
                        reorder(old.id, parent, change.index)
                        old.id
                    }
                    is ExtensionBookmarkMutation.Remove -> {
                        val old = existing(change.id)
                        require(change.recursive || !old.isFolder || bookmarkSiblingIds(db, old.id).isEmpty()) {
                            "Cannot remove a non-empty bookmark folder"
                        }
                        check(db.delete(TABLE_BOOKMARKS, "$COLUMN_ID = ?", arrayOf(old.id)) == 1)
                        null
                    }
                }
                id?.let(::existing)
            }
        }
    }

    fun listPasswords(callback: (List<PasswordEntry>) -> Unit) {
        query(callback, emptyList()) {
            database.readableDatabase.query(
                TABLE_LOGINS,
                arrayOf(COLUMN_GUID, COLUMN_ORIGIN, COLUMN_USERNAME, COLUMN_LAST_USED_AT),
                null,
                null,
                null,
                null,
                "$COLUMN_LAST_USED_AT DESC, $COLUMN_MODIFIED_AT DESC",
                null,
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            PasswordEntry(
                                guid = cursor.string(COLUMN_GUID),
                                origin = cursor.string(COLUMN_ORIGIN),
                                username = cursor.string(COLUMN_USERNAME),
                                lastUsedAt = cursor.long(COLUMN_LAST_USED_AT),
                            ),
                        )
                    }
                }
            }
        }
    }

    fun removePassword(guid: String, onComplete: () -> Unit = {}) {
        val safeGuid = guid.takeIf { it.isNotBlank() && it.length <= MAX_GUID_LENGTH } ?: return
        submit(onComplete) {
            database.writableDatabase.delete(TABLE_LOGINS, "$COLUMN_GUID = ?", arrayOf(safeGuid))
        }
    }

    fun deletePassword(guid: String, onComplete: (Result<Unit>) -> Unit) {
        submitResult(onComplete) {
            require(guid.isNotBlank() && guid.length <= MAX_GUID_LENGTH)
            check(database.writableDatabase.delete(TABLE_LOGINS, "$COLUMN_GUID = ?", arrayOf(guid)) == 1) {
                "Password does not exist"
            }
        }
    }

    /** Decrypt exactly one record only after an explicit reveal request. */
    fun revealPassword(guid: String, onComplete: (Result<String>) -> Unit) {
        queryResult(onComplete) {
            require(guid.isNotBlank() && guid.length <= MAX_GUID_LENGTH)
            database.readableDatabase.query(TABLE_LOGINS, LOGIN_COLUMNS, "$COLUMN_GUID = ?",
                arrayOf(guid), null, null, null, "1").use { cursor ->
                check(cursor.moveToFirst()) { "Password does not exist" }
                val origin = cursor.string(COLUMN_ORIGIN)
                check(normalizedOrigin(origin) == origin)
                passwordCipher.decrypt(cursor.string(COLUMN_PASSWORD), guid, origin)
            }
        }
    }

    fun clearPasswordsSince(
        sinceUnixTimestamp: Long,
        onComplete: (Result<Unit>) -> Unit,
    ) {
        submitResult(onComplete) {
            if (sinceUnixTimestamp <= 0L) {
                database.writableDatabase.delete(TABLE_LOGINS, null, null)
            } else {
                database.writableDatabase.delete(
                    TABLE_LOGINS,
                    "$COLUMN_MODIFIED_AT >= ?",
                    arrayOf(sinceUnixTimestamp.toString()),
                )
            }
        }
    }

    fun fetchLogins(domain: String, callback: (List<StoredLogin>) -> Unit) {
        val requestHost = LoginDomainPolicy.canonicalHostname(domain)
        if (requestHost == null) {
            mainHandler.post { callback(emptyList()) }
            return
        }
        val candidates = loginCandidateSelection(requestHost)
        queryLogins(candidates.clause, candidates.arguments, callback) { entry ->
            val savedHost = originHost(entry.origin) ?: return@queryLogins false
            LoginDomainPolicy.matchesTrustedBase(savedHost, requestHost)
        }
    }

    fun fetchAllLogins(callback: (List<StoredLogin>) -> Unit) {
        queryLogins(callback = callback) { true }
    }

    fun saveLogin(login: StoredLogin, callback: (Result<Unit>) -> Unit) {
        val username = login.username
        val password = login.password
        submitResult(callback) {
            val origin = requireNotNull(normalizedOrigin(login.origin)) { "Invalid login origin" }
            val originHost = requireNotNull(originHost(origin)) { "Invalid login host" }
            require(LoginSavePolicy.accepts(username, password)) { "Invalid login fields" }
            val db = database.writableDatabase
            db.beginTransaction()
            try {
                val now = System.currentTimeMillis()
                val guid = login.guid
                    ?.takeIf { it.isNotBlank() && it.length <= MAX_GUID_LENGTH }
                    ?: findMatchingGuid(db, login, origin, username)
                    ?: UUID.randomUUID().toString()
                val createdAt = findCreatedAt(db, guid) ?: now
                val rowId = db.insertWithOnConflict(
                    TABLE_LOGINS,
                    null,
                    ContentValues().apply {
                        put(COLUMN_GUID, guid)
                        put(COLUMN_ORIGIN, origin)
                        put(COLUMN_ORIGIN_HOST, originHost)
                        put(COLUMN_FORM_ACTION_ORIGIN, login.formActionOrigin?.take(MAX_URL_LENGTH))
                        put(COLUMN_HTTP_REALM, login.httpRealm?.take(MAX_REALM_LENGTH))
                        put(COLUMN_USERNAME, username)
                        put(COLUMN_PASSWORD, passwordCipher.encrypt(password, guid, origin))
                        put(COLUMN_CREATED_AT, createdAt)
                        put(COLUMN_MODIFIED_AT, now)
                        put(COLUMN_LAST_USED_AT, now)
                        put(COLUMN_TIMES_USED, 0)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
                check(rowId != -1L) { "Login write failed" }
                db.setTransactionSuccessful()
            } finally {
                // A commit failure is also a failed result. Never acknowledge before this returns.
                db.endTransaction()
            }
        }
    }

    fun markLoginUsed(guid: String) {
        val safeGuid = guid.takeIf { it.isNotBlank() && it.length <= MAX_GUID_LENGTH } ?: return
        submit {
            val db = database.writableDatabase
            db.execSQL(
                "UPDATE $TABLE_LOGINS SET $COLUMN_LAST_USED_AT = ?, " +
                    "$COLUMN_TIMES_USED = $COLUMN_TIMES_USED + 1 WHERE $COLUMN_GUID = ?",
                arrayOf<Any>(System.currentTimeMillis(), safeGuid),
            )
        }
    }

    override fun close() {
        synchronized(taskAdmissionLock) {
            if (!closed.compareAndSet(false, true)) {
                return
            }
            bookmarkObservers.clear()
            historyObservers.clear()
            executor.execute {
                runCatching(database::close)
                    .onSuccess { closedCompletion.complete(Unit) }
                    .onFailure(closedCompletion::completeExceptionally)
            }
            executor.shutdown()
        }
    }

    private fun queryLogins(
        selection: String? = null,
        selectionArgs: Array<String>? = null,
        callback: (List<StoredLogin>) -> Unit,
        predicate: (StoredLogin) -> Boolean,
    ) {
        query(callback, emptyList()) {
            database.readableDatabase.query(
                TABLE_LOGINS,
                LOGIN_COLUMNS,
                selection,
                selectionArgs,
                null,
                null,
                "$COLUMN_LAST_USED_AT DESC, $COLUMN_MODIFIED_AT DESC",
                null,
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        decodeLogin(cursor)?.takeIf(predicate)?.let(::add)
                    }
                }
            }
        }
    }

    private fun loginCandidateSelection(requestHost: String): LoginCandidateSelection {
        val legacyOrExact = "$COLUMN_ORIGIN_HOST IS NULL OR $COLUMN_ORIGIN_HOST = ?"
        if (!LoginDomainPolicy.admitsSubdomains(requestHost)) {
            return LoginCandidateSelection(legacyOrExact, arrayOf(requestHost))
        }
        val suffix = ".$requestHost"
        return LoginCandidateSelection(
            "$legacyOrExact OR (LENGTH($COLUMN_ORIGIN_HOST) > ? AND " +
                "SUBSTR($COLUMN_ORIGIN_HOST, -?) = ?)",
            arrayOf(
                requestHost,
                suffix.length.toString(),
                suffix.length.toString(),
                suffix,
            ),
        )
    }

    private fun decodeLogin(cursor: Cursor): StoredLogin? = runCatching {
        val guid = cursor.string(COLUMN_GUID)
        val storedOrigin = cursor.string(COLUMN_ORIGIN)
        val origin = normalizedOrigin(storedOrigin)?.takeIf { it == storedOrigin }
            ?: return@runCatching null
        StoredLogin(
            guid = guid,
            origin = origin,
            formActionOrigin = cursor.nullableString(COLUMN_FORM_ACTION_ORIGIN),
            httpRealm = cursor.nullableString(COLUMN_HTTP_REALM),
            username = cursor.string(COLUMN_USERNAME),
            password = passwordCipher.decrypt(cursor.string(COLUMN_PASSWORD), guid, origin),
        )
    }.getOrNull()

    private fun findMatchingGuid(
        db: SQLiteDatabase,
        login: StoredLogin,
        origin: String,
        username: String,
    ): String? = db.query(
        TABLE_LOGINS,
        arrayOf(COLUMN_GUID),
        "$COLUMN_ORIGIN = ? AND $COLUMN_USERNAME = ? AND " +
            "COALESCE($COLUMN_FORM_ACTION_ORIGIN, '') = ? AND " +
            "COALESCE($COLUMN_HTTP_REALM, '') = ?",
        arrayOf(origin, username, login.formActionOrigin.orEmpty(), login.httpRealm.orEmpty()),
        null,
        null,
        null,
        "1",
    ).use { cursor -> cursor.takeIf(Cursor::moveToFirst)?.string(COLUMN_GUID) }

    private fun findCreatedAt(db: SQLiteDatabase, guid: String): Long? = db.query(
        TABLE_LOGINS,
        arrayOf(COLUMN_CREATED_AT),
        "$COLUMN_GUID = ?",
        arrayOf(guid),
        null,
        null,
        null,
        "1",
    ).use { cursor -> cursor.takeIf(Cursor::moveToFirst)?.long(COLUMN_CREATED_AT) }

    private fun saveBookmarkRecord(db: SQLiteDatabase, draft: BookmarkDraft, allowEmptyTitle: Boolean = false): String {
        val id = draft.id ?: UUID.randomUUID().toString()
        require(id.isNotBlank() && id.length <= MAX_GUID_LENGTH)
        val title = draft.title.trim().take(MAX_TITLE_LENGTH)
        require(allowEmptyTitle || title.isNotEmpty()) { "A bookmark title is required" }
        val url = if (draft.isFolder) null else {
            normalizedBookmarkUrl(draft.url)
                ?: throw IllegalArgumentException("A valid bookmark address is required")
        }
        validateBookmarkParent(db, id, draft.parentId)
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put(COLUMN_URL, url)
            put(COLUMN_TITLE, title)
            put(COLUMN_PARENT_ID, draft.parentId)
            put(COLUMN_UPDATED_AT, now)
            put(COLUMN_KIND, if (draft.isFolder) 1 else 0)
        }
        if (draft.id == null) {
            values.put(COLUMN_ID, id)
            values.put(COLUMN_CREATED_AT, now)
            values.put(COLUMN_POSITION, nextBookmarkPosition(db, draft.parentId))
            db.insertOrThrow(TABLE_BOOKMARKS, null, values)
        } else {
            val original = db.query(TABLE_BOOKMARKS, arrayOf(COLUMN_KIND, COLUMN_PARENT_ID),
                "$COLUMN_ID = ?", arrayOf(id), null, null, null, "1").use { cursor ->
                check(cursor.moveToFirst()) { "Bookmark does not exist" }
                (cursor.int(COLUMN_KIND) == 1) to cursor.nullableString(COLUMN_PARENT_ID)
            }
            require(original.first == draft.isFolder) { "A folder cannot become a bookmark" }
            if (original.second != draft.parentId) {
                values.put(COLUMN_POSITION, nextBookmarkPosition(db, draft.parentId))
            }
            check(db.update(TABLE_BOOKMARKS, values, "$COLUMN_ID = ?", arrayOf(id)) == 1)
        }
        return id
    }

    private fun validateBookmarkParent(db: SQLiteDatabase, id: String, parentId: String?) {
        val visited = mutableSetOf(id)
        var ancestor = parentId
        while (ancestor != null) {
            require(visited.add(ancestor)) { "A folder cannot contain itself" }
            ancestor = db.query(TABLE_BOOKMARKS, arrayOf(COLUMN_PARENT_ID, COLUMN_KIND),
                "$COLUMN_ID = ?", arrayOf(ancestor), null, null, null, "1").use { cursor ->
                require(cursor.moveToFirst() && cursor.int(COLUMN_KIND) == 1) { "Folder does not exist" }
                cursor.nullableString(COLUMN_PARENT_ID)
            }
        }
    }

    private fun bookmarkSiblingIds(db: SQLiteDatabase, parentId: String?): List<String> = db.query(
        TABLE_BOOKMARKS, arrayOf(COLUMN_ID),
        if (parentId == null) "$COLUMN_PARENT_ID IS NULL" else "$COLUMN_PARENT_ID = ?",
        parentId?.let { arrayOf(it) },
        null, null, "$COLUMN_POSITION ASC, $COLUMN_CREATED_AT ASC, $COLUMN_ID ASC",
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.string(COLUMN_ID)) } }

    private fun nextBookmarkPosition(db: SQLiteDatabase, parentId: String?): Int = db.rawQuery(
        "SELECT COALESCE(MAX($COLUMN_POSITION), -1) + 1 FROM $TABLE_BOOKMARKS WHERE " +
            if (parentId == null) "$COLUMN_PARENT_ID IS NULL" else "$COLUMN_PARENT_ID = ?",
        parentId?.let { arrayOf(it) },
    ).use { cursor -> check(cursor.moveToFirst()); cursor.getInt(0) }

    private fun submit(task: () -> Unit) {
        enqueue { runCatching(task) }
    }

    private fun submit(onComplete: () -> Unit, task: () -> Unit) {
        enqueue {
            // The legacy callback means success. Failure must never notify success.
            runCatching(task).onSuccess { mainHandler.post(onComplete) }
        }
    }

    private fun submitResult(
        onComplete: (Result<Unit>) -> Unit,
        task: () -> Unit,
    ) {
        if (!enqueue {
                val result = runCatching(task)
                mainHandler.post { onComplete(result) }
            }
        ) {
            mainHandler.post {
                onComplete(Result.failure(IllegalStateException("Profile store is closed")))
            }
        }
    }

    private fun <T> query(callback: (T) -> Unit, fallback: T, task: () -> T) {
        if (!enqueue {
                val value = runCatching(task).getOrElse { fallback }
                mainHandler.post { callback(value) }
            }
        ) {
            mainHandler.post { callback(fallback) }
        }
    }

    private fun <T> queryResult(callback: (Result<T>) -> Unit, task: () -> T) {
        if (!enqueue {
                val result = runCatching(task)
                mainHandler.post { callback(result) }
            }) {
            mainHandler.post { callback(Result.failure(IllegalStateException("Profile store is closed"))) }
        }
    }

    private fun enqueue(task: () -> Unit): Boolean = synchronized(taskAdmissionLock) {
        if (closed.get()) {
            false
        } else {
            executor.execute(task)
            true
        }
    }

    private class Database(context: Context) :
        SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
        override fun onConfigure(db: SQLiteDatabase) {
            db.setForeignKeyConstraintsEnabled(true)
            db.enableWriteAheadLogging()
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(HistorySchema.CREATE_PAGES)
            createHistoryVisits(db)
            createBookmarks(db)
            db.execSQL(
                "CREATE TABLE $TABLE_LOGINS (" +
                    "$COLUMN_GUID TEXT PRIMARY KEY NOT NULL, " +
                    "$COLUMN_ORIGIN TEXT NOT NULL, " +
                    "$COLUMN_ORIGIN_HOST TEXT NOT NULL, " +
                    "$COLUMN_FORM_ACTION_ORIGIN TEXT, " +
                    "$COLUMN_HTTP_REALM TEXT, " +
                    "$COLUMN_USERNAME TEXT NOT NULL, " +
                    "$COLUMN_PASSWORD TEXT NOT NULL, " +
                    "$COLUMN_CREATED_AT INTEGER NOT NULL, " +
                    "$COLUMN_MODIFIED_AT INTEGER NOT NULL, " +
                    "$COLUMN_LAST_USED_AT INTEGER NOT NULL, " +
                    "$COLUMN_TIMES_USED INTEGER NOT NULL)",
            )
            db.execSQL(
                "CREATE INDEX $INDEX_LOGINS_ORIGIN ON $TABLE_LOGINS ($COLUMN_ORIGIN)",
            )
            db.execSQL(
                "CREATE INDEX $INDEX_LOGINS_ORIGIN_HOST ON $TABLE_LOGINS ($COLUMN_ORIGIN_HOST)",
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            var currentVersion = oldVersion
            if (currentVersion == 1) {
                db.execSQL("ALTER TABLE $TABLE_LOGINS ADD COLUMN $COLUMN_ORIGIN_HOST TEXT")
                backfillOriginHosts(db)
                db.execSQL(
                    "CREATE INDEX $INDEX_LOGINS_ORIGIN_HOST " +
                        "ON $TABLE_LOGINS ($COLUMN_ORIGIN_HOST)",
                )
                currentVersion = 2
            }
            if (currentVersion == 2) {
                // SQLiteOpenHelper wraps this upgrade in a transaction: failures
                // roll back the original table as well as every migrated record.
                db.execSQL("ALTER TABLE $TABLE_BOOKMARKS RENAME TO bookmarks_v2")
                createBookmarks(db)
                db.query("bookmarks_v2", null, null, null, null, null,
                    "$COLUMN_UPDATED_AT DESC, $COLUMN_URL ASC").use { cursor ->
                    var position = 0
                    while (cursor.moveToNext()) {
                        db.insertOrThrow(TABLE_BOOKMARKS, null, ContentValues().apply {
                            put(COLUMN_ID, UUID.randomUUID().toString())
                            put(COLUMN_URL, cursor.string(COLUMN_URL))
                            put(COLUMN_TITLE, cursor.string(COLUMN_TITLE))
                            put(COLUMN_CREATED_AT, cursor.long(COLUMN_CREATED_AT))
                            put(COLUMN_UPDATED_AT, cursor.long(COLUMN_UPDATED_AT))
                            put(COLUMN_POSITION, position++)
                            put(COLUMN_KIND, 0)
                        })
                    }
                }
                db.execSQL("DROP TABLE bookmarks_v2")
                currentVersion = 3
            }
            if (currentVersion == 3) {
                HistorySchema.MIGRATE_V3.forEach(db::execSQL)
                createHistoryVisits(db)
                currentVersion = 4
            }
            check(currentVersion == newVersion) {
                "No Navis Android profile migration exists from $oldVersion to $newVersion"
            }
        }

        private fun createBookmarks(db: SQLiteDatabase) {
            db.execSQL(BookmarkSchema.CREATE_TABLE)
            db.execSQL(BookmarkSchema.CREATE_PARENT_INDEX)
            db.execSQL(BookmarkSchema.CREATE_URL_INDEX)
        }

        private fun createHistoryVisits(db: SQLiteDatabase) {
            db.execSQL(HistorySchema.CREATE_VISITS)
            db.execSQL(HistorySchema.CREATE_URL_INDEX)
            db.execSQL(HistorySchema.CREATE_TIME_INDEX)
        }

        private fun backfillOriginHosts(db: SQLiteDatabase) {
            val hosts = db.query(
                TABLE_LOGINS,
                arrayOf(COLUMN_GUID, COLUMN_ORIGIN),
                "$COLUMN_ORIGIN_HOST IS NULL",
                null,
                null,
                null,
                null,
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val host = originHost(cursor.string(COLUMN_ORIGIN)) ?: continue
                        add(cursor.string(COLUMN_GUID) to host)
                    }
                }
            }
            hosts.forEach { (guid, host) ->
                db.update(
                    TABLE_LOGINS,
                    ContentValues().apply { put(COLUMN_ORIGIN_HOST, host) },
                    "$COLUMN_GUID = ? AND $COLUMN_ORIGIN_HOST IS NULL",
                    arrayOf(guid),
                )
            }
        }
    }

    private class PasswordCipher {
        private val key: SecretKey
            get() {
                val store = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
                (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
                val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
                generator.init(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build(),
                )
                return generator.generateKey()
            }

        fun encrypt(value: String, guid: String, normalizedOrigin: String): String {
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.updateAAD(LoginCipherBinding.associatedData(guid, normalizedOrigin))
            val ciphertext = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
            return listOf(
                ENCRYPTION_VERSION,
                Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
                Base64.encodeToString(ciphertext, Base64.NO_WRAP),
            ).joinToString(ENCRYPTION_SEPARATOR)
        }

        fun decrypt(encoded: String, guid: String, normalizedOrigin: String): String {
            val parts = encoded.split(ENCRYPTION_SEPARATOR, limit = 3)
            require(parts.size == 3 && parts[0] == ENCRYPTION_VERSION)
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(128, Base64.decode(parts[1], Base64.NO_WRAP)),
            )
            cipher.updateAAD(LoginCipherBinding.associatedData(guid, normalizedOrigin))
            return String(
                cipher.doFinal(Base64.decode(parts[2], Base64.NO_WRAP)),
                StandardCharsets.UTF_8,
            )
        }
    }

    private companion object {
        const val DATABASE_NAME = "navis_profile.sqlite"
        const val DATABASE_VERSION = 4
        const val TABLE_HISTORY = "history"
        const val TABLE_BOOKMARKS = "bookmarks"
        const val TABLE_LOGINS = "logins"
        const val COLUMN_URL = "url"
        const val COLUMN_ID = "id"
        const val COLUMN_PARENT_ID = "parent_id"
        const val COLUMN_POSITION = "position"
        const val COLUMN_KIND = "kind"
        const val COLUMN_TITLE = "title"
        const val COLUMN_VISITED_AT = "visited_at"
        const val COLUMN_VISIT_COUNT = "visit_count"
        const val COLUMN_TYPED_COUNT = "typed_count"
        const val COLUMN_CREATED_AT = "created_at"
        const val COLUMN_UPDATED_AT = "updated_at"
        const val COLUMN_GUID = "guid"
        const val COLUMN_ORIGIN = "origin"
        const val COLUMN_ORIGIN_HOST = "origin_host"
        const val COLUMN_FORM_ACTION_ORIGIN = "form_action_origin"
        const val COLUMN_HTTP_REALM = "http_realm"
        const val COLUMN_USERNAME = "username"
        const val COLUMN_PASSWORD = "password"
        const val COLUMN_MODIFIED_AT = "modified_at"
        const val COLUMN_LAST_USED_AT = "last_used_at"
        const val COLUMN_TIMES_USED = "times_used"
        const val INDEX_LOGINS_ORIGIN = "logins_origin_idx"
        const val INDEX_LOGINS_ORIGIN_HOST = "logins_origin_host_idx"
        const val MAX_URL_LENGTH = 16_384
        const val MAX_TITLE_LENGTH = 4_096
        const val MAX_GUID_LENGTH = 128
        const val MAX_REALM_LENGTH = 4_096
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "navis.passwords.v1"
        const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        const val ENCRYPTION_VERSION = "v2"
        const val ENCRYPTION_SEPARATOR = "."
        val LOGIN_COLUMNS = arrayOf(
            COLUMN_GUID,
            COLUMN_ORIGIN,
            COLUMN_ORIGIN_HOST,
            COLUMN_FORM_ACTION_ORIGIN,
            COLUMN_HTTP_REALM,
            COLUMN_USERNAME,
            COLUMN_PASSWORD,
        )

        fun normalizedWebUrl(value: String): String? {
            val safe = value.trim().takeIf {
                it.isNotEmpty() && it.length <= MAX_URL_LENGTH && !it.any(Char::isISOControl)
            } ?: return null
            val uri = runCatching { Uri.parse(safe) }.getOrNull() ?: return null
            val isWeb = uri.scheme.equals("http", true) || uri.scheme.equals("https", true)
            return safe.takeIf {
                isWeb && uri.isHierarchical && originHost(safe) != null
            }
        }

        fun normalizedBookmarkUrl(value: String): String? {
            normalizedWebUrl(value)?.let { return it }
            val safe = value.trim()
            return safe.takeIf {
                it.length <= MAX_URL_LENGTH && !it.any(Char::isISOControl) &&
                    (it.startsWith("navis://") || it == "about:blank")
            }
        }

        fun normalizedOrigin(value: String): String? = normalizedWebUrl(value)?.let { safe ->
            val uri = Uri.parse(safe)
            val host = originHost(safe) ?: return null
            buildString {
                append(uri.scheme?.lowercase())
                append("://")
                if (':' in host) {
                    append('[')
                    append(host)
                    append(']')
                } else {
                    append(host)
                }
                if (uri.port != -1) {
                    append(':')
                    append(uri.port)
                }
            }
        }

        fun originHost(value: String): String? = runCatching { Uri.parse(value).host }
            .getOrNull()
            ?.let(LoginDomainPolicy::canonicalHostname)

        data class LoginCandidateSelection(
            val clause: String,
            val arguments: Array<String>,
        )

        fun Cursor.string(column: String): String = getString(getColumnIndexOrThrow(column))

        fun Cursor.nullableString(column: String): String? {
            val index = getColumnIndexOrThrow(column)
            return if (isNull(index)) null else getString(index)
        }

        fun Cursor.long(column: String): Long = getLong(getColumnIndexOrThrow(column))

        fun Cursor.int(column: String): Int = getInt(getColumnIndexOrThrow(column))
    }
}
