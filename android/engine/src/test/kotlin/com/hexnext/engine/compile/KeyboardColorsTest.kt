package com.hexnext.engine.compile

import com.hexnext.engine.arsc.ConfiguredValue
import com.hexnext.engine.arsc.ResourceEntry
import com.hexnext.engine.arsc.ResourceTable
import com.hexnext.engine.arsc.ResourceValue
import com.hexnext.engine.palette.ShadeReference
import com.hexnext.engine.palette.ThemeResolver
import com.hexnext.engine.palette.ThemeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Samsung Keyboard's key and backdrop colours are aliases of other colours (device lab, lab-021:
 * `text_key_background_dark` → `@color/…` → #ff3a3a3d). The exact keyboard names follow them, so
 * the overlay writes the keyboard groups' colours over them.
 */
class KeyboardColorsTest {
    private val keyboard = "com.samsung.android.honeyboard"
    private val rules9 = OneUiRules.load(9).copy(renamed = emptyMap())
    private val reference = ShadeReference(Fixtures.reference)

    private fun colour(name: String, id: Int, value: ResourceValue) =
        ResourceEntry("color", name, id, listOf(ConfiguredValue("", value)))

    private val table = ResourceTable(
        keyboard, 0x7f,
        listOf(
            colour("basic_grey_3a", 0x7f010000, ResourceValue.Color(0xFF3A3A3D.toInt())),
            colour("basic_grey_17", 0x7f010001, ResourceValue.Color(0xFF17171A.toInt())),
            colour("basic_black", 0x7f010002, ResourceValue.Color(0xFF010102.toInt())),
            colour("text_key_background_dark", 0x7f010003, ResourceValue.Reference(0x7f010000, attr = false)),
            colour("function_key_background_dark", 0x7f010004, ResourceValue.Reference(0x7f010001, attr = false)),
            colour("keyboard_background_dark", 0x7f010005, ResourceValue.Reference(0x7f010002, attr = false)),
            // Transparent in Samsung's "solid" style: the alpha stays.
            colour("text_key_background_dark_solid", 0x7f010006, ResourceValue.Color(0)),
        ),
    )

    @Test
    fun `aliased keyboard colours take the keyboard groups`() {
        val key = 0xFF224466.toInt()
        val special = 0xFF113355.toInt()
        val board = 0xFF001122.toInt()
        val spec = ThemeSpec.default().copy(source = ThemeSource(0xFF2E9E44.toInt()))
            .withGroupColor(ColorGroup.KEYBOARD_KEY, true, key)
            .withGroupColor(ColorGroup.KEYBOARD_FUNCTION_KEY, true, special)
            .withGroupColor(ColorGroup.KEYBOARD_BG, true, board)
        val resolved = ThemeResolver.resolve(spec.source)
        val d = ThemeCompiler.planColors(keyboard, table, null, reference, spec, rules9) { resolved }

        assertEquals(key, d.values.getValue("color/text_key_background_dark" to "night"))
        assertEquals(special, d.values.getValue("color/function_key_background_dark" to "night"))
        assertEquals(board, d.values.getValue("color/keyboard_background_dark" to "night"))
        assertTrue((d.values["color/text_key_background_dark_solid" to "night"] ?: 0) ushr 24 == 0)
    }

    @Test
    fun `on Auto the aliased keyboard colours still follow the theme`() {
        val spec = ThemeSpec.default().copy(source = ThemeSource(0xFF2E9E44.toInt()))
        val resolved = ThemeResolver.resolve(spec.source)
        val d = ThemeCompiler.planColors(keyboard, table, null, reference, spec, rules9) { resolved }
        assertEquals(
            spec.effectiveGroupColor(ColorGroup.KEYBOARD_KEY, resolved, true),
            d.values.getValue("color/text_key_background_dark" to "night"),
        )
    }
}
