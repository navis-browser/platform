/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.extensions

/** Closed diagnostic vocabulary: never expose engine messages, URLs or target tokens. */
internal enum class ExtensionPopupFailureStage(val code: String) {
    NATIVE_OPEN("native-open-failed"),
    BIND("popup-bind-failed"),
    BIND_INVALID_TARGET("popup-invalid-target"),
    BIND_STALE("popup-transaction-stale"),
    BIND_OWNER_INVALID("popup-owner-invalid"),
    BIND_OWNER_REUSED("popup-owner-reused"),
    BIND_OWNER_NAME("popup-owner-name"),
    BIND_SOURCE_INACCESSIBLE("popup-source-inaccessible"),
    BIND_SOURCE_INACTIVE("popup-source-inactive"),
    BIND_PRIVATE_MISMATCH("popup-private-mismatch"),
    BIND_TARGET_REUSED("popup-target-reused"),
    BIND_VIEWTYPE("popup-viewtype"),
    BIND_VIEWTYPE_FAILED("popup-viewtype-failed"),
    BIND_INSERTION("popup-insertion-failed"),
    BIND_LOAD("popup-load-failed"),
    DOCUMENT("popup-document-failed"),
    TIMEOUT("load-timeout"),
    SESSION("session-error"),
    CONTENT_PROCESS("content-process-lost"),
    VIEW_CREATION("popup-view-create-failed"),
    ;

    companion object {
        fun fromEngineCode(code: String?): ExtensionPopupFailureStage? =
            entries.firstOrNull { it != VIEW_CREATION && it.code == code }
    }
}
