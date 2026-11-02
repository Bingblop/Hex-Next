package com.hexnext.engine.log

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Severity of a [BuildEvent]; serialised in lower case. */
@Serializable
public enum class LogLevel {
    @SerialName("debug") DEBUG,
    @SerialName("info") INFO,
    @SerialName("warn") WARN,
    @SerialName("error") ERROR,
}

/**
 * One line of an overlay build log (`files/logs/<timestamp>.jsonl`).
 *
 * @property timestamp epoch milliseconds.
 * @property target target package the event concerns, `""` for build-wide events.
 * @property data structured details (resource counts, skipped names, read-back values …).
 */
@Serializable
public data class BuildEvent(
    val timestamp: Long,
    val level: LogLevel,
    val target: String,
    val message: String,
    val data: Map<String, String> = emptyMap(),
) {
    /** This event as one JSON line (no trailing newline). */
    public fun toJsonLine(): String = BuildLogJson.encodeToString(serializer(), this)

    public companion object {
        /** Parses one JSON line written by [toJsonLine]. */
        public fun fromJsonLine(line: String): BuildEvent = try {
            BuildLogJson.decodeFromString(serializer(), line)
        } catch (e: SerializationException) {
            throw IllegalArgumentException("not a build event: ${e.message}", e)
        }

        /** Parses a whole `.jsonl` log, skipping blank lines. */
        public fun parseLog(text: String): List<BuildEvent> =
            text.lineSequence().filter { it.isNotBlank() }.map(::fromJsonLine).toList()
    }
}

private val BuildLogJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/**
 * Appends [BuildEvent]s as JSON lines to [out] (a `FileWriter`, `StringBuilder` …).
 * The caller owns [out] and closes/flushes it. Not thread-safe.
 *
 * @param clock source of event timestamps, epoch milliseconds.
 */
public class BuildLogWriter(
    private val out: Appendable,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Writes [event] followed by `\n`. */
    public fun write(event: BuildEvent) {
        out.append(event.toJsonLine()).append('\n')
    }

    /** Writes an event stamped with [clock]. */
    public fun log(level: LogLevel, target: String, message: String, data: Map<String, String> = emptyMap()) {
        write(BuildEvent(clock(), level, target, message, data))
    }

    /** Shorthand for [LogLevel.INFO]. */
    public fun info(target: String, message: String, data: Map<String, String> = emptyMap()): Unit = log(LogLevel.INFO, target, message, data)

    /** Shorthand for [LogLevel.WARN]. */
    public fun warn(target: String, message: String, data: Map<String, String> = emptyMap()): Unit = log(LogLevel.WARN, target, message, data)

    /** Shorthand for [LogLevel.ERROR]. */
    public fun error(target: String, message: String, data: Map<String, String> = emptyMap()): Unit = log(LogLevel.ERROR, target, message, data)
}
