/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */
package org.navis.browser.documents

import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CancellationException

internal object DocumentStreams {
    const val MAX_PDF_BYTES = 256L * 1024 * 1024

    fun copy(source: InputStream, target: OutputStream, cancelled: () -> Boolean, limit: Long = MAX_PDF_BYTES): Long {
        require(limit > 0)
        val buffer = ByteArray(32 * 1024)
        var total = 0L
        while (true) {
            if (cancelled()) throw CancellationException("Document operation cancelled")
            val count = source.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            if (count > limit - total) throw java.io.IOException("Document exceeds print size limit")
            target.write(buffer, 0, count)
            total += count
        }
        if (total == 0L) throw java.io.IOException("Empty print document")
        return total
    }
}
