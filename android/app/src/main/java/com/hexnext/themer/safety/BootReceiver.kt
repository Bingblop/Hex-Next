package com.hexnext.themer.safety

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.hexnext.themer.shizuku.ThemeService
import com.hexnext.themer.theme.SemWtController
import com.hexnext.themer.theme.ThemeApplier
import java.io.File

/**
 * Records each boot id for [ApplyGuard]. When the guard says the device restarted twice within
 * 10 minutes of an unconfirmed apply, it also tries to turn Hex overlays off right away through
 * `su -c` (an in-process [ThemeService]) and gives the targets back to Samsung's SemWT overlays;
 * otherwise the app does it on next start.
 *
 * On a normal boot with a compiled theme applied, it disables the SemWT overlays Samsung's
 * wallpaper-theme service re-enabled during boot, re-raises the Hex overlays and restarts the
 * SemWT re-assert guard ([SemWtController.reassert]).
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val guard = ApplyGuard(context)
        guard.noteBoot()
        val safeMode = guard.shouldEnterSafeMode()
        val takeover = ThemeApplier.isTakeoverActive(context)
        if (!safeMode && (!takeover || guard.safeModeActive())) return
        if (SU_PATHS.none { File(it).exists() }) return

        val appContext = context.applicationContext
        val pending = goAsync()
        Thread({
            try {
                val service = ThemeService()
                if (safeMode) {
                    val result = service.disableHexOverlays()
                    if (result.getInt(ThemeService.KEY_CODE, -1) == 0) {
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
            } catch (t: Throwable) {
                Log.w(TAG, "Boot handling failed", t)
            } finally {
                pending.finish()
            }
        }, "hexnext-boot").start()
    }

    private companion object {
        const val TAG = "HexNextBoot"
        val SU_PATHS = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/debug_ramdisk/su")
    }
}
