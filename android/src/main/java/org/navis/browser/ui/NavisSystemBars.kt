/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

@Composable
internal fun NavisSystemBars(statusBackground: Color, navigationBackground: Color) {
    val view = LocalView.current
    if (view.isInEditMode) return
    val activity = view.context.activityWindowOwner() ?: return
    SideEffect {
        WindowCompat.getInsetsController(activity.window, view).apply {
            isAppearanceLightStatusBars = statusBackground.luminance() > .5f
            isAppearanceLightNavigationBars = navigationBackground.luminance() > .5f
        }
    }
}

private fun Context.activityWindowOwner(): Activity? {
    var current: Context = this
    repeat(16) {
        val next = when (val owner = current) {
            is Activity -> return owner
            is ContextWrapper -> owner.baseContext
            else -> return null
        }
        if (next === current) return null
        current = next
    }
    return null
}
