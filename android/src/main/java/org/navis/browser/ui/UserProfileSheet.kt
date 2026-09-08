/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.navis.browser.R
import org.navis.browser.persistence.UserProfile
import org.navis.browser.persistence.UserProfileStore
import java.io.File

internal fun userProfileInitial(name: String, fallback: String): String =
    name.trim().firstOrNull()?.uppercase() ?: fallback.trim().firstOrNull()?.uppercase() ?: "?"

internal fun userProfileColor(accent: String): Color? =
    runCatching { Color(android.graphics.Color.parseColor(accent)) }.getOrNull()

@Composable
internal fun UserAvatar(
    name: String,
    accent: String,
    fallbackName: String,
    size: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    val background = userProfileColor(accent) ?: MaterialTheme.colorScheme.primary
    val foreground = if (background.luminance() > .5f) Color.Black else Color.White
    Box(
        modifier = modifier.size(size).clip(CircleShape).background(background),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            userProfileInitial(name, fallbackName),
            color = foreground,
            style = if (size >= 48.dp) MaterialTheme.typography.headlineSmall
            else MaterialTheme.typography.titleMedium,
            maxLines = 1,
        )
    }
}

/** One editable identity per profile; opening another profile preserves the current window. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UserProfileSheet(
    filesRoot: File,
    appName: String,
    onSwitchProfile: (UserProfile) -> Unit,
    onRestart: (Boolean) -> Unit,
    onIdentityChanged: (UserProfile) -> Unit,
    onDismiss: () -> Unit,
    onManage: () -> Unit,
    asPage: Boolean = false,
) {
    var generation by remember { mutableIntStateOf(0) }
    val store = remember(filesRoot, generation) { UserProfileStore.fileBacked(filesRoot) }
    var snapshot by remember(filesRoot, generation) { mutableStateOf(store.snapshot()) }
    fun refresh() {
        generation++
    }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var editing by remember { mutableStateOf<UserProfile?>(null) }
    var creating by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<UserProfile?>(null) }
    var failed by remember { mutableStateOf(false) }
    val current = snapshot.profiles.firstOrNull { it.id == snapshot.currentId }

    val content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit = {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                if (current != null) {
                    UserAvatar(current.userName, current.accent, appName, 48.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            current.userName.ifBlank { appName },
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            listOf(
                                stringResource(R.string.user_profile_current),
                                current.takeIf { it.id == snapshot.defaultId }
                                    ?.let { stringResource(R.string.user_profile_default) },
                            ).filterNotNull().joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { editing = current }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.user_profile_edit))
                    }
                } else {
                    Text(
                        stringResource(R.string.user_profile_title),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, stringResource(R.string.done))
                }
            }
            if (failed) {
                Text(
                    stringResource(R.string.profile_operation_failed),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            HorizontalDivider()
            Text(stringResource(R.string.user_profile_manage), style = MaterialTheme.typography.titleMedium)
            for (profile in snapshot.profiles) {
                var menu by remember(profile.id, generation) { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    UserAvatar(profile.userName, profile.accent, appName, 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            profile.userName.ifBlank { appName },
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        val badges = listOf(
                            profile.takeIf { it.id == snapshot.currentId }
                                ?.let { stringResource(R.string.user_profile_current) },
                            profile.takeIf { it.id == snapshot.defaultId }
                                ?.let { stringResource(R.string.user_profile_default) },
                        ).filterNotNull().joinToString(" · ")
                        if (badges.isNotEmpty()) {
                            Text(
                                badges,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Box {
                        IconButton(onClick = { menu = true }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Default.MoreVert, stringResource(R.string.more_options))
                        }
                        NavisDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            NavisMenuItem(
                                text = { Text(stringResource(R.string.user_profile_launch)) },
                                leadingIcon = { Icon(painterResource(R.drawable.ic_open_full_page), null) },
                                itemIndex = 0, itemCount = 3,
                                enabled = profile.id != snapshot.currentId,
                                onClick = {
                                    menu = false
                                    onSwitchProfile(profile)
                                    onDismiss()
                                },
                            )
                            NavisMenuItem(
                                text = { Text(stringResource(R.string.user_profile_set_default)) },
                                leadingIcon = { Icon(Icons.Default.Star, null) },
                                itemIndex = 1, itemCount = 3,
                                enabled = profile.id != snapshot.defaultId,
                                onClick = {
                                    menu = false
                                    runCatching { store.setDefault(profile.id); refresh() }
                                        .onFailure { failed = true }
                                },
                            )
                            NavisMenuItem(
                                text = { Text(stringResource(R.string.user_profile_remove)) },
                                leadingIcon = { Icon(Icons.Default.Delete, null) },
                                itemIndex = 2, itemCount = 3,
                                enabled = profile.id !in snapshot.inUseIds && profile.id != snapshot.currentId &&
                                    snapshot.profiles.size > 1,
                                onClick = { menu = false; removing = profile },
                            )
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FilledTonalButton(
                    onClick = { creating = true },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.user_profile_create))
                }
                FilledTonalButton(
                    onClick = { onRestart(false) },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.user_profile_restart))
                }
            }
            TextButton(onClick = { onRestart(true) }) {
                Text(stringResource(R.string.user_profile_restart_safe))
            }
            if (!asPage) TextButton(onClick = onManage) {
                Text(stringResource(R.string.user_profile_manage))
            }
            Spacer(Modifier.height(8.dp))
    }
    if (asPage) {
        ProductPageScaffold(stringResource(R.string.user_profile_manage), onDismiss, content = content)
    } else ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
    }
    if (creating || editing != null) {
        val original = editing
        UserProfileEditor(
            title = stringResource(if (original == null) R.string.user_profile_create else R.string.user_profile_edit),
            initialName = original?.userName.orEmpty(),
            initialAccent = original?.accent ?: "#0b57d0",
            onDismiss = { creating = false; editing = null },
            onSubmit = { name, accent ->
                val result = runCatching {
                    if (original == null) store.create(name, accent)
                    else store.updateIdentity(original.id, name, accent)
                }
                if (result.isSuccess) {
                    creating = false
                    editing = null
                    failed = false
                    refresh()
                    if (original == null) {
                        onSwitchProfile(result.getOrThrow())
                        onDismiss()
                    } else onIdentityChanged(result.getOrThrow())
                } else {
                    failed = true
                }
            },
        )
    }
    removing?.let { target ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.user_profile_remove_title)) },
            text = { Text(stringResource(R.string.user_profile_remove_message, target.userName.ifBlank { appName })) },
            confirmButton = {
                TextButton(onClick = {
                    runCatching {
                        store.remove(target.id, deleteFiles = false)
                        removing = null
                        refresh()
                    }.onFailure { failed = true }
                }) { Text(stringResource(R.string.user_profile_keep_files)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { removing = null }) { Text(stringResource(R.string.cancel)) }
                    TextButton(onClick = {
                        runCatching {
                            store.remove(target.id, deleteFiles = true)
                            removing = null
                            refresh()
                        }.onFailure { failed = true }
                    }) { Text(stringResource(R.string.user_profile_delete_files)) }
                }
            },
        )
    }
}

@Composable
private fun UserProfileEditor(
    title: String,
    initialName: String,
    initialAccent: String,
    onDismiss: () -> Unit,
    onSubmit: (String, String) -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    var accent by remember(initialAccent) { mutableStateOf(NativePromptValuePolicy.initialColor(initialAccent)) }
    var choosingColor by remember { mutableStateOf(false) }
    if (choosingColor) {
        var colorDraft by remember { mutableStateOf(accent) }
        var colorValid by remember { mutableStateOf(true) }
        AlertDialog(
            onDismissRequest = { choosingColor = false },
            title = { Text(stringResource(R.string.user_profile_accent)) },
            text = {
                NavisColorPicker(value = colorDraft, onValueChange = { colorDraft = it },
                    onValidityChange = { colorValid = it }, showPresets = false)
            },
            confirmButton = {
                TextButton(enabled = colorValid, onClick = {
                    if (colorValid) { accent = colorDraft; choosingColor = false }
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { choosingColor = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    } else AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    UserAvatar(name, accent, "N", 48.dp)
                    Spacer(Modifier.width(12.dp))
                    OutlinedTextField(
                        value = name,
                        onValueChange = { if (it.length <= 80) name = it },
                        label = { Text(stringResource(R.string.user_profile_username)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(stringResource(R.string.user_profile_accent), style = MaterialTheme.typography.labelLarge)
                NavisColorPresets(value = accent, onValueChange = { accent = it })
                TextButton(onClick = { choosingColor = true },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.color_picker_custom), modifier = Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
                        modifier = Modifier.size(20.dp))
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { if (name.isNotBlank()) onSubmit(name, accent) },
            ) { Text(stringResource(R.string.settings_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
