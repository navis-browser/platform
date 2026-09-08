/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.settings

import android.content.res.Resources

internal fun currentSystemLanguageTag(): String {
    val locales = Resources.getSystem().configuration.locales
    return if (locales.isEmpty) "en-US" else locales[0].toLanguageTag()
}

internal fun AndroidDisplayLanguage.resolvedLanguageTag(): String = resolve(currentSystemLanguageTag()).setting

internal fun AndroidSettingsSnapshot.engineStartupPreferences(): Map<String, Any> {
    val strategy = when (selectedProcessIsolation) {
        AndroidProcessIsolationMode.FULL -> 1
        AndroidProcessIsolationMode.SELECTIVE -> 2
        AndroidProcessIsolationMode.SHARED -> 0
    }
    return mapOf(
        // NavisApplication has already chosen the UI language for this process.
        // Do not resolve SYSTEM again later and accidentally give Gecko another language.
        "intl.locale.requested" to activeDisplayLanguage.setting,
        "browser.desktop-embedder.material-error-pages" to true,
        "fission.autostart" to true,
        "fission.webContentIsolationStrategy" to strategy,
        "navis.cleanLinks.enabled" to cleanLinksEnabled,
        "privacy.query_stripping.enabled" to cleanLinksEnabled,
        "privacy.query_stripping.enabled.pbmode" to cleanLinksEnabled,
        "privacy.query_stripping.redirect" to cleanLinksEnabled,
        "privacy.query_stripping.strip_on_share.enabled" to cleanLinksEnabled,
        "privacy.query_stripping.product_policy_uri" to
            "resource://gre/modules/navis/clean-links-policy.json",
    )
}
