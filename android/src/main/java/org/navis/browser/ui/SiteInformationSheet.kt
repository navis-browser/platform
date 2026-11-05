/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import org.navis.browser.R
import org.navis.browser.api.BrowserSessionState
import org.navis.browser.api.BrowserState
import org.navis.browser.api.BrowserStateObserver
import org.navis.browser.api.LoadingState
import org.navis.browser.engine.AndroidWindowRuntime
import org.navis.browser.engine.SiteInformation

internal fun siteInformationStillCurrent(opened: BrowserSessionState, state: BrowserState): Boolean {
    val current = state.activeSession ?: return false
    if (current.id != opened.id || current.nativeNewTab != opened.nativeNewTab ||
        current.nativeRoute != opened.nativeRoute) return false
    if (opened.nativeNewTab || opened.nativeRoute != null) return true
    return current.navigation.url == opened.navigation.url &&
        current.navigation.security == opened.navigation.security && !current.navigation.crashed &&
        !(current.navigation.revision != opened.navigation.revision &&
            current.navigation.loading in setOf(LoadingState.PENDING, LoadingState.VISIBLE))
}

internal fun verifiedSiteOrganization(info: SiteInformation, now: Long): String? {
    if (info.kind != "secure") return null
    val certificate = info.certificate ?: return null
    val start = certificate.validFrom ?: return null
    val end = certificate.validTo ?: return null
    return if (now in start..end) sanitizeSiteOrganization(certificate.organization) else null
}

