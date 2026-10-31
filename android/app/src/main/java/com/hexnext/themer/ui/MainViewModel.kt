package com.hexnext.themer.ui

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hexnext.themer.shizuku.ShizukuManager
import com.hexnext.themer.shizuku.ShizukuState
import com.hexnext.themer.theme.LogLevel
import com.hexnext.themer.theme.LogLine
import com.hexnext.themer.theme.MonetPalette
import com.hexnext.themer.theme.PackedAppOverlay
import com.hexnext.themer.theme.PaletteStyle
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
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("hexnext", Context.MODE_PRIVATE)
    private val engine = ThemeEngine(prefs)

    val shizuku: StateFlow<ShizukuState> = ShizukuManager.state

    private val _ui = MutableStateFlow(initialState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            ShizukuManager.state.collect { if (it is ShizukuState.Ready) loadDeviceInfo() }
        }
    }

    private fun initialState(): UiState {
        val style = runCatching { PaletteStyle.valueOf(prefs.getString(PREF_STYLE, null)!!) }
            .getOrDefault(PaletteStyle.TONAL_SPOT)
        val spec = ThemeSpec(
            seed = prefs.getInt(PREF_SEED, DEFAULT_SEED),
            style = style,
            pureBlack = prefs.getBoolean(PREF_PURE_BLACK, true),
        )
        return UiState(spec = spec, palette = MonetPalette.generate(spec))
    }

    fun setSeed(color: Int) = updateSpec { it.copy(seed = color or 0xFF000000.toInt()) }
    fun setStyle(style: PaletteStyle) = updateSpec { it.copy(style = style) }
    fun setPureBlack(enabled: Boolean) = updateSpec { it.copy(pureBlack = enabled) }

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
        private const val DEFAULT_SEED = 0xFF00E5FF.toInt()

        val androidVersion: String get() = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
    }
}
