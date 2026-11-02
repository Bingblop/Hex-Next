package com.hexnext.engine.compile

import com.hexnext.engine.arsc.ResourceTable
import com.hexnext.engine.frro.FabricatedOverlayFile
import com.hexnext.engine.log.BuildEvent
import com.hexnext.engine.log.LogLevel
import com.hexnext.engine.map.DrawableSource
import com.hexnext.engine.map.PlannedColor
import com.hexnext.engine.map.PlannedDrawable
import com.hexnext.engine.map.ResourceMapper
import com.hexnext.engine.map.Skipped
import com.hexnext.engine.map.ThemeRequest
import com.hexnext.engine.pack.CompositePlan
import com.hexnext.engine.pack.LayerPlanner
import com.hexnext.engine.pack.Pack
import com.hexnext.engine.palette.ResolvedTheme
import com.hexnext.engine.palette.RoleInference
import com.hexnext.engine.palette.ThemeResolver
import java.security.MessageDigest

/** Supplies theme packs to the compiler (downloaded pack zips on the device). */
public interface PackResolver {
    /** The pack with [id], or null when it is not available. */
    public fun pack(id: String): Pack?

    /**
     * File entry names of pack [id]'s zip ([com.hexnext.engine.pack.PackReader.entryNames]).
     * An empty set means "unknown": layers are then taken from each layer's `resources` list.
     */
    public fun entryNames(id: String): Set<String>
}

/** A [PackResolver] over packs already in memory; [entries] maps pack id → zip entry names. */
public class InMemoryPackResolver(
    packs: Collection<Pack>,
    private val entries: Map<String, Set<String>> = emptyMap(),
) : PackResolver {
    private val byId = packs.associateBy { it.id }

    override fun pack(id: String): Pack? = byId[id]

    override fun entryNames(id: String): Set<String> = entries[id].orEmpty()
}

/**
 * A missing Settings dashboard icon to draw in the pack's style.
 *
 * @property glyph the dashboard row key (`galaxy_ai`); the app maps it to its own glyph artwork.
 * @property reference a pack icon job of the same configuration whose rendered shape and colours
 *   the synthesised icon copies.
 */
public data class IconSynthesis(val glyph: String, val reference: PlannedDrawableJob)

/**
 * One drawable to composite on the device and fabricate.
 *
 * @property resourceName `package:type/name` in the target.
 * @property configuration FRRO configuration (`xxhdpi`, `night-xxhdpi`, `""` …).
 * @property plan layers to composite, bottom first; the app renders it with Bitmaps. Empty for
 *   [custom] and [synthesis] jobs.
 * @property ninePatch true when the result must be fabricated as a 9-patch.
 * @property source pack, category and pack resource name the plan was built from. For a custom
 *   job `DrawableSource(CustomAssets.PACK_ID, CustomAssets.CATEGORY_ICON | CATEGORY_BACKGROUND, file)`;
 *   for a synthesis job the reference's source with the target resource name.
 * @property elementId the [Element] that produced the job.
 * @property custom file name under the app's `custom/<icons|backgrounds>` directory when the
 *   user replaced this drawable with an imported image; [plan] is then empty.
 * @property synthesis set when the icon is generated from a reference pack icon; [plan] is then empty.
 */
public data class PlannedDrawableJob(
    val resourceName: String,
    val configuration: String,
    val plan: CompositePlan,
    val ninePatch: Boolean,
    val source: DrawableSource,
    val elementId: String,
    val custom: String? = null,
    val synthesis: IconSynthesis? = null,
)

/**
 * Overlay content for one target package.
 *
 * @property overlayName fabricated overlay name, `HexNext_<pkg with dots as underscores>`.
 * @property skipped resources not built and why (see the `REASON_*` constants of [ThemeCompiler]
 *   and [ResourceMapper]).
 * @property semwtCoverage distinct planned resources Samsung's SemWT overlay also overrides.
 * @property contentHash SHA-256 (hex) of the sorted colour and drawable entries; equal hashes
 *   mean the overlay does not need to be rebuilt.
 * @property semwtMatches resource names of default-configuration [colors] whose value equals the
 *   one Samsung's SemWT overlay sets (colours kept fixed, or recoloured to the same value), so a
 *   lookup of them cannot tell whether Hex Next or SemWT wins.
 */
