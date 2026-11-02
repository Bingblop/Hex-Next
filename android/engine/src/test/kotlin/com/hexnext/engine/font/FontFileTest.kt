package com.hexnext.engine.font

import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FontFileTest {
    private fun ByteArrayOutputStream.u8(v: Int) = write(v and 0xFF)
    private fun ByteArrayOutputStream.u16(v: Int) { u8(v ushr 8); u8(v) }
    private fun ByteArrayOutputStream.u32(v: Int) { u16(v ushr 16); u16(v) }
    private fun ByteArrayOutputStream.tag(t: String) = write(t.toByteArray(Charsets.US_ASCII))

    /** One `name` record: platform, encoding, language, name ID, text (encoded per platform). */
    private class NameRecord(val platform: Int, val language: Int, val nameId: Int, val text: String) {
        val bytes: ByteArray = if (platform == 1) text.toByteArray(Charsets.ISO_8859_1) else text.toByteArray(Charsets.UTF_16BE)
        val encoding: Int = if (platform == 1) 0 else 1
    }

    private fun nameTable(records: List<NameRecord>): ByteArray {
        val out = ByteArrayOutputStream()
        out.u16(0); out.u16(records.size); out.u16(6 + records.size * 12)
        var offset = 0
        for (r in records) {
            out.u16(r.platform); out.u16(r.encoding); out.u16(r.language); out.u16(r.nameId)
            out.u16(r.bytes.size); out.u16(offset)
            offset += r.bytes.size
        }
        for (r in records) out.write(r.bytes)
        return out.toByteArray()
    }

    /** An sfnt with [version] and the given tables (tag → payload), directory first, data after. */
    private fun sfnt(version: Int, tables: Map<String, ByteArray>, base: Int = 0): ByteArray {
        val out = ByteArrayOutputStream()
        out.u32(version); out.u16(tables.size); out.u16(0); out.u16(0); out.u16(0)
        var offset = base + 12 + tables.size * 16
        for ((tag, data) in tables) {
            out.tag(tag); out.u32(0); out.u32(offset); out.u32(data.size)
            offset += (data.size + 3) / 4 * 4
        }
        for ((_, data) in tables) {
            out.write(data)
            repeat((4 - data.size % 4) % 4) { out.u8(0) }
        }
        return out.toByteArray()
    }

    private val TRUETYPE = 0x00010000
    private val OTTO = 0x4F54544F
    private val TRUE = 0x74727565
    private val glyf = byteArrayOf(0, 0, 0, 0)
    private val cff = byteArrayOf(1, 0, 4, 1)

    private fun windows(nameId: Int, text: String, language: Int = 0x0409) = NameRecord(3, language, nameId, text)
    private fun mac(nameId: Int, text: String) = NameRecord(1, 0, nameId, text)

    private fun trueType(records: List<NameRecord>, extra: Map<String, ByteArray> = mapOf("glyf" to glyf)) =
        sfnt(TRUETYPE, mapOf("head" to ByteArray(54), "name" to nameTable(records)) + extra)

    @Test
    fun `reads Windows English family and subfamily from a TrueType font`() {
        val info = FontFile.info(trueType(listOf(windows(1, "Hex Sans"), windows(2, "Bold"))))
        assertEquals(FontFile.Info("Hex Sans", "Bold", isOpenTypeCff = false), info)
    }

    @Test
    fun `OTTO with CFF is reported as CFF`() {
        val bytes = sfnt(OTTO, mapOf("CFF " to cff, "name" to nameTable(listOf(windows(1, "Serif One"), windows(2, "Regular")))))
        val info = FontFile.info(bytes)
        assertTrue(info.isOpenTypeCff)
        assertEquals("Serif One", info.family)
    }

    @Test
    fun `Apple true header is accepted`() {
        val bytes = sfnt(TRUE, mapOf("glyf" to glyf, "name" to nameTable(listOf(mac(1, "Mac Face"), mac(2, "Light")))))
        assertEquals(FontFile.Info("Mac Face", "Light", isOpenTypeCff = false), FontFile.info(bytes))
    }

    @Test
    fun `prefers Windows en-US over other Windows languages and Macintosh`() {
        val records = listOf(
            mac(1, "Mac Name"),
            windows(1, "Deutsch Name", language = 0x0407),
            windows(1, "English Name"),
            mac(2, "Mac Style"),
            windows(2, "Fett", language = 0x0407),
        )
        val info = FontFile.info(trueType(records))
        assertEquals("English Name", info.family)
        assertEquals("Fett", info.subfamily) // no en-US subfamily: any Windows language beats Mac
    }

    @Test
    fun `falls back to Macintosh Roman when there are no Windows names`() {
        val info = FontFile.info(trueType(listOf(mac(1, "Only Mac"))))
        assertEquals("Only Mac", info.family)
        assertEquals("Regular", info.subfamily)
    }

    @Test
    fun `ttcf collection inspects the first font`() {
        val first = trueType(listOf(windows(1, "First"), windows(2, "Regular")))
        // A TTC header of 16 bytes precedes the first font; its table offsets are rebased.
        val rebased = sfnt(TRUETYPE, mapOf("head" to ByteArray(54), "name" to nameTable(listOf(windows(1, "First"), windows(2, "Regular"))), "glyf" to glyf), base = 16)
        assertEquals(first.size, rebased.size)
        val out = ByteArrayOutputStream()
        out.u32(0x74746366); out.u16(1); out.u16(0); out.u32(1); out.u32(16)
        out.write(rebased)
        assertEquals("First", FontFile.info(out.toByteArray()).family)
    }

    @Test
    fun `rejects non-sfnt bytes`() {
        assertFailsWith<FontFileException> { FontFile.info(ByteArray(0)) }
        assertFailsWith<FontFileException> { FontFile.info("not a font at all".toByteArray()) }
        assertFailsWith<FontFileException> { FontFile.info(sfnt(0x12345678, mapOf("glyf" to glyf))) }
    }

    @Test
    fun `rejects fonts without outlines or without a family name`() {
        val noOutlines = sfnt(TRUETYPE, mapOf("name" to nameTable(listOf(windows(1, "X")))))
        assertFailsWith<FontFileException> { FontFile.info(noOutlines) }
        val noName = sfnt(TRUETYPE, mapOf("glyf" to glyf))
        assertFailsWith<FontFileException> { FontFile.info(noName) }
        val onlySubfamily = trueType(listOf(windows(2, "Bold")))
        assertFailsWith<FontFileException> { FontFile.info(onlySubfamily) }
    }

    @Test
    fun `truncated and out-of-range tables throw FontFileException`() {
        val good = trueType(listOf(windows(1, "Hex Sans"), windows(2, "Regular")))
        assertFailsWith<FontFileException> { FontFile.info(good.copyOf(20)) }
        assertFailsWith<FontFileException> { FontFile.info(good.copyOf(good.size - 10)) }
        // Point the first table record far outside the file.
        val hostile = good.copyOf()
        for (i in 20..23) hostile[i] = 0x7F
        assertFailsWith<FontFileException> { FontFile.info(hostile) }
        // Name record whose string is outside the name table is skipped, so no family remains.
        val records = nameTable(listOf(windows(1, "Hex Sans")))
        records[16] = 0x7F // string offset high byte
        assertFailsWith<FontFileException> { FontFile.info(sfnt(TRUETYPE, mapOf("glyf" to glyf, "name" to records))) }
    }

    @Test
    fun `CFF2 counts as CFF outlines and glyf wins when both are present`() {
        val cff2 = sfnt(OTTO, mapOf("CFF2" to cff, "name" to nameTable(listOf(windows(1, "Var")))))
        assertTrue(FontFile.info(cff2).isOpenTypeCff)
        val both = sfnt(TRUETYPE, mapOf("glyf" to glyf, "CFF " to cff, "name" to nameTable(listOf(windows(1, "Both")))))
        assertFalse(FontFile.info(both).isOpenTypeCff)
    }
}
