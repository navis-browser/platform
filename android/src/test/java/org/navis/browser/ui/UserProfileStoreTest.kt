/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.ui

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.navis.browser.persistence.UserProfileStore

class UserProfileStoreTest {
    private class Memory {
        var body: String? = null
        val deleted = mutableListOf<String>()
        var now = 1_700_000_000_000L
        fun store() = UserProfileStore(
            now = { now },
            loadDocument = { body },
            saveDocument = { body = it },
            deleteProfileData = { deleted.add(it) },
        )
    }

    @Test
    fun emptyStoreLoadsWithoutProfiles() {
        val memory = Memory()
        val snapshot = memory.store().snapshot()
        assertTrue(snapshot.profiles.isEmpty())
    }

    @Test
    fun createdProfilesCarryUuidV4Identities() {
        val memory = Memory()
        val store = memory.store()
        val first = store.create("Ada", "#0B57D0")
        val second = store.create("Grace", "#ff0000")
        assertTrue(UserProfileStore.UUID_V4.matches(first.id))
        assertTrue(UserProfileStore.UUID_V4.matches(second.id))
        assertNotEquals(first.id, second.id)
        assertEquals("Ada", first.userName)
        assertEquals("#0b57d0", first.accent)
    }

    @Test
    fun rejectsBlankNamesAndMalformedAccents() {
        val memory = Memory()
        val store = memory.store()
        try { store.create("   ", "#0b57d0"); fail() } catch (_: IllegalArgumentException) {}
        try { store.create("Ada", "blue"); fail() } catch (_: IllegalArgumentException) {}
        try { store.create("Ada", "#0b57d"); fail() } catch (_: IllegalArgumentException) {}
    }

    @Test
    fun rejectsNonUuidIdentifiers() {
        val memory = Memory()
        val store = memory.store()
        val profile = store.create("Ada", "#0b57d0")
        try { store.switchTo("not-a-uuid"); fail() } catch (_: IllegalArgumentException) {}
        try { store.setDefault(profile.id.replaceFirst('4', '5')); fail() } catch (_: IllegalArgumentException) {}
    }

    @Test
    fun removalProtectsCurrentDefaultAndLastProfile() {
        val memory = Memory()
        val store = memory.store()
        val first = store.create("Ada", "#0b57d0")
        try { store.remove(first.id, false); fail() } catch (_: IllegalStateException) {}
        val second = store.create("Grace", "#00ff00")
        store.switchTo(first.id)
        store.setDefault(second.id)
        try { store.remove(first.id, false); fail() } catch (_: IllegalStateException) {}
        try { store.remove(second.id, false); fail() } catch (_: IllegalStateException) {}
        store.switchTo(first.id)
        store.setDefault(first.id)
        store.remove(second.id, true)
        assertEquals(listOf(first.id), store.snapshot().profiles.map { it.id })
        assertEquals(listOf(second.id), memory.deleted)
    }

    @Test
    fun defaultProfileKeepsLegacyRootWhileOthersSeparate() {
        val memory = Memory()
        val store = memory.store()
        val first = store.create("Ada", "#0b57d0")
        val second = store.create("Grace", "#00ff00")
        store.setDefault(first.id)
        assertEquals("", store.dataDirectory(first.id))
        assertEquals("profiles/${second.id}", store.dataDirectory(second.id))
    }

    @Test
    fun snapshotRoundTripsThroughTheDocument() {
        val memory = Memory()
        val store = memory.store()
        val first = store.create("Ada \"The\" Enchantress", "#0b57d0")
        store.setDefault(first.id)
        val reloaded = memory.store().snapshot()
        assertEquals(1, reloaded.profiles.size)
        assertEquals(first, reloaded.profiles.single())
        assertEquals(first.id, reloaded.defaultId)
    }

    @Test
    fun fileBackedStoreDeletesProfileDirectories() {
        val root = Files.createTempDirectory("navis-user-profiles-").toFile()
        try {
            val store = UserProfileStore.fileBacked(root)
            val first = store.create("Ada", "#0b57d0")
            val second = store.create("Grace", "#00ff00")
            store.switchTo(first.id)
            store.setDefault(first.id)
            val data = java.io.File(root, "profiles/${second.id}")
            check(data.mkdirs())
            check(java.io.File(data, "sentinel").writeText("x") != Unit || true)
            store.remove(second.id, true)
            assertTrue(!data.exists())
        } finally {
            root.deleteRecursively()
        }
    }
}
