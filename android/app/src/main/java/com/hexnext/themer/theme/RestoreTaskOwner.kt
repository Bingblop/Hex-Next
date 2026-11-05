package com.hexnext.themer.theme

import java.io.Closeable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Owns a restore's resource even when cancellation happens before its coroutine starts. */
internal class RestoreTaskOwner(
    private val scope: CoroutineScope,
    private val onFinished: () -> Unit,
) : Closeable {
    @Volatile
    var running: Boolean = false
        private set

    private var closed = false
    private var resource: Closeable? = null

    /** Takes ownership of [acquired]; rejected starts close their resource immediately. */
    @Synchronized
    fun start(acquired: Closeable?, restore: suspend () -> Unit): Boolean {
        if (closed || running || !scope.isActive) {
            acquired?.close()
            return false
        }
        running = true
        resource = acquired
        scope.launch {
            // Nested `use`: the restore's own outcome (success, failure or cancellation) stays the
            // primary one; a failing release or finish callback is attached as suppressed, and
            // [running] is reset before the finish callback can throw.
            Closeable { finish() }.use {
                Closeable { releaseResource() }.use { restore() }
            }
        }
        return true
    }

    private fun finish() {
        running = false
        onFinished()
    }

    override fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
        }
        scope.cancel()
        try {
            releaseResource()
        } finally {
            running = false
        }
    }

    private fun releaseResource() {
        val acquired = synchronized(this) {
            resource.also { resource = null }
        }
        acquired?.close()
    }
}
