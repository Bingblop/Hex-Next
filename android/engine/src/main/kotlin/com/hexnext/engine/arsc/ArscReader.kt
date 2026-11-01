package com.hexnext.engine.arsc

import java.io.File

/**
 * Parser for binary Android resource tables (`resources.arsc`).
 *
 * Supported chunks:
 * * `RES_TABLE_TYPE` (0x0002) with its global `RES_STRING_POOL_TYPE` (0x0001), UTF-8 or UTF-16;
 * * `RES_TABLE_PACKAGE_TYPE` (0x0200) with type/key string pools;
 * * `RES_TABLE_TYPE_SPEC_TYPE` (0x0202, read for nothing but validated by skipping);
 * * `RES_TABLE_TYPE_TYPE` (0x0201) with dense 32-bit offsets, `FLAG_OFFSET16` 16-bit offsets and
 *   `FLAG_SPARSE` sparse entries; simple, complex (bag) and compact (`FLAG_COMPACT`) entries;
 * * `RES_TABLE_OVERLAYABLE_TYPE` (0x0204) with its `RES_TABLE_OVERLAYABLE_POLICY_TYPE` (0x0205)
 *   children, exposed as [ResourceTable.overlayable];
 * * library (0x0203) and staged-alias (0x0206) chunks and any unknown chunk are skipped.
 *
 * All reads are bounds-checked; malformed input throws [ArscFormatException].
 */
public object ArscReader {
    private const val RES_TABLE_TYPE = 0x0002
    private const val RES_TABLE_PACKAGE_TYPE = 0x0200
    private const val RES_TABLE_TYPE_TYPE = 0x0201
    private const val RES_TABLE_TYPE_SPEC_TYPE = 0x0202
    private const val RES_TABLE_OVERLAYABLE_TYPE = 0x0204
    private const val RES_TABLE_OVERLAYABLE_POLICY_TYPE = 0x0205

    private const val FLAG_SPARSE = 0x01
    private const val FLAG_OFFSET16 = 0x02
    private const val ENTRY_FLAG_COMPLEX = 0x0001
    private const val ENTRY_FLAG_COMPACT = 0x0008
    private const val NO_ENTRY = -1
    private const val NO_ENTRY16 = 0xFFFF

    // Res_value data types
    private const val TYPE_NULL = 0x00
    private const val TYPE_REFERENCE = 0x01
    private const val TYPE_ATTRIBUTE = 0x02
    private const val TYPE_STRING = 0x03
    private const val TYPE_DIMENSION = 0x05
    private const val TYPE_DYNAMIC_REFERENCE = 0x07
    private const val TYPE_DYNAMIC_ATTRIBUTE = 0x08
    private const val TYPE_INT_DEC = 0x10
    private const val TYPE_INT_HEX = 0x11
    private const val TYPE_INT_BOOLEAN = 0x12
    private const val TYPE_INT_COLOR_ARGB8 = 0x1c
    private const val TYPE_INT_COLOR_RGB8 = 0x1d
    private const val TYPE_INT_COLOR_ARGB4 = 0x1e
    private const val TYPE_INT_COLOR_RGB4 = 0x1f

    /** Reads the first package of the table in [file]. */
    public fun read(file: File): ResourceTable = read(file.readBytes())

    /** Reads the first package of the table in [bytes]. */
    public fun read(bytes: ByteArray): ResourceTable =
        readAll(bytes).firstOrNull() ?: throw ArscFormatException("resource table has no package")

    /** Reads every package of the table in [bytes]. */
    public fun readAll(bytes: ByteArray): List<ResourceTable> {
        val data = LeBytes(bytes)
        if (data.size < 12) throw ArscFormatException("file too small for a resource table")
        if (data.u16(0) != RES_TABLE_TYPE) throw ArscFormatException("not a resource table (type 0x%04x)".format(data.u16(0)))
        val headerSize = data.u16(2)
        val tableSize = data.i32(4)
        data.check(0, tableSize, "resource table")
        var globalPool: ResStringPool? = null
        val packages = mutableListOf<ResourceTable>()
        forEachChunk(data, headerSize, tableSize) { type, start, _ ->
            when (type) {
                ResStringPool.TYPE -> if (globalPool == null) globalPool = ResStringPool.parse(data, start)
                RES_TABLE_PACKAGE_TYPE -> {
                    val pool = globalPool ?: throw ArscFormatException("package chunk before global string pool")
                    packages += readPackage(data, start, pool)
                }
            }
        }
        return packages
    }

