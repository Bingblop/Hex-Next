package com.hexnext.themer.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hexnext.engine.compile.LiveOption
import com.hexnext.engine.compile.ThemeMode
import com.hexnext.engine.compile.ThemeSpec as EngineThemeSpec
import com.hexnext.engine.compile.withElementEnabled
import com.hexnext.engine.compile.withElementPack
import com.hexnext.engine.compile.withOption
import com.hexnext.engine.pack.Pack
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
import com.hexnext.themer.theme.ThemePreviewer
import com.hexnext.themer.theme.ThemeStore
import com.hexnext.themer.theme.formatOneUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
    /** The stored theme (`files/theme.json`) without the seed; [EngineThemeSpec.default] until loaded. */
    val spec: EngineThemeSpec = EngineThemeSpec.default(),
    val catalogue: CatalogueState = CatalogueState(),
    val preview: PreviewState = PreviewState(),
) {
    /** [EngineThemeSpec.simplePackId] of the stored theme; null is "colours only". */
    val selectedPackId: String? get() = spec.simplePackId
}

/** The "Preview" card: Settings dashboard icons of the theme to apply, from [ThemePreviewer]. */
data class PreviewState(
    val loading: Boolean = false,
    val icons: List<ThemePreviewer.PreviewIcon> = emptyList(),
    val accent: Int? = null,
    val note: String? = null,
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
    private val previewer = ThemePreviewer(app)
    private var safeModeChecked = false
    private var semwtReasserted = false

    /** The debounced preview render in flight; only touched on the main thread. */
    private var previewJob: Job? = null

    /** True when a preview was requested while [UiState.busy]; it runs once busy ends. */
    private var previewPending = false

    val shizuku: StateFlow<ShizukuState> = ShizukuManager.state

    private val _ui = MutableStateFlow(initialState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val stored = themeStore.load()
            _ui.update { it.copy(spec = stored) }
            refreshPreview()
            val seeded = runCatching { packStore.seedBundled() }.getOrElse { PackStore.Seeded(emptyList(), mapOf("assets/packs" to "${it.javaClass.simpleName}: ${it.message}")) }
            if (seeded.added.isNotEmpty()) log(LogLine(LogLevel.INFO, "Bundled test packs installed: ${seeded.added.joinToString()}"))
            seeded.failed.forEach { (name, why) -> log(LogLine(LogLevel.WARN, "Bundled pack $name not installed: $why")) }
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
        viewModelScope.launch {
            _ui.map { it.busy }.distinctUntilChanged().collect { busy ->
                if (busy) {
                    // An apply must not compete with a render for CPU and pack reads.
                    previewJob?.cancel()
                    previewPending = true
                } else if (previewPending) {
                    refreshPreview()
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
        refreshPreview()
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

    /** The stored theme ([UiState.spec]) with the seed preference applied on top. */
    private fun specToApply(): EngineThemeSpec {
        val state = _ui.value
        return state.spec.copy(source = state.spec.source.copy(seedArgb = state.seed))
    }

    // ---- Theme spec ----

    /**
     * Applies [block] to [UiState.spec] at once and persists the result on IO. The write takes
     * the spec from the state under the store's lock, so concurrent saves can never leave an
     * older spec in `theme.json`. Every spec mutation goes through here, so each one refreshes
     * the preview.
     */
    private fun updateSpec(block: (EngineThemeSpec) -> EngineThemeSpec) {
        _ui.update { it.copy(spec = block(it.spec)) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                themeStore.update { _ui.value.spec }
            }.onFailure { log(LogLine(LogLevel.ERROR, "Saving the theme failed: ${it.javaClass.simpleName}: ${it.message}")) }
        }
        refreshPreview()
    }

    /**
     * Simple (one pack for everything) or advanced (a pack per element). Simple mode shows no
     * per-part control, so switching to it turns every part back on; the advanced pack and
     * option choices are kept for the next switch to advanced.
     */
    fun setMode(mode: ThemeMode) = updateSpec {
        it.copy(mode = mode, elementEnabled = if (mode == ThemeMode.SIMPLE) emptyMap() else it.elementEnabled)
    }

    /**
     * Sets [live] of [pack] to [value]: for every element the option gates when [elementId] is
     * null (simple mode), for that element only otherwise (advanced mode).
     */
    fun setOption(pack: Pack, live: LiveOption, value: String, elementId: String? = null) = updateSpec {
        it.withOption(pack, live, value, elementIds = if (elementId == null) live.elementIds else setOf(elementId))
    }

    /**
     * Advanced mode: [elementId] uses [packId] (null = same as the default pack). Choosing a pack
     * for a part also switches the part back on, so the choice takes effect.
     */
    fun setElementPack(elementId: String, packId: String?) = updateSpec {
        it.withElementPack(elementId, packId).withElementEnabled(elementId, true)
    }

    /** Advanced mode: a part that is off gets no drawables from any pack (colours only). */
    fun setElementEnabled(elementId: String, enabled: Boolean) = updateSpec { it.withElementEnabled(elementId, enabled) }

    // ---- Preview ----

    /**
     * Re-renders the preview of [specToApply] after a short debounce, cancelling a render that is
     * still waiting or running. While an apply is in progress the request is deferred and runs
     * once when [UiState.busy] clears. Safe to call from any thread.
     */
    fun refreshPreview() {
        viewModelScope.launch { schedulePreview() }
    }

    /** Main thread only (viewModelScope). */
    private fun schedulePreview() {
        if (_ui.value.busy) {
            previewPending = true
            return
        }
        previewPending = false
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            delay(PREVIEW_DEBOUNCE_MS)
            if (_ui.value.busy) {
                // Busy flipped during the debounce: defer to the busy collector.
                previewPending = true
                return@launch
            }
            _ui.update { it.copy(preview = it.preview.copy(loading = true)) }
            val spec = specToApply()
            val rendered = withContext(Dispatchers.Default) { previewer.render(spec) }
            _ui.update {
                it.copy(preview = PreviewState(loading = false, icons = rendered.icons, accent = rendered.accent, note = rendered.note))
            }
        }
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
                val spec = _ui.value.spec
                val selected = spec.simplePackId
                if (selected != null && selected !in ids) {
                    log(LogLine(LogLevel.WARN, "Theme pack $selected is no longer installed; colours only"))
                    selectPack(null)
                }
                val gone = spec.elementPacks.filterValues { it !in ids }
                if (gone.isNotEmpty()) {
                    log(LogLine(LogLevel.WARN, "Pack ${gone.values.distinct().joinToString()} is no longer installed; ${gone.keys.joinToString()} use the default pack"))
                    updateSpec { it.copy(elementPacks = it.elementPacks.filterValues { v -> v in ids }) }
                }
                refreshPreview()
            }.onFailure { log(LogLine(LogLevel.ERROR, "Listing packs failed: ${it.javaClass.simpleName}: ${it.message}")) }
        }
    }

    /** Chooses the pack of the next apply (null = colours only) and persists it in the theme. */
    fun selectPack(id: String?) = updateSpec { it.copy(simplePackId = id) }

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
        private const val PREVIEW_DEBOUNCE_MS = 400L
        private const val OPAQUE = 0xFF000000.toInt()
        private const val SAFE_MODE_TEXT = "Safe mode: Hex overlays were turned off after repeated restarts"
        private const val SAFE_MODE_FAILED_TEXT =
            "Safe mode: the device restarted repeatedly, but Hex overlays could not be turned off automatically"

        val androidVersion: String get() = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
    }
}
