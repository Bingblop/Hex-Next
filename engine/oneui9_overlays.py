"""
Rebuilt Hex Installer (#hex__30) One UI 9.0 System RRO Overlay Catalog & Compiler.

Replaces the legacy precompiled `.hex_temp/asset_factory` (`anaconda_0..anaconda_70`)
resource tables with dynamic One UI 9.0 (Android 16, SDK 36/37) resource mappings
built on top of Samsung's System RRO carrier architecture (`com.samsung.High_contrast_theme_II.*`
and `OverlayManagerTransaction` fabricated overlays under UID 0 or UID 1000).

No Theme Park (`com.samsung.android.themepark` / `com.samsung.themedesigner`) prerequisite is required.
"""

import colorsys
import io
import json
import zipfile
from typing import Any, Dict, List, Optional


SHADES = [0, 10, 50, 100, 200, 300, 400, 500, 600, 700, 800, 900, 1000]

PALETTE_STYLES = [
    {"id": "TONAL_SPOT", "label": "Tonal Spot", "chroma_mult": 1.0, "sec_shift": 0.0, "tert_shift": 60.0},
    {"id": "VIBRANT", "label": "Vibrant", "chroma_mult": 1.35, "sec_shift": 18.0, "tert_shift": 35.0},
    {"id": "EXPRESSIVE", "label": "Expressive", "chroma_mult": 1.25, "sec_shift": 45.0, "tert_shift": 120.0},
    {"id": "FIDELITY", "label": "Fidelity", "chroma_mult": 1.15, "sec_shift": 0.0, "tert_shift": 30.0},
    {"id": "CONTENT", "label": "Content", "chroma_mult": 1.05, "sec_shift": 0.0, "tert_shift": 20.0},
    {"id": "NEUTRAL", "label": "Neutral (Spritz)", "chroma_mult": 0.25, "sec_shift": 0.0, "tert_shift": 0.0},
    {"id": "MONOCHROME", "label": "Monochrome", "chroma_mult": 0.0, "sec_shift": 0.0, "tert_shift": 0.0},
    {"id": "RAINBOW", "label": "Rainbow", "chroma_mult": 1.1, "sec_shift": 60.0, "tert_shift": 120.0},
    {"id": "FRUIT_SALAD", "label": "Fruit Salad", "chroma_mult": 1.2, "sec_shift": -50.0, "tert_shift": 90.0},
]

# Extracted directly from #hex__30 HEX_EOL.apk assets/hex_qs_toggles/1.xml..28.xml
HEX_QS_TOGGLE_SHAPES: Dict[str, Dict[str, Any]] = {
    "squircle": {
        "id": "squircle",
        "anaconda_index": 1,
        "label": "One UI 9 Squircle",
        "radius_css": "20px",
        "xml_template": (
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<shape android:shape="rectangle" xmlns:android="http://schemas.android.com/apk/res/android">\n'
            '    <corners android:radius="20.0dip" />\n'
            '    <solid android:color="{accent}" />\n'
            '</shape>'
        ),
    },
    "circle": {
        "id": "circle",
        "anaconda_index": 8,
        "label": "AOSP Oval (hex_qs_toggles/1.xml)",
        "radius_css": "50%",
        "xml_template": (
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<shape android:shape="oval" xmlns:android="http://schemas.android.com/apk/res/android">\n'
            '    <stroke android:width="2.0dip" android:color="{accent}" />\n'
            '    <solid android:color="{accent}" />\n'
            '</shape>'
        ),
    },
    "teardrop": {
        "id": "teardrop",
        "anaconda_index": 15,
        "label": "Bodhi Teardrop (hex_qs_toggles/15.xml)",
        "radius_css": "24px 24px 6px 24px",
        "xml_template": (
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<shape android:shape="rectangle" xmlns:android="http://schemas.android.com/apk/res/android">\n'
            '    <corners android:topLeftRadius="24.0dip" android:topRightRadius="24.0dip" '
            'android:bottomLeftRadius="24.0dip" android:bottomRightRadius="6.0dip" />\n'
            '    <solid android:color="{accent}" />\n'
            '</shape>'
        ),
    },
    "hexagon": {
        "id": "hexagon",
        "anaconda_index": 22,
        "label": "Hexagon Frame (hex_qs_toggles/22.xml)",
        "radius_css": "12px",
        "xml_template": (
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<layer-list xmlns:android="http://schemas.android.com/apk/res/android">\n'
            '    <item>\n'
            '        <shape android:shape="oval">\n'
            '            <stroke android:width="2.5dip" android:color="{accent}" />\n'
            '            <solid android:color="{accent_alpha}" />\n'
            '        </shape>\n'
            '    </item>\n'
            '</layer-list>'
        ),
    },
    "sharp_box": {
        "id": "sharp_box",
        "anaconda_index": 4,
        "label": "Cyber Sharp Box (hex_qs_toggles/4.xml)",
        "radius_css": "6px",
        "xml_template": (
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<shape android:shape="rectangle" xmlns:android="http://schemas.android.com/apk/res/android">\n'
            '    <corners android:radius="6.0dip" />\n'
            '    <stroke android:width="1.5dip" android:color="{accent}" />\n'
            '    <solid android:color="{accent}" />\n'
            '</shape>'
        ),
    },
}

