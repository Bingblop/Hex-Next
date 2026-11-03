package com.hexnext.themer.ui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hexnext.engine.compile.ColorGroup
import com.hexnext.engine.compile.ValueSetting
import com.hexnext.themer.ui.studio.QsShapes
import com.hexnext.themer.ui.studio.StudioPreviewState

/**
 * The colours behind the Quick panel: a soft gradient of the palette, standing in for whatever the
 * phone shows under the pulled-down panel (the app cannot know the real wallpaper).
 */
internal fun wallpaperColors(scope: PreviewScope): List<Color> {
    val p = scope.palette
    val accent = scope[ColorGroup.ACCENT]
    return if (scope.dark) {
        listOf(p.shade("accent1_700", accent), p.shade("accent3_600", accent), p.shade("accent2_800", accent))
    } else {
        listOf(p.shade("accent1_300", accent), p.shade("accent3_200", accent), p.shade("accent2_400", accent))
    }
}

/**
 * The Quick panel backdrop: a sample wallpaper gradient under [ColorGroup.QS_PANEL_BG] at the
 * panel opacity. Custom Settings backgrounds never show here: they do not reach System UI.
 * Returns, through [content], the approximate colour the panel shows, so text can pick a readable
 * colour.
 */
@Composable
internal fun QsBackdrop(
    state: StudioPreviewState,
    scope: PreviewScope,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.(panel: Color) -> Unit,
) {
    val gradient = wallpaperColors(scope)
    val alpha = state.qsPanelAlpha.coerceIn(0f, 1f)
    val panelColor = scope[ColorGroup.QS_PANEL_BG]
    val effective = panelColor.copy(alpha = alpha).compositeOver(gradient[1])
    Box(modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.linearGradient(gradient)),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(panelColor.copy(alpha = alpha))
                .themed(ColorGroup.QS_PANEL_BG, scope),
        )
        content(effective)
    }
}

private class QsTile(val label: String, val active: Boolean, val icon: ImageVector? = null, val glyph: Glyph? = null)

/**
 * The 16 round tiles of the default One UI 9 layout, row by row. Row 1 is on (mobile data,
 * sound, location, Dolby Atmos), the rest are off, as on the owner's phone.
 */
private val QsTiles: List<QsTile> = listOf(
    QsTile("Mobile data", active = true, glyph = Glyph.DATA),
    QsTile("Sound", active = true, glyph = Glyph.VOLUME),
    QsTile("Location", active = true, icon = Icons.Filled.LocationOn),
    QsTile("Dolby Atmos", active = true, glyph = Glyph.DOLBY),
    QsTile("Eye comfort shield", active = false, glyph = Glyph.EYE),
    QsTile("Airplane mode", active = false, glyph = Glyph.PLANE),
    QsTile("Dark mode", active = false, glyph = Glyph.MOON),
    QsTile("Auto rotate", active = false, icon = Icons.Filled.Refresh),
    QsTile("Smart View", active = false, glyph = Glyph.CAST),
    QsTile("Screen recorder", active = false, glyph = Glyph.RECORD),
    QsTile("NFC", active = false, glyph = Glyph.NFC),
    QsTile("Power saving", active = false, glyph = Glyph.BOLT),
    QsTile("Wi-Fi calling", active = false, icon = Icons.Filled.Call),
    QsTile("Secure Folder", active = false, icon = Icons.Filled.Lock),
    QsTile("Flashlight", active = false, glyph = Glyph.TORCH),
    QsTile("Do not disturb", active = false, glyph = Glyph.DND),
)

/**
 * The translucent card One UI 9 lays the tile grid and the Bluetooth / Wi-Fi pills on. On One UI 9
 * its SemWT colour is `qs_tile_container_bg`, which the engine puts in [ColorGroup.QS_PANEL_BG]
 * (Samsung's alpha kept, scaled by the panel opacity), so it follows that group and taps edit it.
 * The light veil only keeps the card visible against the panel drawn in the same group colour;
 * on the phone the panel behind it is System UI's blur.
 */
