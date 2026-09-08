/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.persistence

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import org.navis.browser.engine.runtime.EngineLoginRecord
import org.navis.browser.engine.runtime.EngineLoginStorageDelegate

/** Connects the engine login producer to Navis's SQLite + Android Keystore store. */
internal class AndroidLoginStorageDelegate(
    private val store: BrowserProfileStore,
) : EngineLoginStorageDelegate {
    override fun fetchLogins(domain: String?): CompletionStage<List<EngineLoginRecord>> {
        val result = CompletableFuture<List<EngineLoginRecord>>()
        val complete: (List<StoredLogin>) -> Unit = { records ->
            result.complete(
                records.map { record ->
                    EngineLoginRecord(
                        guid = record.guid,
                        origin = record.origin,
                        formActionOrigin = record.formActionOrigin,
                        httpRealm = record.httpRealm,
                        username = record.username,
                        password = record.password,
                    )
                },
            )
        }
        if (domain == null) {
            store.fetchAllLogins(complete)
        } else {
            store.fetchLogins(domain, complete)
        }
        return result
    }

    override fun saveLogin(login: EngineLoginRecord): CompletionStage<Unit> {
        val result = CompletableFuture<Unit>()
        store.saveLogin(
            StoredLogin(
                guid = login.guid,
                origin = login.origin,
                formActionOrigin = login.formActionOrigin,
                httpRealm = login.httpRealm,
                username = login.username,
                password = login.password,
            ),
        ) { saved -> saved.fold(result::complete, result::completeExceptionally) }
        return result
    }

    override fun markPasswordUsed(guid: String) {
        store.markLoginUsed(guid)
    }
}
