package com.hexnext.themer.ui.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hexnext.engine.compile.ColorGroup
import com.hexnext.engine.compile.ThemeSpec
import com.hexnext.engine.compile.groupColor
import com.hexnext.engine.palette.PaletteStyle
import com.hexnext.engine.palette.PitchBlack
import com.hexnext.engine.palette.ThemeAdjustments
import com.hexnext.engine.palette.ThemeResolver
import com.hexnext.themer.ui.MainViewModel
import com.hexnext.themer.ui.withAdjustments
import com.hexnext.themer.ui.withShadeOverride
import kotlin.math.abs
import kotlin.math.roundToInt

/** Friendly names and one-line summaries of the scheme variants. */
internal fun styleLabel(style: PaletteStyle): String = when (style) {
    PaletteStyle.TONAL_SPOT -> "Tonal spot"
    PaletteStyle.VIBRANT -> "Vibrant"
    PaletteStyle.EXPRESSIVE -> "Expressive"
    PaletteStyle.SPRITZ -> "Spritz"
    PaletteStyle.RAINBOW -> "Rainbow"
    PaletteStyle.FRUIT_SALAD -> "Fruit salad"
    PaletteStyle.MONOCHROME -> "Monochrome"
    PaletteStyle.FIDELITY -> "Fidelity"
    PaletteStyle.CONTENT -> "Content"
    PaletteStyle.CMF -> "Colour material finish"
}

private fun styleSummary(style: PaletteStyle): String = when (style) {
    PaletteStyle.TONAL_SPOT -> "One UI default: calm accent, soft secondary"
    PaletteStyle.VIBRANT -> "Punchy accents, colourful surfaces"
    PaletteStyle.EXPRESSIVE -> "Playful, hue-shifted accents"
    PaletteStyle.SPRITZ -> "Barely-there colour, near greyscale"
    PaletteStyle.RAINBOW -> "Colourful accents, grey surfaces"
    PaletteStyle.FRUIT_SALAD -> "Hue-rotated accents"
    PaletteStyle.MONOCHROME -> "Pure greyscale"
    PaletteStyle.FIDELITY -> "Accent matches your seed exactly"
    PaletteStyle.CONTENT -> "Seed-exact, with a related tertiary"
    PaletteStyle.CMF -> "2026 scheme from all three seeds"
}

private fun contrastLabel(value: Float): String = when {
    value <= -0.75f -> "Reduced"
    value < -0.25f -> "Softer"
    abs(value) <= 0.25f -> "Standard"
    value < 0.75f -> "Medium"
    else -> "High"
}

/** What the Color Engine's picker sheet is editing. */
private sealed interface EnginePick {
    data class Seed(val slot: Int) : EnginePick
    data class Shade(val key: String) : EnginePick
    data class Group(val group: ColorGroup) : EnginePick
}

private fun pickOf(saved: String?): EnginePick? = when {
    saved == null -> null
    saved.startsWith("seed:") -> saved.removePrefix("seed:").toIntOrNull()?.let { EnginePick.Seed(it) }
    saved.startsWith("shade:") -> EnginePick.Shade(saved.removePrefix("shade:"))
    saved.startsWith("group:") -> ColorGroup.byId(saved.removePrefix("group:"))?.let { EnginePick.Group(it) }
    else -> null
}

/**
 * The Color Engine card (Studio v2 § 2.1): primary, secondary and tertiary seeds, the scheme
 * variant, contrast, per-mode adjustments with AMOLED, and the 65-shade Monet ribbon with pins.
 *
 * Colours come from [preview] (the draft-aware live state); [spec] and [seed] are the stored
 * values the sliders start from and commit onto. Drags preview through
 * [MainViewModel.previewDraft] and save once on release.
 */
