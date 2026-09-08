/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.settings

/** A search provider exposed by Navis settings and the address input. */
internal data class AndroidSearchProvider(
    val id: String,
    val name: String,
    val searchUrlTemplate: String,
    val suggestionUrlTemplate: String = "",
) {
    val builtIn: Boolean get() = id in setOf("google", "bing", "baidu", "duckduckgo")
}

internal object AndroidSearchProviderPolicy {
    fun valid(provider: AndroidSearchProvider): Boolean = runCatching {
        val template = provider.searchUrlTemplate
        val uri = java.net.URI(template.replace("{searchTerms}", "navis-query"))
        provider.id.matches(Regex("[a-z0-9-]{1,64}")) && provider.name.isNotBlank() &&
            provider.name.length <= 80 && provider.name.none { it.code < 32 || it.code == 127 } &&
            template.length <= 4096 && template.contains("{searchTerms}") &&
            template.none { it.code <= 32 || it.code == 127 } && uri.scheme in setOf("http", "https") &&
            !uri.host.isNullOrEmpty() && !uri.host.contains("navis-query") && uri.rawUserInfo == null
    }.getOrDefault(false)

    fun encode(providers: List<AndroidSearchProvider>): String = providers.joinToString("\n") {
        listOf(it.id, it.name, it.searchUrlTemplate).joinToString("\t") { value ->
            java.util.Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
        }
    }

    fun decode(value: String?): List<AndroidSearchProvider> = runCatching {
        require(value != null && value.length <= 512 * 1024)
        val providers = value.split('\n').map { line ->
            val parts = line.split('\t').map { String(java.util.Base64.getDecoder().decode(it), Charsets.UTF_8) }
            require(parts.size == 3)
            AndroidSearchProvider(parts[0], parts[1], parts[2]).also { require(valid(it)) }
        }
        require(providers.size in 4..64 && providers.map { it.id }.toSet().size == providers.size)
        require(AndroidSettingsCatalog.searchProviders.all { builtin -> providers.any { it.id == builtin.id } })
        providers
    }.getOrElse { AndroidSettingsCatalog.searchProviders }
}

/**
 * Product-owned settings choices shared by the Android UI and its runtime host.
 *
 * The search catalog is a read-only legacy migration fallback. The initialized Core search service
 * owns the live catalog, templates, ordering, default and network-suggestion consent.
 */
internal object AndroidSettingsCatalog {
    val searchProviders: List<AndroidSearchProvider> = listOf(
        AndroidSearchProvider(
            id = "google",
            name = "Google",
            searchUrlTemplate = "https://www.google.com/search?q={searchTerms}",
        ),
        AndroidSearchProvider(
            id = "baidu",
            name = "Baidu",
            searchUrlTemplate = "https://www.baidu.com/s?wd={searchTerms}",
        ),
        AndroidSearchProvider(
            id = "bing",
            name = "Bing",
            searchUrlTemplate = "https://www.bing.com/search?q={searchTerms}",
        ),
        AndroidSearchProvider(
            id = "duckduckgo",
            name = "DuckDuckGo",
            searchUrlTemplate = "https://duckduckgo.com/?q={searchTerms}",
        ),
    )

    val defaultSearchProvider: AndroidSearchProvider = searchProviders.first()

    private val searchProvidersById = searchProviders.associateBy(AndroidSearchProvider::id)

    fun searchProvider(id: String?): AndroidSearchProvider =
        searchProvidersById[id] ?: defaultSearchProvider

    fun knownSearchProvider(id: String): AndroidSearchProvider? = searchProvidersById[id]
}

/**
 * Stable setting values. [applicationLocaleLanguageTags] follows Android's application-locale
 * convention: an empty tag list means "follow the system".
 */
internal enum class AndroidDisplayLanguage(
    val setting: String,
    val applicationLocaleLanguageTags: String,
) {
    SYSTEM("system", ""),
    EN_US("en-US", "en-US"),
    ZH_CN("zh-CN", "zh-CN"),
    ;

    /** Resolve the preference without changing this process's UI or engine locale. */
    fun resolve(systemLanguageTag: String): AndroidDisplayLanguage = when (this) {
        EN_US, ZH_CN -> this
        SYSTEM -> if (java.util.Locale.forLanguageTag(systemLanguageTag).language == "zh") ZH_CN else EN_US
    }

    companion object {
        private val bySetting = entries.associateBy(AndroidDisplayLanguage::setting)

        fun parse(setting: String?): AndroidDisplayLanguage? = bySetting[setting]
    }
}

