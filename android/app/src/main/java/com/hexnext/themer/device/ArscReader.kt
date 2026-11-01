package com.hexnext.themer.device

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Minimal, self-contained `resources.arsc` reader that lists resource NAMES only.
 *
 * It walks RES_TABLE -> RES_TABLE_PACKAGE, decodes the package's type and key string pools and
 * every RES_TABLE_TYPE chunk's entry table (dense u32 offsets, FLAG_SPARSE and Android 15's
 * FLAG_OFFSET16, plus compact entries) to collect `(type, name)` pairs.
 *
 * Limitations (by design, this is a scanner, not a resource resolver):
 * - Values are not decoded: no colours, no file paths, no styles, no config qualifiers.
 * - The global value string pool is skipped.
 * - Shared-library / staged-alias / overlayable chunks are ignored.
 * - Obfuscated (shortened) key names are reported as they are stored.
 * - Malformed chunks stop the walk of the current package; what was read so far is returned
 *   along with a warning.
 *
 * Pass `types` to keep only those resource types: type chunks of other types are skipped by
 * their chunk size, so a framework table (5 MB, mostly translated strings) costs a few thousand
 * entries instead of every name. Files are memory-mapped, never copied onto the heap.
 */
object ArscReader {

    data class Entry(val packageName: String, val type: String, val name: String) {
        /** `pkg:type/name`, the form FabricatedOverlay and `cmd overlay lookup` take. */
        val qualified: String get() = "$packageName:$type/$name"
    }

    data class Result(
        val packages: List<String>,
        val entries: List<Entry>,
        val warnings: List<String>,
    ) {
        fun ofType(type: String): List<Entry> = entries.filter { it.type == type }
        fun countOf(type: String): Int = entries.count { it.type == type }
    }

    /** Resource types the theme and the scan report use. */
    val THEME_TYPES: Set<String> = setOf("color", "drawable", "mipmap")

    fun read(file: File, types: Set<String>? = null): Result {
        val mapped = RandomAccessFile(file, "r").use { raf ->
            raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
        }
        return read(mapped, types)
    }

    fun read(bytes: ByteArray, types: Set<String>? = null): Result = read(ByteBuffer.wrap(bytes), types)

    private fun read(buffer: ByteBuffer, types: Set<String>?): Result {
        val b = buffer.order(ByteOrder.LITTLE_ENDIAN)
        val warnings = ArrayList<String>()
        val packages = ArrayList<String>()
        val entries = LinkedHashSet<Entry>()
        if (b.limit() < 12 || u16(b, 0) != RES_TABLE_TYPE) {
            return Result(emptyList(), emptyList(), listOf("Not a resources.arsc (bad RES_TABLE header)"))
        }
        val headerSize = u16(b, 2)
        val end = minOf(u32(b, 4), b.limit().toLong()).toInt()
        var pos = headerSize
        while (pos + 8 <= end) {
            val type = u16(b, pos)
            val size = u32(b, pos + 4)
            if (size < 8 || pos + size > end) {
                warnings.add("Truncated chunk 0x${type.toString(16)} at $pos")
                break
            }
            if (type == RES_TABLE_PACKAGE_TYPE) {
                try {
                    readPackage(b, pos, size.toInt(), types, packages, entries, warnings)
                } catch (t: Throwable) {
                    warnings.add("Package at $pos: ${t.javaClass.simpleName}: ${t.message}")
                }
            }
            pos += size.toInt()
        }
        return Result(packages, entries.toList(), warnings)
    }

    private fun readPackage(
        b: ByteBuffer,
        start: Int,
        size: Int,
        types: Set<String>?,
        packages: MutableList<String>,
        entries: MutableSet<Entry>,
        warnings: MutableList<String>,
    ) {
        val headerSize = u16(b, start + 2)
        val end = start + size
        val name = buildString {
            for (i in 0 until 128) {
                val c = u16(b, start + 12 + i * 2)
                if (c == 0) break
                append(c.toChar())
            }
        }
        packages.add(name)
        val typeStrings = StringPool(b, start + u32(b, start + 268).toInt())
        val keyStrings = StringPool(b, start + u32(b, start + 276).toInt())

        var pos = start + headerSize
        while (pos + 8 <= end) {
            val type = u16(b, pos)
            val chunkHeader = u16(b, pos + 2)
            val chunkSize = u32(b, pos + 4)
            if (chunkSize < 8 || pos + chunkSize > end) {
                warnings.add("$name: truncated chunk 0x${type.toString(16)} at $pos")
                return
            }
            if (type == RES_TABLE_TYPE_TYPE) {
                readType(b, pos, chunkHeader, chunkSize.toInt(), name, typeStrings, keyStrings, types, entries)
            }
            pos += chunkSize.toInt()
        }
    }

