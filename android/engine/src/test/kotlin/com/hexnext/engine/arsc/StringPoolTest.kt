package com.hexnext.engine.arsc

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StringPoolTest {
    /** A one-string UTF-16 pool whose string data is [data] (u16 words). */
    private fun utf16Pool(vararg data: Int): ResStringPool {
        val headerSize = 28
        val stringsStart = headerSize + 4
        val total = stringsStart + data.size * 2
        val buf = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(ResStringPool.TYPE.toShort()).putShort(headerSize.toShort()).putInt(total)
        buf.putInt(1).putInt(0).putInt(0).putInt(stringsStart).putInt(0)
        buf.putInt(0)
        data.forEach { buf.putShort(it.toShort()) }
        return ResStringPool.parse(LeBytes(buf.array()), 0)
    }

    @Test
    fun `utf16 string decodes`() {
        assertEquals("hi", utf16Pool(2, 'h'.code, 'i'.code, 0)[0])
    }

    @Test
    fun `huge utf16 length is a format error, not an allocation`() {
        // Two-word length 0x40000000: len * 2 overflows Int.
        assertFailsWith<ArscFormatException> { utf16Pool(0xC000, 0x0000, 0)[0] }
        assertFailsWith<ArscFormatException> { utf16Pool(0xFFFF, 0xFFFF, 0)[0] }
        assertFailsWith<ArscFormatException> { utf16Pool(0x8000, 0x0010, 0)[0] }
    }
}
