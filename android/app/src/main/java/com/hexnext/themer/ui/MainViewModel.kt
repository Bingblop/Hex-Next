package com.hexnext.themer.ui

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.hexnext.engine.compile.ApplyReport
import com.hexnext.engine.compile.ColorGroup
import com.hexnext.engine.compile.ColorGroupMatcher
import com.hexnext.engine.compile.ColorPin
import com.hexnext.engine.compile.GroupCount
import com.hexnext.engine.compile.IconColorStyle
import com.hexnext.engine.compile.LiveOption
import com.hexnext.engine.compile.CustomFont
import com.hexnext.engine.compile.OneUiRules
import com.hexnext.engine.compile.ThemeExport
import com.hexnext.engine.compile.ThemeFormatException
import com.hexnext.engine.compile.ThemeMode
import com.hexnext.engine.compile.ValueControl
import com.hexnext.engine.compile.ValuePlanner
import com.hexnext.engine.compile.ValueSetting
import com.hexnext.engine.compile.ThemeSpec as EngineThemeSpec
import com.hexnext.engine.compile.withCustomBackground
import com.hexnext.engine.compile.withCustomIcon
import com.hexnext.engine.compile.withElementEnabled
import com.hexnext.engine.compile.withElementPack
import com.hexnext.engine.compile.withFont
import com.hexnext.engine.compile.withOption
import com.hexnext.engine.compile.withAllGroupsAuto
import com.hexnext.engine.compile.withGroupColor
import com.hexnext.engine.compile.withSynthesizeMissingIcons
import com.hexnext.engine.pack.Pack
import com.hexnext.engine.palette.PaletteStyle
import com.hexnext.engine.palette.ThemeAdjustments
import com.hexnext.engine.palette.ThemeResolver
import com.hexnext.themer.BuildConfig
import com.hexnext.themer.ServiceTasks
import com.hexnext.themer.billing.EntitlementState
import com.hexnext.themer.billing.EntitlementsProvider
import com.hexnext.themer.billing.OfficialPacks
import com.hexnext.themer.billing.PackAccess
import com.hexnext.themer.billing.ProductIds
import com.hexnext.themer.billing.PaidFeature
import com.hexnext.themer.device.CompatibilityCheck
import com.hexnext.themer.device.RootTools
import com.hexnext.themer.safety.ApplyGuard
import com.hexnext.themer.safety.EarlyRecoveryHook
import com.hexnext.themer.shizuku.ShizukuManager
import com.hexnext.themer.shizuku.ShizukuState
import com.hexnext.themer.shizuku.ThemeService
import com.hexnext.themer.theme.BackgroundSlots
import com.hexnext.themer.theme.Capability
import com.hexnext.themer.theme.CatalogueEntry
import com.hexnext.themer.theme.CustomAssetStore
import com.hexnext.themer.theme.FontInstaller
import com.hexnext.themer.theme.InstalledPack
import com.hexnext.themer.theme.InstalledPacks
import com.hexnext.themer.theme.LogLevel
import com.hexnext.themer.theme.LogLine
import com.hexnext.themer.theme.MonetSeed
import com.hexnext.themer.theme.PackStore
import com.hexnext.themer.theme.SemWtController
import com.hexnext.themer.theme.SnapshotBuilder
import com.hexnext.themer.theme.StyledIconPreview
import com.hexnext.themer.theme.ThemeApplier
import com.hexnext.themer.theme.ThemePreviewer
import com.hexnext.themer.theme.ThemeStore
import com.hexnext.themer.theme.formatOneUi
import com.hexnext.themer.ui.settings.AppSettingsStore
import com.hexnext.themer.ui.studio.PreviewIcon
import com.hexnext.themer.ui.studio.QsShapes
import com.hexnext.themer.ui.studio.StudioPreviewState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.WeakHashMap

/** The full-screen destinations of the app; [MainViewModel.screen] holds the current one. */
enum class Screen { MAIN, SETTINGS, DIAGNOSTICS, AI_PROVIDERS, ONBOARDING, RESOURCES }

/** What a [Notice] offers to do besides dismissing itself. */
enum class NoticeAction { OPEN_DIAGNOSTICS, REQUEST_PERMISSION, RETRY_APPLY, RETRY_REMOVE, CONFIRM_WORKS, UNLOCK_PACK }

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
    /** The pack [NoticeAction.UNLOCK_PACK] starts the purchase of. */
    val packId: String? = null,
    /** Distinguishes two identical notices in a row so each one shows. */
    val id: Long = System.nanoTime(),
)

data class UiState(
    /** Accent seed (opaque ARGB) of the compiled theme and the Diagnostics Settings test. */
    val seed: Int,
    val busy: Boolean = false,
    /**
     * True only while the editor's Apply theme or Remove theme holds the theme service
     * ([ServiceTasks]). Pack downloads, imports and deletes set [busy] but not this.
     */
    val serviceBusy: Boolean = false,
    /** The pending snackbar message; null once shown (see [MainViewModel.consumeNotice]). */
    val notice: Notice? = null,
    val oneUiVersion: String? = null,
    /** Non-null while the "Hex Next overlays were turned off after repeated restarts" banner shows. */
    val safeModeBanner: String? = null,
    /** True when safe mode could not turn the overlays off: the banner then points to Diagnostics instead. */
    val safeModeFailed: Boolean = false,
    /** The last compiled-theme apply, for the "Last applied" line of the Apply card; null after a remove. */
    val lastApply: ApplyReport? = null,
    /**
     * True while the last apply has not been confirmed with "It works": restart protection is
     * armed and two restarts within 10 minutes turn the overlays off, but only when root survives
     * a restart (a temporary root such as DFRoot does not, so the UI must not promise it).
     */
    val applyUnconfirmed: Boolean = false,
    /**
     * True when [applyUnconfirmed] but the 10-minute window has passed: restarts can no longer
     * turn the overlays off, so the Apply card must not say protection is on.
     */
    val applyProtectionExpired: Boolean = false,
    /** True when the editor asked for a phone scan; Diagnostics starts it and clears the flag. */
    val scanRequested: Boolean = false,
    /**
     * Pack zips that could be read: `files/packs`, plus locked catalogue packs downloaded into
     * `files/packs-preview` for the preview only (their ids are in [previewOnlyPackIds]).
     */
    val packs: List<InstalledPack> = emptyList(),
    /** Packs of [packs] that sit in the preview-only directory: shown and previewed, never built. */
    val previewOnlyPackIds: Set<String> = emptySet(),
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
    /**
     * What the connection can change, from the Shizuku uid: everything with root, the system
     * palette only through the ADB shell, nothing while disconnected. Gates the icon, background and font cards.
     */
    val capability: Capability = Capability.NONE,
    /** Which packs this install has unlocked, with the store's prices (see billing/PackEntitlements). */
    val entitlement: EntitlementState = EntitlementState(),
    /** Test packs bundled into a debuggable build, which count as unlocked there; empty otherwise. */
    val bundledPackIds: Set<String> = emptySet(),
    /** Packs seen in a fetched catalogue or downloaded from one: paid even when their id is not in PaidPacks. */
    val officialPackIds: Set<String> = emptySet(),
    /** False in a build with no store (everything is unlocked): no prices, no Unlock buttons. */
    val canPurchase: Boolean = false,
) {
    /** [EngineThemeSpec.simplePackId] of the stored theme; null is "colours only". */
    val selectedPackId: String? get() = spec.simplePackId

    /** True when pack [id] may be downloaded, selected and applied. */
    fun isPackUnlocked(id: String): Boolean = PackAccess.isUnlocked(entitlement, id, bundledPackIds, officialPackIds)

    /** [isPackUnlocked], or the cached purchase hint while the store has not answered: for display only. */
    fun packLooksUnlocked(id: String): Boolean = PackAccess.looksUnlocked(entitlement, id, bundledPackIds, officialPackIds)

    /**
     * The packs the current theme uses that are not unlocked: the preview shows them, Apply
     * refuses them. For display only (the cached hint); Apply checks [isPackUnlocked].
     */
    fun previewOnlyPacksInTheme(): Set<String> = PackAccess.lockedPacksIn(spec) { packLooksUnlocked(it) }

    /** The display name of pack [id] from the installed packs or the catalogue; the id capitalised otherwise. */
    fun packDisplayName(id: String): String =
        packs.firstOrNull { it.id == id }?.name
            ?: catalogue.entries.firstOrNull { it.id == id }?.name
            ?: id.replaceFirstChar { it.titlecase() }
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
    /** Names of the icons drawn from the app's built-in art (no pack covers them). */
    val builtIn: Set<String> = emptySet(),
    /** The mode [icons] and [myFiles] were rendered for: true for the night jobs, false for the day jobs. */
    val night: Boolean = true,
    /** The colours the compile plans for the mockup's targets ([ThemePreviewer.Preview.deviceColors]). */
    val deviceColors: com.hexnext.themer.ui.studio.DeviceColors = com.hexnext.themer.ui.studio.DeviceColors(),
)

/** The "Get packs" dialog: catalogue URL, its entries and the downloads in flight (id → 0..1). */
data class CatalogueState(
    val url: String = PackStore.DEFAULT_CATALOGUE_URL,
    val entries: List<CatalogueEntry> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val downloading: Map<String, Float> = emptyMap(),
)

class MainViewModel(app: Application, private val savedState: SavedStateHandle) : AndroidViewModel(app) {
    /**
     * Counted down once `theme.json` is in [UiState.spec]. Declared first: the init block's load
     * runs on IO and may finish before later properties exist (lab-018: NullPointerException).
     */
    private val specLoaded = java.util.concurrent.CountDownLatch(1)


    private val prefs = app.getSharedPreferences("hexnext", Context.MODE_PRIVATE)
    private val guard = ApplyGuard(app)
    private val applier = ThemeApplier(app)
    private val themeStore = ThemeStore(app)
    private val packStore = PackStore(app)

