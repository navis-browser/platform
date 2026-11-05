/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */
package org.navis.browser.engine

/** Unwrap async transport errors and bound text rendered in a failure surface. */
internal fun operationFailureDetail(error: Throwable): String {
    var cause = error
    repeat(8) {
        if (cause is java.util.concurrent.CompletionException || cause is java.util.concurrent.ExecutionException) {
            cause = cause.cause?.takeUnless { it === cause } ?: cause
        }
    }
    return (cause.message ?: cause.javaClass.simpleName).filter { !it.isISOControl() }.take(512)
}
