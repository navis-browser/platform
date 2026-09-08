/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.persistence

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.navis.browser.pages.NativePageHistory
import org.navis.browser.pages.NativePageHistorySnapshot
import org.navis.browser.pages.EnginePageHistory

internal data class PersistedSession(
    val uri: String,
    val title: String,
    val engineState: String?,
    val active: Boolean,
    val nativeNewTab: Boolean = false,
    val nativeHistory: NativePageHistorySnapshot? = null,
    val windowKey: String = LEGACY_WINDOW_KEY,
)

internal const val LEGACY_WINDOW_KEY = "main"

internal class SessionPersistence(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun read(): List<PersistedSession> {
        val encoded = preferences.getString(KEY_SESSIONS, null) ?: return emptyList()
        if (encoded.length > MAX_SERIALIZED_BYTES) {
            clear()
            return emptyList()
        }
        return runCatching {
            val array = JSONArray(encoded)
            buildList {
                repeat(minOf(array.length(), MAX_SESSIONS)) { index ->
                    val item = array.getJSONObject(index)
                    val uri = item.optString(KEY_URI).takeIf(::validUri) ?: return@repeat
                    val engineState = item.optString(KEY_LEGACY_ENGINE_STATE).takeIf {
                        EngineSessionStatePolicy.accepts(it)
                    }
                    add(
                        PersistedSession(
                            uri = uri,
                            title = item.optString(KEY_TITLE).take(MAX_TITLE_LENGTH),
                            engineState = engineState,
                            active = item.optBoolean(KEY_ACTIVE, false),
                            nativeNewTab = item.optBoolean(KEY_NATIVE_NEW_TAB, false),
                            nativeHistory = readNativeHistory(item.optJSONObject(KEY_NATIVE_HISTORY), uri, engineState),
                            windowKey = item.optString("windowKey", LEGACY_WINDOW_KEY)
                                .takeIf { it.isNotBlank() && it.length <= 128 && !it.any(Char::isISOControl) }
                                ?: LEGACY_WINDOW_KEY,
                        ),
                    )
                }
            }
        }.getOrElse {
            clear()
            emptyList()
        }
    }

    fun write(sessions: List<PersistedSession>) {
        val encoded = encode(sessions) ?: return
        preferences.edit().putString(KEY_SESSIONS, encoded).apply()
    }

    /** Strict, durable snapshot for process relaunch. Call off the UI thread with sampled values. */
    fun writeAndCommit(sessions: List<PersistedSession>) {
        require(sessions.size <= MAX_SESSIONS && sessions.all { validUri(it.uri) }) {
            "The complete session snapshot cannot be saved"
        }
        val encoded = checkNotNull(encode(sessions)) { "Session state exceeds its storage limit" }
        check(preferences.edit().putString(KEY_SESSIONS, encoded).commit()) {
            "Session state could not be saved"
        }
    }

    private fun encode(sessions: List<PersistedSession>): String? {
        val array = JSONArray()
        sessions.take(MAX_SESSIONS).forEach { session ->
            if (!validUri(session.uri)) {
                return@forEach
            }
            array.put(
                JSONObject()
                    .put(KEY_URI, session.uri)
                    .put(KEY_TITLE, session.title.take(MAX_TITLE_LENGTH))
                    .put(KEY_ACTIVE, session.active)
                    .put(KEY_NATIVE_NEW_TAB, session.nativeNewTab)
                    .put("windowKey", session.windowKey)
                    .apply {
                        NativePageHistory.validated(session.nativeHistory, session.uri)?.takeIf {
                            it.engine == null || NativePageHistory.boundTo(it, session.engineState)
                        }?.let { history ->
                            put(KEY_NATIVE_HISTORY, JSONObject()
                                .put("version", 2)
                                .put("entries", JSONArray(history.entries))
                                .put("index", history.index)
                                .put("engineSlots", JSONArray(history.engineSlots))
                                .put("suppressedSlots", JSONArray(history.suppressedSlots))
                                .apply {
                                    history.engine?.let { engine ->
                                        put("engineIdentities", JSONArray(engine.entries.map { it.identity }))
                                        put("engine", JSONObject().put("version", 1).put("history", JSONObject()
                                            .put("index", engine.index + 1)
                                            .put("entries", JSONArray().apply {
                                                engine.entries.forEach { entry -> put(JSONObject()
                                                    .put("ID", entry.id).put("url", entry.uri)) }
                                            })))
                                    }
                                })
                        }
                        session.engineState
                            ?.takeIf(EngineSessionStatePolicy::accepts)
                            ?.let { put(KEY_LEGACY_ENGINE_STATE, it) }
                    },
            )
        }
        val encoded = array.toString()
        return encoded.takeIf { it.length <= MAX_SERIALIZED_BYTES }
    }

    fun clear() {
        preferences.edit().remove(KEY_SESSIONS).apply()
    }

    private fun readNativeHistory(value: JSONObject?, selectedUri: String, engineState: String?): NativePageHistorySnapshot? {
        val values = value?.optJSONArray("entries") ?: return null
        if (values.length() !in 1..(NativePageHistory.MAX_ENTRIES + EnginePageHistory.MAX_ENTRIES)) return null
        val entries = (0 until values.length()).map { values.optString(it) }
        val slots = value.optJSONArray("engineSlots")?.let { array ->
            if (array.length() != entries.size) return null
            (0 until array.length()).map { array.optInt(it, -2) }
        } ?: List(entries.size) { -1 }
        val identities = value.optJSONArray("engineIdentities")?.let { array ->
            if (array.length() !in 1..EnginePageHistory.MAX_ENTRIES) return null
            (0 until array.length()).map { array.optString(it) }
        }
        val engine = EnginePageHistory.parse(value.optJSONObject("engine")?.toString(), identities)
        val suppressed = value.optJSONArray("suppressedSlots")?.let { array ->
            if (array.length() > EnginePageHistory.MAX_ENTRIES) return null
            (0 until array.length()).map { array.optInt(it, -1) }
        } ?: emptyList()
        val history = NativePageHistory.validated(
            NativePageHistorySnapshot(entries, value.optInt("index", -1), engine, slots, suppressed), selectedUri) ?: return null
        return history.takeIf { it.engine == null || NativePageHistory.boundTo(it, engineState) }
    }

    private fun validUri(uri: String): Boolean =
        uri.isNotBlank() && uri.length <= MAX_URI_LENGTH && !uri.any(Char::isISOControl)

    private companion object {
        const val PREFERENCES_NAME = "navis_session_restore"
        const val KEY_SESSIONS = "normal_sessions"
        const val KEY_URI = "uri"
        const val KEY_TITLE = "title"
        // Preserve the existing on-disk key while product code uses engine-neutral naming.
        const val KEY_LEGACY_ENGINE_STATE = "gecko_state"
        const val KEY_ACTIVE = "active"
        const val KEY_NATIVE_NEW_TAB = "native_new_tab"
        const val KEY_NATIVE_HISTORY = "native_history"
        const val MAX_SESSIONS = 100
        const val MAX_URI_LENGTH = 16_384
        const val MAX_TITLE_LENGTH = 4_096
        const val MAX_SERIALIZED_BYTES = 4_000_000
    }
}
