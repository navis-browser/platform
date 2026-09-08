/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.ui

import android.animation.ValueAnimator
import android.database.ContentObserver
import android.graphics.PathMeasure
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.core.graphics.PathParser
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.navis.browser.R

/** Native Android renderer of the shared brand geometry; no WebView, bitmap animation or timer. */
@Composable
internal fun NavisBrandMark(
    modifier: Modifier = Modifier,
    motion: NavisBrandMotion = NavisBrandMotion.STATIC,
) {
    if (motion == NavisBrandMotion.STATIC) {
        Image(painterResource(R.drawable.ic_navis_brand), null, modifier.navisBrandOuterClip())
        return
    }
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val view = LocalView.current
    var foreground by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var inViewport by remember { mutableStateOf(false) }
    fun systemScale(): Float = if (ValueAnimator.areAnimatorsEnabled()) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
            .coerceAtLeast(0f)
    } else 0f
    var durationScale by remember(context) { mutableFloatStateOf(systemScale()) }
    DisposableEffect(context, lifecycle) {
        val lifecycleObserver = LifecycleEventObserver { _, _ ->
            foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (foreground) durationScale = systemScale()
        }
        val motionObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { durationScale = systemScale() }
        }
        lifecycle.addObserver(lifecycleObserver)
        context.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, motionObserver,
        )
        onDispose {
            lifecycle.removeObserver(lifecycleObserver)
            context.contentResolver.unregisterContentObserver(motionObserver)
        }
    }
    // Lazy grids retain this flag with their item key even when the heading is recycled.
    var entranceCompleted by rememberSaveable(motion) { mutableStateOf(false) }
    var started by remember(motion) { mutableStateOf(false) }
    var elapsed by remember(motion) {
        mutableFloatStateOf(if (entranceCompleted) NavisBrandGeometry.INTRO_MILLISECONDS else 0f)
    }
    val running = foreground && inViewport && durationScale > 0f
    LaunchedEffect(motion, running, durationScale) {
        if (durationScale <= 0f) {
            // Reduced motion is an explicit final-frame choice, not a pause
            // that should unexpectedly replay if the setting changes later.
            elapsed = NavisBrandGeometry.INTRO_MILLISECONDS
            if (motion == NavisBrandMotion.COUNTERFLOW) entranceCompleted = true
            return@LaunchedEffect
        }
        if (!running) {
            // Before first resume/layout there is nothing to interrupt or consume.
            elapsed = navisBrandInterruptedElapsed(started, elapsed)
            if (started) entranceCompleted = true
            return@LaunchedEffect
        }
        if (motion == NavisBrandMotion.COUNTERFLOW && entranceCompleted) return@LaunchedEffect
        var previous = withFrameNanos { it }
        started = true
        while (motion == NavisBrandMotion.LOADING || elapsed < NavisBrandGeometry.INTRO_MILLISECONDS) {
            val now = withFrameNanos { it }
            elapsed += (now - previous) / 1_000_000f / durationScale
            previous = now
            if (elapsed >= NavisBrandGeometry.INTRO_MILLISECONDS) entranceCompleted = true
        }
    }
    val geometry = remember { BrandPaths() }
    Canvas(modifier.onGloballyPositioned { coordinates ->
        val bounds = coordinates.boundsInWindow()
        inViewport = bounds.width > 0f && bounds.height > 0f &&
            bounds.overlaps(Rect(0f, 0f, view.width.toFloat(), view.height.toFloat()))
    }) {
        val unit = size.minDimension / NavisBrandGeometry.VIEWPORT
        val frame = navisBrandFrame(motion, elapsed, durationScale > 0f, foreground && inViewport)
        translate((size.width - size.minDimension) / 2f, (size.height - size.minDimension) / 2f) {
            scale(unit, unit, Offset.Zero) { drawBrand(geometry, frame) }
        }
    }
}

/**
 * VectorDrawable does not reliably retain an outer clip around a rotated nested
 * group at toolbar sizes. Apply the canonical contour once more at the Compose
 * boundary so Channel, Seams and their relief cannot escape the mark.
 */
