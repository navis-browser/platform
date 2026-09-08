/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.engine

import android.os.Handler
import android.os.Looper
import android.webkit.URLUtil
import org.navis.browser.api.DownloadFailure
import org.navis.browser.api.SessionId
import org.navis.browser.engine.runtime.EngineDownloadResponse
import org.navis.browser.settings.AndroidSettingsHost

/** Keeps the original response body owned while the originating window chooses its destination. */
internal class AndroidDownloadPreferences(
    private val settings: AndroidSettingsHost,
    private val store: AndroidResponseDownloadStore,
    private val windowIdForSession: (SessionId) -> Long?,
    private val isSessionLive: (SessionId, Boolean) -> Boolean,
) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val pending = linkedSetOf<EngineDownloadResponse>()
    private var closed = false

    fun accept(source: EngineDownloadResponse) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (closed || !isSessionLive(source.sessionId, source.privateMode)) {
            source.close(); return
        }
        if (!settings.snapshot.askBeforeSaving) {
            store.enqueue(source); return
        }
        val window = windowIdForSession(source.sessionId)
        if (window == null) {
            store.reject(source, DownloadFailure.CANCELLED); return
        }
        pending += source
        val mime = DownloadMetadata.sanitizeMimeType(source.contentType)
        val name = DownloadMetadata.sanitizeFileName(source.suggestedFileName ?: runCatching {
            URLUtil.guessFileName(source.uri.take(16384), source.contentDisposition, mime)
        }.getOrNull())
        AndroidExtensionDownloadSaveAs.request(name, mime, windowId = window).whenComplete { uri, error ->
            main.post {
                if (!pending.remove(source)) return@post
                if (closed || !isSessionLive(source.sessionId, source.privateMode)) source.close()
                else if (error != null) store.reject(source,
                    if (error.message?.contains("cancelled", ignoreCase = true) == true) DownloadFailure.CANCELLED
                    else DownloadFailure.STORAGE_UNAVAILABLE)
                else store.enqueueTracked(source, AndroidDownloadWriteOptions(destinationUri = uri, fileName = name))
            }
        }
    }

    override fun close() {
        closed = true
        pending.toList().forEach(EngineDownloadResponse::close)
        pending.clear()
    }
}
