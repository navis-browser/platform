/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import org.navis.browser.settings.AndroidSettingsSnapshot

private val LightColors = lightColorScheme(
    primary = Color(0xFF0B57D0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E3FD),
    onPrimaryContainer = Color(0xFF041E49),
    secondary = Color(0xFF0B57D0),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD3E3FD),
    onSecondaryContainer = Color(0xFF041E49),
    tertiary = Color(0xFF0B57D0),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD3E3FD),
    onTertiaryContainer = Color(0xFF041E49),
    background = Color(0xFFF8FAFD),
    onBackground = Color(0xFF1F1F1F),
    surface = Color(0xFFF8FAFD),
    onSurface = Color(0xFF1F1F1F),
    surfaceVariant = Color(0xFFDFE5EE),
    onSurfaceVariant = Color(0xFF444746),
    surfaceTint = Color(0xFF0B57D0),
    surfaceDim = Color(0xFFD3D8E1),
    surfaceBright = Color(0xFFF8FAFD),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF1F4F9),
    surfaceContainer = Color(0xFFE9EEF6),
    surfaceContainerHigh = Color(0xFFDFE5EE),
    surfaceContainerHighest = Color(0xFFD3DAE5),
    inverseSurface = Color(0xFF1E2025),
    inverseOnSurface = Color(0xFFE3E3E3),
    inversePrimary = Color(0xFFA8C7FA),
    outline = Color(0xFF747775),
    outlineVariant = Color(0xFFC4C7CE),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA8C7FA),
    onPrimary = Color(0xFF062E6F),
    primaryContainer = Color(0xFF0842A0),
    onPrimaryContainer = Color(0xFFD3E3FD),
    secondary = Color(0xFFA8C7FA),
    onSecondary = Color(0xFF062E6F),
    secondaryContainer = Color(0xFF0842A0),
    onSecondaryContainer = Color(0xFFD3E3FD),
    tertiary = Color(0xFFA8C7FA),
    onTertiary = Color(0xFF062E6F),
    tertiaryContainer = Color(0xFF0842A0),
    onTertiaryContainer = Color(0xFFD3E3FD),
    background = Color(0xFF111318),
    onBackground = Color(0xFFE3E3E3),
    surface = Color(0xFF111318),
    onSurface = Color(0xFFE3E3E3),
    surfaceVariant = Color(0xFF282A2F),
    onSurfaceVariant = Color(0xFFC4C7C5),
    surfaceTint = Color(0xFFA8C7FA),
    surfaceDim = Color(0xFF111318),
    surfaceBright = Color(0xFF37393F),
    surfaceContainerLowest = Color(0xFF0B0D12),
    surfaceContainerLow = Color(0xFF191C20),
    surfaceContainer = Color(0xFF1E2025),
    surfaceContainerHigh = Color(0xFF282A2F),
    surfaceContainerHighest = Color(0xFF33353A),
    inverseSurface = Color(0xFFE9EEF6),
    inverseOnSurface = Color(0xFF1F1F1F),
    inversePrimary = Color(0xFF0B57D0),
    outline = Color(0xFF8E918F),
    outlineVariant = Color(0xFF44474D),
)

@Composable
internal fun NavisTheme(settings: AndroidSettingsSnapshot? = null, content: @Composable () -> Unit) {
    val dark = when (settings?.theme) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val base = if (dark) DarkColors else LightColors
    val selected = settings?.accent?.takeIf { it != "#0b57d0" }
        ?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() }
    val colors = if (selected == null) base else {
        val accent = if (dark) lerp(selected, Color.White, .4f) else selected
        val foreground = if (accent.luminance() > .179f) Color.Black else Color.White
        val container = lerp(base.surface, selected, if (dark) .35f else .15f)
        base.copy(primary = accent, onPrimary = foreground, primaryContainer = container,
            onPrimaryContainer = base.onSurface, secondary = accent, onSecondary = foreground,
            secondaryContainer = container, onSecondaryContainer = base.onSurface,
            tertiary = accent, onTertiary = foreground, tertiaryContainer = container,
            onTertiaryContainer = base.onSurface, surfaceTint = accent)
    }
    NavisSystemBars(colors.surfaceContainer, colors.surface)
    MaterialTheme(
        colorScheme = colors,
    ) {
        CompositionLocalProvider(LocalTextSelectionColors provides TextSelectionColors(
            handleColor = colors.primary,
            backgroundColor = colors.primary.copy(alpha = .20f),
        ), content = content)
    }
}
