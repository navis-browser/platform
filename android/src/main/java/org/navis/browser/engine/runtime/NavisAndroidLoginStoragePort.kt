/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.engine.runtime

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import org.mozilla.gecko.navis.NavisAndroidLogins

/** The sole adapter allowed to translate the Navis login contract to Gecko's private peer. */
internal class NavisAndroidLoginStoragePort private constructor() :
    EngineLoginStoragePort,
    NavisAndroidLogins.Delegate {
    private val lock = Any()
    private var delegate: EngineLoginStorageDelegate? = null
    private var peer: NavisAndroidLogins? = null
    private var closed = false

    override fun bind(delegate: EngineLoginStorageDelegate) {
        synchronized(lock) {
            check(!closed) { "Login-storage port is closed" }
            check(this.delegate == null || this.delegate === delegate) {
                "Login-storage port already has a product persistence delegate"
            }
            this.delegate = delegate
        }
    }

    override fun unbind(delegate: EngineLoginStorageDelegate) {
        synchronized(lock) {
            if (this.delegate === delegate) {
                this.delegate = null
            }
        }
    }

    override fun onLoginFetch(domain: String?): CompletionStage<Array<NavisAndroidLogins.Login>> {
        val current = synchronized(lock) { delegate.takeUnless { closed } }
            ?: return CompletableFuture.completedFuture(emptyArray())
        val fetched = try {
            current.fetchLogins(domain)
        } catch (error: Throwable) {
            return failedStage(error)
        }
        return fetched.thenApply { records ->
            records.take(MAX_FETCH_RESULTS).map { record ->
                NavisAndroidLogins.Login(
                    record.guid,
                    record.origin,
                    record.formActionOrigin,
                    record.httpRealm,
                    record.username,
                    record.password,
                )
            }.toTypedArray()
        }
    }

    override fun onLoginSave(login: NavisAndroidLogins.Login): CompletionStage<Void> {
        val current = synchronized(lock) { delegate.takeUnless { closed } }
            ?: return failedStage(IllegalStateException("Login storage is closed"))
        return try {
            current.saveLogin(
                EngineLoginRecord(
                    guid = login.guid,
                    origin = login.origin,
                    formActionOrigin = login.formActionOrigin,
                    httpRealm = login.httpRealm,
                    username = login.username,
                    password = login.password,
                ),
            ).thenApply<Void> { null }
        } catch (error: Throwable) {
            failedStage(error)
        }
    }

    override fun onLoginUsed(login: NavisAndroidLogins.Login, usedFields: Int) {
        if (usedFields and NavisAndroidLogins.USED_FIELD_PASSWORD == 0) {
            return
        }
        val guid = login.guid?.takeIf(String::isNotBlank) ?: return
        synchronized(lock) { delegate.takeUnless { closed } }?.markPasswordUsed(guid)
    }

    override fun close() {
        val installedPeer = synchronized(lock) {
            if (closed) {
                return
            }
            closed = true
            delegate = null
            peer.also { peer = null }
        }
        installedPeer?.close()
    }

    companion object {
        private const val MAX_FETCH_RESULTS = 500

        /** Installs exactly one global engine peer; call only after Gecko PROFILE_READY. */
        fun install(): NavisAndroidLoginStoragePort {
            val port = NavisAndroidLoginStoragePort()
            port.peer = NavisAndroidLogins.install(port)
            return port
        }

        private fun <T> failedStage(error: Throwable): CompletionStage<T> =
            CompletableFuture<T>().also { it.completeExceptionally(error) }
    }
}
