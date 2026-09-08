/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.core

internal enum class CoreNavigationCommand(val wireValue: Int) {
    LOAD(0),
    RELOAD(1),
    BACK(2),
    FORWARD(3),
    RESTORE(4),
}

internal enum class CoreNavigationStartKind(val wireValue: Int) {
    STANDARD(0),
    INTERNAL_PAGE(1),
}

internal enum class CoreNavigationSecurity(val wireValue: Int) {
    UNKNOWN(0),
    INSECURE(1),
    BROKEN(2),
    SECURE(3),
}

internal enum class CoreNavigationIdentity(val wireValue: Int) {
    UNKNOWN(0),
    WEB(1),
    INTERNAL_PAGE(2),
    BUILT_IN_EXTENSION(3),
    INTERNAL_ERROR(4),
}

internal data class CoreNavigationSnapshot(
    val revision: Long,
    val navigationId: Long,
    val url: String,
    val title: String,
    val activity: Int,
    val security: Int,
    val identity: Int,
    val identityKey: String,
    val canGoBack: Boolean,
    val canGoForward: Boolean,
    val hasFailure: Boolean,
    val failureCode: Int,
)

internal interface CoreBridge : AutoCloseable {
    fun registerWindow(): Long

    fun closeWindow(windowId: Long)

    fun registerView(windowId: Long): Long

    fun createSession(viewId: Long, privateMode: Boolean): Long

    fun activateSession(sessionId: Long): Boolean

    fun closeSession(sessionId: Long)

    fun setCrashed(sessionId: Long, crashed: Boolean)

    fun beginNavigation(
        sessionId: Long,
        command: CoreNavigationCommand,
        requestedUri: String?,
    ): Long

    fun observeNavigationStart(
        sessionId: Long,
        requestedUri: String?,
        kind: CoreNavigationStartKind,
    ): Long

    fun setNavigationLocation(sessionId: Long, navigationId: Long, uri: String): Boolean

    fun setNavigationTitle(sessionId: Long, navigationId: Long, title: String): Boolean

    fun setNavigationSecurity(
        sessionId: Long,
        navigationId: Long,
        security: CoreNavigationSecurity,
    ): Boolean

    fun setNavigationIdentity(
        sessionId: Long,
        navigationId: Long,
        identity: CoreNavigationIdentity,
        identityKey: String,
    ): Boolean

    fun setNavigationHistory(
        sessionId: Long,
        navigationId: Long,
        canGoBack: Boolean,
        canGoForward: Boolean,
    ): Boolean

    fun finishNavigation(
        sessionId: Long,
        navigationId: Long,
        failureCode: Int?,
    ): Boolean

    fun stopNavigation(sessionId: Long, navigationId: Long): Boolean

    fun navigationSnapshot(sessionId: Long): CoreNavigationSnapshot

    fun checkInvariants()

    fun shutdown(): Boolean
}
