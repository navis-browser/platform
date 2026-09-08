/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.navis.browser.R
import org.navis.browser.extensions.ExtensionClass
import org.navis.browser.extensions.ExtensionHost
import org.navis.browser.extensions.ExtensionNotice
import org.navis.browser.extensions.ExtensionPermissionKind
import org.navis.browser.extensions.ExtensionSignature
import org.navis.browser.extensions.ExtensionSnapshot
import org.navis.browser.extensions.ExtensionStateObserver

@Composable
@OptIn(ExperimentalLayoutApi::class)
@Suppress("UNUSED_PARAMETER") // Kept for the existing caller while developer tools move to their own route.
internal fun ExtensionManagerSurface(
    host: ExtensionHost,
    developerMode: Boolean,
    onBack: () -> Unit,
    onOpenOptions: () -> Unit,
) {
    var state by remember { mutableStateOf(host.extensionState) }
    var pendingUninstall by remember { mutableStateOf<ExtensionSnapshot?>(null) }
    val observer = remember { ExtensionStateObserver { state = it } }
    val packagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(host::installLocalExtension)
    }
    val choosePackage = {
        packagePicker.launch(arrayOf("application/x-xpinstall", "application/zip", "application/octet-stream"))
    }
    val openOptions: (String) -> Unit = { id ->
        host.openExtensionOptions(id) { opened ->
            if (opened) onOpenOptions()
        }
    }

    DisposableEffect(host) {
        host.addExtensionObserver(observer)
        host.refreshExtensions()
        onDispose { host.removeExtensionObserver(observer) }
    }

    Scaffold(
        modifier = Modifier.navisNativePageInsets(),
        topBar = {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.navisTopBarInsets(),
            ) {
                ProductPageHeader(stringResource(R.string.extensions), onBack)
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().navisPageContentPadding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.productPageWidth().navigationBarsPadding(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = choosePackage,
                            enabled = !state.loading && state.busyExtensionId == null,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(stringResource(R.string.install_extension_from_file))
                        }
                        if (state.loading || state.busyExtensionId != null) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp).align(Alignment.CenterVertically))
                        }
                    }
                }

                state.notice?.let { notice ->
                    item {
                        NoticeCard(notice = notice, onDismiss = host::dismissExtensionNotice)
                    }
                }

                extensionSection(
                    title = { Text(stringResource(R.string.built_in_extensions)) },
                    emptyLabel = { Text(stringResource(R.string.no_built_in_extensions)) },
                    extensions = state.extensions.filter {
                        it.extensionClass == ExtensionClass.APPLICATION_BUILT_IN
                    },
                    busyExtensionId = state.busyExtensionId,
                    onEnabledChange = host::setExtensionEnabled,
                    onPrivateAccessChange = host::setExtensionPrivateAccess,
                    onPinnedChange = host::setExtensionPinned,
                    onOpenOptions = openOptions,
                    onUpdateFromFile = choosePackage,
                    onUninstall = { pendingUninstall = it },
                )

                extensionSection(
                    title = { Text(stringResource(R.string.user_extensions)) },
                    emptyLabel = { Text(stringResource(R.string.no_user_extensions)) },
                    extensions = state.extensions.filter { it.extensionClass != ExtensionClass.APPLICATION_BUILT_IN },
                    busyExtensionId = state.busyExtensionId,
                    onEnabledChange = host::setExtensionEnabled,
                    onPrivateAccessChange = host::setExtensionPrivateAccess,
                    onPinnedChange = host::setExtensionPinned,
                    onOpenOptions = openOptions,
                    onUpdateFromFile = choosePackage,
                    onUninstall = { pendingUninstall = it },
                )
            }
        }
    }

    pendingUninstall?.let { extension ->
        AlertDialog(
            onDismissRequest = { pendingUninstall = null },
            title = { Text(stringResource(R.string.remove_extension_title)) },
            text = { Text(stringResource(R.string.remove_extension_message, extension.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingUninstall = null
                        host.uninstallExtension(extension.id)
                    },
                ) {
                    Text(stringResource(R.string.remove))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingUninstall = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
internal fun ExtensionPermissionDialog(host: ExtensionHost) {
    var state by remember { mutableStateOf(host.extensionState) }
    val observer = remember { ExtensionStateObserver { state = it } }

    DisposableEffect(host) {
        host.addExtensionObserver(observer)
        onDispose { host.removeExtensionObserver(observer) }
    }

    val request = state.pendingPermission ?: return
    AlertDialog(
        onDismissRequest = { host.respondToExtensionPermission(request.token, false) },
        title = {
            Text(
                stringResource(
                    when (request.kind) {
                        ExtensionPermissionKind.INSTALL -> R.string.install_extension_title
                        ExtensionPermissionKind.UPDATE -> R.string.update_extension_title
                        ExtensionPermissionKind.OPTIONAL -> R.string.extension_permission_title
                    },
                ),
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(request.extensionName, style = MaterialTheme.typography.titleMedium)
                if (request.kind == ExtensionPermissionKind.OPTIONAL) {
                    Text(stringResource(R.string.extension_optional_permission_summary))
                } else {
                    Text(
                        if (request.installedVersion == null) {
                            stringResource(R.string.extension_candidate_version, request.candidateVersion)
                        } else {
                            stringResource(
                                R.string.extension_update_versions,
                                request.installedVersion,
                                request.candidateVersion,
                            )
                        },
                    )
                }
                request.sourceName?.let { sourceName ->
                    Text(stringResource(R.string.extension_source_file, sourceName))
                }
                PermissionList(
                    if (request.kind == ExtensionPermissionKind.OPTIONAL) {
                        R.string.requested_permissions
                    } else {
                        R.string.required_permissions
                    },
                    request.permissions,
                )
                PermissionList(R.string.site_access, request.origins)
                PermissionList(R.string.data_collection_permissions, request.dataCollectionPermissions)
                if (request.kind != ExtensionPermissionKind.OPTIONAL) {
                    Text(
                        stringResource(R.string.private_access_off_by_default),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { host.respondToExtensionPermission(request.token, true) }) {
                Text(
                    stringResource(
                        if (request.kind == ExtensionPermissionKind.UPDATE) {
                            R.string.update
                        } else {
                            R.string.allow
                        },
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = { host.respondToExtensionPermission(request.token, false) }) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

private fun androidx.compose.foundation.lazy.LazyListScope.extensionSection(
    title: @Composable () -> Unit,
    emptyLabel: @Composable () -> Unit,
    extensions: List<ExtensionSnapshot>,
    busyExtensionId: String?,
    onEnabledChange: (String, Boolean) -> Unit,
    onPrivateAccessChange: (String, Boolean) -> Unit,
    onPinnedChange: (String, Boolean) -> Unit,
    onOpenOptions: (String) -> Unit,
    onUpdateFromFile: () -> Unit,
    onUninstall: (ExtensionSnapshot) -> Unit,
) {
    item {
        Column(Modifier.fillMaxWidth().padding(top = 8.dp).semantics { heading() }) {
            ProvideTextStyle(MaterialTheme.typography.titleSmall) { title() }
        }
    }
    if (extensions.isEmpty()) {
        item {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(modifier = Modifier.padding(16.dp)) {
                    emptyLabel()
                }
            }
        }
    } else {
        items(extensions, key = { it.id }) { extension ->
            ExtensionCard(
                extension = extension,
                busy = busyExtensionId != null,
                onEnabledChange = { onEnabledChange(extension.id, it) },
                onPrivateAccessChange = { onPrivateAccessChange(extension.id, it) },
                onPinnedChange = { onPinnedChange(extension.id, it) },
                onOpenOptions = onOpenOptions,
                onUpdateFromFile = onUpdateFromFile,
                onUninstall = { onUninstall(extension) },
            )
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun ExtensionCard(
    extension: ExtensionSnapshot,
    busy: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onPrivateAccessChange: (Boolean) -> Unit,
    onPinnedChange: (Boolean) -> Unit,
    onOpenOptions: (String) -> Unit,
    onUpdateFromFile: () -> Unit,
    onUninstall: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(MaterialTheme.shapes.medium)
                    .toggleable(value = extension.enabled, enabled = !busy && extension.canChangeEnabled,
                        role = Role.Switch, onValueChange = onEnabledChange),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                extension.icon?.let { icon ->
                    Image(
                        bitmap = icon.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        extension.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        stringResource(R.string.extension_version, extension.version),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                }
                Switch(
                    checked = extension.enabled,
                    onCheckedChange = null,
                    enabled = !busy && extension.canChangeEnabled,
                )
            }
            if (extension.description.isNotBlank()) {
                Text(extension.description, style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                stringResource(
                    when (extension.signature) {
                        ExtensionSignature.APPLICATION_VERIFIED -> R.string.signature_application_verified
                        ExtensionSignature.MOZILLA_SIGNED -> R.string.signature_mozilla_signed
                        ExtensionSignature.UNVERIFIED -> R.string.signature_unverified
                    },
                ),
                color = if (extension.signature == ExtensionSignature.UNVERIFIED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
                style = MaterialTheme.typography.labelMedium,
            )
            HorizontalDivider()
            if (extension.hasToolbarAction) {
                SettingsToggle(
                    title = stringResource(R.string.pin_extension_to_toolbar),
                    summary = stringResource(R.string.pin_extension_to_toolbar_summary),
                    checked = extension.pinnedToToolbar,
                    enabled = !busy && extension.enabled && !extension.blockedSideLoad &&
                        extension.extensionClass != ExtensionClass.TEMPORARY,
                    onChange = onPinnedChange,
                )
                HorizontalDivider()
            }
            SettingsToggle(
                title = stringResource(R.string.run_in_private_tabs),
                summary = stringResource(
                    if (extension.extensionClass == ExtensionClass.APPLICATION_BUILT_IN) {
                        R.string.run_in_private_tabs_builtin_summary
                    } else {
                        R.string.run_in_private_tabs_summary
                    },
                ),
                checked = extension.allowedInPrivateBrowsing,
                enabled = !busy && extension.privateBrowsingAvailable,
                onChange = onPrivateAccessChange,
            )
            PermissionSummary(extension)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (extension.hasOptions) {
                    FilledTonalButton(
                        onClick = { onOpenOptions(extension.id) },
                        enabled = !busy && extension.enabled,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.extension_options))
                    }
                }
                if (extension.canUninstall) {
                    TextButton(onClick = onUninstall, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                        Icon(Icons.Default.Delete, null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.remove))
                    }
                }
                if (extension.canUpdate) {
                    TextButton(onClick = onUpdateFromFile, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.extension_update_from_file))
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionSummary(extension: ExtensionSnapshot) {
    var expanded by remember(extension.id) { mutableStateOf(false) }
    val permissions = (extension.requiredPermissions + extension.requiredOrigins).distinct()
    if (permissions.isEmpty()) {
        Text(stringResource(R.string.no_required_permissions), style = MaterialTheme.typography.bodySmall)
        return
    }
    Text(
        stringResource(R.string.required_permissions_count, permissions.size),
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        permissions.joinToString(separator = " · "),
        style = MaterialTheme.typography.bodySmall,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
    )
    TextButton(onClick = { expanded = true }, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(stringResource(R.string.view_required_permissions))
    }
    if (expanded) AlertDialog(
        onDismissRequest = { expanded = false },
        title = { Text(stringResource(R.string.extension_permissions_named, extension.name)) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PermissionList(R.string.required_permissions, extension.requiredPermissions.distinct())
                PermissionList(R.string.site_access, extension.requiredOrigins.distinct())
            }
        },
        confirmButton = { TextButton(onClick = { expanded = false }) { Text(stringResource(R.string.close)) } },
    )
}

@Composable
private fun NoticeCard(notice: ExtensionNotice, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(
                    when (notice) {
                        ExtensionNotice.BUILT_IN_UNAVAILABLE -> R.string.extension_notice_builtin_unavailable
                        ExtensionNotice.FILE_READ_FAILED -> R.string.extension_notice_file_read_failed
                        ExtensionNotice.FILE_TOO_LARGE -> R.string.extension_notice_file_too_large
                        ExtensionNotice.INSTALL_FAILED -> R.string.extension_notice_install_failed
                        ExtensionNotice.SIGNATURE_REQUIRED -> R.string.extension_notice_signature_required
                        ExtensionNotice.DOWNGRADE_REJECTED -> R.string.extension_notice_downgrade_rejected
                        ExtensionNotice.UNSUPPORTED_CAPABILITY -> R.string.extension_notice_unsupported_capability
                        ExtensionNotice.OPERATION_FAILED -> R.string.extension_notice_operation_failed
                        ExtensionNotice.INSTALL_COMPLETE -> R.string.extension_notice_install_complete
                        ExtensionNotice.UPDATE_COMPLETE -> R.string.extension_notice_update_complete
                        ExtensionNotice.REMOVED -> R.string.extension_notice_removed
                    },
                ),
                color = when (notice) {
                    ExtensionNotice.INSTALL_COMPLETE,
                    ExtensionNotice.UPDATE_COMPLETE,
                    ExtensionNotice.REMOVED,
                    -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.error
                },
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp)) {
                Text(stringResource(R.string.dismiss))
            }
        }
    }
}

@Composable
private fun PermissionList(label: Int, values: List<String>) {
    Text(stringResource(label), style = MaterialTheme.typography.labelLarge)
    Text(
        if (values.isEmpty()) {
            stringResource(R.string.none)
        } else {
            values.joinToString(separator = "\n")
        },
        style = MaterialTheme.typography.bodySmall,
    )
}
