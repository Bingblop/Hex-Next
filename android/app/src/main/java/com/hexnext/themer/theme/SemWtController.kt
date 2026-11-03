package com.hexnext.themer.theme

import com.hexnext.engine.map.PlannedValue
import android.os.Bundle
import com.hexnext.engine.compile.OverlayLookup
import com.hexnext.engine.device.OverlayList
import com.hexnext.engine.device.OverlayListEntry
import com.hexnext.engine.device.SemWtGuardScript
import com.hexnext.engine.device.SemWtPlanner
import com.hexnext.engine.device.SemWtState
import com.hexnext.themer.IThemeService
import com.hexnext.themer.shizuku.ExecOutput
import com.hexnext.themer.shizuku.ThemeService

/**
 * Device side of the SemWT takeover planned by the engine's [SemWtPlanner]: reads and writes
 * `semwt_state.json` through the service's state files, runs the commands with
 * [IThemeService.exec], and starts / stops the root re-assert guard ([SemWtGuardScript]).
 *
 * Every function takes the service interface, so it works with the Shizuku service and with an
 * in-process [ThemeService] that reaches root through `su -c` (boot receiver, safe mode).
 */
object SemWtController {

    /**
     * One read of `semwt_state.json`. [state] is the saved state (empty when there is no file) or
     * null when the file could not be read ([raw] null: su denied, timeout) or parsed ([raw] holds
     * the text). An unreadable file must never be overwritten as if it were empty: it is the only
     * record of which Samsung overlays Hex Next disabled.
     */
    class StateRead(val state: SemWtState?, val raw: String?)

    fun readStateChecked(service: IThemeService, log: (LogLine) -> Unit = {}): StateRead {
        val bundle = service.readStateFile(SemWtState.FILE_NAME)
        when (bundle.code()) {
            0 -> Unit
            // Code 1: the file does not exist, nothing was ever taken over.
            1 -> return StateRead(SemWtState(), null)
            else -> {
                log(LogLine(LogLevel.WARN, "Could not read ${SemWtState.FILE_NAME}: ${bundle.output().ifBlank { "exit ${bundle.code()}" }}"))
                return StateRead(null, null)
            }
        }
        val raw = bundle.getString(ThemeService.KEY_CONTENT).orEmpty()
        return try {
            StateRead(SemWtState.fromJson(raw), raw)
        } catch (e: IllegalArgumentException) {
            log(LogLine(LogLevel.WARN, "Unreadable ${SemWtState.FILE_NAME}: ${e.message}"))
            StateRead(null, raw)
        }
    }

    /** The saved state; empty when there is none or it cannot be read or parsed (read-only callers). */
    fun readState(service: IThemeService, log: (LogLine) -> Unit = {}): SemWtState =
        readStateChecked(service, log).state ?: SemWtState()

    /** Keeps the text of an unparsable state file as [UNREADABLE_FILE_NAME] before it is replaced; true when saved. */
    fun preserveUnreadable(service: IThemeService, raw: String): Boolean =
        service.writeStateFile(UNREADABLE_FILE_NAME, raw).code() == 0

    /** Where [preserveUnreadable] keeps an unparsable `semwt_state.json`. */
    const val UNREADABLE_FILE_NAME = "semwt_state.unreadable.json"

    fun writeState(service: IThemeService, state: SemWtState): Boolean =
        service.writeStateFile(SemWtState.FILE_NAME, state.toJson()).code() == 0

    /** `cmd overlay list --user current`, parsed; empty when the command failed. */
    fun listOverlays(service: IThemeService): List<OverlayListEntry> {
        val (code, text) = ExecOutput.exec(service, "cmd overlay list --user current")
        if (code != 0) return emptyList()
        return OverlayList.parse(text)
    }

    /**
     * The overlay ids in [ids] that a successful `cmd overlay list` no longer shows. Empty when
     * [ids] is empty or the list could not be read (an empty list is never taken as "all gone").
     */
    fun unregistered(service: IThemeService, ids: Collection<String>): Set<String> {
        if (ids.isEmpty()) return emptySet()
        val listed = listOverlays(service)
        if (listed.isEmpty()) return emptySet()
        val known = listed.mapTo(HashSet()) { it.overlayId }
        return ids.filterTo(HashSet()) { it !in known }
    }

    /**
     * `cmd overlay lookup --user current <target> <resource>` until it resolves to [argb], up to
     * [attempts] times one second apart (the resource cache can lag behind an enable). Returns
     * whether it matched (null when the output never held a colour) and the last output.
     */
    fun verifyColor(service: IThemeService, target: String, resource: String, argb: Int, attempts: Int = 3): Pair<Boolean?, String> {
        if (!SAFE_TOKEN.matches(target) || !SAFE_RESOURCE.matches(resource)) return null to "unsafe lookup arguments"
        var last = ""
        var matched: Boolean? = null
        repeat(attempts.coerceAtLeast(1)) { attempt ->
            if (attempt > 0) Thread.sleep(1000)
            val r = service.exec("cmd overlay lookup --user current $target $resource")
            last = r.output().trim()
            matched = OverlayLookup.matches(last, argb)
            if (matched == true) return true to last
        }
        return matched to last
    }

