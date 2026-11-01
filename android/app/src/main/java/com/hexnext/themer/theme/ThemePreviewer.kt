package com.hexnext.themer.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.hexnext.engine.compile.CompiledTarget
import com.hexnext.engine.compile.CompiledTheme
import com.hexnext.engine.compile.CustomAssets
import com.hexnext.engine.compile.Elements
import com.hexnext.engine.compile.OneUiRules
import com.hexnext.engine.compile.PlannedDrawableJob
import com.hexnext.engine.compile.ThemeCompiler
import com.hexnext.engine.compile.ThemeSpec
import com.hexnext.engine.map.PlannedColor
import com.hexnext.engine.pack.DirectoryPackResolver
import com.hexnext.themer.overlay.SettingsTestOverlay
import java.io.IOException

/**
 * Renders a cheap preview of a [ThemeSpec]: the Settings dashboard row icons as the compiler
 * would fabricate them (pack, custom and synthesised ones alike), the first My Files icons, the
 * Settings background the user assigned, the chosen font and the planned accent colour.
 *
 * The last device scan is read with [SnapshotBuilder], the snapshot is restricted to
 * `com.android.settings` and `com.sec.android.app.myfiles` and compiled with [ThemeCompiler]
 * against the installed packs ([ThemeApplier.packsDir]); each icon's best job (non-night,
 * highest density) is rendered with [DrawableRenderer] and decoded into a [Bitmap]. At most
 * [MAX_ICONS] + [MAX_MY_FILES] + 1 bitmaps are decoded per call.
 *
 * [slots] lists, from a full compile of the snapshot, every drawable a custom icon or background
 * can replace; it is heavier than [render] and meant to run only when packs or elements change.
 *
 * Nothing here touches the device or Shizuku. Both calls block on file I/O and bitmap work, so
 * call them on `Dispatchers.Default` or `Dispatchers.IO`, never on the main thread. They never
 * throw: any failure comes back as a [Preview] whose [Preview.note] starts with "Could not", or
 * as an empty [SlotCatalogue].
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
     * @property icons rendered Settings row icons (at most [MAX_ICONS]), custom and synthesised rows first, then dashboard order; see [previewRows].
     * @property accent planned accent ARGB (`sec_widget_round_and_bgcolor`, else the first planned
     *   colour of Settings), null when Settings gets no colours.
     * @property note what the user should know: no scan yet, no icons in the pack, render failures.
     * @property background the first custom Settings background as it will apply, else null.
     * @property myFiles the first [MAX_MY_FILES] My Files icons the theme plans.
     * @property fontFile absolute path of the chosen font's regular file, null for the system default.
     * @property fontName display name of the chosen font.
     * @property synthesised names (without type) of the [icons] the compiler generates in the pack's style.
     */
    data class Preview(
        val icons: List<PreviewIcon>,
        val accent: Int?,
        val note: String?,
        val background: Bitmap? = null,
        val myFiles: List<PreviewIcon> = emptyList(),
        val fontFile: String? = null,
        val fontName: String? = null,
        val synthesised: Set<String> = emptySet(),
    )

    /**
     * A drawable a custom icon can replace.
     *
     * @property key `pkg:drawable/name`, the key of [ThemeSpec.customIcons].
     * @property label human label.
     * @property group section title: "Settings", "My Files" or the element label.
     */
    data class IconSlot(val key: String, val label: String, val group: String)

    /**
     * Every slot the UI offers: icons grouped by [IconSlot.group] (in order) and background slots.
     * [error] says why the lists are empty when building them failed (null without a failure, so an
     * empty catalogue with a null error means no scan yet).
     */
    data class SlotCatalogue(val icons: List<IconSlot>, val backgrounds: List<BackgroundSlots.Slot>, val error: String? = null)

    /** Previews [spec]; safe to call repeatedly. */
    fun render(spec: ThemeSpec): Preview = try {
        renderOrThrow(spec)
    } catch (e: Exception) {
        Preview(emptyList(), null, "Could not render the preview: ${e.javaClass.simpleName}: ${e.message}", fontFile = fontFile(spec), fontName = spec.customFont?.displayName)
    }

    /** The slots of [spec] on this device; empty without a scan, empty with [SlotCatalogue.error] on failure. */
    fun slots(spec: ThemeSpec): SlotCatalogue = try {
        slotsOrThrow(spec)
    } catch (e: Exception) {
        SlotCatalogue(emptyList(), emptyList(), "Could not list the slots: ${e.javaClass.simpleName}: ${e.message}")
    }

    private fun renderOrThrow(spec: ThemeSpec): Preview {
        val fontFile = fontFile(spec)
        val fontName = spec.customFont?.displayName
        val built = SnapshotBuilder(appContext).build()
            ?: return Preview(emptyList(), null, NOTE_NO_SCAN, fontFile = fontFile, fontName = fontName)
        val snapshot = built.snapshot
        val restricted = snapshot.copy(targets = snapshot.targets.filterKeys { it == TARGET || it == MY_FILES })
        val packs = DirectoryPackResolver(ThemeApplier.packsDir(appContext))
        val compiled = ThemeCompiler.compile(spec, restricted, packs)
        val settings = compiled.target(TARGET)
        val myFilesTarget = compiled.target(MY_FILES)
        if (settings == null && myFilesTarget == null) {
            val reason = compiled.skippedTargets.firstOrNull { it.name == TARGET }?.reason
            return Preview(emptyList(), null, skippedNote(reason), fontFile = fontFile, fontName = fontName)
        }

        val accent = settings?.let { accentOf(it) }
        val rowJobs = settings?.let { bestJobs(it, SettingsTestOverlay.DASHBOARD_ROW_ICONS.toHashSet()) }.orEmpty()
        val myFilesJobs = myFilesTarget?.let { myFilesJobs(it) }.orEmpty()
        val backgroundJob = listOfNotNull(settings, myFilesTarget).firstNotNullOfOrNull { backgroundJob(spec, it) }

        val icons = ArrayList<PreviewIcon>(MAX_ICONS)
        val myFiles = ArrayList<PreviewIcon>(MAX_MY_FILES)
        val synthesised = HashSet<String>()
        var background: Bitmap? = null
        var failed = 0
        DrawableRenderer(packs, CustomAssetStore.rootDir(appContext), IconSynthesizer(appContext)).use { renderer ->
            for (name in previewRows(rowJobs)) {
                if (icons.size >= MAX_ICONS) break
                val job = rowJobs[name] ?: continue
                val bitmap = try {
                    decode(renderer.render(job).png)
                } catch (e: Exception) {
                    failed++
                    null
                }
                if (job.synthesis != null) synthesised += name
                icons += PreviewIcon(name, labelFor(name), bitmap)
            }
            for (job in myFilesJobs) {
                if (myFiles.size >= MAX_MY_FILES) break
                val name = job.resourceName.substringAfter('/')
                val bitmap = try {
                    decode(renderer.render(job).png)
                } catch (e: Exception) {
                    failed++
                    null
                }
                myFiles += PreviewIcon(name, BackgroundSlots.humanise(name), bitmap)
            }
            if (backgroundJob != null) {
                background = try {
                    decode(renderer.render(backgroundJob).png)
                } catch (e: Exception) {
                    failed++
                    null
                }
            }
        }
        val note = when {
            failed == 1 -> "1 image could not be rendered."
            failed > 1 -> "$failed images could not be rendered."
            icons.isEmpty() && settings != null -> noIconsNote(spec, packs)
            icons.isEmpty() -> skippedNote(compiled.skippedTargets.firstOrNull { it.name == TARGET }?.reason)
            else -> null
        }
        return Preview(icons, accent, note, background, myFiles, fontFile, fontName, synthesised)
    }

    private fun slotsOrThrow(spec: ThemeSpec): SlotCatalogue {
        val built = SnapshotBuilder(appContext).build() ?: return SlotCatalogue(emptyList(), emptyList())
        val snapshot = built.snapshot
        val rules = OneUiRules.forProfile(snapshot.oneUi)
        val backgrounds = BackgroundSlots.forSnapshot(snapshot, rules)
        val icons = ArrayList<IconSlot>()
        val seen = HashSet<String>()

        // Settings dashboard rows the device defines, in dashboard order, then any extra rule rows.
        val settingsTable = snapshot.targets[TARGET]?.table
        if (settingsTable != null) {
            val rows = (SettingsTestOverlay.DASHBOARD_ROW_ICONS + rules.dashboardRows.map { ThemeCompiler.DASHBOARD_ICON_PREFIX + it }).distinct()
            for (name in rows) {
                if (settingsTable.find("drawable", name) == null) continue
                val key = "$TARGET:drawable/$name"
                if (seen.add(key)) icons += IconSlot(key, labelFor(name), GROUP_SETTINGS)
            }
        }
        // Curated My Files icons present on the device.
        val myFilesTable = snapshot.targets[MY_FILES]?.table
        if (myFilesTable != null) {
            for (entry in myFilesTable.entries) {
                if (entry.type != "drawable" || !isCuratedMyFiles(entry.name)) continue
                val key = "$MY_FILES:drawable/${entry.name}"
                if (seen.add(key)) icons += IconSlot(key, BackgroundSlots.humanise(entry.name), GROUP_MY_FILES)
            }
        }
        // Every other drawable the current packs target, grouped by element. The compile runs
        // without the custom overrides: the compiler replaces overridden pack jobs with custom
        // ones, which would drop every assigned slot from the list and leave it un-assignable.
        val packs = DirectoryPackResolver(ThemeApplier.packsDir(appContext))
        val plain = spec.copy(customIcons = emptyMap(), customBackgrounds = emptyMap(), synthesizeMissingIcons = false)
        val compiled: CompiledTheme = ThemeCompiler.compile(plain, snapshot, packs, rules)
        val byElement = LinkedHashMap<String, MutableList<IconSlot>>()
        val backgroundKeys = backgrounds.mapTo(HashSet()) { it.key }
        for (target in compiled.targets) {
            for (job in target.drawables) {
                if (job.custom != null || job.synthesis != null) continue
                if (!job.resourceName.contains(":drawable/")) continue
                val key = job.resourceName
                if (key in backgroundKeys || !seen.add(key)) continue
                val name = key.substringAfter('/')
                val element = Elements.byId(job.elementId)
                val group = element?.label ?: job.elementId
                val label = if (element == null || element.anyTarget) "${BackgroundSlots.humanise(name)} · ${target.packageName}" else BackgroundSlots.humanise(name)
                byElement.getOrPut(group) { ArrayList() } += IconSlot(key, label, group)
            }
        }
        for (element in Elements.ALL) {
            byElement[element.label]?.let { list -> icons += list.sortedBy { it.label } }
        }
        for ((group, list) in byElement) {
            if (Elements.ALL.none { it.label == group }) icons += list.sortedBy { it.label }
        }
        return SlotCatalogue(icons, backgrounds)
    }

    /**
     * The dashboard rows to preview, in priority order: the first custom row and the first
     * synthesised row (so each kind is seen when present), then every other custom or
     * synthesised row, then the leading dashboard rows, all in dashboard order within a group.
     * Rows most packs lack sit late in the dashboard and would never reach the first [MAX_ICONS].
     */
    internal fun previewRows(rowJobs: Map<String, PlannedDrawableJob>): List<String> {
        val present = SettingsTestOverlay.DASHBOARD_ROW_ICONS.filter { it in rowJobs }
        val custom = present.filter { rowJobs[it]?.custom != null }
        val synthesised = present.filter { rowJobs[it]?.synthesis != null }
        val ordered = LinkedHashSet<String>()
        custom.firstOrNull()?.let { ordered += it }
        synthesised.firstOrNull()?.let { ordered += it }
        ordered += custom
        ordered += synthesised
        ordered += present
        return ordered.toList()
    }

    /** Icon name → the job to render: non-night with the highest density, else any non-night, else any. */
    private fun bestJobs(target: CompiledTarget, wanted: Set<String>): Map<String, PlannedDrawableJob> {
        val byName = target.drawables
            .filter { it.resourceName.substringAfter('/') in wanted }
            .groupBy { it.resourceName.substringAfter('/') }
        val best = LinkedHashMap<String, PlannedDrawableJob>()
        for ((name, candidates) in byName) {
            best[name] = bestOf(candidates) ?: continue
        }
        return best
    }

    private fun bestOf(candidates: List<PlannedDrawableJob>): PlannedDrawableJob? {
        val day = candidates.filterNot { isNight(it.configuration) }
        // A pack with only night variants still gets a preview, from its night job.
        val pool = if (day.isNotEmpty()) day else candidates
        return pool.maxByOrNull { densityRank(it.configuration) }
    }

    /**
     * The first [MAX_MY_FILES] My Files icon jobs (element `my_files_icons`, or custom icons of the
     * target), best configuration each; custom icons first, since the compiler appends them after
     * the pack's own jobs and a pack with 8 or more icons would otherwise hide them.
     */
    private fun myFilesJobs(target: CompiledTarget): List<PlannedDrawableJob> {
        val grouped = target.drawables
            .filter { (it.elementId == MY_FILES_ICONS_ELEMENT || it.custom != null) && it.source.category != CustomAssets.CATEGORY_BACKGROUND }
            .groupBy { it.resourceName }
            .entries
            .sortedByDescending { (_, candidates) -> candidates.any { it.custom != null } }
        val out = ArrayList<PlannedDrawableJob>()
        for ((_, candidates) in grouped) {
            if (out.size >= MAX_MY_FILES) break
            out += bestOf(candidates) ?: continue
        }
        return out
    }

    /** The job of the first custom background of [target] in the spec (non-night, best density), or null. */
    private fun backgroundJob(spec: ThemeSpec, target: CompiledTarget): PlannedDrawableJob? {
        val key = spec.customBackgrounds.keys.firstOrNull { it.startsWith(target.packageName + ":") } ?: return null
        val candidates = target.drawables.filter { it.resourceName == key && it.custom != null }
        return bestOf(candidates)
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

    /** Absolute path of the chosen font's regular file when it exists, else null. */
    private fun fontFile(spec: ThemeSpec): String? {
        val font = spec.customFont ?: return null
        val file = runCatching { CustomAssetStore(appContext).file(CustomAssetStore.Kind.FONT, font.regular) }.getOrNull() ?: return null
        return if (file.isFile) file.absolutePath else null
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
        private const val MY_FILES = "com.sec.android.app.myfiles"
        private const val ACCENT_COLOR = "sec_widget_round_and_bgcolor"
        /** [com.hexnext.engine.compile.Element.id] of the Settings row icons. */
        private const val ICONS_ELEMENT = "settings_icons"
        private const val MY_FILES_ICONS_ELEMENT = "my_files_icons"
        private const val MAX_ICONS = 8
        private const val MAX_MY_FILES = 8
        private const val NAME_PREFIX = "sec_ic_settings_"

        const val GROUP_SETTINGS: String = "Settings"
        const val GROUP_MY_FILES: String = "My Files"

        private const val NOTE_NO_SCAN = "Run a device scan (Diagnostics) to preview the icons on this phone."
        private const val NOTE_NO_ICONS = "This pack has no Settings dashboard icons; Settings keeps Samsung's icons."
        private const val NOTE_NO_ICON_PACK = "No pack is chosen for Settings icons; Settings keeps Samsung's icons."

        private val MY_FILES_EXACT = setOf(
            "home_internal_storage", "home_sd_card", "home_network_storage", "home_recent", "home_downloads",
            "home_trash", "home_favorite", "home_cloud", "home_images", "home_videos", "home_audio", "home_documents", "home_apk",
        )

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

        /** The My Files icon names the slot list curates: category tiles, file type icons and home storage entries. */
        internal fun isCuratedMyFiles(name: String): Boolean =
            name.startsWith("home_category_") || name.startsWith("file_type_") || name in MY_FILES_EXACT

        /** Dashboard rows with labels, for placeholder rows when nothing is rendered yet. */
        internal fun defaultRows(count: Int): List<PreviewIcon> =
            SettingsTestOverlay.DASHBOARD_ROW_ICONS.take(count).map { PreviewIcon(it, labelFor(it), null) }

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