# Rebuilt Hex Plugins extracted from #hex__30 HEX_EOL.apk
HEX_PLUGINS: Dict[str, Dict[str, Any]] = {
    "bodhi_default": {
        "id": "bodhi_default",
        "name": "#hex_ Bodhi Default",
        "plugin_pkg": "project.vivid.hex.bodhi",
        "author": "Vivid / OneUI 9 Rebuild",
        "seed": "#00E5FF",
        "secondary": "#7C4DFF",
        "bg": "#000000",
        "surface": "#0B0E17",
        "card": "#121724",
        "qs_inactive": "#1A2030",
        "style": "VIBRANT",
        "pure_black": True,
        "tile_shape": "squircle",
    },
    "ayos_dark": {
        "id": "ayos_dark",
        "name": "AyOS Dark Retina",
        "plugin_pkg": "project.vivid.ui.plugin.ayosdark.retina",
        "author": "Hex UI Plugin",
        "seed": "#0A84FF",
        "secondary": "#5E5CE6",
        "bg": "#000000",
        "surface": "#111114",
        "card": "#1C1C1E",
        "qs_inactive": "#2C2C2E",
        "style": "TONAL_SPOT",
        "pure_black": True,
        "tile_shape": "circle",
    },
    "aosp_pixel": {
        "id": "aosp_pixel",
        "name": "AOSP Material You",
        "plugin_pkg": "project.vivid.ui.plugin.aosp",
        "author": "Hex UI Plugin",
        "seed": "#4285F4",
        "secondary": "#34A853",
        "bg": "#0E1117",
        "surface": "#161B22",
        "card": "#1F242D",
        "qs_inactive": "#282E3A",
        "style": "TONAL_SPOT",
        "pure_black": False,
        "tile_shape": "squircle",
    },
    "deliun_amoled": {
        "id": "deliun_amoled",
        "name": "Deliun Pitch Black",
        "plugin_pkg": "project.vivid.ui.plugin.deliun",
        "author": "Hex UI Plugin",
        "seed": "#00E676",
        "secondary": "#00BFA5",
        "bg": "#000000",
        "surface": "#06110B",
        "card": "#0D1F15",
        "qs_inactive": "#152E20",
        "style": "VIBRANT",
        "pure_black": True,
        "tile_shape": "teardrop",
    },
    "cyberpunk_gold": {
        "id": "cyberpunk_gold",
        "name": "Cyberpunk 2077 Gold",
        "plugin_pkg": "project.vivid.ui.plugin.hysteria",
        "author": "OneUI-Theme-Engine",
        "seed": "#FFE600",
        "secondary": "#00F0FF",
        "bg": "#06060A",
        "surface": "#10101A",
        "card": "#181926",
        "qs_inactive": "#232538",
        "style": "EXPRESSIVE",
        "pure_black": False,
        "tile_shape": "sharp_box",
    },
    "kurama_crimson": {
        "id": "kurama_crimson",
        "name": "Kurama Crimson OLED",
        "plugin_pkg": "project.vivid.ui.plugin.kurama",
        "author": "Hex UI Plugin",
        "seed": "#FF1744",
        "secondary": "#FF5252",
        "bg": "#000000",
        "surface": "#140407",
        "card": "#1F080D",
        "qs_inactive": "#2E0D14",
        "style": "VIBRANT",
        "pure_black": True,
        "tile_shape": "hexagon",
    },
    "monet_neon_violet": {
        "id": "monet_neon_violet",
        "name": "Monet Neon Violet",
        "plugin_pkg": "project.vivid.ui.plugin.madara",
        "author": "OneUI-Theme-Engine",
        "seed": "#8A2BE2",
        "secondary": "#C084FC",
        "bg": "#07080B",
        "surface": "#0D0E15",
        "card": "#161824",
        "qs_inactive": "#222538",
        "style": "EXPRESSIVE",
        "pure_black": False,
        "tile_shape": "squircle",
    },
}


