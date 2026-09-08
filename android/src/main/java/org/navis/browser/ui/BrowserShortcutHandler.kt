/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import org.navis.browser.BrowserKeyboardAction
import org.navis.browser.MainActivity
import org.navis.browser.engine.AndroidWindowRuntime

/** A UI scope, not a second runtime or a global active-window dispatch target. */
@Composable
internal fun BrowserShortcutHandler(runtime: AndroidWindowRuntime, handle: (BrowserKeyboardAction) -> Boolean) {
    val context = LocalContext.current
    val activity = remember(context) { context.shortcutActivity() }
    val current by rememberUpdatedState(handle)
    DisposableEffect(activity, runtime) {
        val binding = activity?.bindBrowserKeyboardShortcuts(runtime) { current(it) }
        onDispose { binding?.close() }
    }
}

private tailrec fun Context.shortcutActivity(): MainActivity? = when (this) {
    is MainActivity -> this
    is ContextWrapper -> if (baseContext === this) null else baseContext.shortcutActivity()
    else -> null
}
