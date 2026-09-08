/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.ui

import android.view.ViewTreeObserver
import android.view.Window
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** One Activity's presentation binding. Does not own or mutate DOM fullscreen. */
internal class AndroidWindowFullscreenPresentation(
    private val window: Window,
    private val isCurrent: () -> Boolean,
    private val matchesRequest: (Boolean, Boolean) -> Boolean,
    private val onApplied: (Boolean) -> Unit,
) : AutoCloseable {
    private data class Request(val windowFullscreen: Boolean, val pageFullscreen: Boolean) {
        val immersive get() = windowFullscreen || pageFullscreen
    }

    private val view get() = window.decorView
    private val controller = WindowInsetsControllerCompat(window, window.decorView)
    private var request: Request? = null
    private var observer: ViewTreeObserver? = null
    private var preDraw: ViewTreeObserver.OnPreDrawListener? = null
    private var frame: Runnable? = null
    private var closed = false

    /** Called from Compose SideEffect, after the matching toolbar composition committed. */
    fun render(windowFullscreen: Boolean, pageFullscreen: Boolean) {
        if (closed || !isCurrent()) return
        val next = Request(windowFullscreen, pageFullscreen)
        if (request == next) return
        clearObservation()
        request = next
        applySystemBars(next.immersive)
        val listener = ViewTreeObserver.OnPreDrawListener {
            if (closed || request !== next) return@OnPreDrawListener true
            if (!current(next)) {
                clearObservation()
            } else if (frame == null && barsMatch(next.immersive)) {
                // A hide()/show() call is not an acknowledgement. The actual
                // Insets must match at pre-draw and after that frame handoff.
                val completion = Runnable {
                    if (closed || request !== next) return@Runnable
                    frame = null
                    if (current(next) && barsMatch(next.immersive)) {
                        clearObservation()
                        onApplied(next.windowFullscreen)
                    } else if (!current(next)) {
                        clearObservation()
                    }
                }
                frame = completion
                view.postOnAnimation(completion)
            }
            true
        }
        preDraw = listener
        observer = view.viewTreeObserver.also { it.addOnPreDrawListener(listener) }
        view.postInvalidateOnAnimation()
    }

    private fun current(expected: Request): Boolean = !closed && request === expected &&
        isCurrent() && matchesRequest(expected.windowFullscreen, expected.pageFullscreen)

    private fun barsMatch(immersive: Boolean): Boolean {
        if (!view.isAttachedToWindow) return false
        val insets = ViewCompat.getRootWindowInsets(view) ?: return false
        val status = insets.isVisible(WindowInsetsCompat.Type.statusBars())
        val navigation = insets.isVisible(WindowInsetsCompat.Type.navigationBars())
        // Check each bar: !isVisible(systemBars()) is true even if only one hid.
        return if (immersive) !status && !navigation else status && navigation
    }

    private fun applySystemBars(immersive: Boolean) {
        if (immersive) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun clearObservation() {
        frame?.let(view::removeCallbacks)
        frame = null
        preDraw?.let { listener ->
            (observer?.takeIf { it.isAlive } ?: view.viewTreeObserver).removeOnPreDrawListener(listener)
        }
        preDraw = null
        observer = null
    }

    override fun close() {
        if (closed) return
        clearObservation()
        if (request?.immersive == true && isCurrent()) applySystemBars(false)
        closed = true
        request = null
    }
}
