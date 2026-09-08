/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.ui

import android.content.res.Configuration
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.navis.browser.R
import org.navis.browser.BuildConfig
import org.navis.browser.extensions.ExtensionActionSnapshot
import org.navis.browser.extensions.ExtensionHost
import org.navis.browser.extensions.ExtensionStateObserver
import org.navis.browser.extensions.ExtensionActionFailure

/** Presentation selected from the host window, kept separate from the Compose tree for tests. */
internal enum class ExtensionActionSurfaceMode {
    PORTRAIT_OVERFLOW,
    LANDSCAPE_PINNED_ROW,
}

internal fun extensionActionSurfaceMode(isLandscape: Boolean): ExtensionActionSurfaceMode =
    if (isLandscape) {
        ExtensionActionSurfaceMode.LANDSCAPE_PINNED_ROW
    } else {
        ExtensionActionSurfaceMode.PORTRAIT_OVERFLOW
    }

/** The toolbar never exposes an unpinned action as a browser affordance. */
internal fun pinnedExtensionActions(actions: List<ExtensionActionSnapshot>): List<ExtensionActionSnapshot> =
    actions.filter(ExtensionActionSnapshot::pinned)

/**
 * Adaptive extension entry point. Portrait keeps the toolbar compact and puts actions behind
 * the three-dot menu. Landscape exposes the pinned row, while retaining that same menu as a
 * reliable overflow/fallback entry (including when no action is currently pinned).
 */
@Composable
internal fun ExtensionActionToolbar(host: ExtensionHost) {
    val actions = rememberPinnedExtensionActions(host)
    if (LocalConfiguration.current.orientation != Configuration.ORIENTATION_LANDSCAPE || actions.isEmpty()) return
    Row(
        modifier = Modifier.widthIn(max = 144.dp).horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        actions.forEach { ExtensionActionButton(it, host::invokeExtensionAction) }
    }
}

/** The ordinary browser menu is the portrait home and landscape overflow for pinned actions. */
@Composable
internal fun ExtensionPinnedMenuItems(host: ExtensionHost, onDismiss: () -> Unit) {
    ExtensionPinnedMenuItems(rememberPinnedExtensionActions(host), host, onDismiss)
}

@Composable
internal fun ExtensionPinnedMenuItems(
    actions: List<ExtensionActionSnapshot>,
    host: ExtensionHost,
    onDismiss: () -> Unit,
) {
    actions.forEachIndexed { index, action ->
        NavisMenuItem(
            text = { Text(action.title.ifBlank { action.extensionName }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = {
                Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                    if (action.icon != null) Image(action.icon.asImageBitmap(), null, Modifier.size(24.dp))
                    else Text(action.extensionName.take(1).uppercase(), style = MaterialTheme.typography.labelLarge)
                }
            },
            trailingIcon = {
                if (action.badgeText.isNotEmpty()) Text(action.badgeText, style = MaterialTheme.typography.labelSmall)
            },
            enabled = action.enabled,
            itemIndex = index, itemCount = actions.size,
            onClick = { onDismiss(); host.invokeExtensionAction(action.extensionId) },
        )
    }
}

@Composable
internal fun rememberPinnedExtensionActions(host: ExtensionHost): List<ExtensionActionSnapshot> {
    var state by remember(host) { mutableStateOf(host.extensionState) }
    DisposableEffect(host) {
        val observer = ExtensionStateObserver { state = it }
        host.addExtensionObserver(observer)
        onDispose { host.removeExtensionObserver(observer) }
    }
    return pinnedExtensionActions(state.actions)
}

@Composable
private fun ExtensionActionButton(
    action: ExtensionActionSnapshot,
    onInvoke: (String) -> Unit,
) {
    Box(contentAlignment = Alignment.Center) {
        IconButton(
            onClick = { onInvoke(action.extensionId) },
            enabled = action.enabled,
            modifier = Modifier.semantics { contentDescription = action.title },
        ) {
            if (action.icon != null) {
                Image(
                    bitmap = action.icon.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
            } else {
                Text(
                    text = action.extensionName.take(1).uppercase(),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        if (action.badgeText.isNotBlank()) {
            Text(
                text = action.badgeText,
                color = action.badgeTextColor?.let { Color(it) } ?: Color.White,
                fontSize = 8.sp,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 2.dp, end = 1.dp)
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(
                        action.badgeBackgroundColor?.let { Color(it) }
                            ?: MaterialTheme.colorScheme.error,
                    )
                    .padding(horizontal = 3.dp, vertical = 1.dp),
            )
        }
    }
}

/** Material container around the extension-owned HTML/CSS/JS popup document. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExtensionPopupSheet(host: ExtensionHost) {
    var state by remember(host) { mutableStateOf(host.extensionState) }
    val observer = remember(host) { ExtensionStateObserver { state = it } }

    DisposableEffect(host) {
        host.addExtensionObserver(observer)
        onDispose { host.removeExtensionObserver(observer) }
    }

    state.actionFailure?.let { failure ->
        AlertDialog(
            onDismissRequest = host::dismissExtensionActionFailure,
            title = { Text(stringResource(R.string.extension_action_failed_title)) },
            text = {
                Column {
                    Text(stringResource(if (failure == ExtensionActionFailure.POPUP_LOAD_FAILED)
                        R.string.extension_popup_load_failed else R.string.extension_action_invoke_failed))
                    if (BuildConfig.DEBUG) {
                        state.popupFailureStage?.let { stage ->
                            Text(stage.code, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = host::dismissExtensionActionFailure) {
                    Text(stringResource(R.string.done))
                }
            },
        )
    }
    val popup = state.popup
    if (popup == null && state.pendingActionId == null) return
    ModalBottomSheet(onDismissRequest = host::dismissExtensionPopup) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = popup?.title ?: state.extensions.firstOrNull { it.id == state.pendingActionId }?.name.orEmpty(),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = host::dismissExtensionPopup) {
                    Icon(Icons.Default.Close, stringResource(R.string.close_extension_popup))
                }
            }
            if (state.pendingActionId != null || state.popupLoading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(R.string.extension_popup_loading), Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
            }
            if (popup != null) key(popup.targetToken) {
                AndroidView(
                    factory = host::createExtensionPopupView,
                    onRelease = host::releaseExtensionPopupView,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(480.dp),
                )
            }
        }
    }
}
