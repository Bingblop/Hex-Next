package com.hexnext.engine.font

/** Thrown when bytes are not an sfnt font the installer can use (bad header, no outlines, no family name). */
public class FontFileException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Minimal sfnt (TrueType / OpenType) inspector: validates the container and reads the family and
 * subfamily names so an imported font can be labelled and installed. No glyph data is touched.
 *
 * Accepted headers: `0x00010000` (TrueType), `OTTO` (OpenType with CFF outlines), `true` (Apple
 * TrueType) and `ttcf` collections, of which the first font is inspected. The file must carry a
 * `glyf` or `CFF `/`CFF2` table and a `name` table with name ID 1 (family). Names are taken from
 * platform 3 (Windows, UTF-16BE) with language 0x0409 first, then any Windows language, then
 * platform 0 (Unicode), then platform 1 (Macintosh Roman, read as Latin-1).
 */
public object FontFile {
    private const val SFNT_TRUETYPE = 0x00010000
    private const val SFNT_OTTO = 0x4F54544F // 'OTTO'
    private const val SFNT_TRUE = 0x74727565 // 'true'
    private const val SFNT_TTCF = 0x74746366 // 'ttcf'
    private const val TAG_NAME = 0x6E616D65 // 'name'
    private const val TAG_GLYF = 0x676C7966 // 'glyf'
    private const val TAG_CFF = 0x43464620 // 'CFF '
    private const val TAG_CFF2 = 0x43464632 // 'CFF2'
    private const val NAME_FAMILY = 1
    private const val NAME_SUBFAMILY = 2
    private const val PLATFORM_UNICODE = 0
    private const val PLATFORM_MAC = 1
    private const val PLATFORM_WINDOWS = 3
    private const val LANG_EN_US = 0x0409
    private const val MAX_TABLES = 512

    /**
     * @property family name ID 1, e.g. "Inter".
     * @property subfamily name ID 2, e.g. "Bold"; "Regular" when the font has none.
     * @property isOpenTypeCff true when outlines are CFF (`OTTO`) rather than TrueType `glyf`.
     */
    public data class Info(val family: String, val subfamily: String, val isOpenTypeCff: Boolean)

    /** Inspects [bytes]; throws [FontFileException] when they are not a usable sfnt font. */
    public fun info(bytes: ByteArray): Info {
        try {
            return parse(bytes)
        } catch (e: IndexOutOfBoundsException) {
            throw FontFileException("font file is truncated", e)
        }
    }

    private fun parse(bytes: ByteArray): Info {
        if (bytes.size < 12) throw FontFileException("file too short for an sfnt header (${bytes.size} bytes)")
        var base = 0
        var version = u32(bytes, 0)
        if (version == SFNT_TTCF) {
            val numFonts = u32(bytes, 8)
            if (numFonts < 1) throw FontFileException("font collection is empty")
            base = u32(bytes, 12)
            if (base < 0 || base > bytes.size - 12) throw FontFileException("font collection offset $base is outside the file")
            version = u32(bytes, base)
        }
        if (version != SFNT_TRUETYPE && version != SFNT_OTTO && version != SFNT_TRUE) {
            throw FontFileException("not a TrueType or OpenType font (header 0x${Integer.toHexString(version)})")
        }
        val numTables = u16(bytes, base + 4)
        if (numTables == 0) throw FontFileException("font has no tables")
        if (numTables > MAX_TABLES) throw FontFileException("font lists $numTables tables")
        if (base + 12 + numTables * 16 > bytes.size) throw FontFileException("table directory truncated")

        var nameOffset = -1
        var nameLength = 0
        var hasGlyf = false
        var hasCff = false
        for (i in 0 until numTables) {
            val rec = base + 12 + i * 16
            val tag = u32(bytes, rec)
            val offset = u32(bytes, rec + 8)
            val length = u32(bytes, rec + 12)
            if (offset < 0 || length < 0 || offset > bytes.size || length > bytes.size - offset) {
                throw FontFileException("table ${tagName(tag)} points outside the file")
            }
            when (tag) {
                TAG_NAME -> { nameOffset = offset; nameLength = length }
                TAG_GLYF -> hasGlyf = true
                TAG_CFF, TAG_CFF2 -> hasCff = true
            }
        }
        if (!hasGlyf && !hasCff) throw FontFileException("font has neither glyf nor CFF outlines")
        if (nameOffset < 0) throw FontFileException("font has no name table")
        if (nameLength < 6) throw FontFileException("name table truncated")

        val count = u16(bytes, nameOffset + 2)
        val stringOffset = u16(bytes, nameOffset + 4)
        if (6 + count * 12 > nameLength) throw FontFileException("name table lists $count records in $nameLength bytes")
        val storage = nameOffset + stringOffset
        val candidates = ArrayList<Candidate>(count)
        for (i in 0 until count) {
            val rec = nameOffset + 6 + i * 12
            val platform = u16(bytes, rec)
            val language = u16(bytes, rec + 4)
            val nameId = u16(bytes, rec + 6)
            val length = u16(bytes, rec + 8)
            val offset = u16(bytes, rec + 10)
            if (nameId != NAME_FAMILY && nameId != NAME_SUBFAMILY) continue
            val start = storage + offset
            if (start + length > nameOffset + nameLength || start + length > bytes.size) continue
            val text = when (platform) {
                PLATFORM_WINDOWS, PLATFORM_UNICODE -> decodeUtf16Be(bytes, start, length)
                PLATFORM_MAC -> String(bytes, start, length, Charsets.ISO_8859_1)
                else -> continue
            }.trim()
            if (text.isEmpty()) continue
            val rank = when {
                platform == PLATFORM_WINDOWS && language == LANG_EN_US -> 0
                platform == PLATFORM_WINDOWS -> 1
                platform == PLATFORM_UNICODE -> 2
                else -> 3
            }
            candidates += Candidate(nameId, rank, text)
        }
        val family = candidates.filter { it.nameId == NAME_FAMILY }.minByOrNull { it.rank }?.text
            ?: throw FontFileException("font has no family name")
        val subfamily = candidates.filter { it.nameId == NAME_SUBFAMILY }.minByOrNull { it.rank }?.text ?: "Regular"
        return Info(family, subfamily, isOpenTypeCff = hasCff && !hasGlyf)
    }

    private class Candidate(val nameId: Int, val rank: Int, val text: String)

    private fun decodeUtf16Be(bytes: ByteArray, start: Int, length: Int): String {
        val chars = CharArray(length / 2) { i ->
            (((bytes[start + 2 * i].toInt() and 0xFF) shl 8) or (bytes[start + 2 * i + 1].toInt() and 0xFF)).toChar()
        }
        return String(chars)
    }

    private fun tagName(tag: Int): String = String(
        charArrayOf(((tag ushr 24) and 0xFF).toChar(), ((tag ushr 16) and 0xFF).toChar(), ((tag ushr 8) and 0xFF).toChar(), (tag and 0xFF).toChar()),
    )

    private fun u8(b: ByteArray, at: Int): Int = b[at].toInt() and 0xFF
    private fun u16(b: ByteArray, at: Int): Int = (u8(b, at) shl 8) or u8(b, at + 1)

    /** Big-endian u32 as a signed Int: a value ≥ 2^31 comes back negative, which every range check rejects. */
    private fun u32(b: ByteArray, at: Int): Int =
        (u8(b, at) shl 24) or (u8(b, at + 1) shl 16) or (u8(b, at + 2) shl 8) or u8(b, at + 3)
}