# All packed overlays extracted from #hex__30 HEX_EOL.apk (`anaconda_*` -> `com.samsung.High_contrast_theme_II.*`)
# rebuilt with One UI 9.0 (Android 16, SDK 36/37) resource names.
ONEUI9_SYSTEM_RRO_CATALOG: List[Dict[str, Any]] = [
    {
        "id": "common",
        "anaconda_asset": "assets/anaconda/res/anaconda_0",
        "system_rro_package": "com.samsung.High_contrast_theme_II.common",
        "fabricated_name": "HexNextOneUi9Android",
        "target_package": "android",
        "label": "Android Framework & Monet Core",
        "category": "Core Framework",
        "selected": True,
        "legacy_broken_resources": [
            "tw_primary_dark_color_dark (0x01060241)",
            "sem_color_primary_dark (0x01060318)",
            "zz_moto_actionbar_bg (deprecated One UI 4)",
        ],
        "oneui9_resources": [
            {"name": "system_accent1_200", "type": "0x1c", "role": "accent", "desc": "Primary active accent (Dark UI)"},
            {"name": "system_accent1_500", "type": "0x1c", "role": "accent", "desc": "Framework primary key color"},
            {"name": "system_accent1_600", "type": "0x1c", "role": "accent_deep", "desc": "Primary filled button & switch"},
            {"name": "system_accent2_400", "type": "0x1c", "role": "secondary", "desc": "Secondary tonal chip & icon"},
            {"name": "system_accent3_300", "type": "0x1c", "role": "tertiary", "desc": "Tertiary highlight & badge"},
            {"name": "system_neutral1_900", "type": "0x1c", "role": "bg", "desc": "One UI 9 window background"},
            {"name": "system_neutral1_1000", "type": "0x1c", "role": "bg", "desc": "AMOLED deepest background"},
            {"name": "system_neutral2_800", "type": "0x1c", "role": "card", "desc": "Surface container card fill"},
            {"name": "system_surface_dark", "type": "0x1c", "role": "bg", "desc": "Android 15/16 M3 surface dark"},
            {"name": "system_surface_container_dark", "type": "0x1c", "role": "card", "desc": "Android 15/16 M3 container"},
            {"name": "background_dark", "type": "0x1c", "role": "bg", "desc": "Legacy + One UI 9 window dark bg"},
            {"name": "background_floating_material_dark", "type": "0x1c", "role": "surface", "desc": "Floating dialog surface"},
            {"name": "sem_app_bar_bg_color_dark", "type": "0x1c", "role": "bg", "desc": "One UI 9 CollapsingToolbar bg"},
            {"name": "tw_color_control_activated", "type": "0x1c", "role": "accent", "desc": "Samsung checkbox/radio active"},
        ],
    },
    {
        "id": "systemui",
        "anaconda_asset": "assets/anaconda/res/anaconda_13",
        "system_rro_package": "com.samsung.High_contrast_theme_II.systemui",
        "fabricated_name": "HexNextOneUi9SystemUI",
        "target_package": "com.android.systemui",
        "label": "SystemUI, Split QS & Notifications",
        "category": "Core Framework",
        "selected": True,
        "legacy_broken_resources": [
            "qs_tile_background_color_active (removed in One UI 6)",
            "status_bar_battery_frame_dark_color (refactored in One UI 7/9)",
            "notification_material_background_dark_color (removed in One UI 8)",
        ],
        "oneui9_resources": [
            {"name": "qs_background_dark", "type": "0x1c", "role": "bg", "desc": "One UI 9 split QS panel backdrop"},
            {"name": "quick_settings_panel_background_color", "type": "0x1c", "role": "bg", "desc": "Quick Settings blur scrim"},
            {"name": "sec_qs_detail_bg_color", "type": "0x1c", "role": "surface", "desc": "QS expanded detail sheet"},
            {"name": "notification_panel_background_color", "type": "0x1c", "role": "bg", "desc": "Notification shade background"},
            {"name": "notification_guts_bg_color", "type": "0x1c", "role": "card", "desc": "Notification card container"},
            {"name": "qs_tile_active_color", "type": "0x1c", "role": "accent", "desc": "Active Quick Settings toggle"},
            {"name": "qs_tile_round_background_on", "type": "0x1c", "role": "accent", "desc": "One UI 9 pill/circle QS on state"},
            {"name": "qs_tile_round_background_off", "type": "0x1c", "role": "qs_inactive", "desc": "One UI 9 QS inactive tile"},
            {"name": "qs_tile_icon_on_tint_color", "type": "0x1c", "role": "bg", "desc": "Active QS glyph contrast tint"},
            {"name": "qs_brightness_bar_activated_color", "type": "0x1c", "role": "accent", "desc": "Brightness & volume slider fill"},
            {"name": "status_bar_clock_color", "type": "0x1c", "role": "text", "desc": "Status bar clock & indicator tint"},
        ],
    },
    {
        "id": "settings",
        "anaconda_asset": "assets/anaconda/res/anaconda_28",
        "system_rro_package": "com.samsung.High_contrast_theme_II.settings",
        "fabricated_name": "HexNextOneUi9Settings",
        "target_package": "com.android.settings",
        "label": "One UI 9.0 SecSettings",
        "category": "Core Framework",
        "selected": True,
        "legacy_broken_resources": [
            "dashboard_category_background_color (removed One UI 7)",
            "sec_ActionBar_background_color (renamed One UI 9)",
        ],
        "oneui9_resources": [
            {"name": "sec_widget_round_and_bgcolor", "type": "0x1c", "role": "card", "desc": "Rounded preference group card"},
            {"name": "sec_dashboard_background_color", "type": "0x1c", "role": "bg", "desc": "Main Settings scroll canvas"},
            {"name": "sec_dashboard_tab_selected_color", "type": "0x1c", "role": "accent", "desc": "Selected top/side tab tint"},
            {"name": "sec_sub_app_bar_background_color", "type": "0x1c", "role": "surface", "desc": "Sub-header status bar strip"},
            {"name": "list_item_background_color", "type": "0x1c", "role": "card", "desc": "Settings row item container"},
        ],
    },
    {
        "id": "dialer",
        "anaconda_asset": "assets/anaconda/res/anaconda_10",
        "system_rro_package": "com.samsung.High_contrast_theme_II.dialer",
        "fabricated_name": "HexNextOneUi9Dialer",
        "target_package": "com.samsung.android.dialer",
        "label": "Samsung Phone & Keypad",
        "category": "Samsung System Apps",
        "selected": True,
        "legacy_broken_resources": [
            "dialtacts_theme_color (legacy One UI 4)",
            "dialpad_digits_text_color_dark (refactored One UI 8)",
        ],
        "oneui9_resources": [
            {"name": "dialpad_background_color", "type": "0x1c", "role": "bg", "desc": "Dialpad main background"},
            {"name": "dialpad_primary_text_color", "type": "0x1c", "role": "accent", "desc": "Dialpad digits & number display"},
            {"name": "call_background_color", "type": "0x1c", "role": "bg", "desc": "Recent calls list background"},
            {"name": "primary_color", "type": "0x1c", "role": "accent", "desc": "Dialer primary action tint"},
            {"name": "sec_dialer_tab_selected_color", "type": "0x1c", "role": "secondary", "desc": "Keypad / Recents / Contacts tab"},
        ],
    },
    {
        "id": "incallui",
        "anaconda_asset": "assets/anaconda/res/anaconda_11",
        "system_rro_package": "com.samsung.High_contrast_theme_II.InCallUI",
        "fabricated_name": "HexNextOneUi9InCallUI",
        "target_package": "com.samsung.android.incallui",
        "label": "Samsung InCallUI & Call Card",
        "category": "Samsung System Apps",
        "selected": True,
        "legacy_broken_resources": [
            "incall_background_gradient_top (removed One UI 6)",
        ],
        "oneui9_resources": [
            {"name": "incall_call_card_background_color", "type": "0x1c", "role": "bg", "desc": "Active call screen backdrop"},
            {"name": "incall_button_bg_checked_color", "type": "0x1c", "role": "accent", "desc": "Speaker/Mute active button"},
            {"name": "incall_panel_background_color", "type": "0x1c", "role": "card", "desc": "In-call control sheet surface"},
        ],
    },
    {
        "id": "contacts",
        "anaconda_asset": "assets/anaconda/res/anaconda_12",
        "system_rro_package": "com.samsung.High_contrast_theme_II.contacts",
        "fabricated_name": "HexNextOneUi9Contacts",
        "target_package": "com.samsung.android.app.contacts",
        "label": "Samsung Contacts",
        "category": "Samsung System Apps",
        "selected": True,
        "legacy_broken_resources": [
            "contact_browser_list_bg (legacy One UI 5)",
        ],
        "oneui9_resources": [
            {"name": "main_bg_color", "type": "0x1c", "role": "bg", "desc": "Contacts list main background"},
            {"name": "contact_list_item_bg_color", "type": "0x1c", "role": "card", "desc": "Contact card group surface"},
            {"name": "accent_color", "type": "0x1c", "role": "accent", "desc": "Index scrollbar & FAB accent"},
            {"name": "sec_contacts_sub_header_bg", "type": "0x1c", "role": "surface", "desc": "Alphabetical section divider"},
        ],
    },
    {
        "id": "messaging",
        "anaconda_asset": "assets/anaconda/res/anaconda_17",
        "system_rro_package": "com.samsung.High_contrast_theme_II.messaging",
        "fabricated_name": "HexNextOneUi9Messages",
        "target_package": "com.samsung.android.messaging",
        "label": "Samsung Messages",
        "category": "Samsung System Apps",
        "selected": True,
        "legacy_broken_resources": [
            "message_bubble_bg_outgoing (replaced in One UI 8/9)",
        ],
        "oneui9_resources": [
            {"name": "main_bg_color", "type": "0x1c", "role": "bg", "desc": "Conversations list background"},
            {"name": "message_list_bg_color", "type": "0x1c", "role": "bg", "desc": "Chat thread background"},
            {"name": "bubble_sent_bg_color", "type": "0x1c", "role": "accent_deep", "desc": "Outgoing RCS/SMS bubble"},
            {"name": "bubble_received_bg_color", "type": "0x1c", "role": "card", "desc": "Incoming message bubble"},
            {"name": "composer_bg_color", "type": "0x1c", "role": "surface", "desc": "Bottom message input bar"},
            {"name": "accent_color", "type": "0x1c", "role": "accent", "desc": "Send button & unread badge"},
        ],
    },
    {
        "id": "honeyboard",
        "anaconda_asset": "assets/anaconda/res/anaconda_22",
        "system_rro_package": "com.samsung.High_contrast_theme_II.honeyboard",
        "fabricated_name": "HexNextOneUi9Keyboard",
        "target_package": "com.samsung.android.honeyboard",
        "label": "Samsung Keyboard (HoneyBoard)",
        "category": "Samsung System Apps",
        "selected": True,
        "legacy_broken_resources": [
            "SipKeyBgNormal (legacy Sip keyboard)",
        ],
        "oneui9_resources": [
            {"name": "theme_keyboard_bg_color", "type": "0x1c", "role": "bg", "desc": "Keyboard dock background"},
            {"name": "theme_key_bg_color", "type": "0x1c", "role": "card", "desc": "Alphanumeric keycap fill"},
            {"name": "theme_key_func_bg_color", "type": "0x1c", "role": "qs_inactive", "desc": "Shift/Backspace modifier key"},
            {"name": "theme_key_accent_color", "type": "0x1c", "role": "accent", "desc": "Enter/Search action keycap"},
            {"name": "theme_key_text_color", "type": "0x1c", "role": "text", "desc": "Key character legend color"},
        ],
    },
    {
        "id": "myfiles",
        "anaconda_asset": "assets/anaconda/res/anaconda_20",
        "system_rro_package": "com.samsung.High_contrast_theme_II.myfiles",
        "fabricated_name": "HexNextOneUi9MyFiles",
        "target_package": "com.sec.android.app.myfiles",
        "label": "Samsung My Files",
        "category": "Samsung System Apps",
        "selected": True,
        "legacy_broken_resources": [
            "myfiles_category_icon_tint (refactored One UI 8)",
        ],
        "oneui9_resources": [
            {"name": "main_bg_color", "type": "0x1c", "role": "bg", "desc": "File explorer background"},
            {"name": "home_card_bg_color", "type": "0x1c", "role": "card", "desc": "Recent & Categories card"},
            {"name": "storage_bar_used_color", "type": "0x1c", "role": "accent", "desc": "Storage analyzer bar fill"},
            {"name": "accent_color", "type": "0x1c", "role": "accent", "desc": "Selected file check & folder icon"},
        ],
    },
    {
        "id": "gallery3d",
        "anaconda_asset": "assets/anaconda/res/anaconda_21",
        "system_rro_package": "com.samsung.High_contrast_theme_II.gallery3d",
        "fabricated_name": "HexNextOneUi9Gallery",
        "target_package": "com.sec.android.gallery3d",
        "label": "Samsung Gallery",
        "category": "Samsung System Apps",
        "selected": True,
        "legacy_broken_resources": [
            "gallery_actionbar_bg (deprecated One UI 6)",
        ],
        "oneui9_resources": [
            {"name": "album_view_bg_color", "type": "0x1c", "role": "bg", "desc": "Pictures & Albums grid background"},
            {"name": "bottom_bar_bg_color", "type": "0x1c", "role": "surface", "desc": "Floating bottom tab bar"},
            {"name": "accent_color", "type": "0x1c", "role": "accent", "desc": "Active tab & selection badge"},
        ],
    },
    {
        "id": "clockpackage",
        "anaconda_asset": "assets/anaconda/res/anaconda_15",
        "system_rro_package": "com.samsung.High_contrast_theme_II.clockpackage",
        "fabricated_name": "HexNextOneUi9Clock",
        "target_package": "com.sec.android.app.clockpackage",
        "label": "Samsung Clock & Alarms",
        "category": "Samsung System Apps",
        "selected": True,
        "legacy_broken_resources": [
            "alarm_list_card_bg (renamed One UI 7)",
        ],
        "oneui9_resources": [
            {"name": "clock_main_bg_color", "type": "0x1c", "role": "bg", "desc": "Clock app main background"},
            {"name": "clock_card_bg_color", "type": "0x1c", "role": "card", "desc": "Alarm item card surface"},
            {"name": "stopwatch_button_accent_color", "type": "0x1c", "role": "accent", "desc": "Start/Lap & active alarm switch"},
        ],
    },
    {
        "id": "popupcalculator",
        "anaconda_asset": "assets/anaconda/res/anaconda_16",
        "system_rro_package": "com.samsung.High_contrast_theme_II.popupcalculator",
        "fabricated_name": "HexNextOneUi9Calculator",
        "target_package": "com.sec.android.app.popupcalculator",
        "label": "Samsung Calculator",
        "category": "Samsung System Apps",
        "selected": True,
        "legacy_broken_resources": [
            "calc_btn_equal_bg (renamed One UI 8)",
        ],
        "oneui9_resources": [
            {"name": "calc_main_bg_color", "type": "0x1c", "role": "bg", "desc": "Calculator display & pad bg"},
            {"name": "calc_keypad_bg_color", "type": "0x1c", "role": "card", "desc": "Number button background"},
            {"name": "calc_equal_btn_bg_color", "type": "0x1c", "role": "accent", "desc": "Equals button fill"},
            {"name": "calc_operator_text_color", "type": "0x1c", "role": "secondary", "desc": "Operator symbol text color"},
        ],
    },
    {
        "id": "calendar",
        "anaconda_asset": "assets/anaconda/res/anaconda_25",
        "system_rro_package": "com.samsung.High_contrast_theme_II.calendar",
        "fabricated_name": "HexNextOneUi9Calendar",
        "target_package": "com.samsung.android.calendar",
        "label": "Samsung Calendar",
        "category": "Samsung System Apps",
        "selected": True,
        "legacy_broken_resources": [
            "month_view_bg_color (refactored One UI 9)",
        ],
        "oneui9_resources": [
            {"name": "calendar_main_bg_color", "type": "0x1c", "role": "bg", "desc": "Month/Week grid background"},
            {"name": "calendar_card_bg_color", "type": "0x1c", "role": "card", "desc": "Schedule agenda card"},
            {"name": "calendar_event_accent_color", "type": "0x1c", "role": "accent", "desc": "Today highlight & FAB"},
        ],
    },
    {
        "id": "lool",
        "anaconda_asset": "assets/anaconda/res/anaconda_27",
        "system_rro_package": "com.samsung.High_contrast_theme_II.lool",
        "fabricated_name": "HexNextOneUi9DeviceCare",
        "target_package": "com.samsung.android.lool",
        "label": "Samsung Device Care",
        "category": "Samsung System Apps",
        "selected": True,
        "legacy_broken_resources": [
            "security_dashboard_bg (refactored One UI 8)",
        ],
        "oneui9_resources": [
            {"name": "device_care_main_bg_color", "type": "0x1c", "role": "bg", "desc": "Battery & Memory screen bg"},
            {"name": "device_care_card_bg_color", "type": "0x1c", "role": "card", "desc": "Optimization status card"},
            {"name": "device_care_score_color", "type": "0x1c", "role": "accent", "desc": "Optimize button & gauge ring"},
        ],
    },
    {
        "id": "launcher",
        "anaconda_asset": "assets/anaconda/res/anaconda_29",
        "system_rro_package": "com.samsung.High_contrast_theme_II.home",
        "fabricated_name": "HexNextOneUi9Launcher",
        "target_package": "com.sec.android.app.launcher",
        "label": "One UI Home Launcher",
        "category": "Samsung System Apps",
        "selected": True,
        "legacy_broken_resources": [
            "folder_background_color_dark (refactored One UI 9)",
        ],
        "oneui9_resources": [
            {"name": "folder_container_bg_color", "type": "0x1c", "role": "card", "desc": "Expanded home folder popup"},
            {"name": "apps_search_bar_bg_color", "type": "0x1c", "role": "surface", "desc": "App drawer Finder search bar"},
            {"name": "page_indicator_active_color", "type": "0x1c", "role": "accent", "desc": "Active home screen dot"},
        ],
    },
    {
        "id": "aodservice",
        "anaconda_asset": "assets/anaconda/res/anaconda_31",
        "system_rro_package": "com.samsung.High_contrast_theme_II.aodservice",
        "fabricated_name": "HexNextOneUi9AOD",
        "target_package": "com.samsung.android.app.aodservice",
        "label": "Always On Display & Lockscreen",
        "category": "Samsung System Apps",
        "selected": True,
        "legacy_broken_resources": [
            "aod_clock_default_color (refactored One UI 7)",
        ],
        "oneui9_resources": [
            {"name": "aod_clock_accent_color", "type": "0x1c", "role": "accent", "desc": "AOD & Lockscreen clock accent"},
            {"name": "aod_notification_icon_tint", "type": "0x1c", "role": "secondary", "desc": "AOD notification badge tint"},
        ],
    },
    {
        "id": "smartcapture",
        "anaconda_asset": "assets/anaconda/res/anaconda_33",
        "system_rro_package": "com.samsung.High_contrast_theme_II.smartcapture",
        "fabricated_name": "HexNextOneUi9SmartCapture",
        "target_package": "com.samsung.android.app.smartcapture",
        "label": "Samsung Smart Capture",
        "category": "Samsung System Apps",
        "selected": False,
        "legacy_broken_resources": [
            "capture_toolbar_bg (deprecated One UI 6)",
        ],
        "oneui9_resources": [
            {"name": "smartcapture_toolbar_bg_color", "type": "0x1c", "role": "card", "desc": "Floating screenshot toolbar"},
            {"name": "smartcapture_icon_tint_color", "type": "0x1c", "role": "accent", "desc": "Scroll capture / Crop action"},
        ],
    },
    {
        "id": "routines",
        "anaconda_asset": "assets/anaconda/res/anaconda_35",
        "system_rro_package": "com.samsung.High_contrast_theme_II.routines",
        "fabricated_name": "HexNextOneUi9Routines",
        "target_package": "com.samsung.android.app.routines",
        "label": "Modes & Routines",
        "category": "Samsung System Apps",
        "selected": False,
        "legacy_broken_resources": [
            "routine_card_bg_dark (renamed One UI 8)",
        ],
        "oneui9_resources": [
            {"name": "routines_main_bg_color", "type": "0x1c", "role": "bg", "desc": "Modes & Routines canvas"},
            {"name": "routines_card_bg_color", "type": "0x1c", "role": "card", "desc": "Active mode card container"},
            {"name": "routines_active_tint_color", "type": "0x1c", "role": "accent", "desc": "Running routine indicator"},
        ],
    },
]


