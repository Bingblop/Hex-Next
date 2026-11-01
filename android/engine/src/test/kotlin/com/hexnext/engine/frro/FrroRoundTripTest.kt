package com.hexnext.engine.frro

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FrroRoundTripTest {
    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2, 3)
    private val ninePatch = ByteArray(40) { it.toByte() }

    private fun sample(version: Int = 3): FrroWriter =
        FrroWriter("com.hexnext", "HexNext_com.android.systemui", "com.android.systemui", "SystemUIConfig", version)
            .setColor("com.android.systemui:color/qs_tile_active", 0xFF336699.toInt())
            .setResourceValue("dimen/status_bar_height", 0x05, 0x1801)
            .apply {
                if (version >= 2) {
                    setColor("color/qs_tile_active", 0xFF112233.toInt(), "night")
                    setString("string/clock_format", "HH:mm")
                }
            }
            .apply {
                if (version >= 3) {
                    setBinary("drawable/ic_sysbar_back", png, configuration = "xxhdpi")
                    setBinary("drawable/qs_bg", ninePatch, isNinePatch = true)
                }
            }

    @Test
    fun `version 3 round trip`() {
        val bytes = sample().toByteArray()
        assertEquals(FrroFormat.MAGIC, ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int)
        assertEquals("FRRO", String(bytes, 0, 4, Charsets.US_ASCII))
        val file = FrroReader.read(bytes)
        assertEquals("HexNext_com.android.systemui", file.name)
        assertEquals("com.android.systemui", file.targetPackage)
        assertEquals("SystemUIConfig", file.targetOverlayable)
        assertEquals("com.hexnext", file.packageName)
        assertEquals(3, file.version)
        assertTrue(file.crcMatchesProto)
        val byName = file.entries.groupBy { it.resourceName }
        assertEquals(
            listOf(null to 0xFF336699.toInt(), "night" to 0xFF112233.toInt()),
            byName.getValue("com.android.systemui:color/qs_tile_active").map { it.configuration to it.data },
        )
        assertEquals(0x1801, byName.getValue("com.android.systemui:dimen/status_bar_height").single().data)
        assertEquals("HH:mm", byName.getValue("com.android.systemui:string/clock_format").single().stringData)
        val back = byName.getValue("com.android.systemui:drawable/ic_sysbar_back").single()
        assertEquals("xxhdpi", back.configuration)
        assertEquals(png.size, back.binarySize)
        assertContentEquals(png, FrroReader.readBinary(bytes, back))
        val qs = byName.getValue("com.android.systemui:drawable/qs_bg").single()
        assertTrue(qs.isNinePatch)
        assertNull(qs.stringData)
        assertContentEquals(ninePatch, FrroReader.readBinary(bytes, qs))
    }

    @Test
    fun `reader output rewrites to identical bytes`() {
        for (version in 1..3) {
            val bytes = sample(version).toByteArray()
            val parsed = FrroReader.read(bytes)
            assertEquals(version, parsed.version)
            val rewritten = FrroWriter.from(parsed, bytes).toByteArray()
            assertContentEquals(bytes, rewritten, "version $version")
            assertEquals(parsed, FrroReader.read(rewritten))
        }
    }

    @Test
    fun `version 1 stores default values without configuration`() {
        val file = FrroReader.read(FrroWriter("p", "n", "t", version = 1).setColor("color/a", 1).toByteArray())
        assertEquals(listOf(FrroEntry("t:color/a", FrroFormat.TYPE_INT_COLOR_ARGB8, 1)), file.entries)
        assertFailsWith<IllegalArgumentException> { FrroWriter("p", "n", "t", version = 1).setString("string/a", "x") }
        assertFailsWith<IllegalArgumentException> { FrroWriter("p", "n", "t", version = 2).setBinary("drawable/a", png) }
        assertFailsWith<IllegalArgumentException> { FrroWriter("p", "n", "t").setColor("nocolon", 1) }
    }

    @Test
    fun `malformed files throw FrroFormatException`() {
        assertFailsWith<FrroFormatException> { FrroReader.read(ByteArray(8)) }
        assertFailsWith<FrroFormatException> { FrroReader.read("NOPE00000000".toByteArray()) }
        val good = sample().toByteArray()
        val badVersion = good.copyOf().also { it[4] = 9 }
        assertFailsWith<FrroFormatException> { FrroReader.read(badVersion) }
        assertFailsWith<FrroFormatException> { FrroReader.read(good.copyOf(good.size - 3)) }
        val corruptCrc = good.copyOf().also { it[8] = (it[8] + 1).toByte() }
        assertEquals(false, FrroReader.read(corruptCrc).crcMatchesProto)
    }
}
