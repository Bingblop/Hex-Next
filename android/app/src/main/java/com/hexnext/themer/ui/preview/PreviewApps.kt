package com.hexnext.themer.ui.preview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hexnext.engine.compile.ColorGroup

/*
 * Mock screens of the Samsung apps Hex Next themes. Every coloured element is drawn with its
 * group and is tappable ([fill] / [themed] / [GroupText]), so tapping the Calculator keys, the
 * record button or a contact tile opens that group's colour picker.
 */

// ---------------------------------------------------------------- shared One UI app parts

/** One UI's big centred title over the app bar, with an optional count line. */
@Composable
private fun AppTitle(title: String, subtitle: String?, scope: PreviewScope) {
    val background = scope[ColorGroup.BACKGROUND]
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 26.dp, bottom = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        GroupText(title, ColorGroup.TEXT_PRIMARY, background, scope, size = 22.sp, weight = FontWeight.SemiBold, align = TextAlign.Center)
        if (subtitle != null) {
            Spacer(Modifier.height(4.dp))
            GroupText(subtitle, ColorGroup.TEXT_SECONDARY, background, scope, size = 11.sp, align = TextAlign.Center)
        }
    }
}

/** The app bar row: [leading] icon (or none), then [actions] on the right, all in [ColorGroup.ICON_TINT]. */
@Composable
private fun AppBar(scope: PreviewScope, leading: ImageVector? = null, actions: List<ImageVector> = listOf(Icons.Filled.Search, Icons.Filled.MoreVert)) {
    val background = scope[ColorGroup.BACKGROUND]
    val tint = scope.text(background, ColorGroup.ICON_TINT)
    Row(Modifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.CenterVertically) {
        if (leading != null) BarIcon(leading, tint, scope)
        Spacer(Modifier.weight(1f))
        for (a in actions) BarIcon(a, tint, scope)
    }
}

@Composable
private fun BarIcon(icon: ImageVector, tint: Color, scope: PreviewScope) {
    Icon(
        icon,
        contentDescription = null,
        tint = tint,
        modifier = Modifier
            .size(28.dp)
            .themed(ColorGroup.ICON_TINT, scope, CircleShape)
            .padding(5.dp),
    )
}

/** A rounded One UI card in [ColorGroup.SURFACE]. */
@Composable
private fun AppCard(scope: PreviewScope, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .fill(ColorGroup.SURFACE, scope, RoundedCornerShape(18.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        content = content,
    )
}

/** A card row: title, optional summary (in [summaryGroup]) and an optional switch. */
@Composable
private fun CardRow(
    title: String,
    scope: PreviewScope,
    summary: String? = null,
    summaryGroup: ColorGroup = ColorGroup.TEXT_SECONDARY,
    switch: Boolean? = null,
) {
    val surface = scope[ColorGroup.SURFACE]
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            GroupText(title, ColorGroup.TEXT_PRIMARY, surface, scope, size = 12.5.sp, weight = FontWeight.Medium)
            if (summary != null) GroupText(summary, summaryGroup, surface, scope, size = 10.5.sp)
        }
        if (switch != null) {
            Spacer(Modifier.width(8.dp))
            PreviewSwitch(switch, offTrack(scope), scope)
        }
    }
}

private fun offTrack(scope: PreviewScope): Color = if (scope.dark) Color(0xFF4A4A4E) else Color(0xFFC8C8CC)

/** A round floating button in [group] with [icon] in the contrast ink. */
@Composable
private fun RoundButton(group: ColorGroup, icon: ImageVector, scope: PreviewScope, size: Dp = 50.dp) {
    Box(Modifier.size(size).fill(group, scope, CircleShape), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = scope.on(group), modifier = Modifier.size(size * 0.46f))
    }
}

@Composable
private fun AppScreen(scope: PreviewScope, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxSize()
            .fill(ColorGroup.BACKGROUND, scope)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        content = content,
    )
}