def _hex_to_rgb(hex_str: str):
    clean = hex_str.strip().lstrip("#")
    if len(clean) == 8:
        clean = clean[2:]
    if len(clean) != 6:
        return (0, 229, 255)
    return (int(clean[0:2], 16), int(clean[2:4], 16), int(clean[4:6], 16))


def _rgb_to_hex(r: float, g: float, b: float) -> str:
    ri = max(0, min(255, int(round(r))))
    gi = max(0, min(255, int(round(g))))
    bi = max(0, min(255, int(round(b))))
    return f"#{ri:02X}{gi:02X}{bi:02X}"


def _tone_to_hex(h_deg: float, s_ratio: float, tone: int) -> str:
    # Maps HCT tone [0..100] to perceptual lightness
    l_ratio = max(0.0, min(1.0, tone / 100.0))
    # Taper saturation at extreme highlights and shadows
    eff_s = s_ratio * (1.0 - 0.55 * abs(2.0 * l_ratio - 1.0) ** 1.6)
    r, g, b = colorsys.hls_to_rgb((h_deg % 360.0) / 360.0, l_ratio, max(0.0, min(1.0, eff_s)))
    return _rgb_to_hex(r * 255.0, g * 255.0, b * 255.0)


def tone_for_shade(shade: int) -> int:
    if shade == 0:
        return 100
    if shade == 10:
        return 99
    if shade == 50:
        return 95
    return 100 - shade // 10


