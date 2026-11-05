package com.hexnext.themer.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PackDirsTest {
    private val files = File("/data/user/0/app/files")

    @Test
    fun anApplyNeverReadsThePreviewOnlyDirectory() {
        val apply = PackDirs.forApply(files)
        assertEquals(listOf(File(files, "packs")), apply)
        assertFalse(apply.any { it.name == PackDirs.PREVIEW })
    }

    @Test
    fun thePreviewReadsBothAndInstalledPacksWin() {
        val preview = PackDirs.forPreview(files)
        assertEquals(listOf(File(files, "packs"), File(files, "packs-preview")), preview)
        assertTrue(preview.first().name == PackDirs.APPLY)
    }
}
