// SPDX-License-Identifier: MPL-2.0
package org.navis.browser.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.util.Locale
import org.navis.browser.R
import org.navis.browser.api.BrowserSessionState
import org.navis.browser.api.ContentTermination
import org.navis.browser.api.PageTranslationChoices
import org.navis.browser.api.PageTranslationPhase
import org.navis.browser.api.PageTranslationState
import org.navis.browser.engine.AndroidWindowRuntime

internal class PageTranslationController(
    private val runtime: AndroidWindowRuntime,
    private val session: BrowserSessionState?,
) {
    var visible by mutableStateOf(false)
        private set
    var preparing by mutableStateOf(false)
        private set
    var submitting by mutableStateOf(false)
        private set
    var choices by mutableStateOf<PageTranslationChoices?>(null)
        private set
    var source by mutableStateOf("")
    var target by mutableStateOf("")
    var error by mutableStateOf<String?>(null)
        private set
    private var serial = 0L
    private var disposed = false
    private fun current() = !disposed && session != null && runtime.state.activeSession?.let {
        it.id == session.id && it.navigationId == session.navigationId &&
            it.nativeRoute == null && it.navigation.contentTermination == ContentTermination.NONE
    } == true

    fun open(): Boolean {
        if (!current()) return false
        visible = true; preparing = true; error = null
        val request = ++serial
        runtime.prepareTranslation(checkNotNull(session).id).whenComplete { result, failure ->
            if (request == serial && current()) {
                preparing = false
                if (failure != null) error = runtime.state.activeSession?.translation?.error ?: "language-load-failure"
                else { choices = result; if (target.isEmpty()) target = result.state.targetLanguage }
            }
        }
        return true
    }

    fun translate() {
        if (!current() || preparing || submitting || choices == null) return
        submitting = true; error = null
        val request = ++serial
        runtime.translatePage(checkNotNull(session).id, source, target).whenComplete { _, failure ->
            if (request == serial && current()) {
                submitting = false
                if (failure != null) error = runtime.state.activeSession?.translation?.error ?: "engine-load-failure"
            }
        }
    }

    fun restore() {
        if (!current() || preparing || submitting) return
        val request = ++serial
        runtime.restoreOriginalPage(checkNotNull(session).id).whenComplete { _, failure ->
            if (request == serial && current() && failure != null) error = "restore-failure"
        }
    }

    fun close() { ++serial; visible = false; preparing = false; submitting = false }
    fun dispose() { close(); disposed = true }
}

@Composable
internal fun rememberPageTranslation(runtime: AndroidWindowRuntime, session: BrowserSessionState?): PageTranslationController {
    val controller = remember(runtime, session?.id, session?.navigationId, session?.nativeRoute, session?.navigation?.contentTermination) {
        PageTranslationController(runtime, session)
    }
    DisposableEffect(controller) { onDispose { controller.dispose() } }
    return controller
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TranslationLanguage(label: String, selected: String, options: List<Pair<String, String>>, enabled: Boolean,
    onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (enabled) expanded = it }, modifier = modifier) {
        OutlinedTextField(value = options.firstOrNull { it.first == selected }?.second.orEmpty(), onValueChange = {},
            readOnly = true, singleLine = true, enabled = enabled, label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable))
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (id, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { expanded = false; onSelect(id) }) }
        }
    }
}

@Composable
internal fun PageTranslationPanel(controller: PageTranslationController, native: PageTranslationState) {
    if (!controller.visible) return
    BackHandler { controller.close() }
    val busy = controller.preparing || controller.submitting || native.phase in setOf(PageTranslationPhase.DOWNLOADING, PageTranslationPhase.TRANSLATING)
    val error = controller.error ?: native.error
    val languages = controller.choices
    val locale = Locale.getDefault()
    fun name(tag: String) = Locale.forLanguageTag(tag).getDisplayName(locale).ifEmpty { tag }
    val detected = native.detectedLanguage.ifEmpty { languages?.state?.detectedLanguage.orEmpty() }
    val automatic = if (detected.isEmpty()) stringResource(R.string.translate_auto) else stringResource(R.string.translate_detected, name(detected))
    val source = controller.source.ifEmpty { detected }
    val status = when {
        error == "unsupported-device" -> stringResource(R.string.translate_error_device)
        error == "unsupported-language" -> stringResource(R.string.translate_error_language)
        error == "restore-failure" -> stringResource(R.string.translate_error_restore)
        error == "language-load-failure" -> stringResource(R.string.translate_error_catalog)
        error != null -> stringResource(R.string.translate_error_files)
        controller.preparing -> stringResource(R.string.translate_preparing)
        native.phase == PageTranslationPhase.DOWNLOADING -> stringResource(R.string.translate_downloading)
        busy -> stringResource(R.string.translate_working)
        native.phase == PageTranslationPhase.TRANSLATED -> stringResource(R.string.translate_done)
        else -> stringResource(R.string.translate_choose)
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_translate), null)
                Text(stringResource(R.string.translate_title), Modifier.weight(1f).padding(horizontal = 8.dp), style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = controller::close) { Icon(Icons.Default.Close, stringResource(R.string.translate_close)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TranslationLanguage(stringResource(R.string.translate_source), controller.source,
                    listOf("" to automatic) + languages?.sourceLanguages.orEmpty().map { it to name(it) }, !busy,
                    { controller.source = it }, Modifier.weight(1f))
                TranslationLanguage(stringResource(R.string.translate_target), controller.target,
                    languages?.targetLanguages.orEmpty().map { it to name(it) }, !busy,
                    { controller.target = it }, Modifier.weight(1f))
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(status, Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.translate_local), style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (native.sourceLanguage.isNotEmpty()) TextButton(onClick = controller::restore, enabled = !busy) {
                    Text(stringResource(R.string.translate_original))
                }
                Button(onClick = { if (languages == null) controller.open() else controller.translate() },
                    enabled = !busy && error != "unsupported-device" && (languages == null || source.isNotEmpty() && source != controller.target)) {
                    Text(stringResource(if (error == null) R.string.translate_action else R.string.translate_retry))
                }
            }
        }
    }
}
