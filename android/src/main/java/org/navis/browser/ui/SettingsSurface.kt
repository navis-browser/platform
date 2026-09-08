/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.navis.browser.R
import org.navis.browser.engine.AndroidWindowRuntime
import org.navis.browser.settings.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
internal fun rememberBrowserSettings(host: AndroidSettingsHost): AndroidSettingsSnapshot {
    var state by remember(host) { mutableStateOf(host.snapshot) }
    DisposableEffect(host) {
        val observer = AndroidSettingsObserver { state = it }
        host.addObserver(observer)
        onDispose { host.removeObserver(observer) }
    }
    return state
}

@Composable
internal fun SettingsSurface(
    runtime: AndroidWindowRuntime,
    onBack: () -> Unit,
    onNavigate: (String) -> Unit,
    onRelaunch: () -> Unit,
    section: String = "",
) {
    val host = runtime.product.settings
    val state = rememberBrowserSettings(host)
    var query by remember(section) { mutableStateOf("") }
    var dialog by remember(section) { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var feedbackJob by remember { mutableStateOf<Job?>(null) }
    fun report(message: Int?) {
        // Replace stale results rather than queueing old failures behind new actions.
        feedbackJob?.cancel()
        snackbar.currentSnackbarData?.dismiss()
        feedbackJob = message?.let { scope.launch { snackbar.showSnackbar(context.getString(it)) } }
    }
    fun changed(result: AndroidSettingsUpdateResult) {
        report(if (result != AndroidSettingsUpdateResult.UPDATED && result != AndroidSettingsUpdateResult.UNCHANGED) {
            R.string.operation_failed
        } else null)
    }
    val sections = listOf("search" to R.string.settings_search_engine, "privacy" to R.string.settings_section_privacy,
        "appearance" to R.string.settings_appearance, "downloads" to R.string.download_notification_channel,
        "help" to R.string.about_navis)
    val showRootNavigation = section.isEmpty() && query.isBlank()
    fun inSection(value: String) = section == value || query.isNotBlank()
    val title = sections.firstOrNull { it.first == section }?.second ?: R.string.settings
    // The header is session Back, not a breadcrumb: navigating to the root pushes a loop.
    ProductPageScaffold(stringResource(title), onBack,
        snackbarHost = { SnackbarHost(snackbar) }) {
        ProductPageSearchField(query, { query = it }, stringResource(R.string.settings_search))
        if (showRootNavigation) for ((route, label) in sections) {
            SettingsAction(stringResource(label)) { onNavigate("navis://settings/$route") }
        }
        val searchLabel = stringResource(R.string.settings_search_engine)
        val languageLabel = stringResource(R.string.settings_language)
        val barLabel = stringResource(R.string.settings_bookmark_bar)
        val historyLabel = stringResource(R.string.settings_history_button)
        val showSearch = inSection("search") && matchesSettings(query, searchLabel, "google baidu bing duckduckgo")
        val showLanguage = inSection("appearance") && matchesSettings(query, languageLabel, "language locale english chinese 中文 语言")
        val showBar = inSection("appearance") && matchesSettings(query, barLabel, "bookmarks 书签")
        val showHistory = inSection("appearance") && matchesSettings(query, historyLabel, "history toolbar shortcut 历史记录 工具栏 快捷入口")
        if (showSearch || showLanguage || showBar || showHistory) {
            SettingsSection(stringResource(R.string.settings_section_general)) {
                if (showSearch) {
                    SearchSettingsContent(runtime.product, state, ::changed)
                }
                if (showLanguage) {
                    SettingsAction(languageLabel, stringResource(R.string.settings_language_summary), languageName(state.selectedDisplayLanguage)) {
                        dialog = "language"
                    }
                    SettingsSupportingText(stringResource(R.string.settings_language_active, languageName(state.activeDisplayLanguage)))
                    if (state.displayLanguageRestartRequired) RelaunchRow(onRelaunch)
                }
                if (showBar) {
                    SettingsToggle(barLabel, stringResource(R.string.settings_bookmark_bar_summary), state.bookmarkBarVisible) {
                        changed(host.setBookmarkBarVisible(it))
                    }
                }
                if (showHistory) {
                    SettingsToggle(historyLabel, stringResource(R.string.settings_history_button_summary), state.historyButton) {
                        changed(host.setHistoryButtonVisible(it))
                    }
                }
            }
        }
        val isolationLabel = stringResource(R.string.settings_process_isolation)
        val cleanLabel = stringResource(R.string.settings_clean_links)
        val dataLabel = stringResource(R.string.settings_site_data)
        val showIsolation = inSection("privacy") && matchesSettings(query, isolationLabel, "process memory security 进程 隔离 内存")
        val showClean = inSection("privacy") && matchesSettings(query, cleanLabel, "privacy tracking 清理 隐私 跟踪")
        val showData = inSection("privacy") && matchesSettings(query, dataLabel, "cookies privacy storage Cookie 隐私 存储")
        if (showIsolation || showClean || showData) {
            SettingsSection(stringResource(R.string.settings_section_privacy)) {
                if (showIsolation) {
                    SettingsAction(isolationLabel, stringResource(R.string.settings_process_summary), isolationName(state.selectedProcessIsolation)) {
                        dialog = "isolation"
                    }
                    SettingsSupportingText(stringResource(R.string.settings_process_invariant))
                    if (state.processIsolationRestartRequired) RelaunchRow(onRelaunch)
                }
                if (showClean) {
                    SettingsToggle(cleanLabel, stringResource(R.string.settings_clean_links_summary), state.cleanLinksEnabled, !working) { value ->
                        working = true
                        runtime.product.setCleanLinks(value) { result ->
                            working = false
                            report(if (result.isFailure) R.string.operation_failed else null)
                        }
                    }
                    SettingsSupportingText(stringResource(R.string.settings_clean_links_scope))
                    TextButton(onClick = { onNavigate(QUERY_STRIPPING_REFERENCE) }, modifier = Modifier.padding(horizontal = 4.dp)) {
                        Text(stringResource(R.string.settings_clean_links_reference))
                    }
                }
                if (showData) {
                    SettingsAction(dataLabel, stringResource(R.string.settings_site_data_summary)) { dialog = "clear" }
                }
            }
        }
        val showAppearance = inSection("appearance") && matchesSettings(query,
            stringResource(R.string.settings_appearance), "theme light dark accent color 主题 浅色 深色 强调色")
        if (showAppearance) SettingsSection(stringResource(R.string.settings_appearance)) {
            SettingsThemeChoices(state.theme, !working) { value ->
                working = true
                runtime.product.setAppearance("theme", value) { result ->
                    working = false; report(if (result.isFailure) R.string.operation_failed else null)
                }
            }
            SettingsAction(stringResource(R.string.settings_accent), value = state.accent) { if (!working) dialog = "accent" }
        }
        val showDownloads = inSection("downloads") && matchesSettings(query,
            stringResource(R.string.download_notification_channel), "save directory folder download 下载 保存 文件夹")
        if (showDownloads) SettingsSection(stringResource(R.string.download_notification_channel)) {
            DownloadSettingsContent(host, state, ::changed) { report(R.string.operation_failed) }
        }
        val helpLabel = stringResource(R.string.about_navis)
        val showHelp = query.isNotBlank() && matchesSettings(query, helpLabel, "version build help 版本 帮助 关于")
        if (showHelp) {
            SettingsAction(helpLabel) { onNavigate("navis://settings/help") }
        }
        if (!(showRootNavigation || showSearch || showLanguage || showBar || showHistory || showIsolation || showClean || showData || showAppearance || showDownloads || showHelp)) {
            ProductPageEmptyState(stringResource(R.string.profile_no_matches))
        }
    }
    when (dialog) {
        "accent" -> {
            var value by remember { mutableStateOf(NativePromptValuePolicy.initialColor(state.accent)) }
            var colorValid by remember { mutableStateOf(true) }
            AlertDialog(onDismissRequest = { if (!working) dialog = null },
                title = { Text(stringResource(R.string.settings_accent)) },
                text = { NavisColorPicker(value, { value = it }, enabled = !working,
                    onValidityChange = { colorValid = it }) },
                confirmButton = { TextButton(enabled = !working && colorValid, onClick = {
                    if (!working && colorValid) {
                        working = true
                        runtime.product.setAppearance("accent", value.lowercase()) { result ->
                            working = false; if (result.isSuccess) dialog = null
                            report(if (result.isFailure) R.string.operation_failed else null)
                        }
                    }
                }) { Text(stringResource(R.string.settings_save)) } },
                dismissButton = { TextButton(enabled = !working, onClick = { dialog = null }) { Text(stringResource(R.string.cancel)) } })
        }
        "language" -> SettingsChoiceDialog(
            stringResource(R.string.settings_language), state.selectedDisplayLanguage.setting,
            AndroidDisplayLanguage.entries.map { Triple(it.setting, languageName(it), "") },
            { dialog = null }, { changed(host.selectDisplayLanguage(it)); dialog = null },
        )
        "isolation" -> SettingsChoiceDialog(
            stringResource(R.string.settings_process_isolation), state.selectedProcessIsolation.setting,
            AndroidProcessIsolationMode.entries.map {
                Triple(it.setting, isolationName(it), stringResource(when (it) {
                    AndroidProcessIsolationMode.FULL -> R.string.settings_process_full_summary
                    AndroidProcessIsolationMode.SELECTIVE -> R.string.settings_process_selective_summary
                    AndroidProcessIsolationMode.SHARED -> R.string.settings_process_shared_summary
                }))
            },
            { dialog = null }, { changed(host.selectProcessIsolation(it)); dialog = null },
        )
        "clear" -> AlertDialog(
            onDismissRequest = { if (!working) dialog = null },
            title = { Text(stringResource(R.string.settings_clear_site_data_confirm)) },
            text = { Text(stringResource(R.string.settings_site_data_summary)) },
            confirmButton = {
                TextButton(enabled = !working, onClick = {
                    working = true
                    runtime.product.clearAllSiteData { result ->
                        working = false
                        dialog = null
                        report(if (result.isSuccess) R.string.settings_site_data_cleared else R.string.operation_failed)
                    }
                }) { Text(stringResource(if (working) R.string.operation_working else R.string.clear)) }
            },
            dismissButton = { TextButton(enabled = !working, onClick = { dialog = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

internal fun matchesSettings(query: String, title: String, keywords: String): Boolean =
    query.isBlank() || (title + " " + keywords).contains(query.trim(), ignoreCase = true)

@Composable
private fun SettingsThemeChoices(selected: String, enabled: Boolean, onSelect: (String) -> Unit) {
    val choices = listOf("system" to R.string.settings_theme_system,
        "light" to R.string.settings_theme_light, "dark" to R.string.settings_theme_dark)
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((mode, label) in choices) {
                Column(Modifier.weight(1f).clip(MaterialTheme.shapes.medium)
                    .selectable(selected == mode, enabled, Role.RadioButton) { onSelect(mode) }
                    .padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemePreview(mode)
                    RadioButton(selected == mode, onClick = null, enabled = enabled)
                    Text(stringResource(label), style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

@Composable
private fun ThemePreview(mode: String) {
    val accent = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().height(56.dp).clip(MaterialTheme.shapes.small)) {
        fun half(left: Float, width: Float, dark: Boolean) {
            val surface = if (dark) Color(0xff202124) else Color(0xfff8f9fa)
            val chrome = if (dark) Color(0xff3c4043) else Color(0xffdadce0)
            drawRect(surface, Offset(left, 0f), Size(width, size.height))
            drawRect(chrome, Offset(left, 0f), Size(width, size.height * .3f))
            drawRoundRect(accent, Offset(left + width * .12f, size.height * .48f),
                Size(width * .6f, size.height * .1f), CornerRadius(2.dp.toPx()))
            drawRoundRect(chrome, Offset(left + width * .12f, size.height * .68f),
                Size(width * .75f, size.height * .1f), CornerRadius(2.dp.toPx()))
        }
        if (mode == "system") {
            half(0f, size.width / 2, false)
            half(size.width / 2, size.width / 2, true)
        } else half(0f, size.width, mode == "dark")
    }
}

@Composable
private fun languageName(value: AndroidDisplayLanguage): String = stringResource(when (value) {
    AndroidDisplayLanguage.SYSTEM -> R.string.settings_language_system
    AndroidDisplayLanguage.EN_US -> R.string.settings_language_english
    AndroidDisplayLanguage.ZH_CN -> R.string.settings_language_chinese
})

@Composable
private fun isolationName(value: AndroidProcessIsolationMode): String = stringResource(when (value) {
    AndroidProcessIsolationMode.FULL -> R.string.settings_process_full
    AndroidProcessIsolationMode.SELECTIVE -> R.string.settings_process_selective
    AndroidProcessIsolationMode.SHARED -> R.string.settings_process_shared
})

@Composable
private fun SettingsChoiceDialog(
    title: String,
    selected: String,
    choices: List<Triple<String, String, String>>,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).selectableGroup()) {
                choices.forEach { (id, label, detail) ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(MaterialTheme.shapes.medium)
                            .selectable(selected == id, role = Role.RadioButton) { onSelect(id) }
                            .padding(horizontal = 8.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected == id, onClick = null)
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(label)
                            if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun RelaunchRow(onRelaunch: () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.settings_restart_required), style = MaterialTheme.typography.bodySmall)
        FilledTonalButton(onClick = onRelaunch) { Text(stringResource(R.string.settings_relaunch)) }
    }
}

@Composable
internal fun SettingsAction(title: String, summary: String = "", value: String = "", onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(MaterialTheme.shapes.medium)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (summary.isNotEmpty()) Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (value.isNotEmpty()) Text(value, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
}

@Composable
internal fun SettingsToggle(title: String, summary: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(MaterialTheme.shapes.medium)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f))
            Text(summary, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.38f))
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = null)
    }
}

@Composable
internal fun ProductPageScaffold(
    title: String,
    onBack: () -> Unit,
    snackbarHost: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(modifier = Modifier.navisNativePageInsets(), snackbarHost = snackbarHost, topBar = {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.navisTopBarInsets()) {
            ProductPageHeader(title, onBack)
        }
    }) { padding ->
        Box(Modifier.fillMaxSize().navisPageContentPadding(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.productPageWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 16.dp).navigationBarsPadding().padding(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp), content = content,
            )
        }
    }
}

/** Shared reading width and search affordance for settings and native management pages. */
internal fun Modifier.productPageWidth(): Modifier = widthIn(max = 840.dp).fillMaxWidth()

@Composable
internal fun ProductPageHeader(title: String, onBack: () -> Unit, action: (@Composable () -> Unit)? = null) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Row(Modifier.navisTopBarContentInsets().productPageWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.navigate_back)) }
            Text(title, modifier = Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.titleLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            action?.invoke()
        }
    }
}

