package com.hexnext.engine.arsc

import com.hexnext.engine.arsc.ArscTestBuilder.Entry
import com.hexnext.engine.arsc.ArscTestBuilder.Layout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArscReaderTest {
    private val deluxe: ResourceTable by lazy {
        ArscReader.read(javaClass.getResourceAsStream("/deluxe_resources.arsc")!!.readBytes())
    }

    @Test
    fun `reads package of the Deluxe plugin`() {
        assertEquals("project.vivid.ui.plugin.deluxe", deluxe.packageName)
        assertEquals(0x7f, deluxe.packageId)
        assertTrue(deluxe.entries.size > 100)
        assertTrue(deluxe.overlayable.isEmpty())
    }

    @Test
    fun `drawables are file references`() {
        val back = assertNotNull(deluxe.find("drawable", "ic_sysbar_back"))
        assertEquals(0x7f040020, back.id)
        assertEquals(listOf(ConfiguredValue("", ResourceValue.FileRef("res/drawable/ic_sysbar_back.png"))), back.values)
        for (name in listOf("ic_sysbar_home", "ic_sysbar_recent", "qs_circle")) {
            assertIs<ResourceValue.FileRef>(deluxe.find("drawable/$name")!!.values.single().value)
        }
        val drawables = deluxe.entries.filter { it.type == "drawable" }
        assertTrue(drawables.size > 50)
        assertTrue(drawables.all { e -> e.values.all { it.value is ResourceValue.FileRef } })
    }

    @Test
    fun `density configs are decoded`() {
        val banner = deluxe.find("drawable/banner")!!.values.single()
        assertEquals("nodpi", banner.config)
        assertEquals(0xFFFF, banner.resConfig.density)
        assertEquals("xxhdpi", deluxe.find("mipmap/qlogo")!!.values.single().config)
    }

    @Test
    fun `strings colors and bools resolve`() {
        assertEquals(ResourceValue.Str("Deluxe"), deluxe.find("string/plugin_name")!!.values.single().value)
        assertEquals(ResourceValue.Str("Charlie_Dii_"), deluxe.find("string/plugin_authors")!!.values.single().value)
        assertEquals(ResourceValue.Str("#Hex Deluxe Plugin"), deluxe.find("string/app_name")!!.values.single().value)
        assertEquals(ResourceValue.Color(0xFFF16E9A.toInt()), deluxe.find("color/hex_accent")!!.values.single().value)
        assertEquals(ResourceValue.Color(0xFF000000.toInt()), deluxe.find("color/hex_background")!!.values.single().value)
        val accentRef = deluxe.find("color/colorAccent")!!.values.single().value
        assertEquals(ResourceValue.Reference(0x7f030004, attr = false), accentRef)
        assertEquals(ResourceValue.Color(0xFFF16E9A.toInt()), deluxe.resolve(accentRef))
        assertEquals(ResourceValue.BoolValue(true), deluxe.find("bool/has_lvl")!!.values.single().value)
    }

    @Test
    fun `styles are complex values`() {
        val style = assertIs<ResourceValue.Complex>(deluxe.find("style/IconTheme.Default")!!.values.single().value)
        assertEquals(16974382, style.parentId)
        assertTrue(style.items.isNotEmpty())
    }

    private fun syntheticTable(utf16: Boolean = false): ResourceTable {
        val b = ArscTestBuilder("com.android.systemui")
        b.typeNames += listOf("color", "drawable", "style")
        val accent = b.key("accent")
        val bg = b.key("bg")
        val icon = b.key("icon")
        val theme = b.key("Theme")
        val iconPath = b.string("res/drawable-xxhdpi/icon.png")
        val iconNight = b.string("res/drawable-night-xhdpi/icon.png")
        b.typeSpec(1, 3)
        b.type(1, 3, b.config(), mapOf(0 to Entry.Simple(accent, 0x1c, 0xFF112233.toInt()), 2 to Entry.Simple(bg, 0x1d, 0x00445566)))
        b.type(1, 3, b.config(uiMode = 0x20), mapOf(0 to Entry.Compact(accent, 0x1c, 0xFFAABBCC.toInt())), Layout.OFFSET16)
        b.typeSpec(2, 1)
        b.type(2, 1, b.config(density = 480), mapOf(0 to Entry.Simple(icon, 0x03, iconPath)), Layout.SPARSE)
        b.type(2, 1, b.config(density = 320, uiMode = 0x20, sdk = 26), mapOf(0 to Entry.Simple(icon, 0x03, iconNight)))
        b.type(2, 1, b.config(orientation = 2, touchscreen = 3), mapOf(0 to Entry.Simple(icon, 0x01, 0x7f010000)))
        b.typeSpec(3, 1)
        b.type(3, 1, b.config(), mapOf(0 to Entry.Bag(theme, 0x01030005, listOf(Triple(0x01010030, 0x1c, 0xFF000000.toInt())))))
        b.overlayable("SystemUIConfig", "overlay://theme", listOf(
            OverlayablePolicy.PUBLIC to listOf(0x7f010000, 0x7f010002),
            (OverlayablePolicy.SYSTEM_PARTITION or OverlayablePolicy.SIGNATURE) to listOf(0x7f020000),
        ))
        return ArscReader.read(b.build(utf16GlobalPool = utf16))
    }

    @Test
    fun `dense offset16 sparse and compact entries`() {
        val t = syntheticTable()
        assertEquals("com.android.systemui", t.packageName)
        val accent = t.find("color/accent")!!
        assertEquals(0x7f010000, accent.id)
        assertEquals(
            listOf("" to ResourceValue.Color(0xFF112233.toInt()), "night" to ResourceValue.Color(0xFFAABBCC.toInt())),
            accent.values.map { it.config to it.value },
        )
        assertEquals(ResourceValue.Color(0xFF445566.toInt()), t.find("color/bg")!!.values.single().value)
        assertEquals(0x7f010002, t.find("color/bg")!!.id)
        assertNull(t.findById(0x7f010001))
        val icon = t.find("drawable/icon")!!
        assertEquals(ResourceValue.FileRef("res/drawable-xxhdpi/icon.png"), icon.values[0].value)
        assertEquals("xxhdpi", icon.values[0].config)
        assertEquals("night-xhdpi-v26", icon.values[1].config)
        assertTrue(icon.values[1].resConfig.isNight)
        // touchscreen is outside the decoded subset: falls back to raw hex, density stays readable
        assertTrue(icon.values[2].config.startsWith("cfg:40000000"))
        assertEquals(ResourceValue.Reference(0x7f010000, attr = false), icon.values[2].value)
    }

    @Test
    fun `complex entries and utf16 pools`() {
        val t = syntheticTable(utf16 = true)
        val bag = assertIs<ResourceValue.Complex>(t.find("style/Theme")!!.values.single().value)
        assertEquals(0x01030005, bag.parentId)
        assertEquals(mapOf(0x01010030 to ResourceValue.Color(0xFF000000.toInt())), bag.items)
        assertEquals(ResourceValue.FileRef("res/drawable-xxhdpi/icon.png"), t.find("drawable/icon")!!.values[0].value)
    }

    @Test
    fun `overlayable groups are exposed`() {
        val t = syntheticTable()
        assertEquals(
            listOf(
                OverlayableGroup("SystemUIConfig", "overlay://theme", OverlayablePolicy.PUBLIC, listOf(0x7f010000, 0x7f010002)),
                OverlayableGroup("SystemUIConfig", "overlay://theme", 0x12, listOf(0x7f020000)),
            ),
            t.overlayable,
        )
        assertEquals(setOf(0x7f010000, 0x7f010002, 0x7f020000), t.overlayableIds)
    }

    @Test
    fun `config qualifiers`() {
        val b = ArscTestBuilder("x")
        assertEquals("", ResConfig.decode(b.config()).qualifiers)
        assertEquals("night", ResConfig.decode(b.config(uiMode = 0x20)).qualifiers)
        assertEquals("xxhdpi", ResConfig.decode(b.config(density = 480)).qualifiers)
        assertEquals("night-xxhdpi", ResConfig.decode(b.config(density = 480, uiMode = 0x20)).qualifiers)
        assertEquals("land-notnight-420dpi-v31", ResConfig.decode(b.config(density = 420, uiMode = 0x10, orientation = 2, sdk = 31)).qualifiers)
    }

    @Test
    fun `malformed input throws ArscFormatException`() {
        assertFailsWith<ArscFormatException> { ArscReader.read(ByteArray(4)) }
        assertFailsWith<ArscFormatException> { ArscReader.read(ByteArray(16)) }
        val good = ArscTestBuilder("x").run {
            typeNames += "color"; key("a"); type(1, 1, config(), mapOf(0 to Entry.Simple(0, 0x1c, 1))); build()
        }
        ArscReader.read(good)
        assertFailsWith<ArscFormatException> { ArscReader.read(good.copyOf(good.size - 10)) }
        val corrupt = good.copyOf().also { it[4] = 0x7f; it[5] = 0x7f } // table size beyond file
        assertFailsWith<ArscFormatException> { ArscReader.read(corrupt) }
    }
}
