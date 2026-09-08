/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.api

@JvmInline
value class SessionId(val value: Long)

enum class SessionMode {
    NORMAL,
    PRIVATE,
}

enum class LoadingState {
    IDLE,
    PENDING,
    VISIBLE,
    SILENT,
}

enum class SecurityState {
    UNKNOWN,
    INSECURE,
    BROKEN,
    SECURE,
}

/** Why an engine content process stopped owning this Session. */
enum class ContentTermination {
    NONE,
    TERMINATED,
    CRASHED,
}

data class NavigationState(
    val revision: Long = 0,
    val url: String = "",
    val title: String = "",
    val loading: LoadingState = LoadingState.IDLE,
    val security: SecurityState = SecurityState.UNKNOWN,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val crashed: Boolean = false,
    val contentTermination: ContentTermination = ContentTermination.NONE,
    /** In-memory bounded PNG data URI from the authenticated engine document. */
    val faviconPng: String = "",
    /** The engine's actual transport nsresult, not an HTTP response code or a crash. */
    val failureCode: Int? = null,
    /** Gecko already owns/rendered its error document; Platform must not replace it. */
    val engineErrorPage: Boolean = false,
)

data class BrowserSessionState(
    val id: SessionId,
    val mode: SessionMode,
    val active: Boolean,
    val navigation: NavigationState,
    val nativeNewTab: Boolean = false,
    val nativeRoute: String? = null,
)

data class BrowserState(
    val sessions: List<BrowserSessionState> = emptyList(),
    val activeSessionId: SessionId? = null,
    val developerMode: Boolean = false,
    /** Browser window presentation, independent of the document fullscreen API. */
    val windowFullscreen: Boolean = false,
) {
    val activeSession: BrowserSessionState?
        get() = sessions.firstOrNull { it.id == activeSessionId }
}

@JvmInline
value class DownloadId(val value: Long)

data class DownloadRequest(
    val id: DownloadId,
    val sessionId: SessionId,
    val sourceUri: String,
    val fileName: String,
    val contentType: String,
    val expectedBytes: Long?,
    val privateMode: Boolean,
)

enum class DownloadFailure {
    RESPONSE_BODY_UNAVAILABLE,
    STORAGE_UNAVAILABLE,
    TOO_MANY_PENDING,
    TRANSFER_FAILED,
    CANCELLED,
}

data class DownloadResult(
    val request: DownloadRequest,
    val destinationUri: String?,
    val bytesWritten: Long,
    val failure: DownloadFailure?,
) {
    val successful: Boolean
        get() = failure == null
}

interface BrowserDelegate {
    fun onDownloadRequested(request: DownloadRequest)

    fun onDownloadFinished(result: DownloadResult)

    fun onSessionCrashed(sessionId: SessionId)
}

fun interface BrowserStateObserver {
    fun onBrowserStateChanged(state: BrowserState)
}

interface BrowserView

interface BrowserRuntime : AutoCloseable {
    val state: BrowserState

    fun addObserver(observer: BrowserStateObserver)

    fun removeObserver(observer: BrowserStateObserver)

    fun attachView(view: BrowserView)

    fun detachView(view: BrowserView)

    fun createSession(mode: SessionMode, initialUri: String? = null): SessionId

    fun activateSession(sessionId: SessionId)

    fun moveSession(sessionId: SessionId, index: Int)

    fun closeSession(sessionId: SessionId)

    fun load(sessionId: SessionId, uri: String)

    fun reload(sessionId: SessionId)

    fun stop(sessionId: SessionId)

    fun goBack(sessionId: SessionId)

    fun goForward(sessionId: SessionId)

    fun setDeveloperMode(enabled: Boolean)
}
