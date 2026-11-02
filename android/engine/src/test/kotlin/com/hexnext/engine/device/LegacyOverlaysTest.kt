package com.hexnext.engine.device

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LegacyOverlaysTest {
    @Test
    fun `legacy ids are recognised by owner prefix and exact name`() {
        for (id in listOf(
            "com.android.shell:HexNextOneUI9_Android",
            "com.android.shell:HexNextOneUI9_SystemUI",
            "com.android.shell:HexNextMonetShades",
            "com.android.shell:HexNextMonetRoles",
            "com.android.shell:HexNextSettingsIcons26",
            "com.android.shell:HexNext_probe",
        )) assertTrue(LegacyOverlays.isLegacy(id), id)
        for (id in listOf(
            "com.android.shell:HexNext_com.android.systemui", // the compiler's overlay
            "com.android.shell:HexNextOneUI9_",
            "android:HexNextMonetShades",
            "android:SemWT_android",
            "com.android.shell:HexNextMonetShadesX",
            "com.android.shell:HexNextOneUI9_x; rm -rf /",
        )) assertFalse(LegacyOverlays.isLegacy(id), id)
    }

    @Test
    fun `find picks legacy rows from cmd overlay list`() {
        val list = OverlayList.parse(
            """
            android
            [ ] android:SemWT_android
            [x] com.android.shell:HexNextMonetShades
            [x] com.android.shell:HexNextOneUI9_Android
            [x] com.android.shell:HexNext_android
            com.android.systemui
            [x] android:SemWT_com.android.systemui
            [ ] com.android.shell:HexNextOneUI9_SystemUI
            """.trimIndent(),
        )
        assertEquals(
            listOf("com.android.shell:HexNextMonetShades", "com.android.shell:HexNextOneUI9_Android", "com.android.shell:HexNextOneUI9_SystemUI"),
            LegacyOverlays.find(list).map { it.overlayId },
        )
    }

    @Test
    fun `purge command only for safe names`() {
        assertEquals("rm -f /data/resource-cache/com.android.shell-HexNextMonetRoles-*.frro", LegacyOverlays.purgeCommand("HexNextMonetRoles"))
        assertNull(LegacyOverlays.purgeCommand("a b"))
        assertNull(LegacyOverlays.purgeCommand("*"))
    }
}
