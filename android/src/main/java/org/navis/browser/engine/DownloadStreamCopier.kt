/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.engine

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Fixed-memory response copier shared by both Android storage backends. */
internal object DownloadStreamCopier {
    const val BUFFER_BYTES = 64 * 1024

    fun copy(
        input: InputStream,
        output: OutputStream,
        onProgress: (Long) -> Unit,
    ): Long {
        val buffer = ByteArray(BUFFER_BYTES)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) {
                break
            }
            if (count == 0) {
                continue
            }
            output.write(buffer, 0, count)
            if (total > Long.MAX_VALUE - count) {
                throw IOException("Download size overflow")
            }
            total += count
            onProgress(total)
        }
        output.flush()
        return total
    }
}