    private fun readType(
        b: ByteBuffer,
        start: Int,
        headerSize: Int,
        size: Int,
        packageName: String,
        typeStrings: StringPool,
        keyStrings: StringPool,
        types: Set<String>?,
        entries: MutableSet<Entry>,
    ) {
        val end = start + size
        val typeId = u8(b, start + 8)
        val flags = u8(b, start + 9)
        val typeName = typeStrings[typeId - 1] ?: return
        if (types != null && typeName !in types) return
        val entryCount = u32(b, start + 12).toInt()
        val entriesStart = start + u32(b, start + 16).toInt()
        val offsets = start + headerSize

        fun addEntryAt(relative: Int) {
            val e = entriesStart + relative
            if (relative < 0 || e + 8 > end) return
            val entryFlags = u16(b, e + 2)
            val key = if ((entryFlags and ENTRY_FLAG_COMPACT) != 0) u16(b, e) else u32(b, e + 4).toInt()
            val keyName = keyStrings[key] ?: return
            entries.add(Entry(packageName, typeName, keyName))
        }

        when {
            (flags and FLAG_SPARSE) != 0 -> for (i in 0 until entryCount) {
                val at = offsets + i * 4
                if (at + 4 > end) break
                addEntryAt(u16(b, at + 2) * 4)
            }
            (flags and FLAG_OFFSET16) != 0 -> for (i in 0 until entryCount) {
                val at = offsets + i * 2
                if (at + 2 > end) break
                val v = u16(b, at)
                if (v != NO_ENTRY_16) addEntryAt(v * 4)
            }
            else -> for (i in 0 until entryCount) {
                val at = offsets + i * 4
                if (at + 4 > end) break
                val v = u32(b, at)
                if (v != NO_ENTRY_32) addEntryAt(v.toInt())
            }
        }
    }

    /** Lazily decoded ResStringPool (UTF-8 or UTF-16). */
    private class StringPool(private val b: ByteBuffer, private val start: Int) {
        private val valid = start >= 0 && start + 28 <= b.limit() && u16(b, start) == RES_STRING_POOL_TYPE
        private val headerSize = if (valid) u16(b, start + 2) else 0
        private val count = if (valid) u32(b, start + 8).toInt() else 0
        private val utf8 = valid && (u32(b, start + 16).toInt() and UTF8_FLAG) != 0
        private val stringsStart = if (valid) start + u32(b, start + 20).toInt() else 0
        private val cache = arrayOfNulls<String>(count.coerceIn(0, 1_000_000))

        operator fun get(index: Int): String? {
            if (index < 0 || index >= cache.size) return null
            cache[index]?.let { return it }
            val offsetAt = start + headerSize + index * 4
            if (offsetAt + 4 > b.limit()) return null
            val at = stringsStart + u32(b, offsetAt).toInt()
            val value = runCatching { if (utf8) decodeUtf8(at) else decodeUtf16(at) }.getOrNull()
            cache[index] = value
            return value
        }

        private fun decodeUtf8(at: Int): String {
            var p = at
            // UTF-16 length (skipped), then UTF-8 byte length; each 1 or 2 bytes.
            p += if ((u8(b, p) and 0x80) != 0) 2 else 1
            val first = u8(b, p)
            val length: Int
            if ((first and 0x80) != 0) {
                length = ((first and 0x7F) shl 8) or u8(b, p + 1)
                p += 2
            } else {
                length = first
                p += 1
            }
            val bytes = ByteArray(length)
            for (i in 0 until length) bytes[i] = b.get(p + i)
            return String(bytes, Charsets.UTF_8)
        }

        private fun decodeUtf16(at: Int): String {
            var p = at
            val first = u16(b, p)
            val length: Int
            if ((first and 0x8000) != 0) {
                length = ((first and 0x7FFF) shl 16) or u16(b, p + 2)
                p += 4
            } else {
                length = first
                p += 2
            }
            val chars = CharArray(length)
            for (i in 0 until length) chars[i] = u16(b, p + i * 2).toChar()
            return String(chars)
        }
    }
}

private const val RES_STRING_POOL_TYPE = 0x0001
private const val RES_TABLE_TYPE = 0x0002
private const val RES_TABLE_PACKAGE_TYPE = 0x0200
private const val RES_TABLE_TYPE_TYPE = 0x0201

private const val FLAG_SPARSE = 0x01
private const val FLAG_OFFSET16 = 0x02
private const val ENTRY_FLAG_COMPACT = 0x0008
private const val UTF8_FLAG = 0x100
private const val NO_ENTRY_32 = 0xFFFFFFFFL
private const val NO_ENTRY_16 = 0xFFFF

private fun u8(b: ByteBuffer, at: Int): Int = b.get(at).toInt() and 0xFF
private fun u16(b: ByteBuffer, at: Int): Int = b.getShort(at).toInt() and 0xFFFF
private fun u32(b: ByteBuffer, at: Int): Long = b.getInt(at).toLong() and 0xFFFFFFFFL
