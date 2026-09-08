/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.persistence

import java.io.File
import java.io.Closeable
import java.io.RandomAccessFile
import java.util.Locale
import java.util.UUID
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/** One editable user identity bound to one browser profile. */
internal data class UserProfile(
    val id: String,
    val userName: String,
    val accent: String,
    val createdAtMs: Long,
)

internal data class UserProfileSnapshot(
    val profiles: List<UserProfile>,
    val currentId: String,
    val defaultId: String,
    val legacyId: String = "",
    val launchPending: Boolean = false,
    /** Live browser processes, not a persisted hint. */
    val inUseIds: Set<String> = emptySet(),
)

/**
 * User-identity registry with real data separation.
 *
 * Identities live in a small bootstrap document outside any profile directory.
 * Each new profile owns `profiles/<uuid>/` for its separated data;
 * the first profile keeps the legacy root directory so existing user data
 * is never moved or renamed by migration. Profile identifiers must be UUIDv4,
 * matching the desktop naming rule.
 */
internal class UserProfileStore(
    private val now: () -> Long = System::currentTimeMillis,
    private val loadDocument: () -> String?,
    private val saveDocument: (String) -> Unit,
    private val deleteProfileData: (String, Boolean) -> Unit = { _, _ -> },
    private val filesRoot: File? = null,
) {
    private var snapshot: UserProfileSnapshot = load()
    private var processProfileId: String? = null

    fun snapshot(): UserProfileSnapshot = transaction {
        snapshot.copy(
            profiles = snapshot.profiles.toList(),
            currentId = currentId(),
            inUseIds = snapshot.profiles.mapNotNull { profile ->
                profile.id.takeIf { isInUse(it) }
            }.toSet(),
        )
    }

    fun create(userName: String, accent: String): UserProfile = transaction {
        createLocked(userName, accent)
    }

    /** Migration/bootstrap is one transaction, including simultaneous first launches. */
    fun ensureProfile(): UserProfile = transaction {
        snapshot.profiles.firstOrNull() ?: createLocked("Navis", "#0b57d0")
    }

    private fun createLocked(userName: String, accent: String): UserProfile {
        val record = UserProfile(
            id = UUID.randomUUID().toString().lowercase(Locale.ROOT),
            userName = sanitizedName(userName),
            accent = normalizedAccent(accent),
            createdAtMs = now(),
        )
        check(UUID_V4.matches(record.id)) { "Profile identifiers must be UUIDv4" }
        commit(snapshot.copy(
            profiles = snapshot.profiles + record,
            currentId = snapshot.currentId.ifEmpty { record.id },
            defaultId = snapshot.defaultId.ifEmpty { record.id },
            legacyId = if (snapshot.profiles.isEmpty()) record.id else snapshot.legacyId,
        ))
        return record
    }

    fun updateIdentity(id: String, userName: String, accent: String): UserProfile = transaction {
        val index = indexOf(id)
        val updated = snapshot.profiles[index].copy(
            userName = sanitizedName(userName),
            accent = normalizedAccent(accent),
        )
        commit(snapshot.copy(profiles = snapshot.profiles.toMutableList().also { it[index] = updated }))
        updated
    }

    fun setDefault(id: String) = transaction {
        indexOf(id)
        commit(snapshot.copy(defaultId = id))
    }

    /** Legacy relaunch handoff; never changes the identity of a running process. */
    fun switchTo(id: String): UserProfile = transaction {
        val record = snapshot.profiles[indexOf(id)]
        commit(snapshot.copy(currentId = id, launchPending = true))
        record
    }

    /** Bind before opening Gecko or product data, retaining the OS lock until process exit. */
    fun beginProcess(requestedId: String? = null): UserProfile = transaction {
        val existing = processProfileId ?: filesRoot?.let { PROCESS_PROFILES[it.path]?.id }
        if (existing != null) {
            check(requestedId == null || requestedId == existing) { "A process cannot change profile" }
            return@transaction snapshot.profiles[indexOf(existing)]
        }
        val selected = requestedId ?: if (snapshot.launchPending) snapshot.currentId else snapshot.defaultId
        val record = snapshot.profiles[indexOf(selected)]
        val lease = acquireUseLock(selected)
        try {
            // An explicit multi-window launch does not consume a different relaunch handoff.
            if (requestedId == null || (snapshot.launchPending && snapshot.currentId == selected)) {
                commit(snapshot.copy(currentId = selected, launchPending = false))
            } else {
                // Persist legacy identity migration even for explicit launches.
                commit(snapshot)
            }
            filesRoot?.let { PROCESS_PROFILES[it.path] = ProcessProfile(selected, lease) }
            processProfileId = selected
        } catch (error: Throwable) {
            lease.close()
            throw error
        }
        record
    }

    /** The active profile remains locked until the browser process exits. */
    fun remove(id: String, deleteFiles: Boolean) = transaction {
        indexOf(id)
        check(snapshot.profiles.size > 1) { "Cannot remove the last profile" }
        check(id != currentId()) { "Cannot remove the current profile" }
        // Keep the lock through document commit AND data deletion, so another process
        // cannot start Gecko between the in-use check and removal.
        acquireUseLock(id).use {
            val remaining = snapshot.profiles.filterNot { it.id == id }
            val replacement = currentId().takeIf { current -> remaining.any { it.id == current } }
                ?: remaining.first().id
            val legacy = id == snapshot.legacyId
            commit(snapshot.copy(
                profiles = remaining,
                currentId = if (snapshot.currentId == id) replacement else snapshot.currentId,
                defaultId = if (id == snapshot.defaultId) replacement else snapshot.defaultId,
                launchPending = snapshot.launchPending && snapshot.currentId != id,
            ))
            if (deleteFiles) deleteProfileData(id, legacy)
        }
    }

    /** Profile data directory relative to the app files root, or "" for the legacy root. */
    fun dataDirectory(id: String): String = transaction {
        indexOf(id)
        if (id == snapshot.legacyId) "" else "profiles/$id"
    }

    private fun currentId(): String = processProfileId
        ?: filesRoot?.let { PROCESS_PROFILES[it.path]?.id }
        ?: snapshot.currentId

    /** Reload under the same lock as writes; stale UI instances cannot overwrite peers. */
    private fun <T> transaction(action: () -> T): T {
        val root = filesRoot
        if (root == null) return synchronized(this) {
            snapshot = load()
            action()
        }
        val monitor = REGISTRY_MONITORS.getOrPut(root.path) { Any() }
        return synchronized(monitor) {
            check(root.isDirectory || root.mkdirs() || root.isDirectory) { "Cannot create profile registry directory" }
            RandomAccessFile(File(root, "user-profiles.lock"), "rw").use { file ->
                file.channel.lock().use {
                    snapshot = load()
                    action()
                }
            }
        }
    }

    private fun isInUse(id: String): Boolean {
        if (filesRoot == null) return id == processProfileId
        val lease = tryUseLock(id) ?: return true
        lease.close()
        return false
    }

    private fun acquireUseLock(id: String): Closeable =
        tryUseLock(id) ?: error("Profile is in use by another browser window")

    private fun tryUseLock(id: String): Closeable? {
        if (filesRoot == null) return Closeable { }
        // Do not even open a second channel for our retained lock: on POSIX,
        // closing another descriptor can release this process's existing lock.
        if (PROCESS_PROFILES[filesRoot.path]?.id == id) return null
        val directory = File(filesRoot, "profile-locks")
        check(directory.isDirectory || directory.mkdirs() || directory.isDirectory) { "Cannot create profile lock directory" }
        val channel = RandomAccessFile(File(directory, "$id.lock"), "rw").channel
        val lock = try {
            channel.tryLock()
        } catch (_: OverlappingFileLockException) {
            null
        } catch (error: Throwable) {
            channel.close()
            throw error
        }
        if (lock == null) {
            channel.close()
            return null
        }
        return ProfileLease(channel, lock)
    }

    private fun indexOf(id: String): Int {
        if (!UUID_V4.matches(id)) throw IllegalArgumentException("Profile identifiers must be UUIDv4")
        return snapshot.profiles.indexOfFirst { it.id == id }
            .takeIf { it >= 0 } ?: throw NoSuchElementException("Unknown profile: $id")
    }

    private fun load(): UserProfileSnapshot {
        val raw = loadDocument() ?: return UserProfileSnapshot(emptyList(), "", "")
        val profiles = mutableListOf<UserProfile>()
        var currentId = ""
        var defaultId = ""
        var legacyId = ""
        var launchPending = false
        for (maybeEntry in splitTopLevel(raw)) {
            val entry = maybeEntry ?: continue
            val (key, value) = entry
            when (key) {
                "launchPending" -> launchPending = value == "true"
                "currentId", "defaultId", "legacyId" -> if (value.startsWith("\"")) {
                    val parsed = unquote(value)
                    when (key) {
                        "currentId" -> currentId = parsed
                        "defaultId" -> defaultId = parsed
                        "legacyId" -> legacyId = parsed
                    }
                }
                "profiles" -> if (value.startsWith("[")) {
                    for (item in splitArray(value)) {
                        val fields = mutableMapOf<String, String>()
                        for (maybePair in splitTopLevel(item)) {
                            val pair = maybePair ?: continue
                            fields[pair.first] = pair.second
                        }
                        val id = fields["id"]?.let(::unquoteOrNull) ?: continue
                        if (!UUID_V4.matches(id)) continue
                        profiles.add(UserProfile(
                            id = id,
                            userName = fields["userName"]?.let(::unquoteOrNull).orEmpty(),
                            accent = fields["accent"]?.let(::unquoteOrNull) ?: "#0b57d0",
                            createdAtMs = fields["createdAtMs"]?.toLongOrNull() ?: 0L,
                        ))
                    }
                }
            }
        }
        if (profiles.isEmpty()) return UserProfileSnapshot(emptyList(), "", "")
        if (profiles.none { it.id == currentId }) currentId = profiles.first().id
        if (profiles.none { it.id == defaultId }) defaultId = profiles.first().id
        // v1 allocated the legacy data to the first identity; changing the
        // default must never transfer that identity's cookies or passwords.
        if (!UUID_V4.matches(legacyId)) {
            legacyId = profiles.first().id
            launchPending = true
        }
        return UserProfileSnapshot(profiles, currentId, defaultId, legacyId, launchPending)
    }

    private fun commit(next: UserProfileSnapshot) {
        val body = buildString {
            append("{\"version\":2,")
            append("\"currentId\":${quote(next.currentId)},")
            append("\"defaultId\":${quote(next.defaultId)},")
            append("\"legacyId\":${quote(next.legacyId)},")
            append("\"launchPending\":${next.launchPending},")
            append("\"profiles\":[")
            next.profiles.forEachIndexed { index, profile ->
                if (index > 0) append(",")
                append("{\"id\":${quote(profile.id)},")
                append("\"userName\":${quote(profile.userName)},")
                append("\"accent\":${quote(profile.accent)},")
                append("\"createdAtMs\":${profile.createdAtMs}}")
            }
            append("]}")
        }
        saveDocument(body)
        snapshot = next
    }

    companion object {
        private val REGISTRY_MONITORS = ConcurrentHashMap<String, Any>()
        private val PROCESS_PROFILES = ConcurrentHashMap<String, ProcessProfile>()
        private data class ProcessProfile(val id: String, val lease: Closeable)
        private class ProfileLease(private val channel: FileChannel, private val lock: FileLock) : Closeable {
            override fun close() {
                try { lock.release() } finally { channel.close() }
            }
        }
        val UUID_V4 = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")

        fun sanitizedName(value: String): String {
            val name = value.trim().replace(Regex("\\s+"), " ")
            require(name.isNotEmpty() && name.length <= 80) { "Enter a username of 1-80 characters" }
            return name
        }

        fun normalizedAccent(value: String): String {
            val accent = value.trim().lowercase(Locale.ROOT)
            require(Regex("#[0-9a-f]{6}").matches(accent)) { "Use a #rrggbb color" }
            return accent
        }

        fun fileBacked(filesRoot: File): UserProfileStore {
            val root = filesRoot.canonicalFile
            val document = File(root, "user-profiles.json")
            return UserProfileStore(
                loadDocument = { document.takeIf { it.isFile }?.readText() },
                saveDocument = { body ->
                    root.mkdirs()
                    val staging = File(root, "user-profiles.json.tmp")
                    staging.outputStream().use { output ->
                        output.write(body.toByteArray(Charsets.UTF_8))
                        output.fd.sync()
                    }
                    Files.move(staging.toPath(), document.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                },
                deleteProfileData = { id, legacy -> deleteScopedProfileData(root, id, legacy) },
                filesRoot = root,
            )
        }

        private fun quote(value: String): String = buildString {
            append('"')
            for (char in value) when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char < ' ') append("\\u%04x".format(char.code)) else append(char)
            }
            append('"')
        }

        private fun unquoteOrNull(raw: String): String? {
            if (!raw.startsWith("\"") || !raw.endsWith("\"") || raw.length < 2) return null
            val out = StringBuilder()
            var index = 1
            while (index < raw.length - 1) {
                val char = raw[index]
                if (char == '\\' && index + 1 < raw.length - 1) {
                    when (raw[index + 1]) {
                        '"', '\\', '/' -> { out.append(raw[index + 1]); index += 2; continue }
                        'n' -> { out.append('\n'); index += 2; continue }
                        'r' -> { out.append('\r'); index += 2; continue }
                        't' -> { out.append('\t'); index += 2; continue }
                        'u' -> {
                            val hex = raw.substring(index + 2, (index + 6).coerceAtMost(raw.length - 1))
                            out.append(hex.toIntOrNull(16)?.toChar() ?: '?')
                            index += 6; continue
                        }
                    }
                }
                out.append(char)
                index++
            }
            return out.toString()
        }

        private fun unquote(raw: String): String = unquoteOrNull(raw) ?: ""

        private fun splitTopLevel(raw: String): List<Pair<String, String>?> {
            val body = raw.trim().removePrefix("{").removeSuffix("}").trim()
            return splitItems(body).map { item ->
                val colon = indexOfColon(item) ?: return@map null
                val key = unquoteOrNull(item.substring(0, colon).trim()) ?: return@map null
                key to item.substring(colon + 1).trim()
            }
        }

        private fun splitArray(raw: String): List<String> {
            val body = raw.trim().removePrefix("[").removeSuffix("]").trim()
            if (body.isEmpty()) return emptyList()
            return splitItems(body)
        }

        private fun splitItems(body: String): List<String> {
            val items = mutableListOf<String>()
            var depth = 0
            var inString = false
            var escaped = false
            var start = 0
            for (index in body.indices) {
                val char = body[index]
                if (inString) {
                    if (escaped) escaped = false
                    else if (char == '\\') escaped = true
                    else if (char == '"') inString = false
                } else when (char) {
                    '"' -> inString = true
                    '{', '[' -> depth++
                    '}', ']' -> depth--
                    ',' -> if (depth == 0) {
                        items.add(body.substring(start, index).trim())
                        start = index + 1
                    }
                }
            }
            val tail = body.substring(start).trim()
            if (tail.isNotEmpty()) items.add(tail)
            return items
        }

        private fun indexOfColon(item: String): Int? {
            var inString = false
            var escaped = false
            for (index in item.indices) {
                val char = item[index]
                if (inString) {
                    if (escaped) escaped = false
                    else if (char == '\\') escaped = true
                    else if (char == '"') inString = false
                } else when (char) {
                    '"' -> inString = true
                    ':' -> return index
                }
            }
            return null
        }
    }
}
