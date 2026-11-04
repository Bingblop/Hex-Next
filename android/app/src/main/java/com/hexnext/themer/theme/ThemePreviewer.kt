package com.hexnext.themer.theme

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asImageBitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.hexnext.engine.compile.CompiledTarget
import com.hexnext.engine.compile.CompiledTheme
import com.hexnext.engine.compile.CustomAssets
import com.hexnext.engine.compile.Elements
import com.hexnext.engine.compile.IconColorStyle
import com.hexnext.engine.compile.OneUiRules
import com.hexnext.engine.compile.PlannedDrawableJob
import com.hexnext.engine.compile.ThemeCompiler
import com.hexnext.themer.ui.studio.DeviceColors
import com.hexnext.themer.ui.studio.ResourceColors
import com.hexnext.engine.compile.ThemeSpec
import com.hexnext.engine.map.PlannedColor
import com.hexnext.engine.pack.DirectoryPackResolver
import com.hexnext.themer.overlay.SettingsTestOverlay
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import java.lang.ref.SoftReference

/**
 * Renders a cheap preview of a [ThemeSpec]: the Settings dashboard row icons as the compiler
 * would fabricate them (pack, custom and synthesised ones alike), the first My Files icons, the
 * Settings background the user assigned, the chosen font and the planned accent colour.
 *
 * The last device scan is read with [SnapshotBuilder], restricted to `com.android.settings` and
 * `com.sec.android.app.myfiles` (only those two resource tables are read from disk) and compiled
 * with [ThemeCompiler] against the installed packs ([ThemeApplier.packsDir]); each icon's best
 * job (in the requested day or night mode, highest density) is rendered with [DrawableRenderer] and decoded into a
 * [Bitmap]. At most [MAX_ICONS] + [MAX_MY_FILES] + 1 bitmaps are decoded per call.
 *
 * [slots] lists, from a full compile of the snapshot, every drawable a custom icon or background
 * can replace; it is heavier than [render] and meant to run only when packs or elements change.
 *
 * Caches, all keyed on what is on disk so a new scan, pack or custom file is picked up on the
 * next call: the two-target and the full snapshot (soft references, keyed by
 * [SnapshotBuilder.scanStamp]), one [DirectoryPackResolver] per pack directory listing (the
 * resolver opens every pack zip once), and the rendered icons by job plus pack listing
 * ([ICON_CACHE_BYTES] of bitmaps), plus the one custom background, decoded at mockup width and
 * kept apart so it never evicts the icons. Rendering is serialised on one lock, so bitmaps, zip
 * handles and the compile never run on two threads at once; a superseded render stops at its next
 * cancellation check (after the snapshot, after the compile, before each image) and frees the lock.
 *
 * Nothing here touches the device or Shizuku. Both calls block on file I/O and bitmap work, so
 * call them on `Dispatchers.Default` or `Dispatchers.IO`, never on the main thread. They never
 * throw: any failure comes back as a [Preview] whose [Preview.note] starts with "Could not", or
 * as an empty [SlotCatalogue].
 */
class ThemePreviewer(context: Context) {

    private val appContext = context.applicationContext

    /** Guards every field below and serialises [render] / [slots]. */
    private val lock = Any()

    private val synthesizer: IconSynthesizer by lazy { IconSynthesizer(appContext) }

    private var scanStamp: String? = null
    private var previewSnapshot: SoftReference<SnapshotBuilder.Result>? = null
    private var fullSnapshot: SoftReference<SnapshotBuilder.Result>? = null

    private var packsStamp: String? = null
    private var packResolver: LayeredPackResolver? = null

    /** A rendered job and the file stamps its pixels depend on besides the pack listing. */
    private data class IconKey(val job: PlannedDrawableJob, val customStamp: String?)

    private val iconCache = object : LruCache<IconKey, Bitmap>(ICON_CACHE_BYTES) {
        override fun sizeOf(key: IconKey, value: Bitmap): Int = value.byteCount.coerceAtLeast(1)
    }

    /** The last preview background and the job it was rendered from (see [backgroundBitmap]). */
    private var backgroundCache: Pair<IconKey, Bitmap>? = null

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
     * @property builtIn names (without type) of the [icons] and [myFiles] icons drawn from the app's
     *   built-in art ([PlannedDrawableJob.badge]: no pack covers them and built-in icons are on).
     * @property iconStyle the icon colour style the icons were rendered with.
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
        val builtIn: Set<String> = emptySet(),
        val iconStyle: IconColorStyle = IconColorStyle.PACK_ORIGINAL,
        val deviceColors: DeviceColors = DeviceColors(),
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

