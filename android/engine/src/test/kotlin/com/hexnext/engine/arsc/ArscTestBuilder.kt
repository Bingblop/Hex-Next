package com.hexnext.engine.arsc

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Builds small synthetic resource tables exercising the chunk variants real files rarely contain. */
internal class ArscTestBuilder(private val packageName: String, private val packageId: Int = 0x7f) {
    sealed class Entry {
        abstract val key: Int
        data class Simple(override val key: Int, val dataType: Int, val data: Int) : Entry()
        data class Compact(override val key: Int, val dataType: Int, val data: Int) : Entry()
        data class Bag(override val key: Int, val parent: Int, val items: List<Triple<Int, Int, Int>>) : Entry()
    }

    enum class Layout { DENSE, OFFSET16, SPARSE }

    val globalStrings = mutableListOf<String>()
    val typeNames = mutableListOf<String>()
    val keyNames = mutableListOf<String>()
    private val chunks = mutableListOf<ByteArray>()

    fun string(s: String): Int = globalStrings.indexOf(s).takeIf { it >= 0 } ?: run { globalStrings += s; globalStrings.size - 1 }
    fun key(s: String): Int = keyNames.indexOf(s).takeIf { it >= 0 } ?: run { keyNames += s; keyNames.size - 1 }

    /** A 64-byte ResTable_config with the given fields. */
    fun config(density: Int = 0, uiMode: Int = 0, orientation: Int = 0, sdk: Int = 0, touchscreen: Int = 0): ByteArray =
        ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0, 64)
            put(12, orientation.toByte())
            put(13, touchscreen.toByte())
            putShort(14, density.toShort())
            putShort(24, sdk.toShort())
            put(29, uiMode.toByte())
        }.array()

    fun type(typeId: Int, entryCount: Int, config: ByteArray, entries: Map<Int, Entry>, layout: Layout = Layout.DENSE) {
        val body = ByteArrayOutputStream()
        val offsets = sortedMapOf<Int, Int>()
        for ((index, e) in entries.toSortedMap()) {
            offsets[index] = body.size()
            body.write(encode(e))
        }
        val headerSize = 20 + config.size
        val offsetTable = ByteArrayOutputStream()
        val count: Int
        val flags: Int
        when (layout) {
            Layout.DENSE -> {
                flags = 0; count = entryCount
                for (i in 0 until entryCount) offsetTable.write(le32(offsets[i] ?: -1))
            }
            Layout.OFFSET16 -> {
                flags = 0x02; count = entryCount
                for (i in 0 until entryCount) offsetTable.write(le16(offsets[i]?.div(4) ?: 0xFFFF))
                while (offsetTable.size() % 4 != 0) offsetTable.write(0)
            }
            Layout.SPARSE -> {
                flags = 0x01; count = offsets.size
                for ((i, off) in offsets) { offsetTable.write(le16(i)); offsetTable.write(le16(off / 4)) }
            }
        }
        val entriesStart = headerSize + offsetTable.size()
        val total = entriesStart + body.size()
        val buf = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(0x0201).putShort(headerSize.toShort()).putInt(total)
        buf.put(typeId.toByte()).put(flags.toByte()).putShort(0).putInt(count).putInt(entriesStart)
        buf.put(config).put(offsetTable.toByteArray()).put(body.toByteArray())
        chunks += buf.array()
    }

    fun typeSpec(typeId: Int, entryCount: Int) {
        val total = 16 + entryCount * 4
        chunks += ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(0x0202).putShort(16).putInt(total)
            .put(typeId.toByte()).put(0).putShort(0).putInt(entryCount).array()
    }

    fun overlayable(name: String, actor: String, policies: List<Pair<Int, List<Int>>>) {
        val children = ByteArrayOutputStream()
        for ((flags, ids) in policies) {
            val size = 16 + ids.size * 4
            val b = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
                .putShort(0x0205).putShort(16).putInt(size).putInt(flags).putInt(ids.size)
            ids.forEach { b.putInt(it) }
            children.write(b.array())
        }
        val headerSize = 8 + 1024
        val total = headerSize + children.size()
        val b = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN).putShort(0x0204).putShort(headerSize.toShort()).putInt(total)
        b.put(utf16Fixed(name, 256)).put(utf16Fixed(actor, 256)).put(children.toByteArray())
        chunks += b.array()
    }

    fun build(utf16GlobalPool: Boolean = false): ByteArray {
        val typePool = StringPoolWriter.write(typeNames)
        val keyPool = StringPoolWriter.write(keyNames)
        val pkgHeader = 288
        val pkgBody = ByteArrayOutputStream().apply { write(typePool); write(keyPool); chunks.forEach { write(it) } }
        val pkgSize = pkgHeader + pkgBody.size()
        val pkg = ByteBuffer.allocate(pkgSize).order(ByteOrder.LITTLE_ENDIAN)
        pkg.putShort(0x0200).putShort(pkgHeader.toShort()).putInt(pkgSize).putInt(packageId)
        pkg.put(utf16Fixed(packageName, 128))
        pkg.putInt(pkgHeader).putInt(typeNames.size).putInt(pkgHeader + typePool.size).putInt(keyNames.size).putInt(0)
        pkg.put(pkgBody.toByteArray())
        val global = if (utf16GlobalPool) utf16Pool(globalStrings) else StringPoolWriter.write(globalStrings)
        val total = 12 + global.size + pkgSize
        return ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(0x0002).putShort(12).putInt(total).putInt(1)
            .put(global).put(pkg.array()).array()
    }

    private fun encode(e: Entry): ByteArray = when (e) {
        is Entry.Simple -> ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(8).putShort(0).putInt(e.key)
            .putShort(8).put(0).put(e.dataType.toByte()).putInt(e.data).array()
        is Entry.Compact -> ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(e.key.toShort()).putShort(((e.dataType shl 8) or 0x08).toShort()).putInt(e.data).array()
        is Entry.Bag -> ByteBuffer.allocate(16 + e.items.size * 12).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(16).putShort(1).putInt(e.key).putInt(e.parent).putInt(e.items.size)
            e.items.forEach { (name, type, data) -> putInt(name).putShort(8).put(0).put(type.toByte()).putInt(data) }
        }.array()
    }

    private fun utf16Pool(strings: List<String>): ByteArray {
        val body = ByteArrayOutputStream()
        val offs = strings.map { s ->
            val o = body.size()
            body.write(le16(s.length)); s.forEach { body.write(le16(it.code)) }; body.write(le16(0)); o
        }
        while (body.size() % 4 != 0) body.write(0)
        val start = 28 + strings.size * 4
        val total = start + body.size()
        val b = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(1).putShort(28).putInt(total).putInt(strings.size).putInt(0).putInt(0).putInt(start).putInt(0)
        offs.forEach { b.putInt(it) }
        return b.put(body.toByteArray()).array()
    }

    private fun utf16Fixed(s: String, chars: Int): ByteArray {
        val b = ByteBuffer.allocate(chars * 2).order(ByteOrder.LITTLE_ENDIAN)
        s.take(chars - 1).forEach { b.putChar(it) }
        return b.array()
    }

    private fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
    private fun le32(v: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
}
