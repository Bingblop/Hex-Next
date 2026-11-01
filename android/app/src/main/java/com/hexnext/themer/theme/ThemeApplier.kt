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
 * `HexNextMonetShades` overlay, arm the boot-loop guard and the 120 s stability monitor, then per
 * compiled target: render its drawables ([DrawableRenderer]), fabricate `HexNext_<target>` through
 * [OverlayBuilder] (build log + saved request for repair), disable `android:SemWT_<target>` and,
 * only when `cmd overlay list` shows the Hex overlay below another enabled overlay, raise it with a
 * disable/enable cycle ([SemWtPlanner.takeover]; One UI 9 puts an overlay on top on every enable),
 * then verify with `cmd overlay lookup` that the first plain colour resolves to the value set
 * (a mismatch makes the target PARTIAL). A failing target is recorded and the next one is built. The SemWT state is saved and the re-assert guard started; the [ApplyReport] is
 * saved to `files/reports/apply-last.json`.
 *
 * [remove]: stop the guard, disable (and when privileged unregister) every HexNext overlay,
 * re-enable the SemWT overlays Hex Next disabled, and remove the legacy overlay.
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
        fun say(level: LogLevel, text: String, data: Map<String, Any?> = emptyMap()) {
            log(LogLine(level, text))
            buildLog.write(level.name, "", text, data)
        }
        fun note(level: LogLevel, text: String) {
            notes += text
            say(level, text)
        }
        fun finish(outcomes: List<TargetOutcome>): ApplyReport {
            val report = ApplyReport(
                themeId = spec.id,
                themeName = spec.name,
                startedAt = started,
                finishedAt = System.currentTimeMillis(),
                outcomes = outcomes,
                logFile = buildLog.file.absolutePath,
                notes = notes.toList(),
            )
            saveReport(report)
            buildLog.write(
                if (report.failedCount == 0) "OK" else "WARN", "", "theme apply finished",
                mapOf("summary" to report.summary(), "millis" to report.finishedAt - started),
            )
            log(LogLine(if (report.success) LogLevel.OK else LogLevel.WARN, "Theme apply: ${report.summary()}"))
            log(LogLine(LogLevel.INFO, "Build log: ${buildLog.file.path}"))
            return report
        }

        say(LogLevel.INFO, "Applying theme \"${spec.name}\" (${spec.id})")
        val built = runCatching { snapshots.build() }.getOrElse {
            note(LogLevel.ERROR, "Reading the device scan failed: ${it.javaClass.simpleName}: ${it.message}")
            return finish(emptyList())
        }
        if (built == null) {
            note(LogLevel.ERROR, "No device scan yet. Open Diagnostics and run the scan first.")
            return finish(emptyList())
        }
        built.warnings.forEach { note(LogLevel.WARN, it) }
        val oneUi = built.snapshot.oneUi.version?.toString() ?: "unknown One UI"
        say(LogLevel.INFO, "Device snapshot: $oneUi, ${built.snapshot.targets.size} targets, ${built.snapshot.systemShades.size} system shades")

        val packs = DirectoryPackResolver(packsDir(appContext))
        val compiled: CompiledTheme = try {
            ThemeCompiler.compile(spec, built.snapshot, packs)
        } catch (t: Throwable) {
            note(LogLevel.ERROR, "Compile failed: ${t.javaClass.simpleName}: ${t.message}")
            return finish(emptyList())
        }
        compiled.events.forEach { e -> buildLog.write(e.level.name, e.target, e.message, e.data) }
        packs.errors.forEach { (file, why) -> note(LogLevel.WARN, "Pack $file unreadable: $why") }
        say(
            LogLevel.INFO,
            "Compiled ${compiled.targets.size} targets (${compiled.targets.sumOf { it.colors.size }} colours, " +
                "${compiled.targets.sumOf { it.drawables.size }} drawables); ${compiled.skippedTargets.size} skipped",
        )

        cleanupLegacy(service) { level, text -> say(level, text) }?.let { notes += it }

        guard.recordApply()
        val monitor = service.startStabilityMonitor(MONITOR_SECONDS)
        say(LogLevel.INFO, "Stability monitor: ${monitor.output()}")

        val privileged = runCatching { service.uid }.getOrDefault(-1).let { it == 0 || it == 1000 }
        val owner = if (runCatching { service.uid }.getOrDefault(-1) == 1000) ThemeService.SYSTEM_OWNER else ThemeService.SHELL_OWNER
        if (!privileged) {
            note(LogLevel.WARN, "The service is not privileged (uid 0/1000): drawables and night colours are skipped, day colours go through `cmd overlay fabricate`.")
        }

        var semwt = SemWtController.readState(service, log)
        val before = SemWtController.listOverlays(service)
        val outcomes = ArrayList<TargetOutcome>()
        DrawableRenderer(packs).use { renderer ->
            for (target in compiled.targets) {
                val outcome = try {
                    applyTarget(service, target, renderer, privileged, owner, before, buildLog, log) { pkg, hexId, enabledBefore ->
                        semwt = semwt.withTakeover(pkg, hexId, enabledBefore, System.currentTimeMillis())
                    }
                } catch (t: Throwable) {
                    val message = "${t.javaClass.simpleName}: ${t.message}"
                    buildLog.write("ERROR", target.packageName, "target failed", mapOf("error" to message))
                    log(LogLine(LogLevel.ERROR, "${target.packageName}: $message"))
                    TargetOutcome(target.packageName, TargetResult.FAILED, target.overlayName, message = message)
                }
                outcomes += outcome
            }
        }

        if (!SemWtController.writeState(service, semwt)) {
            note(LogLevel.WARN, "Could not save ${SemWtState.FILE_NAME}; removal cannot restore SemWT overlays automatically.")
        }
        SemWtController.startGuard(service, semwt) { say(it.level, it.text) }
        setTakeoverActive(appContext, semwt.records.isNotEmpty())

        outcomes += ApplyReport.skippedOutcomes(compiled)
        return finish(outcomes)
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
        val semwtRow = before.firstOrNull { it.overlayId == semwtId }
        var semwtDisabled = false
        if (semwtRow != null && semwtRow.marker != MARKER_NOT_APPLICABLE) {
            // Disable SemWT; raise ours (disable/enable cycle) only when the list shows it below another overlay.
            val commands = SemWtPlanner.takeover(pkg, hexId, after)
            val ok = SemWtController.run(service, commands, log)
            semwtDisabled = commands.isNotEmpty() && ok >= 1
            if (semwtDisabled) recordTakeover(pkg, hexId, semwtRow.enabled)
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
     * One-time cleanup of the old skeleton's `com.android.shell:HexNextMonetShades` overlay
     * (Monet shades on `android`, which skews the system palette Hex Next infers roles from):
     * disable it, unregister it when the service runs as root, and delete its stale `.frro`
     * copies. Runs again whenever the overlay is still listed. Returns a note when it acted.
     */
    private fun cleanupLegacy(service: IThemeService, say: (LogLevel, String) -> Unit): String? {
        val present = SemWtController.listOverlays(service).any { it.overlayId == LEGACY_SHADES_ID }
        if (!present) {
            if (!prefs.getBoolean(KEY_LEGACY_DONE, false)) {
                say(LogLevel.INFO, "Legacy $LEGACY_SHADES_ID not present")
                prefs.edit().putBoolean(KEY_LEGACY_DONE, true).apply()
            }
            return null
        }
        val disable = service.exec(SemWtPlanner.disable(LEGACY_SHADES_ID))
        say(LogLevel.INFO, "Legacy $LEGACY_SHADES_ID disable: exit ${disable.code()}")
        val uid = runCatching { service.uid }.getOrDefault(-1)
        if (uid == 0) {
            // As root the service's owner package is com.android.shell, matching the legacy id.
            val removed = service.removeOverlay(SnapshotBuilder.LEGACY_SHADES_NAME)
            say(LogLevel.INFO, "Legacy overlay unregister: ${removed.output()}")
        }
        val purge = service.exec("rm -f /data/resource-cache/com.android.shell-${SnapshotBuilder.LEGACY_SHADES_NAME}-*.frro")
        val stillListed = SemWtController.listOverlays(service).firstOrNull { it.overlayId == LEGACY_SHADES_ID }
        val text = "Legacy $LEGACY_SHADES_ID cleaned up: " +
            (if (stillListed == null) "unregistered" else if (stillListed.enabled) "STILL ENABLED" else "disabled") +
            ", stale .frro purge exit ${purge.code()}"
        say(if (stillListed?.enabled == true) LogLevel.WARN else LogLevel.OK, text)
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

    companion object {
        const val MONITOR_SECONDS = 120
        const val LEGACY_SHADES_ID = "${ThemeService.SHELL_OWNER}:${SnapshotBuilder.LEGACY_SHADES_NAME}"
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
