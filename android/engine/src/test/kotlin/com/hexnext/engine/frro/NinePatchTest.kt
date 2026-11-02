package com.hexnext.engine.frro

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NinePatchTest {
    private val black = 0xFF000000.toInt()
    private val red = 0xFFFF0000.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private val clear = 0
    private val fill = 0xFF123456.toInt()

    /** An all-transparent 5-pixel edge with [marks] set at the given inner indices. */
    private fun edge(size: Int = 5, vararg marks: Pair<Int, Int>): IntArray =
        IntArray(size) { clear }.also { e -> marks.forEach { (i, c) -> e[i] = c } }

    /** The 5×5-inner sample: one x run [1,4) in the top row, one y run [2,3) in the left column. */
    private fun sampleBorder(bottom: IntArray = edge(), right: IntArray = edge()) = NinePatch.Border(
        top = edge(5, 1 to black, 2 to black, 3 to black),
        left = edge(5, 2 to black),
        bottom = bottom,
        right = right,
    )

    /**
     * Inner pixels: region (x 0, y 0..1) uniform red, region (x 1..3, y 0..1) transparent with
     * varying RGB, region (x 4, y 0..1) two different colours, everything else [fill].
     */
    private fun samplePixel(x: Int, y: Int): Int = when {
        y >= 2 -> fill
        x == 0 -> red
        x in 1..3 -> (x shl 8) or (y shl 16)
        else -> if (y == 0) 0xFF00FF00.toInt() else 0xFF0000FF.toInt()
    }

    private fun be32(bytes: ByteArray, offset: Int) = ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.BIG_ENDIAN).int
    private fun le32(bytes: ByteArray, offset: Int) = ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val t = type.toByteArray(Charsets.US_ASCII)
        val crc = CRC32().apply { update(t); update(data) }.value.toInt()
        return ByteBuffer.allocate(12 + data.size).putInt(data.size).put(t).put(data).putInt(crc).array()
    }

    /** A minimal valid PNG container: signature, IHDR (5×5 RGBA), an arbitrary IDAT and IEND. */
    private fun tinyPng(idat: ByteArray = byteArrayOf(1, 2, 3, 4, 5)): ByteArray {
        val ihdr = ByteBuffer.allocate(13).putInt(5).putInt(5).put(8).put(6).put(0).put(0).put(0).array()
        return byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) +
            chunk("IHDR", ihdr) + chunk("IDAT", idat) + chunk("IEND", ByteArray(0))
    }

    private class Chunk(val type: String, val data: ByteArray, val crcOk: Boolean)

    /** Hand-written chunk walker, independent of the implementation under test. */
    private fun walk(png: ByteArray): List<Chunk> {
        val out = mutableListOf<Chunk>()
        var p = 8
        while (p < png.size) {
            val len = be32(png, p)
            val type = String(png, p + 4, 4, Charsets.US_ASCII)
            val data = png.copyOfRange(p + 8, p + 8 + len)
            val crc = CRC32().apply { update(png, p + 4, 4 + len) }.value.toInt()
            out += Chunk(type, data, crc == be32(png, p + 8 + len))
            p += 12 + len
        }
        assertEquals(png.size, p, "chunks end exactly at the end of the file")
        return out
    }

    @Test
    fun `single stretch run per axis with default padding and region colours`() {
        val c = NinePatch.compile(sampleBorder(), 5, 5, ::samplePixel)
        assertContentEquals(intArrayOf(1, 4), c.xDivs)
        assertContentEquals(intArrayOf(2, 3), c.yDivs)
        // no padding ticks: padding is copied from the stretch regions
        assertContentEquals(intArrayOf(1, 1, 2, 2), c.padding)
        assertNull(c.layoutBounds)
        // 3 × 3 regions, rows outer: [0,2) × {[0,1), [1,4), [4,5)} then [2,3) × … then [3,5) × …
        assertEquals(9, c.colors.size)
        assertEquals(red, c.colors[0])
        assertEquals(NinePatch.TRANSPARENT_COLOR, c.colors[1])
        assertEquals(NinePatch.NO_COLOR, c.colors[2])
        assertContentEquals(IntArray(6) { fill }, c.colors.copyOfRange(3, 9))
    }

    @Test
    fun `serialised header follows the Res_png_9patch layout`() {
        val c = NinePatch.compile(sampleBorder(), 5, 5, ::samplePixel)
        val b = c.chunk
        assertEquals(32 + 4 * 2 + 4 * 2 + 4 * 9, b.size)
        assertEquals(0, b[0].toInt()) // wasDeserialized
        assertEquals(2, b[1].toInt()) // numXDivs
        assertEquals(2, b[2].toInt()) // numYDivs
        assertEquals(9, b[3].toInt()) // numColors
        assertEquals(32, be32(b, 4)) // xDivsOffset
        assertEquals(40, be32(b, 8)) // yDivsOffset = 32 + 4 * numXDivs
        assertEquals(1, be32(b, 12)) // paddingLeft
        assertEquals(1, be32(b, 16)) // paddingRight
        assertEquals(2, be32(b, 20)) // paddingTop
        assertEquals(2, be32(b, 24)) // paddingBottom
        assertEquals(48, be32(b, 28)) // colorsOffset = yDivsOffset + 4 * numYDivs
        assertEquals(1, be32(b, 32))
        assertEquals(4, be32(b, 36))
        assertEquals(2, be32(b, 40))
        assertEquals(3, be32(b, 44))
        assertEquals(red, be32(b, 48))
        assertEquals(NinePatch.TRANSPARENT_COLOR, be32(b, 52))
        assertEquals(NinePatch.NO_COLOR, be32(b, 56))
        assertEquals(fill, be32(b, 60 + 4 * 5))
        // big-endian: the high byte of the offset comes first
        assertContentEquals(byteArrayOf(0, 0, 0, 32), b.copyOfRange(4, 8))
    }

    @Test
    fun `explicit padding ticks`() {
        val border = sampleBorder(
            bottom = edge(5, 0 to black, 1 to black),
            right = edge(5, 4 to black),
        )
        val c = NinePatch.compile(border, 5, 5, ::samplePixel)
        assertContentEquals(intArrayOf(0, 3, 4, 0), c.padding)
    }

    @Test
    fun `two runs give four divs and more regions`() {
        val border = NinePatch.Border(
            top = edge(5, 0 to black, 2 to black, 3 to black),
            left = edge(5, 2 to black),
            bottom = edge(),
            right = edge(),
        )
        val c = NinePatch.compile(border, 5, 5) { _, _ -> fill }
        assertContentEquals(intArrayOf(0, 1, 2, 4), c.xDivs)
        // segments: [0,1) stretch, [1,2) fixed, [2,4) stretch, [4,5) fixed → 4 × 3 regions
        assertEquals(12, c.colors.size)
        assertEquals(4, c.chunk[1].toInt())
        assertEquals(12, c.chunk[3].toInt())
        assertEquals(32 + 16, be32(c.chunk, 8))
        // default padding from first/last stretch region
        assertContentEquals(intArrayOf(0, 1, 2, 2), c.padding)
    }

    @Test
    fun `red ticks in the padding edges become layout bounds`() {
        val border = sampleBorder(
            bottom = edge(5, 0 to red, 2 to black, 4 to red),
            right = edge(5, 3 to red, 4 to red),
        )
        val c = NinePatch.compile(border, 5, 5, ::samplePixel)
        val lb = assertNotNull(c.layoutBounds)
        assertEquals(16, lb.size)
        // npLb is read raw (little-endian) by NinePatchPeeker, unlike the byte-swapped npTc
        assertEquals(1, le32(lb, 0)) // left
        assertEquals(0, le32(lb, 4)) // top
        assertEquals(1, le32(lb, 8)) // right
        assertEquals(2, le32(lb, 12)) // bottom
        // the black run between the red ticks is still the padding
        assertContentEquals(intArrayOf(2, 2, 2, 2), c.padding)
        // a lone red run that touches no corner is an error
        assertFailsWith<NinePatchException> {
            NinePatch.compile(sampleBorder(bottom = edge(5, 2 to red)), 5, 5, ::samplePixel)
        }
    }

    @Test
    fun `white and transparent pixels are not marks, anything else is an error`() {
        val border = NinePatch.Border(
            top = edge(5, 0 to white, 1 to black, 2 to 0x00FFFFFF, 3 to black),
            left = edge(5, 2 to black),
            bottom = edge(),
            right = edge(),
        )
        assertContentEquals(intArrayOf(1, 2, 3, 4), NinePatch.compile(border, 5, 5) { _, _ -> fill }.xDivs)

        val bad = sampleBorder(right = edge(5, 1 to 0xFF00FF00.toInt()))
        val e = assertFailsWith<NinePatchException> { NinePatch.compile(bad, 5, 5, ::samplePixel) }
        assertTrue(e.message!!.contains("right border"), e.message)
        assertTrue(e.message!!.contains("y=2"), e.message)
        val badTop = NinePatch.Border(edge(5, 1 to black, 3 to 0x80000000.toInt()), edge(5, 2 to black), edge(), edge())
        val e2 = assertFailsWith<NinePatchException> { NinePatch.compile(badTop, 5, 5, ::samplePixel) }
        assertTrue(e2.message!!.contains("x=4 in the top border"), e2.message)
    }

    @Test
    fun `missing stretch regions, extra padding sections and bad sizes are errors`() {
        val noX = NinePatch.Border(edge(), edge(5, 2 to black), edge(), edge())
        assertEquals("no stretch region in the top border", assertFailsWith<NinePatchException> {
            NinePatch.compile(noX, 5, 5, ::samplePixel)
        }.message)
        val noY = NinePatch.Border(edge(5, 2 to black), edge(), edge(), edge())
        assertEquals("no stretch region in the left border", assertFailsWith<NinePatchException> {
            NinePatch.compile(noY, 5, 5, ::samplePixel)
        }.message)
        assertFailsWith<NinePatchException> {
            NinePatch.compile(sampleBorder(bottom = edge(5, 0 to black, 3 to black)), 5, 5, ::samplePixel)
        }
        assertFailsWith<NinePatchException> { NinePatch.compile(sampleBorder(), 4, 5, ::samplePixel) }
        assertFailsWith<NinePatchException> { NinePatch.compile(sampleBorder(), 5, 0, ::samplePixel) }
    }

    @Test
    fun `huge images skip the colour scan`() {
        var reads = 0
        val w = 2001
        val h = 2000
        val border = NinePatch.Border(
            top = IntArray(w).also { it[1] = black },
            left = IntArray(h).also { it[1] = black },
            bottom = IntArray(w),
            right = IntArray(h),
        )
        val c = NinePatch.compile(border, w, h) { _, _ -> reads++; fill }
        assertEquals(0, reads)
        assertContentEquals(IntArray(9) { NinePatch.NO_COLOR }, c.colors)
        // one pixel fewer and the regions are scanned
        val small = NinePatch.compile(border.copy(top = IntArray(w - 1).also { it[1] = black }, bottom = IntArray(w - 1)), w - 1, h) { _, _ -> reads++; fill }
        assertTrue(reads > 0)
        assertContentEquals(IntArray(9) { fill }, small.colors)
    }

    @Test
    fun `embed inserts the chunks right before IDAT with correct CRCs`() {
        val png = tinyPng()
        assertFalse(NinePatch.hasNinePatchChunk(png))
        val plain = NinePatch.compile(sampleBorder(), 5, 5, ::samplePixel)
        val out = NinePatch.embed(png, plain)
        val chunks = walk(out)
        assertEquals(listOf("IHDR", "npTc", "IDAT", "IEND"), chunks.map { it.type })
        assertTrue(chunks.all { it.crcOk })
        assertContentEquals(plain.chunk, chunks[1].data)
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5), chunks[2].data)
        assertTrue(NinePatch.hasNinePatchChunk(out))
        // everything before and after the insertion point is byte-identical
        val idat = 8 + 12 + 13
        assertContentEquals(png.copyOfRange(0, idat), out.copyOfRange(0, idat))
        assertContentEquals(png.copyOfRange(idat, png.size), out.copyOfRange(out.size - (png.size - idat), out.size))

        val withBounds = NinePatch.compile(sampleBorder(bottom = edge(5, 0 to red)), 5, 5, ::samplePixel)
        val out2 = walk(NinePatch.embed(png, withBounds))
        assertEquals(listOf("IHDR", "npTc", "npLb", "IDAT", "IEND"), out2.map { it.type })
        assertTrue(out2.all { it.crcOk })
        assertContentEquals(withBounds.layoutBounds, out2[2].data)

        assertFailsWith<NinePatchException> { NinePatch.embed(out, plain) } // already compiled
        assertFailsWith<NinePatchException> { NinePatch.embed("not a png at all".toByteArray(), plain) }
        assertFailsWith<NinePatchException> { NinePatch.embed(png.copyOf(20), plain) } // truncated
        assertFalse(NinePatch.hasNinePatchChunk(ByteArray(3)))
        assertFalse(NinePatch.hasNinePatchChunk(out.copyOf(20)))
    }
}
