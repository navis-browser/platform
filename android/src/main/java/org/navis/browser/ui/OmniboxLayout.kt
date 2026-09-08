/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.ui

internal data class OmniboxLayout(val identityWidth: Float, val inlineReload: Boolean)

internal fun omniboxLayout(
    widthDp: Float,
    fontScale: Float,
    hasOrganization: Boolean,
    allowInlineReload: Boolean = true,
): OmniboxLayout {
    val scale = fontScale.coerceAtLeast(1f)
    val addressSpace = 144f * scale
    val preferredIdentity = if (hasOrganization) 112f * scale else 48f
    val inlineReload = allowInlineReload && widthDp >= preferredIdentity + addressSpace + 48f
    val remaining = widthDp - addressSpace - if (inlineReload) 48f else 0f
    return OmniboxLayout(
        identityWidth = if (hasOrganization) remaining.coerceIn(48f, preferredIdentity) else 48f,
        inlineReload = inlineReload,
    )
}
