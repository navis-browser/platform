// SPDX-License-Identifier: MPL-2.0
package org.navis.browser.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import org.navis.browser.R
import org.navis.browser.api.BrowserSessionState
import org.navis.browser.api.ContentTermination
import org.navis.browser.api.FindResult
import org.navis.browser.engine.AndroidWindowRuntime

/** Short-lived UI projection; the Session and native finder own all search work. */
internal class PageFindController(
    private val runtime: AndroidWindowRuntime,
    private val session: BrowserSessionState?,
    private val onError: () -> Unit,
) {
    var visible by mutableStateOf(false)
        private set
    var query by mutableStateOf(TextFieldValue())
        private set
    var result by mutableStateOf<FindResult?>(null)
        private set
    var focusRequest by mutableIntStateOf(0)
        private set
    private var submittedQuery: String? = null
    private var serial = 0L
    private var disposed = false
    private fun current() = !disposed && session != null && runtime.state.activeSession?.let {
        it.id == session.id && it.navigationId == session.navigationId &&
            it.nativeRoute == null && it.navigation.contentTermination == ContentTermination.NONE
    } == true

    fun open(again: Boolean = false, backwards: Boolean = false): Boolean {
        if (!current()) return false
        val id = checkNotNull(session).id
        val request = ++serial
        runtime.openFindInPage(id, again, backwards).whenComplete { opened, error ->
            if (request == serial && current()) {
                if (error != null) onError()
                else if (opened.documentOwned) visible = false
                else {
                    val wasVisible = visible
                    visible = true
                    if (!again) {
                        val text = if (!wasVisible && opened.selection.isNotEmpty()) opened.selection else query.text
                        query = TextFieldValue(text, TextRange(0, text.length))
                    }
                    if (!again || !wasVisible) focusRequest++
                    if (query.text.isNotEmpty()) search(query, again && wasVisible, backwards)
                }
            }
        }
        return true
    }

    fun search(value: TextFieldValue, again: Boolean = false, backwards: Boolean = false) {
        if (!current() || !visible || value.text.length > 8192) return
        query = value
        if (value.composition != null) { ++serial; result = null; return }
        if (!again && submittedQuery == value.text) return
        submittedQuery = value.text
        val request = ++serial
        result = null
        runtime.findInPage(checkNotNull(session).id, value.text, again, backwards).whenComplete { found, error ->
            if (request == serial && current() && visible) {
                if (error != null) onError() else result = found
            }
        }
    }

    fun step(backwards: Boolean = false): Boolean {
        if (!visible) return open(again = true, backwards = backwards)
        search(query, again = true, backwards = backwards)
        return true
    }

    fun close() {
        ++serial
        visible = false
        result = null
        submittedQuery = null
        session?.let { if (runtime.state.sessions.any { s -> s.id == it.id }) runtime.clearFindInPage(it.id) }
    }
    fun dispose() { close(); disposed = true }
}

@Composable
internal fun rememberPageFind(runtime: AndroidWindowRuntime, session: BrowserSessionState?): PageFindController {
    val context = LocalContext.current
    val controller = remember(runtime, session?.id, session?.navigationId, session?.nativeRoute, session?.navigation?.contentTermination) {
        PageFindController(runtime, session) {
            android.widget.Toast.makeText(context, R.string.find_unavailable, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    DisposableEffect(controller) { onDispose { controller.dispose() } }
    return controller
}

@Composable
internal fun FindOnPageBar(controller: PageFindController) {
    if (!controller.visible) return
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val title = stringResource(R.string.find_on_page)
    val close = { controller.close(); keyboard?.hide(); focusManager.clearFocus() }
    BackHandler { close() }
    LaunchedEffect(controller.focusRequest) { focus.requestFocus(); keyboard?.show() }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = controller.query, onValueChange = { controller.search(it) },
                modifier = Modifier.weight(1f).semantics { contentDescription = title }.focusRequester(focus).onPreviewKeyEvent {
                    if (controller.query.composition == null && it.type == KeyEventType.KeyDown && it.key == Key.Enter) {
                        controller.step(it.isShiftPressed); true
                    } else false
                },
                placeholder = { Text(stringResource(R.string.find_on_page)) },
                singleLine = true, isError = controller.result?.total == 0 && controller.query.text.isNotEmpty(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { controller.step(); keyboard?.hide() }),
            )
            val result = controller.result
            Text(
                if (controller.query.text.isEmpty() || result == null) "" else if (result.total == -1)
                    stringResource(R.string.find_count_limit, result.limit)
                else stringResource(R.string.find_count, result.current, result.total),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            val enabled = controller.query.text.isNotEmpty() && result?.total != 0
            IconButton(onClick = { controller.step(true) }, enabled = enabled) {
                Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.find_previous))
            }
            IconButton(onClick = { controller.step() }, enabled = enabled) {
                Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.find_next))
            }
            IconButton(onClick = { close() }) { Icon(Icons.Default.Close, stringResource(R.string.find_close)) }
        }
    }
}
