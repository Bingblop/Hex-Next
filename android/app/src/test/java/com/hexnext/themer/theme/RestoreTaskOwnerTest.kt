package com.hexnext.themer.theme

import java.io.Closeable
import java.util.ArrayDeque
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.*
import org.junit.Test

class RestoreTaskOwnerTest {
    private class QueuedDispatcher : CoroutineDispatcher() {
        private val queued = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queued.addLast(block)
        }
        fun drain() {
            while (queued.isNotEmpty()) queued.removeFirst().run()
        }
    }

    private class Fixture {
        val dispatcher = QueuedDispatcher()
        val parent = SupervisorJob()
        val failures = mutableListOf<Throwable>()
        val scope = CoroutineScope(
            parent + dispatcher + CoroutineExceptionHandler { _, error -> failures += error },
        )
        var releases = 0
        var finishes = 0
        val resource = Closeable { releases++ }
        val owner = RestoreTaskOwner(scope) { finishes++ }
    }

    @Test fun destructionBeforeDispatchReleasesTheWakeResourceImmediately() {
        val f = Fixture()
        var entered = false
        assertTrue(f.owner.start(f.resource) { entered = true })
        f.owner.close()
        assertEquals(1, f.releases)
        f.dispatcher.drain()
        assertFalse(entered)
        assertFalse(f.owner.running)
        assertEquals(1, f.releases)
        assertEquals(0, f.finishes)
    }

    @Test fun destructionDuringSuspensionReleasesBeforeTheCancelledTaskResumes() {
        val f = Fixture()
        f.owner.start(f.resource) { awaitCancellation() }
        val job = f.parent.children.single()
        f.dispatcher.drain()
        assertTrue(f.owner.running)
        f.owner.close()
        assertEquals(1, f.releases)
        f.dispatcher.drain()
        assertTrue(job.isCancelled)
        assertEquals(1, f.releases)
        assertEquals(1, f.finishes)
        assertTrue(f.failures.isEmpty())
    }

    @Test fun successReleasesOnceAndFinishesTheService() {
        val f = Fixture()
        f.owner.start(f.resource) { assertEquals(0, f.releases) }
        f.dispatcher.drain()
        assertFalse(f.owner.running)
        assertEquals(1, f.releases)
        assertEquals(1, f.finishes)
        f.owner.close()
        f.owner.close()
        assertEquals(1, f.releases)
        assertTrue(f.failures.isEmpty())
    }

    @Test fun cancellationIsNotConvertedToSuccessfulCompletion() {
        val f = Fixture()
        f.owner.start(f.resource) { throw CancellationException("test cancellation") }
        val job = f.parent.children.single()
        f.dispatcher.drain()
        assertTrue(job.isCancelled)
        assertEquals(1, f.releases)
        assertEquals(1, f.finishes)
        assertTrue(f.failures.isEmpty())
        f.owner.close()
    }

    @Test fun unexpectedFailureReachesTheBoundaryAfterCleanup() {
        val f = Fixture()
        val failure = IllegalStateException("synthetic restore failure")
        f.owner.start(f.resource) { throw failure }
        f.dispatcher.drain()
        assertEquals(listOf(failure), f.failures)
        assertFalse(f.owner.running)
        assertEquals(1, f.releases)
        assertEquals(1, f.finishes)
        f.owner.close()
    }

    @Test fun cleanupFailureDoesNotHideTheOriginalFailureOrSkipTheFinish() {
        val f = Fixture()
        val failure = IllegalStateException("synthetic restore failure")
        val cleanup = IllegalArgumentException("synthetic release failure")
        f.owner.start(Closeable { f.releases++; throw cleanup }) { throw failure }
        f.dispatcher.drain()
        assertSame(failure, f.failures.single())
        assertArrayEquals(arrayOf(cleanup), failure.suppressed)
        assertEquals(1, f.releases)
        assertEquals(1, f.finishes)
        f.owner.close()
        assertEquals(1, f.releases)
    }

    @Test fun finishFailureDoesNotHideTheRestoreFailure() {
        val f = Fixture()
        val failure = IllegalStateException("synthetic restore failure")
        val finishFailure = IllegalArgumentException("synthetic finish failure")
        var finishes = 0
        val owner = RestoreTaskOwner(f.scope) { finishes++; throw finishFailure }
        owner.start(f.resource) { throw failure }
        f.dispatcher.drain()
        assertSame(failure, f.failures.single())
        assertArrayEquals(arrayOf(finishFailure), failure.suppressed)
        assertFalse(owner.running)
        assertEquals(1, f.releases)
        assertEquals(1, finishes)
    }

    @Test fun finishFailureAfterSuccessReachesTheBoundaryWithRunningReset() {
        val f = Fixture()
        val finishFailure = IllegalArgumentException("synthetic finish failure")
        val owner = RestoreTaskOwner(f.scope) { throw finishFailure }
        owner.start(f.resource) { }
        f.dispatcher.drain()
        assertSame(finishFailure, f.failures.single())
        assertFalse(owner.running)
        assertEquals(1, f.releases)
    }

    @Test fun finishFailureDuringDestructionKeepsTheCancellation() {
        val f = Fixture()
        val owner = RestoreTaskOwner(f.scope) { throw IllegalArgumentException("synthetic finish failure") }
        owner.start(f.resource) { awaitCancellation() }
        val job = f.parent.children.single()
        f.dispatcher.drain()
        owner.close()
        assertEquals(1, f.releases)
        f.dispatcher.drain()
        assertTrue(job.isCancelled)
        assertFalse(owner.running)
        assertEquals(1, f.releases)
        assertTrue(f.failures.isEmpty())
    }

    @Test fun duplicateStartClosesOnlyTheRejectedResource() {
        val f = Fixture()
        var rejectedReleases = 0
        assertTrue(f.owner.start(f.resource) { awaitCancellation() })
        assertFalse(f.owner.start(Closeable { rejectedReleases++ }) { fail("duplicate ran") })
        assertEquals(1, rejectedReleases)
        assertEquals(0, f.releases)
        f.owner.close()
        f.dispatcher.drain()
        assertEquals(1, f.releases)
    }

    @Test fun destroyedOwnerRejectsAndReleasesNewResources() {
        val f = Fixture()
        f.owner.close()
        assertFalse(f.owner.start(f.resource) { fail("destroyed owner ran") })
        f.dispatcher.drain()
        assertFalse(f.owner.running)
        assertEquals(1, f.releases)
        assertEquals(0, f.finishes)
    }
}
