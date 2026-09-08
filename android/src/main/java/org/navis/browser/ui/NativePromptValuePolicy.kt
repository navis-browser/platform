/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.ui

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoField
import java.time.temporal.IsoFields
import java.util.Locale
import org.navis.browser.api.DateTimePromptKind

internal data class NativeDateTimeSelection(
    val date: LocalDate,
    val time: LocalTime,
)

/** Pure parsing, bounding, and HTML step alignment for Android-native prompt widgets. */
internal object NativePromptValuePolicy {
    private val minuteTime = DateTimeFormatter.ofPattern("HH:mm")
    private val secondTime = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val minuteDateTime = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
    private val secondDateTime = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
    private val colorPattern = Regex("^#[0-9a-fA-F]{6}$")

    fun initialDateTime(
        kind: DateTimePromptKind,
        defaultValue: String?,
        minimumValue: String?,
        maximumValue: String?,
        stepValue: String?,
        now: LocalDateTime = LocalDateTime.now(),
    ): NativeDateTimeSelection {
        val fallback = NativeDateTimeSelection(now.toLocalDate(), now.toLocalTime().withSecond(0).withNano(0))
        val requested = parseSelection(kind, defaultValue) ?: fallback
        return parseSelection(
            kind,
            normalizeDateTime(
                kind,
                requested,
                defaultValue,
                minimumValue,
                maximumValue,
                stepValue,
            ),
        ) ?: requested
    }

    fun normalizeDateTime(
        kind: DateTimePromptKind,
        selection: NativeDateTimeSelection,
        defaultValue: String?,
        minimumValue: String?,
        maximumValue: String?,
        stepValue: String?,
    ): String {
        val candidate = selection.scalar(kind)
        val minimum = parseSelection(kind, minimumValue)?.scalar(kind)
        val maximum = parseSelection(kind, maximumValue)?.scalar(kind)
        val default = parseSelection(kind, defaultValue)?.scalar(kind)
        val step = parseStep(kind, stepValue)
        val bounded = if (minimum != null && maximum != null && minimum > maximum) {
            candidate
        } else {
            alignAndBound(
                candidate,
                minimum,
                maximum,
                step,
                minimum ?: default ?: stepBase(kind),
            )
        }
        return selectionFromScalar(kind, bounded).format(kind)
    }

    fun minimumDate(kind: DateTimePromptKind, raw: String?): LocalDate? =
        parseSelection(kind, raw)?.date?.takeIf { kind != DateTimePromptKind.TIME }

    fun maximumDate(kind: DateTimePromptKind, raw: String?): LocalDate? =
        parseSelection(kind, raw)?.date?.takeIf { kind != DateTimePromptKind.TIME }

    fun normalizeColor(raw: String?): String? = raw
        ?.trim()
        ?.takeIf(colorPattern::matches)
        ?.lowercase()

    fun initialColor(raw: String?): String = normalizeColor(raw) ?: "#000000"

