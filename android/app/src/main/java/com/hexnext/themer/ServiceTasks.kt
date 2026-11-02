package com.hexnext.themer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One task at a time on the privileged theme service, across the editor and Diagnostics.
 *
 * Each view model keeps its own busy flag for its own screen, but both drive the same service:
 * an editor Apply theme and a Diagnostics "Turn off all Hex Next overlays" (or Settings test,
 * Repair, Scan) must never interleave, or the phone ends up half themed. A task takes the slot
 * with [tryStart] before it touches the service and gives it back with [finish].
 */
object ServiceTasks {
    private val _running = MutableStateFlow<String?>(null)

    /** The name of the task holding the service, or null when it is free. */
    val running: StateFlow<String?> = _running.asStateFlow()

    /** Takes the slot for [name]; false (nothing taken) while another task holds it. */
    fun tryStart(name: String): Boolean = _running.compareAndSet(null, name)

    /**
     * [tryStart] for a worker thread outside any coroutine (the boot receiver): polls until the
     * slot is free and taken, at most [timeoutMs]. False when it never came free; nothing is taken then.
     */
    fun startBlocking(name: String, timeoutMs: Long): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (!tryStart(name)) {
            if (System.nanoTime() - deadline >= 0L) return false
            Thread.sleep(POLL_MS)
        }
        return true
    }

    /** Gives the slot back. Only the task whose [tryStart] returned true calls this. */
    fun finish() {
        _running.value = null
    }

    private const val POLL_MS = 200L
}
