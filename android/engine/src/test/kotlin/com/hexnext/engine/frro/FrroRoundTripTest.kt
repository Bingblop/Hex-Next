package com.hexnext.engine.frro

import com.hexnext.engine.arsc.LeBytes
import com.hexnext.engine.arsc.ResStringPool
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FrroRoundTripTest {
    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2, 3)
    private val png2 = ByteArray(40) { it.toByte() }
    private val path = "/data/resource-cache/com.android.shell-HexNext_com_android_systemui-Ab12.frro"

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
                    setBinary("drawable/qs_bg", png2)
                }
            }

    private fun le32(bytes: ByteArray, offset: Int) = ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int

    @Test
    fun `version 3 round trip`() {
        val bytes = sample().toByteArray(path)
        assertEquals(FrroFormat.MAGIC, le32(bytes, 0))
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
        assertNull(byName.getValue("com.android.systemui:string/clock_format").single().binaryOffset)
        val back = byName.getValue("com.android.systemui:drawable/ic_sysbar_back").single()
        assertEquals("xxhdpi", back.configuration)
        assertEquals(FrroFormat.TYPE_STRING, back.dataType)
        assertEquals(png.size, back.binarySize)
        assertEquals(path, back.binaryPath)
        assertContentEquals(png, FrroReader.readBinary(bytes, back))
        val qs = byName.getValue("com.android.systemui:drawable/qs_bg").single()
        assertContentEquals(png2, FrroReader.readBinary(bytes, qs))
    }

    @Test
    fun `spec with two PNGs matches the AOSP layout`() {
        val spec = FrroSpec(
            ownerPackage = "com.android.shell",
            overlayName = "HexNext_com_android_settings",
            targetPackage = "com.android.settings",
            colors = listOf(
                FrroColor("com.android.settings:color/accent", 0xFF0E667E.toInt()),
                FrroColor("com.android.settings:color/accent", 0xFF113355.toInt(), "night"),
            ),
            // given out of order: the writer sorts entries, so ic_a's payload comes first
            binaries = listOf(
                FrroBinary("com.android.settings:drawable/ic_b", png2),
                FrroBinary("com.android.settings:drawable/ic_a", png),
            ),
        )
        val bytes = FrroWriter.write(spec, path)

        // header: magic, version 3, crc, total_binary_bytes; payloads start at 16
        assertEquals(FrroFormat.MAGIC, le32(bytes, 0))
        assertEquals(3, le32(bytes, 4))
        assertEquals(png.size + png2.size, le32(bytes, 12))
        assertContentEquals(png, bytes.copyOfRange(16, 16 + png.size))
        assertContentEquals(png2, bytes.copyOfRange(16 + png.size, 16 + png.size + png2.size))

        // string pool follows the payloads
        val poolSizeAt = 16 + png.size + png2.size
        val poolSize = le32(bytes, poolSizeAt)
        val pool = ResStringPool.parse(LeBytes(bytes.copyOfRange(poolSizeAt + 4, poolSizeAt + 4 + poolSize)), 0)
        assertTrue(pool.isUtf8)
        val uriA = "frro:/$path?offset=16&size=${png.size}"
        val uriB = "frro:/$path?offset=${16 + png.size}&size=${png2.size}"
        assertEquals(listOf(uriA, uriB), (0 until pool.size).map { pool[it] })
        assertEquals(uriA, FrroWriter.binaryUri(path, 16, png.size.toLong()))

        // CRC = crc32(le32(3) ++ proto), proto is the rest of the file
        val proto = bytes.copyOfRange(poolSizeAt + 4 + poolSize, bytes.size)
        val crc = CRC32().apply { update(byteArrayOf(3, 0, 0, 0)); update(proto) }.value.toInt()
        assertEquals(crc, le32(bytes, 8))

        val file = FrroReader.read(bytes)
        assertTrue(file.crcMatchesProto)
        assertEquals("com.android.shell", file.packageName)
        assertEquals("HexNext_com_android_settings", file.name)
        assertEquals("com.android.settings", file.targetPackage)
        assertEquals("", file.targetOverlayable)
        val a = file.entries.single { it.resourceName == "com.android.settings:drawable/ic_a" }
        val b = file.entries.single { it.resourceName == "com.android.settings:drawable/ic_b" }
        assertEquals(16, a.binaryOffset)
        assertEquals(png.size, a.binarySize)
        assertEquals(uriA, a.stringData)
        assertEquals(0, a.data)
        assertEquals(16 + png.size, b.binaryOffset)
        assertEquals(png2.size, b.binarySize)
        assertEquals(uriB, b.stringData)
        assertEquals(1, b.data)
        assertContentEquals(png, FrroReader.readBinary(bytes, a))
        assertContentEquals(png2, FrroReader.readBinary(bytes, b))
        assertEquals(
            listOf(null to 0xFF0E667E.toInt(), "night" to 0xFF113355.toInt()),
            file.entries.filter { it.resourceName == "com.android.settings:color/accent" }.map { it.configuration to it.data },
        )
    }

    @Test
    fun `output does not depend on insertion order`() {
        val colors = listOf(
            FrroColor("android:color/b", 2, "night"),
            FrroColor("android:color/a", 1),
            FrroColor("android:color/b", 3),
            FrroColor("android:bool/z", 1),
            FrroColor("com.other:color/x", 4),
        )
        val bins = listOf(FrroBinary("android:drawable/y", png2), FrroBinary("android:drawable/x", png))
        val one = FrroWriter.write(FrroSpec("o", "n", "android", colors = colors, binaries = bins), path)
        val two = FrroWriter.write(FrroSpec("o", "n", "android", colors = colors.reversed(), binaries = bins.reversed()), path)
        assertContentEquals(one, two)
        val names = FrroReader.read(one).entries.map { it.resourceName to it.configuration }
        assertEquals(
            listOf(
                "android:bool/z" to null,
                "android:color/a" to null,
                "android:color/b" to null,
                "android:color/b" to "night",
                "android:drawable/x" to null,
                "android:drawable/y" to null,
                "com.other:color/x" to null,
            ),
            names,
        )
    }

    @Test
    fun `colour-only spec reproduces Samsung's file`() {
        val fixture = checkNotNull(javaClass.getResourceAsStream("/semwt_android_oneui9.frro")).use { it.readBytes() }
        val parsed = FrroReader.read(fixture)
        val spec = FrroSpec(
            parsed.packageName, parsed.name, parsed.targetPackage, parsed.targetOverlayable,
            colors = parsed.entries.map { FrroColor(it.resourceName, it.data, it.configuration) }.shuffled(java.util.Random(7)),
        )
        assertContentEquals(fixture, FrroWriter.write(spec, "/data/resource-cache/android-SemWT_android-dX00.frro"))
    }

    @Test
    fun `nine-patch and pathless binaries are rejected`() {
        val e = assertFailsWith<IllegalArgumentException> {
            FrroWriter.write(FrroSpec("o", "n", "t", binaries = listOf(FrroBinary("drawable/a", png, ninePatch = true))), path)
        }
        assertTrue(e.message!!.contains(FrroWriter.NINE_PATCH_UNSUPPORTED))
        assertFailsWith<IllegalArgumentException> { sample().toByteArray() }
        assertFailsWith<IllegalArgumentException> { FrroWriter.write(FrroSpec("o", "n", "t"), "relative.frro") }
    }

    @Test
    fun `binary uri parsing`() {
        assertEquals(Triple(path, 16L, 7L), FrroFormat.parseBinaryUri("frro:/$path?offset=16&size=7"))
        assertNull(FrroFormat.parseBinaryUri("frro:/relative?offset=1&size=2"))
        assertNull(FrroFormat.parseBinaryUri("res/drawable/a.png"))
        assertNull(FrroFormat.parseBinaryUri("frro:/$path?size=7&offset=16"))
    }

    @Test
    fun `reader output rewrites to identical bytes`() {
        for (version in 1..3) {
            val bytes = sample(version).toByteArray(path)
            val parsed = FrroReader.read(bytes)
            assertEquals(version, parsed.version)
            val rewritten = FrroWriter.from(parsed, bytes).toByteArray(path)
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
        val good = sample().toByteArray(path)
        val badVersion = good.copyOf().also { it[4] = 9 }
        assertFailsWith<FrroFormatException> { FrroReader.read(badVersion) }
        assertFailsWith<FrroFormatException> { FrroReader.read(good.copyOf(good.size - 3)) }
        val corruptCrc = good.copyOf().also { it[8] = (it[8] + 1).toByte() }
        assertEquals(false, FrroReader.read(corruptCrc).crcMatchesProto)
        // a file value whose URI points past the binary region
        val stray = FrroWriter("p", "n", "t").setString("string/a", "frro:/$path?offset=999&size=4").toByteArray()
        assertFailsWith<FrroFormatException> { FrroReader.read(stray) }
    }
}
