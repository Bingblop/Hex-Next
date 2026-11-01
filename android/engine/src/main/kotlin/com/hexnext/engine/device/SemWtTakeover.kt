package com.hexnext.engine.device

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * One SemWT overlay Hex Next turned off for a target it themes.
 *
 * @property semwtId `android:SemWT_<target>`.
 * @property hexOverlayId Hex Next's overlay for the target (`com.android.shell:HexNext_…` or
 *   `android:HexNext_…`, depending on the service uid).
 * @property wasEnabled whether the SemWT overlay was enabled before Hex Next first disabled it;
 *   removal re-enables exactly the ones that were.
 * @property disabledAt epoch milliseconds of the first takeover.
 */
@Serializable
public data class SemWtRecord(
    val target: String,
    val semwtId: String,
    val hexOverlayId: String,
    val wasEnabled: Boolean,
    val disabledAt: Long,
)

/**
 * `semwt_state.json` in the service's state directory (`/data/local/tmp/hexnext/`): which
 * Samsung overlays Hex Next disabled, so that removal and safe mode can restore them and the
 * re-assert guard knows which pairs to watch.
 */
@Serializable
public data class SemWtState(
    val format: Int = FORMAT,
    val updatedAt: Long = 0,
    val records: List<SemWtRecord> = emptyList(),
) {
    /** The record for [target], or null. */
    public fun record(target: String): SemWtRecord? = records.firstOrNull { it.target == target }

    /** Records whose SemWT overlay was enabled before the takeover. */
    public val toRestore: List<SemWtRecord> get() = records.filter { it.wasEnabled }

    /**
     * This state with a takeover of [target] recorded. An existing record keeps its
     * [SemWtRecord.wasEnabled] and [SemWtRecord.disabledAt]: after the first takeover the SemWT
     * overlay reads as disabled, which is Hex Next's doing, not the user's.
     */
    public fun withTakeover(target: String, hexOverlayId: String, semwtEnabledNow: Boolean, now: Long): SemWtState {
        val old = record(target)
        val updated = SemWtRecord(
            target = target,
            semwtId = OneUi9Facts.semWtOverlayId(target),
            hexOverlayId = hexOverlayId,
            wasEnabled = old?.wasEnabled ?: semwtEnabledNow,
            disabledAt = old?.disabledAt ?: now,
        )
        return copy(updatedAt = now, records = records.filterNot { it.target == target } + updated)
    }

    /** This state without the records of [targets]. */
    public fun without(targets: Collection<String>, now: Long): SemWtState =
        copy(updatedAt = now, records = records.filterNot { it.target in targets })

    /** This state as JSON. */
    public fun toJson(): String = StateJson.encodeToString(serializer(), this)

    public companion object {
        /** Current [format]. */
        public const val FORMAT: Int = 1

        /** File name passed to `IThemeService.writeStateFile` / `readStateFile`. */
        public const val FILE_NAME: String = "semwt_state.json"

        /** Parses [json]; blank text is an empty state. Throws [IllegalArgumentException] when invalid. */
        public fun fromJson(json: String): SemWtState {
            if (json.isBlank()) return SemWtState()
            return try {
                StateJson.decodeFromString(serializer(), json)
            } catch (e: SerializationException) {
                throw IllegalArgumentException("invalid ${FILE_NAME}: ${e.message}", e)
            }
        }
    }
}

private val StateJson: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** One overlay row of `cmd overlay list`; [marker] is `[x]`, `[ ]` or `---`. */
public data class OverlayListEntry(
    val overlayId: String,
    val target: String,
    val enabled: Boolean,
    val marker: String,
)

/** Parser for `cmd overlay list [--user current]`. */
public object OverlayList {
    private val LINE = Regex("""^(\[[^\]]\]|---)\s+(\S+)""")

    /**
     * An unprefixed line names the target package; the `[x] id` (enabled), `[ ] id` (disabled)
     * and `--- id` (not applicable) lines below it are its overlays.
     */
    public fun parse(text: String): List<OverlayListEntry> {
        var target = ""
        val out = ArrayList<OverlayListEntry>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val match = LINE.find(line)
            if (match != null) {
                val marker = match.groupValues[1]
                out += OverlayListEntry(match.groupValues[2], target, marker == "[x]", marker)
            } else if (' ' !in line && !line.startsWith("[")) {
                target = line
            }
        }
        return out
    }
}

/**
 * Shell commands for taking a target over from Samsung's `android:SemWT_<target>`.
 *
 * SemWT overlays sit at `Integer.MAX_VALUE`, so a numeric priority cannot outrank them. For every
 * themed target Hex Next disables the SemWT overlay and re-raises its own overlay to `highest`
 * after Samsung's are in place; [reassert] repeats that whenever Samsung's wallpaper-theme service
 * re-enables a SemWT overlay. Options come before the positional arguments because
 * `OverlayManagerShellCommand` only reads leading options. Ids that are not plain
 * `owner:name` overlay ids produce no command.
 */
