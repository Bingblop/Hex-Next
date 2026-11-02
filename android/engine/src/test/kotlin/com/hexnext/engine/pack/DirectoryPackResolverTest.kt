package com.hexnext.engine.pack

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DirectoryPackResolverTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun zip(dir: File, name: String, id: String, layer: String = "layers/once/base/xx1/qs/ic_a.png"): File {
        val file = File(dir, name)
        ZipOutputStream(file.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("pack.json"))
            z.write("""{"id":"$id","name":"$id","layers":[]}""".toByteArray())
            z.closeEntry()
            z.putNextEntry(ZipEntry(layer))
            z.write(byteArrayOf(1))
            z.closeEntry()
        }
        return file
    }

    @Test
    fun `finds packs by file name, by declared id and through index json`() {
        val dir = tmp.newFolder("packs")
        val byName = zip(dir, "deluxe.zip", "deluxe")
        val renamed = zip(dir, "download-123.hexnext", "glass")
        val indexed = zip(dir, "aosp-r.bin", "aosp-r")
        File(dir, "index.json").writeText("""{"format":1,"packs":[{"id":"aosp-r","name":"AOSP R","file":"aosp-r.bin"}]}""")

        val resolver = DirectoryPackResolver(dir)
        assertEquals("deluxe", resolver.pack("deluxe")?.id)
        assertEquals(byName, resolver.zipFile("deluxe"))
        assertEquals(renamed, resolver.zipFile("glass"))
        assertEquals(indexed, resolver.zipFile("aosp-r"))
        assertEquals(setOf("pack.json", "layers/once/base/xx1/qs/ic_a.png"), resolver.entryNames("deluxe"))
        assertEquals(listOf("aosp-r", "deluxe", "glass"), resolver.availableIds())
    }

    @Test
    fun `missing and broken packs resolve to null`() {
        val dir = tmp.newFolder("packs")
        File(dir, "broken.zip").writeText("not a zip")
        val resolver = DirectoryPackResolver(dir)
        assertNull(resolver.pack("broken"))
        assertNull(resolver.pack("nothing"))
        assertTrue(resolver.entryNames("nothing").isEmpty())
        assertTrue("broken.zip" in resolver.errors)
    }

    @Test
    fun `a zip whose pack json declares another id is not used`() {
        val dir = tmp.newFolder("packs")
        zip(dir, "deluxe.zip", "something-else")
        val resolver = DirectoryPackResolver(dir)
        assertNull(resolver.pack("deluxe"))
        assertNotNull(resolver.pack("something-else"))
    }

    @Test
    fun `an absent directory is empty`() {
        val resolver = DirectoryPackResolver(File(tmp.root, "none"))
        assertTrue(resolver.availableIds().isEmpty())
        assertNull(resolver.pack("x"))
    }
}
