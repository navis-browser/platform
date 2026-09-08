/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.engine.runtime

import java.util.concurrent.CompletionStage

/**
 * Decrypted credential visible only inside the private Engine/Platform seam.
 *
 * Product UI receives separate secret-free summaries from BrowserProfileStore;
 * this value exists solely so Gecko's real LoginManager producer can fetch,
 * save and account for credentials without importing a GeckoView owner.
 */
internal data class EngineLoginRecord(
    val guid: String?,
    val origin: String,
    val formActionOrigin: String?,
    val httpRealm: String?,
    val username: String,
    val password: String,
)

/** Product-owned persistence consumed by the engine login-storage peer. */
internal interface EngineLoginStorageDelegate {
    /** A null domain requests all records; Gecko performs final origin matching. */
    fun fetchLogins(domain: String?): CompletionStage<List<EngineLoginRecord>>

    /** Persists only a user-confirmed save/update emitted by Gecko. */
    fun saveLogin(login: EngineLoginRecord): CompletionStage<Unit>

    /** Records actual password use without exposing a credential to product UI. */
    fun markPasswordUsed(guid: String)
}

/**
 * Process-wide login-storage service owned by the same direct Engine Runtime.
 *
 * Only one product persistence delegate may be bound. Closing the Runtime
 * closes this port and completes outstanding Gecko callbacks fail-closed.
 */
internal interface EngineLoginStoragePort : AutoCloseable {
    fun bind(delegate: EngineLoginStorageDelegate)

    fun unbind(delegate: EngineLoginStorageDelegate)
}
