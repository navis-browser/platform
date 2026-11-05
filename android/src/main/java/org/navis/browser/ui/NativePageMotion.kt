/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.LayoutDirection
import org.navis.browser.api.BrowserSessionState
import org.navis.browser.api.SessionId

/** Immutable Runtime projection. It does not own, push or traverse history. */
internal data class NativePageTarget(val session: SessionId?, val route: String?, val index: Int) {
    val stateKey: String get() = "${session?.value}:$index:$route"
}

internal fun fullPageRoute(route: String?): String? = route?.takeUnless { it == "newtab" }
internal fun BrowserSessionState?.pageTarget() = NativePageTarget(
    this?.id, fullPageRoute(this?.nativeRoute), this?.presentationIndex ?: -1,
)

internal enum class PageMotion { NONE, FORWARD, BACK, TAB }
internal fun pageMotion(from: NativePageTarget, to: NativePageTarget): PageMotion = when {
    from.session != to.session -> PageMotion.TAB
    from.route == to.route -> PageMotion.NONE
    to.index >= 0 && from.index >= 0 && to.index < from.index -> PageMotion.BACK
    else -> PageMotion.FORWARD
}

/** Suppress pointer, keyboard focus and accessibility on outgoing/hidden pages. */
internal fun Modifier.pageInputEnabled(enabled: Boolean): Modifier = if (enabled) this else
    clearAndSetSemantics {}.focusProperties { canFocus = false }.pointerInput(Unit) {
        awaitEachGesture {
            do {
                val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                event.changes.forEach { it.consume() }
            } while (event.changes.any { it.pressed })
        }
    }

/** Native content only. The single engine View is a stable sibling, never an animated child. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun NativePageHost(
    active: BrowserSessionState?,
    backEnabled: Boolean,
    currentSession: () -> BrowserSessionState?,
    onBack: (SessionId) -> Unit,
    background: @Composable () -> Unit,
    content: @Composable (String) -> Unit,
) {
    val target = active.pageTarget()
    val transition = updateTransition(target, label = "Native page")
    val focus = LocalFocusManager.current
    val holder = rememberSaveableStateHolder()
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1 else 1
    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<androidx.compose.ui.unit.IntOffset>()
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()

    LaunchedEffect(target) { focus.clearFocus() }
    BackHandler(enabled = backEnabled && target.route != null && target.index > 0) {
        // AndroidX invokes this only after Back is committed. Reject an obsolete
        // projection rather than navigating a different Session or history entry.
        if (currentSession().pageTarget() == target) target.session?.let(onBack)
    }
    Box(Modifier.fillMaxSize()) {
        background()
        transition.AnimatedContent(
            modifier = Modifier.fillMaxSize().clipToBounds(),
            transitionSpec = {
                val motion = pageMotion(initialState, targetState)
                val sign = if (motion == PageMotion.BACK) -direction else direction
                when (motion) {
                    PageMotion.NONE -> androidx.compose.animation.EnterTransition.None togetherWith
                        androidx.compose.animation.ExitTransition.None
                    PageMotion.TAB -> fadeIn(effects) togetherWith fadeOut(effects)
                    else -> (slideInHorizontally(spatial) { sign * it / 8 } + fadeIn(effects)) togetherWith
                        (slideOutHorizontally(spatial) { -sign * it / 8 } + fadeOut(effects))
                }.using(null)
            },
            contentKey = { it.stateKey },
        ) { page ->
            if (page.route != null) {
                val interactive = page == target
                Box(Modifier.fillMaxSize().pageInputEnabled(interactive).background(MaterialTheme.colorScheme.surface)) {
                    holder.SaveableStateProvider(page.stateKey) { content(page.route) }
                }
            }
        }
    }
}
