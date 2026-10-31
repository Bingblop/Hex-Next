package com.hexnext.themer.shizuku

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Process
import com.hexnext.themer.BuildConfig
import com.hexnext.themer.IThemeService
import kotlinx.coroutines.CompletableDeferred
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

    data class Ready(val uid: Int, val version: Int, val viaSu: Boolean = false) : ShizukuState {
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

    fun init() {
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

    fun refresh() {
        _state.value = when {
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
        service?.let { if (it.asBinder().pingBinder()) return it }
        val ready = _state.value as? ShizukuState.Ready ?: error("Privileged session (Shizuku UID 0/1000 or SU) isn't ready")
        if (ready.viaSu || Process.myUid() == 0 || Process.myUid() == 1000) {
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
