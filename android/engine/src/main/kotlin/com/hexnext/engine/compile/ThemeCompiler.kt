package com.hexnext.engine.compile

import com.hexnext.engine.arsc.ResourceTable
import com.hexnext.engine.arsc.ResourceValue
import com.hexnext.engine.frro.FabricatedOverlayFile
import com.hexnext.engine.log.BuildEvent
import com.hexnext.engine.log.LogLevel
import com.hexnext.engine.map.DrawableSource
import com.hexnext.engine.map.PlannedColor
import com.hexnext.engine.map.PlannedDrawable
import com.hexnext.engine.map.PlannedValue
import com.hexnext.engine.map.ResourceMapper
import com.hexnext.engine.map.Skipped
import com.hexnext.engine.map.ThemeRequest
import com.hexnext.engine.pack.CompositePlan
import com.hexnext.engine.pack.LayerPlanner
import com.hexnext.engine.pack.Pack
import com.hexnext.engine.palette.ColorMath
import com.hexnext.engine.palette.InferredColor
import com.hexnext.engine.palette.ResolvedTheme
import com.hexnext.engine.palette.RoleInference
import com.hexnext.engine.palette.ShadeReference
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
 * A built-in icon drawn from scratch, with no pack: a badge filled with [fillArgb] (a linear
 * gradient to [fillEndArgb] when set) and the app's own glyph for row [glyph] in [glyphArgb].
 * Planned by [IconColors.builtInJobs] when the spec asks for built-in icons and no pack supplies
 * them.
 */
