package com.hexnext.themer.ui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hexnext.themer.ui.studio.StudioPreviewState

/** One UI 9's own power menu colours: the theme does not reach them (device lab, lab-002). */
private val PowerOffGrey = Color(0xFF6B7079)
private val RestartGreen = Color(0xFF1DB954)
private val EmergencyRed = Color(0xFFE5302C)
private val SidePill = Color(0xFF2A2A2D)
private val Backdrop = listOf(Color(0xFF221733), Color(0xFF3A1D26), Color(0xFF4A2219))

/**
 * The One UI 9 power menu as the phone shows it: the blurred wallpaper, the lock note at the top,
 * a 2 × 2 grid of large round buttons (Power off grey, Restart green, Emergency call and Medical
 * info red) with white labels, and the Side button settings pill at the bottom. One UI keeps its
 * own colours here whatever the theme, so nothing in it is tappable for editing.
 */
@Composable
internal fun PreviewPowerMenu(
    @Suppress("UNUSED_PARAMETER") state: StudioPreviewState,
    scope: PreviewScope,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(Backdrop)),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Filled.Lock, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
            Spacer(Modifier.height(6.dp))
            PText("For your security, your phone is locked.", Color.White, scope, size = 9.5.sp, weight = FontWeight.Medium, align = TextAlign.Center)
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(36.dp)) {
                PowerButton("Power off", PowerOffGrey, scope) { GlyphIcon(Glyph.POWER, Color.White, Modifier.size(24.dp)) }
                PowerButton("Restart", RestartGreen, scope) { Icon(Icons.Filled.Refresh, contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp)) }
            }
            Spacer(Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(36.dp)) {
                PowerButton("Emergency call", EmergencyRed, scope) { IconWithBadge(Icons.Filled.Call, "SOS") }
                PowerButton("Medical info", EmergencyRed, scope) { Icon(Icons.Filled.Favorite, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp)) }
            }
            Spacer(Modifier.weight(1.4f))
            Box(
                Modifier
                    .clip(CircleShape)
                    .background(SidePill)
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            ) {
                PText("Side button settings", Color.White, scope, size = 11.sp, weight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun PowerButton(label: String, color: Color, scope: PreviewScope, icon: @Composable () -> Unit) {
    Column(Modifier.width(92.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) { icon() }
        Spacer(Modifier.height(5.dp))
        PText(label, Color.White, scope, size = 11.sp, weight = FontWeight.Medium, align = TextAlign.Center)
    }
}

@Composable
private fun IconWithBadge(icon: ImageVector, badge: String) {
    Box(Modifier.size(28.dp)) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp).align(Alignment.BottomStart))
        androidx.compose.material3.Text(
            badge,
            color = Color.White,
            fontSize = 7.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.TopEnd),
        )
    }
}
