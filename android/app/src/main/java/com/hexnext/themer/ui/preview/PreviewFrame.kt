package com.hexnext.themer.ui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hexnext.engine.compile.ColorGroup
import com.hexnext.themer.ui.studio.PreviewTab
import com.hexnext.themer.ui.studio.StudioPreviewState

/*
 * The phone frame. Screens are laid out at a fixed design size (DESIGN_WIDTH × DESIGN_HEIGHT,
 * a 9:19.5 phone) and the whole body is scaled to the space available, so every screen looks
 * the same on a 360dp phone, in a collapsed card and on a tablet. graphicsLayer scaling keeps
 * touch targets aligned with what is drawn.
 */

/** Width of the phone body in design dp. */
internal val DESIGN_WIDTH: Dp = 300.dp

/** Height of the phone body in design dp (9:19.5). */
internal val DESIGN_HEIGHT: Dp = 650.dp

/** The largest height the phone is drawn at. */
private val MAX_PHONE_HEIGHT: Dp = 640.dp

/** Height taken by the controls around the phone (tabs, mode row, note), for bounded layouts. */
private val CHROME_HEIGHT: Dp = 136.dp

private val BezelColor = Color(0xFF05070C)
private val BezelEdge = Color(0xFF2A3550)

/**
 * The tab row, the light/dark switch, the scaled phone with [screen] inside its status and
 * gesture bars, and the note line under it.
 */
@Composable
internal fun PreviewFrame(
    state: StudioPreviewState,
    tab: PreviewTab,
    onTabChange: (PreviewTab) -> Unit,
    dark: Boolean,
    onDarkChange: (Boolean) -> Unit,
    scope: PreviewScope,
    modifier: Modifier = Modifier,
    screen: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier) {
        val widthScale = if (constraints.hasBoundedWidth) maxWidth / DESIGN_WIDTH else 1f
        val heightScale = if (constraints.hasBoundedHeight) (maxHeight - CHROME_HEIGHT) / DESIGN_HEIGHT else Float.MAX_VALUE
        val scale = minOf(widthScale, heightScale, MAX_PHONE_HEIGHT / DESIGN_HEIGHT).coerceAtLeast(0.25f)
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PreviewTabRow(tab, onTabChange, Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EditingHint(scope.highlighted, Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                ModeToggle(dark, onDarkChange)
            }
            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier.size(DESIGN_WIDTH * scale, DESIGN_HEIGHT * scale),
                contentAlignment = Alignment.Center,
            ) {
                PhoneBody(
                    scope = scope,
                    modifier = Modifier
                        .requiredSize(DESIGN_WIDTH, DESIGN_HEIGHT)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        },
                    screen = screen,
                )
            }
            Spacer(Modifier.height(8.dp))
            NoteLine(state, Modifier.fillMaxWidth())
        }
    }
}

/** The scrollable row of screen tabs. */
@Composable
private fun PreviewTabRow(selected: PreviewTab, onSelect: (PreviewTab) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (entry in PreviewTab.entries) {
            val isSelected = entry == selected
            Box(
                modifier = Modifier
                    // 48 dp touch target around the slimmer pill.
                    .minimumInteractiveComponentSize()
                    .clip(CircleShape)
                    .background(if (isSelected) colors.primary.copy(alpha = 0.16f) else Color.Transparent)
                    .border(1.dp, if (isSelected) colors.primary else colors.outlineVariant, CircleShape)
                    .selectable(selected = isSelected, role = Role.Tab) { onSelect(entry) }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            ) {
                Text(
                    entry.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) colors.primary else colors.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/** The Light / Dark segmented switch. */
@Composable
private fun ModeToggle(dark: Boolean, onDarkChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .clip(CircleShape)
            .border(1.dp, colors.outlineVariant, CircleShape)
            .padding(3.dp)
            .selectableGroup(),
    ) {
        for ((label, value) in listOf("Light" to false, "Dark" to true)) {
            val isSelected = dark == value
            Box(
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .clip(CircleShape)
                    .background(if (isSelected) colors.primary else Color.Transparent)
                    .selectable(selected = isSelected, role = Role.RadioButton) { onDarkChange(value) }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) colors.onPrimary else colors.onSurfaceVariant,
                )
            }
        }
    }
}

/** "Editing: <group>" with a cyan dot, or the tap hint. */
@Composable
private fun EditingHint(group: ColorGroup?, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (group != null) HighlightCyan else colors.outline),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (group != null) "Editing: ${group.label}" else "Tap any element to edit its colour",
            style = MaterialTheme.typography.labelMedium,
            color = if (group != null) colors.onSurface else colors.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Icon loading progress and the state's note. */
@Composable
private fun NoteLine(state: StudioPreviewState, modifier: Modifier = Modifier) {
    val note = state.note
    val loading = state.iconsLoading
    val fallbackNote = if (!state.hasScan && note == null) "Scan your phone to preview its real icons" else null
    val text = when {
        loading && note != null -> "Rendering icons · $note"
        loading -> "Rendering icons…"
        note != null -> note
        else -> fallbackNote
    } ?: return
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier.padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                color = colors.primary,
                strokeWidth = 1.5.dp,
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The bezel, the screen, the camera hole, status bar and gesture bar, at design size. */
@Composable
private fun PhoneBody(scope: PreviewScope, modifier: Modifier = Modifier, screen: @Composable () -> Unit) {
    val bodyShape = RoundedCornerShape(36.dp)
    val screenShape = RoundedCornerShape(30.dp)
    Box(
        modifier = modifier
            .shadow(14.dp, bodyShape)
            .clip(bodyShape)
            .background(BezelColor)
            .border(1.5.dp, BezelEdge, bodyShape)
            .padding(6.dp),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .clip(screenShape)
                .background(scope[ColorGroup.BACKGROUND]),
        ) {
            StatusBar(scope)
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                screen()
            }
            GestureBar(scope)
        }
        // Camera hole.
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = 8.dp)
                .size(10.dp)
                .clip(CircleShape)
                .background(Color(0xFF020306)),
        )
    }
}

/** 09:41, signal, Wi-Fi and battery on [ColorGroup.SYSTEM_BARS]. */
@Composable
private fun StatusBar(scope: PreviewScope) {
    val bar = scope[ColorGroup.SYSTEM_BARS]
    val ink = contentColorFor(bar)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(26.dp)
            .fill(ColorGroup.SYSTEM_BARS, scope)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PText("09:41", ink, scope, size = 11.sp, weight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        GlyphIcon(Glyph.SIGNAL, ink, Modifier.size(width = 12.dp, height = 9.dp))
        Spacer(Modifier.width(4.dp))
        GlyphIcon(Glyph.WIFI, ink, Modifier.size(12.dp))
        Spacer(Modifier.width(4.dp))
        GlyphIcon(Glyph.BATTERY, ink, Modifier.size(width = 18.dp, height = 10.dp))
        Spacer(Modifier.width(3.dp))
        PText("100%", ink, scope, size = 10.sp, weight = FontWeight.Medium)
    }
}

/** The navigation gesture handle on [ColorGroup.SYSTEM_BARS]. */
@Composable
private fun GestureBar(scope: PreviewScope) {
    val bar = scope[ColorGroup.SYSTEM_BARS]
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(18.dp)
            .fill(ColorGroup.SYSTEM_BARS, scope),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(width = 92.dp, height = 4.dp)
                .clip(CircleShape)
                .background(contentColorFor(bar).copy(alpha = 0.7f)),
        )
    }
}
