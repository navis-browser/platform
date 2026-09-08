/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.persistence

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

internal object LoginCipherBinding {
    private val context = "navis.login.v2".toByteArray(StandardCharsets.UTF_8)

    fun associatedData(guid: String, normalizedOrigin: String): ByteArray {
        val guidBytes = guid.toByteArray(StandardCharsets.UTF_8)
        val originBytes = normalizedOrigin.toByteArray(StandardCharsets.UTF_8)
        return ByteBuffer.allocate(
            Integer.BYTES * 3 + context.size + guidBytes.size + originBytes.size,
        )
            .putInt(context.size)
            .put(context)
            .putInt(guidBytes.size)
            .put(guidBytes)
            .putInt(originBytes.size)
            .put(originBytes)
            .array()
    }
}
