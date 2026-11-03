package com.hexnext.themer.ui.studio

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hexnext.engine.compile.ColorGroup
import com.hexnext.engine.compile.ColorGroupSection
import com.hexnext.engine.compile.GroupCount
import com.hexnext.engine.compile.ThemeSpec
import com.hexnext.engine.compile.groupColor
import com.hexnext.engine.compile.withGroupColor
import com.hexnext.themer.ui.ConfirmDialog
import com.hexnext.themer.ui.LocalAppSettings
import com.hexnext.themer.ui.MainViewModel
import kotlinx.coroutines.delay

/**
 * "412 resources in 31 apps", or null without a count. With [sayNothing] (counts are loaded and
 * the group is a Quick panel group) a group that reaches nothing says so instead of staying silent.
 */
private fun countLine(count: GroupCount?, sayNothing: Boolean = false): String? {
    if (count == null || count.resources == 0) return if (sayNothing) "Reaches nothing on this phone" else null
    val resources = if (count.resources == 1) "1 resource" else "${count.resources} resources"
    val apps = if (count.packages == 1) "1 app" else "${count.packages} apps"
    return "$resources in $apps"
}

/**
 * Whether "Same for light and dark" starts on for [group]: on for accent-like groups (a switch is
 * usually the same colour day and night) and for groups already set to one colour in both modes;
 * off for backgrounds and text, whose light and dark colours must differ.
 */
private fun startsBoth(spec: ThemeSpec, group: ColorGroup): Boolean {
    val light = spec.groupColor(group, false)
    val dark = spec.groupColor(group, true)
    return when {
        light != null && dark != null -> light == dark
        light != null || dark != null -> false
        else -> group.autoRole.isAccent
    }
}

/**
 * One UI element colours (Studio v2 § 2.2): a row per [ColorGroup] in [groups], under its
 * [ColorGroupSection], with day and night swatches, Auto or Custom, the resource count of the last
 * scan and a "Quick panel only" badge on groups that reach only System UI. A swatch opens [ColorPickerSheet]
 * for that mode ("Same for light and dark" writes both).
 *
 * The row of [StudioPreviewState.highlightedGroup] is outlined. With [bringHighlightIntoView]
 * (a tap on the phone preview that is still being handled) it is also scrolled into view, so the
 * tap lands here; a later visit keeps its own scroll.
 */
@Composable
internal fun ElementColorsCard(
    viewModel: MainViewModel,
    spec: ThemeSpec,
    preview: StudioPreviewState,
    counts: Map<ColorGroup, GroupCount>,
    modifier: Modifier = Modifier,
    groups: List<ColorGroup> = ColorGroup.ALL,
    bringHighlightIntoView: Boolean = false,
    title: String = "One UI element colours",
    subtitle: String = "Colour every part of One UI on its own, for light and dark. Auto follows the colour engine.",
    eyebrow: String = "Elements · ${groups.size} groups",
    onOpenResources: (() -> Unit)? = null,
) {
    var picking by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(preview.hasScan) {
        if (preview.hasScan) viewModel.loadGroupCounts()
    }
    val custom = groups.count { spec.colorGroups[it.id]?.isAuto == false }
    StudioCard(
        title = title,
        eyebrow = eyebrow,
        subtitle = subtitle,
        modifier = modifier,
        trailing = {
            if (custom > 0) {
                StudioPill(
                    "$custom custom",
                    container = MaterialTheme.colorScheme.secondary.copy(alpha = 0.16f),
                    content = MaterialTheme.colorScheme.secondary,
                    mono = true,
                )
            }
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Tap a swatch to change it, or tap any part of the phone preview.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }
        ColorGroupSection.entries.forEach { section ->
            val inSection = groups.filter { it.section == section }
            if (inSection.isNotEmpty()) {
                SectionLabel(section.label)
                inSection.forEach { group ->
                    ElementRow(
                        group = group,
                        spec = spec,
                        preview = preview,
                        count = counts[group],
                        counted = counts.isNotEmpty(),
                        highlighted = preview.highlightedGroup == group,
                        bringIntoView = bringHighlightIntoView,
                        onSelect = { viewModel.setHighlightedGroup(group) },
                        onSwatch = { night ->
                            viewModel.setHighlightedGroup(group)
                            picking = "${group.id}:$night"
                        },
                    )
                }
            }
        }
        if (custom > 0) {
            TextButton(onClick = { confirmReset = true }) { Text("Reset all to Auto") }
        }
        if (onOpenResources != null) {
            StudioNavRow(
                title = "Edit individual resources",
                summary = "Every colour of every scanned One UI app, with pins",
                onClick = onOpenResources,
            )
        }
    }

    val pick = picking?.let { saved ->
        val group = ColorGroup.byId(saved.substringBefore(':'))
        val night = saved.substringAfter(':') == "true"
        group?.let { it to night }
    }
    if (pick != null) {
        val (group, night) = pick
        GroupColorSheet(viewModel, spec, preview, group, night, onClose = { picking = null })
    }
    if (confirmReset) {
        ConfirmDialog(
            title = "Reset every element to Auto?",
            text = "Your custom element colours are cleared in light and dark mode. Seeds, shades and pins stay.",
            confirmLabel = "Reset",
            onConfirm = {
                confirmReset = false
                viewModel.resetGroupColors()
            },
            onDismiss = { confirmReset = false },
        )
    }
}

