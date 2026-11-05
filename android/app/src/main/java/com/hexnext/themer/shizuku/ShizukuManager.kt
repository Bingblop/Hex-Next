package com.hexnext.themer.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.RemoteException
import com.hexnext.themer.BuildConfig
import com.hexnext.themer.IThemeService
import com.hexnext.themer.ServiceTasks
import kotlinx.coroutines.CancellationException
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
     * @property viaSystem Hex Next's own service ([SystemLauncher]), not Shizuku.
     *   Check [uid]: UID 1000 persists; the UID 0 fallback does not.
     */
    data class Ready(val uid: Int, val version: Int, val viaSu: Boolean = false, val viaSystem: Boolean = false) : ShizukuState {
        val isRoot: Boolean get() = uid == 0
        val isSystemUid1000: Boolean get() = uid == 1000
        /** True when running at UID 0 (Root) or UID 1000 (android.uid.system). */
        val isPrivileged: Boolean get() = uid == 0 || uid == 1000
    }
}

object ShizukuManager {
    data class RootRequest(val requesting: Boolean = false, val error: String? = null)
    private val _rootRequest = MutableStateFlow(RootRequest())
    val rootRequest: StateFlow<RootRequest> = _rootRequest.asStateFlow()
    private const val PERMISSION_REQUEST = 7301

    private val _state = MutableStateFlow<ShizukuState>(ShizukuState.NotRunning)
    val state: StateFlow<ShizukuState> = _state.asStateFlow()

    private var service: IThemeService? = null
    private var serviceFromShizuku = false
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
            serviceFromShizuku = true
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
        if (permittedShizukuUid() == 1000) { refresh(); return }
        if (SystemLauncher.failed || !SystemLauncher.suPresent()) return
        scope.launch(Dispatchers.Main.immediate) {
            try {
                SystemLauncher.ensure(context) { Handler(Looper.getMainLooper()).post { refresh() } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: RemoteException) {
                _rootRequest.value = RootRequest(error = "Root access could not be started. Open your root manager, allow Hex Next, then try again.")
            } catch (e: SecurityException) {
                _rootRequest.value = RootRequest(error = "Root access could not be started. Open your root manager, allow Hex Next, then try again.")
            }
        }
    }

    /** User-initiated retry, including when KernelSU is installed but su is not visible yet. */
    fun requestRootAccess() {
        // Keep UI-facing state and task ownership on Main without tying this app-scoped flow
        // to an Activity. ensure() moves privileged process work to Dispatchers.IO.
        scope.launch(Dispatchers.Main.immediate) {
            val context = appContext ?: return@launch
            if (permittedShizukuUid() == 1000) { refresh(); return@launch }
            val previous = _rootRequest.value
            if (previous.requesting || !_rootRequest.compareAndSet(previous, RootRequest(requesting = true))) return@launch
            if (!ServiceTasks.tryStart("Requesting root access")) {
                _rootRequest.value = RootRequest(error = "Wait for the current device task to finish, then try again.")
                return@launch
            }
            try {
                val connected = SystemLauncher.ensure(context, requestAccess = true)
                _rootRequest.value = RootRequest(error = if (connected == null) {
                    "Root access was not granted or no privileged service is active. Allow Hex Next in your root manager, " +
                        "or connect Shizuku at UID 1000. If you are not rooted, complete manual setup in DirtyFrag first. " +
                        (SystemLauncher.lastError ?: "Neither UID 1000 nor UID 0 access was obtained.")
                } else null)
            } catch (e: CancellationException) {
                _rootRequest.value = RootRequest()
                throw e
            } catch (e: RemoteException) {
                _rootRequest.value = RootRequest(error = "Root access could not be started. Check your root manager or Shizuku and try again.")
            } catch (e: SecurityException) {
                _rootRequest.value = RootRequest(error = "Root access could not be started. Check your root manager or Shizuku and try again.")
            } finally {
                _rootRequest.value = _rootRequest.value.copy(requesting = false)
                ServiceTasks.finish()
                Handler(Looper.getMainLooper()).post { refresh() }
            }
        }
    }

    private fun permittedShizukuUid(): Int? = runCatching {
        if (Shizuku.pingBinder() && !Shizuku.isPreV11() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) Shizuku.getUid() else null
    }.getOrNull()

    private fun readState(): ShizukuState {
        val shizukuUid = permittedShizukuUid()
        val ownUid = SystemLauncher.connectedUid
        PrivilegeMode.preferredUid(ownUid, shizukuUid)?.let { uid ->
            return ShizukuState.Ready(uid, 13, viaSystem = ownUid == uid && shizukuUid != 1000)
        }
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
        // An already-authorized Shizuku system service must never be downgraded to root UID 0
        // merely because a different su provider is installed or the system launcher failed.
        if (permittedShizukuUid() == 1000) return shizukuService(1000)
        if (Process.myUid() == 1000) return ThemeService()
        // On a rooted phone the system service comes first, even while Shizuku is up: only its
        // overlays survive a reboot. A start in progress is waited for (ensure() holds a lock),
        // so an apply right after launch never builds shell-owned overlays beside it.
        appContext?.let { context ->
            if (!SystemLauncher.failed && SystemLauncher.suPresent()) {
                SystemLauncher.ensure(context) { Handler(Looper.getMainLooper()).post { refresh() } }?.let { system ->
                    if (permittedShizukuUid() == 1000) return shizukuService(1000)
                    service = system
                    serviceFromShizuku = false
                    if (_state.value !is ShizukuState.Ready || !(_state.value as ShizukuState.Ready).viaSystem) refresh()
                    return system
                }
            }
        }
        SystemLauncher.service?.takeIf { it.asBinder().pingBinder() }?.let { return it }
        permittedShizukuUid()?.let { uid ->
            if (PrivilegeMode.fromUid(uid) != PrivilegeMode.NONE) return shizukuService(uid)
        }
        if (Process.myUid() == 0) return ThemeService()
        refresh()
        error("No privileged service is connected. Request root access or authorize Shizuku (UID 1000 preferred, UID 0 fallback; UID 2000 is Monet only).")
    }

    private suspend fun shizukuService(expectedUid: Int): IThemeService {
        service?.takeIf { serviceFromShizuku && it.asBinder().pingBinder() }?.let {
            if (it.uid == expectedUid) return it
            service = null
        }
        val deferred = pending ?: CompletableDeferred<IThemeService>().also {
            pending = it
            Shizuku.bindUserService(serviceArgs, connection)
        }
        return try {
            withTimeout(15_000) { deferred.await() }.also {
                check(it.uid == expectedUid) { "Shizuku changed UID while connecting; reconnect before applying." }
            }
        } catch (e: TimeoutCancellationException) {
            pending = null
            throw IllegalStateException("Shizuku didn't start the theme service within 15 seconds")
        }
    }
}