    /**
     * Locked catalogue packs downloaded so they can be browsed and previewed before purchase
     * ([ThemeApplier.previewPacksDir]). The applier never reads this directory; a zip moves to
     * [packStore] once its purchase is verified ([refreshPacks]).
     */
    private val previewPackStore = PackStore(app) { ThemeApplier.previewPacksDir(it) }
    private val previewer = ThemePreviewer(app)
    private val assets = CustomAssetStore(app)
    private val appSettings = AppSettingsStore.get(app)

    /** Pack unlocks of this build's store flavor; one instance per process, already started. */
    private val entitlements = EntitlementsProvider.get(app)

    /** Catalogue pack ids seen by this install (see [PackAccess.isPaid]). */
    private val officialPacks = OfficialPacks.get(app)
    private val debuggable = (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    private var safeModeChecked = false
    private var semwtReasserted = false

    /** The debounced preview render in flight; only touched on the main thread. */
    private var previewJob: Job? = null

    /** True when a preview was requested while [UiState.busy]; it runs once busy ends. */
    private var previewPending = false

    /** Signature of the spec parts the slot catalogue depends on, as of its last computation. */
    private var slotsSignature: String? = null

    val shizuku: StateFlow<ShizukuState> = ShizukuManager.state

    /**
     * The editor's on-screen build log, kept out of [UiState]: an apply logs many lines, and each
     * one would otherwise recompose every screen that reads [ui] (and the phone preview with it).
     */
    private val _log = MutableStateFlow<List<LogLine>>(emptyList())
    val log: StateFlow<List<LogLine>> = _log.asStateFlow()

    /** The newest log line's text, for the Apply bar's progress line; null with an empty log. */
    val lastLogLine: StateFlow<String?> = _log.map { it.lastOrNull()?.text }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Spec saves, conflated: each committed edit asks for one, and the writer saves the latest
     * spec once the edits pause ([SAVE_DEBOUNCE_MS]), so quick taps do not queue fsync'd writes.
     */
    private val saveRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val savePending = AtomicBoolean(false)

    private val _ui = MutableStateFlow(initialState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    // ---- Studio live preview ----

    /** The spec a drag is showing (live colours only, never saved); null when nothing is dragged. */
    private val draft = MutableStateFlow<EngineThemeSpec?>(null)

    /** The primary seed a drag is showing; null when nothing is dragged (the seed lives outside the spec). */
    private val draftSeed = MutableStateFlow<Int?>(null)
    private val _edits = MutableStateFlow(0)

    /** Counts committed user edits (spec changes and the seed); the preview peek opens in full on a new one. */
    val edits: StateFlow<Int> = _edits.asStateFlow()

    /** The group the Studio is editing; the phone preview outlines its elements. */
    private val highlight = MutableStateFlow<ColorGroup?>(null)

    /**
     * Whether the Studio mockup shows dark mode (its default) or light mode; icons are rendered
     * from the night or day jobs to match, since Apply colours them differently per mode.
     */
    private val previewNight = MutableStateFlow(true)

    /** Built-in badge icons for the preview when the compile preview has none (no scan, no pack icons). */
    private val styledIcons = StyledIconPreview(app)

    /**
     * Compose wrappers of the preview bitmaps, by bitmap identity, so an unchanged icon stays equal.
     * The values are weak too: a wrapper holds its bitmap strongly, so a strong value would keep its
     * own weak key reachable and no entry would ever be dropped.
     */
    private val imageCache: MutableMap<Bitmap, WeakReference<ImageBitmap>> = Collections.synchronizedMap(WeakHashMap())

    /**
     * The icon half of [studioPreview]: rendered from the debounced compile preview, or built-in
     * badges in the current icon style when it has none. Keyed on the committed spec (never the
     * draft), so a slider drag never re-renders icons.
     */
    private val iconPart: StateFlow<StudioIcons> = _ui
        // Only the fields icons depend on, so unrelated UiState changes (busy, notices) stop here.
        .map { IconSource(it.preview, it.spec, it.seed) }
        .distinctUntilChanged()
        .map {
            IconInputs(
                it.preview, it.spec.iconStyle, it.spec.source.copy(seedArgb = it.seed), it.spec.darkAdjustments, it.spec.lightAdjustments,
                settingsBuiltIn = drawsBuiltInIcons(it.spec, SETTINGS_ICONS_ELEMENT),
                myFilesBuiltIn = drawsBuiltInIcons(it.spec, MY_FILES_ICONS_ELEMENT),
                // The mode of the last render, not the toggle: until the new mode's render lands the
                // bitmaps are the old mode's, and the label and built-in badges must match them.
                night = it.preview.night,
            )
        }
        .distinctUntilChanged()
        .conflate()
        .map { studioIcons(it) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StudioIcons(loading = true))

    /**
     * Everything the Studio's phone mockup draws: the colours of the draft (or the stored spec) with
     * the primary seed, recomputed on every edit without a scan or a compile, plus the icons,
     * background and font of the last compile preview.
     */
    val studioPreview: StateFlow<StudioPreviewState> = combine(
        _ui.map { ColourInputs(it.spec, it.seed) }.distinctUntilChanged(),
        draft,
        draftSeed,
        highlight,
        iconPart,
    ) { base, draftSpec, seedDraft, highlighted, icons ->
        val spec = draftSpec ?: base.spec
        StudioPreviewState.colorsOf(spec, seedDraft ?: base.seed).copy(
            settingsIcons = icons.settings,
            myFilesIcons = icons.myFiles,
            background = icons.background,
            fontFile = icons.fontFile,
            fontName = icons.fontName,
            highlightedGroup = highlighted,
            qsPanelAlpha = spec.qsPanelAlpha.coerceAtLeast(EngineThemeSpec.MIN_QS_PANEL_ALPHA) / 255f,
            iconsLoading = icons.loading,
            hasScan = icons.hasScan,
            note = icons.note,
            iconsNight = icons.night,
            dragging = draftSpec != null || seedDraft != null,
            qsShapes = QsShapes.of(spec),
            deviceColors = icons.deviceColors,
        )
    }
        .conflate()
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StudioPreviewState.colorsOf(_ui.value.spec, _ui.value.seed))

    /** Resources per element group over every scanned app, for the element colour rows; empty without a scan. */
    private val _groupCounts = MutableStateFlow<Map<ColorGroup, GroupCount>>(emptyMap())
    val groupCounts: StateFlow<Map<ColorGroup, GroupCount>> = _groupCounts.asStateFlow()

    /**
     * The Quick panel shape controls over the last scan ([ValuePlanner.availableControls]), loaded
     * with [loadGroupCounts]; empty without a scan. A control without names on this phone is
     * hidden, one whose scan predates value lists shows "Rescan needed".
     */
    private val _valueControls = MutableStateFlow<Map<String, ValueControl>>(emptyMap())
    val valueControls: StateFlow<Map<String, ValueControl>> = _valueControls.asStateFlow()

    /** The scan stamp [_groupCounts] was computed from; null before the first count. Main thread only. */
    private var groupCountsStamp: String? = null

    /** True once the Studio showed the counts: a new scan then recounts them. */
    private var groupCountsWanted = false
    private var groupCountsJob: Job? = null

    /** Screens the user came through, so back returns to them (settings → AI providers → back → settings). */
    private val backStack = ArrayDeque<Screen>(
        savedState.get<ArrayList<String>>(KEY_BACK_STACK).orEmpty().mapNotNull { name -> Screen.entries.firstOrNull { it.name == name } }
    )

    /** Restored from [savedState] after process death; otherwise the setup guide or the editor. */
    private val _screen = MutableStateFlow(
        savedState.get<String>(KEY_SCREEN)?.let { name -> Screen.entries.firstOrNull { it.name == name } }
            ?: if (appSettings.settings.value.onboardingDone) Screen.MAIN else Screen.ONBOARDING
    )

    /** The screen on display; the setup guide on the very first launch, the editor otherwise. */
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    // ---- Navigation ----

    /** Shows [screen], remembering the current one for [navigateBack]. Navigating to the current screen is a no-op. */
    fun navigate(screen: Screen) {
        val current = _screen.value
        if (current == screen) return
        if (screen == Screen.MAIN) backStack.clear() else backStack.addLast(current)
        _screen.value = screen
        saveNavigation()
        refreshGuard()
        if (screen == Screen.MAIN) refreshLastApply()
    }

    /** Returns to the previous screen, or to the editor when there is none. */
    fun navigateBack() {
        _screen.value = backStack.removeLastOrNull() ?: Screen.MAIN
        saveNavigation()
        refreshGuard()
        if (_screen.value == Screen.MAIN) refreshLastApply()
    }

    /**
     * Re-reads the saved apply report into [UiState.lastApply]. Diagnostics ("Turn off all Hex
     * Next overlays") deletes it behind this view model's back, so the editor syncs on its return.
     * Skipped while an apply or remove runs ([UiState.serviceBusy]), which sets the report itself
     * when it ends; a pack download or import does not touch the report, so it does not skip this.
     */
    internal fun refreshLastApply() {
        if (_ui.value.serviceBusy) return
        viewModelScope.launch {
            val report = withContext(Dispatchers.IO) { ThemeApplier.lastReport(getApplication<Application>()) }
                .let { staleAfterSafeMode(it, guard.safeModeAt) }
            _ui.update { if (it.serviceBusy || it.lastApply == report) it else it.copy(lastApply = report) }
        }
    }

    /** Keeps the screen and back stack in [savedState] so they survive process death. */
    private fun saveNavigation() {
        savedState[KEY_SCREEN] = _screen.value.name
        savedState[KEY_BACK_STACK] = ArrayList(backStack.map { it.name })
    }

    /**
     * Re-reads the restart-protection guard into [UiState.applyUnconfirmed]. Diagnostics ("It
     * works", the Settings test) and safe mode change the guard behind this view model's back, so
     * the editor syncs on every screen change and when the activity resumes.
     *
     * The stability monitor can also trip after an apply, on its own thread, while the editor is
     * on display: it enters safe mode, turns the overlays off and rewrites the report as aborted.
     * So a safe mode this view model has not shown yet brings the banner up here, and a finished
     * report from before it no longer reads "Applied to N apps" (the rewrite may still be pending).
     */
    fun refreshGuard() {
        val unconfirmed = !guard.confirmed
        val expired = guard.protectionExpired()
        val safeModeAt = guard.safeModeAt
        _ui.update {
            // The "could not turn its overlays off" banner shows only for an unconfirmed apply;
            // once the guard is confirmed (Diagnostics turned the overlays off, or "It works"),
            // it no longer holds.
            val failedResolved = it.safeModeFailed && !unconfirmed
            val banner = when {
                safeModeAt > 0L && (it.safeModeBanner == null || failedResolved) -> SAFE_MODE_TEXT
                failedResolved -> null
                else -> it.safeModeBanner
            }
            val failed = if (banner === it.safeModeBanner) it.safeModeFailed else false
            val lastApply = staleAfterSafeMode(it.lastApply, safeModeAt)
            if (it.applyUnconfirmed == unconfirmed && it.applyProtectionExpired == expired &&
                it.safeModeBanner == banner && it.lastApply == lastApply
            ) {
                it
            } else {
                it.copy(
                    applyUnconfirmed = unconfirmed,
                    applyProtectionExpired = expired,
                    safeModeBanner = banner,
                    safeModeFailed = failed,
                    lastApply = lastApply,
                )
            }
        }
    }

    /**
     * [report] as aborted when safe mode ([safeModeAt], 0 when off) turned the overlays off after
     * it started: the theme it reports is no longer on, whatever the saved file still says.
     */
    private fun staleAfterSafeMode(report: ApplyReport?, safeModeAt: Long): ApplyReport? =
        if (report != null && safeModeAt > 0L && report.finished && report.startedAt < safeModeAt) {
            report.copy(status = ApplyReport.STATUS_ABORTED)
        } else {
            report
        }

    /** Marks the setup guide as seen (finished or skipped) and leaves it. */
    fun finishOnboarding() {
        appSettings.setOnboardingDone(true)
        navigateBack()
    }

    /** Clears [UiState.notice] once the snackbar has shown it. */
    fun consumeNotice(id: Long) = _ui.update { if (it.notice?.id == id) it.copy(notice = null) else it }

    /**
     * Only the editor and the setup guide show [UiState.notice]. One posted while another screen
     * is on display (an apply ending after "Details", a download failing in App settings) is
     * dropped, or it would replay as a stale snackbar on the next visit; the log keeps the lines.
     */
    private fun postNotice(notice: Notice) {
        val screen = _screen.value
        if (screen != Screen.MAIN && screen != Screen.ONBOARDING) return
        _ui.update { it.copy(notice = notice) }
    }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            saveRequests.collectLatest {
                delay(SAVE_DEBOUNCE_MS)
                flushSpecSave()
            }
        }
        viewModelScope.launch {
            // Read off the main thread rather than while the view model is built (cold start).
            val (report, unconfirmed, expired) = withContext(Dispatchers.IO) {
                Triple(
                    ThemeApplier.lastReport(getApplication<Application>()).let { staleAfterSafeMode(it, guard.safeModeAt) },
                    !guard.confirmed,
                    guard.protectionExpired(),
                )
            }
            // An apply or remove that already ran sets these itself.
            _ui.update {
                if (it.serviceBusy || it.lastApply != null) it
                else it.copy(lastApply = report, applyUnconfirmed = unconfirmed, applyProtectionExpired = expired)
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            val stored = runCatching { themeStore.load() }.getOrElse { _ui.value.spec }
            _ui.update { it.copy(spec = stored) }
            specLoaded.countDown()
            refreshPreview()
            val seeded = runCatching { packStore.seedBundled() }.getOrElse { PackStore.Seeded(emptyList(), mapOf("assets/packs" to "${it.javaClass.simpleName}: ${it.message}")) }
            if (seeded.added.isNotEmpty()) log(LogLine(LogLevel.INFO, "Bundled test packs installed: ${seeded.added.joinToString()}"))
            seeded.failed.forEach { (name, why) -> log(LogLine(LogLevel.WARN, "Bundled pack $name not installed: $why")) }
            if (debuggable && BuildConfig.DEBUG) {
                val bundled = runCatching { packStore.bundledIds() }.getOrDefault(emptySet())
                _ui.update { it.copy(bundledPackIds = bundled) }
            }
            refreshPacks()
            refreshAssets()
            learnOfficialPacks()
        }
        viewModelScope.launch {
            for (tool in RootTools.ALL) {
                val url = RootTools.latestApkUrl(tool)
                _ui.update { it.copy(rootToolLinks = it.rootToolLinks + (tool.name to url)) }
            }
        }
        viewModelScope.launch {
            ShizukuManager.state.collect {
                val capability = Capability.of(it)
                _ui.update { state -> if (state.capability == capability) state else state.copy(capability = capability) }
                if (it is ShizukuState.Ready) {
                    runStartupChecks()
                    loadDeviceInfo()
                }
            }
        }
        viewModelScope.launch {
            // A Diagnostics task ("Turn off all Hex Next overlays", the Settings test) can end after
            // the user left Diagnostics, and it changes the report and the guard behind this view
            // model's back: sync both whenever the service slot comes free.
            ServiceTasks.running.drop(1).filter { it == null }.collect {
                refreshGuard()
                refreshLastApply()
            }
        }
        viewModelScope.launch {
            officialPacks.ids.collect { ids ->
                val changed = _ui.value.officialPackIds != ids
                _ui.update { if (it.officialPackIds == ids) it else it.copy(officialPackIds = ids) }
                // A pack that became paid stays in the preview (preview only) but may have to move
                // between the pack directories, and its badge changes.
                if (changed) refreshPacks()
            }
        }
        viewModelScope.launch {
            var previous: EntitlementState? = null
            entitlements.state.collect { state ->
                val old = _ui.value.entitlement
                _ui.update { if (it.entitlement == state) it else it.copy(entitlement = state) }
                // A verified purchase moves its preview-only zip into files/packs, where an apply
                // can use it (refreshPacks); the preview renders locked and unlocked packs alike.
                if (old.ready != state.ready || old.unlockAll != state.unlockAll ||
                    old.ownedProductIds != state.ownedProductIds
                ) {
                    refreshPacks()
                }
                val before = previous
                previous = state
                onEntitlementsChanged(before, state)
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
     * Runs [checkSafeMode] and [reassertSemWt] (once per app run) holding the [ServiceTasks] slot,
     * so an Apply theme, Remove theme or Diagnostics task can never interleave with them on the
     * service. Waits while another task holds the slot.
     */
    private suspend fun runStartupChecks() {
        if (safeModeChecked && semwtReasserted) return
        ServiceTasks.running.first { running -> running == null && ServiceTasks.tryStart(STARTUP_TASK) }
        try {
            checkSafeMode()
            reassertSemWt()
        } finally {
            ServiceTasks.finish()
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
                // Overlays are only part of the theme. The font (kept as a module that is mounted
                // again at every boot) and the shell-tier palette seed go too, before the mark below
                // switches off the boot-time guards that would otherwise remove them.
                withContext(Dispatchers.IO) {
                    val service = runCatching { ShizukuManager.service() }.getOrNull()
                    if (service != null) {
                        runCatching { MonetSeed.restore(service) { line -> if (line.level != LogLevel.INFO) log(line) } }
                            .onFailure { log(LogLine(LogLevel.WARN, "Safe mode could not restore the system palette: ${it.javaClass.simpleName}: ${it.message}")) }
                        val fonts = FontInstaller(service)
                        runCatching { fonts.remove { line -> if (line.level != LogLevel.INFO) log(line) } }
                            .onFailure { log(LogLine(LogLevel.WARN, "Safe mode could not remove the Hex Next font: ${it.javaClass.simpleName}: ${it.message}")) }
                        if (runCatching { fonts.hasState() }.getOrDefault(false)) {
                            log(LogLine(LogLevel.WARN, "The Hex Next font is still installed; run Remove theme with Shizuku running as root."))
                        }
                    }
                }
                guard.markSafeModeEntered(by = "app start")
                // Safe mode confirms the guard: the "not confirmed yet" line must go with the overlays.
                refreshGuard()
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
                log(LogLine(LogLevel.ERROR, "Safe mode could not turn Hex Next overlays off: $why. Use Diagnostics > Turn off all Hex Next overlays."))
                _ui.update { it.copy(safeModeBanner = SAFE_MODE_FAILED_TEXT, safeModeFailed = true) }
                return
            }
        }
        if (guard.safeModeActive()) {
            // Safe mode (here or in the boot receiver) turned the overlays off, so a report from
            // an apply that started before it would still read "Applied to N apps" in the editor
            // and in Diagnostics. Same fix as Remove theme; a later apply's report is kept.
            val app = getApplication<Application>()
            val safeModeAt = guard.safeModeAt
            val dropped = withContext(Dispatchers.IO) {
                val stale = ThemeApplier.lastReport(app)?.let { it.startedAt < safeModeAt } == true
                if (stale) runCatching { ThemeApplier.reportFile(app).delete() }
                stale
            }
            _ui.update {
                it.copy(
                    safeModeBanner = SAFE_MODE_TEXT,
                    safeModeFailed = false,
                    lastApply = if (dropped) null else it.lastApply,
                )
            }
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
        _ui.update { it.copy(safeModeBanner = null, safeModeFailed = false) }
    }

    private fun initialState(): UiState = UiState(
        entitlement = entitlements.state.value,
        officialPackIds = officialPacks.ids.value,
        canPurchase = entitlements.canPurchase,
        seed = prefs.getInt(PREF_SEED, DEFAULT_SEED) or OPAQUE,
        oneUiVersion = CompatibilityCheck.oneUiName(),
        // The last apply report and the restart guard are read on IO in init.
        capability = Capability.of(ShizukuManager.state.value),
    )

    fun setSeed(color: Int) {
        val seed = color or OPAQUE
        prefs.edit().putInt(PREF_SEED, seed).apply()
        _ui.update { it.copy(seed = seed) }
        draftSeed.value = null
        _edits.update { it + 1 }
        refreshPreview()
    }

    fun requestPermission() = ShizukuManager.requestPermission()

    fun clearLog() {
        _log.value = emptyList()
    }

    /**
     * Compiles and applies the full theme (the stored [EngineThemeSpec] with the chosen accent
     * seed and pack) to every scanned target. Works without packs (colours only); drawables and
     * night colours need the privileged service.
     */
    fun applyTheme() {
        // Catalogue packs are paid unlocks: a theme that uses a locked pack (an imported catalogue file, a pack
        // whose purchase was refunded, a pick from "Describe your theme") is not applied.
        val locked = PackAccess.lockedPacksIn(specToApply()) { _ui.value.isPackUnlocked(it) }
        if (locked.isNotEmpty()) {
            refuseLocked(locked.first())
            return
        }
        applyUnlockedTheme()
    }

    private fun applyUnlockedTheme() = runTask(retry = NoticeAction.RETRY_APPLY) { service, _ ->
        // An apply right after start (the device lab hook, a quick tap) must not take the default
        // theme for the stored one: lab-016/017 applied "Hex Next (default)" without its pack.
        awaitSpecLoadedBlocking()
        // The theme being applied is on disk too before anything changes on the phone.
        flushSpecSave()
        val spec = specToApply()
        val stillLocked = PackAccess.lockedPacksIn(spec) { _ui.value.isPackUnlocked(it) }
        if (stillLocked.isNotEmpty()) {
            log(LogLine(LogLevel.ERROR, "Not applied: pack ${stillLocked.joinToString()} is locked"))
            postNotice(lockedNotice(stillLocked.first(), "Unlock ${packName(stillLocked.first())} to apply this theme"))
            return@runTask false
        }
        val report = applier.apply(service, spec, ::log)
        // A safe mode from before this apply is over once the new theme is on: left set, it keeps
        // the SemWT re-assert (here and at boot) and early-hook detection off for the new theme,
        // and its banner back on every start. A safe mode that tripped during this apply is kept.
        val endsSafeMode = report.success && guard.safeModeAt.let { it == 0L || it < report.startedAt }
        if (endsSafeMode && guard.safeModeActive()) guard.dismissSafeMode()
        // A theme that applied is remembered, so it comes back by itself after a restart
        // ([com.hexnext.themer.theme.ThemeRestorer]), and backed up to the chosen folder.
        if (report.success) {
            com.hexnext.themer.theme.ThemeRestorer.rememberApplied(getApplication(), spec)
            val backup = com.hexnext.themer.theme.AutoBackup.get(getApplication())
            if (backup.state.value.folder != null) {
                val ok = backup.backUp { out -> com.hexnext.themer.theme.ThemeBackup.write(getApplication(), spec, appSettings.exportJson(), out) }
                if (!ok) log(LogLine(LogLevel.WARN, "Automatic backup failed: ${backup.state.value.lastError}"))
            }
        }
        _ui.update {
            it.copy(
                lastApply = report,
                applyUnconfirmed = !guard.confirmed,
                applyProtectionExpired = guard.protectionExpired(),
                safeModeBanner = if (endsSafeMode) null else it.safeModeBanner,
                safeModeFailed = if (endsSafeMode) false else it.safeModeFailed,
            )
        }
        if (report.success && !guard.confirmed) {
            postNotice(
                Notice(
                    "Theme applied. Check your phone, then confirm it works",
                    actionLabel = "It works",
                    action = NoticeAction.CONFIRM_WORKS,
                    isSuccess = true,
                ),
            )
        } else if (report.success) {
            // No restart protection was armed (no root): there is nothing to confirm.
            postNotice(Notice("Theme applied. Check your phone", isSuccess = true))
        } else {
            postNotice(Notice("Couldn't apply the theme", actionLabel = "Diagnostics", action = NoticeAction.OPEN_DIAGNOSTICS, isError = true))
        }
        report.success
    }

    /**
     * "It works": the applied theme is confirmed, so restarts no longer trigger safe mode for it,
     * and the service's stability monitor is stopped. Works without a connection too (the guard
     * is a local preference); the monitor stop is then skipped.
     */
    fun confirmWorks() {
        // An apply (here or a Diagnostics Settings test) still running has armed the guard for
        // itself: confirming now would disarm restart protection for overlays nobody has checked.
        // A pack download or import only sets busy and never touches the guard, so it does not block this.
        val running = ServiceTasks.running.value
        if (_ui.value.serviceBusy || running != null) {
            log(LogLine(LogLevel.WARN, "Wait for the running task to finish, check your phone, then tap \"It works\"."))
            postNotice(Notice(if (running == null || running == EDITOR_TASK) "Wait for the apply to finish, then confirm" else "Wait for $running to finish, then confirm"))
            return
        }
        guard.confirm()
        _ui.update { it.copy(applyUnconfirmed = false, applyProtectionExpired = false) }
        log(LogLine(LogLevel.OK, "Confirmed: restarts will no longer turn the theme off"))
        if (ShizukuManager.state.value !is ShizukuState.Ready) return
        viewModelScope.launch {
            runCatching {
                val service = ShizukuManager.service()
                withContext(Dispatchers.IO) {
                    service.stopStabilityMonitor()
                    EarlyRecoveryHook.uninstall(service)
                }
            }.onFailure { log(LogLine(LogLevel.WARN, "Could not stop the stability monitor: ${it.message}")) }
        }
    }

    /** Opens Diagnostics and starts a phone scan there (the editor's "Scan phone" actions). */
    fun openScan() {
        _ui.update { it.copy(scanRequested = true) }
        navigate(Screen.DIAGNOSTICS)
    }

    // ---- System > Targets and the resource editor ----

    /** The app [Screen.RESOURCES] opens on next; null keeps the editor's own choice. */
    private val _resourcesTarget = MutableStateFlow<String?>(null)
    val resourcesTarget: StateFlow<String?> = _resourcesTarget.asStateFlow()

    /** Opens the per-app resource editor, on [packageName] when given (a Targets row). */
    fun openResources(packageName: String? = null) {
        _resourcesTarget.value = packageName
        navigate(Screen.RESOURCES)
    }

    /** The resource editor opened [resourcesTarget]. */
    fun consumeResourcesTarget() {
        _resourcesTarget.value = null
    }

    /**
     * One UI apps the last saved scan found installed (not removed), for System > Targets before
     * the first apply; null until read, empty without a scan.
     */
    private val _scannedTargets = MutableStateFlow<List<String>?>(null)
    val scannedTargets: StateFlow<List<String>?> = _scannedTargets.asStateFlow()

    /** Re-reads [scannedTargets] from the latest saved scan report, off the main thread. */
    fun loadScannedTargets() {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val targets = withContext(Dispatchers.IO) {
                runCatching { SnapshotBuilder(app).latestReport() }.getOrNull()
                    ?.packages
                    ?.filter { it.installed && !it.debloated && !it.deprecated }
                    ?.map { it.pkg }
                    ?.distinct()
                    .orEmpty()
            }
            _scannedTargets.value = targets
        }
    }

    // ---- System > Tools: the theme file ----

    /** The theme as `theme.json` text, with the seed preference applied (what Apply would use). */
    fun themeJson(): String = specToApply().toJson()

    /**
     * Writes the theme to [uri] as a `.hexnext` file ([ThemeExport.export]: a zip holding
     * `theme.json` and the pack ids it uses), on IO. The outcome goes to the snackbar.
     */
    fun exportTheme(uri: Uri) {
        val spec = specToApply()
        viewModelScope.launch {
            val app = getApplication<Application>()
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = ThemeExport.export(spec)
                    val out = app.contentResolver.openOutputStream(uri, "wt") ?: error("The file could not be opened.")
                    out.use { it.write(bytes) }
                }
            }
            result.fold(
                onSuccess = { postNotice(Notice("Theme file saved", isSuccess = true)) },
                onFailure = {
                    log(LogLine(LogLevel.ERROR, "Saving the theme file failed: ${it.javaClass.simpleName}: ${it.message}"))
                    postNotice(Notice("Couldn't save the theme file", isError = true))
                },
            )
        }
    }

