/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.ui

/** Presentation hint for the paste action. Core owns actual address classification/submission. */
internal fun addressInputIsUrl(input: String): Boolean = input.trim().let {
    it.isNotEmpty() && (HOST_PATTERN.matches(it) || SCHEME_PATTERN.matches(it))
}

private val SCHEME_PATTERN = Regex("^[A-Za-z][A-Za-z0-9+.-]*:.*$")
private val HOST_PATTERN = Regex(
    "^(localhost|(?:[A-Za-z0-9-]+\\.)+[A-Za-z]{2,63}|(?:\\d{1,3}\\.){3}\\d{1,3})(?::\\d{1,5})?(?:/.*)?$",
)
