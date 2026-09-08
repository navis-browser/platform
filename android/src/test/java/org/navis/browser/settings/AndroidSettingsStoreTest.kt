/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidSettingsStoreTest {
    @Test
    fun defaultsMatchDesktopProductSettings() {
        val store = AndroidSettingsStore(FakeStorage())

        assertEquals(
            listOf("google", "baidu", "bing", "duckduckgo"),
            AndroidSettingsCatalog.searchProviders.map(AndroidSearchProvider::id),
        )
        assertEquals("google", store.snapshot.searchProvider.id)
        assertEquals(AndroidDisplayLanguage.SYSTEM, store.snapshot.selectedDisplayLanguage)
        assertEquals(AndroidDisplayLanguage.EN_US, store.snapshot.activeDisplayLanguage)
        assertEquals("", AndroidDisplayLanguage.SYSTEM.applicationLocaleLanguageTags)
        assertEquals("en-US", AndroidDisplayLanguage.EN_US.applicationLocaleLanguageTags)
        assertEquals("zh-CN", AndroidDisplayLanguage.ZH_CN.applicationLocaleLanguageTags)
        assertEquals(AndroidProcessIsolationMode.FULL, store.snapshot.selectedProcessIsolation)
        assertEquals(AndroidProcessIsolationMode.FULL, store.snapshot.activeProcessIsolation)
        assertFalse(store.snapshot.cleanLinksEnabled)
        assertTrue(store.snapshot.bookmarkBarVisible)
        assertFalse(store.snapshot.restartRequired)
    }

    @Test
    fun invalidPersistedAndRequestedValuesFallBackWithoutBeingAccepted() {
        val storage = FakeStorage(
            initialValues = mutableMapOf(
                AndroidSettingsKeys.SEARCH_PROVIDER to "unknown-search",
                AndroidSettingsKeys.SELECTED_DISPLAY_LANGUAGE to "fr-FR",
                AndroidSettingsKeys.ACTIVE_DISPLAY_LANGUAGE to "invalid",
                AndroidSettingsKeys.SELECTED_PROCESS_ISOLATION to "none",
                AndroidSettingsKeys.ACTIVE_PROCESS_ISOLATION to "unsafe",
            ),
        )
        val store = AndroidSettingsStore(storage)
        val initial = store.snapshot

        assertEquals("google", initial.searchProvider.id)
        assertEquals(AndroidDisplayLanguage.SYSTEM, initial.selectedDisplayLanguage)
        assertEquals(AndroidDisplayLanguage.EN_US, initial.activeDisplayLanguage)
        assertEquals(AndroidProcessIsolationMode.FULL, initial.selectedProcessIsolation)
        assertEquals(AndroidProcessIsolationMode.FULL, initial.activeProcessIsolation)
        assertEquals(
            AndroidSettingsUpdateResult.INVALID_VALUE,
            store.selectDisplayLanguage("en-GB"),
        )
        assertEquals(
            AndroidSettingsUpdateResult.INVALID_VALUE,
            store.selectProcessIsolation("disabled"),
        )
        assertEquals(
            AndroidSettingsUpdateResult.INVALID_VALUE,
            store.confirmDisplayLanguageApplied("zh-HK"),
        )
        assertEquals(initial, store.snapshot)
    }

    @Test
    fun selectedSettingsPersistButOnlyANewProcessChangesActiveLanguage() {
        val storage = FakeStorage()
        val firstStore = AndroidSettingsStore(storage)

        assertEquals(
            AndroidSettingsUpdateResult.UPDATED,
            firstStore.selectDisplayLanguage("zh-CN"),
        )
        assertEquals(
            AndroidSettingsUpdateResult.UPDATED,
            firstStore.selectProcessIsolation("selective"),
        )
        assertEquals(AndroidDisplayLanguage.ZH_CN, firstStore.snapshot.selectedDisplayLanguage)
        assertEquals(AndroidDisplayLanguage.EN_US, firstStore.snapshot.activeDisplayLanguage)
        assertTrue(firstStore.snapshot.displayLanguageRestartRequired)
        assertTrue(firstStore.snapshot.processIsolationRestartRequired)
        firstStore.close()

        val anotherHost = AndroidSettingsStore(storage, frozenLanguageTag = "en-US")
        assertTrue(anotherHost.snapshot.displayLanguageRestartRequired)
        assertEquals(
            AndroidSettingsUpdateResult.INVALID_VALUE,
            anotherHost.confirmDisplayLanguageApplied("zh-CN"),
        )
        assertEquals(AndroidDisplayLanguage.EN_US, anotherHost.snapshot.activeDisplayLanguage)
        anotherHost.close()

        val restartedStore = AndroidSettingsStore(storage, frozenLanguageTag = "zh-CN")
        assertEquals(AndroidSettingsUpdateResult.UNCHANGED, restartedStore.confirmDisplayLanguageApplied("zh-CN"))
        assertFalse(restartedStore.snapshot.displayLanguageRestartRequired)
        assertTrue(restartedStore.snapshot.processIsolationRestartRequired)
        assertEquals(
            AndroidSettingsUpdateResult.UPDATED,
            restartedStore.confirmProcessIsolationApplied("selective"),
        )
        assertFalse(restartedStore.snapshot.restartRequired)
    }

    @Test
    fun languagePendingUsesResolvedSelectionAndObservesSystemChangesWithoutSwitchingActive() {
        var system = "en-GB"
        val storage = FakeStorage()
        val store = AndroidSettingsStore(storage, "en-US") { system }
        val snapshots = mutableListOf<AndroidSettingsSnapshot>()
        store.addObserver(AndroidSettingsObserver(snapshots::add))
        assertFalse(store.snapshot.displayLanguageRestartRequired)
        store.selectDisplayLanguage("en-US")
        assertFalse(store.snapshot.displayLanguageRestartRequired)
        store.selectDisplayLanguage("system")
        assertFalse(store.snapshot.displayLanguageRestartRequired)
        system = "zh-Hant-HK"
        storage.configurationChanged()
        assertEquals(AndroidDisplayLanguage.ZH_CN, snapshots.last().resolvedDisplayLanguage)
        assertEquals(AndroidDisplayLanguage.EN_US, snapshots.last().activeDisplayLanguage)
        assertTrue(snapshots.last().displayLanguageRestartRequired)
        store.selectDisplayLanguage("en-US")
        val count = snapshots.size
        system = "zh-CN"
        storage.configurationChanged()
        assertEquals(count, snapshots.size)
        assertFalse(store.snapshot.displayLanguageRestartRequired)
        // Old/stale host persistence cannot declare the new locale actually active.
        storage.writeString(AndroidSettingsKeys.ACTIVE_DISPLAY_LANGUAGE, "zh-CN")
        assertEquals(count, snapshots.size)
        assertEquals(AndroidDisplayLanguage.EN_US, store.snapshot.activeDisplayLanguage)
        store.close()
        system = "en-US"
        storage.configurationChanged()
        assertEquals(count, snapshots.size)
    }

    @Test
    fun unsupportedSystemLocalesFallBackToEnglishAndNewHostsUseInjectedProcessLanguage() {
        val storage = FakeStorage(mutableMapOf(AndroidSettingsKeys.SELECTED_DISPLAY_LANGUAGE to "zh-CN"))
        val first = AndroidSettingsStore(storage, "en-US") { "fr-FR" }
        assertTrue(first.snapshot.displayLanguageRestartRequired)
        val another = AndroidSettingsStore(storage, "en-US") { "zh-CN" }
        assertEquals(AndroidDisplayLanguage.EN_US, another.snapshot.activeDisplayLanguage)
        first.selectDisplayLanguage("system")
        assertEquals(AndroidDisplayLanguage.EN_US, first.snapshot.resolvedDisplayLanguage)
        assertFalse(first.snapshot.displayLanguageRestartRequired)
        assertTrue(another.snapshot.displayLanguageRestartRequired)
        first.close(); another.close()
    }

    @Test
    fun liveSettingsAndObserversPersistAcrossStoreInstances() {
        val storage = FakeStorage()
        val store = AndroidSettingsStore(storage)
        val observed = mutableListOf<AndroidSettingsSnapshot>()
        val observer = AndroidSettingsObserver(observed::add)
        store.addObserver(observer)

        store.publishSearchSnapshot(AndroidSettingsCatalog.searchProviders, "baidu", false)
        assertEquals(AndroidSettingsUpdateResult.UPDATED, store.setCleanLinksEnabled(true))
        assertEquals(AndroidSettingsUpdateResult.UPDATED, store.setBookmarkBarVisible(false))
        assertEquals(AndroidSettingsUpdateResult.UNCHANGED, store.setBookmarkBarVisible(false))

        assertEquals(4, observed.size)
        assertEquals("baidu", observed.last().searchProvider.id)
        assertTrue(observed.last().cleanLinksEnabled)
        assertFalse(observed.last().bookmarkBarVisible)
        store.removeObserver(observer)
        store.close()

        val restored = AndroidSettingsStore(storage).snapshot
        assertEquals("google", restored.searchProvider.id) // Core republishes after startup; no Android search write.
        assertTrue(restored.cleanLinksEnabled)
        assertFalse(restored.bookmarkBarVisible)
    }

    @Test
    fun externalOwnedPreferenceChangesRefreshButUnrelatedChangesDoNot() {
        val storage = FakeStorage()
        val store = AndroidSettingsStore(storage)
        val observed = mutableListOf<AndroidSettingsSnapshot>()
        store.addObserver(AndroidSettingsObserver(observed::add))

        storage.writeString("unrelated", "value")
        assertEquals(1, observed.size)

        storage.writeString(AndroidSettingsKeys.SEARCH_PROVIDER, "bing")
        assertEquals(2, observed.size)
        assertEquals("bing", observed.last().searchProvider.id)
    }

    @Test
    fun historyButtonVisibilityNotifiesOtherWindowsAndSurvivesRestart() {
        val storage = FakeStorage()
        val first = AndroidSettingsStore(storage)
        val second = AndroidSettingsStore(storage)
        val observed = mutableListOf<Boolean>()
        second.addObserver(AndroidSettingsObserver { observed += it.historyButton })
        assertFalse(first.snapshot.historyButton)
        assertEquals(AndroidSettingsUpdateResult.UPDATED, first.setHistoryButtonVisible(true))
        assertEquals(listOf(false, true), observed)
        assertEquals(true, storage.readBoolean(AndroidSettingsKeys.HISTORY_BUTTON_VISIBLE))
        assertEquals(null, storage.readBoolean(AndroidSettingsKeys.LEGACY_HISTORY_SIDEBAR_VISIBLE))
        assertFalse(first.snapshot.restartRequired)
        assertEquals(AndroidSettingsUpdateResult.UNCHANGED, second.setHistoryButtonVisible(true))
        first.close()
        second.close()
        val restarted = AndroidSettingsStore(storage)
        assertTrue(restarted.snapshot.historyButton)
        assertEquals(AndroidSettingsUpdateResult.UPDATED, restarted.setHistoryButtonVisible(false))
        assertFalse(restarted.snapshot.historyButton)
        restarted.close()
    }

    @Test
    fun historyButtonReadsLegacyOnlyWhenNewPreferenceIsAbsent() {
        for (current in listOf(null, false, true)) {
            for (legacy in listOf(null, false, true)) {
                val values = mutableMapOf<String, Any>()
                current?.let { values[AndroidSettingsKeys.HISTORY_BUTTON_VISIBLE] = it }
                legacy?.let { values[AndroidSettingsKeys.LEGACY_HISTORY_SIDEBAR_VISIBLE] = it }
                val storage = FakeStorage(values)
                val store = AndroidSettingsStore(storage)
                assertEquals(current ?: legacy ?: false, store.snapshot.historyButton)
                // Reading an older profile does not mutate it or rewrite its legacy value.
                assertEquals(current, storage.readBoolean(AndroidSettingsKeys.HISTORY_BUTTON_VISIBLE))
                assertEquals(legacy, storage.readBoolean(AndroidSettingsKeys.LEGACY_HISTORY_SIDEBAR_VISIBLE))
                store.close()
            }
        }
    }

    @Test
    fun explicitHistoryButtonFalseOverridesLegacyTrueAndIgnoresLaterLegacyChanges() {
        val storage = FakeStorage(mutableMapOf(AndroidSettingsKeys.LEGACY_HISTORY_SIDEBAR_VISIBLE to true))
        val store = AndroidSettingsStore(storage)
        val observed = mutableListOf<Boolean>()
        store.addObserver(AndroidSettingsObserver { observed += it.historyButton })
        assertTrue(store.snapshot.historyButton)
        assertEquals(AndroidSettingsUpdateResult.UPDATED, store.setHistoryButtonVisible(false))
        assertEquals(false, storage.readBoolean(AndroidSettingsKeys.HISTORY_BUTTON_VISIBLE))
        assertEquals(true, storage.readBoolean(AndroidSettingsKeys.LEGACY_HISTORY_SIDEBAR_VISIBLE))
        storage.writeBoolean(AndroidSettingsKeys.LEGACY_HISTORY_SIDEBAR_VISIBLE, false)
        storage.writeBoolean(AndroidSettingsKeys.LEGACY_HISTORY_SIDEBAR_VISIBLE, true)
        assertEquals(listOf(true, false), observed)
        store.close()
        val restarted = AndroidSettingsStore(storage)
        assertFalse(restarted.snapshot.historyButton)
        restarted.close()
    }

    @Test
    fun externalHistoryPreferenceChangesObserveFallbackAndCurrentKeys() {
        val storage = FakeStorage()
        val store = AndroidSettingsStore(storage)
        val observed = mutableListOf<Boolean>()
        store.addObserver(AndroidSettingsObserver { observed += it.historyButton })
        storage.writeBoolean(AndroidSettingsKeys.LEGACY_HISTORY_SIDEBAR_VISIBLE, true)
        storage.writeBoolean(AndroidSettingsKeys.LEGACY_HISTORY_SIDEBAR_VISIBLE, false)
        storage.writeBoolean(AndroidSettingsKeys.HISTORY_BUTTON_VISIBLE, true)
        storage.writeBoolean(AndroidSettingsKeys.HISTORY_BUTTON_VISIBLE, false)
        assertEquals(listOf(false, true, false, true, false), observed)
        store.close()
    }

    @Test
    fun failedHistoryPreferenceWriteKeepsOldSnapshotAndDoesNotNotify() {
        val storage = FakeStorage(mutableMapOf(AndroidSettingsKeys.LEGACY_HISTORY_SIDEBAR_VISIBLE to true))
        val store = AndroidSettingsStore(storage)
        val observed = mutableListOf<Boolean>()
        store.addObserver(AndroidSettingsObserver { observed += it.historyButton })
        storage.failWrites = true
        assertEquals(AndroidSettingsUpdateResult.PERSISTENCE_FAILED, store.setHistoryButtonVisible(false))
        assertTrue(store.snapshot.historyButton)
        assertEquals(null, storage.readBoolean(AndroidSettingsKeys.HISTORY_BUTTON_VISIBLE))
        assertEquals(listOf(true), observed)
        store.close()
    }

    @Test
    fun closedHostRejectsFurtherMutationsAndStopsObservingStorage() {
        val storage = FakeStorage()
        val store = AndroidSettingsStore(storage)
        val observed = mutableListOf<AndroidSettingsSnapshot>()
        store.addObserver(AndroidSettingsObserver(observed::add))
        store.close()

        assertEquals(AndroidSettingsUpdateResult.CLOSED, store.setCleanLinksEnabled(true))
        storage.writeBoolean(AndroidSettingsKeys.CLEAN_LINKS_ENABLED, true)
        assertEquals(1, observed.size)
        assertFalse(store.snapshot.cleanLinksEnabled)
    }

    @Test
    fun coreSearchProjectionRetainsCustomEndpointsAndOverridesLegacyWithoutWritingThem() {
        val storage = FakeStorage()
        val store = AndroidSettingsStore(storage)
        assertFalse(store.snapshot.remoteSuggestionsEnabled)
        assertFalse(store.snapshot.searchServiceReady)
        val custom = AndroidSearchProvider("custom", "Example", "https://example.com/search?q={searchTerms}",
            "https://example.com/suggest?q={searchTerms}")
        val providers = mutableListOf(custom).apply { addAll(AndroidSettingsCatalog.searchProviders) }
        store.publishSearchSnapshot(providers, custom.id, true)
        providers.clear()
        assertEquals(custom, store.snapshot.searchProvider)
        assertEquals(custom, store.snapshot.searchProviders.first())
        assertTrue(store.snapshot.remoteSuggestionsEnabled)
        assertTrue(store.snapshot.searchServiceReady)
        assertEquals(null, storage.readString(AndroidSettingsKeys.SEARCH_PROVIDERS))
        assertEquals(null, storage.readString(AndroidSettingsKeys.SEARCH_PROVIDER))
        storage.writeString(AndroidSettingsKeys.SEARCH_PROVIDER, "bing")
        assertEquals(custom, store.snapshot.searchProvider)
        store.setBookmarkBarVisible(false)
        assertEquals(custom, store.snapshot.searchProvider)
        store.publishSearchSnapshot(AndroidSettingsCatalog.searchProviders, "google", false)
        assertFalse(store.snapshot.remoteSuggestionsEnabled)
        assertEquals("google", store.snapshot.searchProvider.id)
        store.close()
        store.publishSearchSnapshot(AndroidSettingsCatalog.searchProviders, "baidu", true)
        assertEquals("google", store.snapshot.searchProvider.id)
    }

    @Test
    fun searchTemplatesRejectCredentialsHostPlaceholdersAndUnsafeSchemesWithoutMutation() {
        val store = AndroidSettingsStore(FakeStorage())
        val before = store.snapshot
        for (template in listOf("javascript:{searchTerms}", "https://{searchTerms}.example.com/",
            "https://user:pass@example.com/?q={searchTerms}", "https://example.com/", "https://example.com/{searchTerms}\nheader")) {
            assertFalse(AndroidSearchProviderPolicy.valid(AndroidSearchProvider("custom", "Example", template)))
            assertEquals(before, store.snapshot)
        }
    }

    @Test
    fun appearanceAndDownloadChoicesPersistAndRejectInvalidValues() {
        val storage = FakeStorage()
        val store = AndroidSettingsStore(storage)
        assertEquals(AndroidSettingsUpdateResult.UPDATED, store.setAppearance("theme", "dark"))
        assertEquals(AndroidSettingsUpdateResult.UPDATED, store.setAppearance("accent", "#123ABC"))
        assertEquals(AndroidSettingsUpdateResult.UPDATED, store.setDownloadDirectory("content://documents/tree/downloads"))
        assertEquals(AndroidSettingsUpdateResult.UPDATED, store.setDownloadOption("askBeforeSaving", false))
        assertEquals(AndroidSettingsUpdateResult.UPDATED, store.setDownloadOption("deletePrivateOnExit", true))
        assertEquals(AndroidSettingsUpdateResult.UPDATED, store.setDownloadOption("openWhenComplete", true))
        assertEquals(store.snapshot, AndroidSettingsStore(storage).snapshot)
        assertEquals("#123abc", store.snapshot.accent)
        val before = store.snapshot
        assertEquals(AndroidSettingsUpdateResult.INVALID_VALUE, store.setAppearance("theme", "invalid"))
        assertEquals(AndroidSettingsUpdateResult.INVALID_VALUE, store.setAppearance("accent", "red"))
        assertEquals(AndroidSettingsUpdateResult.INVALID_VALUE, store.setDownloadDirectory("file:///etc"))
        assertEquals(AndroidSettingsUpdateResult.INVALID_VALUE, store.setDownloadOption("unknown", true))
        assertEquals(before, store.snapshot)
    }

    private class FakeStorage(
        initialValues: MutableMap<String, Any> = mutableMapOf(),
    ) : AndroidSettingsStorage {
        private val values = initialValues
        private val observers = linkedSetOf<AndroidSettingsStorageObserver>()
        var failWrites = false

        fun configurationChanged() { observers.toList().forEach { it.onStorageChanged(null) } }

        override fun readString(key: String): String? = values[key] as? String

        override fun readBoolean(key: String): Boolean? = values[key] as? Boolean

        override fun writeString(key: String, value: String) {
            values[key] = value
            observers.toList().forEach { it.onStorageChanged(key) }
        }

        override fun writeBoolean(key: String, value: Boolean) {
            check(!failWrites) { "Test storage write failure" }
            values[key] = value
            observers.toList().forEach { it.onStorageChanged(key) }
        }

        override fun observe(observer: AndroidSettingsStorageObserver): AutoCloseable {
            observers += observer
            return object : AutoCloseable {
                override fun close() {
                    observers -= observer
                }
            }
        }
    }
}
