/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package org.navis.browser.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Official M3 Expressive popup, positioning and motion; callers keep their action ownership.
 * Set [grouped] only when [content] supplies its own [NavisMenuGroup]s.
 */
@Composable
internal fun NavisDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    grouped: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    // Unlike the legacy DropdownMenu, DropdownMenuPopup does not scroll its content.
    // The upstream popup already chooses the widest intrinsic child width; Foundation
    // receives its bounded window height before allowing the content to scroll.
    DropdownMenuPopup(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier.verticalScroll(rememberScrollState()),
    ) {
        // Group shadows must stay inside both the scroll viewport and the upstream
        // popup animation layer. Outer padding would still clip their drawing.
        Column(Modifier.padding(8.dp)) {
            if (grouped) content() else NavisMenuGroup(content = content)
        }
    }
}

/** Group position selects the upstream leading/middle/trailing corner treatment. */
@Composable
internal fun NavisMenuGroup(
    index: Int = 0,
    count: Int = 1,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    require(count > 0 && index in 0 until count)
    if (index > 0) Spacer(Modifier.height(MenuDefaults.GroupSpacing))
    DropdownMenuGroup(
        shapes = MenuDefaults.groupShape(index, count),
        // Fill the popup's intrinsic width, not the screen; also aligns label-only groups.
        modifier = modifier.fillMaxWidth(),
        content = content,
    )
}

/** Single click owner; upstream owns disabled, selectable/toggleable semantics and ripple.
 * [selected] is a radio choice, [checked] a checkbox. Both null is an ordinary action.
 */
@Composable
internal fun NavisMenuItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    selected: Boolean? = null,
    checked: Boolean? = null,
    itemIndex: Int = 0,
    itemCount: Int = 1,
) {
    require(selected == null || checked == null)
    require(itemCount > 0 && itemIndex in 0 until itemCount)
    val shapes = MenuDefaults.itemShape(itemIndex, itemCount)
    val touchTarget = modifier.heightIn(min = 48.dp)
    when {
        checked != null -> DropdownMenuItem(
            checked = checked,
            onCheckedChange = { onClick() },
            text = text,
            shapes = shapes,
            modifier = touchTarget,
            leadingIcon = leadingIcon,
            checkedLeadingIcon = { NavisMenuSelectionIcon() },
            trailingIcon = trailingIcon,
            enabled = enabled,
        )
        selected != null -> DropdownMenuItem(
            selected = selected,
            onClick = onClick,
            text = text,
            shapes = shapes,
            modifier = touchTarget,
            leadingIcon = leadingIcon,
            selectedLeadingIcon = { NavisMenuSelectionIcon() },
            trailingIcon = trailingIcon,
            enabled = enabled,
        )
        else -> DropdownMenuItem(
            onClick = onClick,
            text = text,
            shape = shapes.shape,
            modifier = touchTarget,
            leadingIcon = leadingIcon,
            trailingIcon = trailingIcon,
            enabled = enabled,
        )
    }
}

@Composable
private fun NavisMenuSelectionIcon() {
    Icon(Icons.Default.Check, contentDescription = null,
        modifier = Modifier.size(MenuDefaults.LeadingIconSize))
}