/**
 * The colour sheet of element [group] in one mode ([night]): drags preview through
 * [MainViewModel.previewDraft], a pick saves the mode (or both with "Same for light and dark"),
 * "Back to Auto" clears it. Shared by the element rows, the Colour engine's surface tiles and the
 * Studio's tap-to-edit on the phone preview.
 */
@Composable
internal fun GroupColorSheet(
    viewModel: MainViewModel,
    spec: ThemeSpec,
    preview: StudioPreviewState,
    group: ColorGroup,
    night: Boolean,
    onClose: () -> Unit,
) {
    val customHere = spec.groupColor(group, night) != null
    val customOther = spec.groupColor(group, !night) != null
    ColorPickerSheet(
        title = group.label,
        subtitle = buildString {
            append(if (night) "Dark mode" else "Light mode")
            append(" · ")
            append(group.description)
            if (group.quickSettings) append(" · Quick Settings is colour-only")
        },
        initial = preview.scheme(night).color(group),
        shades = preview.scheme(night).shades,
        bothToggle = true,
        initialBoth = startsBoth(spec, group),
        autoLabel = if (customHere || customOther) "Back to Auto" else null,
        onPreview = { argb, both ->
            val draft = if (both) spec.withGroupColor(group, false, argb).withGroupColor(group, true, argb) else spec.withGroupColor(group, night, argb)
            viewModel.previewDraft(draft)
        },
        onPick = { argb, both ->
            if (both) viewModel.setGroupColorBoth(group, argb) else viewModel.setGroupColor(group, night, argb)
            onClose()
        },
        onAuto = { both ->
            if (both) viewModel.setGroupColorBoth(group, null) else viewModel.setGroupColor(group, night, null)
            onClose()
        },
        onDismiss = {
            viewModel.previewDraft(null)
            onClose()
        },
    )
}

/** One element group: label, badges, description, status, and its day and night swatches. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ElementRow(
    group: ColorGroup,
    spec: ThemeSpec,
    preview: StudioPreviewState,
    count: GroupCount?,
    counted: Boolean,
    highlighted: Boolean,
    bringIntoView: Boolean,
    onSelect: () -> Unit,
    onSwatch: (night: Boolean) -> Unit,
) {
    val reduceMotion = LocalAppSettings.current.reduceMotion
    val requester = remember { BringIntoViewRequester() }
    // Read when the effect starts: the jump request is consumed while the row is still scrolling.
    LaunchedEffect(highlighted) {
        if (highlighted && bringIntoView) {
            // Let a step switch and the list scroll settle before the row asks for the rest.
            delay(250)
            runCatching { requester.bringIntoView() }
        }
    }
    val background by animateColorAsState(
        targetValue = if (highlighted) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else Color.Transparent,
        animationSpec = if (reduceMotion) snap() else tween(350),
        label = "elementHighlight",
    )
    val shape = RoundedCornerShape(14.dp)
    val lightCustom = spec.groupColor(group, false) != null
    val darkCustom = spec.groupColor(group, true) != null
    val light = preview.light.color(group)
    val dark = preview.dark.color(group)
    val status = when {
        lightCustom && darkCustom -> "Custom · light and dark"
        lightCustom -> "Custom · light"
        darkCustom -> "Custom · dark"
        else -> "Auto"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(requester)
            .clip(shape)
            .background(background)
            .then(if (highlighted) Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
            .selectable(selected = highlighted, role = Role.Button, onClick = onSelect)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(group.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f, fill = false))
                if (group.quickSettings) {
                    StudioPill(
                        "Quick panel only",
                        container = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.16f),
                        content = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            Text(
                group.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(status, countLine(count, counted && group.quickSettings)).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = if (lightCustom || darkCustom) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            ColorTile(
                argb = light,
                description = "${group.label} light, ${hexOf(light)}, ${if (lightCustom) "custom" else "Auto"}, edit colour",
                onClick = { onSwatch(false) },
                auto = !lightCustom,
            )
            Text("Light", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(hexOf(light), style = monoStyle(MaterialTheme.typography.labelSmall), color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            ColorTile(
                argb = dark,
                description = "${group.label} dark, ${hexOf(dark)}, ${if (darkCustom) "custom" else "Auto"}, edit colour",
                onClick = { onSwatch(true) },
                auto = !darkCustom,
            )
            Text("Dark", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(hexOf(dark), style = monoStyle(MaterialTheme.typography.labelSmall), color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
        }
    }
}
