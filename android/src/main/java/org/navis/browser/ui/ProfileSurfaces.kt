/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.os.PersistableBundle
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import org.navis.browser.R
import org.navis.browser.persistence.BookmarkDraft
import org.navis.browser.persistence.BookmarkEntry
import org.navis.browser.persistence.BookmarkTreePolicy
import org.navis.browser.persistence.HistoryEntry
import org.navis.browser.persistence.PasswordEntry
import java.util.concurrent.atomic.AtomicInteger

internal typealias ProfileCompletion = (Result<Unit>) -> Unit

internal data class HistoryActions(
    val remove: (String, ProfileCompletion) -> Unit,
    val clear: (ProfileCompletion) -> Unit,
)

internal data class BookmarkActions(
    val save: (BookmarkDraft, ProfileCompletion) -> Unit,
    val move: (String, String?, Int, ProfileCompletion) -> Unit,
    val remove: (String, ProfileCompletion) -> Unit,
)

internal data class PasswordActions(
    val reveal: (String, (Result<String>) -> Unit) -> Unit,
    val remove: (String, ProfileCompletion) -> Unit,
    val clear: (ProfileCompletion) -> Unit,
)

@Composable
internal fun HistorySurface(
    entries: List<HistoryEntry>,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onClear: () -> Unit,
    actions: HistoryActions? = null,
    onOpenFullPage: (() -> Unit)? = null,
) {
    var query by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val filtered = remember(entries, query) {
        entries.filter { it.title.contains(query, true) || it.url.contains(query, true) }
    }
    val complete: ProfileCompletion = { busy = false; failed = it.isFailure }
    ProfileListScaffold(
        title = stringResource(R.string.history), onBack = onBack,
        query = query, onQuery = { query = it }, failed = failed,
        action = {
            IconButton(enabled = entries.isNotEmpty() && !busy, onClick = { confirmClear = true }) {
                Icon(Icons.Default.Delete, stringResource(R.string.clear_history))
            }
            onOpenFullPage?.let { open ->
                IconButton(onClick = open) {
                    Icon(painterResource(R.drawable.ic_open_full_page),
                        stringResource(R.string.history_open_full_page))
                }
            }
        },
    ) {
        if (filtered.isEmpty()) item { EmptyProfile(R.string.no_history, query) }
        items(filtered, key = { it.url }) { entry ->
            ProfileRow(
                title = entry.title.ifBlank { entry.url }, subtitle = entry.url,
                detail = DateUtils.getRelativeTimeSpanString(entry.visitedAt).toString(),
                onClick = { onOpen(entry.url) },
                action = {
                    actions?.let {
                        IconButton(enabled = !busy, onClick = {
                            busy = true; failed = false; it.remove(entry.url, complete)
                        }) { Icon(Icons.Default.Delete, productRowActionDescription(
                            stringResource(R.string.profile_remove_history), entry.title.ifBlank { entry.url }, entry.url)) }
                    }
                },
            )
        }
    }
    if (confirmClear) ProfileConfirmation(
        title = stringResource(R.string.clear_history_title),
        message = stringResource(R.string.clear_history_message),
        onDismiss = { confirmClear = false },
        onConfirm = {
            confirmClear = false
            if (actions == null) onClear() else {
                busy = true; failed = false; actions.clear(complete)
            }
        },
    )
}

