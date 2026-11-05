package com.hexnext.themer.shizuku

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible

/** Shared retry boundary for command variants and UID fallback within one root request. */
internal object RootLaunchPolicy {
    sealed interface Attempt<out T>
    data class Started<T>(val value: T) : Attempt<T>
    data class Failed(val message: String, val retryAllowed: Boolean = true) : Attempt<Nothing>

    suspend fun <C, T> firstSuccessful(
        candidates: List<C>,
        attempt: suspend (C) -> Attempt<T>,
    ): Attempt<T> {
        var last = Failed("service unavailable")
        for (candidate in candidates) {
            currentCoroutineContext().ensureActive()
            val result = attempt(candidate)
            currentCoroutineContext().ensureActive()
            when (result) {
                is Started -> return result
                is Failed -> {
                    last = result
                    if (!result.retryAllowed) return result
                }
            }
        }
        return last
    }

    /** Interrupt a cancelled prompt wait and always release the subprocess and its streams. */
    suspend fun runCommand(timeoutSeconds: Long, start: () -> Process): Attempt<Unit> {
        var processStarted = false
        return try {
            runInterruptible(Dispatchers.IO) {
                val process = start().also { processStarted = true }
                Closeable { if (process.isAlive) process.destroyForcibly() }.use {
                    process.outputStream.use {
                        process.errorStream.use {
                            process.inputStream.use { input ->
                                if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                                    Failed("su did not return; check the root manager and try again", retryAllowed = false)
                                } else {
                                    val output = String(
                                        input.readNBytes(minOf(input.available(), 4096)), Charsets.UTF_8,
                                    ).trim()
                                    val exit = process.exitValue()
                                    if (exit == 0) Started(Unit) else Failed("su exit $exit: ${output.take(200)}")
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            // After start, a read/cleanup failure leaves acceptance indeterminate: never re-prompt.
            Failed(e.message ?: "su command could not be completed", retryAllowed = !processStarted)
        }
    }
}
