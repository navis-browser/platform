/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.settings

internal fun interface AndroidSettingsStorageObserver {
    fun onStorageChanged(key: String?)
}

/** Minimal persistence seam so the settings state machine remains an ordinary JVM unit. */
internal interface AndroidSettingsStorage {
    fun readString(key: String): String?

    fun readBoolean(key: String): Boolean?

    fun writeString(key: String, value: String)

    fun writeBoolean(key: String, value: Boolean)

    fun observe(observer: AndroidSettingsStorageObserver): AutoCloseable
}

internal object AndroidSettingsKeys {
    const val SEARCH_PROVIDER = "search_provider"
    const val SEARCH_PROVIDERS = "search_providers_v1"
    const val THEME = "appearance_theme"
    const val ACCENT = "appearance_accent"
    const val DOWNLOAD_DIRECTORY = "download_directory"
    const val ASK_BEFORE_SAVING = "download_ask_before_saving"
    const val DELETE_PRIVATE_ON_EXIT = "download_delete_private_on_exit"
    const val OPEN_WHEN_COMPLETE = "download_open_when_complete"
    const val SELECTED_DISPLAY_LANGUAGE = "selected_display_language"
    // Legacy key remains readable by older profiles but is not process-locale authority.
    const val ACTIVE_DISPLAY_LANGUAGE = "active_display_language"
    const val SELECTED_PROCESS_ISOLATION = "selected_process_isolation"
    const val ACTIVE_PROCESS_ISOLATION = "active_process_isolation"
    const val CLEAN_LINKS_ENABLED = "clean_links_enabled"
    const val BOOKMARK_BAR_VISIBLE = "bookmark_bar_visible"
    const val HISTORY_BUTTON_VISIBLE = "history_button_visible"
    // Read-only fallback for profiles created before shortcut and sheet state were separated.
    const val LEGACY_HISTORY_SIDEBAR_VISIBLE = "history_sidebar_visible"

    val all: Set<String> = setOf(
        SEARCH_PROVIDER,
        SEARCH_PROVIDERS, THEME, ACCENT, DOWNLOAD_DIRECTORY, ASK_BEFORE_SAVING,
        DELETE_PRIVATE_ON_EXIT, OPEN_WHEN_COMPLETE,
        SELECTED_DISPLAY_LANGUAGE,
        ACTIVE_DISPLAY_LANGUAGE,
        SELECTED_PROCESS_ISOLATION,
        ACTIVE_PROCESS_ISOLATION,
        CLEAN_LINKS_ENABLED,
        BOOKMARK_BAR_VISIBLE,
        HISTORY_BUTTON_VISIBLE,
        LEGACY_HISTORY_SIDEBAR_VISIBLE,
    )
}

