package com.hexnext.themer.ui.preview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hexnext.engine.compile.ColorGroup
import com.hexnext.themer.ui.studio.StudioPreviewState

/** The colours behind the Quick panel when no wallpaper is set: a soft gradient of the palette. */
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
 * The Quick panel backdrop: the wallpaper (or a gradient) under [ColorGroup.QS_PANEL_BG] at the
 * panel opacity. Returns, through [content], the approximate colour the panel shows, so text can
 * pick a readable colour.
 */
@Composable
internal fun QsBackdrop(
    state: StudioPreviewState,
    scope: PreviewScope,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.(panel: Color) -> Unit,
) {
    val wallpaper = state.background
    val gradient = wallpaperColors(scope)
    val alpha = state.qsPanelAlpha.coerceIn(0f, 1f)
    val panelColor = scope[ColorGroup.QS_PANEL_BG]
    val behind = if (wallpaper != null) Color(0xFF5C6070) else gradient[1]
    val effective = panelColor.copy(alpha = alpha).compositeOver(behind)
    Box(modifier.fillMaxSize()) {
        if (wallpaper != null) {
            Image(
                bitmap = wallpaper,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.linearGradient(gradient)),
            )
        }
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

/** One UI Quick panel: header, two wide tiles, a 4 × 2 round tile grid, sliders, a media card. */
@Composable
internal fun PreviewQuickPanel(state: StudioPreviewState, scope: PreviewScope, modifier: Modifier = Modifier) {
    QsBackdrop(state, scope, modifier) { panel ->
        val ink = contentColorFor(panel)
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    PText("09:41", ink, scope, size = 22.sp, weight = FontWeight.Medium)
                    PText("Mon, 2 November", ink.copy(alpha = 0.8f), scope, size = 11.sp)
                }
                Icon(Icons.Filled.Edit, contentDescription = null, tint = ink, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(14.dp))
                Icon(Icons.Filled.Settings, contentDescription = null, tint = ink, modifier = Modifier.size(18.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WideTile("Wi-Fi", "Home 5G", Glyph.WIFI, scope, Modifier.weight(1f))
                WideTile("Bluetooth", "Buds connected", Glyph.BLUETOOTH, scope, Modifier.weight(1f))
            }
            TileGrid(ink, scope)
            QsSlider(Glyph.SUN, 0.72f, scope)
            QsSlider(Glyph.VOLUME, 0.46f, scope)
            MediaCard(scope)
        }
    }
}

@Composable
private fun WideTile(title: String, subtitle: String, glyph: Glyph, scope: PreviewScope, modifier: Modifier = Modifier) {
    val tile = scope[ColorGroup.QS_TILE_ACTIVE]
    val ink = contentColorFor(tile)
    Row(
        modifier = modifier
            .height(56.dp)
            .fill(ColorGroup.QS_TILE_ACTIVE, scope, RoundedCornerShape(22.dp))
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(ink.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            GlyphIcon(glyph, ink, Modifier.size(18.dp))
        }
        Spacer(Modifier.width(8.dp))
        Column {
            PText(title, ink, scope, size = 12.sp, weight = FontWeight.SemiBold)
            PText(subtitle, ink.copy(alpha = 0.78f), scope, size = 10.sp)
        }
    }
}

@Composable
private fun TileGrid(ink: Color, scope: PreviewScope) {
    val tiles = listOf(
        QsTile("Sound", active = true, icon = Icons.Filled.Notifications),
        QsTile("Location", active = false, icon = Icons.Filled.LocationOn),
        QsTile("Auto rotate", active = true, icon = Icons.Filled.Refresh),
        QsTile("Dark mode", active = scope.dark, glyph = Glyph.MOON),
        QsTile("Do not disturb", active = false, glyph = Glyph.DND),
        QsTile("Power saving", active = false, glyph = Glyph.BOLT),
        QsTile("Quick Share", active = true, icon = Icons.Filled.Share),
        QsTile("Secure Folder", active = false, icon = Icons.Filled.Lock),
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in tiles.chunked(4)) {
            Row(Modifier.fillMaxWidth()) {
                for (tile in row) {
                    RoundTile(tile, ink, scope, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun RoundTile(tile: QsTile, ink: Color, scope: PreviewScope, modifier: Modifier = Modifier) {
    val group = if (tile.active) ColorGroup.QS_TILE_ACTIVE else ColorGroup.QS_TILE_INACTIVE
    val fill = scope[group]
    val glyphColor = contentColorFor(fill)
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(46.dp)
                .fill(group, scope, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            val icon = tile.icon
            val glyph = tile.glyph
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = glyphColor, modifier = Modifier.size(20.dp))
            } else if (glyph != null) {
                GlyphIcon(glyph, glyphColor, Modifier.size(20.dp), cutout = fill)
            }
        }
        Spacer(Modifier.height(4.dp))
        PText(tile.label, ink, scope, size = 9.sp, align = TextAlign.Center)
    }
}

/** A thick One UI slider: inactive track, [ColorGroup.SLIDER] fill, glyph at the start. */
@Composable
private fun QsSlider(glyph: Glyph, fraction: Float, scope: PreviewScope) {
    val track = scope[ColorGroup.QS_TILE_INACTIVE]
    val fill = scope[ColorGroup.SLIDER]
    val thumb = contentColorFor(fill)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(30.dp)
            .themed(ColorGroup.SLIDER, scope, CircleShape),
        contentAlignment = Alignment.CenterStart,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val radius = CornerRadius(size.height / 2f)
            drawRoundRect(track, cornerRadius = radius)
            val filled = (size.width * fraction).coerceAtLeast(size.height)
            drawRoundRect(fill, size = Size(filled, size.height), cornerRadius = radius)
            drawCircle(thumb.copy(alpha = 0.9f), radius = size.height * 0.22f, center = Offset(filled - size.height / 2f, size.height / 2f))
        }
        GlyphIcon(glyph, thumb, Modifier.padding(start = 9.dp).size(14.dp))
    }
}

@Composable
private fun MediaCard(scope: PreviewScope) {
    val card = scope[ColorGroup.NOTIFICATION_BG]
    val art = wallpaperColors(scope)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .fill(ColorGroup.NOTIFICATION_BG, scope, RoundedCornerShape(22.dp))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Brush.linearGradient(art)),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            GroupText("Night Signals", ColorGroup.TEXT_PRIMARY, card, scope, size = 12.sp, weight = FontWeight.SemiBold)
            GroupText("Aurora Lane", ColorGroup.TEXT_SECONDARY, card, scope, size = 10.sp)
            Spacer(Modifier.height(4.dp))
            ProgressBar(
                fraction = 0.38f,
                group = ColorGroup.SLIDER,
                track = scope.text(card, ColorGroup.TEXT_SECONDARY).copy(alpha = 0.25f),
                scope = scope,
                modifier = Modifier.fillMaxWidth(),
                height = 4.dp,
            )
        }
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier
                .size(34.dp)
                .fill(ColorGroup.ACCENT, scope, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = scope.on(ColorGroup.ACCENT), modifier = Modifier.size(20.dp))
        }
    }
}
