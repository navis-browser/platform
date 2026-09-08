/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.persistence

/** Allocation-safe boundary for the opaque Gecko session-state projection. */
internal object EngineSessionStatePolicy {
    const val MAX_UTF8_BYTES = 1_000_000

    fun accepts(value: String): Boolean =
        value.isNotEmpty() &&
            value.length <= MAX_UTF8_BYTES &&
            value.toByteArray(Charsets.UTF_8).size <= MAX_UTF8_BYTES
}
