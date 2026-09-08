/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.ui

import android.net.Uri
import android.text.format.DateFormat
import android.widget.DatePicker
import android.widget.TimePicker
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.navis.browser.R
import org.navis.browser.api.BrowserPrompt
import org.navis.browser.api.ChoicePromptMode
import org.navis.browser.api.ChoicePromptOption
import org.navis.browser.api.BrowserTargetRuntime
import org.navis.browser.api.DateTimePromptKind
import org.navis.browser.api.PromptResponse
import org.navis.browser.api.SessionId
import org.navis.browser.api.SitePermissionKind
import org.navis.browser.api.SitePermissionRequest
import org.navis.browser.api.SitePermissionDecision
import org.navis.browser.api.TargetRequestObserver

@Composable
internal fun TargetDialogs(
    runtime: BrowserTargetRuntime,
    activeSessionId: SessionId?,
) {
    var state by remember(runtime) { mutableStateOf(runtime.targetState) }
    val observer = remember(runtime) {
        TargetRequestObserver { next -> state = next }
    }
    DisposableEffect(runtime) {
        runtime.addTargetObserver(observer)
        onDispose { runtime.removeTargetObserver(observer) }
    }

    val prompt = state.prompt?.takeIf { it.sessionId == activeSessionId }
    if (prompt != null) {
        BrowserPromptDialog(
            prompt = prompt,
            onResponse = { response -> runtime.respondToPrompt(prompt.id, response) },
        )
    } else {
        state.sitePermission?.takeIf { it.sessionId == activeSessionId }?.let { request ->
            LaunchedEffect(request.id) {
                runtime.notifySitePermissionShown(request.id)
            }
            SitePermissionDialog(
                request = request,
                saveFailed = state.sitePermissionSaveFailed,
                onResponse = { decision ->
                    runtime.respondToSitePermission(request.id, decision)
                },
            )
        }
    }
}

