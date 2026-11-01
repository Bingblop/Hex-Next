package com.hexnext.engine.compile

import com.hexnext.engine.arsc.ConfiguredValue
import com.hexnext.engine.arsc.ResourceEntry
import com.hexnext.engine.arsc.ResourceTable
import com.hexnext.engine.arsc.ResourceValue
import com.hexnext.engine.device.MappedResource
import com.hexnext.engine.device.OneUi9Facts
import com.hexnext.engine.device.OneUiProfile
import com.hexnext.engine.device.OverlayDump
import com.hexnext.engine.frro.FabricatedOverlayFile
import com.hexnext.engine.frro.FrroEntry

/**
 * Everything the device scan learned about one target package, before it is turned into a
 * [TargetSnapshot] by [SnapshotMapper].
 *
 * @property installed true when `pm list packages` lists the package for the current user.
 * @property debloated true when the device scan found traces of a package that is not installed
 *   for the current user: listed by `pm list packages -u` only, or an APK still on `/system` or
 *   `/product`. [SnapshotMapper.isDebloated] also treats a SemWT `STATE_MISSING_TARGET` as
 *   debloated.
 * @property semwtState `mState` of `android:SemWT_<pkg>` when it was recorded without a dump.
 * @property dump the parsed `cmd overlay dump android:SemWT_<pkg>`, null when there is none.
 * @property table the target's `resources.arsc`, null when it could not be extracted or parsed.
 * @property semwtFrro the copied SemWT `.frro`, the fallback when [dump] has no mapping.
 */
public data class TargetScan(
    val packageName: String,
    val installed: Boolean,
    val debloated: Boolean = false,
    val versionCode: Long? = null,
    val semwtState: String? = null,
    val dump: OverlayDump? = null,
    val table: ResourceTable? = null,
    val semwtFrro: FabricatedOverlayFile? = null,
)

/** How a scanned target can be themed; the UI greys everything but [AVAILABLE]. */
public enum class TargetAvailability {
    /** Installed, with resource names from the arsc or Samsung's SemWT dump. */
    AVAILABLE,

    /** On the system image but removed for the user, or its SemWT overlay is `STATE_MISSING_TARGET`. */
    DEBLOATED,

    /** Not on the device. */
    NOT_INSTALLED,

    /** Installed, but neither the arsc nor a SemWT dump gave resource names. */
    NO_RESOURCES,
}

/**
 * Turns scan data into the [DeviceSnapshot] the [ThemeCompiler] consumes.
 *
 * The resource table of a target is its `resources.arsc` when available, extended by every
 * resource Samsung's SemWT dump maps (names the arsc reader missed are kept); without an arsc
 * the dump alone becomes a synthetic table (one entry per mapped resource, default and `night`
 * values). Samsung's overlay is rebuilt from the dump's colour mappings (`0x1c` entries, the
 * second value as `night`), or taken from the copied `.frro` when the dump has none.
 *
 * A debloated target becomes `installed = false, debloated = true` (see [TargetSnapshot]): it
 * cannot be themed for the user, the compiler skips it with [ThemeCompiler.REASON_DEBLOATED]
 * rather than "not installed", and the UI shows it greyed out as debloated.
 */
public object SnapshotMapper {
    /** `Res_value::TYPE_INT_COLOR_ARGB8`. */
    public const val TYPE_INT_COLOR_ARGB8: Int = 0x1c

    /** The FRRO configuration of the second value of a dump mapping line. */
    public const val NIGHT: String = "night"

    private const val DEFAULT_PACKAGE_ID = 0x7f

    /** Builds the snapshot of every target in [scans] (later duplicates of a package win). */
    public fun snapshot(oneUi: OneUiProfile, scans: Collection<TargetScan>, systemShades: Map<String, Int>): DeviceSnapshot =
        DeviceSnapshot(oneUi, scans.associate { it.packageName to target(it) }, systemShades)

    /** The [TargetSnapshot] of one scanned target. */
    public fun target(scan: TargetScan): TargetSnapshot {
        val debloated = isDebloated(scan)
        val semwt = scan.dump?.takeIf { it.entries.isNotEmpty() }?.let { semwtFromDump(it, scan.packageName) } ?: scan.semwtFrro
        val dumpTable = scan.dump?.takeIf { it.entries.isNotEmpty() }?.let { tableFromDump(it, scan.packageName) }
            ?: scan.semwtFrro?.let { tableFromFrro(it, scan.packageName) }
        val arsc = scan.table?.takeIf { it.packageName == scan.packageName }
        return TargetSnapshot(
            packageName = scan.packageName,
            installed = scan.installed && !debloated,
            debloated = debloated,
            table = merge(arsc, dumpTable),
            semwt = semwt,
            versionCode = scan.versionCode,
        )
    }

