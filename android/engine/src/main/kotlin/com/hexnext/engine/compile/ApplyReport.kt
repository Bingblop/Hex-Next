package com.hexnext.engine.compile

import com.hexnext.engine.map.PlannedColor
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** What happened to one target during an apply. */
@Serializable
public enum class TargetResult {
    /** The overlay was registered, enabled and raised. */
    OK,

    /** Registered with some resources dropped (e.g. colours only after a drawable commit failed). */
    PARTIAL,

    /** The service reported a failure, or rendering/IPC threw. */
    FAILED,

    /** Not built: the package is debloated. */
    SKIPPED_DEBLOATED,

    /** Not built for another reason (disabled, not installed, no resources, deprecated …). */
    SKIPPED,

    /** Compiled, but nothing matched on this device; no overlay was built. */
    EMPTY,
}

/**
 * One target's line in an [ApplyReport].
 *
 * @property message service output tail, or the skip reason.
 * @property semwtDisabled true when `android:SemWT_<target>` was disabled for this target.
 * @property verified result of the post-apply `cmd overlay lookup` of the first colour set: true
 *   when the target resolves to the value Hex Next set, false on a mismatch, null when not checked.
 */
@Serializable
public data class TargetOutcome(
    val target: String,
    val result: TargetResult,
    val overlay: String = "",
    val colorCount: Int = 0,
    val drawableCount: Int = 0,
    val resourceCount: Int = 0,
    val semwtDisabled: Boolean = false,
    val millis: Long = 0,
    val message: String = "",
    val verified: Boolean? = null,
) {
    /**
     * This outcome with the lookup verification recorded. A mismatch turns [TargetResult.OK] into
     * [TargetResult.PARTIAL] (the overlay is in place but something outranks it or the value did
     * not land), never into [TargetResult.FAILED]; [detail] is appended to [message].
     */
    public fun withVerification(ok: Boolean?, detail: String = ""): TargetOutcome {
        if (ok == null) return copy(verified = null)
        val result = if (!ok && result == TargetResult.OK) TargetResult.PARTIAL else result
        val text = if (detail.isEmpty()) message else listOf(message, detail).filter { it.isNotEmpty() }.joinToString("\n")
        return copy(result = result, verified = ok, message = text)
    }
}

/** Reading `cmd overlay lookup` output. */
public object OverlayLookup {
    private val HEX = Regex("""#([0-9a-fA-F]{8}|[0-9a-fA-F]{6})\b""")

    /** The ARGB colour in [output] (`#ffff0000`, `#ff0000`), or null when there is none. */
    public fun parseColor(output: String): Int? {
        val digits = HEX.find(output)?.groupValues?.get(1) ?: return null
        val full = if (digits.length == 6) "ff$digits" else digits
        return full.toLong(16).toInt()
    }

    /** True when [output] resolves to [argb], false when it resolves to another colour, null when unreadable. */
    public fun matches(output: String, argb: Int): Boolean? = parseColor(output)?.let { it == argb }

    /**
     * The colour to verify [target] with: the first colour set for the default configuration
     * whose resource has no other configuration variant (so the lookup reads the same value in
     * light and dark mode), or null when there is none.
     */
    public fun probe(target: CompiledTarget): PlannedColor? {
        val configured = target.colors.filter { it.configuration.isNotEmpty() }.mapTo(HashSet()) { it.resourceName }
        return target.colors.firstOrNull { it.configuration.isEmpty() && it.resourceName !in configured }
    }
}

/**
 * Result of applying a compiled theme: one [TargetOutcome] per target, in build order.
 *
 * @property logFile the JSON-lines build log the apply wrote.
 * @property notes apply-wide messages (legacy cleanup, guard start, pack errors …).
 * @property status [STATUS_STARTED] / [STATUS_RUNNING] while the apply is in progress (the report
 *   is saved early and after every target, so a process death still leaves evidence),
 *   [STATUS_FINISHED] when it completed, [STATUS_ABORTED] when it stopped on an error.
 * @property pendingTargets compiled targets not built yet (while running, or when aborted).
 * @property error what aborted the apply (`OutOfMemoryError: …`), empty otherwise.
 */
