/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.uploads

import java.io.InputStream
import java.io.OutputStream

internal enum class UploadFailure { CANCELLED, FILE_COUNT, FILE_SIZE, TOTAL_SIZE, TREE_LIMIT, NO_SPACE, INVALID_NAME, UNREADABLE }
internal class UploadException(val reason: UploadFailure) : Exception(reason.name)

internal object UploadSelectionPolicy {
    const val MAX_FILES = 32
    const val MAX_FILE_BYTES = 128L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 512L * 1024 * 1024
    const val MAX_TREE_ENTRIES = 2048
    const val MAX_TREE_DEPTH = 32
    const val MIN_FREE_BYTES = 16L * 1024 * 1024

    fun checkedName(value: String): String {
        if (value.isEmpty() || value == "." || value == ".." ||
            value.any { it == '/' || it == '\\' || it == '\u0000' } ||
            value.toByteArray(Charsets.UTF_8).size > 255
        ) throw UploadException(UploadFailure.INVALID_NAME)
        // Names are preserved, never renamed/sanitized into a false webkitRelativePath.
        return value
    }

    fun copyBounded(
        input: InputStream,
        output: OutputStream,
        totalBefore: Long,
        checkCancelled: () -> Unit,
        availableBytes: () -> Long,
        maximumFileBytes: Long = MAX_FILE_BYTES,
        maximumTotalBytes: Long = MAX_TOTAL_BYTES,
        minimumFreeBytes: Long = MIN_FREE_BYTES,
    ): Long {
        var copied = 0L
        val buffer = ByteArray(32 * 1024)
        while (true) {
            checkCancelled()
            val count = input.read(buffer)
            if (count < 0) return copied
            if (count == 0) continue
            if (copied + count > maximumFileBytes) throw UploadException(UploadFailure.FILE_SIZE)
            if (totalBefore + copied + count > maximumTotalBytes) throw UploadException(UploadFailure.TOTAL_SIZE)
            if (availableBytes() - count < minimumFreeBytes) throw UploadException(UploadFailure.NO_SPACE)
            output.write(buffer, 0, count)
            copied += count
        }
    }
}
