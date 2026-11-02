package com.hexnext.engine.frro

/** Thrown for a malformed, truncated or unsupported `.ico` / `.cur` file. */
public class IcoException(message: String) : Exception(message)

/**
 * Reads Windows icon files (`.ico`, and `.cur` cursors, which share the container) without any
 * platform image library: the directory and every DIB (BMP-style) entry are decoded here into
 * ARGB pixels; PNG-compressed entries are returned as their raw PNG bytes for the caller to decode
 * (the engine is pure JVM and carries no PNG decoder).
 *
 * Layout (all little-endian): `ICONDIR` = reserved u16 (0), type u16 (1 icon, 2 cursor), count u16;
 * then `count` × `ICONDIRENTRY` (16 bytes: width u8, height u8 — 0 means 256 —, colour count u8,
 * reserved u8, planes/hotspot-x u16, bit count/hotspot-y u16, bytes in resource u32, image offset
 * u32). A DIB entry is a `BITMAPINFOHEADER` whose height counts the XOR (colour) bitmap AND the
 * 1-bpp AND (transparency) mask, followed by an optional palette, the XOR rows and the AND rows,
 * every row padded to 4 bytes and stored bottom-up.
 */
public object IcoReader {
    /** Largest icon side accepted; the format itself cannot express more. */
    public const val MAX_DIMENSION: Int = 256

    /** Most directory entries accepted; real icons carry a handful. */
    public const val MAX_ENTRIES: Int = 64

    private const val TYPE_ICON = 1
    private const val TYPE_CURSOR = 2
    private const val DIR_SIZE = 6
    private const val ENTRY_SIZE = 16
    private const val INFO_HEADER_SIZE = 40
    private const val BI_RGB = 0

    /** Decoded pixels, row-major, top-down, non-premultiplied ARGB. */
    public data class Image(val width: Int, val height: Int, val argb: IntArray) {
        init {
            require(argb.size == width * height) { "argb size ${argb.size} != $width×$height" }
        }
    }

    /** One directory entry. [bitCount] is the colour depth of the stored image (PNG: from its IHDR). */
    public sealed class Entry {
        public abstract val width: Int
        public abstract val height: Int
        public abstract val bitCount: Int

        /** A PNG-compressed entry: [png] is the complete PNG stream (signature to IEND). */
        public data class Png(
            val png: ByteArray,
            override val width: Int,
            override val height: Int,
            override val bitCount: Int,
        ) : Entry()

        /** A DIB entry, already decoded. */
        public data class Dib(val image: Image, override val bitCount: Int) : Entry() {
            override val width: Int get() = image.width
            override val height: Int get() = image.height
        }
    }

    /** Parses every entry of an icon file; throws [IcoException] on malformed input. */
    public fun read(bytes: ByteArray): List<Entry> {
        if (bytes.size < DIR_SIZE) throw IcoException("file too short for an icon directory (${bytes.size} bytes)")
        val reserved = u16(bytes, 0)
        val type = u16(bytes, 2)
        val count = u16(bytes, 4)
        if (reserved != 0) throw IcoException("reserved field is $reserved, expected 0")
        if (type != TYPE_ICON && type != TYPE_CURSOR) throw IcoException("unknown resource type $type")
        if (count == 0) throw IcoException("icon directory is empty")
        if (count > MAX_ENTRIES) throw IcoException("icon directory lists $count entries, limit is $MAX_ENTRIES")
        if (bytes.size < DIR_SIZE + count * ENTRY_SIZE) throw IcoException("file too short for $count directory entries")

        return List(count) { index ->
            val at = DIR_SIZE + index * ENTRY_SIZE
            val dirWidth = u8(bytes, at).let { if (it == 0) 256 else it }
            val dirHeight = u8(bytes, at + 1).let { if (it == 0) 256 else it }
            val dirBitCount = if (type == TYPE_ICON) u16(bytes, at + 6) else 0
            val size = u32(bytes, at + 8)
            val offset = u32(bytes, at + 12)
            if (size < 0 || offset < 0 || size > bytes.size || offset > bytes.size - size) {
                throw IcoException("entry $index points outside the file (offset $offset, size $size, file ${bytes.size})")
            }
            if (size < 8) throw IcoException("entry $index is too small ($size bytes)")
            if (isPng(bytes, offset)) {
                readPng(bytes, offset, size, dirWidth, dirHeight, dirBitCount, index)
            } else {
                readDib(bytes, offset, size, dirHeight, index)
            }
        }
    }

