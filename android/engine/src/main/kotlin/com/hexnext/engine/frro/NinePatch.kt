package com.hexnext.engine.frro

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/** Thrown for a malformed nine-patch marker border or a PNG that cannot carry the `npTc` chunk. */
public class NinePatchException(message: String) : Exception(message)

/**
 * Compiles nine-patch (`.9.png`) marker borders the way aapt2 does at build time
 * (`tools/aapt2/compile/NinePatch.cpp`), so a fabricated overlay can embed the result as a
 * plain PNG: Android's `BitmapFactory` turns any PNG carrying an `npTc` chunk (a serialised
 * `Res_png_9patch`, see `androidfw/ResourceTypes.h`) into a `NinePatch` when the resource is
 * loaded, from an APK or from an `frro:/` asset alike.
 *
 * The engine never decodes pixels itself: the caller strips the 1 px marker border, hands the
 * four edges in as a [Border], encodes the inner image as a PNG and gives [compile] a pixel
 * reader for the region colours. [embed] then inserts the chunk(s) before the first `IDAT`.
 *
 * Marker semantics (all coordinates are INNER image coordinates, i.e. source minus 1):
 *  - top row: runs of opaque black mark horizontal stretch segments `[start, end)`, two divs each;
 *  - left column: the same for vertical stretch segments;
 *  - bottom row: a black run is the horizontal padding (left = start, right = width - end),
 *    red runs touching a corner are the horizontal layout bounds (`npLb`);
 *  - right column: the same for vertical padding and layout bounds;
 *  - fully transparent (alpha 0) and opaque white pixels are not marks; any other colour is an error.
 * A side without padding ticks takes the first/last stretch segment as padding, like aapt2.
 */
public object NinePatch {
    /** `Res_png_9patch::NO_COLOR`: the region is not a single colour. */
    public const val NO_COLOR: Int = 0x00000001

    /** `Res_png_9patch::TRANSPARENT_COLOR`: every pixel of the region is fully transparent. */
    public const val TRANSPARENT_COLOR: Int = 0x00000000

    /** Inner images with more pixels than this get [NO_COLOR] for every region instead of being scanned. */
    public const val REGION_SCAN_PIXEL_LIMIT: Long = 4_000_000L

    /** Size of the fixed `Res_png_9patch` header that precedes the div and colour arrays. */
    public const val HEADER_SIZE: Int = 32

    /** Chunk type carrying the serialised `Res_png_9patch`. */
    public const val CHUNK_NINE_PATCH: String = "npTc"

    /**
     * Chunk type carrying the layout bounds (left, top, right, bottom as native/little-endian
     * int32). Unlike `npTc` this payload is not byte-swapped: aapt2 memcpy()s the host ints and
     * hwui's `NinePatchPeeker` reads them raw, so the device order (little-endian) is written.
     */
    public const val CHUNK_LAYOUT_BOUNDS: String = "npLb"

    private const val COLOR_BLACK = 0xFF000000.toInt()
    private const val COLOR_RED = 0xFFFF0000.toInt()
    private const val COLOR_WHITE = 0xFFFFFFFF.toInt()
    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    /**
     * The four marker edges of the SOURCE `.9.png`, each without its two corner pixels, as ARGB
     * ints: [top] and [bottom] run over x = 1..width-2 (so their size is the inner width), [left]
     * and [right] over y = 1..height-2 (the inner height).
     */
    public data class Border(
        val top: IntArray,
        val left: IntArray,
        val bottom: IntArray,
        val right: IntArray,
    )

    /**
     * Result of [compile].
     *
     * @property chunk `npTc` payload: the big-endian serialised `Res_png_9patch`.
     * @property layoutBounds `npLb` payload (4 native/little-endian int32: left, top, right, bottom,
     *   read raw by `NinePatchPeeker`), or null when the border has no red layout-bounds ticks.
     * @property xDivs stretch segment boundaries along x, `[start0, end0, start1, end1, …]`.
     * @property yDivs stretch segment boundaries along y.
     * @property padding left, right, top, bottom.
     * @property colors one entry per region, rows outer and columns inner.
     */
    public data class Compiled(
        val chunk: ByteArray,
        val layoutBounds: ByteArray?,
        val xDivs: IntArray,
        val yDivs: IntArray,
        val padding: IntArray,
        val colors: IntArray,
    )

