/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.engine

import android.content.Context
import android.os.Handler
import android.view.DragEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.FrameLayout
import org.navis.browser.api.BrowserView
import org.navis.browser.engine.runtime.EngineSessionPort

/** Product-owned Android render host backed directly by the engine compositor surface. */
internal class AndroidBrowserView(context: Context) : FrameLayout(context), BrowserView {
    private val sessionGate = ViewSessionGate<EngineSessionPort>(EngineSessionPort::isOpen)
    private val renderView = ContentSurfaceView(context)

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            attachCurrentSurface(holder)
        }

        override fun surfaceChanged(
            holder: SurfaceHolder,
            format: Int,
            width: Int,
            height: Int,
        ) {
            attachCurrentSurface(holder, width, height)
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            sessionGate.current()?.detachSurface()
        }
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        renderView.holder.addCallback(surfaceCallback)
        addView(
            renderView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
    }

    fun show(session: EngineSessionPort) {
        if (sessionGate.isBoundTo(session)) {
            refreshSurface()
            return
        }
        sessionGate.replace(session)?.let(::detachBoundSession)
        sessionGate.current()?.attachInputView(renderView, this)
        refreshSurface()
    }

    fun refreshSurface() {
        attachCurrentSurface(renderView.holder)
    }

    fun release() {
        sessionGate.clear()?.let(::detachBoundSession)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (!changed) {
            return
        }
        val location = IntArray(2)
        getLocationInWindow(location)
        sessionGate.current()?.updateBounds(location[0], location[1], width, height)
    }

    override fun onDetachedFromWindow() {
        release()
        super.onDetachedFromWindow()
    }

    private fun attachCurrentSurface(
        holder: SurfaceHolder,
        width: Int = renderView.width,
        height: Int = renderView.height,
    ) {
        val session = sessionGate.current() ?: return
        val surface = holder.surface
        if (!surface.isValid || width <= 0 || height <= 0) {
            return
        }
        session.attachSurface(display?.displayId ?: 0, 0, 0, width, height, surface)
        val location = IntArray(2)
        getLocationInWindow(location)
        session.updateBounds(location[0], location[1], width, height)
    }

    private fun detachBoundSession(session: EngineSessionPort) {
        session.detachInputView()
        session.detachSurface()
    }

    /**
     * The SurfaceView is Android's real event target. Keeping forwarding here
     * avoids relying on child-to-parent bubbling that Android does not promise.
     */
    private inner class ContentSurfaceView(context: Context) : SurfaceView(context) {
        init {
            isFocusable = true
            isFocusableInTouchMode = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        override fun getHandler(): Handler? {
            val defaultHandler = super.getHandler()
            return sessionGate.current()?.inputConnectionHandler(defaultHandler) ?: defaultHandler
        }

        override fun onCheckIsTextEditor(): Boolean = sessionGate.current() != null

        override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? =
            sessionGate.current()?.createInputConnection(outAttrs)

        override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
            return super.onKeyPreIme(keyCode, event) ||
                (sessionGate.current()?.onKeyPreIme(keyCode, event) == true)
        }

        override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
            val shortcut = event.toExtensionShortcut()
            val session = sessionGate.current()
            if (shortcut != null && session?.isHardwareShortcutOwned(shortcut) == true) {
                session.dispatchHardwareShortcut(shortcut)
                // A chord is consumed at the reliable key-down boundary. The
                // Gecko registry resolves conflicts and inactive/private tabs
                // before emitting onCommand; unmodified keys remain untouched.
                return true
            }
            return super.onKeyDown(keyCode, event) ||
                (sessionGate.current()?.onKeyDown(keyCode, event) == true)
        }

        override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
            super.onKeyUp(keyCode, event) ||
                (sessionGate.current()?.onKeyUp(keyCode, event) == true)

        override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean =
            super.onKeyLongPress(keyCode, event) ||
                (sessionGate.current()?.onKeyLongPress(keyCode, event) == true)

        override fun onKeyMultiple(
            keyCode: Int,
            repeatCount: Int,
            event: KeyEvent,
        ): Boolean = super.onKeyMultiple(keyCode, repeatCount, event) ||
            (sessionGate.current()?.onKeyMultiple(keyCode, repeatCount, event) == true)

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val session = sessionGate.current() ?: return false
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                requestFocus()
            }
            return session.onTouchEvent(event)
        }

        override fun onGenericMotionEvent(event: MotionEvent): Boolean =
            sessionGate.current()?.onGenericMotionEvent(event) == true

        override fun onDragEvent(event: DragEvent): Boolean =
            sessionGate.current()?.onDragEvent(event) == true
    }
}