    /**
     * As [verifyColor] for a size, number or switch: `cmd overlay lookup` of [value] in
     * [target], compared with [OverlayLookup.matchesValue] (null when the output is unreadable).
     */
    fun verifyValue(service: IThemeService, target: String, value: PlannedValue, attempts: Int = 3): Pair<Boolean?, String> {
        if (!SAFE_TOKEN.matches(target) || !SAFE_RESOURCE.matches(value.resourceName)) return null to "unsafe lookup arguments"
        var last = ""
        var matched: Boolean? = null
        repeat(attempts.coerceAtLeast(1)) { attempt ->
            if (attempt > 0) Thread.sleep(1000)
            val r = service.exec("cmd overlay lookup --user current $target ${value.resourceName}")
            last = r.output().trim()
            matched = OverlayLookup.matchesValue(last, value)
            if (matched == true) return true to last
        }
        return matched to last
    }

    private val SAFE_TOKEN = Regex("^[A-Za-z0-9_.]+$")
    private val SAFE_RESOURCE = Regex("^[A-Za-z0-9_.]+:[A-Za-z0-9_]+/[A-Za-z0-9_.]+$")

    /** Runs [commands] in order; returns how many exited 0. */
    fun run(service: IThemeService, commands: List<String>, log: (LogLine) -> Unit): Int {
        var ok = 0
        for (command in commands) {
            val r = service.exec(command)
            if (r.code() == 0) {
                ok++
                log(LogLine(LogLevel.INFO, "$ $command"))
            } else {
                log(LogLine(LogLevel.WARN, "$ $command -> exit ${r.code()}: ${r.output().ifBlank { "no output" }}"))
            }
        }
        return ok
    }

    /** Stops any running guard, then writes the guard script for [state] and starts it detached (unless [state] is empty). */
    fun startGuard(service: IThemeService, state: SemWtState, log: (LogLine) -> Unit) {
        // Stop a running guard first: `sh` reads its script while it runs, and the non-privileged
        // state-file write truncates the file in place.
        stopGuard(service, log)
        if (state.records.isEmpty()) return
        val text = SemWtGuardScript.text(state)
        val write = service.writeStateFile(SemWtGuardScript.FILE_NAME, text)
        if (write.code() != 0) {
            log(LogLine(LogLevel.WARN, "SemWT guard not written: ${write.output()}"))
            return
        }
        // Root runs the text held here, not the copy in the shell-owned state directory (kept for
        // diagnostics), which a uid 2000 process could swap between the write and the run.
        val start = service.exec(guardStartCommand(text))
        if (start.code() == 0) {
            log(LogLine(LogLevel.INFO, "SemWT guard watching ${state.records.size} targets (${SemWtGuardScript.PATH})"))
        } else {
            log(LogLine(LogLevel.WARN, "SemWT guard did not start: exit ${start.code()} ${start.output()}"))
        }
    }

    /** Like [SemWtGuardScript.startCommand], but runs [text] inline (`sh -c`) with `$0` = the script name. */
    private fun guardStartCommand(text: String): String {
        val script = ThemeService.inlineScript(text, SemWtGuardScript.FILE_NAME, SemWtGuardScript.DEFAULT_INTERVAL.toString())
        return "if command -v setsid >/dev/null 2>&1; then " +
            "nohup setsid $script >/dev/null 2>&1 & " +
            "else nohup $script >/dev/null 2>&1 & fi"
    }

    fun stopGuard(service: IThemeService, log: (LogLine) -> Unit) {
        val r = service.exec(SemWtGuardScript.stopCommand())
        log(LogLine(LogLevel.INFO, r.output().ifBlank { "SemWT guard stop: exit ${r.code()}" }))
    }

