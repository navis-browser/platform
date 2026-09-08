/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.ui

import android.os.Looper
import android.view.Window
import android.view.WindowManager.LayoutParams
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Scoped window adjustment; ordinary browsing retains its own keyboard policy. */
@Suppress("DEPRECATION") // Legacy adjustment prevents OEM window panning; insets are handled by Compose.
internal class AndroidDevToolsWindowPolicy private constructor(
    window: Window,
    private val originalAdjustment: Int,
) : AutoCloseable {
    private val owner = WeakReference(window)
    private var closed = false

    override fun close() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (closed) return
        closed = true
        val window = owner.get() ?: return
        if (active[window] !== this) return
        active.remove(window)
        val current = window.attributes.softInputMode
        // A separate owner may have explicitly changed adjustment meanwhile.
        if (current and LayoutParams.SOFT_INPUT_MASK_ADJUST == LayoutParams.SOFT_INPUT_ADJUST_RESIZE) {
            window.setSoftInputMode((current and LayoutParams.SOFT_INPUT_MASK_ADJUST.inv()) or originalAdjustment)
        }
    }

    companion object {
        private val active = WeakHashMap<Window, AndroidDevToolsWindowPolicy>()

        fun acquire(window: Window): AndroidDevToolsWindowPolicy {
            check(Looper.myLooper() == Looper.getMainLooper())
            val current = window.attributes.softInputMode
            val adjustment = current and LayoutParams.SOFT_INPUT_MASK_ADJUST
            val original = active[window]?.originalAdjustment
                ?.takeIf { adjustment == LayoutParams.SOFT_INPUT_ADJUST_RESIZE } ?: adjustment
            return AndroidDevToolsWindowPolicy(window, original).also { lease ->
                active[window] = lease
                window.setSoftInputMode((current and LayoutParams.SOFT_INPUT_MASK_ADJUST.inv()) or
                    LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }
        }
    }
}