def generate_monet_palette(seed_hex: str, style_id: str = "TONAL_SPOT", pure_black: bool = True) -> Dict[str, Any]:
    """
    Generates all 65 Android 14-16 Monet framework shades (`system_accent1..3_*`, `system_neutral1..2_*`)
    plus Material 3 dark/light role tokens matching `MonetPalette.kt`.
    """
    r, g, b = _hex_to_rgb(seed_hex)
    h, l, s = colorsys.rgb_to_hls(r / 255.0, g / 255.0, b / 255.0)
    h_deg = h * 360.0

    style_cfg = next((st for st in PALETTE_STYLES if st["id"] == style_id), PALETTE_STYLES[0])
    c_mult = style_cfg["chroma_mult"]
    base_s = max(0.35, s) * c_mult if c_mult > 0 else 0.0

    groups_spec = [
        ("ACCENT1", "system_accent1", "Primary (accent1)", h_deg, min(1.0, base_s * 0.95)),
        ("ACCENT2", "system_accent2", "Secondary (accent2)", h_deg + style_cfg["sec_shift"], min(1.0, base_s * 0.42)),
        ("ACCENT3", "system_accent3", "Tertiary (accent3)", h_deg + style_cfg["tert_shift"], min(1.0, base_s * 0.58)),
        ("NEUTRAL1", "system_neutral1", "Neutral (neutral1)", h_deg, min(1.0, base_s * 0.08)),
        ("NEUTRAL2", "system_neutral2", "Neutral Variant (neutral2)", h_deg, min(1.0, base_s * 0.14)),
    ]

    groups_out: Dict[str, List[Dict[str, Any]]] = {}
    flat_shades: Dict[str, str] = {}

    for key, prefix, label, gh, gs in groups_spec:
        row = []
        for sh in SHADES:
            if pure_black and key == "NEUTRAL1" and sh >= 900:
                hex_val = "#000000"
            else:
                hex_val = _tone_to_hex(gh, gs, tone_for_shade(sh))
            res_name = f"{prefix}_{sh}"
            flat_shades[res_name] = hex_val
            row.append({"shade": sh, "name": res_name, "hex": hex_val})
        groups_out[key] = {"label": label, "prefix": prefix, "shades": row}

    # Material 3 Dark/Light Role Tokens (Android 14-16 / One UI 9.0)
    roles_dark = {
        "system_primary_dark": flat_shades["system_accent1_200"],
        "system_on_primary_dark": flat_shades["system_accent1_800"],
        "system_primary_container_dark": flat_shades["system_accent1_700"],
        "system_on_primary_container_dark": flat_shades["system_accent1_100"],
        "system_secondary_dark": flat_shades["system_accent2_200"],
        "system_on_secondary_dark": flat_shades["system_accent2_800"],
        "system_secondary_container_dark": flat_shades["system_accent2_700"],
        "system_on_secondary_container_dark": flat_shades["system_accent2_100"],
        "system_tertiary_dark": flat_shades["system_accent3_200"],
        "system_on_tertiary_dark": flat_shades["system_accent3_800"],
        "system_background_dark": "#000000" if pure_black else flat_shades["system_neutral1_900"],
        "system_on_background_dark": flat_shades["system_neutral1_100"],
        "system_surface_dark": "#000000" if pure_black else flat_shades["system_neutral1_900"],
        "system_surface_dim_dark": "#000000" if pure_black else flat_shades["system_neutral1_900"],
        "system_surface_container_lowest_dark": "#000000" if pure_black else flat_shades["system_neutral1_1000"],
        "system_surface_container_low_dark": "#05070B" if pure_black else flat_shades["system_neutral1_900"],
        "system_surface_container_dark": flat_shades["system_neutral2_800"],
        "system_surface_container_high_dark": flat_shades["system_neutral2_700"],
        "system_surface_container_highest_dark": flat_shades["system_neutral2_600"],
        "system_on_surface_dark": flat_shades["system_neutral1_100"],
        "system_on_surface_variant_dark": flat_shades["system_neutral2_200"],
        "system_outline_dark": flat_shades["system_neutral2_400"],
        "system_control_activated_dark": flat_shades["system_accent1_200"],
    }

    return {
        "seed": seed_hex.upper(),
        "style": style_id,
        "pure_black": pure_black,
        "groups": groups_out,
        "flat_shades": flat_shades,
        "roles_dark": roles_dark,
        "total_shades": len(flat_shades),
        "total_roles": len(roles_dark),
    }


