package com.hexnext.themer

import android.app.Application
import android.util.Log
import com.hexnext.themer.shizuku.ShizukuManager
import com.hexnext.themer.theme.ThemeRestorer
import java.io.File

/**
 * Starts the Shizuku watch with the process, not only with the main screen: Shizuku starts the
 * app's process when its server comes up (it hands every client its binder), so a theme Android
 * dropped on a restart comes back without opening Hex Next ([ThemeRestorer]).
 *
 * Also keeps the last crash in files/last-crash.txt (the device lab collects it), and never lets
 * the background watch take the app down when it opens.
 */
class HexNextApp : Application() {
    override fun onCreate() {
        super.onCreate()
        recordCrashes()
        runCatching {
            ShizukuManager.init()
            ThemeRestorer.watch(this)
        }.onFailure { Log.w("HexNextApp", "Restore watch did not start", it) }
    }

    private fun recordCrashes() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val file = File(filesDir, CRASH_FILE)
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                file.writeText(
                    "time=${System.currentTimeMillis()} thread=${thread.name} version=${BuildConfig.VERSION_NAME}\n" +
                        Log.getStackTraceString(error),
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }

    companion object {
        const val CRASH_FILE = "last-crash.txt"
    }
}