internal class AndroidSettingsStore(
    private val storage: AndroidSettingsStorage,
    frozenLanguageTag: String = "en-US",
    private val systemLanguageTag: () -> String = { "en-US" },
) : AndroidSettingsHost {
    private val lock = Any()
    private val observers = linkedSetOf<AndroidSettingsObserver>()
    private var closed = false
    private data class CoreSearch(val providers: List<AndroidSearchProvider>, val defaultId: String,
        val remoteSuggestionsEnabled: Boolean)
    private var coreSearch: CoreSearch? = null
    // Never trust the legacy active_display_language preference: it described a
    // previous host/selection, not the Android + Gecko language of this process.
    private val processLanguage = when (frozenLanguageTag) {
        "zh-CN" -> AndroidDisplayLanguage.ZH_CN
        else -> AndroidDisplayLanguage.EN_US
    }
    private var currentSnapshot = readSnapshot()
    private val storageObservation = storage.observe(::onStorageChanged)

    override val snapshot: AndroidSettingsSnapshot
        get() = synchronized(lock) { currentSnapshot }

    override fun addObserver(observer: AndroidSettingsObserver) {
        val initial = synchronized(lock) {
            if (closed) return
            observers += observer
            currentSnapshot
        }
        observer.onSettingsChanged(initial)
    }

    override fun removeObserver(observer: AndroidSettingsObserver) {
        synchronized(lock) {
            observers -= observer
        }
    }

    override fun publishSearchSnapshot(providers: List<AndroidSearchProvider>, defaultProviderId: String,
        remoteSuggestionsEnabled: Boolean) {
        require(providers.size in 4..64 && providers.any { it.id == defaultProviderId })
        synchronized(lock) {
            if (closed) return
            coreSearch = CoreSearch(providers.toList(), defaultProviderId, remoteSuggestionsEnabled)
        }
        refreshFromStorage()
    }

    override fun setAppearance(key: String, value: String): AndroidSettingsUpdateResult = when {
        key == "theme" && value in setOf("system", "light", "dark") ->
            updateString(AndroidSettingsKeys.THEME, value) { it.theme == value }
        key == "accent" && value.matches(Regex("#[0-9a-fA-F]{6}")) ->
            updateString(AndroidSettingsKeys.ACCENT, value.lowercase()) { it.accent == value.lowercase() }
        else -> AndroidSettingsUpdateResult.INVALID_VALUE
    }

    override fun setDownloadDirectory(uri: String): AndroidSettingsUpdateResult {
        if (uri.length > 65536 || uri.isNotEmpty() && !uri.startsWith("content://")) return AndroidSettingsUpdateResult.INVALID_VALUE
        return updateString(AndroidSettingsKeys.DOWNLOAD_DIRECTORY, uri) { it.downloadDirectory == uri }
    }

    override fun setDownloadOption(key: String, enabled: Boolean): AndroidSettingsUpdateResult = when (key) {
        "askBeforeSaving" -> updateBoolean(AndroidSettingsKeys.ASK_BEFORE_SAVING, enabled) { it.askBeforeSaving == enabled }
        "deletePrivateOnExit" -> updateBoolean(AndroidSettingsKeys.DELETE_PRIVATE_ON_EXIT, enabled) { it.deletePrivateOnExit == enabled }
        "openWhenComplete" -> updateBoolean(AndroidSettingsKeys.OPEN_WHEN_COMPLETE, enabled) { it.openWhenComplete == enabled }
        else -> AndroidSettingsUpdateResult.INVALID_VALUE
    }

    override fun selectDisplayLanguage(setting: String): AndroidSettingsUpdateResult {
        val language = AndroidDisplayLanguage.parse(setting)
            ?: return AndroidSettingsUpdateResult.INVALID_VALUE
        return updateString(
            key = AndroidSettingsKeys.SELECTED_DISPLAY_LANGUAGE,
            value = language.setting,
            unchanged = { it.selectedDisplayLanguage == language },
        )
    }

    override fun selectProcessIsolation(setting: String): AndroidSettingsUpdateResult {
        val mode = AndroidProcessIsolationMode.parse(setting)
            ?: return AndroidSettingsUpdateResult.INVALID_VALUE
        return updateString(
            key = AndroidSettingsKeys.SELECTED_PROCESS_ISOLATION,
            value = mode.setting,
            unchanged = { it.selectedProcessIsolation == mode },
        )
    }

    override fun setCleanLinksEnabled(enabled: Boolean): AndroidSettingsUpdateResult =
        updateBoolean(
            key = AndroidSettingsKeys.CLEAN_LINKS_ENABLED,
            value = enabled,
            unchanged = { it.cleanLinksEnabled == enabled },
        )

    override fun setBookmarkBarVisible(visible: Boolean): AndroidSettingsUpdateResult =
        updateBoolean(
            key = AndroidSettingsKeys.BOOKMARK_BAR_VISIBLE,
            value = visible,
            unchanged = { it.bookmarkBarVisible == visible },
        )

    override fun setHistoryButtonVisible(visible: Boolean): AndroidSettingsUpdateResult =
        updateBoolean(
            key = AndroidSettingsKeys.HISTORY_BUTTON_VISIBLE,
            value = visible,
            unchanged = { it.historyButton == visible },
        )

    override fun confirmDisplayLanguageApplied(setting: String): AndroidSettingsUpdateResult {
        val language = AndroidDisplayLanguage.parse(setting)
            ?: return AndroidSettingsUpdateResult.INVALID_VALUE
        return synchronized(lock) {
            if (closed) AndroidSettingsUpdateResult.CLOSED
            else if (language.resolve(systemLanguageTag()) == processLanguage) AndroidSettingsUpdateResult.UNCHANGED
            else AndroidSettingsUpdateResult.INVALID_VALUE
        }
    }

    override fun confirmProcessIsolationApplied(setting: String): AndroidSettingsUpdateResult {
        val mode = AndroidProcessIsolationMode.parse(setting)
            ?: return AndroidSettingsUpdateResult.INVALID_VALUE
        return updateString(
            key = AndroidSettingsKeys.ACTIVE_PROCESS_ISOLATION,
            value = mode.setting,
            unchanged = { it.activeProcessIsolation == mode },
        )
    }

    override fun close() {
        val shouldClose = synchronized(lock) {
            if (closed) {
                false
            } else {
                closed = true
                observers.clear()
                true
            }
        }
        if (shouldClose) {
            storageObservation.close()
        }
    }

    private fun updateString(
        key: String,
        value: String,
        unchanged: (AndroidSettingsSnapshot) -> Boolean,
    ): AndroidSettingsUpdateResult = update(
        unchanged = unchanged,
        write = { storage.writeString(key, value) },
    )

    private fun updateBoolean(
        key: String,
        value: Boolean,
        unchanged: (AndroidSettingsSnapshot) -> Boolean,
    ): AndroidSettingsUpdateResult = update(
        unchanged = unchanged,
        write = { storage.writeBoolean(key, value) },
    )

    private fun update(
        unchanged: (AndroidSettingsSnapshot) -> Boolean,
        write: () -> Unit,
    ): AndroidSettingsUpdateResult {
        val before = synchronized(lock) {
            if (closed) return AndroidSettingsUpdateResult.CLOSED
            if (unchanged(currentSnapshot)) return AndroidSettingsUpdateResult.UNCHANGED
            currentSnapshot
        }
        try {
            write()
        } catch (_: RuntimeException) {
            return AndroidSettingsUpdateResult.PERSISTENCE_FAILED
        }
        // SharedPreferences listeners normally refresh synchronously. This explicit refresh also
        // keeps custom storage adapters correct if their notification is deferred.
        refreshFromStorage()
        return if (snapshot != before) {
            AndroidSettingsUpdateResult.UPDATED
        } else {
            AndroidSettingsUpdateResult.PERSISTENCE_FAILED
        }
    }

    private fun onStorageChanged(key: String?) {
        if (key == null || key in AndroidSettingsKeys.all) {
            refreshFromStorage()
        }
    }

    private fun refreshFromStorage() {
        val refreshed = try {
            readSnapshot()
        } catch (_: RuntimeException) {
            return
        }
        val listeners = synchronized(lock) {
            if (closed || refreshed == currentSnapshot) return
            currentSnapshot = refreshed
            observers.toList()
        }
        listeners.forEach { observer ->
            observer.onSettingsChanged(refreshed)
        }
    }

    private fun readSnapshot(): AndroidSettingsSnapshot {
        val search = synchronized(lock) { coreSearch }
        val providers = search?.providers
            ?: AndroidSearchProviderPolicy.decode(storage.readString(AndroidSettingsKeys.SEARCH_PROVIDERS))
        val selectedLanguage = AndroidDisplayLanguage.parse(
            storage.readString(AndroidSettingsKeys.SELECTED_DISPLAY_LANGUAGE),
        ) ?: DEFAULT_DISPLAY_LANGUAGE
        val selectedIsolation = AndroidProcessIsolationMode.parse(
            storage.readString(AndroidSettingsKeys.SELECTED_PROCESS_ISOLATION),
        ) ?: DEFAULT_PROCESS_ISOLATION
        val activeIsolation = AndroidProcessIsolationMode.parse(
            storage.readString(AndroidSettingsKeys.ACTIVE_PROCESS_ISOLATION),
        ) ?: DEFAULT_PROCESS_ISOLATION
        return AndroidSettingsSnapshot(
            searchProvider = providers.firstOrNull { it.id == (search?.defaultId ?: storage.readString(AndroidSettingsKeys.SEARCH_PROVIDER)) }
                ?: providers.first { it.id == "google" },
            selectedDisplayLanguage = selectedLanguage,
            activeDisplayLanguage = processLanguage,
            selectedProcessIsolation = selectedIsolation,
            activeProcessIsolation = activeIsolation,
            cleanLinksEnabled = storage.readBoolean(AndroidSettingsKeys.CLEAN_LINKS_ENABLED)
                ?: DEFAULT_CLEAN_LINKS_ENABLED,
            bookmarkBarVisible = storage.readBoolean(AndroidSettingsKeys.BOOKMARK_BAR_VISIBLE)
                ?: DEFAULT_BOOKMARK_BAR_VISIBLE,
            resolvedDisplayLanguage = selectedLanguage.resolve(systemLanguageTag()),
            searchProviders = providers,
            remoteSuggestionsEnabled = search?.remoteSuggestionsEnabled ?: false,
            searchServiceReady = search != null,
            theme = storage.readString(AndroidSettingsKeys.THEME)?.takeIf { it in setOf("system", "light", "dark") } ?: "system",
            accent = storage.readString(AndroidSettingsKeys.ACCENT)?.takeIf { it.matches(Regex("#[0-9a-fA-F]{6}")) }?.lowercase() ?: "#0b57d0",
            downloadDirectory = storage.readString(AndroidSettingsKeys.DOWNLOAD_DIRECTORY)?.takeIf { it.startsWith("content://") } ?: "",
            askBeforeSaving = storage.readBoolean(AndroidSettingsKeys.ASK_BEFORE_SAVING) ?: true,
            deletePrivateOnExit = storage.readBoolean(AndroidSettingsKeys.DELETE_PRIVATE_ON_EXIT) ?: false,
            openWhenComplete = storage.readBoolean(AndroidSettingsKeys.OPEN_WHEN_COMPLETE) ?: false,
            historyButton = storage.readBoolean(AndroidSettingsKeys.HISTORY_BUTTON_VISIBLE)
                ?: storage.readBoolean(AndroidSettingsKeys.LEGACY_HISTORY_SIDEBAR_VISIBLE) ?: false,
        )
    }

    private companion object {
        val DEFAULT_DISPLAY_LANGUAGE = AndroidDisplayLanguage.SYSTEM
        val DEFAULT_PROCESS_ISOLATION = AndroidProcessIsolationMode.FULL
        const val DEFAULT_CLEAN_LINKS_ENABLED = false
        const val DEFAULT_BOOKMARK_BAR_VISIBLE = true
    }
}
