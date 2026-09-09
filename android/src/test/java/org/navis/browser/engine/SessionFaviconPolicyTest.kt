// SPDX-License-Identifier: MPL-2.0

package org.navis.browser.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionFaviconPolicyTest {
    @Test fun acceptsOnlyBoundedBase64PngOrClear() {
        assertTrue(SessionFaviconPolicy.accepts(""))
        assertTrue(SessionFaviconPolicy.accepts("data:image/png;base64,YWJj=="))
        assertFalse(SessionFaviconPolicy.accepts("https://example.com/icon.png"))
        assertFalse(SessionFaviconPolicy.accepts("data:image/svg+xml;base64,YWJj"))
        assertFalse(SessionFaviconPolicy.accepts("data:image/png;base64,"))
        assertFalse(SessionFaviconPolicy.accepts("data:image/png;base64,<html>"))
        assertFalse(SessionFaviconPolicy.accepts("data:image/png;base64," + "A".repeat(65536)))
    }
}
