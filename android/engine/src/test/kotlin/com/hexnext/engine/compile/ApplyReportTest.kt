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
}
