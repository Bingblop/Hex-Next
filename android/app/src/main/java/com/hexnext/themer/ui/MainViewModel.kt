package com.hexnext.themer.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hexnext.engine.compile.LiveOption
import com.hexnext.engine.compile.CustomFont
import com.hexnext.engine.compile.ThemeMode
import com.hexnext.engine.compile.ThemeSpec as EngineThemeSpec
import com.hexnext.engine.compile.withCustomBackground
import com.hexnext.engine.compile.withCustomIcon
import com.hexnext.engine.compile.withElementEnabled
import com.hexnext.engine.compile.withElementPack
import com.hexnext.engine.compile.withFont
import com.hexnext.engine.compile.withOption
import com.hexnext.engine.compile.withSynthesizeMissingIcons
import com.hexnext.engine.pack.Pack
import com.hexnext.themer.device.CompatibilityCheck
import com.hexnext.themer.device.RootTools
import com.hexnext.themer.safety.ApplyGuard
import com.hexnext.themer.shizuku.ShizukuManager
import com.hexnext.themer.shizuku.ShizukuState
import com.hexnext.themer.shizuku.ThemeService
import com.hexnext.themer.theme.BackgroundSlots
import com.hexnext.themer.theme.CatalogueEntry
import com.hexnext.themer.theme.CustomAssetStore
import com.hexnext.themer.theme.InstalledPack
import com.hexnext.themer.theme.LogLevel
import com.hexnext.themer.theme.LogLine
import com.hexnext.themer.theme.PackStore
import com.hexnext.themer.theme.SemWtController
import com.hexnext.themer.theme.ThemeApplier
import com.hexnext.themer.theme.ThemePreviewer
import com.hexnext.themer.theme.ThemeStore
import com.hexnext.themer.theme.formatOneUi
import com.hexnext.themer.ui.settings.AppSettingsStore
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
import java.io.File

/** The full-screen destinations of the app; [MainViewModel.screen] holds the current one. */
enum class Screen { MAIN, SETTINGS, DIAGNOSTICS, AI_PROVIDERS, ONBOARDING }

/** What a [Notice] offers to do besides dismissing itself. */
enum class NoticeAction { OPEN_DIAGNOSTICS, REQUEST_PERMISSION, RETRY_APPLY, RETRY_REMOVE }

/**
 * One transient message for the snackbar: the outcome of an apply, remove or import, with an
 * action the user can take right there. The log card keeps the detailed lines.
 */
data class Notice(
    val message: String,
    val actionLabel: String? = null,
    val action: NoticeAction? = null,
    /** True for a failed outcome (stronger haptic, error colours). */
    val isError: Boolean = false,
    /** True for a confirmed success (success haptic). */
    val isSuccess: Boolean = false,
    /** Distinguishes two identical notices in a row so each one shows. */
    val id: Long = System.nanoTime(),
)