@Serializable
public data class ApplyReport(
    val themeId: String,
    val themeName: String,
    val startedAt: Long,
    val finishedAt: Long,
    val outcomes: List<TargetOutcome>,
    val logFile: String = "",
    val notes: List<String> = emptyList(),
    val format: Int = FORMAT,
    val status: String = STATUS_FINISHED,
    val pendingTargets: List<String> = emptyList(),
    val error: String = "",
) {
    /** True when the apply ran to the end (not interrupted, not aborted). */
    public val finished: Boolean get() = status == STATUS_FINISHED

    /** Targets whose overlay is in place ([TargetResult.OK] or [TargetResult.PARTIAL]). */
    public val okCount: Int get() = outcomes.count { it.result == TargetResult.OK || it.result == TargetResult.PARTIAL }

    /** Targets with [TargetResult.PARTIAL]. */
    public val partialCount: Int get() = outcomes.count { it.result == TargetResult.PARTIAL }

    /** Targets with [TargetResult.FAILED]. */
    public val failedCount: Int get() = outcomes.count { it.result == TargetResult.FAILED }

    /** Targets skipped because they are debloated. */
    public val debloatedCount: Int get() = outcomes.count { it.result == TargetResult.SKIPPED_DEBLOATED }

    /** Targets skipped for any other reason, or compiled empty. */
    public val skippedCount: Int get() = outcomes.count { it.result == TargetResult.SKIPPED || it.result == TargetResult.EMPTY }

    /** Targets whose post-apply lookup matched. */
    public val verifiedCount: Int get() = outcomes.count { it.verified == true }

    /** Targets whose post-apply lookup returned another value. */
    public val mismatchCount: Int get() = outcomes.count { it.verified == false }

    /** Targets whose SemWT overlay was disabled. */
    public val semwtDisabledCount: Int get() = outcomes.count { it.semwtDisabled }

    /** True when the apply finished, no target failed and at least one overlay is in place. */
    public val success: Boolean get() = finished && failedCount == 0 && okCount > 0

    /**
     * One line: `12 ok (1 partial), 1 failed, 3 debloated, 2 skipped, 12 SemWT disabled`, followed
     * by `, 11/12 verified` when any target was checked.
     */
    public fun summary(): String = buildString {
        when (status) {
            STATUS_FINISHED -> Unit
            STATUS_ABORTED -> append("aborted${if (error.isEmpty()) "" else " ($error)"}: ")
            else -> append("interrupted while $status (${pendingTargets.size} targets pending): ")
        }
        append("$okCount ok")
        if (partialCount > 0) append(" ($partialCount partial)")
        append(", $failedCount failed, $debloatedCount debloated, $skippedCount skipped")
        append(", $semwtDisabledCount SemWT disabled")
        val checked = verifiedCount + mismatchCount
        if (checked > 0) append(", $verifiedCount/$checked verified")
    }

    /** This report as JSON. */
    public fun toJson(): String = ReportJson.encodeToString(serializer(), this)

    public companion object {
        public const val FORMAT: Int = 1

        /** Saved before the first target is built. */
        public const val STATUS_STARTED: String = "started"

        /** Saved after each target. */
        public const val STATUS_RUNNING: String = "running"

        /** The apply completed. */
        public const val STATUS_FINISHED: String = "finished"

        /** The apply stopped on an exception or error (e.g. OutOfMemoryError). */
        public const val STATUS_ABORTED: String = "aborted"

        /** Parses JSON written by [toJson]; throws [IllegalArgumentException] when invalid. */
        public fun fromJson(json: String): ApplyReport = try {
            ReportJson.decodeFromString(serializer(), json)
        } catch (e: SerializationException) {
            throw IllegalArgumentException("invalid apply report: ${e.message}", e)
        }

        /** Outcomes for the targets [compiled] did not build, debloated ones marked as such. */
        public fun skippedOutcomes(compiled: CompiledTheme): List<TargetOutcome> = compiled.skippedTargets.map {
            TargetOutcome(
                target = it.name,
                result = if (it.reason == ThemeCompiler.REASON_DEBLOATED) TargetResult.SKIPPED_DEBLOATED else TargetResult.SKIPPED,
                overlay = ThemeCompiler.overlayName(it.name),
                message = it.reason,
            )
        }
    }
}

private val ReportJson: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