    private inline fun forEachChunk(data: LeBytes, from: Int, end: Int, block: (type: Int, start: Int, size: Int) -> Unit) {
        var p = from
        while (p + 8 <= end) {
            val type = data.u16(p)
            val headerSize = data.u16(p + 2)
            val size = data.i32(p + 4)
            if (size < 8 || headerSize < 8 || headerSize > size || p.toLong() + size > end) {
                throw ArscFormatException("bad chunk 0x%04x at %d (header=%d size=%d)".format(type, p, headerSize, size))
            }
            block(type, p, size)
            p += size
        }
    }

    private class TypeAccumulator {
        val values = LinkedHashMap<Int, MutableList<ConfiguredValue>>()
        val names = HashMap<Int, String>()
    }

    private fun readPackage(data: LeBytes, start: Int, globalPool: ResStringPool): ResourceTable {
        val headerSize = data.u16(start + 2)
        val size = data.i32(start + 4)
        val end = start + size
        val packageId = data.i32(start + 8)
        val name = data.utf16Fixed(start + 12, 128)
        val typeStringsOff = data.i32(start + 268)
        val keyStringsOff = data.i32(start + 276)
        val typeIdOffset = if (headerSize >= 288) data.i32(start + 284) else 0
        val typeStrings = ResStringPool.parse(data, start + typeStringsOff)
        val keyStrings = ResStringPool.parse(data, start + keyStringsOff)

        val types = sortedMapOf<Int, TypeAccumulator>()
        val overlayable = mutableListOf<OverlayableGroup>()
        forEachChunk(data, start + headerSize, end) { type, chunk, chunkSize ->
            when (type) {
                RES_TABLE_TYPE_TYPE -> readType(data, chunk, keyStrings, globalPool, typeStrings, typeIdOffset, types)
                RES_TABLE_OVERLAYABLE_TYPE -> overlayable += readOverlayable(data, chunk, chunkSize)
                RES_TABLE_TYPE_SPEC_TYPE -> Unit // flags only; entry names come from type chunks
                else -> Unit // string pools (already parsed), library, staged alias, unknown
            }
        }

        val entries = mutableListOf<ResourceEntry>()
        for ((typeId, acc) in types) {
            val typeName = typeStrings.getOrNull(typeId - 1 - typeIdOffset) ?: "type$typeId"
            for ((index, values) in acc.values.toSortedMap()) {
                val id = (packageId shl 24) or (typeId shl 16) or index
                entries += ResourceEntry(typeName, acc.names[index] ?: "res_0x%08x".format(id), id, values)
            }
        }
        return ResourceTable(name, packageId, entries, overlayable)
    }

    private fun readType(
        data: LeBytes,
        start: Int,
        keys: ResStringPool,
        globalPool: ResStringPool,
        typeStrings: ResStringPool,
        typeIdOffset: Int,
        types: MutableMap<Int, TypeAccumulator>,
    ) {
        val headerSize = data.u16(start + 2)
        val size = data.i32(start + 4)
        val typeId = data.u8(start + 8)
        val flags = data.u8(start + 9)
        val entryCount = data.i32(start + 12)
        val entriesStart = data.i32(start + 16)
        val configSize = data.i32(start + 20)
        val configBytes = data.slice(start + 20, minOf(configSize, headerSize - 20).coerceAtLeast(0))
        val config = ResConfig.decode(configBytes)
        val typeName = typeStrings.getOrNull(typeId - 1 - typeIdOffset) ?: ""
        val acc = types.getOrPut(typeId) { TypeAccumulator() }
        val offsetsStart = start + headerSize
        val base = start + entriesStart
        val chunkEnd = start + size
        if (entryCount < 0) throw ArscFormatException("negative entry count")

        fun visit(index: Int, entryOffset: Int) {
            val at = base + entryOffset
            if (at < base || at + 8 > chunkEnd) throw ArscFormatException("entry $index outside type chunk")
            val (keyIndex, value) = readEntry(data, at, chunkEnd, globalPool, typeName)
            acc.names.getOrPut(index) { keys[keyIndex] }
            acc.values.getOrPut(index) { mutableListOf() } += ConfiguredValue(config.qualifiers, value, config)
        }

        when {
            flags and FLAG_SPARSE != 0 -> for (i in 0 until entryCount) {
                val p = offsetsStart + i * 4
                visit(data.u16(p), data.u16(p + 2) * 4)
            }
            flags and FLAG_OFFSET16 != 0 -> for (i in 0 until entryCount) {
                val off = data.u16(offsetsStart + i * 2)
                if (off != NO_ENTRY16) visit(i, off * 4)
            }
            else -> for (i in 0 until entryCount) {
                val off = data.i32(offsetsStart + i * 4)
                if (off != NO_ENTRY) visit(i, off)
            }
        }
    }

