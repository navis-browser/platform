/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */
package org.navis.browser.persistence

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject
import org.navis.browser.api.SessionMode
import org.navis.browser.pages.EnginePageHistory
import org.navis.browser.pages.NativePageHistory
import org.navis.browser.pages.NativePageHistorySnapshot

internal data class ClosedTabRecord(
    val sessionId: String,
    /** A previous process's window ID is metadata, not authority over a live window. */
    val windowId: Long,
    val index: Int,
    val closedAt: Long,
    val snapshot: PersistedSession,
    /** Only an in-process record may select an existing window with this ID. Never persisted. */
    val liveWindowId: Long? = null,
)

internal data class ClosedTabClaim(val record: ClosedTabRecord, val token: String)

/** The same complete engine snapshot and validated native presentation metadata as open tabs. */
internal object ClosedTabSnapshotCodec {
    fun encode(snapshot: PersistedSession): JSONObject {
        val actual = checkNotNull(EnginePageHistory.parse(snapshot.engineState)) { "No authentic engine history" }
        require(actual.current?.uri == snapshot.uri) { "Closed tab does not match the engine's committed entry" }
        require(snapshot.uri.length <= 8192 && snapshot.uri.matches(Regex("(?i)^https?://.+")))
        require(!snapshot.nativeNewTab)
        val output = JSONObject().put("uri", snapshot.uri).put("title", snapshot.title.take(4096))
            .put("engineState", snapshot.engineState)
        snapshot.nativeHistory?.let { history ->
            require(NativePageHistory.validated(history, snapshot.uri) != null &&
                NativePageHistory.boundTo(history, snapshot.engineState)) { "Native history does not match its engine snapshot" }
            output.put("nativeHistory", JSONObject().put("entries", JSONArray(history.entries))
                .put("index", history.index).put("engineSlots", JSONArray(history.engineSlots))
                .put("suppressedSlots", JSONArray(history.suppressedSlots)))
        }
        return output
    }

    fun decode(value: JSONObject): PersistedSession {
        val uri = value.getString("uri")
        val state = value.getString("engineState")
        val engine = checkNotNull(EnginePageHistory.parse(state))
        val native = value.optJSONObject("nativeHistory")?.let { data ->
            val entries = data.getJSONArray("entries")
            val slots = data.getJSONArray("engineSlots")
            val suppressed = data.optJSONArray("suppressedSlots") ?: JSONArray()
            require(entries.length() in 1..(NativePageHistory.MAX_ENTRIES + EnginePageHistory.MAX_ENTRIES))
            require(slots.length() == entries.length() && suppressed.length() <= EnginePageHistory.MAX_ENTRIES)
            NativePageHistorySnapshot((0 until entries.length()).map(entries::getString), data.getInt("index"), engine,
                (0 until slots.length()).map(slots::getInt), (0 until suppressed.length()).map(suppressed::getInt))
        }
        return PersistedSession(uri, value.optString("title").take(4096), state, false, nativeHistory = native)
            .also(::encode)
    }
}

internal interface ClosedTabsPersistence {
    fun read(): List<ClosedTabRecord>
    fun write(records: List<ClosedTabRecord>)
}

