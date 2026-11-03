package com.hexnext.themer.shizuku

import android.annotation.SuppressLint
import android.content.Context
import android.content.om.FabricatedOverlay
import android.content.om.OverlayManagerTransaction
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.TypedValue
import androidx.annotation.Keep
import com.hexnext.engine.compile.ValuePlanner
import com.hexnext.engine.compile.ValueTypes
import com.hexnext.engine.device.OverlayList
import com.hexnext.engine.device.RaiseNeed
import com.hexnext.engine.device.SemWtPlanner
import com.hexnext.engine.frro.FrroFormat
import com.hexnext.engine.frro.FrroReader
import com.hexnext.engine.frro.FrroWriter
import com.hexnext.themer.IThemeService
import org.json.JSONArray
import org.json.JSONObject
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.lang.reflect.InvocationTargetException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Privileged One UI 9.0 System RRO user service.
 *
 * Operates under UID 0 (root / SU) or UID 1000 (`android.uid.system`) to fabricate and commit
 * from-scratch overlays (`com.hexnext.oneui9.rro.*` and `HexNextSettingsIcons26`) at highest
 * priority above Samsung's modern `SemWT_*` overlays without using High Contrast Theme overlays
 * or Samsung Theme Park (`com.samsung.android.themepark`).
 */
@Keep
@SuppressLint("PrivateApi", "DiscouragedPrivateApi", "SoonBlockedPrivateApi", "BlockedPrivateApi", "SetWorldReadable")
class ThemeService() : IThemeService.Stub() {

    @Keep
    @Suppress("UNUSED_PARAMETER")
    constructor(context: Context) : this()

    init {
        runCatching { HiddenApiBypass.addHiddenApiExemptions("") }
    }

    override fun destroy() {
        exitProcess(0)
    }

    override fun getUid(): Int = Process.myUid()

    private fun isPrivilegedUid(): Boolean {
        val uid = Process.myUid()
        return uid == 0 || uid == 1000
    }

    /**
     * True when the service itself may run shell commands and touch the state directory: root,
     * system, or the ADB shell (uid 2000, Shizuku without root: colours-only tier). Any other uid
     * is the app process in SU mode, which reaches root only through `su -c`.
     */
    private fun runsDirectly(): Boolean {
        val uid = Process.myUid()
        return uid == 0 || uid == 1000 || uid == SHELL_UID
    }

    private fun activeOwnerPackage(): String {
        return if (Process.myUid() == 1000) SYSTEM_OWNER else SHELL_OWNER
    }

    /**
     * Binder entry point for [shell]. Output longer than [EXEC_INLINE_LIMIT] characters (a full
     * `cmd overlay dump` can exceed the binder buffer, "Transaction failed on small parcel") is
     * written to a world-readable file under the state directory instead: the Bundle then carries
     * its path in [KEY_OUTPUT_PATH], the length in [KEY_OUTPUT_LENGTH] and only the head of the
     * text in [KEY_OUTPUT]. Read it back with `openFile` (see `ExecOutput.fullText`).
     */
    override fun exec(command: String): Bundle = spillLargeOutput(shell(command))

    private fun spillLargeOutput(bundle: Bundle): Bundle {
        val output = bundle.getString(KEY_OUTPUT).orEmpty()
        if (output.length <= EXEC_INLINE_LIMIT) return bundle
        return try {
            val file = File(MonitorScript.DIR, "exec/out-${System.currentTimeMillis()}-${Process.myPid()}-${spillCounter++}.txt")
            writeRootFile(file, output)
            bundle.apply {
                putString(KEY_OUTPUT_PATH, file.path)
                putInt(KEY_OUTPUT_LENGTH, output.length)
                putString(KEY_OUTPUT, output.take(EXEC_HEAD_CHARS) + "\n… (${output.length} characters; full output in ${file.path})")
            }
        } catch (t: Throwable) {
            bundle.apply {
                putInt(KEY_OUTPUT_LENGTH, output.length)
                putString(KEY_OUTPUT, output.take(EXEC_INLINE_LIMIT) + "\n… (truncated from ${output.length} characters: ${describe(t)})")
            }
        }
    }

    @Volatile private var spillCounter = 0

