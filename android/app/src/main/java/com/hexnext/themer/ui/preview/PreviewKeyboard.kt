package com.hexnext.themer.ui.preview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hexnext.engine.compile.ColorGroup

private val KeyRows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")

/** Samsung Keyboard under a chat stub: bubbles, the input field, suggestions and four key rows. */
@Composable
internal fun PreviewKeyboard(scope: PreviewScope, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize()) {
        ChatStub(scope, Modifier.weight(1f))
        KeyboardArea(scope)
    }
}

@Composable
private fun ChatStub(scope: PreviewScope, modifier: Modifier = Modifier) {
    val background = scope[ColorGroup.BACKGROUND]
    val surface = scope[ColorGroup.SURFACE]
    Column(
        modifier = modifier
            .fillMaxWidth()
            .fill(ColorGroup.BACKGROUND, scope)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LetterDot("M", scope.palette.shade("accent3_400", scope[ColorGroup.ACCENT]), scope, size = 28.dp)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                GroupText("Mina", ColorGroup.TEXT_PRIMARY, background, scope, size = 13.sp, weight = FontWeight.SemiBold)
                GroupText("Online", ColorGroup.TEXT_SECONDARY, background, scope, size = 10.sp)
            }
        }
        Spacer(Modifier.weight(1f))
        Bubble("Are we still on for dinner at eight?", outgoing = false, scope = scope)
        Bubble("Yes! See you there", outgoing = true, scope = scope)
        Row(
            Modifier
                .fillMaxWidth()
                .height(36.dp)
                .fill(ColorGroup.SURFACE, scope, CircleShape)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PText("Sounds great", scope.text(surface), scope, Modifier.weight(1f), size = 12.sp)
            Box(
                Modifier
                    .size(24.dp)
                    .fill(ColorGroup.ACCENT, scope, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = null, tint = scope.on(ColorGroup.ACCENT), modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun Bubble(text: String, outgoing: Boolean, scope: PreviewScope) {
    val group = if (outgoing) ColorGroup.ACCENT else ColorGroup.SURFACE
    val fill = scope[group]
    val ink = if (outgoing) contentColorFor(fill) else scope.text(fill)
    val shape = if (outgoing) {
        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 4.dp)
    } else {
        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 16.dp)
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = if (outgoing) Alignment.CenterEnd else Alignment.CenterStart) {
        Box(
            Modifier
                .widthIn(max = 200.dp)
                .fill(group, scope, shape)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            PText(text, ink, scope, size = 11.5.sp, maxLines = 3)
        }
    }
}

@Composable
private fun KeyboardArea(scope: PreviewScope) {
    val board = scope[ColorGroup.KEYBOARD_BG]
    Column(
        Modifier
            .fillMaxWidth()
            .fill(ColorGroup.KEYBOARD_BG, scope)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Toolbar: tool icons and word suggestions.
        Row(
            Modifier
                .fillMaxWidth()
                .height(26.dp)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolIcon(Icons.Filled.Face, board, scope)
            Spacer(Modifier.width(10.dp))
            for (word in listOf("Sounds", "great", "good")) {
                GroupText(word, ColorGroup.TEXT_PRIMARY, board, scope, Modifier.weight(1f), size = 11.5.sp, align = TextAlign.Center)
            }
            ToolIcon(Icons.Filled.Settings, board, scope)
            Spacer(Modifier.width(8.dp))
            ToolIcon(Icons.Filled.MoreVert, board, scope)
        }
        KeyRow { for (c in KeyRows[0]) Key(c.toString(), scope, Modifier.weight(1f)) }
        KeyRow(Modifier.padding(horizontal = 12.dp)) { for (c in KeyRows[1]) Key(c.toString(), scope, Modifier.weight(1f)) }
        KeyRow {
            Key("⇧", scope, Modifier.weight(1.5f), special = true)
            for (c in KeyRows[2]) Key(c.toString(), scope, Modifier.weight(1f))
            Key("⌫", scope, Modifier.weight(1.5f), special = true)
        }
        KeyRow {
            Key("!#1", scope, Modifier.weight(1.5f), size = 10.sp, special = true)
            Key(",", scope, Modifier.weight(1f))
            Key("English (US)", scope, Modifier.weight(4f), size = 9.5.sp)
            Key(".", scope, Modifier.weight(1f))
            EnterKey(scope, Modifier.weight(1.5f))
        }
    }
}

@Composable
private fun KeyRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), content = content)
}

@Composable
private fun Key(label: String, scope: PreviewScope, modifier: Modifier = Modifier, size: TextUnit = 13.sp, special: Boolean = false) {
    // Letters and the special keys (Shift, Backspace, symbols, Enter) are separate groups, and the
    // labels on both are the "Keyboard letters" group, as Samsung Keyboard draws them.
    val group = if (special) ColorGroup.KEYBOARD_FUNCTION_KEY else ColorGroup.KEYBOARD_KEY
    // With pack key art the phone draws it (key_bg) under every key instead of the flat colour.
    val art = scope.device.keyboardKey?.let { image -> androidx.compose.runtime.remember(image) { NinePatchArt.of(image) } }
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    Box(
        modifier
            .height(36.dp)
            .then(
                if (art != null) Modifier.ninePatch(art, density / 3f * 0.75f).themed(group, scope, RoundedCornerShape(8.dp))
                else Modifier.fill(group, scope, RoundedCornerShape(8.dp)),
            ),
        contentAlignment = Alignment.Center,
    ) {
        PText(label, scope[ColorGroup.KEYBOARD_LABEL], scope, size = size, align = TextAlign.Center)
    }
}

@Composable
private fun EnterKey(scope: PreviewScope, modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(36.dp)
            .fill(ColorGroup.KEYBOARD_FUNCTION_KEY, scope, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Done, contentDescription = null, tint = scope[ColorGroup.KEYBOARD_LABEL], modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun ToolIcon(icon: ImageVector, board: Color, scope: PreviewScope) {
    Icon(
        icon,
        contentDescription = null,
        tint = scope.text(board, ColorGroup.ICON_TINT),
        modifier = Modifier
            .size(22.dp)
            .themed(ColorGroup.ICON_TINT, scope, CircleShape)
            .padding(2.dp),
    )
}
