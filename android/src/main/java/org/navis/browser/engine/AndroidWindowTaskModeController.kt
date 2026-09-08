/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.engine

import android.app.Activity
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.OutcomeReceiver
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

/** Public task-mode API only. Browser/DOM immersive presentation remains separate. */
internal class AndroidWindowTaskModeController(
    private val activity: Activity,
    private val isCurrent: () -> Boolean,
    private val onStateChanged: (maximized: Boolean) -> Unit,
) : AutoCloseable {
    private class Pending(val enter: Boolean, val eventAtStart: Long, val result: CompletableFuture<Unit>) {
        var approved = false
        var observed = false
        lateinit var timeout: Runnable
    }

    private val main = Handler(Looper.getMainLooper())
    private val modeEvents = AtomicLong()
    private var topResumed = false
    private var pending: Pending? = null
    private var enteredTaskId: Int? = null
    private var reportedMaximized = false
    private var closed = false

    fun request(enter: Boolean): CompletionStage<Unit> {
        val result = CompletableFuture<Unit>()
        onMain {
            if (result.isDone) return@onMain
            try {
                check(Build.VERSION.SDK_INT >= 34) { "Task fullscreen requests require Android 14 or newer" }
                check(current()) { "Window Activity changed" }
                check(pending == null) { "A task window-mode change is already pending" }
                // WINDOWING_MODE_PINNED is PiP, not Android's lock-task/screen-pinning feature.
                check(activity.isInPictureInPictureMode || (topResumed && activity.hasWindowFocus())) {
                    "Task fullscreen requests require the top-resumed focused Activity or picture-in-picture"
                }
                if (enter && enteredTaskId == activity.taskId && !activity.isInMultiWindowMode) {
                    result.complete(Unit)
                    return@onMain
                }
                if (!enter) check(enteredTaskId == activity.taskId) {
                    "This task has no confirmed API fullscreen entry to restore"
                }
                val issued = Pending(enter, modeEvents.get(), result)
                pending = issued
                issued.timeout = Runnable {
                    if (pending === issued) result.completeExceptionally(
                        TimeoutException("Android did not confirm the requested task window mode"))
                }
                result.whenComplete { _, _ -> onMain {
                    main.removeCallbacks(issued.timeout)
                    if (pending === issued) pending = null
                } }
                main.postDelayed(issued.timeout, 5_000)
                activity.requestFullscreenMode(
                    if (enter) Activity.FULLSCREEN_MODE_REQUEST_ENTER else Activity.FULLSCREEN_MODE_REQUEST_EXIT,
                    object : OutcomeReceiver<Void, Throwable> {
                        override fun onResult(result: Void?) = onMain approval@{
                            if (!active(issued)) return@approval
                            issued.approved = true
                            acknowledge(issued)
                        }
                        override fun onError(error: Throwable) = onMain {
                            if (pending === issued) issued.result.completeExceptionally(error)
                        }
                    },
                )
            } catch (error: Throwable) {
                result.completeExceptionally(error)
            }
        }
        return result
    }

    fun onTopResumedActivityChanged(topResumed: Boolean) = onMain {
        this.topResumed = topResumed
        if (!current()) pending?.result?.completeExceptionally(IllegalStateException("Window Activity changed"))
    }

    /** Call only from the Activity's configuration/multi-window mode callbacks. */
    fun onWindowModeChanged() {
        val event = modeEvents.incrementAndGet()
        onMain {
            if (!current()) {
                pending?.result?.completeExceptionally(IllegalStateException("Window Activity changed"))
                return@onMain
            }
            val multiWindow = activity.isInMultiWindowMode
            if (multiWindow && enteredTaskId != null) {
                enteredTaskId = null
                reportState(false)
            }
            val issued = pending ?: return@onMain
            if (event > issued.eventAtStart) {
                issued.observed = multiWindow != issued.enter
                acknowledge(issued)
            }
        }
    }

    /** Import only a trusted Registry proof of a completed entry on this same task.
     * Activity recreation does not erase the system's task-scoped restore history.
     */
    fun adoptConfirmedEntry(taskId: Int): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (Build.VERSION.SDK_INT < 34 || !current() || pending != null || taskId != activity.taskId ||
            activity.isInMultiWindowMode) return false
        enteredTaskId = taskId
        reportState(true)
        return true
    }

    private fun current(): Boolean = !closed && isCurrent() && !activity.isFinishing && !activity.isDestroyed

    private fun active(issued: Pending): Boolean {
        if (pending !== issued || issued.result.isDone) return false
        if (!current()) {
            issued.result.completeExceptionally(IllegalStateException("Window Activity changed"))
            return false
        }
        return true
    }

    private fun acknowledge(issued: Pending) {
        if (!active(issued) || !issued.approved || !issued.observed ||
            activity.isInMultiWindowMode == issued.enter) return
        enteredTaskId = if (issued.enter) activity.taskId else null
        try {
            reportState(issued.enter)
            issued.result.complete(Unit)
        } catch (error: Throwable) {
            issued.result.completeExceptionally(error)
        }
    }

    private fun reportState(maximized: Boolean) {
        if (reportedMaximized == maximized) return
        reportedMaximized = maximized
        onStateChanged(maximized)
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post(action)
    }

    override fun close() = onMain {
        if (!closed) {
            closed = true
            pending?.result?.completeExceptionally(CancellationException("Window task-mode controller closed"))
            // The API has no cancel/rollback operation. Do not publish a fictitious OS exit.
            enteredTaskId = null
        }
    }
}
