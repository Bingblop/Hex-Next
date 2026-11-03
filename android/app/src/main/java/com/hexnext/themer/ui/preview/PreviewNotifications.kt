package com.hexnext.themer.ui.preview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hexnext.engine.compile.ColorGroup
import com.hexnext.themer.ui.studio.StudioPreviewState

private class PreviewNotification(
    val app: String,
    val letter: String,
    val shade: String,
    val time: String,
    val title: String,
    val text: String,
    val actions: List<String>,
)

private val SampleNotifications = listOf(
    PreviewNotification("Messages", "M", "accent1_500", "now", "Mina", "Are we still on for dinner at eight?", listOf("Reply", "Mark as read")),
    PreviewNotification("Samsung Health", "H", "accent3_500", "12m", "Daily goal reached", "6,240 steps · 48 active minutes", listOf("View")),
    PreviewNotification("Gallery", "G", "accent2_500", "1h", "New memories", "Weekend trip · 24 photos", listOf("Open", "Share")),
)

/** The notification shade: clock, three notification cards with accent action chips. */
@Composable
internal fun PreviewNotifications(state: StudioPreviewState, scope: PreviewScope, modifier: Modifier = Modifier) {
    QsBackdrop(state, scope, modifier) { panel ->
        val ink = contentColorFor(panel)
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PText("09:41", ink, scope, size = 30.sp, weight = FontWeight.Light)
            PText("Monday, 2 November", ink.copy(alpha = 0.8f), scope, size = 11.sp)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                PText("Notifications", ink, scope, Modifier.weight(1f), size = 12.sp, weight = FontWeight.SemiBold)
                PText("3", ink.copy(alpha = 0.7f), scope, size = 11.sp)
            }
            for (notification in SampleNotifications) {
                NotificationCard(notification, scope)
            }
            Spacer(Modifier.height(2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ShadeButton("Notification settings", scope)
                Spacer(Modifier.weight(1f))
                ShadeButton("Clear", scope)
            }
        }
    }
}

@Composable
private fun NotificationCard(notification: PreviewNotification, scope: PreviewScope) {
    val card = scope[ColorGroup.NOTIFICATION_BG]
    val dot = scope.palette.shade(notification.shade, scope[ColorGroup.ACCENT])
    Column(
        Modifier
            .fillMaxWidth()
            .fill(ColorGroup.NOTIFICATION_BG, scope, RoundedCornerShape(20.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LetterDot(notification.letter, dot, scope, size = 18.dp)
            Spacer(Modifier.width(6.dp))
            GroupText(
                "${notification.app} · ${notification.time}",
                ColorGroup.TEXT_SECONDARY,
                card,
                scope,
                size = 10.sp,
            )
        }
        Spacer(Modifier.height(2.dp))
        GroupText(notification.title, ColorGroup.TEXT_PRIMARY, card, scope, size = 12.5.sp, weight = FontWeight.SemiBold)
        GroupText(notification.text, ColorGroup.TEXT_SECONDARY, card, scope, size = 11.sp, maxLines = 2)
        if (notification.actions.isNotEmpty()) {
            Spacer(Modifier.height(5.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (action in notification.actions) {
                    ActionChip(action, card, scope)
                }
            }
        }
    }
}

/** An action chip: accent text on a faint accent pill. */
@Composable
private fun ActionChip(label: String, card: Color, scope: PreviewScope) {
    val accent = scope[ColorGroup.ACCENT]
    val pill = accent.copy(alpha = 0.16f)
    Box(
        Modifier
            .fill(ColorGroup.ACCENT, scope, CircleShape, pill)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        PText(label, readableOn(accent, card), scope, size = 10.5.sp, weight = FontWeight.SemiBold)
    }
}

@Composable
private fun ShadeButton(label: String, scope: PreviewScope) {
    val card = scope[ColorGroup.NOTIFICATION_BG]
    Box(
        Modifier
            .fill(ColorGroup.NOTIFICATION_BG, scope, CircleShape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        PText(label, scope.text(card), scope, size = 10.5.sp, weight = FontWeight.Medium)
    }
}