    /** [read] followed by [best]. */
    public fun best(bytes: ByteArray): Entry = best(read(bytes))

    /** The entry with the largest area, then the highest bit depth; a PNG wins a full tie. */
    public fun best(entries: List<Entry>): Entry {
        if (entries.isEmpty()) throw IcoException("no entries to choose from")
        return entries.maxWith(
            compareBy<Entry> { it.width.toLong() * it.height }
                .thenBy { it.bitCount }
                .thenBy { if (it is Entry.Png) 1 else 0 },
        )
    }

    private fun isPng(bytes: ByteArray, offset: Int): Boolean =
        bytes[offset] == 0x89.toByte() && bytes[offset + 1] == 0x50.toByte() &&
            bytes[offset + 2] == 0x4E.toByte() && bytes[offset + 3] == 0x47.toByte()

    private fun readPng(
        bytes: ByteArray, offset: Int, size: Int, dirWidth: Int, dirHeight: Int, dirBitCount: Int, index: Int,
    ): Entry.Png {
        val png = bytes.copyOfRange(offset, offset + size)
        var width = dirWidth
        var height = dirHeight
        var bitCount = if (dirBitCount > 0) dirBitCount else 32
        // Signature (8) + IHDR length/type (8) + width/height (8) + depth/colour type (2).
        if (png.size >= 26 && png[12] == 'I'.code.toByte() && png[13] == 'H'.code.toByte() &&
            png[14] == 'D'.code.toByte() && png[15] == 'R'.code.toByte()
        ) {
            width = be32(png, 16)
            height = be32(png, 20)
            val depth = png[24].toInt() and 0xFF
            val channels = when (png[25].toInt() and 0xFF) {
                0 -> 1
                2 -> 3
                3 -> 1
                4 -> 2
                6 -> 4
                else -> throw IcoException("entry $index: PNG has unknown colour type ${png[25].toInt() and 0xFF}")
            }
            bitCount = depth * channels
        }
        checkDimensions(width, height, index)
        return Entry.Png(png, width, height, bitCount)
    }

