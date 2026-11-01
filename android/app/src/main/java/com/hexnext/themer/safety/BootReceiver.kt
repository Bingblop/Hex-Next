package com.hexnext.themer.safety

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.hexnext.themer.shizuku.ThemeService
import java.io.File

/**
 * Records each boot id for [ApplyGuard]. When the guard says the device restarted twice within
 * 10 minutes of an unconfirmed apply, it also tries to turn Hex overlays off right away through
 * `su -c` (an in-process [ThemeService]); otherwise the app does it on next start.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val guard = ApplyGuard(context)
        guard.noteBoot()
        if (!guard.shouldEnterSafeMode()) return
        if (SU_PATHS.none { File(it).exists() }) return

        val pending = goAsync()
        Thread({
            try {
                val result = ThemeService().disableHexOverlays()
                if (result.getInt(ThemeService.KEY_CODE, -1) == 0) {
                    guard.markSafeModeEntered(by = "boot receiver")
                }
                Log.i(TAG, "Safe mode at boot: ${result.getString(ThemeService.KEY_OUTPUT)}")
            } catch (t: Throwable) {
                Log.w(TAG, "Safe mode at boot failed", t)
            } finally {
                pending.finish()
            }
        }, "hexnext-safe-mode").start()
    }

    private companion object {
        const val TAG = "HexNextBoot"
        val SU_PATHS = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/debug_ramdisk/su")
    }
}
