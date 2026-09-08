/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key

/** A new query/directory starts at its first result. Ordinary store refreshes
 * keep the same state and stable-item anchors; no asynchronous scroll can race
 * with a newer query. Call outside empty/result branches to retain that state.
 */
@Composable
internal fun rememberProductListState(listKey: Any?, query: String): LazyListState =
    key(listKey, query) { rememberLazyListState() }
