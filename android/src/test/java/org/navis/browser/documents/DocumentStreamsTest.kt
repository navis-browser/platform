// SPDX-License-Identifier: MPL-2.0
package org.navis.browser.documents

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.navis.browser.engine.runtime.EnginePrintSettings

class DocumentStreamsTest {
    @Test fun copiesExactBytesAndChecksSizeBeforeWriting() {
        val bytes = ByteArray(70000) { it.toByte() }
        val output = ByteArrayOutputStream()
        assertEquals(bytes.size.toLong(), DocumentStreams.copy(ByteArrayInputStream(bytes), output, { false }))
        assertArrayEquals(bytes, output.toByteArray())
        val bounded = ByteArrayOutputStream()
        assertThrows(IOException::class.java) {
            DocumentStreams.copy(ByteArrayInputStream(bytes), bounded, { false }, 50000)
        }
        assertEquals(32768, bounded.size())
    }

    @Test fun rejectsEmptyAndCancelledOutput() {
        assertThrows(IOException::class.java) {
            DocumentStreams.copy(ByteArrayInputStream(byteArrayOf()), ByteArrayOutputStream(), { false })
        }
        val output = ByteArrayOutputStream()
        assertThrows(CancellationException::class.java) {
            DocumentStreams.copy(ByteArrayInputStream(byteArrayOf(1)), output, { true })
        }
        assertEquals(0, output.size())
    }

    @Test fun validatesDimensionsMarginsAndNonoverlappingRanges() {
        val valid = EnginePrintSettings(8270, 11690, 0, 0, 0, 0, true, listOf(0..1, 4..5))
        assertEquals(2, valid.ranges.size)
        listOf<() -> Unit>(
            { valid.copy(widthMils = 0) }, { valid.copy(leftMils = -1) },
            { valid.copy(leftMils = Int.MAX_VALUE, rightMils = Int.MAX_VALUE) },
            { valid.copy(ranges = listOf(-1..2)) }, { valid.copy(ranges = listOf(0..3, 3..4)) },
            { valid.copy(ranges = listOf(4..5, 0..1)) }, { valid.copy(ranges = listOf(0..1000000)) },
        ).forEach { invalid -> assertThrows(IllegalArgumentException::class.java) { invalid() } }
    }
}
