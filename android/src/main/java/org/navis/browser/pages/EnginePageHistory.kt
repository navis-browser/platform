/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.pages

import org.json.JSONObject
import org.navis.browser.persistence.EngineSessionStatePolicy

/** Identities belong to real Gecko SHEntries, never to URL guesses or product routes. */
internal data class EnginePageEntry(val id: Long, val uri: String, val identity: String = id.toString())
internal data class EnginePageHistory(val entries: List<EnginePageEntry>, val index: Int) {
    val current: EnginePageEntry? get() = entries.getOrNull(index)

    companion object {
        const val MAX_ENTRIES = 512
        fun parse(serialized: String?, bindingIdentities: List<String>? = null): EnginePageHistory? = runCatching {
            if (serialized == null || !EngineSessionStatePolicy.accepts(serialized)) return null
            val root = JSONObject(serialized)
            if (root.optInt("version") != 1) return null
            val history = root.getJSONObject("history")
            val values = history.getJSONArray("entries")
            if (values.length() !in 1..MAX_ENTRIES) return null
            val index = history.getInt("index") - 1
            if (index !in 0 until values.length()) return null
            if (bindingIdentities != null && bindingIdentities.size != values.length()) return null
            val entries = (0 until values.length()).map {
                val entry = values.getJSONObject(it)
                val id = entry.getLong("ID")
                val uri = entry.getString("url")
                if (id <= 0 || uri.isBlank() || uri.length > 65536 || uri.any(Char::isISOControl) ||
                    AndroidInternalPages.resolve(uri) != null) return null
                val identity = bindingIdentities?.get(it) ?: identity(entry)
                if (identity.isEmpty() || identity.length > 65536) return null
                EnginePageEntry(id, uri, identity)
            }
            if (entries.map { it.identity }.distinct().size != entries.size) return null
            EnginePageHistory(entries, index)
        }.getOrNull()

        // Gecko clones the top-level ID when a child frame navigates. Include the
        // real child-entry tree and Navigation keys rather than assuming ID uniqueness.
        private fun identity(entry: JSONObject, depth: Int = 0): String {
            require(depth <= 32)
            val children = entry.optJSONArray("children")
            val childKeys = if (children == null) "" else (0 until children.length()).joinToString(",", "[", "]") {
                identity(children.getJSONObject(it), depth + 1)
            }
            return entry.getLong("ID").toString() + entry.optString("navigationKey") + childKeys
        }
    }
}