@Composable
internal fun BookmarksSurface(
    entries: List<BookmarkEntry>,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onRemove: (String) -> Unit,
    actions: BookmarkActions? = null,
) {
    var query by remember { mutableStateOf("") }
    var folderId by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf<BookmarkDraft?>(null) }
    var removal by remember { mutableStateOf<BookmarkEntry?>(null) }
    var addMenu by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val current = entries.firstOrNull { it.id == folderId && it.isFolder }
    val actualParent = current?.id
    BackHandler(enabled = current != null) { folderId = current?.parentId }
    val siblings = entries.filter { it.parentId == actualParent }
    val filtered = if (query.isBlank()) siblings else entries.filter {
        it.title.contains(query, true) || it.url.contains(query, true)
    }
    val complete: ProfileCompletion = { busy = false; failed = it.isFailure }
    ProfileListScaffold(
        title = stringResource(R.string.bookmarks),
        onBack = { if (current == null) onBack() else folderId = current.parentId },
        listKey = actualParent,
        query = query, onQuery = { query = it }, failed = failed,
        action = {
            if (actions != null) Box {
                IconButton(enabled = !busy, onClick = { addMenu = true }) {
                    Icon(Icons.Default.Add, stringResource(R.string.profile_add_bookmark))
                }
                NavisDropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                    NavisMenuItem(text = { Text(stringResource(R.string.profile_add_bookmark)) },
                        leadingIcon = { Icon(Icons.Default.Add, null) }, itemIndex = 0, itemCount = 2,
                        onClick = { addMenu = false; draft = BookmarkDraft(title = "", parentId = actualParent) })
                    NavisMenuItem(text = { Text(stringResource(R.string.profile_add_folder)) },
                        leadingIcon = { Icon(ProfileFolderIcon, null) }, itemIndex = 1, itemCount = 2,
                        onClick = { addMenu = false; draft = BookmarkDraft(title = "", parentId = actualParent, isFolder = true) })
                }
            }
        },
    ) {
        if (current != null && query.isBlank()) item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { folderId = current.parentId }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    Text(stringResource(R.string.profile_parent_folder))
                }
                Text(current.title, style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (filtered.isEmpty()) item { EmptyProfile(R.string.no_bookmarks, query) }
        items(filtered, key = { it.id }) { entry ->
            var menu by remember(entry.id) { mutableStateOf(false) }
            val index = siblings.indexOfFirst { it.id == entry.id }
            ProfileRow(
                title = entry.title.ifBlank { entry.url },
                subtitle = if (entry.isFolder) stringResource(R.string.profile_folder) else entry.url,
                leading = if (entry.isFolder) ({ Icon(ProfileFolderIcon, null) }) else null,
                onClick = { if (entry.isFolder) { folderId = entry.id; query = "" } else onOpen(entry.url) },
                action = {
                    if (actions == null) {
                        IconButton(onClick = { onRemove(entry.url) }) {
                            Icon(Icons.Default.Delete, productRowActionDescription(
                                stringResource(R.string.remove_bookmark), entry.title.ifBlank { entry.url }, entry.url))
                        }
                    } else Box {
                        IconButton(enabled = !busy, onClick = { menu = true }) {
                            Icon(Icons.Default.MoreVert, productRowActionDescription(
                                stringResource(R.string.profile_bookmark_actions), entry.title.ifBlank { entry.url }, entry.url))
                        }
                        NavisDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            NavisMenuItem(text = { Text(stringResource(R.string.profile_edit_move)) },
                                leadingIcon = { Icon(Icons.Default.Edit, null) }, itemIndex = 0, itemCount = 4,
                                onClick = {
                                menu = false
                                draft = BookmarkDraft(entry.id, entry.title, entry.url, entry.parentId, entry.isFolder)
                            })
                            NavisMenuItem(text = { Text(stringResource(R.string.profile_move_up)) },
                                leadingIcon = { Icon(Icons.Default.KeyboardArrowUp, null) }, itemIndex = 1, itemCount = 4,
                                enabled = query.isBlank() && index > 0, onClick = {
                                    menu = false; busy = true; failed = false
                                    actions.move(entry.id, entry.parentId, index - 1, complete)
                                })
                            NavisMenuItem(text = { Text(stringResource(R.string.profile_move_down)) },
                                leadingIcon = { Icon(Icons.Default.KeyboardArrowDown, null) }, itemIndex = 2, itemCount = 4,
                                enabled = query.isBlank() && index >= 0 && index < siblings.lastIndex, onClick = {
                                    menu = false; busy = true; failed = false
                                    actions.move(entry.id, entry.parentId, index + 1, complete)
                                })
                            NavisMenuItem(text = { Text(stringResource(R.string.remove)) },
                                leadingIcon = { Icon(Icons.Default.Delete, null) }, itemIndex = 3, itemCount = 4,
                                onClick = { menu = false; removal = entry })
                        }
                    }
                },
            )
        }
    }
    draft?.let { edit ->
        if (actions != null) BookmarkEditor(edit, entries, onDismiss = { draft = null }) { changed, done ->
            actions.save(changed) { result ->
                done(result)
                if (result.isSuccess) draft = null
            }
        }
    }
    removal?.let { entry -> ProfileConfirmation(
        title = stringResource(R.string.profile_remove_bookmark_title),
        message = if (entry.isFolder) stringResource(R.string.profile_remove_folder_message, entry.title)
            else entry.title.ifBlank { entry.url },
        onDismiss = { removal = null },
        onConfirm = {
            removal = null; busy = true; failed = false
            actions?.remove?.invoke(entry.id, complete)
        },
    ) }
}

