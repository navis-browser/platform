/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.engine.runtime

import java.util.concurrent.CompletionStage

internal enum class EngineProductDataType {
    HISTORY,
    PASSWORDS,
    DOWNLOADS,
}

internal data class EngineProductDataClearRequest(
    val extensionId: String,
    val dataType: EngineProductDataType,
    val sinceUnixMillis: Long,
    val privateMode: Boolean,
) {
    init {
        require(extensionId.isNotBlank() && extensionId.length <= MAX_EXTENSION_ID_LENGTH) {
            "Invalid extension identity"
        }
        require(sinceUnixMillis in 0..MAX_SAFE_INTEGER) {
            "Invalid browsing-data timestamp"
        }
    }

    private companion object {
        const val MAX_EXTENSION_ID_LENGTH = 256
        const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
    }
}

internal interface EngineProductDataDelegate {
    fun clear(request: EngineProductDataClearRequest): CompletionStage<Boolean>
}

/** Process-wide engine transport for product-owned browsing-data stores. */
internal interface EngineBrowsingDataPort : AutoCloseable {
    fun bind(delegate: EngineProductDataDelegate)

    fun unbind(delegate: EngineProductDataDelegate)
}
