package com.hexnext.themer.ui.studio

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import com.hexnext.engine.palette.PaletteRole
import com.hexnext.engine.palette.ShadeScale

/** Short row labels of the five Monet palettes. */
private val ROLE_LABELS: Map<PaletteRole, String> = mapOf(
    PaletteRole.ACCENT1 to "A1",
    PaletteRole.ACCENT2 to "A2",
    PaletteRole.ACCENT3 to "A3",
    PaletteRole.NEUTRAL1 to "N1",
    PaletteRole.NEUTRAL2 to "N2",
)

/** Spoken names of the five Monet palettes. */
internal val ROLE_NAMES: Map<PaletteRole, String> = mapOf(
    PaletteRole.ACCENT1 to "Primary accent",
    PaletteRole.ACCENT2 to "Secondary accent",
    PaletteRole.ACCENT3 to "Tertiary accent",
    PaletteRole.NEUTRAL1 to "Neutral",
    PaletteRole.NEUTRAL2 to "Neutral variant",
)

/** `accent1_500` for [role] at [shade]. */
internal fun shortShadeKey(role: PaletteRole, shade: Int): String = ShadeScale.key(role, shade).removePrefix("system_")

/** `accent1 500` for a short or system shade key. */
internal fun shadeLabel(key: String): String = key.removePrefix("system_").replace('_', ' ')

/**
 * The Monet ribbon: 5 rows (accent1-3, neutral1-2) of 13 shades from [shades] (keys
 * `system_accent1_500`, as [com.hexnext.engine.palette.ResolvedScheme.shades]).
 *
 * Tapping a cell calls [onShade] with its short key (`accent1_500`). Keys in [pinned] (short form)
 * carry a dot; a long press on one calls [onUnpin]. [selectedKey] rings one cell (pickers).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ShadeRibbon(
    shades: Map<String, Int>,
    onShade: (String) -> Unit,
    modifier: Modifier = Modifier,
    pinned: Set<String> = emptySet(),
    onUnpin: ((String) -> Unit)? = null,
    selectedKey: String? = null,
    cellHeight: Dp = 28.dp,
    showScale: Boolean = true,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        if (showScale) {
            // 0, 500 and 1000 at the start, middle and end of the scale, at a readable 12 sp.
            Row(Modifier.fillMaxWidth().padding(start = 26.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("0", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                Text(
                    "500",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                Text("1000", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
        PaletteRole.SHADE_ROLES.forEach { role ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    ROLE_LABELS[role].orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(24.dp),
                )
                ShadeScale.SHADES.forEachIndexed { index, shade ->
                    val key = shortShadeKey(role, shade)
                    val argb = shades["system_$key"] ?: shades[key] ?: 0xFF808080.toInt()
                    val isPinned = key in pinned
                    val selected = key == selectedKey
                    val shape = when (index) {
                        0 -> RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp)
                        ShadeScale.SHADES.lastIndex -> RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp)
                        else -> RoundedCornerShape(2.dp)
                    }
                    val unpin = onUnpin
                    Box(
                        Modifier
                            .weight(1f)
                            .height(cellHeight)
                            .clip(shape)
                            .background(Color(argb))
                            .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
                            .combinedClickable(
                                role = Role.Button,
                                onClick = { onShade(key) },
                                onLongClick = if (isPinned && unpin != null) ({ unpin(key) }) else null,
                                onLongClickLabel = if (isPinned && unpin != null) "Unpin" else null,
                            )
                            .semantics {
                                contentDescription = "${ROLE_NAMES[role]} $shade, ${hexOf(argb)}" + if (isPinned) ", pinned" else ""
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isPinned) {
                            Box(
                                Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(readableOn(argb))
                                    .border(1.dp, Color(argb), CircleShape),
                            )
                        }
                    }
                }
            }
        }
    }
}
