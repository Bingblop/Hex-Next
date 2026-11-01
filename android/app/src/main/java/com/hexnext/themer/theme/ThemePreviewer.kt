package com.hexnext.themer.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.hexnext.engine.compile.CompiledTarget
import com.hexnext.engine.compile.PlannedDrawableJob
import com.hexnext.engine.compile.ThemeCompiler
import com.hexnext.engine.compile.ThemeSpec
import com.hexnext.engine.map.PlannedColor
import com.hexnext.engine.pack.DirectoryPackResolver
import com.hexnext.themer.overlay.SettingsTestOverlay
import java.io.IOException

/**
 * Renders a cheap preview of a [ThemeSpec]: the Settings dashboard row icons as the compiler
 * would fabricate them, plus the planned accent colour.
 *
 * The last device scan is read with [SnapshotBuilder], the snapshot is restricted to
 * `com.android.settings` and compiled with [ThemeCompiler] against the installed packs
 * ([ThemeApplier.packsDir]); each row icon's best job (non-night, highest density) is composited
 * with [DrawableRenderer] and decoded into a [Bitmap].
 *
 * Nothing here touches the device or Shizuku. [render] blocks on file I/O and bitmap work, so
 * call it on `Dispatchers.Default` or `Dispatchers.IO`, never on the main thread. It never
 * throws: any failure comes back as a [Preview] whose [Preview.note] starts with "Could not".
 */
class ThemePreviewer(context: Context) {

    private val appContext = context.applicationContext

    /**
     * @property name resource name without type, e.g. `sec_ic_settings_connections`.
     * @property label human label for the row, e.g. "Connections".
     * @property bitmap the rendered icon, null when its job could not be rendered.
     */
    data class PreviewIcon(val name: String, val label: String, val bitmap: Bitmap?)

    /**
     * @property icons rendered row icons in dashboard order (at most [MAX_ICONS]).
     * @property accent planned accent ARGB (`sec_widget_round_and_bgcolor`, else the first planned
     *   colour of Settings), null when Settings gets no colours.
     * @property note what the user should know: no scan yet, no icons in the pack, render failures.
     */
    data class Preview(val icons: List<PreviewIcon>, val accent: Int?, val note: String?)

    /** Previews [spec]; safe to call repeatedly. */
    fun render(spec: ThemeSpec): Preview = try {
        renderOrThrow(spec)
    } catch (e: Exception) {
        Preview(emptyList(), null, "Could not render the preview: ${e.javaClass.simpleName}: ${e.message}")
    }

    private fun renderOrThrow(spec: ThemeSpec): Preview {
        val built = SnapshotBuilder(appContext).build() ?: return Preview(emptyList(), null, NOTE_NO_SCAN)
        val snapshot = built.snapshot
        val restricted = snapshot.copy(targets = snapshot.targets.filterKeys { it == TARGET })
        val packs = DirectoryPackResolver(ThemeApplier.packsDir(appContext))
        val compiled = ThemeCompiler.compile(spec, restricted, packs)
        val target = compiled.target(TARGET)
            ?: return Preview(emptyList(), null, skippedNote(compiled.skippedTargets.firstOrNull { it.name == TARGET }?.reason))

        val accent = accentOf(target)
        val jobs = bestJobs(target)
        if (jobs.isEmpty()) return Preview(emptyList(), accent, noIconsNote(spec, packs))

        val icons = ArrayList<PreviewIcon>(MAX_ICONS)
        var failed = 0
        DrawableRenderer(packs).use { renderer ->
            for (name in SettingsTestOverlay.DASHBOARD_ROW_ICONS) {
                if (icons.size >= MAX_ICONS) break
                val job = jobs[name] ?: continue
                val bitmap = try {
                    decode(renderer.render(job).png)
                } catch (e: Exception) {
                    failed++
                    null
                }
                icons += PreviewIcon(name, labelFor(name), bitmap)
            }
        }
        val note = when (failed) {
            0 -> null
            1 -> "1 icon could not be rendered."
            else -> "$failed icons could not be rendered."
        }
        return Preview(icons, accent, note)
    }

