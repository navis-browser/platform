/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.ui

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.navis.browser.R
import org.navis.browser.BrowserKeyboardAction
import org.navis.browser.api.BrowserSessionState
import org.navis.browser.api.BrowserState
import org.navis.browser.api.BrowserStateObserver
import org.navis.browser.api.ContentTermination
import org.navis.browser.api.LoadingState
import org.navis.browser.api.SessionMode
import org.navis.browser.api.SessionId
import org.navis.browser.api.TargetRequestObserver
import org.navis.browser.engine.AndroidWindowRuntime
import org.navis.browser.engine.AddressSuggestion
import org.navis.browser.engine.AndroidBrowserView
import org.navis.browser.engine.AndroidContextMenuRequest
import org.navis.browser.extensions.ExtensionHost
import org.navis.browser.persistence.BookmarkEntry
import org.navis.browser.persistence.BookmarkDraft
import org.navis.browser.persistence.HistoryEntry
import org.navis.browser.persistence.PasswordEntry

private enum class AppSurface {
    BROWSER, SETTINGS, EXTENSIONS, HISTORY, BOOKMARKS, PASSWORDS, DOWNLOADS, HELP, SUPPORT, URLS,
    PROCESSES, PROFILES, CREDITS,
}

private fun pageSurface(route: String?): AppSurface = when (route) {
    "settings", "settings/search", "settings/privacy", "settings/appearance", "settings/downloads" -> AppSurface.SETTINGS
    "extensions" -> AppSurface.EXTENSIONS
    "history" -> AppSurface.HISTORY
    "bookmarks" -> AppSurface.BOOKMARKS
    "passwords" -> AppSurface.PASSWORDS
    "downloads" -> AppSurface.DOWNLOADS
    "help" -> AppSurface.HELP
    "support" -> AppSurface.SUPPORT
    "urls" -> AppSurface.URLS
    "processes" -> AppSurface.PROCESSES
    "profiles" -> AppSurface.PROFILES
    "credits" -> AppSurface.CREDITS
    else -> AppSurface.BROWSER
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun NavisBrowserApp(runtime: AndroidWindowRuntime) {
    var state by remember(runtime) { mutableStateOf(runtime.state) }
    var pageFullscreen by remember(runtime) { mutableStateOf(runtime.targetState.fullscreenSessionId != null) }
    val fullscreen = state.windowFullscreen || pageFullscreen
    var showTabs by remember { mutableStateOf(false) }
    var showHistory by remember(runtime) { mutableStateOf(false) }
    var showSiteInformation by remember { mutableStateOf(false) }
    var devToolsTarget by remember { mutableStateOf<org.navis.browser.api.SessionId?>(null) }
    var devToolsInitialTool by remember { mutableStateOf<String?>(null) }
    var omniboxFocusRequest by remember { mutableStateOf(0L) }
    var omniboxFocusSerial by remember { mutableStateOf(0L) }
    var showShortcutAddress by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf<List<HistoryEntry>>(emptyList()) }
    var bookmarks by remember { mutableStateOf<List<BookmarkEntry>>(emptyList()) }
    var pageBookmarkDraft by remember(runtime) { mutableStateOf<BookmarkDraft?>(null) }
    var passwords by remember { mutableStateOf<List<PasswordEntry>>(emptyList()) }
    var contextMenu by remember { mutableStateOf<AndroidContextMenuRequest?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = remember(context) { context.navisActivity() }
    val surface = pageSurface(state.activeSession?.nativeRoute)
    val settings = rememberBrowserSettings(runtime.product.settings)
    BrowserWindowFullscreen(runtime, state.windowFullscreen, pageFullscreen)

    fun exitFullscreenPresentation() {
        if (runtime.state.windowFullscreen) runtime.exitWindowFullscreen()
        if (runtime.targetState.fullscreenSessionId != null) runtime.exitFullscreen()
    }

    fun navigate(uri: String) {
        runtime.navigate(uri)
    }
    fun backFromPage() {
        state.activeSessionId?.let(runtime::goBack)
    }
    fun openDevTools(initialTool: String? = null) {
        showSiteInformation = false
        contextMenu = null
        runtime.extensions.dismissExtensionPopup()
        devToolsInitialTool = initialTool
        devToolsTarget = state.activeSessionId
    }

    fun focusShortcutAddress() {
        showTabs = false
        showSiteInformation = false
        contextMenu = null
        runtime.extensions.dismissExtensionPopup()
        if (fullscreen) exitFullscreenPresentation()
        if (pageSurface(runtime.state.activeSession?.nativeRoute) == AppSurface.BROWSER) {
            omniboxFocusSerial++
            omniboxFocusRequest = omniboxFocusSerial
        } else showShortcutAddress = true
    }

    BrowserShortcutHandler(runtime) { command ->
        if (devToolsTarget != null) false else {
            val current = runtime.state
            val active = current.activeSession
            if (active == null) false else when (command) {
                BrowserKeyboardAction.FocusAddress -> { focusShortcutAddress(); true }
                BrowserKeyboardAction.NewTab -> {
                    // Ctrl+T stays in the current normal/private product window.
                    val stillCurrent = activity?.browserKeyboardWindowGuard(runtime) ?: { false }
                    runtime.openSession(active.mode).whenComplete { id, error -> activity?.runOnUiThread {
                        if (stillCurrent()) {
                            if (error == null && runtime.state.activeSessionId == id) focusShortcutAddress()
                            else if (error != null) android.widget.Toast.makeText(activity, R.string.operation_failed,
                                android.widget.Toast.LENGTH_SHORT).show()
                        }
                    } }
                    true
                }
                BrowserKeyboardAction.CloseTab -> { runtime.closeSession(active.id); true }
                BrowserKeyboardAction.Reload -> { runtime.reload(active.id); true }
                BrowserKeyboardAction.NextTab, BrowserKeyboardAction.PreviousTab -> {
                    val offset = if (command == BrowserKeyboardAction.PreviousTab) -1 else 1
                    val index = current.sessions.indexOfFirst { it.id == active.id }
                    val next = (index + offset + current.sessions.size) % current.sessions.size
                    runtime.activateSession(current.sessions[next].id)
                    true
                }
                is BrowserKeyboardAction.SelectTab -> {
                    val selected = if (command.number == 9) current.sessions.lastOrNull()
                        else current.sessions.getOrNull(command.number - 1)
                    selected?.let { runtime.activateSession(it.id) }
                    selected != null
                }
                BrowserKeyboardAction.ToggleDevTools -> if (active.nativeRoute != null) false
                    else { openDevTools(); true }
                BrowserKeyboardAction.ExitFullscreen -> if (fullscreen) {
                    exitFullscreenPresentation(); true
                } else false
            }
        }
    }

    DisposableEffect(runtime) {
        var observing = true
        val bookmarksSubscription = runtime.observeBookmarksChanged {
            runtime.listBookmarks { if (observing) bookmarks = it }
        }
        val historySubscription = runtime.observeHistoryChanged {
            runtime.listHistory { if (observing) history = it }
        }
        val observer = BrowserStateObserver { next -> state = next }
        val targetObserver = TargetRequestObserver { next -> pageFullscreen = next.fullscreenSessionId != null }
        val contextObserver = { next: AndroidContextMenuRequest ->
            showSiteInformation = false
            contextMenu = next
        }
        val shortcutObserver = { _: String -> navigate("navis://extensions/") }
        runtime.addObserver(observer)
        runtime.addTargetObserver(targetObserver)
        runtime.addContextMenuObserver(contextObserver)
        runtime.addShortcutSettingsObserver(shortcutObserver)
        onDispose {
            observing = false
            bookmarksSubscription.close()
            historySubscription.close()
            runtime.removeObserver(observer)
            runtime.removeTargetObserver(targetObserver)
            runtime.removeContextMenuObserver(contextObserver)
            runtime.removeShortcutSettingsObserver(shortcutObserver)
        }
    }
    LaunchedEffect(fullscreen, state.activeSessionId, state.activeSession?.navigation?.url) {
        showSiteInformation = false
        contextMenu?.let { runtime.respondToContextMenu(it, null) }
        contextMenu = null
        if (fullscreen) {
            showTabs = false
            showHistory = false
        }
    }
    LaunchedEffect(state.sessions.isEmpty()) {
        if (state.sessions.isEmpty()) activity?.closeBrowserWindow()
    }
    LaunchedEffect(surface, state.activeSessionId, settings.bookmarkBarVisible) {
        if (surface != AppSurface.BROWSER) runtime.extensions.dismissExtensionPopup()
        if (surface == AppSurface.HISTORY) runtime.listHistory { history = it }
        if (surface == AppSurface.PASSWORDS) runtime.listPasswords { passwords = it }
        runtime.listBookmarks { bookmarks = it }
    }
    LaunchedEffect(showHistory) {
        if (showHistory) runtime.listHistory { history = it }
    }

    BackHandler(enabled = devToolsTarget != null || showTabs || showHistory || showSiteInformation ||
        (surface != AppSurface.BROWSER && state.activeSession?.navigation?.canGoBack == true)) {
        when {
            devToolsTarget != null -> devToolsTarget = null
            showSiteInformation -> showSiteInformation = false
            showHistory -> showHistory = false
            showTabs -> showTabs = false
            else -> backFromPage()
        }
    }
    BackHandler(enabled = fullscreen) { exitFullscreenPresentation() }
    BackHandler(
        enabled = !fullscreen && surface == AppSurface.BROWSER && devToolsTarget == null && !showTabs &&
            !showSiteInformation && !showHistory && state.activeSession?.navigation?.canGoBack == true,
    ) { state.activeSessionId?.let(runtime::goBack) }

    val profileContext = androidx.compose.ui.platform.LocalContext.current
    val profileFilesRoot = remember(profileContext) { profileContext.applicationContext.filesDir }
    val appNameLabel = stringResource(R.string.app_name)
    var profileGeneration by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val userProfileSnapshot = remember(profileFilesRoot, profileGeneration) {
        runCatching {
            org.navis.browser.persistence.UserProfileStore.fileBacked(profileFilesRoot).snapshot()
        }.getOrNull()
    }
    val currentUserProfile = userProfileSnapshot?.profiles?.firstOrNull {
        it.id == userProfileSnapshot.currentId
    }
    var showProfile by remember { androidx.compose.runtime.mutableStateOf(false) }

    NavisTheme(settings) {
        val toolsTarget = devToolsTarget
        if (toolsTarget != null) {
            DeveloperToolsSurface(runtime, toolsTarget, devToolsInitialTool) { devToolsTarget = null }
        } else when (surface) {
            AppSurface.BROWSER -> BrowserSurface(
                runtime = runtime, state = state, fullscreen = fullscreen,
                omniboxFocusRequest = omniboxFocusRequest,
                onOmniboxFocusRequestHandled = { request ->
                    if (omniboxFocusRequest == request) omniboxFocusRequest = 0
                },
                bookmarks = bookmarks,
                onSaveBookmark = { draft, done ->
                    runtime.saveBookmark(draft) { result ->
                        runtime.listBookmarks { bookmarks = it; done(result) }
                    }
                },
                onShowTabs = { showSiteInformation = false; showTabs = true },
                onToggleHistory = { showHistory = !showHistory },
                onProductPage = ::navigate,
                onShowSiteInformation = {
                    runtime.extensions.dismissExtensionPopup()
                    showSiteInformation = true
                },
                onOpenDevTools = { openDevTools() },
                onBookmark = { active ->
                    pageBookmarkDraft = org.navis.browser.persistence.BookmarkTreePolicy.draftForPage(
                        bookmarks, active.navigation.url, active.navigation.title)
                },
                onShowProfile = { showProfile = true },
                avatarName = currentUserProfile?.userName.orEmpty(),
                avatarAccent = settings.accent,
                avatarAppName = appNameLabel,
            )
            AppSurface.SETTINGS -> SettingsSurface(
                runtime = runtime, onBack = ::backFromPage, onNavigate = ::navigate,
                onRelaunch = { activity?.relaunchBrowser() },
                section = state.activeSession?.nativeRoute?.substringAfter("settings/", "").orEmpty(),
            )
            AppSurface.EXTENSIONS -> ExtensionManagerSurface(
                host = runtime.extensions, developerMode = false,
                onBack = ::backFromPage, onOpenOptions = {},
            )
            AppSurface.HISTORY -> HistorySurface(
                entries = history, onBack = ::backFromPage, onOpen = ::navigate,
                onClear = { runtime.clearHistory { runtime.listHistory { history = it } } },
                actions = HistoryActions(
                    remove = { url, done ->
                        runtime.removeHistoryResult(url) { result ->
                            runtime.listHistory { history = it; done(result) }
                        }
                    },
                    clear = { done ->
                        runtime.clearHistoryResult { result ->
                            runtime.listHistory { history = it; done(result) }
                        }
                    },
                ),
            )
            AppSurface.BOOKMARKS -> BookmarksSurface(
                entries = bookmarks, onBack = ::backFromPage, onOpen = ::navigate,
                onRemove = { url -> runtime.removeBookmark(url) { runtime.listBookmarks { bookmarks = it } } },
                actions = BookmarkActions(
                    save = { draft, done ->
                        runtime.saveBookmark(draft) { result ->
                            runtime.listBookmarks { bookmarks = it; done(result) }
                        }
                    },
                    move = { id, parent, position, done ->
                        runtime.moveBookmark(id, parent, position) { result ->
                            runtime.listBookmarks { bookmarks = it; done(result) }
                        }
                    },
                    remove = { id, done ->
                        runtime.deleteBookmark(id) { result ->
                            runtime.listBookmarks { bookmarks = it; done(result) }
                        }
                    },
                ),
            )
            AppSurface.PASSWORDS -> PasswordsSurface(
                entries = passwords, onBack = ::backFromPage,
                onRemove = { id -> runtime.removePassword(id) { runtime.listPasswords { passwords = it } } },
                actions = PasswordActions(
                    reveal = runtime::revealPassword,
                    remove = { id, done ->
                        runtime.deletePassword(id) { result ->
                            runtime.listPasswords { passwords = it; done(result) }
                        }
                    },
                    clear = { done ->
                        runtime.clearPasswords { result ->
                            runtime.listPasswords { passwords = it; done(result) }
                        }
                    },
                ),
            )
            AppSurface.DOWNLOADS -> DownloadsSurface(
                manager = runtime.downloads,
                privateMode = state.activeSession?.mode == SessionMode.PRIVATE,
                onBack = ::backFromPage, onOpenSource = ::navigate,
            )
            AppSurface.HELP -> AboutSurface(runtime, ::backFromPage, ::navigate)
            AppSurface.SUPPORT -> SupportSurface(runtime, ::backFromPage)
            AppSurface.URLS -> InternalUrlsSurface(::backFromPage, ::navigate)
            AppSurface.PROCESSES -> ProcessManagementSurface(runtime, ::backFromPage)
            AppSurface.CREDITS -> CreditsSurface(runtime, ::backFromPage, ::navigate)
            AppSurface.PROFILES -> Unit
        }
        if (showTabs && !fullscreen) TabsSheet(runtime, state) { showTabs = false }
        if (showHistory && surface == AppSurface.BROWSER && !fullscreen) {
            val closeHistory = { showHistory = false }
            ModalBottomSheet(onDismissRequest = closeHistory) {
                HistorySurface(
                    entries = history,
                    onBack = closeHistory,
                    onOpen = { uri -> closeHistory(); navigate(uri) },
                    onOpenFullPage = { closeHistory(); navigate("navis://history/") },
                    onClear = { runtime.clearHistory { runtime.listHistory { history = it } } },
                    actions = HistoryActions(
                        remove = { url, done -> runtime.removeHistoryResult(url) { result ->
                            runtime.listHistory { history = it; done(result) }
                        } },
                        clear = { done -> runtime.clearHistoryResult { result ->
                            runtime.listHistory { history = it; done(result) }
                        } },
                    ),
                )
            }
        }
        if (showSiteInformation) state.activeSession?.let { active ->
            SiteInformationSheet(runtime, active) { showSiteInformation = false }
        }
        if (showProfile || surface == AppSurface.PROFILES) {
            UserProfileSheet(
                filesRoot = profileFilesRoot,
                appName = appNameLabel,
                onSwitchProfile = { selected ->
                    activity?.openProfileWindow(selected.id)
                },
                onRestart = { safeMode ->
                    // ModalBottomSheet owns a separate Android window. Release it before
                    // prepareRelaunch waits for browser focus to dispatch beforeunload.
                    showProfile = false
                    activity?.relaunchBrowser(safeMode = safeMode)
                },
                onIdentityChanged = { user ->
                    runtime.product.settings.setAppearance("accent", user.accent)
                    profileGeneration++
                },
                onDismiss = {
                    showProfile = false
                    profileGeneration++
                    if (surface == AppSurface.PROFILES) backFromPage()
                },
                onManage = { showProfile = false; navigate("navis://profiles/") },
                asPage = surface == AppSurface.PROFILES,
            )
        }
        pageBookmarkDraft?.let { draft ->
            BookmarkEditor(draft, bookmarks, onDismiss = { pageBookmarkDraft = null }) { changed, done ->
                runtime.saveBookmark(changed) { result ->
                    done(result)
                    if (result.isSuccess) pageBookmarkDraft = null
                }
            }
        }
        ExtensionPermissionDialog(runtime.extensions)
        ExtensionPopupSheet(runtime.extensions)
        contextMenu?.let { request ->
            AndroidContextMenuSheet(runtime, request, onDismiss = { contextMenu = null },
                onInspect = { openDevTools("inspector") })
        }
        if (showShortcutAddress) state.activeSession?.let { active ->
            ShortcutAddressDialog(runtime, active) { showShortcutAddress = false }
        }
        TargetDialogs(runtime, state.activeSessionId)
    }
}

private tailrec fun android.content.Context.navisActivity(): org.navis.browser.MainActivity? = when (this) {
    is org.navis.browser.MainActivity -> this
    is android.content.ContextWrapper -> baseContext.navisActivity()
    else -> null
}

@Composable
private fun BrowserSurface(
    runtime: AndroidWindowRuntime,
    state: BrowserState,
    fullscreen: Boolean,
    omniboxFocusRequest: Long = 0,
    onOmniboxFocusRequestHandled: (Long) -> Unit,
    onShowTabs: () -> Unit,
    onToggleHistory: () -> Unit,
    bookmarks: List<BookmarkEntry>,
    onSaveBookmark: (BookmarkDraft, ProfileCompletion) -> Unit,
    onProductPage: (String) -> Unit,
    onShowSiteInformation: () -> Unit,
    onOpenDevTools: () -> Unit,
    onBookmark: (BrowserSessionState) -> Unit,
    onShowProfile: () -> Unit,
    avatarName: String,
    avatarAccent: String,
    avatarAppName: String,
) {
    val active = state.activeSession
    val settings = rememberBrowserSettings(runtime.product.settings)
    val omniboxFocusRequester = remember { FocusRequester() }
    var addressEditing by remember(active?.id) { mutableStateOf(false) }
    Scaffold(
        topBar = {
            if (!fullscreen) {
                BrowserToolbar(
                    runtime = runtime,
                    extensionHost = runtime.extensions,
                    state = state,
                    active = active,
                    onBack = { active?.id?.let(runtime::goBack) },
                    onForward = { active?.id?.let(runtime::goForward) },
                    onReload = {
                        active?.let {
                            if (it.navigation.loading == LoadingState.IDLE) {
                                runtime.reload(it.id)
                            } else {
                                runtime.stop(it.id)
                            }
                        }
                    },
                    onNavigate = onProductPage,
                    editing = addressEditing,
                    onEditingChanged = { addressEditing = it },
                    onShowTabs = onShowTabs,
                    onToggleHistory = onToggleHistory,
                    onNewTab = { runtime.openSession(SessionMode.NORMAL) },
                    onNewPrivateTab = { runtime.openSession(SessionMode.PRIVATE) },
                    onProductPage = onProductPage,
                    onShowSiteInformation = onShowSiteInformation,
                    onOpenDevTools = onOpenDevTools,
                    onBookmark = { active?.let(onBookmark) },
                    onShowProfile = onShowProfile,
                    avatarName = avatarName,
                    avatarAccent = avatarAccent,
                    avatarAppName = avatarAppName,
                    focusRequester = omniboxFocusRequester,
                    focusRequest = omniboxFocusRequest,
                    onFocusRequestHandled = onOmniboxFocusRequestHandled,
                )
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .navisPageContentPadding(padding),
        ) {
            if (
                active != null &&
                active.navigation.contentTermination != ContentTermination.NONE
            ) {
                ContentRecoverySurface(
                    crashed = active.navigation.contentTermination == ContentTermination.CRASHED,
                    onRecover = { runtime.reload(active.id) },
                )
            } else if (active?.nativeNewTab == true) {
                key(active.id) {
                    NativeNewTabSurface(
                        privateMode = active.mode == SessionMode.PRIVATE,
                        backEnabled = !addressEditing,
                        bookmarks = bookmarks,
                        showShortcuts = settings.bookmarkBarVisible,
                        sessions = state.sessions,
                        onOpen = onProductPage,
                        onManageBookmarks = { onProductPage("navis://bookmarks/") },
                        onSaveBookmark = onSaveBookmark,
                    )
                }
            } else if (active != null) {
                // Closing the final tab/window publishes an empty state before
                // Activity disposal. No Session means no new engine View owner.
                AndroidView(
                    factory = { context ->
                        AndroidBrowserView(context).also(runtime::attachView)
                    },
                    update = runtime::attachView,
                    onRelease = runtime::detachView,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (
                active?.nativeNewTab != true &&
                active?.navigation?.contentTermination == ContentTermination.NONE &&
                active.navigation.loading != LoadingState.IDLE
            ) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun ContentRecoverySurface(
    crashed: Boolean,
    onRecover: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                stringResource(
                    if (crashed) R.string.page_crashed else R.string.page_process_terminated,
                ),
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onRecover) {
                Text(stringResource(R.string.restore_tab))
            }
        }
    }
}

@Composable
private fun BrowserToolbar(
    runtime: AndroidWindowRuntime,
    extensionHost: ExtensionHost,
    state: BrowserState,
    active: BrowserSessionState?,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onReload: () -> Unit,
    onNavigate: (String) -> Unit,
    onShowTabs: () -> Unit,
    onToggleHistory: () -> Unit,
    onNewTab: () -> Unit,
    onNewPrivateTab: () -> Unit,
    onProductPage: (String) -> Unit,
    onShowSiteInformation: () -> Unit,
    onOpenDevTools: () -> Unit,
    onBookmark: () -> Unit,
    onShowProfile: () -> Unit,
    avatarName: String,
    avatarAccent: String,
    avatarAppName: String,
    focusRequester: FocusRequester,
    focusRequest: Long = 0,
    onFocusRequestHandled: (Long) -> Unit,
    editing: Boolean,
    onEditingChanged: (Boolean) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val pinnedActions = rememberPinnedExtensionActions(extensionHost)
    var focused by remember { mutableStateOf(false) }
    val editPolicy = remember { OmniboxEditPolicy() }
    var address by remember {
        mutableStateOf(TextFieldValue(active.displayAddress()))
    }
    val focusManager = LocalFocusManager.current
    val browseFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val tabsDescription = stringResource(R.string.browser_tabs_count, state.sessions.size)
    val settings = rememberBrowserSettings(runtime.product.settings)
    val suggestions = rememberAddressSuggestions(runtime, active, address, editing, settings)
    var addressFailed by remember(active?.id, address.text) { mutableStateOf(false) }
    var submissionSerial by remember(active?.id) { mutableLongStateOf(0L) }
    val siteLabel = stringResource(R.string.site_information)
    val sitePresentation = rememberToolbarSitePresentation(runtime, active)
    val organization = sitePresentation.organization
    val identityKind = sitePresentation.information?.kind ?: fallbackSiteIdentityKind(active)
    val identityLabel = organization?.let { stringResource(R.string.site_organization_action, it) } ?: siteLabel
    val cancelAddressLabel = stringResource(R.string.browser_cancel_address)
    val isLoading = active != null && active.navigation.loading != LoadingState.IDLE
    val pasteGoLabel = stringResource(R.string.paste_and_go)
    val pasteSearchLabel = stringResource(R.string.paste_and_search)
    val context = androidx.compose.ui.platform.LocalContext.current
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    fun beginAddressEditing() {
        submissionSerial++
        val url = active.displayAddress()
        address = TextFieldValue(url, selection = androidx.compose.ui.text.TextRange(0, url.length))
        onEditingChanged(true)
    }

    fun endAddressEditing() {
        submissionSerial++
        editPolicy.endEditing()
        // Clearing the Android owner can let root focus search pick this editor
        // again (notably after hardware Enter). Keep focus inside the toolbar,
        // on its existing non-editor action; onFocusChanged owns editing state.
        // Navigation updates must not steal focus from the page when not editing.
        if (focused && browseFocusRequester.requestFocus()) {
            keyboardController?.hide()
        }
        if (!focused) onEditingChanged(false)
    }

    fun submitAddress(value: String, forceSearch: Boolean = false) {
        if (value.isBlank() || address.composition != null) return
        val original = active ?: return
        val editText = address.text
        val request = ++submissionSerial
        runtime.product.resolveAddress(value, original.id, forceSearch) { result ->
            val current = runtime.state.activeSession
            if (request != submissionSerial || current?.id != original.id || current.navigation.revision != original.navigation.revision ||
                address.text != editText || address.composition != null || !focused) return@resolveAddress
            result.onSuccess { destination -> endAddressEditing(); onNavigate(destination) }
                .onFailure { addressFailed = true }
        }
    }

    fun commitSuggestion(row: AddressSuggestion) {
        val current = runtime.state.activeSession
        if (address.composition != null || !focused || current?.id != active?.id ||
            current?.navigation?.revision != active?.navigation?.revision) return
        if (row.kind == "search") submitAddress(row.title, forceSearch = true)
        else if (row.kind == "tab") {
            val id = row.id.toLongOrNull()?.let(::SessionId) ?: return
            if (runtime.state.sessions.none { it.id == id && it.navigation.url == row.url && it.mode == active?.mode }) return
            endAddressEditing()
            runtime.activateSession(id)
        } else { endAddressEditing(); onNavigate(row.url) }
    }

    fun cancelAddressEditing() {
        address = TextFieldValue(active.displayAddress())
        endAddressEditing()
    }

    // Leaving this toolbar (including in-app DevTools) must not retain an old IME editor/focus.
    DisposableEffect(editPolicy, focusManager) {
        onDispose {
            editPolicy.endEditing()
            focusManager.clearFocus(force = true)
        }
    }
    LaunchedEffect(active?.id) {
        endAddressEditing()
    }
    LaunchedEffect(active?.id, active?.navigation?.url) { menuOpen = false }

    LaunchedEffect(active?.id, active?.navigation?.url, active?.nativeNewTab, editing) {
        if (!editing) {
            address = TextFieldValue(active.displayAddress())
        }
    }
    LaunchedEffect(focusRequest) {
        if (focusRequest > 0) {
            beginAddressEditing()
            onFocusRequestHandled(focusRequest)
        }
    }
    LaunchedEffect(editing) {
        if (editing) focusRequester.requestFocus()
    }
    BackHandler(enabled = editing) { cancelAddressEditing() }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.navisTopBarInsets(),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navisTopBarContentInsets()
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    BoxWithConstraints {
                    val fontScale = LocalDensity.current.fontScale
                    val layout = omniboxLayout(maxWidth.value, fontScale, organization != null,
                        allowInlineReload = isLandscape)
                    Row(Modifier.heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                        // Keep this focus target alive across browse/edit presentation changes.
                        IconButton(
                            enabled = editing || active != null,
                            onClick = if (editing) ::cancelAddressEditing else {
                                { endAddressEditing(); onShowSiteInformation() }
                            },
                            modifier = Modifier.width(if (editing) 48.dp else layout.identityWidth.dp)
                                .heightIn(min = if (!editing && organization != null)
                                    maxOf(48f, 32f * fontScale + 8f).dp else 48.dp)
                                .focusRequester(browseFocusRequester)
                                .focusProperties { canFocus = true }
                                .semantics { contentDescription = if (editing) cancelAddressLabel else identityLabel },
                            colors = IconButtonDefaults.iconButtonColors(
                                contentColor = when {
                                    !editing && organization != null -> MaterialTheme.colorScheme.primary
                                    !editing && identityKind in setOf("insecure", "broken", "error") -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            ),
                        ) {
                            if (editing) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                            } else if (organization != null) {
                                Text(organization, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                                    style = MaterialTheme.typography.labelMedium)
                            } else if (identityKind == "internal") {
                                NavisBrandMark(Modifier.size(24.dp))
                            } else {
                                Icon(painterResource(siteIdentityIcon(identityKind)), null, Modifier.size(24.dp))
                            }
                        }
                if (editing) TextField(
                            value = address,
                            onValueChange = { submissionSerial++; address = it },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 56.dp)
                                .focusRequester(focusRequester)
                                .onPreviewKeyEvent {
                                    if (it.type == KeyEventType.KeyDown && it.key == Key.Escape && address.composition == null) {
                                        cancelAddressEditing(); true
                                    } else suggestions.key(it, address.composition != null, ::commitSuggestion)
                                }
                                .pointerInput(editPolicy) {
                                    // Observe, never consume: TextField keeps its native long-press,
                                    // selection handles, scrolling and accessibility semantics.
                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                        val token = editPolicy.beginPointerGesture()
                                        var isTap = true
                                        try {
                                            while (true) {
                                                val event = awaitPointerEvent(PointerEventPass.Final)
                                                val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                                                if (event.changes.any { it.id != down.id && it.pressed } ||
                                                    (pointer.position - down.position).getDistance() > viewConfiguration.touchSlop ||
                                                    pointer.uptimeMillis - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis
                                                ) isTap = false
                                                if (!pointer.pressed) {
                                                    // Final runs after TextField's main-pass tap placed its caret.
                                                    if (editPolicy.finishPointerGesture(token, isTap, focused)) {
                                                        address = address.copy(
                                                            selection = androidx.compose.ui.text.TextRange(0, address.text.length),
                                                            composition = null,
                                                        )
                                                    }
                                                    break
                                                }
                                            }
                                        } finally {
                                            editPolicy.cancelPointerGesture(token)
                                        }
                                    }
                                }
                                .appendTextContextMenuComponents {
                                    val manager = context.getSystemService(android.content.ClipboardManager::class.java)
                                    val value = manager.primaryClip?.takeIf { it.itemCount > 0 }
                                        ?.getItemAt(0)?.text?.toString()?.takeIf { it.length in 1..16384 }
                                    if (!value.isNullOrBlank()) {
                                        separator()
                                        item(key = "navis-paste-navigate", label = if (addressInputIsUrl(value)) pasteGoLabel else pasteSearchLabel) {
                                            close()
                                            submitAddress(value)
                                        }
                                    }
                                }
                                .onFocusChanged { focus ->
                                    val wasFocused = focused
                                    focused = focus.isFocused
                                    if (wasFocused && !focused) onEditingChanged(false)
                                    if (editPolicy.focusChanged(focus.isFocused)) {
                                        address = address.copy(
                                            selection = androidx.compose.ui.text.TextRange(0, address.text.length),
                                            composition = null,
                                        )
                                    }
                                },
                            placeholder = { Text(stringResource(R.string.omnibox_search_with, settings.searchProvider.name)) },
                            singleLine = true,
                            shape = MaterialTheme.shapes.extraLarge,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                disabledIndicatorColor = Color.Transparent,
                                errorIndicatorColor = Color.Transparent,
                            ),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Uri,
                                imeAction = ImeAction.Go,
                            ),
                            keyboardActions = KeyboardActions(
                                onGo = {
                                    suggestions.rows.getOrNull(suggestions.selected)?.let(::commitSuggestion)
                                        ?: submitAddress(address.text)
                                },
                            ),
                        ) else OmniboxBrowseText(
                            title = active?.navigation?.title.orEmpty(),
                            url = active.displayAddress(),
                            hint = stringResource(R.string.omnibox_hint),
                            editLabel = stringResource(R.string.browser_edit_address),
                            modifier = Modifier.weight(1f),
                            onEdit = ::beginAddressEditing,
                        )
                        if (editing) {
                            IconButton(
                                enabled = address.text.isNotEmpty(),
                                onClick = { address = TextFieldValue("") },
                                modifier = Modifier.size(48.dp),
                            ) {
                                Icon(Icons.Default.Close, stringResource(R.string.browser_clear_address))
                            }
                        } else if (layout.inlineReload) IconButton(enabled = active != null, onClick = { endAddressEditing(); onReload() }, modifier = Modifier.size(48.dp)) {
                            if (!isLoading) {
                                Icon(Icons.Default.Refresh, stringResource(R.string.reload))
                            } else {
                                Icon(Icons.Default.Close, stringResource(R.string.stop))
                            }
                        }
                    }
                    }
                }
                // Editing gets the whole row; navigation controls return on submit/cancel.
                if (!editing) {
                    ExtensionActionToolbar(extensionHost)
                    if (settings.historyButton) {
                        IconButton(
                            onClick = { endAddressEditing(); onToggleHistory() },
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(painterResource(R.drawable.ic_menu_history),
                                stringResource(R.string.history), Modifier.size(24.dp))
                        }
                    }
                    IconButton(
                        onClick = { endAddressEditing(); onShowTabs() },
                        modifier = Modifier.size(48.dp).semantics {
                            contentDescription = tabsDescription
                        },
                    ) {
                        Box(
                            Modifier.sizeIn(minWidth = 24.dp, minHeight = 24.dp)
                                .border(1.5.dp, MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.shapes.extraSmall)
                                .padding(horizontal = 3.dp, vertical = 1.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(if (state.sessions.size > 99) "99+" else state.sessions.size.toString(),
                                style = MaterialTheme.typography.labelSmall, maxLines = 1)
                        }
                    }
                    // Portrait returns this whole 48dp slot to the measured omnibox.
                    if (isLandscape) {
                        val profileLabel = stringResource(R.string.user_profile_title)
                        IconButton(
                            onClick = { endAddressEditing(); onShowProfile() },
                            modifier = Modifier.size(48.dp).semantics { contentDescription = profileLabel },
                        ) {
                            UserAvatar(avatarName, avatarAccent, avatarAppName, 28.dp)
                        }
                    }
                    Box {
                        IconButton(onClick = { endAddressEditing(); menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, stringResource(R.string.more_options))
                        }
                        NavisDropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                            grouped = true,
                        ) {
                            // Group positions come from the same observable extension snapshot
                            // as the rows: no empty pinned group when nothing is pinned.
                            val groupCount = if (pinnedActions.isEmpty()) 5 else 6
                            NavisMenuGroup(index = 0, count = groupCount) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceEvenly,
                                ) {
                                    IconButton(
                                        enabled = active?.navigation?.canGoBack == true,
                                        onClick = { menuOpen = false; onBack() },
                                    ) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.navigate_back))
                                    }
                                    IconButton(
                                        enabled = active?.navigation?.canGoForward == true,
                                        onClick = { menuOpen = false; onForward() },
                                    ) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowForward, stringResource(R.string.navigate_forward))
                                    }
                                    IconButton(
                                        enabled = active != null,
                                        onClick = { menuOpen = false; onReload() },
                                    ) {
                                        if (!isLoading) {
                                            Icon(Icons.Default.Refresh, stringResource(R.string.reload))
                                        } else {
                                            Icon(Icons.Default.Close, stringResource(R.string.stop))
                                        }
                                    }
                                }
                            }
                            NavisMenuGroup(index = 1, count = groupCount) {
                                NavisMenuItem(
                                    text = { Text(stringResource(R.string.new_tab)) },
                                    leadingIcon = { Icon(Icons.Default.Add, null) },
                                    itemIndex = 0, itemCount = 2,
                                    onClick = { menuOpen = false; onNewTab() },
                                )
                                NavisMenuItem(
                                    text = { Text(stringResource(R.string.new_private_tab)) },
                                    leadingIcon = { Icon(Icons.Default.Lock, null) },
                                    itemIndex = 1, itemCount = 2,
                                    onClick = { menuOpen = false; onNewPrivateTab() },
                                )
                            }
                            NavisMenuGroup(index = 2, count = groupCount) {
                                NavisMenuItem(
                                    text = { Text(stringResource(R.string.bookmark_this_page)) },
                                    leadingIcon = { Icon(Icons.Default.Star, null) },
                                    itemIndex = 0, itemCount = 5,
                                    enabled = active?.navigation?.url?.startsWith("http") == true,
                                    onClick = { menuOpen = false; onBookmark() },
                                )
                                val pages = listOf(
                                    Triple(R.string.history, "navis://history/", R.drawable.ic_menu_history),
                                    Triple(R.string.bookmarks, "navis://bookmarks/", R.drawable.ic_menu_bookmarks),
                                    Triple(R.string.passwords, "navis://passwords/", R.drawable.ic_site_controls),
                                    Triple(R.string.download_notification_channel, "navis://downloads/", R.drawable.ic_menu_download),
                                )
                                pages.forEachIndexed { index, (label, uri, icon) ->
                                    NavisMenuItem(
                                        text = { Text(stringResource(label)) },
                                        leadingIcon = {
                                            if (label == R.string.passwords) Icon(Icons.Default.Lock, null)
                                            else Icon(painterResource(icon), null, Modifier.size(24.dp))
                                        },
                                        itemIndex = index + 1, itemCount = 5,
                                        onClick = { menuOpen = false; onProductPage(uri) },
                                    )
                                }
                            }
                            if (pinnedActions.isNotEmpty()) {
                                NavisMenuGroup(index = 3, count = groupCount) {
                                    ExtensionPinnedMenuItems(pinnedActions, extensionHost) { menuOpen = false }
                                }
                            }
                            NavisMenuGroup(index = groupCount - 2, count = groupCount) {
                                NavisMenuItem(
                                    text = { Text(stringResource(R.string.extensions)) },
                                    leadingIcon = { Icon(painterResource(R.drawable.ic_site_extension), null, Modifier.size(24.dp)) },
                                    itemIndex = 0, itemCount = 3,
                                    onClick = { menuOpen = false; onProductPage("navis://extensions/") },
                                )
                                NavisMenuItem(
                                    text = { Text(stringResource(R.string.processes_title)) },
                                    leadingIcon = { Icon(painterResource(R.drawable.ic_menu_processes), null, Modifier.size(24.dp)) },
                                    itemIndex = 1, itemCount = 3,
                                    onClick = { menuOpen = false; onProductPage("navis://processes/") },
                                )
                                NavisMenuItem(
                                    text = { Text(stringResource(R.string.developer_tools)) },
                                    leadingIcon = { Icon(Icons.Default.Build, null) },
                                    itemIndex = 2, itemCount = 3,
                                    enabled = active?.nativeRoute == null && active != null,
                                    onClick = { menuOpen = false; onOpenDevTools() },
                                )
                            }
                            NavisMenuGroup(index = groupCount - 1, count = groupCount) {
                                NavisMenuItem(
                                    text = { Text(stringResource(R.string.user_profile_title)) },
                                    leadingIcon = { UserAvatar(avatarName, avatarAccent, avatarAppName, 24.dp) },
                                    itemIndex = 0, itemCount = 2,
                                    onClick = { menuOpen = false; onShowProfile() },
                                )
                                NavisMenuItem(
                                    text = { Text(stringResource(R.string.settings)) },
                                    leadingIcon = { Icon(Icons.Default.Settings, null) },
                                    itemIndex = 1, itemCount = 2,
                                    onClick = { menuOpen = false; onProductPage("navis://settings/") },
                                )
                            }
                        }
                    }
                }
            }
            if (editing) {
                if (addressFailed) Text(stringResource(R.string.operation_failed), color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp))
                AddressSuggestionsContent(suggestions, settings.searchProvider.name, ::commitSuggestion)
            }
            if (active?.mode == SessionMode.PRIVATE) {
                Text(
                    text = stringResource(R.string.private_mode),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(start = 20.dp, bottom = 6.dp),
                )
            }
        }
    }
}

