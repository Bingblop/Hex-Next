package com.hexnext.themer.shizuku

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import androidx.annotation.Keep
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import kotlin.system.exitProcess

/**
 * Hex Next's own system (UID 1000) theme service, for phones with root and no Shizuku.
 *
 * The app starts it through su (`su 1000 -c app_process … SystemServer <package> <token> <pid>`,
 * see [SystemLauncher]); it runs [ThemeService] in that process and hands its binder to the app
 * in a broadcast only the app can receive (a non-exported receiver: only the system uid, root and
 * the app itself may send to it) carrying the one-time token the app chose. Overlays it makes are
 * owned by `android`, which One UI keeps across soft and full reboots, where the shell-owned ones
 * root or Shizuku make are dropped at boot (device lab, labs 029 and 031).
 *
 * The process ends when the app process that started it ends, like a Shizuku user service.
 */
@Keep
object SystemServer {
    const val ACTION = "com.hexnext.themer.action.SYSTEM_SERVICE"
    const val EXTRA_BUNDLE = "com.hexnext.themer.extra.SYSTEM_SERVICE"
    const val KEY_BINDER = "binder"
    const val KEY_TOKEN = "token"
    const val PROCESS_NAME = "hexnext_system"

    private const val SEND_EVERY_MS = 1_000L
    private const val SENDS = 15
    private const val WATCH_MS = 5_000L

    @Keep
    @JvmStatic
    fun main(args: Array<String>) {
        val pkg = args.getOrNull(0)?.takeIf { it.matches(Regex("[A-Za-z0-9._]+")) } ?: exitProcess(2)
        val token = args.getOrNull(1)?.takeIf { it.matches(Regex("[A-Za-z0-9]{16,64}")) } ?: exitProcess(2)
        val appPid = args.getOrNull(2)?.toIntOrNull() ?: exitProcess(2)
        if (Process.myUid() != 1000 && Process.myUid() != 0) exitProcess(3)

        @Suppress("DEPRECATION")
        Looper.prepareMainLooper()
        val context = systemContext() ?: exitProcess(4)
        val service = ThemeService()
        val handler = Handler(Looper.getMainLooper())

        // The app may still be registering its receiver: send a few times, it keeps the first.
        var sent = 0
        val send = object : Runnable {
            override fun run() {
                val bundle = Bundle().apply {
                    putBinder(KEY_BINDER, service)
                    putString(KEY_TOKEN, token)
                }
                runCatching {
                    context.sendBroadcast(Intent(ACTION).setPackage(pkg).putExtra(EXTRA_BUNDLE, bundle))
                }
                if (++sent < SENDS) handler.postDelayed(this, SEND_EVERY_MS)
            }
        }
        handler.post(send)

        // Gone with the app process that asked for it. /proc hides other uids' processes from
        // UID 1000 (hidepid), so ask the kernel: signal 0 is ESRCH once the pid is gone and EPERM
        // (or nothing) while it lives.
        val watch = object : Runnable {
            override fun run() {
                if (!alive(appPid)) exitProcess(0)
                handler.postDelayed(this, WATCH_MS)
            }
        }
        handler.postDelayed(watch, WATCH_MS)
        Looper.loop()
    }

    private fun alive(pid: Int): Boolean = try {
        Os.kill(pid, 0)
        true
    } catch (e: ErrnoException) {
        e.errno != OsConstants.ESRCH
    }

    /** The system context of a process started with app_process (no Application of its own). */
    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    private fun systemContext(): Context? = runCatching {
        val activityThread = Class.forName("android.app.ActivityThread")
        val thread = activityThread.getMethod("systemMain").invoke(null)
        activityThread.getMethod("getSystemContext").invoke(thread) as Context
    }.getOrNull()
}