def resolve_overlay_tokens(
    spec: Dict[str, Any],
    custom_overrides: Optional[Dict[str, str]] = None,
) -> List[Dict[str, Any]]:
    """
    Resolves concrete One UI 9.0 color tokens for every packed Samsung System RRO target package.
    """
    custom_overrides = custom_overrides or {}
    seed = spec.get("accent", "#00E5FF").upper()
    secondary = spec.get("secondary", "#7C4DFF").upper()
    bg = "#000000" if spec.get("pure_black", True) else spec.get("bg", "#080A0F").upper()
    surface = spec.get("surface", "#0C1018").upper()
    card = spec.get("card", "#121724").upper()
    qs_inactive = spec.get("qs_inactive", "#1C2233").upper()
    text_color = "#F3F6FC"

    monet = generate_monet_palette(seed, spec.get("style", "VIBRANT"), spec.get("pure_black", True))
    accent_deep = monet["flat_shades"].get("system_accent1_600", seed)
    tertiary = monet["flat_shades"].get("system_accent3_300", secondary)

    role_map = {
        "accent": seed,
        "accent_deep": accent_deep,
        "secondary": secondary,
        "tertiary": tertiary,
        "bg": bg,
        "surface": surface,
        "card": card,
        "qs_inactive": qs_inactive,
        "text": text_color,
    }

    selected_ids = set(spec.get("selected_targets", [item["id"] for item in ONEUI9_SYSTEM_RRO_CATALOG if item["selected"]]))
    owner = "android" if spec.get("privilege_uid", 1000) == 1000 else "com.android.shell"

    compiled_packages = []
    for item in ONEUI9_SYSTEM_RRO_CATALOG:
        is_selected = item["id"] in selected_ids
        resolved_resources = []
        fab_args = []
        for res in item["oneui9_resources"]:
            override_key = f"{item['target_package']}:{res['name']}"
            hex_val = custom_overrides.get(override_key, role_map.get(res["role"], seed)).upper()
            argb_hex = "0xff" + hex_val.lstrip("#").lower()
            resolved_resources.append({
                "name": res["name"],
                "full_name": f"{item['target_package']}:color/{res['name']}",
                "type": res["type"],
                "role": res["role"],
                "desc": res["desc"],
                "value": hex_val,
                "argb": argb_hex,
            })
            fab_args.append(f"{item['target_package']}:color/{res['name']} {res['type']} {argb_hex}")

        fab_cmd = (
            f"cmd overlay fabricate --target {item['target_package']} "
            f"--name {item['fabricated_name']} " + " ".join(fab_args)
        )
        enable_cmd = (
            f"cmd overlay enable --user 0 {owner}:{item['fabricated_name']} && "
            f"cmd overlay set-priority --user 0 {owner}:{item['fabricated_name']} highest"
        )

        # Generate AndroidManifest.xml and colors.xml for Samsung System RRO carrier APK
        manifest_xml = (
            '<?xml version="1.0" encoding="utf-8" standalone="no"?>\n'
            '<manifest xmlns:android="http://schemas.android.com/apk/res/android"\n'
            f'    package="{item["system_rro_package"]}"\n'
            '    android:versionCode="909090"\n'
            '    android:versionName="hex_oneui9">\n'
            '    <overlay\n'
            f'        android:targetPackage="{item["target_package"]}"\n'
            '        android:isStatic="false"\n'
            '        android:priority="9999" />\n'
            '    <application\n'
            f'        android:label="{item["label"]} (Hex One UI 9.0)"\n'
            '        android:hasCode="false"\n'
            '        android:extractNativeLibs="false"\n'
            '        android:allowBackup="false" />\n'
            '</manifest>\n'
        )
        colors_lines = ['<?xml version="1.0" encoding="utf-8"?>', "<resources>"]
        for r_item in resolved_resources:
            colors_lines.append(f'    <color name="{r_item["name"]}">{r_item["value"]}</color>')
        colors_lines.append("</resources>\n")
        colors_xml = "\n".join(colors_lines)

        compiled_packages.append({
            **item,
            "selected": is_selected,
            "owner_package": owner,
            "overlay_identifier": f"{owner}:{item['fabricated_name']}",
            "resolved_resources": resolved_resources,
            "fabricate_command": fab_cmd,
            "enable_command": enable_cmd,
            "manifest_xml": manifest_xml,
            "colors_xml": colors_xml,
        })

    return compiled_packages