@Composable
internal fun ColorEngineCard(
    viewModel: MainViewModel,
    spec: ThemeSpec,
    seed: Int,
    preview: StudioPreviewState,
    /** Opens the full shade grid (Colours > Shades); the ribbon here is then drawn compact. */
    onOpenShades: (() -> Unit)? = null,
) {
    var night by rememberSaveable { mutableStateOf(true) }
    var picking by rememberSaveable { mutableStateOf<String?>(null) }
    val adjustments = if (night) spec.darkAdjustments else spec.lightAdjustments
    val scheme = preview.scheme(night)
    val source = spec.source

    StudioCard(
        title = "Colour engine",
        eyebrow = "Monet · 65 shades",
        subtitle = "Seeds and scheme behind every One UI colour. Changes show in the preview instantly.",
    ) {
        // Seeds.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SeedTile(
                label = "Primary seed",
                argb = preview.seedArgb,
                auto = false,
                onClick = { picking = "seed:0" },
                modifier = Modifier.weight(1f),
            )
            SeedTile(
                label = "Secondary",
                argb = source.secondaryArgb ?: scheme.shade("accent2_500") ?: seed,
                auto = source.secondaryArgb == null,
                onClick = { picking = "seed:1" },
                modifier = Modifier.weight(1f),
            )
            SeedTile(
                label = "Tertiary",
                argb = source.tertiaryArgb ?: scheme.shade("accent3_500") ?: seed,
                auto = source.tertiaryArgb == null,
                onClick = { picking = "seed:2" },
                modifier = Modifier.weight(1f),
            )
        }

        // The two surfaces of the console reference: Settings screens and cards, per mode.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(ColorGroup.BACKGROUND to "Settings surface", ColorGroup.SURFACE to "Card surface").forEach { (group, label) ->
                SeedTile(
                    label = "$label · ${if (night) "dark" else "light"}",
                    argb = scheme.color(group),
                    auto = spec.groupColor(group, night) == null,
                    onClick = { picking = "group:${group.id}" },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // Scheme variant.
        SectionLabel("Scheme variant")
        StyleDropdown(
            selected = source.style,
            spec = spec,
            seed = seed,
            night = night,
            onSelect = viewModel::setPaletteStyle,
        )
        DraftSlider(
            label = "Contrast",
            value = source.contrast.toFloat(),
            valueRange = -1f..1f,
            steps = 3,
            format = ::contrastLabel,
            onDraft = { v -> viewModel.previewDraft(spec.copy(source = source.copy(contrast = v.toDouble()))) },
            onCommit = { v -> viewModel.setContrast(v.toDouble()) },
        )

        // Per-mode adjustments.
        SectionLabel("Adjust each mode")
        SegmentedChoice(
            options = listOf(false, true),
            selected = night,
            label = { if (it) "Dark" else "Light" },
            onSelect = { night = it },
        )
        AdjustmentSliders(viewModel, spec, night, adjustments)
        StudioSwitchRow(
            title = "Vivid shades",
            summary = "Richer accents with deeper mid tones",
            checked = !adjustments.accurateShades,
            onCheckedChange = { on -> viewModel.setAdjustments(night) { it.copy(accurateShades = !on) } },
        )
        StudioSwitchRow(
            title = "Tinted text",
            summary = "Text takes a hint of the theme colour; off is pure black and white",
            checked = adjustments.tintedText,
            onCheckedChange = { on -> viewModel.setAdjustments(night) { it.copy(tintedText = on) } },
        )
        if (night) {
            SectionLabel("AMOLED black")
            SegmentedChoice(
                options = PitchBlack.entries,
                selected = adjustments.pitchBlack,
                label = {
                    when (it) {
                        PitchBlack.OFF -> "Off"
                        PitchBlack.SURFACES -> "Surfaces"
                        PitchBlack.SURFACES_AND_CONTAINERS -> "+ Cards"
                    }
                },
                onSelect = { mode -> viewModel.setAdjustments(true) { it.copy(pitchBlack = mode) } },
            )
            Text(
                "Surfaces turns backgrounds true black; + Cards blackens cards and containers too.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val tuned = adjustments.accentSaturation != 100 || adjustments.backgroundSaturation != 100 ||
            adjustments.backgroundLightness != 100 || !adjustments.accurateShades || !adjustments.tintedText ||
            adjustments.pitchBlack != PitchBlack.OFF
        if (tuned) {
            TextButton(onClick = {
                viewModel.setAdjustments(night) { ThemeAdjustments(shadeOverrides = it.shadeOverrides, roleOverrides = it.roleOverrides) }
            }) { Text(if (night) "Reset dark adjustments" else "Reset light adjustments") }
        }

        // Monet ribbon.
        val pinned = remember(adjustments.shadeOverrides) { adjustments.shadeOverrides.keys.mapTo(HashSet()) { it.removePrefix("system_") } }
        SectionLabel(
            "Monet shades · ${if (night) "dark" else "light"}",
            trailing = {
                if (pinned.isNotEmpty()) {
                    StudioPill(
                        "${pinned.size} pinned",
                        container = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.16f),
                        content = MaterialTheme.colorScheme.tertiary,
                        mono = true,
                    )
                }
            },
        )
        ShadeRibbon(
            shades = scheme.shades,
            pinned = pinned,
            onShade = { key -> picking = "shade:$key" },
            onUnpin = { key -> viewModel.setShadeOverride(night, key, null) },
            cellHeight = if (onOpenShades != null) 20.dp else 28.dp,
            showScale = onOpenShades == null,
        )
        Text(
            "Tap a shade to pin it to your own colour. Pinning bends the neighbouring shades so the scale stays smooth. " +
                "Long-press a pinned shade to unpin it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (pinned.isNotEmpty()) {
            TextButton(onClick = { viewModel.setAdjustments(night) { it.copy(shadeOverrides = emptyMap()) } }) {
                Text("Unpin all ${if (night) "dark" else "light"} shades")
            }
        }
        if (onOpenShades != null) {
            StudioNavRow(
                title = "All 65 shades",
                summary = "The full grid with pins, and the system colour roles",
                onClick = onOpenShades,
            )
        }
    }

    when (val pick = pickOf(picking)) {
        is EnginePick.Seed -> SeedPicker(viewModel, spec, preview, night, pick.slot, onClose = { picking = null })
        is EnginePick.Shade -> {
            val key = pick.key
            val isPinned = key in adjustments.shadeOverrides || "system_$key" in adjustments.shadeOverrides
            ColorPickerSheet(
                title = shadeLabel(key),
                subtitle = "${if (night) "Dark" else "Light"} scheme · pinning bends the neighbouring shades",
                initial = scheme.shade(key) ?: seed,
                shades = scheme.shades,
                autoLabel = if (isPinned) "Unpin" else null,
                onPreview = { argb, _ -> viewModel.previewDraft(spec.withShadeOverride(night, key, argb)) },
                onPick = { argb, _ ->
                    viewModel.setShadeOverride(night, key, argb)
                    picking = null
                },
                onAuto = {
                    viewModel.setShadeOverride(night, key, null)
                    picking = null
                },
                onDismiss = {
                    viewModel.previewDraft(null)
                    picking = null
                },
            )
        }
        is EnginePick.Group -> GroupColorSheet(viewModel, spec, preview, pick.group, night, onClose = { picking = null })
        null -> Unit
    }
}

