/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.engine

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject
import org.json.JSONArray
import org.navis.browser.api.SessionId
import org.navis.browser.engine.runtime.EngineSessionPort
import org.navis.browser.settings.AndroidSettingsHost
import org.navis.browser.settings.AndroidSettingsUpdateResult
import org.navis.browser.settings.AndroidSearchProvider

internal data class AddressSuggestion(val kind: String, val id: String, val title: String, val url: String)

internal data class ProcessInformation(
    val id: String, val title: String, val origin: String, val type: String,
    val memoryBytes: Long, val cpuPercent: Double?, val threadCount: Int,
    val canEnd: Boolean, val token: String,
)

internal data class SiteCertificate(
    val commonName: String,
    val subject: String,
    val organization: String,
    val issuer: String,
    val serial: String,
    val fingerprint: String,
    val validFrom: Long?,
    val validTo: Long?,
    val displayName: String = "",
    val issuerCommonName: String = "",
    val issuerOrganization: String = "",
    val organizationalUnit: String = "",
    val publicKeyDigest: String = "",
) {
    companion object {
        fun fromJson(value: JSONObject): SiteCertificate = SiteCertificate(
            value.optString("commonName"), value.optString("subjectName"),
            value.optString("organization"),
            value.optString("issuerName").ifBlank {
                value.optString("issuerOrganization").ifBlank { value.optString("issuerCommonName") }
            },
            value.optString("serialNumber"), value.optString("sha256Fingerprint"),
            value.optLong("validFrom").takeIf { it > 0 },
            value.optLong("validTo").takeIf { it > 0 },
            value.optString("displayName"), value.optString("issuerCommonName"),
            value.optString("issuerOrganization"), value.optString("organizationalUnit"),
            value.optString("sha256SubjectPublicKeyInfoDigest"),
        )
    }
}

internal data class SiteInformation(
    val kind: String,
    val origin: String,
    val host: String,
    val navigationToken: String,
    val canClearData: Boolean,
    val protocol: String,
    val cipher: String,
    val certificate: SiteCertificate?,
    val certificateChain: List<SiteCertificate> = emptyList(),
    val keyExchange: String = "",
    val signature: String = "",
    val error: String = "",
    val builtInExtension: Boolean = false,
) {
    companion object {
        fun fromJson(value: JSONObject): SiteInformation {
            val chain = value.optJSONArray("certificateChain")
            return SiteInformation(
                value.optString("kind", "unknown"), value.optString("origin"),
                value.optString("host"), value.optString("navigationToken"),
                value.optBoolean("canClearData"), value.optString("protocol"),
                value.optString("cipher"), value.optJSONObject("certificate")?.let(SiteCertificate::fromJson),
                (0 until minOf(chain?.length() ?: 0, 16)).mapNotNull { index ->
                    chain?.optJSONObject(index)?.let(SiteCertificate::fromJson)
                },
                value.optString("keyExchange"), value.optString("signature"), value.optString("error"),
                value.opt("builtInExtension") == true,
            )
        }
    }
}

