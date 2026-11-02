package com.hexnext.engine.log

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BuildLogTest {
    @Test
    fun `writes one json object per line`() {
        val out = StringBuilder()
        var now = 1_700_000_000_000L
        val log = BuildLogWriter(out) { now++ }
        log.info("com.android.systemui", "built overlay", mapOf("resources" to "42", "skipped" to "color/a,color/b"))
        log.warn("", "sentinel mismatch")
        log.write(BuildEvent(5, LogLevel.ERROR, "android", "quote \" and\nnewline"))
        val lines = out.toString().split('\n')
        assertEquals(4, lines.size)
        assertEquals("", lines.last())
        assertEquals(
            """{"timestamp":1700000000000,"level":"info","target":"com.android.systemui","message":"built overlay","data":{"resources":"42","skipped":"color/a,color/b"}}""",
            lines[0],
        )
        val events = BuildEvent.parseLog(out.toString())
        assertEquals(listOf(LogLevel.INFO, LogLevel.WARN, LogLevel.ERROR), events.map { it.level })
        assertEquals(1_700_000_000_001L, events[1].timestamp)
        assertEquals(emptyMap(), events[1].data)
        assertEquals("quote \" and\nnewline", events[2].message)
        assertFailsWith<IllegalArgumentException> { BuildEvent.fromJsonLine("{\"level\":\"loud\"}") }
    }
}
