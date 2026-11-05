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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hexnext.engine.compile.ColorGroup

private val Keys = listOf(
    listOf("1" to "", "2" to "ABC", "3" to "DEF"),
    listOf("4" to "GHI", "5" to "JKL", "6" to "MNO"),
    listOf("7" to "PQRS", "8" to "TUV", "9" to "WXYZ"),
    listOf("*" to "", "0" to "+", "#" to ""),
)

/** Phone: an in-call strip, the typed number, a keypad, the call button and bottom tabs. */
@Composable
internal fun PreviewDialer(scope: PreviewScope, modifier: Modifier = Modifier) {
    val background = scope[ColorGroup.BACKGROUND]
    Column(
        modifier = modifier
            .fillMaxSize()
            .fill(ColorGroup.BACKGROUND, scope)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        InCallStrip(scope)
        Spacer(Modifier.weight(1f))
        GroupText("+1 555 0142", ColorGroup.TEXT_PRIMARY, background, scope, size = 26.sp, weight = FontWeight.Normal, align = TextAlign.Center)
        Spacer(Modifier.height(2.dp))
        GroupText("Add to Contacts", ColorGroup.ACCENT, background, scope, size = 11.sp, weight = FontWeight.Medium)
        Spacer(Modifier.height(14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (row in Keys) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    for ((digit, letters) in row) {
                        DialKey(digit, letters, scope)
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.size(30.dp))
            Box(
                Modifier
                    .size(58.dp)
                    .fill(ColorGroup.DIALER, scope, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Call, contentDescription = null, tint = scope.on(ColorGroup.DIALER), modifier = Modifier.size(26.dp))
            }
            Icon(
                Icons.Filled.Clear,
                contentDescription = null,
                tint = scope.text(background, ColorGroup.ICON_TINT),
                modifier = Modifier
                    .size(30.dp)
                    .themed(ColorGroup.ICON_TINT, scope, CircleShape)
                    .padding(4.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        BottomTabs(scope)
    }
}

/** The mini in-call strip in [ColorGroup.DIALER]. */
@Composable
private fun InCallStrip(scope: PreviewScope) {
    val ink = scope.on(ColorGroup.DIALER)
    Row(
        Modifier
            .fillMaxWidth()
            .height(38.dp)
            .fill(ColorGroup.DIALER, scope, CircleShape)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Call, contentDescription = null, tint = ink, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        PText("Mina · On call", ink, scope, Modifier.weight(1f), size = 11.5.sp, weight = FontWeight.SemiBold)
        PText("02:14", ink.copy(alpha = 0.85f), scope, size = 11.sp, weight = FontWeight.Medium)
        Spacer(Modifier.width(6.dp))
        Icon(Icons.Filled.MoreVert, contentDescription = null, tint = ink, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun DialKey(digit: String, letters: String, scope: PreviewScope) {
    val key = scope[ColorGroup.SURFACE]
    Column(
        Modifier
            .size(width = 70.dp, height = 50.dp)
            .fill(ColorGroup.SURFACE, scope, RoundedCornerShape(25.dp)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        PText(digit, scope.text(key), scope, size = 20.sp, weight = FontWeight.Normal, align = TextAlign.Center)
        if (letters.isNotEmpty()) {
            PText(letters, scope.text(key, ColorGroup.TEXT_SECONDARY), scope, size = 7.5.sp, letterSpacing = 1.sp, align = TextAlign.Center)
        }
    }
}

@Composable
private fun BottomTabs(scope: PreviewScope) {
    val background = scope[ColorGroup.BACKGROUND]
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        for ((label, selected) in listOf("Keypad" to true, "Recents" to false, "Contacts" to false)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                GroupText(
                    label,
                    if (selected) ColorGroup.ACCENT else ColorGroup.TEXT_SECONDARY,
                    background,
                    scope,
                    size = 11.sp,
                    weight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                )
                Spacer(Modifier.height(3.dp))
                Box(
                    Modifier
                        .size(width = 18.dp, height = 3.dp)
                        .clip(CircleShape)
                        .background(if (selected) scope[ColorGroup.ACCENT] else background),
                )
            }
        }
    }
}
