package com.hexnext.themer.theme

import android.os.Bundle
import com.hexnext.themer.IThemeService
import com.hexnext.themer.shizuku.ExecOutput
import com.hexnext.themer.shizuku.ThemeService
import java.io.File

/**
 * Installs the user's font as the system `sans-serif` family through the root theme service.
 *
 * [plan] reads `/system/etc/fonts.xml` (fallback `/system/etc/font_fallback.xml`) at apply time
 * and lists the font files of the first `<family name="sans-serif">` block, so no file name is
 * hardcoded. [install] writes a KernelSU/Magisk-style module to [MODULE_DIR] (`module.prop` +
 * `system/fonts/<FILE>` for every slot: weight ≥ 600 → the bold file, else the regular one;
 * italic slots get the same files) by copying the app-private font files as root, then, for a
 * live effect without a reboot, bind-mounts every module file over `/system/fonts/<FILE>` in
 * init's mount namespace (`nsenter -t 1 -m`) unless `/proc/1/mountinfo` shows it bound already,
 * and restarts Settings only. The bound paths are saved in the service state file [STATE_FILE].
 * [remove] unmounts them, deletes the module and clears the state.
 *
 * Every path piece interpolated into a shell command is validated against `[A-Za-z0-9_.-]+`
 * and single-quoted. All functions block on the service; call them off the main thread.
 */
class FontInstaller(private val service: IThemeService) {

    /** One `<font>` of the default family: the file under `/system/fonts`, its weight and style. */
    data class Slot(val file: String, val weight: Int, val italic: Boolean)

    /** The default family as read from the device: [source] is the XML read, [slots] its fonts (may be empty). */
    data class FontPlan(val source: String, val slots: List<Slot>) {
        /** Distinct file names, in order of first appearance. */
        val files: List<String> get() = slots.map { it.file }.distinct()

        /** True when [file] should carry the bold font: every slot naming it weighs 600 or more. */
        fun isBold(file: String): Boolean {
            val weights = slots.filter { it.file == file }.map { it.weight }
            return weights.isNotEmpty() && weights.all { it >= BOLD_WEIGHT }
        }
    }

    /** Reads the font configuration and returns the `sans-serif` slots; empty when none were found. */
    fun plan(): FontPlan {
        for (path in listOf(FONTS_XML, FALLBACK_XML)) {
            val (code, text) = ExecOutput.exec(service, "cat '$path'")
            if (code != 0 || text.isBlank()) continue
            val slots = parseSansSerif(text)
            if (slots.isNotEmpty()) return FontPlan(path, slots)
        }
        return FontPlan(FONTS_XML, emptyList())
    }

    /**
     * Installs [regular] (and [bold], or [regular] again when null) over the default family.
     * Returns true when every module file was written and bound. Failures are logged, never thrown.
     */
    fun install(regular: File, bold: File?, log: (LogLine) -> Unit): Boolean {
        if (!requireRoot(log)) return false
        val regularPath = safeAbsolutePath(regular) ?: run {
            log(LogLine(LogLevel.ERROR, "Font path cannot be used in a shell command: ${regular.path}"))
            return false
        }
        val boldPath = if (bold == null) {
            regularPath
        } else {
            safeAbsolutePath(bold) ?: run {
                log(LogLine(LogLevel.WARN, "Bold font path cannot be used in a shell command; using the regular file: ${bold.path}"))
                regularPath
            }
        }
        if (!regular.isFile) {
            log(LogLine(LogLevel.ERROR, "Font file is missing: ${regular.name}"))
            return false
        }
        val plan = plan()
        if (plan.slots.isEmpty()) {
            log(LogLine(LogLevel.ERROR, "No sans-serif family found in $FONTS_XML; the font was not installed"))
            return false
        }
        log(LogLine(LogLevel.INFO, "Default font files (${plan.source}): ${plan.files.joinToString()}"))
        val files = plan.files.filter { SAFE_PIECE.matches(it) }
        if (files.size != plan.files.size) {
            log(LogLine(LogLevel.WARN, "Skipping ${plan.files.size - files.size} font files with unusual names"))
        }
        if (files.isEmpty()) return false

        var ok = true
        if (!run("mkdir -p '$FONTS_DIR'", log)) return false
        if (!run("printf '%s\\n' $MODULE_PROP_LINES > '$MODULE_DIR/module.prop'", log)) ok = false

        val bound = ArrayList<String>()
        val mounted = mountedFontFiles()
        for (file in files) {
            val source = if (plan.isBold(file)) boldPath else regularPath
            val moduleFile = "$FONTS_DIR/$file"
            val systemFile = "$SYSTEM_FONTS/$file"
            val copied = run(
                "cp -f '$source' '$moduleFile' && chmod 644 '$moduleFile' && chown root:root '$moduleFile' && chcon u:object_r:system_file:s0 '$moduleFile'",
                log,
            )
            if (!copied) {
                ok = false
                continue
            }
            if (systemFile in mounted) {
                log(LogLine(LogLevel.INFO, "$systemFile is already bound; left as is"))
                bound += systemFile
                continue
            }
            val exists = service.exec("[ -f '$systemFile' ]").code() == 0
            if (!exists) {
                log(LogLine(LogLevel.WARN, "$systemFile does not exist on this device; module file written, not bound"))
                continue
            }
            if (run("nsenter -t 1 -m -- mount --bind '$moduleFile' '$systemFile'", log)) {
                bound += systemFile
            } else {
                ok = false
            }
        }
        writeState(bound)
        run("am force-stop $SETTINGS", log)
        log(
            LogLine(
                if (ok) LogLevel.OK else LogLevel.WARN,
                "Font installed: ${bound.size} of ${files.size} files bound. Reboot for the font to apply everywhere.",
            ),
        )
        return ok
    }

