package com.hexnext.themer.theme

import android.content.SharedPreferences
import android.os.Bundle
import com.hexnext.themer.IThemeService
import com.hexnext.themer.shizuku.ThemeService

enum class LogLevel { INFO, OK, WARN, ERROR }

data class LogLine(val level: LogLevel, val text: String)

/**
 * Rebuilt Hex Installer One UI 9.0 Overlay Engine.
 *
 * Operates exclusively via Shizuku (UID 0 or UID 1000) or SU root access.
 * Theme Park (`com.samsung.android.themepark`) requirements are completely removed.
 * Overlays are built and prioritized directly on top of Samsung's System RRO carrier stack
 * (`com.samsung.High_contrast_theme_II.*`).
 */
class ThemeEngine(private val prefs: SharedPreferences) {

    /**
     * Privileged UID 0 / UID 1000 path:
     * 1. Enables Samsung's base System RRO carrier (`com.samsung.High_contrast_theme_II.*`) if installed.
     * 2. Fabricates and commits the 65 Monet framework shades + 23 Material 3 dark roles.
     * 3. Fabricates and commits all 17 rebuilt One UI 9.0 app overlays at `highest` priority.
     */
    fun applyFabricated(
        service: IThemeService,
        palette: MonetPalette,
        enabledTargets: Set<String> = PackedAppOverlay.ALL.map { it.id }.toSet(),
        log: (LogLine) -> Unit,
    ): Boolean {
        log(LogLine(LogLevel.INFO, "Privilege verified (UID ${service.uid}) — Theme Park requirement bypassed."))

        val shades = palette.shadeEntries()
        log(LogLine(LogLevel.INFO, "Fabricating $SHADES_OVERLAY (${shades.size} Monet shades)…"))
        val shadeResult = service.applyColorOverlay(SHADES_OVERLAY, TARGET, shades.names(), shades.colors())
        if (!report(shadeResult, log)) return false

        val roles = palette.roleEntries()
        log(LogLine(LogLevel.INFO, "Fabricating $ROLES_OVERLAY (${roles.size} Material 3 dark roles)…"))
        val roleResult = service.applyColorOverlay(ROLES_OVERLAY, TARGET, roles.names(), roles.colors())
        if (!report(roleResult, log, failLevel = LogLevel.WARN)) {
            log(LogLine(LogLevel.WARN, "Role colors were skipped; the 65 Monet shades remain active."))
        }

        val selectedOverlays = PackedAppOverlay.ALL.filter { it.id in enabledTargets }
        for (packed in selectedOverlays) {
            // Enable underlying Samsung System RRO carrier if present on device
            service.exec("cmd overlay enable --user current ${packed.systemRroCarrier} 2>/dev/null || true")

            val entries = packed.buildEntries(palette)
            if (entries.isEmpty()) continue
            log(
                LogLine(
                    LogLevel.INFO,
                    "Layering ${packed.overlayName} over ${packed.systemRroCarrier} -> ${packed.targetPackage} (${entries.size} One UI 9.0 tokens)…"
                )
            )
            val res = service.applyColorOverlay(packed.overlayName, packed.targetPackage, entries.names(), entries.colors())
            if (!report(res, log, failLevel = LogLevel.WARN)) {
                log(LogLine(LogLevel.WARN, "Skipped ${packed.targetPackage} (not installed or resource mismatch)."))
            }
        }

        verify(service, shades.first { it.name == "system_accent1_500" }, log)
        log(LogLine(LogLevel.OK, "One UI 9.0 System RRO overlays applied (${selectedOverlays.size + 2} layers)."))
        return true
    }

    fun remove(service: IThemeService, isPrivileged: Boolean, log: (LogLine) -> Unit): Boolean {
        var ok = true
        if (isPrivileged) {
            val allNames = listOf(ROLES_OVERLAY, SHADES_OVERLAY) + PackedAppOverlay.ALL.map { it.overlayName }
            for (name in allNames) {
                log(LogLine(LogLevel.INFO, "Removing $name…"))
                ok = report(service.removeOverlay(name), log, failLevel = LogLevel.WARN) && ok
            }
        } else {
            log(LogLine(LogLevel.ERROR, "UID 0 (Root) or UID 1000 (System) is required to remove System RRO overlays."))
            return false
        }
        if (ok) log(LogLine(LogLevel.OK, "Restored stock One UI 9.0 System RRO state."))
        return ok
    }

    /** Reads back what the framework now resolves for one color. */
    private fun verify(service: IThemeService, expected: ColorEntry, log: (LogLine) -> Unit) {
        val lookup = service.exec("cmd overlay lookup --user current android ${expected.resourceName}")
        val text = lookup.output()
        val want = expected.argb.toHex().removePrefix("#")
        when {
            lookup.code() != 0 -> log(LogLine(LogLevel.WARN, "Couldn't read back ${expected.name}: $text"))
            text.contains(want, ignoreCase = true) ->
                log(LogLine(LogLevel.OK, "Verified: ${expected.name} now resolves to #$want."))
            else -> log(LogLine(LogLevel.WARN, "${expected.name} resolves to \"$text\", expected #$want."))
        }
    }

    private fun report(result: Bundle, log: (LogLine) -> Unit, failLevel: LogLevel = LogLevel.ERROR): Boolean {
        val ok = result.code() == 0
        val output = result.output()
        if (ok) {
            if (output.isNotBlank()) log(LogLine(LogLevel.OK, output))
        } else {
            log(LogLine(failLevel, "Exit ${result.code()}: ${output.ifBlank { "no output" }}"))
        }
        return ok
    }

    private fun Bundle.code() = getInt(ThemeService.KEY_CODE, -1)
    private fun Bundle.output() = getString(ThemeService.KEY_OUTPUT).orEmpty()
    private fun List<ColorEntry>.names() = map { it.resourceName }.toTypedArray()
    private fun List<ColorEntry>.colors() = map { it.argb }.toIntArray()

    companion object {
        const val TARGET = "android"
        const val SHADES_OVERLAY = "HexNextMonetShades"
        const val ROLES_OVERLAY = "HexNextMonetRoles"
    }
}
