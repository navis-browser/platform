/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.ui

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import org.navis.browser.api.BrowserStateObserver
import org.navis.browser.api.SessionId
import org.navis.browser.engine.AndroidWindowRuntime

internal class TabReorderGesture(
    private val runtime: AndroidWindowRuntime,
    private val list: LazyListState,
    private val edge: Float,
) {
    private var target by mutableStateOf<TabReorderTarget?>(null)
    private var pointerY by mutableStateOf(0f)
    private var grabOffset = 0f
    private var itemHeight = 0
    private var lastOver: SessionId? = null
    val active: Boolean get() = target != null

    fun validate() {
        if (target?.matches(runtime.windowId, runtime.state) == false) stop()
    }

    fun begin(position: Offset) {
        val item = list.layoutInfo.visibleItemsInfo.firstOrNull {
            position.y >= it.offset && position.y < it.offset + it.size
        } ?: return
        val id = (item.key as? Long)?.let(::SessionId) ?: return
        target = TabReorderTarget.capture(runtime.windowId, id, runtime.state) ?: return
        pointerY = position.y
        grabOffset = position.y - item.offset
        itemHeight = item.size
        lastOver = null
    }

    fun stop() {
        target = null
        lastOver = null
    }

    fun dragBy(amount: Float) {
        pointerY += amount
        reorder()
    }

    private fun reorder() {
        val current = target ?: return
        if (!current.matches(runtime.windowId, runtime.state)) { stop(); return }
        val center = pointerY - grabOffset + itemHeight / 2f
        val over = list.layoutInfo.visibleItemsInfo.firstOrNull {
            it.key != current.sessionId.value && center >= it.offset && center < it.offset + it.size
        }?.key as? Long
        if (over == null) { lastOver = null; return }
        val id = SessionId(over)
        if (id == lastOver) return
        val destination = current.destination(runtime.windowId, runtime.state, id) ?: return
        lastOver = id
        runCatching { runtime.moveSession(current.sessionId, destination) }.onFailure { stop() }
    }

    suspend fun scrollFrame() {
        if (!active) return
        val viewport = list.layoutInfo
        val speed = when {
            pointerY < viewport.viewportStartOffset + edge -> -((viewport.viewportStartOffset + edge - pointerY) / edge).coerceIn(0f, 1f)
            pointerY > viewport.viewportEndOffset - edge -> ((pointerY - viewport.viewportEndOffset + edge) / edge).coerceIn(0f, 1f)
            else -> 0f
        }
        if (speed != 0f) {
            list.scrollBy(speed * edge / 4)
            reorder()
        }
    }

    fun gestures(): Modifier = Modifier.pointerInput(this) {
        detectDragGesturesAfterLongPress(
            onDragStart = ::begin,
            onDragEnd = ::stop,
            onDragCancel = ::stop,
            onDrag = { change, amount -> if (active) { change.consume(); dragBy(amount.y) } },
        )
    }

    fun row(id: SessionId): Modifier = Modifier.zIndex(if (target?.sessionId == id) 1f else 0f)
        .graphicsLayer {
            if (target?.sessionId == id) {
                val current = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id.value }
                translationY = if (current == null) 0f else pointerY - grabOffset - current.offset
                shadowElevation = 8.dp.toPx()
            } else {
                translationY = 0f
                shadowElevation = 0f
            }
        }
}

@Composable
internal fun rememberTabReorderGesture(runtime: AndroidWindowRuntime, list: LazyListState): TabReorderGesture {
    val edge = with(LocalDensity.current) { 48.dp.toPx() }
    val gesture = remember(runtime, list, edge) { TabReorderGesture(runtime, list, edge) }
    DisposableEffect(runtime, gesture) {
        val observer = BrowserStateObserver { gesture.validate() }
        runtime.addObserver(observer)
        onDispose { runtime.removeObserver(observer); gesture.stop() }
    }
    LaunchedEffect(gesture, gesture.active) {
        while (gesture.active) {
            withFrameNanos { }
            gesture.scrollFrame()
        }
    }
    return gesture
}
