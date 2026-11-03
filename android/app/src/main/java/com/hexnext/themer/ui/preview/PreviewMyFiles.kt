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

private class RecentFile(val name: String, val detail: String, val shade: String, val letter: String)

private val RecentFiles = listOf(
    RecentFile("IMG_20261102_0941.jpg", "4.2 MB · Today", "accent3_400", "J"),
    RecentFile("Boarding pass.pdf", "312 KB · Yesterday", "accent1_400", "P"),
    RecentFile("Voice memo 014.m4a", "1.8 MB · 31 Oct", "accent2_400", "A"),
)

/** My Files: header, recent thumbnails, a category grid, a storage card and a recent list. */
@Composable
internal fun PreviewMyFiles(state: StudioPreviewState, scope: PreviewScope, modifier: Modifier = Modifier) {
    val background = scope[ColorGroup.BACKGROUND]
    val surface = scope[ColorGroup.SURFACE]
    val cardShape = RoundedCornerShape(20.dp)
    Column(
        modifier = modifier
            .fillMaxSize()
            .fill(ColorGroup.BACKGROUND, scope)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            GroupText(
                "My Files",
                ColorGroup.TEXT_PRIMARY,
                background,
                scope,
                modifier = Modifier.padding(start = 6.dp),
                size = 22.sp,
                weight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            for (icon in listOf(Icons.Filled.Search, Icons.Filled.MoreVert)) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = scope.text(background, ColorGroup.ICON_TINT),
                    modifier = Modifier
                        .size(26.dp)
                        .themed(ColorGroup.ICON_TINT, scope, CircleShape)
                        .padding(3.dp),
                )
            }
        }
        RecentStrip(scope)
        Column(
            Modifier
                .fillMaxWidth()
                .fill(ColorGroup.SURFACE, scope, cardShape)
                .padding(vertical = 12.dp, horizontal = 6.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            for (row in categories(state.myFilesIcons).chunked(4)) {
                Row(Modifier.fillMaxWidth()) {
                    for (icon in row) {
                        key(icon.key) {
                            CategoryCell(icon, surface, state.iconsLoading, scope, Modifier.weight(1f))
                        }
                    }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        StorageCard(scope, cardShape)
        Column(
            Modifier
                .fillMaxWidth()
                .fill(ColorGroup.SURFACE, scope, cardShape)
                .padding(vertical = 4.dp),
        ) {
            for (file in RecentFiles) {
                RecentRow(file, surface, scope)
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun RecentStrip(scope: PreviewScope) {
    val background = scope[ColorGroup.BACKGROUND]
    val accent = scope[ColorGroup.ACCENT]
    val p = scope.palette
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GroupText("Recent files", ColorGroup.TEXT_PRIMARY, background, scope, Modifier.padding(start = 6.dp), size = 12.sp, weight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            GroupText("See all", ColorGroup.ACCENT, background, scope, Modifier.padding(end = 6.dp), size = 11.sp, weight = FontWeight.Medium)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val thumbs = listOf(
                listOf(p.shade("accent1_200", accent), p.shade("accent1_500", accent)),
                listOf(p.shade("accent3_200", accent), p.shade("accent3_500", accent)),
                listOf(p.shade("accent2_200", accent), p.shade("accent2_500", accent)),
            )
            for (colors in thumbs) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(58.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Brush.linearGradient(colors))
                        .themed(ColorGroup.SURFACE, scope, RoundedCornerShape(14.dp)),
                )
            }
        }
    }
}

@Composable
private fun CategoryCell(icon: PreviewIcon, surface: Color, loading: Boolean, scope: PreviewScope, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        PreviewIconImage(icon, loading, scope.reduceMotion, size = 34.dp)
        Spacer(Modifier.height(5.dp))
        GroupText(icon.label, ColorGroup.TEXT_PRIMARY, surface, scope, size = 9.5.sp, align = TextAlign.Center)
    }
}

@Composable
private fun StorageCard(scope: PreviewScope, shape: RoundedCornerShape) {
    val surface = scope[ColorGroup.SURFACE]
    Column(
        Modifier
            .fillMaxWidth()
            .fill(ColorGroup.SURFACE, scope, shape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GroupText("Internal storage", ColorGroup.TEXT_PRIMARY, surface, scope, Modifier.weight(1f), size = 13.sp, weight = FontWeight.Medium)
            GroupText("84.2 GB / 256 GB", ColorGroup.TEXT_SECONDARY, surface, scope, size = 10.5.sp)
        }
        ProgressBar(
            fraction = 0.33f,
            group = ColorGroup.ACCENT,
            track = scope.text(surface, ColorGroup.TEXT_SECONDARY).copy(alpha = 0.2f),
            scope = scope,
            modifier = Modifier.fillMaxWidth(),
            height = 7.dp,
        )
        Row {
            GroupText("Analyse storage", ColorGroup.ACCENT, surface, scope, Modifier.weight(1f), size = 11.sp, weight = FontWeight.Medium)
            GroupText("Samsung Cloud", ColorGroup.ACCENT, surface, scope, size = 11.sp, weight = FontWeight.Medium)
        }
    }
}

@Composable
private fun RecentRow(file: RecentFile, surface: Color, scope: PreviewScope) {
    val tile = scope.palette.shade(file.shade, scope[ColorGroup.ACCENT])
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(tile),
            contentAlignment = Alignment.Center,
        ) {
            PText(file.letter, contentColorFor(tile), scope, size = 12.sp, weight = FontWeight.SemiBold)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            GroupText(file.name, ColorGroup.TEXT_PRIMARY, surface, scope, size = 12.sp, weight = FontWeight.Medium)
            GroupText(file.detail, ColorGroup.TEXT_SECONDARY, surface, scope, size = 10.sp)
        }
    }
}
