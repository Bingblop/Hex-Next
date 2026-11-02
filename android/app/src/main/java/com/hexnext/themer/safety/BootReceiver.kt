package com.hexnext.themer.safety

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.hexnext.themer.ServiceTasks
import com.hexnext.themer.shizuku.ThemeService
import com.hexnext.themer.theme.FontInstaller
import com.hexnext.themer.theme.SemWtController
import com.hexnext.themer.theme.ThemeApplier
import java.io.File

/**
 * Records each boot id for [ApplyGuard]. When the guard says the device restarted twice within
 * 10 minutes of an unconfirmed apply, it also tries to turn Hex overlays off right away through
 * `su -c` (an in-process [ThemeService]), removes the Hex Next font ([FontInstaller.remove]) and
 * gives the targets back to Samsung's SemWT overlays; otherwise the app does it on next start.
 *
 * On a normal boot with a compiled theme applied, it disables the SemWT overlays Samsung's
 * wallpaper-theme service re-enabled during boot, re-raises the Hex overlays and restarts the
 * SemWT re-assert guard ([SemWtController.reassert]).
 *
 * The service work holds the [ServiceTasks] slot, like an editor or Diagnostics task, so an Apply
 * or Remove started right after unlock never interleaves with it. Guard and takeover state are
 * read again once the slot is held: the task that held it before may have changed both.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_BOOT_COMPLETED
        ) return
        val lockedBoot = intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED
        val appContext = context.applicationContext
        val guard = ApplyGuard(context)
        guard.noteBoot()
        if (!needsService(guard, lockedBoot, appContext)) return
        if (SU_PATHS.none { File(it).exists() }) return

        val pending = goAsync()
        Thread({
            try {
                if (!ServiceTasks.startBlocking(BOOT_TASK, SLOT_WAIT_MS)) {
                    // The app is running a task of its own; its start-up checks cover this boot.
                    Log.i(TAG, "Boot handling skipped: ${ServiceTasks.running.value ?: "another task"} holds the service")
                    return@Thread
                }
                try {
                    handleBoot(appContext, lockedBoot)
                } finally {
                    ServiceTasks.finish()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Boot handling failed", t)
            } finally {
                pending.finish()
            }
        }, "hexnext-boot").start()
    }

    /** Runs holding the [ServiceTasks] slot; reads guard and takeover state afresh. */
    private fun handleBoot(appContext: Context, lockedBoot: Boolean) {
        val guard = ApplyGuard(appContext)
        if (!needsService(guard, lockedBoot, appContext)) return
        val guardSafeMode = guard.shouldEnterSafeMode()
        val mayHaveEarlyMarker = mayHaveEarlyMarker(guard)
        val takeover = !lockedBoot && ThemeApplier.isTakeoverActive(appContext)
        val service = ThemeService()
        val safeMode = guardSafeMode ||
            (mayHaveEarlyMarker && EarlyRecoveryHook.wasTriggeredThisBoot(service))
        if (!safeMode && (!takeover || guard.safeModeActive())) return
        if (safeMode) {
            val result = service.disableHexOverlays()
            // The font may be what kept the phone restarting: take it out before safe mode
            // confirms the guard, or it stays mounted and comes back at every boot.
            runCatching { FontInstaller(service).remove { Log.i(TAG, it.text) } }
                .onFailure { Log.w(TAG, "Safe mode could not remove the Hex Next font", it) }
            if (!lockedBoot && result.getInt(ThemeService.KEY_CODE, -1) == 0) {
                guard.markSafeModeEntered(by = "boot receiver")
            }
            Log.i(TAG, "Safe mode at boot: ${result.getString(ThemeService.KEY_OUTPUT)}")
            if (takeover) {
                SemWtController.restore(service) { Log.i(TAG, it.text) }
                ThemeApplier.setTakeoverActive(appContext, false)
            }
        } else {
            val fixed = SemWtController.reassert(service, { Log.i(TAG, it.text) })
            Log.i(TAG, "SemWT re-assert at boot: $fixed commands")
        }
    }

    /** True when this boot may need the root service: safe mode, an early-hook marker or a takeover. */
    private fun needsService(guard: ApplyGuard, lockedBoot: Boolean, appContext: Context): Boolean {
        if (guard.shouldEnterSafeMode() || mayHaveEarlyMarker(guard)) return true
        val takeover = !lockedBoot && ThemeApplier.isTakeoverActive(appContext)
        return takeover && !guard.safeModeActive()
    }

    /** The early root hook may have disabled the overlays already; its marker is read as root. */
    private fun mayHaveEarlyMarker(guard: ApplyGuard): Boolean =
        !guard.confirmed && guard.appliedAt > 0L && !guard.safeModeActive()

    private companion object {
        const val TAG = "HexNextBoot"
        const val BOOT_TASK = "Boot check"

        /** Well inside the 60 s a background broadcast may take; a running Apply can take longer. */
        const val SLOT_WAIT_MS = 20_000L
        val SU_PATHS = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/debug_ramdisk/su")
    }
}
