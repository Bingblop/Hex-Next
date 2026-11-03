package com.hexnext.themer.theme

import android.content.Context
import android.os.Bundle
import com.hexnext.engine.compile.ApplyReport
import com.hexnext.engine.compile.CompiledTarget
import com.hexnext.engine.compile.CompiledTheme
import com.hexnext.engine.compile.OverlayLookup
import com.hexnext.engine.compile.TargetOutcome
import com.hexnext.engine.compile.TargetResult
import com.hexnext.engine.compile.ThemeCompiler
import com.hexnext.engine.compile.ValuePlanner
import com.hexnext.engine.device.LegacyOverlays
import com.hexnext.themer.device.DeviceScanner
import com.hexnext.engine.device.OneUi9Facts
import com.hexnext.engine.device.OverlayListEntry
import com.hexnext.engine.device.SemWtPlanner
import com.hexnext.engine.device.SemWtState
import com.hexnext.engine.pack.DirectoryPackResolver
import com.hexnext.themer.IThemeService
import com.hexnext.themer.overlay.BuildLog
import com.hexnext.themer.overlay.BuildRequest
import com.hexnext.themer.overlay.BuildResult
import com.hexnext.themer.overlay.DrawableSpec
import com.hexnext.themer.overlay.OverlayBuilder
import com.hexnext.themer.overlay.OverlayColor
import com.hexnext.themer.overlay.OverlayDrawable
import com.hexnext.themer.overlay.OverlayValue
import com.hexnext.themer.safety.ApplyGuard
import com.hexnext.themer.safety.EarlyRecoveryHook
import com.hexnext.themer.shizuku.MonitorScript
import com.hexnext.themer.shizuku.ThemeService
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import com.hexnext.engine.compile.ThemeSpec as EngineThemeSpec

/**
 * Applies a full compiled theme.
 *
 * [apply]: build the [com.hexnext.engine.compile.DeviceSnapshot] from the last scan
 * ([SnapshotBuilder]), compile with [ThemeCompiler] against the packs in `files/packs`
 * ([DirectoryPackResolver]; colour-only themes compile without packs), clean up the legacy
 * overlays of older builds ([LegacyOverlays]), arm the boot-loop guard and the 120 s stability
 * monitor, then per compiled target: render its drawables ([DrawableRenderer], one at a time),
 * fabricate `HexNext_<target>` through [OverlayBuilder] (build log + saved request for repair),
 * disable `android:SemWT_<target>` and, only when `cmd overlay list` shows the Hex overlay below
 * another enabled overlay, raise it with a disable/enable cycle ([SemWtPlanner.takeover]; One UI 9
 * puts an overlay on top on every enable), then verify with `cmd overlay lookup` that the first
 * plain colour resolves to the value set (a mismatch makes the target PARTIAL). A failing target
 * is recorded and the next one is built. A final pass disables any SemWT overlay of a themed
 * target that is still enabled, and [retireUnbuilt] takes an earlier theme's overlay and SemWT
 * takeover off the targets this one no longer builds. The SemWT state is saved and the re-assert
 * guard started.
 *
 * The [ApplyReport] in `files/reports/apply-last.json` is written when the apply starts (status
 * `started`, then with the compiled target list), after every target (`running`, with the
 * targets still pending) and at the end (`finished`). Any throwable, OutOfMemoryError included,
 * aborts the apply and is recorded (`aborted`, with the error) in the report and the build log.
 *
 * Tiers ([Capability]): with root (uid 0/1000) everything above happens. With Shizuku over ADB
 * (uid 2000, [Capability.SHELL]) the theme's seed becomes the system palette through [MonetSeed].
 * Each target's plain colours are also tried through `cmd overlay fabricate --file`, but AOSP
 * refuses that to non-root callers, so normally the first target is refused, recorded as skipped
 * and the rest are not tried. Drawables, fonts and night colours are skipped with a "needs root" note.
 *
 * [remove]: stop the guard, disable (and when privileged unregister) every HexNext overlay,
 * re-enable the SemWT overlays Hex Next disabled, put the previous system palette back
 * ([MonetSeed.restore]) and clean up the legacy overlays.
 */
class ThemeApplier(context: Context) {

    private val appContext = context.applicationContext
    private val builder = OverlayBuilder(appContext)
    private val snapshots = SnapshotBuilder(appContext)
    private val guard = ApplyGuard(appContext)
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    init {
        rememberContext(appContext)
    }

    fun apply(service: IThemeService, spec: EngineThemeSpec, log: (LogLine) -> Unit): ApplyReport {
        val started = System.currentTimeMillis()
        val generation = APPLY_GENERATION.incrementAndGet()
        // Counted before the lock: a missed-trip recovery that takes the lock after this sees it.
        RUNNING_APPLIES.incrementAndGet()
        // A post-apply trip handler already running for the previous apply finishes first, so it
        // cannot unregister or bury the overlays this apply builds; one not yet started sees the
        // new generation and does nothing. The previous apply's trip record goes with it.
        synchronized(TRIP_LOCK) { clearTripWatch() }
        // A pending value set belongs to the apply that put it on; a trip of this apply (even
        // before its value pass) must not quarantine the previous apply's sizes.
        prefs.edit().remove(KEY_VALUES_PENDING).commit()
        var monitorStartedAt = 0L
        val buildLog = BuildLog(appContext)
        val notes = ArrayList<String>()
        val outcomes = ArrayList<TargetOutcome>()
        var pending: List<String> = emptyList()
        var semwt = SemWtState()
        fun say(level: LogLevel, text: String, data: Map<String, Any?> = emptyMap()) {
            log(LogLine(level, text))
            buildLog.write(level.name, "", text, data)
        }
        fun note(level: LogLevel, text: String) {
            notes += text
            say(level, text)
        }
        fun currentReport(status: String, error: String = ""): ApplyReport = ApplyReport(
            themeId = spec.id,
            themeName = spec.name,
            startedAt = started,
            finishedAt = System.currentTimeMillis(),
            outcomes = outcomes.toList(),
            logFile = buildLog.file.absolutePath,
            notes = notes.toList(),
            status = status,
            pendingTargets = pending,
            error = error,
        )
        // Saved before every step that can take long or die: a crash still leaves this file.
        fun progress(status: String) = saveReport(currentReport(status))
        fun finish(): ApplyReport {
            pending = emptyList()
            val report = currentReport(ApplyReport.STATUS_FINISHED)
            saveReport(report)
            buildLog.write(
                if (report.failedCount == 0) "OK" else "WARN", "", "theme apply finished",
                mapOf("summary" to report.summary(), "millis" to report.finishedAt - started),
            )
            log(LogLine(if (report.success) LogLevel.OK else LogLevel.WARN, "Theme apply: ${report.summary()}"))
            log(LogLine(LogLevel.INFO, "Build log: ${buildLog.file.path}"))
            return report
        }

        progress(ApplyReport.STATUS_STARTED)
        return try {
            applyAll(
                service, spec, buildLog, log, ::say, ::note, notes, outcomes,
                { status, left -> pending = left; progress(status) },
                { at -> monitorStartedAt = at; recordTripWatch(started, at) },
            ) { semwt = it }
            val report = finish()
            // The monitor keeps watching after this returns; a trip then must reach the app too.
            if (monitorStartedAt > 0L) watchMonitorAfterApply(service, generation, monitorStartedAt, report, log)
            report
        } catch (t: Throwable) {
            // OutOfMemoryError included: the snapshot, compiled theme and bitmaps are only
            // referenced from applyAll's frame, which is gone here, so there is room to record it.
            val error = if (t is ApplyAborted) t.message.orEmpty() else "${t.javaClass.simpleName}: ${t.message}"
            val report = currentReport(ApplyReport.STATUS_ABORTED, error)
            saveReport(report)
            runCatching { buildLog.write("ERROR", "", "theme apply aborted", mapOf("error" to error, "pending" to pending.size)) }
            // An ApplyAborted message was already shown by the step that raised it.
            if (t !is ApplyAborted) log(LogLine(LogLevel.ERROR, "Theme apply aborted: $error"))
            if (t is OutOfMemoryError) {
                log(LogLine(LogLevel.ERROR, "Out of memory (max heap ${Runtime.getRuntime().maxMemory() / MIB} MiB). Remove theme, then report the build log."))
            }
            // Keep removal able to restore the SemWT overlays taken over before the failure.
            if (semwt.records.isNotEmpty()) {
                runCatching {
                    SemWtController.writeState(service, semwt)
                    setTakeoverActive(appContext, true)
                }
            }
            report
        } finally {
            RUNNING_APPLIES.decrementAndGet()
        }
    }

