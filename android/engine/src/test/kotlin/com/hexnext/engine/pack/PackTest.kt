package com.hexnext.engine.pack

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PackTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val packJson = """
        {
         "format": 1, "id": "deluxe-4-2", "name": "Deluxe", "author": "Charlie_Dii_", "version": "4.2",
         "sourcePackage": "project.vivid.ui.plugin.deluxe", "dayNight": true, "features": ["navbar", "qs"],
         "palette": {"lxx1": "#FFEEEEEE", "xx1": "#FF111111", "xy2": "#FF0000FF", "bogus": "nope"},
         "settings": {"radius": 12, "style": "round"},
         "options": [
          {"type": "select", "keys": ["a", "b"], "label": "Shape", "choices": ["Round", "Square"]},
          {"type": "switch", "key": "glow", "label": "Glow:on"}
         ],
         "categoryOptions": {"navbar": ["a", "b"]},
         "categories": {"navbar": "Navigation bar", "qs": "Quick settings"},
         "layers": [
          {"pass": "twice", "stage": "base", "category": "navbar", "tint": {"kind": "slot", "slot": "xy2"},
           "path": "layers/twice/base/xy2/navbar", "resources": ["ic_sysbar_back"]},
          {"pass": "once", "stage": "first", "category": "navbar", "tint": {"kind": "fixed", "argb": "#80FF0000"},
           "path": "layers/once/first/H80FF0000/navbar", "resources": ["ic_sysbar_back", "ic_sysbar_home"]},
          {"pass": "once", "stage": "base", "category": "navbar", "tint": {"kind": "slot", "slot": "xx1"},
           "path": "layers/once/base/xx1/navbar", "resources": ["ic_sysbar_back"]},
          {"pass": "twice", "stage": "second", "category": "navbar", "tint": {"kind": "none"},
           "path": "layers/twice/second/default/navbar", "resources": ["ic_sysbar_back"]},
          {"pass": "twice", "stage": "first", "category": "navbar", "tint": {"kind": "slot", "slot": "zz9"},
           "path": "layers/twice/first/zz9/navbar", "resources": ["ic_sysbar_back"]},
          {"pass": "once", "stage": "base", "category": "qs", "tint": {"kind": "slot", "slot": "xx1"},
           "path": "layers/once/base/xx1/qs", "resources": ["ic_sysbar_back"]}
         ],
         "previewCount": 2, "drawableCount": 0, "futureField": {"x": 1}
        }
    """.trimIndent()

    private fun buildZip(): File {
        val file = tmp.newFile("deluxe-4-2.zip")
        ZipOutputStream(file.outputStream()).use { z ->
            fun put(name: String, bytes: ByteArray) { z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry() }
            put("pack.json", packJson.toByteArray())
            put("layers/once/base/xx1/navbar/ic_sysbar_back.png", byteArrayOf(1))
            put("layers/once/first/H80FF0000/navbar/ic_sysbar_back.png", byteArrayOf(2))
            put("layers/once/first/H80FF0000/navbar/ic_sysbar_home.png", byteArrayOf(3))
            put("layers/twice/base/xy2/navbar/ic_sysbar_back.9.png", byteArrayOf(4))
            // twice/second is listed in pack.json but its file is missing from the zip
            put("layers/twice/first/zz9/navbar/ic_sysbar_back.png", byteArrayOf(5))
        }
        return file
    }

    @Test
    fun `reads pack json from zip`() {
        val zip = buildZip()
        val pack = PackReader.read(zip)
        assertEquals("deluxe-4-2", pack.id)
        assertEquals("Charlie_Dii_", pack.author)
        assertTrue(pack.dayNight)
        assertEquals(6, pack.layers.size)
        assertEquals(Tint("fixed", argb = "#80FF0000"), pack.layers[1].tint)
        assertEquals(listOf("a", "b"), pack.options[0].keys)
        assertEquals("glow", pack.options[1].key)
        assertEquals(mapOf("navbar" to listOf("a", "b")), pack.categoryOptions)
        assertEquals(mapOf("lxx1" to 0xFFEEEEEE.toInt(), "xx1" to 0xFF111111.toInt(), "xy2" to 0xFF0000FF.toInt()), pack.paletteArgb)
        assertContentEquals(byteArrayOf(3), PackReader.openLayer(zip, "layers/once/first/H80FF0000/navbar/ic_sysbar_home.png"))
        assertFailsWith<PackFormatException> { PackReader.openLayer(zip, "layers/missing.png") }
    }

    @Test
    fun `plans layers in pass and stage order with night tints`() {
        val zip = buildZip()
        val pack = PackReader.read(zip)
        val plan = LayerPlanner.plan(pack, "navbar", "ic_sysbar_back", mapOf("xy2" to 0xFF00FF00.toInt()), night = true, PackReader.entryNames(zip))
        assertEquals(
            listOf(
                CompositeStep("layers/once/base/xx1/navbar/ic_sysbar_back.png", 0xFF111111.toInt()),
                CompositeStep("layers/once/first/H80FF0000/navbar/ic_sysbar_back.png", 0x80FF0000.toInt()),
                CompositeStep("layers/twice/base/xy2/navbar/ic_sysbar_back.9.png", 0xFF00FF00.toInt()),
                CompositeStep("layers/twice/first/zz9/navbar/ic_sysbar_back.png", null),
            ),
            plan.steps,
        )
        assertTrue(plan.steps[2].isNinePatch)
        assertEquals(listOf("zz9"), plan.unresolvedSlots)
    }

    @Test
    fun `light mode prefers l-prefixed slots`() {
        val pack = PackReader.read(buildZip())
        val plan = LayerPlanner.plan(pack, "navbar", "ic_sysbar_back", emptyMap(), night = false)
        // without a zip listing the resources lists decide, so twice/second is included
        assertEquals(
            listOf(0xFFEEEEEE.toInt(), 0x80FF0000.toInt(), 0xFF0000FF.toInt(), null, null),
            plan.steps.map { it.tintArgb },
        )
        assertEquals("layers/twice/second/default/navbar/ic_sysbar_back.png", plan.steps[4].layerPath)
        val override = LayerPlanner.plan(pack, "navbar", "ic_sysbar_back", mapOf("lxx1" to 7), night = false)
        assertEquals(7, override.steps.first().tintArgb)
        assertTrue(LayerPlanner.plan(pack, "navbar", "nothing", emptyMap(), night = true).isEmpty)
        assertEquals(1, LayerPlanner.plan(pack, "qs", "ic_sysbar_back", emptyMap(), night = true).steps.size)
    }

    @Test
    fun `parses index json and rejects garbage`() {
        val index = PackIndex.parse(
            """{"format": 1, "packs": [{"id": "deluxe-4-2", "name": "Deluxe", "author": "C", "version": "4.2",
               "file": "deluxe-4-2.zip", "bytes": 123456, "categories": ["navbar"], "dayNight": true, "previewCount": 2}]}""",
        )
        assertEquals("deluxe-4-2.zip", index.packs.single().file)
        assertEquals(123456L, index.packs.single().bytes)
        assertFailsWith<PackFormatException> { PackIndex.parse("{") }
        assertFailsWith<PackFormatException> { PackReader.parse("""{"id": 1}""") }
        val notZip = tmp.newFile("x.zip").apply { writeText("not a zip") }
        assertFailsWith<PackFormatException> { PackReader.read(notZip) }
    }

    @Test
    fun `colour parsing`() {
        assertEquals(0xFF112233.toInt(), Pack.parseColor("#112233"))
        assertEquals(0x80112233.toInt(), Pack.parseColor("80112233"))
        assertEquals(0xFFAABBCC.toInt(), Pack.parseColor("#ABC"))
        assertEquals(null, Pack.parseColor("#12"))
        assertEquals(null, Pack.parseColor("#GGGGGG"))
    }
}
