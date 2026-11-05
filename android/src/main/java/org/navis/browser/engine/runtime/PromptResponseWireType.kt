/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.engine.runtime

import org.navis.browser.api.PromptResponse

/** The native protocol uses stable values, independent of R8 class names. */
internal fun PromptResponse.engineWireType(): String = when (this) {
    PromptResponse.Dismiss -> "Dismiss"
    PromptResponse.Accept -> "Accept"
    PromptResponse.Reject -> "Reject"
    is PromptResponse.TextValue -> "TextValue"
    is PromptResponse.Credentials -> "Credentials"
    is PromptResponse.SelectOption -> "SelectOption"
    is PromptResponse.ChoiceValue -> "ChoiceValue"
    is PromptResponse.DateTimeValue -> "DateTimeValue"
    is PromptResponse.ColorValue -> "ColorValue"
}