    /** Returns (key string index, value). */
    private fun readEntry(data: LeBytes, at: Int, end: Int, pool: ResStringPool, typeName: String): Pair<Int, ResourceValue> {
        val sizeOrKey = data.u16(at)
        val flags = data.u16(at + 2)
        if (flags and ENTRY_FLAG_COMPACT != 0) {
            val dataType = (flags shr 8) and 0xFF
            return Pair(sizeOrKey, decodeValue(dataType, data.i32(at + 4), pool, typeName))
        }
        val key = data.i32(at + 4)
        if (flags and ENTRY_FLAG_COMPLEX != 0) {
            val parent = data.i32(at + 8)
            val count = data.i32(at + 12)
            var p = at + sizeOrKey
            if (count < 0 || p.toLong() + count.toLong() * 12 > end) throw ArscFormatException("bag overruns type chunk")
            val items = LinkedHashMap<Int, ResourceValue>()
            repeat(count) {
                val nameId = data.i32(p)
                items[nameId] = decodeValue(data.u8(p + 7), data.i32(p + 8), pool, typeName)
                p += 12
            }
            return Pair(key, ResourceValue.Complex(parent, items))
        }
        val v = at + sizeOrKey
        if (v + 8 > end) throw ArscFormatException("value overruns type chunk")
        return Pair(key, decodeValue(data.u8(v + 3), data.i32(v + 4), pool, typeName))
    }

    private fun decodeValue(dataType: Int, value: Int, pool: ResStringPool, typeName: String): ResourceValue =
        when (dataType) {
            TYPE_REFERENCE, TYPE_DYNAMIC_REFERENCE -> ResourceValue.Reference(value, attr = false)
            TYPE_ATTRIBUTE, TYPE_DYNAMIC_ATTRIBUTE -> ResourceValue.Reference(value, attr = true)
            TYPE_STRING -> {
                val s = pool[value]
                if (typeName != "string" && s.startsWith("res/")) ResourceValue.FileRef(s) else ResourceValue.Str(s)
            }
            TYPE_DIMENSION -> ResourceValue.Dimension(value)
            TYPE_INT_DEC, TYPE_INT_HEX -> ResourceValue.IntValue(value)
            TYPE_INT_BOOLEAN -> ResourceValue.BoolValue(value != 0)
            TYPE_INT_COLOR_ARGB8, TYPE_INT_COLOR_ARGB4 -> ResourceValue.Color(value)
            TYPE_INT_COLOR_RGB8, TYPE_INT_COLOR_RGB4 -> ResourceValue.Color(value or 0xFF000000.toInt())
            TYPE_NULL -> ResourceValue.Other(dataType, value)
            else -> ResourceValue.Other(dataType, value)
        }

    private fun readOverlayable(data: LeBytes, start: Int, size: Int): List<OverlayableGroup> {
        val headerSize = data.u16(start + 2)
        val name = data.utf16Fixed(start + 8, 256)
        val actor = data.utf16Fixed(start + 8 + 512, 256)
        val groups = mutableListOf<OverlayableGroup>()
        forEachChunk(data, start + headerSize, start + size) { type, chunk, _ ->
            if (type == RES_TABLE_OVERLAYABLE_POLICY_TYPE) {
                val policyHeader = data.u16(chunk + 2)
                val policies = data.i32(chunk + 8)
                val count = data.i32(chunk + 12)
                if (count < 0) throw ArscFormatException("negative overlayable entry count")
                data.check(chunk + policyHeader, count * 4, "overlayable ids")
                val ids = List(count) { data.i32(chunk + policyHeader + it * 4) }
                groups += OverlayableGroup(name, actor, policies, ids)
            }
        }
        return groups
    }
}