private fun containerColor(panel: Color, dark: Boolean): Color =
    Color.White.copy(alpha = if (dark) 0.09f else 0.42f).compositeOver(panel)

/**
 * One UI 9 Quick panel in its default block order: status row, clock header, media pill, volume
 * slider, the 4 × 4 tile card, brightness slider, then the Bluetooth and Wi-Fi pills. The design
 * screen is 300 dp wide; blocks share the height by weight so nothing clips.
 */
@Composable
internal fun PreviewQuickPanel(state: StudioPreviewState, scope: PreviewScope, modifier: Modifier = Modifier) {
    QsBackdrop(state, scope, modifier) { panel ->
        val ink = scope.text(panel, ColorGroup.QS_TEXT)
        val container = containerColor(panel, scope.dark)
        val shapes = state.qsShapes
        // The theme's slider thickness replaces the slider's share of the height.
        val sliderHeight = QsShapes.previewDp(shapes.sliderThickness, DEFAULT_SLIDER_DP)
        fun Modifier.slider(): Modifier = if (sliderHeight != null) this.height(sliderHeight.dp) else this
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            QsStatusRow(ink, scope)
            QsHeader(ink, scope)
            MediaPill(panel, scope, Modifier.weight(1f))
            QsSlider(Glyph.NOTE, 0.30f, "21", scope, if (sliderHeight != null) Modifier.slider() else Modifier.weight(1f))
            TileCard(container, scope, shapes, Modifier.weight(5.2f))
            QsSlider(Glyph.SUN, 0.05f, "5", scope, if (sliderHeight != null) Modifier.slider() else Modifier.weight(1f))
            Row(
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ConnectionPill("Bluetooth", null, Glyph.BLUETOOTH, container, scope, Modifier.weight(1f))
                ConnectionPill("Wi-Fi", "TheParkAveFi", Glyph.WIFI, container, scope, Modifier.weight(1f))
            }
        }
    }
}

/** Carrier on the left, status icons on the right, in [ColorGroup.QS_TEXT]. */
@Composable
private fun QsStatusRow(ink: Color, scope: PreviewScope) {
    Row(Modifier.fillMaxWidth().themed(ColorGroup.QS_TEXT, scope), verticalAlignment = Alignment.CenterVertically) {
        PText("T-Mobile", ink.copy(alpha = 0.85f), scope, Modifier.weight(1f), size = 10.sp, weight = FontWeight.Medium)
        GlyphIcon(Glyph.VOLUME, ink.copy(alpha = 0.85f), Modifier.size(11.dp))
        Spacer(Modifier.width(4.dp))
        GlyphIcon(Glyph.WIFI, ink.copy(alpha = 0.85f), Modifier.size(11.dp))
        Spacer(Modifier.width(4.dp))
        GlyphIcon(Glyph.SIGNAL, ink.copy(alpha = 0.85f), Modifier.size(width = 11.dp, height = 8.dp))
        Spacer(Modifier.width(4.dp))
        GlyphIcon(Glyph.BATTERY, ink.copy(alpha = 0.85f), Modifier.size(width = 16.dp, height = 9.dp))
    }
}

/**
 * Clock with seconds and the date on one baseline; edit, power and settings on the right. All in
 * [ColorGroup.QS_TEXT] (`shade_header_clock_color`, `shade_header_text_color`, `sec_qs_header_tint_color`).
 */
@Composable
private fun QsHeader(ink: Color, scope: PreviewScope) {
    Row(Modifier.fillMaxWidth().themed(ColorGroup.QS_TEXT, scope), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f)) {
            PText("10:04:18", ink, scope, Modifier.alignByBaseline(), size = 24.sp, weight = FontWeight.Normal)
            Spacer(Modifier.width(6.dp))
            PText("Mon, Nov 2", ink.copy(alpha = 0.8f), scope, Modifier.alignByBaseline(), size = 11.sp)
        }
        Icon(Icons.Filled.Edit, contentDescription = null, tint = ink, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(10.dp))
        GlyphIcon(Glyph.POWER, ink, Modifier.size(17.dp))
        Spacer(Modifier.width(10.dp))
        Icon(Icons.Filled.Settings, contentDescription = null, tint = ink, modifier = Modifier.size(17.dp))
    }
}

