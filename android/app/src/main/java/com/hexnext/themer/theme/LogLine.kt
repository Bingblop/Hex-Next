package com.hexnext.themer.theme

enum class LogLevel { INFO, OK, WARN, ERROR }

/** One line of the on-screen log; the build log file under `files/logs/` is the full record. */
data class LogLine(val level: LogLevel, val text: String)
