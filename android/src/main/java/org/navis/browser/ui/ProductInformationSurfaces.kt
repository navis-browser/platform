/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.navis.browser.BuildConfig
import org.navis.browser.R
import org.navis.browser.engine.AndroidWindowRuntime
import org.navis.browser.extensions.ExtensionClass
import org.navis.browser.extensions.ExtensionStateObserver
import org.navis.browser.pages.AndroidInternalPages

private data class SupportInformation(
    val facts: Map<String, String> = emptyMap(),
    val loading: Boolean = true,
    val failed: Boolean = false,
)

@Composable
private fun rememberSupportInformation(runtime: AndroidWindowRuntime, refresh: Int): SupportInformation {
    var state by remember(runtime) { mutableStateOf(SupportInformation()) }
    DisposableEffect(runtime, refresh) {
        var live = true
        state = SupportInformation()
        runtime.product.support { result ->
            if (live) state = SupportInformation(result.getOrNull().orEmpty(), false, result.isFailure)
        }
        onDispose { live = false }
    }
    return state
}

@Composable
internal fun AboutSurface(runtime: AndroidWindowRuntime, onBack: () -> Unit, onNavigate: (String) -> Unit) {
    var refresh by remember { mutableStateOf(0) }
    val support = rememberSupportInformation(runtime, refresh)
    ProductPageScaffold(stringResource(R.string.about_navis), onBack) {
        NavisBrandMark(Modifier.size(80.dp).padding(top = 12.dp), NavisBrandMotion.COUNTERFLOW)
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displaySmall,
            modifier = Modifier.padding(top = 20.dp, bottom = 12.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            SelectionContainer {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    InformationFact(stringResource(R.string.about_version), BuildConfig.NAVIS_PRODUCT_VERSION_DISPLAY)
                    support.facts["engineVersion"]?.takeIf(String::isNotBlank)?.let {
                        InformationFact(stringResource(R.string.about_engine), it)
                    }
                    support.facts["applicationBuildId"]?.takeIf(String::isNotBlank)?.let {
                        InformationFact(stringResource(R.string.about_build), it)
                    }
                }
            }
        }
        if (support.loading) CircularProgressIndicator(Modifier.size(24.dp).padding(top = 4.dp))
        if (support.failed) {
            Text(stringResource(R.string.support_engine_unavailable), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { refresh++ }) { Text(stringResource(R.string.site_retry)) }
        }
        SettingsAction(stringResource(R.string.about_support), stringResource(R.string.support_summary)) {
            onNavigate("navis://support/")
        }
        SettingsAction(stringResource(R.string.about_internal_pages), "navis://urls/") {
            onNavigate("navis://urls/")
        }
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.about_copyright), style = MaterialTheme.typography.bodySmall)
                OpenSourceAttribution(onNavigate)
            }
        }
    }
}

