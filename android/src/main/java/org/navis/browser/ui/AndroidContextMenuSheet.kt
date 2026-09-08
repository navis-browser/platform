/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.navis.browser.R
import org.navis.browser.api.BrowserStateObserver
import org.navis.browser.api.SessionMode
import org.navis.browser.engine.AndroidWindowRuntime
import org.navis.browser.engine.AndroidContextMenuItem
import org.navis.browser.engine.AndroidContextMenuRequest

/** Material actions and extension items share one engine-owned context token. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AndroidContextMenuSheet(
    runtime: AndroidWindowRuntime,
    request: AndroidContextMenuRequest,
    onDismiss: () -> Unit,
    onInspect: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val dismiss by rememberUpdatedState(onDismiss)
    var state by remember(runtime) { mutableStateOf(runtime.state) }
    val original = remember(request.sessionId, request.token) { runtime.state.activeSession }
    val gate = remember(request.sessionId, request.token) {
        ContextMenuActionGate(request.sessionId, request.token,
            original?.navigation?.url.orEmpty(), original?.navigation?.revision ?: -1)
    }
    DisposableEffect(runtime, request.sessionId, request.token) {
        val observer = BrowserStateObserver {
            state = it
            if (!gate.matches(it)) {
                runtime.respondToContextMenu(request, null)
                dismiss()
            }
        }
        runtime.addObserver(observer)
        onDispose {
            runtime.removeObserver(observer)
            runtime.respondToContextMenu(request, null)
        }
    }
    val failedLabel = stringResource(R.string.context_action_failed)
    val copiedLabel = stringResource(R.string.context_copied)
    fun ordinary(action: () -> Unit) {
        val accepted = gate.claim(runtime.state) { runtime.respondToContextMenu(request, null) }
        dismiss()
        if (accepted) runCatching(action).onFailure {
            Toast.makeText(context, failedLabel, Toast.LENGTH_SHORT).show()
        }
    }
    fun copyText(value: String) {
        val text = ContextMenuActionPolicy.copyableText(value) ?: return
        val copied = runCatching {
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                ClipData.newPlainText("Navis", text),
            )
        }.isSuccess
        Toast.makeText(context, if (copied) copiedLabel else failedLabel, Toast.LENGTH_SHORT).show()
    }
    fun copyAddress(value: String) = ordinary {
        runtime.product.copyLink(value) { result ->
            if (gate.matches(runtime.state)) {
                result.onSuccess(::copyText).onFailure {
                    Toast.makeText(context, failedLabel, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    val link = request.linkUrl.takeIf { request.onLink }
    val openableLink = link?.let(ContextMenuActionPolicy::openableWebAddress)
    val media = request.srcUrl.takeIf { request.onImage || request.onAudio || request.onVideo }
    val openableMedia = media?.let(ContextMenuActionPolicy::openableWebAddress)
    val selection = request.selectionText.takeIf { request.isTextSelected && it.isNotBlank() }
    val pageActions = request.pageActions.filter { contextPageActionLabel(it.id) != null }
    val nativeCopy = pageActions.any { it.id == "copy" }
    val navigation = state.activeSession?.navigation
    val live = gate.matches(state)
    val linkItemCount = (if (openableLink != null) 2 else 0) + 1 +
        (if (request.linkText.isNotBlank()) 1 else 0)
    val mediaItemCount = if (openableMedia != null) 2 else 1
    val selectionItemCount = if (nativeCopy) 1 else 2
    val navigationItemCount = if (link == null) 4 else 3
    val extensionGroups = remember(request.items) { contextMenuGroupPositions(request.items) }
    val byId = request.items.associateBy { it.id }
    val parents = request.items.mapNotNull { it.parentId }.toSet()
    fun depth(item: AndroidContextMenuItem): Int {
        val seen = mutableSetOf(item.id)
        var current = item.parentId
        var count = 0
        while (current != null && count < 16 && seen.add(current)) {
            count++
            current = byId[current]?.parentId
        }
        return count
    }
    ModalBottomSheet(
        onDismissRequest = {
            runtime.respondToContextMenu(request, null)
            dismiss()
        },
    ) {
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            item {
                Text(text = link?.takeIf(String::isNotBlank) ?: media?.takeIf(String::isNotBlank) ?: request.pageUrl,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    maxLines = 3, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall)
            }
            if (link != null) {
                if (openableLink != null) {
                    item { ContextMenuRow(stringResource(R.string.context_open_link_tab), enabled = live,
                        itemIndex = 0, itemCount = linkItemCount) {
                        ordinary { runtime.createSession(original?.mode ?: SessionMode.NORMAL, openableLink) }
                    } }
                    item { ContextMenuRow(stringResource(R.string.context_open_link_private), enabled = live,
                        itemIndex = 1, itemCount = linkItemCount) {
                        // Privacy belongs to the product window. The existing window
                        // transaction owns async failure feedback after this menu closes.
                        ordinary { runtime.openSession(SessionMode.PRIVATE, openableLink) }
                    } }
                }
                item { ContextMenuRow(stringResource(R.string.context_menu_copy_link), enabled = live && link.isNotBlank(),
                    itemIndex = if (openableLink != null) 2 else 0, itemCount = linkItemCount) {
                    copyAddress(link)
                } }
                if (request.linkText.isNotBlank()) item {
                    ContextMenuRow(stringResource(R.string.context_copy_link_text), enabled = live,
                        itemIndex = linkItemCount - 1, itemCount = linkItemCount) {
                        ordinary { copyText(request.linkText) }
                    }
                }
                item { HorizontalDivider() }
            }
            if (media != null && media.isNotBlank()) {
                if (openableMedia != null) item {
                    ContextMenuRow(stringResource(if (request.onImage) R.string.context_open_image_tab
                        else R.string.context_open_media_tab), enabled = live,
                        itemIndex = 0, itemCount = mediaItemCount) {
                        ordinary { runtime.createSession(original?.mode ?: SessionMode.NORMAL, openableMedia) }
                    }
                }
                item { ContextMenuRow(stringResource(if (request.onImage) R.string.context_copy_image_address
                    else R.string.context_copy_media_address), enabled = live,
                    itemIndex = mediaItemCount - 1, itemCount = mediaItemCount) { copyAddress(media) } }
                item { HorizontalDivider() }
            }
            if (selection != null) {
                if (!nativeCopy) item { ContextMenuRow(stringResource(R.string.context_copy_selection), enabled = live,
                    itemIndex = 0, itemCount = selectionItemCount) {
                    ordinary { copyText(selection) }
                } }
                item { ContextMenuRow(stringResource(R.string.context_search_selection,
                    runtime.product.settings.snapshot.searchProvider.name), enabled = live,
                    itemIndex = selectionItemCount - 1, itemCount = selectionItemCount) {
                    ordinary {
                        runtime.product.resolveAddress(selection, request.sessionId, forceSearch = true) { result ->
                            result.onSuccess { url ->
                                original?.let { source ->
                                    if (runtime.state.sessions.any { it.id == source.id &&
                                        it.navigation.revision == source.navigation.revision }) {
                                        runtime.createSession(source.mode, url)
                                    }
                                }
                            }.onFailure { Toast.makeText(context, R.string.operation_failed, Toast.LENGTH_SHORT).show() }
                        }
                    }
                } }
                item { HorizontalDivider() }
            }
            itemsIndexed(pageActions, key = { _, action -> "navis-context:${action.id}" }) { index, action ->
                ContextMenuRow(stringResource(checkNotNull(contextPageActionLabel(action.id))),
                    enabled = live && action.enabled, itemIndex = index, itemCount = pageActions.size) {
                    // The engine validates the original actor and DOM target before execution.
                    val accepted = gate.matches(runtime.state)
                    dismiss()
                    if (accepted) gate.claim(runtime.state) {
                        runtime.respondToContextMenu(request, "navis-context:${action.id}")
                    }
                }
            }
            if (pageActions.isNotEmpty()) item { HorizontalDivider() }
            item { ContextMenuRow(stringResource(R.string.context_back), enabled = live && navigation?.canGoBack == true,
                itemIndex = 0, itemCount = navigationItemCount) {
                ordinary { runtime.goBack(request.sessionId) }
            } }
            item { ContextMenuRow(stringResource(R.string.context_forward), enabled = live && navigation?.canGoForward == true,
                itemIndex = 1, itemCount = navigationItemCount) {
                ordinary { runtime.goForward(request.sessionId) }
            } }
            item { ContextMenuRow(stringResource(R.string.context_reload), enabled = live,
                itemIndex = 2, itemCount = navigationItemCount) {
                ordinary { runtime.reload(request.sessionId) }
            } }
            if (link == null) item {
                ContextMenuRow(stringResource(R.string.context_copy_page_address), enabled = live,
                    itemIndex = 3, itemCount = navigationItemCount) {
                    copyAddress(original?.navigation?.url.orEmpty())
                }
            }
            if (request.items.isNotEmpty()) item { HorizontalDivider() }
            itemsIndexed(request.items, key = { _, item -> "${item.extensionId}:${item.id}" }) { index, item ->
                if (item.type == "separator") HorizontalDivider() else {
                    ContextMenuRow(item.title,
                        description = stringResource(R.string.context_menu_extension_description, item.extensionName, item.title),
                        enabled = live && item.enabled && item.id !in parents,
                        indent = depth(item),
                        itemIndex = extensionGroups[index].first,
                        itemCount = extensionGroups[index].second,
                        checked = item.checked.takeIf { item.type == "checkbox" },
                        selected = item.checked.takeIf { item.type == "radio" },
                    ) {
                        val accepted = gate.matches(runtime.state)
                        dismiss()
                        if (accepted) runtime.respondToContextMenu(request, item.id)
                    }
                }
            }
            if (onInspect != null && ContextMenuActionPolicy.openableWebAddress(original?.navigation?.url.orEmpty()) != null) {
                item { HorizontalDivider() }
                item { ContextMenuRow(stringResource(R.string.context_inspect_page), enabled = live) {
                    ordinary(onInspect)
                } }
            }
        }
    }
}

private fun contextPageActionLabel(id: String): Int? = when (id) {
    "play-media" -> R.string.context_play_media
    "pause-media" -> R.string.context_pause_media
    "mute-media" -> R.string.context_mute_media
    "unmute-media" -> R.string.context_unmute_media
    "show-media-controls" -> R.string.context_show_media_controls
    "hide-media-controls" -> R.string.context_hide_media_controls
    "undo" -> R.string.context_undo
    "redo" -> R.string.context_redo
    "cut" -> R.string.context_cut
    "copy" -> R.string.context_copy_selection
    "paste" -> R.string.context_paste
    "select-all" -> R.string.context_select_all
    else -> null
}

/** Separators delimit the official first/middle/last menu shapes. */
private fun contextMenuGroupPositions(items: List<AndroidContextMenuItem>): List<Pair<Int, Int>> {
    val positions = MutableList(items.size) { 0 to 1 }
    var start = 0
    for (end in 0..items.size) {
        if (end == items.size || items[end].type == "separator") {
            for (index in start until end) positions[index] = index - start to end - start
            start = end + 1
        }
    }
    return positions
}

@Composable
private fun ContextMenuRow(
    title: String,
    enabled: Boolean,
    description: String = title,
    indent: Int = 0,
    itemIndex: Int = 0,
    itemCount: Int = 1,
    checked: Boolean? = null,
    selected: Boolean? = null,
    onClick: () -> Unit,
) {
    NavisMenuItem(
        text = { Text(title, modifier = Modifier.padding(start = (indent * 16).dp)) },
        enabled = enabled,
        onClick = onClick,
        itemIndex = itemIndex,
        itemCount = itemCount,
        checked = checked,
        selected = selected,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = description },
    )
}