    /** Row icon name → the job to render: non-night with the highest density, else any non-night. */
    private fun bestJobs(target: CompiledTarget): Map<String, PlannedDrawableJob> {
        val wanted = SettingsTestOverlay.DASHBOARD_ROW_ICONS.toHashSet()
        val byName = target.drawables
            .filter { it.resourceName.substringAfter('/') in wanted }
            .groupBy { it.resourceName.substringAfter('/') }
        val best = LinkedHashMap<String, PlannedDrawableJob>()
        for ((name, candidates) in byName) {
            val day = candidates.filterNot { isNight(it.configuration) }
            // A pack with only night variants still gets a preview, from its night job.
            val pool = if (day.isNotEmpty()) day else candidates
            best[name] = pool.maxByOrNull { densityRank(it.configuration) } ?: continue
        }
        return best
    }

    private fun accentOf(target: CompiledTarget): Int? {
        val accent = target.colors.filter { it.resourceName.substringAfter('/') == ACCENT_COLOR }
        val chosen: PlannedColor? = accent.firstOrNull { !isNight(it.configuration) }
            ?: accent.firstOrNull()
            ?: target.colors.firstOrNull()
        return chosen?.argb
    }

    private fun decode(png: ByteArray): Bitmap {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }
        return BitmapFactory.decodeByteArray(png, 0, png.size, options) ?: throw IOException("cannot decode PNG")
    }

    /** Why Settings gets no row icons: no pack chosen for them, pack not installed, or a pack without them. */
    private fun noIconsNote(spec: ThemeSpec, packs: DirectoryPackResolver): String {
        val packId = spec.packFor(ICONS_ELEMENT)
        return when {
            packId == null || !spec.isElementEnabled(ICONS_ELEMENT) -> NOTE_NO_ICON_PACK
            packs.pack(packId) == null -> "Pack $packId is not installed; Settings keeps Samsung's icons."
            else -> NOTE_NO_ICONS
        }
    }

    private fun skippedNote(reason: String?): String = when (reason) {
        null -> "Could not compile Settings from the last scan; re-run the device scan."
        ThemeCompiler.REASON_DISABLED -> "Settings is disabled in this theme, so there is nothing to preview."
        else -> "Could not compile Settings from the last scan ($reason); re-run the device scan."
    }

    companion object {
        private const val TARGET = "com.android.settings"
        private const val ACCENT_COLOR = "sec_widget_round_and_bgcolor"
        /** [com.hexnext.engine.compile.Element.id] of the Settings row icons. */
        private const val ICONS_ELEMENT = "settings_icons"
        private const val MAX_ICONS = 12
        private const val NAME_PREFIX = "sec_ic_settings_"

        private const val NOTE_NO_SCAN = "Run a device scan (Diagnostics) to preview the icons on this phone."
        private const val NOTE_NO_ICONS = "This pack has no Settings dashboard icons; Settings keeps Samsung's icons."
        private const val NOTE_NO_ICON_PACK = "No pack is chosen for Settings icons; Settings keeps Samsung's icons."

        /** Row names whose One UI label differs from the derived form. */
        private val LABELS: Map<String, String> = mapOf(
            "cloud_and_account" to "Accounts and backup",
            "devicecare" to "Device care",
            "advanced_feature" to "Advanced features",
            "general_device_management" to "General management",
            "galaxy_ai" to "Galaxy AI",
            "biometrics_and_security" to "Security and privacy",
            "user_manual" to "User manual",
            "about" to "About phone",
            "applications" to "Apps",
            "multi_devices" to "Connected devices",
        )

        /** True when the FRRO configuration qualifier string contains `night`. */
        internal fun isNight(configuration: String): Boolean = configuration.split('-').contains("night")

        /** Density of a configuration string as a comparable number; 0 without a density qualifier. */
        internal fun densityRank(configuration: String): Int {
            for (token in configuration.split('-')) {
                when (token) {
                    "ldpi" -> return 120
                    "mdpi" -> return 160
                    "tvdpi" -> return 213
                    "hdpi" -> return 240
                    "xhdpi" -> return 320
                    "xxhdpi" -> return 480
                    "xxxhdpi" -> return 640
                    "anydpi", "nodpi" -> return 0
                }
                if (token.endsWith("dpi")) token.removeSuffix("dpi").toIntOrNull()?.let { return it }
            }
            return 0
        }

        /** "sec_ic_settings_cloud_and_account" → "Accounts and backup"; unknown names are derived. */
        internal fun labelFor(name: String): String {
            val key = name.removePrefix(NAME_PREFIX)
            LABELS[key]?.let { return it }
            val words = key.replace('_', ' ').trim()
            if (words.isEmpty()) return name
            return words.replaceFirstChar { it.uppercaseChar() }
        }
    }
}
