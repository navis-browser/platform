/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */
package org.navis.browser.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.navis.browser.R
import org.navis.browser.api.BrowserSessionState
import org.navis.browser.engine.AddressSuggestion
import org.navis.browser.engine.AndroidWindowRuntime
import org.navis.browser.settings.AndroidSettingsSnapshot

internal class AddressSuggestionsState {
    var local by mutableStateOf(emptyList<AddressSuggestion>())
    var remote by mutableStateOf(emptyList<AddressSuggestion>())
    var selected by mutableIntStateOf(-1)
    val rows: List<AddressSuggestion> get() = (local + remote.distinctBy { it.title.lowercase() }).take(8)

    fun key(event: KeyEvent, composing: Boolean, commit: (AddressSuggestion) -> Unit): Boolean {
        if (composing || event.type != KeyEventType.KeyDown || rows.isEmpty()) return false
        return when (event.key) {
            Key.DirectionDown -> { selected = (selected + 1).coerceAtMost(rows.lastIndex); true }
            Key.DirectionUp -> { selected = (selected - 1).coerceAtLeast(-1); true }
            Key.Enter, Key.NumPadEnter -> rows.getOrNull(selected)?.let { commit(it); true } ?: false
            else -> false
        }
    }
}

@Composable
internal fun rememberAddressSuggestions(runtime: AndroidWindowRuntime, session: BrowserSessionState?,
    value: TextFieldValue, editing: Boolean, settings: AndroidSettingsSnapshot): AddressSuggestionsState {
    // A new immutable request identity clears stale rows during this composition, before effects run.
    val result = remember(runtime, session?.id, session?.navigation?.revision, value.text, value.composition,
        editing, settings.searchProvider, settings.remoteSuggestionsEnabled, settings.searchServiceReady) {
        AddressSuggestionsState()
    }
    DisposableEffect(result) {
        var live = true
        var remote: AutoCloseable? = null
        val main = Handler(Looper.getMainLooper())
        val query = value.text.trim()
        val id = session?.id
        val eligible = editing && value.composition == null && query.length in 1..8192 &&
            id != null && settings.searchServiceReady
        val startRemote = Runnable {
            if (live && id != null) remote = runtime.product.remoteSuggestions(query,
                java.util.UUID.randomUUID().toString(), id) { response ->
                if (live) { result.selected = -1; result.remote = response.getOrDefault(emptyList()) }
            }
        }
        if (eligible) {
            runtime.addressCandidates(query, id) { response ->
                if (live) { result.selected = -1; result.local = response.getOrDefault(emptyList()) }
            }
            if (settings.remoteSuggestionsEnabled && session.mode == org.navis.browser.api.SessionMode.NORMAL) {
                main.postDelayed(startRemote, 250)
            }
        }
        onDispose { live = false; main.removeCallbacks(startRemote); remote?.close() }
    }
    return result
}

@Composable
internal fun AddressSuggestionsContent(state: AddressSuggestionsState, providerName: String,
    onCommit: (AddressSuggestion) -> Unit) {
    val rows = state.rows
    if (rows.isEmpty()) return
    Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState())
        .semantics { collectionInfo = CollectionInfo(rows.size, 1) }) {
        Text(stringResource(R.string.omnibox_search_with, providerName),
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        rows.forEachIndexed { index, row ->
            val bringIntoView = remember(row.id) { BringIntoViewRequester() }
            LaunchedEffect(state.selected, row.id) { if (state.selected == index) bringIntoView.bringIntoView() }
            val kind = stringResource(when (row.kind) {
                "tab" -> R.string.omnibox_suggestion_tab
                "bookmark" -> R.string.bookmarks
                "history" -> R.string.history
                else -> R.string.omnibox_suggestion_search
            })
            ListItem(headlineContent = { Text(row.title.ifBlank { row.url }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text(if (row.url.isBlank()) kind else "$kind · ${row.url}",
                    maxLines = 1, overflow = TextOverflow.Ellipsis) },
                colors = ListItemDefaults.colors(containerColor = if (state.selected == index)
                    MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer),
                modifier = Modifier.fillMaxWidth().bringIntoViewRequester(bringIntoView).focusProperties { canFocus = false }
                    .selectable(selected = state.selected == index, role = Role.Button, onClick = { onCommit(row) }))
        }
    }
}
