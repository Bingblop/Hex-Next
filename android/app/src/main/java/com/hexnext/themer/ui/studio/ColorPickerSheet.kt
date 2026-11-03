package com.hexnext.themer.ui.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.hexnext.themer.ui.COLOR_PRESETS
import com.hexnext.themer.ui.ColorSwatch
import com.hexnext.themer.ui.HexField

/**
 * The Studio's colour picker, as a bottom sheet: a large swatch, a hex field, hue / saturation /
 * brightness sliders, the 12 presets and the theme's own Monet shades ([shades], keys
 * `system_accent1_500`).
 *
 * Every change calls [onPreview] (live colours, nothing saved); Done calls [onPick] once.
 * [autoLabel] ("Back to Auto", "Unpin") shows a button that calls [onAuto]. With [bothToggle] a
 * "Same for light and dark" switch is offered and its state is passed to every callback.
 * Dismissing without Done calls [onDismiss] only, so the caller drops the preview.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ColorPickerSheet(
    title: String,
    initial: Int,
    shades: Map<String, Int>,
    onPreview: (argb: Int, both: Boolean) -> Unit,
    onPick: (argb: Int, both: Boolean) -> Unit,
    onDismiss: () -> Unit,
    subtitle: String? = null,
    autoLabel: String? = null,
    onAuto: (both: Boolean) -> Unit = {},
    bothToggle: Boolean = false,
    initialBoth: Boolean = false,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var both by rememberSaveable { mutableStateOf(initialBoth) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // Saved, so a rotation keeps the colour being edited (the draft preview survives it too)
            // and Done commits the edit rather than the original colour.
            var current by rememberSaveable { mutableIntStateOf(initial or OPAQUE_ARGB) }
            ColorPickerContent(
                color = current,
                shades = shades,
                onColor = { argb ->
                    current = argb
                    onPreview(argb, both)
                },
            )
            if (bothToggle) {
                StudioSwitchRow(
                    title = "Same for light and dark",
                    summary = "Writes this colour to both modes",
                    checked = both,
                    onCheckedChange = { on ->
                        both = on
                        onPreview(current, on)
                    },
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (autoLabel != null) {
                    TextButton(onClick = { onAuto(both) }) { Text(autoLabel) }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Button(onClick = { onPick(current, both) }) { Text("Done") }
            }
        }
    }
}

/** Saves the picker's hue, saturation and brightness as a float array. */
private val HsvSaver: Saver<List<Float>, FloatArray> = Saver(
    save = { it.toFloatArray() },
    restore = { it.toList() },
)

/**
 * The body of a colour picker without its sheet: swatch with hex, the hex field, HSV sliders with
 * gradient tracks, presets and theme shades. Reused by the resource pin editor.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ColorPickerContent(color: Int, shades: Map<String, Int>, onColor: (Int) -> Unit) {
    // HSV is kept apart from the colour so the hue does not jump while saturation or brightness is 0.
    // Saved with the colour, so the sliders come back where they were after a rotation.
    var hsv by rememberSaveable(stateSaver = HsvSaver) { mutableStateOf(hsvOf(color)) }
    val setColor: (Int) -> Unit = { argb ->
        hsv = hsvOf(argb)
        onColor(argb or OPAQUE_ARGB)
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(
                Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(color))
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
                    .semantics { contentDescription = "Chosen colour ${hexOf(color)}" },
            )
            HexField(
                seed = color,
                onSeed = setColor,
                modifier = Modifier.weight(1f),
            )
        }
        val hue = hsv[0]
        val sat = hsv[1]
        val value = hsv[2]
        GradientSlider(
            label = "Hue",
            value = hue,
            valueRange = 0f..360f,
            display = "${hue.toInt()}°",
            colors = (0..6).map { Color(colorOfHsv(listOf(it * 60f % 360f, 1f, 1f))) },
            onChange = { h ->
                hsv = listOf(h, sat, value)
                onColor(colorOfHsv(hsv))
            },
        )
        GradientSlider(
            label = "Saturation",
            value = sat,
            valueRange = 0f..1f,
            display = "${(sat * 100).toInt()}%",
            colors = listOf(Color(colorOfHsv(listOf(hue, 0f, value))), Color(colorOfHsv(listOf(hue, 1f, value)))),
            onChange = { s ->
                hsv = listOf(hue, s, value)
                onColor(colorOfHsv(hsv))
            },
        )
        GradientSlider(
            label = "Brightness",
            value = value,
            valueRange = 0f..1f,
            display = "${(value * 100).toInt()}%",
            colors = listOf(Color.Black, Color(colorOfHsv(listOf(hue, sat, 1f)))),
            onChange = { v ->
                hsv = listOf(hue, sat, v)
                onColor(colorOfHsv(hsv))
            },
        )
        SectionLabel("Presets")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            COLOR_PRESETS.forEach { preset ->
                ColorSwatch(color = preset, selected = preset == color, onClick = { setColor(preset) })
            }
        }
        if (shades.isNotEmpty()) {
            SectionLabel("Theme shades")
            ShadeRibbon(
                shades = shades,
                onShade = { key -> (shades["system_$key"] ?: shades[key])?.let(setColor) },
                cellHeight = 24.dp,
                showScale = false,
            )
        }
    }
}

/** A slider drawn over a gradient strip of [colors] (the hue wheel, a saturation ramp …). */
@Composable
private fun GradientSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    display: String,
    colors: List<Color>,
    onChange: (Float) -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text(display, style = monoStyle(MaterialTheme.typography.labelMedium), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
                    .height(12.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Brush.horizontalGradient(colors))
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), RoundedCornerShape(6.dp)),
            )
            Slider(
                value = value,
                onValueChange = onChange,
                valueRange = valueRange,
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.onSurface,
                    activeTrackColor = Color.Transparent,
                    inactiveTrackColor = Color.Transparent,
                    activeTickColor = Color.Transparent,
                    inactiveTickColor = Color.Transparent,
                ),
                modifier = Modifier.semantics {
                    contentDescription = label
                    stateDescription = display
                },
            )
        }
    }
}
