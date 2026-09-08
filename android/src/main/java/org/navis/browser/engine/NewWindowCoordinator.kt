/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.engine

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import org.navis.browser.api.SessionId

internal fun interface NewWindowDeadline {
    fun cancel()
}

internal fun interface NewWindowDeadlineScheduler {
    fun schedule(delayMillis: Long, action: () -> Unit): NewWindowDeadline
}

/**
 * Owns the product half of Gecko's two-phase new-window handoff.
 *
 * A child exists as an unpublished Session before its engine token is opened.
 * It becomes product-visible only after the engine reports that the native
 * window is real and the opener is still live. Every other path retires the
 * child exactly once and resolves fail-closed.
 */
internal class NewWindowCoordinator<Child>(
    private val isOpenerLive: (SessionId) -> Boolean,
    private val isChildLive: (Child) -> Boolean,
    private val scheduler: NewWindowDeadlineScheduler,
    private val onAccepted: (Child) -> Unit,
    private val onRejected: (Child) -> Unit,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MS,
) : AutoCloseable {
    private class Pending<Child>(
        val openerId: SessionId,
        val child: Child,
        val result: CompletableFuture<Boolean>,
    ) {
        var deadline: NewWindowDeadline? = null
    }

    private val pendingByToken = linkedMapOf<String, Pending<Child>>()
    private var closed = false

    @Synchronized
    fun request(
        openerId: SessionId,
        engineWindowToken: String,
        createChild: () -> Child,
        openChild: (Child) -> CompletionStage<Boolean>,
    ): CompletionStage<Boolean> {
        if (
            closed ||
            !isOpenerLive(openerId) ||
            !isValidEngineWindowToken(engineWindowToken) ||
            pendingByToken.containsKey(engineWindowToken)
        ) {
            return CompletableFuture.completedFuture(false)
        }

        val child = try {
            createChild()
        } catch (error: Throwable) {
            return CompletableFuture<Boolean>().also { it.completeExceptionally(error) }
        }
        val pending = Pending(openerId, child, CompletableFuture())
        pendingByToken[engineWindowToken] = pending

        val engineResult = try {
            openChild(child)
        } catch (error: Throwable) {
            finish(engineWindowToken, pending, accepted = false, error)
            return pending.result
        }
        engineResult.whenComplete { accepted, error ->
            finish(
                engineWindowToken,
                pending,
                accepted = error == null && accepted == true,
                error = error,
            )
        }
        if (pendingByToken[engineWindowToken] === pending) {
            val deadline = scheduler.schedule(timeoutMillis) {
                finish(engineWindowToken, pending, accepted = false, error = null)
            }
            pending.deadline = deadline
            if (pendingByToken[engineWindowToken] !== pending) {
                deadline.cancel()
            }
        }
        return pending.result
    }

    @Synchronized
    fun cancelForOpener(openerId: SessionId) {
        pendingByToken.entries
            .filter { it.value.openerId == openerId }
            .toList()
            .forEach { (token, pending) ->
                finish(token, pending, accepted = false, error = null)
            }
    }

    @Synchronized
    override fun close() {
        if (closed) {
            return
        }
        closed = true
        pendingByToken.entries.toList().forEach { (token, pending) ->
            finish(token, pending, accepted = false, error = null)
        }
    }

    @Synchronized
    private fun finish(
        engineWindowToken: String,
        pending: Pending<Child>,
        accepted: Boolean,
        error: Throwable?,
    ) {
        if (pendingByToken[engineWindowToken] !== pending) {
            return
        }
        pendingByToken.remove(engineWindowToken)
        pending.deadline?.cancel()

        if (accepted && isOpenerLive(pending.openerId) && isChildLive(pending.child)) {
            try {
                onAccepted(pending.child)
                pending.result.complete(true)
                return
            } catch (acceptError: Throwable) {
                reject(pending, acceptError)
                return
            }
        }
        reject(pending, error)
    }

    private fun reject(pending: Pending<Child>, error: Throwable?) {
        val cleanupError = runCatching { onRejected(pending.child) }.exceptionOrNull()
        val failure = error?.also { cause -> cleanupError?.let(cause::addSuppressed) }
            ?: cleanupError
        if (failure == null) {
            pending.result.complete(false)
        } else {
            pending.result.completeExceptionally(failure)
        }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 8_000L

        fun isValidEngineWindowToken(token: String): Boolean =
            token.length == 32 && token.all { it in '0'..'9' || it in 'a'..'f' }
    }
}
