package com.hexnext.themer.theme

/**
 * Rebuilt Hex Installer (`project.vivid.hex.bodhi` / `#hex__30`) & Niferpitou (`terpsichora_`)
 * from-scratch RRO overlay catalog for Samsung One UI 9.0 (Android 16).
 *
 * Replaces the legacy precompiled `assets/anaconda/res/anaconda_0..70` archives and builds new
 * overlays from scratch (`com.hexnext.oneui9.rro.*`) that take highest-priority precedence
 * over Samsung's modern `SemWT_*` (Samsung Wallpaper Theme) overlays via UID 0 or UID 1000.
 * Does NOT use High Contrast Theme overlays. Zero Theme Park requirements.
 */
data class PackedAppOverlay(
    val id: String,
    val overlayName: String,
    val overlayPackage: String,
    val semWtOverridden: String,
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
            "settings" -> {
                val spec = palette.spec
                val iconTint = when (spec.settingsIconStyle) {
                    SettingsIconStyle.STYLE_2SETT, SettingsIconStyle.STYLE_ALT_2SETT -> secondaryAccent
                    else -> primaryAccent
                }
                val iconFrame = if (spec.framelessIcons) 0x00000000 else surfaceDark
                val dividerColor = if (spec.showListDividers) surfaceDark else cardDark
                val emailColor = if (spec.hideAccountEmail) cardDark else neutral2[4]

                val baseSettings = listOf(
                    "sec_widget_round_and_bgcolor" to cardDark,
                    "sec_dashboard_background_color" to bgDark,
                    "sec_dashboard_tab_selected_color" to primaryAccent,
                    "sec_sub_app_bar_background_color" to surfaceDark,
                    "list_item_background_color" to cardDark,
                    "sec_dashboard_icon_tint_color" to iconTint,
                    "sec_dashboard_icon_frame_color" to iconFrame,
                    "sec_widget_list_divider_color" to dividerColor,
                    "sec_account_profile_email_text_color" to emailColor,
                )
                val iconEntries = OneUi9SettingsIcon.ALL_26_ICONS.map { icon ->
                    "${icon.resName}_tint" to icon.resolveForegroundColor(palette)
                }
                baseSettings + iconEntries
            }
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
            PackedAppOverlay("common", "HexNextOneUI9_Android", "com.hexnext.oneui9.rro.common", "android:SemWT_android", "android", "One UI 9.0 Framework", "Core"),
            PackedAppOverlay("systemui", "HexNextOneUI9_SystemUI", "com.hexnext.oneui9.rro.systemui", "com.android.systemui:SemWT_com.android.systemui", "com.android.systemui", "SystemUI & Quick Settings", "Core"),
            PackedAppOverlay("settings", "HexNextOneUI9_Settings", "com.hexnext.oneui9.rro.settings", "com.android.settings:SemWT_com.android.settings", "com.android.settings", "One UI SecSettings & 26 Icons", "Core"),
            PackedAppOverlay("dialer", "HexNextOneUI9_Dialer", "com.hexnext.oneui9.rro.dialer", "com.samsung.android.dialer:SemWT_com.samsung.android.dialer", "com.samsung.android.dialer", "Samsung Phone & Dialer", "Samsung"),
            PackedAppOverlay("incallui", "HexNextOneUI9_InCallUI", "com.hexnext.oneui9.rro.incallui", "com.samsung.android.incallui:SemWT_com.samsung.android.incallui", "com.samsung.android.incallui", "Samsung InCallUI", "Samsung"),
            PackedAppOverlay("contacts", "HexNextOneUI9_Contacts", "com.hexnext.oneui9.rro.contacts", "com.samsung.android.app.contacts:SemWT_com.samsung.android.app.contacts", "com.samsung.android.app.contacts", "Samsung Contacts", "Samsung"),
            PackedAppOverlay("messages", "HexNextOneUI9_Messages", "com.hexnext.oneui9.rro.messaging", "com.samsung.android.messaging:SemWT_com.samsung.android.messaging", "com.samsung.android.messaging", "Samsung Messages", "Samsung"),
            PackedAppOverlay("honeyboard", "HexNextOneUI9_HoneyBoard", "com.hexnext.oneui9.rro.honeyboard", "com.samsung.android.honeyboard:SemWT_com.samsung.android.honeyboard", "com.samsung.android.honeyboard", "Samsung HoneyBoard", "Samsung"),
            PackedAppOverlay("myfiles", "HexNextOneUI9_MyFiles", "com.hexnext.oneui9.rro.myfiles", "com.sec.android.app.myfiles:SemWT_com.sec.android.app.myfiles", "com.sec.android.app.myfiles", "Samsung My Files", "Samsung"),
            PackedAppOverlay("gallery", "HexNextOneUI9_Gallery", "com.hexnext.oneui9.rro.gallery3d", "com.sec.android.gallery3d:SemWT_com.sec.android.gallery3d", "com.sec.android.gallery3d", "Samsung Gallery", "Samsung"),
            PackedAppOverlay("clock", "HexNextOneUI9_Clock", "com.hexnext.oneui9.rro.clockpackage", "com.sec.android.app.clockpackage:SemWT_com.sec.android.app.clockpackage", "com.sec.android.app.clockpackage", "Samsung Clock", "Samsung"),
            PackedAppOverlay("calculator", "HexNextOneUI9_Calculator", "com.hexnext.oneui9.rro.popupcalculator", "com.sec.android.app.popupcalculator:SemWT_com.sec.android.app.popupcalculator", "com.sec.android.app.popupcalculator", "Samsung Calculator", "Samsung"),
            PackedAppOverlay("calendar", "HexNextOneUI9_Calendar", "com.hexnext.oneui9.rro.calendar", "com.samsung.android.calendar:SemWT_com.samsung.android.calendar", "com.samsung.android.calendar", "Samsung Calendar", "Samsung"),
            PackedAppOverlay("devicecare", "HexNextOneUI9_DeviceCare", "com.hexnext.oneui9.rro.lool", "com.samsung.android.lool:SemWT_com.samsung.android.lool", "com.samsung.android.lool", "Device Care", "Samsung"),
            PackedAppOverlay("home", "HexNextOneUI9_OneUIHome", "com.hexnext.oneui9.rro.home", "com.sec.android.app.launcher:SemWT_com.sec.android.app.launcher", "com.sec.android.app.launcher", "One UI Home", "Samsung"),
            PackedAppOverlay("aod", "HexNextOneUI9_AOD", "com.hexnext.oneui9.rro.aodservice", "com.samsung.android.app.aodservice:SemWT_com.samsung.android.app.aodservice", "com.samsung.android.app.aodservice", "Always On Display", "Samsung"),
            PackedAppOverlay("smartcapture", "HexNextOneUI9_SmartCapture", "com.hexnext.oneui9.rro.smartcapture", "com.samsung.android.app.smartcapture:SemWT_com.samsung.android.app.smartcapture", "com.samsung.android.app.smartcapture", "Smart Capture", "Samsung"),
        )
    }
}

