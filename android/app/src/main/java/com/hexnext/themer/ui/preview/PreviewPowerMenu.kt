package com.hexnext.themer.ui.preview

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hexnext.engine.compile.ColorGroup
import com.hexnext.themer.ui.studio.StudioPreviewState

/** The lock screen with its clock in [ColorGroup.LOCK_SCREEN], dimmed by the power menu dialog. */
@Composable
internal fun PreviewPowerMenu(state: StudioPreviewState, scope: PreviewScope, modifier: Modifier = Modifier) {
    val wallpaper = state.background
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
                    .background(Brush.verticalGradient(wallpaperColors(scope))),
            )
        }
        LockClock(scope, Modifier.align(Alignment.TopCenter).padding(top = 34.dp))
        // Scrim: dims the lock screen behind the dialog.
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.38f)),
        )
        PowerSheet(scope, Modifier.align(Alignment.BottomCenter).padding(10.dp))
    }
}

@Composable
private fun LockClock(scope: PreviewScope, modifier: Modifier = Modifier) {
    val clock = scope[ColorGroup.LOCK_SCREEN]
    Column(
        modifier = modifier.themed(ColorGroup.LOCK_SCREEN, scope, RoundedCornerShape(16.dp)).padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Filled.Lock, contentDescription = null, tint = clock, modifier = Modifier.size(16.dp))
        Spacer(Modifier.height(6.dp))
        PText("09", clock, scope, size = 64.sp, weight = FontWeight.Light, align = TextAlign.Center)
        PText("41", clock, scope, size = 64.sp, weight = FontWeight.Light, align = TextAlign.Center)
        PText("Mon, 2 November", clock.copy(alpha = 0.9f), scope, size = 12.sp, weight = FontWeight.Medium)
    }
}

@Composable
private fun PowerSheet(scope: PreviewScope, modifier: Modifier = Modifier) {
    val dialog = scope[ColorGroup.DIALOG]
    Column(
        modifier = modifier
            .fillMaxWidth()
            .fill(ColorGroup.DIALOG, scope, RoundedCornerShape(28.dp))
            .padding(horizontal = 16.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            PowerButton("Power off", dialog, scope) { tint -> GlyphIcon(Glyph.POWER, tint, Modifier.size(24.dp)) }
            PowerButton("Restart", dialog, scope) { tint ->
                Icon(Icons.Filled.Refresh, contentDescription = null, tint = tint, modifier = Modifier.size(26.dp))
            }
        }
        EmergencyButton(dialog, scope)
        GroupText("Side button settings", ColorGroup.ACCENT, dialog, scope, size = 11.sp, weight = FontWeight.Medium)
    }
}

@Composable
private fun PowerButton(
    label: String,
    dialog: Color,
    scope: PreviewScope,
    modifier: Modifier = Modifier,
    glyph: @Composable (Color) -> Unit,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(64.dp)
                .fill(ColorGroup.BUTTON, scope, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            glyph(scope.on(ColorGroup.BUTTON))
        }
        Spacer(Modifier.height(6.dp))
        GroupText(label, ColorGroup.TEXT_PRIMARY, dialog, scope, size = 11.5.sp, weight = FontWeight.Medium)
    }
}

/** A tonal pill in [ColorGroup.BUTTON]: faint fill, button-coloured text. */
@Composable
private fun EmergencyButton(dialog: Color, scope: PreviewScope) {
    val button = scope[ColorGroup.BUTTON]
    Row(
        Modifier
            .fillMaxWidth()
            .height(40.dp)
            .fill(ColorGroup.BUTTON, scope, CircleShape, button.copy(alpha = 0.16f)),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val ink = readableOn(button, dialog)
        Icon(Icons.Filled.Call, contentDescription = null, tint = ink, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        PText("Emergency call", ink, scope, size = 12.sp, weight = FontWeight.SemiBold)
    }
}
