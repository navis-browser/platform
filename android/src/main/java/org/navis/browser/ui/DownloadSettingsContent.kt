/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.ui

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.navis.browser.R
import org.navis.browser.settings.AndroidSettingsHost
import org.navis.browser.settings.AndroidSettingsSnapshot
import org.navis.browser.settings.AndroidSettingsUpdateResult

@Composable
internal fun DownloadSettingsContent(
    host: AndroidSettingsHost,
    state: AndroidSettingsSnapshot,
    changed: (AndroidSettingsUpdateResult) -> Unit,
    failed: () -> Unit,
) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                host.setDownloadDirectory(uri.toString())
            }.onSuccess(changed).onFailure { failed() }
        }
    }
    val systemDownloads = state.downloadDirectory.isEmpty()
    SettingsAction(
        stringResource(R.string.settings_download_choose),
        summary = stringResource(if (systemDownloads) R.string.settings_download_system_summary
            else R.string.settings_download_other_summary),
        value = stringResource(R.string.settings_download_current,
            if (systemDownloads) stringResource(R.string.settings_download_default)
            else downloadDirectoryDisplayName(state.downloadDirectory,
                stringResource(R.string.settings_download_internal_storage),
                stringResource(R.string.settings_download_external_storage),
                stringResource(R.string.settings_download_custom_folder))),
    ) {
        runCatching { picker.launch(state.downloadDirectory.takeIf(String::isNotEmpty)?.let(Uri::parse)) }.onFailure { failed() }
    }
    TextButton(
        onClick = { changed(host.setDownloadDirectory("")) },
        enabled = !systemDownloads,
        modifier = Modifier.padding(horizontal = 16.dp),
        contentPadding = PaddingValues(0.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
    ) {
        Text(stringResource(R.string.settings_download_restore_default))
    }
    SettingsToggle(stringResource(R.string.settings_download_ask), stringResource(R.string.settings_download_ask_summary), state.askBeforeSaving) {
        changed(host.setDownloadOption("askBeforeSaving", it))
    }
    SettingsToggle(stringResource(R.string.settings_download_private), stringResource(R.string.settings_download_private_summary), state.deletePrivateOnExit) {
        changed(host.setDownloadOption("deletePrivateOnExit", it))
    }
    SettingsToggle(stringResource(R.string.settings_download_open), stringResource(R.string.settings_download_open_summary), state.openWhenComplete) {
        changed(host.setDownloadOption("openWhenComplete", it))
    }
}

/** Display only: provider document IDs are not filesystem paths or access grants. */
internal fun downloadDirectoryDisplayName(
    raw: String,
    internalStorage: String,
    externalStorage: String,
    fallback: String,
): String = runCatching {
    val uri = Uri.parse(raw)
    if (uri.authority != "com.android.externalstorage.documents") return@runCatching fallback
    val treeId = DocumentsContract.getTreeDocumentId(uri)
    if (':' !in treeId) return@runCatching fallback
    val storage = if (treeId.substringBefore(':') == "primary") internalStorage else externalStorage
    val path = treeId.substringAfter(':').split('/').filter(String::isNotEmpty)
    (listOf(storage) + path).joinToString(" / ")
}.getOrDefault(fallback)
