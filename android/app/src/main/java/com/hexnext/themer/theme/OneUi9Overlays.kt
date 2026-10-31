package com.hexnext.themer.theme

/**
 * Rebuilt Hex Installer (`project.vivid.hex.bodhi` / `#hex__30`) System RRO overlay catalog
 * for Samsung One UI 9.0 (Android 16).
 *
 * Replaces the legacy precompiled `assets/anaconda/res/anaconda_0..70` archives and builds directly
 * on top of Samsung's `com.samsung.High_contrast_theme_II.*` System RRO packages via UID 0 or UID 1000
 * with zero Theme Park (`com.samsung.android.themepark`) requirements.
 */
data class PackedAppOverlay(
    val id: String,
    val overlayName: String,
    val systemRroCarrier: String,
    val targetPackage: String,
    val label: String,
    val category: String,
    val defaultEnabled: Boolean = true,
) {
    fun buildEntries(palette: MonetPalette): List<ColorEntry> {
        val shades = palette.shades
        val accent1 = shades.getValue(ToneGroup.ACCENT1)
        val accent2 = shades.getValue(ToneGroup.ACCENT2)
        val accent3 = shades.getValue(ToneGroup.ACCENT3)
        val neutral1 = shades.getValue(ToneGroup.NEUTRAL1)
        val neutral2 = shades.getValue(ToneGroup.NEUTRAL2)

        val primaryAccent = accent1[4]   // shade 200
        val deepAccent = accent1[8]      // shade 600
        val softAccent = accent1[3]      // shade 100
        val secondaryAccent = accent2[6] // shade 400
        val tertiaryAccent = accent3[5]  // shade 300
        val bgDark = if (palette.spec.pureBlack) 0xFF000000.toInt() else neutral1[11]
        val surfaceDark = if (palette.spec.pureBlack) 0xFF080A0F.toInt() else neutral1[10]
        val cardDark = neutral2[10]
        val textPrimary = neutral1[1]

        val raw = when (id) {
            "common" -> listOf(
                "tw_color_control_activated" to primaryAccent,
                "tw_primary_color_dark" to surfaceDark,
                "sec_W_main_background_color" to bgDark,
                "sec_surface_container_dark" to cardDark,
                "sem_app_bar_bg_color_dark" to bgDark,
            )
            "systemui" -> listOf(
                "qs_panel_bg_color" to bgDark,
                "sec_qs_panel_blur_bg_color" to surfaceDark,
                "qs_tile_round_background_on" to primaryAccent,
                "qs_tile_round_background_off" to cardDark,
                "qs_tile_icon_on_tint_color" to bgDark,
                "sec_qs_large_tile_active_bg_color" to primaryAccent,
                "sec_brightness_slider_progress_color" to primaryAccent,
                "notification_guts_bg_color" to cardDark,
                "sec_notification_card_bg_color" to cardDark,
                "status_bar_clock_color" to textPrimary,
            )
            "settings" -> listOf(
                "sec_widget_round_and_bgcolor" to cardDark,
                "sec_dashboard_background_color" to bgDark,
                "sec_sub_app_bar_background_color" to surfaceDark,
                "sec_preference_summary_primary_color" to primaryAccent,
                "sec_switchbar_switch_track_on_color" to primaryAccent,
            )
            "dialer" -> listOf(
                "dialpad_background_color" to bgDark,
                "dialpad_primary_text_color" to primaryAccent,
                "dialer_call_button_bg_color" to primaryAccent,
                "sec_dialer_tab_selected_color" to secondaryAccent,
                "dialer_list_card_bg_color" to cardDark,
            )
            "incallui" -> listOf(
                "incall_background_gradient_top" to bgDark,
                "incall_background_gradient_bottom" to surfaceDark,
                "incall_button_bg_checked_color" to primaryAccent,
                "incall_call_state_label_color" to secondaryAccent,
            )
            "contacts" -> listOf(
                "contacts_main_bg_color" to bgDark,
                "contact_list_item_bg_color" to cardDark,
                "contacts_primary_accent_color" to primaryAccent,
                "contacts_fab_bg_color" to primaryAccent,
            )
            "messages" -> listOf(
                "message_list_bg_color" to bgDark,
                "message_bubble_sent_bg_color" to deepAccent,
                "message_bubble_received_bg_color" to cardDark,
                "composer_input_field_bg_color" to surfaceDark,
                "composer_send_btn_tint_color" to primaryAccent,
            )
            "honeyboard" -> listOf(
                "keyboard_bg_color" to bgDark,
                "key_bg_color" to cardDark,
                "key_functional_bg_color" to surfaceDark,
                "key_enter_bg_color" to primaryAccent,
                "key_text_primary_color" to textPrimary,
            )
            "myfiles" -> listOf(
                "myfiles_main_bg_color" to bgDark,
                "myfiles_card_bg_color" to cardDark,
                "storage_analyze_progress_primary" to primaryAccent,
                "myfiles_category_icon_tint" to secondaryAccent,
            )
            "gallery" -> listOf(
                "gallery_main_bg_color" to bgDark,
                "gallery_album_card_bg_color" to cardDark,
                "gallery_bottom_tab_selected_color" to primaryAccent,
            )
            "clock" -> listOf(
                "clock_main_bg_color" to bgDark,
                "alarm_card_bg_color" to cardDark,
                "alarm_switch_track_on_color" to primaryAccent,
            )
            "calculator" -> listOf(
                "calc_main_bg_color" to bgDark,
                "calc_keypad_btn_bg_color" to cardDark,
                "calc_equal_btn_bg_color" to primaryAccent,
                "calc_operator_text_color" to tertiaryAccent,
            )
            "calendar" -> listOf(
                "calendar_main_bg_color" to bgDark,
                "calendar_event_card_bg_color" to cardDark,
                "calendar_today_highlight_color" to primaryAccent,
            )
            "devicecare" -> listOf(
                "lool_main_bg_color" to bgDark,
                "lool_card_bg_color" to cardDark,
                "sec_device_care_score_color" to softAccent,
            )
            "home" -> listOf(
                "folder_container_bg_color" to cardDark,
                "page_indicator_active_color" to primaryAccent,
                "app_search_bar_bg_color" to surfaceDark,
            )
            "aod" -> listOf(
                "aod_clock_primary_color" to primaryAccent,
                "aod_notification_icon_tint" to secondaryAccent,
            )
            "smartcapture" -> listOf(
                "capture_toolbar_bg_color" to cardDark,
                "capture_action_icon_tint" to primaryAccent,
            )
            else -> emptyList()
        }
        return raw.map { (name, argb) -> ColorEntry("$targetPackage:color/$name", argb) }
    }

    companion object {
        val ALL: List<PackedAppOverlay> = listOf(
            PackedAppOverlay("common", "HexNextOneUI9_Android", "com.samsung.High_contrast_theme_II.common", "android", "One UI 9.0 Framework", "Core"),
            PackedAppOverlay("systemui", "HexNextOneUI9_SystemUI", "com.samsung.High_contrast_theme_II.systemui", "com.android.systemui", "SystemUI & Quick Settings", "Core"),
            PackedAppOverlay("settings", "HexNextOneUI9_Settings", "com.samsung.High_contrast_theme_II.settings", "com.android.settings", "One UI SecSettings", "Core"),
            PackedAppOverlay("dialer", "HexNextOneUI9_Dialer", "com.samsung.High_contrast_theme_II.dialer", "com.samsung.android.dialer", "Samsung Phone & Dialer", "Samsung"),
            PackedAppOverlay("incallui", "HexNextOneUI9_InCallUI", "com.samsung.High_contrast_theme_II.InCallUI", "com.samsung.android.incallui", "Samsung InCallUI", "Samsung"),
            PackedAppOverlay("contacts", "HexNextOneUI9_Contacts", "com.samsung.High_contrast_theme_II.contacts", "com.samsung.android.app.contacts", "Samsung Contacts", "Samsung"),
            PackedAppOverlay("messages", "HexNextOneUI9_Messages", "com.samsung.High_contrast_theme_II.messaging", "com.samsung.android.messaging", "Samsung Messages", "Samsung"),
            PackedAppOverlay("honeyboard", "HexNextOneUI9_HoneyBoard", "com.samsung.High_contrast_theme_II.honeyboard", "com.samsung.android.honeyboard", "Samsung HoneyBoard", "Samsung"),
            PackedAppOverlay("myfiles", "HexNextOneUI9_MyFiles", "com.samsung.High_contrast_theme_II.myfiles", "com.sec.android.app.myfiles", "Samsung My Files", "Samsung"),
            PackedAppOverlay("gallery", "HexNextOneUI9_Gallery", "com.samsung.High_contrast_theme_II.gallery3d", "com.sec.android.gallery3d", "Samsung Gallery", "Samsung"),
            PackedAppOverlay("clock", "HexNextOneUI9_Clock", "com.samsung.High_contrast_theme_II.clockpackage", "com.sec.android.app.clockpackage", "Samsung Clock", "Samsung"),
            PackedAppOverlay("calculator", "HexNextOneUI9_Calculator", "com.samsung.High_contrast_theme_II.popupcalculator", "com.sec.android.app.popupcalculator", "Samsung Calculator", "Samsung"),
            PackedAppOverlay("calendar", "HexNextOneUI9_Calendar", "com.samsung.High_contrast_theme_II.calendar", "com.samsung.android.calendar", "Samsung Calendar", "Samsung"),
            PackedAppOverlay("devicecare", "HexNextOneUI9_DeviceCare", "com.samsung.High_contrast_theme_II.lool", "com.samsung.android.lool", "Device Care", "Samsung"),
            PackedAppOverlay("home", "HexNextOneUI9_OneUIHome", "com.samsung.High_contrast_theme_II.home", "com.sec.android.app.launcher", "One UI Home", "Samsung"),
            PackedAppOverlay("aod", "HexNextOneUI9_AOD", "com.samsung.High_contrast_theme_II.aodservice", "com.samsung.android.app.aodservice", "Always On Display", "Samsung"),
            PackedAppOverlay("smartcapture", "HexNextOneUI9_SmartCapture", "com.samsung.High_contrast_theme_II.smartcapture", "com.samsung.android.app.smartcapture", "Smart Capture", "Samsung"),
        )
    }
}
