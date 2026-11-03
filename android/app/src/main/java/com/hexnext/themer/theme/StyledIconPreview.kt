package com.hexnext.themer.theme

import android.content.Context
import android.graphics.Bitmap
import com.hexnext.engine.compile.IconColorStyle
import com.hexnext.engine.compile.IconColors
import com.hexnext.engine.compile.ThemeCompiler
import com.hexnext.engine.palette.ResolvedTheme
import com.hexnext.themer.overlay.SettingsTestOverlay
import java.io.IOException

/**
 * Preview icons drawn on the device without a pack or a scan: one badge per Settings dashboard row
 * (the app's own `hx_glyph_*` art) coloured for an [IconColorStyle], so the preview is never a row
 * of grey circles.
 *
 * The colours come from [IconColors.badgeFor], the same function the compiler's built-in icon
 * jobs use, and the pixels from [IconSynthesizer.drawBadge], the same code the apply renderer
 * runs, so what the preview shows is what applying fabricates.
 *
 * Rendered icons are cached by row, style, colours and size, so moving an unrelated control
 * costs nothing. The returned bitmaps are shared with that cache: never recycle them. One
 * instance may be called from several background threads; call it off the main thread.
 */
class StyledIconPreview(private val context: Context) {
    /** One rendered icon. [key] is the dashboard row (`display`, `connections` …) or My Files category (`image`, `video` …). */
    data class Icon(val key: String, val label: String, val bitmap: Bitmap)

    private val synthesizer: IconSynthesizer by lazy { IconSynthesizer(appContext) }

    /** Rendered badges keyed by glyph, colours and size; small (a few dozen 140 px icons). */
    private val cache = object : android.util.LruCache<CacheKey, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: CacheKey, value: Bitmap): Int = value.byteCount.coerceAtLeast(1)
    }

    private data class CacheKey(val glyph: String, val fill: Int, val fillEnd: Int?, val glyphColor: Int, val sizePx: Int)

    /**
     * Built-in Settings row icons, [sizePx] square, for [style] in [theme] (night or day), in
     * dashboard order, at most [limit]. [IconColorStyle.PACK_ORIGINAL] renders like MULTICOLOR
     * (there is no pack to keep). Empty only when rendering fails.
     *
     * "Dashboard order" is the order of the Settings main list as most devices show it
     * (connections, sounds, notifications, display, wallpaper … like stock One UI), then every
     * other dashboard row.
     */
    fun settingsIcons(style: IconColorStyle, theme: ResolvedTheme, night: Boolean, sizePx: Int, limit: Int): List<Icon> {
        if (limit <= 0 || sizePx <= 0) return emptyList()
        val rows = (PREVIEW_ROWS + SettingsTestOverlay.DASHBOARD_ROW_ICONS.map { it.removePrefix(ThemeCompiler.DASHBOARD_ICON_PREFIX) })
            .distinct()
            .filter { it in IconSynthesizer.GLYPHS }
        val out = ArrayList<Icon>(minOf(limit, rows.size))
        for (row in rows) {
            if (out.size >= limit) break
            val name = ThemeCompiler.DASHBOARD_ICON_PREFIX + row
            val bitmap = render(style, name, theme, night, sizePx) ?: continue
            out += Icon(row, ThemePreviewer.labelFor(name), bitmap)
        }
        return out
    }

    /** The same for My Files categories (`home_category_images`, `videos` …). */
    fun myFilesIcons(style: IconColorStyle, theme: ResolvedTheme, night: Boolean, sizePx: Int, limit: Int): List<Icon> {
        if (limit <= 0 || sizePx <= 0) return emptyList()
        val out = ArrayList<Icon>(minOf(limit, IconColors.MY_FILES_PREVIEW.size))
        for (name in IconColors.MY_FILES_PREVIEW) {
            if (out.size >= limit) break
            val bitmap = render(style, name, theme, night, sizePx) ?: continue
            val key = name.removePrefix(MY_FILES_PREFIX)
            out += Icon(key, ThemePreviewer.myFilesLabel(name), bitmap)
        }
        return out
    }

    /** The badge of [resourceName] for [style], or null when it has no built-in art or drawing failed. */
    private fun render(style: IconColorStyle, resourceName: String, theme: ResolvedTheme, night: Boolean, sizePx: Int): Bitmap? {
        val badge = IconColors.badgeFor(style, resourceName, night, theme) ?: return null
        val key = CacheKey(badge.glyph, badge.fillArgb, badge.fillEndArgb, badge.glyphArgb, sizePx)
        cache.get(key)?.let { return it }
        val bitmap = try {
            synthesizer.drawBadge(badge.glyph, badge.fillArgb, badge.fillEndArgb, badge.glyphArgb, sizePx)
        } catch (e: IOException) {
            return null
        } catch (e: RuntimeException) {
            // A glyph resource that fails to inflate costs only its own tile.
            return null
        } catch (e: OutOfMemoryError) {
            return null
        }
        cache.put(key, bitmap)
        return bitmap
    }

    /** The application context (resources and vector glyphs). */
    internal val appContext: Context get() = context.applicationContext

    private companion object {
        const val MY_FILES_PREFIX = "home_category_"
        const val CACHE_BYTES = 4 * 1024 * 1024

        /** The rows of the stock One UI Settings main list, top first (see the reference screenshots). */
        val PREVIEW_ROWS: List<String> = listOf(
            "connections", "sound", "notifications", "display", "wallpaper", "lockscreen",
            "biometrics_and_security", "cloud_and_account", "google", "galaxy_ai", "devicecare", "applications",
            "general_device_management", "accessibility", "software_update", "about",
        )
    }
}
