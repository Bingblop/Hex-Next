package com.hexnext.engine.device

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OverlayDumpTest {
    private val settingsDump: String =
        checkNotNull(javaClass.getResourceAsStream("/overlay_dump_settings_oneui9.txt")) { "fixture missing" }
            .use { it.readBytes().toString(Charsets.UTF_8) }

    @Test
    fun `parses the One UI 9 settings dump`() {
        val all = OverlayDump.parseAll(settingsDump)
        assertEquals(1, all.size)
        val d = all.single()
        assertEquals("android:SemWT_com.android.settings", d.overlayId)
        assertEquals(0, d.userId)
        assertEquals("android", d.packageName)
        assertEquals("SemWT_com.android.settings", d.overlayName)
        assertEquals("com.android.settings", d.targetPackage)
        assertEquals("", d.targetOverlayable)
        assertEquals("/data/resource-cache/android-SemWT_com.android.settings-Ozaw.frro", d.baseCodePath)
        assertEquals("STATE_ENABLED", d.state)
        assertTrue(d.isEnabled)
        assertEquals(true, d.isMutable)
        assertEquals(Int.MAX_VALUE, d.priority)
        assertTrue(d.isMaxPriority)
        assertTrue(d.isFabricated)

        // 174 Mapping lines in the fixture, each with a default and a night value; the
        // trailing "Color palette history" log is not part of the mapping.
        assertEquals(174, d.entries.size)
        assertTrue(d.entries.all { it.kind == "color" && it.nightValue != null && it.type == "color" })
        assertEquals(174, d.entries.map { it.name }.distinct().size)
        assertEquals(14, d.missing.size)
        assertTrue("com.android.settings:color/wifi_hidden_ap_list_empty_text" in d.missing)

        val first = d.entries.first()
        assertEquals(MappedResource(0x7f060020, "color", "actionbar_text_color", "0xff0b4d5f", "0xff6ed4ef", "color"), first)
        assertEquals(0xff0b4d5f.toInt(), first.defaultColor)
        assertEquals(0xff6ed4ef.toInt(), first.nightColor)
        assertEquals("com.android.settings:color/actionbar_text_color", d.resourceName(first))
        val bg = d.entries.single { it.name == "data_usage_progress_bar_bg_color" }
        assertEquals(0x4d19b9e5, bg.defaultColor)
    }

    @Test
    fun `mapping lines with one value and other kinds`() {
        val one = assertNotNull(OverlayDump.parseMapping("    0x7f060020 -> color 0xff0b4d5f (color/a)"))
        assertNull(one.nightValue)
        assertEquals("0xff0b4d5f", one.defaultValue)

        val dimen = assertNotNull(OverlayDump.parseMapping("    0x7f070001 -> dimension 0x00001801 (dimen/status_bar_height)"))
        assertEquals("dimension", dimen.kind)
        assertEquals("0x00001801", dimen.defaultValue)
        assertNull(dimen.defaultColor)

        val raw = assertNotNull(OverlayDump.parseMapping("    0x7f070001 -> 0x00001801 (dimen/x)"))
        assertEquals("raw", raw.kind)
        assertEquals("0x00001801", raw.defaultValue)

        val file = assertNotNull(
            OverlayDump.parseMapping(
                "    0x7f080010 -> file res/drawable/a.png    0x7f080010 -> file res/drawable-night/a.png (drawable/a)",
            ),
        )
        assertEquals("file", file.kind)
        assertEquals("res/drawable/a.png", file.defaultValue)
        assertEquals("res/drawable-night/a.png", file.nightValue)
        assertNull(file.nightKind)

        val mixed = assertNotNull(OverlayDump.parseMapping("0x7f060001 -> color 0xff000000    0x7f060001 -> reference 0x7f060002 (color/m)"))
        assertEquals("reference", mixed.nightKind)
        assertNull(mixed.nightColor)

        assertNull(OverlayDump.parseMapping("10-31 05:56:39.563: V SWT_MetaData(4216): replaced by x"))
        assertNull(OverlayDump.parseMapping("Mapping:"))
    }

    @Test
    fun `parses several info blocks`() {
        val text = """
            ${'$'} cmd overlay dump android | head -120
            android:SemWT_MonetPalette:0 {
              mPackageName...........: android
              mOverlayName...........: SemWT_MonetPalette
              mUserId................: 0
              mTargetPackageName.....: android
              mTargetOverlayableName.: 
              mBaseCodePath..........: /data/resource-cache/android-SemWT_MonetPalette-pmkz.frro
              mState.................: STATE_ENABLED
              mIsEnabled.............: true
              mIsMutable.............: true
              mPriority..............: 2147483647
              mCategory..............: null
              mIsFabricated..........: true
              mConstraints...........: None
            }
            android:SemWT_com.samsung.android.dynamiclock:0 {
              mPackageName...........: android
              mOverlayName...........: SemWT_com.samsung.android.dynamiclock
              mTargetPackageName.....: com.samsung.android.dynamiclock
              mTargetOverlayableName.: 
              mState.................: STATE_MISSING_TARGET
              mIsEnabled.............: true
              mPriority..............: 2147483647
              mIsFabricated..........: true
            }
            android:SemWT_com.android.vpndialogs:0 {
              mPackageName...........: android
              mTargetPackageName.....: com.android.vpndialogs
              mState.................: STATE_NO_IDMAP
              mIsEnabled.............: false
              mPriority..............: 2147483647
            }
        """.trimIndent()
        val all = OverlayDump.parseAll(text)
        assertEquals(
            listOf("android:SemWT_MonetPalette", "android:SemWT_com.samsung.android.dynamiclock", "android:SemWT_com.android.vpndialogs"),
            all.map { it.overlayId },
        )
        assertEquals(listOf("STATE_ENABLED", "STATE_MISSING_TARGET", "STATE_NO_IDMAP"), all.map { it.state })
        assertTrue(all.all { it.priority == Int.MAX_VALUE && it.targetOverlayable.isEmpty() && it.entries.isEmpty() })
        assertEquals("SemWT_com.android.vpndialogs", all[2].overlayName) // falls back to the id
        assertFalse(all[2].isEnabled)
        assertNull(all[2].isMutable)
        assertTrue(OneUi9Facts.isDebloated(all[1].state))
        assertFalse(OneUi9Facts.isDebloated(all[0].state))
        assertEquals("com.samsung.android.dynamiclock", OneUi9Facts.semWtTarget(all[1].overlayId))
    }

    @Test
    fun `one ui 9 facts match the diagnostics`() {
        assertEquals(42, OneUi9Facts.ENABLED_SEMWT_TARGETS.size)
        assertEquals(OneUi9Facts.ENABLED_SEMWT_TARGETS.size, OneUi9Facts.ENABLED_SEMWT_TARGETS.toSet().size)
        assertTrue("com.android.settings" in OneUi9Facts.ENABLED_SEMWT_TARGETS)
        assertTrue("android" in OneUi9Facts.ENABLED_SEMWT_TARGETS)
        assertFalse(OneUi9Facts.ENABLED_SEMWT_TARGETS.any { it.contains("MonetPalette") })
        assertEquals("android:SemWT_com.android.settings", OneUi9Facts.semWtOverlayId("com.android.settings"))
        assertNull(OneUi9Facts.semWtTarget("android:SemWT_MonetPalette"))
        assertTrue(OneUi9Facts.SEMWT_FRRO_PATH.matches("/data/resource-cache/android-SemWT_com.android.settings-Ozaw.frro"))
        assertEquals(0xff008396.toInt(), OneUi9Facts.LOOKUP_SYSTEM_ACCENT1_500)
    }
}
