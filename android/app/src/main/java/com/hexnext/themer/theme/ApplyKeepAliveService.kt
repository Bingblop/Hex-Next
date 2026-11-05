package com.hexnext.themer.theme

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.hexnext.themer.R

/**
 * Keeps Hex Next running while an apply from the editor is in progress.
 *
 * The apply runs in the app process. Leaving the app or locking the screen mid-apply let Android
 * freeze that process: lab-039 saw the service finish My Files in 6 s while the app logged it 14
 * minutes later. A foreground service (and a partial wake lock, capped at 20 minutes) keeps the
 * process unfrozen until [stop].
 */
class ApplyKeepAliveService : Service() {
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            release()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val ok = runCatching { goForeground() }.onFailure { Log.w(TAG, "Could not go foreground", it) }.isSuccess
        if (!ok) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (wake?.isHeld != true) {
            wake = runCatching {
                getSystemService(PowerManager::class.java)
                    ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HexNext:apply")
                    ?.apply { acquire(MAX_HOLD_MS) }
            }.getOrNull()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        release()
        super.onDestroy()
    }

    private fun release() {
        runCatching { if (wake?.isHeld == true) wake?.release() }
        wake = null
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(
            NotificationChannel(CHANNEL, "Theme apply in progress", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Shown while Hex Next applies your theme."
            },
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.hx_glyph_themes)
            .setContentTitle("Applying your theme")
            .setContentText("You can leave Hex Next; the apply keeps going.")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(ID, notification)
        }
    }

    companion object {
        private const val TAG = "HexNextApply"
        private const val CHANNEL = "theme_apply_running"
        private const val ID = 4203
        private const val ACTION_STOP = "com.hexnext.themer.action.APPLY_DONE"
        private const val MAX_HOLD_MS = 20 * 60_000L

        /** Starts the keep-alive; false when Android refused (the apply still runs, unprotected). */
        fun start(context: Context): Boolean =
            runCatching { context.startForegroundService(Intent(context, ApplyKeepAliveService::class.java)) }
                .onFailure { Log.w(TAG, "Keep-alive not started", it) }.isSuccess

        fun stop(context: Context) {
            runCatching { context.startService(Intent(context, ApplyKeepAliveService::class.java).setAction(ACTION_STOP)) }
        }
    }
}
