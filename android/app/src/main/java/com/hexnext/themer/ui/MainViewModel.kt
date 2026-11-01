package com.hexnext.themer.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hexnext.engine.compile.ThemeSpec as EngineThemeSpec
import com.hexnext.themer.safety.ApplyGuard
import com.hexnext.themer.shizuku.ShizukuManager
import com.hexnext.themer.shizuku.ShizukuState
import com.hexnext.themer.shizuku.ThemeService
import com.hexnext.themer.theme.CatalogueEntry
import com.hexnext.themer.theme.InstalledPack
import com.hexnext.themer.theme.LogLevel
import com.hexnext.themer.theme.LogLine
import com.hexnext.themer.theme.PackStore
import com.hexnext.themer.theme.SemWtController
import com.hexnext.themer.theme.ThemeApplier
import com.hexnext.themer.theme.ThemeStore
import com.hexnext.themer.theme.formatOneUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UiState(
    /** Accent seed (opaque ARGB) of the compiled theme and the Diagnostics Settings test. */
    val seed: Int,
    val busy: Boolean = false,
    val oneUiVersion: String? = null,
    val log: List<LogLine> = emptyList(),
    /** Non-null while the "Hex overlays were turned off after repeated restarts" banner shows. */
    val safeModeBanner: String? = null,
    /** [com.hexnext.engine.compile.ApplyReport.summary] of the last compiled-theme apply. */
    val lastApplySummary: String? = null,
    /** Pack zips in `files/packs` that could be read. */
    val packs: List<InstalledPack> = emptyList(),
    /** Pack zips that could not be read: file name → message. */
    val packErrors: Map<String, String> = emptyMap(),
    /** [EngineThemeSpec.simplePackId] of the stored theme; null is "colours only". */
    val selectedPackId: String? = null,
    val catalogue: CatalogueState = CatalogueState(),
)

