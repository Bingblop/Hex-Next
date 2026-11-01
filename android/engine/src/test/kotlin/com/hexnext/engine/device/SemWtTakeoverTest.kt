package com.hexnext.engine.device

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SemWtTakeoverTest {
    private val list = """
        android
        [x] android:SemWT_android
        [x] com.android.shell:HexNextMonetShades
        [x] com.android.shell:HexNext_android

        com.android.settings
        [ ] android:SemWT_com.android.settings
        [x] com.android.shell:HexNext_com_android_settings

        com.android.settings.intelligence
        [x] android:SemWT_com.android.settings.intelligence

        com.samsung.android.calendar
        --- android:SemWT_com.samsung.android.calendar
    """.trimIndent()

    private val state = SemWtState()
        .withTakeover("android", "com.android.shell:HexNext_android", semwtEnabledNow = true, now = 10)
        .withTakeover("com.android.settings", "com.android.shell:HexNext_com_android_settings", semwtEnabledNow = false, now = 11)

    @Test
    fun `overlay list parsing keeps targets and markers`() {
        val rows = OverlayList.parse(list)
        assertEquals(7, rows.size)
        val settings = rows.first { it.overlayId == "android:SemWT_com.android.settings" }
        assertEquals("com.android.settings", settings.target)
        assertFalse(settings.enabled)
        assertEquals("[ ]", settings.marker)
        assertEquals("---", rows.last().marker)
        assertTrue(rows.first { it.overlayId == "android:SemWT_com.android.settings.intelligence" }.enabled)
    }

    @Test
    fun `takeover keeps the first enabled state`() {
        val again = state.withTakeover("android", "com.android.shell:HexNext_android", semwtEnabledNow = false, now = 99)
        val record = again.record("android")!!
        assertTrue(record.wasEnabled)
        assertEquals(10, record.disabledAt)
        assertEquals("android:SemWT_android", record.semwtId)
        assertEquals(99, again.updatedAt)
        assertEquals(2, again.records.size)
        assertEquals(listOf("com.android.settings"), again.without(listOf("android"), 100).records.map { it.target })
    }

    @Test
    fun `state json round trip and blank state`() {
        assertEquals(state, SemWtState.fromJson(state.toJson()))
        assertEquals(SemWtState(), SemWtState.fromJson(" "))
        assertFailsWith<IllegalArgumentException> { SemWtState.fromJson("[1]") }
        assertTrue(state.toJson().contains("\"wasEnabled\":true"))
    }

    @Test
    fun `commands disable semwt then raise the hex overlay`() {
        assertEquals(
            listOf(
                "cmd overlay disable --user current android:SemWT_com.android.settings",
                "cmd overlay set-priority --user current com.android.shell:HexNext_com_android_settings highest",
            ),
            SemWtPlanner.takeover("com.android.settings", "com.android.shell:HexNext_com_android_settings"),
        )
        assertTrue(SemWtPlanner.takeover("com.android.settings", "x; reboot").isEmpty())
        assertFailsWith<IllegalArgumentException> { SemWtPlanner.enable("a:b c") }
    }

    @Test
    fun `restore re-enables only overlays that were enabled`() {
        assertEquals(listOf("cmd overlay enable --user current android:SemWT_android"), SemWtPlanner.restore(state))
    }

    @Test
    fun `reassert acts only where samsung re-enabled semwt under an enabled hex overlay`() {
        val rows = OverlayList.parse(list)
        assertEquals(
            listOf(
                "cmd overlay disable --user current android:SemWT_android",
                "cmd overlay set-priority --user current com.android.shell:HexNext_android highest",
            ),
            SemWtPlanner.reassert(state, rows),
        )
        val hexOff = rows.map { if (it.overlayId == "com.android.shell:HexNext_android") it.copy(enabled = false) else it }
        assertTrue(SemWtPlanner.reassert(state, hexOff).isEmpty())
    }

    @Test
    fun `guard script embeds the pairs and has no template markers left`() {
        val text = SemWtGuardScript.text(state)
        assertTrue(text.startsWith("#!/system/bin/sh\n"))
        assertTrue("android:SemWT_android|com.android.shell:HexNext_android\n" in text)
        assertTrue("android:SemWT_com.android.settings|com.android.shell:HexNext_com_android_settings'" in text)
        assertTrue("RESTORE='android:SemWT_android'" in text)
        assertTrue("FULL=${SemWtGuardScript.FULL_CHECK_TICKS}" in text)
        assertFalse('£' in text)
        assertFalse('@' in text)
        assertTrue("\$(stamp)" in text)
        assertTrue("grep -qxF \"[x] \$hex\"" in text)
    }

    @Test
    fun `guard start and stop commands`() {
        val start = SemWtGuardScript.startCommand(1)
        assertTrue(start.contains("setsid sh ${SemWtGuardScript.PATH} 5 "))
        val stop = SemWtGuardScript.stopCommand()
        assertFalse('£' in stop)
        assertTrue(stop.startsWith("P=\$(cat ${SemWtGuardScript.PID_FILE}"))
        assertTrue(stop.contains("*semwt_guard.sh*) kill \$P"))
    }
}
