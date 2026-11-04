package com.hexnext.themer.theme

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.hexnext.themer.ServiceTasks
import com.hexnext.themer.safety.ApplyGuard
import com.hexnext.themer.safety.OtherBuild
import com.hexnext.themer.shizuku.ExecOutput
import com.hexnext.themer.shizuku.ShizukuManager
import com.hexnext.themer.shizuku.ShizukuState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import com.hexnext.engine.compile.ThemeSpec as EngineThemeSpec

/**
 * Puts the theme back when Android has dropped its overlays.
 *
 * A framework restart (soft reboot) and a reboot both deregister and delete Hex Next's
 * fabricated overlays (device lab, lab-007/010: 59 overlays and their files gone, Samsung's own
 * kept). Many users only have root again once KernelSU is reapplied, so the theme cannot be
 * kept alive across a reboot; it has to come back by itself as soon as root does.
 *
 * Every successful apply saves the exact theme it applied ([APPLIED_FILE]); a remove deletes it.
 * When root through Shizuku becomes available (the app's process is started by Shizuku handing
 * out its binder, so this runs without opening Hex Next) and the theme is meant to be on but no
 * Hex Next overlay is registered, the saved theme is applied again in the background. One try
 * per boot, never in safe mode, never while another task holds the service.
 */
object ThemeRestorer {
    private const val TAG = "HexNextRestore"
    const val APPLIED_FILE = "applied-theme.json"
    private const val PREFS = "hexnext_restore"
    private const val KEY_FAILURES = "failures"

    /** Restores that may fail in a row before Hex Next waits for a manual apply. */
    private const val MAX_FAILURES = 2
    private const val TASK = "Restoring your theme"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var watching = false

    fun appliedFile(context: Context): File = File(context.applicationContext.filesDir, APPLIED_FILE)

    /** After a successful apply: the theme to bring back after a reboot. */
    fun rememberApplied(context: Context, spec: EngineThemeSpec) {
        runCatching {
            val f = appliedFile(context)
            val tmp = File(f.parentFile, "${f.name}.part")
            tmp.writeText(spec.toJson())
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_FAILURES).apply()
        }.onFailure { Log.w(TAG, "Could not save the applied theme", it) }
    }

    /** After a successful remove: nothing to bring back. */
    fun forget(context: Context) {
        appliedFile(context).delete()
    }

    /** Starts watching for root through Shizuku (idempotent); call from Application.onCreate. */
    fun watch(context: Context) {
        if (watching) return
        watching = true
        val app = context.applicationContext
        scope.launch {
            // Nothing here may take the app down: a restore that throws is only logged.
            runCatching {
                ShizukuManager.state.filter { it is ShizukuState.Ready && Capability.of(it) == Capability.ROOT }.first()
                restoreIfNeeded(app)
            }.onFailure { Log.w(TAG, "Restore failed", it) }
        }
    }

    private fun bootCount(context: Context): Int =
        runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT) }.getOrDefault(-1)

    /** Runs the checks and, when the theme is meant to be on but its overlays are gone, re-applies it. */
    suspend fun restoreIfNeeded(app: Context) {
        val file = appliedFile(app)
        if (!file.isFile) return
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // [watch] runs once per process, and a framework restart starts a new one: one try each.
        // Two failed restores in a row wait for the user to apply by hand.
        if (prefs.getInt(KEY_FAILURES, 0) >= MAX_FAILURES) return
        val boot = bootCount(app)
        val guard = ApplyGuard(app)
        if (guard.safeModeActive()) return
        if (OtherBuild.isInstalled(app)) return
        // The app's own start-up check may hold the service for a moment: wait for it.
        if (!ServiceTasks.startBlocking(TASK, 120_000)) return
        try {
            val service = ShizukuManager.service()
            val list = ExecOutput.fullText(service, service.exec("cmd overlay list --user 0"))
            val ours = list.lines().count { it.contains(":HexNext_") }
            if (ours > 0) return
            val spec = runCatching { EngineThemeSpec.fromJson(file.readText()) }.getOrNull() ?: return
            Log.i(TAG, "Theme overlays are gone (boot $boot); applying \"${spec.name}\" again")
            val report = ThemeApplier(app).apply(service, spec) { line -> Log.i(TAG, line.text) }
            Log.i(TAG, "Restore finished: success=${report.success}, ${report.okCount} apps")
            prefs.edit().putInt(KEY_FAILURES, if (report.success) 0 else prefs.getInt(KEY_FAILURES, 0) + 1).apply()
            RestoreNotice.post(app, report.success, spec.name)
        } finally {
            ServiceTasks.finish()
        }
    }
}
