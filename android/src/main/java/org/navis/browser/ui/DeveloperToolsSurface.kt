/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.ui

import android.app.Activity
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.navis.browser.R
import org.navis.browser.BrowserKeyboardAction
import org.navis.browser.api.SessionId
import org.navis.browser.api.BrowserStateObserver
import org.navis.browser.api.ContentTermination
import org.navis.browser.engine.AndroidBrowserView
import org.navis.browser.engine.AndroidWindowRuntime

@Composable
internal fun DeveloperToolsSurface(
    runtime: AndroidWindowRuntime,
    target: SessionId,
    initialTool: String? = null,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val window = remember(context) {
        generateSequence(context) { (it as? ContextWrapper)?.baseContext?.takeUnless { next -> next === it } }
            .filterIsInstance<Activity>().firstOrNull()?.window
    }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    DisposableEffect(window) {
        val policy = window?.let(AndroidDevToolsWindowPolicy::acquire)
        onDispose { policy?.close() }
    }
    val host = remember(runtime, target, initialTool) {
        runCatching { runtime.createDevToolsHost(target, initialTool) }.getOrNull()
    }
    var ready by remember(host) { mutableStateOf<Boolean?>(if (host == null) false else null) }
    var busy by remember(host) { mutableStateOf(false) }
    var closing by remember(host) { mutableStateOf(false) }
    var operationFailed by remember(host) { mutableStateOf(false) }
    var webTarget by remember(runtime, target) { mutableStateOf(false) }
    var targetView by remember(runtime, target) { mutableStateOf<AndroidBrowserView?>(null) }
    val currentBack by rememberUpdatedState(onBack)
    val main = remember { Handler(Looper.getMainLooper()) }
    val closeTools: () -> Unit = {
        if (!closing) {
            if (host == null || ready == false) {
                host?.close()
                currentBack()
            } else {
                closing = true
                operationFailed = false
                host.closeAfterRestoring().whenComplete { restored, error -> main.post {
                    closing = false
                    if (error == null && restored == true) currentBack()
                    else operationFailed = true
                } }
            }
        }
    }
    BackHandler {
        if (runtime.state.windowFullscreen) runtime.exitWindowFullscreen() else closeTools()
    }
    BrowserShortcutHandler(runtime) { command ->
        if (command == BrowserKeyboardAction.ToggleDevTools) { closeTools(); true }
        else if (command == BrowserKeyboardAction.ExitFullscreen && runtime.state.windowFullscreen) {
            runtime.exitWindowFullscreen(); true
        }
        else false // Console/editor/panel shortcuts continue through Gecko unchanged.
    }
    DisposableEffect(runtime, target, host) {
        var disposed = false
        val observer = BrowserStateObserver { state ->
            if (!disposed) {
                val session = state.activeSession?.takeIf { it.id == target }
                webTarget = session != null && !session.nativeNewTab && session.nativeRoute == null &&
                    session.navigation.contentTermination == ContentTermination.NONE
                // State notifications and Runtime's ownership transfer run on
                // the same UI thread: never show the previous document under
                // a native page, nor attach a different tab to this inspector.
                if (!webTarget) targetView?.let(runtime::detachView)
                if (session == null) {
                    host?.close()
                    currentBack()
                }
            }
        }
        runtime.addObserver(observer)
        onDispose {
            disposed = true
            runtime.removeObserver(observer)
            targetView?.let(runtime::detachView)
        }
    }
    DisposableEffect(host) {
        var disposed = false
        host?.ready?.whenComplete { accepted, error ->
            main.post { if (!disposed) ready = error == null && accepted == true }
        }
        onDispose { disposed = true; host?.close() }
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.navisTopBarInsets()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = closeTools, enabled = !closing) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.navigate_back))
                }
                Text(stringResource(R.string.developer_tools), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                if (webTarget && host != null && ready == true) {
                    val command: (String) -> Unit = { action ->
                        busy = true
                        operationFailed = false
                        host.viewport(action).whenComplete { accepted, error -> main.post {
                            busy = false
                            operationFailed = error != null || accepted != true
                        } }
                    }
                    IconButton(onClick = { command("responsive-toggle") }, enabled = !busy && !closing) {
                        Icon(painterResource(R.drawable.ic_responsive_devices), stringResource(R.string.devtools_responsive_mode))
                    }
                    IconButton(onClick = { command("zoom-out") }, enabled = !busy && !closing) {
                        Icon(painterResource(R.drawable.ic_viewport_zoom_out), stringResource(R.string.devtools_viewport_zoom_out))
                    }
                    TextButton(onClick = { command("reset-zoom") }, enabled = !busy && !closing,
                        contentPadding = PaddingValues(0.dp)) {
                        Text("100%")
                    }
                    IconButton(onClick = { command("zoom-in") }, enabled = !busy && !closing) {
                        Icon(Icons.Default.Add, stringResource(R.string.devtools_viewport_zoom_in))
                    }
                }
            }
        }
        if (operationFailed) Text(stringResource(R.string.devtools_viewport_failed),
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            color = MaterialTheme.colorScheme.error)
        if (busy || closing) LinearProgressIndicator(Modifier.fillMaxWidth())
        Box(Modifier.weight(1f).fillMaxWidth().navigationBarsPadding()) {
            if (host != null && ready != false) {
                val preview: @Composable (Modifier) -> Unit = { modifier ->
                    AndroidView(
                        factory = { context -> AndroidBrowserView(context).also {
                            targetView = it
                            if (runtime.state.activeSessionId == target && webTarget) runtime.attachView(it)
                        } },
                        update = { view ->
                            if (runtime.state.activeSessionId == target && webTarget) runtime.attachView(view)
                            else runtime.detachView(view)
                        },
                        onRelease = { view ->
                            runtime.detachView(view)
                            if (targetView === view) targetView = null
                        },
                        modifier = modifier,
                    )
                }
                val tools: @Composable (Modifier) -> Unit = { modifier ->
                    AndroidView(factory = host::createView, onRelease = host::releaseView, modifier = modifier)
                }
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    if (!webTarget) tools(Modifier.fillMaxSize())
                    else if (landscape) {
                        Row(Modifier.fillMaxSize()) {
                            preview(Modifier.weight(1f).fillMaxHeight())
                            VerticalDivider()
                            tools(Modifier.weight(1f).fillMaxHeight())
                        }
                    } else {
                        Column(Modifier.fillMaxSize()) {
                            preview(Modifier.weight(0.45f).fillMaxWidth())
                            HorizontalDivider()
                            tools(Modifier.weight(0.55f).fillMaxWidth())
                        }
                    }
                }
            }
            if (ready == null) {
                Column(Modifier.align(Alignment.TopCenter).fillMaxWidth()) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(R.string.developer_tools_loading), Modifier.padding(16.dp))
                }
            } else if (ready == false) {
                Text(stringResource(R.string.developer_tools_failed), Modifier.align(Alignment.Center).padding(24.dp))
            }
        }
    }
}
