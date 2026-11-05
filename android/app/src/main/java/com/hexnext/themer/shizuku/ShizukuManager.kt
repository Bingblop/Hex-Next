package com.hexnext.themer.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import com.hexnext.themer.BuildConfig
import com.hexnext.themer.IThemeService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku
import java.io.File

/**
 * Privilege state for HexNext One UI 9.0 System RRO Compiler.
 *
 * Theme Park (`com.samsung.android.themepark` / `com.samsung.themedesigner`) is NOT required.
 * The ONLY opening requirement is Shizuku running at UID 0 (Root) or UID 1000 (System),
 * or direct SU (UID 0) / sharedUserId UID 1000 access.
 */
sealed interface ShizukuState {
    /** Neither Shizuku (UID 0 / 1000) nor SU is active. */
    data object NotRunning : ShizukuState

    /** Shizuku is older than the v11 API this app uses. */
    data object TooOld : ShizukuState

    data object PermissionNeeded : ShizukuState

    /** The user picked "deny and don't ask again"; it must be granted in the Shizuku app. */
    data object PermissionDenied : ShizukuState

    /**
     * @property viaSystem Hex Next's own system service ([SystemLauncher], UID 1000 through su),
     *   not Shizuku: its overlays survive reboots.
     */
    data class Ready(val uid: Int, val version: Int, val viaSu: Boolean = false, val viaSystem: Boolean = false) : ShizukuState {
        val isRoot: Boolean get() = uid == 0
        val isSystemUid1000: Boolean get() = uid == 1000
        /** True when running at UID 0 (Root) or UID 1000 (android.uid.system). */
        val isPrivileged: Boolean get() = uid == 0 || uid == 1000
    }
}

object ShizukuManager {
    private const val PERMISSION_REQUEST = 7301

    private val _state = MutableStateFlow<ShizukuState>(ShizukuState.NotRunning)
    val state: StateFlow<ShizukuState> = _state.asStateFlow()

    private var service: IThemeService? = null
    private var pending: CompletableDeferred<IThemeService>? = null

