package com.hexnext.themer.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

class BetaBundleTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `bundle holds device info, reports without old bundles, and only the newest logs`() {
        val reports = tmp.newFolder("reports")
        val logs = tmp.newFolder("logs")
        File(reports, "apply-last.json").writeText("{}")
        File(reports, "scan-1.json").writeText("{}")
        File(reports, "hexnext-beta-report.zip").writeText("old")
        File(reports, "x.tmp").writeText("half")
        (1..7).forEach { i -> File(logs, "$i.jsonl").apply { writeText("$i"); setLastModified(i * 1000L) } }

        val out = BetaBundle.write(File(reports, "hexnext-beta-report.zip"), "info", reports, logs, maxLogs = 3)
        assertNotNull(out)
        val names = ZipFile(out!!).use { z -> z.entries().toList().map { it.name } }
        assertEquals(
            listOf("device.txt", "reports/apply-last.json", "reports/scan-1.json", "logs/7.jsonl", "logs/6.jsonl", "logs/5.jsonl"),
            names,
        )
    }
}
