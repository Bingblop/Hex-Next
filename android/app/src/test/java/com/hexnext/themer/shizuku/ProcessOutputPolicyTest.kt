package com.hexnext.themer.shizuku

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

class ProcessOutputPolicyTest {
    @Test fun acceptsOnlyTheServiceGeneratedSpillNamespace() {
        val root = "/data/local/tmp/hexnext"
        assertTrue(ProcessOutputPolicy.isSpillPath("$root/exec/out-123-456-0.txt", root))
        for (path in listOf("$root/exec/../secret", "$root/exec/sub/out-1-2-3.txt",
            "$root/exec/out-1-2-3.txt';id", "/data/other/exec/out-1-2-3.txt")) {
            assertFalse(path, ProcessOutputPolicy.isSpillPath(path, root))
        }
    }

    @Test fun captureNeverGrowsPastItsLimit() {
        val capture = ProcessOutputPolicy.Capture(4)
        assertTrue(capture.append("abc".toCharArray(), 3))
        assertFalse(capture.append("defgh".toCharArray(), 5))
        assertEquals("abcd", capture.snapshot())
        assertTrue(capture.exceeded())
    }

    @Test fun concurrentReadersHaveAnAtomicBound() {
        val capture = ProcessOutputPolicy.Capture(100)
        val workers = List(8) { Thread { repeat(50) { capture.append(charArrayOf('x'), 1) } } }
        workers.forEach(Thread::start)
        workers.forEach(Thread::join)
        assertEquals(100, capture.snapshot().length)
        assertTrue(capture.exceeded())
    }

    @Test fun byteLimitDoesNotConfuseUtf8WithCharacterCounts() {
        val input = ByteArrayInputStream("éé".toByteArray())
        assertThrows(IOException::class.java) { ProcessOutputPolicy.readUtf8(input, 3) }
        assertEquals("éé", ProcessOutputPolicy.readUtf8(ByteArrayInputStream("éé".toByteArray()), 4))
    }
}
