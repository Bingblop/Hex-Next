package com.hexnext.engine.device

/**
 * Overlays left behind by Hex Next builds before the theme compiler (the "Build & Apply One UI
 * 9.0 System RROs" path and early probes). They were registered as root, so their owner is
 * `com.android.shell`. On SM-S948U1 that path registered 19 `HexNextOneUI9_*` overlays, enabled
 * only a few plus `HexNextMonetRoles` / `HexNextMonetShades` (Monet shades on `android`, which
 * skew the system palette role inference reads), and never disabled Samsung's SemWT overlays.
 * Apply and Remove theme disable them and, where possible, unregister them.
 */
public object LegacyOverlays {
    /** Owner package of every legacy overlay (registered as uid 0). */
    public const val OWNER: String = "com.android.shell"

    /** Name prefix of the old per-app overlays (`HexNextOneUI9_SystemUI` …). */
    public const val ONE_UI9_PREFIX: String = "HexNextOneUI9_"

    /** Legacy overlay names matched exactly. */
    public val NAMES: List<String> = listOf("HexNextMonetShades", "HexNextMonetRoles", "HexNextSettingsIcons26", "HexNext_probe")

    private val SAFE_NAME = Regex("^[A-Za-z0-9_.]+$")

    /** True when [overlayId] (`owner:name`) is a legacy overlay. */
    public fun isLegacy(overlayId: String): Boolean {
        if (!SemWtPlanner.isSafeId(overlayId)) return false
        if (overlayId.substringBefore(':') != OWNER) return false
        val name = overlayId.substringAfter(':')
        return name in NAMES || (name.startsWith(ONE_UI9_PREFIX) && name.length > ONE_UI9_PREFIX.length)
    }

    /** The legacy overlays in a parsed `cmd overlay list`, each id once, in list order. */
    public fun find(overlays: List<OverlayListEntry>): List<OverlayListEntry> =
        overlays.filter { isLegacy(it.overlayId) }.distinctBy { it.overlayId }

    /**
     * `rm -f` of the stale `.frro` copies OverlayManager keeps in `/data/resource-cache` for the
     * legacy overlay [name] (`com.android.shell-<name>-<n>.frro`); null for unsafe names.
     */
    public fun purgeCommand(name: String): String? {
        if (!SAFE_NAME.matches(name)) return null
        return "rm -f /data/resource-cache/$OWNER-$name-*.frro"
    }
}
