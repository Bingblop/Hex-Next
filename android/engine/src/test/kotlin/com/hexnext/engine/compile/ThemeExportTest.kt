package com.hexnext.engine.compile

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ThemeExportTest {
    private val spec = ThemeSpec.default().copy(
        id = "x", mode = ThemeMode.ADVANCED, simplePackId = "a", elementPacks = mapOf("keyboard" to "b"),
        colorPins = mapOf("android:color/c" to ColorPin.Fixed(5)),
    )

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> for ((n, b) in entries) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
        return out.toByteArray()
    }

    @Test
    fun `round trip with pack ids`() {
        val bytes = ThemeExport.export(spec)
        assertEquals(spec, ThemeExport.import(bytes))
        assertEquals(listOf("a", "b"), ThemeExport.read(bytes).packIds)
        val names = ZipInputStream(bytes.inputStream()).use { z -> generateSequence { z.nextEntry?.name }.toList() }
        assertEquals(listOf(ThemeExport.THEME_JSON, ThemeExport.PACKS_JSON), names)
    }

    @Test
    fun `export is reproducible and pack ids are optional`() {
        assertContentEquals(ThemeExport.export(spec), ThemeExport.export(spec))
        val bare = ThemeExport.export(spec, includePackIds = false)
        assertEquals(emptyList(), ThemeExport.read(bare).packIds)
        assertEquals(spec, ThemeExport.import(bare))
    }

    @Test
    fun `invalid files are rejected`() {
        assertFailsWith<ThemeFormatException> { ThemeExport.import(byteArrayOf(1, 2, 3)) }
        assertFailsWith<ThemeFormatException> { ThemeExport.import(zip("other.json" to "{}".toByteArray())) }
        assertFailsWith<ThemeFormatException> { ThemeExport.import(zip(ThemeExport.THEME_JSON to "{nope".toByteArray())) }
        assertFailsWith<ThemeFormatException> {
            ThemeExport.read(zip(ThemeExport.THEME_JSON to spec.toJson().toByteArray(), ThemeExport.PACKS_JSON to "{}".toByteArray()))
        }
        assertFailsWith<ThemeFormatException> {
            ThemeExport.import(zip(ThemeExport.THEME_JSON to ByteArray(ThemeExport.MAX_ENTRY_BYTES + 10) { ' '.code.toByte() }))
        }
    }
}
