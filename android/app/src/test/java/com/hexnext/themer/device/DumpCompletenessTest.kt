package com.hexnext.themer.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A `cmd overlay dump` that did not exit 0 must not become a mapping or a remembered palette:
 * `ExecOutput.fullText` reports 125 and returns only the inline head when a spilled output
 * cannot be read back, and that head can already hold the first mapping lines.
 */
class DumpCompletenessTest {

    private val overlayId = "android:SemWT_com.android.settings"

    private val head = """
        |$overlayId:0 {
        |  mPackageName...........: android
        |  mOverlayName...........: SemWT_com.android.settings
        |  mTargetPackageName.....: com.android.settings
        |  mIsEnabled.............: true
        |}
        |IDMAP OF $overlayId
        |Mapping:
        |    0x7f060020 -> color 0xff0b4d5f    0x7f060020 -> color 0xff6ed4ef (color/actionbar_text_color)
        |    0x7f06025c -> color 0x4d19b9e5    0x7f06025c -> color 0x4d19b9e5 (color/data_usage_progress_bar_bg_color)
        |… (480000 characters; full output in /data/local/tmp/hexnext/exec/out-1-1-0.txt)
    """.trimMargin()

    @Test
    fun `a failed spill read leaves no dump even though its head parses`() {
        // The precondition the guard exists for: the partial head alone yields mapping entries.
        assertEquals(2, DeviceScanner.completeDump(0, head, overlayId)?.entries?.size)

        assertNull(DeviceScanner.completeDump(125, head, overlayId))
        assertNull(DeviceScanner.completeOutput(125, head))
    }

    @Test
    fun `any nonzero or missing exit code is rejected`() {
        for (code in listOf(1, 125, 255, -1)) {
            assertNull("code $code", DeviceScanner.completeDump(code, head, overlayId))
            assertNull("code $code", DeviceScanner.completeOutput(code, head))
        }
    }

    @Test
    fun `a successful dump is parsed as before`() {
        assertEquals(head, DeviceScanner.completeOutput(0, head))
        val dump = checkNotNull(DeviceScanner.completeDump(0, head, overlayId))
        assertEquals(overlayId, dump.overlayId)
        assertEquals("com.android.settings", dump.targetPackage)
        assertEquals(listOf("color/actionbar_text_color", "color/data_usage_progress_bar_bg_color"), dump.entries.map { it.typeAndName })
        assertEquals(0xff6ed4ef.toInt(), dump.entries.first().nightColor)
    }

    @Test
    fun `a successful dump without the requested overlay is still null`() {
        assertNull(DeviceScanner.completeDump(0, head, "android:SemWT_com.android.systemui"))
        assertNull(DeviceScanner.completeDump(0, "", overlayId))
    }
}
