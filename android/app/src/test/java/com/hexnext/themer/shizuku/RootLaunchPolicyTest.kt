package com.hexnext.themer.shizuku

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RootLaunchPolicyTest {
    private val calls = mutableListOf<Pair<Int, Int>>()

    private suspend fun request(
        attempt: suspend (Int, Int) -> RootLaunchPolicy.Attempt<Int>,
    ): RootLaunchPolicy.Attempt<Int> =
        RootLaunchPolicy.firstSuccessful(listOf(1000, 0)) { uid ->
            RootLaunchPolicy.firstSuccessful(listOf(0, 1)) { variant ->
                calls += uid to variant
                attempt(uid, variant)
            }
        }

    @Test fun systemSuccessNeverTriesRootFallback() = runBlocking {
        assertEquals(RootLaunchPolicy.Started(1000), request { uid, _ ->
            RootLaunchPolicy.Started(uid)
        })
        assertEquals(listOf(1000 to 0), calls)
    }

    @Test fun ordinaryFailureStillTriesAlternateSystemCommand() = runBlocking {
        assertEquals(RootLaunchPolicy.Started(1000), request { uid, variant ->
            if (variant == 0) RootLaunchPolicy.Failed("unsupported command")
            else RootLaunchPolicy.Started(uid)
        })
        assertEquals(listOf(1000 to 0, 1000 to 1), calls)
    }

    @Test fun ordinarySystemFailuresStillAllowRealRootFallback() = runBlocking {
        assertEquals(RootLaunchPolicy.Started(0), request { uid, _ ->
            if (uid == 1000) RootLaunchPolicy.Failed("cannot switch UID")
            else RootLaunchPolicy.Started(uid)
        })
        assertEquals(listOf(1000 to 0, 1000 to 1, 0 to 0), calls)
    }

    @Test fun allOrdinaryFailuresExhaustExistingCompatibilityForms() = runBlocking {
        assertEquals(RootLaunchPolicy.Failed("denied"), request { _, _ ->
            RootLaunchPolicy.Failed("denied")
        })
        assertEquals(listOf(1000 to 0, 1000 to 1, 0 to 0, 0 to 1), calls)
    }

    @Test fun firstPromptTimeoutStopsCommandAndUidRetries() = runBlocking {
        val timeout = RootLaunchPolicy.Failed("prompt timed out", retryAllowed = false)
        assertEquals(timeout, request { _, _ -> timeout })
        assertEquals(listOf(1000 to 0), calls)
    }

    @Test fun alternateSystemPromptTimeoutStopsRootFallback() = runBlocking {
        val timeout = RootLaunchPolicy.Failed("prompt timed out", retryAllowed = false)
        assertEquals(timeout, request { _, variant ->
            if (variant == 0) RootLaunchPolicy.Failed("unsupported command") else timeout
        })
        assertEquals(listOf(1000 to 0, 1000 to 1), calls)
    }

    @Test fun rootPromptTimeoutStopsItsAlternateCommand() = runBlocking {
        val timeout = RootLaunchPolicy.Failed("prompt timed out", retryAllowed = false)
        assertEquals(timeout, request { uid, _ ->
            if (uid == 1000) RootLaunchPolicy.Failed("cannot switch UID") else timeout
        })
        assertEquals(listOf(1000 to 0, 1000 to 1, 0 to 0), calls)
    }

    @Test fun serviceHandshakeTimeoutDoesNotStartAnotherUid() = runBlocking {
        val timeout = RootLaunchPolicy.Failed("service did not answer", retryAllowed = false)
        val uids = mutableListOf<Int>()
        assertEquals(timeout, RootLaunchPolicy.firstSuccessful(listOf(1000, 0)) { uid ->
            uids += uid
            timeout
        })
        assertEquals(listOf(1000), uids)
    }

    @Test fun cancellationEscapesWithoutCommandOrUidFallback() = runBlocking {
        val cancellation = CancellationException("request cancelled")
        try {
            request { _, _ -> throw cancellation }
            fail("Cancellation must escape the retry policy")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
        assertEquals(listOf(1000 to 0), calls)
    }

    @Test fun cancellationBetweenAttemptsPreventsTheNextCommand() = runBlocking {
        val child = launch {
            request { _, _ ->
                cancel("request cancelled")
                RootLaunchPolicy.Failed("unsupported command")
            }
        }
        child.join()
        assertTrue(child.isCancelled)
        assertEquals(listOf(1000 to 0), calls)
    }

    @Test fun cancellationCannotBeReturnedAsSuccessfulAccess() = runBlocking {
        var returnedNormally = false
        val child = launch {
            request { uid, _ ->
                cancel("request cancelled")
                RootLaunchPolicy.Started(uid)
            }
            returnedNormally = true
        }
        child.join()
        assertTrue(child.isCancelled)
        assertFalse(returnedNormally)
        assertEquals(listOf(1000 to 0), calls)
    }

    @Test fun aLaterExplicitRequestCanStartAtSystemUidAgain() = runBlocking {
        val timeout = RootLaunchPolicy.Failed("prompt timed out", retryAllowed = false)
        assertEquals(timeout, request { _, _ -> timeout })
        assertEquals(RootLaunchPolicy.Started(1000), request { uid, _ ->
            RootLaunchPolicy.Started(uid)
        })
        assertEquals(listOf(1000 to 0, 1000 to 0), calls)
    }

    @Test fun processTimeoutIsTerminalAndCleansUpStreams() = runBlocking {
        val process = FakeProcess(completed = false)
        val result = RootLaunchPolicy.runCommand(45) { process }
        assertTrue(result is RootLaunchPolicy.Failed)
        assertFalse((result as RootLaunchPolicy.Failed).retryAllowed)
        assertTrue(result.message.contains("su did not return"))
        assertEquals(45L, process.waitSeconds)
        assertTrue(process.destroyed.get())
        assertTrue(process.inputClosed)
        assertTrue(process.outputClosed)
        assertTrue(process.errorClosed)
    }

    @Test fun successfulProcessDoesNotKillTheAcceptedServiceLauncher() = runBlocking {
        val process = FakeProcess(completed = true)
        assertEquals(RootLaunchPolicy.Started(Unit), RootLaunchPolicy.runCommand(8) { process })
        assertEquals(8L, process.waitSeconds)
        assertFalse(process.destroyed.get())
        assertTrue(process.inputClosed && process.outputClosed && process.errorClosed)
    }

    @Test fun failedProcessKeepsTheCompatibilityRetryPath() = runBlocking {
        val process = FakeProcess(completed = true, exit = 1)
        val result = RootLaunchPolicy.runCommand(8) { process }
        assertTrue(result is RootLaunchPolicy.Failed)
        assertTrue((result as RootLaunchPolicy.Failed).retryAllowed)
        assertTrue(result.message.startsWith("su exit 1:"))
    }

    @Test fun cleanupIoFailureAfterStartIsTerminalAndClosesEveryStream() = runBlocking {
        val process = FakeProcess(completed = true, closeFails = true)
        var attempts = 0
        val result = RootLaunchPolicy.firstSuccessful(listOf(0, 1)) {
            attempts++
            RootLaunchPolicy.runCommand(8) { process }
        }
        assertTrue(result is RootLaunchPolicy.Failed)
        assertFalse((result as RootLaunchPolicy.Failed).retryAllowed)
        assertEquals(1, attempts)
        assertTrue(process.inputClosed && process.outputClosed && process.errorClosed)
    }

    @Test fun cleanupIoFailureAfterTimeoutCannotReopenPrompts() = runBlocking {
        val process = FakeProcess(completed = false, closeFails = true)
        val result = RootLaunchPolicy.runCommand(45) { process }
        assertTrue(result is RootLaunchPolicy.Failed)
        assertFalse((result as RootLaunchPolicy.Failed).retryAllowed)
        assertTrue(process.destroyed.get())
        assertTrue(process.inputClosed && process.outputClosed && process.errorClosed)
    }

    @Test fun diagnosticIoFailureAfterStartIsNotACompatibilityFailure() = runBlocking {
        val process = FakeProcess(completed = true, readFails = true)
        val result = RootLaunchPolicy.runCommand(8) { process }
        assertTrue(result is RootLaunchPolicy.Failed)
        assertFalse((result as RootLaunchPolicy.Failed).retryAllowed)
        assertTrue(process.inputClosed && process.outputClosed && process.errorClosed)
    }

    @Test fun processStartIoFailureStillAllowsACompatibleCommand() = runBlocking {
        var attempts = 0
        val result = RootLaunchPolicy.firstSuccessful(listOf(0, 1)) { variant ->
            attempts++
            RootLaunchPolicy.runCommand(8) {
                if (variant == 0) throw IOException("unsupported command")
                FakeProcess(completed = true)
            }
        }
        assertEquals(RootLaunchPolicy.Started(Unit), result)
        assertEquals(2, attempts)
    }

    @Test fun unexpectedCleanupFailureEscapesButOtherStreamsStillClose() = runBlocking {
        val process = FakeProcess(completed = true, unexpectedCloseFailure = true)
        try {
            RootLaunchPolicy.runCommand(8) { process }
            fail("Unexpected cleanup failures must escape")
        } catch (e: IllegalStateException) {
            assertEquals("unexpected close failure", e.message)
        }
        assertTrue(process.inputClosed && process.outputClosed && process.errorClosed)
    }

    @Test fun cancellationSurvivesCleanupIoFailuresWithSuppressedEvidence() = runBlocking {
        val process = FakeProcess(completed = false, blockUntilInterrupted = true, closeFails = true)
        val observed = AtomicReference<CancellationException>()
        val child = launch(Dispatchers.Default) {
            try {
                RootLaunchPolicy.runCommand(45) { process }
                fail("Cancellation must not become normal completion")
            } catch (e: CancellationException) {
                observed.set(e)
                throw e
            }
        }
        try {
            assertTrue("Process wait did not start", process.waitEntered.await(5, TimeUnit.SECONDS))
            child.cancel()
            child.join()
            assertTrue(child.isCancelled)
            assertNotNull(observed.get())
            val interruption = process.interruption.get()
            assertNotNull("Original interrupted wait must survive cleanup", interruption)
            assertEquals(3, interruption!!.suppressed.filterIsInstance<IOException>().size)
            assertTrue(process.destroyed.get())
            assertTrue(process.inputClosed && process.outputClosed && process.errorClosed)
        } finally {
            child.cancel()
        }
    }

    @Test fun cancellationInterruptsPromptWaitAndReleasesTheProcess() = runBlocking {
        val process = FakeProcess(completed = false, blockUntilInterrupted = true)
        val child = launch(Dispatchers.Default) {
            RootLaunchPolicy.runCommand(45) { process }
            fail("Cancelled process wait must not return normally")
        }
        try {
            assertTrue("Process wait did not start", process.waitEntered.await(5, TimeUnit.SECONDS))
            child.cancel()
            child.join()
            assertTrue(child.isCancelled)
            assertTrue(process.interrupted.get())
            assertTrue(process.destroyed.get())
            assertTrue(process.inputClosed && process.outputClosed && process.errorClosed)
        } finally {
            child.cancel()
        }
    }

    private class FakeProcess(
        private val completed: Boolean,
        private val exit: Int = 0,
        private val blockUntilInterrupted: Boolean = false,
        private val closeFails: Boolean = false,
        private val readFails: Boolean = false,
        private val unexpectedCloseFailure: Boolean = false,
    ) : Process() {
        val destroyed = AtomicBoolean(false)
        val interrupted = AtomicBoolean(false)
        val interruption = AtomicReference<InterruptedException>()
        val waitEntered = CountDownLatch(1)
        var waitSeconds: Long? = null
        var inputClosed = false
        var outputClosed = false
        var errorClosed = false
        private val input = object : ByteArrayInputStream("diagnostic".toByteArray()) {
            override fun available(): Int {
                if (readFails) throw IOException("diagnostic read failed")
                return super.available()
            }
            override fun close() {
                inputClosed = true
                if (unexpectedCloseFailure) throw IllegalStateException("unexpected close failure")
                if (closeFails) throw IOException("input close failed")
                super.close()
            }
        }
        private val output = object : ByteArrayOutputStream() {
            override fun close() {
                outputClosed = true
                if (closeFails) throw IOException("output close failed")
                super.close()
            }
        }
        private val error = object : ByteArrayInputStream(byteArrayOf()) {
            override fun close() {
                errorClosed = true
                if (closeFails) throw IOException("error close failed")
                super.close()
            }
        }
        override fun getInputStream(): InputStream = input
        override fun getOutputStream(): OutputStream = output
        override fun getErrorStream(): InputStream = error
        override fun waitFor(): Int = exit
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            waitSeconds = unit.toSeconds(timeout)
            waitEntered.countDown()
            if (blockUntilInterrupted) {
                try {
                    CountDownLatch(1).await()
                } catch (e: InterruptedException) {
                    interrupted.set(true)
                    interruption.set(e)
                    throw e
                }
            }
            return completed
        }
        override fun exitValue(): Int = exit
        override fun isAlive(): Boolean = !completed && !destroyed.get()
        override fun destroy() { destroyed.set(true) }
        override fun destroyForcibly(): Process { destroy(); return this }
    }
}
