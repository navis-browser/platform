/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.persistence

import java.io.File

/**
 * Binds Navis-owned data to one user profile.
 *
 * The first profile keeps the legacy root locations so migration never moves
 * existing user data. Every other profile owns `profiles/<uuid>/` for databases
 * and files, plus suffixed preference names that live next to the legacy ones
 * and are removed together with the profile. Default selection never changes
 * storage ownership.
 */
internal object UserProfileScope {
    /** Preference files that hold per-profile product state. */
    val PROFILE_PREFERENCES = setOf(
        "navis.settings.v1",
        "navis_session_restore",
        "navis.site-permissions.v1",
        "navis.site-permissions.v2",
        "navis_developer_settings",
        "navis-extension-downloads",
        "navis-download-ids",
    )

    /** Databases owned by Navis product code. */
    val PROFILE_DATABASES = setOf(
        "navis_profile.sqlite",
    )

    fun resolve(filesRoot: File, startProcess: Boolean = false, profileId: String? = null): ResolvedProfile {
        val store = UserProfileStore.fileBacked(filesRoot)
        store.ensureProfile()
        if (startProcess) store.beginProcess(profileId)
        val snapshot = store.snapshot()
        val selectedId = if (startProcess) snapshot.currentId else profileId ?: snapshot.currentId
        check(snapshot.profiles.any { it.id == selectedId }) { "Unknown profile" }
        return ResolvedProfile(
            currentId = selectedId,
            defaultId = snapshot.defaultId,
            legacyId = snapshot.legacyId,
            directory = if (selectedId == snapshot.legacyId) null
            else File(filesRoot, "profiles/$selectedId"),
        )
    }

    fun scopedPreferenceName(name: String, currentId: String, legacyId: String): String {
        if (currentId == legacyId || name !in PROFILE_PREFERENCES) return name
        return "$name.$currentId"
    }

    fun closedTabsFile(filesRoot: File, directory: File?): File =
        File(directory ?: filesRoot, "closed-tabs.json")

    data class ResolvedProfile(
        val currentId: String,
        val defaultId: String,
        val legacyId: String,
        /** Null for the legacy root; otherwise the profile data directory. */
        val directory: File?,
    )
}

/** Called only while the registry and inactive-profile locks are held by remove(). */
internal fun deleteScopedProfileData(filesRoot: File, id: String, legacy: Boolean) {
    require(UserProfileStore.UUID_V4.matches(id))
    if (legacy) {
        for (name in listOf("mozilla", "closed-tabs.json", "closed-tabs.json.bak")) {
            File(filesRoot, name).deleteRecursively()
        }
        for (name in UserProfileScope.PROFILE_DATABASES) {
            for (suffix in listOf("", "-wal", "-shm", "-journal")) {
                File(filesRoot, "../databases/$name$suffix").delete()
            }
        }
    } else {
        File(filesRoot, "profiles/$id").deleteRecursively()
    }
    val prefs = File(filesRoot, "../shared_prefs").normalize()
    for (name in UserProfileScope.PROFILE_PREFERENCES) {
        val scoped = if (legacy) name else "$name.$id"
        File(prefs, "$scoped.xml").delete()
        File(prefs, "$scoped.xml.bak").delete()
    }
}
