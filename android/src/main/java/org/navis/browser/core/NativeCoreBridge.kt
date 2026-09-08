/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.core

import org.mozilla.geckoview.internal.NavisCoreBridge as GeckoNavisCoreBridge

internal class NativeCoreBridge private constructor(
    private var handle: Long,
) : CoreBridge {
    companion object {
        fun open(): NativeCoreBridge {
            val handle = GeckoNavisCoreBridge.create()
            check(handle != 0L) { "Navis Core runtime allocation failed" }
            return NativeCoreBridge(handle)
        }
    }

    override fun registerWindow(): Long = GeckoNavisCoreBridge.registerWindow(requireHandle())

    override fun closeWindow(windowId: Long) =
        GeckoNavisCoreBridge.closeWindow(requireHandle(), windowId)

    override fun registerView(windowId: Long): Long =
        GeckoNavisCoreBridge.registerView(requireHandle(), windowId)

    override fun createSession(viewId: Long, privateMode: Boolean): Long =
        GeckoNavisCoreBridge.createSession(requireHandle(), viewId, privateMode)

    override fun activateSession(sessionId: Long): Boolean =
        GeckoNavisCoreBridge.activateSession(requireHandle(), sessionId)

    override fun closeSession(sessionId: Long) =
        GeckoNavisCoreBridge.closeSession(requireHandle(), sessionId)

    override fun setCrashed(sessionId: Long, crashed: Boolean) =
        GeckoNavisCoreBridge.setCrashed(requireHandle(), sessionId, crashed)

    override fun beginNavigation(
        sessionId: Long,
        command: CoreNavigationCommand,
        requestedUri: String?,
    ): Long = GeckoNavisCoreBridge.beginNavigation(
        requireHandle(),
        sessionId,
        command.wireValue,
        requestedUri,
    )

    override fun observeNavigationStart(
        sessionId: Long,
        requestedUri: String?,
        kind: CoreNavigationStartKind,
    ): Long = GeckoNavisCoreBridge.observeNavigationStart(
        requireHandle(),
        sessionId,
        requestedUri,
        kind.wireValue,
    )

    override fun setNavigationLocation(
        sessionId: Long,
        navigationId: Long,
        uri: String,
    ): Boolean = GeckoNavisCoreBridge.setNavigationLocation(
        requireHandle(),
        sessionId,
        navigationId,
        uri,
    )

    override fun setNavigationTitle(
        sessionId: Long,
        navigationId: Long,
        title: String,
    ): Boolean = GeckoNavisCoreBridge.setNavigationTitle(
        requireHandle(),
        sessionId,
        navigationId,
        title,
    )

    override fun setNavigationSecurity(
        sessionId: Long,
        navigationId: Long,
        security: CoreNavigationSecurity,
    ): Boolean = GeckoNavisCoreBridge.setNavigationSecurity(
        requireHandle(),
        sessionId,
        navigationId,
        security.wireValue,
    )

    override fun setNavigationIdentity(
        sessionId: Long,
        navigationId: Long,
        identity: CoreNavigationIdentity,
        identityKey: String,
    ): Boolean = GeckoNavisCoreBridge.setNavigationIdentity(
        requireHandle(),
        sessionId,
        navigationId,
        identity.wireValue,
        identityKey,
    )

    override fun setNavigationHistory(
        sessionId: Long,
        navigationId: Long,
        canGoBack: Boolean,
        canGoForward: Boolean,
    ): Boolean = GeckoNavisCoreBridge.setNavigationHistory(
        requireHandle(),
        sessionId,
        navigationId,
        canGoBack,
        canGoForward,
    )

    override fun finishNavigation(
        sessionId: Long,
        navigationId: Long,
        failureCode: Int?,
    ): Boolean = GeckoNavisCoreBridge.finishNavigation(
        requireHandle(),
        sessionId,
        navigationId,
        failureCode != null,
        failureCode ?: 0,
    )

    override fun stopNavigation(sessionId: Long, navigationId: Long): Boolean =
        GeckoNavisCoreBridge.stopNavigation(requireHandle(), sessionId, navigationId)

    override fun navigationSnapshot(sessionId: Long): CoreNavigationSnapshot {
        val snapshot = GeckoNavisCoreBridge.navigationSnapshot(requireHandle(), sessionId)
        return CoreNavigationSnapshot(
            revision = snapshot.revision,
            navigationId = snapshot.navigationId,
            url = snapshot.url,
            title = snapshot.title,
            activity = snapshot.activity,
            security = snapshot.security,
            identity = snapshot.identity,
            identityKey = snapshot.identityKey,
            canGoBack = snapshot.canGoBack,
            canGoForward = snapshot.canGoForward,
            hasFailure = snapshot.hasFailure,
            failureCode = snapshot.failureCode,
        )
    }

    override fun checkInvariants() = GeckoNavisCoreBridge.checkInvariants(requireHandle())

    override fun shutdown(): Boolean = GeckoNavisCoreBridge.shutdown(requireHandle())

    override fun close() {
        val current = handle
        if (current == 0L) {
            return
        }
        handle = 0
        GeckoNavisCoreBridge.free(current)
    }

    private fun requireHandle(): Long {
        check(handle != 0L) { "Navis Core runtime is closed" }
        return handle
    }

}
