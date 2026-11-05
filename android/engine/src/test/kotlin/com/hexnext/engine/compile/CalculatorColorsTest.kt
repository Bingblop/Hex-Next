package com.hexnext.engine.compile

import com.hexnext.engine.compile.Fixtures.table
import com.hexnext.engine.palette.ThemeResolver
import com.hexnext.engine.palette.ThemeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Samsung Calculator's operator and = keys follow the primary (accent) colour, with readable symbols. */
class CalculatorColorsTest {
    private val calc = "com.sec.android.app.popupcalculator"
    private val source = ThemeSource(0xFF3E91FF.toInt())

    private fun compile(): Map<Pair<String, String>, Int> {
        val t = table(calc, listOf("calc_keypad_operation_button_bg", "keypad_equal_bg", "keypad_operation", "keypad_equal", "keypad_number"))
        val device = DeviceSnapshot(Fixtures.profile9, mapOf(calc to TargetSnapshot(calc, installed = true, table = t)), Fixtures.reference)
        val spec = ThemeSpec.default().copy(source = source)
        return ThemeCompiler.compile(spec, device, InMemoryPackResolver(emptyList()), OneUiRules.load(9)) { 1L }
            .target(calc)?.colors.orEmpty().associate { (it.resourceName.substringAfter(':') to it.configuration) to it.argb }
    }

    @Test
    fun `operator and equals keys use the accent, symbols the ink on it`() {
        val theme = ThemeResolver.resolve(source)
        val c = compile()
        val accentDay = ColorGroup.CALCULATOR_KEYS.auto(theme, night = false)
        assertEquals(accentDay, c["color/calc_keypad_operation_button_bg" to ""])
        assertEquals(accentDay, c["color/keypad_equal_bg" to ""])
        assertNotNull(c["color/keypad_operation" to ""])
        assertNotNull(c["color/keypad_equal" to "night"], "the = symbol needs a night colour on the night accent")
    }

    private fun compileApp(pkg: String, names: List<String>, spec: ThemeSpec = ThemeSpec.default().copy(source = source)): Map<Pair<String, String>, Int> {
        val t = table(pkg, names)
        val device = DeviceSnapshot(Fixtures.profile9, mapOf(pkg to TargetSnapshot(pkg, installed = true, table = t)), Fixtures.reference)
        return ThemeCompiler.compile(spec, device, InMemoryPackResolver(emptyList()), OneUiRules.load(9)) { 1L }
            .target(pkg)?.colors.orEmpty().associate { (it.resourceName.substringAfter(':') to it.configuration) to it.argb }
    }

    @Test
    fun `voice recorder's record button follows the primary colour`() {
        val theme = ThemeResolver.resolve(source)
        val c = compileApp("com.sec.android.app.voicenote", listOf("basic_token_sys_red", "basic_token_sys_red_light", "basic_token_sys_red_dark"))
        assertEquals(ColorGroup.VOICE_RECORDER_BUTTON.auto(theme, night = false), c["color/basic_token_sys_red_light" to ""])
        assertEquals(ColorGroup.VOICE_RECORDER_BUTTON.auto(theme, night = true), c["color/basic_token_sys_red_dark" to "night"] ?: c["color/basic_token_sys_red_dark" to ""])
    }

    @Test
    fun `contact tiles keep Samsung's colours on Auto and take the user's colour when set`() {
        val pkg = "com.samsung.android.app.contacts"
        val names = listOf("color_palette_3", "color_palette_7_2")
        val auto = compileApp(pkg, names)
        assertNull(auto["color/color_palette_3" to ""], "Auto leaves the colourful tiles alone")
        val red = 0xFFE53935.toInt()
        val spec = ThemeSpec.default().copy(source = source).withGroupColor(ColorGroup.CONTACT_TILES, false, red).withGroupColor(ColorGroup.CONTACT_TILES, true, red)
        val set = compileApp(pkg, names, spec)
        assertEquals(red, set["color/color_palette_3" to ""])
        assertEquals(red, set["color/color_palette_7_2" to ""])
    }
}
