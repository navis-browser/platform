/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.settings

import android.content.Context
import android.content.ComponentCallbacks
import android.content.SharedPreferences
import android.content.res.Configuration
import org.navis.browser.NavisApplication

/** Android persistence adapter; all settings semantics remain in [AndroidSettingsStore]. */
internal class SharedPreferencesAndroidSettingsStorage(context: Context) : AndroidSettingsStorage {
    private val applicationContext = context.applicationContext
    private val preferences = applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    override fun readString(key: String): String? = try {
        preferences.getString(key, null)
    } catch (_: ClassCastException) {
        null
    }

    override fun readBoolean(key: String): Boolean? = try {
        if (preferences.contains(key)) preferences.getBoolean(key, false) else null
    } catch (_: ClassCastException) {
        null
    }

    override fun writeString(key: String, value: String) {
        preferences.edit().putString(key, value).apply()
    }

    override fun writeBoolean(key: String, value: Boolean) {
        preferences.edit().putBoolean(key, value).apply()
    }

    override fun observe(observer: AndroidSettingsStorageObserver): AutoCloseable {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            observer.onStorageChanged(key)
        }
        val configurationListener = object : ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) {
                // The preference may still be SYSTEM while its resolved language changed.
                // This refreshes pending/active presentation only; no half-switch of locales.
                observer.onStorageChanged(null)
            }
            @Deprecated("Deprecated in Android")
            override fun onLowMemory() = Unit
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        applicationContext.registerComponentCallbacks(configurationListener)
        return object : AutoCloseable {
            override fun close() {
                preferences.unregisterOnSharedPreferenceChangeListener(listener)
                applicationContext.unregisterComponentCallbacks(configurationListener)
            }
        }
    }

    internal companion object {
        const val PREFERENCES_NAME = "navis.settings.v1"
    }
}

/** The single production construction point used by the Android product host. */
internal fun createAndroidSettingsHost(context: Context): AndroidSettingsHost =
    AndroidSettingsStore(
        SharedPreferencesAndroidSettingsStorage(context),
        frozenLanguageTag = (context.applicationContext as? NavisApplication)?.resolvedDisplayLanguageTag ?: "en-US",
        systemLanguageTag = ::currentSystemLanguageTag,
    )

/** Called off the UI thread after session preparation and before terminating the old process. */
internal fun flushAndroidSettingsForRelaunch(context: Context): Boolean =
    context.getSharedPreferences(
        SharedPreferencesAndroidSettingsStorage.PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    ).edit().commit()