public data class CompiledTarget(
    val packageName: String,
    val overlayName: String,
    val colors: List<PlannedColor>,
    val drawables: List<PlannedDrawableJob>,
    val skipped: List<Skipped>,
    val semwtCoverage: Int,
    val contentHash: String,
    val versionCode: Long? = null,
    val semwtMatches: Set<String> = emptySet(),
) {
    /** True when there is nothing to fabricate. */
    public val isEmpty: Boolean get() = colors.isEmpty() && drawables.isEmpty()
}

/**
 * Output of [ThemeCompiler.compile].
 *
 * @property targets compiled targets, in build order.
 * @property events build-log events (one summary per target, warnings for skips).
 * @property skippedTargets target packages not compiled, with the reason.
 */
public data class CompiledTheme(
    val targets: List<CompiledTarget>,
    val events: List<BuildEvent>,
    val skippedTargets: List<Skipped> = emptyList(),
) {
    /** The compiled target for [packageName], or null. */
    public fun target(packageName: String): CompiledTarget? = targets.firstOrNull { it.packageName == packageName }
}

/**
 * Compiles a [ThemeSpec] against a [DeviceSnapshot] into per-target overlay plans.
 *
 * Per enabled, installed, non-debloated target with a resource table:
 * 1. **Colours.** Each colour entry (`Res_value` types `0x1c`–`0x1f`) of the target's SemWT
 *    overlay is recoloured with [RoleInference.recolor] against [DeviceSnapshot.systemShades],
 *    from the dark scheme when its configuration contains `night`, else from the light scheme;
 *    entries without a match keep Samsung's observed value, copied verbatim into the overlay in
 *    the same configuration because apply disables SemWT ([REASON_FIXED], still reported).
 *    [ThemeSpec.colorPins] then win
 *    (a pinned resource absent from SemWT is written in `""` and, for shade pins, `night`).
 *    For System UI only names [QuickSettingsRules.allowsColour] accepts are themed
 *    ([REASON_QS_POLICY]): the version's allowlist, or [QS_COLOUR_NAMES] when it is empty.
 *    A rejected colour SemWT sets keeps Samsung's observed value, copied verbatim (apply
 *    disables SemWT); one only a pin set is left out.
 *    Names missing from the target table are skipped by [ResourceMapper].
 * 2. **Drawables.** For each enabled element of the target, its pack ([ThemeSpec.packFor])
 *    layer categories ([Elements.elementForCategory]; option tokens must be on, in
 *    [Pack.defaultOptions] overlaid by [ThemeSpec.elementOptions]) are planned with
 *    [LayerPlanner] using the pack palette overlaid by [SlotPalette.derive]. Each resource is
 *    planned once per density the target defines ([ResourceMapper.pngDensities]: the default
 *    configuration and `anydpi` become `xxhdpi`); night categories go to `night-<density>`. A
 *    day category without a night counterpart also gets a night twin planned with dark tints;
 *    so does one that is more specific (more option tokens, or a newer Android release) than the
 *    night category covering its resource, whose base artwork it replaces in dark mode too.
 *    More specific categories (more option tokens) and targeted elements win over generic ones.
 *    Categories for landscape, right-to-left or a screen width ([CategoryName.configSpecific])
 *    are not used: their artwork would land in the plain density configuration.
 *    **System UI never gets pack drawables** unless [QuickSettingsRules.drawablesAllowed] is on
 *    (no bundled version allows them; Quick Settings layouts differ per One UI release), and
 *    [QuickSettingsPolicy.COLOURS_ONLY] elements never do. Each is skipped with
 *    [REASON_QS_POLICY], and the System UI target logs one `Quick Settings: colours only
 *    (One UI N layout)` event.
 * 3. **Custom overrides.** Each [ThemeSpec.customIcons] / [ThemeSpec.customBackgrounds] entry of
 *    the target replaces every pack job of its resource with custom jobs: a day and a `night`
 *    job for each density the pack jobs used (also when they were night-only), else for each
 *    density the target defines. Densities go through [ResourceMapper.pngDensities]: a target
 *    value in the default configuration or in `anydpi` gives `xxhdpi` and `night-xxhdpi`.
 *    Resources the target lacks are skipped with [REASON_NOT_IN_TARGET].
 * 4. **Icon synthesis.** For Settings, when [ThemeSpec.synthesizeMissingIcons] is on and the
 *    `settings_icons` element has a pack: every [OneUiRules.dashboardRows] row the target
 *    defines but the pack left without a job gets a [PlannedDrawableJob.synthesis] job in each
 *    configuration the pack's dashboard icons cover, referencing that configuration's
 *    `sec_ic_settings_display` job (else `_connections`, else any).
 *
 * [OneUiRules.renamed] is applied to every resource name before lookup.
 */