public object SemWtPlanner {
    private val OVERLAY_ID = Regex("^[A-Za-z0-9_.]+:[A-Za-z0-9_.]+$")

    /** True when [overlayId] is safe to put on a shell command line. */
    public fun isSafeId(overlayId: String): Boolean = OVERLAY_ID.matches(overlayId)

    /** `cmd overlay set-priority --user current <id> highest`. */
    public fun raisePriority(overlayId: String): String = "cmd overlay set-priority --user current ${checked(overlayId)} highest"

    /** `cmd overlay disable --user current <id>`. */
    public fun disable(overlayId: String): String = "cmd overlay disable --user current ${checked(overlayId)}"

    /** `cmd overlay enable --user current <id>`. */
    public fun enable(overlayId: String): String = "cmd overlay enable --user current ${checked(overlayId)}"

    /** Disable `android:SemWT_<target>`, then raise [hexOverlayId] to the top. */
    public fun takeover(target: String, hexOverlayId: String): List<String> {
        val semwt = OneUi9Facts.semWtOverlayId(target)
        if (!isSafeId(semwt) || !isSafeId(hexOverlayId)) return emptyList()
        return listOf(disable(semwt), raisePriority(hexOverlayId))
    }

    /** Re-enable every SemWT overlay of [state] that was enabled before the takeover. */
    public fun restore(state: SemWtState): List<String> =
        state.toRestore.map { it.semwtId }.filter(::isSafeId).distinct().map(::enable)

    /**
     * Commands for the records whose Hex overlay is enabled while Samsung's SemWT overlay was
     * enabled again: disable the SemWT overlay and re-raise the Hex overlay.
     */
    public fun reassert(state: SemWtState, overlays: List<OverlayListEntry>): List<String> {
        val enabled = overlays.filter { it.enabled }.mapTo(HashSet()) { it.overlayId }
        return state.records
            .filter { it.hexOverlayId in enabled && it.semwtId in enabled }
            .filter { isSafeId(it.semwtId) && isSafeId(it.hexOverlayId) }
            .flatMap { listOf(disable(it.semwtId), raisePriority(it.hexOverlayId)) }
    }

    private fun checked(id: String): String {
        require(isSafeId(id)) { "unsafe overlay id: $id" }
        return id
    }
}

/**
 * The re-assert guard: a detached POSIX sh loop (run as root like the stability monitor) that
 * keeps Samsung's SemWT overlays off for the targets Hex Next themes.
 *
 * Every [DEFAULT_INTERVAL] seconds it compares the modification times of `/data/resource-cache`
 * (Samsung re-fabricates SemWT `.frro` files there on a palette change) and the user 0
 * `wallpaper_info.xml`; when either changed, and in any case every [FULL_CHECK_TICKS] ticks, it
 * reads `cmd overlay list` and, for each recorded pair whose Hex overlay is enabled while the
 * SemWT overlay is enabled again, disables the SemWT overlay and re-raises the Hex overlay.
 * When `safe_mode.json` appears (the stability monitor tripped) it re-enables the SemWT overlays
 * that were enabled before the takeover and exits. A second guard replaces the first: each
 * instance exits once the pid file names another process.
 *
 * The template uses `£` for the shell dollar sign so Kotlin templates never touch it.
 */
public object SemWtGuardScript {
    public const val DIR: String = "/data/local/tmp/hexnext"
    public const val FILE_NAME: String = "semwt_guard.sh"
    public const val PATH: String = "$DIR/$FILE_NAME"
    public const val PID_FILE: String = "$DIR/semwt_guard.pid"
    public const val STATUS_FILE_NAME: String = "semwt_guard_status.json"
    public const val SAFE_MODE_FILE: String = "$DIR/safe_mode.json"
    public const val DEFAULT_INTERVAL: Int = 15
    public const val FULL_CHECK_TICKS: Int = 20

    /** The script for [state]'s records; restore candidates are the records that were enabled. */
    public fun text(state: SemWtState): String {
        val pairs = state.records
            .filter { SemWtPlanner.isSafeId(it.semwtId) && SemWtPlanner.isSafeId(it.hexOverlayId) }
            .joinToString("\n") { "${it.semwtId}|${it.hexOverlayId}" }
        val restore = state.toRestore.map { it.semwtId }.filter(SemWtPlanner::isSafeId).distinct().joinToString(" ")
        return TEMPLATE.trimIndent()
            .replace("@PAIRS@", pairs)
            .replace("@RESTORE@", restore)
            .replace("@FULL@", FULL_CHECK_TICKS.toString())
            .replace('£', '$') + "\n"
    }

