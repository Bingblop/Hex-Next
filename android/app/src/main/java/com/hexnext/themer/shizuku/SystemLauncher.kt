package com.hexnext.themer.shizuku

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import androidx.core.content.ContextCompat
import com.hexnext.themer.IThemeService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Starts [SystemServer] as UID 1000 through any compatible su provider, or UID 0 as fallback.
 *
 * System-owned overlays are the only ones One UI keeps across reboots (device lab, labs 029-032),
 * so on a rooted phone this is the preferred way in; Shizuku and SU-only mode stay as fallbacks
 * for when su is missing, refused, or cannot switch to UID 1000.
 */
object SystemLauncher {
    @Volatile var service: IThemeService? = null
        private set

    /** Why the last start failed, for diagnostics; null when it worked or never ran. */
    @Volatile var lastError: String? = null
        private set

    /** Set after a failed start so the app stops trying until [reset]. */
    @Volatile var failed: Boolean = false
        private set

    private val lock = Mutex()
    private const val WAIT_MS = 12_000L

    private val SU_PATHS = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/debug_ramdisk/su")

    fun suPresent(): Boolean = SU_PATHS.any { File(it).exists() }

    /** True when a system service is connected and answering. */
    fun alive(): Boolean = service?.asBinder()?.pingBinder() == true
    val connectedUid: Int? get() = service?.takeIf { it.asBinder().pingBinder() }
        ?.let { runCatching { it.uid }.getOrNull() }

    fun reset() {
        failed = false
        lastError = null
    }

    /** The connected system service, starting it when needed; null when it cannot be started. */
    suspend fun ensure(context: Context, requestAccess: Boolean = false, onChange: () -> Unit = {}): IThemeService? = lock.withLock {
        service?.takeIf { it.asBinder().pingBinder() }?.let { return it }
        service = null
        // Only an explicit user request retries a denial or tries su when its path is hidden.
        if (requestAccess) reset()
        if (failed || (!requestAccess && !suPresent())) return null
        val started = withContext(Dispatchers.IO) { start(context.applicationContext, requestAccess) }
        if (started == null) failed = true
        service = started
        started?.asBinder()?.linkToDeath({
            service = null
            onChange()
        }, 0)
        onChange()
        started
    }

    private suspend fun start(context: Context, interactive: Boolean): IThemeService? {
        val errors = mutableListOf<String>()
        for (uid in listOf(1000, 0)) {
            startAsUid(context, uid, interactive)?.let { return it }
            errors += "UID $uid: ${lastError ?: "service unavailable"}"
        }
        lastError = errors.joinToString("; ")
        return null
    }

    private suspend fun startAsUid(context: Context, expectedUid: Int, interactive: Boolean): IThemeService? {
        val token = token()
        val received = CompletableDeferred<IBinder>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val bundle = intent.getBundleExtra(SystemServer.EXTRA_BUNDLE) ?: return
                if (bundle.getString(SystemServer.KEY_TOKEN) != token) return
                bundle.getBinder(SystemServer.KEY_BINDER)?.let { received.complete(it) }
            }
        }
        // Not exported: only the system uid, root and this app may send to it.
        ContextCompat.registerReceiver(context, receiver, IntentFilter(SystemServer.ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            val launch = launch(context, token, expectedUid, interactive)
            if (launch != null) {
                lastError = launch
                return null
            }
            val binder = withTimeoutOrNull(WAIT_MS) { received.await() }
            if (binder == null) {
                lastError = "the system service did not answer within ${WAIT_MS / 1000} s"
                return null
            }
            val bound = IThemeService.Stub.asInterface(binder)
            val uid = runCatching { bound.uid }.getOrDefault(-1)
            if (uid != expectedUid) {
                lastError = "the service runs as UID $uid, not $expectedUid"
                runCatching { bound.destroy() }
                return null
            }
            lastError = null
            return bound
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    /** Runs the start command through su; null when su accepted it, else what went wrong. */
    private fun launch(context: Context, token: String, uid: Int, interactive: Boolean): String? {
        val apk = context.applicationInfo.sourceDir
        val pkg = context.packageName
        val main = SystemServer::class.java.name
        val server = "CLASSPATH='$apk' exec app_process -Djava.class.path='$apk' /system/bin " +
            "--nice-name=${SystemServer.PROCESS_NAME} $main $pkg $token ${Process.myPid()}"
        // One server per app process: an older one (from an app process that is gone) is replaced.
        val script = "pkill -x ${SystemServer.PROCESS_NAME} 2>/dev/null; setsid sh -c \"$server\" </dev/null >/dev/null 2>&1 &"
        // KernelSU and Magisk su take the uid directly; some su builds only do it from root.
        val attempts = if (uid == 1000) listOf(
            arrayOf("su", uid.toString(), "-c", script),
            arrayOf("su", "-c", "su 1000 -c '${script.replace("'", "'\\''")}'"),
        ) else listOf(
            arrayOf("su", "0", "-c", script),
            arrayOf("su", "-c", script),
        )
        var last = "su not found"
        for (cmd in attempts) {
            val result = runCatching {
                val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
                val done = p.waitFor(if (interactive) 45L else 8L, TimeUnit.SECONDS)
                if (!done) {
                    // Do not read to EOF before killing a su process still waiting on its prompt.
                    p.destroyForcibly()
                    -1 to "su did not return; check the root manager and try again"
                } else {
                    val out = p.inputStream.use { input ->
                        String(input.readNBytes(minOf(input.available(), 4096)), Charsets.UTF_8).trim()
                    }
                    p.exitValue() to out
                }
            }.getOrElse { -1 to (it.message ?: it.javaClass.simpleName) }
            if (result.first == 0) return null
            last = "su exit ${result.first}: ${result.second.take(200)}"
        }
        return last
    }

    private fun token(): String {
        val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val random = SecureRandom()
        return String(CharArray(32) { chars[random.nextInt(chars.length)] })
    }
}