/** All disk operations run on the store's single worker; AtomicFile never replaces a good file on failure. */
internal class AtomicClosedTabsPersistence(file: File) : ClosedTabsPersistence {
    private val file = AtomicFile(file)
    override fun read(): List<ClosedTabRecord> {
        // openRead also recovers AtomicFile's backup after an interrupted write.
        val input = try { file.openRead() } catch (_: FileNotFoundException) { return emptyList() }
        val data = input.use {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = it.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_BYTES) { "Closed-tab file exceeds its storage limit" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        val array = JSONArray(String(data, Charsets.UTF_8))
        require(array.length() <= ClosedTabsStore.MAX_RECORDS)
        val records = (0 until array.length()).map { index ->
            val value = array.getJSONObject(index)
            ClosedTabRecord(value.getString("sessionId"), value.getLong("windowId"), value.getInt("index"),
                value.getLong("closedAt"), ClosedTabSnapshotCodec.decode(value.getJSONObject("snapshot")))
                .also(ClosedTabsStore::validateRecord)
        }
        require(records.map { it.sessionId }.distinct().size == records.size)
        return records
    }
    override fun write(records: List<ClosedTabRecord>) {
        require(records.size <= ClosedTabsStore.MAX_RECORDS)
        val json = JSONArray().apply { records.forEach { record ->
            ClosedTabsStore.validateRecord(record)
            put(JSONObject().put("sessionId", record.sessionId).put("windowId", record.windowId)
                .put("index", record.index).put("closedAt", record.closedAt)
                .put("snapshot", ClosedTabSnapshotCodec.encode(record.snapshot)))
        } }.toString().toByteArray(Charsets.UTF_8)
        require(json.size <= MAX_BYTES) { "Closed-tab snapshots exceed their storage budget" }
        val output = file.startWrite()
        try { output.write(json); file.finishWrite(output) }
        catch (error: Throwable) { file.failWrite(output); throw error }
    }
    companion object { private const val MAX_BYTES = 8 * 1024 * 1024 }
}

/** Claimed records are consumed only after a real Runtime creation; failed creation leaves them restorable. */
internal class ClosedTabsStore(
    private val persistence: ClosedTabsPersistence,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "NavisClosedTabs").apply { isDaemon = true }
    },
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : AutoCloseable {
    constructor(context: Context) : this(AtomicClosedTabsPersistence(profileClosedTabsFile(context)))

    private val closed = AtomicBoolean(false)
    private var records: List<ClosedTabRecord>? = null
    private val claims = mutableMapOf<String, String>()
    private val observers = CopyOnWriteArraySet<() -> Unit>()
    fun observe(observer: () -> Unit) { observers.add(observer) }
    fun removeObserver(observer: () -> Unit) { observers.remove(observer) }

    fun recordClosed(windowId: Long, index: Int, mode: SessionMode, snapshot: PersistedSession,
        isWindowClosing: Boolean = false): CompletionStage<ClosedTabRecord?> {
        if (mode != SessionMode.NORMAL || isWindowClosing || !snapshot.uri.matches(Regex("(?i)^https?://.+")) ||
            EnginePageHistory.parse(snapshot.engineState) == null) return CompletableFuture.completedFuture(null)
        return submit {
            val captured = ClosedTabSnapshotCodec.decode(ClosedTabSnapshotCodec.encode(snapshot))
            val record = ClosedTabRecord(newId(), windowId, index.coerceAtLeast(0), now(), captured, liveWindowId = windowId)
            validateRecord(record)
            val next = (current() + record).sortedWith(order)
            val excess = next.size - MAX_RECORDS
            val removed = if (excess > 0) next.asReversed().filter { it.sessionId != record.sessionId && it.sessionId !in claims }
                .take(excess).mapTo(mutableSetOf()) { it.sessionId } else emptySet()
            check(removed.size >= excess) { "Recently closed tabs are busy restoring" }
            save(next.filterNot { it.sessionId in removed })
            record
        }
    }

    fun list(limit: Int = MAX_RECORDS): CompletionStage<List<ClosedTabRecord>> = submit {
        require(limit in 0..MAX_RECORDS)
        current().sortedWith(order).take(limit)
    }

    fun forget(windowId: Long, sessionId: String): CompletionStage<Unit> = submit {
        val record = current().firstOrNull { it.sessionId == sessionId && it.windowId == windowId }
            ?: error("Closed tab does not exist in that window")
        check(record.sessionId !in claims) { "Closed tab is restoring" }
        save(current().filterNot { it.sessionId == sessionId })
    }

    fun claim(sessionId: String?): CompletionStage<ClosedTabClaim> = submit {
        val record = if (sessionId == null) current().sortedWith(order).firstOrNull { it.sessionId !in claims }
            else current().firstOrNull { it.sessionId == sessionId }
        checkNotNull(record) { "Closed tab does not exist" }
        check(record.sessionId !in claims) { "Closed tab is already restoring" }
        ClosedTabClaim(record, newId()).also { claims[record.sessionId] = it.token }
    }

    fun consume(claim: ClosedTabClaim): CompletionStage<Unit> = submit {
        check(claims[claim.record.sessionId] == claim.token) { "Restore claim is stale" }
        check(current().any { it.sessionId == claim.record.sessionId })
        save(current().filterNot { it.sessionId == claim.record.sessionId })
        claims.remove(claim.record.sessionId)
    }
    fun release(claim: ClosedTabClaim): CompletionStage<Unit> = submit {
        if (claims[claim.record.sessionId] == claim.token) claims.remove(claim.record.sessionId)
    }

    private fun current(): List<ClosedTabRecord> = records ?: persistence.read()
        .map { it.copy(liveWindowId = null) }.also { records = it }
    private fun save(next: List<ClosedTabRecord>) {
        persistence.write(next)
        records = next
        observers.forEach { observer -> runCatching(observer) }
    }
    private val admissionLock = Any()
    private fun <T> submit(operation: () -> T): CompletionStage<T> = synchronized(admissionLock) {
        val result = CompletableFuture<T>()
        if (closed.get()) return result.also { it.completeExceptionally(IllegalStateException("Closed-tabs store is closed")) }
        try { executor.execute { try { result.complete(operation()) } catch (error: Throwable) { result.completeExceptionally(error) } } }
        catch (error: Throwable) { result.completeExceptionally(error) }
        return result
    }
    internal val closedCompletion = CompletableFuture<Unit>()

    override fun close(): Unit = synchronized(admissionLock) {
        if (!closed.compareAndSet(false, true)) return
        observers.clear()
        executor.execute { closedCompletion.complete(Unit) }
        executor.shutdown()
    }
    companion object {
        /** Keep the process's frozen profile even while preparing a profile switch. */
        fun profileClosedTabsFile(context: Context): File {
            val root = context.filesDir
            val application = context.applicationContext as? org.navis.browser.NavisApplication
            val directory = if (application != null) checkNotNull(application.profileScope).directory
                else UserProfileScope.resolve(root).directory
            directory?.mkdirs()
            return File(directory ?: root, "closed-tabs.json")
        }
        const val MAX_RECORDS = 25
        private val order = compareByDescending<ClosedTabRecord> { it.closedAt }.thenByDescending { it.sessionId }
        fun validateRecord(record: ClosedTabRecord) {
            require(record.sessionId.matches(Regex("[A-Za-z0-9-]{1,128}")))
            require(record.windowId > 0 && record.index >= 0 && record.closedAt >= 0)
            ClosedTabSnapshotCodec.encode(record.snapshot)
        }
    }
}
