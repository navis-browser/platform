/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.extensions

import android.content.Context
import android.widget.FrameLayout
import org.mozilla.gecko.navis.NavisAndroidPopupSurface
import org.navis.browser.engine.extensions.EngineExtensionPopup

/** Compose bridge for one engine-owned remote moz-extension popup window. */
internal class AndroidExtensionPopupView(
    context: Context,
    popup: EngineExtensionPopup,
    onDismissed: () -> Unit,
    onLoaded: () -> Unit,
    onFailed: (ExtensionPopupFailureStage?) -> Unit,
) : FrameLayout(context) {
    val targetToken: String = popup.targetToken

    private val surface = NavisAndroidPopupSurface(
        context,
        popup.extensionId,
        popup.targetToken,
        popup.popupUri,
        popup.privateMode,
        Runnable { onDismissed() },
        Runnable { onLoaded() },
        java.util.function.Consumer { code ->
            onFailed(ExtensionPopupFailureStage.fromEngineCode(code))
        },
    )

    init {
        addView(surface, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun release() {
        surface.release()
        removeAllViews()
    }
}