/** IconButtons are separate accessibility targets, so include their row's identity. */
@Composable
internal fun productRowActionDescription(action: String, title: String, detail: String = ""): String {
    val target = if (detail.isNotBlank() && detail != title) "$title, $detail" else title
    return stringResource(R.string.ui_row_named_action, action, target)
}

@Composable
internal fun ProductPageSearchField(query: String, onQuery: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    TextField(
        value = query, onValueChange = onQuery, singleLine = true,
        label = { Text(label) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = if (query.isNotEmpty()) ({
            IconButton(onClick = { onQuery("") }) {
                Icon(Icons.Default.Close, stringResource(R.string.page_search_clear))
            }
        }) else null,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); keyboard?.hide() }),
        shape = MaterialTheme.shapes.extraLarge,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp),
    )
}

/** A shared, quiet empty state for filtered and empty native collections. */
@Composable
internal fun ProductPageEmptyState(message: String, modifier: Modifier = Modifier) {
    Text(message, style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp))
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp).semantics { heading() })
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), content = content)
        }
    }
}

@Composable
private fun SettingsSupportingText(value: String) {
    Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp))
}

private const val QUERY_STRIPPING_REFERENCE =
    "https://firefox-source-docs.mozilla.org/toolkit/components/antitracking/anti-tracking/query-stripping/index.html"