internal fun canClearSiteInformation(info: SiteInformation): Boolean = info.canClearData &&
    info.kind in setOf("secure", "insecure", "broken", "local") && info.navigationToken.isNotBlank()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SiteInformationSheet(
    runtime: AndroidWindowRuntime,
    session: BrowserSessionState,
    nativePage: Boolean = session.nativeNewTab || session.nativeRoute != null,
    onDismiss: () -> Unit,
) {
    val opened = remember { session }
    val dismiss by rememberUpdatedState(onDismiss)
    var information by remember { mutableStateOf<SiteInformation?>(null) }
    var failed by remember { mutableStateOf(false) }
    var retry by remember { mutableStateOf(0) }
    var showCertificate by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    var cleared by remember { mutableStateOf(false) }
    var actionFailed by remember { mutableStateOf(false) }
    var permissionBusy by remember { mutableStateOf(false) }
    var permissionFailed by remember { mutableStateOf(false) }
    var devices by remember { mutableStateOf(session.devices) }
    val configuration = LocalConfiguration.current
    val locale = configuration.locales[0]

    DisposableEffect(runtime, opened) {
        val observer = BrowserStateObserver { state ->
            if (!siteInformationStillCurrent(opened, state)) dismiss()
            else state.activeSession?.let { devices = it.devices }
        }
        runtime.addObserver(observer)
        onDispose { runtime.removeObserver(observer) }
    }
    DisposableEffect(runtime, opened.id, nativePage, retry) {
        var live = true
        if (!nativePage) {
            failed = false
            runtime.product.siteInformation(opened.id) { result ->
                if (live && siteInformationStillCurrent(opened, runtime.state)) {
                    information = result.getOrNull()
                    failed = result.isFailure
                }
            }
        }
        onDispose { live = false }
    }

    val info = information
    fun changePermission(kind: String, decision: String = "ask", originToken: String? = null) {
        if (info != null && !permissionBusy && siteInformationStillCurrent(opened, runtime.state)) {
            permissionBusy = true
            permissionFailed = false
            val complete: (Result<SiteInformation>) -> Unit = { result ->
                permissionBusy = false
                if (siteInformationStillCurrent(opened, runtime.state)) {
                    result.onSuccess { information = it }.onFailure { permissionFailed = true; retry++ }
                }
            }
            if (originToken == null) runtime.product.stopSiteDevices(opened.id, info.navigationToken, kind, complete)
            else runtime.product.setSitePermission(opened.id, info.navigationToken, originToken, kind, decision, complete)
        }
    }
    val kind = if (nativePage) "internal" else info?.kind ?: "unknown"
    val builtInExtension = kind == "extension" && info?.builtInExtension == true
    val title = stringResource(when (kind) {
        "internal" -> R.string.site_desktop_siteinfo_internal_toolbartitle
        "extension" -> if (builtInExtension) R.string.site_desktop_siteinfo_extension_title else R.string.site_sheet_extension_title
        "error" -> R.string.site_desktop_siteinfo_error_title
        "secure", "insecure", "broken", "local" -> R.string.site_desktop_siteinfo_title
        else -> R.string.site_desktop_siteinfo_pagetitle
    })
    val pageHost = remember(info?.origin, opened.navigation.url) {
        runCatching {
            val uri = java.net.URI(info?.origin?.takeIf(String::isNotBlank) ?: opened.navigation.url)
            uri.host?.let { if (uri.port == -1) it else "$it:${uri.port}" }
        }.getOrNull()
    }
    val subtitle = when {
        builtInExtension -> stringResource(R.string.site_desktop_siteinfo_extension_subtitle)
        nativePage -> "navis://${opened.nativeRoute ?: "newtab"}"
        else -> pageHost ?: info?.host?.takeIf(String::isNotBlank) ?: opened.navigation.url
    }
    val status = stringResource(when (kind) {
        "internal" -> R.string.site_desktop_siteinfo_internal_status
        "secure" -> R.string.site_desktop_siteinfo_secure_status
        "insecure" -> R.string.site_desktop_siteinfo_insecure_status
        "broken" -> R.string.site_desktop_siteinfo_broken_status
        "local" -> R.string.site_desktop_siteinfo_local_status
        "extension" -> if (builtInExtension) R.string.site_desktop_siteinfo_extension_status else R.string.site_sheet_extension_status
        "error" -> R.string.site_desktop_siteinfo_error_status
        else -> R.string.site_desktop_siteinfo_unknown_status
    })
    val description = stringResource(when (kind) {
        "internal" -> R.string.site_desktop_siteinfo_internal_description
        "secure" -> R.string.site_desktop_siteinfo_secure_description
        "insecure" -> R.string.site_desktop_siteinfo_insecure_description
        "broken" -> R.string.site_desktop_siteinfo_broken_description
        "local" -> R.string.site_desktop_siteinfo_local_description
        "extension" -> if (builtInExtension) R.string.site_desktop_siteinfo_extension_description else R.string.site_sheet_extension_description
        "error" -> R.string.site_desktop_siteinfo_error_description
        else -> R.string.site_desktop_siteinfo_unknown_description
    })
    val warning = kind in setOf("insecure", "broken", "error")
    val summaryScroll = rememberScrollState()
    val certificateScroll = rememberScrollState()

    NavisModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = (configuration.screenHeightDp * .85f).dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showCertificate) {
                    IconButton(onClick = { showCertificate = false }) {
                        Icon(painterResource(R.drawable.ic_site_back),
                            stringResource(R.string.site_desktop_siteinfo_back))
                    }
                } else {
                    Icon(painterResource(siteIdentityIcon(kind)), contentDescription = null, modifier = Modifier.size(24.dp),
                        tint = if (warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(if (showCertificate) stringResource(R.string.site_desktop_siteinfo_certificatedetails) else title,
                    style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).semantics { heading() })
                IconButton(onClick = onDismiss) {
                    Icon(painterResource(R.drawable.ic_site_close), stringResource(R.string.site_desktop_siteinfo_close))
                }
            }
            HorizontalDivider()
            Column(
                Modifier.weight(1f, fill = false).fillMaxWidth()
                    .verticalScroll(if (showCertificate) certificateScroll else summaryScroll)
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                SelectionContainer {
                    Text(subtitle, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium.copy(
                        textDirection = if (builtInExtension) TextDirection.Content else TextDirection.Ltr),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (showCertificate && info?.certificate != null) {
                    val certificate = info.certificate
                    val dateFormat = remember(locale) { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.LONG, locale) }
                    val unavailable = stringResource(R.string.site_desktop_common_unavailable)
                    val rows = buildList {
                        add(Triple(stringResource(R.string.site_desktop_certificate_subject), certificate.subject, false))
                        add(Triple(stringResource(R.string.site_desktop_certificate_organization), certificate.organization.ifBlank { certificate.commonName }, false))
                        add(Triple(stringResource(R.string.site_desktop_certificate_issuer), certificate.issuer, false))
                        add(Triple(stringResource(R.string.site_desktop_certificate_validfrom), certificate.validFrom?.let { dateFormat.format(Date(it)) } ?: unavailable, false))
                        add(Triple(stringResource(R.string.site_desktop_certificate_validuntil), certificate.validTo?.let { dateFormat.format(Date(it)) } ?: unavailable, false))
                        add(Triple(stringResource(R.string.site_desktop_certificate_serialnumber), certificate.serial, true))
                        add(Triple(stringResource(R.string.site_desktop_certificate_sha256fingerprint), certificate.fingerprint, true))
                        add(Triple(stringResource(R.string.site_desktop_certificate_publickeydigest), certificate.publicKeyDigest, true))
                        add(Triple(stringResource(R.string.site_desktop_certificate_protocol), info.protocol, true))
                        add(Triple(stringResource(R.string.site_desktop_certificate_cipher), info.cipher, true))
                        add(Triple(stringResource(R.string.site_desktop_certificate_keyexchange), info.keyExchange, true))
                        add(Triple(stringResource(R.string.site_desktop_certificate_signature), info.signature, true))
                    }.map { (label, value, technical) -> Triple(label, value.ifBlank { unavailable }, technical) }
                    val chain = info.certificateChain.mapIndexed { index, item ->
                        val name = item.displayName.ifBlank { item.commonName }.ifBlank { item.subject }
                            .ifBlank { stringResource(R.string.site_desktop_certificate_entry, (index + 1).toString()) }
                        val issuer = item.issuerCommonName.ifBlank { item.issuer }.ifBlank { unavailable }
                        name to stringResource(R.string.site_desktop_certificate_issuedby, issuer)
                    }
                    val chainTitle = stringResource(R.string.site_desktop_certificate_chain)
                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            for ((label, value, technical) in rows) SiteCertificateFact(label, value, technical)
                            if (chain.isNotEmpty()) {
                                HorizontalDivider()
                                Text(chainTitle, style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.semantics { heading() })
                                for ((name, issuer) in chain) {
                                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(name, style = MaterialTheme.typography.titleSmall)
                                        Text(issuer, style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                } else {
                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(status, style = MaterialTheme.typography.titleMedium,
                                color = if (warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                            Text(description, style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (!nativePage && info == null) {
                        if (failed) {
                            Text(stringResource(R.string.operation_failed), color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = { retry++ }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                Text(stringResource(R.string.site_retry))
                            }
                        } else Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(24.dp))
                            Text(stringResource(R.string.site_sheet_loading), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    if (kind !in setOf("internal", "error") && info != null) {
                        if (info.permissions.isNotEmpty()) {
                            HorizontalDivider()
                            Text(stringResource(R.string.site_permissions_heading),
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.semantics { heading() })
                            for ((index, permission) in info.permissions.withIndex()) {
                                if (index == 0 || info.permissions[index - 1].originToken != permission.originToken) {
                                    Text(if (permission.embedded) stringResource(R.string.site_permission_embedded_origin, permission.origin)
                                        else permission.origin, style = MaterialTheme.typography.titleSmall,
                                        modifier = Modifier.semantics { heading() })
                                }
                                var expanded by remember(permission.kind, permission.originToken, info.navigationToken) { mutableStateOf(false) }
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(stringResource(sitePermissionLabel(permission.kind)), modifier = Modifier.weight(1f))
                                    Box {
                                        OutlinedButton(enabled = !permissionBusy, onClick = { expanded = true }) {
                                            Text(stringResource(sitePermissionDecision(permission.state)))
                                        }
                                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                            for (decision in permission.decisions) DropdownMenuItem(
                                                text = { Text(stringResource(sitePermissionDecision(decision))) },
                                                onClick = { expanded = false; changePermission(permission.kind, decision, permission.originToken) },
                                            )
                                        }
                                    }
                                }
                                if (permission.temporary) Text(stringResource(R.string.site_permission_temporary),
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            if (devices.camera || devices.microphone) FilledTonalButton(
                                enabled = !permissionBusy,
                                onClick = { changePermission("camera", "ask") }, modifier = Modifier.fillMaxWidth(),
                            ) { Text(stringResource(R.string.site_permission_stop_media)) }
                            if (permissionFailed) Text(stringResource(R.string.site_permission_update_failed),
                                color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        }
                        val canClear = canClearSiteInformation(info)
                        HorizontalDivider()
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.site_desktop_siteinfo_cookiestitle),
                                style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                            Text(if (canClear) stringResource(R.string.site_desktop_siteinfo_storeddata, info.host)
                                else stringResource(R.string.site_desktop_siteinfo_unavailable),
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            FilledTonalButton(enabled = canClear && !clearing && !cleared,
                                onClick = { confirmClear = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                Text(stringResource(R.string.site_desktop_siteinfo_cleardata))
                            }
                        }
                    }
                    if (cleared) Text(stringResource(R.string.site_desktop_siteinfo_datacleared, info?.host.orEmpty()))
                    if (actionFailed) Text(stringResource(R.string.site_desktop_siteinfo_dataclearfailed), color = MaterialTheme.colorScheme.error)
                    if (!nativePage && info?.certificate != null) {
                        TextButton(onClick = { showCertificate = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Icon(painterResource(R.drawable.ic_site_certificate), contentDescription = null,
                                modifier = Modifier.padding(end = 12.dp).size(20.dp))
                            Text(stringResource(R.string.site_desktop_siteinfo_certificatedetails), modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
    if (confirmClear && info != null && canClearSiteInformation(info) && !nativePage) {
        AlertDialog(
            onDismissRequest = { if (!clearing) confirmClear = false },
            title = { Text(stringResource(R.string.settings_clear_site_data)) },
            text = { Text(stringResource(R.string.site_clear_confirm, info.origin)) },
            confirmButton = {
                TextButton(enabled = !clearing, onClick = {
                    if (!siteInformationStillCurrent(opened, runtime.state)) {
                        dismiss()
                    } else {
                        clearing = true
                        actionFailed = false
                        runtime.product.clearSiteData(opened.id, info.navigationToken) { result ->
                            clearing = false
                            confirmClear = false
                            cleared = result.isSuccess
                            actionFailed = result.isFailure
                        }
                    }
                }) { Text(stringResource(if (clearing) R.string.operation_working else R.string.site_clear)) }
            },
            dismissButton = { TextButton(enabled = !clearing, onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

private fun sitePermissionLabel(kind: String): Int = when (kind) {
    "camera" -> R.string.site_permission_camera
    "microphone" -> R.string.site_permission_microphone
    "geolocation" -> R.string.site_permission_location
    "desktop-notification" -> R.string.site_permission_notifications
    "persistent-storage" -> R.string.site_permission_storage
    else -> R.string.site_permissions_heading
}

private fun sitePermissionDecision(value: String): Int = when (value) {
    "allow" -> R.string.site_permission_allow
    "block" -> R.string.site_permission_block
    else -> R.string.site_permission_ask
}

@Composable
private fun SiteCertificateFact(label: String, value: String, technical: Boolean = false) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, modifier = Modifier.fillMaxWidth(),
            style = if (technical) MaterialTheme.typography.bodyMedium.copy(
                fontFamily = FontFamily.Monospace, textDirection = TextDirection.Ltr)
            else MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun InformationFact(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
