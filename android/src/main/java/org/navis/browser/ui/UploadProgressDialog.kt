/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup
import androidx.activity.ComponentDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.navis.browser.R
import org.navis.browser.settings.createAndroidSettingsHost

internal fun showUploadProgressDialog(context: Context, cancel: () -> Unit): ComponentDialog {
    val settings = createAndroidSettingsHost(context.applicationContext).use { it.snapshot }
    return ComponentDialog(context).apply {
        setContentView(ComposeView(context).apply {
            setContent {
                NavisTheme(settings) {
                    Surface(modifier = Modifier.padding(24.dp).fillMaxWidth(),
                        shape = MaterialTheme.shapes.extraLarge,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text(stringResource(R.string.upload_selection_preparing), style = MaterialTheme.typography.headlineSmall)
                            Text(stringResource(R.string.upload_selection_preparing_detail))
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            TextButton(onClick = cancel) { Text(stringResource(R.string.cancel)) }
                        }
                    }
                }
            }
        })
        setOnCancelListener { cancel() }
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        show()
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}
