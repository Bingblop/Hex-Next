package com.hexnext.themer.theme

import android.os.Bundle
import com.hexnext.engine.compile.OverlayLookup
import com.hexnext.engine.device.OverlayList
import com.hexnext.engine.device.OverlayListEntry
import com.hexnext.engine.device.SemWtGuardScript
import com.hexnext.engine.device.SemWtPlanner
import com.hexnext.engine.device.SemWtState
import com.hexnext.themer.IThemeService
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

    /** The saved state; empty when there is none or it cannot be parsed. */
    fun readState(service: IThemeService, log: (LogLine) -> Unit = {}): SemWtState {
        val bundle = service.readStateFile(SemWtState.FILE_NAME)
        if (bundle.code() != 0) return SemWtState()
        return try {
            SemWtState.fromJson(bundle.getString(ThemeService.KEY_CONTENT).orEmpty())
        } catch (e: IllegalArgumentException) {
            log(LogLine(LogLevel.WARN, "Ignoring unreadable ${SemWtState.FILE_NAME}: ${e.message}"))
            SemWtState()
        }
    }

    fun writeState(service: IThemeService, state: SemWtState): Boolean =
        service.writeStateFile(SemWtState.FILE_NAME, state.toJson()).code() == 0

    /** `cmd overlay list --user current`, parsed; empty when the command failed. */
    fun listOverlays(service: IThemeService): List<OverlayListEntry> {
        val bundle = service.exec("cmd overlay list --user current")
        if (bundle.code() != 0) return emptyList()
        return OverlayList.parse(bundle.output())
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
        val write = service.writeStateFile(SemWtGuardScript.FILE_NAME, SemWtGuardScript.text(state))
        if (write.code() != 0) {
            log(LogLine(LogLevel.WARN, "SemWT guard not written: ${write.output()}"))
            return
        }
        val start = service.exec(SemWtGuardScript.startCommand())
        if (start.code() == 0) {
            log(LogLine(LogLevel.INFO, "SemWT guard watching ${state.records.size} targets (${SemWtGuardScript.PATH})"))
        } else {
            log(LogLine(LogLevel.WARN, "SemWT guard did not start: exit ${start.code()} ${start.output()}"))
        }
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
        val state = readState(service, log)
        if (state.records.isEmpty()) return 0
        val commands = SemWtPlanner.reassert(state, listOverlays(service))
        val ok = run(service, commands, log)
        if (commands.isNotEmpty()) {
            log(LogLine(LogLevel.INFO, "Re-asserted Hex overlays over ${commands.size / 2} re-enabled SemWT overlays"))
        }
        if (restartGuard) startGuard(service, state, log)
        return ok
    }

    /**
     * Stops the guard, re-enables every SemWT overlay that was enabled before Hex Next disabled it
     * and clears the state. Returns the number of SemWT overlays re-enabled.
     */
    fun restore(service: IThemeService, log: (LogLine) -> Unit): Int {
        stopGuard(service, log)
        val state = readState(service, log)
        val commands = SemWtPlanner.restore(state)
        val ok = run(service, commands, log)
        if (state.records.isNotEmpty()) {
            log(LogLine(LogLevel.OK, "Re-enabled $ok/${commands.size} Samsung SemWT overlays (${state.records.size} were taken over)"))
        }
        if (!writeState(service, SemWtState(updatedAt = System.currentTimeMillis()))) {
            log(LogLine(LogLevel.WARN, "Could not clear ${SemWtState.FILE_NAME}"))
        }
        return ok
    }

    private fun Bundle.code() = getInt(ThemeService.KEY_CODE, -1)
    private fun Bundle.output() = getString(ThemeService.KEY_OUTPUT).orEmpty()
}