@Composable
internal fun BookmarkEditor(
    draft: BookmarkDraft,
    entries: List<BookmarkEntry>,
    onDismiss: () -> Unit,
    onSave: (BookmarkDraft, ProfileCompletion) -> Unit,
) {
    var title by remember(draft) { mutableStateOf(draft.title) }
    var url by remember(draft) { mutableStateOf(draft.url) }
    var parent by remember(draft) { mutableStateOf(draft.parentId) }
    var folderMenu by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val parents = remember(entries, draft.id) { BookmarkTreePolicy.availableParents(entries, draft.id) }
    val addressFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    fun canSave() = !busy && title.isNotBlank() && (draft.isFolder || url.isNotBlank())
    fun submit() {
        // Recheck live state: a second IME/button action can precede recomposition.
        if (!canSave()) return
        busy = true; failed = false
        focusManager.clearFocus()
        onSave(draft.copy(title = title, url = url, parentId = parent)) {
            busy = false; failed = it.isFailure
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(if (draft.isFolder) R.string.profile_folder else R.string.profile_bookmark)) },
        text = { Column(modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(title, { title = it }, enabled = !busy, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = if (draft.isFolder) ImeAction.Done else ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = {
                    if (draft.isFolder) submit() else addressFocus.requestFocus()
                }, onDone = { submit() }),
                label = { Text(stringResource(R.string.profile_title)) }, modifier = Modifier.fillMaxWidth())
            if (!draft.isFolder) OutlinedTextField(url, { url = it }, enabled = !busy, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                label = { Text(stringResource(R.string.profile_address)) },
                modifier = Modifier.fillMaxWidth().focusRequester(addressFocus))
            Box {
                TextButton(enabled = !busy, onClick = { folderMenu = true }) {
                    Text(stringResource(R.string.profile_location, entries.firstOrNull { it.id == parent }?.title
                        ?: stringResource(R.string.profile_root_folder)))
                }
                NavisDropdownMenu(expanded = folderMenu, onDismissRequest = { folderMenu = false }) {
                    NavisMenuItem(text = { Text(stringResource(R.string.profile_root_folder)) },
                        leadingIcon = { Icon(ProfileFolderIcon, null) }, selected = parent == null,
                        itemIndex = 0, itemCount = parents.size + 1,
                        onClick = { parent = null; folderMenu = false })
                    parents.forEachIndexed { index, folder ->
                        NavisMenuItem(text = { Text(BookmarkTreePolicy.folderPath(entries, folder.id)) },
                            leadingIcon = { Icon(ProfileFolderIcon, null) }, selected = parent == folder.id,
                            itemIndex = index + 1, itemCount = parents.size + 1,
                            onClick = { parent = folder.id; folderMenu = false })
                    }
                }
            }
            if (failed) ProfileFailure()
        } },
        confirmButton = { TextButton(enabled = canSave(), onClick = ::submit) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
internal fun PasswordsSurface(
    entries: List<PasswordEntry>,
    onBack: () -> Unit,
    onRemove: (String) -> Unit,
    actions: PasswordActions? = null,
) {
    var query by remember { mutableStateOf("") }
    var removal by remember { mutableStateOf<PasswordEntry?>(null) }
    var selected by remember { mutableStateOf<PasswordEntry?>(null) }
    var clear by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val filtered = remember(entries, query) {
        entries.filter { it.origin.contains(query, true) || it.username.contains(query, true) }
    }
    val complete: ProfileCompletion = { busy = false; failed = it.isFailure }
    ProfileListScaffold(
        title = stringResource(R.string.passwords), onBack = onBack,
        query = query, onQuery = { query = it }, failed = failed,
        action = { if (actions != null) IconButton(enabled = entries.isNotEmpty() && !busy,
            onClick = { clear = true }) {
            Icon(Icons.Default.Delete, stringResource(R.string.profile_clear_passwords))
        } },
    ) {
        if (filtered.isEmpty()) item { EmptyProfile(R.string.no_saved_passwords, query) }
        items(filtered, key = { it.guid }) { entry -> ProfileRow(
            title = entry.origin, subtitle = entry.username.ifBlank { stringResource(R.string.no_username) },
            onClick = if (actions != null) ({ selected = entry }) else null,
            action = { IconButton(enabled = !busy, onClick = { removal = entry }) {
                Icon(Icons.Default.Delete, stringResource(R.string.remove_saved_password_named,
                    entry.origin, entry.username.ifBlank { stringResource(R.string.no_username) }))
            } },
        ) }
    }
    selected?.let { entry -> if (actions != null) PasswordDetails(entry, actions,
        onDismiss = { selected = null }) }
    removal?.let { entry -> ProfileConfirmation(
        title = stringResource(R.string.remove_saved_password_title),
        message = stringResource(R.string.remove_saved_password_message, entry.origin),
        onDismiss = { removal = null }, onConfirm = {
            removal = null
            if (actions == null) onRemove(entry.guid) else {
                busy = true; failed = false; actions.remove(entry.guid, complete)
            }
        },
    ) }
    if (clear) ProfileConfirmation(
        title = stringResource(R.string.profile_clear_passwords),
        message = stringResource(R.string.profile_clear_passwords_message),
        onDismiss = { clear = false }, onConfirm = {
            clear = false; busy = true; failed = false; actions?.clear?.invoke(complete)
        },
    )
}

@Composable
private fun PasswordDetails(entry: PasswordEntry, actions: PasswordActions, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = remember(context) { context.profileLifecycleOwner()?.lifecycle }
    val generation = remember { AtomicInteger() }
    var revealed by remember(entry.guid) { mutableStateOf<String?>(null) }
    var pending by remember(entry.guid) { mutableStateOf(false) }
    var failed by remember(entry.guid) { mutableStateOf(false) }
    DisposableEffect(lifecycle, entry.guid) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                generation.incrementAndGet(); revealed = null; pending = false; onDismiss()
            }
        }
        lifecycle?.addObserver(observer)
        onDispose {
            generation.incrementAndGet(); revealed = null
            lifecycle?.removeObserver(observer)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text(entry.origin) },
        text = { Column(modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(entry.username.ifBlank { stringResource(R.string.no_username) })
            Text(revealed ?: "••••••••", style = MaterialTheme.typography.bodyLarge)
            if (failed) ProfileFailure()
            Row {
                TextButton(enabled = !pending, onClick = {
                    if (revealed != null) { generation.incrementAndGet(); revealed = null } else {
                        pending = true; failed = false
                        val request = generation.incrementAndGet()
                        actions.reveal(entry.guid) { result ->
                            if (generation.get() == request) {
                                pending = false; failed = result.isFailure
                                revealed = result.getOrNull()
                            }
                        }
                    }
                }) { Text(stringResource(if (revealed == null) R.string.profile_show_password else R.string.profile_hide_password)) }
                TextButton(enabled = revealed != null, onClick = {
                    revealed?.let { value ->
                        val clip = ClipData.newPlainText("Navis", value)
                        clip.description.extras = PersistableBundle().apply {
                            putBoolean("android.content.extra.IS_SENSITIVE", true)
                        }
                        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
                    }
                }) { Text(stringResource(R.string.profile_copy)) }
            }
        } },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

