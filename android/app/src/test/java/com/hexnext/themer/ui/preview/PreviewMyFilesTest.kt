package com.hexnext.themer.ui.preview

import com.hexnext.themer.ui.studio.PreviewIcon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PreviewMyFilesTest {
    @Test
    fun `categories use resource keys to avoid duplicating defaults`() {
        val icons = categories(
            listOf(
                PreviewIcon("home_category_image", "Home category image", null, 0),
                PreviewIcon("home_category_images", "Images", null, 0),
                PreviewIcon("audio", "Audio", null, 0),
                PreviewIcon("file_type_installation_files", "Installation files", null, 0),
            ),
        )

        assertEquals(8, icons.size)
        assertEquals(
            listOf("home_category_image", "audio", "file_type_installation_files"),
            icons.take(3).map { it.key },
        )
        assertFalse(icons.any { it.key == "default:images" })
        assertFalse(icons.any { it.key == "default:audio" })
        assertFalse(icons.any { it.key == "default:apk" })
    }
}
