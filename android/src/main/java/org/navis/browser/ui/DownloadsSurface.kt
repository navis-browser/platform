/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.ui

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.concurrent.CompletionStage
import kotlinx.coroutines.launch
import org.navis.browser.R
import org.navis.browser.downloads.DownloadEntry
import org.navis.browser.downloads.DownloadAction
import org.navis.browser.downloads.DownloadActionPolicy
import org.navis.browser.downloads.DownloadActionTicket
import org.navis.browser.downloads.DownloadManager
import org.navis.browser.downloads.DownloadObserver
import org.navis.browser.downloads.DownloadState

@Composable
internal fun DownloadsSurface(
    manager: DownloadManager,
    privateMode: Boolean,
    onBack: () -> Unit,
    onOpenSource: (String) -> Unit,
) {
    var entries by remember(manager, privateMode) { mutableStateOf(emptyList<DownloadEntry>()) }
    var query by remember(manager, privateMode) { mutableStateOf("") }
    val listState = rememberProductListState(manager to privateMode, query)
    var confirmClear by remember(manager, privateMode) { mutableStateOf(false) }
    val snackbar = remember(manager, privateMode) { SnackbarHostState() }
    val actionPolicy = remember(manager, privateMode) { DownloadActionPolicy(privateMode) }
    var actionRevision by remember(actionPolicy) { mutableStateOf(0) }
    val actions = remember(actionPolicy, actionRevision) { actionPolicy.state() }
    val scope = rememberCoroutineScope()
    val actionFailed = stringResource(R.string.download_action_failed)
    fun completeAction(ticket: DownloadActionTicket, error: Throwable?) {
        scope.launch {
            if (!actionPolicy.complete(ticket, error == null)) return@launch
            actionRevision++
            if (error != null) snackbar.showSnackbar(actionFailed)
            else if (actionPolicy.requiresSnapshot(ticket)) manager.refresh()
        }
    }
    fun perform(action: DownloadAction, id: Long? = null, operation: () -> CompletionStage<Unit>) {
        val ticket = actionPolicy.begin(action, id) ?: return
        actionRevision++
        try {
            operation().whenComplete { _, error -> completeAction(ticket, error) }
        } catch (error: Throwable) {
            completeAction(ticket, error)
        }
    }
    DisposableEffect(manager, privateMode, actionPolicy) {
        val observer = DownloadObserver {
            if (actionPolicy.observe(it)) {
                entries = it.filter { entry -> entry.privateMode == privateMode }
                actionRevision++
            }
        }
        manager.addObserver(privateMode, observer)
        onDispose {
            actionPolicy.close()
            manager.removeObserver(observer)
        }
    }

    Scaffold(
        modifier = Modifier.navisNativePageInsets(),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.navisTopBarInsets(),
            ) {
                ProductPageHeader(stringResource(R.string.downloads_title), onBack) {
                    TextButton(
                        enabled = actions.canClear,
                        onClick = { confirmClear = true },
                    ) {
                        Text(stringResource(R.string.download_clear_finished))
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().navisPageContentPadding(padding), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.productPageWidth().navigationBarsPadding()) {
                ProductPageSearchField(
                    query = query,
                    onQuery = { query = it },
                    label = stringResource(R.string.download_search),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
                if (privateMode) {
                    Text(
                        stringResource(R.string.download_private_history),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                val visible = entries.filter {
                    it.fileName.contains(query, ignoreCase = true) ||
                        it.sourceUri.contains(query, ignoreCase = true)
                }
                if (visible.isEmpty()) {
                    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        ProductPageEmptyState(stringResource(if (query.isBlank()) R.string.download_empty else R.string.profile_no_matches))
                    }
                } else {
                    LazyColumn(Modifier.fillMaxWidth().weight(1f), state = listState,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                        items(visible, key = DownloadEntry::id) { entry ->
                            DownloadRow(
                                entry = entry,
                                enabled = !actions.clearing && entry.id !in actions.busyIds,
                                onCancel = { perform(DownloadAction.CANCEL, entry.id) { manager.cancel(entry.id, privateMode) } },
                                onPause = { perform(DownloadAction.PAUSE, entry.id) { manager.pause(entry.id, privateMode) } },
                                onResume = { perform(DownloadAction.RESUME, entry.id) { manager.resume(entry.id, privateMode) } },
                                onRetry = { perform(DownloadAction.RETRY, entry.id) { manager.retry(entry.id, privateMode) } },
                                onRemove = { perform(DownloadAction.REMOVE, entry.id) { manager.remove(entry.id, privateMode) } },
                                onOpen = { perform(DownloadAction.OPEN, entry.id) { manager.open(entry.id, privateMode) } },
                                onOpenSource = { onOpenSource(entry.sourceUri) },
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.download_clear_finished)) },
            text = { Text(stringResource(R.string.download_clear_message)) },
            confirmButton = {
                TextButton(enabled = actions.canClear, onClick = {
                    confirmClear = false
                    perform(DownloadAction.CLEAR_FINISHED) { manager.clearFinished(privateMode) }
                }) { Text(stringResource(R.string.clear)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun DownloadRow(
    entry: DownloadEntry,
    enabled: Boolean,
    onCancel: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
    onOpen: () -> Unit,
    onOpenSource: () -> Unit,
) {
    val context = LocalContext.current
    var menuOpen by remember(entry.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(entry.fileName, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                Text(
                    entry.sourceUri,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box {
                IconButton(enabled = enabled, onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, productRowActionDescription(
                        stringResource(R.string.download_actions), entry.fileName, entry.sourceUri))
                }
                NavisDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    val menuItemCount = if (entry.canOpenSource) 2 else 1
                    if (entry.canOpenSource) {
                        NavisMenuItem(
                            enabled = enabled,
                            text = { Text(stringResource(R.string.download_open_source)) },
                            leadingIcon = { Icon(painterResource(R.drawable.ic_open_full_page), null) },
                            itemIndex = 0, itemCount = menuItemCount,
                            onClick = { menuOpen = false; onOpenSource() },
                        )
                    }
                    NavisMenuItem(
                        enabled = enabled,
                        leadingIcon = { Icon(Icons.Default.Delete, null) },
                        itemIndex = menuItemCount - 1, itemCount = menuItemCount,
                        text = { Text(stringResource(if (entry.active) {
                            R.string.download_cancel_remove
                        } else {
                            R.string.download_remove_record
                        })) },
                        onClick = { menuOpen = false; onRemove() },
                    )
                }
            }
        }
        val status = stringResource(when (entry.state) {
            DownloadState.DOWNLOADING -> R.string.download_state_downloading
            DownloadState.PAUSED -> R.string.download_state_paused
            DownloadState.COMPLETE -> if (entry.exists) R.string.download_state_complete else R.string.download_state_missing
            DownloadState.CANCELLED -> R.string.download_state_cancelled
            DownloadState.FAILED -> R.string.download_state_failed
        })
        val received = Formatter.formatShortFileSize(context, entry.bytesReceived)
        val size = if (entry.totalBytes > 0) {
            stringResource(R.string.download_progress_bytes, received, Formatter.formatShortFileSize(context, entry.totalBytes))
        } else received
        Text(
            stringResource(R.string.download_status_bytes, status, size),
            style = MaterialTheme.typography.bodySmall,
            color = if (entry.state == DownloadState.FAILED ||
                (entry.state == DownloadState.COMPLETE && !entry.exists)) {
                MaterialTheme.colorScheme.error
            } else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        if (entry.state == DownloadState.DOWNLOADING) {
            entry.progress?.let { progress ->
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            } ?: LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (entry.active) {
                TextButton(enabled = enabled, onClick = onCancel) { Text(stringResource(R.string.cancel)) }
                if (entry.canPause) {
                    TextButton(enabled = enabled, onClick = if (entry.state == DownloadState.PAUSED) onResume else onPause) {
                        Text(stringResource(if (entry.state == DownloadState.PAUSED) R.string.download_resume else R.string.download_pause))
                    }
                }
            }
            if (entry.canOpen) {
                TextButton(enabled = enabled, onClick = onOpen) { Text(stringResource(R.string.download_open)) }
            }
            if (entry.canRetry) {
                TextButton(enabled = enabled, onClick = onRetry) { Text(stringResource(R.string.download_retry)) }
            } else if (!entry.active && !entry.canOpen && entry.canOpenSource) {
                TextButton(enabled = enabled, onClick = onOpenSource) { Text(stringResource(R.string.download_open_source)) }
            }
        }
    }
}
