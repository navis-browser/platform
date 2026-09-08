/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.persistence

import android.content.Context
import android.content.SharedPreferences

/** The old boolean store cannot distinguish a dismissed prompt from an explicit block. */
internal class AndroidSitePermissionStore(context: Context) : SitePermissionDecisions by
    SitePermissionPolicy(AndroidPermissionStorage(context.applicationContext))

private class AndroidPermissionStorage(context: Context) : SitePermissionStorage {
    private val preferences: SharedPreferences = context.getSharedPreferences(
        "navis.site-permissions.v2", Context.MODE_PRIVATE,
    ).also {
        // Re-prompt instead of migrating ambiguous grants/denials into explicit user intent.
        val legacy = context.getSharedPreferences("navis.site-permissions.v1", Context.MODE_PRIVATE)
        if (legacy.all.isNotEmpty()) check(legacy.edit().clear().commit()) { "Could not reset legacy site permissions" }
    }
    private val values = preferences.all.mapNotNull { (key, value) ->
        (value as? String)?.let { key to it }
    }.toMap().toMutableMap()

    override fun entries(): Map<String, String> = values.toMap()

    override fun put(key: String, value: String) {
        persist(values + (key to value))
    }

    override fun remove(keys: Set<String>) {
        if (keys.isEmpty()) return
        persist(values.filterKeys { it !in keys })
    }

    private fun persist(next: Map<String, String>) {
        // SharedPreferences mutates its own memory even when commit() fails. Rebuild
        // from the last acknowledged snapshot so a later save cannot persist that failed grant.
        val editor = preferences.edit().clear()
        next.forEach { (key, value) -> editor.putString(key, value) }
        check(editor.commit()) { "Could not save site permissions" }
        values.clear()
        values.putAll(next)
    }
}
