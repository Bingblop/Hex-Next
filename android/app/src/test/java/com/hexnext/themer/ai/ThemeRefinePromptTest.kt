package com.hexnext.themer.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeRefinePromptTest {
    private val current = ThemeSuggestion(
        name = "Night tide",
        seedArgb = 0xFF1E88E5.toInt(),
        accents = listOf(0xFF00BFA5.toInt()),
        mode = "dark",
        packStyle = "minimal",
        notes = "calm",
    )

    @Test
    fun promptCarriesTheCurrentThemeAndTheChange() {
        val prompt = ThemeSuggester.refinePrompt("deep ocean", listOf("more teal"), current, "  warmer  ")
        assertTrue(prompt.contains("Original description: deep ocean"))
        assertTrue(prompt.contains("- more teal"))
        assertTrue(prompt.contains("\"seed\":\"#1E88E5\""))
        assertTrue(prompt.contains("\"accents\":[\"#00BFA5\"]"))
        assertTrue(prompt.contains("keep everything this does not mention: warmer\n"))
        assertTrue(prompt.endsWith("same JSON format."))
    }

    @Test
    fun historyIsCappedAndBlankChangesDropped() {
        val earlier = (1..12).map { "change $it" } + "   "
        val prompt = ThemeSuggester.refinePrompt("x", earlier, current, "y")
        assertFalse(prompt.contains("- change 4\n"))
        assertTrue(prompt.contains("- change 5\n"))
        assertEquals(8, prompt.lines().count { it.startsWith("- ") })
    }

    @Test
    fun longTextIsCut() {
        val prompt = ThemeSuggester.refinePrompt("a".repeat(1000), emptyList(), current, "b".repeat(1000))
        assertFalse(prompt.contains("a".repeat(301)))
        assertFalse(prompt.contains("b".repeat(301)))
        assertFalse(prompt.contains("Changes already made"))
    }
}
