/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.ui

/** Brand animation is presentation only: it must never hold up Core readiness. */
internal enum class NavisBrandMotion { STATIC, COUNTERFLOW, LOADING }

internal data class NavisBrandFrame(
    val base: Float = 1f,
    val seam: Float = 1f,
    val cometMilliseconds: Float? = null,
)

/** Never consume an entrance just because its first layout/resume has not arrived. */
internal fun navisBrandInterruptedElapsed(started: Boolean, elapsed: Float): Float =
    if (started) NavisBrandGeometry.INTRO_MILLISECONDS else elapsed

internal fun navisBrandFrame(
    motion: NavisBrandMotion,
    milliseconds: Float,
    animationsEnabled: Boolean = true,
    visible: Boolean = true,
): NavisBrandFrame {
    if (!animationsEnabled || !visible || motion == NavisBrandMotion.STATIC) return NavisBrandFrame()
    val elapsed = milliseconds.coerceAtLeast(0f)
    return NavisBrandFrame(
        base = (elapsed / NavisBrandGeometry.BASE_MILLISECONDS).coerceIn(0f, 1f),
        seam = ((elapsed - NavisBrandGeometry.SEAM_DELAY_MILLISECONDS) /
            NavisBrandGeometry.SEAM_MILLISECONDS).coerceIn(0f, 1f),
        cometMilliseconds = if (motion == NavisBrandMotion.LOADING &&
            elapsed >= NavisBrandGeometry.INTRO_MILLISECONDS) {
            elapsed - NavisBrandGeometry.INTRO_MILLISECONDS
        } else null,
    )
}

/** The exported SVG keyPoints/keyTimes are also the native particle timing source. */
internal fun navisBrandInterpolate(values: FloatArray, times: FloatArray, fraction: Float): Float {
    require(values.size == times.size && values.size >= 2)
    val t = fraction.coerceIn(times.first(), times.last())
    val index = times.indexOfFirst { it >= t }.coerceAtLeast(1)
    val span = times[index] - times[index - 1]
    val progress = if (span > 0f) (t - times[index - 1]) / span else 1f
    return values[index - 1] + (values[index] - values[index - 1]) * progress
}
