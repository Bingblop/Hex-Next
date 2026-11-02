package com.hexnext.themer.theme

import android.os.Bundle
import com.hexnext.themer.IThemeService
import com.hexnext.themer.shizuku.ExecOutput
import com.hexnext.themer.shizuku.MonitorScript
import com.hexnext.themer.shizuku.ShizukuState
import com.hexnext.themer.shizuku.ThemeService
import org.json.JSONException
import org.json.JSONObject

/**
 * What the connected theme service can change on this phone, from the uid it runs as.
 *
 * - [ROOT] (uid 0 or 1000): everything. Fabricated overlays with colours and PNG drawables, the
 *   `.frro` rewrite, fonts and backgrounds.
 * - [SHELL] (uid 2000: Shizuku started over ADB or wireless debugging, no root): colours only.
 *   The shell may enable and disable overlays and write secure settings, so Hex Next sets the
 *   Monet seed ([MonetSeed]) and tries `cmd overlay fabricate` for colour overlays. Anything that
 *   rewrites files as root (PNG drawables, fonts, backgrounds) is skipped with a "Needs root" note.
 * - [NONE]: no usable service.
 */
enum class Capability {
    NONE,
    SHELL,
    ROOT;

    /** Colour overlays and the Monet seed. */
    val canApplyColours: Boolean get() = this != NONE

    /** PNG drawables (custom icons, backgrounds) and fonts: the root `.frro` rewrite and `/data` writes. */
    val canApplyDrawables: Boolean get() = this == ROOT

    /** "Full (root)" / "Colours only (Shizuku)" / "Not connected" for the Connection card and the status pill. */
    val label: String
        get() = when (this) {
            ROOT -> "Full (root)"
            SHELL -> "Colours only (Shizuku)"
            NONE -> "Not connected"
        }

    companion object {
        const val SHELL_UID = ThemeService.SHELL_UID

        /** One line on why icons, backgrounds and fonts are off in the colours-only tier. */
        const val NEEDS_ROOT_REASON = "Icons, backgrounds and fonts rewrite system files, which needs root. Colours still apply."

        fun fromUid(uid: Int): Capability = when (uid) {
            0, 1000 -> ROOT
            SHELL_UID -> SHELL
            else -> NONE
        }

        /** The tier of the current connection, before the service is bound (from the Shizuku uid). */
        fun of(state: ShizukuState): Capability = when (state) {
            is ShizukuState.Ready -> fromUid(state.uid)
            else -> NONE
        }
    }
}

/**
 * The Monet seed of the system palette: `Settings.Secure.theme_customization_overlay_packages`,
 * a JSON object System UI's ThemeOverlayController reads. Hex Next sets the palette and accent
 * keys to the theme's seed and keeps every other key as it was. The value found before the first
 * write is saved in the state file `monet_state.json`, so [restore] can put it back.
 *
 * Writing secure settings needs uid 0, 1000 or 2000; the shell tier therefore has this even
 * without root.
 */
object MonetSeed {
    const val SETTING = "theme_customization_overlay_packages"
    const val FILE_NAME = "monet_state.json"

    /** The pseudo target the apply report lists this step under. */
    const val TARGET = "system palette"

    const val KEY_PALETTE = "android.theme.customization.system_palette"
    const val KEY_ACCENT = "android.theme.customization.accent_color"
    const val KEY_SOURCE = "android.theme.customization.color_source"
    const val SOURCE_PRESET = "preset"

    private const val STATE_PREVIOUS = "previous"
    private const val STATE_SAVED_AT = "savedAt"
    private const val STATE_SEED = "seed"

    /** `RRGGBB` (no `#`), the form ThemePicker writes. */
    fun hex(argb: Int): String = "%06X".format(argb and 0xFFFFFF)

    /** The current setting text, or "" when unset ("null" from `settings get`). */
    fun read(service: IThemeService): String {
        val (code, text) = ExecOutput.exec(service, "settings get secure $SETTING")
        val value = text.trim()
        return if (code != 0 || value.isEmpty() || value == "null") "" else value
    }

    /**
     * [current] with the palette and accent keys set to [seedArgb] and the colour source marked
     * as a preset. Keys already present are kept; an unreadable [current] starts a new object.
     */
    fun merged(current: String, seedArgb: Int): String {
        val json = try {
            if (current.isBlank()) JSONObject() else JSONObject(current)
        } catch (e: JSONException) {
            JSONObject()
        }
        val hex = hex(seedArgb)
        json.put(KEY_PALETTE, hex)
        json.put(KEY_ACCENT, hex)
        json.put(KEY_SOURCE, SOURCE_PRESET)
        return json.toString()
    }

