package com.hexnext.themer.ui.studio

import android.graphics.Color as AndroidColor
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hexnext.themer.theme.toHex
import com.hexnext.themer.ui.LocalAppSettings

/** Opaque alpha for colours the Studio writes. */
internal const val OPAQUE_ARGB: Int = 0xFF000000.toInt()

/** Text style of resource names, hex values and console captions. */
@Composable
internal fun monoStyle(base: TextStyle = MaterialTheme.typography.bodySmall): TextStyle =
    base.copy(fontFamily = FontFamily.Monospace, letterSpacing = 0.sp)

/**
 * The console card of the Studio: a 1 dp outline around the container colour, an optional
 * monospace [eyebrow] in the primary colour with a status dot ("● COLOUR ENGINE"), the [title],
 * an optional [subtitle] and a [trailing] control at the title's right.
 */
@Composable
internal fun StudioCard(
    title: String,
    modifier: Modifier = Modifier,
    eyebrow: String? = null,
    subtitle: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val reduceMotion = LocalAppSettings.current.reduceMotion
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            Modifier
                .then(if (reduceMotion) Modifier else Modifier.animateContentSize())
                .padding(horizontal = 18.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (eyebrow != null) Eyebrow(eyebrow)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                    if (subtitle != null) {
                        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                trailing?.invoke(this)
            }
            content()
        }
    }
}

/** "● COLOUR ENGINE": a dot and a monospace caption in the primary colour. */
@Composable
internal fun Eyebrow(text: String, color: Color = MaterialTheme.colorScheme.primary) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Text(
            text.uppercase(),
            style = monoStyle(MaterialTheme.typography.labelSmall).copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp),
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A small-caps section header in muted text, as in the console reference ("SCHEME VARIANT"). */
@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: (@Composable RowScope.() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp, fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        trailing?.invoke(this)
    }
}

/** A rounded pill: [text] on [container]. */
@Composable
internal fun StudioPill(
    text: String,
    container: Color = MaterialTheme.colorScheme.secondaryContainer,
    content: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    mono: Boolean = false,
) {
    Text(
        text,
        style = if (mono) monoStyle(MaterialTheme.typography.labelSmall) else MaterialTheme.typography.labelSmall,
        color = content,
        maxLines = 1,
        modifier = Modifier
            .clip(CircleShape)
            .background(container)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** Black or white, whichever reads better on [argb]. */
internal fun readableOn(argb: Int): Color = if (Color(argb).luminance() > 0.45f) Color(0xFF0B1220) else Color.White

/** `#RRGGBB` of [argb]. */
internal fun hexOf(argb: Int): String = argb.toHex()

/** Hue (0 … 360), saturation and value (0 … 1) of [argb]. */
internal fun hsvOf(argb: Int): List<Float> {
    val out = FloatArray(3)
    AndroidColor.colorToHSV(argb, out)
    return out.toList()
}

/** The opaque colour of [hsv] (hue, saturation, value). */
internal fun colorOfHsv(hsv: List<Float>): Int = AndroidColor.HSVToColor(hsv.toFloatArray()) or OPAQUE_ARGB

/**
 * A square colour swatch. Auto colours carry a small "A", user colours a dot; [selected] draws a
 * thick ring. One button node named by [description].
 */
@Composable
internal fun ColorTile(
    argb: Int,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    auto: Boolean = false,
    selected: Boolean = false,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier
            .size(size)
            .clip(shape)
            .background(Color(argb))
            .border(
                if (selected) 2.5.dp else 1.dp,
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                shape,
            )
            .selectable(selected = selected, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        val mark = readableOn(argb)
        if (auto) {
            Text("A", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = mark.copy(alpha = 0.75f))
        } else {
            Box(Modifier.size(7.dp).clip(CircleShape).background(mark.copy(alpha = 0.85f)))
        }
    }
}

/** One step of [StepTabs]. */
internal data class StepTab(val key: String, val label: String, val badge: String? = null)

/**
 * The Studio's step tabs: a horizontally scrolling row of numbered pills; the selected one is
 * filled with the primary container and outlined in the primary colour. One tab node each.
 */
@Composable
internal fun StepTabs(tabs: List<StepTab>, selectedKey: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp))
            .horizontalScroll(rememberScrollState())
            .padding(5.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        tabs.forEachIndexed { index, tab ->
            val selected = tab.key == selectedKey
            Row(
                Modifier
                    .heightIn(min = 48.dp)
                    .clip(shape)
                    .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                    .border(1.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, shape)
                    .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(tab.key) })
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "${index + 1}",
                    style = monoStyle(MaterialTheme.typography.labelMedium),
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    tab.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                if (tab.badge != null) {
                    StudioPill(
                        tab.badge,
                        container = MaterialTheme.colorScheme.secondary.copy(alpha = 0.18f),
                        content = MaterialTheme.colorScheme.secondary,
                        mono = true,
                    )
                }
            }
        }
    }
}

/**
 * A labelled slider whose drag only previews: [onDraft] runs on every move (live colours, no
 * save) and [onCommit] once on release with the final value. The thumb follows the finger while
 * dragging and [value] otherwise.
 */
@Composable
internal fun DraftSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    format: (Float) -> String,
    onDraft: (Float) -> Unit,
    onCommit: (Float) -> Unit,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    enabled: Boolean = true,
    caption: String? = null,
) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val shown = dragging ?: value
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(format(shown), style = monoStyle(MaterialTheme.typography.labelMedium), color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = shown,
            onValueChange = { v ->
                dragging = v
                onDraft(v)
            },
            onValueChangeFinished = {
                val final = dragging
                dragging = null
                if (final != null) onCommit(final)
            },
            valueRange = valueRange,
            steps = steps,
            enabled = enabled,
            modifier = Modifier.semantics {
                contentDescription = label
                stateDescription = format(shown)
            },
        )
        if (caption != null) {
            Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Single choice of [options] as connected segmented buttons. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <T> SegmentedChoice(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                enabled = enabled,
                label = { Text(label(option), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

/** A switch row with a title and an optional summary; the whole row is one toggleable node. */
@Composable
internal fun StudioSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    summary: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (summary != null) {
                Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** A row that opens another screen: title, summary and a chevron, one button node. */
@Composable
internal fun StudioNavRow(title: String, summary: String, onClick: () -> Unit, enabled: Boolean = true) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
            .selectable(selected = false, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
    }
}

/**
 * A drop-down on an outlined button that shows [selectedLabel]; [items] draws the menu entries
 * and gets a callback that closes the menu. Announced as "[label]: [selectedLabel]".
 */
@Composable
internal fun StudioDropdown(
    label: String,
    selectedLabel: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    items: @Composable (close: () -> Unit) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = enabled,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    role = Role.DropdownList
                    contentDescription = "$label: $selectedLabel"
                },
        ) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(10.dp))
            }
            Text(selectedLabel, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            items { expanded = false }
        }
    }
}

/** One entry of [StudioDropdown]: [text] with an optional [trailing] decoration. */
@Composable
internal fun StudioDropdownItem(text: String, onClick: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    DropdownMenuItem(
        text = { Text(text) },
        onClick = onClick,
        trailingIcon = trailing,
    )
}

/** A row of small colour dots (the colours a style or theme produces). */
@Composable
internal fun ColorDots(colors: List<Int>, size: Dp = 14.dp) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        colors.forEach { argb ->
            Box(
                Modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(Color(argb))
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), CircleShape),
            )
        }
    }
}
