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
import com.hexnext.themer.IThemeService
import org.json.JSONArray
import org.json.JSONObject
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
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
                // Fallback via SU shell command fabrication
                val args = resourceNames.indices.joinToString(" ") { i ->
                    "${resourceNames[i]} 0x1c 0x${Integer.toUnsignedString(colors[i], 16)}"
                }
                val fab = shell("cmd overlay fabricate --target $targetPackage --name $name $args")
                if (fab.getInt(KEY_CODE) != 0) return fab
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

    private class Outcome(val resourceCount: Int, val drawablesApplied: Int, val skipped: JSONArray)

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
                            ", so FabricatedOverlay cannot be committed directly. Start Shizuku with root and retry; " +
                            "colour-only requests still work through `cmd overlay fabricate`."
                    ),
                    0,
                    "",
                )
            }

            val owner = activeOwnerPackage()
            val id = "$owner:$name"
            val log = StringBuilder()
            log.appendLine("[UID ${Process.myUid()}] $id -> $target: ${colorList.size} colours, ${drawables.size} drawables")

            var outcome: Outcome? = null
            var failure: Throwable? = null
            var partial = false

            try {
                outcome = buildAndCommit(name, target, owner, colorList, drawables, log)
                log.appendLine("Committed with the caller's file descriptors.")
            } catch (t: Throwable) {
                failure = t
                log.appendLine("Commit with caller descriptors failed: ${describe(t)}")
            }

            if (outcome == null && drawables.isNotEmpty()) {
                val staged = stageDrawables(name, drawables, log)
                if (staged != null) {
                    try {
                        outcome = buildAndCommit(name, target, owner, colorList, staged, log)
                        log.appendLine("Committed with PNGs re-staged under ${MonitorScript.DIR}/stage.")
                    } catch (t: Throwable) {
                        failure = t
                        log.appendLine("Commit with staged PNGs failed: ${describe(t)}")
                    } finally {
                        staged.forEach { runCatching { it.fd.close() } }
                    }
                }
            }

            if (outcome == null && drawables.isNotEmpty() && colorList.isNotEmpty()) {
                try {
                    val colourOnly = buildAndCommit(name, target, owner, colorList, emptyList(), log)
                    val reason = "drawable commit failed: ${describe(failure)}"
                    drawables.forEach { skip(colourOnly.skipped, it.name, reason) }
                    outcome = colourOnly
                    partial = true
                    log.appendLine("Committed colours only; every drawable was dropped.")
                } catch (t: Throwable) {
                    failure = t
                    log.appendLine("Colour-only commit failed too: ${describe(t)}")
                }
            }

            val done = outcome ?: return finish(result(-1, log.toString() + describe(failure)), 0, "")
            for (i in 0 until done.skipped.length()) skipped.put(done.skipped.get(i))
            if (drawables.isNotEmpty() && done.drawablesApplied == 0 && !partial) {
                log.appendLine("No drawable was applied (see skipped).")
            }

            val top = enableOnTop(id, name, target, owner)
            val enableCode = top.code
            if (enableCode == 0) {
                log.appendLine("Registered ${done.resourceCount} resources, enabled.")
                log.appendLine(top.log)
            } else {
                log.appendLine("Enable failed: ${top.log}")
            }
            val code = when {
                enableCode != 0 -> enableCode
                partial -> 3
                else -> 0
            }
            return finish(result(code, log.toString()), done.resourceCount, lookup(target, firstResource))
        } catch (t: Throwable) {
            return finish(result(-1, describe(t)), 0, "")
        } finally {
            fds.forEach { fd -> runCatching { fd?.close() } }
        }
    }

    /** Builds one FabricatedOverlay with every colour and drawable, then registers it. Throws on commit failure. */
    private fun buildAndCommit(
        name: String,
        target: String,
        owner: String,
        colors: List<ColorInput>,
        drawables: List<DrawableInput>,
        log: StringBuilder,
    ): Outcome {
        val skipped = JSONArray()
        val pfdType = ParcelFileDescriptor::class.java
        val stringType = String::class.java
        val overlayClass = FabricatedOverlay::class.java
        val directPng = findMethod(overlayClass, "setResourceValue", stringType, pfdType, stringType)
        val directNine = findMethod(overlayClass, "setNinePatchResourceValue", stringType, pfdType, stringType)

        var count = 0
        var drawn = 0
        val overlay: FabricatedOverlay

        val viaBuilder = if (drawables.isNotEmpty() && directPng == null && directNine == null) {
            log.appendLine("FabricatedOverlay has no PFD setters; trying FabricatedOverlay.Builder.")
            buildWithBuilder(name, target, owner, colors, drawables, skipped, log)
        } else {
            null
        }

        if (viaBuilder != null) {
            overlay = viaBuilder.overlay
            count = viaBuilder.count
            drawn = viaBuilder.drawn
        } else {
            overlay = newOverlay(name, target, owner)
            colors.forEach { c ->
                try {
                    overlay.setResourceValue(c.name, TypedValue.TYPE_INT_COLOR_ARGB8, c.argb, c.config)
                    count++
                } catch (t: Throwable) {
                    skip(skipped, c.name, describe(t))
                }
            }
            if (drawables.isNotEmpty() && directPng == null && directNine == null) {
                drawables.forEach {
                    skip(skipped, it.name, "this Android build has no FabricatedOverlay drawable API (setResourceValue(String, ParcelFileDescriptor, String))")
                }
                log.appendLine("Skipped ${drawables.size} drawables: no FabricatedOverlay drawable API on this build.")
            } else {
                drawables.forEach { d ->
                    val setter = pickDrawableSetter(d, directPng, directNine, log)
                    if (setter == null) {
                        skip(skipped, d.name, "no matching drawable setter")
                        return@forEach
                    }
                    try {
                        setter.invoke(overlay, d.name, d.fd, d.config)
                        count++
                        drawn++
                    } catch (t: Throwable) {
                        skip(skipped, d.name, describe(t))
                    }
                }
            }
        }

        val register = OverlayManagerTransaction.newInstance()
        register.registerFabricatedOverlay(overlay)
        commit(register)
        return Outcome(count, drawn, skipped)
    }

    private fun pickDrawableSetter(d: DrawableInput, png: Method?, nine: Method?, log: StringBuilder): Method? {
        if (!d.ninePatch) return png
        if (nine != null) return nine
        if (png != null) log.appendLine("${d.name}: setNinePatchResourceValue is missing, applied as a plain PNG.")
        return png
    }

    private class BuilderResult(val overlay: FabricatedOverlay, val count: Int, val drawn: Int)

    /** Hidden `FabricatedOverlay.Builder(owner, name, target)` path, only used when the direct setters are missing. */
    private fun buildWithBuilder(
        name: String,
        target: String,
        owner: String,
        colors: List<ColorInput>,
        drawables: List<DrawableInput>,
        skipped: JSONArray,
        log: StringBuilder,
    ): BuilderResult? {
        return try {
            val builderClass = Class.forName("android.content.om.FabricatedOverlay\$Builder")
            val stringType = String::class.java
            val intType = Integer.TYPE
            val pfdType = ParcelFileDescriptor::class.java
            val builder = builderClass.getConstructor(stringType, stringType, stringType).newInstance(owner, name, target)
            val color4 = findMethod(builderClass, "setResourceValue", stringType, intType, intType, stringType)
            val color3 = findMethod(builderClass, "setResourceValue", stringType, intType, intType)
            val png = findMethod(builderClass, "setResourceValue", stringType, pfdType, stringType)
            val nine = findMethod(builderClass, "setNinePatchResourceValue", stringType, pfdType, stringType)
            if (png == null && nine == null) {
                log.appendLine("FabricatedOverlay.Builder has no PFD setters either.")
                return null
            }
            var count = 0
            var drawn = 0
            colors.forEach { c ->
                try {
                    when {
                        color4 != null -> color4.invoke(builder, c.name, TypedValue.TYPE_INT_COLOR_ARGB8, c.argb, c.config)
                        color3 != null && c.config == null -> color3.invoke(builder, c.name, TypedValue.TYPE_INT_COLOR_ARGB8, c.argb)
                        color3 != null -> error("Builder has no configured colour setter")
                        else -> error("Builder has no colour setter")
                    }
                    count++
                } catch (t: Throwable) {
                    skip(skipped, c.name, describe(t))
                }
            }
            drawables.forEach { d ->
                val setter = pickDrawableSetter(d, png, nine, log)
                if (setter == null) {
                    skip(skipped, d.name, "no matching Builder drawable setter")
                    return@forEach
                }
                try {
                    setter.invoke(builder, d.name, d.fd, d.config)
                    count++
                    drawn++
                } catch (t: Throwable) {
                    skip(skipped, d.name, describe(t))
                }
            }
            val overlay = builderClass.getMethod("build").invoke(builder) as FabricatedOverlay
            log.appendLine("Built through FabricatedOverlay.Builder.")
            BuilderResult(overlay, count, drawn)
        } catch (t: Throwable) {
            log.appendLine("FabricatedOverlay.Builder unavailable: ${describe(t)}")
            null
        }
    }

    /** Copies each PNG into a root-owned, world-readable file and reopens it (retry for SELinux fd denials). */
    private fun stageDrawables(name: String, drawables: List<DrawableInput>, log: StringBuilder): List<DrawableInput>? {
        val opened = ArrayList<DrawableInput>()
        return try {
            val dir = File(MonitorScript.DIR, "stage")
            if (!dir.isDirectory && !dir.mkdirs()) error("cannot create ${dir.path}")
            dir.setReadable(true, false)
            dir.setExecutable(true, false)
            drawables.forEachIndexed { i, d ->
                val file = File(dir, "${name}_$i.png")
                file.writeBytes(readAll(d.fd))
                file.setReadable(true, false)
                opened.add(d.copy(fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)))
            }
            opened
        } catch (t: Throwable) {
            opened.forEach { runCatching { it.fd.close() } }
            log.appendLine("Could not stage PNGs: ${describe(t)}")
            null
        }
    }

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

    private fun findMethod(owner: Class<*>, name: String, vararg types: Class<*>): Method? =
        owner.methods.firstOrNull { it.name == name && it.parameterTypes.contentEquals(types) }

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