    /**
     * Disables SemWT overlays Samsung re-enabled under an enabled Hex overlay and re-raises the Hex
     * overlay, then (re)starts the guard. Returns the number of commands that succeeded.
     */
    fun reassert(service: IThemeService, log: (LogLine) -> Unit, restartGuard: Boolean = true): Int {
        // Runs at app start and at boot, i.e. after a system_server restart killed the app and
        // its post-apply watcher. A monitor trip that nothing handled is finished here (theme off,
        // SemWT given back, font and palette seed removed) instead of turning SemWT off again.
        if (ThemeApplier.recoverMissedTripWithKnownContext(service, log)) return 0
        val state = readState(service, log)
        if (state.records.isEmpty()) return 0
        val before = listOverlays(service)
        // Several records can share one Hex overlay (android, MonetPalette and G_MonetPalette all
        // use HexNext_android), and a list read before the SemWT disables still shows SemWT above
        // ours. So: run the SemWT disables first, read the list again, then raise each Hex overlay
        // at most once. Every raise of the framework overlay restarts the whole UI.
        val semwtDisables = state.records.map { it.semwtId }.filter(SemWtPlanner::isSafeId)
            .mapTo(HashSet()) { SemWtPlanner.disable(it) }
        val planned = SemWtPlanner.reassert(state, before)
        val disables = planned.filter { it in semwtDisables }.distinct()
        var ok = run(service, disables, log)
        val after = if (disables.isEmpty()) before else listOverlays(service).ifEmpty { before }
        val raises = SemWtPlanner.reassert(state, after).filterNot { it in semwtDisables }.distinct()
        ok += run(service, raises, log)
        if (disables.isNotEmpty() || raises.isNotEmpty()) {
            log(
                LogLine(
                    LogLevel.INFO,
                    "Re-asserted Hex overlays: disabled ${disables.size} re-enabled SemWT overlays, raised ${raises.size / 2} Hex overlays",
                ),
            )
        }
        if (restartGuard) startGuard(service, state, log)
        return ok
    }

    /**
     * Stops the guard, re-enables every SemWT overlay that was enabled before Hex Next disabled it
     * and clears the state. Records whose re-enable failed (busy system_server, timeout) stay in
     * the state file, so the next Remove (or safe mode) tries them again and a later apply keeps
     * their original [com.hexnext.engine.device.SemWtRecord.wasEnabled]. Returns the number of
     * SemWT overlays re-enabled.
     */
    fun restore(service: IThemeService, log: (LogLine) -> Unit): Int {
        stopGuard(service, log)
        val read = readStateChecked(service, log)
        val state = read.state ?: run {
            val raw = read.raw
            if (raw == null) {
                // A failed read (su not ready, timeout): leave the file as it is for the next try.
                log(LogLine(LogLevel.WARN, "Samsung SemWT overlays not restored: ${SemWtState.FILE_NAME} could not be read; it was kept, run Remove again."))
            } else if (preserveUnreadable(service, raw) && writeState(service, SemWtState(updatedAt = System.currentTimeMillis()))) {
                // Corrupt beyond parsing: its records are unusable either way. Keep a copy and
                // start clean so a later apply does not trip over it, but say so.
                log(LogLine(LogLevel.WARN, "Samsung SemWT overlays not restored: ${SemWtState.FILE_NAME} was corrupt (copy kept as $UNREADABLE_FILE_NAME); re-enable them in Samsung's theme settings if a screen looks wrong."))
            } else {
                log(LogLine(LogLevel.WARN, "Samsung SemWT overlays not restored: ${SemWtState.FILE_NAME} is corrupt and could not be set aside; it was kept."))
            }
            return 0
        }
        val failed = HashSet<String>()
        var ok = 0
        for (semwtId in state.toRestore.map { it.semwtId }.filter(SemWtPlanner::isSafeId).distinct()) {
            val command = SemWtPlanner.enable(semwtId)
            val r = service.exec(command)
            if (r.code() == 0) {
                ok++
                log(LogLine(LogLevel.INFO, "$ $command"))
            } else {
                failed += semwtId
                log(LogLine(LogLevel.WARN, "$ $command -> exit ${r.code()}: ${r.output().ifBlank { "no output" }}"))
            }
        }
        // A SemWT overlay Samsung no longer registers (app removed, palette regenerated without
        // it) can never be re-enabled; keeping its record would fail every Remove for good.
        val gone = unregistered(service, failed)
        for (semwtId in gone) {
            log(LogLine(LogLevel.INFO, "$semwtId is no longer registered; nothing to re-enable, record dropped"))
        }
        failed -= gone
        val total = ok + failed.size
        if (state.records.isNotEmpty()) {
            log(
                LogLine(
                    if (failed.isEmpty()) LogLevel.OK else LogLevel.WARN,
                    "Re-enabled $ok/$total Samsung SemWT overlays (${state.records.size} were taken over)" +
                        (if (gone.isEmpty()) "" else "; ${gone.size} no longer exist") +
                        if (failed.isEmpty()) "" else "; ${failed.size} kept in ${SemWtState.FILE_NAME}, Remove again to retry",
                ),
            )
        }
        val now = System.currentTimeMillis()
        val remaining = SemWtState(updatedAt = now, records = state.records.filter { it.wasEnabled && it.semwtId in failed })
        if (!writeState(service, remaining)) {
            log(LogLine(LogLevel.WARN, "Could not update ${SemWtState.FILE_NAME}"))
        }
        return ok
    }

    private fun Bundle.code() = getInt(ThemeService.KEY_CODE, -1)
    private fun Bundle.output() = getString(ThemeService.KEY_OUTPUT).orEmpty()
}