private fun BrowserSessionState?.displayAddress(): String =
    if (this?.nativeNewTab == true) "" else this?.navigation?.url.orEmpty()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TabsSheet(
    runtime: AndroidWindowRuntime,
    state: BrowserState,
    onDismiss: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // Position once when opened; do not jump while the user browses or reorders tabs.
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = state.sessions
            .indexOfFirst { it.id == state.activeSessionId }.coerceAtLeast(0),
    )
    // A partial sheet translates a full-height lazy viewport below the screen,
    // hiding the selected end item despite a correct initial scroll position.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val reorder = rememberTabReorderGesture(runtime, listState)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetGesturesEnabled = !reorder.active,
        // Consume top safe drawing outside M3's animated offset/inset layer.
        // Otherwise the last item's initial viewport shrinks near Expanded and
        // LazyColumn preserves its first-item anchor, clipping the selected tail.
        modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.tabs), style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row {
                IconButton(
                    onClick = {
                        runtime.openSession(state.activeSession?.mode ?: SessionMode.NORMAL)
                        onDismiss()
                    },
                ) {
                    Icon(Icons.Default.Add, stringResource(
                        if (state.activeSession?.mode == SessionMode.PRIVATE) R.string.new_private_tab
                        else R.string.browser_new_regular_tab,
                    ))
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, stringResource(R.string.done))
                }
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f, fill = false)
                .fillMaxWidth()
                .then(reorder.gestures())
                .navigationBarsPadding(),
        ) {
            items(state.sessions, key = { it.id.value }) { session ->
                var menuTarget by remember(runtime, session.id) { mutableStateOf<TabMenuTarget?>(null) }
                fun perform(action: TabMenuAction) {
                    val target = menuTarget ?: return
                    menuTarget = null
                    runCatching {
                        target.perform(action, runtime.windowId, { runtime.state },
                            duplicate = { mode, uri -> runtime.createSession(mode, uri); onDismiss() },
                            move = runtime::moveSession, activate = runtime::activateSession,
                            close = runtime::closeSession,
                        )
                    }.onFailure {
                        android.widget.Toast.makeText(context, R.string.operation_failed,
                            android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
                val selected = session.id == state.activeSessionId
                val tabTitle = org.navis.browser.pages.AndroidInternalPages.pages
                    .firstOrNull { it.route == session.nativeRoute }?.let { stringResource(it.title) }
                    ?: session.navigation.title.ifBlank {
                        stringResource(
                            if (session.mode == SessionMode.PRIVATE) R.string.private_tab else R.string.normal_tab,
                        )
                    }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .then(reorder.row(session.id))
                        .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                        .selectable(selected = selected, role = Role.Tab) {
                            runtime.activateSession(session.id)
                            onDismiss()
                        }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TabFavicon(session)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            tabTitle,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            omniboxVisibleAddress(session.navigation.url),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (session.mode == SessionMode.PRIVATE) {
                            Text(
                                stringResource(R.string.browser_private_tab_label),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    Box {
                        IconButton(onClick = {
                            menuTarget = TabMenuTarget.capture(runtime.windowId, session.id, runtime.state)
                        }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Default.MoreVert, stringResource(R.string.browser_tab_actions_for, tabTitle))
                        }
                        NavisDropdownMenu(expanded = menuTarget != null, onDismissRequest = { menuTarget = null }) {
                            NavisMenuItem(
                                text = { Text(stringResource(R.string.tab_duplicate)) },
                                leadingIcon = { Icon(painterResource(R.drawable.ic_menu_copy), null, Modifier.size(24.dp)) },
                                enabled = menuTarget?.enabled(TabMenuAction.DUPLICATE, state) == true,
                                onClick = { perform(TabMenuAction.DUPLICATE) },
                            )
                        }
                    }
                    IconButton(onClick = { runtime.closeSession(session.id) }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.Close, stringResource(R.string.browser_close_tab_named, tabTitle))
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
internal fun CoreStartingScreen() {
    NavisTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    NavisBrandMark(Modifier.size(112.dp), NavisBrandMotion.LOADING)
                    Spacer(modifier = Modifier.size(12.dp))
                    Text(
                        stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Spacer(modifier = Modifier.size(12.dp))
                    Text(stringResource(R.string.core_starting))
                }
            }
        }
    }
}

@Composable
internal fun CoreUnavailableScreen(message: Int = R.string.core_unavailable) {
    NavisTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    NavisBrandMark(Modifier.size(112.dp))
                    Spacer(modifier = Modifier.size(12.dp))
                    Text(
                        stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Spacer(modifier = Modifier.size(12.dp))
                    Text(stringResource(message))
                }
            }
        }
    }
}