    private fun readDib(bytes: ByteArray, offset: Int, size: Int, dirHeight: Int, index: Int): Entry.Dib {
        if (size < INFO_HEADER_SIZE) throw IcoException("entry $index: DIB header truncated ($size bytes)")
        val headerSize = u32(bytes, offset)
        if (headerSize < INFO_HEADER_SIZE || headerSize > size) {
            throw IcoException("entry $index: unsupported DIB header size $headerSize")
        }
        val width = i32(bytes, offset + 4)
        val rawHeight = i32(bytes, offset + 8)
        val bitCount = u16(bytes, offset + 14)
        val compression = u32(bytes, offset + 16)
        val colorsUsed = u32(bytes, offset + 32)
        if (compression != BI_RGB) throw IcoException("entry $index: compressed DIB (biCompression=$compression) is not supported")
        if (bitCount !in setOf(1, 4, 8, 24, 32)) throw IcoException("entry $index: $bitCount-bpp DIB is not supported")
        if (rawHeight == 0 || rawHeight == Int.MIN_VALUE) throw IcoException("entry $index: DIB height is $rawHeight")
        val topDown = rawHeight < 0
        val absHeight = if (topDown) -rawHeight else rawHeight
        // Icon DIBs store XOR + AND rows in one height; a writer that forgot to double it is tolerated.
        val height = if (absHeight == 2 * dirHeight || (absHeight % 2 == 0 && absHeight != dirHeight)) absHeight / 2 else absHeight
        checkDimensions(width, height, index)

        val end = offset + size
        var cursor = offset + headerSize
        val palette: IntArray? = if (bitCount <= 8) {
            val declared = if (colorsUsed in 1..(1 shl bitCount)) colorsUsed else (1 shl bitCount)
            if (cursor + declared * 4 > end) throw IcoException("entry $index: palette truncated")
            IntArray(1 shl bitCount) { i ->
                if (i < declared) {
                    val p = cursor + i * 4
                    (0xFF shl 24) or ((bytes[p + 2].toInt() and 0xFF) shl 16) or
                        ((bytes[p + 1].toInt() and 0xFF) shl 8) or (bytes[p].toInt() and 0xFF)
                } else {
                    0xFF000000.toInt()
                }
            }.also { cursor += declared * 4 }
        } else {
            null
        }

        val xorStride = ((width * bitCount + 31) / 32) * 4
        val maskStride = ((width + 31) / 32) * 4
        val xorStart = cursor
        if (xorStart + xorStride.toLong() * height > end) throw IcoException("entry $index: pixel data truncated")
        val maskStart = xorStart + xorStride * height
        val hasMask = maskStart + maskStride.toLong() * height <= end

        val argb = IntArray(width * height)
        var anyAlpha = false
        for (y in 0 until height) {
            val srcRow = if (topDown) y else height - 1 - y
            val row = xorStart + srcRow * xorStride
            for (x in 0 until width) {
                val pixel = when (bitCount) {
                    32 -> {
                        val p = row + x * 4
                        val a = bytes[p + 3].toInt() and 0xFF
                        if (a != 0) anyAlpha = true
                        (a shl 24) or ((bytes[p + 2].toInt() and 0xFF) shl 16) or
                            ((bytes[p + 1].toInt() and 0xFF) shl 8) or (bytes[p].toInt() and 0xFF)
                    }
                    24 -> {
                        val p = row + x * 3
                        (0xFF shl 24) or ((bytes[p + 2].toInt() and 0xFF) shl 16) or
                            ((bytes[p + 1].toInt() and 0xFF) shl 8) or (bytes[p].toInt() and 0xFF)
                    }
                    8 -> palette!![bytes[row + x].toInt() and 0xFF]
                    4 -> {
                        val b = bytes[row + x / 2].toInt() and 0xFF
                        palette!![if (x % 2 == 0) b ushr 4 else b and 0x0F]
                    }
                    else -> { // 1 bpp
                        val b = bytes[row + x / 8].toInt() and 0xFF
                        palette!![(b ushr (7 - x % 8)) and 1]
                    }
                }
                argb[y * width + x] = pixel
            }
        }

        // 32-bpp icons with a real alpha channel ignore the AND mask; everything else (and a
        // 32-bpp image whose alpha is all zero, as old writers emit) is opaque where the mask is 0.
        val useMask = hasMask && (bitCount != 32 || !anyAlpha)
        if (bitCount == 32 && !anyAlpha) for (i in argb.indices) argb[i] = argb[i] or (0xFF shl 24)
        if (useMask) {
            for (y in 0 until height) {
                val srcRow = if (topDown) y else height - 1 - y
                val row = maskStart + srcRow * maskStride
                for (x in 0 until width) {
                    val bit = (bytes[row + x / 8].toInt() ushr (7 - x % 8)) and 1
                    if (bit == 1) argb[y * width + x] = argb[y * width + x] and 0x00FFFFFF
                }
            }
        }
        return Entry.Dib(Image(width, height, argb), bitCount)
    }

    private fun checkDimensions(width: Int, height: Int, index: Int) {
        if (width < 1 || height < 1 || width > MAX_DIMENSION || height > MAX_DIMENSION) {
            throw IcoException("entry $index: unsupported size ${width}×$height (limit $MAX_DIMENSION)")
        }
    }

    private fun u8(b: ByteArray, at: Int): Int = b[at].toInt() and 0xFF
    private fun u16(b: ByteArray, at: Int): Int = u8(b, at) or (u8(b, at + 1) shl 8)
    private fun i32(b: ByteArray, at: Int): Int =
        u8(b, at) or (u8(b, at + 1) shl 8) or (u8(b, at + 2) shl 16) or (u8(b, at + 3) shl 24)

    /** Little-endian u32 clamped into an Int: anything ≥ 2^31 is reported as -1 so size checks fail. */
    private fun u32(b: ByteArray, at: Int): Int = i32(b, at).let { if (it < 0) -1 else it }
    private fun be32(b: ByteArray, at: Int): Int =
        (u8(b, at) shl 24) or (u8(b, at + 1) shl 16) or (u8(b, at + 2) shl 8) or u8(b, at + 3)
}
