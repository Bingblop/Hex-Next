package com.hexnext.themer.shizuku

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.junit.Assert.*
import org.junit.Test

class RootRequestTest {
    private val backgroundError = "Background root start failed"

    @Test fun backgroundFailurePreservesActiveRequest() {
        val active = ShizukuManager.RootRequest(requesting = true)

        val next = active.onBackgroundFailure(backgroundError)

        assertSame(active, next)
        assertTrue(next.requesting)
        assertNull(next.error)
    }

    @Test fun backgroundFailureDoesNotReplaceActiveRequestError() {
        val active = ShizukuManager.RootRequest(requesting = true, error = "Explicit request")

        val next = active.onBackgroundFailure(backgroundError)

        assertSame(active, next)
        assertEquals("Explicit request", next.error)
    }

    @Test fun backgroundFailureStillReportsAnIdleFailure() {
        val next = ShizukuManager.RootRequest().onBackgroundFailure(backgroundError)

        assertFalse(next.requesting)
        assertEquals(backgroundError, next.error)
    }

    @Test fun backgroundFailureRetainsExistingBehaviorAfterFailedRequestCompletes() {
        val completed = ShizukuManager.RootRequest(error = "Explicit request failed")

        val next = completed.onBackgroundFailure(backgroundError)

        assertEquals(ShizukuManager.RootRequest(error = backgroundError), next)
    }

    @Test fun backgroundFailureRetainsExistingBehaviorAfterSuccessfulRequestCompletes() {
        val state = MutableStateFlow(ShizukuManager.RootRequest(requesting = true))
        state.value = ShizukuManager.RootRequest()

        state.update { it.onBackgroundFailure(backgroundError) }

        assertEquals(ShizukuManager.RootRequest(error = backgroundError), state.value)
    }

    @Test fun atomicUpdatePreservesARequestThatStartsDuringTheUpdate() {
        val state = MutableStateFlow(ShizukuManager.RootRequest())
        val active = ShizukuManager.RootRequest(requesting = true)
        var attempts = 0

        state.update { previous ->
            if (attempts++ == 0) state.value = active
            previous.onBackgroundFailure(backgroundError)
        }

        assertEquals(2, attempts)
        assertSame(active, state.value)
        assertTrue(state.value.requesting)
        assertNull(state.value.error)
    }
}