/** The picker of seed [slot] (0 primary, 1 secondary, 2 tertiary). */
@Composable
private fun SeedPicker(
    viewModel: MainViewModel,
    spec: ThemeSpec,
    preview: StudioPreviewState,
    night: Boolean,
    slot: Int,
    onClose: () -> Unit,
) {
    val source = spec.source
    val shades = preview.scheme(night).shades
    when (slot) {
        0 -> ColorPickerSheet(
            title = "Primary seed",
            subtitle = "The colour every palette grows from",
            initial = preview.seedArgb,
            shades = shades,
            onPreview = { argb, _ -> viewModel.previewSeed(argb) },
            onPick = { argb, _ ->
                viewModel.setSeed(argb)
                onClose()
            },
            onDismiss = {
                viewModel.previewSeed(null)
                onClose()
            },
        )
        else -> {
            val secondary = slot == 1
            val current = if (secondary) source.secondaryArgb else source.tertiaryArgb
            val auto = preview.scheme(night).shade(if (secondary) "accent2_500" else "accent3_500") ?: preview.seedArgb
            val withSeed: (Int?) -> ThemeSpec = { argb ->
                spec.copy(source = if (secondary) source.copy(secondaryArgb = argb) else source.copy(tertiaryArgb = argb))
            }
            ColorPickerSheet(
                title = if (secondary) "Secondary seed" else "Tertiary seed",
                subtitle = if (secondary) "Sets the hue of the secondary accent palette" else "Sets the hue of the tertiary accent palette",
                initial = current ?: auto,
                shades = shades,
                autoLabel = if (current != null) "Back to Auto" else null,
                onPreview = { argb, _ -> viewModel.previewDraft(withSeed(argb)) },
                onPick = { argb, _ ->
                    if (secondary) viewModel.setSecondarySeed(argb) else viewModel.setTertiarySeed(argb)
                    onClose()
                },
                onAuto = {
                    if (secondary) viewModel.setSecondarySeed(null) else viewModel.setTertiarySeed(null)
                    onClose()
                },
                onDismiss = {
                    viewModel.previewDraft(null)
                    onClose()
                },
            )
        }
    }
}

