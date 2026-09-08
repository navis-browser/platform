/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.engine.runtime

/** Raw top-level progress facts. Zero generation means overlapping, uncorrelated events. */
internal data class EngineLoadResult(
    val generation: Long,
    val status: Int?,
    val cancelled: Boolean,
    val errorPage: Boolean,
    val uri: String,
) {
    // nsresult uses the high bit for failure, not HTTP status codes. In particular
    // a rendered HTTP 404/500 can be a successful document navigation.
    val succeeded: Boolean
        get() = generation > 0 && status != null && status >= 0 && !cancelled && !errorPage

    val failureCode: Int?
        get() = status?.takeIf { generation > 0 && it < 0 && !cancelled }
}
