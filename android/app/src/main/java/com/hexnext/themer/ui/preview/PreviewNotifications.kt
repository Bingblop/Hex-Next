package com.hexnext.themer.ui.preview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hexnext.engine.compile.ColorGroup
import com.hexnext.themer.ui.studio.QsShapes
import com.hexnext.themer.ui.studio.StudioPreviewState

/** One UI 9's own notification card corner and shade brightness bar height, in preview dp. */
private const val DEFAULT_NOTIFICATION_CORNER_DP: Float = 22f
private const val DEFAULT_SHADE_SLIDER_DP: Float = 22f

private class ShadeNotification(val title: String, val time: String?, val text: String, val count: Int?, val glyph: Glyph?, val icon: ImageVector?)

private val SampleNotifications = listOf(
    ShadeNotification("Mina", "4:13 PM", "Are we still on for dinner at eight?", 2, null, Icons.Filled.Call),
    ShadeNotification("Daily goal reached", "4:08 PM", "Samsung Health", 7, Glyph.BOLT, null),
    ShadeNotification("New memories", "3:35 PM", "Gallery", 6, Glyph.EYE, null),
    ShadeNotification("Software update", null, "Ready to install", null, Glyph.DATA, null),
)

/** The first row of the panel as the shade shows it: six round tiles, Bluetooth on. */
private val ShadeTiles = listOf(
    Glyph.DATA to false, Glyph.BOLT to false, Glyph.SUN to false, Glyph.NFC to false, Glyph.BLUETOOTH to true, Glyph.WIFI to false,
)

/**
 * The One UI 9 notification shade, block by block as the phone draws it: status row, clock and
 * date with the settings gear, a row of six round tiles, the slim brightness bar with its menu
 * button, the Device control and Media output pills, the notification cards, then the
 * Notification settings and Clear pills. Colours are the resources the theme writes
 * (`qp_notification_*`, `shade_header_*`, the tile colours); see [PreviewScope.ui].
 */
@Composable
internal fun PreviewNotifications(state: StudioPreviewState, scope: PreviewScope, modifier: Modifier = Modifier) {
    QsBackdrop(state, scope, modifier) { panel ->
        val ink = scope.ui("shade_header_clock_color", scope.text(panel, ColorGroup.QS_TEXT))
        val container = scope.ui("qs_tile_container_bg", Color.White.copy(alpha = if (scope.dark) 0.09f else 0.42f)).compositeOver(panel)
        val density = LocalDensity.current
        val corner = QsShapes.previewDp(state.qsShapes.notificationCorner, DEFAULT_NOTIFICATION_CORNER_DP, density) ?: DEFAULT_NOTIFICATION_CORNER_DP
        val slider = QsShapes.previewDp(state.qsShapes.sliderThickness, DEFAULT_SHADE_SLIDER_DP, density) ?: DEFAULT_SHADE_SLIDER_DP
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(Modifier.fillMaxWidth().themed(ColorGroup.QS_TEXT, scope), verticalAlignment = Alignment.CenterVertically) {
                PText("T-Mobile", ink.copy(alpha = 0.85f), scope, Modifier.weight(1f), size = 10.sp, weight = FontWeight.Medium)
                GlyphIcon(Glyph.WIFI, ink.copy(alpha = 0.85f), Modifier.size(11.dp))
                Spacer(Modifier.width(4.dp))
                GlyphIcon(Glyph.SIGNAL, ink.copy(alpha = 0.85f), Modifier.size(width = 11.dp, height = 8.dp))
                Spacer(Modifier.width(4.dp))
                GlyphIcon(Glyph.BATTERY, ink.copy(alpha = 0.85f), Modifier.size(width = 16.dp, height = 9.dp))
            }
            Row(Modifier.fillMaxWidth().themed(ColorGroup.QS_TEXT, scope), verticalAlignment = Alignment.CenterVertically) {
                PText("4:13:29", ink, scope, Modifier.alignByBaseline(), size = 22.sp)
                Spacer(Modifier.width(6.dp))
                PText("Tue, Nov 3", scope.ui("shade_header_text_color", ink).copy(alpha = 0.85f), scope, Modifier.alignByBaseline().weight(1f), size = 11.sp)
                Icon(Icons.Filled.Settings, contentDescription = null, tint = scope.ui("sec_qs_header_tint_color", ink), modifier = Modifier.size(17.dp))
            }
            Row(Modifier.fillMaxWidth().height(36.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                for ((glyph, on) in ShadeTiles) {
                    val fill = qsTileFill(on, scope)
                    Box(
                        Modifier.size(36.dp).fill(if (on) ColorGroup.QS_TILE_ACTIVE else ColorGroup.QS_TILE_INACTIVE, scope, CircleShape, color = fill),
                        contentAlignment = Alignment.Center,
                    ) {
                        GlyphIcon(glyph, qsTileGlyph(on, scope), Modifier.size(16.dp), cutout = fill)
                    }
                }
            }
            ShadeBrightness(slider, ink, scope)
            Row(Modifier.fillMaxWidth().height(30.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ShadePill("Device control", container, scope, Modifier.weight(1f))
                ShadePill("Media output", container, scope, Modifier.weight(1f))
            }
            Spacer(Modifier.height(2.dp))
            SampleNotifications.forEachIndexed { i, n ->
                // The newest card takes the theme's corner; the stacked older ones keep One UI's
                // rounded shape (as on the phone in lab-002).
                NotificationCard(n, panel, scope, if (i == 0) corner else DEFAULT_NOTIFICATION_CORNER_DP)
            }
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                ShadePill("Notification settings", container, scope, Modifier.height(26.dp), padded = true)
                Spacer(Modifier.weight(1f))
                ShadePill("Clear", container, scope, Modifier.height(26.dp), padded = true)
            }
        }
    }
}

