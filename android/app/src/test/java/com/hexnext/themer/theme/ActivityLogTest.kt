package com.hexnext.themer.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ActivityLogTest {
    @Test
    fun `lines are appended and the file keeps its newer half past the cap`() {
        val dir = Files.createTempDirectory("activity").toFile()
        val file = ActivityLog.file(dir)
        val log = ActivityLog(file, maxBytes = 200)
        log.write("first line\n")
        assertEquals("first line\n", file.readText())
        repeat(20) { log.write("line $it padding padding\n") }
        val text = file.readText()
        assertTrue(file.length() <= 200)
        assertTrue(text.endsWith("line 19 padding padding\n"))
        assertTrue(!text.contains("first line"))
        // Whole lines only after trimming.
        assertTrue(text.startsWith("line "))
        dir.deleteRecursively()
    }

    @Test
    fun `the beta report carries the activity log`() {
        val dir = Files.createTempDirectory("bundle").toFile()
        val activity = File(dir, "activity.log").apply { writeText("2026-11-05 18:44:00 INFO Pack selected: Carnelian\n") }
        val out = com.hexnext.themer.device.BetaBundle.write(File(dir, "out.zip"), "info", File(dir, "r"), File(dir, "l"), activity = activity)!!
        val names = java.util.zip.ZipFile(out).use { z -> z.entries().toList().map { it.name } }
        assertTrue("activity.log" in names)
        dir.deleteRecursively()
    }
}