    /**
     * Previews [spec] as the phone shows it in dark mode ([night] true) or light mode: night jobs
     * are preferred for [night], day jobs otherwise, falling back to the other mode when a
     * resource has no job in the wanted one (as the device falls back to the default
     * configuration). Safe to call repeatedly and from any background thread.
     */
    fun render(spec: ThemeSpec, night: Boolean = false, cancelled: () -> Boolean = { false }): Preview = synchronized(lock) {
        try {
            renderOrThrow(spec, night, cancelled)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Preview(emptyList(), null, "Could not render the preview: ${e.javaClass.simpleName}: ${e.message}", fontFile = fontFile(spec), fontName = spec.customFont?.displayName)
        }
    }

    /** The slots of [spec] on this device; empty without a scan, empty with [SlotCatalogue.error] on failure. */
    fun slots(spec: ThemeSpec): SlotCatalogue = synchronized(lock) {
        try {
            slotsOrThrow(spec)
        } catch (e: Exception) {
            SlotCatalogue(emptyList(), emptyList(), "Could not list the slots: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun renderOrThrow(spec: ThemeSpec, night: Boolean, cancelled: () -> Boolean): Preview {
        fun checkCancelled() {
            if (cancelled()) throw CancellationException("Preview render superseded")
        }
        checkCancelled()
        val fontFile = fontFile(spec)
        val fontName = spec.customFont?.displayName
        val built = snapshot(full = false)
            ?: return Preview(emptyList(), null, NOTE_NO_SCAN, fontFile = fontFile, fontName = fontName)
        checkCancelled()
        // Built for the two preview targets only; equal to the full snapshot filtered to them.
        val restricted = built.snapshot
        val packs = packResolver()
        val compiled = ThemeCompiler.compile(spec, restricted, packs)
        checkCancelled()
        val settings = compiled.target(TARGET)
        val myFilesTarget = compiled.target(MY_FILES)
        if (settings == null && myFilesTarget == null) {
            val reason = compiled.skippedTargets.firstOrNull { it.name == TARGET }?.reason
            return Preview(emptyList(), null, skippedNote(reason), fontFile = fontFile, fontName = fontName)
        }

        val accent = settings?.let { accentOf(it, night) }
        val rowJobs = settings?.let { bestJobs(it, SettingsTestOverlay.DASHBOARD_ROW_ICONS.toHashSet(), night) }.orEmpty()
        val myFilesJobs = myFilesTarget?.let { myFilesJobs(it, night) }.orEmpty()
        val backgroundJob = listOfNotNull(settings, myFilesTarget).firstNotNullOfOrNull { backgroundJob(spec, it, night) }

        val icons = ArrayList<PreviewIcon>(MAX_ICONS)
        val myFiles = ArrayList<PreviewIcon>(MAX_MY_FILES)
        val synthesised = HashSet<String>()
        val builtIn = HashSet<String>()
        var background: Bitmap? = null
        var keyboardKey: Bitmap? = null
        val keyboardTarget = compiled.target(KEYBOARD)
        val keyJob = keyboardTarget?.let { bestJobs(it, setOf("key_bg"), night)["key_bg"] }
        var failed = 0
        val customRoot = CustomAssetStore.rootDir(appContext)
        DrawableRenderer(packs, customRoot, synthesizer).use { renderer ->
            fun bitmapOf(job: PlannedDrawableJob): Bitmap? {
                checkCancelled()
                val bitmap = cachedBitmap(renderer, job, customRoot)
                if (bitmap == null) failed++
                return bitmap
            }
            for (name in previewRows(rowJobs)) {
                if (icons.size >= MAX_ICONS) break
                val job = rowJobs[name] ?: continue
                val bitmap = bitmapOf(job)
                if (job.synthesis != null) synthesised += name
                if (job.badge != null) builtIn += name
                icons += PreviewIcon(name, labelFor(name), bitmap)
            }
            for (job in myFilesJobs) {
                if (myFiles.size >= MAX_MY_FILES) break
                val name = job.resourceName.substringAfter('/')
                val label = myFilesLabel(name)
                // "home_category_image" and "home_category_images" are the same tile.
                if (myFiles.any { it.label == label }) continue
                if (job.badge != null) builtIn += name
                myFiles += PreviewIcon(name, label, bitmapOf(job))
            }
            // The pack's key art, as Samsung Keyboard draws it under every key (lab-024).
            if (keyJob != null) keyboardKey = bitmapOf(keyJob)
            if (backgroundJob != null) {
                checkCancelled()
                background = backgroundBitmap(renderer, backgroundJob, customRoot)
                if (background == null) failed++
            }
        }
        val note = when {
            failed == 1 -> "1 image could not be rendered."
            failed > 1 -> "$failed images could not be rendered."
            icons.isEmpty() && settings != null -> noIconsNote(spec, packs)
            icons.isEmpty() -> skippedNote(compiled.skippedTargets.firstOrNull { it.name == TARGET }?.reason)
            else -> null
        }
        val deviceColors = DeviceColors(
            systemUi = colorsOf(compiled.target(SYSTEM_UI)),
            settings = colorsOf(settings),
            myFiles = colorsOf(myFilesTarget),
            keyboard = colorsOf(keyboardTarget),
            keyboardKey = keyboardKey?.asImageBitmap(),
        )
        return Preview(icons, accent, note, background, myFiles, fontFile, fontName, synthesised, builtIn, spec.iconStyle, deviceColors)
    }

    private fun slotsOrThrow(spec: ThemeSpec): SlotCatalogue {
        val built = snapshot(full = true) ?: return SlotCatalogue(emptyList(), emptyList())
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
        val packs = packResolver()
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
                val label = if (element == null || element.anyTarget) "${BackgroundSlots.humanise(name)} · ${appLabel(target.packageName)}" else BackgroundSlots.humanise(name)
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
     * The installed app's name for [pkg] (users do not know package names), else its last
     * package segment, capitalised.
     */
    private fun appLabel(pkg: String): String = when (pkg) {
        "android" -> "Android framework"
        "com.android.systemui" -> "System UI"
        else -> runCatching {
            val pm = appContext.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrNull()?.takeIf { it.isNotBlank() && it != pkg }
            ?: pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
    }

    // ---- caches -----------------------------------------------------------------------------

    /**
     * The snapshot of the latest scan, built for the two preview targets or in [full]; null
     * without a scan. Rebuilt only when [SnapshotBuilder.scanStamp] changes or the soft
     * reference was cleared under memory pressure. A scan that saved no system shades builds
     * from the live palette, which can change at any time, so that case is never cached.
     */
    private fun snapshot(full: Boolean): SnapshotBuilder.Result? {
        val stamp = SnapshotBuilder.scanStamp(appContext) ?: return null
        if (stamp != scanStamp) {
            scanStamp = stamp
            previewSnapshot = null
            fullSnapshot = null
        }
        (if (full) fullSnapshot else previewSnapshot)?.get()?.let { return it }
        val built = SnapshotBuilder(appContext).build(only = if (full) null else PREVIEW_TARGETS) ?: return null
        if (SnapshotBuilder.hasSavedShades(appContext)) {
            if (full) fullSnapshot = SoftReference(built) else previewSnapshot = SoftReference(built)
        }
        return built
    }

    /**
     * One resolver per state of the pack directory. [DirectoryPackResolver] opens and parses
     * every pack zip on its first lookup and caches the result for its lifetime, so it is kept
     * until a zip is added, replaced or removed; the rendered icons are dropped with it.
     */
    private fun packResolver(): LayeredPackResolver {
        // Installed packs and locked packs downloaded for the preview only (PackDirs.forPreview):
        // rendered in memory here, never built into an overlay.
        val dirs = PackDirs.forPreview(appContext.filesDir)
        val stamp = dirs.joinToString("\n--\n") { dir ->
            dir.listFiles { f -> f.isFile }.orEmpty()
                .sortedBy { it.name }
                .joinToString("\n") { "${it.name}|${it.length()}|${it.lastModified()}" }
        }
        packResolver?.takeIf { stamp == packsStamp }?.let { return it }
        packsStamp = stamp
        iconCache.evictAll()
        backgroundCache = null
        return LayeredPackResolver.over(dirs).also { packResolver = it }
    }

    /**
     * The decoded render of [job], from the cache when the same job was rendered against the
     * same packs and custom file before; null when rendering fails (failures are not cached, so
     * a fixed file renders on the next call).
     */
    private fun cachedBitmap(renderer: DrawableRenderer, job: PlannedDrawableJob, customRoot: File): Bitmap? {
        val key = IconKey(job, customStamp(job, customRoot))
        iconCache.get(key)?.let { return it }
        val bitmap = try {
            decode(renderer.render(job).png)
        } catch (e: Exception) {
            return null
        } catch (e: OutOfMemoryError) {
            // A corrupt or crafted pack layer must cost only its own preview tile, not crash the app.
            return null
        }
        iconCache.put(key, bitmap)
        return bitmap
    }

    /**
     * The custom background of [job], decoded down to about the mockup's width ([BACKGROUND_PX])
     * and kept in its own one-entry cache: a full-resolution background is 15 to 20 MB and would
     * push nearly every icon out of [iconCache].
     */
    private fun backgroundBitmap(renderer: DrawableRenderer, job: PlannedDrawableJob, customRoot: File): Bitmap? {
        val key = IconKey(job, customStamp(job, customRoot))
        backgroundCache?.takeIf { it.first == key }?.let { return it.second }
        val bitmap = try {
            decodeScaled(renderer.render(job).png, BACKGROUND_PX)
        } catch (e: Exception) {
            return null
        } catch (e: OutOfMemoryError) {
            return null
        }
        backgroundCache = key to bitmap
        return bitmap
    }

    /** Size and mtime of the custom file a job renders, so a re-imported file of the same name re-renders. */
    private fun customStamp(job: PlannedDrawableJob, customRoot: File): String? {
        val file = job.custom ?: return null
        val background = job.source.category == CustomAssets.CATEGORY_BACKGROUND
        val source = File(File(customRoot, if (background) CustomSizing.DIR_BACKGROUNDS else CustomSizing.DIR_ICONS), file)
        return "${source.length()}|${source.lastModified()}"
    }

    // ---- job selection ----------------------------------------------------------------------

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

    /** Icon name → the job to render: see [bestOf]. */
    private fun bestJobs(target: CompiledTarget, wanted: Set<String>, night: Boolean): Map<String, PlannedDrawableJob> {
        val byName = target.drawables
            .filter { it.resourceName.substringAfter('/') in wanted }
            .groupBy { it.resourceName.substringAfter('/') }
        val best = LinkedHashMap<String, PlannedDrawableJob>()
        for ((name, candidates) in byName) {
            best[name] = bestOf(candidates, night) ?: continue
        }
        return best
    }


    /**
     * The first [MAX_MY_FILES] My Files category tile jobs (`home_category_*` drawables of element
     * `my_files_icons`, or custom icons of the target), best configuration each; custom icons
     * first, since the compiler appends them after the pack's own jobs and a pack with 8 or more
     * icons would otherwise hide them. Other My Files drawables (file types, menu glyphs) are not
     * tiles of the category grid, so they are left out.
     */
    private fun myFilesJobs(target: CompiledTarget, night: Boolean): List<PlannedDrawableJob> {
        val grouped = target.drawables
            .filter { (it.elementId == MY_FILES_ICONS_ELEMENT || it.custom != null) && it.source.category != CustomAssets.CATEGORY_BACKGROUND }
            .filter { it.resourceName.substringAfter('/').startsWith(MY_FILES_CATEGORY_PREFIX) }
            .groupBy { it.resourceName }
            .entries
            .sortedByDescending { (_, candidates) -> candidates.any { it.custom != null } }
        val out = ArrayList<PlannedDrawableJob>()
        for ((_, candidates) in grouped) {
            if (out.size >= MAX_MY_FILES) break
            out += bestOf(candidates, night) ?: continue
        }
        return out
    }

    /**
     * The job of the first custom background of [target] in the spec that the compiler kept
     * (wanted mode, best density), or null. A key the compiler skipped (not in the target any
     * more) does not hide a later one; a key the rules renamed is found by its category.
     */
    private fun backgroundJob(spec: ThemeSpec, target: CompiledTarget, night: Boolean): PlannedDrawableJob? {
        val keys = spec.customBackgrounds.keys.filter { it.startsWith(target.packageName + ":") }
        if (keys.isEmpty()) return null
        val custom = target.drawables.filter { it.custom != null && it.source.category == CustomAssets.CATEGORY_BACKGROUND }
        for (key in keys) {
            bestOf(custom.filter { it.resourceName == key }, night)?.let { return it }
        }
        val first = custom.firstOrNull()?.resourceName ?: return null
        return bestOf(custom.filter { it.resourceName == first }, night)
    }

    /**
     * The colours [target] will really write, by resource name: the default configuration and
     * the night one. Other qualifiers (land, sw600dp …) are left out; the phone preview is a
     * portrait phone.
     */
    private fun colorsOf(target: CompiledTarget?): ResourceColors {
        if (target == null) return ResourceColors()
        val day = HashMap<String, Int>()
        val night = HashMap<String, Int>()
        for (c in target.colors) {
            val name = c.resourceName.substringAfter('/')
            when {
                c.configuration.isEmpty() -> day[name] = c.argb
                c.configuration == "night" || c.configuration.startsWith("night-v") -> night[name] = c.argb
            }
        }
        return ResourceColors(day, night)
    }

    private fun accentOf(target: CompiledTarget, night: Boolean): Int? {
        val accent = target.colors.filter { it.resourceName.substringAfter('/') == ACCENT_COLOR }
        val chosen: PlannedColor? = accent.firstOrNull { isNight(it.configuration) == night }
            ?: accent.firstOrNull()
            ?: target.colors.firstOrNull()
        return chosen?.argb
    }

    /** Decodes [png] with a power-of-two sample size so its width stays at or above [targetWidth]. */
    private fun decodeScaled(png: ByteArray, targetWidth: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(png, 0, png.size, bounds)
        var sample = 1
        while (bounds.outWidth > 0 && bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
            inSampleSize = sample
        }
        return BitmapFactory.decodeByteArray(png, 0, png.size, options) ?: throw IOException("cannot decode PNG")
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
    private fun noIconsNote(spec: ThemeSpec, packs: LayeredPackResolver): String {
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
        /**
         * The job of the wanted mode ([night] or day) with the highest density; when there is none in
         * that mode, the best job of the other one (a day-only pack still shows in dark mode, as the
         * phone falls back to it, and a night-only pack still gets a preview in light mode).
         */
        internal fun bestOf(candidates: List<PlannedDrawableJob>, night: Boolean = false): PlannedDrawableJob? {
            val wanted = candidates.filter { isNight(it.configuration) == night }
            val pool = if (wanted.isNotEmpty()) wanted else candidates
            return pool.maxByOrNull { densityRank(it.configuration) }
        }
        private const val TARGET = "com.android.settings"
        private const val MY_FILES = "com.sec.android.app.myfiles"
        private const val SYSTEM_UI = "com.android.systemui"
        private const val KEYBOARD = "com.samsung.android.honeyboard"
        private val PREVIEW_TARGETS = setOf(TARGET, MY_FILES, SYSTEM_UI, KEYBOARD)
        private const val ACCENT_COLOR = "sec_widget_round_and_bgcolor"
        /** [com.hexnext.engine.compile.Element.id] of the Settings row icons. */
        private const val ICONS_ELEMENT = "settings_icons"
        private const val MY_FILES_ICONS_ELEMENT = "my_files_icons"
        private const val MAX_ICONS = 12
        private const val MAX_MY_FILES = 8
        private const val NAME_PREFIX = "sec_ic_settings_"

        /** Rendered preview bitmaps kept between renders: ~300 Settings icons, or a few backgrounds. */
        private const val ICON_CACHE_BYTES = 24 * 1024 * 1024

        /** Width the preview background is decoded to: the mockup draws it about 300 dp wide. */
        private const val BACKGROUND_PX = 540

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

        private const val MY_FILES_CATEGORY_PREFIX = "home_category_"

        /**
         * One UI labels of the My Files categories, keyed by the name after `home_category_`. The
         * same words as the default tiles of the preview grid, so a rendered tile replaces its
         * default instead of appearing next to it.
         */
        private val MY_FILES_LABELS: Map<String, String> = mapOf(
            "image" to "Images",
            "images" to "Images",
            "video" to "Videos",
            "videos" to "Videos",
            "audio" to "Audio files",
            "documents" to "Documents",
            "download" to "Downloads",
            "apk" to "APK files",
            "compressed" to "Compressed",
        )

        /** "home_category_image" → "Images"; unknown names are derived. */
        internal fun myFilesLabel(name: String): String =
            MY_FILES_LABELS[name.removePrefix(MY_FILES_CATEGORY_PREFIX)] ?: BackgroundSlots.humanise(name)

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
