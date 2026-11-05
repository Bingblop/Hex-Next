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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hexnext.engine.compile.ColorGroup
import com.hexnext.themer.ui.studio.PreviewIcon
import com.hexnext.themer.ui.studio.StudioPreviewState

private data class DefaultCategory(val key: String, val label: String, val argb: Long)

private val DefaultCategories = listOf(
    DefaultCategory("images", "Images", 0xFFFF6B8A),
    DefaultCategory("videos", "Videos", 0xFF8C6CFF),
    DefaultCategory("audio", "Audio files", 0xFFFF9F43),
    DefaultCategory("documents", "Documents", 0xFF3E91FF),
    DefaultCategory("downloads", "Downloads", 0xFF22B8A6),
    DefaultCategory("apk", "APK files", 0xFF34C759),
    DefaultCategory("compressed", "Compressed", 0xFFF6B92B),
    DefaultCategory("favourites", "Favourites", 0xFFFF5A6E),
)

private const val CATEGORY_COUNT = 8

/** Rendered My Files icons first (unique by key), then default categories up to eight. */
internal fun categories(icons: List<PreviewIcon>): List<PreviewIcon> {
    val rendered = icons.distinctBy { categoryKey(it.key) ?: it.key }.take(CATEGORY_COUNT)
    if (rendered.size >= CATEGORY_COUNT) return rendered
    val taken = rendered.mapNotNullTo(HashSet()) { categoryKey(it.key) }
    val fill = DefaultCategories
        .filter { it.key !in taken }
        .take(CATEGORY_COUNT - rendered.size)
        .map { PreviewIcon(key = "default:${it.key}", label = it.label, bitmap = null, fallbackArgb = it.argb.toInt()) }
    return rendered + fill
}

private fun categoryKey(key: String): String? {
    val name = key.substringAfterLast(':').substringAfterLast('/')
        .removePrefix("home_category_")
        .removePrefix("file_type_")
        .lowercase()
    return when (name) {
        "image", "images" -> "images"
        "video", "videos" -> "videos"
        "audio", "audio_files" -> "audio"
        "document", "documents" -> "documents"
        "download", "downloads" -> "downloads"
        "apk", "apks", "installation_files" -> "apk"
        "compressed", "archive", "archives" -> "compressed"
        "favorite", "favorites", "favourite", "favourites" -> "favourites"
        else -> null
    }
}

/** The six tiles of the One UI 9 category card, in its order (two columns). */
private val GridCategories = listOf(
    DefaultCategory("downloads", "Downloads", 0xFF2E8F86),
    DefaultCategory("documents", "Docs", 0xFFD49A2A),
    DefaultCategory("images", "Images", 0xFFE0577A),
    DefaultCategory("audio", "Audio", 0xFF6E7BE0),
    DefaultCategory("videos", "Videos", 0xFF9B5DE5),
    DefaultCategory("apk", "Install files", 0xFF7DB33A),
)

/** The category card's six tiles: the theme's icon for a category when it has one, else One UI's. */
internal fun gridCategories(icons: List<PreviewIcon>): List<Pair<String, PreviewIcon?>> {
    val byKey = icons.associateBy { categoryKey(it.key) }
    return GridCategories.map { it.label to byKey[it.key] }
}

private fun gridTint(label: String): Color =
    Color(GridCategories.first { it.label == label }.argb)

/**
 * One UI 9 My Files, block by block as the phone shows it: the title, the rounded category card
 * (two columns of six, round tinted icons; the theme's icons when it has them), the Recent files
 * card with thumbnails, the Storage list (internal storage with its bar, Google Drive, OneDrive,
 * Network storage), the start of Utilities and the floating search pill.
 */
