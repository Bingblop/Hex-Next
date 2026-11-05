package com.hexnext.themer.ui.settings

import com.hexnext.themer.ui.settings.AppSettingsStore.Companion.enumOrDefault
import com.hexnext.themer.ui.settings.AppSettingsStore.Companion.jsonString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppSettingsImportTest {
    @Test
    fun onlyJsonStringsAreReadAsEnumNames() {
        assertEquals("DARK", jsonString("DARK"))
        // Absent keys (opt returns null), JSON null (a non-string sentinel) and other types.
        assertNull(jsonString(null))
        assertNull(jsonString(Any()))
        assertNull(jsonString(1))
        assertNull(jsonString(false))
    }

    @Test
    fun knownNamesReplaceTheCurrentValue() {
        assertEquals(ThemeMode.LIGHT, enumOrDefault(jsonString("LIGHT"), ThemeMode.DARK))
        for (source in PaletteSource.entries) {
            assertEquals(source, enumOrDefault(jsonString(source.name), PaletteSource.entries.first()))
        }
        for (level in ContrastLevel.entries) {
            assertEquals(level, enumOrDefault(jsonString(level.name), ContrastLevel.entries.first()))
        }
    }

    @Test
    fun missingOrUnknownValuesKeepTheCurrentValue() {
        val current = ThemeMode.DARK
        for (raw in listOf<Any?>(null, Any(), 2, true, "", "null", "dark", "Dark", "UNKNOWN")) {
            assertEquals("raw=$raw", current, enumOrDefault(jsonString(raw), current))
        }
        val palette = PaletteSource.entries.last()
        assertEquals(palette, enumOrDefault(jsonString(null), palette))
        val contrast = ContrastLevel.entries.last()
        assertEquals(contrast, enumOrDefault(jsonString("NOT_A_LEVEL"), contrast))
    }
}
