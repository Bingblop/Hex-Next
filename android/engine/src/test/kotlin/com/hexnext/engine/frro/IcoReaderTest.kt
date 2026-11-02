package com.hexnext.engine.frro

import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class IcoReaderTest {
    private val red = 0xFFFF0000.toInt()
    private val green = 0xFF00FF00.toInt()
    private val blue = 0xFF0000FF.toInt()
    private val white = 0xFFFFFFFF.toInt()

    // ---- byte assembly helpers -------------------------------------------------------------

    private fun ByteArrayOutputStream.u8(v: Int) = write(v and 0xFF)
    private fun ByteArrayOutputStream.u16(v: Int) { u8(v); u8(v ushr 8) }
    private fun ByteArrayOutputStream.u32(v: Int) { u16(v); u16(v ushr 16) }

    /** One image payload plus the directory fields that describe it. */
    private class Payload(val width: Int, val height: Int, val bitCount: Int, val data: ByteArray)

    /** Assembles ICONDIR + entries + payloads; [type] 1 = icon, 2 = cursor. */
    private fun ico(vararg payloads: Payload, type: Int = 1, reserved: Int = 0): ByteArray {
        val out = ByteArrayOutputStream()
        out.u16(reserved); out.u16(type); out.u16(payloads.size)
        var offset = 6 + 16 * payloads.size
        for (p in payloads) {
            out.u8(if (p.width == 256) 0 else p.width)
            out.u8(if (p.height == 256) 0 else p.height)
            out.u8(0); out.u8(0)
            out.u16(1); out.u16(p.bitCount)
            out.u32(p.data.size); out.u32(offset)
            offset += p.data.size
        }
        for (p in payloads) out.write(p.data)
        return out.toByteArray()
    }

    private fun padTo4(out: ByteArrayOutputStream, rowBytes: Int) { repeat((4 - rowBytes % 4) % 4) { out.u8(0) } }

    /**
     * A DIB payload: BITMAPINFOHEADER (height doubled unless [doubleHeight] is false), [palette]
     * (ARGB, alpha ignored), bottom-up XOR rows from [rowsTopDown] (a function of x, y giving the
     * raw pixel value: BGRA int for 32 bpp, BGR for 24, index otherwise) and an optional AND mask.
     */
    private fun dib(
        width: Int, height: Int, bitCount: Int, pixel: (Int, Int) -> Int,
        mask: ((Int, Int) -> Boolean)? = null, palette: IntArray? = null, doubleHeight: Boolean = true,
        compression: Int = 0, colorsUsed: Int = 0,
    ): Payload {
        val out = ByteArrayOutputStream()
        out.u32(40); out.u32(width); out.u32(if (doubleHeight) height * 2 else height)
        out.u16(1); out.u16(bitCount); out.u32(compression); out.u32(0)
        out.u32(0); out.u32(0); out.u32(colorsUsed); out.u32(0)
        palette?.forEach { c -> out.u8(c); out.u8(c ushr 8); out.u8(c ushr 16); out.u8(0) }
        for (y in height - 1 downTo 0) {
            when (bitCount) {
                32 -> { for (x in 0 until width) out.u32(pixel(x, y)); }
                24 -> { for (x in 0 until width) { val p = pixel(x, y); out.u8(p); out.u8(p ushr 8); out.u8(p ushr 16) }; padTo4(out, width * 3) }
                8 -> { for (x in 0 until width) out.u8(pixel(x, y)); padTo4(out, width) }
                4 -> {
                    var x = 0
                    while (x < width) { val hi = pixel(x, y); val lo = if (x + 1 < width) pixel(x + 1, y) else 0; out.u8((hi shl 4) or lo); x += 2 }
                    padTo4(out, (width + 1) / 2)
                }
                1 -> writeBits(out, width) { x -> pixel(x, y) == 1 }
            }
        }
        if (mask != null) for (y in height - 1 downTo 0) writeBits(out, width) { x -> mask(x, y) }
        return Payload(width, height, bitCount, out.toByteArray())
    }

    private fun writeBits(out: ByteArrayOutputStream, width: Int, bit: (Int) -> Boolean) {
        var x = 0
        var bytes = 0
        while (x < width) {
            var b = 0
            for (i in 0 until 8) if (x + i < width && bit(x + i)) b = b or (0x80 ushr i)
            out.u8(b); bytes++; x += 8
        }
        padTo4(out, bytes)
    }

    /** The 32-bpp DIB pixel for an ARGB value: an ARGB int written little-endian is the B, G, R, A byte order. */
    private fun bgra(argb: Int): Int = argb

    /** A PNG stream consisting of signature + IHDR only (enough for the reader; decoding is the caller's job). */
    private fun png(width: Int, height: Int, colorType: Int = 6, depth: Int = 8): Payload {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        fun be32(v: Int) { out.u8(v ushr 24); out.u8(v ushr 16); out.u8(v ushr 8); out.u8(v) }
        be32(13); out.write("IHDR".toByteArray(Charsets.US_ASCII))
        be32(width); be32(height); out.u8(depth); out.u8(colorType); out.u8(0); out.u8(0); out.u8(0)
        be32(0) // crc, not verified
        return Payload(width, height, 32, out.toByteArray())
    }

    private fun dibOf(entry: IcoReader.Entry): IcoReader.Image = assertIs<IcoReader.Entry.Dib>(entry).image

    // ---- tests -----------------------------------------------------------------------------

    @Test
    fun `32 bpp with real alpha keeps alpha and ignores the AND mask`() {
        val pixels = intArrayOf(red, 0x8000FF00.toInt(), blue, 0x00000000)
        val bytes = ico(dib(2, 2, 32, { x, y -> bgra(pixels[y * 2 + x]) }, mask = { _, _ -> true }))
        val entries = IcoReader.read(bytes)
        assertEquals(1, entries.size)
        val image = dibOf(entries[0])
        assertEquals(2, image.width); assertEquals(2, image.height)
        assertContentEquals(pixels, image.argb)
        assertEquals(32, entries[0].bitCount)
    }

    @Test
    fun `32 bpp with all-zero alpha is opaque where the AND mask is clear`() {
        val bytes = ico(dib(2, 1, 32, { x, _ -> bgra(if (x == 0) red else green) and 0x00FFFFFF }, mask = { x, _ -> x == 1 }))
        val image = dibOf(IcoReader.read(bytes).single())
        assertEquals(red, image.argb[0])
        assertEquals(green and 0x00FFFFFF, image.argb[1])
    }

    @Test
    fun `24 bpp rows are bottom-up, padded and masked`() {
        // 3×2: top row red green blue, bottom row white ×3 with the middle pixel masked out.
        val top = intArrayOf(red, green, blue)
        val bytes = ico(dib(3, 2, 24, { x, y -> if (y == 0) top[x] and 0xFFFFFF else 0xFFFFFF }, mask = { x, y -> y == 1 && x == 1 }))
        val image = dibOf(IcoReader.read(bytes).single())
        assertContentEquals(intArrayOf(red, green, blue, white, white and 0x00FFFFFF, white), image.argb)
    }

    @Test
    fun `8 bpp uses the palette and honours biClrUsed`() {
        val palette = intArrayOf(red, green, blue)
        val bytes = ico(dib(2, 2, 8, { x, y -> (y * 2 + x) % 3 }, palette = palette, colorsUsed = 3))
        val image = dibOf(IcoReader.read(bytes).single())
        assertContentEquals(intArrayOf(red, green, blue, red), image.argb)
    }

    @Test
    fun `4 bpp packs two pixels per byte`() {
        val palette = IntArray(16) { i -> (0xFF shl 24) or (i * 0x111111) }
        val bytes = ico(dib(3, 1, 4, { x, _ -> x + 1 }, palette = palette))
        val image = dibOf(IcoReader.read(bytes).single())
        assertContentEquals(intArrayOf(palette[1], palette[2], palette[3]), image.argb)
    }

    @Test
    fun `1 bpp monochrome icon with AND mask`() {
        val palette = intArrayOf(0xFF000000.toInt(), white)
        // 9 pixels wide so the row needs two bytes plus padding; pixel 8 is white, pixel 0 masked.
        val bytes = ico(dib(9, 1, 1, { x, _ -> if (x == 8) 1 else 0 }, mask = { x, _ -> x == 0 }, palette = palette))
        val image = dibOf(IcoReader.read(bytes).single())
        assertEquals(9, image.width)
        assertEquals(0, image.argb[0] ushr 24)
        assertEquals(0xFF000000.toInt(), image.argb[1])
        assertEquals(white, image.argb[8])
    }

    @Test
    fun `undoubled height is tolerated when it matches the directory`() {
        val bytes = ico(dib(2, 2, 32, { _, _ -> bgra(red) }, doubleHeight = false))
        val image = dibOf(IcoReader.read(bytes).single())
        assertEquals(2, image.height)
    }

    @Test
    fun `PNG entries are returned raw with IHDR dimensions`() {
        val bytes = ico(png(48, 48, colorType = 6))
        val entry = assertIs<IcoReader.Entry.Png>(IcoReader.read(bytes).single())
        assertEquals(48, entry.width); assertEquals(48, entry.height); assertEquals(32, entry.bitCount)
        assertEquals(0x89.toByte(), entry.png[0])
        assertEquals("IHDR", String(entry.png, 12, 4, Charsets.US_ASCII))
    }

    @Test
    fun `best picks largest area then depth then PNG`() {
        val small = dib(2, 2, 32, { _, _ -> bgra(red) })
        val big8 = dib(4, 4, 8, { _, _ -> 0 }, palette = intArrayOf(red), colorsUsed = 1)
        val big32 = dib(4, 4, 32, { _, _ -> bgra(blue) })
        val bigPng = png(4, 4)
        assertEquals(32, IcoReader.best(ico(small, big8, big32)).bitCount)
        assertEquals(4, IcoReader.best(ico(small, big8)).width)
        assertIs<IcoReader.Entry.Png>(IcoReader.best(ico(big32, bigPng)))
        assertIs<IcoReader.Entry.Dib>(IcoReader.best(ico(small, png(2, 2), big32)))
    }

    @Test
    fun `cursor files are accepted`() {
        val bytes = ico(dib(2, 2, 32, { _, _ -> bgra(red) }), type = 2)
        assertEquals(2, dibOf(IcoReader.read(bytes).single()).width)
    }

    @Test
    fun `256 is encoded as zero in the directory`() {
        val bytes = ico(png(256, 256))
        val entry = IcoReader.read(bytes).single()
        assertEquals(256, entry.width)
        assertEquals(0, bytes[6].toInt())
    }

    @Test
    fun `malformed input throws IcoException`() {
        val good = dib(2, 2, 32, { _, _ -> bgra(red) })
        assertFailsWith<IcoException> { IcoReader.read(ByteArray(3)) }
        assertFailsWith<IcoException> { IcoReader.read(ico(good, reserved = 7)) }
        assertFailsWith<IcoException> { IcoReader.read(ico(good, type = 3)) }
        assertFailsWith<IcoException> { IcoReader.read(ico()) }
        // Entry pointing past the end of the file.
        assertFailsWith<IcoException> { IcoReader.read(ico(good).copyOf(30)) }
        // Truncated pixel data.
        assertFailsWith<IcoException> { IcoReader.read(ico(good).let { it.copyOf(it.size - 8) }) }
        // Unsupported depth and compression.
        assertFailsWith<IcoException> { IcoReader.read(ico(dib(2, 2, 16, { _, _ -> 0 }))) }
        assertFailsWith<IcoException> { IcoReader.read(ico(dib(2, 2, 32, { _, _ -> 0 }, compression = 3))) }
        // Too large and too many entries.
        assertFailsWith<IcoException> { IcoReader.read(ico(png(300, 10))) }
        val many = Array(IcoReader.MAX_ENTRIES + 1) { good }
        assertFailsWith<IcoException> { IcoReader.read(ico(*many)) }
        assertFailsWith<IcoException> { IcoReader.best(emptyList()) }
    }

    @Test
    fun `hostile offsets never escape the array`() {
        val bytes = ico(dib(2, 2, 32, { _, _ -> bgra(red) }))
        // Overwrite the image offset with 0xFFFFFFFF and the size with a huge value.
        for (i in 14..21) bytes[i] = 0xFF.toByte()
        val e = assertFailsWith<IcoException> { IcoReader.read(bytes) }
        assertTrue(e.message!!.contains("outside"), e.message)
    }
}
