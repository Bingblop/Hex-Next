package com.hexnext.engine.compile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApplyReportTest {
    private val report = ApplyReport(
        themeId = "default",
        themeName = "Hex Next",
        startedAt = 1,
        finishedAt = 2,
        outcomes = listOf(
            TargetOutcome("android", TargetResult.OK, "HexNext_android", colorCount = 10, resourceCount = 10, semwtDisabled = true),
            TargetOutcome("com.android.settings", TargetResult.PARTIAL, semwtDisabled = true),
            TargetOutcome("com.android.systemui", TargetResult.FAILED, message = "boom"),
            TargetOutcome("com.samsung.android.app.notes", TargetResult.SKIPPED_DEBLOATED),
            TargetOutcome("com.sec.android.app.clockpackage", TargetResult.SKIPPED),
            TargetOutcome("com.samsung.android.lool", TargetResult.EMPTY),
        ),
        logFile = "/data/user/0/com.hexnext.themer/files/logs/x.jsonl",
        notes = listOf("legacy overlay removed"),
    )

    @Test
    fun `counts and summary`() {
        assertEquals(2, report.okCount)
        assertEquals(1, report.partialCount)
        assertEquals(1, report.failedCount)
        assertEquals(1, report.debloatedCount)
        assertEquals(2, report.skippedCount)
        assertEquals(2, report.semwtDisabledCount)
        assertFalse(report.success)
        assertEquals("2 ok (1 partial), 1 failed, 1 debloated, 2 skipped, 2 SemWT disabled", report.summary())
        assertTrue(report.copy(outcomes = report.outcomes.take(2)).success)
    }

    @Test
    fun `json round trip`() {
        assertEquals(report, ApplyReport.fromJson(report.toJson()))
        assertFailsWith<IllegalArgumentException> { ApplyReport.fromJson("{") }
    }

    @Test
    fun `verification mismatch is partial and counted`() {
        val ok = TargetOutcome("android", TargetResult.OK, message = "registered")
        val pass = ok.withVerification(true)
        assertEquals(TargetResult.OK, pass.result)
        assertEquals(true, pass.verified)
        val miss = ok.withVerification(false, "lookup #ff00ff00, expected #ffff0000")
        assertEquals(TargetResult.PARTIAL, miss.result)
        assertEquals(false, miss.verified)
        assertEquals("registered\nlookup #ff00ff00, expected #ffff0000", miss.message)
        assertEquals(TargetResult.FAILED, TargetOutcome("x", TargetResult.FAILED).withVerification(false).result)
        assertEquals(null, ok.withVerification(null).verified)

        val checked = report.copy(outcomes = listOf(pass, miss, TargetOutcome("y", TargetResult.SKIPPED)))
        assertEquals(1, checked.verifiedCount)
        assertEquals(1, checked.mismatchCount)
        assertEquals("2 ok (1 partial), 0 failed, 0 debloated, 1 skipped, 0 SemWT disabled, 1/2 verified", checked.summary())
        assertEquals(checked, ApplyReport.fromJson(checked.toJson()))
        // Reports written before the field existed still load.
        assertEquals(null, ApplyReport.fromJson(report.toJson().replace(",\"verified\":null", "")).outcomes.first().verified)
    }

    @Test
    fun `lookup output parsing`() {
        assertEquals(0xffff0000.toInt(), OverlayLookup.parseColor("#ffff0000"))
        assertEquals(0xff00ff00.toInt(), OverlayLookup.parseColor("  #00ff00\n"))
        assertEquals(null, OverlayLookup.parseColor("Error: resource not found"))
        assertEquals(true, OverlayLookup.matches("#ffff0000", 0xffff0000.toInt()))
        assertEquals(false, OverlayLookup.matches("#ff0000ff", 0xffff0000.toInt()))
        assertEquals(null, OverlayLookup.matches("", 0))
    }

    @Test
    fun `probe skips colours with a night variant`() {
        fun target(vararg colors: com.hexnext.engine.map.PlannedColor) =
            CompiledTarget("p", "HexNext_p", colors.toList(), emptyList(), emptyList(), 0, "")
        val a = com.hexnext.engine.map.PlannedColor("p:color/a", 1)
        val aNight = com.hexnext.engine.map.PlannedColor("p:color/a", 2, "night")
        val b = com.hexnext.engine.map.PlannedColor("p:color/b", 3)
        assertEquals(b, OverlayLookup.probe(target(a, aNight, b)))
        assertEquals(a, OverlayLookup.probe(target(a, b)))
        assertEquals(null, OverlayLookup.probe(target(a, aNight)))
    }
}