public data class IconBadge(val glyph: String, val fillArgb: Int, val fillEndArgb: Int?, val glyphArgb: Int)

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
 * @property badge set for a built-in icon drawn without a pack (see [IconBadge]); [plan] is then empty.
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
    val badge: IconBadge? = null,
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
 * @property semwtConfigMatches the same check for every configuration, as `"<resource>|<config>"`
 *   (config empty for the default), so a night-mode probe can also skip colours SemWT sets alike.
 * @property values non-colour values of the separate value overlay [valueOverlayName]
 *   (see [ValuePlanner]); empty for most targets.
 * @property valueOverlayName `HexNextValues_<pkg with dots as underscores>`.
 * @property valueHash [ValuePlanner.valueHash] of [values]; [contentHash] never includes them.
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
    val semwtConfigMatches: Set<String> = emptySet(),
    val values: List<PlannedValue> = emptyList(),
    val valueOverlayName: String = ValuePlanner.overlayName(packageName),
    val valueHash: String = ValuePlanner.valueHash(values),
) {
    /** True when the colour and drawable overlay has nothing to fabricate (values are separate). */
    public val isEmpty: Boolean get() = colors.isEmpty() && drawables.isEmpty()

    /** True when the value overlay has something to fabricate. */
    public val hasValues: Boolean get() = values.isNotEmpty()
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
 *    **Element groups** come next: every colour is classified with [ColorGroupMatcher.classify],
 *    and a user colour for its group and mode ([ThemeSpec.colorGroups]) replaces the inferred or
 *    verbatim value, keeping Samsung's alpha ([ColorGroupMatcher.groupValue]). Quick panel
 *    backgrounds have their alpha scaled by [ThemeSpec.qsPanelAlpha] / 255 below 255. With any group colour set, literal APK
 *    colours SemWT does not set are added in `""` and `night` when a name rule puts them in a
 *    set structural group ([ColorGroupMatcher.TABLE_GROUPS]). One `group colours` event counts
 *    what the groups reached.
 *    [ThemeSpec.colorPins] then win
 *    (a pinned resource absent from SemWT is written in `""` and, for shade pins, `night`).
 *    For System UI only names [OneUiRules.allowsSystemUiColour] accepts are themed
 *    ([REASON_QS_POLICY]): the version's allowlist, or [QS_COLOUR_NAMES] when it is empty, plus
 *    the System UI [ColorGroupFamily.exact] names.
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
    private const val OPAQUE_ALPHA = 255
    private val DAY_NIGHT = listOf("", "night")

    /** Table qualifier strings of a night-only value (aapt2 adds `-v8` to `night`). */
    private val NIGHT_QUALIFIERS = setOf("night", "night-v8")

    /** `HexNext_` + [packageName] with dots replaced by underscores. */
    public fun overlayName(packageName: String): String = "HexNext_" + packageName.replace('.', '_')

    /** The value overlay of [packageName] ([ValuePlanner.overlayName]). */
    public fun valueOverlayName(packageName: String): String = ValuePlanner.overlayName(packageName)

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
        // Pack options rebuilt natively, merged below the user's own settings.
        val extras = NativeExtras.resolve(spec, packs, { resolved })
        @Suppress("NAME_SHADOWING")
        val spec = extras.spec
        if (extras.applied.isNotEmpty()) {
            event(LogLevel.INFO, "", "native extras", mapOf("count" to extras.applied.size.toString(), "choices" to extras.applied.joinToString(";")))
        }

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
            val ctx = TargetContext(spec, device, packs, rules, snapshot!!, snapshot.table!!, { resolved }, { derivedPalette }, ::event, extras.colours)
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
                ) + if (target.hasValues) mapOf("values" to target.values.size.toString(), "valueHash" to target.valueHash) else emptyMap(),
            )
        }
        return CompiledTheme(compiled, events, skippedTargets)
    }

    /**
     * SHA-256 (lower-case hex) of the sorted entries of a target: one line per colour
     * (`c|config|name|argb`) and drawable (`d|config|name|ninepatch|layer:tint[>gradientEnd],…`; custom jobs
     * `d|config|name|custom:<file>`; synthesis jobs `d|config|name|synth:<glyph>|<reference line>`).
     */
    public fun contentHash(colors: List<PlannedColor>, drawables: List<PlannedDrawableJob>): String {
        val lines = colors.map { "c|${it.configuration}|${it.resourceName}|%08x".format(it.argb) } +
            drawables.map(::hashLine)
        val digest = MessageDigest.getInstance("SHA-256").digest(lines.sorted().joinToString("\n").toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Scales the alpha of a [group] colour by [scale] / 255 rather than replacing it, so a colour
     * Samsung already ships see-through only ever gets more see-through. A Quick panel backdrop
     * never drops below [ThemeSpec.MIN_QS_PANEL_ALPHA] (or the stock alpha, when that is lower).
     */
    private fun scaleGroupAlpha(argb: Int, group: ColorGroup, scale: Int): Int {
        val stock = ColorMath.alpha(argb)
        val floor = if (group == ColorGroup.QS_PANEL_BG) minOf(stock, ThemeSpec.MIN_QS_PANEL_ALPHA) else 0
        return ColorMath.withAlpha(argb, (stock * scale / OPAQUE_ALPHA).coerceAtLeast(floor))
    }

    /** The alpha scale [ThemeSpec.alphaFor] gives [group], with the panel backdrop's readability floor. */
    private fun groupAlphaScale(spec: ThemeSpec, group: ColorGroup): Int {
        val a = spec.alphaFor(group)
        return if (group == ColorGroup.QS_PANEL_BG) a.coerceAtLeast(ThemeSpec.MIN_QS_PANEL_ALPHA) else a
    }

    private fun hashLine(job: PlannedDrawableJob): String {
        val head = "d|${job.configuration}|${job.resourceName}|"
        val synthesis = job.synthesis
        return when {
            job.custom != null -> head + "custom:${job.custom}"
            synthesis != null -> head + "synth:${synthesis.glyph}|" + hashLine(synthesis.reference)
            job.badge != null -> head + job.badge.let { b ->
                "badge:${b.glyph}|%08x|%s|%08x".format(b.fillArgb, b.fillEndArgb?.let { "%08x".format(it) } ?: "-", b.glyphArgb)
            }
            else -> head + "${job.ninePatch}|" +
                job.plan.steps.joinToString(",") { s ->
                    "${s.layerPath}:${s.tintArgb?.let { "%08x".format(it) } ?: "-"}" +
                        (s.gradientEndArgb?.let { ">%08x".format(it) } ?: "")
                }
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

    /**
     * Every colour decision for one target, shared by [compile] and [ColorGroupMatcher.inventory]
     * so the resource editor shows exactly what an apply writes.
     *
     * @property values `(type/name, configuration)` → value to write, before the target-table check
     *   of [mapColors].
     * @property fixed SemWT colours copied verbatim because no rule recolours them.
     * @property observed Samsung's SemWT value per key.
     * @property inferred nearest scan-time shade per SemWT and APK-table key considered.
     * @property groups [ColorGroup] per classified key.
     * @property reached keys whose final value is a group colour (pins and the Quick Settings
     *   filter already applied).
     * @property tableObserved APK literal per key a group colour added from the resource table.
     * @property tableAdded how many of [values] came from the resource table.
     */
    internal class ColorDecisions(
        val values: LinkedHashMap<Pair<String, String>, Int>,
        val fixed: LinkedHashMap<Pair<String, String>, Skipped>,
        val observed: Map<Pair<String, String>, Int>,
        val inferred: Map<Pair<String, String>, InferredColor?>,
        val groups: Map<Pair<String, String>, ColorGroup>,
        val reached: Set<Pair<String, String>>,
        val tableObserved: Map<Pair<String, String>, Int>,
        val tableAdded: Int,
        val qsSkipped: List<Skipped>,
        val qsDropped: Int,
        val qsVerbatim: Int,
    )

    /**
     * A colour of a target's resource table whose values are literal colours: [literals] maps
     * `""` (day) and `"night"` to the value. [exclusive] is true when the resource has no value in
     * any other configuration and no reference value, so writing day and night replaces it fully.
     */
    internal class TableColor(val typeName: String, val literals: Map<String, Int>, val exclusive: Boolean)

    /** Literal colours of [table] (see [TableColor]), in table order. */
    internal fun tableColors(table: ResourceTable): List<TableColor> {
        val out = ArrayList<TableColor>()
        for (entry in table.entries) {
            if (entry.type != "color") continue
            val literals = LinkedHashMap<String, Int>(2)
            var exclusive = true
            for (v in entry.values) {
                val config = when {
                    v.config.isEmpty() -> ""
                    v.resConfig.isNight && v.config in NIGHT_QUALIFIERS -> "night"
                    else -> null
                }
                val argb = (v.value as? ResourceValue.Color)?.argb
                if (config == null || argb == null) {
                    exclusive = false
                    continue
                }
                literals[config] = argb
            }
            if (literals.isNotEmpty()) out += TableColor(entry.key, literals, exclusive)
        }
        return out
    }

    /**
     * Plans the colours of target [pkg], in order:
     * 1. SemWT colours: [RoleInference] against [reference]; unmatched values are kept verbatim.
     * 2. Element groups: each key is classified with [ColorGroupMatcher.classify]; a user colour
     *    for its group and mode ([ThemeSpec.colorGroups]) replaces the value (observed alpha
     *    kept). A name in [ColorGroupFamily.exact] whose group is on Auto takes the group's Auto
     *    colour ([ColorGroup.auto]) the same way, so the device matches the Studio preview.
     *    [ThemeSpec.qsPanelAlpha] below 255 scales the alpha of every Quick panel background.
     * 3. With any group colour set, exclusive literal colours of [table] that SemWT does not set
     *    and that a name rule puts in a set [ColorGroupMatcher.TABLE_GROUPS] group are added in
     *    `""` and `night`. A `_dark` / `_light` name takes the dark / light colour in both.
     *    [ColorGroupFamily.exact] names are added this way in every case, with the group's
     *    effective colour (Auto included, whatever the group), so the device matches the preview.
     * 4. [ThemeSpec.colorPins] win over everything above.
     * 5. System UI: the Quick Settings filter ([OneUiRules.allowsSystemUiColour]).
     */
    internal fun planColors(
        pkg: String,
        table: ResourceTable?,
        semwt: FabricatedOverlayFile?,
        reference: ShadeReference,
        spec: ThemeSpec,
        rules: OneUiRules,
        extraColours: List<ExtraColour> = emptyList(),
        resolved: () -> ResolvedTheme,
    ): ColorDecisions {
        fun display(typeName: String, config: String) = "$pkg:$typeName" + if (config.isEmpty()) "" else " [$config]"
        val values = LinkedHashMap<Pair<String, String>, Int>()
        val fixed = LinkedHashMap<Pair<String, String>, Skipped>()
        // Samsung's observed SemWT value per key, before recolouring or pins.
        val observed = HashMap<Pair<String, String>, Int>()
        val inferred = HashMap<Pair<String, String>, InferredColor?>()
        for (e in semwt?.entries.orEmpty()) {
            if (e.dataType !in COLOR_TYPES) continue
            val typeName = rules.rename(e.typeAndName)
            val config = e.configuration.orEmpty()
            val key = typeName to config
            // RGB8 / RGB4 values can arrive with the alpha byte cleared; they are opaque.
            val data = if (e.dataType == TYPE_RGB8 || e.dataType == TYPE_RGB4) e.data or OPAQUE else e.data
            observed[key] = data
            val match = reference.infer(data)
            inferred[key] = match
            val out = RoleInference.recolor(match, resolved(), isNight(config))
            if (out == null) {
                // Apply disables SemWT, so its value is copied verbatim or the stock APK colour returns.
                fixed[key] = Skipped(display(typeName, config), REASON_FIXED)
                values[key] = data
            } else {
                values[key] = out
            }
        }

        // Element groups beat role inference (and the verbatim copy of fixed colours).
        val groups = HashMap<Pair<String, String>, ColorGroup>()
        val reached = LinkedHashSet<Pair<String, String>>()
        // Alpha scales per group: ThemeSpec.groupAlpha, or qsPanelAlpha for the panel backdrop.
        val alphaScales = ColorGroup.ALL.associateWith { g -> groupAlphaScale(spec, g) }.filterValues { it < OPAQUE_ALPHA }
        for (entry in values.entries) {
            val key = entry.key
            // A colour whose meaning is its hue (error, Sunday red) that no reliable shade explains
            // would match by name alone; it keeps its role-inferred or verbatim value.
            val match = inferred[key]
            val reliable = match != null && match.deltaE <= RoleInference.DEFAULT_MAX_DELTA_E && match.alpha != 0
            if (!reliable && ColorGroupMatcher.TABLE_EXCLUDED.containsMatchIn(key.first.substringAfter('/'))) continue
            val m = ColorGroupMatcher.match(pkg, key.first, match, rules) ?: continue
            val group = m.group
            groups[key] = group
            var value = entry.value
            var changed = false
            // A device-verified exact name takes the group's Auto colour too, as the Studio preview shows it.
            val user = spec.groupColor(group, isNight(key.second))
                ?: if (m.exact) spec.effectiveGroupColor(group, resolved(), isNight(key.second)) else null
            if (user != null) {
                value = ColorGroupMatcher.groupValue(group, user, observed.getValue(key), inferred[key])
                reached += key
                changed = true
            }
            alphaScales[group]?.let { scale ->
                value = scaleGroupAlpha(value, group, scale)
                changed = true
            }
            if (changed) {
                entry.setValue(value)
                fixed.remove(key)
            }
        }

        // Group colours also reach APK colours SemWT does not set.
        val tableObserved = HashMap<Pair<String, String>, Int>()
        var tableAdded = 0
        val anyGroupSet = spec.colorGroups.values.any { !it.isAuto }
        // Device-verified exact names take their group's Auto colour from the table too.
        val exactNames = rules.exactColourNames(pkg)
        if (table != null && (anyGroupSet || exactNames.isNotEmpty())) {
            val known = values.keys.mapTo(HashSet()) { it.first }
            for (c in tableColors(table)) {
                if (!c.exclusive || c.typeName in known) continue
                val name = c.typeName.substringAfter('/')
                if (!anyGroupSet && name.lowercase() !in exactNames) continue
                if (ColorGroupMatcher.TABLE_EXCLUDED.containsMatchIn(name)) continue
                if (pkg == SYSTEMUI && !rules.allowsSystemUiColour(name)) continue
                val forced: Boolean? = when {
                    name.endsWith("_dark") -> true
                    name.endsWith("_light") -> false
                    else -> null
                }
                // Configs the user left on Auto; each still needs its APK literal once the other is
                // written, or the written default-config value would also match in that mode.
                val untouched = mutableListOf<Pair<Pair<String, String>, Int>>()
                var wrote = false
                for (config in DAY_NIGHT) {
                    val literal = c.literals[config] ?: c.literals.getValue(c.literals.keys.first())
                    val key = c.typeName to config
                    val match = reference.infer(literal)
                    inferred[key] = match
                    val m = ColorGroupMatcher.match(pkg, c.typeName, match, rules)
                    val mode = forced ?: isNight(config)
                    val user = when {
                        m == null || m.byFallback -> null
                        m.exact -> spec.effectiveGroupColor(m.group, resolved(), mode)
                        m.group !in ColorGroupMatcher.TABLE_GROUPS -> null
                        else -> spec.groupColor(m.group, mode)
                    }
                    if (m == null || user == null) {
                        untouched += key to literal
                        continue
                    }
                    var value = ColorGroupMatcher.groupValue(m.group, user, literal, match)
                    alphaScales[m.group]?.let { value = scaleGroupAlpha(value, m.group, it) }
                    values[key] = value
                    groups[key] = m.group
                    tableObserved[key] = literal
                    reached += key
                    tableAdded++
                    wrote = true
                }
                if (wrote) for ((key, literal) in untouched) values[key] = literal
            }
        }

        // Native extras by name (divider and control tints, a hidden carrier label): after the
        // groups, before the pins. SemWT colours and exclusive APK literals, in "" and night.
        for (ec in extraColours) {
            for (entry in values.entries) {
                if (!ec.matches(pkg, entry.key.first.substringAfter('/'))) continue
                entry.setValue(ec.apply(entry.value, isNight(entry.key.second)))
                fixed.remove(entry.key)
                reached.remove(entry.key)
            }
            if (table == null) continue
            val known = values.keys.mapTo(HashSet()) { it.first }
            for (c in tableColors(table)) {
                if (!c.exclusive || c.typeName in known) continue
                val name = c.typeName.substringAfter('/')
                if (!ec.matches(pkg, name)) continue
                if (pkg == SYSTEMUI && !rules.allowsSystemUiColour(name)) continue
                for (config in DAY_NIGHT) {
                    val literal = c.literals[config] ?: c.literals.getValue(c.literals.keys.first())
                    values[c.typeName to config] = ec.apply(literal, isNight(config))
                    tableObserved[c.typeName to config] = literal
                    tableAdded++
                }
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
                reached.remove(key)
            }
        }

        val qsSkipped = mutableListOf<Skipped>()
        var dropped = 0
        var verbatim = 0
        if (pkg == SYSTEMUI) {
            val it = values.entries.iterator()
            while (it.hasNext()) {
                val entry = it.next()
                val key = entry.key
                val (typeName, config) = key
                if (!rules.allowsSystemUiColour(typeName.substringAfter('/'))) {
                    qsSkipped += Skipped(display(typeName, config), REASON_QS_POLICY)
                    // Reported once, as a policy skip, not also as "kept fixed".
                    fixed.remove(key)
                    reached.remove(key)
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
        }
        return ColorDecisions(values, fixed, observed, inferred, groups, reached, tableObserved, tableAdded, qsSkipped, dropped, verbatim)
    }

    /**
     * Maps planned [values] onto [table] per configuration: the overlay colours, and the names the
     * table lacks ([ResourceMapper.REASON_NOT_IN_TARGET]) as skips named `pkg:type/name`.
     */
    internal fun mapColors(
        pkg: String,
        table: ResourceTable,
        semwt: FabricatedOverlayFile?,
        values: Map<Pair<String, String>, Int>,
    ): Pair<List<PlannedColor>, List<Skipped>> {
        val out = mutableListOf<PlannedColor>()
        val skipped = mutableListOf<Skipped>()
        for ((config, group) in values.entries.groupBy({ it.key.second }, { it.key.first to it.value })) {
            val plan = ResourceMapper.map(table, semwt, ThemeRequest(colors = group.toMap(LinkedHashMap())))
            plan.colorEntries.mapTo(out) { it.copy(configuration = config) }
            plan.skipped.mapTo(skipped) { Skipped("$pkg:${it.name.substringAfter(':')}", it.reason) }
        }
        return out to skipped
    }

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
        val extraColours: List<ExtraColour> = emptyList(),
    ) {
        val pkg = snapshot.packageName
        val semwt: FabricatedOverlayFile? = snapshot.semwt
        val skipped = mutableListOf<Skipped>()

        fun compile(): CompiledTarget {
            val colors = compileColors()
            val drawables = compileDrawables()
            val values = compileValues()
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
                semwtConfigMatches = semwtConfigMatches(colors),
                values = values,
            )
        }

        /** The value overlay's content ([ValuePlanner]), with one `values planned` event. */
        private fun compileValues(): List<PlannedValue> {
            val plan = ValuePlanner.plan(pkg, snapshot, spec, rules)
            if (plan.requested == 0 && plan.skipped.isEmpty()) return emptyList()
            skipped += plan.skipped
            val rescan = plan.skipped.any { it.reason == ValuePlanner.REASON_RESCAN }
            event(
                if (rescan || plan.clamped > 0) LogLevel.WARN else LogLevel.INFO, pkg,
                if (rescan) "values skipped: re-scan needed" else "values planned",
                mapOf(
                    "count" to plan.values.size.toString(),
                    "skipped" to plan.skipped.size.toString(),
                    "clamped" to plan.clamped.toString(),
                    "controls" to plan.controls.joinToString(","),
                    "overlay" to ValuePlanner.overlayName(pkg),
                ),
            )
            return plan.values
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

        /** Every-configuration colours whose planned value equals SemWT's value in that configuration. */
        private fun semwtConfigMatches(colors: List<PlannedColor>): Set<String> {
            val semwtValues = HashMap<String, MutableSet<Int>>()
            for (e in semwt?.entries.orEmpty()) {
                if (e.dataType !in COLOR_TYPES) continue
                val key = "$pkg:${rules.rename(e.typeAndName)}|${e.configuration.orEmpty()}"
                val values = semwtValues.getOrPut(key) { HashSet() }
                values += e.data
                if (e.dataType == TYPE_RGB8 || e.dataType == TYPE_RGB4) values += e.data or OPAQUE
            }
            if (semwtValues.isEmpty()) return emptySet()
            return colors.mapNotNull { c ->
                val key = "${c.resourceName}|${c.configuration}"
                key.takeIf { semwtValues[it]?.contains(c.argb) == true }
            }.toCollection(LinkedHashSet())
        }

        private fun display(typeName: String, config: String) = "$pkg:$typeName" + if (config.isEmpty()) "" else " [$config]"

        private fun compileColors(): List<PlannedColor> {
            val d = planColors(pkg, table, semwt, ShadeReference(device.systemShades), spec, rules, extraColours, resolved)
            if (d.reached.isNotEmpty()) {
                val perGroup = d.reached.mapNotNull { d.groups[it] }.groupingBy { it }.eachCount()
                event(
                    LogLevel.INFO, pkg, "group colours",
                    mapOf(
                        "count" to d.reached.size.toString(),
                        "fromApk" to d.tableAdded.toString(),
                    ) + ColorGroup.ALL.mapNotNull { g -> perGroup[g]?.let { g.id to it.toString() } },
                )
            }
            skipped += d.qsSkipped
            if (pkg == SYSTEMUI) {
                val qs = rules.quickSettings
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
                        "colours" to (d.values.size - d.qsVerbatim).toString(),
                        "dropped" to d.qsDropped.toString(),
                        "verbatim" to d.qsVerbatim.toString(),
                    ),
                )
            }

            // Reported after the Quick Settings filter so only colours actually written verbatim are counted.
            if (d.fixed.isNotEmpty()) {
                event(
                    LogLevel.WARN, pkg, "colours kept fixed",
                    mapOf("count" to d.fixed.size.toString(), "names" to d.fixed.values.take(20).joinToString(",") { it.name }),
                )
            }
            skipped += d.fixed.values

            val (out, mapped) = mapColors(pkg, table, semwt, d.values)
            skipped += mapped
            return out
        }

        private fun compileDrawables(): List<PlannedDrawableJob> {
            val jobs = LinkedHashMap<Pair<String, String>, PlannedDrawableJob>()
            val missingPacks = HashSet<String>()
            // Catch-all elements first, then elements for every app (controls), then targeted
            // elements, so the more specific one overrides.
            val elements = Elements.forTarget(pkg).sortedBy {
                when {
                    it.id == Elements.MISC || it.id == Elements.SYSTEM_DRAWABLES -> 0
                    it.anyTarget -> 1
                    else -> 2
                }
            }
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
            if (spec.builtInIcons) {
                for (j in IconColors.builtInJobs(spec, pkg, table, rules, jobs.keys.toSet(), overridden, resolved())) {
                    jobs.putIfAbsent(j.resourceName to j.configuration, j)
                }
            }
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
                        synthesis = IconSynthesis(
                            row, IconColors.styleReference(spec.iconStyle, reference, resourceName, isNight(config), resolved()),
                        ),
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
            val palette = if (options["palette"] == "pack" || IconColors.keepsPackPalette(spec.iconStyle, element.id)) {
                emptyMap()
            } else {
                pack.paletteArgb + derived()
            }
            val entryNames = packs.entryNames(pack.id).ifEmpty { null }
            val jobs = LinkedHashMap<Pair<String, String>, PlannedDrawableJob>()
            val dayWinners = LinkedHashMap<Pair<String, String>, Pair<PlannedDrawable, CategoryName>>()
            // The category that wrote each night job, to compare it with the day winner.
            val nightSources = HashMap<Pair<String, String>, CategoryName>()

            fun job(pd: PlannedDrawable, category: CategoryName, night: Boolean, density: String): PlannedDrawableJob? {
                val plan = IconColors.style(
                    spec.iconStyle, element.id,
                    LayerPlanner.plan(pack, category.raw, pd.source.resourceName, palette, night, entryNames),
                    pd.resourceName, night, resolved(),
                )
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
                    val target = rules.renameFor("$type/$res") { table.find(it) != null }
                    if (request[target]?.resourceName?.let { "$type/$it" } == target) continue
                    request[target] = DrawableSource(pack.id, category.raw, res)
                }
                // Copies never replace a pack drawable that has the target's own name.
                for (res in pack.layers.filter { it.category == category.raw }.flatMap { it.resources }.distinct()) {
                    for (copy in rules.drawableCopies["$type/$res"].orEmpty()) {
                        if (copy in request || table.find(copy) == null) continue
                        request[copy] = DrawableSource(pack.id, category.raw, res)
                    }
                }
                val plan = ResourceMapper.map(table, semwt, ThemeRequest(drawables = request))
                if (!element.anyTarget) plan.skipped.mapTo(skipped) { Skipped("$pkg:${it.name}", it.reason) }
                for (pd in plan.drawableEntries) {
                    if (qsBlocked && pd.resourceName.substringAfter(':') !in rules.quickSettings.outsideDrawables) {
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
