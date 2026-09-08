/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.api

@JvmInline
value class TargetRequestId(val value: Long)

enum class PlatformPermission {
    CAMERA,
    MICROPHONE,
    COARSE_LOCATION,
    FINE_LOCATION,
    BLUETOOTH_CONNECT,
    BLUETOOTH_SCAN,
}

enum class SitePermissionKind {
    GEOLOCATION,
    NOTIFICATIONS,
    PERSISTENT_STORAGE,
    XR,
    AUTOPLAY_AUDIBLE,
    AUTOPLAY_INAUDIBLE,
    MEDIA_KEY_SYSTEM_ACCESS,
    TRACKING,
    STORAGE_ACCESS,
    LOCAL_DEVICE_ACCESS,
    LOCAL_NETWORK_ACCESS,
}

/** User intent must survive the target boundary: dismissing is not a saved block. */
enum class SitePermissionDecision(val wireValue: String, val allows: Boolean) {
    ALLOW_SESSION("allow-session", true),
    ALLOW_ALWAYS("allow-always", true),
    BLOCK("block", false),
    DISMISS("dismiss", false),
}

enum class FilePickerMode {
    SINGLE,
    MULTIPLE,
    FOLDER,
}

enum class FileCapture {
    NONE,
    ANY,
    USER,
    ENVIRONMENT,
}

enum class ChoicePromptMode {
    MENU,
    SINGLE,
    MULTIPLE,
}

data class ChoicePromptOption(
    val id: String,
    val label: String,
    val selected: Boolean,
    val disabled: Boolean,
    val separator: Boolean,
    val group: Boolean,
    val children: List<ChoicePromptOption> = emptyList(),
)

enum class DateTimePromptKind {
    DATE,
    MONTH,
    WEEK,
    TIME,
    DATETIME_LOCAL,
}

data class MediaSourceDescriptor(
    val id: String,
    val label: String,
)

data class LoginPromptOption(
    val index: Int,
    val origin: String,
    val username: String,
)

sealed interface BrowserPrompt {
    val id: TargetRequestId
    val sessionId: SessionId
    val title: String?
    val message: String?
    val privateMode: Boolean

    data class Alert(
        override val id: TargetRequestId,
        override val sessionId: SessionId,
        override val title: String?,
        override val message: String?,
        override val privateMode: Boolean,
    ) : BrowserPrompt

    data class Confirm(
        override val id: TargetRequestId,
        override val sessionId: SessionId,
        override val title: String?,
        override val message: String?,
        override val privateMode: Boolean,
    ) : BrowserPrompt

    data class Text(
        override val id: TargetRequestId,
        override val sessionId: SessionId,
        override val title: String?,
        override val message: String?,
        override val privateMode: Boolean,
        val defaultValue: String,
    ) : BrowserPrompt

    data class Authentication(
        override val id: TargetRequestId,
        override val sessionId: SessionId,
        override val title: String?,
        override val message: String?,
        override val privateMode: Boolean,
        val uri: String?,
        val username: String,
        val password: String,
        val passwordOnly: Boolean,
        val previousAttemptFailed: Boolean,
        val secure: Boolean,
    ) : BrowserPrompt

    data class Choice(
        override val id: TargetRequestId,
        override val sessionId: SessionId,
        override val title: String?,
        override val message: String?,
        override val privateMode: Boolean,
        val mode: ChoicePromptMode,
        val choices: List<ChoicePromptOption>,
    ) : BrowserPrompt

    data class DateTime(
        override val id: TargetRequestId,
        override val sessionId: SessionId,
        override val title: String?,
        override val message: String? = null,
        override val privateMode: Boolean,
        val kind: DateTimePromptKind,
        val defaultValue: String?,
        val minimumValue: String?,
        val maximumValue: String?,
        val stepValue: String?,
    ) : BrowserPrompt

    data class ColorPicker(
        override val id: TargetRequestId,
        override val sessionId: SessionId,
        override val title: String?,
        override val message: String? = null,
        override val privateMode: Boolean,
        val defaultValue: String?,
        val predefinedValues: List<String>,
    ) : BrowserPrompt

    data class SaveLogin(
        override val id: TargetRequestId,
        override val sessionId: SessionId,
        override val title: String? = null,
        override val message: String? = null,
        override val privateMode: Boolean,
        val login: LoginPromptOption,
        val saving: Boolean = false,
        val saveFailed: Boolean = false,
    ) : BrowserPrompt

    data class SelectLogin(
        override val id: TargetRequestId,
        override val sessionId: SessionId,
        override val title: String? = null,
        override val message: String? = null,
        override val privateMode: Boolean,
        val logins: List<LoginPromptOption>,
    ) : BrowserPrompt
}

sealed interface PromptResponse {
    data object Dismiss : PromptResponse
    data object Accept : PromptResponse
    data object Reject : PromptResponse
    data class TextValue(val value: String) : PromptResponse
    data class Credentials(val username: String, val password: String) : PromptResponse
    data class SelectOption(val index: Int) : PromptResponse
    data class ChoiceValue(val ids: List<String>) : PromptResponse
    data class DateTimeValue(val value: String) : PromptResponse
    data class ColorValue(val value: String) : PromptResponse
}

sealed interface SitePermissionRequest {
    val id: TargetRequestId
    val sessionId: SessionId
    val uri: String
    val privateMode: Boolean

    data class Content(
        override val id: TargetRequestId,
        override val sessionId: SessionId,
        override val uri: String,
        override val privateMode: Boolean,
        val kind: SitePermissionKind,
        val thirdPartyOrigin: String?,
    ) : SitePermissionRequest

    data class Media(
        override val id: TargetRequestId,
        override val sessionId: SessionId,
        override val uri: String,
        override val privateMode: Boolean,
        val videoSources: List<MediaSourceDescriptor>,
        val audioSources: List<MediaSourceDescriptor>,
    ) : SitePermissionRequest
}

data class PlatformPermissionRequest(
    val id: TargetRequestId,
    val sessionId: SessionId,
    val permissions: Set<PlatformPermission>,
    val privateMode: Boolean,
)

data class FilePickerRequest(
    val id: TargetRequestId,
    val sessionId: SessionId,
    val mode: FilePickerMode,
    val capture: FileCapture,
    val mimeTypes: List<String>,
    val privateMode: Boolean,
)

data class TargetRequestState(
    val prompt: BrowserPrompt? = null,
    val sitePermission: SitePermissionRequest? = null,
    val platformPermission: PlatformPermissionRequest? = null,
    val filePicker: FilePickerRequest? = null,
    val sitePermissionSaveFailed: Boolean = false,
    val fullscreenSessionId: SessionId? = null,
)

fun interface TargetRequestObserver {
    fun onTargetRequestStateChanged(state: TargetRequestState)
}

interface BrowserTargetRuntime {
    val targetState: TargetRequestState

    fun addTargetObserver(observer: TargetRequestObserver)

    fun removeTargetObserver(observer: TargetRequestObserver)

    fun respondToPrompt(id: TargetRequestId, response: PromptResponse)

    fun notifySitePermissionShown(id: TargetRequestId)

    fun respondToSitePermission(id: TargetRequestId, allow: Boolean)

    fun respondToSitePermission(id: TargetRequestId, decision: SitePermissionDecision) =
        respondToSitePermission(id, decision.allows)

    fun respondToPlatformPermission(id: TargetRequestId, granted: Boolean)

    fun respondToFilePicker(id: TargetRequestId, uris: List<String>)

    fun exitFullscreen()
}
