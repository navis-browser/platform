/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.navis.browser.api.BrowserSessionState
import org.navis.browser.api.BrowserStateObserver
import org.navis.browser.engine.AndroidWindowRuntime
import org.navis.browser.engine.SiteInformation

internal data class ToolbarSitePresentation(val organization: String?, val information: SiteInformation?)

@Composable
internal fun rememberToolbarSiteOrganization(
    runtime: AndroidWindowRuntime,
    active: BrowserSessionState?,
): String? = rememberToolbarSitePresentation(runtime, active).organization

@Composable
internal fun rememberToolbarSitePresentation(
    runtime: AndroidWindowRuntime,
    active: BrowserSessionState?,
): ToolbarSitePresentation {
    var organization by remember(runtime) { mutableStateOf<String?>(null) }
    var information by remember(runtime) { mutableStateOf<SiteInformation?>(null) }
    val controller = remember(runtime) {
        ToolbarSiteOrganizationController(
            currentState = { runtime.state },
            load = { id, callback -> runtime.product.siteIdentity(id, callback) },
            onChanged = { organization = it },
            queryAllKinds = true,
            onIdentityChanged = { information = it },
        )
    }
    DisposableEffect(runtime, controller) {
        val observer = BrowserStateObserver(controller::observe)
        runtime.addObserver(observer)
        onDispose {
            runtime.removeObserver(observer)
            controller.close()
        }
    }
    // Read Compose state to observe asynchronous completion, then enforce the current page.
    return ToolbarSitePresentation(
        organization?.takeIf { controller.organizationFor(active) == it },
        information?.takeIf { controller.informationFor(active) == it },
    )
}