public object ThemeCompiler {
    /**
     * Skip reason: SemWT colour without a palette match within ΔE 6. Not recoloured: Samsung's
     * observed value is copied verbatim into the overlay (SemWT is disabled on apply).
     */
    public const val REASON_FIXED: String = "fixed"

    /**
     * Skip reason: not themed by the quick-settings policy. A System UI colour SemWT sets keeps
     * Samsung's observed value verbatim; drawables and pin-only colours are not written.
     */
    public const val REASON_QS_POLICY: String = "qs-policy"

    /** Skip reason: the element's pack is not available. */
    public const val REASON_PACK_MISSING: String = "pack not available"

    /** Skip reason: the pack has no layer file for the resource. */
    public const val REASON_NO_LAYERS: String = "no layers in pack"

    /** Target skip reason: the rules mark the package deprecated. */
    public const val REASON_DEPRECATED: String = "deprecated"

    /** Target skip reason: switched off in [ThemeSpec.targetEnabled]. */
    public const val REASON_DISABLED: String = "disabled"

    /** Target skip reason: the device snapshot has no entry for the package. */
    public const val REASON_NOT_SCANNED: String = "not scanned"

    /** Target skip reason: the package is not installed. */
    public const val REASON_NOT_INSTALLED: String = "not installed"

    /** Target skip reason: the package is debloated (disabled or removed for the user). */
    public const val REASON_DEBLOATED: String = "debloated"

    /** Target skip reason: the target's `resources.arsc` could not be read. */
    public const val REASON_NO_TABLE: String = "no resource table"

    /** Skip reason: a custom icon/background key names a drawable the target does not define. */
    public const val REASON_NOT_IN_TARGET: String = ResourceMapper.REASON_NOT_IN_TARGET

    /** Resource-name prefix of the Settings dashboard row icons. */
    public const val DASHBOARD_ICON_PREFIX: String = "sec_ic_settings_"

    /** Build-event message prefix of the System UI Quick Settings policy line. */
    public const val QS_REPORT_PREFIX: String = "Quick Settings: "

    /** System UI colour names kept under the quick-settings policy when the version has no allowlist. */
    public val QS_COLOUR_NAMES: Regex =
        Regex("qs_|quick|tile|panel|notification|status_bar|statusbar|navigation|nav_bar|volume|power|lock|keyguard|brightness", RegexOption.IGNORE_CASE)

    private const val SYSTEMUI = "com.android.systemui"
    private const val SETTINGS = "com.android.settings"
    private const val MYFILES = "com.sec.android.app.myfiles"
    private const val SETTINGS_ICONS = "settings_icons"
    private val COLOR_TYPES = 0x1c..0x1f
    private const val TYPE_RGB8 = 0x1d
    private const val TYPE_RGB4 = 0x1f
    private const val OPAQUE = 0xFF000000.toInt()

    /** `HexNext_` + [packageName] with dots replaced by underscores. */
    public fun overlayName(packageName: String): String = "HexNext_" + packageName.replace('.', '_')