// ---------------------------------------------------------------- Calculator

private val CalcRows = listOf(
    listOf("C", "( )", "%", "÷"),
    listOf("7", "8", "9", "×"),
    listOf("4", "5", "6", "−"),
    listOf("1", "2", "3", "+"),
    listOf("+/−", "0", ".", "="),
)

/** Calculator: the display and the keypad; the ÷ × − + = keys are [ColorGroup.CALCULATOR_KEYS]. */
@Composable
internal fun PreviewCalculator(scope: PreviewScope, modifier: Modifier = Modifier) {
    val background = scope[ColorGroup.BACKGROUND]
    AppScreen(scope, modifier) {
        AppBar(scope, actions = listOf(Icons.Filled.MoreVert))
        Spacer(Modifier.weight(1f))
        GroupText("12×4", ColorGroup.TEXT_SECONDARY, background, scope, Modifier.fillMaxWidth(), size = 16.sp, align = TextAlign.End)
        GroupText("48", ColorGroup.TEXT_PRIMARY, background, scope, Modifier.fillMaxWidth(), size = 34.sp, align = TextAlign.End)
        Spacer(Modifier.height(14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (row in CalcRows) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (label in row) CalcKey(label, scope, Modifier.weight(1f))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun CalcKey(label: String, scope: PreviewScope, modifier: Modifier) {
    val operator = label in setOf("÷", "×", "−", "+", "=")
    val function = label in setOf("C", "( )", "%", "+/−")
    val box = modifier.aspectRatio(1f)
    if (operator) {
        Box(box.fill(ColorGroup.CALCULATOR_KEYS, scope, CircleShape), contentAlignment = Alignment.Center) {
            PText(
                label, scope[ColorGroup.CALCULATOR_SYMBOLS], scope,
                Modifier.themed(ColorGroup.CALCULATOR_SYMBOLS, scope, CircleShape),
                size = 20.sp, weight = FontWeight.Medium, align = TextAlign.Center,
            )
        }
    } else {
        val key = scope[ColorGroup.SURFACE]
        Box(box.fill(ColorGroup.SURFACE, scope, CircleShape), contentAlignment = Alignment.Center) {
            val ink = if (function) scope.text(key, ColorGroup.ACCENT) else scope.text(key)
            PText(label, ink, scope, size = if (function) 15.sp else 19.sp, align = TextAlign.Center)
        }
    }
}

// ---------------------------------------------------------------- Voice Recorder

/** Voice Recorder: the recordings list and the big record button ([ColorGroup.VOICE_RECORDER_BUTTON]). */
@Composable
internal fun PreviewVoiceRecorder(scope: PreviewScope, modifier: Modifier = Modifier) {
    val surface = scope[ColorGroup.SURFACE]
    AppScreen(scope, modifier) {
        AppTitle("All recordings", "2 recordings", scope)
        AppBar(scope, leading = Icons.Filled.Menu)
        Spacer(Modifier.height(8.dp))
        for ((name, time) in listOf("Interview_0412" to "12:48", "Lecture notes" to "41:03")) {
            AppCard(scope) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        GroupText(name, ColorGroup.TEXT_PRIMARY, surface, scope, size = 13.sp, weight = FontWeight.Medium)
                        GroupText("Apr 12 · $time", ColorGroup.TEXT_SECONDARY, surface, scope, size = 10.5.sp)
                    }
                    Icon(
                        Icons.Filled.PlayArrow, contentDescription = null,
                        tint = scope.text(surface, ColorGroup.ACCENT),
                        modifier = Modifier.size(26.dp).themed(ColorGroup.ACCENT, scope, CircleShape).padding(3.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        Spacer(Modifier.weight(1f))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(Modifier.size(62.dp).fill(ColorGroup.VOICE_RECORDER_BUTTON, scope, CircleShape), contentAlignment = Alignment.Center) {
                MicGlyph(scope.on(ColorGroup.VOICE_RECORDER_BUTTON), Modifier.size(26.dp))
            }
        }
        Spacer(Modifier.height(10.dp))
    }
}

/** A microphone (the core icon set has none). */
@Composable
private fun MicGlyph(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.09f
        drawRoundRect(color, Offset(w * 0.34f, 0f), Size(w * 0.32f, h * 0.6f), CornerRadius(w * 0.16f))
        drawArc(color, 0f, 180f, false, Offset(w * 0.2f, h * 0.24f), Size(w * 0.6f, h * 0.52f), style = Stroke(stroke))
        drawLine(color, Offset(w / 2, h * 0.76f), Offset(w / 2, h * 0.92f), stroke)
        drawLine(color, Offset(w * 0.34f, h * 0.94f), Offset(w * 0.66f, h * 0.94f), stroke)
    }
}

// ---------------------------------------------------------------- Contacts

/** Samsung's colourful letter tiles (top-left to bottom-right gradient stops). */
private val SamsungTiles = listOf(
    listOf(0xFFF3ADCD, 0xFFF7B4B9),
    listOf(0xFFAED1F1, 0xFFADF3DD),
    listOf(0xFFD0AFFA, 0xFFA1CEF8),
    listOf(0xFFF7E4A3, 0xFFFBB399),
    listOf(0xFFA9F2C3, 0xFFEFE79B),
).map { stops -> stops.map { Color(it) } }

/**
 * Contacts: favourites, then the list. Contacts without a photo get a letter tile in
 * [ColorGroup.CONTACT_TILES]; on Auto the tiles keep Samsung's colourful gradients.
 */
@Composable
internal fun PreviewContacts(scope: PreviewScope, modifier: Modifier = Modifier) {
    val background = scope[ColorGroup.BACKGROUND]
    AppScreen(scope, modifier) {
        AppTitle("Contacts", "128 contacts", scope)
        AppBar(scope, actions = listOf(Icons.Filled.Add, Icons.Filled.Search, Icons.Filled.MoreVert))
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Star, contentDescription = null, tint = scope.text(background, ColorGroup.TEXT_SECONDARY), modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            GroupText("Favorites", ColorGroup.TEXT_SECONDARY, background, scope, size = 11.sp, weight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((i, name) in listOf("Mina", "Jae", "Rosa").withIndex()) {
                Column(
                    Modifier.weight(1f).fill(ColorGroup.SURFACE, scope, RoundedCornerShape(14.dp)).padding(vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    LetterTile(name.take(1), i, scope, 40.dp)
                    Spacer(Modifier.height(6.dp))
                    PText(name, scope.text(scope[ColorGroup.SURFACE]), scope, size = 11.sp, weight = FontWeight.Medium)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        for ((i, name) in listOf("Aliona", "Brandon", "Chris Park", "Dana", "Eli Moreno").withIndex()) {
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                LetterTile(name.take(1), i + 3, scope, 32.dp)
                Spacer(Modifier.width(12.dp))
                GroupText(name, ColorGroup.TEXT_PRIMARY, background, scope, size = 13.sp, weight = FontWeight.Medium)
            }
        }
    }
}

/** One letter tile: Samsung's gradient [index] on Auto, else the group colour. Tappable as the tiles. */
@Composable
private fun LetterTile(letter: String, index: Int, scope: PreviewScope, size: Dp) {
    val kept = scope.keptByApp(ColorGroup.CONTACT_TILES)
    val stops = SamsungTiles[index % SamsungTiles.size]
    val fill = if (kept) Modifier.background(Brush.linearGradient(stops)) else Modifier.background(scope[ColorGroup.CONTACT_TILES])
    val ink = if (kept) Color.White else scope.on(ColorGroup.CONTACT_TILES)
    Box(
        Modifier.size(size).clip(CircleShape).then(fill).themed(ColorGroup.CONTACT_TILES, scope, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        PText(letter, ink, scope, size = (size.value * 0.42f).sp, weight = FontWeight.SemiBold, align = TextAlign.Center)
    }
}

// ---------------------------------------------------------------- Gallery

/** Gallery: the photo grid, the bottom tabs and the Menu sheet's round buttons ([ColorGroup.GALLERY_MENU]). */
@Composable
internal fun PreviewGallery(scope: PreviewScope, modifier: Modifier = Modifier) {
    val background = scope[ColorGroup.BACKGROUND]
    val photos = listOf(
        0xFF8FB8DE, 0xFFE9B384, 0xFF9AD0A0, 0xFFC7A2E0, 0xFFE58F8F, 0xFF8ED1CF,
        0xFFDCC48E, 0xFFA3B1E6, 0xFFB7D88F,
    ).map { Color(it) }
    AppScreen(scope, modifier) {
        AppBar(scope, actions = listOf(Icons.Filled.Search, Icons.Filled.MoreVert))
        GroupText("Today", ColorGroup.ACCENT, background, scope, size = 12.sp, weight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        for (r in 0 until 3) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                for (c in 0 until 3) {
                    val p = photos[r * 3 + c]
                    Box(Modifier.weight(1f).aspectRatio(1f).background(Brush.linearGradient(listOf(p, p.copy(alpha = 0.6f)))))
                }
            }
            Spacer(Modifier.height(3.dp))
        }
        Spacer(Modifier.weight(1f))
        // The Menu sheet, open over the grid.
        AppCard(scope) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                for ((label, icon) in listOf("Favorites" to Icons.Filled.Favorite, "Shared" to Icons.Filled.Share, "Trash" to Icons.Filled.Delete, "Edit" to Icons.Filled.Create)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(40.dp).fill(ColorGroup.GALLERY_MENU, scope, CircleShape), contentAlignment = Alignment.Center) {
                            Icon(icon, contentDescription = null, tint = scope.on(ColorGroup.GALLERY_MENU), modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.height(4.dp))
                        PText(label, scope.text(scope[ColorGroup.SURFACE]), scope, size = 9.5.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        BottomPill(listOf("Pictures", "Albums", "Stories", "Menu"), 3, scope)
    }
}

/** One UI's floating bottom tab pill; [selected] is drawn in [ColorGroup.ACCENT]. */
@Composable
private fun BottomPill(labels: List<String>, selected: Int, scope: PreviewScope) {
    val surface = scope[ColorGroup.SURFACE]
    Row(
        Modifier.fillMaxWidth().fill(ColorGroup.SURFACE, scope, CircleShape).padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        for ((i, label) in labels.withIndex()) {
            GroupText(
                label, if (i == selected) ColorGroup.ACCENT else ColorGroup.TEXT_SECONDARY, surface, scope,
                size = 10.5.sp, weight = if (i == selected) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }
}

// ---------------------------------------------------------------- Clock

/** Clock: alarms with switches and the add button. */
@Composable
internal fun PreviewClock(scope: PreviewScope, modifier: Modifier = Modifier) {
    val surface = scope[ColorGroup.SURFACE]
    AppScreen(scope, modifier) {
        AppTitle("Alarm in 7 hours\n30 minutes", null, scope)
        AppBar(scope, actions = listOf(Icons.Filled.Add, Icons.Filled.MoreVert))
        Spacer(Modifier.height(6.dp))
        for ((time, on) in listOf("6:30" to true, "7:15" to false, "9:00" to true)) {
            AppCard(scope) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        GroupText("$time AM", if (on) ColorGroup.TEXT_PRIMARY else ColorGroup.TEXT_SECONDARY, surface, scope, size = 22.sp)
                        Row {
                            for ((i, d) in listOf("S", "M", "T", "W", "T", "F", "S").withIndex()) {
                                val active = on && i in 1..5
                                GroupText(d + " ", if (active) ColorGroup.ACCENT else ColorGroup.TEXT_SECONDARY, surface, scope, size = 10.sp, weight = if (active) FontWeight.Bold else FontWeight.Normal)
                            }
                        }
                    }
                    PreviewSwitch(on, offTrack(scope), scope)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        Spacer(Modifier.weight(1f))
        BottomPill(listOf("Alarm", "World clock", "Stopwatch", "Timer"), 0, scope)
    }
}

// ---------------------------------------------------------------- Calendar

/** Calendar: a month grid with today in [ColorGroup.ACCENT] and an event list. */
@Composable
internal fun PreviewCalendar(scope: PreviewScope, modifier: Modifier = Modifier) {
    val background = scope[ColorGroup.BACKGROUND]
    AppScreen(scope, modifier) {
        AppBar(scope, leading = Icons.Filled.Menu, actions = listOf(Icons.Filled.Search, Icons.Filled.MoreVert))
        GroupText("November", ColorGroup.TEXT_PRIMARY, background, scope, size = 20.sp, weight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            for ((i, d) in listOf("S", "M", "T", "W", "T", "F", "S").withIndex()) {
                PText(d, if (i == 0) Color(0xFFE5484D) else scope.text(background, ColorGroup.TEXT_SECONDARY), scope, Modifier.weight(1f), size = 10.sp, align = TextAlign.Center)
            }
        }
        for (week in 0 until 5) {
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                for (d in 0 until 7) {
                    val day = week * 7 + d + 1
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        if (day == 4) {
                            Box(Modifier.size(24.dp).fill(ColorGroup.ACCENT, scope, CircleShape), contentAlignment = Alignment.Center) {
                                PText("$day", scope.on(ColorGroup.ACCENT), scope, size = 11.sp, weight = FontWeight.Bold)
                            }
                        } else if (day <= 30) {
                            PText("$day", scope.text(background), scope, size = 11.sp, align = TextAlign.Center)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        AppCard(scope) {
            CardRow("Team sync", scope, "10:00 – 10:30 AM")
            CardRow("Dentist", scope, "2:15 PM")
        }
        Spacer(Modifier.weight(1f))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { RoundButton(ColorGroup.BUTTON, Icons.Filled.Add, scope) }
    }
}

// ---------------------------------------------------------------- Notes

/** Samsung Notes: note cards and the compose button. */
@Composable
internal fun PreviewNotes(scope: PreviewScope, modifier: Modifier = Modifier) {
    val surface = scope[ColorGroup.SURFACE]
    AppScreen(scope, modifier) {
        AppTitle("All notes", "6 notes", scope)
        AppBar(scope, leading = Icons.Filled.Menu)
        Spacer(Modifier.height(6.dp))
        for (row in listOf(listOf("Groceries" to "Milk, eggs, basil", "Trip ideas" to "Lisbon in May"), listOf("Gift list" to "Books for Sam", "Meeting" to "Q3 roadmap"))) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((title, body) in row) {
                    Column(Modifier.weight(1f).height(92.dp).fill(ColorGroup.SURFACE, scope, RoundedCornerShape(14.dp)).padding(10.dp)) {
                        GroupText(title, ColorGroup.TEXT_PRIMARY, surface, scope, size = 12.sp, weight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        GroupText(body, ColorGroup.TEXT_SECONDARY, surface, scope, size = 10.sp, maxLines = 3)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        Spacer(Modifier.weight(1f))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { RoundButton(ColorGroup.BUTTON, Icons.Filled.Create, scope) }
    }
}

// ---------------------------------------------------------------- Reminder

/** Reminder: a list with round check marks in [ColorGroup.ACCENT]. */
@Composable
internal fun PreviewReminder(scope: PreviewScope, modifier: Modifier = Modifier) {
    val surface = scope[ColorGroup.SURFACE]
    AppScreen(scope, modifier) {
        AppTitle("Reminders", "3 to do", scope)
        AppBar(scope, leading = Icons.Filled.Menu)
        Spacer(Modifier.height(6.dp))
        AppCard(scope) {
            for ((text, done) in listOf("Call the bank" to false, "Pick up dry cleaning" to true, "Water the plants" to false)) {
                Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (done) {
                        Box(Modifier.size(20.dp).fill(ColorGroup.ACCENT, scope, CircleShape), contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.Check, contentDescription = null, tint = scope.on(ColorGroup.ACCENT), modifier = Modifier.size(14.dp))
                        }
                    } else {
                        Canvas(Modifier.size(20.dp).themed(ColorGroup.ACCENT, scope, CircleShape)) {
                            drawCircle(scope[ColorGroup.ACCENT], radius = size.minDimension / 2 - 2f, style = Stroke(3f))
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    GroupText(text, if (done) ColorGroup.TEXT_SECONDARY else ColorGroup.TEXT_PRIMARY, surface, scope, size = 12.5.sp)
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { RoundButton(ColorGroup.BUTTON, Icons.Filled.Add, scope) }
    }
}

// ---------------------------------------------------------------- Internet

/** Samsung Internet settings: its Material switches follow [ColorGroup.SWITCH]. */
@Composable
internal fun PreviewInternet(scope: PreviewScope, modifier: Modifier = Modifier) {
    AppScreen(scope, modifier) {
        AppTitle("Settings", null, scope)
        Spacer(Modifier.height(4.dp))
        AppCard(scope) {
            CardRow("Block pop-ups", scope, switch = true)
            CardRow("Smart anti-tracking", scope, "Always", summaryGroup = ColorGroup.ACCENT)
            CardRow("Secret mode password", scope, switch = false)
        }
        Spacer(Modifier.height(8.dp))
        AppCard(scope) {
            CardRow("Show status bar", scope, switch = true)
            CardRow("Text size", scope, "100%", summaryGroup = ColorGroup.ACCENT)
        }
        Spacer(Modifier.weight(1f))
    }
}

// ---------------------------------------------------------------- Email

/** Samsung Email: inbox rows and the compose button. */
@Composable
internal fun PreviewEmail(scope: PreviewScope, modifier: Modifier = Modifier) {
    val background = scope[ColorGroup.BACKGROUND]
    AppScreen(scope, modifier) {
        AppTitle("Inbox", "3 unread", scope)
        AppBar(scope, leading = Icons.Filled.Menu)
        for ((i, mail) in listOf(Triple("Mina", "Lunch Friday?", true), Triple("Bank", "Your statement is ready", true), Triple("Jae", "Photos from the trip", false)).withIndex()) {
            Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                LetterDot(mail.first.take(1), SamsungTiles[i].first(), scope, size = 32.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    GroupText(mail.first, ColorGroup.TEXT_PRIMARY, background, scope, size = 12.5.sp, weight = if (mail.third) FontWeight.Bold else FontWeight.Normal)
                    GroupText(mail.second, ColorGroup.TEXT_SECONDARY, background, scope, size = 10.5.sp)
                }
                if (mail.third) Box(Modifier.size(8.dp).fill(ColorGroup.ACCENT, scope, CircleShape))
            }
        }
        Spacer(Modifier.weight(1f))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { RoundButton(ColorGroup.BUTTON, Icons.Filled.Create, scope) }
    }
}

// ---------------------------------------------------------------- Music

/** Samsung Music: the player with its seek bar ([ColorGroup.SLIDER]) and play button. */
@Composable
internal fun PreviewMusic(scope: PreviewScope, modifier: Modifier = Modifier) {
    val background = scope[ColorGroup.BACKGROUND]
    AppScreen(scope, modifier) {
        AppBar(scope, actions = listOf(Icons.Filled.MoreVert))
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).padding(horizontal = 18.dp).clip(RoundedCornerShape(20.dp))
                .background(Brush.linearGradient(listOf(scope.palette.shade("accent1_300", scope[ColorGroup.ACCENT]), scope.palette.shade("accent3_500", scope[ColorGroup.ACCENT])))),
        )
        Spacer(Modifier.height(16.dp))
        GroupText("Midnight Drive", ColorGroup.TEXT_PRIMARY, background, scope, Modifier.fillMaxWidth(), size = 17.sp, weight = FontWeight.SemiBold, align = TextAlign.Center)
        GroupText("The Lanterns", ColorGroup.TEXT_SECONDARY, background, scope, Modifier.fillMaxWidth(), size = 11.sp, align = TextAlign.Center)
        Spacer(Modifier.height(14.dp))
        ProgressBar(0.38f, ColorGroup.SLIDER, offTrack(scope), scope, Modifier.fillMaxWidth(), height = 5.dp)
        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { RoundButton(ColorGroup.ACCENT, Icons.Filled.PlayArrow, scope, 58.dp) }
        Spacer(Modifier.weight(1f))
    }
}

// ---------------------------------------------------------------- Health

/** Samsung Health: the step ring in [ColorGroup.ACCENT] and summary cards. */
@Composable
internal fun PreviewHealth(scope: PreviewScope, modifier: Modifier = Modifier) {
    val surface = scope[ColorGroup.SURFACE]
    AppScreen(scope, modifier) {
        AppBar(scope, actions = listOf(Icons.Filled.Add, Icons.Filled.MoreVert))
        AppCard(scope) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(78.dp).themed(ColorGroup.ACCENT, scope, CircleShape), contentAlignment = Alignment.Center) {
                    val accent = scope[ColorGroup.ACCENT]
                    val track = offTrack(scope)
                    Canvas(Modifier.fillMaxSize().padding(4.dp)) {
                        drawArc(track, 0f, 360f, false, style = Stroke(10f))
                        drawArc(accent, -90f, 250f, false, style = Stroke(10f))
                    }
                    PText("6,912", scope.text(surface), scope, size = 13.sp, weight = FontWeight.Bold)
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    GroupText("Steps", ColorGroup.TEXT_PRIMARY, surface, scope, size = 14.sp, weight = FontWeight.SemiBold)
                    GroupText("Goal 10,000", ColorGroup.TEXT_SECONDARY, surface, scope, size = 11.sp)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        AppCard(scope) {
            CardRow("Sleep", scope, "7 h 12 min")
            CardRow("Heart rate", scope, "64 bpm")
            CardRow("Water", scope, "4 cups", summaryGroup = ColorGroup.ACCENT)
        }
        Spacer(Modifier.weight(1f))
    }
}

// ---------------------------------------------------------------- Device care

/** Device care: the score and the optimise button. */
@Composable
internal fun PreviewDeviceCare(scope: PreviewScope, modifier: Modifier = Modifier) {
    val background = scope[ColorGroup.BACKGROUND]
    AppScreen(scope, modifier) {
        AppTitle("Device care", null, scope)
        GroupText("Excellent", ColorGroup.ACCENT, background, scope, Modifier.fillMaxWidth(), size = 18.sp, weight = FontWeight.SemiBold, align = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(Modifier.fill(ColorGroup.BUTTON, scope, CircleShape).padding(horizontal = 26.dp, vertical = 9.dp)) {
                PText("Optimize now", scope.on(ColorGroup.BUTTON), scope, size = 12.sp, weight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(14.dp))
        AppCard(scope) {
            CardRow("Battery", scope, "82% · 1 day 4 h left")
            CardRow("Storage", scope, "143 GB / 256 GB")
            CardRow("Memory", scope, "6.1 GB / 12 GB")
        }
        Spacer(Modifier.height(8.dp))
        AppCard(scope) { CardRow("Auto optimization", scope, "Daily at 3:00 AM", switch = true) }
        Spacer(Modifier.weight(1f))
    }
}