@Composable
internal fun PreviewMyFiles(state: StudioPreviewState, scope: PreviewScope, modifier: Modifier = Modifier) {
    val background = scope[ColorGroup.BACKGROUND]
    val surface = scope[ColorGroup.SURFACE]
    val accent = scope[ColorGroup.ACCENT]
    val cardShape = RoundedCornerShape(22.dp)
    Box(modifier.fillMaxSize().fill(ColorGroup.BACKGROUND, scope)) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Spacer(Modifier.height(18.dp))
            Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                GroupText("My Files", ColorGroup.ACCENT, background, scope, size = 19.sp, weight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Icon(Icons.Filled.MoreVert, contentDescription = null, tint = scope.text(background, ColorGroup.ICON_TINT), modifier = Modifier.size(18.dp).themed(ColorGroup.ICON_TINT, scope, CircleShape))
            }
            Column(
                Modifier.fillMaxWidth().fill(ColorGroup.SURFACE, scope, cardShape).padding(horizontal = 10.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (row in gridCategories(state.myFilesIcons).chunked(2)) {
                    Row(Modifier.fillMaxWidth()) {
                        for ((label, icon) in row) {
                            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                CategoryIcon(label, icon, surface, state.iconsLoading, scope)
                                Spacer(Modifier.width(9.dp))
                                GroupText(label, ColorGroup.TEXT_PRIMARY, surface, scope, size = 11.sp, weight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }
            RecentCard(surface, cardShape, scope)
            SectionLabel("Storage", background, scope)
            Column(Modifier.fillMaxWidth().fill(ColorGroup.SURFACE, scope, cardShape).padding(vertical = 4.dp)) {
                StorageRow("Internal storage", "21% used", accent, 0.21f, surface, scope)
                StorageRow("Google Drive", "32.15 TB free", accent, null, surface, scope)
                StorageRow("OneDrive", "Not signed in", null, null, surface, scope)
                StorageRow("Network storage", null, null, null, surface, scope, divider = false)
            }
            SectionLabel("Utilities", background, scope)
            Box(Modifier.fillMaxWidth().height(40.dp).fill(ColorGroup.SURFACE, scope, cardShape))
        }
        SearchPill(surface, scope, Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp))
    }
}

/** A category's round icon: the theme's bitmap, else One UI's tinted circle with a glyph-like dot. */
@Composable
private fun CategoryIcon(label: String, icon: PreviewIcon?, surface: Color, loading: Boolean, scope: PreviewScope) {
    val bitmap = icon?.bitmap
    if (icon != null && bitmap != null) {
        PreviewIconImage(icon, loading, scope.reduceMotion, size = 28.dp, slotPackage = "com.sec.android.app.myfiles")
        return
    }
    val tint = gridTint(label)
    Box(
        Modifier.size(28.dp).clip(CircleShape).background(tint.copy(alpha = if (scope.dark) 0.22f else 0.16f).compositeOver(surface)),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(11.dp).clip(RoundedCornerShape(3.dp)).background(tint))
    }
}

@Composable
private fun SectionLabel(text: String, background: Color, scope: PreviewScope) {
    GroupText(text, ColorGroup.TEXT_SECONDARY, background, scope, modifier = Modifier.padding(start = 12.dp, top = 2.dp), size = 11.sp, weight = FontWeight.Medium)
}

/** The Recent files card: header with chevron and four thumbnails. */
@Composable
private fun RecentCard(surface: Color, shape: RoundedCornerShape, scope: PreviewScope) {
    Column(Modifier.fillMaxWidth().fill(ColorGroup.SURFACE, scope, shape).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GroupText("Recent files", ColorGroup.TEXT_PRIMARY, surface, scope, modifier = Modifier.weight(1f), size = 12.sp, weight = FontWeight.SemiBold)
            PText("›", scope.text(surface, ColorGroup.TEXT_SECONDARY), scope, size = 14.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val photo = Brush.verticalGradient(listOf(Color(0xFFB9C7C9), Color(0xFF8C9A6B)))
            Box(Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(8.dp)).background(photo))
            repeat(3) { i ->
                Box(
                    Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFFF2F2F2)),
                    contentAlignment = Alignment.Center,
                ) {
                    PText(if (i < 2) "TXT" else "MD", Color(0xFF9A9A9A), scope, size = 10.sp, weight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun StorageRow(title: String, detail: String?, detailColor: Color?, used: Float?, surface: Color, scope: PreviewScope, divider: Boolean = true) {
    val secondary = scope.text(surface, ColorGroup.TEXT_SECONDARY)
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 7.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(scope.text(surface, ColorGroup.ICON_TINT).copy(alpha = 0.8f)))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                GroupText(title, ColorGroup.TEXT_PRIMARY, surface, scope, size = 12.sp, weight = FontWeight.Medium)
                if (detail != null) {
                    if (detailColor != null) {
                        GroupText(detail, ColorGroup.ACCENT, surface, scope, size = 10.sp)
                    } else {
                        PText(detail, secondary, scope, size = 10.sp)
                    }
                }
            }
            if (used != null) {
                Box(Modifier.width(54.dp).height(5.dp).clip(CircleShape).background(secondary.copy(alpha = 0.3f))) {
                    Box(Modifier.fillMaxWidth(used).fillMaxHeight().fill(ColorGroup.ACCENT, scope, CircleShape))
                }
            }
        }
        Spacer(Modifier.height(7.dp))
        if (divider) Box(Modifier.padding(start = 24.dp).fillMaxWidth().height(0.5.dp).background(secondary.copy(alpha = 0.25f)))
    }
}

/** The floating search pill One UI 9 keeps at the bottom of My Files and Settings. */
@Composable
internal fun SearchPill(surface: Color, scope: PreviewScope, modifier: Modifier = Modifier) {
    val pill = scope.text(surface, ColorGroup.TEXT_PRIMARY).copy(alpha = 0.10f).compositeOver(surface)
    Row(
        modifier.width(150.dp).height(32.dp).clip(CircleShape).background(pill).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Search, contentDescription = null, tint = scope.text(pill, ColorGroup.TEXT_PRIMARY), modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        PText("Search", scope.text(pill, ColorGroup.TEXT_PRIMARY), scope, Modifier.weight(1f), size = 12.sp)
    }
}
