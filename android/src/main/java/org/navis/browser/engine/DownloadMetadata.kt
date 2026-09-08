/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.engine

import java.util.Locale

/** Pure validation helpers for metadata received from untrusted responses. */
internal object DownloadMetadata {
    const val DEFAULT_MIME_TYPE = "application/octet-stream"
    const val MAX_FILE_NAME_BYTES = 180
    const val MAX_SOURCE_URI_BYTES = 2_048

    private val whitespace = Regex("\\s+")
    private val mimeType = Regex(
        "^[a-z0-9!#$&^_.+*-]{1,64}/[a-z0-9!#$&^_.+*-]{1,127}$",
    )

    fun sanitizeFileName(suggested: String?): String {
        val filtered = buildString {
            val source = suggested.orEmpty()
            var offset = 0
            while (offset < source.length) {
                val codePoint = source.codePointAt(offset)
                offset += Character.charCount(codePoint)
                when {
                    codePoint < 0x20 || codePoint == 0x7f -> append(' ')
                    Character.getType(codePoint) == Character.FORMAT.toInt() -> append(' ')
                    codePoint in FORBIDDEN_ASCII -> append('_')
                    else -> appendCodePoint(codePoint)
                }
            }
        }.replace(whitespace, " ").trim(' ', '.')

        val usable = filtered.takeIf { it.isNotBlank() && it != "." && it != ".." }
            ?: DEFAULT_FILE_NAME
        if (utf8Length(usable) <= MAX_FILE_NAME_BYTES) {
            return usable
        }

        val extension = usable.safeExtension()
        val base = usable.dropLast(extension.length).trimEnd(' ', '.')
        val baseBudget = MAX_FILE_NAME_BYTES - utf8Length(extension)
        val truncatedBase = truncateUtf8(base, baseBudget).trimEnd(' ', '.')
            .ifBlank { DEFAULT_FILE_NAME }
        return truncateUtf8(truncatedBase + extension, MAX_FILE_NAME_BYTES)
    }

    fun numberedFileName(fileName: String, index: Int): String {
        require(index > 0)
        val safeName = sanitizeFileName(fileName)
        val extension = safeName.safeExtension()
        val suffix = " ($index)"
        val base = safeName.dropLast(extension.length).trimEnd(' ', '.')
        val baseBudget = MAX_FILE_NAME_BYTES - utf8Length(suffix) - utf8Length(extension)
        val truncatedBase = truncateUtf8(base, baseBudget).trimEnd(' ', '.')
            .ifBlank { DEFAULT_FILE_NAME }
        return truncatedBase + suffix + extension
    }

    fun sanitizeMimeType(raw: String?): String {
        val candidate = raw
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase(Locale.ROOT)
            .orEmpty()
        return candidate.takeIf { it.length <= MAX_MIME_TYPE_CHARS && mimeType.matches(it) }
            ?: DEFAULT_MIME_TYPE
    }

    fun parseContentLength(raw: String?): Long? = raw
        ?.trim()
        ?.toLongOrNull()
        ?.takeIf { it > 0L }

    fun boundedSourceUri(uri: String): String {
        val candidate = uri.trim()
        if (candidate.startsWith("data:", ignoreCase = true)) {
            return "data:"
        }
        return truncateUtf8(candidate, MAX_SOURCE_URI_BYTES)
    }

    private fun String.safeExtension(): String {
        val dot = lastIndexOf('.')
        if (dot <= 0 || dot == lastIndex) {
            return ""
        }
        val candidate = substring(dot)
        return candidate.takeIf {
            it.length <= MAX_EXTENSION_CHARS &&
                it.drop(1).all { character -> character.isLetterOrDigit() }
        }.orEmpty()
    }

    private fun truncateUtf8(value: String, maximumBytes: Int): String {
        if (maximumBytes <= 0) {
            return ""
        }
        var usedBytes = 0
        var offset = 0
        while (offset < value.length) {
            val codePoint = value.codePointAt(offset)
            val codePointChars = Character.toChars(codePoint)
            val byteCount = String(codePointChars).toByteArray(Charsets.UTF_8).size
            if (usedBytes + byteCount > maximumBytes) {
                break
            }
            usedBytes += byteCount
            offset += codePointChars.size
        }
        return value.substring(0, offset)
    }

    private fun utf8Length(value: String): Int = value.toByteArray(Charsets.UTF_8).size

    private const val DEFAULT_FILE_NAME = "download"
    private const val MAX_EXTENSION_CHARS = 17
    private const val MAX_MIME_TYPE_CHARS = 192
    private val FORBIDDEN_ASCII = setOf(
        '/'.code,
        '\\'.code,
        ':'.code,
        '*'.code,
        '?'.code,
        '"'.code,
        '<'.code,
        '>'.code,
        '|'.code,
    )
}