    /** A half-open run `[start, end)` of same-coloured marker pixels, in inner coordinates. */
    private data class Run(val start: Int, val end: Int)

    /** Black runs ([primary]) and red runs ([secondary]) of one edge. */
    private class Marks(val primary: List<Run>, val secondary: List<Run>)

    /**
     * Compiles the marker [border] of a source image whose inner (border-stripped) size is
     * [innerWidth] × [innerHeight]. [pixelAt] reads inner-image ARGB pixels for the region
     * colours; it is not called when the inner image exceeds [REGION_SCAN_PIXEL_LIMIT] pixels
     * (every region is then [NO_COLOR], which only costs a little drawing speed).
     *
     * @throws NinePatchException for a malformed border, with an aapt2-like message.
     */
    public fun compile(border: Border, innerWidth: Int, innerHeight: Int, pixelAt: (x: Int, y: Int) -> Int): Compiled {
        if (innerWidth < 1 || innerHeight < 1) {
            throw NinePatchException("image must be at least 3x3 (a 1x1 image with a 1 px marker border)")
        }
        checkLength(border.top, innerWidth, "top")
        checkLength(border.bottom, innerWidth, "bottom")
        checkLength(border.left, innerHeight, "left")
        checkLength(border.right, innerHeight, "right")

        val top = scan(border.top, "top", "x")
        val left = scan(border.left, "left", "y")
        val bottom = scan(border.bottom, "bottom", "x")
        val right = scan(border.right, "right", "y")

        val xStretch = top.primary
        val yStretch = left.primary
        if (xStretch.isEmpty()) throw NinePatchException("no stretch region in the top border")
        if (yStretch.isEmpty()) throw NinePatchException("no stretch region in the left border")
        if (xStretch.size > 127 || yStretch.size > 127) throw NinePatchException("too many stretch regions (at most 127 per axis)")

        val xDivs = IntArray(xStretch.size * 2).also { d -> xStretch.forEachIndexed { i, r -> d[2 * i] = r.start; d[2 * i + 1] = r.end } }
        val yDivs = IntArray(yStretch.size * 2).also { d -> yStretch.forEachIndexed { i, r -> d[2 * i] = r.start; d[2 * i + 1] = r.end } }

        val (paddingLeft, paddingRight) = padding(bottom.primary, xStretch, innerWidth, "bottom")
        val (paddingTop, paddingBottom) = padding(right.primary, yStretch, innerHeight, "right")
        val padding = intArrayOf(paddingLeft, paddingRight, paddingTop, paddingBottom)

        val (boundLeft, boundRight) = layoutBounds(bottom.secondary, innerWidth, "bottom")
        val (boundTop, boundBottom) = layoutBounds(right.secondary, innerHeight, "right")
        val layoutBounds = if ((boundLeft or boundTop or boundRight or boundBottom) != 0) {
            // Not byte-swapped like npTc: NinePatchPeeker memcpy()s these into int32s on device.
            ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(boundLeft).putInt(boundTop).putInt(boundRight).putInt(boundBottom).array()
        } else {
            null
        }

        val xSegments = segments(xStretch, innerWidth)
        val ySegments = segments(yStretch, innerHeight)
        val regionCount = xSegments.size * ySegments.size
        if (regionCount > 255) throw NinePatchException("too many regions ($regionCount, at most 255)")
        val scanRegions = innerWidth.toLong() * innerHeight.toLong() <= REGION_SCAN_PIXEL_LIMIT
        val colors = IntArray(regionCount)
        var i = 0
        for (ys in ySegments) for (xs in xSegments) {
            colors[i++] = if (scanRegions) regionColor(xs, ys, pixelAt) else NO_COLOR
        }

        return Compiled(serialize(xDivs, yDivs, padding, colors), layoutBounds, xDivs, yDivs, padding, colors)
    }

