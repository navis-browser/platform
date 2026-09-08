/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser

/**
 * Main-thread handoff for either an immediately cached or asynchronously created Runtime.
 *
 * Activity.onStart can run before Lifecycle reaches STARTED. Only the lifecycle event opens this
 * gate, and a rejected acceptance never consumes the pending result. No timing delay is involved.
 */
internal class StartedRuntimeDelivery<T>(
    private val accept: (Result<T>) -> Boolean,
) : AutoCloseable {
    private var pending: Result<T>? = null
    private var started = false
    private var delivered = false
    private var closed = false
    private var dispatching = false

    fun offer(result: Result<T>) {
        if (closed || delivered) return
        // The first engine completion owns this Activity's handoff.
        if (pending == null) pending = result
        drain()
    }

    /** Returns true only when this event actually handed off the Runtime result. */
    fun onStarted(): Boolean {
        if (closed) return false
        started = true
        return drain()
    }

    fun onStopped() {
        started = false
    }

    override fun close() {
        closed = true
        started = false
        pending = null
    }

    private fun drain(): Boolean {
        if (closed || delivered || !started || dispatching) return false
        val result = pending ?: return false
        dispatching = true
        return try {
            if (accept(result)) {
                pending = null
                delivered = true
                true
            } else {
                false
            }
        } finally {
            dispatching = false
        }
    }
}
