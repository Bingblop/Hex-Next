package com.hexnext.themer.theme

import android.os.Bundle
import com.hexnext.themer.BuildConfig
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
 * italic slots get the same files) by copying the app-private font files as root to a new file
 * that is renamed over the old one (a bound module file is never rewritten in place), then, for a
 * live effect without a reboot, bind-mounts every module file over `/system/fonts/<FILE>` in
 * init's mount namespace (`nsenter -t 1 -m`), first unmounting a bind from an earlier install
 * that `/proc/1/mountinfo` shows, and restarts Settings only. The bound paths are saved in the
 * service state file [STATE_FILE]. When one variable file serves every weight, the bold font is
 * added as [BOLD_FILE] with rewritten font XMLs ([installSharedBold]), active after a reboot.
 * [remove] unmounts what is still mounted, deletes the module and clears the state.
 *
 * The Play and owner builds can be installed side by side, but two font modules would fight over
 * the same `/system/fonts` files, so both use the one [MODULE_DIR]. Its `module.prop` records the
 * installing application id under [OWNER_KEY]; the last build to install a font owns it, and
 * [remove] (also on "It works", Remove theme and the boot-time safe mode) leaves a module the
 * other build owns alone. A module without the key predates the owner build and is the Play
 * build's.
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

        /**
         * True when [file] serves both regular and bold weights (one variable font listed once per
         * weight, as AOSP has done since Android 12): one static file cannot be both.
         */
        fun isShared(file: String): Boolean {
            val weights = slots.filter { it.file == file }.map { it.weight }
            return weights.any { it >= BOLD_WEIGHT } && weights.any { it < BOLD_WEIGHT }
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

        if (boldPath != regularPath && (files.any { plan.isShared(it) } || BOLD_FILE in files)) {
            // The regular file below also stands for the bold weights, so the bold font would be
            // dropped. Ship it as its own file instead and point the bold weights at it. The live
            // XML already naming BOLD_FILE (an earlier install, or one removed without a reboot
            // since, whose XMLs are still mounted) needs the module XMLs written all the same.
            if (!installSharedBold(boldPath, log)) ok = false
        } else if (BOLD_FILE !in files) {
            // No separate bold this time, and the live font XML does not name the module bold
            // file: drop a bold file and rewritten XMLs left by an earlier install, or they would
            // bring the previous font's bold back at the next boot.
            if (!run("rm -f '$FONTS_DIR/$BOLD_FILE' '$ETC_DIR/${FONTS_XML.substringAfterLast('/')}' '$ETC_DIR/${FALLBACK_XML.substringAfterLast('/')}'", log)) ok = false
        }

        val bound = ArrayList<String>()
        val mounted = mountedFontFiles()
        for (file in files) {
            val source = if (plan.isBold(file)) boldPath else regularPath
            val moduleFile = "$FONTS_DIR/$file"
            val stagedFile = "$moduleFile$STAGED_SUFFIX"
            val systemFile = "$SYSTEM_FONTS/$file"
            // The module file may be bound over /system/fonts and mmapped by every app: never
            // rewrite it in place (cp truncates the inode; readers past the new end get SIGBUS).
            // Write a new file and rename it over the old name, which leaves the old inode intact
            // for the existing mount and mappings.
            val copied = run(
                "rm -f '$stagedFile' && cp '$source' '$stagedFile' && chmod 644 '$stagedFile' && chown root:root '$stagedFile' && chcon u:object_r:system_file:s0 '$stagedFile' && mv -f '$stagedFile' '$moduleFile'",
                log,
            )
            if (!copied) {
                run("rm -f '$stagedFile'", log)
                ok = false
                continue
            }
            if (systemFile in mounted) {
                // The existing bind still shows the old inode: detach it (lazily when busy, as
                // remove() does) and bind the new file below.
                if (!run("nsenter -t 1 -m -- umount '$systemFile'", log) && !run("nsenter -t 1 -m -- umount -l '$systemFile'", log)) {
                    log(LogLine(LogLevel.WARN, "$systemFile could not be unmounted; it keeps the previous font until a reboot"))
                    bound += systemFile
                    ok = false
                    continue
                }
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
        val (propCode, prop) = ExecOutput.exec(service, "cat '$MODULE_DIR/module.prop'")
        // A module left by the other build is only protected while that build is still installed:
        // once it is uninstalled nothing else would ever remove its font.
        val otherInstalled = service.exec("pm path '${com.hexnext.themer.safety.OtherBuild.packageName}'").code() == 0
        if (otherInstalled && !isOwnedBy(if (propCode == 0) prop else null, APP_ID, OWNS_LEGACY_MODULE)) {
            // The other Hex Next build installed a font since: its module and binds are not ours.
            log(LogLine(LogLevel.INFO, "The font module belongs to the other Hex Next build; left in place"))
            if (recorded.isNotEmpty()) service.writeStateFile(STATE_FILE, EMPTY_STATE)
            return
        }
        // Null when init's mountinfo cannot be read: then every path is unmounted as before.
        val mountInfo = readMountInfo()
        val mountPoints = mountInfo?.let { parseFontMountPoints(it) }
        val paths = LinkedHashSet<String>()
        paths += recorded
        paths += mountInfo?.let { parseMountInfo(it) }.orEmpty()
        if (paths.isEmpty() && service.exec("[ -d '$MODULE_DIR' ]").code() != 0) {
            return
        }
        log(LogLine(LogLevel.INFO, "Removing the Hex Next font (${paths.size} bound files)"))
        val stillBound = ArrayList<String>()
        for (path in paths) {
            if (!SAFE_SYSTEM_PATH.matches(path)) continue
            if (mountPoints != null && path !in mountPoints) {
                // Recorded but no longer a mount point (a reboot dropped the live bind, or the root
                // tool mounts modules as an overlay): umount would fail with EINVAL and keep the
                // record forever, so the path simply leaves the record.
                log(LogLine(LogLevel.INFO, "$path is no longer mounted; dropping it from the record"))
                continue
            }
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

    /**
     * For a `sans-serif` family whose single (variable) file serves every weight: copies the bold
     * font to the module as [BOLD_FILE] and writes module copies of [FONTS_XML] and [FALLBACK_XML]
     * whose bold `sans-serif` entries name that file ([rewriteSharedBold]). New files under
     * `/system` cannot be bound live, so this takes effect after a reboot, when the root tool
     * mounts the module. A live XML whose bold entries already name [BOLD_FILE] (still mounted
     * from an earlier install, even one removed since without a reboot) is copied unchanged.
     * Returns false when a step failed.
     */
    private fun installSharedBold(boldPath: String, log: (LogLine) -> Unit): Boolean {
        val moduleBold = "$FONTS_DIR/$BOLD_FILE"
        val stagedBold = "$moduleBold$STAGED_SUFFIX"
        if (!run(
                "rm -f '$stagedBold' && cp '$boldPath' '$stagedBold' && chmod 644 '$stagedBold' && chown root:root '$stagedBold' && chcon u:object_r:system_file:s0 '$stagedBold' && mv -f '$stagedBold' '$moduleBold'",
                log,
            )
        ) {
            run("rm -f '$stagedBold'", log)
            return false
        }
        if (!run("mkdir -p '$ETC_DIR'", log)) return false
        var ok = true
        var rewritten = 0
        for (path in listOf(FONTS_XML, FALLBACK_XML)) {
            // The text must come inline: an output the service spilled to a file sits in the
            // shell-owned state directory, where a uid 2000 process could change it before it is
            // read back, and it would then be written as root into the module.
            val read = service.exec("cat '$path'")
            if (read.getString(ThemeService.KEY_OUTPUT_PATH) != null) {
                ExecOutput.fullText(service, read)
                log(LogLine(LogLevel.WARN, "$path is too large to rewrite safely; skipped"))
                ok = false
                continue
            }
            val text = read.output()
            if (read.code() != 0 || text.isBlank()) continue
            val xml = rewriteSharedBold(text, BOLD_FILE)
                ?: text.takeIf { live -> parseSansSerif(live).any { it.file == BOLD_FILE } }
                ?: continue
            val name = path.substringAfterLast('/')
            if (writeModuleText("$ETC_DIR/$name", xml, log)) {
                rewritten++
            } else {
                ok = false
            }
        }
        if (rewritten == 0) {
            log(LogLine(LogLevel.WARN, "The bold font could not be set up; bold text keeps the regular font"))
            return false
        }
        log(LogLine(LogLevel.INFO, "The default font is one file for every weight; the bold font was added as $BOLD_FILE and applies after a reboot"))
        return ok
    }

    /**
     * Writes [text] as root to [target], a path inside the root-only module directory. The text
     * travels inside the commands themselves, single-quoted for `printf '%s'`, never through a
     * file in the shell-owned state directory that a uid 2000 process could swap before root
     * copies it. It goes in chunks well under the kernel's per-argument limit to a staged file
     * (created under umask 022), which is then renamed over [target]: a module file that may be
     * mounted is never rewritten in place. Returns false when a step failed.
     */
    private fun writeModuleText(target: String, text: String, log: (LogLine) -> Unit): Boolean {
        val staged = "$target$STAGED_SUFFIX"
        if (text.isEmpty() || text.indexOf('\u0000') >= 0) {
            log(LogLine(LogLevel.WARN, "Refusing to write $target: the text is empty or holds a NUL byte"))
            return false
        }
        var at = 0
        while (at < text.length) {
            var end = minOf(text.length, at + TEXT_CHUNK)
            // Never split a surrogate pair: half of one would not encode.
            if (end < text.length && Character.isHighSurrogate(text[end - 1])) end--
            val chunk = "'" + text.substring(at, end).replace("'", "'\\''") + "'"
            val command = if (at == 0) {
                "umask 022 && rm -f '$staged' && printf '%s' $chunk > '$staged'"
            } else {
                "umask 022 && printf '%s' $chunk >> '$staged'"
            }
            val r = service.exec(command)
            if (r.code() != 0) {
                log(LogLine(LogLevel.WARN, "Writing $staged failed -> exit ${r.code()}: ${r.output().trim().ifBlank { "no output" }}"))
                run("rm -f '$staged'", log)
                return false
            }
            at = end
        }
        log(LogLine(LogLevel.INFO, "Wrote ${text.length} characters to $staged"))
        val done = run(
            "chmod 644 '$staged' && chown root:root '$staged' && chcon u:object_r:system_file:s0 '$staged' && mv -f '$staged' '$target'",
            log,
        )
        if (!done) run("rm -f '$staged'", log)
        return done
    }

    /** True when a previous [install] left bound files recorded. */
    fun hasState(): Boolean = readState().isNotEmpty()

    // ---- helpers --------------------------------------------------------------------------

    /**
     * The uid commands run as. The in-process `su -c` service (the boot receiver's) reports the
     * app's own uid over the binder while every command runs as root, so a non-root binder uid
     * is checked against `id -u`.
     */
    private fun uid(): Int {
        val binderUid = runCatching { service.uid }.getOrDefault(-1)
        if (binderUid == 0) return 0
        val execUid = runCatching { service.exec("id -u").output().trim().toIntOrNull() }.getOrNull()
        return execUid ?: binderUid
    }

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
    private fun mountedFontFiles(): Set<String> = readMountInfo()?.let { parseMountInfo(it) }.orEmpty()

    /** Init's `/proc/1/mountinfo`, or null when it could not be read. */
    private fun readMountInfo(): String? {
        val (code, text) = ExecOutput.exec(service, "cat /proc/1/mountinfo")
        if (code != 0 || text.isBlank()) return null
        return text
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
        /** `module.prop` key that names the application id that installed the module. */
        const val OWNER_KEY = "hexnextOwner"
        private const val APP_ID = BuildConfig.APPLICATION_ID

        /** The Play build owns a module from before [OWNER_KEY] existed (the owner build is newer). */
        private val OWNS_LEGACY_MODULE = BuildConfig.ROOT_STATE_SUFFIX.isEmpty()

        const val MODULE_ID = "hexnext_font"
        const val MODULE_DIR = "/data/adb/modules/$MODULE_ID"
        const val FONTS_DIR = "$MODULE_DIR/system/fonts"
        const val SYSTEM_FONTS = "/system/fonts"
        const val STATE_FILE = "font.json"
        const val FONTS_XML = "/system/etc/fonts.xml"
        const val FALLBACK_XML = "/system/etc/font_fallback.xml"
        const val ETC_DIR = "$MODULE_DIR/system/etc"
        const val BOLD_WEIGHT = 600

        /** Module font the bold weights point at when one file serves every weight. */
        const val BOLD_FILE = "HexNextSans-Bold.ttf"
        private const val SETTINGS = "com.android.settings"
        private const val EMPTY_STATE = "{\"bound\":[]}"

        /** Suffix of the file a font is written to before it is renamed over the module file. */
        private const val STAGED_SUFFIX = ".hexnext-new"

        /**
         * Characters of text per root command in [writeModuleText]. Quoting can at most
         * quadruple a chunk, which keeps every argument far below the kernel's 128 KiB limit.
         */
        private const val TEXT_CHUNK = 16_384

        /** `module.prop` lines, each single-quoted for `printf '%s\n'`. */
        private val MODULE_PROP_LINES = listOf(
            "id=$MODULE_ID",
            "$OWNER_KEY=$APP_ID",
            "name=Hex Next font",
            "version=1",
            "versionCode=1",
            "author=Hex Next",
            "description=Custom system font applied by Hex Next",
        ).joinToString(" ") { "'$it'" }

        /**
         * POSIX sh condition, true when [MODULE_DIR]'s `module.prop` names this build as its
         * owner, as [isOwnedBy] decides. [APP_ID] holds only `[a-z0-9.]`, and `-F` matches it literally.
         */
        val OWNED_HERE_TEST: String = run {
            val prop = "'$MODULE_DIR/module.prop'"
            val mine = "grep -qxF '$OWNER_KEY=$APP_ID' $prop"
            if (OWNS_LEGACY_MODULE) "{ $mine || ! grep -q '^$OWNER_KEY=' $prop; }" else mine
        }

        /**
         * True when a font module whose `module.prop` text is [moduleProp] (null: no module) may be
         * replaced or removed by the build [appId]: it names [appId] under [OWNER_KEY], or it names
         * no owner and [ownsLegacy]. Pure.
         */
        fun isOwnedBy(moduleProp: String?, appId: String, ownsLegacy: Boolean): Boolean {
            if (moduleProp == null) return true
            val owner = moduleProp.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.startsWith("$OWNER_KEY=") }
                ?.substringAfter('=')
                ?: return ownsLegacy
            return owner == appId
        }

        /** One path segment; `.` and `..` are rejected so no piece can escape its directory. */
        private val SAFE_PIECE = Regex("^(?!\\.\\.?$)[A-Za-z0-9_.-]+$")
        private val SAFE_SYSTEM_PATH = Regex("^/system/fonts/(?!\\.\\.?$)[A-Za-z0-9_.-]+$")
        private val FAMILY_OPEN = Regex("<family\\b([^>]*)>", RegexOption.IGNORE_CASE)
        private val FONT = Regex("<font\\b([^>]*)>(.*?)</font>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val ATTR = Regex("([A-Za-z_:-]+)\\s*=\\s*\"([^\"]*)\"")
        private val TAGS = Regex("<[^>]*>")
        private val POST_SCRIPT_NAME = Regex("\\s+postScriptName\\s*=\\s*\"[^\"]*\"")

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

        /**
         * [xml] with every `<font>` of the first `sans-serif` family that weighs [BOLD_WEIGHT] or
         * more and names a file shared with lighter weights ([FontPlan.isShared]) changed to name
         * [boldFile]: its variable-font `<axis>` children and `postScriptName` are dropped, its other
         * attributes kept. Null when no entry needs it.
         */
        internal fun rewriteSharedBold(xml: String, boldFile: String): String? {
            val (start, end) = sansSerifBlock(xml) ?: return null
            val block = xml.substring(start, end)
            val plan = FontPlan("", parseSansSerif(xml))
            val shared = plan.files.filter { plan.isShared(it) }.toSet()
            if (shared.isEmpty()) return null
            val newBlock = FONT.replace(block) { m ->
                val attrs = attributes(m.groupValues[1])
                val file = m.groupValues[2].replace(TAGS, "").trim()
                val weight = attrs["weight"]?.toIntOrNull() ?: 400
                if (attrs.containsKey("fallbackFor") || file !in shared || weight < BOLD_WEIGHT) {
                    m.value
                } else {
                    "<font" + m.groupValues[1].replace(POST_SCRIPT_NAME, "") + ">" + boldFile + "</font>"
                }
            }
            return xml.substring(0, start) + newBlock + xml.substring(end)
        }

        /** Start (inclusive) and end (exclusive) of the first `sans-serif` family's body. */
        private fun sansSerifBlock(xml: String): Pair<Int, Int>? {
            for (open in FAMILY_OPEN.findAll(xml)) {
                if (attributes(open.groupValues[1])["name"] == "sans-serif") {
                    val start = open.range.last + 1
                    val end = xml.indexOf("</family>", start, ignoreCase = true).let { if (it < 0) xml.length else it }
                    return start to end
                }
            }
            return null
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

        /** Every mount point `/system/fonts/<file>` in a mountinfo text, whatever its source. */
        internal fun parseFontMountPoints(text: String): Set<String> {
            val result = LinkedHashSet<String>()
            for (line in text.lineSequence()) {
                val fields = line.trim().split(Regex("\\s+"))
                if (fields.size < 5) continue
                val mountPoint = fields[4]
                if (mountPoint.startsWith("$SYSTEM_FONTS/")) result += mountPoint
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