    /**
     * Compiles [spec] for [device].
     *
     * @param rules One UI rules; defaults to the bundled rules for the device's version.
     * @param clock timestamp source for [CompiledTheme.events].
     */
    public fun compile(
        spec: ThemeSpec,
        device: DeviceSnapshot,
        packs: PackResolver,
        rules: OneUiRules = OneUiRules.forProfile(device.oneUi),
        clock: () -> Long = System::currentTimeMillis,
    ): CompiledTheme {
        val events = mutableListOf<BuildEvent>()
        fun event(level: LogLevel, target: String, message: String, data: Map<String, String> = emptyMap()) {
            events += BuildEvent(clock(), level, target, message, data)
        }
        val resolved: ResolvedTheme by lazy { ThemeResolver.resolve(spec.source, spec.darkAdjustments, spec.lightAdjustments) }
        val derivedPalette: Map<String, Int> by lazy { SlotPalette.derive(resolved) }

        val order = rules.targets + device.targets.keys.filterNot { it in rules.targets }.sorted()
        val compiled = mutableListOf<CompiledTarget>()
        val skippedTargets = mutableListOf<Skipped>()
        for (pkg in order) {
            val snapshot = device.targets[pkg]
            val reason = when {
                pkg in rules.deprecated -> REASON_DEPRECATED
                !spec.isTargetEnabled(pkg) -> REASON_DISABLED
                snapshot == null -> REASON_NOT_SCANNED
                snapshot.debloated -> REASON_DEBLOATED
                !snapshot.installed -> REASON_NOT_INSTALLED
                snapshot.table == null -> REASON_NO_TABLE
                else -> null
            }
            if (reason != null) {
                skippedTargets += Skipped(pkg, reason)
                event(LogLevel.INFO, pkg, "target skipped", mapOf("reason" to reason))
                continue
            }
            val ctx = TargetContext(spec, device, packs, rules, snapshot!!, snapshot.table!!, { resolved }, { derivedPalette }, ::event)
            val target = ctx.compile()
            compiled += target
            event(
                LogLevel.INFO, pkg, "target compiled",
                mapOf(
                    "overlay" to target.overlayName,
                    "colors" to target.colors.size.toString(),
                    "drawables" to target.drawables.size.toString(),
                    "skipped" to target.skipped.size.toString(),
                    "semwtCoverage" to target.semwtCoverage.toString(),
                    "hash" to target.contentHash,
                ),
            )
        }
        return CompiledTheme(compiled, events, skippedTargets)
    }

