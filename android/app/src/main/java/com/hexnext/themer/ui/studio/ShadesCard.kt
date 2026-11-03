package com.hexnext.themer.ui.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hexnext.engine.compile.ThemeSpec
import com.hexnext.engine.palette.ThemeResolver
import com.hexnext.themer.ui.MainViewModel
import com.hexnext.themer.ui.withShadeOverride

/**
 * Colours > Shades (Studio v2 phase 2 § 2.5): the 65 Monet shades (5 palettes × 13 stops) of one
 * mode as a full grid. Tapping a shade pins it to a colour ([MainViewModel.setShadeOverride]);
 * pinned shades carry a dot and are listed underneath with Unpin. Under that, read-only, the
 * Material colour roles the engine resolves from the same palettes.
 */
@Composable
internal fun ShadesCard(viewModel: MainViewModel, spec: ThemeSpec, preview: StudioPreviewState) {
    var night by rememberSaveable { mutableStateOf(true) }
    var picking by rememberSaveable { mutableStateOf<String?>(null) }
    var showRoles by rememberSaveable { mutableStateOf(false) }
    val adjustments = if (night) spec.darkAdjustments else spec.lightAdjustments
    val scheme = preview.scheme(night)
    val pinned = remember(adjustments.shadeOverrides) {
        adjustments.shadeOverrides.keys.mapTo(LinkedHashSet()) { it.removePrefix("system_") }
    }
    val mode = if (night) "dark" else "light"

    StudioCard(
        title = "Palette shades",
        eyebrow = "65 shades · $mode",
        subtitle = "Five palettes of 13 shades each. Every One UI colour is taken from these.",
        trailing = {
            if (pinned.isNotEmpty()) StatusChip("${pinned.size} pinned", StatusTone.WARN)
        },
    ) {
        SegmentedChoice(
            options = listOf(false, true),
            selected = night,
            label = { if (it) "Dark" else "Light" },
            onSelect = { night = it },
        )
        ShadeRibbon(
            shades = scheme.shades,
            pinned = pinned,
            onShade = { key -> picking = key },
            onUnpin = { key -> viewModel.setShadeOverride(night, key, null) },
            cellHeight = 40.dp,
        )
        Text(
            "Tap a shade to pin it to your own colour. Pinning bends the neighbouring shades so the scale stays smooth.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (pinned.isNotEmpty()) {
            SectionLabel("Pinned · $mode")
            pinned.forEach { key ->
                val argb = scheme.shade(key) ?: 0
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    RoleSwatch(argb, "${shadeLabel(key)}, ${hexOf(argb)}, pinned")
                    Column(Modifier.weight(1f)) {
                        Text("system_$key", style = monoStyle(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(hexOf(argb), style = monoStyle(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(
                        onClick = { viewModel.setShadeOverride(night, key, null) },
                        modifier = Modifier.semantics { contentDescription = "Unpin ${shadeLabel(key)}" },
                    ) { Text("Unpin") }
                }
            }
            TextButton(onClick = { viewModel.setAdjustments(night) { it.copy(shadeOverrides = emptyMap()) } }) {
                Text("Unpin all $mode shades")
            }
        }

        SectionLabel(
            "System roles · $mode",
            trailing = {
                TextButton(onClick = { showRoles = !showRoles }) { Text(if (showRoles) "Hide" else "Show") }
            },
        )
        if (showRoles) {
            // The tuned scheme the engine applies, resolved only while the list is open.
            val roles = remember(preview.seedArgb, spec.source, adjustments, night) {
                runCatching {
                    ThemeResolver.resolveScheme(spec.source.copy(seedArgb = preview.seedArgb), isDark = night, adjustments = adjustments).roles
                }.getOrDefault(emptyMap())
            }
            Text(
                "Read only. The Material colour roles the engine works out from these shades, as apps see them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            roles.forEach { (name, argb) ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 40.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    RoleSwatch(argb, "$name, ${hexOf(argb)}")
                    Text(name, style = monoStyle(), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(hexOf(argb), style = monoStyle(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    val key = picking
    if (key != null) {
        val isPinned = key in pinned
        ColorPickerSheet(
            title = shadeLabel(key),
            subtitle = "${if (night) "Dark" else "Light"} scheme · pinning bends the neighbouring shades",
            initial = scheme.shade(key) ?: preview.seedArgb,
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
}

/** A 28 dp colour square with an outline, named for TalkBack. */
@Composable
private fun RoleSwatch(argb: Int, description: String) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .size(28.dp)
            .clip(shape)
            .background(Color(argb))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .semantics { contentDescription = description },
    )
}