    /** Starts the guard detached (setsid + nohup) for [intervalSeconds] per tick. */
    public fun startCommand(intervalSeconds: Int = DEFAULT_INTERVAL): String {
        val interval = intervalSeconds.coerceIn(5, 300)
        return "chmod 755 $PATH; " +
            "if command -v setsid >/dev/null 2>&1; then " +
            "nohup setsid sh $PATH $interval >/dev/null 2>&1 & " +
            "else nohup sh $PATH $interval >/dev/null 2>&1 & fi"
    }

    /** Stops the guard named by the pid file, but only when that pid still runs the guard script. */
    public fun stopCommand(): String = (
        "P=£(cat $PID_FILE 2>/dev/null); " +
            "if [ -n \"£P\" ]; then " +
            "case \"£(tr '\\0' ' ' < /proc/£P/cmdline 2>/dev/null)\" in " +
            "*$FILE_NAME*) kill £P && echo \"Stopped SemWT guard pid £P\" ;; " +
            "*) echo \"Stale SemWT guard pid file (£P) removed\" ;; esac; " +
            "rm -f $PID_FILE; " +
            "else echo 'No SemWT guard running'; fi"
        ).replace('£', '$')

    private const val TEMPLATE = """
        #!/system/bin/sh
        # Hex Next SemWT guard. Generated by the app from semwt_state.json; plain POSIX sh.
        set -f
        DIR=/data/local/tmp/hexnext
        PIDFILE=£DIR/semwt_guard.pid
        STATUS=£DIR/semwt_guard_status.json
        INTERVAL=£{1:-15}
        FULL=@FULL@
        PAIRS='@PAIRS@'
        RESTORE='@RESTORE@'
        STARTED=£(date +%s)
        CHECKS=0
        FIXES=0

        write_status() {
          printf '{"state":"%s","started":%s,"updated":%s,"checks":%s,"fixes":%s,"pid":%s}\n' \
            "£1" "£STARTED" "£(date +%s)" "£CHECKS" "£FIXES" "££" > "£STATUS.tmp" \
            && mv -f "£STATUS.tmp" "£STATUS"
          chmod 644 "£STATUS" 2>/dev/null
        }

        release_pid() {
          if [ "£(cat "£PIDFILE" 2>/dev/null)" = "££" ]; then
            rm -f "£PIDFILE"
          fi
        }

        on_term() {
          write_status stopped
          release_pid
          exit 0
        }
        trap on_term TERM INT HUP

        stamp() {
          printf '%s/%s' "£(stat -c %Y /data/resource-cache 2>/dev/null)" "£(stat -c %Y /data/system/users/0/wallpaper_info.xml 2>/dev/null)"
        }

        fix_pairs() {
          LIST=£(cmd overlay list --user current 2>/dev/null | sed 's/^[[:space:]]*//')
          [ -n "£LIST" ] || return 0
          printf '%s\n' "£PAIRS" | while IFS='|' read -r sem hex; do
            [ -n "£sem" ] && [ -n "£hex" ] || continue
            if printf '%s\n' "£LIST" | grep -qxF "[x] £hex" && printf '%s\n' "£LIST" | grep -qxF "[x] £sem"; then
              cmd overlay disable --user current "£sem" >/dev/null 2>&1
              cmd overlay set-priority --user current "£hex" highest >/dev/null 2>&1
              echo fixed
            fi
          done
        }

        check() {
          CHECKS=£((CHECKS + 1))
          N=£(fix_pairs | grep -c fixed)
          FIXES=£((FIXES + £{N:-0}))
          write_status running
        }

        restore_semwt() {
          for id in £RESTORE; do
            cmd overlay enable --user current "£id" >/dev/null 2>&1
          done
        }

        exit_if_safe_mode() {
          if [ -f "£DIR/safe_mode.json" ]; then
            restore_semwt
            write_status safe_mode
            release_pid
            exit 0
          fi
        }

        mkdir -p "£DIR"
        echo ££ > "£PIDFILE"
        chmod 644 "£PIDFILE" 2>/dev/null
        LAST=£(stamp)
        TICK=0
        exit_if_safe_mode
        check

        while true; do
          sleep "£INTERVAL" &
          wait £!
          if [ "£(cat "£PIDFILE" 2>/dev/null)" != "££" ]; then
            write_status replaced
            exit 0
          fi
          exit_if_safe_mode
          TICK=£((TICK + 1))
          NOW=£(stamp)
          if [ "£NOW" != "£LAST" ]; then
            sleep 3
            LAST=£(stamp)
            TICK=0
            check
          elif [ "£TICK" -ge "£FULL" ]; then
            TICK=0
            check
          fi
        done
    """
}