    /**
     * SHA-256 (lower-case hex) of the sorted entries of a target: one line per colour
     * (`c|config|name|argb`) and drawable (`d|config|name|ninepatch|layer:tint,…`; custom jobs
     * `d|config|name|custom:<file>`; synthesis jobs `d|config|name|synth:<glyph>|<reference line>`).
     */
    public fun contentHash(colors: List<PlannedColor>, drawables: List<PlannedDrawableJob>): String {
        val lines = colors.map { "c|${it.configuration}|${it.resourceName}|%08x".format(it.argb) } +
            drawables.map(::hashLine)
        val digest = MessageDigest.getInstance("SHA-256").digest(lines.sorted().joinToString("\n").toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun hashLine(job: PlannedDrawableJob): String {
        val head = "d|${job.configuration}|${job.resourceName}|"
        val synthesis = job.synthesis
        return when {
            job.custom != null -> head + "custom:${job.custom}"
            synthesis != null -> head + "synth:${synthesis.glyph}|" + hashLine(synthesis.reference)
            else -> head + "${job.ninePatch}|" +
                job.plan.steps.joinToString(",") { s -> "${s.layerPath}:${s.tintArgb?.let { "%08x".format(it) } ?: "-"}" }
        }
    }

    /** Day-versus-night precedence: option-token count, then Android release rank. */
    private val NIGHT_PRECEDENCE: Comparator<CategoryName> =
        compareBy<CategoryName>({ it.optionTokens.size }, { it.androidRank })

    /** True when the configuration qualifier string contains the `night` qualifier. */
    internal fun isNight(configuration: String): Boolean = configuration.split('-').contains("night")

    /** `night-<density>`, `<density>`, `night` or `""`. */
    internal fun configFor(night: Boolean, density: String): String =
        listOfNotNull("night".takeIf { night }, density.ifEmpty { null }).joinToString("-")

    private class TargetContext(
        val spec: ThemeSpec,
        val device: DeviceSnapshot,
        val packs: PackResolver,
        val rules: OneUiRules,
        val snapshot: TargetSnapshot,
        val table: ResourceTable,
        val resolved: () -> ResolvedTheme,
        val derived: () -> Map<String, Int>,
        val event: (LogLevel, String, String, Map<String, String>) -> Unit,
    ) {
        val pkg = snapshot.packageName
        val semwt: FabricatedOverlayFile? = snapshot.semwt
        val skipped = mutableListOf<Skipped>()

        fun compile(): CompiledTarget {
            val colors = compileColors()
            val drawables = compileDrawables()
            val semwtNames = semwt?.entries?.mapTo(HashSet()) { rules.rename(it.typeAndName) }.orEmpty()
            val planned = colors.map { it.resourceName.substringAfter(':') } + drawables.map { it.resourceName.substringAfter(':') }
            val coverage = planned.distinct().count { it in semwtNames }
            return CompiledTarget(
                packageName = pkg,
                overlayName = overlayName(pkg),
                colors = colors,
                drawables = drawables,
                skipped = skipped.distinct(),
                semwtCoverage = coverage,
                contentHash = contentHash(colors, drawables),
                versionCode = snapshot.versionCode,
                semwtMatches = semwtMatches(colors),
            )
        }

        /** Default-configuration colours whose planned value equals SemWT's own default value. */
        private fun semwtMatches(colors: List<PlannedColor>): Set<String> {
            val semwtValues = HashMap<String, MutableSet<Int>>()
            for (e in semwt?.entries.orEmpty()) {
                if (e.dataType !in COLOR_TYPES || !e.configuration.isNullOrEmpty()) continue
                val values = semwtValues.getOrPut("$pkg:${rules.rename(e.typeAndName)}") { HashSet() }
                values += e.data
                if (e.dataType == TYPE_RGB8 || e.dataType == TYPE_RGB4) values += e.data or OPAQUE
            }
            if (semwtValues.isEmpty()) return emptySet()
            return colors.filter { it.configuration.isEmpty() && semwtValues[it.resourceName]?.contains(it.argb) == true }
                .mapTo(LinkedHashSet()) { it.resourceName }
        }

        private fun display(typeName: String, config: String) = "$pkg:$typeName" + if (config.isEmpty()) "" else " [$config]"

        private fun compileColors(): List<PlannedColor> {
            val values = LinkedHashMap<Pair<String, String>, Int>()
            val fixed = LinkedHashMap<Pair<String, String>, Skipped>()
            // Samsung's observed SemWT value per key, before recolouring or pins.
            val observed = HashMap<Pair<String, String>, Int>()
            for (e in semwt?.entries.orEmpty()) {
                if (e.dataType !in COLOR_TYPES) continue
                val typeName = rules.rename(e.typeAndName)
                val config = e.configuration.orEmpty()
                val key = typeName to config
                // RGB8 / RGB4 values can arrive with the alpha byte cleared; they are opaque.
                val data = if (e.dataType == TYPE_RGB8 || e.dataType == TYPE_RGB4) e.data or OPAQUE else e.data
                observed[key] = data
                val out = RoleInference.recolor(data, device.systemShades, resolved(), isNight(config))
                if (out == null) {
                    // Apply disables SemWT, so its value is copied verbatim or the stock APK colour returns.
                    fixed[key] = Skipped(display(typeName, config), REASON_FIXED)
                    values[key] = data
                } else {
                    values[key] = out
                }
            }
            for ((pinKey, pin) in spec.colorPins) {
                if (pinKey.substringBefore(':', "") != pkg) continue
                val typeName = rules.rename(pinKey.substringAfter(':'))
                val existing = (values.keys + fixed.keys).filter { it.first == typeName }.map { it.second }.distinct()
                val configs = existing.ifEmpty { if (pin is ColorPin.Shade) listOf("", "night") else listOf("") }
                for (config in configs) {
                    val key = typeName to config
                    values[key] = when (pin) {
                        is ColorPin.Fixed -> pin.argb
                        is ColorPin.Shade -> resolved().scheme(isNight(config)).shade(pin.role, pin.shade)
                    }
                    fixed.remove(key)
                }
            }
            if (pkg == SYSTEMUI) {
                val qs = rules.quickSettings
                var dropped = 0
                var verbatim = 0
                val it = values.entries.iterator()
                while (it.hasNext()) {
                    val entry = it.next()
                    val key = entry.key
                    val (typeName, config) = key
                    if (!qs.allowsColour(typeName.substringAfter('/'))) {
                        skipped += Skipped(display(typeName, config), REASON_QS_POLICY)
                        // Reported once, as a policy skip, not also as "kept fixed".
                        fixed.remove(key)
                        val samsung = observed[key]
                        if (samsung != null) {
                            // Apply disables SemWT, so Samsung's value is copied verbatim or the stock APK colour returns.
                            entry.setValue(samsung)
                            verbatim++
                        } else {
                            // Only a pin wrote it; SemWT never set it, so leaving it out keeps the stock value.
                            it.remove()
                        }
                        dropped++
                    }
                }
                val deviceMajor = device.oneUi.version?.major
                event(
                    LogLevel.INFO, pkg,
                    QS_REPORT_PREFIX + (if (qs.coloursOnly) "colours only" else "colours and drawables") + " (${qs.layoutLabel} layout)",
                    mapOf(
                        "layout" to qs.layout,
                        "rulesMajor" to rules.major.toString(),
                        "deviceOneUi" to (device.oneUi.version?.name ?: "unknown"),
                        "rulesFallback" to (deviceMajor != rules.major).toString(),
                        "allowlist" to if (qs.colours.isEmpty()) "pattern" else qs.colours.size.toString(),
                        "colours" to (values.size - verbatim).toString(),
                        "dropped" to dropped.toString(),
                        "verbatim" to verbatim.toString(),
                    ),
                )
            }

            // Reported after the Quick Settings filter so only colours actually written verbatim are counted.
            if (fixed.isNotEmpty()) {
                event(
                    LogLevel.WARN, pkg, "colours kept fixed",
                    mapOf("count" to fixed.size.toString(), "names" to fixed.values.take(20).joinToString(",") { it.name }),
                )
            }
            skipped += fixed.values

            val out = mutableListOf<PlannedColor>()
            for ((config, group) in values.entries.groupBy({ it.key.second }, { it.key.first to it.value })) {
                val plan = ResourceMapper.map(table, semwt, ThemeRequest(colors = group.toMap(LinkedHashMap())))
                plan.colorEntries.mapTo(out) { it.copy(configuration = config) }
                plan.skipped.mapTo(skipped) { Skipped("$pkg:${it.name.substringAfter(':')}", it.reason) }
            }
            return out
        }

        private fun compileDrawables(): List<PlannedDrawableJob> {
            val jobs = LinkedHashMap<Pair<String, String>, PlannedDrawableJob>()
            val missingPacks = HashSet<String>()
            // Generic elements first so targeted elements override them.
            val elements = Elements.forTarget(pkg).sortedByDescending { it.anyTarget }
            for (element in elements) {
                if (!spec.isElementEnabled(element.id)) continue
                val packId = spec.packFor(element.id) ?: continue
                val pack = packs.pack(packId)
                if (pack == null) {
                    if (missingPacks.add(packId)) {
                        skipped += Skipped(packId, REASON_PACK_MISSING)
                        event(LogLevel.WARN, pkg, "pack not available", mapOf("pack" to packId, "element" to element.id))
                    }
                    continue
                }
                jobs.putAll(elementJobs(element, pack))
            }
            val overridden = HashSet<String>()
            customJobs(spec.customIcons, CustomAssets.CATEGORY_ICON, jobs, overridden)
            customJobs(spec.customBackgrounds, CustomAssets.CATEGORY_BACKGROUND, jobs, overridden)
            if (pkg == SETTINGS && spec.synthesizeMissingIcons) synthesisJobs(jobs, overridden)
            return jobs.values.toList()
        }

        /**
         * Replaces the pack jobs of every [entries] key of this target with custom jobs; the
         * resource names overridden are added to [overridden].
         */
        private fun customJobs(
            entries: Map<String, String>,
            category: String,
            jobs: LinkedHashMap<Pair<String, String>, PlannedDrawableJob>,
            overridden: MutableSet<String>,
        ) {
            for ((key, file) in entries) {
                if (key.substringBefore(':', "") != pkg) continue
                val typeName = rules.rename(key.substringAfter(':'))
                if (pkg == SYSTEMUI && !rules.quickSettings.drawablesAllowed) {
                    // Colours only: a custom System UI drawable (from an older or hand-edited spec) is dropped like a pack one.
                    skipped += Skipped(display(typeName, ""), REASON_QS_POLICY)
                    event(LogLevel.INFO, pkg, "custom drawable dropped by Quick Settings policy", mapOf("key" to key, "file" to file))
                    continue
                }
                val entry = table.find(typeName)
                if (entry == null || '/' !in typeName) {
                    skipped += Skipped(key, REASON_NOT_IN_TARGET)
                    event(LogLevel.WARN, pkg, "custom drawable not in target", mapOf("key" to key, "file" to file))
                    continue
                }
                val resourceName = "$pkg:$typeName"
                val replaced = jobs.values.filter { it.resourceName == resourceName }
                replaced.forEach { jobs.remove(it.resourceName to it.configuration) }
                // Only the densities are taken, and every density gets both a day and a night job: without
                // the night job the target's own night drawable wins in dark mode, and without the day job
                // the stock icon stays in light mode. The pack jobs give the densities when there are any
                // (a pack can theme a resource in its night category only, so they may all be night jobs).
                // Otherwise the table does: the app's THEME table read keeps one placeholder per density,
                // carrying the config of whichever chunk came first (that can be a night one).
                val densities = ResourceMapper.pngDensities(
                    replaced.map { job -> job.configuration.split('-').filter { it != "night" }.joinToString("-") }
                        .ifEmpty { entry.values.map { it.resConfig.densityQualifier } },
                )
                val configs = densities.flatMap { d -> listOf(configFor(false, d), configFor(true, d)) }.distinct()
                val name = typeName.substringAfter('/')
                val elementId = replaced.firstOrNull()?.elementId ?: when {
                    category == CustomAssets.CATEGORY_BACKGROUND -> "backgrounds"
                    name.startsWith(DASHBOARD_ICON_PREFIX) -> SETTINGS_ICONS
                    pkg == MYFILES -> "my_files_icons"
                    else -> "system_drawables"
                }
                val source = DrawableSource(CustomAssets.PACK_ID, category, file)
                for (config in configs) {
                    jobs[resourceName to config] =
                        PlannedDrawableJob(resourceName, config, CompositePlan(emptyList()), false, source, elementId, custom = file)
                }
                overridden += resourceName
                event(LogLevel.INFO, pkg, "custom drawable", mapOf("key" to key, "file" to file, "configs" to configs.joinToString(",")))
            }
        }

        /** Adds synthesis jobs for dashboard rows the Settings icon pack left out (see [ThemeCompiler], step 4). */
        private fun synthesisJobs(jobs: LinkedHashMap<Pair<String, String>, PlannedDrawableJob>, overridden: Set<String>) {
            if (!spec.isElementEnabled(SETTINGS_ICONS)) return
            val packId = spec.packFor(SETTINGS_ICONS) ?: return
            if (packs.pack(packId) == null) return
            val prefix = "$pkg:drawable/$DASHBOARD_ICON_PREFIX"
            val packIcons = jobs.values.filter { it.custom == null && it.synthesis == null && it.resourceName.startsWith(prefix) }
            if (packIcons.isEmpty()) return
            for ((config, group) in packIcons.groupBy { it.configuration }) {
                val reference = group.firstOrNull { it.resourceName == "${prefix}display" }
                    ?: group.firstOrNull { it.resourceName == "${prefix}connections" }
                    ?: group.first()
                for (row in rules.dashboardRows) {
                    val name = DASHBOARD_ICON_PREFIX + row
                    val resourceName = "$pkg:drawable/$name"
                    if (resourceName in overridden || (resourceName to config) in jobs) continue
                    if (table.find("drawable", name) == null) continue
                    jobs[resourceName to config] = PlannedDrawableJob(
                        resourceName, config, CompositePlan(emptyList()), ninePatch = false,
                        source = reference.source.copy(resourceName = name), elementId = SETTINGS_ICONS,
                        synthesis = IconSynthesis(row, reference),
                    )
                    event(
                        LogLevel.INFO, pkg, "icon synthesised",
                        mapOf("row" to row, "reference" to reference.resourceName, "configuration" to config),
                    )
                }
            }
        }

        private fun elementJobs(element: Element, pack: Pack): Map<Pair<String, String>, PlannedDrawableJob> {
            // The pack's own defaults (first choice of every select) under the user's choices.
            val options = pack.defaultOptions + spec.elementOptions[element.id].orEmpty()
            val categories = pack.layers.map { it.category }.distinct().map(CategoryName::parse)
                .filter { Elements.elementForCategory(it.raw).id == element.id }
                .filter { c -> c.optionTokens.all { options[it] == "true" } }
                // Landscape, RTL and screen-width artwork only fits that configuration, but jobs are
                // written to the plain density configuration, so it would show everywhere.
                .filterNot { it.configSpecific }
                // Later categories win: more option tokens, then newer Android release artwork,
                // then the higher v<sdk> qualifier compared as a number (v26 over v4 and v8), then
                // neutral categories over `port` / `ldltr` ones.
                .sortedWith(
                    compareBy<CategoryName>(
                        { it.optionTokens.size }, { it.androidRank }, { it.sdkLevel }, { !it.defaultLeaning }, { it.raw },
                    ),
                )
            if (categories.isEmpty()) return emptyMap()

            // System UI: pack drawables only when the version's Quick Settings rules allow them,
            // and never for colours-only elements (tile shapes).
            val qsBlocked = pkg == SYSTEMUI &&
                (!rules.quickSettings.drawablesAllowed || element.quickSettingsPolicy == QuickSettingsPolicy.COLOURS_ONLY)
            val palette = if (options["palette"] == "pack") emptyMap() else pack.paletteArgb + derived()
            val entryNames = packs.entryNames(pack.id).ifEmpty { null }
            val jobs = LinkedHashMap<Pair<String, String>, PlannedDrawableJob>()
            val dayWinners = LinkedHashMap<Pair<String, String>, Pair<PlannedDrawable, CategoryName>>()
            // The category that wrote each night job, to compare it with the day winner.
            val nightSources = HashMap<Pair<String, String>, CategoryName>()

            fun job(pd: PlannedDrawable, category: CategoryName, night: Boolean, density: String): PlannedDrawableJob? {
                val plan = LayerPlanner.plan(pack, category.raw, pd.source.resourceName, palette, night, entryNames)
                if (plan.isEmpty) {
                    skipped += Skipped(pd.resourceName, REASON_NO_LAYERS)
                    return null
                }
                return PlannedDrawableJob(pd.resourceName, configFor(night, density), plan, plan.steps.any { it.isNinePatch }, pd.source, element.id)
            }

            for (category in categories) {
                val type = if (category.base == "mipmap") "mipmap" else "drawable"
                // Several pack names can rename to one target; the pack's exact-name artwork wins over aliases.
                val request = LinkedHashMap<String, DrawableSource>()
                for (res in pack.layers.filter { it.category == category.raw }.flatMap { it.resources }.distinct()) {
                    val target = rules.rename("$type/$res")
                    if (request[target]?.resourceName?.let { "$type/$it" } == target) continue
                    request[target] = DrawableSource(pack.id, category.raw, res)
                }
                val plan = ResourceMapper.map(table, semwt, ThemeRequest(drawables = request))
                if (!element.anyTarget) plan.skipped.mapTo(skipped) { Skipped("$pkg:${it.name}", it.reason) }
                for (pd in plan.drawableEntries) {
                    if (qsBlocked) {
                        skipped += Skipped(pd.resourceName, REASON_QS_POLICY)
                        continue
                    }
                    for (density in pd.configs) {
                        val j = job(pd, category, category.night, density) ?: continue
                        jobs[j.resourceName to j.configuration] = j
                        if (category.night) nightSources[j.resourceName to j.configuration] = category
                        else dayWinners[pd.resourceName to density] = pd to category
                    }
                }
            }
            for ((key, winner) in dayWinners) {
                val (name, density) = key
                val (pd, category) = winner
                // A night category is the day winner's counterpart unless the winner is more
                // specific (more option tokens, or newer Android release artwork): then the
                // winner's own dark twin replaces the base night artwork.
                val nightSource = nightSources[name to configFor(true, density)]
                if (nightSource != null && NIGHT_PRECEDENCE.compare(nightSource, category) >= 0) continue
                job(pd, category, night = true, density = density)?.let { jobs[it.resourceName to it.configuration] = it }
            }
            return jobs
        }
    }
}
