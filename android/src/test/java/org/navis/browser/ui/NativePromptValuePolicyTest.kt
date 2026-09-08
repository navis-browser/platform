/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.ui

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.navis.browser.api.DateTimePromptKind

class NativePromptValuePolicyTest {
    @Test
    fun everyHtmlDateTimeKindRoundTripsThroughNativeSelection() {
        assertNormalized(DateTimePromptKind.DATE, LocalDate.of(2026, 9, 4), LocalTime.NOON, "2026-09-04")
        assertNormalized(DateTimePromptKind.MONTH, LocalDate.of(2026, 9, 28), LocalTime.NOON, "2026-09")
        assertNormalized(DateTimePromptKind.WEEK, LocalDate.of(2026, 9, 4), LocalTime.NOON, "2026-W36")
        assertNormalized(DateTimePromptKind.TIME, LocalDate.of(2026, 9, 4), LocalTime.of(9, 42), "09:42")
        assertNormalized(
            DateTimePromptKind.DATETIME_LOCAL,
            LocalDate.of(2026, 9, 4),
            LocalTime.of(9, 42),
            "2026-09-04T09:42",
        )
    }

    @Test
    fun boundsAndStepAreAppliedBeforeReturningToGecko() {
        val belowMinimum = NativePromptValuePolicy.normalizeDateTime(
            DateTimePromptKind.DATE,
            NativeDateTimeSelection(LocalDate.of(2026, 8, 1), LocalTime.MIDNIGHT),
            defaultValue = null,
            minimumValue = "2026-09-04",
            maximumValue = "2026-09-30",
            stepValue = "2",
        )
        val steppedTime = NativePromptValuePolicy.normalizeDateTime(
            DateTimePromptKind.TIME,
            NativeDateTimeSelection(LocalDate.ofEpochDay(0), LocalTime.of(10, 7)),
            defaultValue = null,
            minimumValue = null,
            maximumValue = null,
            stepValue = "900",
        )

        assertEquals("2026-09-04", belowMinimum)
        assertEquals("10:00", steppedTime)
    }

    @Test
    fun initialValueUsesValidDefaultThenClampsIt() {
        val initial = NativePromptValuePolicy.initialDateTime(
            DateTimePromptKind.DATETIME_LOCAL,
            defaultValue = "2026-09-04T08:00",
            minimumValue = "2026-09-04T09:30",
            maximumValue = "2026-09-04T18:00",
            stepValue = "60",
            now = LocalDateTime.of(2000, 1, 1, 0, 0),
        )

        assertEquals(LocalDate.of(2026, 9, 4), initial.date)
        assertEquals(LocalTime.of(9, 30), initial.time)
    }

    @Test
    fun colorsAreCanonicalAndUntrustedValuesAreRejected() {
        assertEquals("#abcdef", NativePromptValuePolicy.normalizeColor(" #AbCdEf "))
        assertNull(NativePromptValuePolicy.normalizeColor("red"))
        assertEquals("#000000", NativePromptValuePolicy.initialColor("not-a-color"))
    }

    private fun assertNormalized(
        kind: DateTimePromptKind,
        date: LocalDate,
        time: LocalTime,
        expected: String,
    ) {
        assertEquals(
            expected,
            NativePromptValuePolicy.normalizeDateTime(
                kind,
                NativeDateTimeSelection(date, time),
                defaultValue = null,
                minimumValue = null,
                maximumValue = null,
                stepValue = "any",
            ),
        )
    }
}