    /** Unmounts every bound font file, deletes the module and clears the state. */
    fun remove(log: (LogLine) -> Unit) {
        val recorded = readState()
        if (uid() != 0) {
            // Nothing runs without root; a stale record is worth one line, a clean device none.
            if (recorded.isNotEmpty()) log(LogLine(LogLevel.WARN, "Font changes need a root service (uid 0); running as uid ${uid()}. ${recorded.size} bound font files were left as they are"))
            return
        }
        val paths = LinkedHashSet<String>()
        paths += recorded
        paths += mountedFontFiles()
        if (paths.isEmpty() && service.exec("[ -d '$MODULE_DIR' ]").code() != 0) {
            return
        }
        log(LogLine(LogLevel.INFO, "Removing the Hex Next font (${paths.size} bound files)"))
        val stillBound = ArrayList<String>()
        for (path in paths) {
            if (!SAFE_SYSTEM_PATH.matches(path)) continue
            if (!run("nsenter -t 1 -m -- umount '$path'", log) && !run("nsenter -t 1 -m -- umount -l '$path'", log)) {
                stillBound += path
            }
        }
        run("rm -rf '$MODULE_DIR'", log)
        run("am force-stop $SETTINGS", log)
        if (stillBound.isEmpty()) {
            service.writeStateFile(STATE_FILE, EMPTY_STATE)
            log(LogLine(LogLevel.INFO, "Font removed. Reboot to restore the system font everywhere."))
        } else {
            writeState(stillBound)
            log(LogLine(LogLevel.WARN, "${stillBound.size} font files could not be unmounted; they stay recorded for the next removal. Reboot to restore the system font."))
        }
    }

    /** True when a previous [install] left bound files recorded. */
    fun hasState(): Boolean = readState().isNotEmpty()

    // ---- helpers --------------------------------------------------------------------------

    private fun uid(): Int = runCatching { service.uid }.getOrDefault(-1)

    /** Logs one line and returns false when the service is not root; no shell command runs then. */
    private fun requireRoot(log: (LogLine) -> Unit): Boolean {
        val uid = uid()
        if (uid == 0) return true
        log(LogLine(LogLevel.ERROR, "Font changes need a root service (uid 0); running as uid $uid"))
        return false
    }

    /** Runs [command] as root; logs it, returns whether it exited 0. */
    private fun run(command: String, log: (LogLine) -> Unit): Boolean {
        val r = service.exec(command)
        return if (r.code() == 0) {
            log(LogLine(LogLevel.INFO, "$ $command"))
            true
        } else {
            log(LogLine(LogLevel.WARN, "$ $command -> exit ${r.code()}: ${r.output().trim().ifBlank { "no output" }}"))
            false
        }
    }

    /** Mount points under `/system/fonts` whose bind source lives in the font module, from init's mountinfo. */
    private fun mountedFontFiles(): Set<String> {
        val (code, text) = ExecOutput.exec(service, "cat /proc/1/mountinfo")
        if (code != 0) return emptySet()
        return parseMountInfo(text)
    }

    private fun writeState(bound: List<String>) {
        val json = buildString {
            append("{\"module\":\"").append(MODULE_DIR).append("\",\"bound\":[")
            bound.forEachIndexed { i, path ->
                if (i > 0) append(',')
                append('"').append(path).append('"')
            }
            append("]}")
        }
        service.writeStateFile(STATE_FILE, json)
    }

    private fun readState(): List<String> {
        val bundle = service.readStateFile(STATE_FILE)
        if (bundle.code() != 0) return emptyList()
        return parseState(bundle.getString(ThemeService.KEY_CONTENT).orEmpty())
    }

    private fun Bundle.code() = getInt(ThemeService.KEY_CODE, -1)
    private fun Bundle.output() = getString(ThemeService.KEY_OUTPUT).orEmpty()

