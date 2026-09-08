/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.settings

import androidx.core.os.LocaleListCompat

/**
 * Converts a persisted language choice to Android's application-locale value without applying it.
 * The empty list is Android's contract for following system locales. The product host remains
 * responsible for projecting this into Android and Gecko before confirming the setting as active.
 */
internal fun AndroidDisplayLanguage.toApplicationLocales(): LocaleListCompat =
    if (this == AndroidDisplayLanguage.SYSTEM) {
        LocaleListCompat.getEmptyLocaleList()
    } else {
        LocaleListCompat.forLanguageTags(applicationLocaleLanguageTags)
    }