def build_oneui9_bundle_zip(spec: Dict[str, Any], custom_overrides: Optional[Dict[str, str]] = None) -> bytes:
    """
    Packages the rebuilt One UI 9.0 Samsung System RRO overlays, Monet 65-shade table,
    QS toggle drawable XMLs, and UID 0 / UID 1000 installer script into a downloadable ZIP archive.
    """
    compiled = resolve_overlay_tokens(spec, custom_overrides)
    monet = generate_monet_palette(
        spec.get("accent", "#00E5FF"),
        spec.get("style", "VIBRANT"),
        spec.get("pure_black", True),
    )
    shape_id = spec.get("tile_shape", "squircle")
    shape_cfg = HEX_QS_TOGGLE_SHAPES.get(shape_id, HEX_QS_TOGGLE_SHAPES["squircle"])
    accent_hex = spec.get("accent", "#00E5FF").upper()
    qs_drawable_xml = shape_cfg["xml_template"].format(
        accent=accent_hex,
        accent_alpha="#33" + accent_hex.lstrip("#"),
    )

    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as zf:
        script_lines = [
            "#!/system/bin/sh",
            "# ======================================================================",
            "# HexNext — Rebuilt Hex Installer One UI 9.0 System RRO Installer",
            "# Privilege Mode: Shizuku (UID 0 / UID 1000) or Direct SU",
            "# Theme Park Requirement: REMOVED (Direct System RRO Injection)",
            "# ======================================================================",
            "",
            'CALLER_UID=$(id -u)',
            'if [ "$CALLER_UID" != "0" ] && [ "$CALLER_UID" != "1000" ]; then',
            '    echo "[!] Error: UID 0 (Root) or UID 1000 (System) is required (current UID=$CALLER_UID)."',
            '    exit 1',
            'fi',
            'echo "[*] Authenticated as privileged UID $CALLER_UID — building One UI 9.0 System RROs..."',
            "",
        ]

        # 1. Add 65 Monet framework shades fabrication command
        monet_args = []
        for res_name, hex_val in monet["flat_shades"].items():
            monet_args.append(f"android:color/{res_name} 0x1c 0xff{hex_val.lstrip('#').lower()}")
        for res_name, hex_val in monet["roles_dark"].items():
            monet_args.append(f"android:color/{res_name} 0x1c 0xff{hex_val.lstrip('#').lower()}")

        script_lines.append("# --- 1. Fabricate 65 Monet Shades + 23 Material 3 Dark Roles on android ---")
        script_lines.append(
            "cmd overlay fabricate --target android --name HexNextMonetShades " + " ".join(monet_args)
        )
        script_lines.append("cmd overlay enable --user 0 com.android.shell:HexNextMonetShades 2>/dev/null || cmd overlay enable --user 0 android:HexNextMonetShades")
        script_lines.append("")

        # 2. Add each selected One UI 9.0 System RRO package
        for pkg in compiled:
            if not pkg["selected"]:
                continue
            folder = f"overlays/{pkg['system_rro_package']}"
            zf.writestr(f"{folder}/AndroidManifest.xml", pkg["manifest_xml"])
            zf.writestr(f"{folder}/res/values/colors.xml", pkg["colors_xml"])
            if pkg["id"] == "systemui":
                zf.writestr(f"{folder}/res/drawable/sec_qs_tile_bg.xml", qs_drawable_xml)

            script_lines.append(f"# --- Target: {pkg['target_package']} ({pkg['system_rro_package']}) ---")
            script_lines.append(pkg["fabricate_command"])
            script_lines.append(pkg["enable_command"])
            script_lines.append(f"cmd overlay enable --user 0 {pkg['system_rro_package']} 2>/dev/null || true")
            script_lines.append("")

        script_lines.append('echo "[✓] All One UI 9.0 System RRO overlays fabricated and prioritized!"')
        zf.writestr("install_oneui9_overlays.sh", "\n".join(script_lines) + "\n")
        zf.writestr(
            "theme_profile_oneui9.json",
            json.dumps(
                {
                    "engine": "HexNext One UI 9.0 System RRO Compiler",
                    "theme_park_required": False,
                    "privilege_requirement": "UID 0 (Root) or UID 1000 (System)",
                    "spec": spec,
                    "monet_palette": monet,
                    "overlays": [
                        {
                            "id": p["id"],
                            "system_rro_package": p["system_rro_package"],
                            "target_package": p["target_package"],
                            "selected": p["selected"],
                            "resources": {r["name"]: r["value"] for r in p["resolved_resources"]},
                        }
                        for p in compiled
                    ],
                },
                indent=2,
            ),
        )

    return buf.getvalue()