/** The slim shade brightness bar ([height] follows the theme's slider size) and its menu button. */
@Composable
private fun ShadeBrightness(height: Float, ink: Color, scope: PreviewScope) {
    val track = Color.White.copy(alpha = 0.72f).compositeOver(BlurredWallpaper[1])
    val fill = scope.ui("soundcraft_volume_progress_color_control", scope[ColorGroup.QS_TILE_ACTIVE])
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .height(height.coerceIn(6f, 40f).dp)
                .fill(ColorGroup.QS_TILE_INACTIVE, scope, CircleShape, color = track),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                Modifier.width(maxWidth * 0.33f).fillMaxHeight().fill(ColorGroup.QS_TILE_ACTIVE, scope, CircleShape, color = fill),
                contentAlignment = Alignment.CenterStart,
            ) {
                GlyphIcon(
                    Glyph.SUN,
                    readableOn(scope.ui("animated_brightness_sun_icon_color", scope[ColorGroup.QS_TILE_ICON_ON]), fill),
                    Modifier.padding(start = 6.dp).size((height * 0.6f).coerceIn(6f, 14f).dp),
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        Icon(Icons.Filled.MoreVert, contentDescription = null, tint = ink, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun ShadePill(label: String, container: Color, scope: PreviewScope, modifier: Modifier = Modifier, padded: Boolean = false) {
    val ink = readableOn(scope.ui("qs_tile_label", scope[ColorGroup.QS_TEXT]), container)
    Box(
        modifier
            .then(if (padded) Modifier else Modifier.fillMaxHeight())
            .fill(ColorGroup.QS_PANEL_BG, scope, CircleShape, color = container)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        PText(label, ink, scope, size = 11.sp, weight = FontWeight.Medium)
    }
}

/**
 * A notification card in `qp_notification_background_color` (see-through over the panel as
 * System UI draws it): a round app icon, the `qp_notification_title_color` title with the time,
 * the `qp_notification_content_color` text and the stacked count.
 */
@Composable
private fun NotificationCard(n: ShadeNotification, panel: Color, scope: PreviewScope, corner: Float) {
    val card = scope.ui("qp_notification_background_color", scope[ColorGroup.NOTIFICATION_BG]).copy(alpha = 0.82f).compositeOver(panel)
    val title = readableOn(scope.ui("qp_notification_title_color", scope[ColorGroup.TEXT_PRIMARY]), card)
    val content = readableOn(scope.ui("qp_notification_content_color", scope[ColorGroup.TEXT_SECONDARY]), card, 2.6f)
    Row(
        Modifier
            .fillMaxWidth()
            .fill(ColorGroup.NOTIFICATION_BG, scope, RoundedCornerShape(corner.dp), color = card)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val iconBg = content.copy(alpha = 0.22f).compositeOver(card)
        Box(Modifier.size(26.dp).fill(ColorGroup.NOTIFICATION_BG, scope, CircleShape, color = iconBg), contentAlignment = Alignment.Center) {
            val icon = n.icon
            if (icon != null) Icon(icon, contentDescription = null, tint = title, modifier = Modifier.size(14.dp))
            else n.glyph?.let { GlyphIcon(it, title, Modifier.size(13.dp), cutout = iconBg) }
        }
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PText(n.title, title, scope, Modifier.weight(1f, fill = false), size = 11.5.sp, weight = FontWeight.Medium)
                n.time?.let {
                    Spacer(Modifier.width(5.dp))
                    PText(it, content, scope, size = 9.sp)
                }
            }
            PText(n.text, content, scope, size = 10.sp)
        }
        n.count?.let {
            Spacer(Modifier.width(6.dp))
            PText(it.toString(), content, scope, size = 11.sp, weight = FontWeight.Medium)
        }
    }
}
