/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.engine

/**
 * Owns resources acquired during runtime initialization.
 *
 * Entries are released in strict reverse-acquisition order. Release is
 * idempotent, and one broken cleanup cannot prevent the remaining resources
 * from being released. This class intentionally has no Android or Gecko
 * dependency so its failure paths can be exercised as a local unit test.
 */
internal class RuntimeResourceLedger {
    internal data class ReleaseFailure(
        val resource: String,
        val cause: Throwable,
    )

    private data class Entry(
        val resource: String,
        val release: () -> Unit,
    )

    private val lock = Any()
    private val entries = ArrayDeque<Entry>()
    private var released = false

    fun own(resource: String, release: () -> Unit) {
        require(resource.isNotBlank()) { "Runtime resource name must not be blank" }
        synchronized(lock) {
            check(!released) { "Runtime resource ledger is already released" }
            entries.addLast(Entry(resource, release))
        }
    }

    fun releaseAll(): List<ReleaseFailure> {
        val pending = synchronized(lock) {
            if (released) {
                return emptyList()
            }
            released = true
            buildList(entries.size) {
                while (entries.isNotEmpty()) {
                    add(entries.removeLast())
                }
            }
        }
        return buildList {
            pending.forEach { entry ->
                try {
                    entry.release()
                } catch (error: Throwable) {
                    add(ReleaseFailure(entry.resource, error))
                }
            }
        }
    }
}
