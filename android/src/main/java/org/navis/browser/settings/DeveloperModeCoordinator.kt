/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.settings

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/** Result of one requested live developer-mode transition. */
internal enum class DeveloperModeChange {
    UNCHANGED,
    APPLIED,
    ENGINE_UNAVAILABLE,
}

/**
 * Keeps the live engine setting, durable preference, extension policy, and UI snapshot in one
 * transaction. A rejected engine transition must never be presented or persisted as successful.
 */
internal class DeveloperModeCoordinator(
    private val currentValue: () -> Boolean,
    private val applyToEngine: (Boolean) -> CompletionStage<Unit>,
    private val persistValue: (Boolean) -> Unit,
    private val onApplied: (Boolean) -> Unit,
) {
    fun setEnabled(enabled: Boolean): CompletionStage<DeveloperModeChange> {
        val previous = currentValue()
        if (previous == enabled) {
            return CompletableFuture.completedFuture(DeveloperModeChange.UNCHANGED)
        }
        val result = CompletableFuture<DeveloperModeChange>()
        val transition = try {
            applyToEngine(enabled)
        } catch (_: Throwable) {
            result.complete(DeveloperModeChange.ENGINE_UNAVAILABLE)
            return result
        }
        transition.whenComplete { _, error ->
            if (error != null) {
                result.complete(DeveloperModeChange.ENGINE_UNAVAILABLE)
            } else {
                try {
                    persistValue(enabled)
                    onApplied(enabled)
                    result.complete(DeveloperModeChange.APPLIED)
                } catch (commitError: Throwable) {
                    rollback(previous, commitError, result)
                }
            }
        }
        return result
    }

    /**
     * The engine has already accepted the new policy at this point. If the durable or published
     * product state cannot commit, restore all participants before surfacing the original failure.
     */
    private fun rollback(
        previous: Boolean,
        commitError: Throwable,
        result: CompletableFuture<DeveloperModeChange>,
    ) {
        val engineRollback = try {
            applyToEngine(previous)
        } catch (rollbackError: Throwable) {
            commitError.addSuppressed(rollbackError)
            restoreProductState(previous, commitError)
            result.completeExceptionally(commitError)
            return
        }
        engineRollback.whenComplete { _, rollbackError ->
            if (rollbackError != null) {
                commitError.addSuppressed(rollbackError)
            }
            restoreProductState(previous, commitError)
            result.completeExceptionally(commitError)
        }
    }

    private fun restoreProductState(previous: Boolean, commitError: Throwable) {
        try {
            persistValue(previous)
        } catch (rollbackError: Throwable) {
            commitError.addSuppressed(rollbackError)
        }
        try {
            onApplied(previous)
        } catch (rollbackError: Throwable) {
            commitError.addSuppressed(rollbackError)
        }
    }
}
