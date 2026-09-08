/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.ui

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.github.skydoves.colorpicker.compose.HsvColorPicker
import com.github.skydoves.colorpicker.compose.rememberColorPickerController
import org.navis.browser.R
import kotlin.math.roundToInt

/** Visual draft editor only. The host owns Save/Cancel and all persistence/prompt replies.
 * Presets are compact by default; expanded controls share one bounded scroll viewport.
 */
@Composable
internal fun NavisColorPicker(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    predefinedValues: List<String> = emptyList(),
    enabled: Boolean = true,
    onValidityChange: (Boolean) -> Unit = {},
    showPresets: Boolean = true,
) {
    val initial = remember { NativePromptValuePolicy.initialColor(value) }
    var hsv by remember { mutableStateOf(colorHsv(opaqueColor(initial))) }
    val selectedColor = Color.hsv(hsv[0], hsv[1], hsv[2])
    val selected = opaqueHex(selectedColor)
    val controller = rememberColorPickerController()
    var dragging by remember { mutableStateOf(false) }
    var customExpanded by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    var hexInput by remember(selected) { mutableStateOf(selected) }
    val currentHsv by rememberUpdatedState(hsv)
    // The library collects its callback once per controller; do not capture an old draft.
    val emit by rememberUpdatedState<(FloatArray) -> Unit>({ next ->
        if (enabled) {
            hsv = next
            val color = opaqueHex(Color.hsv(next[0], next[1], next[2]))
            // Visual selection also repairs an invalid code when the color is unchanged.
            hexInput = color
            onValidityChange(true)
            onValueChange(color)
        }
    })
    SideEffect {
        controller.enabled = enabled
        onValidityChange(NativePromptValuePolicy.normalizeColor(hexInput) != null)
    }
    LaunchedEffect(value) {
        val normalized = NativePromptValuePolicy.normalizeColor(value)
        if (normalized != null && normalized != selected) hsv = colorHsv(opaqueColor(normalized))
    }
    LaunchedEffect(hsv[0], hsv[1]) {
        // The wheel represents hue/saturation, while M3 Slider owns brightness.
        controller.selectByColor(Color.hsv(hsv[0], hsv[1], 1f), fromUser = false)
    }
    val wheelLabel = stringResource(R.string.color_picker_hue_saturation)
    val wheelState = stringResource(R.string.color_picker_hsv_state,
        hsv[0].roundToInt(), (hsv[1] * 100).roundToInt())
    val hueLess = stringResource(R.string.color_picker_hue_less)
    val hueMore = stringResource(R.string.color_picker_hue_more)
    val saturationLess = stringResource(R.string.color_picker_saturation_less)
    val saturationMore = stringResource(R.string.color_picker_saturation_more)
    fun adjust(hueDelta: Float = 0f, saturationDelta: Float = 0f): Boolean {
        if (!enabled) return false
        emit(floatArrayOf((hsv[0] + hueDelta + 360f) % 360f,
            (hsv[1] + saturationDelta).coerceIn(0f, 1f), hsv[2]))
        return true
    }
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState(), enabled = !dragging),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val previewLabel = stringResource(R.string.color_picker_preview)
        Surface(
            color = selectedColor,
            contentColor = if (selectedColor.luminance() > .5f) Color.Black else Color.White,
            shape = MaterialTheme.shapes.medium,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth().height(56.dp).semantics {
                contentDescription = previewLabel
                stateDescription = selected
            },
        ) {
            Box(contentAlignment = Alignment.Center) { Text(previewLabel) }
        }
        if (showPresets) {
            Text(stringResource(R.string.color_picker_presets), style = MaterialTheme.typography.labelLarge)
            NavisColorPresets(selected, { emit(colorHsv(opaqueColor(it))) },
                predefinedValues = predefinedValues, enabled = enabled)
        }
        if (showPresets) TextButton(onClick = { customExpanded = !customExpanded; dragging = false }, enabled = enabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(stringResource(if (customExpanded) R.string.color_picker_hide_custom else R.string.color_picker_custom),
                modifier = Modifier.weight(1f))
            Icon(if (customExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null)
        }
        if (!showPresets || customExpanded) {
            HsvColorPicker(
                // Leave side gutters to scroll past the wheel, even in a short landscape dialog.
                modifier = Modifier.size(if (isLandscape) 160.dp else 208.dp).align(Alignment.CenterHorizontally)
                    .semantics {
                        contentDescription = wheelLabel
                        stateDescription = wheelState
                        if (!enabled) disabled()
                        customActions = listOf(
                            CustomAccessibilityAction(hueLess) { adjust(hueDelta = -15f) },
                            CustomAccessibilityAction(hueMore) { adjust(hueDelta = 15f) },
                            CustomAccessibilityAction(saturationLess) { adjust(saturationDelta = -.1f) },
                            CustomAccessibilityAction(saturationMore) { adjust(saturationDelta = .1f) },
                        )
                    }.onKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                            Key.DirectionLeft -> adjust(hueDelta = -15f)
                            Key.DirectionRight -> adjust(hueDelta = 15f)
                            Key.DirectionDown -> adjust(saturationDelta = -.1f)
                            Key.DirectionUp -> adjust(saturationDelta = .1f)
                            else -> false
                        }
                    }.focusable(enabled),
                controller = controller,
                initialColor = Color.hsv(hsv[0], hsv[1], 1f),
                onColorChanged = { envelope ->
                    if (envelope.fromUser) {
                        val wheelHsv = colorHsv(envelope.color)
                        emit(floatArrayOf(wheelHsv[0], wheelHsv[1], currentHsv[2]))
                    }
                },
                onStart = { dragging = true },
                onFinish = { dragging = false },
            )
            val brightnessLabel = stringResource(R.string.color_picker_brightness)
            Text(stringResource(R.string.color_picker_brightness_value, (hsv[2] * 100).roundToInt()),
                style = MaterialTheme.typography.labelLarge)
            Slider(
                value = hsv[2], enabled = enabled,
                onValueChange = { emit(floatArrayOf(hsv[0], hsv[1], it)) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .semantics { contentDescription = brightnessLabel },
            )
            TextButton(onClick = { advanced = !advanced }, enabled = enabled,
                modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(if (advanced) R.string.color_picker_hide_hex else R.string.color_picker_show_hex))
            }
            if (advanced) OutlinedTextField(
                value = hexInput,
                onValueChange = { raw ->
                    // Keep invalid pasted codes visible instead of silently saving the old color.
                    hexInput = raw.take(64)
                    val normalized = NativePromptValuePolicy.normalizeColor(hexInput)
                    onValidityChange(normalized != null)
                    normalized?.let { emit(colorHsv(opaqueColor(it))) }
                },
                enabled = enabled, singleLine = true,
                label = { Text(stringResource(R.string.color_picker_hex)) },
                supportingText = { Text(stringResource(R.string.color_picker_hex_help)) },
                isError = NativePromptValuePolicy.normalizeColor(hexInput) == null,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun NavisColorPresets(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    predefinedValues: List<String> = emptyList(),
    enabled: Boolean = true,
) {
    val initial = remember { NativePromptValuePolicy.initialColor(value) }
    val palette = remember(predefinedValues, initial) {
        (predefinedValues + initial + NAVIS_COLOR_PRESETS)
            .mapNotNull(NativePromptValuePolicy::normalizeColor).distinct()
    }
    val selected = NativePromptValuePolicy.initialColor(value)
    FlowRow(
        modifier = modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        palette.forEach { preset ->
            val color = opaqueColor(preset)
            val label = stringResource(R.string.color_picker_preset, preset)
            Box(
                modifier = Modifier.size(48.dp).clip(CircleShape)
                    .selectable(selected == preset, enabled = enabled, role = Role.RadioButton) {
                        onValueChange(preset)
                    }.semantics { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    color = color, shape = CircleShape,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier.size(36.dp),
                ) {
                    if (selected == preset) Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Check, contentDescription = null,
                            tint = if (color.luminance() > .5f) Color.Black else Color.White,
                            modifier = Modifier.size(24.dp))
                    }
                }
            }
        }
    }
}

private fun opaqueColor(value: String): Color = Color(value.drop(1).toLong(16) or 0xff000000L)

private fun opaqueHex(color: Color): String =
    "#" + (color.toArgb() and 0xffffff).toString(16).padStart(6, '0')

private fun colorHsv(color: Color): FloatArray = FloatArray(3).also {
    android.graphics.Color.colorToHSV(color.toArgb(), it)
}

private val NAVIS_COLOR_PRESETS = listOf(
    "#0b57d0", "#f44336", "#ff9800", "#ffeb3b", "#4caf50", "#00bcd4",
    "#2196f3", "#673ab7", "#e91e63", "#ffffff", "#9e9e9e", "#000000",
)
