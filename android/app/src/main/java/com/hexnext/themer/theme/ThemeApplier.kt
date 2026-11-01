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
import com.hexnext.themer.overlay.DrawableSpec
import com.hexnext.themer.overlay.OverlayBuilder
import com.hexnext.themer.overlay.OverlayColor
import com.hexnext.themer.overlay.OverlayDrawable
import com.hexnext.themer.safety.ApplyGuard
import com.hexnext.themer.shizuku.ThemeService
import java.io.File
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
 * target that is still enabled. The SemWT state is saved and the re-assert guard started.
 *
 * The [ApplyReport] in `files/reports/apply-last.json` is written when the apply starts (status
 * `started`, then with the compiled target list), after every target (`running`, with the
 * targets still pending) and at the end (`finished`). Any throwable, OutOfMemoryError included,
 * aborts the apply and is recorded (`aborted`, with the error) in the report and the build log.
 *
 * [remove]: stop the guard, disable (and when privileged unregister) every HexNext overlay,
 * re-enable the SemWT overlays Hex Next disabled, and clean up the legacy overlays.
 */
class ThemeApplier(context: Context) {

    private val appContext = context.applicationContext
    private val builder = OverlayBuilder(appContext)
    private val snapshots = SnapshotBuilder(appContext)
    private val guard = ApplyGuard(appContext)
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun apply(service: IThemeService, spec: EngineThemeSpec, log: (LogLine) -> Unit): ApplyReport {
        val started = System.currentTimeMillis()
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
            applyAll(service, spec, buildLog, log, ::say, ::note, notes, outcomes, { status, left -> pending = left; progress(status) }) { semwt = it }
            finish()
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
        val privileged = uid == 0 || uid == 1000
        if (!privileged) {
            // Nothing can be committed from an unprivileged service: `cmd overlay fabricate` takes a
            // single resource per call and replaces the overlay each time. Stop before touching anything.
            throw ApplyAborted(
                "The theme service runs as uid $uid, but committing overlays needs uid 0 or 1000. " +
                    "Start Shizuku with root (or restart it after a reboot) and apply again.",
            )
        }
        val owner = if (uid == 1000) ThemeService.SYSTEM_OWNER else ThemeService.SHELL_OWNER
        compiled.events.forEach { e -> buildLog.write(e.level.name, e.target, e.message, e.data) }
        packs.errors.forEach { (file, why) -> note(LogLevel.WARN, "Pack $file unreadable: $why") }
        say(
            LogLevel.INFO,
            "Compiled ${compiled.targets.size} targets (${compiled.targets.sumOf { it.colors.size }} colours, " +
                "${compiled.targets.sumOf { it.drawables.size }} drawables); ${compiled.skippedTargets.size} skipped",
            emptyMap(),
        )
        onProgress(ApplyReport.STATUS_STARTED, compiled.targets.map { it.packageName })

        cleanupLegacy(service) { level, text -> say(level, text, emptyMap()) }?.let { notes += it }

        guard.recordApply()
        val monitor = service.startStabilityMonitor(MONITOR_SECONDS)
        say(LogLevel.INFO, "Stability monitor: ${monitor.output()}", emptyMap())

        var semwt = SemWtController.readState(service, log)
        onSemWt(semwt)
        val before = SemWtController.listOverlays(service)
        DrawableRenderer(packs).use { renderer ->
            compiled.targets.forEachIndexed { index, target ->
                val outcome = try {
                    applyTarget(service, target, renderer, privileged, owner, before, buildLog, log) { pkg, hexId, enabledBefore ->
                        semwt = semwt.withTakeover(pkg, hexId, enabledBefore, System.currentTimeMillis())
                        onSemWt(semwt)
                    }
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

        ensureSemWtDisabled(service, outcomes, owner, before, log) { pkg, hexId, enabledBefore ->
            semwt = semwt.withTakeover(pkg, hexId, enabledBefore, System.currentTimeMillis())
            onSemWt(semwt)
        }?.let { note(LogLevel.WARN, it) }

        if (!SemWtController.writeState(service, semwt)) {
            note(LogLevel.WARN, "Could not save ${SemWtState.FILE_NAME}; removal cannot restore SemWT overlays automatically.")
        }
        SemWtController.startGuard(service, semwt) { say(it.level, it.text, emptyMap()) }
        setTakeoverActive(appContext, semwt.records.isNotEmpty())

        outcomes += ApplyReport.skippedOutcomes(compiled)
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
        privileged: Boolean,
        owner: String,
        before: List<OverlayListEntry>,
        buildLog: BuildLog,
        log: (LogLine) -> Unit,
        recordTakeover: (target: String, hexOverlayId: String, semwtEnabledBefore: Boolean) -> Unit,
    ): TargetOutcome {
        val pkg = target.packageName
        if (target.isEmpty) {
            buildLog.write("INFO", pkg, "nothing to build", mapOf("skipped" to target.skipped.size))
            return TargetOutcome(pkg, TargetResult.EMPTY, target.overlayName, message = "nothing matched on this device")
        }
        check(target.overlayName == OverlayBuilder.overlayName(pkg)) { "overlay name mismatch for $pkg" }

        val drawables = ArrayList<OverlayDrawable>()
        var renderFailures = 0
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
        }
        val colors = target.colors.map { OverlayColor(it.resourceName, it.argb, it.configuration) }
        val request = BuildRequest(pkg, colors, drawables)
        if (request.colors.isEmpty() && request.drawables.isEmpty()) {
            return TargetOutcome(pkg, TargetResult.EMPTY, target.overlayName, message = "no colours and no renderable drawables")
        }

        val result = builder.apply(service, request, buildLog) { line -> log(LogLine(LogLevel.INFO, line)) }
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
            val disableOk = SemWtController.run(service, commands.take(1), log)
            val raiseOk = SemWtController.run(service, commands.drop(1), log)
            semwtDisabled = commands.isNotEmpty() && disableOk == 1
            if (semwtDisabled) recordTakeover(pkg, hexId, semwtRow.enabled)
            val ok = disableOk + raiseOk
            buildLog.write(
                if (ok == commands.size) "INFO" else "WARN", pkg, "SemWT takeover",
                mapOf("semwt" to semwtId, "wasEnabled" to semwtRow.enabled, "hexOverlay" to hexId, "commandsOk" to ok, "commands" to commands.size),
            )
        } else if (SemWtPlanner.isSafeId(hexId) && SemWtPlanner.needsRaise(after, pkg, hexId)) {
            SemWtController.run(service, SemWtPlanner.raiseCycle(hexId), log)
        }

        val probe = OverlayLookup.probe(target)
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
     * disabled and removes the legacy shades overlay. Returns true when the Hex overlays were
     * turned off.
     */
    fun remove(service: IThemeService, log: (LogLine) -> Unit): Boolean {
        val buildLog = BuildLog(appContext)
        val say: (LogLevel, String) -> Unit = { level, text ->
            log(LogLine(level, text))
            buildLog.write(level.name, "", text)
        }
        say(LogLevel.INFO, "Removing the Hex Next theme")
        SemWtController.stopGuard(service) { say(it.level, it.text) }
        val disabled = service.disableHexOverlays()
        val ok = disabled.code() == 0
        say(if (ok) LogLevel.OK else LogLevel.WARN, disabled.output().ifBlank { "disableHexOverlays exit ${disabled.code()}" })
        SemWtController.restore(service) { say(it.level, it.text) }
        setTakeoverActive(appContext, false)
        cleanupLegacy(service, say)
        guard.confirm()
        say(LogLevel.INFO, "Build log: ${buildLog.file.path}")
        return ok
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
        recordTakeover: (target: String, hexOverlayId: String, semwtEnabledBefore: Boolean) -> Unit,
    ): String? {
        val themed = outcomes.filter { it.result == TargetResult.OK || it.result == TargetResult.PARTIAL }
        if (themed.isEmpty()) return null
        val now = SemWtController.listOverlays(service)
        val stillOn = ArrayList<String>()
        for (outcome in themed) {
            val semwtId = OneUi9Facts.semWtOverlayId(outcome.target)
            if (now.none { it.overlayId == semwtId && it.enabled }) continue
            if (!SemWtPlanner.isSafeId(semwtId)) continue
            val ok = SemWtController.run(service, listOf(SemWtPlanner.disable(semwtId)), log) == 1
            if (ok) {
                val hexId = now.firstOrNull { it.target == outcome.target && it.overlayId.substringAfter(':') == outcome.overlay }?.overlayId
                    ?: "$owner:${outcome.overlay}"
                recordTakeover(outcome.target, hexId, before.firstOrNull { it.overlayId == semwtId }?.enabled ?: true)
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
                if (SemWtController.run(service, listOf(SemWtPlanner.disable(id)), log) == 1) {
                    recordTakeover(pseudoTarget, hexAndroid, before.firstOrNull { it.overlayId == id }?.enabled ?: true)
                    log(LogLine(LogLevel.INFO, "android: $id (Monet palette) was enabled; disabled"))
                } else {
                    stillOn += id
                }
            }
        }
        return if (stillOn.isEmpty()) null else "Still enabled after the apply: ${stillOn.joinToString()}"
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

    private fun saveReport(report: ApplyReport) {
        runCatching {
            val file = reportFile(appContext)
            file.parentFile?.mkdirs()
            file.writeText(report.toJson())
        }
    }

    private fun Bundle.code() = getInt(ThemeService.KEY_CODE, -1)
    private fun Bundle.output() = getString(ThemeService.KEY_OUTPUT).orEmpty()

    /** Thrown by [applyAll] when the apply cannot start; recorded as `aborted` with this message. */
    private class ApplyAborted(message: String) : RuntimeException(message)

    companion object {
        const val MONITOR_SECONDS = 120
        private const val MIB = 1024 * 1024
        private const val PREFS = "hexnext_theme_applier"
        private const val KEY_LEGACY_DONE = "legacyShadesCleaned"
        private const val KEY_TAKEOVER_ACTIVE = "semwtTakeoverActive"
        private const val MARKER_NOT_APPLICABLE = "---"

        /** `applyOverlay` code for "committed colours only". */
        private const val PARTIAL_CODE = 3

        /** Pack zips (`<id>.zip`, `<id>.hexnext`, optional `index.json`). */
        fun packsDir(context: Context): File = File(context.filesDir, "packs").apply { mkdirs() }

        fun reportFile(context: Context): File = File(File(context.filesDir, "reports"), "apply-last.json")

        /** True while a compiled theme has SemWT overlays taken over (the boot receiver re-asserts then). */
        fun isTakeoverActive(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_TAKEOVER_ACTIVE, false)

        fun setTakeoverActive(context: Context, active: Boolean) {
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
