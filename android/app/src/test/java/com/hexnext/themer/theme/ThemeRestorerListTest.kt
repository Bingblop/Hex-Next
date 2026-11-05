package com.hexnext.themer.theme

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A restore re-applies the whole theme, so it may start only when `cmd overlay list` succeeded
 * and shows no Hex Next overlay; a failed or incomplete list must not read as "overlays gone".
 */
class ThemeRestorerListTest {

    private val withoutOurs = """
        |com.android.settings
        |[x] android:SemWT_com.android.settings
        |com.android.systemui
        |[ ] com.samsung.android.themecenter.overlay
    """.trimMargin()

    private val withOurs = """
        |com.android.settings
        |[x] com.hexnext.themer:HexNext_com.android.settings
        |[x] android:SemWT_com.android.settings
    """.trimMargin()

    @Test
    fun `a successful list without Hex Next overlays means they are gone`() {
        assertTrue(ThemeRestorer.overlaysGone(0, withoutOurs))
        assertTrue(ThemeRestorer.overlaysGone(0, ""))
    }

    @Test
    fun `a successful list with a Hex Next overlay means nothing is gone`() {
        assertFalse(ThemeRestorer.overlaysGone(0, withOurs))
    }

    @Test
    fun `a failed or incomplete list never reads as gone`() {
        // 125: ExecOutput.fullText or the service could not return the whole spilled output;
        // -1: the bundle carried no code at all.
        for (code in listOf(1, 125, 255, -1)) {
            assertFalse("code $code, empty", ThemeRestorer.overlaysGone(code, ""))
            assertFalse("code $code, partial", ThemeRestorer.overlaysGone(code, withoutOurs))
            assertFalse("code $code, ours", ThemeRestorer.overlaysGone(code, withOurs))
        }
    }

    @Test
    fun `failed foreground starts do not require a particular error message`() {
        for (code in listOf(1, 125, 255, -1)) {
            assertTrue(ThemeRestorer.foregroundStartFailed(code, ""))
            assertTrue(ThemeRestorer.foregroundStartFailed(code, "Starting service"))
        }
    }

    @Test
    fun `a reported platform error remains a failed start even with exit zero`() {
        assertTrue(ThemeRestorer.foregroundStartFailed(0, "Error: service not found"))
    }

    @Test
    fun `an ordinary successful foreground start is unchanged`() {
        assertFalse(ThemeRestorer.foregroundStartFailed(0, "Starting service"))
    }
}