private fun Modifier.navisBrandOuterClip(): Modifier = drawWithCache {
    val unit = size.minDimension / NavisBrandGeometry.VIEWPORT
    val offsetX = (size.width - size.minDimension) / 2f
    val offsetY = (size.height - size.minDimension) / 2f
    val radius = NavisBrandGeometry.CORNER_RADIUS * unit
    val contour = Path().apply {
        addRoundRect(
            RoundRect(
                offsetX + NavisBrandGeometry.CONTOUR_X * unit,
                offsetY + NavisBrandGeometry.CONTOUR_Y * unit,
                offsetX + (NavisBrandGeometry.CONTOUR_X + NavisBrandGeometry.CONTOUR_WIDTH) * unit,
                offsetY + (NavisBrandGeometry.CONTOUR_Y + NavisBrandGeometry.CONTOUR_HEIGHT) * unit,
                CornerRadius(radius),
            ),
        )
    }
    onDrawWithContent {
        val content = this
        clipPath(contour) { content.drawContent() }
    }
}

private class BrandSeam(data: String, val side: Int) {
    private val native = checkNotNull(PathParser.createPathFromPathData(data))
    val measure = PathMeasure(native, false)
    val partial = android.graphics.Path()
    val partialPath = partial.asComposePath()
    val position = FloatArray(2)
    val tangent = FloatArray(2)
}

private class BrandPaths {
    val contour = Path().apply {
        addRoundRect(RoundRect(NavisBrandGeometry.CONTOUR_X, NavisBrandGeometry.CONTOUR_Y,
            NavisBrandGeometry.CONTOUR_X + NavisBrandGeometry.CONTOUR_WIDTH,
            NavisBrandGeometry.CONTOUR_Y + NavisBrandGeometry.CONTOUR_HEIGHT,
            CornerRadius(NavisBrandGeometry.CORNER_RADIUS)))
    }
    val channel = checkNotNull(PathParser.createPathFromPathData(NavisBrandGeometry.CHANNEL)).asComposePath()
    val seams = listOf(BrandSeam(NavisBrandGeometry.SEAM_A, 0), BrandSeam(NavisBrandGeometry.SEAM_B, 1))
    val base = linearBrandBrush(NavisBrandGeometry.CONTOUR_COLORS, NavisBrandGeometry.CONTOUR_STOPS, NavisBrandGeometry.CONTOUR_POSITION)
    val channelBrush = linearBrandBrush(NavisBrandGeometry.CHANNEL_COLORS, NavisBrandGeometry.CHANNEL_STOPS, NavisBrandGeometry.CHANNEL_POSITION)
    val diffuse = radialBrandBrush(NavisBrandGeometry.FROST_DIFFUSE_COLORS, NavisBrandGeometry.FROST_DIFFUSE_STOPS, NavisBrandGeometry.FROST_DIFFUSE_POSITION)
    val fog = radialBrandBrush(NavisBrandGeometry.FROST_FOG_COLORS, NavisBrandGeometry.FROST_FOG_STOPS, NavisBrandGeometry.FROST_FOG_POSITION)
    val rim = linearBrandBrush(NavisBrandGeometry.RIM_COLORS, NavisBrandGeometry.RIM_STOPS, NavisBrandGeometry.RIM_POSITION)
}

private fun linearBrandBrush(colors: LongArray, stops: FloatArray, position: FloatArray) = Brush.linearGradient(
    *Array(colors.size) { stops[it] to Color(colors[it]) },
    start = Offset(position[0], position[1]), end = Offset(position[2], position[3]),
)
private fun radialBrandBrush(colors: LongArray, stops: FloatArray, position: FloatArray) = Brush.radialGradient(
    *Array(colors.size) { stops[it] to Color(colors[it]) },
    center = Offset(position[0], position[1]), radius = position[2],
)
private fun brandEasing(points: FloatArray) = CubicBezierEasing(points[0], points[1], points[2], points[3])
private val baseEasing = brandEasing(NavisBrandGeometry.BASE_EASING)
private val seamEasing = brandEasing(NavisBrandGeometry.SEAM_EASING)
private val pulseEasing = brandEasing(NavisBrandGeometry.HEAD_EASING)
private val overlayEasing = CubicBezierEasing(0f, 0f, .58f, 1f)
private val brandCenter = Offset(NavisBrandGeometry.CENTER_X, NavisBrandGeometry.CENTER_Y)

private fun DrawScope.drawBrandRelief(path: Path, stroke: Stroke, relief: List<NavisBrandGeometry.Relief>, alpha: Float = 1f) {
    for (shadow in relief) translate(shadow.x, shadow.y) {
        drawPath(path, Color(shadow.color), alpha = shadow.alpha * alpha, style = stroke)
    }
}

