// SPDX-License-Identifier: MPL-2.0

package org.navis.browser.uploads

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class UploadSelectionPolicyTest {
    @Test fun preservesActualNamesAndUnicode() {
        listOf("文件夹", "a b.txt", " report.csv ", ".hidden").forEach {
            assertEquals(it, UploadSelectionPolicy.checkedName(it))
        }
    }

    @Test fun refusesPathTraversalInsteadOfChangingRelativePath() {
        listOf("", ".", "..", "a/b", "a\\b", "bad\u0000name", "x".repeat(256)).forEach {
            assertEquals(UploadFailure.INVALID_NAME, assertThrows(UploadException::class.java) {
                UploadSelectionPolicy.checkedName(it)
            }.reason)
        }
    }

    @Test fun exactBoundarySucceedsAndRetainsAllBytes() {
        val output = ByteArrayOutputStream()
        assertEquals(4L, UploadSelectionPolicy.copyBounded(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)), output,
            2, {}, { 100 }, 4, 6, 10))
        assertEquals(listOf<Byte>(1, 2, 3, 4), output.toByteArray().toList())
    }

    @Test fun unknownSizeStreamsCannotOverrunFileOrAggregateLimits() {
        assertFailure(UploadFailure.FILE_SIZE, fileLimit = 3)
        assertFailure(UploadFailure.TOTAL_SIZE, totalLimit = 3)
    }

    @Test fun lowStorageAndCancellationFailBeforeWriting() {
        assertFailure(UploadFailure.NO_SPACE, free = 5)
        val output = ByteArrayOutputStream()
        val error = assertThrows(UploadException::class.java) {
            UploadSelectionPolicy.copyBounded(ByteArrayInputStream(byteArrayOf(1)), output, 0,
                { throw UploadException(UploadFailure.CANCELLED) }, { 100 }, 10, 20, 1)
        }
        assertEquals(UploadFailure.CANCELLED, error.reason)
        assertEquals(0, output.size())
    }

    private fun assertFailure(reason: UploadFailure, fileLimit: Long = 10, totalLimit: Long = 20, free: Long = 100) {
        val output = ByteArrayOutputStream()
        val error = assertThrows(UploadException::class.java) {
            UploadSelectionPolicy.copyBounded(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)), output, 0,
                {}, { free }, fileLimit, totalLimit, 10)
        }
        assertEquals(reason, error.reason)
        assertEquals(0, output.size())
    }
}
