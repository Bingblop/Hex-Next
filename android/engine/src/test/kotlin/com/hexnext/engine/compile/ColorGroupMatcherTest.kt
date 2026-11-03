package com.hexnext.engine.compile

import com.hexnext.engine.palette.InferredColor
import com.hexnext.engine.palette.PaletteRole
import com.hexnext.engine.palette.ShadeScale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ColorGroupMatcherTest {
    private val rules = OneUiRules.load(9)
    private val settings = "com.android.settings"
    private val android = "android"
    private val sysui = "com.android.systemui"

    private fun shade(role: PaletteRole, shade: Int, deltaE: Double = 0.5, alpha: Int = 255) =
        InferredColor(role, ShadeScale.indexOf(shade), alpha, deltaE)

    private fun group(name: String, pkg: String = settings, inferred: InferredColor? = null) =
        ColorGroupMatcher.classify(pkg, "color/$name", inferred, rules)

    private fun assertGroups(expected: ColorGroup?, pkg: String, vararg names: String) {
        for (n in names) assertEquals(expected, group(n, pkg), n)
    }

    @Test
    fun `real One UI 9 Settings and framework names land in their groups`() {
        // Names from the Settings SemWT idmap dump and the framework SemWT fixture.
        assertGroups(
            ColorGroup.SWITCH, settings,
            "sesl_switch_track_on_color_light", "sesl_switch_track_on_color_dark", "sesl_switchbar_on_background_color_light",
            "sec_widget_switchbar_checked_background_color",
        )
        assertGroups(ColorGroup.SWITCH, android, "tw_switch_on_mtrl", "sem_switch_track_on_mtrl_dark", "qs_switch_off_color")
        assertGroups(
            ColorGroup.SLIDER, settings,
            "sesl_seekbar_control_color_activated", "sesl_progress_control_color_activated_light", "sec_volume_limit_thumb_drawable_solid_color",
            "data_usage_progress_bar_color", "sesl_tick_mark_seekbar_level",
        )
        assertGroups(ColorGroup.SLIDER, android, "tw_seekbar_color_control_activated_light", "tw_progress_color_control_normal_dark")
        assertGroups(
            ColorGroup.BUTTON, settings,
            "sesl_btn_background_color_light", "sec_widget_color_round_button_bg_color", "sec_wifi_nearby_share_button_background",
        )
        assertGroups(
            ColorGroup.ACCENT, settings,
            "sesl_primary_color_light", "sec_tips_description_link_text_color", "sec_widget_relative_link_text_color",
            "sesl_tablayout_selected_indicator_color", "sesl_loading_progress_color1", "sesl_swipe_refresh_color1", "sec_highlight_text_color",
        )
        assertGroups(ColorGroup.ACCENT, android, "sem_color_primary", "tw_color_control_activated", "link_text_light", "sem_tab_selected_text_color")
        assertGroups(
            ColorGroup.TEXT_PRIMARY, settings,
            "actionbar_text_color", "sec_app_name_text_color", "sesl_action_bar_text_color_title_light", "sesl_search_view_text_color",
            "sec_wifi_action_menu_text", "sesl_switchbar_on_text_color_light",
        )
        assertGroups(
            ColorGroup.TEXT_SECONDARY, settings,
            "sec_dashboard_simplified_summary_text_color", "sesl_action_bar_text_color_subtitle_light", "sesl_extended_appbar_subtitle_light",
            "sec_no_item_sub_text_color", "sesl_search_view_hint_text_color", "sec_app_state_text_color", "wifi_hidden_ap_list_empty_text",
        )
        assertGroups(ColorGroup.TEXT_SECONDARY, android, "sem_text_color_subtitle_material_toolbar", "sem_search_view_no_background_hint_text_color_dark")
        assertGroups(
            ColorGroup.ICON_TINT, settings,
            "sesl_app_picker_list_action_icon_tint_color", "sec_volume_icon_color", "sec_wifi_icon_blue_color", "sesl_search_view_icon_color",
        )
        assertGroups(
            ColorGroup.BACKGROUND, settings,
            "sesl_round_and_bgcolor_light", "sec_widget_round_and_bgcolor", "sesl_background_color_light", "winset_content_background_color_theme",
        )
        assertGroups(ColorGroup.BACKGROUND, android, "tw_screen_background_color_light", "sem_round_and_bgcolor_dark")
        assertGroups(
            ColorGroup.SURFACE, settings,
            "sec_suggestion_card_background_color", "sesl_action_bar_background_color_light", "sesl_bottom_navigation_background_light",
        )
        assertGroups(ColorGroup.SURFACE, android, "sem_app_bar_bg_color")
        assertGroups(ColorGroup.SYSTEM_BARS, android, "tw_navigation_bar_color", "tw_status_bar_color")
    }

    @Test
    fun `Quick panel names land in the Quick panel groups`() {
        assertGroups(ColorGroup.QS_PANEL_BG, sysui, "quick_panel_bg", "qs_background_dark", "sec_qs_detail_bg_color", "notification_panel_background_color")
        assertGroups(ColorGroup.QS_TILE_ACTIVE, sysui, "qs_tile_round_background_on", "qs_tile_bg_active", "sec_qs_tile_bg_enabled")
        assertGroups(ColorGroup.QS_TILE_INACTIVE, sysui, "qs_tile_round_background_off", "qs_tile_bg_inactive", "qs_tile_bg_unavailable")
        assertGroups(ColorGroup.NOTIFICATION_BG, sysui, "notification_bg", "notification_guts_bg_color", "notification_card_background")
        assertGroups(ColorGroup.SLIDER, sysui, "brightness_slider_bg", "volume_slider_progress_color")
        // A stateless tile colour follows its value: accent is the on state, neutral the off state.
        assertEquals(ColorGroup.QS_TILE_ACTIVE, group("qs_tile_bg", sysui, shade(PaletteRole.ACCENT1, 500)))
        // Foreground parts of a tile never join the tile fill.
        assertNotEquals(ColorGroup.QS_TILE_ACTIVE, group("sec_qs_tile_icon_on_color", sysui))
        assertEquals(ColorGroup.TEXT_PRIMARY, group("qs_tile_label_on_color", sysui))
    }

    @Test
    fun `no false positives on obvious names`() {
        // "unselected" is not "selected"; the text stays text.
        assertEquals(ColorGroup.TEXT_PRIMARY, group("tw_tab_unselected_text_color", android))
        // Text and icons never become a backdrop.
        for (n in listOf("sesl_action_bar_text_color_title_dark", "sem_search_view_no_background_text_color_light", "sesl_search_view_icon_color_dark")) {
            val g = group(n, android)
            assertTrue(g != ColorGroup.BACKGROUND && g != ColorGroup.SURFACE, "$n -> $g")
        }
        // Strokes, lines and dots belong to no group.
        assertGroups(
            null, settings,
            "sesl_menu_popup_background_stroke_color", "sec_lock_pattern_line_color_confirm", "usage_graph_dots",
            "sesl_tablayout_subtab_background_stroke_color_light",
        )
        // The Settings lock pages are not the lock screen.
        assertNotEquals(ColorGroup.LOCK_SCREEN, group("sec_lockscreen_biometrics_summary_color"))
        assertNotEquals(ColorGroup.LOCK_SCREEN, group("sec_lock_suw_background_color"))
        // A switch name in text form is text, not the switch fill.
        assertEquals(ColorGroup.TEXT_PRIMARY, group("sesl_switchbar_on_text_color_dark"))
        // Only colours are classified.
        assertNull(ColorGroupMatcher.classify(settings, "drawable/sesl_switch_track_on", null, rules))
        assertNull(ColorGroupMatcher.classify(settings, "color/", null, rules))
    }

    @Test
    fun `a name rule only claims colours whose tone fits the group`() {
        val white = shade(PaletteRole.NEUTRAL1, 10)
        val accent = shade(PaletteRole.ACCENT1, 600)
        // The track is the accent fill; the white thumb drawn on it is not.
        assertEquals(ColorGroup.SWITCH, group("sesl_switch_track_on_color_light", inferred = accent))
        assertNull(group("sesl_switch_thumb_on_color", inferred = white))
        assertNull(group("sec_volume_limit_thumb_color", inferred = white))
        // The off switch bar is a white backdrop, so it falls through to the backdrop rules.
        assertEquals(ColorGroup.SURFACE, group("sesl_switchbar_off_background_color_light", inferred = white))
        // Pure white and black tie to accent1, but they are neutral.
        assertNull(group("sesl_switch_thumb_off_color", inferred = shade(PaletteRole.ACCENT1, 0)))
        // An accent-tinted backdrop name falls through to the accent fallback.
        assertEquals(ColorGroup.ACCENT, group("sec_widget_relative_link_bg_color", inferred = shade(PaletteRole.ACCENT1, 500, alpha = 0x1a)))
        // A colour without a reliable shade (a fixed Samsung colour) is matched by name alone.
        assertEquals(ColorGroup.SWITCH, group("sesl_switch_thumb_on_color", inferred = shade(PaletteRole.NEUTRAL1, 10, deltaE = 30.0)))
        // Text takes any tone.
        assertEquals(ColorGroup.TEXT_PRIMARY, group("actionbar_text_color", inferred = shade(PaletteRole.ACCENT1, 800)))
    }

    @Test
    fun `backdrops are settled between screen and card by shade`() {
        // One UI: the grey screen behind the cards is shade 50 by day and 900 by night ...
        assertEquals(ColorGroup.BACKGROUND, group("sesl_round_and_bgcolor_light", inferred = shade(PaletteRole.NEUTRAL1, 50)))
        assertEquals(ColorGroup.BACKGROUND, group("sesl_round_and_bgcolor_dark", inferred = shade(PaletteRole.NEUTRAL1, 900)))
        // ... and the cards are 10 by day and 800 by night, whatever the name says.
        assertEquals(ColorGroup.SURFACE, group("sesl_background_color_light", inferred = shade(PaletteRole.NEUTRAL1, 10)))
        assertEquals(ColorGroup.SURFACE, group("sesl_background_color_dark", inferred = shade(PaletteRole.NEUTRAL1, 800)))
        assertEquals(ColorGroup.BACKGROUND, group("sesl_action_bar_background_color_light", inferred = shade(PaletteRole.NEUTRAL2, 50)))
        // A mid grey keeps the group its name gives.
        assertEquals(ColorGroup.SURFACE, group("sec_suggestion_card_background_color", inferred = shade(PaletteRole.NEUTRAL2, 400)))
    }

    @Test
    fun `the fallback uses the inferred shade`() {
        assertEquals(ColorGroup.ACCENT, group("sec_dashboard_account_default_id_color", inferred = shade(PaletteRole.ACCENT1, 700)))
        assertEquals(ColorGroup.ACCENT, group("wifi_ap_connecting", inferred = shade(PaletteRole.ACCENT3, 400)))
        assertEquals(ColorGroup.BACKGROUND, group("picker_app_list_window", inferred = shade(PaletteRole.NEUTRAL1, 1000)))
        assertEquals(ColorGroup.BACKGROUND, group("some_unknown_colour", inferred = shade(PaletteRole.NEUTRAL1, 900)))
        assertEquals(ColorGroup.SURFACE, group("some_unknown_colour", inferred = shade(PaletteRole.NEUTRAL2, 800)))
        assertEquals(ColorGroup.SURFACE, group("some_unknown_colour", inferred = shade(PaletteRole.NEUTRAL1, 10)))
        // Mid greys, foreground parts and unreliable matches stay out.
        assertNull(group("some_unknown_colour", inferred = shade(PaletteRole.NEUTRAL1, 500)))
        assertNull(group("sec_locale_drag_cell_stroke_color", inferred = shade(PaletteRole.NEUTRAL1, 900)))
        assertNull(group("sec_data_usage_graph_line_color", inferred = shade(PaletteRole.NEUTRAL2, 50)))
        assertNull(group("some_unknown_colour", inferred = shade(PaletteRole.ACCENT1, 500, deltaE = 6.5)))
        assertNull(group("some_unknown_colour", inferred = shade(PaletteRole.ACCENT1, 500, alpha = 0)))
        assertNull(group("some_unknown_colour"))
    }

    @Test
    fun `app rules cover the keyboard, the dialer, AOD and the lock screen`() {
        val keyboard = "com.samsung.android.honeyboard"
        assertEquals(ColorGroup.KEYBOARD_KEY, group("keyboard_key_bg_color", keyboard))
        assertEquals(ColorGroup.KEYBOARD_KEY, group("honeyboard_keycap_color", keyboard))
        assertEquals(ColorGroup.KEYBOARD_BG, group("keyboard_background_color", keyboard))
        assertEquals(ColorGroup.KEYBOARD_BG, group("keyboard_bg_color", keyboard))
        // Key labels are text; an accent key is an accent.
        assertEquals(ColorGroup.TEXT_PRIMARY, group("keyboard_key_text_color", keyboard))
        assertEquals(ColorGroup.ACCENT, group("keyboard_key_action_color", keyboard, shade(PaletteRole.ACCENT1, 600)))
        // Generic backdrops of the keyboard are remapped onto the keyboard groups.
        assertEquals(ColorGroup.KEYBOARD_BG, group("sesl_round_and_bgcolor_light", keyboard))
        assertEquals(ColorGroup.KEYBOARD_KEY, group("some_unknown_colour", keyboard, shade(PaletteRole.NEUTRAL1, 10)))

        val dialer = "com.samsung.android.dialer"
        assertEquals(ColorGroup.DIALER, group("dialer_call_button_color", dialer))
        assertEquals(ColorGroup.DIALER, group("dialpad_digit_key_color", dialer))
        assertEquals(ColorGroup.DIALER, group("call_accept_color", "com.samsung.android.incallui"))
        assertEquals(ColorGroup.DIALER, group("sesl_btn_background_color_light", dialer))
        // The dialer's screen background stays a background; a dialog is not the dialer.
        assertEquals(ColorGroup.BACKGROUND, group("sesl_round_and_bgcolor_light", dialer))
        assertNotEquals(ColorGroup.DIALER, group("dialog_title_text_color", dialer))

        val aod = "com.samsung.android.app.aodservice"
        assertEquals(ColorGroup.LOCK_SCREEN, group("aod_clock_text_color", aod))
        assertEquals(ColorGroup.LOCK_SCREEN, group("clock_date_color", aod))
        assertNotEquals(ColorGroup.LOCK_SCREEN, group("aod_background_color", aod))

        assertEquals(ColorGroup.LOCK_SCREEN, group("keyguard_clock_color", sysui))
        assertEquals(ColorGroup.LOCK_SCREEN, group("lock_icon_color", sysui))
        assertNotEquals(ColorGroup.LOCK_SCREEN, group("keyguard_clock_color", settings))
    }

    @Test
    fun `group values keep the observed alpha and accent containers keep their tone`() {
        val red = 0xFFE53935.toInt()
        // A strong accent becomes the user colour exactly, with Samsung's alpha.
        assertEquals(0x1AE53935, ColorGroupMatcher.groupValue(ColorGroup.SWITCH, red, 0x1A1E88E5, shade(PaletteRole.ACCENT1, 500)))
        assertEquals(red, ColorGroupMatcher.groupValue(ColorGroup.SWITCH, red, 0xFF1E88E5.toInt(), shade(PaletteRole.ACCENT1, 500)))
        // A pale accent container becomes a pale red container, not a solid red block.
        val pale = ColorGroupMatcher.groupValue(ColorGroup.BUTTON, red, 0xFFDDE7F5.toInt(), shade(PaletteRole.ACCENT1, 100))
        assertNotEquals(red, pale)
        assertTrue(com.hexnext.engine.palette.ColorMath.lstar(pale) > 80, "container stays light")
        // Neutral groups always get the colour exactly.
        val navy = 0xFF0B1F3A.toInt()
        assertEquals(navy, ColorGroupMatcher.groupValue(ColorGroup.BACKGROUND, navy, 0xFFF2F2F2.toInt(), shade(PaletteRole.NEUTRAL1, 50)))
        // Stored user colours are forced opaque before the observed alpha is applied.
        assertEquals(0x80123456.toInt(), ColorGroupMatcher.groupValue(ColorGroup.BACKGROUND, 0x00123456, 0x80FFFFFF.toInt(), null))
    }
}
