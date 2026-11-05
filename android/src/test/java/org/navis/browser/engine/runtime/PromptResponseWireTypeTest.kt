/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.engine.runtime

import org.junit.Assert.assertEquals
import org.junit.Test
import org.navis.browser.api.PromptResponse

class PromptResponseWireTypeTest {
    @Test
    fun responseTypesRetainTheNativeProtocolValues() {
        val cases = listOf(
            PromptResponse.Dismiss to "Dismiss",
            PromptResponse.Accept to "Accept",
            PromptResponse.Reject to "Reject",
            PromptResponse.TextValue("input") to "TextValue",
            PromptResponse.Credentials("user", "password") to "Credentials",
            PromptResponse.SelectOption(2) to "SelectOption",
            PromptResponse.ChoiceValue(listOf("42")) to "ChoiceValue",
            PromptResponse.DateTimeValue("2026-01-01") to "DateTimeValue",
            PromptResponse.ColorValue("#123456") to "ColorValue",
        )
        cases.forEach { (response, expected) ->
            assertEquals(expected, response.engineWireType())
        }
    }
}
