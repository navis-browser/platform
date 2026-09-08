/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */
package org.navis.browser.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.navis.browser.R
import org.navis.browser.engine.AndroidWindowRuntime
import org.navis.browser.engine.ProcessInformation

@Composable
internal fun ProcessManagementSurface(runtime: AndroidWindowRuntime, onBack: () -> Unit) {
    var refresh by remember { mutableIntStateOf(0) }
    var processes by remember(runtime) { mutableStateOf<List<ProcessInformation>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var ending by remember { mutableStateOf(false) }
    LaunchedEffect(runtime) {
        while (true) { delay(2000); if (!ending) refresh++ }
    }
    DisposableEffect(runtime, refresh) {
        var active = true
        runtime.product.processes { result ->
            if (active) {
                failed = result.isFailure
                result.getOrNull()?.let { processes = it }
            }
        }
        onDispose { active = false }
    }
    ProductPageScaffold(stringResource(R.string.processes_title), onBack) {
        Text(stringResource(R.string.processes_explanation))
        if (processes == null && !failed) CircularProgressIndicator()
        if (failed) Text(stringResource(R.string.profile_operation_failed), color = MaterialTheme.colorScheme.error)
        TextButton(onClick = { refresh++ }, enabled = !ending) { Text(stringResource(R.string.site_retry)) }
        processes.orEmpty().forEach { process ->
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SelectionContainer {
                        Column {
                            Text(process.title.ifBlank { process.type }, style = MaterialTheme.typography.titleMedium)
                            if (process.origin.isNotEmpty()) Text(process.origin, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Text(stringResource(R.string.processes_memory, process.memoryBytes / (1024 * 1024)))
                    Text(stringResource(R.string.processes_cpu, process.cpuPercent?.let { "%.1f".format(it) } ?: "—"))
                    Text(stringResource(R.string.processes_threads, process.threadCount))
                    if (process.canEnd) TextButton(onClick = { selectedId = process.id }, enabled = !ending) {
                        Text(stringResource(R.string.processes_end))
                    }
                }
            }
        }
    }
    val selected = processes?.firstOrNull { it.id == selectedId }
    if (selected != null) AlertDialog(
        onDismissRequest = { if (!ending) selectedId = null },
        title = { Text(stringResource(R.string.processes_end)) },
        text = { Text(stringResource(R.string.processes_end_warning)) },
        confirmButton = {
            TextButton(enabled = !ending && selected.canEnd, onClick = {
                ending = true
                runtime.product.endProcess(selected.token) { result ->
                    ending = false
                    failed = result.isFailure
                    selectedId = null
                    refresh++
                }
            }) { Text(stringResource(R.string.processes_end)) }
        },
        dismissButton = { TextButton(enabled = !ending, onClick = { selectedId = null }) { Text(stringResource(R.string.cancel)) } },
    )
}
