/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.engine

internal object SessionFaviconPolicy {
    const val PNG_PREFIX = "data:image/png;base64,"
    const val MAX_PNG_CHARS = 64 * 1024

    fun accepts(value: String): Boolean = value.isEmpty() ||
        (value.length in (PNG_PREFIX.length + 1)..MAX_PNG_CHARS &&
            value.startsWith(PNG_PREFIX) && value.substring(PNG_PREFIX.length).all {
                it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '+' || it == '/' || it == '='
            })
}
