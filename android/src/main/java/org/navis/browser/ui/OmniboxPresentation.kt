/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.net.URI

internal fun omniboxVisibleAddress(url: String): String = runCatching {
    val uri = URI(url)
    val scheme = uri.scheme?.lowercase(java.util.Locale.ROOT)
    if (scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) return@runCatching url
    val host = uri.host
    val visibleHost = if (host.startsWith("www.", ignoreCase = true) && host.substring(4).contains('.')) {
        host.substring(4)
    } else host
    val authority = visibleHost + if (uri.port >= 0) ":${uri.port}" else ""
    if (scheme == "http") "http://$authority" else authority
}.getOrDefault(url)

@Composable
internal fun OmniboxBrowseText(
    title: String,
    url: String,
    hint: String,
    editLabel: String,
    modifier: Modifier = Modifier,
    onEdit: () -> Unit,
) {
    val visibleAddress = omniboxVisibleAddress(url)
    Column(
        modifier.clickable(role = Role.Button, onClick = onEdit)
            .semantics(mergeDescendants = true) {
                contentDescription = listOf(title, url, editLabel).filter(String::isNotBlank).distinct().joinToString(". ")
            }
            .heightIn(min = 48.dp).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        if (title.isNotBlank() && title != url && url.isNotBlank()) {
            Text(title, modifier = Modifier.fillMaxWidth(), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(visibleAddress.ifBlank { hint }, modifier = Modifier.fillMaxWidth(),
            maxLines = 1, overflow = TextOverflow.MiddleEllipsis,
            style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr),
            color = if (url.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
    }
}
