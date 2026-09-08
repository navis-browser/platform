/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.extensions

import org.junit.Assert.assertEquals
import org.junit.Test

class ExtensionNoticeTest {
    @Test
    fun requiredBuiltInFailuresKeepTheirDedicatedProductNoticeAcrossWrappers() {
        for (code in listOf(
            "invalid-built-in-registry",
            "built-in-identity-conflict",
            "invalid-built-in",
        )) {
            val backend = IllegalStateException("$code:backend failure")
            val wrapper = java.util.concurrent.CompletionException(backend)

            assertEquals(ExtensionNotice.BUILT_IN_UNAVAILABLE, extensionNoticeFor(wrapper))
        }
    }

    @Test
    fun ordinaryBackendFailuresDoNotMasqueradeAsBuiltInFailures() {
        assertEquals(
            ExtensionNotice.OPERATION_FAILED,
            extensionNoticeFor(IllegalStateException("operation-failed:request rejected")),
        )
        assertEquals(
            ExtensionNotice.UNSUPPORTED_CAPABILITY,
            extensionNoticeFor(IllegalStateException("wrapper", UnsupportedOperationException())),
        )
    }

    @Test
    fun localPackageFailuresKeepSizeAndReadErrorsDistinct() {
        assertEquals(
            ExtensionNotice.FILE_TOO_LARGE,
            extensionNoticeFor(java.io.IOException("package-size:stream exceeded the limit")),
        )
        assertEquals(
            ExtensionNotice.FILE_READ_FAILED,
            extensionNoticeFor(
                java.util.concurrent.CompletionException(java.io.IOException("read failed")),
            ),
        )
    }
}
