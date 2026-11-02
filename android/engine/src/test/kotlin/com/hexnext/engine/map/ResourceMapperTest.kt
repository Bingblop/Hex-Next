package com.hexnext.engine.map

import com.hexnext.engine.arsc.ConfiguredValue
import com.hexnext.engine.arsc.ResConfig
import com.hexnext.engine.arsc.ResourceEntry
import com.hexnext.engine.arsc.ResourceTable
import com.hexnext.engine.arsc.ResourceValue
import com.hexnext.engine.frro.FabricatedOverlayFile
import com.hexnext.engine.frro.FrroEntry
import kotlin.test.Test
import kotlin.test.assertEquals

class ResourceMapperTest {
    private fun file(path: String, density: Int, uiMode: Int = 0) =
        ConfiguredValue(
            ResConfig(density = density, uiMode = uiMode).qualifiers,
            ResourceValue.FileRef(path),
            ResConfig(density = density, uiMode = uiMode),
        )

    private val table = ResourceTable(
        "com.android.systemui", 0x7f,
        listOf(
            ResourceEntry("color", "qs_tile_active", 0x7f010000, listOf(ConfiguredValue("", ResourceValue.Color(0)))),
            ResourceEntry("color", "notification_bg", 0x7f010001, listOf(ConfiguredValue("", ResourceValue.Color(0)))),
            ResourceEntry(
                "drawable", "ic_sysbar_back", 0x7f020000,
                listOf(file("a", 480), file("b", 640), file("c", 480, uiMode = 0x20)),
            ),
            ResourceEntry("drawable", "qs_bg", 0x7f020001, listOf(ConfiguredValue("", ResourceValue.FileRef("res/drawable/qs_bg.xml")))),
            ResourceEntry(
                "mipmap", "ic_launcher", 0x7f030000,
                listOf(file("res/mipmap-anydpi-v26/ic_launcher.xml", 0xFFFE), file("d", 480)),
            ),
            ResourceEntry("drawable", "ic_vector", 0x7f020002, listOf(file("res/drawable-anydpi/ic_vector.xml", 0xFFFE))),
            ResourceEntry("drawable", "ic_hdpi", 0x7f020003, listOf(file("e", 240), file("f", 0xFFFE))),
        ),
    )

    private val semwt = FabricatedOverlayFile(
        "SemWT_com.android.systemui", "com.android.systemui", "",
        listOf(FrroEntry("com.android.systemui:color/qs_tile_active", 0x1c, 1), FrroEntry("com.android.systemui:drawable/qs_bg", 0x03, 0)),
    )

    private val src = DrawableSource("deluxe-4-2", "navbar", "ic_sysbar_back")

    @Test
    fun `plans present resources and skips missing ones`() {
        val plan = ResourceMapper.map(
            table, semwt,
            ThemeRequest(
                colors = linkedMapOf(
                    "color/qs_tile_active" to 1,
                    "com.android.systemui:color/notification_bg" to 2,
                    "color/removed_in_oneui9" to 3,
                    "nonsense" to 4,
                ),
                drawables = linkedMapOf(
                    "ic_sysbar_back" to src,
                    "drawable/qs_bg" to src.copy(resourceName = "qs_bg"),
                    "drawable/ic_gone" to src,
                ),
            ),
        )
        assertEquals("com.android.systemui", plan.targetPackage)
        assertEquals(
            listOf(PlannedColor("com.android.systemui:color/qs_tile_active", 1), PlannedColor("com.android.systemui:color/notification_bg", 2)),
            plan.colorEntries,
        )
        assertEquals(
            listOf(
                PlannedDrawable("com.android.systemui:drawable/ic_sysbar_back", src, listOf("xxhdpi", "xxxhdpi")),
                PlannedDrawable("com.android.systemui:drawable/qs_bg", src.copy(resourceName = "qs_bg"), listOf("xxhdpi")),
            ),
            plan.drawableEntries,
        )
        assertEquals(
            listOf(
                Skipped("color/removed_in_oneui9", ResourceMapper.REASON_NOT_IN_TARGET),
                Skipped("nonsense", ResourceMapper.REASON_BAD_NAME),
                Skipped("drawable/ic_gone", ResourceMapper.REASON_NOT_IN_TARGET),
            ),
            plan.skipped,
        )
        assertEquals(2, plan.semwtCoverage)
        assertEquals(4, plan.size)
    }

    @Test
    fun `no semwt means zero coverage`() {
        val plan = ResourceMapper.map(table, null, ThemeRequest(colors = mapOf("color/qs_tile_active" to 1)))
        assertEquals(0, plan.semwtCoverage)
        assertEquals(1, plan.colorEntries.size)
    }

    @Test
    fun `png jobs never use the default configuration or anydpi`() {
        val plan = ResourceMapper.map(
            table, null,
            ThemeRequest(
                drawables = linkedMapOf(
                    "drawable/qs_bg" to src,
                    "mipmap/ic_launcher" to src,
                    "drawable/ic_vector" to src,
                    "drawable/ic_hdpi" to src,
                ),
            ),
        )
        assertEquals(
            listOf(listOf("xxhdpi"), listOf("xxhdpi"), listOf("xxhdpi"), listOf("hdpi", "xxhdpi")),
            plan.drawableEntries.map { it.configs },
        )
    }

    @Test
    fun `png densities keep real densities and nodpi`() {
        assertEquals(listOf("xxhdpi", "xxxhdpi"), ResourceMapper.pngDensities(listOf("xxhdpi", "xxxhdpi", "xxhdpi")))
        assertEquals(listOf("nodpi"), ResourceMapper.pngDensities(listOf("nodpi")))
        assertEquals(listOf(ResourceMapper.FALLBACK_DENSITY), ResourceMapper.pngDensities(emptyList()))
        assertEquals(listOf("xxhdpi"), ResourceMapper.pngDensities(listOf("", "anydpi")))
    }
}