    /** Runs [command] as the service uid (root, system or shell), or through `su` from the app process, and returns the whole output. */
    private fun shell(command: String): Bundle = try {
        val cmdArray = if (runsDirectly()) {
            arrayOf("sh", "-c", command)
        } else {
            arrayOf("su", "-c", command)
        }
        val process = ProcessBuilder(*cmdArray)
            .redirectErrorStream(true)
            .start()
        // Nothing is fed on stdin; a command that reads it gets EOF instead of waiting forever.
        runCatching { process.outputStream.close() }
        // Read on a separate thread: reading to EOF here would block for as long as the child
        // keeps its output open, so the timeout below could never fire for a hung command.
        val sink = StringBuilder()
        val reader = Thread({
            try {
                process.inputStream.bufferedReader().use { input ->
                    val buffer = CharArray(8192)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        synchronized(sink) { sink.append(buffer, 0, n) }
                    }
                }
            } catch (ignored: Throwable) {
                // The stream closed under us (process destroyed); keep what was read.
            }
        }, "hexnext-shell")
        reader.isDaemon = true
        reader.start()
        val finished = process.waitFor(SHELL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            runCatching { process.waitFor(5, TimeUnit.SECONDS) }
        }
        // A background grandchild that inherited the pipe can hold it open after the child
        // exits; do not wait on it indefinitely.
        reader.join(if (finished) READER_GRACE_MILLIS else 2_000L)
        val output = synchronized(sink) { sink.toString() }
        if (!finished) {
            result(124, output + "\n(timed out after ${SHELL_TIMEOUT_SECONDS}s)")
        } else {
            result(process.exitValue(), output)
        }
    } catch (t: Throwable) {
        result(-1, describe(t))
    }

    override fun applyColorOverlay(
        name: String,
        targetPackage: String,
        resourceNames: Array<String>,
        colors: IntArray,
    ): Bundle {
        if (resourceNames.size != colors.size) {
            return result(-1, "resourceNames and colors differ in length")
        }
        return try {
            val owner = activeOwnerPackage()
            val id = "$owner:$name"

            if (isPrivilegedUid()) {
                val overlay = newOverlay(name, targetPackage, owner)
                resourceNames.forEachIndexed { i, res ->
                    overlay.setResourceValue(res, TypedValue.TYPE_INT_COLOR_ARGB8, colors[i], null)
                }
                val register = OverlayManagerTransaction.newInstance()
                register.registerFabricatedOverlay(overlay)
                commit(register)
            } else {
                // `cmd overlay fabricate` accepts exactly one resource per invocation and every
                // invocation replaces the whole overlay, so a multi-colour overlay cannot be built
                // through the shell (the old multi-argument form failed with "For input string ...
                // under radix 16" on One UI 9). Committing through OverlayManager needs uid 0/1000.
                return result(
                    3,
                    "Cannot build $id: this service runs as uid ${Process.myUid()}, but committing a fabricated " +
                        "overlay needs uid 0 or 1000. Start Shizuku with root (or restart it after a reboot) and retry.",
                )
            }

            // Enable; on One UI 9 every enable moves the overlay to the top of its target.
            val top = enableOnTop(id, name, targetPackage, owner)
            val cleanup = StringBuilder()
            if (top.code == 0) pruneStaleAfterEnable(id, owner, name, null, cleanup)
            result(
                top.code,
                "Registered $id (${resourceNames.size} One UI 9.0 tokens) on $targetPackage [UID ${Process.myUid()}]" +
                    (if (top.code == 0) ", enabled.\n${top.log}" else ".\nEnable failed: ${top.log}") +
                    cleanup.toString().trimEnd().let { if (it.isEmpty()) "" else "\n$it" }
            )
        } catch (t: Throwable) {
            result(-1, describe(t))
        }
    }

    override fun removeOverlay(name: String): Bundle {
        return try {
            val owner = activeOwnerPackage()
            val id = "$owner:$name"
            shell("cmd overlay disable --user current $id")
            if (isPrivilegedUid()) {
                val unregister = OverlayManagerTransaction.newInstance()
                unregister.unregisterFabricatedOverlay(newOverlay(name, "android", owner).identifier)
                commit(unregister)
            }
            // Only when unregistered is the overlay gone from OverlayManager and none of its files
            // (this or earlier applies) live any more; a merely disabled overlay keeps its .frro.
            val cleanup = StringBuilder()
            if (isPrivilegedUid()) {
                pruneOverlayFrros(owner, name, null, cleanup)
            } else {
                cleanup.appendLine("Cleanup: $id is still registered (uid ${Process.myUid()} cannot unregister); its files were left in place.")
            }
            result(0, "Removed $id\n$cleanup")
        } catch (t: Throwable) {
            result(-1, describe(t))
        }
    }

    override fun repairAndRecompileBatch(
        overlayNames: Array<String>,
        targetPackages: Array<String>,
    ): Bundle {
        if (overlayNames.size != targetPackages.size) {
            return result(-1, "overlayNames and targetPackages differ in length")
        }
        return try {
            val owner = activeOwnerPackage()
            val logs = StringBuilder()
            val total = overlayNames.size

            overlayNames.indices.forEach { index ->
                val name = overlayNames[index]
                val target = targetPackages[index]
                val id = "$owner:$name"
                val seqLabel = "[SEQ ${index + 1}/$total]"
                if (!OVERLAY_NAME.matches(name) || !PACKAGE_NAME.matches(target)) {
                    logs.appendLine("$seqLabel Skipped $id -> $target: invalid overlay name or target package.")
                    return@forEach
                }

                // Step 1: Disable stale overlay & purge its idmap2/frro cache files under UID 0 / Shizuku.
                // Only `<owner>-<name>-<suffix>.frro` files are removed (a `*name*` glob would also
                // hit overlays whose name merely contains this one, e.g. HexNext_android_auto).
                shell("cmd overlay disable --user current $id 2>/dev/null")
                val cleanup = StringBuilder()
                pruneOverlayFrros(owner, name, null, cleanup)
                logs.append(cleanup)

                // Step 2: Re-register fabricated overlay or re-fabricate via shell
                if (isPrivilegedUid()) {
                    val tx = OverlayManagerTransaction.newInstance()
                    tx.registerFabricatedOverlay(newOverlay(name, target, owner))
                    commit(tx)
                } else {
                    shell("cmd overlay fabricate --target $target --name $name")
                }

                // Step 3: Re-enable; the enable lands it above Samsung SemWT_* on One UI 9
                val top = enableOnTop(id, name, target, owner)
                logs.appendLine(
                    "$seqLabel [UID ${Process.myUid()}] Cleared idmap2 cache & rebuilt $id -> $target " +
                        if (top.code == 0) "(STATE_ENABLED)\n${top.log}"
                        else "(FAILED: ${top.log})"
                )
            }

            result(0, logs.toString().trimEnd())
        } catch (t: Throwable) {
            result(-1, describe(t))
        }
    }

    override fun triggerSafeModeFallback(crashReason: String): Bundle {
        return try {
            val logs = StringBuilder()
            logs.appendLine("[!] [SAFE_MODE_FALLBACK] System crash detected: $crashReason")
            logs.appendLine("[*] [UID ${Process.myUid()}] Executing emergency rollback: cmd overlay disable-all --user current")

            // 1. Disable all overlays for the current user and purge every HexNext idmap2/frro file
            //    (possibly corrupted): exactly the `<owner>-HexNext…-<suffix>.frro` files, nothing else.
            val disableRes = shell("cmd overlay disable-all --user current 2>/dev/null")
            val cleanup = StringBuilder()
            val purged = pruneHexFrros(onlyUnregistered = false, cleanup)
            logs.appendLine("[✓] Disabled active RROs (code=${disableRes.getInt(KEY_CODE)}) & purged $purged HexNext .frro files from $RESOURCE_CACHE")
            logs.append(cleanup)
            logs.appendLine("[✓] Restored stock One UI 9.0 SemWT_* & Framework resource state (Safe Mode active).")

            result(0, logs.toString().trimEnd())
        } catch (t: Throwable) {
            result(-1, describe(t))
        }
    }

    // ---------------------------------------------------------------------------------------
    // Discovery
    // ---------------------------------------------------------------------------------------

    override fun listOverlays(): Bundle {
        return try {
            val list = shell("cmd overlay list --user current")
            val overlays = parseOverlayList(list.getString(KEY_OUTPUT).orEmpty())
            val json = JSONArray()
            overlays.forEach { o ->
                json.put(
                    JSONObject()
                        .put("overlayId", o.overlayId)
                        .put("target", o.target)
                        .put("enabled", o.enabled)
                        .put("isSamsungWallpaperTheme", o.isSamsungWallpaperTheme)
                )
            }
            val find = shell("find $FRRO_SEARCH_DIRS -name '*.frro' 2>/dev/null")
            val frroPaths = find.getString(KEY_OUTPUT).orEmpty().lineSequence()
                .map { it.trim() }
                .filter { it.startsWith("/") && it.endsWith(".frro") }
                .distinct()
                .toList()
            val code = list.getInt(KEY_CODE)
            val summary = if (code == 0) {
                "${overlays.size} overlays (${overlays.count { it.isSamsungWallpaperTheme }} SemWT_*), " +
                    "${frroPaths.size} .frro files under $FRRO_SEARCH_DIRS"
            } else {
                "cmd overlay list failed: ${list.getString(KEY_OUTPUT)}"
            }
            result(code, summary).apply {
                putString(KEY_JSON, json.toString())
                putStringArray(KEY_FRRO_PATHS, frroPaths.toTypedArray())
            }
        } catch (t: Throwable) {
            result(-1, describe(t))
        }
    }

    override fun openFile(path: String?): ParcelFileDescriptor? {
        if (path == null || !isSafePath(path)) return null
        return try {
            if (runsDirectly()) {
                ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
            } else {
                openThroughSu(path)
            }
        } catch (t: Throwable) {
            null
        }
    }

    /** Streams `su -c cat '<path>'` into the write end of a pipe and returns the read end. */
    private fun openThroughSu(path: String): ParcelFileDescriptor? {
        if (shell("[ -f '$path' ] && [ -r '$path' ]").getInt(KEY_CODE) != 0) return null
        val pipe = ParcelFileDescriptor.createPipe()
        val process = try {
            ProcessBuilder("su", "-c", "cat '$path' 2>/dev/null").start()
        } catch (t: Throwable) {
            runCatching { pipe[0].close() }
            runCatching { pipe[1].close() }
            return null
        }
        runCatching { process.outputStream.close() }
        val copier = Thread({
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { out ->
                    process.inputStream.use { input -> input.copyTo(out) }
                }
            } catch (ignored: Throwable) {
                // Reader went away or cat failed; the pipe end is closed by use{}.
            } finally {
                runCatching { if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroy() }
            }
        }, "hexnext-openFile")
        copier.isDaemon = true
        copier.start()
        return pipe[0]
    }

    override fun packageInfo(packages: Array<String?>?): Bundle {
        return try {
            val installed = listPackages("pm list packages")
            val everything = listPackages("pm list packages -u")
            val json = JSONArray()
            packages.orEmpty().filterNotNull().forEach { pkg ->
                val entry = JSONObject().put("pkg", pkg)
                if (!PACKAGE_NAME.matches(pkg)) {
                    json.put(
                        entry.put("apkPath", JSONObject.NULL).put("installed", false)
                            .put("debloated", false).put("error", "invalid package name")
                    )
                    return@forEach
                }
                val apk = shell("pm path $pkg").getString(KEY_OUTPUT).orEmpty().lineSequence()
                    .map { it.trim() }
                    .firstOrNull { it.startsWith("package:") }
                    ?.removePrefix("package:")
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                val isInstalled = pkg in installed
                // Debloated: not installed for the current user, but the system still knows it.
                val reason = when {
                    isInstalled -> null
                    pkg in everything -> "pm list packages -u"
                    else -> systemCodePath(pkg)?.let { "APK on system image: $it" }
                }
                json.put(
                    entry.put("apkPath", apk ?: JSONObject.NULL)
                        .put("installed", isInstalled)
                        .put("debloated", reason != null)
                        .put("debloatReason", reason ?: JSONObject.NULL)
                )
            }
            result(0, "Checked ${json.length()} packages (${installed.size} installed for the current user)").apply {
                putString(KEY_JSON, json.toString())
                putInt(KEY_UID, Process.myUid())
                DEVICE_PROPS.forEach { prop ->
                    putString(prop, shell("getprop $prop").getString(KEY_OUTPUT).orEmpty().trim())
                }
            }
        } catch (t: Throwable) {
            result(-1, describe(t))
        }
    }

    /**
     * The `/system`, `/product`, `/system_ext` or `/vendor` code path `dumpsys package` still
     * reports for [pkg] (a package removed for every user keeps its preinstalled APK), or null.
     */
    private fun systemCodePath(pkg: String): String? =
        shell("dumpsys package $pkg | grep -m 3 'codePath='").getString(KEY_OUTPUT).orEmpty()
            .lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("codePath=") }
            .map { it.removePrefix("codePath=").trim() }
            .firstOrNull { path -> SYSTEM_PARTITIONS.any { path.startsWith(it) } }

    private fun listPackages(command: String): Set<String> =
        shell(command).getString(KEY_OUTPUT).orEmpty().lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:").trim() }
            .toSet()

    // ---------------------------------------------------------------------------------------
    // Colours + PNG drawables in one fabricated overlay
    // ---------------------------------------------------------------------------------------

    private data class DrawableInput(
        val name: String,
        val config: String?,
        val fd: ParcelFileDescriptor,
        val ninePatch: Boolean,
    )

    private data class ColorInput(val name: String, val argb: Int, val config: String?)

    /** Colours that went into the committed FabricatedOverlay, and those it rejected. */
    private class Outcome(val committed: List<ColorInput>, val skipped: JSONArray)

    /** A PNG read from the caller's descriptor, ready to embed in the rewritten `.frro`. */
    private class PngInput(val input: DrawableInput, val resource: String, val bytes: ByteArray)

    /** Owner, group, permission bits and SELinux label of a file. */
    private data class FileMeta(val uid: Int, val gid: Int, val mode: Int, val label: String?) {
        override fun toString(): String =
            "uid=$uid gid=$gid mode=0${Integer.toOctalString(mode)} label=${label ?: "(unknown)"}"
    }

    /** What [rewriteFrro] replaced: enough to verify the new file or put the old one back. */
    private class Rewrite(
        val path: String,
        val original: ByteArray,
        val meta: FileMeta,
        val crc: Int,
        val pngs: List<PngInput>,
    )

    /** Result of the drawable step; [frroPath] is the registered file when the rewrite located it, else null. */
    private class DrawableOutcome(val enable: EnableResult, val applied: Int, val frroPath: String? = null)

    override fun applyOverlay(
        name: String?,
        target: String?,
        colorNames: Array<String?>?,
        colors: IntArray?,
        drawableNames: Array<String?>?,
        drawableConfigs: Array<String?>?,
        drawableFds: Array<ParcelFileDescriptor?>?,
        ninePatch: BooleanArray?,
    ): Bundle = applyOverlayInternal(name, target, colorNames, colors, null, drawableNames, drawableConfigs, drawableFds, ninePatch)

    override fun applyOverlayConfigured(
        name: String?,
        target: String?,
        colorNames: Array<String?>?,
        colors: IntArray?,
        colorConfigs: Array<String?>?,
        drawableNames: Array<String?>?,
        drawableConfigs: Array<String?>?,
        drawableFds: Array<ParcelFileDescriptor?>?,
        ninePatch: BooleanArray?,
    ): Bundle = applyOverlayInternal(name, target, colorNames, colors, colorConfigs, drawableNames, drawableConfigs, drawableFds, ninePatch)

    /**
     * Registers (or replaces) `HexNextValues_<target>`: one fabricated overlay of raw typed values
     * (dimensions, integers, booleans, floats; [ValueTypes.ALLOWED]), enabled and raised. A
     * separate overlay from the colours so a System UI crash rollback can drop the sizes alone.
     * Root only (code 3 otherwise, nothing touched). No values unregisters the overlay. System UI
     * is not restarted: OverlayManager's configuration change makes it reload.
     */
    override fun applyValueOverlay(
        name: String?,
        target: String?,
        names: Array<String?>?,
        dataTypes: IntArray?,
        data: IntArray?,
        configs: Array<String?>?,
    ): Bundle {
        val started = SystemClock.elapsedRealtime()
        val skipped = JSONArray()
        fun finish(bundle: Bundle, count: Int, lookup: String): Bundle = bundle.apply {
            putInt(KEY_RESOURCE_COUNT, count)
            putString(KEY_SKIPPED, skipped.toString())
            putLong(KEY_MILLIS, SystemClock.elapsedRealtime() - started)
            putString(KEY_LOOKUP, lookup)
        }
        return try {
            if (name == null || target == null || !OVERLAY_NAME.matches(name) || !PACKAGE_NAME.matches(target) ||
                !name.startsWith(VALUES_PREFIX)
            ) {
                return finish(result(-1, "Invalid value overlay name or target package"), 0, "")
            }
            val n = names.orEmpty()
            val types = dataTypes ?: IntArray(0)
            val values = data ?: IntArray(0)
            if (n.size != types.size || n.size != values.size || (configs != null && configs.size != n.size)) {
                return finish(result(-1, "names, dataTypes, data and configs differ in length"), 0, "")
            }
            if (!isPrivilegedUid()) {
                return finish(
                    result(3, "Sizes need the privileged service (Shizuku running as root, uid 0/1000); this service runs as uid ${Process.myUid()}."),
                    0, "",
                )
            }
            if (n.isEmpty()) return finish(removeOverlay(name), 0, "")

            val owner = activeOwnerPackage()
            val id = "$owner:$name"
            val log = StringBuilder()
            log.appendLine("[UID ${Process.myUid()}] $id -> $target: ${n.size} values")
            // FabricatedOverlay.setResourceValue only takes integer types (0x10..0x1f) and throws
            // "dataType is out of range of [16, 31]" for dimensions and floats (seen on One UI 9).
            // Those are registered as plain integers first and given their real type by a root
            // rewrite of the .frro before the overlay is ever enabled, so System UI never reads a
            // size typed as an integer. An earlier registration is dropped just before registering
            // for the same reason: replacing an enabled overlay would make the placeholders live.
            val overlay = newOverlay(name, target, owner)
            var committed = 0
            var first: String? = null
            val retype = ArrayList<RetypedValue>()
            n.forEachIndexed { i, res ->
                val config = configs?.getOrNull(i)?.takeIf { it.isNotBlank() }
                when {
                    res == null -> Unit
                    !RESOURCE_NAME.matches(res) -> skip(skipped, res, "invalid resource name")
                    config != null && !CONFIG_NAME.matches(config) -> skip(skipped, res, "invalid configuration $config")
                    types[i] !in ValueTypes.ALLOWED -> skip(skipped, res, "data type 0x%02x is not a size, number or switch".format(types[i]))
                    else -> try {
                        val qualifiedName = qualified(res, target)
                        val direct = types[i] in FIRST_INT_TYPE..LAST_INT_TYPE
                        overlay.setResourceValue(qualifiedName, if (direct) types[i] else TypedValue.TYPE_INT_DEC, values[i], config)
                        if (!direct) retype.add(RetypedValue(qualifiedName, types[i], values[i], config.orEmpty()))
                        committed++
                        if (config == null && first == null) first = qualifiedName
                    } catch (t: Throwable) {
                        skip(skipped, res, describe(t))
                    }
                }
            }
            if (committed == 0) return finish(result(4, log.append("No value could be set; the previous sizes were left as they were.").toString()), 0, "")
            if (retype.isNotEmpty()) {
                val dropped = removeOverlay(name)
                log.appendLine("Dropped the previous registration first (code ${dropped.getInt(KEY_CODE)}).")
                if (overlayEnabled(id) != false) {
                    return finish(
                        result(4, log.append("$id could not be confirmed disabled, so no size was registered: ${dropped.getString(KEY_OUTPUT)}").toString()),
                        0, "",
                    )
                }
            }
            val register = OverlayManagerTransaction.newInstance()
            register.registerFabricatedOverlay(overlay)
            commit(register)
            log.appendLine("Committed $committed values through OverlayManager.")
            if (retype.isNotEmpty()) {
                try {
                    retypeByRewrite(id, name, target, owner, retype, log)
                } catch (t: Throwable) {
                    // Never enable placeholders: an integer where System UI expects a size crashes it.
                    val dropped = removeOverlay(name)
                    log.appendLine("Unregistered the placeholders (code ${dropped.getInt(KEY_CODE)}; enabled now: ${overlayEnabled(id)}).")
                    retype.forEach { skip(skipped, it.resource, "size rewrite failed: ${describe(t)}") }
                    return finish(result(4, log.append("Size rewrite failed (${describe(t)}); nothing was enabled.").toString()), 0, "")
                }
            }
            val top = enableOnTop(id, name, target, owner)
            if (top.code == 0) {
                log.appendLine(top.log)
                pruneStaleAfterEnable(id, owner, name, null, log)
            } else {
                log.appendLine("Enable failed: ${top.log}")
            }
            finish(result(top.code, log.toString()), if (top.code == 0) committed else 0, lookup(target, first))
        } catch (t: Throwable) {
            finish(result(-1, describe(t)), 0, "")
        }
    }

    /** Whether [id] is enabled now: null when `cmd overlay list` cannot tell; false when absent or disabled. */
    private fun overlayEnabled(id: String): Boolean? {
        val list = shell("cmd overlay list --user current")
        if (list.getInt(KEY_CODE) != 0) return null
        val lines = parseOverlayList(list.getString(KEY_OUTPUT).orEmpty())
        if (lines.isEmpty()) return null
        return lines.any { it.overlayId == id && it.enabled }
    }

    /** A value registered as an integer placeholder that the root rewrite gives its real [dataType]. */
    private data class RetypedValue(val resource: String, val dataType: Int, val data: Int, val config: String)

    /**
     * Rewrites the just-registered (still disabled) value overlay's `.frro` as root so each
     * [values] entry carries its real data type, then checks the file reads back with those
     * types. Throws on any failure; the caller then unregisters the overlay.
     */
    private fun retypeByRewrite(id: String, name: String, target: String, owner: String, values: List<RetypedValue>, log: StringBuilder) {
        val path = findFrroPath(id, owner, name, log) ?: error("no registered .frro found for $id under $RESOURCE_CACHE")
        val meta = try {
            fileMeta(path).let { if (it.label == null) it.copy(label = RESOURCE_CACHE_LABEL) else it }
        } catch (t: Throwable) {
            log.appendLine("stat $path failed (${describe(t)}); using the One UI 9 defaults.")
            DEFAULT_FRRO_META
        }
        val original = File(path).readBytes()
        val current = FrroReader.read(original)
        check(current.packageName == owner && current.name == name && current.targetPackage == target) {
            "$path belongs to ${current.packageName}:${current.name} -> ${current.targetPackage}, not $id -> $target"
        }
        val writer = FrroWriter.from(current, original)
        values.forEach { writer.setResourceValue(it.resource, it.dataType, it.data, it.config) }
        val bytes = writer.toByteArray(path)
        val built = FrroReader.read(bytes)
        val wrong = values.filter { v ->
            built.entries.none { e ->
                e.resourceName == v.resource && e.configuration.orEmpty() == v.config && e.dataType == v.dataType && e.data == v.data
            }
        }
        check(built.crcMatchesProto && wrong.isEmpty() && built.entries.size == current.entries.size) {
            "rewrite read-back failed (${wrong.size} values with the wrong type, ${built.entries.size}/${current.entries.size} entries)"
        }
        replaceFile(path, bytes, meta, log)
        log.appendLine("Gave ${values.size} sizes their real type in $path (crc 0x%08x).".format(built.crc))
    }

    private fun applyOverlayInternal(
        name: String?,
        target: String?,
        colorNames: Array<String?>?,
        colors: IntArray?,
        colorConfigs: Array<String?>?,
        drawableNames: Array<String?>?,
        drawableConfigs: Array<String?>?,
        drawableFds: Array<ParcelFileDescriptor?>?,
        ninePatch: BooleanArray?,
    ): Bundle {
        val started = SystemClock.elapsedRealtime()
        val cNames = colorNames.orEmpty()
        val cValues = colors ?: IntArray(0)
        val dNames = drawableNames.orEmpty()
        val fds = drawableFds.orEmpty()
        val skipped = JSONArray()

        fun finish(bundle: Bundle, count: Int, lookup: String): Bundle = bundle.apply {
            putInt(KEY_RESOURCE_COUNT, count)
            putString(KEY_SKIPPED, skipped.toString())
            putLong(KEY_MILLIS, SystemClock.elapsedRealtime() - started)
            putString(KEY_LOOKUP, lookup)
        }

        try {
            if (name == null || target == null || !OVERLAY_NAME.matches(name) || !PACKAGE_NAME.matches(target)) {
                return finish(result(-1, "Invalid overlay name or target package"), 0, "")
            }
            if (cNames.size != cValues.size) {
                return finish(result(-1, "colorNames and colors differ in length"), 0, "")
            }
            if (colorConfigs != null && colorConfigs.size != cNames.size) {
                return finish(result(-1, "colorNames and colorConfigs differ in length"), 0, "")
            }
            if (dNames.size != fds.size) {
                return finish(result(-1, "drawableNames and drawableFds differ in length"), 0, "")
            }

            val colorList = ArrayList<ColorInput>()
            cNames.forEachIndexed { i, res ->
                val config = colorConfigs?.getOrNull(i)?.takeIf { it.isNotBlank() }
                when {
                    res == null -> Unit
                    !RESOURCE_NAME.matches(res) -> skip(skipped, res, "invalid resource name")
                    config != null && !CONFIG_NAME.matches(config) -> skip(skipped, res, "invalid configuration $config")
                    else -> colorList.add(ColorInput(res, cValues[i], config))
                }
            }
            val drawables = ArrayList<DrawableInput>()
            dNames.forEachIndexed { i, res ->
                val fd = fds[i]
                when {
                    res == null -> Unit
                    !RESOURCE_NAME.matches(res) -> skip(skipped, res, "invalid resource name")
                    fd == null -> skip(skipped, res, "no file descriptor")
                    else -> drawables.add(
                        DrawableInput(
                            name = res,
                            config = drawableConfigs?.getOrNull(i)?.takeIf { it.isNotBlank() },
                            fd = fd,
                            ninePatch = ninePatch?.getOrNull(i) == true,
                        )
                    )
                }
            }
            val firstResource = colorList.firstOrNull { it.config == null }?.name
                ?: colorList.firstOrNull()?.name
                ?: drawables.firstOrNull()?.name

            if (!isPrivilegedUid()) {
                if (dNames.isEmpty()) {
                    val plain = colorList.filter { it.config == null }
                    colorList.filter { it.config != null }.forEach {
                        skip(skipped, it.name, "configuration ${it.config} needs the privileged service (uid 0/1000)")
                    }
                    val fallback = applyColorOverlay(
                        name,
                        target,
                        plain.map { it.name }.toTypedArray(),
                        plain.map { it.argb }.toIntArray(),
                    )
                    val count = if (fallback.getInt(KEY_CODE) == 0) plain.size else 0
                    return finish(fallback, count, lookup(target, firstResource))
                }
                return finish(
                    result(
                        2,
                        "PNG drawables need the privileged service (Shizuku running as root, uid 0/1000). " +
                            "This service runs as uid ${Process.myUid()}" +
                            (if (Process.myUid() >= 10000) " inside the app (SU mode, only exec() has root)" else "") +
                            ", so FabricatedOverlay cannot be committed directly. Start Shizuku with root " +
                            "(or restart it after a reboot) and retry."
                    ),
                    0,
                    "",
                )
            }

            val owner = activeOwnerPackage()
            val id = "$owner:$name"
            val log = StringBuilder()
            log.appendLine("[UID ${Process.myUid()}] $id -> $target: ${colorList.size} colours, ${drawables.size} drawables")

            // Step 1: commit the colours through OverlayManager. PNG descriptors are never sent:
            // SELinux makes idmap2d reject them (FAILED_TRANSACTION, see frro-binary-format.md).
            val done = try {
                commitColors(name, target, owner, colorList)
            } catch (t: Throwable) {
                log.appendLine("Colour commit failed: ${describe(t)}")
                return finish(result(-1, log.toString()), 0, "")
            }
            for (i in 0 until done.skipped.length()) skipped.put(done.skipped.get(i))
            log.appendLine("Committed ${done.committed.size} colours through OverlayManager.")

            // Steps 2-4: PNGs are added by rewriting the registered .frro as root.
            val drawn = if (drawables.isEmpty()) {
                DrawableOutcome(enableOnTop(id, name, target, owner), 0)
            } else {
                applyDrawablesByRewrite(id, name, target, owner, done.committed, drawables, skipped, log)
            }
            val top = drawn.enable
            if (top.code == 0) {
                log.appendLine("Enabled ${done.committed.size} colours and ${drawn.applied}/${drawables.size} drawables.")
                log.appendLine(top.log)
                // Apps keep decoded icons in their drawable caches: Settings kept drawing its old
                // dashboard icons until it was restarted (build 53). A framework overlay change
                // restarts everything anyway. A force-stop also drops the package's alarms and
                // notifications and leaves it stopped, so only targets that hold neither get one;
                // every other app target is only killed while in the background (`am kill`), which
                // keeps its alarms, and reloads its drawables the next time it starts.
                if (drawn.applied > 0 && target !in NEVER_FORCE_STOP) {
                    if (target in SAFE_TO_FORCE_STOP) {
                        val stop = shell("am force-stop --user current $target")
                        log.appendLine("Restarted $target so it reloads its drawables (am force-stop exit ${stop.getInt(KEY_CODE)}).")
                    } else {
                        val kill = shell("am kill --user current $target")
                        log.appendLine("Killed the background process of $target so it reloads its drawables (am kill exit ${kill.getInt(KEY_CODE)}); if it is open, close and reopen it.")
                    }
                }
                // Step 5: every registration wrote a new `<owner>-<name>-XXXX.frro`; drop the files of
                // earlier applies (and their idmaps) now that the live one is enabled and verified.
                pruneStaleAfterEnable(id, owner, name, drawn.frroPath, log)
            } else {
                log.appendLine("Enable failed: ${top.log}")
            }
            val code = when {
                top.code != 0 -> top.code
                drawn.applied < drawables.size -> 3
                else -> 0
            }
            return finish(result(code, log.toString()), done.committed.size + drawn.applied, lookup(target, firstResource)).apply {
                putInt(KEY_DRAWABLES_APPLIED, drawn.applied)
            }
        } catch (t: Throwable) {
            return finish(result(-1, describe(t)), 0, "")
        } finally {
            fds.forEach { fd -> runCatching { fd?.close() } }
        }
    }

    /** Registers one FabricatedOverlay holding [colors]. Throws when the commit fails. */
    private fun commitColors(name: String, target: String, owner: String, colors: List<ColorInput>): Outcome {
        val skipped = JSONArray()
        val committed = ArrayList<ColorInput>()
        val overlay = newOverlay(name, target, owner)
        colors.forEach { c ->
            try {
                overlay.setResourceValue(c.name, TypedValue.TYPE_INT_COLOR_ARGB8, c.argb, c.config)
                committed.add(c)
            } catch (t: Throwable) {
                skip(skipped, c.name, describe(t))
            }
        }
        val register = OverlayManagerTransaction.newInstance()
        register.registerFabricatedOverlay(overlay)
        commit(register)
        return Outcome(committed, skipped)
    }

    /**
     * Adds the PNGs to the colour overlay that was just committed: rewrites its `.frro` as root
     * with [FrroWriter] (payloads plus `frro:/` URIs for the registered path), disables and
     * re-enables it so OverlayManager regenerates the idmap, then verifies the idmap, the
     * `cmd overlay lookup` result and the file itself. Any failure keeps (or restores) the
     * colour-only file and reports the drawables as skipped with the reason.
     */
    private fun applyDrawablesByRewrite(
        id: String,
        name: String,
        target: String,
        owner: String,
        colors: List<ColorInput>,
        drawables: List<DrawableInput>,
        skipped: JSONArray,
        log: StringBuilder,
    ): DrawableOutcome {
        val pngs = ArrayList<PngInput>()
        drawables.forEach { d ->
            if (d.ninePatch) {
                skip(skipped, d.name, FrroWriter.NINE_PATCH_UNSUPPORTED)
                return@forEach
            }
            try {
                pngs.add(PngInput(d, qualified(d.name, target), readAll(d.fd)))
            } catch (t: Throwable) {
                skip(skipped, d.name, "cannot read PNG: ${describe(t)}")
            }
        }
        val nine = drawables.count { it.ninePatch }
        if (nine > 0) log.appendLine("Skipped $nine nine-patch drawables: ${FrroWriter.NINE_PATCH_UNSUPPORTED}.")
        if (pngs.isEmpty()) {
            log.appendLine("No PNG left to embed; keeping the colour-only overlay.")
            return DrawableOutcome(enableOnTop(id, name, target, owner), 0)
        }

        // Steps 2 + 3: locate the registered file and replace it with colours + PNGs.
        val rewrite = try {
            rewriteFrro(id, name, target, owner, colors, pngs, log)
        } catch (t: Throwable) {
            log.appendLine("Root rewrite failed: ${describe(t)}. Keeping the colour-only overlay.")
            pngs.forEach { skip(skipped, it.input.name, "root .frro rewrite failed: ${describe(t)}") }
            return DrawableOutcome(enableOnTop(id, name, target, owner), 0)
        }

        // Step 4: disable + enable makes OverlayManager see the new CRC and recreate the idmap;
        // the enable also lands the overlay above Samsung's SemWT_*.
        val off = shell("cmd overlay disable --user current $id")
        log.appendLine("Disabled $id to regenerate its idmap (exit ${off.getInt(KEY_CODE)}).")
        val top = enableOnTop(id, name, target, owner)
        if (top.code != 0) {
            val reason = "enable after the .frro rewrite failed: ${top.log}"
            return DrawableOutcome(restoreColourOnly(id, name, target, owner, rewrite, reason, skipped, log), 0, rewrite.path)
        }

        // Verification: idmap regenerated, file parses with every PNG, lookup resolves to frro:/.
        val idmap = idmapPathFor(rewrite.path)
        val idmapFile = File(idmap)
        if (!idmapFile.isFile) {
            val reason = "OverlayManager did not create $idmap after the rewrite"
            return DrawableOutcome(restoreColourOnly(id, name, target, owner, rewrite, reason, skipped, log), 0, rewrite.path)
        }
        val idmapCrc = runCatching { readLe32(idmapFile, IDMAP_OVERLAY_CRC_OFFSET) }.getOrNull()
        val idmapFresh = idmapCrc == rewrite.crc
        log.appendLine(
            "idmap $idmap: ${idmapFile.length()} bytes, overlay crc " +
                (idmapCrc?.let { "0x%08x".format(it) } ?: "unreadable") +
                (if (idmapFresh) " (matches the rewritten .frro)" else " (expected 0x%08x)".format(rewrite.crc))
        )

        val reread = try {
            FrroReader.read(File(rewrite.path))
        } catch (t: Throwable) {
            val reason = "rewritten .frro does not parse: ${describe(t)}"
            return DrawableOutcome(restoreColourOnly(id, name, target, owner, rewrite, reason, skipped, log), 0, rewrite.path)
        }
        val onDisk = File(rewrite.path).readBytes()
        log.appendLine(
            "Re-read ${rewrite.path}: ${reread.entries.size} entries, " +
                "${reread.entries.count { it.binaryOffset != null }} file values, crc ok=${reread.crcMatchesProto}"
        )

        var applied = 0
        var mismatched = 0
        // Skips are held back until the end: when the colour-only file is restored,
        // restoreColourOnly records every PNG itself, so nothing is listed twice.
        val pending = ArrayList<Pair<String, String>>()
        val lookedUp = HashMap<String, String>()
        rewrite.pngs.forEach { p ->
            val entry = reread.entries.firstOrNull {
                it.resourceName == p.resource && it.configuration == p.input.config && it.binaryOffset != null
            }
            val inFile = entry != null && runCatching { FrroReader.readBinary(onDisk, entry) }.getOrNull()?.contentEquals(p.bytes) == true
            val value = lookedUp.getOrPut(p.resource) {
                shell("cmd overlay lookup --user current $target ${p.resource}").getString(KEY_OUTPUT).orEmpty().trim()
            }
            val resolved = value.contains(FrroFormat.URI_SCHEME)
            log.appendLine(
                "  ${p.resource}${p.input.config?.let { " [$it]" } ?: ""}: " +
                    (entry?.let { "offset=${it.binaryOffset} size=${it.binarySize}" } ?: "missing from file") +
                    ", lookup: ${value.ifEmpty { "(empty)" }}"
            )
            when {
                !inFile -> {
                    mismatched++
                    pending.add(p.input.name to "payload missing or different in the rewritten .frro")
                }
                idmapFresh || resolved -> applied++
                else -> pending.add(
                    p.input.name to
                        "written to ${rewrite.path} but the idmap still has the old CRC and lookup returned: ${value.ifEmpty { "(empty)" }}"
                )
            }
        }
        // A payload that does not read back as written, or a rewrite with no verified PNG at
        // all, must not stay registered: the next idmap regeneration (a reboot) would make the
        // unverified bytes live. Put the colour-only file back instead.
        if (mismatched > 0 || applied == 0) {
            val reason = if (mismatched > 0) {
                "$mismatched PNG payload(s) missing or different in the rewritten .frro"
            } else {
                "no PNG verified after the rewrite (idmap crc " +
                    (idmapCrc?.let { "0x%08x".format(it) } ?: "unreadable") +
                    ", expected 0x%08x, lookup did not resolve to ${FrroFormat.URI_SCHEME})".format(rewrite.crc)
            }
            return DrawableOutcome(restoreColourOnly(id, name, target, owner, rewrite, reason, skipped, log), 0, rewrite.path)
        }
        pending.forEach { (drawable, why) -> skip(skipped, drawable, why) }
        log.appendLine("Root rewrite applied $applied/${drawables.size} drawables.")
        return DrawableOutcome(top, applied, rewrite.path)
    }

    /**
     * Finds the `.frro` OverlayManager registered for [id], checks it is our colour overlay,
     * builds the full file (its colours plus [pngs]) for that exact path and atomically
     * replaces it with matching owner, mode and SELinux label. Throws on any failure, after
     * removing the temporary file; the registered file is then untouched.
     */
    private fun rewriteFrro(
        id: String,
        name: String,
        target: String,
        owner: String,
        colors: List<ColorInput>,
        pngs: List<PngInput>,
        log: StringBuilder,
    ): Rewrite {
        val path = findFrroPath(id, owner, name, log) ?: error("no registered .frro found for $id under $RESOURCE_CACHE")
        val meta = try {
            fileMeta(path).let { if (it.label == null) it.copy(label = RESOURCE_CACHE_LABEL) else it }
        } catch (t: Throwable) {
            log.appendLine("stat $path failed (${describe(t)}); using the One UI 9 defaults.")
            DEFAULT_FRRO_META
        }
        log.appendLine("Registered file $path: $meta")
        shell("ls -lZ '$path'").getString(KEY_OUTPUT).orEmpty().trim().takeIf { it.isNotEmpty() }?.let { log.appendLine("  $it") }

        val original = File(path).readBytes()
        val current = FrroReader.read(original)
        check(current.packageName == owner && current.name == name && current.targetPackage == target) {
            "$path belongs to ${current.packageName}:${current.name} -> ${current.targetPackage}, not $id -> $target"
        }
        if (current.entries.size != colors.size) {
            log.appendLine("Note: the registered file holds ${current.entries.size} values, ${colors.size} colours were committed.")
        }

        // Every entry exactly as OverlayManager wrote it (colours and any other typed value, so a
        // file that also holds dimensions or booleans is never truncated to its colours), plus the
        // PNG payloads. Version 3 is needed for file values.
        val previousBinaries = current.entries.count { it.binaryOffset != null }
        val writer = FrroWriter.from(current, original, version = maxOf(current.version, FrroFormat.CURRENT_VERSION))
        pngs.forEach { writer.setBinary(it.resource, it.bytes, false, it.input.config.orEmpty()) }
        val bytes = writer.toByteArray(path)
        val built = FrroReader.read(bytes)
        val kept = current.entries.count { it.binaryOffset == null }
        check(
            built.crcMatchesProto &&
                built.entries.count { it.binaryOffset != null } >= pngs.size &&
                built.entries.count { it.binaryOffset == null } >= kept - pngs.size
        ) { "FrroWriter output failed its own read-back" }
        val nonColour = current.entries.count { it.binaryOffset == null && it.dataType != FrroFormat.TYPE_INT_COLOR_ARGB8 }
        log.appendLine(
            "Built ${bytes.size} bytes: $kept kept values ($nonColour non-colour, $previousBinaries earlier files) + ${pngs.size} PNGs " +
                "(${pngs.sumOf { it.bytes.size }} payload bytes), crc 0x%08x".format(built.crc)
        )

        replaceFile(path, bytes, meta, log)
        log.appendLine("Replaced $path (was ${original.size} bytes, crc 0x%08x).".format(current.crc))
        return Rewrite(path, original, meta, built.crc, pngs)
    }

    /** Puts the colour-only file back after a failed rewrite, re-enables it and skips every PNG with [reason]. */
    private fun restoreColourOnly(
        id: String,
        name: String,
        target: String,
        owner: String,
        rewrite: Rewrite,
        reason: String,
        skipped: JSONArray,
        log: StringBuilder,
    ): EnableResult {
        log.appendLine("Rewrite verification failed: $reason")
        try {
            replaceFile(rewrite.path, rewrite.original, rewrite.meta, log)
            log.appendLine("Restored the colour-only ${rewrite.path}.")
        } catch (t: Throwable) {
            log.appendLine("Could not restore the colour-only file: ${describe(t)}")
        }
        rewrite.pngs.forEach { skip(skipped, it.input.name, reason) }
        shell("cmd overlay disable --user current $id")
        return enableOnTop(id, name, target, owner)
    }

    /**
     * The `.frro` path of [id]: from `cmd overlay dump <id>` (its base code path), else the
     * newest `/data/resource-cache/<owner>-<name>-*.frro`. Only paths of that exact form are accepted.
     */
    private fun findFrroPath(id: String, owner: String, name: String, log: StringBuilder): String? {
        val prefix = "$RESOURCE_CACHE/$owner-$name-"
        fun ours(p: String) = p.startsWith(prefix) && FRRO_SUFFIX.matches(p.substring(prefix.length))
        val dump = shell("cmd overlay dump $id").getString(KEY_OUTPUT).orEmpty()
        FRRO_PATH.findAll(dump).map { it.value }.firstOrNull { ours(it) }?.let {
            log.appendLine("Located $it via cmd overlay dump.")
            return it
        }
        val newest = shell("ls -t $prefix*.frro 2>/dev/null").getString(KEY_OUTPUT).orEmpty().lineSequence()
            .map { it.trim() }
            .firstOrNull { ours(it) }
        if (newest != null) log.appendLine("cmd overlay dump named no path; using the newest file $newest.")
        return newest
    }

    /** uid, gid and mode from stat(2); label from `stat -c %C` (toybox), else `ls -Z`. */
    private fun fileMeta(path: String): FileMeta {
        val st = Os.stat(path)
        return FileMeta(st.st_uid, st.st_gid, st.st_mode and 0xFFF, selinuxLabel(path))
    }

    private fun selinuxLabel(path: String): String? {
        for (cmd in listOf("toybox stat -c %C '$path'", "stat -c %C '$path'", "ls -Zd '$path'")) {
            val r = shell("$cmd 2>/dev/null")
            if (r.getInt(KEY_CODE) != 0) continue
            r.getString(KEY_OUTPUT).orEmpty().split(Regex("\\s+")).firstOrNull { SELINUX_LABEL.matches(it) }?.let { return it }
        }
        return null
    }

    /**
     * Writes [bytes] to `<path>.hexnext.tmp` in the same directory, applies [meta] (mode,
     * owner, SELinux label; label fallback [RESOURCE_CACHE_LABEL], then `restorecon`), checks
     * the result and renames it over [path]. Deletes the temporary file on any failure.
     */
    private fun replaceFile(path: String, bytes: ByteArray, meta: FileMeta, log: StringBuilder) {
        val tmp = "$path$TMP_SUFFIX"
        try {
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.fd.sync()
            }
            Os.chmod(tmp, meta.mode)
            try {
                Os.chown(tmp, meta.uid, meta.gid)
            } catch (t: Throwable) {
                log.appendLine("chown ${meta.uid}:${meta.gid} failed: ${describe(t)}")
            }
            val wanted = meta.label ?: RESOURCE_CACHE_LABEL
            var label = labelAfter("chcon '$wanted' '$tmp'", tmp)
            if (label != wanted && wanted != RESOURCE_CACHE_LABEL) {
                log.appendLine("chcon $wanted left ${label ?: "(unknown)"}; trying $RESOURCE_CACHE_LABEL.")
                label = labelAfter("chcon '$RESOURCE_CACHE_LABEL' '$tmp'", tmp)
            }
            if (label != wanted && label != RESOURCE_CACHE_LABEL) {
                log.appendLine("chcon left ${label ?: "(unknown)"}; running restorecon.")
                label = labelAfter("restorecon '$tmp'", tmp)
            }
            val got = fileMeta(tmp).copy(label = label)
            check(got.uid == meta.uid && got.gid == meta.gid && got.mode == meta.mode) { "temporary file is $got, wanted $meta" }
            check(label == null || label == wanted || label == RESOURCE_CACHE_LABEL) { "temporary file has label $label, wanted $wanted" }
            if (label == null) log.appendLine("SELinux label of $tmp could not be read; continuing.")
            try {
                Os.rename(tmp, path)
            } catch (t: Throwable) {
                val mv = shell("mv -f '$tmp' '$path'")
                check(mv.getInt(KEY_CODE) == 0) { "rename failed (${describe(t)}), mv: ${mv.getString(KEY_OUTPUT)}" }
            }
            log.appendLine("Wrote ${bytes.size} bytes to $path as $got.")
        } catch (t: Throwable) {
            runCatching { File(tmp).delete() }
            throw t
        }
    }

    private fun labelAfter(command: String, path: String): String? {
        shell("$command 2>&1")
        return selinuxLabel(path)
    }

    /** `/data/resource-cache/` + [frroPath] without its leading slash, `/` -> `@`, + `@idmap`. */
    private fun idmapPathFor(frroPath: String): String =
        "$RESOURCE_CACHE/" + frroPath.removePrefix("/").replace('/', '@') + "@idmap"

    // ---------------------------------------------------------------------------------------
    // Stale .frro cleanup
    // ---------------------------------------------------------------------------------------

    /** One `<owner>-<name>-<suffix>.frro` file in [RESOURCE_CACHE], as idmap2d names fabricated overlays. */
    private class FrroEntry(val owner: String, val name: String, val fileName: String) {
        val path: String get() = "$RESOURCE_CACHE/$fileName"
        val overlayId: String get() = "$owner:$name"
    }

    /**
     * Every file in [RESOURCE_CACHE] named exactly `<owner>-<name>-<suffix>.frro` ([FRRO_FILE], suffix
     * re-checked with [FRRO_SUFFIX]). Built from a plain `ls` of the directory, never from a glob, so
     * no caller-supplied string reaches the shell unvalidated. Owner and name cannot contain `-`,
     * which makes the split unambiguous.
     */
    private fun listFrroEntries(): List<FrroEntry> =
        shell("ls -1 '$RESOURCE_CACHE' 2>/dev/null").getString(KEY_OUTPUT).orEmpty().lineSequence()
            .map { it.trim() }
            .mapNotNull { file ->
                val m = FRRO_FILE.matchEntire(file) ?: return@mapNotNull null
                val owner = m.groupValues[1]
                val name = m.groupValues[2]
                val suffix = file.substring(owner.length + 1 + name.length + 1)
                if (!FRRO_SUFFIX.matches(suffix)) return@mapNotNull null
                FrroEntry(owner, name, file)
            }
            .toList()

    /**
     * Removes the `.frro` files in [entries] and their `@idmap` siblings with `rm -f` and logs one
     * line naming what went. Returns how many `.frro` files were removed (rm exit 0).
     */
    private fun removeFrroFiles(entries: List<FrroEntry>, what: String, log: StringBuilder): Int {
        if (entries.isEmpty()) {
            log.appendLine("Cleanup: no $what .frro files in $RESOURCE_CACHE.")
            return 0
        }
        var removed = 0
        entries.forEach { e ->
            val frro = e.path
            val idmap = idmapPathFor(frro)
            val rm = shell("rm -f '$frro' '$idmap'")
            if (rm.getInt(KEY_CODE) == 0) {
                removed++
            } else {
                log.appendLine("Cleanup: could not remove $frro: ${rm.getString(KEY_OUTPUT).orEmpty().trim()}")
            }
        }
        log.appendLine(
            "Cleanup: removed $removed/${entries.size} $what .frro files (and their idmaps) from $RESOURCE_CACHE: " +
                entries.joinToString(", ") { it.fileName }
        )
        return removed
    }

    /**
     * Removes every `<owner>-<name>-<suffix>.frro` of one overlay (plus idmaps) except [keep], the
     * path OverlayManager currently uses. Each registration writes a new random suffix and nothing
     * deletes the previous file, so earlier applies pile up beside the live one. With [keep] null
     * (overlay unregistered, or about to be re-registered) every file of the overlay goes. Returns
     * the number of files removed; nothing is touched when [owner] or [name] look unexpected.
     */
    private fun pruneOverlayFrros(owner: String, name: String, keep: String?, log: StringBuilder): Int {
        if (!PACKAGE_NAME.matches(owner) || !OVERLAY_NAME.matches(name)) {
            log.appendLine("Cleanup: not touching $RESOURCE_CACHE for $owner:$name (unexpected characters).")
            return 0
        }
        val stale = listFrroEntries().filter { it.owner == owner && it.name == name && it.path != keep }
        if (keep != null) log.appendLine("Cleanup: keeping the live $keep.")
        return removeFrroFiles(stale, if (keep != null) "stale $owner:$name" else "$owner:$name", log)
    }

    /**
     * After a successful enable: keeps the live `.frro` of [id] ([known] from the rewrite, else located
     * with [findFrroPath]) and removes the overlay's other files. When the live path cannot be
     * located nothing is removed, so a confused `cmd overlay dump` can never delete the file in use.
     */
    private fun pruneStaleAfterEnable(id: String, owner: String, name: String, known: String?, log: StringBuilder) {
        val live = known ?: findFrroPath(id, owner, name, log)
        if (live == null) {
            log.appendLine("Cleanup: skipped, the live .frro of $id could not be located under $RESOURCE_CACHE.")
            return
        }
        pruneOverlayFrros(owner, name, live, log)
    }

    /**
     * Removes `.frro` files (plus idmaps) of HexNext overlays, i.e. entries whose name starts with
     * [HEX_PREFIX]. With [onlyUnregistered] only overlays `cmd overlay list` does not know as
     * `owner:name` go (left behind by unregister or by a registration that was never cleaned up);
     * nothing is removed when the list cannot be read or is empty, because then "unregistered"
     * cannot be told apart from "live". Without it every HexNext file goes (safe-mode purge).
     */
    private fun pruneHexFrros(onlyUnregistered: Boolean, log: StringBuilder): Int {
        val hex = listFrroEntries().filter { it.name.startsWith(HEX_PREFIX, ignoreCase = true) }
        if (!onlyUnregistered) {
            val removed = removeFrroFiles(hex, "HexNext", log)
            removeOrphanHexIdmaps(log)
            return removed
        }
        val list = shell("cmd overlay list --user current")
        if (list.getInt(KEY_CODE) != 0) {
            log.appendLine("Cleanup: not pruning unregistered HexNext .frro files, cmd overlay list failed (exit ${list.getInt(KEY_CODE)}).")
            return 0
        }
        val registered = parseOverlayList(list.getString(KEY_OUTPUT).orEmpty()).map { it.overlayId }.toSet()
        if (registered.isEmpty()) {
            log.appendLine("Cleanup: not pruning unregistered HexNext .frro files, cmd overlay list returned no overlays.")
            return 0
        }
        return removeFrroFiles(hex.filter { it.overlayId !in registered }, "unregistered HexNext", log)
    }

    /**
     * Safe-mode purge of `data@resource-cache@<owner>-HexNext…-<suffix>.frro@idmap` files whose
     * `.frro` is already gone ([removeFrroFiles] derives idmaps from existing `.frro` files only).
     * Names come from a plain `ls`, are validated with [HEX_IDMAP_FILE] and single-quoted.
     */
    private fun removeOrphanHexIdmaps(log: StringBuilder) {
        val orphans = shell("ls -1 '$RESOURCE_CACHE' 2>/dev/null").getString(KEY_OUTPUT).orEmpty().lineSequence()
            .map { it.trim() }
            .filter { file ->
                val m = HEX_IDMAP_FILE.matchEntire(file) ?: return@filter false
                m.groupValues[2].startsWith(HEX_PREFIX, ignoreCase = true)
            }
            .toList()
        if (orphans.isEmpty()) return
        var removed = 0
        orphans.forEach { file ->
            val rm = shell("rm -f '$RESOURCE_CACHE/$file'")
            if (rm.getInt(KEY_CODE) == 0) {
                removed++
            } else {
                log.appendLine("Cleanup: could not remove $RESOURCE_CACHE/$file: ${rm.getString(KEY_OUTPUT).orEmpty().trim()}")
            }
        }
        log.appendLine("Cleanup: removed $removed/${orphans.size} orphaned HexNext idmap files from $RESOURCE_CACHE: " + orphans.joinToString(", "))
    }

    private fun readLe32(file: File, offset: Long): Int = RandomAccessFile(file, "r").use { raf ->
        raf.seek(offset)
        val b = ByteArray(4)
        raf.readFully(b)
        (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8) or
            ((b[2].toInt() and 0xFF) shl 16) or ((b[3].toInt() and 0xFF) shl 24)
    }

    /** `pkg:type/name`, prefixing [target] when [name] has no package. */
    private fun qualified(name: String, target: String): String = if (name.contains(':')) name else "$target:$name"

    /** Reads a regular-file descriptor with pread so its offset is untouched. */
    private fun readAll(pfd: ParcelFileDescriptor): ByteArray {
        val fd = pfd.fileDescriptor
        val size = Os.fstat(fd).st_size
        require(size in 1..MAX_DRAWABLE_BYTES) { "unexpected drawable size $size" }
        val out = ByteArray(size.toInt())
        var offset = 0
        while (offset < out.size) {
            val n = Os.pread(fd, out, offset, out.size - offset, offset.toLong())
            if (n <= 0) break
            offset += n
        }
        require(offset == out.size) { "short read $offset/${out.size}" }
        return out
    }
    private fun lookup(target: String, resource: String?): String {
        if (resource == null || !RESOURCE_NAME.matches(resource) || !PACKAGE_NAME.matches(target)) return ""
        val r = shell("cmd overlay lookup --user current $target $resource")
        return r.getString(KEY_OUTPUT).orEmpty()
    }

    private fun skip(skipped: JSONArray, name: String, reason: String) {
        skipped.put(JSONObject().put("name", name).put("reason", reason))
    }

    // ---------------------------------------------------------------------------------------
    // Panic / safety
    // ---------------------------------------------------------------------------------------

    override fun disableHexOverlays(): Bundle {
        return try {
            val list = shell("cmd overlay list --user current")
            // A failed list (su denied, timeout) parses as "no overlays"; reporting that as
            // success would let safe mode and Remove claim the theme is off while it is not.
            val listCode = list.getInt(KEY_CODE)
            if (listCode != 0) {
                return result(
                    listCode,
                    "cmd overlay list failed (exit $listCode); no HexNext overlay was disabled [UID ${Process.myUid()}]\n" +
                        list.getString(KEY_OUTPUT).orEmpty().trim(),
                )
            }
            val hex = parseOverlayList(list.getString(KEY_OUTPUT).orEmpty())
                .filter { it.name.startsWith(HEX_PREFIX) }
            val json = JSONArray()
            var failures = 0
            hex.forEach { o ->
                val entry = JSONObject().put("overlayId", o.overlayId)
                if (!OVERLAY_ID.matches(o.overlayId)) {
                    failures++
                    json.put(entry.put("disabled", false).put("unregistered", false).put("message", "unexpected id format"))
                    return@forEach
                }
                val disable = shell("cmd overlay disable --user current ${o.overlayId}")
                val disabled = disable.getInt(KEY_CODE) == 0
                var unregistered = false
                var message = disable.getString(KEY_OUTPUT).orEmpty()
                if (isPrivilegedUid() && o.overlayId.contains(':')) {
                    try {
                        val owner = o.overlayId.substringBefore(':')
                        val tx = OverlayManagerTransaction.newInstance()
                        tx.unregisterFabricatedOverlay(newOverlay(o.name, "android", owner).identifier)
                        commit(tx)
                        unregistered = true
                    } catch (t: Throwable) {
                        message = (message + "\nunregister failed: " + describe(t)).trim()
                    }
                }
                if (!disabled && !unregistered) failures++
                json.put(entry.put("disabled", disabled).put("unregistered", unregistered).put("message", message))
            }
            val ids = hex.joinToString("\n") { "  ${it.overlayId}" }
            // Unregistered overlays leave their `.frro` + idmap behind in /data/resource-cache;
            // remove every HexNext file that `cmd overlay list` no longer knows (this run's and older ones).
            val cleanup = StringBuilder()
            pruneHexFrros(onlyUnregistered = true, cleanup)
            result(
                if (failures == 0) 0 else 1,
                "Acted on ${hex.size} HexNext overlays (${hex.size - failures} ok) [UID ${Process.myUid()}]" +
                    (if (ids.isNotEmpty()) "\n$ids" else "") +
                    "\n$cleanup"
            ).apply { putString(KEY_JSON, json.toString()) }
        } catch (t: Throwable) {
            result(-1, describe(t))
        }
    }

    override fun writeStateFile(name: String?, content: String?): Bundle {
        return try {
            val file = stateFile(name) ?: return result(-1, "Invalid state file name: $name")
            writeRootFile(file, content.orEmpty())
            result(0, "Wrote ${file.path}")
        } catch (t: Throwable) {
            result(-1, describe(t))
        }
    }

    override fun readStateFile(name: String?): Bundle {
        return try {
            val file = stateFile(name) ?: return result(-1, "Invalid state file name: $name")
            val text = readRootFile(file)
            if (text == null) {
                result(1, "${file.path} does not exist")
            } else {
                result(0, "Read ${file.path}").apply { putString(KEY_CONTENT, text) }
            }
        } catch (t: Throwable) {
            result(-1, describe(t))
        }
    }

    override fun startStabilityMonitor(seconds: Int): Bundle = startMonitor(seconds, null)

    /**
     * [startStabilityMonitor] in staged mode: a first trip disables only the overlays whose name
     * starts with [firstPrefix] (`[A-Za-z0-9_]+`) and writes `values_rollback.json`; only a
     * second trip in a fresh window disables every HexNext overlay (see [MonitorScript]).
     */
    override fun startStabilityMonitorStaged(seconds: Int, firstPrefix: String?): Bundle {
        if (firstPrefix == null || !MonitorScript.PREFIX.matches(firstPrefix) || !firstPrefix.startsWith(HEX_PREFIX)) {
            return result(-1, "Invalid monitor prefix")
        }
        return startMonitor(seconds, firstPrefix)
    }

    private fun startMonitor(seconds: Int, firstPrefix: String?): Bundle {
        return try {
            val window = seconds.coerceIn(10, 1800)
            // A previous monitor gets TERM; give it a second to write its final status first.
            val previous = stopMonitorProcess()
            val settle = if (previous.startsWith("Stopped")) "sleep 1; " else ""
            // The file is kept for diagnostics only. Root runs the text it holds in memory
            // (`sh -c '<text>' monitor.sh <seconds>`), never the copy in the shell-owned state
            // directory, which any uid 2000 process could swap before `sh` reads it.
            writeRootFile(File(MonitorScript.PATH), MonitorScript.text)
            val args = listOfNotNull(window.toString(), firstPrefix).toTypedArray()
            val script = inlineScript(MonitorScript.text, MonitorScript.PATH.substringAfterLast('/'), *args)
            val launch = shell(
                settle + "rm -f ${MonitorScript.SAFE_MODE_FILE} ${MonitorScript.VALUES_ROLLBACK_FILE} ${MonitorScript.PID_FILE}; " +
                    "if command -v setsid >/dev/null 2>&1; then " +
                    "nohup setsid $script >/dev/null 2>&1 & " +
                    "else nohup $script >/dev/null 2>&1 & fi"
            )
            val launchCode = launch.getInt(KEY_CODE)
            if (launchCode != 0) {
                // Never MONITOR_PID_MISSING: that code means "launched, so watch it".
                return result(if (launchCode == MONITOR_PID_MISSING) -1 else launchCode, "Could not start the monitor (exit $launchCode): ${launch.getString(KEY_OUTPUT)}")
            }
            // A phone busy with the apply can take more than a second to get to the pid file.
            val pid = shell(
                "i=0; while [ \$i -lt $PID_WAIT_SECONDS ] && [ ! -s ${MonitorScript.PID_FILE} ]; do sleep 1; i=\$((i + 1)); done; " +
                    "cat ${MonitorScript.PID_FILE} 2>/dev/null"
            ).getString(KEY_OUTPUT).orEmpty().trim()
            val pidValue = pid.toIntOrNull()
            if (pidValue == null) {
                result(MONITOR_PID_MISSING, "Monitor launched but ${MonitorScript.PID_FILE} is missing: $pid")
            } else {
                val staged = firstPrefix?.let { " (staged: $it first)" }.orEmpty()
                result(0, "Stability monitor pid $pidValue watching system_server + SystemUI for $window s$staged").apply {
                    putInt("pid", pidValue)
                    putInt("seconds", window)
                }
            }
        } catch (t: Throwable) {
            result(-1, describe(t))
        }
    }

    override fun stopStabilityMonitor(): Bundle {
        return try {
            result(0, stopMonitorProcess())
        } catch (t: Throwable) {
            result(-1, describe(t))
        }
    }

    /** Kills the monitor named by the pid file, but only if that pid is still running monitor.sh. */
    private fun stopMonitorProcess(): String {
        val pidFile = MonitorScript.PID_FILE
        val r = shell(
            "P=\$(cat $pidFile 2>/dev/null); " +
                "if [ -n \"\$P\" ]; then " +
                "case \"\$(tr '\\0' ' ' < /proc/\$P/cmdline 2>/dev/null)\" in " +
                "*monitor.sh*) kill \$P && echo \"Stopped monitor pid \$P\" ;; " +
                "*) rm -f $pidFile; echo \"Stale pid file (\$P) removed\" ;; esac; " +
                "else echo 'No monitor running'; fi"
        )
        return r.getString(KEY_OUTPUT).orEmpty()
    }

    private fun stateFile(name: String?): File? {
        if (name == null || !STATE_NAME.matches(name) || name.startsWith(".")) return null
        return File(MonitorScript.DIR, name)
    }

    /**
     * Writes a world-readable file: directly as root, system or shell, else through `su -c cat >`.
     *
     * The state directory must stay writable for the shell (uid 2000): the same phone can run the
     * service as root one day and as the ADB shell the next (Shizuku restarted over wireless
     * debugging). A directory root created is root:root 0755 and the shell could not write a single
     * state file in it, so root hands every directory and file it writes here to the shell
     * (`chown 2000:2000`); uid 1000, which cannot chown, opens its directories to all instead.
     * This also repairs a directory an older build left root-owned, on the next root write.
     *
     * Because the shell owns `/data/local/tmp` and this directory, any uid 2000 process can put a
     * symlink in place of [MonitorScript.DIR] (or `exec`, or a file in them). Root must never chown,
     * chmod or write through such a link: every directory is opened top-down with O_NOFOLLOW and
     * changed through its descriptor, and the file is created relative to that descriptor
     * (`/proc/self/fd/N/name`), so a swap after the check changes nothing. The su path does the
     * same with `cd -P` and a `pwd -P` check, and works on `.` and relative names only.
     */
    private fun writeRootFile(file: File, content: String) {
        val dir = file.parentFile ?: error("no parent for ${file.path}")
        // Top-down: MonitorScript.DIR first, then each subdirectory under it.
        val stateDirs = stateDirsOf(dir).asReversed()
        if (stateDirs.isEmpty()) error("${file.path} is outside ${MonitorScript.DIR}")
        val shellOwned = !file.name.endsWith(".sh")
        if (runsDirectly()) {
            val opened = ArrayList<ParcelFileDescriptor>()
            try {
                var anchor = ""
                stateDirs.forEachIndexed { index, d ->
                    val pfd = openStateDir(if (index == 0) d.path else "$anchor/${d.name}", d.path)
                    opened += pfd
                    anchor = "/proc/self/fd/${pfd.fd}"
                }
                val tmp = "$anchor/${file.name}.tmp"
                val target = "$anchor/${file.name}"
                writeNew(tmp, content, shellOwned)
                try {
                    Os.rename(tmp, target)
                } catch (e: ErrnoException) {
                    writeNew(target, content, shellOwned)
                    runCatching { Os.remove(tmp) }
                }
            } finally {
                opened.forEach { runCatching { it.close() } }
            }
            return
        }
        // The tmp name is random so that no uid 2000 process can plant it in advance; `set -C`
        // then creates it with O_EXCL. umask 022 gives 0644 without a chmod that follows links,
        // and `chown -h` changes a link swapped in afterwards, never its target.
        val tmpName = ".${file.name}.${UUID.randomUUID()}.tmp"
        val script = buildString {
            append("umask 022; ")
            stateDirs.forEachIndexed { index, d ->
                val name = if (index == 0) d.path else d.name
                append("mkdir '$name' 2>/dev/null; ")
                append("cd -P '$name' && [ \"\$(pwd -P)\" = '${d.path}' ] || { echo '${d.path} is not a real directory' >&2; exit 1; }; ")
                append("chmod 755 . && chown $SHELL_UID:$SHELL_UID . || exit 1; ")
            }
            append("(set -C; cat > '$tmpName') || { rm -f '$tmpName'; exit 1; }; ")
            if (shellOwned) append("chown -h $SHELL_UID:$SHELL_UID '$tmpName' && ")
            append("mv -f '$tmpName' '${file.name}' || { rm -f '$tmpName'; exit 1; }")
        }
        val process = ProcessBuilder("su", "-c", script).redirectErrorStream(true).start()
        // Feed stdin and read the output on their own threads, as in shell(): a hung su (an
        // unanswered root prompt, a stuck daemon) keeps both pipes open, and blocking on either
        // here would mean the timeout below never fires.
        val sink = StringBuilder()
        val reader = Thread({
            try {
                process.inputStream.bufferedReader().use { input ->
                    val buffer = CharArray(8192)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        synchronized(sink) { sink.append(buffer, 0, n) }
                    }
                }
            } catch (ignored: Throwable) {
                // The stream closed under us (process destroyed); keep what was read.
            }
        }, "hexnext-su-write-out")
        reader.isDaemon = true
        reader.start()
        val writer = Thread({
            runCatching { process.outputStream.use { it.write(content.toByteArray()) } }
        }, "hexnext-su-write-in")
        writer.isDaemon = true
        writer.start()
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            runCatching { process.waitFor(5, TimeUnit.SECONDS) }
            error("su write of ${file.path} timed out")
        }
        reader.join(READER_GRACE_MILLIS)
        val out = synchronized(sink) { sink.toString() }
        if (process.exitValue() != 0) error("su write of ${file.path} failed (${process.exitValue()}): $out")
    }

    /**
     * Unlinks [path] (a symlink itself, never its target) and creates it anew with O_EXCL and
     * O_NOFOLLOW, 0644. Mode and owner are set on the descriptor, never through the path.
     */
    private fun writeNew(path: String, content: String, shellOwned: Boolean) {
        runCatching { Os.remove(path) }
        val fd = Os.open(
            path,
            OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC,
            MODE_FILE,
        )
        try {
            runCatching { Os.fchmod(fd, MODE_FILE) }
            if (shellOwned && Process.myUid() == 0) runCatching { Os.fchown(fd, SHELL_UID, SHELL_UID) }
            val bytes = content.toByteArray()
            var offset = 0
            while (offset < bytes.size) {
                val n = Os.write(fd, bytes, offset, bytes.size - offset)
                if (n <= 0) error("short write to $path")
                offset += n
            }
        } finally {
            Os.close(fd)
        }
    }

    /**
     * Opens the state directory at [path] (created 0755 when missing) without following a
     * symlink, checks that it is a directory and makes it usable for the shell tier through the
     * descriptor: root chowns it to uid 2000, uid 1000 (no CAP_CHOWN) opens it to all. [label] is
     * its real path, for errors.
     */
    private fun openStateDir(path: String, label: String): ParcelFileDescriptor {
        try {
            Os.mkdir(path, MODE_DIR)
        } catch (e: ErrnoException) {
            if (e.errno != OsConstants.EEXIST) throw e
        }
        // O_NOFOLLOW fails (ELOOP) on a planted symlink; O_NONBLOCK keeps a planted FIFO from blocking.
        val fd = try {
            Os.open(path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK or OsConstants.O_CLOEXEC, 0)
        } catch (e: ErrnoException) {
            error("$label is not a real directory (${e.message})")
        }
        try {
            if (!OsConstants.S_ISDIR(Os.fstat(fd).st_mode)) error("$label is not a directory")
            when (Process.myUid()) {
                0 -> {
                    runCatching { Os.fchmod(fd, MODE_DIR) }
                    runCatching { Os.fchown(fd, SHELL_UID, SHELL_UID) }
                }
                1000 -> runCatching { Os.fchmod(fd, MODE_DIR_OPEN) }
                else -> runCatching { Os.fchmod(fd, MODE_DIR) }
            }
            return ParcelFileDescriptor.dup(fd)
        } finally {
            Os.close(fd)
        }
    }

    /** [dir] and its parents up to and including [MonitorScript.DIR]; empty when [dir] is outside it. */
    private fun stateDirsOf(dir: File): List<File> {
        val root = File(MonitorScript.DIR)
        val dirs = mutableListOf<File>()
        var d: File? = dir
        while (d != null) {
            dirs += d
            if (d.path == root.path) return dirs
            d = d.parentFile
        }
        return emptyList()
    }

    /**
     * The text of [file], or null when it does not exist. A read that fails (su denied or not
     * ready, a timeout, an unreadable file) throws instead: callers such as the SemWT restore must
     * never take a file they could not read for a missing one and overwrite it.
     */
    private fun readRootFile(file: File): String? {
        if (runsDirectly()) {
            return if (file.isFile) file.readText() else null
        }
        val r = shell("if [ -f '${file.path}' ]; then cat '${file.path}'; else echo '$NO_FILE_MARKER'; fi")
        val code = r.getInt(KEY_CODE)
        val out = r.getString(KEY_OUTPUT).orEmpty()
        if (code != 0) error("su read of ${file.path} failed ($code): ${out.lineSequence().firstOrNull().orEmpty()}")
        return if (out.trim() == NO_FILE_MARKER) null else out
    }

    private fun isSafePath(path: String): Boolean =
        path.startsWith("/") && path.length < 4096 && path.none { it == '\'' || it == '"' || it == '\n' || it == '\r' || it == '\u0000' }

    private class EnableResult(val code: Int, val log: String)

    /**
     * Enables [id] and makes sure it ends up as the highest enabled overlay of [target].
     *
     * On One UI 9 OverlayManagerService calls `setHighestPriority` on every successful enable, so
     * a plain enable normally lands on top. `cmd overlay set-priority … highest` still runs, but
     * only best-effort and separately: it fails for fabricated overlays ("Unable to retrieve
     * overlay information", exit 255), and that exit must never be read as "overlay missing" or
     * fail the apply. Afterwards `cmd overlay list` is checked; when another enabled overlay of
     * the target is listed after ours the overlay is raised with a disable/enable cycle (through
     * an OverlayManagerTransaction when privileged and the hidden `setEnabled` exists, else the
     * shell). Only a failing enable makes the result non-zero.
     */
    private fun enableOnTop(id: String, name: String, target: String, owner: String): EnableResult {
        val log = StringBuilder()
        val enable = shell("cmd overlay enable --user current $id")
        val code = enable.getInt(KEY_CODE)
        if (code != 0) return EnableResult(code, enable.getString(KEY_OUTPUT).orEmpty().ifEmpty { "exit $code" })

        val priority = shell("cmd overlay set-priority --user current $id highest")
        val priorityCode = priority.getInt(KEY_CODE)
        log.append("set-priority highest (best-effort): exit $priorityCode")
        priority.getString(KEY_OUTPUT).orEmpty().trim().takeIf { it.isNotEmpty() }?.let { log.append(" ").append(it) }
        log.appendLine()

        val need = raiseNeed(id, target)
        if (need == RaiseNeed.RAISE) {
            val how = raiseWithTransaction(name, target, owner) ?: run {
                val off = shell("cmd overlay disable --user current $id")
                val on = shell("cmd overlay enable --user current $id")
                "shell disable/enable (exit ${off.getInt(KEY_CODE)}/${on.getInt(KEY_CODE)})"
            }
            log.appendLine("Another overlay of $target was above $id; re-raised via $how.")
            val after = raiseNeed(id, target)
            log.appendLine("Order after raise: ${if (after == RaiseNeed.NONE) "on top" else after.name.lowercase()}.")
        } else {
            log.appendLine("Order: ${if (need == RaiseNeed.NONE) "on top of $target" else need.name.lowercase()}.")
        }
        return EnableResult(0, log.toString().trimEnd())
    }

    private fun raiseNeed(id: String, target: String): RaiseNeed {
        val list = shell("cmd overlay list --user current")
        if (list.getInt(KEY_CODE) != 0) return RaiseNeed.NONE
        return SemWtPlanner.raiseNeed(OverlayList.parse(list.getString(KEY_OUTPUT).orEmpty()), target, id)
    }

    /**
     * Disable then enable the fabricated overlay in one transaction through the hidden
     * `OverlayManagerTransaction.setEnabled(OverlayIdentifier, boolean, int)`. Returns a
     * description on success, or null when not privileged or the method is unavailable.
     */
    private fun raiseWithTransaction(name: String, target: String, owner: String): String? {
        if (!isPrivilegedUid()) return null
        return try {
            val identifier = newOverlay(name, target, owner).identifier
            val setEnabled = OverlayManagerTransaction::class.java.methods.firstOrNull { m ->
                m.name == "setEnabled" && m.parameterTypes.size == 3 &&
                    m.parameterTypes[1] == java.lang.Boolean.TYPE && m.parameterTypes[2] == Integer.TYPE
            } ?: return null
            val user = currentUserId()
            val off = OverlayManagerTransaction.newInstance()
            setEnabled.invoke(off, identifier, false, user)
            commit(off)
            val on = OverlayManagerTransaction.newInstance()
            setEnabled.invoke(on, identifier, true, user)
            commit(on)
            "OverlayManagerTransaction.setEnabled (user $user)"
        } catch (t: Throwable) {
            null
        }
    }

    private fun currentUserId(): Int =
        shell("am get-current-user").getString(KEY_OUTPUT).orEmpty().trim().toIntOrNull() ?: 0

    private fun newOverlay(name: String, target: String, owner: String): FabricatedOverlay {
        val overlay = FabricatedOverlay(name, target)
        FabricatedOverlay::class.java
            .getMethod("setOwningPackage", String::class.java)
            .invoke(overlay, owner)
        return overlay
    }

    private fun commit(transaction: OverlayManagerTransaction) {
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, "overlay") as IBinder
        val iom = Class.forName("android.content.om.IOverlayManager\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder)
        Class.forName("android.content.om.IOverlayManager")
            .getMethod("commit", OverlayManagerTransaction::class.java)
            .invoke(iom, transaction)
    }

    private fun describe(t: Throwable?): String {
        if (t == null) return "unknown error"
        val cause = if (t is InvocationTargetException) t.targetException ?: t else t
        return "${cause.javaClass.simpleName}: ${cause.message}"
    }

    companion object {
        const val KEY_CODE = "code"

        /**
         * [startStabilityMonitor]: the launch succeeded (the old safe_mode.json is gone) but the
         * pid file was not there yet. The monitor is most likely running, so callers watch it.
         */
        const val MONITOR_PID_MISSING = 2

        /** How long [startStabilityMonitor] waits for the monitor's pid file. */
        private const val PID_WAIT_SECONDS = 5
        const val KEY_OUTPUT = "output"
        const val KEY_OUTPUT_PATH = "outputPath"
        const val KEY_OUTPUT_LENGTH = "outputLength"

        /** Characters of exec output returned inline; ~400 KB as UTF-16 in the parcel. */
        const val EXEC_INLINE_LIMIT = 200_000
        private const val EXEC_HEAD_CHARS = 4_000
        /** Longest a single [shell] command may run before it is destroyed (exit 124). */
        private const val SHELL_TIMEOUT_SECONDS = 60L
        /** How long [shell] waits for the output to close after the command exited. */
        private const val READER_GRACE_MILLIS = 10_000L
        const val KEY_JSON = "json"
        const val KEY_FRRO_PATHS = "frroPaths"
        const val KEY_RESOURCE_COUNT = "resourceCount"
        const val KEY_SKIPPED = "skipped"
        const val KEY_MILLIS = "millis"
        const val KEY_LOOKUP = "lookup"

        /** Drawables embedded by the root `.frro` rewrite and verified afterwards. */
        const val KEY_DRAWABLES_APPLIED = "drawablesApplied"
        /** Targets never force-stopped after an overlay: the framework and SystemUI restart on their own terms. */
        private val NEVER_FORCE_STOP = setOf("android", "com.android.systemui")
        /**
         * App targets a force-stop cannot hurt (no alarms, timers or notifications to lose). Any
         * other target, such as Clock, Calendar, Reminder or Bluetooth, is only `am kill`ed.
         */
        private val SAFE_TO_FORCE_STOP = setOf("com.android.settings")
        const val KEY_CONTENT = "content"

        /**
         * `sh -c '<text>' <name> <args>`: runs a script from the text the caller holds rather
         * than from a file in the shell-owned state directory, which a uid 2000 process could
         * replace between the write and the run. [name] becomes `$0`, so a `/proc/<pid>/cmdline`
         * match on the script name (the stop commands) still finds it. [name] and [args] must
         * be plain tokens (letters, digits, `_`, `-`, `.`).
         */
        fun inlineScript(text: String, name: String, vararg args: String): String {
            val token = Regex("^[A-Za-z0-9_.-]+$")
            require(token.matches(name) && args.all { token.matches(it) }) { "unsafe script name or argument" }
            val quoted = "'" + text.replace("'", "'\\''") + "'"
            return (listOf("sh", "-c", quoted, name) + args).joinToString(" ")
        }

        const val KEY_UID = "uid"
        const val SHELL_OWNER = "com.android.shell"
        const val SYSTEM_OWNER = "android"

        /** The ADB shell uid Shizuku runs as without root (colours-only tier). */
        const val SHELL_UID = 2000

        /** 0755, 0777 and 0644 (Kotlin has no octal literals). */
        private const val MODE_DIR = 493
        private const val MODE_DIR_OPEN = 511
        private const val MODE_FILE = 420
        const val HEX_PREFIX = "HexNext"

        /** Name prefix of the value (size) overlays, [ValuePlanner.OVERLAY_PREFIX]. */
        const val VALUES_PREFIX = ValuePlanner.OVERLAY_PREFIX

        /** Read-only partitions whose APKs survive a per-user uninstall. */
        val SYSTEM_PARTITIONS = listOf("/system/", "/product/", "/system_ext/", "/vendor/")

        /** /data/overlays is where One UI keeps SemWT_* frros; AOSP keeps fabricated overlays in /data/resource-cache. */
        const val FRRO_SEARCH_DIRS = "/data/overlays /data/resource-cache"
        private const val MAX_DRAWABLE_BYTES = 32L * 1024 * 1024

        /** Where idmap2d keeps fabricated overlays and their idmaps. */
        private const val RESOURCE_CACHE = "/data/resource-cache"
        private const val RESOURCE_CACHE_LABEL = "u:object_r:resourcecache_data_file:s0"
        /** FabricatedOverlay.setResourceValue accepts only these (TypedValue.TYPE_FIRST_INT..TYPE_LAST_INT). */
        private const val FIRST_INT_TYPE = 0x10
        private const val LAST_INT_TYPE = 0x1f
        private const val TMP_SUFFIX = ".hexnext.tmp"

        /**
         * Seen on every .frro in /data/resource-cache on One UI 9 (SM-S948U1): written by idmap2d
         * as `system system 0644 resourcecache_data_file`. Only a fallback; the live file's stat wins.
         */
        private val DEFAULT_FRRO_META = FileMeta(1000, 1000, "644".toInt(8), RESOURCE_CACHE_LABEL)

        /** idmap header: magic, version, target crc, then the overlay crc (the .frro header crc). */
        private const val IDMAP_OVERLAY_CRC_OFFSET = 12L

        val DEVICE_PROPS = listOf(
            "ro.build.version.oneui",
            "ro.build.version.sem",
            "ro.build.version.sdk",
            "ro.build.version.release",
            "ro.product.model",
            "ro.build.display.id",
        )

        private val PACKAGE_NAME = Regex("^[A-Za-z0-9_.]+$")
        private val OVERLAY_NAME = Regex("^[A-Za-z0-9_.]+$")
        private val OVERLAY_ID = Regex("^[A-Za-z0-9_.]+(:[A-Za-z0-9_.]+)?$")
        private val RESOURCE_NAME = Regex("^([A-Za-z0-9_.]+:)?[A-Za-z0-9_]+/[A-Za-z0-9_.]+$")
        private val STATE_NAME = Regex("^[A-Za-z0-9_.-]+$")

        /** Printed by the su read of a state file that does not exist (exit 0, so a failed su is told apart). */
        private const val NO_FILE_MARKER = "__hexnext_no_such_file__"

        private val CONFIG_NAME = Regex("^[A-Za-z0-9_+-]+$")
        private val FRRO_PATH = Regex("""/data/resource-cache/[A-Za-z0-9_.-]+\.frro""")
        private val FRRO_SUFFIX = Regex("""^[A-Za-z0-9]+\.frro$""")

        /** `<owner>-<name>-<suffix>.frro` file name in /data/resource-cache; owner and name never contain `-`. */
        private val FRRO_FILE = Regex("""^([A-Za-z0-9_.]+)-([A-Za-z0-9_.]+)-[A-Za-z0-9]+\.frro$""")

        /** idmap of a fabricated overlay in /data/resource-cache: the `.frro` path with `/` -> `@`, plus `@idmap`. */
        private val HEX_IDMAP_FILE = Regex("""^data@resource-cache@([A-Za-z0-9_.]+)-([A-Za-z0-9_.]+)-[A-Za-z0-9]+\.frro@idmap$""")
        private val SELINUX_LABEL = Regex("""^u:[a-z_]+:[A-Za-z0-9_]+:s0(:[A-Za-z0-9,.]+)?$""")
        private val OVERLAY_LINE = Regex("""^(\[[^\]]\]|---)\s+(\S+)""")

        /**
         * Parses `cmd overlay list` output: an unprefixed line names the target package, then
         * `[x] id` (enabled), `[ ] id` (disabled) or `--- id` (not applicable) list its overlays.
         */
        fun parseOverlayList(text: String): List<OverlayLine> {
            var target = ""
            val out = ArrayList<OverlayLine>()
            text.lineSequence().forEach { raw ->
                val line = raw.trim()
                if (line.isEmpty()) return@forEach
                val match = OVERLAY_LINE.find(line)
                if (match != null) {
                    val state = match.groupValues[1]
                    out.add(OverlayLine(match.groupValues[2], target, state == "[x]", state))
                } else if (!line.contains(' ') && !line.startsWith("[")) {
                    target = line
                }
            }
            return out
        }

        fun result(code: Int, output: String) = Bundle().apply {
            putInt(KEY_CODE, code)
            putString(KEY_OUTPUT, output.trimEnd())
        }
    }
}

/** One overlay row of `cmd overlay list`. */
data class OverlayLine(
    val overlayId: String,
    val target: String,
    val enabled: Boolean,
    val state: String,
) {
    /** The name part of `owner:name` fabricated ids, or the package name of an APK overlay. */
    val name: String get() = overlayId.substringAfter(':')
    val isSamsungWallpaperTheme: Boolean get() = overlayId.contains("SemWT")
}