    /** True when [scan] is installed for nobody: removed for the user, or SemWT reports a missing target. */
    public fun isDebloated(scan: TargetScan): Boolean {
        if (scan.installed) return false
        if (scan.debloated) return true
        val state = scan.dump?.state?.ifEmpty { null } ?: scan.semwtState
        return state != null && OneUi9Facts.isDebloated(state)
    }

    /** What the UI should show for [snapshot]. */
    public fun availability(snapshot: TargetSnapshot): TargetAvailability = when {
        snapshot.debloated -> TargetAvailability.DEBLOATED
        !snapshot.installed -> TargetAvailability.NOT_INSTALLED
        snapshot.table == null -> TargetAvailability.NO_RESOURCES
        else -> TargetAvailability.AVAILABLE
    }

    /**
     * Samsung's SemWT overlay as the compiler sees it: one `0x1c` entry per colour mapping in the
     * default configuration and, when the line has a second colour, one in `night`. Mappings
     * whose values are not colours are left out.
     *
     * @param targetPackage used when the dump does not print `mTargetPackageName`.
     */
    public fun semwtFromDump(dump: OverlayDump, targetPackage: String = dump.targetPackage): FabricatedOverlayFile {
        val pkg = dump.targetPackage.ifEmpty { targetPackage }
        val entries = ArrayList<FrroEntry>()
        for (e in dump.entries) {
            val name = "$pkg:${e.typeAndName}"
            e.defaultColor?.let { entries += FrroEntry(name, TYPE_INT_COLOR_ARGB8, it) }
            e.nightColor?.let { entries += FrroEntry(name, TYPE_INT_COLOR_ARGB8, it, configuration = NIGHT) }
        }
        return FabricatedOverlayFile(
            name = dump.overlayName,
            targetPackage = pkg,
            targetOverlayable = dump.targetOverlayable,
            entries = entries,
            packageName = dump.packageName,
        )
    }

    /** A synthetic table holding exactly the resources [dump] maps, with their resource ids. */
    public fun tableFromDump(dump: OverlayDump, targetPackage: String = dump.targetPackage): ResourceTable {
        val pkg = dump.targetPackage.ifEmpty { targetPackage }
        val entries = dump.entries.distinctBy { it.typeAndName }.map { e ->
            val values = buildList {
                add(ConfiguredValue("", valueOf(e.defaultColor, e.defaultValue)))
                if (e.nightValue != null) add(ConfiguredValue(NIGHT, valueOf(e.nightColor, e.nightValue)))
            }
            ResourceEntry(e.type, e.name, e.resId, values)
        }
        val packageId = dump.entries.firstOrNull()?.resId?.ushr(24)?.takeIf { it != 0 } ?: DEFAULT_PACKAGE_ID
        return ResourceTable(pkg, packageId, entries)
    }

    /** A synthetic table from a SemWT `.frro`: one entry per distinct name; ids are unknown (0). */
    public fun tableFromFrro(frro: FabricatedOverlayFile, targetPackage: String = frro.targetPackage): ResourceTable {
        val pkg = frro.targetPackage.ifEmpty { targetPackage }
        val entries = frro.entries.groupBy { it.typeAndName }.mapNotNull { (typeName, values) ->
            val type = typeName.substringBefore('/', "")
            val name = typeName.substringAfter('/', "")
            if (type.isEmpty() || name.isEmpty()) return@mapNotNull null
            ResourceEntry(type, name, 0, values.map { ConfiguredValue(it.configuration.orEmpty(), ResourceValue.Other(it.dataType, it.data)) })
        }
        return ResourceTable(pkg, DEFAULT_PACKAGE_ID, entries)
    }

    /**
     * [primary] extended by the entries of [extra] it does not define (by `type/name`); either
     * alone when the other is null.
     */
    public fun merge(primary: ResourceTable?, extra: ResourceTable?): ResourceTable? {
        if (primary == null) return extra
        if (extra == null) return primary
        val missing = extra.entries.filter { primary.find(it.key) == null }
        if (missing.isEmpty()) return primary
        return primary.copy(entries = primary.entries + missing)
    }

    private fun valueOf(color: Int?, raw: String): ResourceValue =
        color?.let { ResourceValue.Color(it) } ?: ResourceValue.Other(0, MappedResource.parseHex(raw) ?: 0)
}
