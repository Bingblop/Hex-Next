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

/** A default Settings row: shown when no icons are rendered yet, or to fill a short list. */
private class SettingsRow(val title: String, val summary: String, val argb: Long)

private val DefaultSettingsRows = listOf(
    SettingsRow("Connections", "Wi-Fi, Bluetooth, SIM manager", 0xFF3E91FF),
    SettingsRow("Connected devices", "Quick Share, Android Auto", 0xFF22B8A6),
    SettingsRow("Modes and Routines", "Modes, Routines", 0xFF38B26B),
    SettingsRow("Sounds and vibration", "Sound mode, Ringtone", 0xFF7A5CFF),
    SettingsRow("Notifications", "Status bar, Do not disturb", 0xFFFF7043),
    SettingsRow("Display", "Brightness, Eye comfort shield", 0xFFF6B92B),
    SettingsRow("Battery", "Power saving, Charging", 0xFF34C759),
    SettingsRow("Wallpaper and style", "Wallpapers, Colour palette", 0xFFFF4F8B),
    SettingsRow("Themes", "Themes, Wallpapers, Icons", 0xFFB14DFF),
    SettingsRow("Home screen", "Layout, App icon badges", 0xFF4F7BFF),
    SettingsRow("Lock screen and AOD", "Screen lock type, Always On Display", 0xFF5C6BC0),
    SettingsRow("Security and privacy", "Biometrics, Permission manager", 0xFF3DA5F4),
)

private const val MIN_ROWS = 10
private const val MAX_ROWS = 12
private const val ROWS_PER_CARD = 3

/** The summary line for a row titled [label], empty when the label is not a known row. */
private fun summaryFor(label: String): String {
    DefaultSettingsRows.firstOrNull { it.title.equals(label, ignoreCase = true) }?.let { return it.summary }
    val word = label.substringBefore(' ').lowercase()
    if (word.length < 4) return ""
    return DefaultSettingsRows.firstOrNull { it.title.lowercase().startsWith(word) }?.summary.orEmpty()
}

/** The rendered icons first (unique by key), then default rows up to [MIN_ROWS]. */
private fun settingsRows(icons: List<PreviewIcon>): List<PreviewIcon> {
    val rendered = icons.distinctBy { it.key }.take(MAX_ROWS)
    if (rendered.size >= MIN_ROWS) return rendered
    val taken = rendered.mapTo(HashSet()) { it.label.lowercase() }
    val fill = DefaultSettingsRows
        .filter { it.title.lowercase() !in taken }
        .take(MIN_ROWS - rendered.size)
        .map { PreviewIcon(key = "default:${it.title}", label = it.title, bitmap = null, fallbackArgb = it.argb.toInt()) }
    return rendered + fill
}