    companion object {
        const val MODULE_ID = "hexnext_font"
        const val MODULE_DIR = "/data/adb/modules/$MODULE_ID"
        const val FONTS_DIR = "$MODULE_DIR/system/fonts"
        const val SYSTEM_FONTS = "/system/fonts"
        const val STATE_FILE = "font.json"
        const val FONTS_XML = "/system/etc/fonts.xml"
        const val FALLBACK_XML = "/system/etc/font_fallback.xml"
        const val BOLD_WEIGHT = 600
        private const val SETTINGS = "com.android.settings"
        private const val EMPTY_STATE = "{\"bound\":[]}"

        /** `module.prop` lines, each single-quoted for `printf '%s\n'`. */
        private val MODULE_PROP_LINES = listOf(
            "id=$MODULE_ID",
            "name=Hex Next font",
            "version=1",
            "versionCode=1",
            "author=Hex Next",
            "description=Custom system font applied by Hex Next",
        ).joinToString(" ") { "'$it'" }

        /** One path segment; `.` and `..` are rejected so no piece can escape its directory. */
        private val SAFE_PIECE = Regex("^(?!\\.\\.?$)[A-Za-z0-9_.-]+$")
        private val SAFE_SYSTEM_PATH = Regex("^/system/fonts/(?!\\.\\.?$)[A-Za-z0-9_.-]+$")
        private val FAMILY_OPEN = Regex("<family\\b([^>]*)>", RegexOption.IGNORE_CASE)
        private val FONT = Regex("<font\\b([^>]*)>(.*?)</font>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val ATTR = Regex("([A-Za-z_:-]+)\\s*=\\s*\"([^\"]*)\"")
        private val TAGS = Regex("<[^>]*>")

        /**
         * The absolute path of [file] when every segment is a safe shell piece (the app's own
         * `files/custom/fonts/<name>` always is), else null.
         */
        internal fun safeAbsolutePath(file: File): String? {
            val path = file.absolutePath
            val pieces = path.split('/').filter { it.isNotEmpty() }
            if (pieces.isEmpty() || pieces.any { !SAFE_PIECE.matches(it) }) return null
            return "/" + pieces.joinToString("/")
        }

        /**
         * The `<font>` entries of the first `<family name="sans-serif">` block of a `fonts.xml`.
         * Child elements inside `<font>` (variable-font `<axis>` tags) are ignored; the text is the
         * file name. Fonts with a `fallbackFor` attribute are skipped.
         */
        internal fun parseSansSerif(xml: String): List<Slot> {
            var start = -1
            for (open in FAMILY_OPEN.findAll(xml)) {
                val attrs = attributes(open.groupValues[1])
                if (attrs["name"] == "sans-serif") {
                    start = open.range.last + 1
                    break
                }
            }
            if (start < 0) return emptyList()
            val end = xml.indexOf("</family>", start, ignoreCase = true).let { if (it < 0) xml.length else it }
            val block = xml.substring(start, end)
            val slots = ArrayList<Slot>()
            for (font in FONT.findAll(block)) {
                val attrs = attributes(font.groupValues[1])
                if (attrs.containsKey("fallbackFor")) continue
                val file = font.groupValues[2].replace(TAGS, "").trim()
                if (file.isEmpty()) continue
                val weight = attrs["weight"]?.toIntOrNull() ?: 400
                val italic = attrs["style"]?.equals("italic", ignoreCase = true) == true
                slots += Slot(file, weight, italic)
            }
            return slots
        }

        /** Mount points `/system/fonts/<file>` whose root (field 4) lies inside the font module. */
        internal fun parseMountInfo(text: String): Set<String> {
            val result = LinkedHashSet<String>()
            for (line in text.lineSequence()) {
                val fields = line.trim().split(Regex("\\s+"))
                if (fields.size < 5) continue
                val root = fields[3]
                val mountPoint = fields[4]
                if (mountPoint.startsWith("$SYSTEM_FONTS/") && root.contains("/$MODULE_ID/")) result += mountPoint
            }
            return result
        }

        /** The `bound` paths of a [STATE_FILE] body; tolerant of anything unexpected. */
        internal fun parseState(json: String): List<String> {
            val at = json.indexOf("\"bound\"")
            if (at < 0) return emptyList()
            val open = json.indexOf('[', at)
            val close = json.indexOf(']', open + 1)
            if (open < 0 || close < 0) return emptyList()
            return Regex("\"([^\"]+)\"").findAll(json.substring(open + 1, close)).map { it.groupValues[1] }
                .filter { SAFE_SYSTEM_PATH.matches(it) }.toList()
        }

        private fun attributes(text: String): Map<String, String> =
            ATTR.findAll(text).associate { it.groupValues[1] to it.groupValues[2] }
    }
}
