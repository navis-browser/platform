/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.persistence

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineSessionStatePolicyTest {
    @Test
    fun acceptsNonEmptyStateWithinTheUtf8Budget() {
        assertTrue(EngineSessionStatePolicy.accepts("{\"index\":1}"))
        assertFalse(EngineSessionStatePolicy.accepts(""))
    }

    @Test
    fun rejectsOversizedAsciiAndMultibyteState() {
        assertFalse(
            EngineSessionStatePolicy.accepts(
                "a".repeat(EngineSessionStatePolicy.MAX_UTF8_BYTES + 1),
            ),
        )
        assertFalse(
            EngineSessionStatePolicy.accepts(
                "界".repeat(EngineSessionStatePolicy.MAX_UTF8_BYTES / 2),
            ),
        )
    }
}