/**
 * The media pill ("Play music"). One UI 9 draws the panel's media player from the media
 * notification surface, so it follows [ColorGroup.NOTIFICATION_BG], kept slightly see-through
 * over the panel the way System UI shows it.
 */
@Composable
private fun MediaPill(panel: Color, scope: PreviewScope, modifier: Modifier = Modifier) {
    val card = scope[ColorGroup.NOTIFICATION_BG].copy(alpha = 0.88f).compositeOver(panel)
    val ink = contentColorFor(card)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .fill(ColorGroup.NOTIFICATION_BG, scope, CircleShape, color = card)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphIcon(Glyph.NOTE, ink.copy(alpha = 0.85f), Modifier.size(14.dp))
        Spacer(Modifier.width(8.dp))
        GroupText("Play music", ColorGroup.TEXT_PRIMARY, card, scope, size = 12.sp, weight = FontWeight.Medium)
        Spacer(Modifier.weight(1f))
        GlyphIcon(Glyph.SPEAKER, ink.copy(alpha = 0.85f), Modifier.size(18.dp))
    }
}

/**
 * A thick One UI 9 slider pill. One UI 9 draws the panel's brightness and volume sliders in the
 * tile colours (Samsung's own Theme Park sets no other slider colours there): a
 * [ColorGroup.QS_TILE_INACTIVE] track, a rounded [ColorGroup.QS_TILE_ACTIVE] block at the start
 * holding [glyph] in [ColorGroup.QS_TILE_ICON_ON] (`animated_brightness_sun_icon_color`), and the
 * level number at the end of the track. Track and fill are each tappable as their own group.
 */
@Composable
private fun QsSlider(glyph: Glyph, fraction: Float, level: String, scope: PreviewScope, modifier: Modifier = Modifier) {
    val track = scope[ColorGroup.QS_TILE_INACTIVE]
    val fill = scope[ColorGroup.QS_TILE_ACTIVE]
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .fill(ColorGroup.QS_TILE_INACTIVE, scope, CircleShape),
        contentAlignment = Alignment.CenterStart,
    ) {
        val knob = maxHeight
        val filled = maxOf(maxWidth * fraction.coerceIn(0f, 1f), knob)
        PText(
            level,
            readableOn(scope[ColorGroup.QS_TILE_ICON_OFF], track),
            scope,
            Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 16.dp),
            size = 12.sp,
            weight = FontWeight.Medium,
        )
        Box(
            Modifier
                .width(filled)
                .fillMaxHeight()
                .fill(ColorGroup.QS_TILE_ACTIVE, scope, CircleShape),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(Modifier.size(knob), contentAlignment = Alignment.Center) {
                GlyphIcon(glyph, readableOn(scope[ColorGroup.QS_TILE_ICON_ON], fill), Modifier.size(16.dp).themed(ColorGroup.QS_TILE_ICON_ON, scope, CircleShape))
            }
        }
    }
}

/**
 * The rounded card with the 4 × 4 label-less round tiles and the drag handle. Its corners follow
 * the theme's panel corner, the tiles' the theme's tile corner ([QsShapes]).
 */
