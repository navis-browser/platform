/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */
package org.navis.browser.engine.runtime

import java.io.InputStream
import java.util.concurrent.CompletionStage

/** Plain physical settings. Page ranges use inclusive zero-based document indices. */
internal data class EnginePrintSettings(
    val widthMils: Int,
    val heightMils: Int,
    val leftMils: Int,
    val topMils: Int,
    val rightMils: Int,
    val bottomMils: Int,
    val color: Boolean,
    val ranges: List<IntRange> = emptyList(),
) {
    init {
        require(widthMils in 256..200000 && heightMils in 256..200000)
        require(listOf(leftMils, topMils, rightMils, bottomMils).all { it >= 0 })
        require(leftMils.toLong() + rightMils < widthMils && topMils.toLong() + bottomMils < heightMils)
        require(ranges.size <= 1024)
        var previous = -1
        ranges.forEach { range ->
            require(!range.isEmpty() && range.first > previous && range.last < 1000000)
            previous = range.last
        }
    }
}

/** One owned document transport; no native document identity crosses this seam. */
internal interface EnginePrintDocument : AutoCloseable {
    val title: String
    fun render(settings: EnginePrintSettings): CompletionStage<InputStream>
    override fun close()
}