    private fun parseSelection(
        kind: DateTimePromptKind,
        raw: String?,
    ): NativeDateTimeSelection? {
        val value = raw?.trim()?.takeIf(String::isNotEmpty) ?: return null
        return runCatching {
            when (kind) {
                DateTimePromptKind.DATE -> NativeDateTimeSelection(
                    LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE),
                    LocalTime.MIDNIGHT,
                )
                DateTimePromptKind.MONTH -> NativeDateTimeSelection(
                    YearMonth.parse(value).atDay(1),
                    LocalTime.MIDNIGHT,
                )
                DateTimePromptKind.WEEK -> NativeDateTimeSelection(
                    parseIsoWeek(value),
                    LocalTime.MIDNIGHT,
                )
                DateTimePromptKind.TIME -> NativeDateTimeSelection(
                    LocalDate.ofEpochDay(0),
                    LocalTime.parse(value, DateTimeFormatter.ISO_LOCAL_TIME),
                )
                DateTimePromptKind.DATETIME_LOCAL -> {
                    val parsed = LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                    NativeDateTimeSelection(parsed.toLocalDate(), parsed.toLocalTime())
                }
            }
        }.getOrNull()
    }

    private fun parseIsoWeek(value: String): LocalDate {
        val match = ISO_WEEK.matchEntire(value) ?: error("Invalid ISO week")
        val year = match.groupValues[1].toInt()
        val week = match.groupValues[2].toInt()
        val date = LocalDate.of(year, 1, 4)
            .with(IsoFields.WEEK_BASED_YEAR, year.toLong())
            .with(IsoFields.WEEK_OF_WEEK_BASED_YEAR, week.toLong())
            .with(ChronoField.DAY_OF_WEEK, DayOfWeek.MONDAY.value.toLong())
        check(date.get(IsoFields.WEEK_BASED_YEAR) == year)
        check(date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR) == week)
        return date
    }

    private fun NativeDateTimeSelection.scalar(kind: DateTimePromptKind): Long = when (kind) {
        DateTimePromptKind.DATE -> date.toEpochDay()
        DateTimePromptKind.MONTH -> date.year.toLong() * 12L + date.monthValue - 1L
        DateTimePromptKind.WEEK -> Math.floorDiv(date.toEpochDay() - ISO_WEEK_BASE.toEpochDay(), 7L)
        DateTimePromptKind.TIME -> time.toNanoOfDay()
        DateTimePromptKind.DATETIME_LOCAL -> LocalDateTime.of(date, time)
            .toInstant(ZoneOffset.UTC)
            .toEpochMilli()
    }

    private fun selectionFromScalar(
        kind: DateTimePromptKind,
        value: Long,
    ): NativeDateTimeSelection = when (kind) {
        DateTimePromptKind.DATE -> NativeDateTimeSelection(LocalDate.ofEpochDay(value), LocalTime.MIDNIGHT)
        DateTimePromptKind.MONTH -> NativeDateTimeSelection(
            YearMonth.of(Math.floorDiv(value, 12L).toInt(), Math.floorMod(value, 12L).toInt() + 1).atDay(1),
            LocalTime.MIDNIGHT,
        )
        DateTimePromptKind.WEEK -> NativeDateTimeSelection(
            ISO_WEEK_BASE.plusWeeks(value),
            LocalTime.MIDNIGHT,
        )
        DateTimePromptKind.TIME -> NativeDateTimeSelection(
            LocalDate.ofEpochDay(0),
            LocalTime.ofNanoOfDay(value.coerceIn(0L, NANOS_PER_DAY - 1L)),
        )
        DateTimePromptKind.DATETIME_LOCAL -> {
            val parsed = LocalDateTime.ofInstant(Instant.ofEpochMilli(value), ZoneOffset.UTC)
            NativeDateTimeSelection(parsed.toLocalDate(), parsed.toLocalTime())
        }
    }

    private fun NativeDateTimeSelection.format(kind: DateTimePromptKind): String = when (kind) {
        DateTimePromptKind.DATE -> date.format(DateTimeFormatter.ISO_LOCAL_DATE)
        DateTimePromptKind.MONTH -> YearMonth.from(date).toString()
        DateTimePromptKind.WEEK -> String.format(
            Locale.ROOT,
            "%04d-W%02d",
            date.get(IsoFields.WEEK_BASED_YEAR),
            date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR),
        )
        DateTimePromptKind.TIME -> formatTime(time)
        DateTimePromptKind.DATETIME_LOCAL -> {
            val formatter = if (time.second == 0 && time.nano == 0) minuteDateTime else secondDateTime
            LocalDateTime.of(date, time).format(formatter) + formatFraction(time.nano)
        }
    }

    private fun formatTime(time: LocalTime): String {
        val formatter = if (time.second == 0 && time.nano == 0) minuteTime else secondTime
        return time.format(formatter) + formatFraction(time.nano)
    }

    private fun formatFraction(nanos: Int): String = if (nanos == 0) {
        ""
    } else {
        ".${nanos.toString().padStart(9, '0').trimEnd('0')}"
    }

    private fun parseStep(kind: DateTimePromptKind, raw: String?): Long? {
        if (raw.equals("any", ignoreCase = true)) {
            return null
        }
        val units = raw?.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
            ?: BigDecimal.valueOf(defaultStep(kind))
        return runCatching {
            units.multiply(BigDecimal.valueOf(stepScale(kind)))
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact()
                .coerceAtLeast(1L)
        }.getOrElse { defaultStep(kind) * stepScale(kind) }
    }

    private fun alignAndBound(
        candidate: Long,
        minimum: Long?,
        maximum: Long?,
        step: Long?,
        base: Long,
    ): Long {
        var result = candidate.coerceToBounds(minimum, maximum)
        if (step == null) {
            return result
        }
        result = nearestAligned(result, base, step)
        if (minimum != null && result < minimum) {
            result = alignedAtOrAbove(minimum, base, step)
        }
        if (maximum != null && result > maximum) {
            result = alignedAtOrBelow(maximum, base, step)
        }
        return result.coerceToBounds(minimum, maximum)
    }

    private fun Long.coerceToBounds(minimum: Long?, maximum: Long?): Long {
        var result = this
        minimum?.let { result = maxOf(result, it) }
        maximum?.let { result = minOf(result, it) }
        return result
    }

    private fun nearestAligned(value: Long, base: Long, step: Long): Long {
        val offset = value - base
        val lower = base + Math.floorDiv(offset, step) * step
        val upper = lower + step
        return if (value - lower <= upper - value) lower else upper
    }

    private fun alignedAtOrAbove(value: Long, base: Long, step: Long): Long {
        val lower = base + Math.floorDiv(value - base, step) * step
        return if (lower < value) lower + step else lower
    }

    private fun alignedAtOrBelow(value: Long, base: Long, step: Long): Long =
        base + Math.floorDiv(value - base, step) * step

    private fun stepBase(kind: DateTimePromptKind): Long = when (kind) {
        DateTimePromptKind.DATE -> LocalDate.of(1970, 1, 1).toEpochDay()
        DateTimePromptKind.MONTH -> 1970L * 12L
        DateTimePromptKind.WEEK -> 0L
        DateTimePromptKind.TIME -> 0L
        DateTimePromptKind.DATETIME_LOCAL -> 0L
    }

    private fun defaultStep(kind: DateTimePromptKind): Long = when (kind) {
        DateTimePromptKind.DATE,
        DateTimePromptKind.MONTH,
        DateTimePromptKind.WEEK,
        -> 1L
        DateTimePromptKind.TIME,
        DateTimePromptKind.DATETIME_LOCAL,
        -> 60L
    }

    private fun stepScale(kind: DateTimePromptKind): Long = when (kind) {
        DateTimePromptKind.DATE,
        DateTimePromptKind.MONTH,
        DateTimePromptKind.WEEK,
        -> 1L
        DateTimePromptKind.TIME -> NANOS_PER_SECOND
        DateTimePromptKind.DATETIME_LOCAL -> MILLIS_PER_SECOND
    }

    private const val NANOS_PER_SECOND = 1_000_000_000L
    private const val NANOS_PER_DAY = 86_400L * NANOS_PER_SECOND
    private const val MILLIS_PER_SECOND = 1_000L
    private val ISO_WEEK_BASE = LocalDate.of(1969, 12, 29)
    private val ISO_WEEK = Regex("^([+-]?\\d{4,})-W(\\d{2})$")
}
