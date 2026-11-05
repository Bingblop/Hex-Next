package com.hexnext.engine.compile

import com.hexnext.engine.compile.Fixtures.table
import com.hexnext.engine.palette.ThemeResolver
import com.hexnext.engine.palette.ThemeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

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
        println("CALC " + c.entries.joinToString { "${it.key}=${"%08x".format(it.value)}" })
        val accentDay = ColorGroup.ACCENT.auto(theme, night = false)
        assertEquals(accentDay, c["color/calc_keypad_operation_button_bg" to ""])
        assertEquals(accentDay, c["color/keypad_equal_bg" to ""])
        assertNotNull(c["color/keypad_operation" to ""])
        assertNotNull(c["color/keypad_equal" to "night"], "the = symbol needs a night colour on the night accent")
    }
}
