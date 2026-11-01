package com.hexnext.engine.frro

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Reads Samsung's real `SemWT_android.frro` from a One UI 9.0 Galaxy SM-S948U1. */
class SemWtFixtureTest {
    private val bytes: ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/semwt_android_oneui9.frro")) { "fixture missing" }.use { it.readBytes() }

    private fun le32(offset: Int) = ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int

    @Test
    fun `header matches the observed version 3 layout`() {
        assertEquals(8868, bytes.size)
        assertEquals(FrroFormat.MAGIC, le32(0))
        assertEquals(3, le32(4))
        assertEquals(0xfa4a4884.toInt(), le32(8))
        assertEquals(0, le32(12)) // total_binary_bytes
        assertEquals(28, le32(16)) // string_pool_size
        assertEquals(0x0a, bytes[48].toInt()) // protobuf field 1 (packages), length-delimited
    }

    @Test
    fun `parses names, targets and every colour`() {
        val file = FrroReader.read(bytes)
        assertEquals(3, file.version)
        assertEquals("SemWT_android", file.name)
        assertEquals("android", file.packageName)
        assertEquals("android", file.targetPackage)
        assertEquals("", file.targetOverlayable)
        assertTrue(file.crcMatchesProto, "CRC covers version + protobuf")

        assertEquals(186, file.entries.size)
        assertTrue(file.entries.all { it.dataType == FrroFormat.TYPE_INT_COLOR_ARGB8 })
        assertTrue(file.entries.all { it.binaryOffset == null && it.stringData == null })
        val byName = file.entries.groupBy { it.resourceName }
        assertEquals(93, byName.size)
        for ((name, values) in byName) {
            assertEquals(listOf(null, "night"), values.map { it.configuration }, name)
        }
        assertTrue(byName.keys.all { it.startsWith("android:color/") })

        val link = byName.getValue("android:color/link_text_dark")
        // 12 08 08 1c 10 fe cc b9 f8 0f -> data_value varint 0xff0e667e
        assertEquals(0xff0e667e.toInt(), link.first { it.configuration == null }.data)
        assertNull(link.first().binarySize)
    }

    @Test
    fun `writer reproduces the device file byte for byte`() {
        val parsed = FrroReader.read(bytes)
        val rewritten = FrroWriter.from(parsed, bytes).toByteArray()
        assertContentEquals(bytes, rewritten)
    }

    @Test
    fun `crc mismatch is informational`() {
        val corrupt = bytes.copyOf().also { it[8] = (it[8] + 1).toByte() }
        val file = FrroReader.read(corrupt)
        assertEquals(false, file.crcMatchesProto)
        assertEquals(186, file.entries.size)
    }
}
