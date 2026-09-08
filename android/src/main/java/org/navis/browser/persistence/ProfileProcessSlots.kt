/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.persistence

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/** Assigns independent browser processes to profiles without changing a running process's identity. */
internal object ProfileProcessSlots {
    private const val SLOT_COUNT = 8
    private const val RESERVATION_MS = 30_000L
    private const val HEADER = "navis-profile-process-slots-v1"
    private val monitors = ConcurrentHashMap<String, Any>()
    private val processSlots = ConcurrentHashMap<String, BoundSlot>()

    /** Returns an existing window's slot, or reserves an idle slot for the next Activity launch. */
    fun reserve(filesRoot: File, profileId: String): Int = gated(filesRoot) { root ->
        require(UserProfileStore.UUID_V4.matches(profileId)) { "Profile identifiers must be UUIDv4" }
        val entries = read(root)
        // Lock ordering throughout this object is slot gate, then registry.
        check(UserProfileStore.fileBacked(root).snapshot().profiles.any { it.id == profileId }) {
            "Unknown profile"
        }
        val now = System.currentTimeMillis()
        val existing = entries.indexOfFirst { it.profileId == profileId }
        if (existing >= 0) {
            // Also renew a live process's reservation: a prepared restart must
            // keep this assignment during the gap between old and new OS leases.
            write(root, entries.toMutableList().also {
                it[existing] = Entry(profileId, now + RESERVATION_MS)
            })
            return@gated existing
        }
        val available = entries.indices.firstOrNull { slot ->
            val entry = entries[slot]
            (entry.profileId == null || entry.reservedUntilMs <= now) && !isLive(root, slot)
        } ?: error("All browser profile windows are in use")
        write(root, entries.toMutableList().also {
            it[available] = Entry(profileId, now + RESERVATION_MS)
        })
        available
    }

    fun profileId(filesRoot: File, slot: Int): String = gated(filesRoot) { root ->
        requireSlot(slot)
        read(root)[slot].profileId ?: error("Browser process slot is not assigned")
    }

    /** Called once during Application attachment, before Gecko or any profile data is opened. */
    fun bind(filesRoot: File, slot: Int): UserProfileScope.ResolvedProfile = gated(filesRoot) { root ->
        requireSlot(slot)
        processSlots[root.path]?.let { bound ->
            check(bound.slot == slot) { "A process cannot change browser slots" }
            return@gated bound.profile
        }
        val lease = tryLease(root, slot) ?: error("Browser process slot is already running")
        try {
            val id = read(root)[slot].profileId ?: error("Browser process slot is not assigned")
            val profile = UserProfileScope.resolve(root, startProcess = true, profileId = id)
            processSlots[root.path] = BoundSlot(slot, profile, lease)
            profile
        } catch (error: Throwable) {
            lease.close()
            throw error
        }
    }

    private data class Entry(val profileId: String? = null, val reservedUntilMs: Long = 0)
    private data class BoundSlot(
        val slot: Int,
        val profile: UserProfileScope.ResolvedProfile,
        // Retain both the FileLock and its channel for the lifetime of the browser process.
        val lease: Closeable,
    )

    private fun requireSlot(slot: Int) = require(slot in 0 until SLOT_COUNT) { "Invalid browser slot" }

    private fun <T> gated(filesRoot: File, action: (File) -> T): T {
        val root = filesRoot.canonicalFile
        val monitor = monitors.getOrPut(root.path) { Any() }
        return synchronized(monitor) {
            check(root.isDirectory || root.mkdirs() || root.isDirectory) { "Cannot create browser slot directory" }
            RandomAccessFile(File(root, "profile-process-slots.lock"), "rw").use { file ->
                file.channel.lock().use { action(root) }
            }
        }
    }

    private fun isLive(root: File, slot: Int): Boolean {
        val lease = tryLease(root, slot) ?: return true
        lease.close()
        return false
    }

    private fun tryLease(root: File, slot: Int): Closeable? {
        // Opening and closing another descriptor for a locally held POSIX lock
        // can release the original lock; check our retained lease first.
        if (processSlots[root.path]?.slot == slot) return null
        val directory = File(root, "profile-process-slot-leases")
        check(directory.isDirectory || directory.mkdirs() || directory.isDirectory) { "Cannot create browser slot locks" }
        val channel = RandomAccessFile(File(directory, "$slot.lock"), "rw").channel
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
        return Lease(channel, lock)
    }

    private class Lease(private val channel: FileChannel, private val lock: FileLock) : Closeable {
        override fun close() {
            try { lock.release() } finally { channel.close() }
        }
    }

    private fun read(root: File): List<Entry> {
        val file = File(root, "profile-process-slots.tsv")
        if (!file.exists()) return List(SLOT_COUNT) { Entry() }
        val lines = file.readText().removeSuffix("\n").split('\n')
        check(lines.size == SLOT_COUNT + 1 && lines.first() == HEADER) {
            "Invalid browser slot mapping"
        }
        val seen = mutableSetOf<String>()
        return (0 until SLOT_COUNT).map { slot ->
            val fields = lines[slot + 1].split('\t')
            check(fields.size == 3 && fields[0] == slot.toString()) { "Invalid browser slot mapping" }
            val id = fields[1].takeUnless { it == "-" }
            val expiry = fields[2].toLongOrNull()
            check(expiry != null && expiry >= 0) { "Invalid browser slot reservation" }
            if (id == null) {
                check(expiry == 0L) { "Unassigned browser slot has a reservation" }
            } else {
                check(UserProfileStore.UUID_V4.matches(id) && seen.add(id)) {
                    "Invalid or duplicate browser slot profile"
                }
            }
            Entry(id, expiry)
        }
    }

    private fun write(root: File, entries: List<Entry>) {
        val body = buildString {
            append(HEADER).append('\n')
            entries.forEachIndexed { slot, entry ->
                append(slot).append('\t').append(entry.profileId ?: "-")
                    .append('\t').append(entry.reservedUntilMs).append('\n')
            }
        }
        val staging = File(root, "profile-process-slots.tsv.tmp")
        staging.outputStream().use { output ->
            output.write(body.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        Files.move(staging.toPath(), File(root, "profile-process-slots.tsv").toPath(),
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}
