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
    fun `takeover disables semwt and raises only when the list shows ours below`() {
        val hex = "com.android.shell:HexNext_com_android_settings"
        val disableSem = "cmd overlay disable --user current android:SemWT_com.android.settings"
        assertEquals(listOf(disableSem), SemWtPlanner.takeover("com.android.settings", hex))
        assertEquals(listOf(disableSem), SemWtPlanner.takeover("com.android.settings", hex, OverlayList.parse(list)))
        val below = OverlayList.parse(
            """
            com.android.settings
            [x] $hex
            [x] android:SemWT_com.android.settings
            """.trimIndent(),
        )
        assertEquals(
            listOf(disableSem, "cmd overlay disable --user current $hex", "cmd overlay enable --user current $hex"),
            SemWtPlanner.takeover("com.android.settings", hex, below),
        )
        assertTrue(SemWtPlanner.takeover("com.android.settings", "x; reboot").isEmpty())
        assertFailsWith<IllegalArgumentException> { SemWtPlanner.enable("a:b c") }
        assertTrue(SemWtPlanner.raiseCycle(hex).none { "set-priority" in it })
    }

    @Test
    fun `raise need follows list order within the target section`() {
        val ours = "com.android.shell:HexNext_com_android_settings"
        val sem = "android:SemWT_com.android.settings"
        fun need(text: String) = SemWtPlanner.raiseNeed(OverlayList.parse(text.trimIndent()), "com.android.settings", ours)

        // Ours last: no raise.
        assertEquals(RaiseNeed.NONE, need("com.android.settings\n[x] $sem\n[x] $ours"))
        // SemWT after ours: raise.
        assertEquals(RaiseNeed.RAISE, need("com.android.settings\n[x] $ours\n[x] $sem"))
        // SemWT after ours but disabled: no raise.
        assertEquals(RaiseNeed.NONE, need("com.android.settings\n[x] $ours\n[ ] $sem"))
        // Another enabled overlay of the same target after ours: raise.
        assertEquals(RaiseNeed.RAISE, need("com.android.settings\n[x] $ours\n[x] com.example:other"))
        // Enabled overlays of the next target do not count.
        assertEquals(RaiseNeed.NONE, need("com.android.settings\n[x] $ours\n\nandroid\n[x] android:SemWT_android"))
        // A filtered list without headers, as the device test greps it (finding: probe one line after SemWT).
        assertEquals(RaiseNeed.NONE, need("[x] $sem\n[x] $ours"))
        assertEquals(RaiseNeed.RAISE, need("[x] $ours\n[x] $sem"))
        // Ours disabled: enable; ours missing: register again.
        assertEquals(RaiseNeed.ENABLE, need("com.android.settings\n[ ] $ours\n[x] $sem"))
        assertEquals(RaiseNeed.REGISTER, need("com.android.settings\n[x] $sem"))
        assertTrue(SemWtPlanner.needsRaise(OverlayList.parse("[x] $ours\n[x] $sem"), "com.android.settings", ours))
        assertFalse(SemWtPlanner.needsRaise(OverlayList.parse("[x] $sem"), "com.android.settings", ours))
    }

    @Test
    fun `restore re-enables only overlays that were enabled`() {
        assertEquals(listOf("cmd overlay enable --user current android:SemWT_android"), SemWtPlanner.restore(state))
    }

    @Test
    fun `reassert disables a re-enabled semwt and raises a hex overlay that fell below`() {
        val rows = OverlayList.parse(list)
        // SemWT_android was re-enabled but sits below HexNext_android: disable it, no raise needed.
        assertEquals(listOf("cmd overlay disable --user current android:SemWT_android"), SemWtPlanner.reassert(state, rows))
        val reordered = OverlayList.parse(
            """
            android
            [x] com.android.shell:HexNext_android
            [x] android:SemWT_android
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                "cmd overlay disable --user current android:SemWT_android",
                "cmd overlay disable --user current com.android.shell:HexNext_android",
                "cmd overlay enable --user current com.android.shell:HexNext_android",
            ),
            SemWtPlanner.reassert(state, reordered),
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
        assertTrue("cksum" in text)
        assertTrue("needs_raise \"\$hex\"" in text)
        assertFalse("set-priority" in text)
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
