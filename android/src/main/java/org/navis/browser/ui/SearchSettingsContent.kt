/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.navis.browser.R
import org.navis.browser.settings.AndroidSearchProvider
import org.navis.browser.engine.AndroidProductServices
import org.json.JSONObject
import org.navis.browser.settings.AndroidSettingsSnapshot
import org.navis.browser.settings.AndroidSettingsUpdateResult

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SearchSettingsContent(
    product: AndroidProductServices,
    state: AndroidSettingsSnapshot,
    changed: (AndroidSettingsUpdateResult) -> Unit,
) {
    var editing by remember { mutableStateOf<AndroidSearchProvider?>(null) }
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<AndroidSearchProvider?>(null) }
    var defaultMenu by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    val ready = state.searchServiceReady && !working
    fun update(action: String, data: JSONObject = JSONObject(), done: (Boolean) -> Unit = {}) {
        if (working || !state.searchServiceReady) return
        working = true
        product.updateSearch(action, data) { result ->
            working = false
            changed(if (result.isSuccess) AndroidSettingsUpdateResult.UPDATED else AndroidSettingsUpdateResult.PERSISTENCE_FAILED)
            done(result.isSuccess)
        }
    }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.settings_search_engine_summary), style = MaterialTheme.typography.bodyMedium)
        ExposedDropdownMenuBox(expanded = defaultMenu, onExpandedChange = { if (ready) defaultMenu = it }) {
            OutlinedTextField(
                value = state.searchProvider.name, onValueChange = {}, readOnly = true, singleLine = true,
                enabled = ready,
                label = { Text(stringResource(R.string.settings_provider_default)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = defaultMenu) },
                modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(expanded = defaultMenu, onDismissRequest = { defaultMenu = false },
                shape = MenuDefaults.standaloneGroupShape,
                containerColor = MenuDefaults.groupStandardContainerColor) {
                state.searchProviders.forEachIndexed { index, provider ->
                    NavisMenuItem(text = { Text(provider.name) },
                        selected = provider.id == state.searchProvider.id,
                        itemIndex = index, itemCount = state.searchProviders.size,
                        onClick = {
                            defaultMenu = false
                            update("default", JSONObject().put("id", provider.id))
                        })
                }
            }
        }
        SettingsToggle(stringResource(R.string.settings_remote_suggestions),
            stringResource(R.string.settings_remote_suggestions_summary), state.remoteSuggestionsEnabled, ready) {
            update("remote", JSONObject().put("enabled", it))
        }
        Text(stringResource(R.string.settings_provider_manage), style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 12.dp))
        state.searchProviders.forEachIndexed { index, provider ->
            var menu by remember(provider.id) { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(provider.name, style = MaterialTheme.typography.bodyLarge)
                    Text(provider.searchUrlTemplate, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Box {
                    IconButton(enabled = ready, onClick = { menu = true }) {
                        Icon(Icons.Default.MoreVert, stringResource(R.string.settings_provider_actions, provider.name))
                    }
                    NavisDropdownMenu(menu, onDismissRequest = { menu = false }) {
                        val itemCount = if (provider.builtIn) 3 else 4
                        NavisMenuItem(text = { Text(stringResource(R.string.settings_provider_edit)) },
                            leadingIcon = { Icon(Icons.Default.Edit, null) },
                            itemIndex = 0, itemCount = itemCount,
                            onClick = { menu = false; editing = provider })
                        NavisMenuItem(text = { Text(stringResource(R.string.settings_provider_up)) }, enabled = index > 0,
                            leadingIcon = { Icon(Icons.Default.KeyboardArrowUp, null) },
                            itemIndex = 1, itemCount = itemCount,
                            onClick = { menu = false; update("move", JSONObject().put("id", provider.id).put("index", index - 1)) })
                        NavisMenuItem(text = { Text(stringResource(R.string.settings_provider_down)) }, enabled = index < state.searchProviders.lastIndex,
                            leadingIcon = { Icon(Icons.Default.KeyboardArrowDown, null) },
                            itemIndex = 2, itemCount = itemCount,
                            onClick = { menu = false; update("move", JSONObject().put("id", provider.id).put("index", index + 1)) })
                        if (!provider.builtIn) NavisMenuItem(text = { Text(stringResource(R.string.settings_provider_remove)) },
                            leadingIcon = { Icon(Icons.Default.Delete, null) },
                            itemIndex = 3, itemCount = itemCount,
                            onClick = { menu = false; removing = provider })
                    }
                }
            }
        }
        FilledTonalButton(enabled = ready && state.searchProviders.size < 64, onClick = { adding = true }) {
            Text(stringResource(R.string.settings_provider_add))
        }
    }
    if (adding || editing != null) {
        val original = editing
        var name by remember(original) { mutableStateOf(original?.name.orEmpty()) }
        var template by remember(original) { mutableStateOf(original?.searchUrlTemplate.orEmpty()) }
        var suggestionTemplate by remember(original) { mutableStateOf(original?.suggestionUrlTemplate.orEmpty()) }
        var failed by remember(original) { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { adding = false; editing = null },
            title = { Text(stringResource(if (original == null) R.string.settings_provider_add else R.string.settings_provider_edit)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { if (it.length <= 80) name = it }, label = { Text(stringResource(R.string.settings_provider_name)) }, singleLine = true)
                OutlinedTextField(template, { if (it.length <= 4096) template = it }, label = { Text(stringResource(R.string.settings_provider_url)) },
                    supportingText = { Text(stringResource(R.string.settings_provider_template)) })
                OutlinedTextField(suggestionTemplate, { if (it.length <= 4096) suggestionTemplate = it },
                    label = { Text(stringResource(R.string.settings_provider_suggestion_url)) },
                    supportingText = { Text(stringResource(R.string.settings_provider_suggestion_template)) })
                if (failed) Text(stringResource(R.string.settings_provider_invalid), color = MaterialTheme.colorScheme.error)
            } },
            confirmButton = { TextButton(enabled = ready, onClick = {
                update("upsert", JSONObject().put("id", original?.id ?: java.util.UUID.randomUUID().toString())
                    .put("name", name.trim()).put("template", template.trim())
                    .put("suggestionTemplate", suggestionTemplate.trim())) { success ->
                    failed = !success
                    if (success) { adding = false; editing = null }
                }
            }) { Text(stringResource(R.string.settings_save)) } },
            dismissButton = { TextButton(onClick = { adding = false; editing = null }) { Text(stringResource(R.string.cancel)) } })
    }
    removing?.let { provider ->
        AlertDialog(onDismissRequest = { removing = null }, title = { Text(stringResource(R.string.settings_provider_remove)) },
            text = { Text(stringResource(R.string.settings_provider_remove_confirm, provider.name)) },
            confirmButton = { TextButton(enabled = ready, onClick = { update("remove", JSONObject().put("id", provider.id)) {
                if (it) removing = null
            } }) {
                Text(stringResource(R.string.settings_provider_remove))
            } }, dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.cancel)) } })
    }
}
