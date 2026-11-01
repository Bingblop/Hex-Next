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
import android.system.Os
import android.util.TypedValue
import androidx.annotation.Keep
import com.hexnext.engine.device.OverlayList
import com.hexnext.engine.device.RaiseNeed
import com.hexnext.engine.device.SemWtPlanner
import com.hexnext.engine.frro.FrroBinary
import com.hexnext.engine.frro.FrroColor
import com.hexnext.engine.frro.FrroFormat
import com.hexnext.engine.frro.FrroReader
import com.hexnext.engine.frro.FrroSpec
import com.hexnext.engine.frro.FrroWriter
import com.hexnext.themer.IThemeService
import org.json.JSONArray
import org.json.JSONObject
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.lang.reflect.InvocationTargetException
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

    /** Runs [command] as the service uid (or through `su`) and returns the whole output. */
    private fun shell(command: String): Bundle = try {
        val cmdArray = if (Process.myUid() == 0 || Process.myUid() == 1000) {
            arrayOf("sh", "-c", command)
        } else {
            arrayOf("su", "-c", command)
        }
        val process = ProcessBuilder(*cmdArray)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            result(124, output + "\n(timed out after 60s)")
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
            result(
                top.code,
                "Registered $id (${resourceNames.size} One UI 9.0 tokens) on $targetPackage [UID ${Process.myUid()}]" +
                    if (top.code == 0) ", enabled.\n${top.log}" else ".\nEnable failed: ${top.log}"
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
            result(0, "Removed $id")
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

                // Step 1: Disable stale overlay & purge idmap2/frro cache files under UID 0 / Shizuku
                shell(
                    "cmd overlay disable --user current $id 2>/dev/null; " +
                        "rm -f /data/resource-cache/*${name}*.idmap /data/resource-cache/*${name}*.frro"
                )

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

            // 1. Disable all overlays for the current user and purge any corrupted HexNext idmap2/frro files
            val disableRes = shell(
                "cmd overlay disable-all --user current 2>/dev/null; " +
                    "rm -f /data/resource-cache/*HexNext*.idmap /data/resource-cache/*HexNext*.frro " +
                    "/data/resource-cache/*hexnext*.idmap /data/resource-cache/*hexnext*.frro"
            )
            logs.appendLine("[✓] Disabled active RROs & purged /data/resource-cache/ (code=${disableRes.getInt(KEY_CODE)})")
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
            if (isPrivilegedUid()) {
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

    private class DrawableOutcome(val enable: EnableResult, val applied: Int)

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
                // restarts everything anyway; for app targets drop the process so the new PNGs show.
                if (drawn.applied > 0 && target !in NEVER_FORCE_STOP) {
                    val stop = shell("am force-stop --user current $target")
                    log.appendLine("Restarted $target so it reloads its drawables (am force-stop exit ${stop.getInt(KEY_CODE)}).")
                }
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
            return DrawableOutcome(restoreColourOnly(id, name, target, owner, rewrite, reason, skipped, log), 0)
        }

        // Verification: idmap regenerated, file parses with every PNG, lookup resolves to frro:/.
        val idmap = idmapPathFor(rewrite.path)
        val idmapFile = File(idmap)
        if (!idmapFile.isFile) {
            val reason = "OverlayManager did not create $idmap after the rewrite"
            return DrawableOutcome(restoreColourOnly(id, name, target, owner, rewrite, reason, skipped, log), 0)
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
            return DrawableOutcome(restoreColourOnly(id, name, target, owner, rewrite, reason, skipped, log), 0)
        }
        val onDisk = File(rewrite.path).readBytes()
        log.appendLine(
            "Re-read ${rewrite.path}: ${reread.entries.size} entries, " +
                "${reread.entries.count { it.binaryOffset != null }} file values, crc ok=${reread.crcMatchesProto}"
        )

        var applied = 0
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
                !inFile -> skip(skipped, p.input.name, "payload missing or different in the rewritten .frro")
                idmapFresh || resolved -> applied++
                else -> skip(
                    skipped, p.input.name,
                    "written to ${rewrite.path} but the idmap still has the old CRC and lookup returned: ${value.ifEmpty { "(empty)" }}"
                )
            }
        }
        log.appendLine("Root rewrite applied $applied/${drawables.size} drawables.")
        return DrawableOutcome(top, applied)
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
        val foreign = current.entries.filter { it.dataType != FrroFormat.TYPE_INT_COLOR_ARGB8 }
        check(foreign.isEmpty()) { "$path holds ${foreign.size} non-colour values (${foreign.first().resourceName})" }
        if (current.entries.size != colors.size) {
            log.appendLine("Note: the registered file holds ${current.entries.size} colours, ${colors.size} were committed.")
        }

        // Colours exactly as OverlayManager wrote them, plus the PNG payloads.
        val spec = FrroSpec(
            ownerPackage = owner,
            overlayName = name,
            targetPackage = target,
            targetOverlayable = current.targetOverlayable,
            colors = current.entries.map { FrroColor(it.resourceName, it.data, it.configuration) },
            binaries = pngs.map { FrroBinary(it.resource, it.bytes, false, it.input.config) },
        )
        val bytes = FrroWriter.write(spec, path)
        val built = FrroReader.read(bytes)
        check(built.crcMatchesProto && built.entries.count { it.binaryOffset != null } == pngs.size) {
            "FrroWriter output failed its own read-back"
        }
        log.appendLine(
            "Built ${bytes.size} bytes: ${spec.colors.size} colours + ${pngs.size} PNGs " +
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
            result(
                if (failures == 0) 0 else 1,
                "Acted on ${hex.size} HexNext overlays (${hex.size - failures} ok) [UID ${Process.myUid()}]" +
                    if (ids.isNotEmpty()) "\n$ids" else ""
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

    override fun startStabilityMonitor(seconds: Int): Bundle {
        return try {
            val window = seconds.coerceIn(10, 1800)
            // A previous monitor gets TERM; give it a second to write its final status first.
            val previous = stopMonitorProcess()
            val settle = if (previous.startsWith("Stopped")) "sleep 1; " else ""
            writeRootFile(File(MonitorScript.PATH), MonitorScript.text)
            val script = MonitorScript.PATH
            val launch = shell(
                settle + "chmod 755 $script; rm -f ${MonitorScript.SAFE_MODE_FILE}; " +
                    "if command -v setsid >/dev/null 2>&1; then " +
                    "nohup setsid sh $script $window >/dev/null 2>&1 & " +
                    "else nohup sh $script $window >/dev/null 2>&1 & fi"
            )
            if (launch.getInt(KEY_CODE) != 0) {
                return result(launch.getInt(KEY_CODE), "Could not start the monitor: ${launch.getString(KEY_OUTPUT)}")
            }
            val pid = shell("sleep 1; cat ${MonitorScript.PID_FILE} 2>/dev/null").getString(KEY_OUTPUT).orEmpty().trim()
            val pidValue = pid.toIntOrNull()
            if (pidValue == null) {
                result(1, "Monitor launched but ${MonitorScript.PID_FILE} is missing: $pid")
            } else {
                result(0, "Stability monitor pid $pidValue watching system_server + SystemUI for $window s").apply {
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

    /** Writes a world-readable file as root: directly when privileged, else through `su -c cat >`. */
    private fun writeRootFile(file: File, content: String) {
        if (isPrivilegedUid()) {
            val dir = file.parentFile ?: error("no parent for ${file.path}")
            if (!dir.isDirectory && !dir.mkdirs()) error("Cannot create ${dir.path}")
            dir.setReadable(true, false)
            dir.setExecutable(true, false)
            val tmp = File(dir, file.name + ".tmp")
            tmp.writeText(content)
            tmp.setReadable(true, false)
            if (!tmp.renameTo(file)) {
                file.writeText(content)
                tmp.delete()
            }
            file.setReadable(true, false)
            return
        }
        val dir = file.parent ?: error("no parent for ${file.path}")
        val process = ProcessBuilder(
            "su", "-c",
            "mkdir -p '$dir' && chmod 755 '$dir' && cat > '${file.path}' && chmod 644 '${file.path}'"
        ).redirectErrorStream(true).start()
        process.outputStream.use { it.write(content.toByteArray()) }
        val out = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("su write of ${file.path} timed out")
        }
        if (process.exitValue() != 0) error("su write of ${file.path} failed (${process.exitValue()}): $out")
    }

    private fun readRootFile(file: File): String? {
        if (isPrivilegedUid()) {
            return if (file.isFile) file.readText() else null
        }
        val r = shell("[ -f '${file.path}' ] && cat '${file.path}'")
        return if (r.getInt(KEY_CODE) == 0) r.getString(KEY_OUTPUT).orEmpty() else null
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
        const val KEY_OUTPUT = "output"
        const val KEY_OUTPUT_PATH = "outputPath"
        const val KEY_OUTPUT_LENGTH = "outputLength"

        /** Characters of exec output returned inline; ~400 KB as UTF-16 in the parcel. */
        const val EXEC_INLINE_LIMIT = 200_000
        private const val EXEC_HEAD_CHARS = 4_000
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
        const val KEY_CONTENT = "content"
        const val KEY_UID = "uid"
        const val SHELL_OWNER = "com.android.shell"
        const val SYSTEM_OWNER = "android"
        const val HEX_PREFIX = "HexNext"

        /** Read-only partitions whose APKs survive a per-user uninstall. */
        val SYSTEM_PARTITIONS = listOf("/system/", "/product/", "/system_ext/", "/vendor/")

        /** /data/overlays is where One UI keeps SemWT_* frros; AOSP keeps fabricated overlays in /data/resource-cache. */
        const val FRRO_SEARCH_DIRS = "/data/overlays /data/resource-cache"
        private const val MAX_DRAWABLE_BYTES = 32L * 1024 * 1024

        /** Where idmap2d keeps fabricated overlays and their idmaps. */
        private const val RESOURCE_CACHE = "/data/resource-cache"
        private const val RESOURCE_CACHE_LABEL = "u:object_r:resourcecache_data_file:s0"
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
        private val CONFIG_NAME = Regex("^[A-Za-z0-9_+-]+$")
        private val FRRO_PATH = Regex("""/data/resource-cache/[A-Za-z0-9_.-]+\.frro""")
        private val FRRO_SUFFIX = Regex("""^[A-Za-z0-9]+\.frro$""")
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