private fun DrawScope.drawBrand(paths: BrandPaths, frame: NavisBrandFrame) {
    val baseAlpha = baseEasing.transform((frame.base / NavisBrandGeometry.BASE_OPAQUE_AT).coerceIn(0f, 1f))
    val channelStroke = Stroke(NavisBrandGeometry.CHANNEL_WIDTH, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val seamStroke = Stroke(NavisBrandGeometry.SEAM_WIDTH, cap = StrokeCap.Round, join = StrokeJoin.Round)
    clipPath(paths.contour) {
        // Match the source's outer clip: relief never creates a second outline or shadow box.
        for (shadow in NavisBrandGeometry.contourRelief) translate(shadow.x, shadow.y) {
            drawPath(paths.contour, Color(shadow.color), alpha = shadow.alpha * baseAlpha)
        }
        drawPath(paths.contour, paths.base, alpha = baseAlpha)
        drawPath(paths.contour, paths.diffuse, alpha = baseAlpha)
        drawPath(paths.contour, paths.fog, alpha = baseAlpha)
        drawPath(paths.contour, paths.rim, alpha = NavisBrandGeometry.RIM_OPACITY * baseAlpha,
            style = Stroke(NavisBrandGeometry.RIM_WIDTH))
        rotate(NavisBrandGeometry.ROTATION, brandCenter) {
            drawBrandRelief(paths.channel, channelStroke, NavisBrandGeometry.channelRelief, baseAlpha)
            drawPath(paths.channel, paths.channelBrush, alpha = NavisBrandGeometry.CHANNEL_OPACITY * baseAlpha, style = channelStroke)
            val revealed = seamEasing.transform(frame.seam)
            for (seam in paths.seams) {
                val distance = (NavisBrandGeometry.SEAM_DASH * revealed).coerceAtMost(seam.measure.length)
                seam.partial.reset()
                if (distance > 0f) {
                    seam.measure.getSegment(0f, distance, seam.partial, true)
                    drawBrandRelief(seam.partialPath, seamStroke, NavisBrandGeometry.seamRelief)
                    drawPath(seam.partialPath, Color.White, style = seamStroke)
                }
                frame.cometMilliseconds?.let { elapsed ->
                    val appearance = overlayEasing.transform(
                        (elapsed / NavisBrandGeometry.OVERLAY_MILLISECONDS).coerceIn(0f, 1f),
                    )
                    val overlayScale = NavisBrandGeometry.OVERLAY_INITIAL_SCALE +
                        (1f - NavisBrandGeometry.OVERLAY_INITIAL_SCALE) * appearance
                    scale(overlayScale, pivot = brandCenter) { drawComet(seam, elapsed) }
                }
            }
        }
    }
}

private fun DrawScope.drawComet(seam: BrandSeam, elapsed: Float) {
    val overlayAlpha = overlayEasing.transform((elapsed / NavisBrandGeometry.OVERLAY_MILLISECONDS).coerceIn(0f, 1f))
    val pulse = elapsed % NavisBrandGeometry.HEAD_MILLISECONDS / NavisBrandGeometry.HEAD_MILLISECONDS
    val middle = NavisBrandGeometry.HEAD_KEY_TIMES[1]
    val rising = pulse <= middle
    val localPulse = if (rising) pulse / middle else (pulse - middle) / (1f - middle)
    val start = NavisBrandGeometry.HEAD_SCALE[if (rising) 0 else 1]
    val end = NavisBrandGeometry.HEAD_SCALE[if (rising) 1 else 2]
    val radius = NavisBrandGeometry.HEAD_RADIUS * (start + (end - start) * pulseEasing.transform(localPulse))
    seam.measure.getPosTan(seam.measure.length, seam.position, seam.tangent)
    val head = Offset(seam.position[0], seam.position[1])
    drawCircle(Color.White, radius + 2.4f, head, alpha = .16f * overlayAlpha)
    drawCircle(Color.White, radius, head, alpha = overlayAlpha)
    val phase = elapsed % NavisBrandGeometry.PARTICLE_MILLISECONDS / NavisBrandGeometry.PARTICLE_MILLISECONDS
    for (particle in NavisBrandGeometry.particles) {
        val alpha = navisBrandInterpolate(particle.opacities, particle.opacityTimes, phase) * particle.alpha * overlayAlpha
        if (alpha <= 0f) continue
        val distance = seam.measure.length * navisBrandInterpolate(particle.positions, particle.times, phase)
        seam.measure.getPosTan(distance, seam.position, seam.tangent)
        val shift = particle.offsets[seam.side]
        val center = Offset(seam.position[0] - seam.tangent[1] * shift,
            seam.position[1] + seam.tangent[0] * shift)
        drawCircle(Color.White, particle.radius + 2.4f, center, alpha = alpha * .16f)
        drawCircle(Color.White, particle.radius, center, alpha = alpha)
    }
}
