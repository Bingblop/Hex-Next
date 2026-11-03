package com.hexnext.engine.compile

import com.hexnext.engine.compile.Fixtures.ref
import com.hexnext.engine.compile.Fixtures.semwt
import com.hexnext.engine.palette.ShadeReference
import com.hexnext.engine.palette.ThemeResolver
import com.hexnext.engine.palette.ThemeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Colours whose meaning is their hue keep it when a group colour is set. */
class HueMeaningColorTest {
    private val settings = "com.android.settings"
    private val rules9 = OneUiRules.load(9).copy(renamed = emptyMap())
    private val reference = ShadeReference(Fixtures.reference)
    private val user = 0xFF1E3AE5.toInt()

    @Test
    fun `SemWT Sunday red is not recoloured by Primary text`() {
        val sunday = 0xFFDB332A.toInt()
        val sundayFaded = 0x40DB332A
        val title = ref("system_neutral1_900")
        val file = semwt(
            settings,
            listOf(
                Triple("sesl_date_picker_sunday_number_text_color_light", sunday, ""),
                Triple("sesl_date_picker_sunday_text_color_light", sundayFaded, ""),
                Triple("sesl_action_bar_text_color_title_light", title, ""),
            ),
        )
        val spec = ThemeSpec.default().copy(source = ThemeSource(0xFF2E9E44.toInt()))
            .withGroupColor(ColorGroup.TEXT_PRIMARY, false, user)
        val resolved = ThemeResolver.resolve(spec.source)
        val d = ThemeCompiler.planColors(settings, null, file, reference, spec, rules9) { resolved }

        assertEquals(sunday, d.values.getValue("color/sesl_date_picker_sunday_number_text_color_light" to ""))
        assertEquals(sundayFaded, d.values.getValue("color/sesl_date_picker_sunday_text_color_light" to ""))
        assertFalse(("color/sesl_date_picker_sunday_number_text_color_light" to "") in d.reached)
        // A neutral title colour still takes the group colour.
        assertEquals(user, d.values.getValue("color/sesl_action_bar_text_color_title_light" to ""))
        assertTrue(("color/sesl_action_bar_text_color_title_light" to "") in d.reached)
    }

    @Test
    fun `Sunday and Saturday names are excluded from the table path`() {
        assertTrue(ColorGroupMatcher.TABLE_EXCLUDED.containsMatchIn("sesl_date_picker_sunday_text_color_dark"))
        assertTrue(ColorGroupMatcher.TABLE_EXCLUDED.containsMatchIn("calendar_saturday_text_color"))
    }
}
