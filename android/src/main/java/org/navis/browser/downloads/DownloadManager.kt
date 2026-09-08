/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.downloads

import java.net.URI
import java.util.concurrent.CompletionStage

internal enum class DownloadState { DOWNLOADING, PAUSED, COMPLETE, CANCELLED, FAILED }

/** Immutable product projection of the same records exposed to downloads.* extensions. */
internal data class DownloadEntry(
    val id: Long,
    val fileName: String,
    val sourceUri: String,
    val privateMode: Boolean,
    val state: DownloadState,
    val bytesReceived: Long,
    val totalBytes: Long,
    val exists: Boolean,
    val canRetry: Boolean,
    val canPause: Boolean,
    val startedAt: String,
) {
    val active: Boolean get() = state == DownloadState.DOWNLOADING || state == DownloadState.PAUSED
    val progress: Float? get() = if (totalBytes > 0) {
        (bytesReceived.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)
    } else {
        null
    }
    val canOpen: Boolean get() = state == DownloadState.COMPLETE && exists
    val canOpenSource: Boolean get() = runCatching {
        URI(sourceUri).let { it.scheme in setOf("http", "https") && it.host != null }
    }.getOrDefault(false)
}

internal fun interface DownloadObserver {
    fun onDownloadsChanged(entries: List<DownloadEntry>)
}

/** Android presentation consumes this contract without owning another download history. */
internal interface DownloadManager {
    fun find(id: Long, privateMode: Boolean): DownloadEntry?
    fun addObserver(privateMode: Boolean, observer: DownloadObserver)
    fun removeObserver(observer: DownloadObserver)
    fun refresh()
    fun cancel(id: Long, privateMode: Boolean): CompletionStage<Unit>
    fun pause(id: Long, privateMode: Boolean): CompletionStage<Unit>
    fun resume(id: Long, privateMode: Boolean): CompletionStage<Unit>
    fun retry(id: Long, privateMode: Boolean): CompletionStage<Unit>
    fun remove(id: Long, privateMode: Boolean): CompletionStage<Unit>
    fun clearFinished(privateMode: Boolean): CompletionStage<Unit>
    fun open(id: Long, privateMode: Boolean): CompletionStage<Unit>
}