data class UiState(
    /** Accent seed (opaque ARGB) of the compiled theme and the Diagnostics Settings test. */
    val seed: Int,
    val busy: Boolean = false,
    /** The pending snackbar message; null once shown (see [MainViewModel.consumeNotice]). */
    val notice: Notice? = null,
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
    /** Imported assets under `files/custom/<icons|backgrounds|fonts>`. */
    val customIcons: List<CustomAssetStore.Asset> = emptyList(),
    val customBackgrounds: List<CustomAssetStore.Asset> = emptyList(),
    val customFonts: List<CustomAssetStore.Asset> = emptyList(),
    /** Background drawables of the last scan a custom background can replace; empty without a scan. */
    val backgroundSlots: List<BackgroundSlots.Slot> = emptyList(),
    /** Drawables a custom icon can replace, grouped by [ThemePreviewer.IconSlot.group]. */
    val iconSlots: List<ThemePreviewer.IconSlot> = emptyList(),
    /** Why the slot lists are empty when building them failed; null without a failure (no scan yet, or fine). */
    val slotsError: String? = null,
    /** [RootTools.Tool.name] → the newest APK URL (the pinned one until resolved). */
    val rootToolLinks: Map<String, String> = RootTools.ALL.associate { it.name to it.pinnedApk },
    /** "Android 17 · One UI 9.0" from the compatibility check. */
    val versionLine: String = CompatibilityCheck.versionLine(),
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
    val background: Bitmap? = null,
    val myFiles: List<ThemePreviewer.PreviewIcon> = emptyList(),
    val fontFile: String? = null,
    val fontName: String? = null,
    val synthesised: Set<String> = emptySet(),
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
    private val assets = CustomAssetStore(app)
    private val appSettings = AppSettingsStore.get(app)
    private var safeModeChecked = false
    private var semwtReasserted = false

    /** The debounced preview render in flight; only touched on the main thread. */
    private var previewJob: Job? = null

    /** True when a preview was requested while [UiState.busy]; it runs once busy ends. */
    private var previewPending = false

    /** Signature of the spec parts the slot catalogue depends on, as of its last computation. */
    private var slotsSignature: String? = null

    val shizuku: StateFlow<ShizukuState> = ShizukuManager.state

    private val _ui = MutableStateFlow(initialState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    /** Screens the user came through, so back returns to them (settings → AI providers → back → settings). */
    private val backStack = ArrayDeque<Screen>()

    private val _screen = MutableStateFlow(if (appSettings.settings.value.onboardingDone) Screen.MAIN else Screen.ONBOARDING)

    /** The screen on display; the setup guide on the very first launch, the editor otherwise. */
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    // ---- Navigation ----

    /** Shows [screen], remembering the current one for [navigateBack]. Navigating to the current screen is a no-op. */
    fun navigate(screen: Screen) {
        val current = _screen.value
        if (current == screen) return
        if (screen == Screen.MAIN) backStack.clear() else backStack.addLast(current)
        _screen.value = screen
    }

    /** Returns to the previous screen, or to the editor when there is none. */
    fun navigateBack() {
        _screen.value = backStack.removeLastOrNull() ?: Screen.MAIN
    }

    /** Marks the setup guide as seen (finished or skipped) and leaves it. */
    fun finishOnboarding() {
        appSettings.setOnboardingDone(true)
        navigateBack()
    }

    /** Clears [UiState.notice] once the snackbar has shown it. */
    fun consumeNotice(id: Long) = _ui.update { if (it.notice?.id == id) it.copy(notice = null) else it }

    private fun postNotice(notice: Notice) = _ui.update { it.copy(notice = notice) }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val stored = themeStore.load()
            _ui.update { it.copy(spec = stored) }
            refreshPreview()
            val seeded = runCatching { packStore.seedBundled() }.getOrElse { PackStore.Seeded(emptyList(), mapOf("assets/packs" to "${it.javaClass.simpleName}: ${it.message}")) }
            if (seeded.added.isNotEmpty()) log(LogLine(LogLevel.INFO, "Bundled test packs installed: ${seeded.added.joinToString()}"))
            seeded.failed.forEach { (name, why) -> log(LogLine(LogLevel.WARN, "Bundled pack $name not installed: $why")) }
            refreshPacks()
            refreshAssets()
        }
        viewModelScope.launch {
            for (tool in RootTools.ALL) {
                val url = RootTools.latestApkUrl(tool)
                _ui.update { it.copy(rootToolLinks = it.rootToolLinks + (tool.name to url)) }
            }
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
        oneUiVersion = CompatibilityCheck.oneUiName(),
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
    fun applyTheme() = runTask(retry = NoticeAction.RETRY_APPLY) { service, _ ->
        val spec = specToApply()
        val report = applier.apply(service, spec, ::log)
        _ui.update { it.copy(lastApplySummary = report.summary()) }
        if (report.success) {
            postNotice(Notice("Theme applied", isSuccess = true))
        } else {
            postNotice(Notice("Theme could not be applied", actionLabel = "Diagnostics", action = NoticeAction.OPEN_DIAGNOSTICS, isError = true))
        }
        report.success
    }

    /** Replaces the whole stored theme (the "Describe your theme" card hands back a mapped spec). */
    fun replaceSpec(spec: EngineThemeSpec) = updateSpec { spec }

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
                it.copy(
                    preview = PreviewState(
                        loading = false,
                        icons = rendered.icons,
                        accent = rendered.accent,
                        note = rendered.note,
                        background = rendered.background,
                        myFiles = rendered.myFiles,
                        fontFile = rendered.fontFile,
                        fontName = rendered.fontName,
                        synthesised = rendered.synthesised,
                    ),
                )
            }
            val signature = slotsSignatureOf(spec)
            if (signature != slotsSignature) {
                slotsSignature = signature
                val catalogue = withContext(Dispatchers.Default) { previewer.slots(spec) }
                _ui.update { it.copy(iconSlots = catalogue.icons, backgroundSlots = catalogue.backgrounds, slotsError = catalogue.error) }
            }
        }
    }

    /** The slot catalogue changes only with the packs, elements and options of the theme, not with colours or custom files. */
    private fun slotsSignatureOf(spec: EngineThemeSpec): String =
        listOf(spec.mode, spec.simplePackId, spec.elementPacks, spec.elementEnabled, spec.elementOptions, _ui.value.packs.map { it.id }).toString()

    /** Forces the slot catalogue to be recomputed with the next preview (after a scan or pack change). */
    fun invalidateSlots() {
        slotsSignature = null
        refreshPreview()
    }

    // ---- Custom assets ----

    /** The stored file of [asset] (for thumbnails and font rendering). */
    fun assetFile(asset: CustomAssetStore.Asset): File = assets.file(asset.kind, asset.fileName)

    /** Re-reads `files/custom`; a spec that references a file that is gone is cleaned up. */
    fun refreshAssets() {
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                Triple(
                    assets.list(CustomAssetStore.Kind.ICON),
                    assets.list(CustomAssetStore.Kind.BACKGROUND),
                    assets.list(CustomAssetStore.Kind.FONT),
                )
            }
            result.onSuccess { (icons, backgrounds, fonts) ->
                _ui.update { it.copy(customIcons = icons, customBackgrounds = backgrounds, customFonts = fonts) }
                val iconFiles = icons.mapTo(HashSet()) { it.fileName }
                val backgroundFiles = backgrounds.mapTo(HashSet()) { it.fileName }
                val fontFiles = fonts.mapTo(HashSet()) { it.fileName }
                val spec = _ui.value.spec
                val staleIcons = spec.customIcons.filterValues { it !in iconFiles }
                val staleBackgrounds = spec.customBackgrounds.filterValues { it !in backgroundFiles }
                val font = spec.customFont
                val bold = font?.bold
                val staleFont = font != null && font.regular !in fontFiles
                val staleBold = bold != null && bold !in fontFiles
                if (staleIcons.isNotEmpty() || staleBackgrounds.isNotEmpty() || staleFont || staleBold) {
                    log(LogLine(LogLevel.WARN, "Custom files that are no longer stored were removed from the theme"))
                    updateSpec { current ->
                        current.copy(
                            customIcons = current.customIcons.filterValues { v -> v in iconFiles },
                            customBackgrounds = current.customBackgrounds.filterValues { v -> v in backgroundFiles },
                            customFont = current.customFont?.let { f ->
                                val b = f.bold
                                when {
                                    f.regular !in fontFiles -> null
                                    b != null && b !in fontFiles -> f.copy(bold = null)
                                    else -> f
                                }
                            },
                        )
                    }
                }
            }.onFailure { log(LogLine(LogLevel.ERROR, "Listing custom files failed: ${it.javaClass.simpleName}: ${it.message}")) }
        }
    }

    /** Converts the chosen image to an overlay-ready PNG under `files/custom/icons`. */
    fun importCustomIcon(uri: Uri) = importAsset("icon") { assets.importImage(uri, CustomAssetStore.Kind.ICON) }

    /** Converts the chosen image to an overlay-ready PNG under `files/custom/backgrounds`. */
    fun importCustomBackground(uri: Uri) = importAsset("background") { assets.importImage(uri, CustomAssetStore.Kind.BACKGROUND) }

    /** Validates and copies the chosen `.ttf` / `.otf` under `files/custom/fonts`. */
    fun importFont(uri: Uri) = importAsset("font") { assets.importFont(uri) }

    private fun importAsset(what: String, block: () -> CustomAssetStore.Asset) {
        if (_ui.value.busy) return
        _ui.update { it.copy(busy = true) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val asset = block()
                val size = if (asset.width > 0) " (${asset.width}×${asset.height})" else ""
                log(LogLine(LogLevel.OK, "Imported $what ${asset.displayName}$size as ${asset.fileName}"))
            } catch (t: Exception) {
                log(LogLine(LogLevel.ERROR, "Importing the $what failed: ${t.message ?: t.javaClass.simpleName}"))
                postNotice(Notice("Couldn't import the $what", isError = true))
            } finally {
                _ui.update { it.copy(busy = false) }
                refreshAssets()
            }
        }
    }

    /** Deletes [asset] and every theme entry that used it. */
    fun deleteAsset(asset: CustomAssetStore.Asset) {
        if (_ui.value.busy) return
        _ui.update { it.copy(busy = true) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                assets.delete(asset)
                updateSpec { spec ->
                    when (asset.kind) {
                        CustomAssetStore.Kind.ICON -> spec.copy(customIcons = spec.customIcons.filterValues { it != asset.fileName })
                        CustomAssetStore.Kind.BACKGROUND -> spec.copy(customBackgrounds = spec.customBackgrounds.filterValues { it != asset.fileName })
                        CustomAssetStore.Kind.FONT -> {
                            val font = spec.customFont
                            when {
                                font == null -> spec
                                font.regular == asset.fileName -> spec.withFont(null)
                                font.bold == asset.fileName -> spec.withFont(font.copy(bold = null))
                                else -> spec
                            }
                        }
                    }
                }
                log(LogLine(LogLevel.OK, "Removed ${asset.displayName}"))
            } catch (t: Exception) {
                log(LogLine(LogLevel.ERROR, "Removing ${asset.displayName} failed: ${t.message ?: t.javaClass.simpleName}"))
            } finally {
                _ui.update { it.copy(busy = false) }
                refreshAssets()
            }
        }
    }

    /** Uses the icon [file] for the drawable [slotKey] (`pkg:drawable/name`); null gives the slot back to the pack. */
    fun assignIcon(slotKey: String, file: String?) = updateSpec { it.withCustomIcon(slotKey, file) }

    /** Uses the background [file] for [slotKey]; null gives the slot back to the pack or Samsung. */
    fun assignBackground(slotKey: String, file: String?) = updateSpec { it.withCustomBackground(slotKey, file) }

    /** Makes [asset] the system font of the theme (keeping a bold file that is not the same asset); null restores the default. */
    fun setFont(asset: CustomAssetStore.Asset?) = updateSpec { spec ->
        if (asset == null) {
            spec.withFont(null)
        } else {
            val bold = spec.customFont?.bold?.takeIf { it != asset.fileName }
            spec.withFont(CustomFont(regular = asset.fileName, bold = bold, displayName = asset.displayName))
        }
    }

    /** The bold weight of the chosen font: another font file, or null to reuse the regular file. */
    fun setFontBold(asset: CustomAssetStore.Asset?) = updateSpec { spec ->
        val font = spec.customFont ?: return@updateSpec spec
        spec.withFont(font.copy(bold = asset?.fileName?.takeIf { it != font.regular }))
    }

    /** Whether the compiler draws pack-style icons for Settings rows the pack lacks. */
    fun setSynthesizeMissingIcons(enabled: Boolean) = updateSpec { it.withSynthesizeMissingIcons(enabled) }

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
                postNotice(Notice("Couldn't import the pack file", isError = true))
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
                postNotice(Notice("Couldn't download ${entry.name}", isError = true))
            } finally {
                _ui.update { it.copy(busy = false, catalogue = it.catalogue.copy(downloading = it.catalogue.downloading - entry.id)) }
                refreshPacks()
            }
        }
    }

    /** Removes the compiled theme and gives the targets back to Samsung's SemWT overlays. */
    fun removeTheme() = runTask(retry = NoticeAction.RETRY_REMOVE) { service, _ ->
        val ok = applier.remove(service, ::log)
        _ui.update { it.copy(lastApplySummary = null) }
        if (ok) {
            postNotice(Notice("Theme removed", isSuccess = true))
        } else {
            postNotice(Notice("Theme could not be removed", actionLabel = "Diagnostics", action = NoticeAction.OPEN_DIAGNOSTICS, isError = true))
        }
        ok
    }

    /**
     * Runs [block] on the privileged service with the busy flag held. [retry] is the snackbar
     * action offered when [block] throws; null shows the failure without an action.
     */
    private fun runTask(retry: NoticeAction?, block: (com.hexnext.themer.IThemeService, ShizukuState.Ready) -> Boolean) {
        val ready = ShizukuManager.state.value as? ShizukuState.Ready ?: run {
            log(LogLine(LogLevel.ERROR, "Shizuku (UID 0 / UID 1000) or SU isn't ready."))
            val state = ShizukuManager.state.value
            postNotice(
                if (state == ShizukuState.PermissionNeeded || state == ShizukuState.PermissionDenied) {
                    Notice("Hex Next needs Shizuku access first", actionLabel = "Allow", action = NoticeAction.REQUEST_PERMISSION, isError = true)
                } else {
                    Notice("Start Shizuku with root or grant root access first", isError = true)
                },
            )
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
                postNotice(
                    Notice(
                        "Something went wrong. Check the log for details",
                        actionLabel = if (retry != null) "Retry" else null,
                        action = retry,
                        isError = true,
                    ),
                )
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
        _ui.update { it.copy(oneUiVersion = version ?: CompatibilityCheck.oneUiName() ?: "One UI") }
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