/** The "Get packs" dialog: catalogue URL, its entries and the downloads in flight (id → 0..1). */
data class CatalogueState(
    val url: String = PackStore.DEFAULT_CATALOGUE_URL,
    val entries: List<CatalogueEntry> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val downloading: Map<String, Float> = emptyMap(),
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("hexnext", Context.MODE_PRIVATE)
    private val guard = ApplyGuard(app)
    private val applier = ThemeApplier(app)
    private val themeStore = ThemeStore(app)
    private val packStore = PackStore(app)
    private var safeModeChecked = false
    private var semwtReasserted = false

    val shizuku: StateFlow<ShizukuState> = ShizukuManager.state

    private val _ui = MutableStateFlow(initialState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val selected = themeStore.load().simplePackId
            _ui.update { it.copy(selectedPackId = selected) }
            val seeded = runCatching { packStore.seedBundled() }.getOrDefault(emptyList())
            if (seeded.isNotEmpty()) log(LogLine(LogLevel.INFO, "Bundled test packs installed: ${seeded.joinToString()}"))
            refreshPacks()
        }
        viewModelScope.launch {
            ShizukuManager.state.collect {
                if (it is ShizukuState.Ready) {
                    checkSafeMode()
                    reassertSemWt()
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
                runCatching {
                    val service = ShizukuManager.service()
                    withContext(Dispatchers.IO) {
                        SemWtController.restore(service, ::log)
                        ThemeApplier.setTakeoverActive(getApplication<Application>(), false)
                    }
                }.onFailure { log(LogLine(LogLevel.WARN, "Safe mode could not restore Samsung SemWT overlays: ${it.message}")) }
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

    /**
     * Once per app run: if a compiled theme took targets over from Samsung's SemWT overlays,
     * disable any SemWT overlay Samsung re-enabled since (wallpaper or palette change) and restart
     * the re-assert guard. Skipped in safe mode.
     */
    private suspend fun reassertSemWt() {
        if (semwtReasserted) return
        semwtReasserted = true
        if (guard.safeModeActive() || !ThemeApplier.isTakeoverActive(getApplication<Application>())) return
        runCatching {
            val service = ShizukuManager.service()
            withContext(Dispatchers.IO) { SemWtController.reassert(service, { line -> if (line.level != LogLevel.INFO) log(line) }) }
        }.onFailure { log(LogLine(LogLevel.WARN, "SemWT re-assert failed: ${it.javaClass.simpleName}: ${it.message}")) }
    }

    fun dismissSafeMode() {
        guard.dismissSafeMode()
        _ui.update { it.copy(safeModeBanner = null) }
    }

    private fun initialState(): UiState = UiState(
        seed = prefs.getInt(PREF_SEED, DEFAULT_SEED) or OPAQUE,
        lastApplySummary = ThemeApplier.lastReport(getApplication<Application>())?.summary(),
    )

    fun setSeed(color: Int) {
        val seed = color or OPAQUE
        prefs.edit().putInt(PREF_SEED, seed).apply()
        _ui.update { it.copy(seed = seed) }
    }

    fun requestPermission() = ShizukuManager.requestPermission()

    fun clearLog() = _ui.update { it.copy(log = emptyList()) }

    /**
     * Compiles and applies the full theme (the stored [EngineThemeSpec] with the chosen accent
     * seed and pack) to every scanned target. Works without packs (colours only); drawables and
     * night colours need the privileged service.
     */
    fun applyTheme() = runTask { service, _ ->
        val spec = specToApply()
        val report = applier.apply(service, spec, ::log)
        _ui.update { it.copy(lastApplySummary = report.summary()) }
        report.success
    }

    /** The stored theme with the seed preference and the selected pack applied on top. */
    private fun specToApply(): EngineThemeSpec {
        val stored = themeStore.load()
        val state = _ui.value
        return stored.copy(source = stored.source.copy(seedArgb = state.seed), simplePackId = state.selectedPackId)
    }

    // ---- Theme packs ----

    /** Re-reads `files/packs`; a selected pack that is gone falls back to colours only. */
    fun refreshPacks() {
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching { packStore.installed() }
            result.onSuccess { installed ->
                val ids = installed.packs.mapTo(HashSet()) { it.id }
                _ui.update {
                    it.copy(
                        packs = installed.packs,
                        packErrors = installed.errors,
                        catalogue = it.catalogue.copy(entries = it.catalogue.entries.map { e -> e.copy(installed = e.id in ids) }),
                    )
                }
                val selected = _ui.value.selectedPackId
                if (selected != null && installed.packs.none { it.id == selected }) {
                    log(LogLine(LogLevel.WARN, "Theme pack $selected is no longer installed; colours only"))
                    selectPack(null)
                }
            }.onFailure { log(LogLine(LogLevel.ERROR, "Listing packs failed: ${it.javaClass.simpleName}: ${it.message}")) }
        }
    }

    /** Chooses the pack of the next apply (null = colours only) and persists it in the theme. */
    fun selectPack(id: String?) {
        _ui.update { it.copy(selectedPackId = id) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                themeStore.update { it.copy(simplePackId = id) }
            }.onFailure { log(LogLine(LogLevel.ERROR, "Saving the theme failed: ${it.javaClass.simpleName}: ${it.message}")) }
        }
    }

    /** Copies the chosen document into `files/packs` and lists it. */
    fun importPack(uri: Uri) {
        if (_ui.value.busy) return
        _ui.update { it.copy(busy = true) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val pack = packStore.import(uri)
                log(LogLine(LogLevel.OK, "Imported pack ${pack.name} (${pack.id}, ${pack.drawableCount} drawables)"))
            } catch (t: Exception) {
                log(LogLine(LogLevel.ERROR, "Import failed: ${t.message ?: t.javaClass.simpleName}"))
            } finally {
                _ui.update { it.copy(busy = false) }
                refreshPacks()
            }
        }
    }

    /** Deletes pack [id]; a deleted selected pack falls back to colours only. */
    fun deletePack(id: String) {
        if (_ui.value.busy) return
        _ui.update { it.copy(busy = true) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val removed = packStore.delete(id)
                if (_ui.value.selectedPackId == id) selectPack(null)
                log(LogLine(if (removed) LogLevel.OK else LogLevel.WARN, if (removed) "Removed pack $id" else "Pack $id was not found"))
            } catch (t: Exception) {
                log(LogLine(LogLevel.ERROR, "Removing pack $id failed: ${t.message ?: t.javaClass.simpleName}"))
            } finally {
                _ui.update { it.copy(busy = false) }
                refreshPacks()
            }
        }
    }

    fun setCatalogueUrl(url: String) = _ui.update { it.copy(catalogue = it.catalogue.copy(url = url)) }

    /** Fetches `index.json` from the catalogue URL; errors show in the dialog, not only the log. */
    fun loadCatalogue() {
        if (_ui.value.catalogue.loading) return
        val url = _ui.value.catalogue.url
        _ui.update { it.copy(catalogue = it.catalogue.copy(loading = true, error = null)) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val entries = packStore.fetchCatalogue(url)
                _ui.update { it.copy(catalogue = it.catalogue.copy(entries = entries, loading = false, error = null)) }
                log(LogLine(LogLevel.OK, "Catalogue: ${entries.size} packs at ${PackStore.normaliseBaseUrl(url)}"))
            } catch (t: Exception) {
                val message = t.message ?: t.javaClass.simpleName
                _ui.update { it.copy(catalogue = it.catalogue.copy(loading = false, error = message)) }
                log(LogLine(LogLevel.ERROR, "Catalogue failed: $message"))
            }
        }
    }

    /** Downloads [entry] into `files/packs` with progress in [CatalogueState.downloading]. */
    fun downloadPack(entry: CatalogueEntry) {
        if (_ui.value.busy || entry.id in _ui.value.catalogue.downloading) return
        val url = _ui.value.catalogue.url
        _ui.update { it.copy(busy = true, catalogue = it.catalogue.copy(downloading = it.catalogue.downloading + (entry.id to 0f))) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val pack = packStore.download(url, entry) { read, total ->
                    val fraction = if (total > 0) (read.toFloat() / total).coerceIn(0f, 1f) else 0f
                    _ui.update { it.copy(catalogue = it.catalogue.copy(downloading = it.catalogue.downloading + (entry.id to fraction))) }
                }
                log(LogLine(LogLevel.OK, "Downloaded pack ${pack.name} (${pack.id}, ${pack.bytes / 1024} KiB)"))
            } catch (t: Exception) {
                log(LogLine(LogLevel.ERROR, "Download of ${entry.name} failed: ${t.message ?: t.javaClass.simpleName}"))
            } finally {
                _ui.update { it.copy(busy = false, catalogue = it.catalogue.copy(downloading = it.catalogue.downloading - entry.id)) }
                refreshPacks()
            }
        }
    }

    /** Removes the compiled theme and gives the targets back to Samsung's SemWT overlays. */
    fun removeTheme() = runTask { service, _ ->
        val ok = applier.remove(service, ::log)
        _ui.update { it.copy(lastApplySummary = null) }
        ok
    }

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

    /** Keeps the on-screen log bounded: the full record is the build log file. */
    private fun log(line: LogLine) {
        val text = if (line.text.length > MAX_LOG_CHARS) line.text.take(MAX_LOG_CHARS) + " …" else line.text
        val bounded = if (text === line.text) line else line.copy(text = text)
        _ui.update { it.copy(log = (it.log + bounded).takeLast(MAX_LOG_LINES)) }
    }

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
        private const val DEFAULT_SEED = 0xFF00E5FF.toInt()
        private const val MAX_LOG_LINES = 400
        private const val MAX_LOG_CHARS = 2000
        private const val OPAQUE = 0xFF000000.toInt()
        private const val SAFE_MODE_TEXT = "Safe mode: Hex overlays were turned off after repeated restarts"
        private const val SAFE_MODE_FAILED_TEXT =
            "Safe mode: the device restarted repeatedly, but Hex overlays could not be turned off automatically"

        val androidVersion: String get() = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
    }
}