    /** True when [setting] already carries [seedArgb] as palette and accent. */
    fun holdsSeed(setting: String, seedArgb: Int): Boolean = try {
        val json = JSONObject(setting)
        val hex = hex(seedArgb)
        json.optString(KEY_PALETTE).equals(hex, ignoreCase = true) && json.optString(KEY_ACCENT).equals(hex, ignoreCase = true)
    } catch (e: JSONException) {
        false
    }

    /**
     * Sets the seed. The value in place before Hex Next's first write is captured in [FILE_NAME]
     * (a second apply keeps the first capture, so a remove still restores Samsung's value).
     * When that capture cannot be saved the seed is not written at all: without it a later remove
     * could only restore Hex Next's own value instead of the stock one.
     * Returns true when the setting reads back with the seed.
     */
    fun apply(service: IThemeService, seedArgb: Int, log: (LogLine) -> Unit): Boolean {
        val current = read(service)
        if (readState(service) == null) {
            val state = JSONObject()
                .put(STATE_PREVIOUS, current)
                .put(STATE_SAVED_AT, System.currentTimeMillis())
                .put(STATE_SEED, hex(seedArgb))
            val saved = service.writeStateFile(FILE_NAME, state.toString())
            if (saved.code() != 0) {
                log(
                    LogLine(
                        LogLevel.WARN,
                        "System palette not set: could not save the previous palette to $FILE_NAME " +
                            "(${saved.output().lineSequence().firstOrNull().orEmpty()}). " +
                            "Apply once with root, or clear ${MonitorScript.DIR} with adb, then try again.",
                    ),
                )
                return false
            }
        }
        val next = merged(current, seedArgb)
        if (!isShellSafe(next)) {
            log(LogLine(LogLevel.WARN, "System palette not set: the current $SETTING value has characters Hex Next does not pass to the shell."))
            return false
        }
        val put = service.exec("settings put secure $SETTING '$next'")
        if (put.code() != 0) {
            log(LogLine(LogLevel.WARN, "settings put secure $SETTING failed (exit ${put.code()}): ${put.output().lineSequence().firstOrNull().orEmpty()}"))
            return false
        }
        val after = read(service)
        val ok = holdsSeed(after, seedArgb)
        log(
            LogLine(
                if (ok) LogLevel.OK else LogLevel.WARN,
                if (ok) "System palette seed set to #${hex(seedArgb)} (Monet)" else "System palette did not read back the seed: $after",
            ),
        )
        return ok
    }

    /**
     * Puts the captured value back (or deletes the setting when there was none) and clears the
     * state file. Returns true when something was restored; false when nothing was captured.
     */
    fun restore(service: IThemeService, log: (LogLine) -> Unit): Boolean {
        val state = readState(service) ?: return false
        val previous = state.optString(STATE_PREVIOUS)
        val command = if (previous.isBlank()) {
            "settings delete secure $SETTING"
        } else {
            if (!isShellSafe(previous)) {
                log(LogLine(LogLevel.WARN, "Previous system palette not restored: its value has characters Hex Next does not pass to the shell."))
                return false
            }
            "settings put secure $SETTING '$previous'"
        }
        val r = service.exec(command)
        if (r.code() == 0) {
            log(LogLine(LogLevel.OK, if (previous.isBlank()) "System palette setting cleared (it was unset before)" else "Previous system palette restored"))
            if (service.writeStateFile(FILE_NAME, JSONObject().toString()).code() != 0) {
                log(LogLine(LogLevel.WARN, "Could not clear $FILE_NAME"))
            }
        } else {
            log(LogLine(LogLevel.WARN, "Restoring the system palette failed (exit ${r.code()}): ${r.output().lineSequence().firstOrNull().orEmpty()}"))
        }
        return r.code() == 0
    }

    /** The saved state, or null when there is none (file missing, empty object, unreadable). */
    private fun readState(service: IThemeService): JSONObject? {
        val bundle = service.readStateFile(FILE_NAME)
        if (bundle.code() != 0) return null
        return try {
            JSONObject(bundle.getString(ThemeService.KEY_CONTENT).orEmpty()).takeIf { it.has(STATE_PREVIOUS) }
        } catch (e: JSONException) {
            null
        }
    }

    /**
     * JSON goes to the shell in single quotes: it must hold none, and no line breaks. A backslash
     * is literal inside single quotes, and org.json writes `\/` for every slash, so it stays allowed.
     */
    private fun isShellSafe(value: String): Boolean =
        value.none { it == '\'' || it == '\n' || it == '\r' || it == '\u0000' }

    private fun Bundle.code() = getInt(ThemeService.KEY_CODE, -1)
    private fun Bundle.output() = getString(ThemeService.KEY_OUTPUT).orEmpty()
}
