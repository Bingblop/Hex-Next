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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hexnext.engine.compile.ColorGroup
import com.hexnext.themer.ui.studio.PreviewIcon
import com.hexnext.themer.ui.studio.StudioPreviewState

/** A One UI 9 Settings dashboard row; [argb] tints One UI's own icon when the theme has none. */
private class SettingsRow(val title: String, val argb: Long)

/** The One UI 9 dashboard, group by group, in the phone's order (lab-002 screenshots). */
private val DashboardGroups: List<List<SettingsRow>> = listOf(
    listOf(SettingsRow("Accounts and backup", 0xFF3E91FF), SettingsRow("Google services", 0xFF4F7BFF)),
    listOf(SettingsRow("Connections", 0xFF3E91FF), SettingsRow("Device care", 0xFF38B26B), SettingsRow("Apps", 0xFF3DA5F4)),
    listOf(
        SettingsRow("Display", 0xFFF6B92B), SettingsRow("Advanced features", 0xFFFF7043),
        SettingsRow("Wallpaper and style", 0xFFFF4F8B), SettingsRow("General management", 0xFF3DA5F4),
    ),
    listOf(
        SettingsRow("Galaxy AI", 0xFF7A5CFF), SettingsRow("Sounds and vibration", 0xFF22B8A6),
        SettingsRow("Notifications", 0xFFFF7043), SettingsRow("Themes", 0xFFB14DFF),
    ),
    listOf(
        SettingsRow("Security and privacy", 0xFF5C6BC0), SettingsRow("Lock screen and AOD", 0xFFFF7043),
        SettingsRow("Accessibility", 0xFF38B26B), SettingsRow("Location", 0xFF22B8A6),
    ),
    listOf(SettingsRow("About phone", 0xFF3DA5F4), SettingsRow("Developer options", 0xFFFF4F8B)),
)

/** The significant words of a label or resource name, for matching an icon to its row. */
private fun words(label: String): Set<String> =
    label.lowercase().split(' ', '_', '-').filter { it.length > 3 && it !in IgnoredWords }.toSet()

private val IgnoredWords = setOf("settings", "and", "sec_ic", "icon")

/** Whether two word sets share a word, allowing plurals and short forms ("wallpaper"/"wallpapers"). */
private fun overlaps(a: Set<String>, b: Set<String>): Boolean =
    a.any { x -> b.any { y -> x.startsWith(y) || y.startsWith(x) } }

/**
 * Each dashboard row with the theme's rendered icon for it (matched by label), else a default
 * badge in One UI's colour for that row.
 */
private fun dashboard(icons: List<PreviewIcon>): List<List<PreviewIcon>> {
    val pool = icons.distinctBy { it.key }.toMutableList()
    return DashboardGroups.map { group ->
        group.map { row ->
            val want = words(row.title)
            val match = pool.firstOrNull { it.label.equals(row.title, ignoreCase = true) }
                ?: pool.firstOrNull { want.isNotEmpty() && (overlaps(words(it.label), want) || overlaps(words(it.key), want)) }
            if (match != null) {
                pool.remove(match)
                match.copy(label = row.title)
            } else {
                PreviewIcon(key = "default:${row.title}", label = row.title, bitmap = null, fallbackArgb = row.argb.toInt())
            }
        }
    }
}

/**
 * One UI 9 Settings as the phone shows it: the large centred title (the accent colour), the
 * Samsung account row with its avatar, the dashboard in rounded groups (icon and title only,
 * inset dividers) and the floating search pill.
 */
@Composable
internal fun PreviewSettings(state: StudioPreviewState, scope: PreviewScope, modifier: Modifier = Modifier) {
    val background = scope[ColorGroup.BACKGROUND]
    val surface = scope[ColorGroup.SURFACE]
    val cardShape = RoundedCornerShape(20.dp)
    val groups = dashboard(state.settingsIcons)
    Box(modifier.fillMaxSize().fill(ColorGroup.BACKGROUND, scope)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Spacer(Modifier.height(64.dp))
            // The title is painted by the SESL action bar title colour (lab-004/005), not the
            // accent: show what the theme writes there, else the accent before a scan.
            PText(
                "Settings",
                readableOn(scope.settings("sesl_action_bar_text_color_title_light", scope.text(background, ColorGroup.ACCENT)), background),
                scope,
                Modifier.align(Alignment.CenterHorizontally).themed(ColorGroup.ACCENT, scope),
                size = 26.sp,
                weight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(54.dp))
            groups.forEachIndexed { g, group ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .fill(ColorGroup.SURFACE, scope, cardShape)
                        .padding(vertical = 2.dp),
                ) {
                    // One UI 9 opens the first card with the Samsung account row.
                    if (g == 0) AccountRow(scope, surface)
                    group.forEachIndexed { i, icon ->
                        key(icon.key) {
                            SettingsRowItem(icon, surface, state.iconsLoading, scope, divider = i < group.lastIndex)
                        }
                    }
                }
            }
            Spacer(Modifier.height(56.dp))
        }
        SearchPill(surface, scope, Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp))
    }
}

@Composable
private fun AccountRow(scope: PreviewScope, background: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            GroupText("Galaxy user", ColorGroup.TEXT_PRIMARY, background, scope, size = 14.sp, weight = FontWeight.SemiBold)
            GroupText("Samsung account", ColorGroup.TEXT_SECONDARY, background, scope, size = 10.5.sp)
        }
        Box(
            modifier = Modifier
                .size(36.dp)
                .fill(ColorGroup.ACCENT, scope, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Person, contentDescription = null, tint = scope.on(ColorGroup.ACCENT), modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun SettingsRowItem(icon: PreviewIcon, surface: Color, loading: Boolean, scope: PreviewScope, divider: Boolean) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PreviewIconImage(icon, loading, scope.reduceMotion, size = 20.dp, slotPackage = "com.android.settings")
            Spacer(Modifier.width(14.dp))
            GroupText(icon.label, ColorGroup.TEXT_PRIMARY, surface, scope, modifier = Modifier.weight(1f), size = 13.sp, weight = FontWeight.Medium)
        }
        if (divider) {
            Box(
                Modifier
                    .padding(start = 46.dp, end = 12.dp)
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(scope.text(surface, ColorGroup.TEXT_SECONDARY).copy(alpha = 0.25f)),
            )
        }
    }
}
