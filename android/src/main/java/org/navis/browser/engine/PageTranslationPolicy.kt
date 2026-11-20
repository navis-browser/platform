// SPDX-License-Identifier: MPL-2.0
package org.navis.browser.engine

import org.json.JSONObject
import org.navis.browser.api.PageTranslationChoices
import org.navis.browser.api.PageTranslationPhase
import org.navis.browser.api.PageTranslationState

/** Bounded plain values from the document-scoped native translation adapter. */
internal object PageTranslationPolicy {
    private val languageTag = Regex("[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*")
    private val errors = setOf("unsupported-device", "unsupported-language", "language-load-failure", "engine-load-failure", "restore-failure")
    private fun language(value: String) = value.also { require(it.length <= 80 && (it.isEmpty() || languageTag.matches(it))) }

    fun decode(value: JSONObject): PageTranslationState {
        val phase = when (value.getString("phase")) {
            "original" -> PageTranslationPhase.ORIGINAL
            "downloading" -> PageTranslationPhase.DOWNLOADING
            "translating" -> PageTranslationPhase.TRANSLATING
            "translated" -> PageTranslationPhase.TRANSLATED
            "error" -> PageTranslationPhase.ERROR
            else -> error("Unknown translation phase")
        }
        val error = if (value.isNull("error")) null else value.getString("error").also { require(it in errors) }
        val bytes = value.getLong("downloadBytes").also { require(it >= 0) }
        return PageTranslationState(phase, language(value.getString("detectedLanguage")),
            language(value.getString("sourceLanguage")), language(value.getString("targetLanguage")), bytes, error)
    }

    fun choices(value: JSONObject): PageTranslationChoices {
        fun languages(key: String): List<String> {
            val array = value.getJSONArray(key)
            require(array.length() in 1..512)
            return List(array.length()) { language(array.getString(it)).also { tag -> require(tag.isNotEmpty()) } }.distinct()
        }
        return PageTranslationChoices(languages("sourceLanguages"), languages("targetLanguages"), decode(value))
    }
}
