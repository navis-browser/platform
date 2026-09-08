/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier

/** Keeps Navis-owned chrome below status bars, caption bars and display cutouts. */
@Composable
internal fun Modifier.navisTopBarInsets(): Modifier = background(MaterialTheme.colorScheme.surfaceContainer).windowInsetsPadding(
    WindowInsets.safeDrawing.only(WindowInsetsSides.Top),
)

/** Apply to the header's content, not its Surface: its background stays full width. */
@Composable
internal fun Modifier.navisTopBarContentInsets(): Modifier = windowInsetsPadding(
    WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
)

/** Scaffold already includes system insets in this padding. Descendants must not
 * add them a second time; consume exactly the applied padding, not all IME insets.
 */
internal fun Modifier.navisPageContentPadding(contentPadding: PaddingValues): Modifier =
    padding(contentPadding).consumeWindowInsets(contentPadding)

/** Resize the whole native page, including its SnackbarHost, above the keyboard.
 * Consumed IME insets prevent descendant navigation-bar padding from doubling it.
 * Gecko-backed content owns its own viewport and must not use this modifier.
 */
internal fun Modifier.navisNativePageInsets(): Modifier = imePadding()
