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
import androidx.compose.ui.platform.LocalDensity
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
 * A stand-in for the blurred wallpaper One UI 9 shows behind the pulled-down panel (the app
 * cannot read the real wallpaper): muted, so the panel colours on top read as they do on the
 * phone. Day and night share it, as the phone's blur does.
 */
internal val BlurredWallpaper: List<Color> = listOf(Color(0xFF69717C), Color(0xFF4C535D), Color(0xFF786A60))

/** The colours behind the Quick panel ([BlurredWallpaper]); kept for the other System UI screens. */
internal fun wallpaperColors(@Suppress("UNUSED_PARAMETER") scope: PreviewScope): List<Color> = BlurredWallpaper

/**
 * The Quick panel backdrop: the blurred wallpaper under `sec_panel_background_color`, the colour
 * the theme really writes (Samsung's see-through alpha kept), else [ColorGroup.QS_PANEL_BG] at the
 * panel opacity before a scan. Returns, through [content], the approximate colour the panel shows,
 * so text can pick a readable colour.
 */
@Composable
internal fun QsBackdrop(
    state: StudioPreviewState,
    scope: PreviewScope,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.(panel: Color) -> Unit,
) {
    val gradient = BlurredWallpaper
    val alpha = state.qsPanelAlpha.coerceIn(0f, 1f)
    val panelColor = scope.ui("sec_panel_background_color", scope[ColorGroup.QS_PANEL_BG].copy(alpha = alpha * 0.4f))
    val effective = panelColor.compositeOver(gradient[1])
    Box(modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.linearGradient(gradient)),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(panelColor)
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
private fun containerColor(panel: Color, scope: PreviewScope): Color =
    scope.ui("qs_tile_container_bg", Color.White.copy(alpha = if (scope.dark) 0.09f else 0.42f)).compositeOver(panel)

/**
 * One UI 9 Quick panel in its default block order: status row, clock header, media pill, volume
 * slider, the 4 × 4 tile card, brightness slider, then the Bluetooth and Wi-Fi pills. The design
 * screen is 300 dp wide; blocks share the height by weight so nothing clips.
 */
@Composable
internal fun PreviewQuickPanel(state: StudioPreviewState, scope: PreviewScope, modifier: Modifier = Modifier) {
    QsBackdrop(state, scope, modifier) { panel ->
        val ink = scope.ui("shade_header_clock_color", scope.text(panel, ColorGroup.QS_TEXT))
        val container = containerColor(panel, scope)
        // One UI 9 keeps its own tile, card and slider shapes in this panel (device lab, lab-002):
        // the theme's shape sizes reach the notification shade only, so they are not drawn here.
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            QsStatusRow(ink, scope)
            QsHeader(ink, scope)
            MediaPill(panel, scope, Modifier.weight(1f))
            QsSlider(Glyph.NOTE, 0.30f, "21", scope, Modifier.weight(1f))
            TileCard(container, scope, Modifier.weight(5.2f))
            QsSlider(Glyph.SUN, 0.33f, "33", scope, Modifier.weight(1f))
            Row(
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ConnectionPill("Bluetooth", null, Glyph.BLUETOOTH, true, container, scope, Modifier.weight(1f))
                ConnectionPill("Wi-Fi", null, Glyph.WIFI, false, container, scope, Modifier.weight(1f))
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
    val card = scope.ui("sec_qs_media_player_background_color", scope[ColorGroup.NOTIFICATION_BG].copy(alpha = 0.88f)).compositeOver(panel)
    val ink = readableOn(scope.ui("media_primary_text", contentColorFor(card)), card)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .fill(ColorGroup.NOTIFICATION_BG, scope, CircleShape, color = card)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphIcon(Glyph.NOTE, ink.copy(alpha = 0.85f), Modifier.size(14.dp))
        Spacer(Modifier.width(8.dp))
        PText("Play music", ink, scope, size = 12.sp, weight = FontWeight.Medium)
        Spacer(Modifier.weight(1f))
        GlyphIcon(Glyph.SPEAKER, ink.copy(alpha = 0.85f), Modifier.size(18.dp))
    }
}

/**
 * A thick One UI 9 slider pill: a light see-through track (One UI's own; the theme sets no track
 * colour here), the filled part in `soundcraft_volume_progress_color_control` (the colour the
 * phone fills both panel sliders with), the glyph in `animated_brightness_sun_icon_color` and the
 * level at the end. Track and fill stay tappable as the tile groups they follow in the Studio.
 */
@Composable
private fun QsSlider(glyph: Glyph, fraction: Float, level: String, scope: PreviewScope, modifier: Modifier = Modifier) {
    val track = Color.White.copy(alpha = 0.72f).compositeOver(BlurredWallpaper[1])
    val fill = scope.ui("soundcraft_volume_progress_color_control", scope[ColorGroup.QS_TILE_ACTIVE])
    val glyphColor = readableOn(scope.ui("animated_brightness_sun_icon_color", scope[ColorGroup.QS_TILE_ICON_ON]), fill)
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .fill(ColorGroup.QS_TILE_INACTIVE, scope, CircleShape, color = track),
        contentAlignment = Alignment.CenterStart,
    ) {
        val knob = maxHeight
        val filled = maxOf(maxWidth * fraction.coerceIn(0f, 1f), knob)
        PText(
            level,
            contentColorFor(track),
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
                .fill(ColorGroup.QS_TILE_ACTIVE, scope, CircleShape, color = fill),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(Modifier.size(knob), contentAlignment = Alignment.Center) {
                GlyphIcon(glyph, glyphColor, Modifier.size(16.dp).themed(ColorGroup.QS_TILE_ICON_ON, scope, CircleShape))
            }
        }
    }
}

/**
 * The rounded card with the 4 × 4 label-less round tiles and the drag handle. Its corners follow
 * the theme's panel corner, the tiles' the theme's tile corner ([QsShapes]).
 */
@Composable
private fun TileCard(container: Color, scope: PreviewScope, modifier: Modifier = Modifier) {
    val corner = DEFAULT_PANEL_CORNER_DP
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
                    RoundTile(tile, scope, Modifier.weight(1f).fillMaxHeight())
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
 * A label-less round tile, as the phone draws it: on, a `qs_tile_round_background_on` circle with
 * a `qs_tile_icon_on_dim_tint_color` glyph; off, the faint `qs_tile_round_background_off` circle
 * with a `qs_tile_icon_off_tint_color` glyph. Always round: One UI 9 ignores tile corner sizes.
 */
@Composable
private fun RoundTile(tile: QsTile, scope: PreviewScope, modifier: Modifier = Modifier) {
    val group = if (tile.active) ColorGroup.QS_TILE_ACTIVE else ColorGroup.QS_TILE_INACTIVE
    val iconGroup = if (tile.active) ColorGroup.QS_TILE_ICON_ON else ColorGroup.QS_TILE_ICON_OFF
    val fill = qsTileFill(tile.active, scope)
    val glyphColor = qsTileGlyph(tile.active, scope)
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val diameter = minOf(maxWidth, maxHeight) * 0.84f
        Box(
            Modifier
                .size(diameter)
                .fill(group, scope, CircleShape, color = fill),
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

/** The fill of a round Quick panel tile, on or off, as System UI paints it. */
internal fun qsTileFill(active: Boolean, scope: PreviewScope): Color =
    if (active) scope.ui("qs_tile_round_background_on", scope[ColorGroup.QS_TILE_ACTIVE])
    else scope.ui("qs_tile_round_background_off", scope[ColorGroup.QS_TILE_INACTIVE].copy(alpha = 0.25f))

/** The glyph colour of a round Quick panel tile, on or off. */
internal fun qsTileGlyph(active: Boolean, scope: PreviewScope): Color =
    if (active) scope.ui("qs_tile_icon_on_dim_tint_color", scope[ColorGroup.QS_TILE_ICON_ON])
    else scope.ui("qs_tile_icon_off_tint_color", scope[ColorGroup.QS_TILE_ICON_OFF])

/**
 * A wide connection pill (Bluetooth, Wi-Fi) on the `qs_tile_container_bg` card: a large round
 * tile at the start (on or off, as [RoundTile]) and the `qs_tile_label` text beside it.
 */
@Composable
private fun ConnectionPill(
    title: String,
    subtitle: String?,
    glyph: Glyph,
    active: Boolean,
    container: Color,
    scope: PreviewScope,
    modifier: Modifier = Modifier,
) {
    val fill = qsTileFill(active, scope)
    val ink = readableOn(scope.ui("qs_tile_label", scope[ColorGroup.QS_TEXT]), container)
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
                    .fill(if (active) ColorGroup.QS_TILE_ACTIVE else ColorGroup.QS_TILE_INACTIVE, scope, CircleShape, color = fill),
                contentAlignment = Alignment.Center,
            ) {
                GlyphIcon(
                    glyph,
                    qsTileGlyph(active, scope),
                    Modifier.size(circle * 0.44f).themed(if (active) ColorGroup.QS_TILE_ICON_ON else ColorGroup.QS_TILE_ICON_OFF, scope, CircleShape),
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
