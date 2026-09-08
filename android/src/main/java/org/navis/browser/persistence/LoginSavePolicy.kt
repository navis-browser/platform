/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.persistence

/** Bounds credentials without changing the values a site submitted. */
internal object LoginSavePolicy {
    const val MAX_USERNAME_LENGTH = 4_096
    const val MAX_PASSWORD_LENGTH = 16_384

    fun accepts(username: String, password: String): Boolean =
        username.length <= MAX_USERNAME_LENGTH &&
            password.isNotEmpty() &&
            password.length <= MAX_PASSWORD_LENGTH
}
