package com.hexnext.themer.ui.studio

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hexnext.engine.compile.IconColorStyle
import com.hexnext.engine.compile.ThemeSpec
import com.hexnext.themer.theme.Capability
import com.hexnext.themer.ui.MainViewModel
import com.hexnext.themer.ui.NeedsRootBadge
import com.hexnext.themer.ui.NeedsRootReason

/** Name and one-line description of each icon colour style. */
internal fun iconStyleLabel(style: IconColorStyle): String = when (style) {
    IconColorStyle.PACK_ORIGINAL -> "Pack original"
    IconColorStyle.ACCENT -> "Accent tint"
    IconColorStyle.MULTICOLOR -> "Multicolour"
    IconColorStyle.DUOTONE -> "Duotone"
    IconColorStyle.GRADIENT -> "Gradient"
}

private fun iconStyleSummary(style: IconColorStyle): String = when (style) {
    IconColorStyle.PACK_ORIGINAL -> "The pack's own artwork and colours, untouched. Built-in icons are multicolour."
    IconColorStyle.ACCENT -> "Every icon tinted with your accent shades."
    IconColorStyle.MULTICOLOR -> "A vivid hue for each Settings row and My Files category, like stock One UI."
    IconColorStyle.DUOTONE -> "Primary and tertiary accent on alternating layers."
    IconColorStyle.GRADIENT -> "Multicolour, with a soft two-stop gradient on each icon."
}

/**
 * The Icons step's style card: a live strip of the preview icons, the five [IconColorStyle]s, the
 * built-in icons switch and the synthesis switch. Icons are PNG drawables, which need root.
 */
@Composable
internal fun IconStyleCard(
    viewModel: MainViewModel,
    spec: ThemeSpec,
    preview: StudioPreviewState,
    busy: Boolean,
    capability: Capability,
) {
    val needsRoot = capability == Capability.SHELL
    StudioCard(
        title = "Icon style",
        eyebrow = "Settings and My Files icons",
        subtitle = "Colourful PNG icons for Settings rows and My Files categories, from your pack or built in.",
        trailing = if (needsRoot) ({ NeedsRootBadge() }) else null,
    ) {
        IconStrip(preview)
        if (needsRoot) {
            NeedsRootReason("Icons replace system images, which needs root. Your colours still apply.")
        } else {
            Text(
                "Icons apply with root. Without root only the colours change.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            IconColorStyle.entries.forEach { style ->
                StyleRow(
                    style = style,
                    selected = spec.iconStyle == style,
                    enabled = !busy,
                    onClick = { viewModel.setIconStyle(style) },
                )
            }
        }
        StudioSwitchRow(
            title = "Built-in colourful icons",
            summary = "Hex Next's own icons for Settings rows and My Files categories no pack covers",
            checked = spec.builtInIcons,
            enabled = !busy,
            onCheckedChange = viewModel::setBuiltInIcons,
        )
        StudioSwitchRow(
            title = "Fill in missing Settings icons",
            summary = "Draw rows the pack lacks in the pack's own style",
            checked = spec.synthesizeMissingIcons,
            enabled = !busy,
            onCheckedChange = viewModel::setSynthesizeMissingIcons,
        )
    }
}

/** The first preview icons in a scrolling strip, with a spinner while they render. */
@Composable
private fun IconStrip(preview: StudioPreviewState) {
    val icons = (preview.settingsIcons + preview.myFilesIcons).filter { it.bitmap != null }.take(14)
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(preview.dark.color(com.hexnext.engine.compile.ColorGroup.BACKGROUND)))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
            .semantics(mergeDescendants = true) { contentDescription = "Icon preview, ${icons.size} icons" }
            .padding(12.dp),
    ) {
        if (icons.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (preview.iconsLoading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(
                    if (preview.iconsLoading) "Rendering icons…" else "No icons to show yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(preview.dark.color(com.hexnext.engine.compile.ColorGroup.TEXT_SECONDARY)),
                )
            }
        } else {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                icons.forEach { icon ->
                    val bitmap = icon.bitmap
                    if (bitmap != null) {
                        Image(bitmap, contentDescription = null, modifier = Modifier.size(44.dp))
                    } else {
                        Box(Modifier.size(44.dp).clip(CircleShape).background(Color(icon.fallbackArgb)))
                    }
                }
                if (preview.iconsLoading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
        }
    }
}

/** One radio row of [IconStyleCard]. */
@Composable
private fun StyleRow(style: IconColorStyle, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(shape)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f) else Color.Transparent)
            .border(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, shape)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(Modifier.weight(1f)) {
            Text(iconStyleLabel(style), style = MaterialTheme.typography.titleSmall)
            Text(iconStyleSummary(style), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
