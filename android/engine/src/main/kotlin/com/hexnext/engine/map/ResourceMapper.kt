package com.hexnext.engine.map

import com.hexnext.engine.arsc.ResourceTable
import com.hexnext.engine.frro.FabricatedOverlayFile

/**
 * Where a themed drawable comes from: [resourceName] in [category] of pack [packId].
 */
public data class DrawableSource(
    val packId: String,
    val category: String,
    val resourceName: String,
)

/**
 * What the user wants applied to one target package.
 *
 * Keys are `type/name` (`color/qs_tile_active`); a `package:` prefix is accepted and ignored.
 * A [drawables] key without a type (`ic_sysbar_back`) means `drawable/ic_sysbar_back`.
 */
public data class ThemeRequest(
    val colors: Map<String, Int> = emptyMap(),
    val drawables: Map<String, DrawableSource> = emptyMap(),
)

/**
 * A colour to fabricate. [resourceName] is `package:type/name`; [configuration] is the FRRO
 * configuration qualifier string (`""` for the default configuration, `night` …).
 */
public data class PlannedColor(val resourceName: String, val argb: Int, val configuration: String = "")

/**
 * A drawable to composite and fabricate once per entry of [configs]: real density qualifiers
 * such as `xxhdpi` (see [ResourceMapper.pngDensities]), never `""` or `anydpi`.
 */
public data class PlannedDrawable(val resourceName: String, val source: DrawableSource, val configs: List<String>)

/** A requested resource that will not be built, and why. */
public data class Skipped(val name: String, val reason: String)

/**
 * The resolved overlay content for [targetPackage].
 *
 * @property semwtCoverage how many planned resources the Samsung `SemWT_*` overlay also
 *   overrides (Hex Next must out-prioritise SemWT for these); 0 without a SemWT overlay.
 */
public data class OverlayPlan(
    val targetPackage: String,
    val colorEntries: List<PlannedColor>,
    val drawableEntries: List<PlannedDrawable>,
    val skipped: List<Skipped>,
    val semwtCoverage: Int,
) {
    /** Total planned resources. */
    public val size: Int get() = colorEntries.size + drawableEntries.size
}

/** Matches a [ThemeRequest] against the resources a target package really has. */
public object ResourceMapper {
    /** Reason used in [Skipped] for names the target table does not define. */
    public const val REASON_NOT_IN_TARGET: String = "not present in target"

    /** Reason used in [Skipped] for keys that are not `type/name`. */
    public const val REASON_BAD_NAME: String = "invalid resource name"

    /**
     * Density qualifier PNG artwork is planned under when the target has no usable density of
     * its own (its own value is in the default configuration, or in `anydpi`). It matches the
     * size the app renders unqualified artwork at (xxhdpi, 480 dpi).
     */
    public const val FALLBACK_DENSITY: String = "xxhdpi"

    /**
     * The density qualifiers to write PNG artwork under, from the target's [qualifiers].
     *
     * A PNG must not go in the default configuration (Android decodes it as mdpi, so artwork
     * rendered at xxhdpi size draws three times too large) or in `anydpi` (density 0xFFFE is
     * taken as the source density, so the bitmap is shrunk to about one pixel). Both become
     * [FALLBACK_DENSITY]; real densities (and `nodpi`) are kept. Never empty.
     */
    public fun pngDensities(qualifiers: List<String>): List<String> =
        qualifiers.map { if (it.isEmpty() || it == "anydpi") FALLBACK_DENSITY else it }
            .distinct().ifEmpty { listOf(FALLBACK_DENSITY) }

    /**
     * Builds the overlay plan for [target].
     *
     * Requested names missing from [target] are reported in [OverlayPlan.skipped] rather than
     * fabricated (an FRRO with unknown names is accepted by `cmd overlay` but changes nothing).
     * Results keep the request's iteration order.
     */
    public fun map(target: ResourceTable, semwt: FabricatedOverlayFile?, request: ThemeRequest): OverlayPlan {
        val pkg = target.packageName
        val skipped = mutableListOf<Skipped>()
        val semwtNames = semwt?.entries?.mapTo(HashSet()) { it.typeAndName }.orEmpty()
        var coverage = 0

        val colors = request.colors.mapNotNull { (key, argb) ->
            val typeName = normalize(key, defaultType = null)
            when {
                typeName == null -> null.also { skipped += Skipped(key, REASON_BAD_NAME) }
                target.find(typeName) == null -> null.also { skipped += Skipped(key, REASON_NOT_IN_TARGET) }
                else -> {
                    if (typeName in semwtNames) coverage++
                    PlannedColor("$pkg:$typeName", argb)
                }
            }
        }

        val drawables = request.drawables.mapNotNull { (key, source) ->
            val typeName = normalize(key, defaultType = "drawable")
            val entry = typeName?.let(target::find)
            when {
                typeName == null -> null.also { skipped += Skipped(key, REASON_BAD_NAME) }
                entry == null -> null.also { skipped += Skipped(key, REASON_NOT_IN_TARGET) }
                else -> {
                    if (typeName in semwtNames) coverage++
                    val densities = pngDensities(entry.values.map { it.resConfig.densityQualifier })
                    PlannedDrawable("$pkg:$typeName", source, densities)
                }
            }
        }
        return OverlayPlan(pkg, colors, drawables, skipped, coverage)
    }

    /** `[pkg:]type/name` → `type/name`; a bare name gets [defaultType]; null when invalid. */
    private fun normalize(key: String, defaultType: String?): String? {
        val noPkg = key.substringAfter(':').trim()
        val withType = if ('/' !in noPkg && defaultType != null) "$defaultType/$noPkg" else noPkg
        val type = withType.substringBefore('/', "")
        val name = withType.substringAfter('/', "")
        return if (type.isEmpty() || name.isEmpty() || '/' in name) null else "$type/$name"
    }
}
