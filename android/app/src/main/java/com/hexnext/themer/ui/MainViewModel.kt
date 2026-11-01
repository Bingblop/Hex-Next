package com.hexnext.themer.ui

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hexnext.themer.safety.ApplyGuard
import com.hexnext.themer.shizuku.ShizukuManager
import com.hexnext.themer.shizuku.ShizukuState
import com.hexnext.themer.shizuku.ThemeService
import com.hexnext.themer.theme.LogLevel
import com.hexnext.themer.theme.LogLine
import com.hexnext.themer.theme.MonetPalette
import com.hexnext.themer.theme.PackedAppOverlay
import com.hexnext.themer.theme.PaletteStyle
import com.hexnext.themer.theme.SettingsIconStyle
import com.hexnext.themer.theme.ThemeEngine
import com.hexnext.themer.theme.ThemeSpec
import com.hexnext.themer.theme.formatOneUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UiState(
    val spec: ThemeSpec,
    val palette: MonetPalette,
    val enabledTargets: Set<String> = PackedAppOverlay.ALL.map { it.id }.toSet(),
    val busy: Boolean = false,
    val oneUiVersion: String? = null,
    val log: List<LogLine> = emptyList(),
    /** Non-null while the "Hex overlays were turned off after repeated restarts" banner shows. */
    val safeModeBanner: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("hexnext", Context.MODE_PRIVATE)
    private val engine = ThemeEngine(prefs)
    private val guard = ApplyGuard(app)
    private var safeModeChecked = false

    val shizuku: StateFlow<ShizukuState> = ShizukuManager.state

    private val _ui = MutableStateFlow(initialState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            ShizukuManager.state.collect {
                if (it is ShizukuState.Ready) {
                    checkSafeMode()
                    loadDeviceInfo()
                }
            }
        }
    }

    /**
     * Boot-loop guard: if the device restarted twice within 10 minutes of an unconfirmed apply,
     * turn every HexNext overlay off and show the safe-mode banner.
     */
    private suspend fun checkSafeMode() {
        if (safeModeChecked) return
        safeModeChecked = true
        if (guard.shouldEnterSafeMode()) {
            val result = runCatching {
                val service = ShizukuManager.service()
                withContext(Dispatchers.IO) { service.disableHexOverlays() }
            }
            val bundle = result.getOrNull()
            if (bundle != null && bundle.getInt(ThemeService.KEY_CODE, -1) == 0) {
                guard.markSafeModeEntered(by = "app start")
                log(LogLine(LogLevel.WARN, "Safe mode: ${bundle.getString(ThemeService.KEY_OUTPUT).orEmpty()}"))
            } else {
                val why = bundle?.getString(ThemeService.KEY_OUTPUT)
                    ?: result.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" }
                    ?: "unknown error"
                log(LogLine(LogLevel.ERROR, "Safe mode could not turn Hex overlays off: $why. Use Diagnostics > Remove all Hex overlays."))
                _ui.update { it.copy(safeModeBanner = SAFE_MODE_FAILED_TEXT) }
                return
            }
        }
        if (guard.safeModeActive()) {
            _ui.update { it.copy(safeModeBanner = SAFE_MODE_TEXT) }
        }
    }

    fun dismissSafeMode() {
        guard.dismissSafeMode()
        _ui.update { it.copy(safeModeBanner = null) }
    }

    private fun initialState(): UiState {
        val style = runCatching { PaletteStyle.valueOf(prefs.getString(PREF_STYLE, null)!!) }
            .getOrDefault(PaletteStyle.TONAL_SPOT)
        val iconStyle = SettingsIconStyle.fromId(prefs.getString(PREF_ICON_STYLE, "1sett"))
        val spec = ThemeSpec(
            seed = prefs.getInt(PREF_SEED, DEFAULT_SEED),
            style = style,
            pureBlack = prefs.getBoolean(PREF_PURE_BLACK, true),
            settingsIconStyle = iconStyle,
            framelessIcons = prefs.getBoolean(PREF_SETTFRAME, false),
            largeSettingsIcons = prefs.getBoolean(PREF_BIGSETICO, false),
            hideAccountEmail = prefs.getBoolean(PREF_HIDEMAIL, false),
            useAboutLogo = prefs.getBoolean(PREF_USELOGO, true),
            showListDividers = prefs.getBoolean(PREF_UIDIVIDER, true),
        )
        return UiState(spec = spec, palette = MonetPalette.generate(spec))
    }

    fun setSeed(color: Int) = updateSpec { it.copy(seed = color or 0xFF000000.toInt()) }
    fun setStyle(style: PaletteStyle) = updateSpec { it.copy(style = style) }
    fun setPureBlack(enabled: Boolean) = updateSpec { it.copy(pureBlack = enabled) }
    fun setSettingsIconStyle(style: SettingsIconStyle) = updateSpec { it.copy(settingsIconStyle = style) }
    fun setFramelessIcons(enabled: Boolean) = updateSpec { it.copy(framelessIcons = enabled) }
    fun setLargeSettingsIcons(enabled: Boolean) = updateSpec { it.copy(largeSettingsIcons = enabled) }
    fun setHideAccountEmail(enabled: Boolean) = updateSpec { it.copy(hideAccountEmail = enabled) }
    fun setUseAboutLogo(enabled: Boolean) = updateSpec { it.copy(useAboutLogo = enabled) }
    fun setShowListDividers(enabled: Boolean) = updateSpec { it.copy(showListDividers = enabled) }

    fun setPerIconColor(iconId: String, color: Int?) = updateSpec { current ->
        val updated = current.perIconColors.toMutableMap()
        if (color != null) updated[iconId] = color or 0xFF000000.toInt()
        else updated.remove(iconId)
        current.copy(perIconColors = updated)
    }

    fun resetPerIconColors() = updateSpec { it.copy(perIconColors = emptyMap()) }

    fun toggleTarget(id: String) {
        _ui.update { state ->
            val updated = state.enabledTargets.toMutableSet()
            if (!updated.add(id)) updated.remove(id)
            state.copy(enabledTargets = updated)
        }
    }

    private fun updateSpec(change: (ThemeSpec) -> ThemeSpec) {
        val spec = change(_ui.value.spec)
        prefs.edit()
            .putInt(PREF_SEED, spec.seed)
            .putString(PREF_STYLE, spec.style.name)
            .putBoolean(PREF_PURE_BLACK, spec.pureBlack)
            .putString(PREF_ICON_STYLE, spec.settingsIconStyle.id)
            .putBoolean(PREF_SETTFRAME, spec.framelessIcons)
            .putBoolean(PREF_BIGSETICO, spec.largeSettingsIcons)
            .putBoolean(PREF_HIDEMAIL, spec.hideAccountEmail)
            .putBoolean(PREF_USELOGO, spec.useAboutLogo)
            .putBoolean(PREF_UIDIVIDER, spec.showListDividers)
            .apply()
        _ui.update { it.copy(spec = spec, palette = MonetPalette.generate(spec)) }
    }

    fun requestPermission() = ShizukuManager.requestPermission()

    fun clearLog() = _ui.update { it.copy(log = emptyList()) }

    fun apply() = runTask { service, ready ->
        val palette = _ui.value.palette
        if (ready.isPrivileged) {
            engine.applyFabricated(service, palette, _ui.value.enabledTargets, ::log)
        } else {
            log(
                LogLine(
                    LogLevel.ERROR,
                    "Opening Requirement Not Met: Shizuku must run at UID 0 (Root) or UID 1000 (System), or direct SU access is required (current UID=${ready.uid})."
                )
            )
            false
        }
    }

    fun remove() = runTask { service, ready -> engine.remove(service, ready.isPrivileged, ::log) }

    private fun runTask(block: (com.hexnext.themer.IThemeService, ShizukuState.Ready) -> Boolean) {
        val ready = ShizukuManager.state.value as? ShizukuState.Ready ?: run {
            log(LogLine(LogLevel.ERROR, "Shizuku (UID 0 / UID 1000) or SU isn't ready."))
            return
        }
        if (_ui.value.busy) return
        _ui.update { it.copy(busy = true, log = emptyList()) }
        viewModelScope.launch {
            try {
                val service = ShizukuManager.service()
                withContext(Dispatchers.IO) { block(service, ready) }
            } catch (t: Throwable) {
                log(LogLine(LogLevel.ERROR, "${t.javaClass.simpleName}: ${t.message}"))
            } finally {
                _ui.update { it.copy(busy = false) }
            }
        }
    }

    private fun log(line: LogLine) = _ui.update { it.copy(log = it.log + line) }

    private suspend fun loadDeviceInfo() {
        if (_ui.value.oneUiVersion != null) return
        val version = runCatching {
            val service = ShizukuManager.service()
            val raw = withContext(Dispatchers.IO) {
                service.exec("getprop ro.build.version.oneui").getString("output").orEmpty().trim()
            }
            formatOneUi(raw)
        }.getOrNull()
        _ui.update { it.copy(oneUiVersion = version ?: "One UI 9.0") }
    }

    companion object {
        private const val PREF_SEED = "seed"
        private const val PREF_STYLE = "style"
        private const val PREF_PURE_BLACK = "pure_black"
        private const val PREF_ICON_STYLE = "settings_icon_style"
        private const val PREF_SETTFRAME = "settframe"
        private const val PREF_BIGSETICO = "bigsetico"
        private const val PREF_HIDEMAIL = "hidemail"
        private const val PREF_USELOGO = "uselogo"
        private const val PREF_UIDIVIDER = "uidivider"
        private const val DEFAULT_SEED = 0xFF00E5FF.toInt()
        private const val SAFE_MODE_TEXT = "Safe mode: Hex overlays were turned off after repeated restarts"
        private const val SAFE_MODE_FAILED_TEXT =
            "Safe mode: the device restarted repeatedly, but Hex overlays could not be turned off automatically"

        val androidVersion: String get() = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
    }
}
