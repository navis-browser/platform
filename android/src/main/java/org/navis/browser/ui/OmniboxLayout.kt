/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.ui

internal data class OmniboxLayout(val identityWidth: Float, val inlineReload: Boolean)

internal fun omniboxLayout(
    widthDp: Float,
    fontScale: Float,
    organizationWidthDp: Float?,
    allowInlineReload: Boolean = true,
): OmniboxLayout {
    val scale = fontScale.coerceAtLeast(1f)
    val addressSpace = 144f * scale
    val preferredIdentity = organizationWidthDp?.let { (it + 16f).coerceAtLeast(48f) } ?: 48f
    // Keep the reload action's existing compact admission budget; its label
    // uses the actual text width rather than that action-layout threshold.
    val reloadIdentitySpace = preferredIdentity.coerceAtMost(112f * scale)
    val inlineReload = allowInlineReload && widthDp >= reloadIdentitySpace + addressSpace + 48f
    val remaining = widthDp - addressSpace - if (inlineReload) 48f else 0f
    return OmniboxLayout(
        identityWidth = preferredIdentity.coerceAtMost(remaining.coerceAtLeast(48f)),
        inlineReload = inlineReload,
    )
}
