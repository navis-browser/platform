/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.navis.browser.R
import org.navis.browser.api.BrowserSessionState
import org.navis.browser.api.SessionMode
import org.navis.browser.persistence.BookmarkDraft
import org.navis.browser.persistence.BookmarkEntry

/** Mobile Speed Dial is a responsive view of the same persisted bookmark tree. */
@Composable
internal fun NativeNewTabSurface(
    privateMode: Boolean,
    bookmarks: List<BookmarkEntry>,
    showShortcuts: Boolean,
    sessions: List<BrowserSessionState>,
    onOpen: (String) -> Unit,
    onManageBookmarks: () -> Unit,
    onSaveBookmark: (BookmarkDraft, ProfileCompletion) -> Unit,
    backEnabled: Boolean = true,
) {
    var folderId by rememberSaveable { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf<BookmarkDraft?>(null) }
    val folder = SpeedDialPolicy.folder(bookmarks, folderId)
    val children = remember(bookmarks, folderId) { SpeedDialPolicy.children(bookmarks, folderId) }
    // A folder is a new list, not another slice at the previous folder's scroll offset.
    val gridState = key(folder?.id) { rememberLazyGridState() }
    val minimumCellWidth = SpeedDialPolicy.minimumCellWidthDp(LocalDensity.current.fontScale).dp
    BackHandler(enabled = backEnabled && showShortcuts && folder != null) { folderId = folder?.parentId }
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            val compactHeading = maxHeight < 480.dp
            LazyVerticalGrid(
                // Three columns fit a standard 360dp phone with the outer gutters;
                // wider screens add columns, and larger system fonts get wider cells.
                columns = GridCells.Adaptive(minSize = minimumCellWidth),
                state = gridState,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = if (compactHeading) 16.dp else 24.dp),
                modifier = Modifier.widthIn(max = 840.dp).fillMaxSize().navigationBarsPadding(),
            ) {
                item(key = "heading", span = { GridItemSpan(maxLineSpan) }) {
                    NewTabHeading(privateMode, compactHeading)
                }
                if (showShortcuts) {
                    item(key = "shortcut-heading", span = { GridItemSpan(maxLineSpan) }) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            if (folder != null) IconButton(onClick = { folderId = folder.parentId }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.profile_parent_folder))
                            }
                            Text(folder?.title ?: stringResource(R.string.newtab_shortcuts),
                                style = MaterialTheme.typography.titleMedium, maxLines = 1,
                                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            TextButton(onClick = onManageBookmarks) {
                                Text(stringResource(R.string.newtab_manage))
                            }
                        }
                    }
                    if (children.isEmpty()) {
                        item(key = "shortcut-empty", span = { GridItemSpan(maxLineSpan) }) {
                            Text(stringResource(if (folder == null) R.string.newtab_empty_shortcuts else R.string.newtab_empty_folder),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp))
                        }
                    }
                    items(children, key = { "bookmark:" + it.id }) { entry ->
                        val faviconSession = sessions.firstOrNull {
                            it.mode == SessionMode.NORMAL && it.navigation.faviconPng.isNotEmpty() &&
                                it.navigation.url.substringBefore('#') == entry.url.substringBefore('#')
                        }
                        SpeedDialTile(entry, faviconSession,
                            onOpen = { if (entry.isFolder) folderId = entry.id else onOpen(entry.url) },
                            onEdit = { draft = BookmarkDraft(entry.id, entry.title, entry.url, entry.parentId, entry.isFolder) },
                            onManage = onManageBookmarks)
                    }
                    item(key = "add-shortcut") {
                        ShortcutTile(label = stringResource(R.string.profile_add_bookmark),
                            onClick = { draft = BookmarkDraft(title = "", parentId = folder?.id) }) {
                            Icon(Icons.Default.Add, null, Modifier.size(32.dp))
                        }
                    }
                }
            }
        }
    }
    draft?.let { editing ->
        BookmarkEditor(editing, bookmarks, onDismiss = { draft = null }) { changed, done ->
            onSaveBookmark(changed) { result ->
                done(result)
                if (result.isSuccess) draft = null
            }
        }
    }
}

@Composable
private fun SpeedDialTile(
    entry: BookmarkEntry,
    faviconSession: BrowserSessionState?,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onManage: () -> Unit,
) {
    var menuOpen by remember(entry.id) { mutableStateOf(false) }
    Box {
        ShortcutTile(entry.title.ifBlank { entry.url }, onOpen, onLongClick = { menuOpen = true }) {
            when {
                entry.isFolder -> Icon(ProfileFolderIcon, null, Modifier.size(32.dp))
                faviconSession != null -> TabFavicon(faviconSession)
                else -> Text(SpeedDialPolicy.monogram(entry), style = MaterialTheme.typography.headlineSmall)
            }
        }
        NavisDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            NavisMenuItem(text = { Text(stringResource(R.string.profile_edit_move)) },
                leadingIcon = { Icon(Icons.Default.Edit, null) }, itemIndex = 0, itemCount = 2,
                onClick = { menuOpen = false; onEdit() })
            NavisMenuItem(text = { Text(stringResource(R.string.newtab_manage_bookmarks)) },
                leadingIcon = { Icon(ProfileFolderIcon, null) }, itemIndex = 1, itemCount = 2,
                onClick = { menuOpen = false; onManage() })
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun ShortcutTile(
    label: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    icon: @Composable () -> Unit,
) {
    val manageLabel = stringResource(R.string.profile_bookmark_actions)
    Column(horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .combinedClickable(role = Role.Button, onClick = onClick,
                onLongClickLabel = if (onLongClick != null) manageLabel else null, onLongClick = onLongClick)
            .padding(horizontal = 4.dp, vertical = 12.dp)) {
        Surface(color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            shape = RoundedCornerShape(20.dp), modifier = Modifier.size(64.dp)) {
            Box(contentAlignment = Alignment.Center) { icon() }
        }
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 2,
            minLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

@Composable
private fun NewTabHeading(
    privateMode: Boolean,
    compact: Boolean,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        if (compact) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NavisBrandMark(Modifier.size(40.dp), NavisBrandMotion.COUNTERFLOW)
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
            }
        } else {
            NavisBrandMark(Modifier.size(64.dp), NavisBrandMotion.COUNTERFLOW)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
        }
        Spacer(Modifier.height(if (compact) 12.dp else 24.dp))
        if (privateMode) {
            Spacer(Modifier.height(20.dp))
            Text(stringResource(R.string.private_new_tab_title), color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.private_new_tab_summary), color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 520.dp))
        }
        Spacer(Modifier.height(12.dp))
    }
}