private fun Context.profileLifecycleOwner(): LifecycleOwner? = when (this) {
    is LifecycleOwner -> this
    is ContextWrapper -> if (baseContext === this) null else baseContext.profileLifecycleOwner()
    else -> null
}

internal val ProfileFolderIcon: ImageVector = ImageVector.Builder(
    name = "Folder", defaultWidth = 24.dp, defaultHeight = 24.dp,
    viewportWidth = 24f, viewportHeight = 24f,
).apply {
    path(fill = SolidColor(Color.Black)) {
        moveTo(10f, 4f)
        lineTo(4f, 4f)
        curveTo(2.9f, 4f, 2f, 4.9f, 2f, 6f)
        lineTo(2f, 18f)
        curveTo(2f, 19.1f, 2.9f, 20f, 4f, 20f)
        lineTo(20f, 20f)
        curveTo(21.1f, 20f, 22f, 19.1f, 22f, 18f)
        lineTo(22f, 8f)
        curveTo(22f, 6.9f, 21.1f, 6f, 20f, 6f)
        lineTo(12f, 6f)
        close()
    }
}.build()

@Composable
private fun ProfileConfirmation(title: String, message: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.remove)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun ProfileListScaffold(
    title: String,
    onBack: () -> Unit,
    query: String,
    onQuery: (String) -> Unit,
    failed: Boolean,
    listKey: Any? = Unit,
    action: (@Composable () -> Unit)? = null,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    val listState = rememberProductListState(listKey, query)
    Scaffold(modifier = Modifier.navisNativePageInsets(), topBar = {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.navisTopBarInsets()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                ProductPageHeader(title, onBack, action)
                Column(Modifier.navisTopBarContentInsets().productPageWidth().padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 16.dp)) {
                    ProductPageSearchField(query, onQuery, stringResource(R.string.profile_search))
                    if (failed) ProfileFailure(Modifier.padding(top = 12.dp))
                }
            }
        }
    }) { padding ->
        Box(Modifier.fillMaxSize().navisPageContentPadding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                state = listState,
                modifier = Modifier.productPageWidth().navigationBarsPadding(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun EmptyProfile(emptyLabel: Int, query: String) {
    ProductPageEmptyState(stringResource(if (query.isBlank()) emptyLabel else R.string.profile_no_matches))
}

@Composable
private fun ProfileFailure(modifier: Modifier = Modifier) {
    Text(stringResource(R.string.profile_operation_failed), modifier = modifier, color = MaterialTheme.colorScheme.error)
}

@Composable
private fun ProfileRow(
    title: String,
    subtitle: String,
    detail: String? = null,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
) {
    ListItem(
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
            .then(if (onClick == null) Modifier else Modifier.clickable(onClick = onClick)),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = {
            SelectionContainer { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        },
        supportingContent = {
            SelectionContainer {
                Column {
                    Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    detail?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
                }
            }
        },
        leadingContent = leading,
        trailingContent = action,
    )
}