internal enum class AndroidProcessIsolationMode(val setting: String) {
    FULL("full"),
    SELECTIVE("selective"),
    SHARED("shared"),
    ;

    companion object {
        private val bySetting = entries.associateBy(AndroidProcessIsolationMode::setting)

        fun parse(setting: String?): AndroidProcessIsolationMode? = bySetting[setting]
    }
}

/**
 * One immutable view of all Android 1.0 settings.
 *
 * Restart-sensitive settings retain the selection and the actual running value. Language is
 * injected from the application's frozen process locale, never inferred from a persisted active
 * selection. Persisting a choice never claims that the engine has already adopted it.
 */
internal data class AndroidSettingsSnapshot(
    val searchProvider: AndroidSearchProvider,
    val selectedDisplayLanguage: AndroidDisplayLanguage,
    val activeDisplayLanguage: AndroidDisplayLanguage,
    val selectedProcessIsolation: AndroidProcessIsolationMode,
    val activeProcessIsolation: AndroidProcessIsolationMode,
    val cleanLinksEnabled: Boolean,
    val bookmarkBarVisible: Boolean,
    /** Current selection after system-locale negotiation, distinct from the frozen active value. */
    val resolvedDisplayLanguage: AndroidDisplayLanguage = selectedDisplayLanguage.resolve("en-US"),
    val searchProviders: List<AndroidSearchProvider> = AndroidSettingsCatalog.searchProviders,
    val theme: String = "system",
    val accent: String = "#0b57d0",
    val downloadDirectory: String = "",
    val askBeforeSaving: Boolean = true,
    val deletePrivateOnExit: Boolean = false,
    val openWhenComplete: Boolean = false,
    /** Toolbar shortcut visibility; opening its sheet is transient window UI state. */
    val historyButton: Boolean = false,
    val remoteSuggestionsEnabled: Boolean = false,
    val searchServiceReady: Boolean = false,
) {
    val displayLanguageRestartRequired: Boolean
        get() = resolvedDisplayLanguage != activeDisplayLanguage

    val processIsolationRestartRequired: Boolean
        get() = selectedProcessIsolation != activeProcessIsolation

    val restartRequired: Boolean
        get() = displayLanguageRestartRequired || processIsolationRestartRequired
}

internal fun interface AndroidSettingsObserver {
    fun onSettingsChanged(snapshot: AndroidSettingsSnapshot)
}

internal enum class AndroidSettingsUpdateResult {
    UPDATED,
    UNCHANGED,
    INVALID_VALUE,
    PERSISTENCE_FAILED,
    CLOSED,
}

/**
 * Host-facing settings boundary. UI requests durable choices here; only a new application process
 * can change the active language. The runtime confirms isolation after applying its startup mode.
 */
internal interface AndroidSettingsHost : AutoCloseable {
    val snapshot: AndroidSettingsSnapshot

    fun addObserver(observer: AndroidSettingsObserver)

    fun removeObserver(observer: AndroidSettingsObserver)

    /** Cache an immutable Core projection. This never writes Android search preferences. */
    fun publishSearchSnapshot(providers: List<AndroidSearchProvider>, defaultProviderId: String,
        remoteSuggestionsEnabled: Boolean)

    fun setAppearance(key: String, value: String): AndroidSettingsUpdateResult = AndroidSettingsUpdateResult.INVALID_VALUE

    fun setDownloadDirectory(uri: String): AndroidSettingsUpdateResult = AndroidSettingsUpdateResult.INVALID_VALUE

    fun setDownloadOption(key: String, enabled: Boolean): AndroidSettingsUpdateResult = AndroidSettingsUpdateResult.INVALID_VALUE

    fun selectDisplayLanguage(setting: String): AndroidSettingsUpdateResult

    fun selectProcessIsolation(setting: String): AndroidSettingsUpdateResult

    fun setCleanLinksEnabled(enabled: Boolean): AndroidSettingsUpdateResult

    fun setBookmarkBarVisible(visible: Boolean): AndroidSettingsUpdateResult

    fun setHistoryButtonVisible(visible: Boolean): AndroidSettingsUpdateResult = AndroidSettingsUpdateResult.INVALID_VALUE

    /** Compatibility check only; it cannot replace the application-owned frozen process locale. */
    fun confirmDisplayLanguageApplied(setting: String): AndroidSettingsUpdateResult

    /** Called only after a newly started runtime has applied [setting]. */
    fun confirmProcessIsolationApplied(setting: String): AndroidSettingsUpdateResult
}