/** Product operations keep native engine objects behind the Runtime adapter. */
internal class AndroidProductServices private constructor(
    private val shared: SharedServices,
    private val activeSession: () -> EngineSessionPort?,
    private val session: (SessionId) -> EngineSessionPort?,
) {
    private class SharedServices(
        val settings: AndroidSettingsHost,
        val clearSitePermissions: (String?, Boolean?) -> Result<Unit>,
    ) {
        val main = Handler(Looper.getMainLooper())
        val cleanLinksChangePending = AtomicBoolean(false)
        val appearanceChangePending = AtomicBoolean(false)
    }

    constructor(
        settings: AndroidSettingsHost,
        activeSession: () -> EngineSessionPort?,
        session: (SessionId) -> EngineSessionPort?,
        clearSitePermissions: (String?, Boolean?) -> Result<Unit>,
    ) : this(SharedServices(settings, clearSitePermissions), activeSession, session)

    val settings: AndroidSettingsHost get() = shared.settings

    /** Only session lookup is window-owned; preferences and engine-wide writes are not. */
    fun forWindow(
        activeSession: () -> EngineSessionPort?,
        session: (SessionId) -> EngineSessionPort?,
    ): AndroidProductServices = AndroidProductServices(shared, activeSession, session)

    fun initialize(): CompletionStage<Unit> = query("product:initialize", JSONObject().put("searchLegacy",
        JSONObject().put("providers", JSONArray().also { rows -> settings.snapshot.searchProviders.forEach { provider ->
            rows.put(JSONObject().put("id", provider.id).put("name", provider.name)
                .put("template", provider.searchUrlTemplate))
        } }).put("defaultProviderId", settings.snapshot.searchProvider.id)
            .put("remoteSuggestionsEnabled", false))).thenCompose { initialized ->
        publishSearch(initialized.getJSONObject("search"))
        val selected = settings.snapshot
        settings.confirmDisplayLanguageApplied(selected.selectedDisplayLanguage.setting)
        settings.confirmProcessIsolationApplied(selected.selectedProcessIsolation.setting)
        query("appearance:set", JSONObject().put("key", "theme").put("value", selected.theme))
            .thenCompose { query("appearance:set", JSONObject().put("key", "accent").put("value", selected.accent)) }
            .thenApply { Unit }
    }

    private fun publishSearch(value: JSONObject) {
        val rows = value.getJSONArray("providers")
        require(rows.length() in 4..64)
        val providers = (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            AndroidSearchProvider(row.getString("id"), row.getString("name"), row.getString("template"),
                row.optString("suggestionTemplate"))
        }
        settings.publishSearchSnapshot(providers, value.getString("defaultProviderId"),
            value.getBoolean("remoteSuggestionsEnabled"))
    }

    fun updateSearch(action: String, data: JSONObject = JSONObject(), callback: (Result<Unit>) -> Unit) =
        deliver(query("search:$action", data)) { result -> callback(result.mapCatching { publishSearch(it) }) }

    fun resolveAddress(input: String, id: SessionId, forceSearch: Boolean = false,
        callback: (Result<String>) -> Unit) = deliver(
        query("search:resolve", JSONObject().put("query", input).put("forceSearch", forceSearch), session(id))
            .thenApply { it.getString("url") }, callback,
    )

    fun localSuggestions(input: String, candidates: JSONArray, id: SessionId,
        callback: (Result<List<AddressSuggestion>>) -> Unit) = deliver(
        query("search:local-rank", JSONObject().put("query", input).put("candidates", candidates), session(id))
            .thenApply { result ->
                val rows = result.getJSONArray("suggestions")
                (0 until minOf(rows.length(), 6)).map { index ->
                    val row = rows.getJSONObject(index)
                    AddressSuggestion(row.getString("kind"), row.getString("id"), row.getString("title"), row.getString("url"))
                }
            }, callback,
    )

    /** The native caller cancels the actual backend request when its editor/query changes. */
    fun remoteSuggestions(input: String, requestId: String, id: SessionId,
        callback: (Result<List<AddressSuggestion>>) -> Unit): AutoCloseable {
        val target = session(id)
        val cancelled = AtomicBoolean(false)
        deliver(query("search:suggest", JSONObject().put("query", input).put("requestId", requestId), target)
            .thenApply { result ->
                val rows = result.getJSONArray("suggestions")
                (0 until minOf(rows.length(), 5)).map { index ->
                    val text = rows.getString(index)
                    AddressSuggestion("search", "remote:$index", text, "")
                }
            }) { result -> if (!cancelled.get()) callback(result) }
        return AutoCloseable {
            if (cancelled.compareAndSet(false, true)) {
                query("search:cancel", JSONObject().put("requestId", requestId), target)
            }
        }
    }

    fun setAppearance(key: String, value: String, callback: (Result<Unit>) -> Unit) {
        if (!shared.appearanceChangePending.compareAndSet(false, true)) {
            deliver(CompletableFuture<Unit>().also {
                it.completeExceptionally(IllegalStateException("Another appearance change is pending"))
            }, callback)
            return
        }
        val previous = if (key == "theme") settings.snapshot.theme else settings.snapshot.accent
        deliver(query("appearance:set", JSONObject().put("key", key).put("value", value))) { result ->
            if (result.isFailure) {
                shared.appearanceChangePending.set(false)
                callback(Result.failure(checkNotNull(result.exceptionOrNull())))
            } else if (settings.setAppearance(key, value) in setOf(AndroidSettingsUpdateResult.UPDATED,
                    AndroidSettingsUpdateResult.UNCHANGED)) {
                shared.appearanceChangePending.set(false)
                callback(Result.success(Unit))
            } else {
                deliver(query("appearance:set", JSONObject().put("key", key).put("value", previous))) { rollback ->
                    shared.appearanceChangePending.set(false)
                    callback(Result.failure(IllegalStateException("Could not persist appearance settings", rollback.exceptionOrNull())))
                }
            }
        }
    }

    fun siteInformation(id: SessionId, callback: (Result<SiteInformation>) -> Unit) = deliver(
        query("site:get", target = session(id)).thenApply(SiteInformation::fromJson), callback,
    )

    fun siteIdentity(id: SessionId, callback: (Result<SiteInformation>) -> Unit) = deliver(
        query("site:get", JSONObject().put("issueClearToken", false), session(id))
            .thenApply(SiteInformation::fromJson), callback,
    )

    fun clearSiteData(id: SessionId, token: String, callback: (Result<Unit>) -> Unit) {
        deliver(query("site:clear", JSONObject().put("navigationToken", token), session(id))) { result ->
            callback(result.mapCatching {
                shared.clearSitePermissions(it.getString("origin"), it.getBoolean("privateMode")).getOrThrow()
            })
        }
    }

    fun clearAllSiteData(callback: (Result<Unit>) -> Unit) {
        deliver(query("data:clear")) { result ->
            callback(result.mapCatching { shared.clearSitePermissions(null, null).getOrThrow() })
        }
    }

    fun copyLink(value: String, callback: (Result<String>) -> Unit) = deliver(
        query("links:copy", JSONObject().put("url", value)).thenApply { it.getString("url") }, callback,
    )

    fun support(callback: (Result<Map<String, String>>) -> Unit) = deliver(
        query("support:get").thenApply { json ->
            json.keys().asSequence().associateWith { json.opt(it)?.toString().orEmpty() }
        }, callback,
    )

    fun processes(callback: (Result<List<ProcessInformation>>) -> Unit) = deliver(
        query("processes:get").thenApply { result ->
            val rows = result.getJSONArray("processes")
            (0 until rows.length()).map { index ->
                val row = rows.getJSONObject(index)
                ProcessInformation(row.getString("id"), row.getString("title"), row.getString("origin"),
                    row.getString("type"), row.getLong("memoryBytes"),
                    if (row.isNull("cpuPercent")) null else row.getDouble("cpuPercent"),
                    row.getInt("threadCount"), row.getBoolean("canEnd"), row.getString("token"))
            }
        }, callback,
    )

    fun endProcess(token: String, callback: (Result<Unit>) -> Unit) = deliver(
        query("processes:end", JSONObject().put("token", token)).thenApply {
            check(it.getBoolean("ended"))
            Unit
        }, callback,
    )

    fun licenses(callback: (Result<String>) -> Unit) = deliver(
        query("credits:get").thenApply { it.getString("licenses") }, callback,
    )

    fun setCleanLinks(enabled: Boolean, callback: (Result<Unit>) -> Unit) {
        if (!shared.cleanLinksChangePending.compareAndSet(false, true)) {
            deliver(CompletableFuture<Unit>().also {
                it.completeExceptionally(IllegalStateException("A Clean Links setting change is already in progress"))
            }, callback)
            return
        }
        val previous = settings.snapshot.cleanLinksEnabled
        deliver(query("links:set", JSONObject().put("enabled", enabled))) { result ->
            if (result.isFailure) {
                shared.cleanLinksChangePending.set(false)
                callback(Result.failure(checkNotNull(result.exceptionOrNull())))
            } else {
                when (settings.setCleanLinksEnabled(enabled)) {
                    AndroidSettingsUpdateResult.UPDATED, AndroidSettingsUpdateResult.UNCHANGED -> {
                        shared.cleanLinksChangePending.set(false)
                        callback(Result.success(Unit))
                    }
                    else -> deliver(query("links:set", JSONObject().put("enabled", previous))) {
                        shared.cleanLinksChangePending.set(false)
                        callback(Result.failure(IllegalStateException("Could not save the setting")))
                    }
                }
            }
        }
    }

    private fun query(
        operation: String,
        data: JSONObject = JSONObject(),
        target: EngineSessionPort? = activeSession(),
    ): CompletionStage<JSONObject> = try {
        checkNotNull(target) { "No live browser session" }
            .querySession(operation, data.toString()).thenApply(::JSONObject)
    } catch (error: Throwable) {
        CompletableFuture<JSONObject>().also { it.completeExceptionally(error) }
    }

    private fun <T> deliver(stage: CompletionStage<T>, callback: (Result<T>) -> Unit) {
        stage.whenComplete { value, error ->
            val result = if (error == null) Result.success(value) else Result.failure(error)
            if (Looper.myLooper() == Looper.getMainLooper()) callback(result)
            else shared.main.post { callback(result) }
        }
    }
}
