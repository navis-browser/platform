/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.ui

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.navis.browser.api.BrowserSessionState
import org.navis.browser.engine.SessionFaviconPolicy

/** UI decodes bounded local PNG bytes; it has no icon network authority. */
@Composable
internal fun TabFavicon(session: BrowserSessionState) {
    val native = session.nativeNewTab || session.nativeRoute != null
    val source = if (native) "" else session.navigation.faviconPng
    var bitmap by remember(source) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(source) {
        bitmap = withContext(Dispatchers.Default) {
            if (source.isEmpty() || !SessionFaviconPolicy.accepts(source)) return@withContext null
            runCatching {
                val bytes = Base64.decode(source.substring(SessionFaviconPolicy.PNG_PREFIX.length), Base64.NO_WRAP)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth !in 1..64 || bounds.outHeight !in 1..64) return@runCatching null
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }.getOrNull()
        }
    }
    Box(Modifier.size(24.dp)) {
        val image = bitmap
        when {
            native -> NavisBrandMark(Modifier.size(24.dp))
            image != null -> Image(image, null, Modifier.size(24.dp))
            else -> {
                val color = MaterialTheme.colorScheme.onSurfaceVariant
                Canvas(Modifier.size(24.dp)) {
                    val line = Stroke(1.5.dp.toPx())
                    val diameter = size.minDimension * .8f
                    val inset = (size.minDimension - diameter) / 2
                    drawCircle(color, diameter / 2, style = line)
                    drawOval(color, Offset(size.width * .33f, inset),
                        Size(size.width * .34f, diameter), style = line)
                    drawLine(color, Offset(inset, center.y),
                        Offset(size.width - inset, center.y), line.width)
                }
            }
        }
    }
}
