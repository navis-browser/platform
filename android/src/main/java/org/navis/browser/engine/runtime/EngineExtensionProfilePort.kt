/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */
package org.navis.browser.engine.runtime

import java.util.concurrent.CompletionStage

/** Private bounded messages, not arbitrary product commands or a Session selector. */
internal data class EngineExtensionProfileRequest(
    val extensionId: String,
    val operation: String,
    val arguments: String,
    val privateMode: Boolean,
    val privateBrowsingAllowed: Boolean,
) {
    init {
        require(extensionId.isNotBlank() && extensionId.length <= 256)
        require(operation in OPERATIONS)
        require(arguments.length <= 128 * 1024)
        require(!privateMode || privateBrowsingAllowed) { "Private browsing access is not allowed" }
    }
    companion object {
        val OPERATIONS = setOf("bookmarks:list", "bookmarks:create", "bookmarks:update",
            "bookmarks:move", "bookmarks:remove", "bookmarks:removeTree", "topSites:list",
            "history:search", "history:visits", "history:add", "history:deleteUrl", "history:deleteRange", "history:deleteAll",
            "sessions:list", "sessions:forget", "sessions:restore")
    }
}

internal fun interface EngineExtensionProfileDelegate {
    fun request(request: EngineExtensionProfileRequest): CompletionStage<String>
}

internal interface EngineExtensionProfilePort : AutoCloseable {
    fun bind(delegate: EngineExtensionProfileDelegate)
    fun unbind(delegate: EngineExtensionProfileDelegate)
    fun publishBookmarkChange(json: String)
    fun publishHistoryChange(json: String)
    fun publishSessionsChange()
}