    /**
     * The apply proper. Everything large (snapshot, compiled theme, rendered PNGs) lives only in
     * this frame so that [apply] can still record a failure after it unwinds.
     */
    private fun applyAll(
        service: IThemeService,
        spec: EngineThemeSpec,
        buildLog: BuildLog,
        log: (LogLine) -> Unit,
        say: (LogLevel, String, Map<String, Any?>) -> Unit,
        note: (LogLevel, String) -> Unit,
        notes: MutableList<String>,
        outcomes: MutableList<TargetOutcome>,
        onProgress: (status: String, pending: List<String>) -> Unit,
        onMonitor: (startedAt: Long) -> Unit,
        onSemWt: (SemWtState) -> Unit,
    ) {
        say(LogLevel.INFO, "Applying theme \"${spec.name}\" (${spec.id})", emptyMap())
        val packs = DirectoryPackResolver(packsDir(appContext))
        spec.simplePackId?.let { packId ->
            val pack = packs.pack(packId)
            if (pack != null) {
                say(LogLevel.INFO, "Theme pack: ${pack.name} ($packId)", emptyMap())
            } else {
                note(LogLevel.WARN, "Theme pack $packId is not installed; colours only")
            }
        }
        // The device snapshot (every target's resource table) is unreachable once this returns.
        val compiled = compileFromScan(service, spec, packs, say, note)
            ?: throw ApplyAborted(notes.lastOrNull() ?: "The theme could not be compiled.")
        val uid = runCatching { service.uid }.getOrDefault(-1)
        val capability = Capability.fromUid(uid)
        if (capability == Capability.NONE) {
            // Neither root nor the shell: nothing can be committed. Stop before touching anything.
            // uid 10000 and up is the app process itself: SU mode (su found, Shizuku not running),
            // where only exec() reaches root and overlays cannot be committed.
            val suMode = if (uid >= 10000) " Hex Next found su but Shizuku is not running; su alone (SU mode) cannot apply themes." else ""
            throw ApplyAborted(
                "The theme service runs as uid $uid, but applying a theme needs root (uid 0 or 1000) or the Shizuku shell (uid 2000).$suMode " +
                    "Start Shizuku (with root for icons, backgrounds and fonts) and apply again.",
            )
        }
        val owner = if (uid == 1000) ThemeService.SYSTEM_OWNER else ThemeService.SHELL_OWNER
        compiled.events.forEach { e -> buildLog.write(e.level.name, e.target, e.message, e.data) }
        packs.errors.forEach { (file, why) -> note(LogLevel.WARN, "Pack $file unreadable: $why") }
        val drawableCount = compiled.targets.sumOf { it.drawables.size }
        say(
            LogLevel.INFO,
            "Compiled ${compiled.targets.size} targets (${compiled.targets.sumOf { it.colors.size }} colours, " +
                "$drawableCount drawables); ${compiled.skippedTargets.size} skipped",
            mapOf("capability" to capability.name, "uid" to uid),
        )
        if (capability == Capability.SHELL) {
            note(
                LogLevel.WARN,
                "System palette only: Shizuku runs without root (uid $uid). " +
                    "Per-app colour overlays need root on most builds (the shell may not fabricate overlays); " +
                    (if (drawableCount > 0) "$drawableCount drawables, " else "") +
                    "night colours, fonts and backgrounds need root and are skipped.",
            )
        }
        // Read before anything changes: a state file that exists but cannot be read holds what
        // earlier applies disabled and must not be replaced by this apply's records.
        val stateRead = SemWtController.readStateChecked(service, log)
        var semwt = stateRead.state ?: run {
            val raw = stateRead.raw
            val why = when {
                raw == null -> "Could not read ${SemWtState.FILE_NAME} (the record of the Samsung overlays Hex Next disabled). Nothing was changed; try again."
                !SemWtController.preserveUnreadable(service, raw) ->
                    "${SemWtState.FILE_NAME} is corrupt and could not be set aside. Nothing was changed; try again."
                else -> null
            }
            if (why != null) {
                note(LogLevel.ERROR, why)
                throw ApplyAborted(why)
            }
            note(LogLevel.WARN, "${SemWtState.FILE_NAME} was corrupt; kept as ${SemWtController.UNREADABLE_FILE_NAME} and started a new record.")
            SemWtState()
        }
        onSemWt(semwt)
        // Every takeover is saved (and the takeover flag set) before its disable command runs:
        // a system_server restart kills this process mid-apply, and removal, safe mode and the
        // boot re-assert only know about SemWT overlays that are on disk. False when it could not
        // be saved; the SemWT overlay is then left enabled (ours is still enabled last, on top).
        val persistTakeover: (String, String, Boolean) -> Boolean = { pkg, hexId, enabledBefore ->
            semwt = semwt.withTakeover(pkg, hexId, enabledBefore, System.currentTimeMillis())
            onSemWt(semwt)
            val saved = SemWtController.writeState(service, semwt)
            if (saved) setTakeoverActive(appContext, true)
            saved
        }

        val shell = ShellFabricator()
        onProgress(ApplyReport.STATUS_STARTED, compiled.targets.map { it.packageName })

        cleanupLegacy(service) { level, text -> say(level, text, emptyMap()) }?.let { notes += it }

        // The restart guard only works when something survives a reboot to act on it: the early
        // root hook (uid 0) or the boot receiver (needs su on the phone). Without either (Shizuku
        // in ADB mode on a phone without su) nothing could turn the theme off after two restarts,
        // so the apply is not left unconfirmed and the UI does not promise that.
        // A new theme goes on: a safe mode left from an earlier trip (banner never dismissed)
        // would otherwise keep switching off the app-start and boot re-assert for this theme.
        guard.dismissSafeMode()
        if (uid == 0 || SU_PATHS.any { File(it).exists() }) {
            guard.recordApply()
        } else {
            guard.confirm()
            note(LogLevel.WARN, "No restart protection without root: if the phone restarts repeatedly, start Shizuku and remove the theme.")
        }
        if (EarlyRecoveryHook.install(service) == false) {
            note(LogLevel.WARN, "Early root recovery hook could not be installed")
        }
        val monitorAt = System.currentTimeMillis()
        val monitor = service.startStabilityMonitor(MONITOR_SECONDS)
        say(LogLevel.INFO, "Stability monitor: ${monitor.output()}", emptyMap())

        val before = SemWtController.listOverlays(service)
        // The monitor turns the HexNext overlays off when SystemUI or system_server restarts twice.
        // Once it has, nothing may build on top: later targets would be unwatched, and the SemWT
        // guard started at the end would put Samsung's overlays back over them at once.
        // Only a monitor this apply started cleared the old safe_mode.json; without one, a file
        // left by an earlier apply would stop this one for nothing. A monitor whose pid file was
        // not there yet was still launched (and the old file deleted), so it is watched too.
        val watched = monitor.code() == 0 || monitor.code() == ThemeService.MONITOR_PID_MISSING
        if (watched) onMonitor(monitorAt)
        var tripped = false
        DrawableRenderer(packs, CustomAssetStore.rootDir(appContext), IconSynthesizer(appContext)).use { renderer ->
            compiled.targets.forEachIndexed { index, target ->
                if (tripped || (watched && monitorTripped(service))) {
                    tripped = true
                    return@forEachIndexed
                }
                val outcome = try {
                    applyTarget(service, target, renderer, capability, shell, owner, before, buildLog, log, persistTakeover)
                } catch (t: Exception) {
                    val message = "${t.javaClass.simpleName}: ${t.message}"
                    buildLog.write("ERROR", target.packageName, "target failed", mapOf("error" to message))
                    log(LogLine(LogLevel.ERROR, "${target.packageName}: $message"))
                    TargetOutcome(target.packageName, TargetResult.FAILED, target.overlayName, message = message)
                }
                outcomes += outcome
                onProgress(ApplyReport.STATUS_RUNNING, compiled.targets.drop(index + 1).map { it.packageName })
            }
        }

        if (tripped || (watched && monitorTripped(service))) stopAfterSafeMode(service, log, note, onSemWt, semwt)
        ensureSemWtDisabled(service, outcomes, owner, before, log, persistTakeover)?.let { note(LogLevel.WARN, it) }
        if (watched && monitorTripped(service)) stopAfterSafeMode(service, log, note, onSemWt, semwt)
        // Targets this theme no longer builds (deprecated, switched off, not installed, compiled
        // empty) must not keep an earlier theme's overlay, nor have SemWT forced off by the guard.
        // SKIPPED (the shell tier: "needs root") built nothing either, so a root theme applied
        // earlier is taken off those targets instead of staying on under this theme's name.
        val built = outcomes.filter { it.result != TargetResult.EMPTY && it.result != TargetResult.SKIPPED }.mapTo(HashSet()) { it.target }
        val retired = retireUnbuilt(service, built, semwt, log, note)
        if (retired != semwt) {
            semwt = retired
            onSemWt(semwt)
        }

        // Sizes (Quick panel corners, slider thickness, native extras) go last, in their own
        // HexNextValues_ overlays, under a staged monitor: a System UI crash loop then turns the
        // sizes off first and keeps the colours that were stable before them.
        if (watched && monitorTripped(service)) stopAfterSafeMode(service, log, note, onSemWt, semwt)
        applyValuesPass(service, spec, compiled, capability, before, outcomes, buildLog, log, note, onMonitor)
        if (watched && monitorTripped(service)) stopAfterSafeMode(service, log, note, onSemWt, semwt)

        if (!SemWtController.writeState(service, semwt)) {
            note(LogLevel.WARN, "Could not save ${SemWtState.FILE_NAME}; removal cannot restore SemWT overlays automatically.")
        }
        SemWtController.startGuard(service, semwt) { say(it.level, it.text, emptyMap()) }
        setTakeoverActive(appContext, semwt.records.isNotEmpty())

        outcomes += ApplyReport.skippedOutcomes(compiled)

        if (capability == Capability.SHELL) {
            shell.unavailable?.let { why -> note(LogLevel.WARN, "Colour overlays were not built: $why") }
            // The seed is what the shell tier can always do; it is listed in the report as its own line.
            val seeded = MonetSeed.apply(service, spec.source.seedArgb, log)
            buildLog.write(if (seeded) "OK" else "WARN", MonetSeed.TARGET, "system palette seed", mapOf("seed" to MonetSeed.hex(spec.source.seedArgb), "ok" to seeded))
            outcomes += TargetOutcome(
                target = MonetSeed.TARGET,
                result = if (seeded) TargetResult.OK else TargetResult.FAILED,
                overlay = MonetSeed.SETTING,
                colorCount = 2,
                resourceCount = if (seeded) 2 else 0,
                message = if (seeded) "" else "the secure setting did not take the seed (see log)",
            )
        }

        applyFont(service, spec, capability, log, note)
    }

