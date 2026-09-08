/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import org.navis.browser.R
import org.navis.browser.api.BrowserSessionState
import org.navis.browser.api.BrowserStateObserver
import org.navis.browser.api.SessionId
import org.navis.browser.engine.AddressSuggestion
import org.navis.browser.engine.AndroidWindowRuntime

/** Ctrl+L on a full-page native surface uses the existing address resolver and
 * same Session navigation. Dismissal leaves the current page/history untouched.
 */
@Composable
internal fun ShortcutAddressDialog(runtime: AndroidWindowRuntime, session: BrowserSessionState, onDismiss: () -> Unit) {
    val opened = remember { session }
    val dismiss by rememberUpdatedState(onDismiss)
    var value by remember { mutableStateOf(TextFieldValue(opened.navigation.url,
        selection = TextRange(0, opened.navigation.url.length))) }
    val focus = remember { FocusRequester() }
    val settings = rememberBrowserSettings(runtime.product.settings)
    val suggestions = rememberAddressSuggestions(runtime, opened, value, true, settings)
    var failed by remember(value.text) { mutableStateOf(false) }
    var live by remember { mutableStateOf(true) }
    var submissionSerial by remember { mutableLongStateOf(0L) }
    fun dismissAddress() { live = false; submissionSerial++; dismiss() }
    DisposableEffect(runtime, opened.id) {
        val observer = BrowserStateObserver { state ->
            if (state.activeSessionId != opened.id || state.activeSession?.nativeRoute != opened.nativeRoute ||
                state.activeSession?.navigation?.url != opened.navigation.url) dismissAddress()
        }
        runtime.addObserver(observer)
        onDispose { live = false; runtime.removeObserver(observer) }
    }
    fun submit(text: String = value.text, forceSearch: Boolean = false) {
        if (text.isBlank() || value.composition != null) return
        val current = runtime.state.activeSession
        if (current?.id != opened.id || current.nativeRoute != opened.nativeRoute ||
            current.navigation.url != opened.navigation.url) { dismissAddress(); return }
        val editText = value.text
        val request = ++submissionSerial
        runtime.product.resolveAddress(text, opened.id, forceSearch) { result ->
            val now = runtime.state.activeSession
            if (!live || request != submissionSerial || value.text != editText || value.composition != null || now?.id != opened.id ||
                now.navigation.revision != opened.navigation.revision) return@resolveAddress
            result.onSuccess { dismissAddress(); runtime.navigate(it) }.onFailure { failed = true }
        }
    }
    fun commit(row: AddressSuggestion) {
        val current = runtime.state.activeSession
        if (!live || value.composition != null || current?.id != opened.id ||
            current.navigation.revision != opened.navigation.revision) return
        if (row.kind == "search") submit(row.title, forceSearch = true)
        else if (row.kind == "tab") {
            val id = row.id.toLongOrNull()?.let(::SessionId) ?: return
            if (runtime.state.sessions.none { it.id == id && it.navigation.url == row.url && it.mode == opened.mode }) return
            dismissAddress(); runtime.activateSession(id)
        } else { dismissAddress(); runtime.navigate(row.url) }
    }
    AlertDialog(onDismissRequest = ::dismissAddress,
        title = { Text(stringResource(R.string.omnibox_hint)) },
        text = {
            Column {
            TextField(value = value, onValueChange = { submissionSerial++; value = it }, singleLine = true,
                modifier = Modifier.fillMaxWidth().focusRequester(focus)
                    .onPreviewKeyEvent {
                        if (it.type == KeyEventType.KeyDown && it.key == Key.Escape && value.composition == null) {
                            dismissAddress(); true
                        } else suggestions.key(it, value.composition != null, ::commit)
                    },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = {
                    suggestions.rows.getOrNull(suggestions.selected)?.let(::commit) ?: submit()
                }))
            if (failed) Text(stringResource(R.string.operation_failed))
            AddressSuggestionsContent(suggestions, settings.searchProvider.name, ::commit)
            }
        },
        confirmButton = { TextButton(onClick = { submit() }, enabled = value.text.isNotBlank() && value.composition == null) {
            Text(stringResource(R.string.ui_address_go))
        } },
        dismissButton = { TextButton(onClick = ::dismissAddress) { Text(stringResource(R.string.cancel)) } },
    )
    LaunchedEffect(focus) { focus.requestFocus() }
}