@Composable
internal fun SupportSurface(runtime: AndroidWindowRuntime, onBack: () -> Unit) {
    var refresh by remember { mutableStateOf(0) }
    var copied by remember { mutableStateOf(false) }
    val support = rememberSupportInformation(runtime, refresh)
    val context = LocalContext.current
    val host = runtime.extensions
    var extensions by remember(host) { mutableStateOf(host.extensionState) }
    DisposableEffect(host) {
        val observer = ExtensionStateObserver { extensions = it }
        host.addExtensionObserver(observer)
        onDispose { host.removeExtensionObserver(observer) }
    }
    val enabled = stringResource(R.string.support_enabled)
    val disabled = stringResource(R.string.support_disabled)
    val rows = mutableListOf(
        stringResource(R.string.about_version) to BuildConfig.NAVIS_PRODUCT_VERSION_DISPLAY,
        stringResource(R.string.support_package) to BuildConfig.APPLICATION_ID,
        stringResource(R.string.support_version_code) to BuildConfig.VERSION_CODE.toString(),
        stringResource(R.string.support_system) to "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        stringResource(R.string.support_architecture) to
            (System.getProperty("os.arch") ?: Build.SUPPORTED_ABIS.firstOrNull().orEmpty()),
    )
    // Only supported, meaningful facts are rendered; new backend fields do not
    // silently become product settings or expose browsing contents in reports.
    for ((key, label) in listOf(
        "engineVersion" to R.string.about_engine,
        "engineBuildId" to R.string.support_engine_build,
        "applicationBuildId" to R.string.about_build,
        "locale" to R.string.support_locale,
        "availableLocales" to R.string.support_available_languages,
        "multiprocess" to R.string.support_remote_content,
        "fission" to R.string.support_site_isolation,
        "remoteType" to R.string.support_process_type,
        "diagnostics.cpuDetails" to R.string.support_cpu_details,
        "diagnostics.adapterDescription2" to R.string.support_gpu_secondary,
        "diagnostics.adapterRAM" to R.string.support_gpu_memory,
        "diagnostics.graphicsFeatures" to R.string.support_gpu_features,
        "diagnostics.graphicsFailureDetails" to R.string.support_gpu_failures,
        "diagnostics.graphicsCrashGuards" to R.string.support_gpu_guards,
        "diagnostics.sandboxFeatures" to R.string.support_sandbox_features,
        "diagnostics.modifiedPreferences" to R.string.support_modified_preferences,
    )) {
        support.facts[key]?.takeIf(String::isNotBlank)?.let { value ->
            rows += stringResource(label) to when (value) {
                "true" -> enabled
                "false" -> disabled
                else -> value
            }
        }
    }
    val builtIn = stringResource(R.string.support_builtin)
    val user = stringResource(R.string.support_user_extension)
    val temporary = stringResource(R.string.support_temporary)
    val extensionRows = extensions.extensions.sortedBy { it.name.lowercase() }.map { extension ->
        extension.name to buildString {
            append(extension.version)
            append(" · ")
            append(if (extension.enabled) enabled else disabled)
            append(" · ")
            append(when (extension.extensionClass) {
                ExtensionClass.APPLICATION_BUILT_IN -> builtIn
                ExtensionClass.USER -> user
                ExtensionClass.TEMPORARY -> temporary
            })
            append('\n')
            append(extension.id)
        }
    }
    val extensionsTitle = stringResource(R.string.support_extensions)
    val reportTitle = stringResource(R.string.about_support)
    val noExtensions = stringResource(R.string.support_no_extensions)
    val unavailable = stringResource(R.string.support_engine_unavailable)
    val report = buildString {
        append("Navis\n\n")
        rows.forEach { (label, value) -> append("$label: $value\n") }
        if (support.failed) append("\n$unavailable\n")
        append("\n$extensionsTitle\n")
        if (extensionRows.isEmpty()) append(noExtensions)
        extensionRows.forEach { (name, value) -> append("$name: $value\n") }
    }
    ProductPageScaffold(reportTitle, onBack) {
        Text(stringResource(R.string.support_summary), modifier = Modifier.padding(top = 12.dp))
        FilledTonalButton(enabled = !support.loading && !extensions.loading, onClick = {
            context.getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText(reportTitle, report))
            copied = true
        }) { Text(stringResource(if (copied) R.string.support_copied else R.string.support_copy)) }
        if (support.loading) CircularProgressIndicator(Modifier.size(24.dp))
        if (support.failed) {
            Text(unavailable, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { copied = false; refresh++ }) { Text(stringResource(R.string.site_retry)) }
        }
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                rows.forEach { (label, value) -> InformationFact(label, value) }
                HorizontalDivider()
                Text(extensionsTitle, style = MaterialTheme.typography.titleLarge)
                if (!extensions.loading && extensionRows.isEmpty()) Text(noExtensions)
                extensionRows.forEach { (label, value) -> InformationFact(label, value) }
            }
        }
    }
}

@Composable
internal fun InternalUrlsSurface(onBack: () -> Unit, onNavigate: (String) -> Unit) {
    ProductPageScaffold(stringResource(R.string.about_internal_pages), onBack) {
        Text(stringResource(R.string.support_internal_pages_summary), modifier = Modifier.padding(top = 12.dp))
        AndroidInternalPages.pages.forEach { page ->
            SettingsAction(stringResource(page.title), page.uri) { onNavigate(page.uri) }
        }
    }
}

@Composable
private fun OpenSourceAttribution(onNavigate: (String) -> Unit) {
    val before = stringResource(R.string.credits_attribution_before)
    val between = stringResource(R.string.credits_attribution_between)
    val software = stringResource(R.string.credits_title)
    val after = stringResource(R.string.credits_attribution_after)
    val style = TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.primary))
    Text(buildAnnotatedString {
        append(before)
        withLink(LinkAnnotation.Clickable("gecko", style) {
            onNavigate("https://firefox-source-docs.mozilla.org/overview/gecko.html")
        }) { append("Mozilla Gecko") }
        append(between)
        withLink(LinkAnnotation.Clickable("credits", style) { onNavigate("navis://credits/") }) { append(software) }
        append(after)
    }, style = MaterialTheme.typography.bodySmall)
}

@Composable
internal fun CreditsSurface(runtime: AndroidWindowRuntime, onBack: () -> Unit, onNavigate: (String) -> Unit) {
    var refresh by remember { mutableStateOf(0) }
    var licenses by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    DisposableEffect(runtime, refresh) {
        var active = true
        runtime.product.licenses { result ->
            if (active) { licenses = result.getOrNull(); failed = result.isFailure }
        }
        onDispose { active = false }
    }
    ProductPageScaffold(stringResource(R.string.credits_title), onBack) {
        Text(stringResource(R.string.about_copyright))
        OpenSourceAttribution(onNavigate)
        SettingsAction("Mozilla Public License 2.0", "Mozilla Gecko") {
            onNavigate("https://www.mozilla.org/MPL/2.0/")
        }
        SettingsAction("uBlock Origin", "Copyright Raymond Hill and contributors · GNU GPL v3") {
            onNavigate("https://github.com/gorhill/uBlock")
        }
        Text(stringResource(R.string.credits_licenses), style = MaterialTheme.typography.titleLarge)
        if (licenses == null && !failed) CircularProgressIndicator()
        if (failed) {
            Text(stringResource(R.string.profile_operation_failed), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { failed = false; refresh++ }) { Text(stringResource(R.string.site_retry)) }
        }
        licenses?.let { text -> SelectionContainer { Text(text, style = MaterialTheme.typography.bodySmall) } }
    }
}