    /**
     * Reads a `.hexnext` file from [uri] on IO ([ThemeExport.read]) and hands its theme to
     * [onRead] on the main thread; nothing changes until [importTheme]. A file that is not a
     * theme posts a notice instead.
     */
    fun readThemeFile(uri: Uri, onRead: (EngineThemeSpec) -> Unit) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val input = app.contentResolver.openInputStream(uri) ?: error("The file could not be opened.")
                    val bytes = input.use { stream ->
                        // A theme file is a few kilobytes; refuse anything far larger before reading it whole.
                        val buffer = java.io.ByteArrayOutputStream()
                        val chunk = ByteArray(8192)
                        while (true) {
                            val n = stream.read(chunk)
                            if (n < 0) break
                            buffer.write(chunk, 0, n)
                            if (buffer.size() > MAX_THEME_FILE_BYTES) throw ThemeFormatException("The file is too large to be a theme.")
                        }
                        buffer.toByteArray()
                    }
                    ThemeExport.read(bytes).spec
                }
            }
            result.fold(
                onSuccess = onRead,
                onFailure = {
                    log(LogLine(LogLevel.ERROR, "Reading the theme file failed: ${it.javaClass.simpleName}: ${it.message}"))
                    postNotice(Notice("That file is not a Hex Next theme", isError = true))
                },
            )
        }
    }

    /**
     * Replaces the stored theme with an imported one. The seed lives outside the spec, so it moves
     * too, as "Describe your theme" does. Locked packs it names stay locked: Apply refuses them.
     */
    fun importTheme(spec: EngineThemeSpec) {
        setSeed(spec.source.seedArgb)
        replaceSpec(spec)
        val locked = PackAccess.lockedPacksIn(spec) { _ui.value.isPackUnlocked(it) }
        if (locked.isEmpty()) {
            postNotice(Notice("Theme imported. Apply it to see it on your phone", isSuccess = true))
        } else {
            log(LogLine(LogLevel.WARN, "Imported theme uses locked pack ${locked.joinToString()}; preview only until it is unlocked"))
            postNotice(lockedNotice(locked.first(), "Theme imported. Unlock ${packName(locked.first())} to apply it"))
        }
    }

    // ---- System > Tools: backup and restore ----

    /** The automatic backup folder and the last backup ([com.hexnext.themer.theme.AutoBackup]). */
    val autoBackup: StateFlow<com.hexnext.themer.theme.AutoBackup.State> =
        com.hexnext.themer.theme.AutoBackup.get(app).state

    /** Writes a full backup ([com.hexnext.themer.theme.ThemeBackup]) to [uri], on IO. */
    fun backUpTo(uri: Uri) {
        val spec = specToApply()
        viewModelScope.launch {
            val app = getApplication<Application>()
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val out = app.contentResolver.openOutputStream(uri, "wt") ?: error("The file could not be opened.")
                    out.use { com.hexnext.themer.theme.ThemeBackup.write(app, spec, appSettings.exportJson(), it) }
                }
            }
            result.fold(
                onSuccess = { postNotice(Notice("Backup saved", isSuccess = true)) },
                onFailure = {
                    log(LogLine(LogLevel.ERROR, "Saving the backup failed: ${it.javaClass.simpleName}: ${it.message}"))
                    postNotice(Notice("Couldn't save the backup", isError = true))
                },
            )
        }
    }

    /** Backs up to the automatic folder now. */
    fun backUpNow() {
        val spec = specToApply()
        viewModelScope.launch {
            val app = getApplication<Application>()
            val ok = withContext(Dispatchers.IO) {
                com.hexnext.themer.theme.AutoBackup.get(app).backUp { com.hexnext.themer.theme.ThemeBackup.write(app, spec, appSettings.exportJson(), it) }
            }
            postNotice(if (ok) Notice("Backup saved to your backup folder", isSuccess = true) else Notice("Couldn't save the backup", isError = true))
        }
    }

    fun setBackupFolder(tree: Uri) {
        com.hexnext.themer.theme.AutoBackup.get(getApplication()).setFolder(tree)
        backUpNow()
    }

    fun clearBackupFolder() = com.hexnext.themer.theme.AutoBackup.get(getApplication()).clearFolder()

    /** Reads a backup from [uri] on IO and hands it to [onRead]; nothing changes until [restoreBackup]. */
    fun readBackup(uri: Uri, onRead: (com.hexnext.themer.theme.ThemeBackup.Contents) -> Unit) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val input = app.contentResolver.openInputStream(uri) ?: error("The file could not be opened.")
                    input.use { com.hexnext.themer.theme.ThemeBackup.read(it) }
                }
            }
            result.fold(
                onSuccess = onRead,
                onFailure = {
                    log(LogLine(LogLevel.ERROR, "Reading the backup failed: ${it.javaClass.simpleName}: ${it.message}"))
                    postNotice(Notice(if (it is ThemeFormatException) it.message ?: "That file is not a Hex Next backup" else "That file is not a Hex Next backup", isError = true))
                },
            )
        }
    }

    /** Restores a read backup: assets and learned maps, then settings and the theme. */
    fun restoreBackup(contents: com.hexnext.themer.theme.ThemeBackup.Contents) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val written = withContext(Dispatchers.IO) { runCatching { com.hexnext.themer.theme.ThemeBackup.restoreFiles(app, contents) } }
            written.onFailure { log(LogLine(LogLevel.ERROR, "Restoring backup files failed: ${it.message}")) }
            contents.settingsJson?.let { appSettings.importJson(it) }
            refreshAssets()
            importTheme(contents.spec)
            log(LogLine(LogLevel.INFO, "Backup restored: ${written.getOrDefault(0)} files, theme ${contents.spec.name}"))
        }
    }

    /** System > Tools could not hand the build log to a share sheet. */
    fun noteBuildLogShareFailed() = postNotice(Notice("Couldn't share the build log", isError = true))

    /** Diagnostics took the scan request. */
    fun consumeScanRequest() = _ui.update { if (it.scanRequested) it.copy(scanRequested = false) else it }

    /** A generated icon was stored as a custom icon: list it and tell the user where it went. */
    fun noteGeneratedIcon(asset: CustomAssetStore.Asset) {
        refreshAssets()
        postNotice(Notice("${asset.displayName} added to Custom icons", isSuccess = true))
    }

    /** Replaces the whole stored theme (the "Describe your theme" card hands back a mapped spec). */
    fun replaceSpec(spec: EngineThemeSpec) = updateSpec { spec }

    /**
     * The theme the live preview renders: [specToApply] as it is, locked packs included, so every
     * catalogue pack can be browsed and previewed before purchase. The preview compiles and
     * renders in memory only (ThemePreviewer); it writes no overlay. Building overlays stays
     * gated: [applyTheme] refuses a theme that uses a locked pack, and the applier only reads
     * `files/packs`, never the preview-only directory.
     */
    private fun specToPreview(): EngineThemeSpec = PackAccess.specForPreview(specToApply())

    /** The stored theme ([UiState.spec]) with the seed preference applied on top. */
    private fun awaitSpecLoadedBlocking() {
        specLoaded.await(20, java.util.concurrent.TimeUnit.SECONDS)
    }

    /** Suspends until the stored theme is loaded (up to 20 s). */
    suspend fun awaitSpecLoaded() {
        withContext(Dispatchers.IO) { awaitSpecLoadedBlocking() }
    }

    /**
     * Suspends until an apply right now would use the stored theme with its packs: the theme is
     * loaded and the purchase state has answered (before that every catalogue pack counts as
     * locked and Apply refuses it). Up to 20 s each.
     */
    suspend fun awaitReadyToApply() {
        awaitSpecLoaded()
        kotlinx.coroutines.withTimeoutOrNull(20_000) { _ui.first { it.entitlement.ready } }
        // The startup safety check (or a Diagnostics task) makes Apply refuse until it is done.
        kotlinx.coroutines.withTimeoutOrNull(120_000) { ServiceTasks.running.first { it == null } }
    }

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
        val before = _ui.value.spec
        _ui.update { it.copy(spec = block(it.spec)) }
        val after = _ui.value.spec
        _edits.update { it + 1 }
        savePending.set(true)
        saveRequests.tryEmit(Unit)
        // The icon render only sees Settings and My Files: an edit that cannot change them (Quick
        // panel opacity or Quick panel-only colours, another app's switch or pins) skips it. An
        // unchanged spec still renders, as a re-imported custom file may need it.
        if (after == before || iconSignatureOf(after) != iconSignatureOf(before)) refreshPreview()
    }

    /**
     * [spec] without the fields the preview render cannot see, so two specs with the same
     * signature render the same Settings and My Files icons.
     */
    private fun iconSignatureOf(spec: EngineThemeSpec): EngineThemeSpec = spec.copy(
        name = "",
        qsPanelAlpha = 255,
        targetEnabled = spec.targetEnabled.filterKeys { it in PREVIEW_PACKAGES },
        colorPins = spec.colorPins.filterKeys { it.substringBefore(':', "") in PREVIEW_PACKAGES },
        colorGroups = spec.colorGroups.filterKeys { id -> ColorGroup.byId(id)?.quickSettings != true },
    )

    /** Writes the current spec to `theme.json` when an edit is waiting to be saved. Blocking. */
    private fun flushSpecSave() {
        if (!savePending.getAndSet(false)) return
        runCatching {
            themeStore.update { _ui.value.spec }
        }.onFailure { log(LogLine(LogLevel.ERROR, "Saving the theme failed: ${it.javaClass.simpleName}: ${it.message}")) }
    }

    override fun onCleared() {
        // The writer's scope is gone: save a last pending edit now.
        flushSpecSave()
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
    fun setElementPack(elementId: String, packId: String?) {
        // A locked pack may be chosen for the preview; Apply refuses it until it is unlocked.
        updateSpec { it.withElementPack(elementId, packId).withElementEnabled(elementId, true) }
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
            val spec = specToPreview()
            val night = previewNight.value
            val rendered = withContext(Dispatchers.Default) {
                // A newer edit cancels this job; the render then stops at its next check.
                val job = coroutineContext[Job]
                previewer.render(spec, night) { job?.isActive == false }
            }
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
                        builtIn = rendered.builtIn,
                        night = night,
                        deviceColors = rendered.deviceColors,
                    ),
                )
            }
            val signature = slotsSignatureOf(spec)
            if (signature != slotsSignature) {
                val catalogue = withContext(Dispatchers.Default) { previewer.slots(spec) }
                // Stored only once the lists are in: a job cancelled during slots() (a seed
                // nudge, an apply) must leave the next job to recompute them.
                slotsSignature = signature
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
        // A new scan changes the element colour counts too; recount once they were shown.
        if (groupCountsWanted) loadGroupCounts()
        // System > Targets lists the scanned apps before the first apply; a new scan changes them.
        if (_scannedTargets.value != null) loadScannedTargets()
    }

    // ---- Colour Studio ----

    /** The colour inputs of [studioPreview] that come from [UiState]. */
    private data class ColourInputs(val spec: EngineThemeSpec, val seed: Int)

    /** The [UiState] fields the icon half of [studioPreview] is built from. */
    private data class IconSource(val preview: PreviewState, val spec: EngineThemeSpec, val seed: Int)

    /** What the icon half of [studioPreview] depends on. */
    private data class IconInputs(
        val preview: PreviewState,
        val style: IconColorStyle,
        val source: com.hexnext.engine.palette.ThemeSource,
        val dark: ThemeAdjustments,
        val light: ThemeAdjustments,
        val settingsBuiltIn: Boolean,
        val myFilesBuiltIn: Boolean,
        val night: Boolean,
    )

    /**
     * Whether the preview may draw built-in badges for icon element [elementId] on its own:
     * built-in icons on, the element enabled and no pack chosen for it. With a pack, the compile
     * preview already carries the built-ins the engine plans when the pack draws none of the
     * icons, so a preview without a scan or render never guesses colourful icons for a pack.
     */
    private fun drawsBuiltInIcons(spec: EngineThemeSpec, elementId: String): Boolean =
        spec.builtInIcons && spec.isElementEnabled(elementId) && spec.packFor(elementId) == null

    /** The icon half of [studioPreview]. */
    private data class StudioIcons(
        val settings: List<PreviewIcon> = emptyList(),
        val myFiles: List<PreviewIcon> = emptyList(),
        val background: ImageBitmap? = null,
        val fontFile: String? = null,
        val fontName: String? = null,
        val loading: Boolean = false,
        val hasScan: Boolean = false,
        val note: String? = null,
        val night: Boolean = true,
        val deviceColors: com.hexnext.themer.ui.studio.DeviceColors = com.hexnext.themer.ui.studio.DeviceColors(),
    )

    private fun imageOf(bitmap: Bitmap): ImageBitmap = synchronized(imageCache) {
        imageCache[bitmap]?.get() ?: bitmap.asImageBitmap().also { imageCache[bitmap] = WeakReference(it) }
    }

    /** Background thread only: converts the compile preview, filling in built-in badges where it has no icons. */
    private fun studioIcons(inputs: IconInputs): StudioIcons {
        val preview = inputs.preview
        val fallback = preview.accent ?: inputs.source.seedArgb
        val synthesised = preview.synthesised
        var settings = preview.icons.map { icon ->
            PreviewIcon(icon.name, icon.label, icon.bitmap?.let(::imageOf), fallback, synthesised = icon.name in synthesised)
        }
        var myFiles = preview.myFiles.map { icon ->
            PreviewIcon(icon.name, icon.label, icon.bitmap?.let(::imageOf), fallback)
        }
        // Built-in badges stand in only when Apply would draw them too (built-in icons on, element
        // enabled, no pack for it), so the preview never shows colourful icons the phone will not get.
        val needSettings = inputs.settingsBuiltIn && settings.none { it.bitmap != null }
        val needMyFiles = inputs.myFilesBuiltIn && myFiles.none { it.bitmap != null }
        if (needSettings || needMyFiles) {
            runCatching {
                val theme = ThemeResolver.resolve(inputs.source, inputs.dark, inputs.light)
                if (needSettings) {
                    settings = styledIcons.settingsIcons(inputs.style, theme, inputs.night, STUDIO_ICON_PX, STUDIO_SETTINGS_ICONS)
                        .map { PreviewIcon(it.key, it.label, imageOf(it.bitmap), fallback) }
                        .ifEmpty { settings }
                }
                if (needMyFiles) {
                    myFiles = styledIcons.myFilesIcons(inputs.style, theme, inputs.night, STUDIO_ICON_PX, STUDIO_MY_FILES_ICONS)
                        .map { PreviewIcon(it.key, it.label, imageOf(it.bitmap), fallback) }
                        .ifEmpty { myFiles }
                }
            }
        }
        return StudioIcons(
            settings = settings,
            myFiles = myFiles,
            background = preview.background?.let(::imageOf),
            fontFile = preview.fontFile,
            fontName = preview.fontName,
            loading = preview.loading,
            hasScan = SnapshotBuilder.scanStamp(getApplication<Application>()) != null,
            note = preview.note,
            night = inputs.night,
            deviceColors = preview.deviceColors,
        )
    }

    /**
     * Applies [block] to the stored theme (persisted, preview re-rendered), like every other spec
     * change, and ends a drag preview. For Studio controls that have no dedicated setter.
     */
    fun editSpec(block: (EngineThemeSpec) -> EngineThemeSpec) = commitSpec(block)

    /** A committed Studio edit: the stored spec changes first, then the drag preview ends, so the colours never step back. */
    private fun commitSpec(block: (EngineThemeSpec) -> EngineThemeSpec) {
        updateSpec(block)
        draft.value = null
    }

    /**
     * Shows [spec] in the live preview without saving it (a slider being dragged); null returns
     * the preview to the stored theme. The final value is committed with a setter on release.
     */
    fun previewDraft(spec: EngineThemeSpec?) {
        draft.value = spec
    }

    /** As [previewDraft], for the primary seed (which lives outside the spec); null ends it. [setSeed] commits. */
    fun previewSeed(argb: Int?) {
        draftSeed.value = argb?.or(OPAQUE)
    }

    /** The secondary seed (its hue replaces the secondary palette's); null is Auto. */
    fun setSecondarySeed(argb: Int?) = commitSpec { it.copy(source = it.source.copy(secondaryArgb = argb?.or(OPAQUE))) }

    /** The tertiary seed (its hue replaces the tertiary palette's); null is Auto. */
    fun setTertiarySeed(argb: Int?) = commitSpec { it.copy(source = it.source.copy(tertiaryArgb = argb?.or(OPAQUE))) }

    /** The Monet scheme variant the palettes are built with. */
    fun setPaletteStyle(style: PaletteStyle) = commitSpec { it.copy(source = it.source.copy(style = style)) }

    /** Scheme contrast, -1 … 1 (0 is standard). */
    fun setContrast(contrast: Double) = commitSpec { it.copy(source = it.source.copy(contrast = contrast.coerceIn(-1.0, 1.0))) }

    /** Changes the light ([night] false) or dark adjustments. */
    fun setAdjustments(night: Boolean, block: (ThemeAdjustments) -> ThemeAdjustments) = commitSpec { it.withAdjustments(night, block) }

    /**
     * Pins Monet shade [key] (`accent1_500`, with or without the `system_` prefix) to [argb] in the
     * dark ([night]) or light scheme; null unpins it.
     */
    fun setShadeOverride(night: Boolean, key: String, argb: Int?) = commitSpec { it.withShadeOverride(night, key, argb) }

    /** The colour of element [group] in one mode; null returns that mode to Auto. */
    fun setGroupColor(group: ColorGroup, night: Boolean, argb: Int?) = commitSpec { it.withGroupColor(group, night, argb) }

    /** The colour of element [group] in both modes; null returns both to Auto. */
    fun setGroupColorBoth(group: ColorGroup, argb: Int?) = commitSpec { it.withGroupColor(group, false, argb).withGroupColor(group, true, argb) }

    /** Every element group back to Auto, in both modes. */
    fun resetGroupColors() = commitSpec { it.withAllGroupsAuto() }

    /** Pins one colour resource (`pkg:color/name`) to [pin]; null removes the pin. */
    fun setColorPin(resourceName: String, pin: ColorPin?) = commitSpec { spec ->
        spec.copy(colorPins = if (pin == null) spec.colorPins - resourceName else spec.colorPins + (resourceName to pin))
    }

    /** Whether Apply themes [packageName]; a missing entry means enabled, so enabling drops it. */
    fun setTargetEnabled(packageName: String, enabled: Boolean) = commitSpec { spec ->
        spec.copy(targetEnabled = if (enabled) spec.targetEnabled - packageName else spec.targetEnabled + (packageName to false))
    }

    /** How pack, synthesised and built-in icons are coloured. */
    fun setIconStyle(style: IconColorStyle) = commitSpec { it.copy(iconStyle = style) }

    /** The app's own colourful icons for Settings rows and My Files categories no pack covers. */
    fun setBuiltInIcons(enabled: Boolean) = commitSpec { it.copy(builtInIcons = enabled) }

    /**
     * Sets Quick panel shape [id] ([ShapeControls]) to [setting]; null returns it to One UI's
     * size. A changed value set is no longer the one a crash rollback quarantined.
     */
    fun setShape(id: String, setting: ValueSetting?) {
        commitSpec { if (setting == null) it.copy(shapes = it.shapes - id) else it.copy(shapes = it.shapes + (id to setting)) }
        ThemeApplier.clearValuesQuarantine(getApplication())
    }

    /** Every Quick panel shape back to One UI's sizes. */
    fun resetShapes() {
        commitSpec { it.copy(shapes = emptyMap()) }
        ThemeApplier.clearValuesQuarantine(getApplication())
    }

    /** Opacity of the Quick panel backdrop, 0 … 255 (255 leaves Samsung's). */
    fun setQsPanelAlpha(alpha: Int) = commitSpec { it.copy(qsPanelAlpha = alpha.coerceIn(EngineThemeSpec.MIN_QS_PANEL_ALPHA, 255)) }

    /**
     * The Studio mockup switched to dark ([dark] true) or light mode: re-renders the preview icons
     * from the matching night or day jobs. Main thread only.
     */
    fun setPreviewDark(dark: Boolean) {
        if (previewNight.value == dark) return
        previewNight.value = dark
        // The icons on screen are the other mode's until the new render lands: show them as loading.
        _ui.update { it.copy(preview = it.preview.copy(loading = true)) }
        refreshPreview()
    }

    /** The group the Studio is editing (the preview outlines it); null for none. */
    fun setHighlightedGroup(group: ColorGroup?) {
        highlight.value = group
    }

    /**
     * Counts the resources of every element group over the last scan, off the main thread, once
     * per scan ([SnapshotBuilder.scanStamp]). Without a scan the counts stay empty.
     */
    fun loadGroupCounts() {
        groupCountsWanted = true
        if (groupCountsJob?.isActive == true) return
        val known = groupCountsStamp
        groupCountsJob = viewModelScope.launch {
            val app = getApplication<Application>()
            val result = withContext<Triple<String?, Map<ColorGroup, GroupCount>, Map<String, ValueControl>>?>(Dispatchers.IO) {
                val stamp = SnapshotBuilder.scanStamp(app)
                when {
                    stamp == null -> Triple(null, emptyMap(), emptyMap())
                    stamp == known -> null
                    else -> runCatching {
                        val built = SnapshotBuilder(app).build()
                        val rules = built?.let { OneUiRules.forProfile(it.snapshot.oneUi) }
                        val counts = built?.let { ColorGroupMatcher.groupCounts(it.snapshot, rules!!) }.orEmpty()
                        // The Quick panel shape controls: which candidate names this phone defines.
                        val controls = if (built != null && rules != null) ValuePlanner.availableControls(built.snapshot, rules) else emptyMap()
                        Triple(stamp, counts, controls)
                    }.getOrElse {
                        log(LogLine(LogLevel.WARN, "Counting element colours failed: ${it.javaClass.simpleName}: ${it.message}"))
                        null
                    }
                }
            } ?: return@launch
            groupCountsStamp = result.first
            _groupCounts.value = result.second
            _valueControls.value = result.third
        }
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
            val result = runCatching {
                // A preview-only pack whose purchase is now verified moves to files/packs, where an
                // apply can use it. Only the verified state counts, never the cached hint.
                val previewOnly = previewPackStore.installed()
                // OfficialPacks' own set too: the UiState copy may lag a just-recorded download.
                val state = _ui.value
                val official = state.officialPackIds + officialPacks.ids.value
                for (pack in previewOnly.packs) {
                    if (PackAccess.isUnlocked(state.entitlement, pack.id, state.bundledPackIds, official) &&
                        packStore.adoptFrom(previewPackStore, pack.id)
                    ) {
                        log(LogLine(LogLevel.OK, "${pack.name} unlocked: it can be applied now"))
                    }
                }
                previewPackStore.pruneOlderThan(PREVIEW_PACK_MAX_AGE_MS, keep = PackAccess.packsUsedBy(_ui.value.spec) + listOfNotNull(_ui.value.spec.simplePackId))
                val applicable = packStore.installed()
                val preview = previewPackStore.installed()
                val applicableIds = applicable.packs.mapTo(HashSet()) { it.id }
                val extra = preview.packs.filter { it.id !in applicableIds }
                InstalledPacks(
                    (applicable.packs + extra).sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }),
                    applicable.errors + preview.errors,
                ) to extra.mapTo(HashSet()) { it.id }
            }
            result.onSuccess { (installed, previewOnlyIds) ->
                val ids = installed.packs.mapTo(HashSet()) { it.id }
                // Only a pack that the last listing had and this one lacks was removed. A theme may
                // name a pack that was never installed here (an imported theme waiting for its
                // pack); that choice is kept, so it works once the pack is bought or downloaded.
                val removed = _ui.value.packs.mapTo(HashSet()) { it.id } - ids
                entitlements.setCatalogue(ids)
                val versions = installed.packs.associate { it.id to it.version }
                _ui.update {
                    it.copy(
                        packs = installed.packs,
                        previewOnlyPackIds = previewOnlyIds,
                        packErrors = installed.errors,
                        catalogue = it.catalogue.copy(
                            entries = it.catalogue.entries.map { e ->
                                e.copy(installed = PackStore.isCatalogueInstalled(e.version, versions[e.id]))
                            },
                        ),
                    )
                }
                val spec = _ui.value.spec
                val selected = spec.simplePackId
                if (selected != null && selected in removed) {
                    log(LogLine(LogLevel.WARN, "Theme pack $selected is no longer installed; colours only"))
                    selectPack(null)
                }
                val gone = spec.elementPacks.filterValues { it in removed }
                if (gone.isNotEmpty()) {
                    log(LogLine(LogLevel.WARN, "Pack ${gone.values.distinct().joinToString()} is no longer installed; ${gone.keys.joinToString()} use the default pack"))
                    updateSpec { it.copy(elementPacks = it.elementPacks.filterValues { v -> v !in removed }) }
                }
                refreshPreview()
            }.onFailure { log(LogLine(LogLevel.ERROR, "Listing packs failed: ${it.javaClass.simpleName}: ${it.message}")) }
        }
    }

    /**
     * Chooses the pack of the next apply (null = colours only) and persists it in the theme. A
     * locked pack may be chosen: the preview shows it, and Apply refuses it until it is unlocked.
     */
    fun selectPack(id: String?) {
        updateSpec { it.copy(simplePackId = id) }
    }

    /**
     * The Skins gallery picked catalogue pack [entry]: selects it when it is on the device,
     * otherwise downloads it (into the preview-only directory while locked) and then selects it.
     */
    fun previewCataloguePack(entry: CatalogueEntry) {
        if (_ui.value.packs.any { it.id == entry.id }) selectPack(entry.id) else downloadPack(entry, selectWhenDone = true)
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
                val removed = packStore.delete(id) or previewPackStore.delete(id)
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

    /**
     * Store builds only, once at start-up on IO: reads the default catalogue's `index.json` so that
     * packs added to it after this APK shipped count as paid even if the user never opens "Get
     * packs" (see [OfficialPacks]). Best effort: offline or a bad answer changes nothing.
     */
    private fun learnOfficialPacks() {
        if (!entitlements.canPurchase) return
        runCatching { packStore.fetchCatalogue(PackStore.DEFAULT_CATALOGUE_URL) }
            .onSuccess { entries -> officialPacks.record(entries.map { it.id }) }
            .onFailure { log(LogLine(LogLevel.INFO, "Catalogue check skipped: ${it.javaClass.simpleName}")) }
    }

    private fun isOfficialCatalogue(url: String): Boolean =
        PackStore.normaliseBaseUrl(url) == PackStore.normaliseBaseUrl(PackStore.DEFAULT_CATALOGUE_URL)

    fun setCatalogueUrl(url: String) = _ui.update { it.copy(catalogue = it.catalogue.copy(url = url)) }

    /** Fetches `index.json` from the catalogue URL; errors show in the dialog, not only the log. */
    fun loadCatalogue() {
        if (_ui.value.catalogue.loading) return
        val url = _ui.value.catalogue.url
        _ui.update { it.copy(catalogue = it.catalogue.copy(loading = true, error = null)) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val entries = packStore.fetchCatalogue(url)
                // Only the official catalogue marks packs as paid: a catalogue of the user's own
                // packs elsewhere stays free (official ids also come from learnOfficialPacks).
                if (isOfficialCatalogue(url)) officialPacks.record(entries.map { it.id })
                entitlements.setCatalogue(entries.map { it.id })
                _ui.update { it.copy(catalogue = it.catalogue.copy(entries = entries, loading = false, error = null)) }
                log(LogLine(LogLevel.OK, "Catalogue: ${entries.size} packs at ${PackStore.normaliseBaseUrl(url)}"))
            } catch (t: Exception) {
                val message = t.message ?: t.javaClass.simpleName
                _ui.update { it.copy(catalogue = it.catalogue.copy(loading = false, error = message)) }
                log(LogLine(LogLevel.ERROR, "Catalogue failed: $message"))
            }
        }
    }

    /**
     * Downloads [entry] with progress in [CatalogueState.downloading]: into `files/packs` when it
     * is unlocked, otherwise into the preview-only directory, so a locked pack can be browsed and
     * previewed (Apply still refuses it). With [selectWhenDone] the pack is selected afterwards.
     */
    fun downloadPack(entry: CatalogueEntry, selectWhenDone: Boolean = false) {
        if (_ui.value.busy || entry.id in _ui.value.catalogue.downloading) return
        val url = _ui.value.catalogue.url
        // An entry of the official catalogue is paid even before OfficialPacks has recorded its id,
        // so it is judged with that id already counted as official (never the cached hint).
        fun unlocked(state: UiState, vararg ids: String): Boolean {
            val known = state.officialPackIds + officialPacks.ids.value
            val official = if (isOfficialCatalogue(url)) known + ids else known
            return ids.all { PackAccess.isUnlocked(state.entitlement, it, state.bundledPackIds, official) }
        }
        val store = if (unlocked(_ui.value, entry.id)) packStore else previewPackStore
        _ui.update { it.copy(busy = true, catalogue = it.catalogue.copy(downloading = it.catalogue.downloading + (entry.id to 0f))) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val pack = store.download(url, entry) { read, total ->
                    val fraction = if (total > 0) (read.toFloat() / total).coerceIn(0f, 1f) else 0f
                    _ui.update { it.copy(catalogue = it.catalogue.copy(downloading = it.catalogue.downloading + (entry.id to fraction))) }
                }
                if (isOfficialCatalogue(url)) officialPacks.record(listOf(entry.id, pack.id))
                // The zip declared an id that is not unlocked: keep it out of files/packs.
                if (store === packStore && !unlocked(_ui.value, entry.id, pack.id)) previewPackStore.adoptFrom(packStore, pack.id)
                log(LogLine(LogLevel.OK, "Downloaded pack ${pack.name} (${pack.id}, ${pack.bytes / 1024} KiB)" + if (store === previewPackStore) ", preview only until unlocked" else ""))
                if (selectWhenDone) selectPack(pack.id)
            } catch (t: Exception) {
                log(LogLine(LogLevel.ERROR, "Download of ${entry.name} failed: ${t.message ?: t.javaClass.simpleName}"))
                postNotice(Notice("Couldn't download ${entry.name}", isError = true))
            } finally {
                _ui.update { it.copy(busy = false, catalogue = it.catalogue.copy(downloading = it.catalogue.downloading - entry.id)) }
                refreshPacks()
            }
        }
    }

    // ---- Pack unlocks ----

    /** Starts the store purchase of [productId] (a pack, [ProductIds.ALL_PACKS] or [ProductIds.AI_LIFETIME]). */
    fun unlock(activity: Activity, productId: String) {
        if (!entitlements.canPurchase) return
        entitlements.launchPurchase(activity, productId)
    }

    /** "Restore purchases": reads the purchases from the store again. */
    fun restorePurchases() {
        log(LogLine(LogLevel.INFO, "Checking purchases with Google Play"))
        entitlements.refresh()
    }

    /** The app came back to the foreground: a purchase may have completed or been refunded meanwhile. */
    fun refreshEntitlements() = entitlements.refresh()

    /** Tells the user why [packId] cannot be used, or that the purchase check is still running. */
    private fun refuseLocked(packId: String) {
        val state = _ui.value
        val name = packName(packId)
        when {
            !state.entitlement.ready && state.packLooksUnlocked(packId) -> {
                postNotice(Notice("Checking your purchases. Try again in a moment"))
                entitlements.refresh()
            }
            state.entitlement.isPending(packId) -> postNotice(Notice("The payment for $name is still pending"))
            else -> {
                log(LogLine(LogLevel.WARN, "$name is a preview: unlock it to apply it"))
                postNotice(lockedNotice(packId, "$name is a preview. Unlock it to apply"))
            }
        }
    }

    /** [message] with an "Unlock" action for [packId] in a store build. */
    private fun lockedNotice(packId: String, message: String): Notice =
        if (entitlements.canPurchase) {
            Notice(message, actionLabel = "Unlock", action = NoticeAction.UNLOCK_PACK, isError = true, packId = packId)
        } else {
            Notice(message, isError = true)
        }

    /** The name of pack [packId] from the installed packs or the catalogue; the id when neither has it. */
    private fun packName(packId: String): String {
        val state = _ui.value
        return state.packs.firstOrNull { it.id == packId }?.name
            ?: state.catalogue.entries.firstOrNull { it.id == packId }?.name
            ?: packId
    }

    /** Snackbars for what a purchase changed; the first state of the run ([before] null or not ready) only logs. */
    private fun onEntitlementsChanged(before: EntitlementState?, state: EntitlementState) {
        if (before == null) {
            state.error?.let { log(LogLine(LogLevel.WARN, "Purchases: $it")) }
            return
        }
        if (state.error != null && state.error != before.error) {
            log(LogLine(LogLevel.WARN, "Purchases: ${state.error}"))
            postNotice(Notice(state.error, isError = true))
        }
        if (!before.ready) return
        val newlyOwned = state.ownedProductIds - before.ownedProductIds
        val aiNow = state.unlocks(PaidFeature.AI_CREATION)
        when {
            state.unlockAll && !before.unlockAll -> postNotice(Notice("All packs unlocked. Thank you!", isSuccess = true))
            aiNow && !before.unlocks(PaidFeature.AI_CREATION) ->
                postNotice(Notice("AI theme creation unlocked. Thank you!", isSuccess = true))
            newlyOwned.isNotEmpty() -> {
                val names = newlyOwned.map { productId -> productName(productId) }
                log(LogLine(LogLevel.OK, "Unlocked ${names.joinToString()}"))
                postNotice(Notice("${names.joinToString()} unlocked. Thank you!", isSuccess = true))
            }
            (state.pendingProductIds - before.pendingProductIds).isNotEmpty() ->
                postNotice(Notice("Payment pending. It unlocks once Google Play confirms it"))
        }
    }

    private fun productName(productId: String): String {
        when (productId) {
            ProductIds.ALL_PACKS -> return "All packs"
            ProductIds.AI_LIFETIME -> return "AI theme creation"
        }
        val state = _ui.value
        // allForPack also matches a purchase made under a former product id, so the user sees
        // the pack's current name instead of the raw id.
        return state.packs.firstOrNull { productId in ProductIds.allForPack(it.id) }?.name
            ?: state.catalogue.entries.firstOrNull { productId in ProductIds.allForPack(it.id) }?.name
            ?: productId
    }

    /** Removes the compiled theme and gives the targets back to Samsung's SemWT overlays. */
    fun removeTheme() = runTask(retry = NoticeAction.RETRY_REMOVE) { service, _ ->
        val ok = applier.remove(service, ::log)
        // The saved report would otherwise come back as "Applied to N apps" on the next start
        // (and in Diagnostics) although nothing is applied any more.
        if (ok) runCatching { ThemeApplier.reportFile(getApplication<Application>()).delete() }
        if (ok) com.hexnext.themer.theme.ThemeRestorer.forget(getApplication())
        // A failed remove keeps the report (overlays may still be on), so the card keeps its line.
        _ui.update { it.copy(lastApply = if (ok) null else it.lastApply, applyUnconfirmed = !guard.confirmed, applyProtectionExpired = guard.protectionExpired()) }
        if (ok) {
            postNotice(Notice("Theme removed", isSuccess = true))
        } else {
            postNotice(Notice("Couldn't remove the theme", actionLabel = "Diagnostics", action = NoticeAction.OPEN_DIAGNOSTICS, isError = true))
        }
        ok
    }

    /**
     * Runs [block] on the privileged service with the busy flag held. [retry] is the snackbar
     * action offered when [block] throws; null shows the failure without an action.
     */
    private fun runTask(retry: NoticeAction?, block: (com.hexnext.themer.IThemeService, ShizukuState.Ready) -> Boolean) {
        val ready = ShizukuManager.state.value as? ShizukuState.Ready ?: run {
            log(LogLine(LogLevel.ERROR, "No theme service: Shizuku (root for everything, ADB shell for the system palette only) isn't ready."))
            val state = ShizukuManager.state.value
            postNotice(
                if (state == ShizukuState.PermissionNeeded || state == ShizukuState.PermissionDenied) {
                    Notice("Hex Next needs Shizuku access first", actionLabel = "Allow", action = NoticeAction.REQUEST_PERMISSION, isError = true)
                } else {
                    Notice("Start Shizuku or grant root access first", isError = true)
                },
            )
            return
        }
        // As the Apply card: SU mode (su found, Shizuku not running) is Ready but cannot apply or
        // remove (Capability.of gives NONE). A snackbar Retry can still arrive here in that state,
        // and running on would overwrite the last good report with an aborted one.
        if (ready.viaSu || Capability.of(ready) == Capability.NONE) {
            log(LogLine(LogLevel.ERROR, "Hex Next found su but Shizuku is not running; su alone (SU mode) cannot apply or remove themes."))
            postNotice(Notice("Start Shizuku with root first", isError = true))
            return
        }
        if (_ui.value.busy) return
        if (com.hexnext.themer.safety.OtherBuild.isInstalled(getApplication<Application>())) {
            log(LogLine(LogLevel.ERROR, "${com.hexnext.themer.safety.OtherBuild.packageName} is installed; refusing to change the theme while two builds share the overlays."))
            postNotice(Notice(com.hexnext.themer.safety.OtherBuild.refusal, isError = true))
            return
        }
        // Diagnostics drives the same service: never run alongside one of its tasks.
        if (!ServiceTasks.tryStart(EDITOR_TASK)) {
            val other = ServiceTasks.running.value ?: "A Diagnostics task"
            if (other == STARTUP_TASK) {
                log(LogLine(LogLevel.WARN, "The startup safety check is still running. Try again when it finishes."))
                postNotice(Notice("Wait a moment: the startup safety check is still running", isError = true))
            } else {
                log(LogLine(LogLevel.WARN, "$other is still running in Diagnostics. Try again when it finishes."))
                postNotice(Notice("Wait for Diagnostics to finish ($other)", isError = true))
            }
            return
        }
        _log.value = emptyList()
        _ui.update { it.copy(busy = true, serviceBusy = true) }
        viewModelScope.launch {
            try {
                val service = ShizukuManager.service()
                withContext(Dispatchers.IO) { block(service, ready) }
            } catch (t: Throwable) {
                log(LogLine(LogLevel.ERROR, "${t.javaClass.simpleName}: ${t.message}"))
                postNotice(
                    Notice(
                        "Something went wrong",
                        actionLabel = if (retry != null) "Retry" else null,
                        action = retry,
                        isError = true,
                    ),
                )
            } finally {
                ServiceTasks.finish()
                _ui.update { it.copy(busy = false, serviceBusy = false) }
            }
        }
    }

    /** Keeps the on-screen log bounded: the full record is the build log file. */
    private fun log(line: LogLine) {
        val text = if (line.text.length > MAX_LOG_CHARS) line.text.take(MAX_LOG_CHARS) + " …" else line.text
        val bounded = if (text === line.text) line else line.copy(text = text)
        _log.update { (it + bounded).takeLast(MAX_LOG_LINES) }
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
        private const val KEY_SCREEN = "screen"

        /** [ServiceTasks] name of the editor's Apply theme or Remove theme. */
        const val EDITOR_TASK = "Apply theme in the editor"

        /** [ServiceTasks] name of the startup safe-mode check and SemWT re-assert. */
        const val STARTUP_TASK = "Startup safety check"

        private const val KEY_BACK_STACK = "back_stack"
        private const val PREF_SEED = "seed"
        private const val DEFAULT_SEED = 0xFF00E5FF.toInt()
        private const val MAX_LOG_LINES = 400
        private const val MAX_LOG_CHARS = 2000
        private const val MAX_THEME_FILE_BYTES = 4 shl 20
        private const val PREVIEW_DEBOUNCE_MS = 400L

        /** How long the spec writer waits for edits to pause before it saves. */
        private const val SAVE_DEBOUNCE_MS = 300L

        /** The packages the preview render compiles ([ThemePreviewer]). */
        private val PREVIEW_PACKAGES = setOf("com.android.settings", "com.sec.android.app.myfiles")

        /** A locked pack kept for the preview only is deleted after this long unless the theme uses it. */
        private const val PREVIEW_PACK_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

        /** Size and count of the built-in badges the Studio preview draws without pack icons. */
        private const val STUDIO_ICON_PX = 96
        private const val STUDIO_SETTINGS_ICONS = 12
        private const val STUDIO_MY_FILES_ICONS = 6
        private const val SETTINGS_ICONS_ELEMENT = "settings_icons"
        private const val MY_FILES_ICONS_ELEMENT = "my_files_icons"
        private const val OPAQUE = 0xFF000000.toInt()
        private const val SAFE_MODE_TEXT = "Safe mode: Hex Next overlays were turned off after repeated restarts"
        private const val SAFE_MODE_FAILED_TEXT =
            "Safe mode: your phone restarted repeatedly, but Hex Next overlays could not be turned off automatically"

        val androidVersion: String get() = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
    }
}

/** [this] with the light ([night] false) or dark adjustments changed by [block]. */
internal fun EngineThemeSpec.withAdjustments(night: Boolean, block: (ThemeAdjustments) -> ThemeAdjustments): EngineThemeSpec =
    if (night) copy(darkAdjustments = block(darkAdjustments)) else copy(lightAdjustments = block(lightAdjustments))

/**
 * [this] with Monet shade [key] (`accent1_500` or `system_accent1_500`) pinned to [argb] (opaque)
 * in one mode; null unpins it. Stored in the short form.
 */
internal fun EngineThemeSpec.withShadeOverride(night: Boolean, key: String, argb: Int?): EngineThemeSpec {
    val short = key.removePrefix("system_")
    return withAdjustments(night) { adj ->
        val rest = adj.shadeOverrides - short - "system_$short"
        adj.copy(shadeOverrides = if (argb == null) rest else rest + (short to (argb or 0xFF000000.toInt())))
    }
}
