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
        val byName = zip(dir, "iolite.zip", "iolite")
        val renamed = zip(dir, "download-123.hexnext", "willemite")
        val indexed = zip(dir, "kyanite.bin", "kyanite")
        File(dir, "index.json").writeText("""{"format":1,"packs":[{"id":"kyanite","name":"Kyanite","file":"kyanite.bin"}]}""")

        val resolver = DirectoryPackResolver(dir)
        assertEquals("iolite", resolver.pack("iolite")?.id)
        assertEquals(byName, resolver.zipFile("iolite"))
        assertEquals(renamed, resolver.zipFile("willemite"))
        assertEquals(indexed, resolver.zipFile("kyanite"))
        assertEquals(setOf("pack.json", "layers/once/base/xx1/qs/ic_a.png"), resolver.entryNames("iolite"))
        assertEquals(listOf("iolite", "kyanite", "willemite"), resolver.availableIds())
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
        zip(dir, "iolite.zip", "something-else")
        val resolver = DirectoryPackResolver(dir)
        assertNull(resolver.pack("iolite"))
        assertNotNull(resolver.pack("something-else"))
    }

    @Test
    fun `an absent directory is empty`() {
        val resolver = DirectoryPackResolver(File(tmp.root, "none"))
        assertTrue(resolver.availableIds().isEmpty())
        assertNull(resolver.pack("x"))
    }
}