/** Accent saturation, background saturation and background lightness of one mode. */
@Composable
private fun AdjustmentSliders(viewModel: MainViewModel, spec: ThemeSpec, night: Boolean, adjustments: ThemeAdjustments) {
    val percent: (Float) -> String = { "${it.roundToInt()}%" }
    DraftSlider(
        label = "Accent saturation",
        value = adjustments.accentSaturation.toFloat(),
        valueRange = 0f..200f,
        format = percent,
        onDraft = { v -> viewModel.previewDraft(spec.withAdjustments(night) { it.copy(accentSaturation = v.roundToInt()) }) },
        onCommit = { v -> viewModel.setAdjustments(night) { it.copy(accentSaturation = v.roundToInt()) } },
    )
    DraftSlider(
        label = "Background saturation",
        value = adjustments.backgroundSaturation.toFloat(),
        valueRange = 0f..200f,
        format = percent,
        onDraft = { v -> viewModel.previewDraft(spec.withAdjustments(night) { it.copy(backgroundSaturation = v.roundToInt()) }) },
        onCommit = { v -> viewModel.setAdjustments(night) { it.copy(backgroundSaturation = v.roundToInt()) } },
    )
    DraftSlider(
        label = "Background lightness",
        value = adjustments.backgroundLightness.toFloat(),
        valueRange = 0f..200f,
        format = { v ->
            val r = v.roundToInt()
            when {
                r < 100 -> "Darker $r%"
                r > 100 -> "Lighter $r%"
                else -> "100%"
            }
        },
        onDraft = { v -> viewModel.previewDraft(spec.withAdjustments(night) { it.copy(backgroundLightness = v.roundToInt()) }) },
        onCommit = { v -> viewModel.setAdjustments(night) { it.copy(backgroundLightness = v.roundToInt()) } },
    )
}

/** One seed slot: swatch, label, hex and an Auto pill; opens the picker. */
@Composable
private fun SeedTile(label: String, argb: Int, auto: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .selectable(selected = false, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "$label, ${if (auto) "Auto, " else ""}${hexOf(argb)}. Change" }
            .heightIn(min = 48.dp)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(argb)),
            contentAlignment = Alignment.Center,
        ) {
            if (auto) {
                Text("AUTO", style = monoStyle(MaterialTheme.typography.labelSmall), color = readableOn(argb))
            }
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Text(hexOf(argb), style = monoStyle(MaterialTheme.typography.labelLarge), maxLines = 1)
    }
}

/** The scheme variant drop-down; each entry shows the four key colours it gives this seed. */
@Composable
private fun StyleDropdown(selected: PaletteStyle, spec: ThemeSpec, seed: Int, night: Boolean, onSelect: (PaletteStyle) -> Unit) {
    StudioDropdown(
        label = "Scheme variant",
        selectedLabel = "${styleLabel(selected)} · ${styleSummary(selected)}",
    ) { close ->
        // Resolved only while the menu is open: ten small palette builds.
        val samples = remember(seed, spec.source, spec.darkAdjustments, spec.lightAdjustments, night) {
            PaletteStyle.entries.associateWith { style ->
                runCatching {
                    val scheme = ThemeResolver.resolveScheme(
                        spec.source.copy(seedArgb = seed, style = style),
                        isDark = night,
                        adjustments = if (night) spec.darkAdjustments else spec.lightAdjustments,
                    )
                    listOf("system_accent1_500", "system_accent2_500", "system_accent3_500", if (night) "system_neutral1_800" else "system_neutral1_100")
                        .mapNotNull { scheme.shades[it] }
                }.getOrDefault(emptyList())
            }
        }
        PaletteStyle.entries.forEach { style ->
            StudioDropdownItem(
                text = if (style == selected) "${styleLabel(style)}  ✓" else styleLabel(style),
                onClick = {
                    close()
                    onSelect(style)
                },
                trailing = { ColorDots(samples[style].orEmpty()) },
            )
        }
    }
}
