// SPDX-License-Identifier: MPL-2.0

package org.navis.browser.engine

import org.navis.browser.api.SessionId

/** Product-owned immutable projection of one direct-runtime context menu. */
internal data class AndroidContextMenuItem(
    val id: String,
    val parentId: String?,
    val extensionId: String,
    val extensionName: String,
    val title: String,
    val type: String,
    val checked: Boolean,
    val enabled: Boolean,
)

internal data class AndroidContextMenuPageAction(val id: String, val enabled: Boolean)

internal data class AndroidContextMenuRequest(
    val sessionId: SessionId,
    val token: String,
    val tabId: Long,
    val pageUrl: String,
    val frameUrl: String,
    val frameId: Int,
    val inFrame: Boolean,
    val onLink: Boolean,
    val onImage: Boolean,
    val onAudio: Boolean,
    val onVideo: Boolean,
    val isTextSelected: Boolean,
    val editable: Boolean,
    val linkUrl: String,
    val linkText: String,
    val srcUrl: String,
    val selectionText: String,
    val mediaType: String,
    val items: List<AndroidContextMenuItem>,
    val pageActions: List<AndroidContextMenuPageAction> = emptyList(),
) {
    init {
        require(token.matches(Regex("[0-9a-f]{32}")))
        require(tabId > 0 && pageUrl.isNotBlank())
        require(items.size <= 256)
        require(pageActions.size <= 32 && pageActions.all { it.id.matches(Regex("[a-z-]{1,32}")) })
        require(items.all { it.id.isNotBlank() && (it.title.isNotBlank() || it.type == "separator") })
    }
}