/** One UI Settings: title, search pill, account card, a suggestion card and grouped rows. */
@Composable
internal fun PreviewSettings(state: StudioPreviewState, scope: PreviewScope, modifier: Modifier = Modifier) {
    val background = scope[ColorGroup.BACKGROUND]
    val surface = scope[ColorGroup.SURFACE]
    val cardShape = RoundedCornerShape(20.dp)
    val rows = settingsRows(state.settingsIcons)
    Column(
        modifier = modifier
            .fillMaxSize()
            .fill(ColorGroup.BACKGROUND, scope)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Spacer(Modifier.height(30.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            GroupText(
                "Settings",
                ColorGroup.TEXT_PRIMARY,
                background,
                scope,
                modifier = Modifier.padding(start = 6.dp),
                size = 26.sp,
                weight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            Icon(
                Icons.Filled.MoreVert,
                contentDescription = null,
                tint = scope.text(background, ColorGroup.ICON_TINT),
                modifier = Modifier
                    .size(26.dp)
                    .themed(ColorGroup.ICON_TINT, scope, CircleShape)
                    .padding(3.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        SearchPill(scope)
        AccountCard(scope, cardShape)
        SuggestionCard(scope, cardShape)
        for (chunk in rows.chunked(ROWS_PER_CARD)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .fill(ColorGroup.SURFACE, scope, cardShape)
                    .padding(vertical = 4.dp),
            ) {
                for (icon in chunk) {
                    key(icon.key) {
                        SettingsRowItem(icon, surface, state.iconsLoading, scope)
                    }
                }
            }
        }
        Footer(scope)
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun SearchPill(scope: PreviewScope) {
    val surface = scope[ColorGroup.SURFACE]
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .fill(ColorGroup.SURFACE, scope, CircleShape)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Search,
            contentDescription = null,
            tint = scope.text(surface, ColorGroup.ICON_TINT),
            modifier = Modifier
                .size(18.dp)
                .themed(ColorGroup.ICON_TINT, scope, CircleShape),
        )
        Spacer(Modifier.width(8.dp))
        GroupText("Search settings", ColorGroup.TEXT_SECONDARY, surface, scope, size = 12.sp)
    }
}

@Composable
private fun AccountCard(scope: PreviewScope, shape: RoundedCornerShape) {
    val surface = scope[ColorGroup.SURFACE]
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .fill(ColorGroup.SURFACE, scope, shape)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .fill(ColorGroup.ACCENT, scope, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Person, contentDescription = null, tint = scope.on(ColorGroup.ACCENT), modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            GroupText("Galaxy user", ColorGroup.TEXT_PRIMARY, surface, scope, size = 14.sp, weight = FontWeight.SemiBold)
            GroupText("Samsung account · Profile, apps", ColorGroup.TEXT_SECONDARY, surface, scope, size = 11.sp)
        }
    }
}

/** A row with a switch, a filled button and an accent link, so all controls are visible. */
@Composable
private fun SuggestionCard(scope: PreviewScope, shape: RoundedCornerShape) {
    val surface = scope[ColorGroup.SURFACE]
    val offTrack = scope.palette.shade(if (scope.dark) "neutral2_600" else "neutral2_200", Color.Gray)
    Column(
        Modifier
            .fillMaxWidth()
            .fill(ColorGroup.SURFACE, scope, shape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                GroupText("Dark mode", ColorGroup.TEXT_PRIMARY, surface, scope, size = 13.sp, weight = FontWeight.Medium)
                GroupText("Easier on the eyes at night", ColorGroup.TEXT_SECONDARY, surface, scope, size = 11.sp)
            }
            PreviewSwitch(checked = true, offTrack = offTrack, scope = scope)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                GroupText("Eye comfort shield", ColorGroup.TEXT_PRIMARY, surface, scope, size = 13.sp, weight = FontWeight.Medium)
                GroupText("Off", ColorGroup.TEXT_SECONDARY, surface, scope, size = 11.sp)
            }
            PreviewSwitch(checked = false, offTrack = offTrack, scope = scope)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                GroupText("Software update", ColorGroup.TEXT_PRIMARY, surface, scope, size = 13.sp, weight = FontWeight.Medium)
                GroupText(
                    "What's new",
                    ColorGroup.ACCENT,
                    surface,
                    scope,
                    size = 11.sp,
                    weight = FontWeight.Medium,
                )
            }
            Box(
                Modifier
                    .fill(ColorGroup.BUTTON, scope, CircleShape)
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                PText("Download", scope.on(ColorGroup.BUTTON), scope, size = 11.sp, weight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun SettingsRowItem(icon: PreviewIcon, surface: Color, loading: Boolean, scope: PreviewScope) {
    val summary = summaryFor(icon.label)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PreviewIconImage(icon, loading, scope.reduceMotion, size = 28.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            GroupText(icon.label, ColorGroup.TEXT_PRIMARY, surface, scope, size = 13.sp, weight = FontWeight.Medium)
            if (summary.isNotEmpty()) {
                GroupText(summary, ColorGroup.TEXT_SECONDARY, surface, scope, size = 10.5.sp)
            }
        }
    }
}

@Composable
private fun Footer(scope: PreviewScope) {
    val background = scope[ColorGroup.BACKGROUND]
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        GroupText("Looking for something else?", ColorGroup.TEXT_PRIMARY, background, scope, size = 12.sp, weight = FontWeight.Medium)
        for (link in listOf("Reset", "Samsung Cloud", "Link to Windows")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(scope[ColorGroup.ACCENT]),
                )
                Spacer(Modifier.width(8.dp))
                GroupText(link, ColorGroup.ACCENT, background, scope, size = 12.sp, weight = FontWeight.Medium)
            }
        }
    }
}
