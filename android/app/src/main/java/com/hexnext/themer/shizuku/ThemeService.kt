package com.hexnext.themer.shizuku

import android.content.Context
import android.content.om.FabricatedOverlay
import android.content.om.OverlayManagerTransaction
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.util.TypedValue
import androidx.annotation.Keep
import com.hexnext.themer.IThemeService
import org.lsposed.hiddenapibypass.HiddenApiBypass
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

    override fun exec(command: String): Bundle = try {
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
                val fab = exec("cmd overlay fabricate --target $targetPackage --name $name $args")
                if (fab.getInt(KEY_CODE) != 0) return fab
            }

            // Enable and raise priority to highest on top of Samsung's System RROs
            val enable = exec(
                "cmd overlay enable --user current $id && " +
                    "cmd overlay set-priority --user current $id highest"
            )
            val code = enable.getInt(KEY_CODE)
            result(
                code,
                "Registered $id (${resourceNames.size} One UI 9.0 tokens) on $targetPackage [UID ${Process.myUid()}]" +
                    if (code == 0) ", enabled at highest priority." else ".\nEnable failed: ${enable.getString(KEY_OUTPUT)}"
            )
        } catch (t: Throwable) {
            result(-1, describe(t))
        }
    }

    override fun removeOverlay(name: String): Bundle {
        return try {
            val owner = activeOwnerPackage()
            val id = "$owner:$name"
            exec("cmd overlay disable --user current $id")
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
                exec(
                    "cmd overlay disable --user current $id 2>/dev/null; " +
                        "rm -f /data/resource-cache/*${name}*.idmap /data/resource-cache/*${name}*.frro"
                )

                // Step 2: Re-register fabricated overlay or re-fabricate via shell
                if (isPrivilegedUid()) {
                    val tx = OverlayManagerTransaction.newInstance()
                    tx.registerFabricatedOverlay(newOverlay(name, target, owner))
                    commit(tx)
                } else {
                    exec("cmd overlay fabricate --target $target --name $name")
                }

                // Step 3: Re-enable and elevate priority above Samsung SemWT_*
                val enableRes = exec(
                    "cmd overlay enable --user current $id && " +
                        "cmd overlay set-priority --user current $id highest"
                )
                val code = enableRes.getInt(KEY_CODE)
                logs.appendLine(
                    "$seqLabel [UID ${Process.myUid()}] Cleared idmap2 cache & rebuilt $id -> $target " +
                        if (code == 0) "(STATE_ENABLED, priority=highest > SemWT_*)"
                        else "(FAILED: ${enableRes.getString(KEY_OUTPUT)})"
                )
            }

            result(0, logs.toString().trimEnd())
        } catch (t: Throwable) {
            result(-1, describe(t))
        }
    }

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

    private fun describe(t: Throwable): String {
        val cause = if (t is InvocationTargetException) t.targetException ?: t else t
        return "${cause.javaClass.simpleName}: ${cause.message}"
    }

    companion object {
        const val KEY_CODE = "code"
        const val KEY_OUTPUT = "output"
        const val SHELL_OWNER = "com.android.shell"
        const val SYSTEM_OWNER = "android"

        fun result(code: Int, output: String) = Bundle().apply {
            putInt(KEY_CODE, code)
            putString(KEY_OUTPUT, output.trimEnd())
        }
    }
}
