/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import org.navis.browser.MainActivity
import org.navis.browser.engine.AndroidWindowRuntime

/** Shared presentation for webpage and browser fullscreen; one system-bar writer. */
@Composable
internal fun BrowserWindowFullscreen(
    runtime: AndroidWindowRuntime,
    windowFullscreen: Boolean,
    pageFullscreen: Boolean,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.fullscreenActivity() }
    val lease = activity?.browserWindowLease(runtime)
    val presentation = remember(activity, runtime, lease) {
        if (activity == null || lease == null) null else AndroidWindowFullscreenPresentation(
            activity.window,
            isCurrent = { activity.browserWindowLease(runtime) == lease },
            matchesRequest = { window, page ->
                runtime.state.windowFullscreen == window &&
                    (runtime.targetState.fullscreenSessionId != null) == page
            },
            onApplied = { requested -> runtime.reportWindowFullscreenApplied(lease, requested) },
        )
    }
    DisposableEffect(presentation) { onDispose { presentation?.close() } }
    SideEffect { presentation?.render(windowFullscreen, pageFullscreen) }
}

private tailrec fun Context.fullscreenActivity(): MainActivity? = when (this) {
    is MainActivity -> this
    is ContextWrapper -> if (baseContext === this) null else baseContext.fullscreenActivity()
    else -> null
}