/**
 * All 26 One UI 9.0 SecSettings Dashboard Icons rebuilt from `project.vivid.ui.plugin.niferpitou`
 * (`assets/anaconda_tints/once/base/lxx1/settingsicon-*/*.png`) as One UI 9.0 VectorDrawables & RRO color tokens.
 */
data class OneUi9SettingsIcon(
    val id: String,
    val resName: String,
    val title: String,
    val subtitle: String,
    val ogColor: Int,
    val svgPath: String,
) {
    fun resolveForegroundColor(palette: MonetPalette): Int {
        val spec = palette.spec
        spec.perIconColors[id]?.let { return it or 0xFF000000.toInt() }
        val primary = palette.shades.getValue(ToneGroup.ACCENT1)[4]
        val secondary = palette.shades.getValue(ToneGroup.ACCENT2)[6]
        return when (spec.settingsIconStyle) {
            SettingsIconStyle.STYLE_1SETT -> primary
            SettingsIconStyle.STYLE_2SETT -> secondary
            SettingsIconStyle.STYLE_3SETT -> ogColor
            SettingsIconStyle.STYLE_ALT_1SETT -> primary
            SettingsIconStyle.STYLE_ALT_2SETT -> secondary
        }
    }

    fun resolveBadgeColor(palette: MonetPalette): Int {
        val spec = palette.spec
        val primary = palette.shades.getValue(ToneGroup.ACCENT1)[4]
        val secondary = palette.shades.getValue(ToneGroup.ACCENT2)[6]
        return when (spec.settingsIconStyle) {
            SettingsIconStyle.STYLE_1SETT -> primary
            SettingsIconStyle.STYLE_2SETT -> secondary
            SettingsIconStyle.STYLE_3SETT -> primary
            SettingsIconStyle.STYLE_ALT_1SETT -> secondary
            SettingsIconStyle.STYLE_ALT_2SETT -> primary
        }
    }

    fun buildVectorDrawableXml(palette: MonetPalette): String {
        val spec = palette.spec
        val fgHex = String.format("%06X", resolveForegroundColor(palette) and 0xFFFFFF)
        val badgeHex = String.format("%06X", resolveBadgeColor(palette) and 0xFFFFFF)
        val isDuotone = spec.settingsIconStyle == SettingsIconStyle.STYLE_ALT_1SETT ||
            spec.settingsIconStyle == SettingsIconStyle.STYLE_ALT_2SETT
        val dp = if (spec.largeSettingsIcons) 36 else 28
        val activeFolder = if (spec.framelessIcons) spec.settingsIconStyle.framelessFolder else spec.settingsIconStyle.assetFolder

        return buildString {
            appendLine("""<?xml version="1.0" encoding="utf-8"?>""")
            appendLine("""<!-- Rebuilt from project.vivid.ui.plugin.niferpitou ($activeFolder/$resName.png) for One UI 9.0 -->""")
            appendLine("""<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="${dp}dp" android:height="${dp}dp" android:viewportWidth="24" android:viewportHeight="24">""")
            if (!spec.framelessIcons) {
                val containerHex = if (isDuotone) badgeHex else fgHex
                appendLine("""    <path android:fillColor="#26$containerHex" android:strokeColor="#4D$containerHex" android:strokeWidth="0.75" android:pathData="M7,2h10c2.76,0 5,2.24 5,5v10c0,2.76 -2.24,5 -5,5H7c-2.76,0 -5,-2.24 -5,-5V7c0,-2.76 2.24,-5 5,-5z" />""")
            }
            if (isDuotone) {
                appendLine("""    <path android:fillColor="#33$badgeHex" android:pathData="M12,6a6,6 0 1,0 0,12a6,6 0 1,0 0,-12z" />""")
            }
            appendLine("""    <path android:strokeWidth="2.0" android:strokeLineCap="round" android:strokeLineJoin="round" android:strokeColor="#$fgHex" android:fillColor="#00000000" android:pathData="$svgPath" />""")
            appendLine("""</vector>""")
        }
    }

    companion object {
        fun buildSettingsIconsEntries(palette: MonetPalette): List<ColorEntry> =
            ALL_26_ICONS.map { icon ->
                ColorEntry(
                    "com.android.settings:color/${icon.resName}_tint",
                    icon.resolveForegroundColor(palette)
                )
            }

        val ALL_26_ICONS: List<OneUi9SettingsIcon> = listOf(
            OneUi9SettingsIcon("connections", "sec_ic_settings_connections", "Connections", "Wi-Fi, Bluetooth, NFC, UWB", 0xFF38BDF8.toInt(), "M12 20h.01M8.5 16.5a5 5 0 017 0M5 13a10 10 0 0114 0M2 9.5a15 15 0 0120 0"),
            OneUi9SettingsIcon("multi_devices", "sec_ic_settings_multi_devices", "Connected devices", "Quick Share, Galaxy Wear, DeX", 0xFF34D399.toInt(), "M8 7h12m0 0l-4-4m4 4l-4 4m0 6H4m0 0l4 4m-4-4l4-4"),
            OneUi9SettingsIcon("modes", "sec_ic_settings_modes", "Modes and Routines", "Sleep, Driving, Work automation", 0xFFA78BFA.toInt(), "M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z"),
            OneUi9SettingsIcon("notifications", "sec_ic_settings_notifications", "Notifications", "App notifications, Brief pop-up", 0xFFFB7185.toInt(), "M15 17h5l-1.4-1.4A2 2 0 0118 14.2V11a6 6 0 00-4-5.7V5a2 2 0 10-4 0v.3A6 6 0 006 11v3.2c0 .5-.2 1-.6 1.4L4 17h5m6 0v1a3 3 0 11-6 0v-1m6 0H9"),
            OneUi9SettingsIcon("sound", "ic_settings_sound", "Sounds and vibration", "Sound mode, Ringtone, Dolby Atmos", 0xFF818CF8.toInt(), "M15.5 8.5a5 5 0 010 7m2.8-9.8a9 9 0 010 12.6M6 15H4a1 1 0 01-1-1v-4a1 1 0 011-1h2l4-4v14l-4-4z"),
            OneUi9SettingsIcon("display", "ic_settings_display", "Display", "Dark mode, Motion smoothness, Font", 0xFFFBBF24.toInt(), "M12 3v1m0 16v1m9-9h-1M4 12H3m15.4 6.4l-.7-.7M6.3 6.3l-.7-.7m12.8 0l-.7.7M6.3 17.7l-.7.7M16 12a4 4 0 11-8 0 4 4 0 018 0z"),
            OneUi9SettingsIcon("wallpaper", "sec_ic_settings_wallpaper", "Wallpaper and style", "Wallpapers, Color palette (SemWT_*)", 0xFFF472B6.toInt(), "M4 16l4.6-4.6a2 2 0 012.8 0L16 16m-2-2l1.6-1.6a2 2 0 012.8 0L20 14m-6-6h.01M6 20h12a2 2 0 002-2V6a2 2 0 00-2-2H6a2 2 0 00-2 2v12a2 2 0 002 2z"),
            OneUi9SettingsIcon("themes", "ic_theme", "Themes", "HexNext From-Scratch RROs (> SemWT_*)", 0xFFC084FC.toInt(), "M7 21a4 4 0 01-4-4V5a2 2 0 012-2h4a2 2 0 012 2v12a4 4 0 01-4 4zm0 0h12a2 2 0 002-2v-4a2 2 0 00-2-2h-2.3M11 7.3l1.7-1.7a2 2 0 012.8 0l2.8 2.8a2 2 0 010 2.8l-8.5 8.5"),
            OneUi9SettingsIcon("homescreen", "sec_ic_settings_homescreen", "Home screen", "Layout, Grid 5x6, App icon badges", 0xFF60A5FA.toInt(), "M3 12l2-2m0 0l7-7 7 7M5 10v10a1 1 0 001 1h3m10-11l2 2m-2-2v10a1 1 0 01-1 1h-3m-6 0a1 1 0 001-1v-4a1 1 0 011-1h2a1 1 0 011 1v4a1 1 0 001 1m-6 0h6"),
            OneUi9SettingsIcon("lockscreen", "sec_ic_settings_lockscreen", "Lock screen and AOD", "Screen lock type, Always On Display", 0xFF2DD4BF.toInt(), "M12 15v2m-6 4h12a2 2 0 002-2v-6a2 2 0 00-2-2H6a2 2 0 00-2 2v6a2 2 0 002 2zm10-10V7a4 4 0 00-8 0v4h8z"),
            OneUi9SettingsIcon("biometrics", "sec_ic_settings_biometrics_and_security", "Security and privacy", "Biometrics, Auto Blocker, Knox Matrix", 0xFF4ADE80.toInt(), "M9 12l2 2 4-4m5.6-4.4A12 12 0 0012 3a12 12 0 00-8.6 4.6A12 12 0 0012 21a12 12 0 008.6-13.4z"),
            OneUi9SettingsIcon("location", "ic_settings_location", "Location", "Location permissions, Wi-Fi scanning", 0xFF38BDF8.toInt(), "M17.7 16.7L13.4 21a2 2 0 01-2.8 0l-4.2-4.3a8 8 0 1111.3 0zM15 11a3 3 0 11-6 0 3 3 0 016 0z"),
            OneUi9SettingsIcon("emergency", "sec_ic_settings_emergency", "Safety and emergency", "Medical info, Emergency SOS, Alerts", 0xFFF87171.toInt(), "M12 9v2m0 4h.01m-6.9 4h13.8c1.5 0 2.5-1.7 1.7-3L13.7 4c-.8-1.3-2.7-1.3-3.5 0L3.3 16c-.8 1.3.2 3 1.8 3z"),
            OneUi9SettingsIcon("accounts", "sec_ic_settings_cloud_and_account", "Accounts and backup", "Manage accounts, Samsung Cloud, Smart Switch", 0xFF60A5FA.toInt(), "M16 7a4 4 0 11-8 0 4 4 0 018 0zM12 14a7 7 0 00-7 7h14a7 7 0 00-7-7z"),
            OneUi9SettingsIcon("google", "sec_ic_settings_google", "Google", "Google Play services & parental controls", 0xFF93C5FD.toInt(), "M21 12a9 9 0 11-3.2-6.9M21 12h-9"),
            OneUi9SettingsIcon("advanced", "sec_ic_settings_advanced_feature", "Advanced features", "Labs, S Pen, Multi window, Motions", 0xFFFBBF24.toInt(), "M5 3v4M3 5h4M6 17v4m-2-2h4m5-16l2.3 6.9L21 12l-5.7 2.1L13 21l-2.3-6.9L5 12l5.7-2.1L13 3z"),
            OneUi9SettingsIcon("wellbeing", "sec_ic_settings_digitalwellbeing", "Digital Wellbeing", "Screen time, App timers, Driving monitor", 0xFF34D399.toInt(), "M4.3 6.3a4.5 4.5 0 016.4 0L12 7.6l1.3-1.3a4.5 4.5 0 116.4 6.4L12 20.4l-7.7-7.7a4.5 4.5 0 010-6.4z"),
            OneUi9SettingsIcon("devicecare", "sec_ic_settings_devicecare", "Device care", "Storage, Memory, App protection", 0xFF2DD4BF.toInt(), "M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z"),
            OneUi9SettingsIcon("apps", "ic_settings_applications", "Apps", "Default apps, App management", 0xFF818CF8.toInt(), "M4 6a2 2 0 012-2h2a2 2 0 012 2v2a2 2 0 01-2 2H6a2 2 0 01-2-2V6zm10 0a2 2 0 012-2h2a2 2 0 012 2v2a2 2 0 01-2 2h-2a2 2 0 01-2-2V6zM4 16a2 2 0 012-2h2a2 2 0 012 2v2a2 2 0 01-2 2H6a2 2 0 01-2-2v-2zm10 0a2 2 0 012-2h2a2 2 0 012 2v2a2 2 0 01-2 2h-2a2 2 0 01-2-2v-2z"),
            OneUi9SettingsIcon("general", "sec_ic_settings_general_device_management", "General management", "Language, Date and time, Samsung Keyboard", 0xFF94A3B8.toInt(), "M12 6V4m0 2a2 2 0 100 4m0-4a2 2 0 110 4m-6 8a2 2 0 100-4m0 4a2 2 0 110-4m0 4v2m0-6V4m6 6v10m6-2a2 2 0 100-4m0 4a2 2 0 110-4m0 4v2m0-6V4"),
            OneUi9SettingsIcon("accessibility", "ic_settings_accessibility", "Accessibility", "TalkBack, Vision & Hearing enhancements", 0xFF4ADE80.toInt(), "M16 7a4 4 0 11-8 0 4 4 0 018 0zM12 14v7m-4-5l4-2 4 2"),
            OneUi9SettingsIcon("software_update", "sec_ic_settings_software_update", "Software update", "Download and install, One UI 9.0", 0xFF38BDF8.toInt(), "M4 4v5h.6m15.4 2A8 8 0 004.6 9m0 0H9m11 11v-5h-.6m0 0a8 8 0 01-15.4-2m15.4 2H15"),
            OneUi9SettingsIcon("remotesupport", "sec_ic_settings_remotesupport", "Tips and user guide", "Remote support, One UI 9.0 changelog", 0xFFFBBF24.toInt(), "M8.2 9c.5-1.2 1.8-2 3.3-2 2 0 3.5 1.3 3.5 3 0 1.4-1 2.2-2.2 2.7-.7.3-1.3.8-1.3 1.6V15m0 3h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z"),
            OneUi9SettingsIcon("coverscreen", "sec_ic_settings_coverscreen", "Cover screen", "Flex Window widgets & quick settings", 0xFFA78BFA.toInt(), "M9 3H5a2 2 0 00-2 2v14a2 2 0 002 2h4m6-18h4a2 2 0 012 2v14a2 2 0 01-2 2h-4m-6-18v18m6-18v18"),
            OneUi9SettingsIcon("battery", "sec_ic_settings_battery", "Battery", "Battery protection, Charging settings", 0xFF4ADE80.toInt(), "M13 10V3L4 14h7v7l9-11h-7z"),
            OneUi9SettingsIcon("about", "ic_settings_about", "About phone", "SM-S938U • One UI 9.0 • Android 16", 0xFF94A3B8.toInt(), "M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z"),
        )
    }
}
