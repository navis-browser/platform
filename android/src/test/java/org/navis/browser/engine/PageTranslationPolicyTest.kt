// SPDX-License-Identifier: MPL-2.0
package org.navis.browser.engine

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.navis.browser.api.PageTranslationPhase

class PageTranslationPolicyTest {
    private fun state() = JSONObject().put("phase", "downloading").put("detectedLanguage", "es")
        .put("sourceLanguage", "es").put("targetLanguage", "zh-Hans").put("downloadBytes", 12_000_000L)
        .put("error", JSONObject.NULL)

    @Test fun plainNativeStateRetainsItsPhaseAndLanguages() {
        val decoded = PageTranslationPolicy.decode(state())
        assertEquals(PageTranslationPhase.DOWNLOADING, decoded.phase)
        assertEquals("zh-Hans", decoded.targetLanguage)
        assertEquals(12_000_000L, decoded.downloadBytes)
        assertNull(decoded.error)
    }

    @Test fun languageChoicesAreBoundedAndDoNotCarryNativeObjects() {
        val value = state().put("sourceLanguages", JSONArray().put("es").put("es").put("en"))
            .put("targetLanguages", JSONArray().put("en").put("zh-Hans"))
        assertEquals(listOf("es", "en"), PageTranslationPolicy.choices(value).sourceLanguages)
        value.put("sourceLanguages", JSONArray())
        assertThrows(IllegalArgumentException::class.java) { PageTranslationPolicy.choices(value) }
    }

    @Test fun invalidPhasesErrorsAndLanguagePayloadsAreRejected() {
        for ((key, value) in listOf("phase" to "native-object", "error" to "unbounded native failure",
            "sourceLanguage" to "<script>", "targetLanguage" to "x".repeat(81), "downloadBytes" to -1)) {
            assertThrows(RuntimeException::class.java) { PageTranslationPolicy.decode(state().put(key, value)) }
        }
    }

    @Test fun ordinaryFailedDownloadsUseAStableErrorCode() {
        val decoded = PageTranslationPolicy.decode(state().put("phase", "error").put("error", "engine-load-failure"))
        assertEquals(PageTranslationPhase.ERROR, decoded.phase)
        assertEquals("engine-load-failure", decoded.error)
    }
}