    private val serviceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(BuildConfig.APPLICATION_ID, ThemeService::class.java.name))
            .daemon(false)
            .processNameSuffix("theme")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null || !binder.pingBinder()) return
            val bound = IThemeService.Stub.asInterface(binder)
            service = bound
            pending?.complete(bound)
            pending = null
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    private var initialized = false

    private var appContext: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * With [context], a rooted phone gets Hex Next's own system service ([SystemLauncher]) when it
     * starts; Shizuku and SU-only mode are the fallbacks.
     */
    fun init(context: Context? = null) {
        if (context != null && appContext == null) {
            appContext = context.applicationContext
            startSystem()
        }
        if (initialized) return
        initialized = true
        Shizuku.addBinderReceivedListenerSticky { refresh() }
        Shizuku.addBinderDeadListener {
            service = null
            refresh()
        }
        Shizuku.addRequestPermissionResultListener { requestCode, _ ->
            if (requestCode == PERMISSION_REQUEST) refresh()
        }
        refresh()
    }

    private fun hasDirectPrivilegedAccess(): Pair<Int, Boolean>? {
        val myUid = Process.myUid()
        if (myUid == 0 || myUid == 1000) {
            return myUid to false
        }
        val suPaths = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/debug_ramdisk/su")
        if (suPaths.any { File(it).exists() }) {
            return 0 to true
        }
        return null
    }

    /**
     * Right after Shizuku (re)starts, it hands out its binder before it has attached this app as
     * a client, and every call then throws IllegalStateException("Not an attached client")
     * (device lab, lab-013: the app crashed in Application.onCreate after a soft reboot). Such a
     * moment is never fatal: keep the previous state and look again shortly.
     */
    private val retry = Handler(Looper.getMainLooper())
    private var retries = 0

    fun refresh() {
        val next = runCatching { readState() }.getOrElse {
            if (retries < MAX_RETRIES) {
                retries++
                retry.postDelayed({ refresh() }, RETRY_MS)
            }
            return
        }
        retries = 0
        _state.value = next
    }

    private const val MAX_RETRIES = 40
    private const val RETRY_MS = 500L

    /** Starts (or reconnects) the system service in the background; the state follows. */
    fun startSystem() {
        val context = appContext ?: return
        if (SystemLauncher.failed || !SystemLauncher.suPresent()) return
        scope.launch {
            SystemLauncher.ensure(context) { Handler(Looper.getMainLooper()).post { refresh() } }
        }
    }

    private fun readState(): ShizukuState {
        if (SystemLauncher.alive()) return ShizukuState.Ready(uid = 1000, version = 13, viaSystem = true)
        return when {
            Shizuku.pingBinder() -> {
                when {
                    Shizuku.isPreV11() -> ShizukuState.TooOld
                    Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED ->
                        ShizukuState.Ready(Shizuku.getUid(), Shizuku.getVersion(), viaSu = false)
                    Shizuku.shouldShowRequestPermissionRationale() -> ShizukuState.PermissionDenied
                    else -> ShizukuState.PermissionNeeded
                }
            }
            else -> {
                val direct = hasDirectPrivilegedAccess()
                if (direct != null) {
                    ShizukuState.Ready(uid = direct.first, version = 13, viaSu = direct.second)
                } else {
                    ShizukuState.NotRunning
                }
            }
        }
    }

    fun requestPermission() {
        if (Shizuku.pingBinder() && !Shizuku.isPreV11()) {
            Shizuku.requestPermission(PERMISSION_REQUEST)
        }
    }

    /** Binds the privileged user service (or local SU/UID 1000 service), starting it if needed. */
    suspend fun service(): IThemeService {
        // On a rooted phone the system service comes first, even while Shizuku is up: only its
        // overlays survive a reboot. A start in progress is waited for (ensure() holds a lock),
        // so an apply right after launch never builds shell-owned overlays beside it.
        appContext?.let { context ->
            if (!SystemLauncher.failed && SystemLauncher.suPresent()) {
                SystemLauncher.ensure(context) { Handler(Looper.getMainLooper()).post { refresh() } }?.let { system ->
                    service = system
                    if (_state.value !is ShizukuState.Ready || !(_state.value as ShizukuState.Ready).viaSystem) refresh()
                    return system
                }
            }
        }
        val current = _state.value as? ShizukuState.Ready
        val wantLocal = current != null && !current.viaSystem && (current.viaSu || Process.myUid() == 0 || Process.myUid() == 1000)
        service?.let { cached ->
            // An in-process ThemeService cached in SU mode always pings (it is a local Binder),
            // so it would outlive the switch to Shizuku and keep handing back the app uid.
            // Only reuse the cached service when it is the kind the current state calls for.
            val cachedLocal = cached is ThemeService
            val cachedSystem = cached === SystemLauncher.service
            if (current?.viaSystem == true && !cachedSystem) {
                service = null
            } else if (current == null || cachedLocal == wantLocal) {
                if (cached.asBinder().pingBinder()) return cached
            } else if (cachedLocal) {
                service = null
            }
        }
        if (current == null) error("Privileged session (Shizuku UID 0/1000 or SU) isn't ready")
        if (current.viaSystem) {
            val context = appContext ?: error("System service has no context")
            val system = SystemLauncher.ensure(context) { Handler(Looper.getMainLooper()).post { refresh() } }
            if (system != null) return system.also { service = it }
            refresh()
            error("System service stopped: ${SystemLauncher.lastError ?: "unknown"}")
        }
        if (wantLocal) {
            return ThemeService().also { service = it }
        }
        val deferred = pending ?: CompletableDeferred<IThemeService>().also {
            pending = it
            Shizuku.bindUserService(serviceArgs, connection)
        }
        return try {
            withTimeout(15_000) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            pending = null
            throw IllegalStateException("Shizuku didn't start the theme service within 15 seconds")
        }
    }
}