@Composable
private fun BrowserPromptDialog(
    prompt: BrowserPrompt,
    onResponse: (PromptResponse) -> Unit,
) {
    when (prompt) {
        is BrowserPrompt.Alert -> AlertDialog(
            onDismissRequest = { onResponse(PromptResponse.Dismiss) },
            title = { PromptTitle(prompt.title) },
            text = { PromptMessage(prompt.message) },
            confirmButton = {
                TextButton(onClick = { onResponse(PromptResponse.Dismiss) }) {
                    Text(stringResource(R.string.ok))
                }
            },
        )
        is BrowserPrompt.Confirm -> AlertDialog(
            onDismissRequest = { onResponse(PromptResponse.Dismiss) },
            title = { PromptTitle(prompt.title) },
            text = { PromptMessage(prompt.message) },
            confirmButton = {
                TextButton(onClick = { onResponse(PromptResponse.Accept) }) {
                    Text(stringResource(R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { onResponse(PromptResponse.Reject) }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
        is BrowserPrompt.Text -> TextPromptDialog(prompt, onResponse)
        is BrowserPrompt.Authentication -> AuthenticationPromptDialog(prompt, onResponse)
        is BrowserPrompt.Choice -> ChoicePromptDialog(prompt, onResponse)
        is BrowserPrompt.DateTime -> DateTimePromptDialog(prompt, onResponse)
        is BrowserPrompt.ColorPicker -> ColorPromptDialog(prompt, onResponse)
        is BrowserPrompt.SaveLogin -> SaveLoginPromptDialog(prompt, onResponse)
        is BrowserPrompt.SelectLogin -> SelectLoginPromptDialog(prompt, onResponse)
    }
}

@Composable
private fun ChoicePromptDialog(
    prompt: BrowserPrompt.Choice,
    onResponse: (PromptResponse) -> Unit,
) {
    val visibleChoices = remember(prompt.choices) { flattenChoices(prompt.choices) }
    var selectedIds by remember(prompt.id, prompt.choices) {
        mutableStateOf<Set<String>>(
            visibleChoices
                .filter { it.option.selected && it.selectable }
                .mapTo(linkedSetOf()) { it.option.id },
        )
    }
    val multiple = prompt.mode == ChoicePromptMode.MULTIPLE
    AlertDialog(
        onDismissRequest = { onResponse(PromptResponse.Dismiss) },
        title = { Text(prompt.title ?: stringResource(R.string.choose_option)) },
        text = {
            Column {
                prompt.message?.takeIf(String::isNotBlank)?.let {
                    Text(it, modifier = Modifier.padding(bottom = 8.dp))
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .then(if (multiple) Modifier else Modifier.selectableGroup())
                        .verticalScroll(rememberScrollState()),
                ) {
                    visibleChoices.forEach { choice ->
                        when {
                            choice.option.separator -> HorizontalDivider()
                            choice.option.group -> Text(
                                text = choice.option.label,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.semantics { heading() }.padding(
                                    start = (choice.depth * 20).dp,
                                    top = 12.dp,
                                    bottom = 4.dp,
                                ),
                            )
                            else -> {
                                val selected = choice.option.id in selectedIds
                                val selectionModifier = if (multiple) {
                                    Modifier.toggleable(
                                        value = selected,
                                        enabled = choice.selectable,
                                        role = Role.Checkbox,
                                        onValueChange = { checked ->
                                            selectedIds = selectedIds.toMutableSet().apply {
                                                if (checked) add(choice.option.id) else remove(choice.option.id)
                                            }
                                        },
                                    )
                                } else {
                                    Modifier.selectable(
                                        selected = selected,
                                        enabled = choice.selectable,
                                        role = Role.RadioButton,
                                        onClick = {
                                            onResponse(PromptResponse.ChoiceValue(listOf(choice.option.id)))
                                        },
                                    )
                                }
                                ListItem(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 48.dp)
                                        .then(selectionModifier),
                                    headlineContent = { Text(choice.option.label) },
                                    leadingContent = {
                                        Box(Modifier.padding(start = (choice.depth * 20).dp)) {
                                            if (multiple) {
                                                Checkbox(checked = selected, enabled = choice.selectable,
                                                    onCheckedChange = null)
                                            } else {
                                                RadioButton(selected = selected, enabled = choice.selectable,
                                                    onClick = null)
                                            }
                                        }
                                    },
                                    colors = ListItemDefaults.colors(
                                        containerColor = Color.Transparent,
                                        headlineColor = MaterialTheme.colorScheme.onSurface.copy(
                                            alpha = if (choice.selectable) 1f else 0.38f,
                                        ),
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (multiple) {
                TextButton(
                    onClick = {
                        onResponse(PromptResponse.ChoiceValue(selectedIds.toList()))
                    },
                ) {
                    Text(stringResource(R.string.ok))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = { onResponse(PromptResponse.Dismiss) }) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

private data class VisibleChoice(
    val option: ChoicePromptOption,
    val depth: Int,
    val selectable: Boolean,
)

private fun flattenChoices(choices: List<ChoicePromptOption>): List<VisibleChoice> = buildList {
    fun append(options: List<ChoicePromptOption>, depth: Int, parentDisabled: Boolean) {
        options.forEach { option ->
            val disabled = parentDisabled || option.disabled
            add(
                VisibleChoice(
                    option = option,
                    depth = depth,
                    selectable = !disabled && !option.separator && !option.group,
                ),
            )
            append(option.children, depth + 1, disabled)
        }
    }
    append(choices, 0, false)
}

@Composable
private fun DateTimePromptDialog(
    prompt: BrowserPrompt.DateTime,
    onResponse: (PromptResponse) -> Unit,
) {
    val initial = remember(
        prompt.id,
        prompt.defaultValue,
        prompt.minimumValue,
        prompt.maximumValue,
        prompt.stepValue,
    ) {
        NativePromptValuePolicy.initialDateTime(
            prompt.kind,
            prompt.defaultValue,
            prompt.minimumValue,
            prompt.maximumValue,
            prompt.stepValue,
        )
    }
    var selectedDate by remember(prompt.id, initial) { mutableStateOf(initial.date) }
    var selectedTime by remember(prompt.id, initial) { mutableStateOf(initial.time) }
    val includesDate = prompt.kind != DateTimePromptKind.TIME
    val includesTime = prompt.kind == DateTimePromptKind.TIME ||
        prompt.kind == DateTimePromptKind.DATETIME_LOCAL
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = { onResponse(PromptResponse.Dismiss) },
        title = { Text(prompt.title ?: dateTimePromptTitle(prompt.kind)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (includesDate) {
                    AndroidView(
                        factory = { viewContext ->
                            DatePicker(viewContext).apply {
                                NativePromptValuePolicy.minimumDate(prompt.kind, prompt.minimumValue)
                                    ?.toEpochMillis()
                                    ?.let { runCatching { minDate = it } }
                                NativePromptValuePolicy.maximumDate(prompt.kind, prompt.maximumValue)
                                    ?.toEpochMillis()
                                    ?.let { runCatching { maxDate = it } }
                                init(
                                    selectedDate.year,
                                    selectedDate.monthValue - 1,
                                    selectedDate.dayOfMonth,
                                ) { _, year, month, day ->
                                    selectedDate = LocalDate.of(year, month + 1, day)
                                }
                            }
                        },
                        update = { picker ->
                            if (
                                picker.year != selectedDate.year ||
                                picker.month != selectedDate.monthValue - 1 ||
                                picker.dayOfMonth != selectedDate.dayOfMonth
                            ) {
                                picker.updateDate(
                                    selectedDate.year,
                                    selectedDate.monthValue - 1,
                                    selectedDate.dayOfMonth,
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (includesTime) {
                    AndroidView(
                        factory = { viewContext ->
                            TimePicker(viewContext).apply {
                                setIs24HourView(DateFormat.is24HourFormat(context))
                                hour = selectedTime.hour
                                minute = selectedTime.minute
                                setOnTimeChangedListener { _, hour, minute ->
                                    selectedTime = LocalTime.of(hour, minute)
                                }
                            }
                        },
                        update = { picker ->
                            if (picker.hour != selectedTime.hour) picker.hour = selectedTime.hour
                            if (picker.minute != selectedTime.minute) picker.minute = selectedTime.minute
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(
                    onClick = { onResponse(PromptResponse.DateTimeValue("")) },
                ) {
                    Text(stringResource(R.string.clear))
                }
                TextButton(
                    onClick = {
                        onResponse(
                            PromptResponse.DateTimeValue(
                                NativePromptValuePolicy.normalizeDateTime(
                                    prompt.kind,
                                    NativeDateTimeSelection(selectedDate, selectedTime),
                                    prompt.defaultValue,
                                    prompt.minimumValue,
                                    prompt.maximumValue,
                                    prompt.stepValue,
                                ),
                            ),
                        )
                    },
                ) {
                    Text(stringResource(R.string.ok))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = { onResponse(PromptResponse.Dismiss) }) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun dateTimePromptTitle(kind: DateTimePromptKind): String = stringResource(
    when (kind) {
        DateTimePromptKind.DATE -> R.string.choose_date
        DateTimePromptKind.MONTH -> R.string.choose_month
        DateTimePromptKind.WEEK -> R.string.choose_week
        DateTimePromptKind.TIME -> R.string.choose_time
        DateTimePromptKind.DATETIME_LOCAL -> R.string.choose_date_and_time
    },
)

private fun LocalDate.toEpochMillis(): Long =
    atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

@Composable
private fun ColorPromptDialog(
    prompt: BrowserPrompt.ColorPicker,
    onResponse: (PromptResponse) -> Unit,
) {
    val initial = remember(prompt.id, prompt.defaultValue) {
        NativePromptValuePolicy.initialColor(prompt.defaultValue)
    }
    var selected by remember(prompt.id, initial) { mutableStateOf(initial) }
    var colorValid by remember(prompt.id, initial) { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = { onResponse(PromptResponse.Dismiss) },
        title = { Text(prompt.title ?: stringResource(R.string.choose_color)) },
        text = {
            NavisColorPicker(
                value = selected,
                onValueChange = { selected = it },
                onValidityChange = { colorValid = it },
                predefinedValues = prompt.predefinedValues,
            )
        },
        confirmButton = {
            TextButton(enabled = colorValid, onClick = {
                if (colorValid) onResponse(PromptResponse.ColorValue(selected))
            }) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = { onResponse(PromptResponse.Dismiss) }) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun SaveLoginPromptDialog(
    prompt: BrowserPrompt.SaveLogin,
    onResponse: (PromptResponse) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!prompt.saving) onResponse(PromptResponse.Dismiss) },
        title = { Text(stringResource(R.string.save_password)) },
        text = {
            Column {
                Text(stringResource(R.string.save_password_question, displayOrigin(prompt.login.origin)))
                Spacer(Modifier.height(8.dp))
                Text(prompt.login.username.ifBlank { stringResource(R.string.no_username) })
                if (prompt.saveFailed) {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.login_save_failed), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !prompt.saving,
                onClick = {
                    onResponse(PromptResponse.SelectOption(prompt.login.index))
                },
            ) {
                Text(stringResource(if (prompt.saving) R.string.login_saving else
                    if (prompt.saveFailed) R.string.login_save_retry else R.string.save))
            }
        },
        dismissButton = {
            TextButton(enabled = !prompt.saving, onClick = { onResponse(PromptResponse.Dismiss) }) {
                Text(stringResource(R.string.not_now))
            }
        },
    )
}

@Composable
private fun SelectLoginPromptDialog(
    prompt: BrowserPrompt.SelectLogin,
    onResponse: (PromptResponse) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onResponse(PromptResponse.Dismiss) },
        title = { Text(stringResource(R.string.choose_login)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                prompt.logins.forEach { login ->
                    TextButton(
                        onClick = { onResponse(PromptResponse.SelectOption(login.index)) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(login.username.ifBlank { stringResource(R.string.no_username) })
                            Text(displayOrigin(login.origin))
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = { onResponse(PromptResponse.Dismiss) }) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun TextPromptDialog(
    prompt: BrowserPrompt.Text,
    onResponse: (PromptResponse) -> Unit,
) {
    var value by remember(prompt.id) { mutableStateOf(prompt.defaultValue) }
    AlertDialog(
        onDismissRequest = { onResponse(PromptResponse.Dismiss) },
        title = { PromptTitle(prompt.title) },
        text = {
            Column {
                PromptMessage(prompt.message)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.prompt_value)) },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onResponse(PromptResponse.TextValue(value)) }) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = { onResponse(PromptResponse.Dismiss) }) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun AuthenticationPromptDialog(
    prompt: BrowserPrompt.Authentication,
    onResponse: (PromptResponse) -> Unit,
) {
    var username by remember(prompt.id) { mutableStateOf(prompt.username) }
    var password by remember(prompt.id) { mutableStateOf(prompt.password) }
    AlertDialog(
        onDismissRequest = { onResponse(PromptResponse.Dismiss) },
        title = { Text(prompt.title ?: stringResource(R.string.authentication_required)) },
        text = {
            Column {
                Text(prompt.uri?.let(::displayOrigin) ?: prompt.message.orEmpty())
                if (prompt.previousAttemptFailed) {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.authentication_failed))
                }
                Spacer(Modifier.height(12.dp))
                if (!prompt.passwordOnly) {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(stringResource(R.string.username)) },
                    )
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.password)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = PasswordVisualTransformation(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(
                        if (prompt.secure) {
                            R.string.authentication_secure
                        } else {
                            R.string.authentication_not_secure
                        },
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onResponse(PromptResponse.Credentials(username, password))
                },
            ) {
                Text(stringResource(R.string.sign_in))
            }
        },
        dismissButton = {
            TextButton(onClick = { onResponse(PromptResponse.Dismiss) }) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun SitePermissionDialog(
    request: SitePermissionRequest,
    saveFailed: Boolean,
    onResponse: (SitePermissionDecision) -> Unit,
) {
    val origin = displayOrigin(request.uri)
    val action = sitePermissionAction(request)
    AlertDialog(
        onDismissRequest = { onResponse(SitePermissionDecision.DISMISS) },
        title = { Text(stringResource(R.string.site_permission_title)) },
        text = {
            Column {
                Text(stringResource(R.string.site_permission_question, origin, action))
                if (saveFailed) {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.permission_save_failed))
                }
                if (!request.privateMode && request is SitePermissionRequest.Content) {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.permission_session_explanation))
                }
                if (request.privateMode) {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.private_permission_notice))
                }
                if (request is SitePermissionRequest.Content && request.thirdPartyOrigin != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(
                            R.string.third_party_permission_notice,
                            displayOrigin(request.thirdPartyOrigin),
                        ),
                    )
                }
            }
        },
        confirmButton = {
            Column {
                TextButton(onClick = { onResponse(SitePermissionDecision.ALLOW_SESSION) }) {
                    Text(stringResource(if (request is SitePermissionRequest.Content) {
                        R.string.permission_allow_session
                    } else {
                        R.string.allow
                    }))
                }
                if (!request.privateMode && request is SitePermissionRequest.Content) {
                    TextButton(onClick = { onResponse(SitePermissionDecision.ALLOW_ALWAYS) }) {
                        Text(stringResource(R.string.permission_allow_always))
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = { onResponse(SitePermissionDecision.BLOCK) }) {
                Text(stringResource(R.string.block))
            }
        },
    )
}

@Composable
private fun PromptTitle(title: String?) {
    Text(title ?: stringResource(R.string.website_dialog))
}

@Composable
private fun PromptMessage(message: String?) {
    Text(message.orEmpty())
}

@Composable
private fun sitePermissionAction(request: SitePermissionRequest): String = when (request) {
    is SitePermissionRequest.Media -> when {
        request.videoSources.isNotEmpty() && request.audioSources.isNotEmpty() -> {
            stringResource(R.string.permission_camera_and_microphone)
        }
        request.videoSources.isNotEmpty() -> stringResource(R.string.permission_camera)
        else -> stringResource(R.string.permission_microphone)
    }
    is SitePermissionRequest.Content -> stringResource(
        when (request.kind) {
            SitePermissionKind.GEOLOCATION -> R.string.permission_location
            SitePermissionKind.NOTIFICATIONS -> R.string.permission_notifications
            SitePermissionKind.PERSISTENT_STORAGE -> R.string.permission_persistent_storage
            SitePermissionKind.XR -> R.string.permission_xr
            SitePermissionKind.AUTOPLAY_AUDIBLE -> R.string.permission_autoplay_audio
            SitePermissionKind.AUTOPLAY_INAUDIBLE -> R.string.permission_autoplay_silent
            SitePermissionKind.MEDIA_KEY_SYSTEM_ACCESS -> R.string.permission_protected_media
            SitePermissionKind.TRACKING -> R.string.permission_tracking
            SitePermissionKind.STORAGE_ACCESS -> R.string.permission_cross_site_storage
            SitePermissionKind.LOCAL_DEVICE_ACCESS -> R.string.permission_local_device
            SitePermissionKind.LOCAL_NETWORK_ACCESS -> R.string.permission_local_network
        },
    )
}

private fun displayOrigin(uri: String): String = runCatching {
    val parsed = Uri.parse(uri)
    parsed.host ?: parsed.authority ?: uri
}.getOrDefault(uri)