    /**
     * Returns [png] with the `npTc` chunk (and `npLb` when [Compiled.layoutBounds] is present)
     * inserted right before the first `IDAT`, every other chunk kept as encoded.
     *
     * @throws NinePatchException when [png] is not a PNG, is truncated, has no `IDAT`, or
     *   already carries an `npTc` chunk.
     */
    public fun embed(png: ByteArray, compiled: Compiled): ByteArray {
        var idatOffset = -1
        forEachChunk(png) { type, offset, _ ->
            when (type) {
                CHUNK_NINE_PATCH -> throw NinePatchException("PNG already carries an $CHUNK_NINE_PATCH chunk")
                "IDAT" -> { idatOffset = offset; false }
                else -> true
            }
        }
        if (idatOffset < 0) throw NinePatchException("PNG has no IDAT chunk")
        val out = ByteArrayOutputStream(png.size + compiled.chunk.size + 12 + 28)
        out.write(png, 0, idatOffset)
        out.write(chunk(CHUNK_NINE_PATCH, compiled.chunk))
        compiled.layoutBounds?.let { out.write(chunk(CHUNK_LAYOUT_BOUNDS, it)) }
        out.write(png, idatOffset, png.size - idatOffset)
        return out.toByteArray()
    }

    /**
     * True when [png] carries an `npTc` chunk before its first `IDAT`/`IEND`, i.e. it is an
     * already compiled nine-patch. False for anything that is not a well-formed PNG.
     */
    public fun hasNinePatchChunk(png: ByteArray): Boolean {
        var found = false
        try {
            forEachChunk(png) { type, _, _ ->
                when (type) {
                    CHUNK_NINE_PATCH -> { found = true; false }
                    "IDAT", "IEND" -> false
                    else -> true
                }
            }
        } catch (e: NinePatchException) {
            return false
        }
        return found
    }

    // --- border scanning -------------------------------------------------------------------

    private fun checkLength(edge: IntArray, expected: Int, name: String) {
        if (edge.size != expected) {
            throw NinePatchException("$name border has ${edge.size} pixels, expected $expected")
        }
    }

    /**
     * Splits one edge into black runs and red runs like aapt2's `PopulateBounds`: a black
     * pixel ends a red run and vice versa, transparent and white pixels end both.
     */
    private fun scan(edge: IntArray, name: String, axis: String): Marks {
        val primary = mutableListOf<Run>()
        val secondary = mutableListOf<Run>()
        var primaryStart = -1
        var secondaryStart = -1
        for (i in edge.indices) {
            val color = edge[i]
            when {
                color == COLOR_BLACK -> {
                    if (primaryStart < 0) primaryStart = i
                    if (secondaryStart >= 0) { secondary += Run(secondaryStart, i); secondaryStart = -1 }
                }
                color == COLOR_RED -> {
                    if (secondaryStart < 0) secondaryStart = i
                    if (primaryStart >= 0) { primary += Run(primaryStart, i); primaryStart = -1 }
                }
                (color ushr 24) == 0 || color == COLOR_WHITE -> {
                    if (primaryStart >= 0) { primary += Run(primaryStart, i); primaryStart = -1 }
                    if (secondaryStart >= 0) { secondary += Run(secondaryStart, i); secondaryStart = -1 }
                }
                else -> throw NinePatchException(
                    "pixel at $axis=${i + 1} in the $name border is not black, red or transparent " +
                        "(0x${Integer.toHexString(color).uppercase().padStart(8, '0')})",
                )
            }
        }
        if (primaryStart >= 0) primary += Run(primaryStart, edge.size)
        if (secondaryStart >= 0) secondary += Run(secondaryStart, edge.size)
        return Marks(primary, secondary)
    }

    /** Padding (start side, end side) from the black run of a padding edge, or from the stretch regions. */
    private fun padding(ticks: List<Run>, stretch: List<Run>, length: Int, name: String): Pair<Int, Int> {
        if (ticks.size > 1) throw NinePatchException("too many padding sections in the $name border")
        val run = ticks.firstOrNull()
        return if (run != null) run.start to (length - run.end) else stretch.first().start to (length - stretch.last().end)
    }

