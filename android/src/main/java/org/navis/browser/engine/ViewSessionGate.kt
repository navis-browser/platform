/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.engine

/**
 * Keeps a View's bound owner separate from its currently usable owner.
 *
 * A native session can close asynchronously while Android still holds the
 * View. Every platform callback must therefore resolve the live owner at the
 * time of dispatch instead of retaining an optimistic reference.
 */
internal class ViewSessionGate<T>(
    private val isLive: (T) -> Boolean,
) {
    private var bound: T? = null

    fun isBoundTo(candidate: T): Boolean = bound === candidate

    fun current(): T? = bound?.takeIf(isLive)

    /** Binds [next] and returns the previous, distinct owner. */
    fun replace(next: T): T? {
        if (bound === next) {
            return null
        }
        return bound.also { bound = next }
    }

    /** Clears the binding and returns it even when it has become stale. */
    fun clear(): T? = bound.also { bound = null }
}
