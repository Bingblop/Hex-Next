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
 * - [SHELL] (uid 2000: Shizuku started over ADB or wireless debugging, no root): the system
 *   palette only through secure settings ([MonetSeed]). Hex Next never attempts per-app overlay
 *   fabrication, takeover, removal, PNG rewrites, fonts or backgrounds in this tier.
 * - [NONE]: no usable service.
 */
enum class Capability {
    NONE,
    SHELL,
    ROOT;

    /** The Monet seed at UID 2000; per-app colour overlays at UID 0 or UID 1000. */
    val canApplyColours: Boolean get() = this != NONE

    /** PNG drawables (custom icons, backgrounds) and fonts: the root `.frro` rewrite and `/data` writes. */
    val canApplyDrawables: Boolean get() = this == ROOT

    /** "Full (root)" / "System palette only (Shizuku)" / "Not connected" for the Connection card and the status pill. */
    val label: String
        get() = when (this) {
            ROOT -> "Full (root)"
            SHELL -> "System palette only (Shizuku)"
            NONE -> "Not connected"
        }

    companion object {
        const val SHELL_UID = ThemeService.SHELL_UID

        /** One line on why per-app colours, icons, backgrounds and fonts are off in the Shizuku (no root) tier. */
        const val NEEDS_ROOT_REASON = "Per-app colours, icons, backgrounds and fonts need root. Without it only the system palette (Monet seed) changes."

        fun fromUid(uid: Int): Capability = when (uid) {
            0, 1000 -> ROOT
            SHELL_UID -> SHELL
            else -> NONE
        }

        /**
         * The tier of the current connection, before the service is bound (from the Shizuku uid).
         *
         * SU mode ([ShizukuState.Ready.viaSu]: `su` found, Shizuku not running) is [NONE]: its
         * Ready uid of 0 is only a stand-in, the service then lives in the app process (uid 10xxx)
         * and reaches root through `su -c` for commands alone, so it cannot commit overlays and
         * [ThemeApplier] stops before applying anything.
         */
        fun of(state: ShizukuState): Capability = when (state) {
            is ShizukuState.Ready -> if (state.viaSu) NONE else fromUid(state.uid)
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

    /** Where [restore] keeps an unparsable [FILE_NAME] before clearing it. */
    const val UNREADABLE_FILE_NAME = "monet_state.unreadable.json"

    /** The pseudo target the apply report lists this step under. */
    const val TARGET = "system palette"

    const val KEY_PALETTE = "android.theme.customization.system_palette"
    const val KEY_ACCENT = "android.theme.customization.accent_color"
    const val KEY_SOURCE = "android.theme.customization.color_source"
    const val SOURCE_PRESET = "preset"

    private const val STATE_PREVIOUS = "previous"
    private const val STATE_SAVED_AT = "savedAt"
    private const val STATE_SEED = "seed"

    /** The seed the previous apply wrote, kept so a failed write of [STATE_SEED] still restores. */
    private const val STATE_SEED_BEFORE = "seedBefore"

    /** `RRGGBB` (no `#`), the form ThemePicker writes. */
    fun hex(argb: Int): String = "%06X".format(argb and 0xFFFFFF)

    /**
     * The current setting text, "" when it is unset ("null" from `settings get`), or null when the
     * read itself failed (non-zero exit, timeout, no output at all). A failed read must never be
     * taken for "unset": [restore] would then delete the user's real palette setting.
     */
    fun read(service: IThemeService): String? {
        val (code, text) = ExecOutput.exec(service, "settings get secure $SETTING")
        val value = text.trim()
        return when {
            code != 0 || value.isEmpty() -> null
            value == "null" -> ""
            else -> value
        }
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
    fun holdsSeed(setting: String, seedArgb: Int): Boolean = holdsHex(setting, hex(seedArgb))

    /** [holdsSeed] for a seed already in [hex] form. */
    private fun holdsHex(setting: String, hex: String): Boolean = try {
        val json = JSONObject(setting)
        json.optString(KEY_PALETTE).equals(hex, ignoreCase = true) && json.optString(KEY_ACCENT).equals(hex, ignoreCase = true)
    } catch (e: JSONException) {
        false
    }

    /**
     * Sets the seed. The value in place before Hex Next's first write is captured in [FILE_NAME]
     * (a second apply keeps the first capture, so a remove still restores Samsung's value), with
     * the seed written. When the setting no longer holds that seed (the user picked a palette in
     * Wallpaper and style since), the current value is captured instead: that is the one to restore.
     * When that capture cannot be saved the seed is not written at all: without it a later remove
     * could only restore Hex Next's own value instead of the stock one.
     * Returns true when the setting reads back with the seed.
     */
    fun apply(service: IThemeService, seedArgb: Int, log: (LogLine) -> Unit): Boolean {
        val current = read(service)
        if (current == null) {
            log(LogLine(LogLevel.WARN, "System palette not set: could not read the current $SETTING value (settings get failed). Try applying again."))
            return false
        }
        val capture = readCapture(service)
        if (capture == null) {
            // Unreadable or unparsable: it may hold Samsung's value from the first apply. Writing a
            // new capture now would record Hex Next's own seed as "previous" and lose it.
            log(
                LogLine(
                    LogLevel.WARN,
                    "System palette not set: $FILE_NAME (the palette in place before Hex Next) could not be read. " +
                        "Nothing was changed; try again, or use Remove theme to clear it first.",
                ),
            )
            return false
        }
        val savedSeeds = seeds(capture)
        val heldSeed = savedSeeds.firstOrNull { holdsHex(current, it) }
        // A capture without a seed predates the seed record: kept as it is, so restore() still
        // puts it back unconditionally (the seed in place now is unknown).
        val legacy = capture.has(STATE_PREVIOUS) && savedSeeds.isEmpty()
        val keep = legacy || (capture.has(STATE_PREVIOUS) && heldSeed != null)
        val now = System.currentTimeMillis()
        val state = JSONObject()
            .put(STATE_PREVIOUS, if (keep) capture.optString(STATE_PREVIOUS) else current)
            .put(STATE_SAVED_AT, if (keep) capture.optLong(STATE_SAVED_AT, now) else now)
        if (!legacy) state.put(STATE_SEED, hex(seedArgb))
        if (heldSeed != null) state.put(STATE_SEED_BEFORE, heldSeed)
        // Rewritten on every apply so the seed restore() checks for is this apply's.
        val saved = service.writeStateFile(FILE_NAME, state.toString())
        if (saved.code() != 0) {
            log(
                LogLine(
                    LogLevel.WARN,
                    "System palette not set: could not save the previous palette to $FILE_NAME " +
                        "(${saved.output().lineSequence().firstOrNull().orEmpty()}). " +
                        "Apply once with root (Hex Next then hands ${MonitorScript.DIR} to the shell), " +
                        "or remove it with `su -c rm -rf ${MonitorScript.DIR}`, then try again.",
                ),
            )
            return false
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
        val after = read(service).orEmpty()
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
     * state file. When the setting no longer holds the seed Hex Next wrote (the user picked another
     * palette since), it is left as it is and only the state file is cleared; that counts as restored.
     * Returns true when it was restored, false when that failed (the state file is
     * kept, so another call retries) and null when nothing was captured (no file, or a cleared one).
     * A state file that cannot be read or parsed is a failure, never "nothing captured": the seed
     * may still be in place. An unparsable file is kept as [UNREADABLE_FILE_NAME] and cleared, so
     * the warning shows once instead of on every later remove.
     */
    fun restore(service: IThemeService, log: (LogLine) -> Unit): Boolean? {
        val bundle = service.readStateFile(FILE_NAME)
        when (bundle.code()) {
            0 -> Unit
            // Code 1: the file does not exist, so no apply ever captured the setting.
            1 -> return null
            else -> {
                log(LogLine(LogLevel.WARN, "System palette not restored: could not read $FILE_NAME (${bundle.output().lineSequence().firstOrNull().orEmpty().ifBlank { "exit ${bundle.code()}" }})."))
                return false
            }
        }
        val raw = bundle.getString(ThemeService.KEY_CONTENT).orEmpty()
        val state = try {
            if (raw.isBlank()) throw JSONException("empty file")
            JSONObject(raw)
        } catch (e: JSONException) {
            val kept = service.writeStateFile(UNREADABLE_FILE_NAME, raw).code() == 0 &&
                service.writeStateFile(FILE_NAME, JSONObject().toString()).code() == 0
            log(
                LogLine(
                    LogLevel.WARN,
                    "System palette not restored: $FILE_NAME is unreadable (${e.message}), so the previous palette is unknown. " +
                        (if (kept) "It was kept as $UNREADABLE_FILE_NAME. " else "") +
                        "Pick a colour palette in Wallpaper and style to replace the Hex Next seed.",
                ),
            )
            return false
        }
        if (!state.has(STATE_PREVIOUS)) return null
        val seeds = seeds(state)
        val current = if (seeds.isEmpty()) null else read(service)
        // No seed recorded (an older capture) or a failed read cannot tell, so it restores as before.
        if (current != null && seeds.none { holdsHex(current, it) }) {
            log(LogLine(LogLevel.OK, "System palette left as it is: it was changed after Hex Next set its seed"))
            if (service.writeStateFile(FILE_NAME, JSONObject().toString()).code() != 0) {
                log(LogLine(LogLevel.WARN, "Could not clear $FILE_NAME"))
            }
            return true
        }
        val previous = state.optString(STATE_PREVIOUS)
        val command = if (previous.isBlank()) {
            "settings delete secure $SETTING"
        } else {
            // The value was captured verbatim and need not be JSON (apply() then wrote a fresh
            // object), so it is quoted rather than refused: a quote becomes close, escaped quote,
            // reopen, and line breaks are literal inside single quotes for `sh -c`. Only NUL cannot
            // reach a command line.
            if ('\u0000' in previous) {
                log(LogLine(LogLevel.WARN, "Previous system palette not restored: its value holds a NUL character, which cannot be passed to the shell."))
                return false
            }
            "settings put secure $SETTING '${previous.replace("'", "'\\''")}'"
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

    /** The seeds Hex Next wrote since the capture, as recorded in [state]. */
    private fun seeds(state: JSONObject): List<String> =
        listOf(state.optString(STATE_SEED), state.optString(STATE_SEED_BEFORE)).filter { it.isNotEmpty() }

    /**
     * The content of [FILE_NAME]: an object with [STATE_PREVIOUS] when it holds a capture, an
     * object without it when nothing was captured (no file, or the empty object [restore] leaves),
     * null when the file could not be read or parsed. As in [restore], an unreadable file is never
     * taken for "nothing captured".
     */
    private fun readCapture(service: IThemeService): JSONObject? {
        val bundle = service.readStateFile(FILE_NAME)
        when (bundle.code()) {
            0 -> Unit
            1 -> return JSONObject()
            else -> return null
        }
        val raw = bundle.getString(ThemeService.KEY_CONTENT).orEmpty()
        if (raw.isBlank()) return null
        return try {
            JSONObject(raw)
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