    /** Layout bounds (start side, end side) from the red runs of a padding edge; they must touch a corner. */
    private fun layoutBounds(ticks: List<Run>, length: Int, name: String): Pair<Int, Int> {
        return when (ticks.size) {
            0 -> 0 to 0
            1 -> {
                val run = ticks[0]
                when {
                    run.start == 0 -> run.end to 0
                    run.end == length -> 0 to (length - run.start)
                    else -> throw NinePatchException("layout bounds in the $name border must touch a corner")
                }
            }
            2 -> {
                if (ticks[0].start != 0 || ticks[1].end != length) {
                    throw NinePatchException("layout bounds in the $name border must touch the corners")
                }
                ticks[0].end to (length - ticks[1].start)
            }
            else -> throw NinePatchException("too many layout bounds sections in the $name border")
        }
    }

    /** Fixed and stretch segments along one axis, in order, covering `[0, length)`. */
    private fun segments(stretch: List<Run>, length: Int): List<Run> {
        val out = mutableListOf<Run>()
        var next = 0
        for (run in stretch) {
            if (next < run.start) out += Run(next, run.start)
            out += run
            next = run.end
        }
        if (next < length) out += Run(next, length)
        return out
    }

    /** aapt2 `GetRegionColor`: transparent, a single ARGB, or [NO_COLOR]. */
    private fun regionColor(xs: Run, ys: Run, pixelAt: (Int, Int) -> Int): Int {
        val expected = pixelAt(xs.start, ys.start)
        val expectedTransparent = (expected ushr 24) == 0
        for (y in ys.start until ys.end) {
            for (x in xs.start until xs.end) {
                val color = pixelAt(x, y)
                if ((color ushr 24) == 0) {
                    if (!expectedTransparent) return NO_COLOR
                } else if (color != expected) {
                    return NO_COLOR
                }
            }
        }
        return if (expectedTransparent) TRANSPARENT_COLOR else expected
    }

    /** `Res_png_9patch::serialize` in device (big-endian) order. */
    private fun serialize(xDivs: IntArray, yDivs: IntArray, padding: IntArray, colors: IntArray): ByteArray {
        val xDivsOffset = HEADER_SIZE
        val yDivsOffset = xDivsOffset + 4 * xDivs.size
        val colorsOffset = yDivsOffset + 4 * yDivs.size
        val buf = ByteBuffer.allocate(colorsOffset + 4 * colors.size).order(ByteOrder.BIG_ENDIAN)
        buf.put(0) // wasDeserialized
        buf.put(xDivs.size.toByte()).put(yDivs.size.toByte()).put(colors.size.toByte())
        buf.putInt(xDivsOffset).putInt(yDivsOffset)
        buf.putInt(padding[0]).putInt(padding[1]).putInt(padding[2]).putInt(padding[3])
        buf.putInt(colorsOffset)
        xDivs.forEach { buf.putInt(it) }
        yDivs.forEach { buf.putInt(it) }
        colors.forEach { buf.putInt(it) }
        return buf.array()
    }

    // --- PNG chunks ------------------------------------------------------------------------

    /**
     * Walks the chunks of [png], calling [visit] with the chunk type, the offset of its length
     * field and its data length, until [visit] returns false or the data ends.
     */
    private fun forEachChunk(png: ByteArray, visit: (type: String, offset: Int, length: Int) -> Boolean) {
        if (png.size < PNG_SIGNATURE.size || !png.copyOf(PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE)) {
            throw NinePatchException("not a PNG (bad signature)")
        }
        var p = PNG_SIGNATURE.size
        while (p < png.size) {
            if (p + 8 > png.size) throw NinePatchException("truncated PNG chunk header at $p")
            val length = ByteBuffer.wrap(png, p, 4).order(ByteOrder.BIG_ENDIAN).int
            if (length < 0 || p + 12L + length > png.size) throw NinePatchException("truncated PNG chunk at $p")
            val type = String(png, p + 4, 4, Charsets.US_ASCII)
            if (!visit(type, p, length)) return
            p += 12 + length
        }
    }

    /** `[len][type][data][crc32(type + data)]`. */
    private fun chunk(type: String, data: ByteArray): ByteArray {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        require(typeBytes.size == 4) { "chunk type must be 4 ASCII bytes" }
        val crc = CRC32().apply { update(typeBytes); update(data) }.value.toInt()
        return ByteBuffer.allocate(12 + data.size).order(ByteOrder.BIG_ENDIAN)
            .putInt(data.size).put(typeBytes).put(data).putInt(crc).array()
    }
}