@Composable
private fun TileCard(container: Color, scope: PreviewScope, shapes: QsShapes, modifier: Modifier = Modifier) {
    val corner = QsShapes.previewDp(shapes.panelCorner, DEFAULT_PANEL_CORNER_DP) ?: DEFAULT_PANEL_CORNER_DP
    Column(
        modifier = modifier
            .fillMaxWidth()
            .fill(ColorGroup.QS_PANEL_BG, scope, RoundedCornerShape(corner.dp), color = container)
            .padding(start = 8.dp, end = 8.dp, top = 10.dp, bottom = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        for (row in QsTiles.chunked(4)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                for (tile in row) {
                    RoundTile(tile, scope, shapes.tileCorner, Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .size(width = 26.dp, height = 4.dp)
                .clip(CircleShape)
                .background(contentColorFor(container).copy(alpha = 0.35f)),
        )
    }
}

/**
 * A label-less round tile: [ColorGroup.QS_TILE_ACTIVE] with a [ColorGroup.QS_TILE_ICON_ON] icon
 * when on, [ColorGroup.QS_TILE_INACTIVE] with a [ColorGroup.QS_TILE_ICON_OFF] icon when off.
 */
@Composable
private fun RoundTile(tile: QsTile, scope: PreviewScope, corner: ValueSetting?, modifier: Modifier = Modifier) {
    val group = if (tile.active) ColorGroup.QS_TILE_ACTIVE else ColorGroup.QS_TILE_INACTIVE
    val iconGroup = if (tile.active) ColorGroup.QS_TILE_ICON_ON else ColorGroup.QS_TILE_ICON_OFF
    val fill = scope[group]
    val glyphColor = scope[iconGroup]
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val diameter = minOf(maxWidth, maxHeight) * 0.84f
        // One UI draws the tiles round; a theme tile corner squares them off (capped at a circle).
        val radius = QsShapes.previewDp(corner, diameter.value / 2f)?.coerceAtMost(diameter.value / 2f)
        val shape = if (radius == null) CircleShape else RoundedCornerShape(radius.dp)
        Box(
            Modifier
                .size(diameter)
                .fill(group, scope, shape),
            contentAlignment = Alignment.Center,
        ) {
            val iconSize = diameter * 0.42f
            val icon = tile.icon
            val glyph = tile.glyph
            val iconModifier = Modifier.size(iconSize).themed(iconGroup, scope, CircleShape)
            if (icon != null) {
                Icon(icon, contentDescription = tile.label, tint = glyphColor, modifier = iconModifier)
            } else if (glyph != null) {
                GlyphIcon(glyph, glyphColor, iconModifier, cutout = fill)
            }
        }
    }
}

/**
 * A wide connection pill (Bluetooth, Wi-Fi): the translucent container with a large
 * [ColorGroup.QS_TILE_ACTIVE] circle at the start holding the glyph in
 * [ColorGroup.QS_TILE_ICON_ON], and the [ColorGroup.QS_TEXT] label beside it.
 */
@Composable
private fun ConnectionPill(
    title: String,
    subtitle: String?,
    glyph: Glyph,
    container: Color,
    scope: PreviewScope,
    modifier: Modifier = Modifier,
) {
    val active = scope[ColorGroup.QS_TILE_ACTIVE]
    val ink = scope.text(container, ColorGroup.QS_TEXT)
    Row(
        modifier = modifier
            .fillMaxHeight()
            .fill(ColorGroup.QS_PANEL_BG, scope, CircleShape, color = container)
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BoxWithConstraints(Modifier.fillMaxHeight(), contentAlignment = Alignment.Center) {
            val circle = maxHeight
            Box(
                Modifier
                    .size(circle)
                    .fill(ColorGroup.QS_TILE_ACTIVE, scope, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                GlyphIcon(
                    glyph,
                    readableOn(scope[ColorGroup.QS_TILE_ICON_ON], active),
                    Modifier.size(circle * 0.44f).themed(ColorGroup.QS_TILE_ICON_ON, scope, CircleShape),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f).themed(ColorGroup.QS_TEXT, scope)) {
            PText(title, ink, scope, size = 11.5.sp, weight = FontWeight.Medium)
            if (subtitle != null) {
                PText(subtitle, ink.copy(alpha = 0.72f), scope, size = 10.sp)
            }
        }
    }
}

/** The mockup's One UI 9 sizes in preview dp, for [QsShapes.previewDp]. */
private const val DEFAULT_PANEL_CORNER_DP = 26f
private const val DEFAULT_SLIDER_DP = 34f
