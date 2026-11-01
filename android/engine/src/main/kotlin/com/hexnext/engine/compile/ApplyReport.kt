package com.hexnext.engine.compile

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
)

/**
 * Result of applying a compiled theme: one [TargetOutcome] per target, in build order.
 *
 * @property logFile the JSON-lines build log the apply wrote.
 * @property notes apply-wide messages (legacy cleanup, guard start, pack errors …).
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
) {
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

    /** Targets whose SemWT overlay was disabled. */
    public val semwtDisabledCount: Int get() = outcomes.count { it.semwtDisabled }

    /** True when no target failed and at least one overlay is in place. */
    public val success: Boolean get() = failedCount == 0 && okCount > 0

    /** One line: `12 ok (1 partial), 1 failed, 3 debloated, 2 skipped, 12 SemWT disabled`. */
    public fun summary(): String = buildString {
        append("$okCount ok")
        if (partialCount > 0) append(" ($partialCount partial)")
        append(", $failedCount failed, $debloatedCount debloated, $skippedCount skipped")
        append(", $semwtDisabledCount SemWT disabled")
    }

    /** This report as JSON. */
    public fun toJson(): String = ReportJson.encodeToString(serializer(), this)

    public companion object {
        public const val FORMAT: Int = 1

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