    /**
     * The value pass: registers `HexNextValues_<pkg>` for every compiled target with
     * [CompiledTarget.values], verifies one value per target by `cmd overlay lookup`, and removes
     * value overlays of targets that no longer have any. Root only; skipped (with a note) on the
     * shell tier, for a value set a System UI crash rollback quarantined, or on a theme service
     * that predates value overlays. Before any value is written the stability monitor is
     * restarted in staged mode ([IThemeService.startStabilityMonitorStaged]) and the value set is
     * recorded as pending, so a trip can tell the sizes were on.
     */
    private fun applyValuesPass(
        service: IThemeService,
        spec: EngineThemeSpec,
        compiled: CompiledTheme,
        capability: Capability,
        before: List<OverlayListEntry>,
        outcomes: MutableList<TargetOutcome>,
        buildLog: BuildLog,
        log: (LogLine) -> Unit,
        note: (LogLevel, String) -> Unit,
        onMonitor: (startedAt: Long) -> Unit,
    ) {
        val withValues = compiled.targets.filter { it.hasValues }
        val existing = before.filter { it.overlayId.substringAfter(':').startsWith(ValuePlanner.OVERLAY_PREFIX) }
        val keep = HashSet<String>()
        fun removeStale() {
            for (row in existing) {
                val name = row.overlayId.substringAfter(':')
                if (name in keep) continue
                val r = runCatching { service.removeOverlay(name) }.getOrNull()
                buildLog.write("INFO", row.target, "value overlay removed", mapOf("overlay" to name, "code" to r?.code()))
            }
        }
        if (withValues.isEmpty()) {
            removeStale()
            compiled.targets.forEach { builder.saveValues(it.packageName, emptyList()) }
            return
        }
        val count = withValues.sumOf { it.values.size }
        if (capability != Capability.ROOT) {
            note(LogLevel.WARN, "Quick panel shapes and other sizes need Shizuku running as root; $count sizes were skipped.")
            return
        }
        // The spec's own settings plus the compiled values: pack-driven sizes (native extras)
        // are not in the spec's shapes, so two packs' sizes never share a quarantine key.
        val key = valuesKeyHash(spec) + ":" + ValuePlanner.valueHash(withValues.flatMap { it.values })
        if (prefs.getString(KEY_VALUES_QUARANTINE, null) == key) {
            note(
                LogLevel.WARN,
                "These Quick panel sizes were switched off after System UI restarted repeatedly; change them (or reset them) to try again. Colours were applied.",
            )
            removeStale()
            // Repair re-applies the saved request; it must not bring the quarantined sizes back.
            compiled.targets.forEach { builder.saveValues(it.packageName, emptyList()) }
            return
        }
        val monitorAt = System.currentTimeMillis()
        val monitor = try {
            service.startStabilityMonitorStaged(MONITOR_SECONDS, ValuePlanner.OVERLAY_PREFIX)
        } catch (t: Throwable) {
            note(LogLevel.WARN, "Sizes skipped: the theme service is older than this app. Restart Shizuku (or reboot) and apply again.")
            buildLog.write("WARN", "", "staged monitor unavailable", mapOf("error" to "${t.javaClass.simpleName}: ${t.message}"))
            return
        }
        log(LogLine(LogLevel.INFO, "Stability monitor (sizes first): ${monitor.output()}"))
        if (monitor.code() != 0 && monitor.code() != ThemeService.MONITOR_PID_MISSING) {
            note(LogLevel.WARN, "Sizes skipped: the staged stability monitor did not start (${monitor.output().lineSequence().firstOrNull().orEmpty()}).")
            return
        }
        onMonitor(monitorAt)
        // commit(): a System UI crash loop can take this process down right after the first value.
        prefs.edit().putString(KEY_VALUES_PENDING, key).commit()

        for (target in withValues) {
            val pkg = target.packageName
            val values = target.values.map { OverlayValue(it.resourceName, it.dataType, it.data, it.configuration) }
            val result = builder.applyValues(service, pkg, values, buildLog) { line -> log(LogLine(LogLevel.INFO, line)) }
            val index = outcomes.indexOfFirst { it.target == pkg }
            val base = if (index >= 0) outcomes[index] else TargetOutcome(pkg, TargetResult.EMPTY, target.overlayName)
            val outcome = if (!result.ok) {
                val tail = result.output.lineSequence().lastOrNull { it.isNotBlank() }.orEmpty()
                log(LogLine(LogLevel.WARN, "$pkg: sizes not applied (code ${result.code}): $tail"))
                base.withValues(0, null, tail, failed = true)
            } else {
                keep += target.valueOverlayName
                builder.saveValues(pkg, values)
                val probe = OverlayLookup.valueProbe(target)
                val (match, output) = probe?.let { SemWtController.verifyValue(service, pkg, it) } ?: (null to "")
                buildLog.write(
                    if (match == false) "WARN" else "INFO", pkg, "value verification",
                    mapOf("resource" to probe?.resourceName, "output" to output, "verified" to match),
                )
                val detail = if (match == false) "lookup ${probe?.resourceName} = ${output.lineSequence().firstOrNull().orEmpty()}" else ""
                log(
                    LogLine(
                        if (match == false) LogLevel.WARN else LogLevel.OK,
                        "$pkg: ${result.resourceCount} sizes" + (if (match == true) ", verified" else "") + (if (detail.isNotEmpty()) " ($detail)" else ""),
                    ),
                )
                base.withValues(result.resourceCount, match, detail)
            }
            if (index >= 0) outcomes[index] = outcome else outcomes += outcome
        }
        removeStale()
    }

    /**
     * Stage one of the staged monitor fired: it turned the `HexNextValues_` overlays off and
     * wrote `values_rollback.json`. The pending value set is quarantined (the same sizes are not
     * applied again until changed), the value overlays are unregistered and the colours stay.
     * Returns true when it acted.
     */
    private fun handleValuesRollback(service: IThemeService, log: (LogLine) -> Unit): Boolean {
        val read = runCatching { service.readStateFile(MonitorScript.VALUES_ROLLBACK_NAME) }.getOrNull() ?: return false
        if (read.code() != 0) return false
        val pending = prefs.getString(KEY_VALUES_PENDING, null) ?: return false
        prefs.edit().putString(KEY_VALUES_QUARANTINE, pending).remove(KEY_VALUES_PENDING).commit()
        // The staged monitor now watches a fresh window from the rollback for its second stage;
        // move the trip watch there so a later full trip is still recognised as this apply's.
        val rolledBackAt = TRIP_TIME.find(read.getString(ThemeService.KEY_CONTENT).orEmpty())
            ?.groupValues?.get(1)?.toLongOrNull()?.times(1000L)
        val applyAt = prefs.getLong(KEY_TRIP_APPLY_AT, 0L)
        if (rolledBackAt != null && applyAt > 0L && rolledBackAt > prefs.getLong(KEY_TRIP_MONITOR_AT, 0L)) {
            recordTripWatch(applyAt, rolledBackAt)
        }
        removeValueOverlays(service)
        runCatching {
            log(LogLine(LogLevel.WARN, "System UI restarted twice after the Quick panel sizes went on; the sizes were switched off and the colours kept."))
        }
        return true
    }

    /** A full trip while sizes were pending: the sizes are quarantined too, they may be the cause. */
    private fun quarantinePendingValues() {
        val pending = prefs.getString(KEY_VALUES_PENDING, null) ?: return
        prefs.edit().putString(KEY_VALUES_QUARANTINE, pending).remove(KEY_VALUES_PENDING).commit()
    }

    /** Unregisters every `HexNextValues_` overlay the overlay list shows. */
    private fun removeValueOverlays(service: IThemeService) {
        val rows = runCatching { SemWtController.listOverlays(service) }.getOrDefault(emptyList())
        for (row in rows) {
            val name = row.overlayId.substringAfter(':')
            if (!name.startsWith(ValuePlanner.OVERLAY_PREFIX)) continue
            runCatching { service.removeOverlay(name) }
            // Repair re-applies the saved request; it must not bring the rolled-back sizes back.
            runCatching { builder.saveValues(row.target, emptyList()) }
        }
    }

