/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser

import android.app.Activity
import org.navis.browser.engine.AndroidWindowAttention

/** Notification-click routing only. Never initializes or binds a Runtime, window or Session. */
open class WindowAttentionActivity : Activity() {
    private var dispatched = false

    override fun onResume() {
        super.onResume()
        if (dispatched) return
        dispatched = true
        try {
            AndroidWindowAttention.Activation.dispatch(this, intent).whenComplete { _, _ ->
                runOnUiThread { finish() }
            }
        } catch (_: Throwable) {
            finish()
        }
    }
}
