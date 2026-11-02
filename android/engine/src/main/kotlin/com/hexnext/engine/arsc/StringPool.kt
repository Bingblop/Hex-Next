package com.hexnext.engine.arsc

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Little-endian bounds-checked view over a byte array or a (possibly memory-mapped) [ByteBuffer];
 * every failure is an [ArscFormatException]. Reads use absolute positions, so the buffer's own
 * position and limit are never changed.
 */
internal class LeBytes private constructor(private val buf: ByteBuffer) {
    constructor(bytes: ByteArray) : this(ByteBuffer.wrap(bytes))

    val size: Int = buf.limit()

    fun check(offset: Int, length: Int, what: String) {
        if (offset < 0 || length < 0 || offset.toLong() + length > size) {
            throw ArscFormatException("$what out of bounds (offset=$offset length=$length size=$size)")
        }
    }

    fun u8(offset: Int): Int {
        check(offset, 1, "u8")
        return buf.get(offset).toInt() and 0xFF
    }

    fun u16(offset: Int): Int {
        check(offset, 2, "u16")
        return (buf.get(offset).toInt() and 0xFF) or ((buf.get(offset + 1).toInt() and 0xFF) shl 8)
    }

    fun i32(offset: Int): Int {
        check(offset, 4, "u32")
        return (buf.get(offset).toInt() and 0xFF) or
            ((buf.get(offset + 1).toInt() and 0xFF) shl 8) or
            ((buf.get(offset + 2).toInt() and 0xFF) shl 16) or
            ((buf.get(offset + 3).toInt() and 0xFF) shl 24)
    }

    fun slice(offset: Int, length: Int): ByteArray {
        check(offset, length, "slice")
        val out = ByteArray(length)
        for (i in 0 until length) out[i] = buf.get(offset + i)
        return out
    }

    /** Decodes [length] bytes at [offset] as UTF-8. */
    fun utf8(offset: Int, length: Int): String = String(slice(offset, length), Charsets.UTF_8)

    /** Reads a NUL-terminated UTF-16 string of at most [maxChars] code units. */
    fun utf16Fixed(offset: Int, maxChars: Int): String {
        val sb = StringBuilder()
        for (i in 0 until maxChars) {
            val c = u16(offset + i * 2)
            if (c == 0) break
            sb.append(c.toChar())
        }
        return sb.toString()
    }

    companion object {
        /** A view over [buffer] from index 0 to its limit (its byte order is ignored). */
        fun of(buffer: ByteBuffer): LeBytes = LeBytes(buffer.duplicate().also { it.position(0) })
    }
}

/** A decoded `ResStringPool` chunk. Strings are decoded lazily and cached. */
internal class ResStringPool private constructor(
    private val data: LeBytes,
    private val chunkStart: Int,
    private val offsets: IntArray,
    private val stringsStart: Int,
    private val chunkEnd: Int,
    val isUtf8: Boolean,
) {
    private val cache = arrayOfNulls<String>(offsets.size)

    val size: Int get() = offsets.size

    operator fun get(index: Int): String {
        if (index !in offsets.indices) throw ArscFormatException("string index $index outside pool of ${offsets.size}")
        cache[index]?.let { return it }
        val s = decode(stringsStart + offsets[index])
        cache[index] = s
        return s
    }

    /** Returns the string at [index], or null for `-1` / out-of-range indices. */
    fun getOrNull(index: Int): String? = if (index in offsets.indices) get(index) else null

    private fun decode(at: Int): String {
        if (at !in chunkStart until chunkEnd) throw ArscFormatException("string offset $at outside pool")
        return if (isUtf8) {
            var p = at
            val (_, n1) = utf8Len(p); p += n1 // UTF-16 length, unused
            val (byteLen, n2) = utf8Len(p); p += n2
            if (p + byteLen > chunkEnd) throw ArscFormatException("UTF-8 string overruns pool")
            data.utf8(p, byteLen)
        } else {
            var p = at
            var len = data.u16(p); p += 2
            if (len and 0x8000 != 0) {
                len = ((len and 0x7FFF) shl 16) or data.u16(p); p += 2
            }
            if (p + len * 2 > chunkEnd) throw ArscFormatException("UTF-16 string overruns pool")
            val chars = CharArray(len) { data.u16(p + it * 2).toChar() }
            String(chars)
        }
    }

    private fun utf8Len(p: Int): Pair<Int, Int> {
        val b0 = data.u8(p)
        return if (b0 and 0x80 != 0) Pair(((b0 and 0x7F) shl 8) or data.u8(p + 1), 2) else Pair(b0, 1)
    }

    companion object {
        const val TYPE = 0x0001
        private const val UTF8_FLAG = 0x100

        /** Parses the string pool chunk starting at [start]. */
        fun parse(data: LeBytes, start: Int): ResStringPool {
            val type = data.u16(start)
            if (type != TYPE) throw ArscFormatException("expected string pool at $start, found chunk 0x%04x".format(type))
            val headerSize = data.u16(start + 2)
            val size = data.i32(start + 4)
            data.check(start, size, "string pool")
            val count = data.i32(start + 8)
            val flags = data.i32(start + 16)
            val stringsStart = data.i32(start + 20)
            if (count < 0 || count > size / 4) throw ArscFormatException("bad string count $count")
            val offsets = IntArray(count) { data.i32(start + headerSize + it * 4) }
            return ResStringPool(data, start, offsets, start + stringsStart, start + size, flags and UTF8_FLAG != 0)
        }
    }
}

/** Serialises a UTF-8 `ResStringPool` chunk (no styles). Used by the FRRO writer and tests. */
internal object StringPoolWriter {
    fun write(strings: List<String>): ByteArray {
        val body = ByteArrayOutputStream()
        val offsets = IntArray(strings.size)
        strings.forEachIndexed { i, s ->
            offsets[i] = body.size()
            val utf8 = s.toByteArray(Charsets.UTF_8)
            writeLen8(body, s.length)
            writeLen8(body, utf8.size)
            body.write(utf8)
            body.write(0)
        }
        while (body.size() % 4 != 0) body.write(0)
        val headerSize = 28
        val stringsStart = headerSize + strings.size * 4
        val total = stringsStart + body.size()
        val buf = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(ResStringPool.TYPE.toShort()).putShort(headerSize.toShort()).putInt(total)
        buf.putInt(strings.size).putInt(0).putInt(0x100).putInt(stringsStart).putInt(0)
        offsets.forEach { buf.putInt(it) }
        buf.put(body.toByteArray())
        return buf.array()
    }

    private fun writeLen8(out: ByteArrayOutputStream, len: Int) {
        require(len <= 0x7FFF) { "string too long for pool: $len" }
        if (len > 0x7F) {
            out.write(((len shr 8) and 0x7F) or 0x80)
            out.write(len and 0xFF)
        } else {
            out.write(len)
        }
    }
}