    /**
     * Font step, after the overlays: installs [EngineThemeSpec.customFont] through
     * [FontInstaller] (its lines go to the on-screen log), or removes a previously installed
     * font when the spec has none. Font failures are noted but never fail the theme.
     */
    private fun applyFont(
        service: IThemeService,
        spec: EngineThemeSpec,
        capability: Capability,
        log: (LogLine) -> Unit,
        note: (LogLevel, String) -> Unit,
    ) {
        val font = spec.customFont
        if (!capability.canApplyDrawables) {
            // The font module lives under /data and is bound as root; the shell can neither install nor remove it.
            if (font != null) note(LogLevel.WARN, "Font \"${font.displayName}\" needs root; the system font was left unchanged")
            // Without a font in this theme, a font an earlier (root) apply bound stays: say so.
            if (font == null && runCatching { FontInstaller(service).hasState() }.getOrDefault(false)) {
                note(LogLevel.WARN, "The font of an earlier theme is still installed; removing it needs root (start Shizuku as root and apply or remove again)")
            }
            return
        }
        val installer = FontInstaller(service)
        try {
            if (font == null) {
                // remove() is a no-op without recorded binds or a module directory, so the module a
                // half-finished install left behind (nothing bound) is cleaned up as well.
                installer.remove(log)
                return
            }
            val store = CustomAssetStore(appContext)
            val regular = store.file(CustomAssetStore.Kind.FONT, font.regular)
            if (!regular.isFile) {
                note(LogLevel.WARN, "Font \"${font.displayName}\" is missing (${font.regular}); the system font was left unchanged")
                return
            }
            val bold = font.bold?.let { store.file(CustomAssetStore.Kind.FONT, it) }?.takeIf { it.isFile }
            note(LogLevel.INFO, "Installing font \"${font.displayName}\"" + if (bold != null) " with a bold file" else "")
            if (!installer.install(regular, bold, log)) {
                note(LogLevel.WARN, "Font \"${font.displayName}\" was not fully installed (see log)")
            }
        } catch (e: Exception) {
            note(LogLevel.WARN, "Font step failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** Builds the snapshot from the last scan and compiles [spec]; null (with a note) when either fails. */
    private fun compileFromScan(
        service: IThemeService,
        spec: EngineThemeSpec,
        packs: DirectoryPackResolver,
        say: (LogLevel, String, Map<String, Any?>) -> Unit,
        note: (LogLevel, String) -> Unit,
    ): CompiledTheme? {
        var built = try {
            snapshots.build()
        } catch (e: Exception) {
            note(LogLevel.ERROR, "Reading the device scan failed: ${e.javaClass.simpleName}: ${e.message}")
            return null
        }
        if (built == null) {
            // First apply on a fresh install: scan the device now instead of sending the user to Diagnostics.
            say(LogLevel.INFO, "No device scan yet; scanning the device first", emptyMap())
            val report = try {
                DeviceScanner(appContext).scanBlocking(service) { step -> say(LogLevel.INFO, step, emptyMap()) }
            } catch (e: Exception) {
                note(LogLevel.ERROR, "Device scan failed: ${e.javaClass.simpleName}: ${e.message}")
                return null
            }
            report.errors.forEach { note(LogLevel.WARN, "Scan: $it") }
            built = try {
                snapshots.build(report)
            } catch (e: Exception) {
                note(LogLevel.ERROR, "Reading the device scan failed: ${e.javaClass.simpleName}: ${e.message}")
                return null
            }
            if (built == null) {
                note(LogLevel.ERROR, "The device scan produced no usable report. Open Diagnostics and run the scan.")
                return null
            }
        }
        built.warnings.forEach { note(LogLevel.WARN, it) }
        val oneUi = built.snapshot.oneUi.version?.toString() ?: "unknown One UI"
        say(LogLevel.INFO, "Device snapshot: $oneUi, ${built.snapshot.targets.size} targets, ${built.snapshot.systemShades.size} system shades", emptyMap())
        if (built.snapshot.targets.isEmpty()) {
            note(LogLevel.ERROR, "The last scan (${built.report.jsonPath ?: "unknown file"}) has no targets. Open Diagnostics and run the scan again.")
            return null
        }
        return try {
            ThemeCompiler.compile(spec, built.snapshot, packs)
        } catch (e: Exception) {
            note(LogLevel.ERROR, "Compile failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private fun applyTarget(
        service: IThemeService,
        target: CompiledTarget,
        renderer: DrawableRenderer,
        capability: Capability,
        shell: ShellFabricator,
        owner: String,
        before: List<OverlayListEntry>,
        buildLog: BuildLog,
        log: (LogLine) -> Unit,
        recordTakeover: (target: String, hexOverlayId: String, semwtEnabledBefore: Boolean) -> Boolean,
    ): TargetOutcome {
        val pkg = target.packageName
        if (target.isEmpty) {
            buildLog.write("INFO", pkg, "nothing to build", mapOf("skipped" to target.skipped.size))
            return TargetOutcome(pkg, TargetResult.EMPTY, target.overlayName, message = "nothing matched on this device")
        }
        check(target.overlayName == OverlayBuilder.overlayName(pkg)) { "overlay name mismatch for $pkg" }

        val drawables = ArrayList<OverlayDrawable>()
        var renderFailures = 0
        val privileged = capability.canApplyDrawables
        if (!privileged && target.drawables.isNotEmpty()) {
            buildLog.write("INFO", pkg, "drawables skipped: needs root", mapOf("count" to target.drawables.size))
        }
        if (privileged) {
            for (job in target.drawables) {
                try {
                    val rendered = renderer.render(job)
                    rendered.warning?.let { buildLog.write("WARN", pkg, "drawable rendered as plain PNG", mapOf("resource" to job.resourceName, "why" to it)) }
                    drawables += OverlayDrawable(job.resourceName, DrawableSpec(job.configuration.ifEmpty { null }, rendered.png, rendered.ninePatch))
                } catch (t: Throwable) {
                    renderFailures++
                    buildLog.write(
                        "WARN", pkg, "drawable not rendered",
                        mapOf("resource" to job.resourceName, "config" to job.configuration, "error" to "${t.javaClass.simpleName}: ${t.message}"),
                    )
                }
            }
            if (renderFailures > 0) log(LogLine(LogLevel.WARN, "$pkg: $renderFailures drawables could not be rendered (see build log)"))
            val customCount = target.drawables.count { it.custom != null }
            val synthesisedCount = target.drawables.count { it.synthesis != null }
            if (customCount > 0 || synthesisedCount > 0) {
                buildLog.write("INFO", pkg, "custom drawables", mapOf("custom" to customCount, "synthesised" to synthesisedCount))
                log(LogLine(LogLevel.INFO, "$pkg: $customCount custom, $synthesisedCount synthesised drawables"))
            }
        }
        val colors = target.colors.map { OverlayColor(it.resourceName, it.argb, it.configuration) }
        val request = BuildRequest(pkg, colors, drawables)
        if (request.colors.isEmpty() && request.drawables.isEmpty()) {
            return TargetOutcome(pkg, TargetResult.EMPTY, target.overlayName, message = "no colours and no renderable drawables")
        }

        if (capability == Capability.SHELL) {
            shell.unavailable?.let { why ->
                return TargetOutcome(pkg, TargetResult.SKIPPED, target.overlayName, colors.size, message = "needs root: $why")
            }
        }
        val result = if (capability == Capability.SHELL) {
            fabricateViaShell(service, target, colors, shell, buildLog, log)
        } else {
            builder.apply(service, request, buildLog) { line -> log(LogLine(LogLevel.INFO, line)) }
        }
        if (capability == Capability.SHELL && shell.unavailable != null) {
            // The shell refused to fabricate (AOSP requires root for it): not a failure of the theme.
            log(LogLine(LogLevel.WARN, "$pkg: colour overlay needs root here (${shell.unavailable}); only the system palette seed applies"))
            return TargetOutcome(pkg, TargetResult.SKIPPED, target.overlayName, colors.size, message = "needs root: ${shell.unavailable}")
        }
        val status = when {
            result.code == 0 && renderFailures == 0 -> TargetResult.OK
            result.code == 0 || result.code == PARTIAL_CODE -> TargetResult.PARTIAL
            else -> TargetResult.FAILED
        }
        val tail = result.output.lineSequence().lastOrNull { it.isNotBlank() }.orEmpty()
        if (status == TargetResult.FAILED) {
            log(LogLine(LogLevel.ERROR, "$pkg: applyOverlay code ${result.code}: $tail"))
            return TargetOutcome(pkg, status, target.overlayName, colors.size, drawables.size, result.resourceCount, millis = result.millis, message = tail)
        }

        val after = SemWtController.listOverlays(service)
        val hexId = after
            .firstOrNull { it.overlayId.substringAfter(':') == target.overlayName && it.target == pkg }?.overlayId
            ?: "$owner:${target.overlayName}"
        val semwtId = OneUi9Facts.semWtOverlayId(pkg)
        // The list from before the apply tells whether SemWT was on; when that list was empty
        // (listing failed) the current one still lets the takeover happen.
        val semwtRow = before.firstOrNull { it.overlayId == semwtId } ?: after.firstOrNull { it.overlayId == semwtId }
        var semwtDisabled = false
        if (semwtRow != null && semwtRow.marker != MARKER_NOT_APPLICABLE) {
            // Disable SemWT; raise ours (disable/enable cycle) only when the list shows it below another overlay.
            val commands = SemWtPlanner.takeover(pkg, hexId, after)
            // Recorded before the disable runs, so a death mid-apply still leaves it on disk.
            val recorded = commands.isNotEmpty() && recordTakeover(pkg, hexId, semwtRow.enabled)
            if (commands.isNotEmpty() && !recorded) {
                log(LogLine(LogLevel.WARN, "$pkg: $semwtId left enabled: the takeover could not be saved to ${SemWtState.FILE_NAME}"))
            }
            val disableOk = if (recorded) SemWtController.run(service, commands.take(1), log) else 0
            val raiseOk = SemWtController.run(service, commands.drop(1), log)
            semwtDisabled = recorded && disableOk == 1
            val ok = disableOk + raiseOk
            buildLog.write(
                if (ok == commands.size) "INFO" else "WARN", pkg, "SemWT takeover",
                mapOf("semwt" to semwtId, "wasEnabled" to semwtRow.enabled, "hexOverlay" to hexId, "commandsOk" to ok, "commands" to commands.size),
            )
        } else if (SemWtPlanner.isSafeId(hexId) && SemWtPlanner.needsRaise(after, pkg, hexId)) {
            SemWtController.run(service, SemWtPlanner.raiseCycle(hexId), log)
        }

        // The device's current mode, so System UI (whose colours all have day and night values) is verified too.
        val night = runCatching {
            (android.content.res.Resources.getSystem().configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        }.getOrNull()
        val probe = OverlayLookup.probe(target, night)
        var verified: Boolean? = null
        var verifyDetail = ""
        if (probe != null) {
            val (match, output) = SemWtController.verifyColor(service, pkg, probe.resourceName, probe.argb)
            verified = match
            val expected = "#%08x".format(probe.argb)
            verifyDetail = when (match) {
                true -> ""
                false -> "lookup ${probe.resourceName} = ${output.lineSequence().firstOrNull().orEmpty()}, expected $expected"
                null -> ""
            }
            buildLog.write(
                if (match == false) "WARN" else "INFO", pkg, "lookup verification",
                mapOf("resource" to probe.resourceName, "expected" to expected, "output" to output, "verified" to match),
            )
            if (match == false) log(LogLine(LogLevel.WARN, "$pkg: $verifyDetail"))
        }
        val finalStatus = if (verified == false && status == TargetResult.OK) TargetResult.PARTIAL else status
        log(
            LogLine(
                if (finalStatus == TargetResult.OK) LogLevel.OK else LogLevel.WARN,
                "$pkg: ${result.resourceCount} resources${if (semwtDisabled) ", SemWT disabled" else ""}" +
                    (if (verified == true) ", verified" else "") +
                    if (finalStatus == TargetResult.PARTIAL) " (partial)" else "",
            ),
        )
        return TargetOutcome(
            target = pkg,
            result = status,
            overlay = target.overlayName,
            colorCount = colors.size,
            drawableCount = drawables.size,
            resourceCount = result.resourceCount,
            semwtDisabled = semwtDisabled,
            millis = result.millis,
            message = if (status == TargetResult.PARTIAL) tail else "",
        ).withVerification(verified, verifyDetail)
    }

    /**
     * Stops the guard, disables every HexNext overlay, re-enables the SemWT overlays Hex Next
     * disabled and removes the legacy shades overlay. Returns true only when the Hex overlays were
     * turned off, every SemWT overlay was re-enabled (and its record could be read), the system
     * palette was put back (or never changed) and no Hex Next font is left installed.
     */
    fun remove(service: IThemeService, log: (LogLine) -> Unit): Boolean {
        // Ends the post-apply monitor watch: a trip after Remove has nothing left to turn off.
        APPLY_GENERATION.incrementAndGet()
        // Removal turns the theme off itself; a monitor trip left over is not one to recover.
        synchronized(TRIP_LOCK) { clearTripWatch() }
        prefs.edit().remove(KEY_VALUES_PENDING).apply()
        val buildLog = BuildLog(appContext)
        val say: (LogLevel, String) -> Unit = { level, text ->
            log(LogLine(level, text))
            buildLog.write(level.name, "", text)
        }
        say(LogLevel.INFO, "Removing the Hex Next theme")
        SemWtController.stopGuard(service) { say(it.level, it.text) }
        val disabled = service.disableHexOverlays()
        val disabledOk = disabled.code() == 0
        say(if (disabledOk) LogLevel.OK else LogLevel.WARN, disabled.output().ifBlank { "disableHexOverlays exit ${disabled.code()}" })
        // An unreadable state file is not "nothing to restore": restore() leaves it (or sets a
        // corrupt one aside) and re-enables nothing, so Remove must not report success.
        val semwtUnreadable = SemWtController.readStateChecked(service).state == null
        SemWtController.restore(service) { say(it.level, it.text) }
        // restore() keeps every SemWT overlay it could not re-enable in the state file; those
        // targets now have neither the Hex overlay nor Samsung's.
        val semwtAfter = SemWtController.readStateChecked(service).state
        val semwtLeft = semwtAfter?.records?.size ?: 0
        if (semwtLeft > 0) {
            say(LogLevel.WARN, "$semwtLeft Samsung SemWT overlays are still disabled; run Remove again.")
        }
        val semwtOk = !semwtUnreadable && semwtAfter != null && semwtLeft == 0
        setTakeoverActive(appContext, false)
        // null: nothing was captured (no shell-tier apply); false: the setting still holds Hex Next's seed.
        val palette = runCatching { MonetSeed.restore(service, log) }
            .onFailure { say(LogLevel.WARN, "System palette restore failed: ${it.javaClass.simpleName}: ${it.message}") }
            .getOrDefault(false)
        val paletteOk = palette != false
        if (!paletteOk) {
            say(LogLevel.WARN, "The system palette still holds the Hex Next seed; run Remove again.")
        }
        cleanupLegacy(service, say)
        val fonts = FontInstaller(service)
        runCatching { fonts.remove(log) }
            .onFailure { say(LogLevel.WARN, "Font removal failed: ${it.javaClass.simpleName}: ${it.message}") }
        // remove() leaves the record in place when it could not unbind the files (no root, or an
        // unmount failed), so a remaining record means the Hex Next font is still installed.
        val fontLeft = runCatching { fonts.hasState() }.getOrDefault(false)
        if (fontLeft) {
            say(LogLevel.WARN, "The Hex Next font is still installed; run Remove again with Shizuku running as root.")
        }
        // The restart protection stays armed while Hex overlays may still be enabled: a failed
        // Remove is exactly when the phone may go on restarting.
        if (disabledOk) {
            EarlyRecoveryHook.uninstall(service)
            guard.confirm()
        } else {
            say(LogLevel.WARN, "Restart protection left on: the Hex Next overlays could not all be turned off.")
        }
        say(LogLevel.INFO, "Build log: ${buildLog.file.path}")
        return disabledOk && semwtOk && paletteOk && !fontLeft
    }

    /**
     * After all targets: every themed target (OK or PARTIAL) must have its
     * `android:SemWT_<target>` disabled. Any SemWT overlay still listed as enabled (a failed
     * disable, or Samsung re-enabled it meanwhile) gets one more disable, and the takeover is
     * recorded so removal restores it. Returns a note naming what is still enabled, or null.
     */
    private fun ensureSemWtDisabled(
        service: IThemeService,
        outcomes: List<TargetOutcome>,
        owner: String,
        before: List<OverlayListEntry>,
        log: (LogLine) -> Unit,
        recordTakeover: (target: String, hexOverlayId: String, semwtEnabledBefore: Boolean) -> Boolean,
    ): String? {
        val themed = outcomes.filter { it.result == TargetResult.OK || it.result == TargetResult.PARTIAL }
        if (themed.isEmpty()) return null
        val now = SemWtController.listOverlays(service)
        val stillOn = ArrayList<String>()
        for (outcome in themed) {
            val semwtId = OneUi9Facts.semWtOverlayId(outcome.target)
            if (now.none { it.overlayId == semwtId && it.enabled }) continue
            if (!SemWtPlanner.isSafeId(semwtId)) continue
            val hexId = now.firstOrNull { it.target == outcome.target && it.overlayId.substringAfter(':') == outcome.overlay }?.overlayId
                ?: "$owner:${outcome.overlay}"
            // Recorded before the disable, like in applyTarget.
            val recorded = recordTakeover(outcome.target, hexId, before.firstOrNull { it.overlayId == semwtId }?.enabled ?: true)
            if (recorded && SemWtController.run(service, listOf(SemWtPlanner.disable(semwtId)), log) == 1) {
                log(LogLine(LogLevel.INFO, "${outcome.target}: $semwtId was still enabled; disabled"))
            } else {
                stillOn += semwtId
            }
        }
        // Samsung's Monet palette overlays also target `android` and repaint the system shades the
        // theme just set; with them enabled Settings kept its stock look (Kyle, build 53). Take them
        // over like a SemWT overlay of a pseudo-target so removal restores them.
        val androidOutcome = themed.firstOrNull { it.target == "android" }
        if (androidOutcome != null) {
            val hexAndroid = now.firstOrNull { it.target == "android" && it.overlayId.substringAfter(':') == androidOutcome.overlay }?.overlayId
                ?: "$owner:${androidOutcome.overlay}"
            for (name in OneUi9Facts.MONET_PALETTE_OVERLAYS) {
                val pseudoTarget = name.removePrefix(OneUi9Facts.SEMWT_PREFIX)
                val id = OneUi9Facts.semWtOverlayId(pseudoTarget)
                if (now.none { it.overlayId == id && it.enabled } || !SemWtPlanner.isSafeId(id)) continue
                val recorded = recordTakeover(pseudoTarget, hexAndroid, before.firstOrNull { it.overlayId == id }?.enabled ?: true)
                if (recorded && SemWtController.run(service, listOf(SemWtPlanner.disable(id)), log) == 1) {
                    log(LogLine(LogLevel.INFO, "android: $id (Monet palette) was enabled; disabled"))
                } else {
                    stillOn += id
                }
            }
        }
        return if (stillOn.isEmpty()) null else "Still enabled after the apply: ${stillOn.joinToString()}"
    }

    /**
     * After all targets: turns off what an earlier apply left for the targets this one did not
     * build ([built]: the targets with an outcome other than EMPTY; deprecated, switched-off or
     * missing targets never get one). Every enabled `HexNext_<target>` of another target is
     * disabled, and every SemWT record whose Hex overlay is no longer enabled is handed back: its
     * SemWT overlay is re-enabled when it was enabled before the takeover, and the record dropped,
     * so the guard stops turning Samsung's overlay off. A record is kept when its Hex overlay
     * could not be disabled or its SemWT overlay could not be re-enabled (Remove retries it).
     * Returns the updated state.
     */
    private fun retireUnbuilt(
        service: IThemeService,
        built: Set<String>,
        semwt: SemWtState,
        log: (LogLine) -> Unit,
        note: (LogLevel, String) -> Unit,
    ): SemWtState {
        val now = SemWtController.listOverlays(service)
        if (now.isEmpty()) {
            note(LogLevel.WARN, "Could not list overlays; overlays an earlier theme left on apps this theme does not cover were not checked.")
            return semwt
        }
        // The Monet palette pseudo-targets ride on the android overlay (see ensureSemWtDisabled).
        val keep = HashSet(built)
        if ("android" in built) {
            OneUi9Facts.MONET_PALETTE_OVERLAYS.forEach { keep += it.removePrefix(OneUi9Facts.SEMWT_PREFIX) }
        }
        val stale = now.filter { entry ->
            entry.enabled && entry.target.isNotEmpty() && entry.target !in built &&
                entry.overlayId.substringAfter(':') == OverlayBuilder.overlayName(entry.target) &&
                SemWtPlanner.isSafeId(entry.overlayId)
        }
        val enabled = now.filter { it.enabled }.mapTo(HashSet()) { it.overlayId }
        val turnedOff = ArrayList<String>()
        val stillOn = ArrayList<String>()
        for (entry in stale) {
            if (SemWtController.run(service, listOf(SemWtPlanner.disable(entry.overlayId)), log) == 1) {
                enabled -= entry.overlayId
                turnedOff += entry.target
                log(LogLine(LogLevel.INFO, "${entry.target}: not in this theme; turned off ${entry.overlayId}"))
            } else {
                stillOn += entry.overlayId
            }
        }
        val handBack = semwt.records.filter { it.target !in keep && it.hexOverlayId !in enabled }
        // A SemWT overlay Samsung no longer registers has nothing to re-enable: its record is dropped.
        val listed = now.mapTo(HashSet()) { it.overlayId }
        val retired = HashSet<String>()
        val failedSemWt = HashSet<String>()
        val restored = HashSet<String>()
        for (record in handBack) {
            if (record.wasEnabled && record.semwtId !in listed) {
                log(LogLine(LogLevel.INFO, "${record.target}: ${record.semwtId} is no longer registered; record dropped"))
            } else if (record.wasEnabled && SemWtPlanner.isSafeId(record.semwtId) && record.semwtId !in restored) {
                if (record.semwtId in failedSemWt ||
                    SemWtController.run(service, listOf(SemWtPlanner.enable(record.semwtId)), log) != 1
                ) {
                    failedSemWt += record.semwtId
                    continue
                }
                restored += record.semwtId
            }
            retired += record.target
        }
        if (turnedOff.isNotEmpty() || restored.isNotEmpty()) {
            note(
                LogLevel.INFO,
                "Earlier theme taken off apps this theme does not cover: " +
                    (if (turnedOff.isEmpty()) "" else "${turnedOff.size} Hex Next overlays turned off (${turnedOff.joinToString()})") +
                    (if (turnedOff.isNotEmpty() && restored.isNotEmpty()) "; " else "") +
                    (if (restored.isEmpty()) "" else "${restored.size} Samsung SemWT overlays re-enabled"),
            )
        }
        if (stillOn.isNotEmpty() || failedSemWt.isNotEmpty()) {
            note(
                LogLevel.WARN,
                "Left from an earlier theme on apps this theme does not cover: " +
                    (stillOn + failedSemWt.map { "$it (still disabled)" }).joinToString() + ". Run Remove theme to clear them.",
            )
        }
        return if (retired.isEmpty()) semwt else semwt.without(retired, System.currentTimeMillis())
    }

    /**
     * Cleanup of the overlays older Hex Next builds left behind ([LegacyOverlays]: the
     * `com.android.shell:HexNextOneUI9_*` per-app overlays of the old "Build & Apply" path,
     * `HexNextMonetShades` (Monet shades on `android`, which skews the system palette Hex Next
     * infers roles from), `HexNextMonetRoles`, `HexNextSettingsIcons26` and `HexNext_probe`):
     * disable each listed one, unregister it when the service runs as root (its owner package is
     * then com.android.shell, matching the legacy owner), and delete its stale `.frro` copies.
     * Runs on every apply and remove while any is still listed. Returns a note when it acted.
     */
    private fun cleanupLegacy(service: IThemeService, say: (LogLevel, String) -> Unit): String? {
        val legacy = LegacyOverlays.find(SemWtController.listOverlays(service))
        if (legacy.isEmpty()) {
            if (!prefs.getBoolean(KEY_LEGACY_DONE, false)) {
                say(LogLevel.INFO, "No legacy Hex Next overlays present")
                prefs.edit().putBoolean(KEY_LEGACY_DONE, true).apply()
            }
            return null
        }
        say(LogLevel.INFO, "Cleaning up ${legacy.size} legacy overlays: ${legacy.joinToString { it.overlayId }}")
        val asRoot = runCatching { service.uid }.getOrDefault(-1) == 0
        for (overlay in legacy) {
            val name = overlay.overlayId.substringAfter(':')
            val disable = service.exec(SemWtPlanner.disable(overlay.overlayId))
            val unregister = if (asRoot) {
                val removed = service.removeOverlay(name)
                if (removed.code() == 0) "unregistered" else "unregister failed: ${removed.output().lineSequence().firstOrNull().orEmpty()}"
            } else {
                "not unregistered (needs root)"
            }
            val purge = LegacyOverlays.purgeCommand(name)?.let { service.exec(it).code() }
            say(LogLevel.INFO, "Legacy ${overlay.overlayId}: disable exit ${disable.code()}, $unregister, .frro purge exit $purge")
        }
        val remaining = LegacyOverlays.find(SemWtController.listOverlays(service))
        val enabled = remaining.filter { it.enabled }
        val text = "Legacy overlays cleaned up: ${legacy.size - remaining.size} unregistered, " +
            "${remaining.size - enabled.size} disabled" +
            if (enabled.isEmpty()) "" else ", STILL ENABLED: ${enabled.joinToString { it.overlayId }}"
        say(if (enabled.isEmpty()) LogLevel.OK else LogLevel.WARN, text)
        prefs.edit().putBoolean(KEY_LEGACY_DONE, true).apply()
        return text
    }

    /**
     * Shell tier ([Capability.SHELL]): builds `HexNext_<target>` from the plain colours of
     * [target] with `cmd overlay fabricate --file` (the XML is written to the state directory,
     * which the shell owns, and removed again) and enables `com.android.shell:<name>`. Night
     * colours need the privileged service and are left out. When the shell refuses to fabricate
     * (AOSP keeps `fabricate` for root: "must be root to fabricate overlays through the shell")
     * [shell] remembers why, so the remaining targets are skipped instead of failing one by one.
     * Returns a [BuildResult] like [OverlayBuilder.apply].
     */
    private fun fabricateViaShell(
        service: IThemeService,
        target: CompiledTarget,
        colors: List<OverlayColor>,
        shell: ShellFabricator,
        buildLog: BuildLog,
        log: (LogLine) -> Unit,
    ): BuildResult {
        val started = System.currentTimeMillis()
        val pkg = target.packageName
        val name = target.overlayName
        fun result(code: Int, output: String, count: Int) =
            BuildResult(code, output, count, "[]", System.currentTimeMillis() - started, "", buildLog.file)
        if (!SAFE_NAME.matches(pkg) || !SAFE_NAME.matches(name)) return result(-1, "unexpected target or overlay name", 0)
        val plain = colors.filter { it.config.isEmpty() && SAFE_RESOURCE.matches(it.name) }
        val configured = colors.size - plain.size
        if (plain.isEmpty()) return result(-1, "no plain colours to build (night colours need root)", 0)

        val xml = buildString {
            appendLine("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
            appendLine("<overlay>")
            for (c in plain) {
                appendLine("    <item target=\"${c.name.substringAfter(':')}\" value=\"0x${"%08x".format(c.argb)}\" type=\"color\" />")
            }
            appendLine("</overlay>")
        }
        val fileName = "fabricate_$name.xml"
        val write = service.writeStateFile(fileName, xml)
        if (write.code() != 0) return result(write.code(), "could not write $fileName: ${write.output()}", 0)
        val path = "${MonitorScript.DIR}/$fileName"
        val id = "${ThemeService.SHELL_OWNER}:$name"
        log(LogLine(LogLevel.INFO, "Building $name through the shell: ${plain.size} colours" + if (configured > 0) " ($configured night colours need root)" else ""))
        buildLog.write("INFO", pkg, "shell fabricate", mapOf("overlay" to name, "colors" to plain.size, "skippedConfigured" to configured, "file" to path))

        val fabricate = service.exec("cmd overlay fabricate --target $pkg --name $name --file $path")
        runCatching { service.exec("rm -f $path") }
        val fabricateOut = fabricate.output().trim()
        buildLog.write(if (fabricate.code() == 0) "INFO" else "WARN", pkg, "cmd overlay fabricate", mapOf("code" to fabricate.code(), "output" to fabricateOut))
        if (fabricate.code() != 0) {
            val why = fabricateOut.lineSequence().firstOrNull { it.isNotBlank() } ?: "exit ${fabricate.code()}"
            if (ShellFabricator.isRefusal(fabricateOut)) shell.unavailable = why
            return result(fabricate.code(), fabricateOut.ifEmpty { "cmd overlay fabricate exit ${fabricate.code()}" }, 0)
        }
        val enable = service.exec(SemWtPlanner.enable(id))
        val enableOut = enable.output().trim()
        buildLog.write(if (enable.code() == 0) "INFO" else "WARN", pkg, "cmd overlay enable", mapOf("code" to enable.code(), "output" to enableOut))
        if (enable.code() != 0) {
            return result(enable.code(), "fabricated $id but enable failed: ${enableOut.ifEmpty { "exit ${enable.code()}" }}", 0)
        }
        log(LogLine(LogLevel.INFO, "Fabricated and enabled $id (${plain.size} colours)"))
        return result(0, listOf(fabricateOut, "Enabled $id").filter { it.isNotEmpty() }.joinToString("\n"), plain.size)
    }

    /** Shell-tier state across targets: once `cmd overlay fabricate` refused the shell, the rest are not tried. */
    private class ShellFabricator {
        @Volatile
        var unavailable: String? = null

        companion object {
            private val REFUSAL = listOf("root", "permission", "denied", "not allowed", "unknown option", "unknown command")

            /** True when the output says the shell may not fabricate at all (as opposed to one bad resource). */
            fun isRefusal(output: String): Boolean = REFUSAL.any { output.contains(it, ignoreCase = true) }
        }
    }

    private fun saveReport(report: ApplyReport) {
        runCatching {
            val file = reportFile(appContext)
            file.parentFile?.mkdirs()
            file.writeText(report.toJson())
        }
    }

    /**
     * True once this apply's stability monitor has written `safe_mode.json` (it is deleted when
     * the monitor starts, so the file can only come from this apply). A failed read is not a trip.
     */
    private fun monitorTripped(service: IThemeService): Boolean =
        runCatching { service.readStateFile(SAFE_MODE_NAME).code() == 0 }.getOrDefault(false)

    /**
     * Keeps polling for this apply's `safe_mode.json` after [apply] returned, until the monitor's
     * window is over or another apply or a removal starts. Without this a trip after the apply
     * (the usual case: SystemUI crash-loops once the theme is on) would leave the app saying the
     * theme is applied and still asking to confirm it.
     */
    private fun watchMonitorAfterApply(
        service: IThemeService,
        generation: Int,
        monitorStartedAt: Long,
        report: ApplyReport,
        log: (LogLine) -> Unit,
    ) {
        val deadline = monitorStartedAt + (MONITOR_SECONDS + WATCH_GRACE_SECONDS) * 1000L
        val watcher = Thread({ watchMonitorLoop(service, generation, deadline, report, log) }, "hexnext-monitor-watch")
        watcher.isDaemon = true
        watcher.start()
    }

    private fun watchMonitorLoop(
        service: IThemeService,
        generation: Int,
        deadline: Long,
        report: ApplyReport,
        log: (LogLine) -> Unit,
    ) {
        var valuesHandled = false
        var until = deadline
        while (APPLY_GENERATION.get() == generation && System.currentTimeMillis() < until) {
            if (!valuesHandled) {
                synchronized(TRIP_LOCK) {
                    if (APPLY_GENERATION.get() == generation) valuesHandled = handleValuesRollback(service, log)
                }
                // Stage two of the staged monitor runs a fresh window from the rollback.
                if (valuesHandled) {
                    val from = prefs.getLong(KEY_TRIP_MONITOR_AT, 0L).coerceAtLeast(System.currentTimeMillis())
                    until = maxOf(until, from + (MONITOR_SECONDS + WATCH_GRACE_SECONDS) * 1000L)
                }
            }
            if (monitorTripped(service)) {
                // Checked again under the lock: an apply or removal started meanwhile owns the overlays.
                synchronized(TRIP_LOCK) {
                    // A cleared trip record: recoverMissedTrip handled this trip already.
                    if (APPLY_GENERATION.get() == generation && prefs.getLong(KEY_TRIP_APPLY_AT, 0L) == report.startedAt) {
                        onTripAfterApply(service, report, log)
                    }
                }
                return
            }
            try {
                Thread.sleep(WATCH_INTERVAL_MS)
            } catch (e: InterruptedException) {
                return
            }
        }
    }

    /**
     * The monitor fired after the apply finished. It has already turned the HexNext overlays off
     * (and the SemWT guard gave Samsung's back), so the app follows as safe mode does: the guard
     * is marked (confirmed, banner on next start), the SemWT record is restored, the palette seed
     * restored and the font removed, the takeover flag cleared and the saved report no longer says
     * the theme is applied.
     */
    private fun onTripAfterApply(
        service: IThemeService,
        report: ApplyReport,
        log: (LogLine) -> Unit,
        why: String = "SystemUI or system_server restarted twice after the apply; the stability monitor turned the theme off.",
    ) {
        guard.markSafeModeEntered(by = "stability monitor")
        clearTripWatch()
        quarantinePendingValues()
        runCatching { log(LogLine(LogLevel.ERROR, why)) }
        runCatching { service.disableHexOverlays() }
        runCatching { SemWtController.restore(service) { line -> if (line.level != LogLevel.INFO) log(line) } }
        // The monitor only turns overlays off. The font (bound live and kept as a module that is
        // mounted again at every boot) and the shell-tier palette seed were set after it started,
        // and the mark above switches off the boot-time guards that would remove them, so they go here.
        runCatching { MonetSeed.restore(service) { line -> if (line.level != LogLevel.INFO) log(line) } }
            .onFailure { runCatching { log(LogLine(LogLevel.WARN, "System palette restore failed: ${it.javaClass.simpleName}: ${it.message}")) } }
        val fonts = FontInstaller(service)
        runCatching { fonts.remove { line -> if (line.level != LogLevel.INFO) log(line) } }
            .onFailure { runCatching { log(LogLine(LogLevel.WARN, "Font removal failed: ${it.javaClass.simpleName}: ${it.message}")) } }
        if (runCatching { fonts.hasState() }.getOrDefault(false)) {
            runCatching { log(LogLine(LogLevel.WARN, "The Hex Next font is still installed; run Remove theme with Shizuku running as root.")) }
        }
        val left = runCatching { SemWtController.readStateChecked(service).state }.getOrNull()
        // An unreadable record keeps the flag, so Remove theme still knows there is something to restore.
        setTakeoverActive(appContext, left == null || left.records.isNotEmpty())
        if (lastReport(appContext)?.startedAt == report.startedAt) {
            saveReport(report.copy(status = ApplyReport.STATUS_ABORTED, error = why, notes = report.notes + why))
        }
    }

    /**
     * Finishes the trip handling no app process saw. A system_server restart kills every app
     * process, the post-apply watcher and a running apply included, while the root monitor goes
     * on, trips, turns the HexNext overlays off and writes `safe_mode.json`. The boot id does not
     * change on such a restart, so the restart guard never fires either. Here, in a later
     * process, a `safe_mode.json` written inside the window of the monitor the last apply started
     * gets the same handling as [onTripAfterApply]. Returns true when it acted.
     */
    fun recoverMissedTrip(service: IThemeService, log: (LogLine) -> Unit): Boolean {
        // An apply running in this process watches its own monitor.
        if (RUNNING_APPLIES.get() > 0) return false
        // First: a values rollback moves the trip watch to the staged monitor's second window.
        synchronized(TRIP_LOCK) { if (RUNNING_APPLIES.get() == 0) handleValuesRollback(service, log) }
        val applyAt = prefs.getLong(KEY_TRIP_APPLY_AT, 0L)
        val monitorAt = prefs.getLong(KEY_TRIP_MONITOR_AT, 0L)
        if (applyAt <= 0L || monitorAt <= 0L) return false
        val read = runCatching { service.readStateFile(SAFE_MODE_NAME) }.getOrNull() ?: return false
        val windowEnd = monitorAt + (MONITOR_SECONDS + WATCH_GRACE_SECONDS) * 1000L
        // code 1: the file does not exist. Once the window is over that means no trip: the
        // watch is dropped, so it no longer keeps [isTakeoverActive] (and the boot check) on.
        if (read.code() == 1 && System.currentTimeMillis() > windowEnd) {
            expireTripWatch(applyAt, monitorAt)
            return false
        }
        if (read.code() != 0) return false
        val content = read.getString(ThemeService.KEY_CONTENT).orEmpty()
        val tripAt = TRIP_TIME.find(content)?.groupValues?.get(1)?.toLongOrNull()?.times(1000L) ?: return false
        // `date +%s` has whole seconds; a later monitor (another apply, the Settings test) deletes the file first.
        if (tripAt < monitorAt - 1000L || tripAt > windowEnd) {
            if (System.currentTimeMillis() > windowEnd) expireTripWatch(applyAt, monitorAt)
            return false
        }
        synchronized(TRIP_LOCK) {
            // Checked again under the lock: an apply, a removal or the watcher may have got here first.
            if (RUNNING_APPLIES.get() > 0) return false
            if (prefs.getLong(KEY_TRIP_APPLY_AT, 0L) != applyAt || prefs.getLong(KEY_TRIP_MONITOR_AT, 0L) != monitorAt) return false
            val report = lastReport(appContext)
            if (report == null || report.startedAt != applyAt || report.status == ApplyReport.STATUS_ABORTED) {
                // Removed, replaced or already ended: nothing claims the theme is still on.
                clearTripWatch()
                return false
            }
            val why = if (report.finished) {
                "SystemUI or system_server restarted twice after the apply; the stability monitor turned the theme off."
            } else {
                "SystemUI or system_server restarted twice during the apply; the stability monitor turned the theme off and the apply was stopped."
            }
            onTripAfterApply(service, report, log, why)
        }
        return true
    }

    /** Remembers which apply's monitor a later process may find tripped ([recoverMissedTrip]). */
    private fun recordTripWatch(applyAt: Long, monitorAt: Long) {
        // commit(): a system_server restart can kill this process right after the monitor starts.
        prefs.edit().putLong(KEY_TRIP_APPLY_AT, applyAt).putLong(KEY_TRIP_MONITOR_AT, monitorAt).commit()
    }

    /** Drops the trip watch of [applyAt]/[monitorAt] unless an apply or removal replaced it meanwhile. */
    private fun expireTripWatch(applyAt: Long, monitorAt: Long) {
        synchronized(TRIP_LOCK) {
            if (RUNNING_APPLIES.get() > 0) return
            if (prefs.getLong(KEY_TRIP_APPLY_AT, 0L) != applyAt || prefs.getLong(KEY_TRIP_MONITOR_AT, 0L) != monitorAt) return
            clearTripWatch()
        }
    }

    private fun clearTripWatch() {
        if (!prefs.contains(KEY_TRIP_APPLY_AT) && !prefs.contains(KEY_TRIP_MONITOR_AT)) return
        prefs.edit().remove(KEY_TRIP_APPLY_AT).remove(KEY_TRIP_MONITOR_AT).commit()
    }

    /**
     * The monitor fired during the apply: turns off the HexNext overlays built after it did,
     * gives the SemWT overlays taken over so far back (as the guard would) and ends the apply
     * as aborted, so the report never says success.
     */
    private fun stopAfterSafeMode(
        service: IThemeService,
        log: (LogLine) -> Unit,
        note: (LogLevel, String) -> Unit,
        onSemWt: (SemWtState) -> Unit,
        semwt: SemWtState,
    ): Nothing {
        val why = "SystemUI or system_server restarted twice during the apply; the stability monitor turned the theme off and the apply was stopped."
        // As after the apply: the theme is already off, so there is nothing left to confirm.
        guard.markSafeModeEntered(by = "stability monitor")
        clearTripWatch()
        quarantinePendingValues()
        note(LogLevel.ERROR, why)
        val disabled = runCatching { service.disableHexOverlays() }.getOrNull()
        if (disabled == null || disabled.code() != 0) {
            note(LogLevel.WARN, "Some Hex Next overlays may still be enabled; run Remove theme. ${disabled?.output().orEmpty()}".trim())
        }
        runCatching { SemWtController.restore(service, log) }
        val left = runCatching { SemWtController.readStateChecked(service, log).state }.getOrNull() ?: semwt
        onSemWt(left)
        setTakeoverActive(appContext, left.records.isNotEmpty())
        throw ApplyAborted(why)
    }

    private fun Bundle.code() = getInt(ThemeService.KEY_CODE, -1)
    private fun Bundle.output() = getString(ThemeService.KEY_OUTPUT).orEmpty()

    /** Thrown by [applyAll] when the apply cannot start; recorded as `aborted` with this message. */
    private class ApplyAborted(message: String) : RuntimeException(message)

    companion object {
        const val MONITOR_SECONDS = 120

        /** Poll interval and slack after the monitor window for [watchMonitorAfterApply]. */
        private const val WATCH_INTERVAL_MS = 5_000L
        private const val WATCH_GRACE_SECONDS = 20

        /** Bumped by every apply and removal; a post-apply watch stops once it changes. */
        private val APPLY_GENERATION = AtomicInteger()

        /**
         * Held by a post-apply trip handler while it turns the theme off; [apply] and [remove]
         * take it after bumping [APPLY_GENERATION] so they never overlap a running handler.
         */
        private val TRIP_LOCK = Any()

        /** Applies running in this process; [recoverMissedTrip] leaves a trip to them. */
        private val RUNNING_APPLIES = AtomicInteger()

        /** The `"time":<epoch seconds>` field of the monitor's `safe_mode.json`. */
        private val TRIP_TIME = Regex("\"time\"\\s*:\\s*(\\d+)")
        private const val KEY_TRIP_APPLY_AT = "tripWatchApplyAt"
        private const val KEY_TRIP_MONITOR_AT = "tripWatchMonitorAt"

        /** Hash of the value set the last apply put on, until its monitor window is over. */
        private const val KEY_VALUES_PENDING = "valuesPending"

        /** Hash of a value set a System UI crash rollback switched off; not applied again. */
        private const val KEY_VALUES_QUARANTINE = "valuesQuarantine"

        /** SHA-256 of [EngineThemeSpec.valuesKey], the identity of a value set. */
        fun valuesKeyHash(spec: EngineThemeSpec): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(spec.valuesKey().toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }

        /** True when [spec]'s sizes are the set a crash rollback switched off. */
        fun isValuesQuarantined(context: Context, spec: EngineThemeSpec): Boolean =
            spec.hasValues() &&
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_VALUES_QUARANTINE, null)
                    ?.substringBefore(':') == valuesKeyHash(spec)

        /** Forgets the quarantined value set, so the next apply tries the sizes again. */
        fun clearValuesQuarantine(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_VALUES_QUARANTINE).apply()
        }

        /**
         * The application context, kept by every entry point that is handed one, so that
         * [SemWtController.reassert] (run at app start and at boot, after [isTakeoverActive])
         * can run [recoverMissedTrip] without a context of its own.
         */
        @Volatile
        private var knownContext: Context? = null

        private fun rememberContext(context: Context) {
            if (knownContext == null) knownContext = context.applicationContext
        }

        /** [recoverMissedTrip] for callers that have a context (app start, boot receiver). */
        fun recoverMissedTrip(context: Context, service: IThemeService, log: (LogLine) -> Unit): Boolean =
            ThemeApplier(context).recoverMissedTrip(service, log)

        /** [recoverMissedTrip] with the remembered context; false when none was seen yet. */
        internal fun recoverMissedTripWithKnownContext(service: IThemeService, log: (LogLine) -> Unit): Boolean {
            val context = knownContext ?: return false
            return runCatching { ThemeApplier(context).recoverMissedTrip(service, log) }
                .onFailure { runCatching { log(LogLine(LogLevel.WARN, "Monitor trip check failed: ${it.javaClass.simpleName}: ${it.message}")) } }
                .getOrDefault(false)
        }

        /** The monitor's trip record, as a state file name ([IThemeService.readStateFile]). */
        private val SAFE_MODE_NAME = MonitorScript.SAFE_MODE_FILE.substringAfterLast('/')
        private const val MIB = 1024 * 1024
        private const val PREFS = "hexnext_theme_applier"
        private const val KEY_LEGACY_DONE = "legacyShadesCleaned"
        private const val KEY_TAKEOVER_ACTIVE = "semwtTakeoverActive"
        private const val MARKER_NOT_APPLICABLE = "---"

        /** `applyOverlay` code for "committed colours only". */
        private const val PARTIAL_CODE = 3

        /** Package and overlay names that may go into a shell command unquoted. */
        private val SAFE_NAME = Regex("^[A-Za-z0-9_.]+$")

        /** `pkg:type/name` resource names that may go into the fabricate XML. */
        private val SAFE_RESOURCE = Regex("^[A-Za-z0-9_.]+:[A-Za-z0-9_]+/[A-Za-z0-9_.]+$")

        /** The su binaries the boot receiver looks for before it can act (same list as BootReceiver). */
        private val SU_PATHS = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/debug_ramdisk/su")

        /** Pack zips (`<id>.zip`, `<id>.hexnext`, optional `index.json`). */
        fun packsDir(context: Context): File = File(context.filesDir, PackDirs.APPLY).apply { mkdirs() }

        /**
         * Locked catalogue packs downloaded for the preview only ([PackDirs.PREVIEW]). The apply,
         * the overlay builder and Diagnostics' repair never read it: they resolve packs from
         * [packsDir] alone, so a pack that is not unlocked cannot reach an overlay.
         */
        fun previewPacksDir(context: Context): File = File(context.filesDir, PackDirs.PREVIEW).apply { mkdirs() }

        fun reportFile(context: Context): File = File(File(context.filesDir, "reports"), "apply-last.json")

        /**
         * True while a compiled theme has SemWT overlays taken over, or while the last apply's
         * stability monitor may have tripped unseen (a trip watch is recorded). App start and the
         * boot receiver run [SemWtController.reassert] then, which runs [recoverMissedTrip] first;
         * without the second part an apply with no SemWT records (shell tier, font only) would
         * never have a missed trip recovered. [recoverMissedTrip] drops the watch once its window
         * is over without a trip.
         */
        fun isTakeoverActive(context: Context): Boolean {
            rememberContext(context)
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_TAKEOVER_ACTIVE, false) || prefs.getLong(KEY_TRIP_APPLY_AT, 0L) > 0L
        }

        fun setTakeoverActive(context: Context, active: Boolean) {
            rememberContext(context)
            // commit(): the boot receiver and safe mode read this right before the process may die.
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_TAKEOVER_ACTIVE, active).commit()
        }

        /** The report of the last [apply], or null. */
        fun lastReport(context: Context): ApplyReport? = runCatching {
            val file = reportFile(context)
            if (file.isFile) ApplyReport.fromJson(file.readText()) else null
        }.getOrNull()
    }
}
