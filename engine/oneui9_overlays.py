"""
Rebuilt Hex Installer (#hex__30) + Niferpitou (terpsichora_) One UI 9.0 RRO Overlay Catalog & Compiler.

Replaces the legacy precompiled `.hex_temp/asset_factory` (`anaconda_0..anaconda_70` and `crocodile_13..65`)
resource tables with newly built-from-scratch One UI 9.0 (Android 16, SDK 36/37) RRO overlays
(`com.hexnext.oneui9.rro.*` and `OverlayManagerTransaction` fabricated overlays under UID 0 or UID 1000)
that take highest-priority precedence (`priority=10000` / `highest`) over Samsung's modern `SemWT_*`
(Samsung Wallpaper Theme) overlays.

Does NOT use High Contrast Theme overlays.
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

# Rebuilt Hex Plugins extracted from #hex__30 HEX_EOL.apk and Niferpitou APK
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
    "niferpitou_terpsichora": {
        "id": "niferpitou_terpsichora",
        "name": "terpsichora_ (Niferpitou)",
        "plugin_pkg": "project.vivid.ui.plugin.niferpitou",
        "author": "@envy4 • One UI 9.0 Rebuild",
        "seed": "#7A96AE",
        "secondary": "#B99499",
        "bg": "#15161B",
        "surface": "#1F2026",
        "card": "#252730",
        "qs_inactive": "#2D303B",
        "style": "TONAL_SPOT",
        "pure_black": False,
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

# Settings Icon Tint Styles extracted from project.vivid.ui.plugin.niferpitou
# (`assets/anaconda_tints/once/base/lxx1/settingsicon-*` and `assets/extra.preferences`)
SETTINGS_ICON_STYLES: List[Dict[str, Any]] = [
    {
        "id": "1sett",
        "label": "Primary Color Tint (1sett)",
        "asset_folder": "settingsicon-1sett",
        "frameless_folder": "settingsicon-1sett_frameless",
        "desc": "Tints all 26 One UI 9.0 SecSettings dashboard icons with your Primary Seed color.",
    },
    {
        "id": "2sett",
        "label": "Secondary Accent Tint (2sett)",
        "asset_folder": "settingsicon-2sett",
        "frameless_folder": "settingsicon-2sett_frameless",
        "desc": "Tints all 26 One UI 9.0 SecSettings dashboard icons with your Secondary Accent color.",
    },
    {
        "id": "3sett",
        "label": "OG Multi-Tone Colors (3sett)",
        "asset_folder": "settingsicon-3sett",
        "frameless_folder": "settingsicon-3sett_frameless",
        "desc": "Uses Niferpitou Terpsichora original multi-tone semantic palette per category icon.",
    },
    {
        "id": "alt_1sett",
        "label": "Duotone Primary + Accent (alt_1sett)",
        "asset_folder": "settingsicon-alt_1sett",
        "frameless_folder": "settingsicon-alt_1sett_frameless",
        "desc": "Primary seed foreground glyph with secondary accent container & badge highlight.",
    },
    {
        "id": "alt_2sett",
        "label": "Duotone Accent + Primary (alt_2sett)",
        "asset_folder": "settingsicon-alt_2sett",
        "frameless_folder": "settingsicon-alt_2sett_frameless",
        "desc": "Secondary accent foreground glyph with primary seed container & badge highlight.",
    },
]

# All 26 One UI 9.0 SecSettings Dashboard Icons extracted from Niferpitou APK
# (`assets/anaconda_tints/once/base/lxx1/settingsicon-*/*.png`) rebuilt as One UI 9.0 VectorDrawables
SETTINGS_DASHBOARD_ICONS: List[Dict[str, Any]] = [
    {
        "id": "connections",
        "res_name": "sec_ic_settings_connections",
        "title": "Connections",
        "subtitle": "Wi-Fi, Bluetooth, NFC, UWB",
        "og_color": "#38BDF8",
        "svg_path": "M12 20h.01M8.5 16.5a5 5 0 017 0M5 13a10 10 0 0114 0M2 9.5a15 15 0 0120 0",
    },
    {
        "id": "multi_devices",
        "res_name": "sec_ic_settings_multi_devices",
        "title": "Connected devices",
        "subtitle": "Quick Share, Galaxy Wear, DeX",
        "og_color": "#34D399",
        "svg_path": "M8 7h12m0 0l-4-4m4 4l-4 4m0 6H4m0 0l4 4m-4-4l4-4",
    },
    {
        "id": "modes",
        "res_name": "sec_ic_settings_modes",
        "title": "Modes and Routines",
        "subtitle": "Sleep, Driving, Work automation",
        "og_color": "#A78BFA",
        "svg_path": "M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z",
    },
    {
        "id": "notifications",
        "res_name": "sec_ic_settings_notifications",
        "title": "Notifications",
        "subtitle": "App notifications, Brief pop-up",
        "og_color": "#FB7185",
        "svg_path": "M15 17h5l-1.4-1.4A2 2 0 0118 14.2V11a6 6 0 00-4-5.7V5a2 2 0 10-4 0v.3A6 6 0 006 11v3.2c0 .5-.2 1-.6 1.4L4 17h5m6 0v1a3 3 0 11-6 0v-1m6 0H9",
    },
    {
        "id": "sound",
        "res_name": "ic_settings_sound",
        "title": "Sounds and vibration",
        "subtitle": "Sound mode, Ringtone, Dolby Atmos",
        "og_color": "#818CF8",
        "svg_path": "M15.5 8.5a5 5 0 010 7m2.8-9.8a9 9 0 010 12.6M6 15H4a1 1 0 01-1-1v-4a1 1 0 011-1h2l4-4v14l-4-4z",
    },
    {
        "id": "display",
        "res_name": "ic_settings_display",
        "title": "Display",
        "subtitle": "Dark mode, Motion smoothness, Font",
        "og_color": "#FBBF24",
        "svg_path": "M12 3v1m0 16v1m9-9h-1M4 12H3m15.4 6.4l-.7-.7M6.3 6.3l-.7-.7m12.8 0l-.7.7M6.3 17.7l-.7.7M16 12a4 4 0 11-8 0 4 4 0 018 0z",
    },
    {
        "id": "wallpaper",
        "res_name": "sec_ic_settings_wallpaper",
        "title": "Wallpaper and style",
        "subtitle": "Wallpapers, Color palette (SemWT_*)",
        "og_color": "#F472B6",
        "svg_path": "M4 16l4.6-4.6a2 2 0 012.8 0L16 16m-2-2l1.6-1.6a2 2 0 012.8 0L20 14m-6-6h.01M6 20h12a2 2 0 002-2V6a2 2 0 00-2-2H6a2 2 0 00-2 2v12a2 2 0 002 2z",
    },
    {
        "id": "themes",
        "res_name": "ic_theme",
        "title": "Themes",
        "subtitle": "HexNext From-Scratch RROs (> SemWT_*)",
        "og_color": "#C084FC",
        "svg_path": "M7 21a4 4 0 01-4-4V5a2 2 0 012-2h4a2 2 0 012 2v12a4 4 0 01-4 4zm0 0h12a2 2 0 002-2v-4a2 2 0 00-2-2h-2.3M11 7.3l1.7-1.7a2 2 0 012.8 0l2.8 2.8a2 2 0 010 2.8l-8.5 8.5",
    },
    {
        "id": "homescreen",
        "res_name": "sec_ic_settings_homescreen",
        "title": "Home screen",
        "subtitle": "Layout, Grid 5x6, App icon badges",
        "og_color": "#60A5FA",
        "svg_path": "M3 12l2-2m0 0l7-7 7 7M5 10v10a1 1 0 001 1h3m10-11l2 2m-2-2v10a1 1 0 01-1 1h-3m-6 0a1 1 0 001-1v-4a1 1 0 011-1h2a1 1 0 011 1v4a1 1 0 001 1m-6 0h6",
    },
    {
        "id": "lockscreen",
        "res_name": "sec_ic_settings_lockscreen",
        "title": "Lock screen and AOD",
        "subtitle": "Screen lock type, Always On Display",
        "og_color": "#2DD4BF",
        "svg_path": "M12 15v2m-6 4h12a2 2 0 002-2v-6a2 2 0 00-2-2H6a2 2 0 00-2 2v6a2 2 0 002 2zm10-10V7a4 4 0 00-8 0v4h8z",
    },
    {
        "id": "biometrics",
        "res_name": "sec_ic_settings_biometrics_and_security",
        "title": "Security and privacy",
        "subtitle": "Biometrics, Auto Blocker, Knox Matrix",
        "og_color": "#4ADE80",
        "svg_path": "M9 12l2 2 4-4m5.6-4.4A12 12 0 0012 3a12 12 0 00-8.6 4.6A12 12 0 0012 21a12 12 0 008.6-13.4z",
    },
    {
        "id": "location",
        "res_name": "ic_settings_location",
        "title": "Location",
        "subtitle": "Location permissions, Wi-Fi scanning",
        "og_color": "#38BDF8",
        "svg_path": "M17.7 16.7L13.4 21a2 2 0 01-2.8 0l-4.2-4.3a8 8 0 1111.3 0zM15 11a3 3 0 11-6 0 3 3 0 016 0z",
    },
    {
        "id": "emergency",
        "res_name": "sec_ic_settings_emergency",
        "title": "Safety and emergency",
        "subtitle": "Medical info, Emergency SOS, Alerts",
        "og_color": "#F87171",
        "svg_path": "M12 9v2m0 4h.01m-6.9 4h13.8c1.5 0 2.5-1.7 1.7-3L13.7 4c-.8-1.3-2.7-1.3-3.5 0L3.3 16c-.8 1.3.2 3 1.8 3z",
    },
    {
        "id": "accounts",
        "res_name": "sec_ic_settings_cloud_and_account",
        "title": "Accounts and backup",
        "subtitle": "Manage accounts, Samsung Cloud, Smart Switch",
        "og_color": "#60A5FA",
        "svg_path": "M16 7a4 4 0 11-8 0 4 4 0 018 0zM12 14a7 7 0 00-7 7h14a7 7 0 00-7-7z",
    },
    {
        "id": "google",
        "res_name": "sec_ic_settings_google",
        "title": "Google",
        "subtitle": "Google Play services & parental controls",
        "og_color": "#93C5FD",
        "svg_path": "M21 12a9 9 0 11-3.2-6.9M21 12h-9",
    },
    {
        "id": "advanced",
        "res_name": "sec_ic_settings_advanced_feature",
        "title": "Advanced features",
        "subtitle": "Labs, S Pen, Multi window, Motions",
        "og_color": "#FBBF24",
        "svg_path": "M5 3v4M3 5h4M6 17v4m-2-2h4m5-16l2.3 6.9L21 12l-5.7 2.1L13 21l-2.3-6.9L5 12l5.7-2.1L13 3z",
    },
    {
        "id": "wellbeing",
        "res_name": "sec_ic_settings_digitalwellbeing",
        "title": "Digital Wellbeing",
        "subtitle": "Screen time, App timers, Driving monitor",
        "og_color": "#34D399",
        "svg_path": "M4.3 6.3a4.5 4.5 0 016.4 0L12 7.6l1.3-1.3a4.5 4.5 0 116.4 6.4L12 20.4l-7.7-7.7a4.5 4.5 0 010-6.4z",
    },
    {
        "id": "devicecare",
        "res_name": "sec_ic_settings_devicecare",
        "title": "Device care",
        "subtitle": "Storage, Memory, App protection",
        "og_color": "#2DD4BF",
        "svg_path": "M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z",
    },
    {
        "id": "apps",
        "res_name": "ic_settings_applications",
        "title": "Apps",
        "subtitle": "Default apps, App management",
        "og_color": "#818CF8",
        "svg_path": "M4 6a2 2 0 012-2h2a2 2 0 012 2v2a2 2 0 01-2 2H6a2 2 0 01-2-2V6zm10 0a2 2 0 012-2h2a2 2 0 012 2v2a2 2 0 01-2 2h-2a2 2 0 01-2-2V6zM4 16a2 2 0 012-2h2a2 2 0 012 2v2a2 2 0 01-2 2H6a2 2 0 01-2-2v-2zm10 0a2 2 0 012-2h2a2 2 0 012 2v2a2 2 0 01-2 2h-2a2 2 0 01-2-2v-2z",
    },
    {
        "id": "general",
        "res_name": "sec_ic_settings_general_device_management",
        "title": "General management",
        "subtitle": "Language, Date and time, Samsung Keyboard",
        "og_color": "#94A3B8",
        "svg_path": "M12 6V4m0 2a2 2 0 100 4m0-4a2 2 0 110 4m-6 8a2 2 0 100-4m0 4a2 2 0 110-4m0 4v2m0-6V4m6 6v10m6-2a2 2 0 100-4m0 4a2 2 0 110-4m0 4v2m0-6V4",
    },
    {
        "id": "accessibility",
        "res_name": "ic_settings_accessibility",
        "title": "Accessibility",
        "subtitle": "TalkBack, Vision & Hearing enhancements",
        "og_color": "#4ADE80",
        "svg_path": "M16 7a4 4 0 11-8 0 4 4 0 018 0zM12 14v7m-4-5l4-2 4 2",
    },
    {
        "id": "software_update",
        "res_name": "sec_ic_settings_software_update",
        "title": "Software update",
        "subtitle": "Download and install, One UI 9.0",
        "og_color": "#38BDF8",
        "svg_path": "M4 4v5h.6m15.4 2A8 8 0 004.6 9m0 0H9m11 11v-5h-.6m0 0a8 8 0 01-15.4-2m15.4 2H15",
    },
    {
        "id": "remotesupport",
        "res_name": "sec_ic_settings_remotesupport",
        "title": "Tips and user guide",
        "subtitle": "Remote support, One UI 9.0 changelog",
        "og_color": "#FBBF24",
        "svg_path": "M8.2 9c.5-1.2 1.8-2 3.3-2 2 0 3.5 1.3 3.5 3 0 1.4-1 2.2-2.2 2.7-.7.3-1.3.8-1.3 1.6V15m0 3h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z",
    },
    {
        "id": "coverscreen",
        "res_name": "sec_ic_settings_coverscreen",
        "title": "Cover screen",
        "subtitle": "Flex Window widgets & quick settings",
        "og_color": "#A78BFA",
        "svg_path": "M9 3H5a2 2 0 00-2 2v14a2 2 0 002 2h4m6-18h4a2 2 0 012 2v14a2 2 0 01-2 2h-4m-6-18v18m6-18v18",
    },
    {
        "id": "battery",
        "res_name": "sec_ic_settings_battery",
        "title": "Battery",
        "subtitle": "Battery protection, Charging settings",
        "og_color": "#4ADE80",
        "svg_path": "M13 10V3L4 14h7v7l9-11h-7z",
    },
    {
        "id": "about",
        "res_name": "ic_settings_about",
        "title": "About phone",
        "subtitle": "SM-S938U • One UI 9.0 • Android 16",
        "og_color": "#94A3B8",
        "svg_path": "M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z",
    },
]

# Extra preferences catalog extracted from Niferpitou APK `assets/extra.preferences`
NIFERPITOU_EXTRA_PREFERENCES: Dict[str, Any] = {
    "plugin_title": "terpsichora_ - Day/Night",
    "plugin_package": "project.vivid.ui.plugin.niferpitou",
    "plugin_author": "@envy4",
    "settings_icon_styles": SETTINGS_ICON_STYLES,
    "categories": [
        {
            "id": "settings_tweaks",
            "title": "Settings Icons & Dashboard Tweaks (anaconda_tints/settingsicon-*)",
            "options": [
                {"key": "settframe", "type": "switch", "title": "Remove Dashboard Icons Frame", "subtitle": "Switches to settingsicon-*_frameless vector drawables"},
                {"key": "bigsetico", "type": "switch", "title": "Large Settings Dashboard Icons", "subtitle": "Enlarges SecSettings category icons from 28dp to 36dp"},
                {"key": "hidemail", "type": "switch", "title": "Hide Account Info on Dashboard", "subtitle": "Masks Samsung Account email address in top SecSettings header"},
                {"key": "uselogo", "type": "switch", "title": "Use Custom About Phone Logo", "subtitle": "Displays Terpsichora custom graphic header inside About Phone"},
                {"key": "uidivider", "type": "switch", "title": "Use Applist & Settings Dividers", "subtitle": "Renders subtle 1dp list dividers between preference rows"},
            ],
        },
        {
            "id": "qs_tweaks",
            "title": "Quick Settings & Notification Tweaks (crocodile_27)",
            "options": [
                {
                    "key": "qs_icon_style",
                    "type": "select",
                    "title": "Quick Settings Icon Style",
                    "choices": [
                        {"value": "default", "label": "Default One UI 9"},
                        {"value": "fill", "label": "Filled Glyphs (fill)"},
                        {"value": "outlin", "label": "Outlined Glyphs (outlin)"},
                    ],
                },
                {
                    "key": "qs_toggle_size",
                    "type": "select",
                    "title": "Quick Settings Toggle Size",
                    "choices": [
                        {"value": "default", "label": "Default (44dp)"},
                        {"value": "bigtoggle", "label": "Big Toggles (52dp)"},
                        {"value": "smalltoggle", "label": "Compact Toggles (38dp)"},
                    ],
                },
                {
                    "key": "qs_blur_mode",
                    "type": "select",
                    "title": "Panel Blur Intensity",
                    "choices": [
                        {"value": "default", "label": "Default (100%)"},
                        {"value": "1qsblur", "label": "Reduce to 75% (1qsblur)"},
                        {"value": "2qsblur", "label": "Reduce to 50% (2qsblur)"},
                        {"value": "3qsblur", "label": "Reduce to 25% (3qsblur)"},
                        {"value": "noqsblur", "label": "Disable Panel Blur (noqsblur)"},
                    ],
                },
                {
                    "key": "notif_corner_radius",
                    "type": "select",
                    "title": "Notification Card Corner Radius",
                    "choices": [
                        {"value": "default", "label": "Default One UI 9 (22dp)"},
                        {"value": "1roundnoti", "label": "Sharp 0dp (1roundnoti)"},
                        {"value": "2roundnoti", "label": "Subtle 6dp (2roundnoti)"},
                        {"value": "3roundnoti", "label": "Medium 12dp (3roundnoti)"},
                        {"value": "4roundnoti", "label": "Rounded 18dp (4roundnoti)"},
                    ],
                },
                {"key": "hidcarriernoti", "type": "switch", "title": "Hide Carrier Label on Notification Panel", "subtitle": "Removes carrier text from bottom of One UI 9 split shade"},
            ],
        },
        {
            "id": "statusbar_tweaks",
            "title": "Statusbar & Network Indicators",
            "options": [
                {
                    "key": "statusbar_icon_style",
                    "type": "select",
                    "title": "Statusbar Wi-Fi & Signal Bars",
                    "choices": [
                        {"value": "default", "label": "Default One UI 9"},
                        {"value": "0staico", "label": "Terpsichora Custom Bars (0staico)"},
                        {"value": "1staico", "label": "Minimal Line Bars (1staico)"},
                        {"value": "2staico", "label": "Segmented Pills (2staico)"},
                        {"value": "3staico", "label": "Dot Matrix Signal (3staico)"},
                    ],
                },
                {"key": "4g", "type": "switch", "title": "Use 4G Instead of LTE Icon", "subtitle": "Replaces LTE data indicator with compact 4G/5G glyph"},
                {"key": "inout", "type": "switch", "title": "Hide Data In-Out Arrows", "subtitle": "Hides upload/download arrows under Wi-Fi & Mobile Data icons"},
            ],
        },
        {
            "id": "ui_components",
            "title": "UI Controls, Keyboard, Volume & Navbar",
            "options": [
                {
                    "key": "switch_thumb_tint",
                    "type": "select",
                    "title": "Switch Knob Tint",
                    "choices": [
                        {"value": "default", "label": "Default White"},
                        {"value": "0swithumb", "label": "Tint with Primary Color (0swithumb)"},
                        {"value": "1swithumb", "label": "Tint with Accent Color (1swithumb)"},
                    ],
                },
                {
                    "key": "volume_panel_style",
                    "type": "select",
                    "title": "Volume Panel Layout (crocodile_65)",
                    "choices": [
                        {"value": "default", "label": "Default One UI 9"},
                        {"value": "0vvol", "label": "Terpsichora Slim Slider (0vvol)"},
                        {"value": "1vvol", "label": "Compact Pill Slider (1vvol)"},
                    ],
                },
                {
                    "key": "keyboard_border_style",
                    "type": "select",
                    "title": "HoneyBoard Key Border (crocodile_25)",
                    "choices": [
                        {"value": "default", "label": "Filled Keys (Default)"},
                        {"value": "0kbor", "label": "Borderless Minimal (0kbor)"},
                        {"value": "2kbor", "label": "Primary Color Key Outline (2kbor)"},
                        {"value": "3kbor", "label": "Accent Color Key Outline (3kbor)"},
                    ],
                },
                {
                    "key": "navbar_style",
                    "type": "select",
                    "title": "Navigation Bar Style (crocodile_41)",
                    "choices": [
                        {"value": "default", "label": "Default One UI 9"},
                        {"value": "0nav", "label": "Terpsichora Geometric (0nav)"},
                        {"value": "1nav", "label": "Minimal Line Pill (1nav)"},
                        {"value": "nonav", "label": "Hide Gesture Hint Bar (nonav)"},
                    ],
                },
                {
                    "key": "dialpad_call_btn",
                    "type": "select",
                    "title": "Dialpad Call Button Tint (crocodile_17)",
                    "choices": [
                        {"value": "default", "label": "Stock Green"},
                        {"value": "0calbtn", "label": "Tint with Primary Color (0calbtn)"},
                        {"value": "1calbtn", "label": "Tint with Accent Color (1calbtn)"},
                    ],
                },
            ],
        },
    ],
}

# All packed overlays extracted from #hex__30 HEX_EOL.apk (`anaconda_*`) and Niferpitou (`crocodile_*` / `anaconda_tints`),
# rebuilt from scratch as `com.hexnext.oneui9.rro.*` overlays with priority=10000 (`highest`) so they take
# precedence over Samsung's modern `SemWT_*` (Samsung Wallpaper Theme) overlays on One UI 9.0 (Android 16).
# Does NOT use High Contrast Theme overlays.
ONEUI9_SYSTEM_RRO_CATALOG: List[Dict[str, Any]] = [
    {
        "id": "common",
        "anaconda_asset": "assets/anaconda/res/anaconda_0",
        "system_rro_package": "com.hexnext.oneui9.rro.common",
        "semwt_target": "android:SemWT_android, android:SemWT_MonetPalette, android:SemWT_G_MonetPalette",
        "fabricated_name": "HexNextOneUi9Android",
        "target_package": "android",
        "label": "Android Framework & Monet Core",
        "category": "Core Framework",
        "selected": True,
        "notes": "Built from scratch as com.hexnext.oneui9.rro.common (priority=10000) to override android:SemWT_android & SemWT_MonetPalette.",
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
        "anaconda_asset": "assets/anaconda/res/anaconda_13 + crocodile_27",
        "system_rro_package": "com.hexnext.oneui9.rro.systemui",
        "semwt_target": "com.android.systemui:SemWT_com.android.systemui",
        "fabricated_name": "HexNextOneUi9SystemUI",
        "target_package": "com.android.systemui",
        "label": "SystemUI, Split QS & Notifications",
        "category": "Core Framework",
        "selected": True,
        "notes": "Built from scratch as com.hexnext.oneui9.rro.systemui (priority=10000) to override SemWT_com.android.systemui.",
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
        "anaconda_asset": "assets/anaconda/res/anaconda_28 + anaconda_tints/settingsicon-*",
        "system_rro_package": "com.hexnext.oneui9.rro.settings",
        "semwt_target": "com.android.settings:SemWT_com.android.settings",
        "fabricated_name": "HexNextOneUi9Settings",
        "target_package": "com.android.settings",
        "label": "One UI 9.0 SecSettings & 26 Dashboard Icons",
        "category": "Core Framework",
        "selected": True,
        "notes": "Built from scratch as com.hexnext.oneui9.rro.settings (priority=10000) to override SemWT_com.android.settings + 26 VectorDrawables.",
        "legacy_broken_resources": [
            "dashboard_category_background_color (removed One UI 7)",
            "sec_ActionBar_background_color (renamed One UI 9)",
            "settingsicon-1sett PNG bitmaps (replaced by One UI 9 VectorDrawables)",
        ],
        "oneui9_resources": [
            {"name": "sec_widget_round_and_bgcolor", "type": "0x1c", "role": "card", "desc": "Rounded preference group card"},
            {"name": "sec_dashboard_background_color", "type": "0x1c", "role": "bg", "desc": "Main Settings scroll canvas"},
            {"name": "sec_dashboard_tab_selected_color", "type": "0x1c", "role": "accent", "desc": "Selected top/side tab tint"},
            {"name": "sec_sub_app_bar_background_color", "type": "0x1c", "role": "surface", "desc": "Sub-header status bar strip"},
            {"name": "list_item_background_color", "type": "0x1c", "role": "card", "desc": "Settings row item container"},
            {"name": "sec_dashboard_icon_tint_color", "type": "0x1c", "role": "settings_icon_tint", "desc": "Dashboard category icon glyph tint (1sett/2sett/3sett)"},
            {"name": "sec_dashboard_icon_frame_color", "type": "0x1c", "role": "settings_icon_frame", "desc": "Dashboard icon squircle frame container (settframe)"},
            {"name": "sec_widget_list_divider_color", "type": "0x1c", "role": "settings_divider", "desc": "Preference row 1dp divider line (uidivider)"},
            {"name": "sec_account_profile_email_text_color", "type": "0x1c", "role": "settings_email", "desc": "Samsung Account email subtitle visibility tint (hidemail)"},
        ],
    },
    {
        "id": "dialer",
        "anaconda_asset": "assets/anaconda/res/anaconda_10 + crocodile_17",
        "system_rro_package": "com.hexnext.oneui9.rro.dialer",
        "semwt_target": "com.samsung.android.dialer:SemWT_com.samsung.android.dialer",
        "fabricated_name": "HexNextOneUi9Dialer",
        "target_package": "com.samsung.android.dialer",
        "label": "Samsung Phone & Keypad",
        "category": "Samsung System Apps",
        "selected": True,
        "notes": "Built from scratch as com.hexnext.oneui9.rro.dialer (priority=10000) to override SemWT_com.samsung.android.dialer.",
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
        "system_rro_package": "com.hexnext.oneui9.rro.incallui",
        "semwt_target": "com.samsung.android.incallui:SemWT_com.samsung.android.incallui",
        "fabricated_name": "HexNextOneUi9InCallUI",
        "target_package": "com.samsung.android.incallui",
        "label": "Samsung InCallUI & Call Card",
        "category": "Samsung System Apps",
        "selected": True,
        "notes": "Built from scratch as com.hexnext.oneui9.rro.incallui (priority=10000) to override SemWT_com.samsung.android.incallui.",
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
        "anaconda_asset": "assets/anaconda/res/anaconda_12 + crocodile_16",
        "system_rro_package": "com.hexnext.oneui9.rro.contacts",
        "semwt_target": "com.samsung.android.app.contacts:SemWT_com.samsung.android.app.contacts",
        "fabricated_name": "HexNextOneUi9Contacts",
        "target_package": "com.samsung.android.app.contacts",
        "label": "Samsung Contacts",
        "category": "Samsung System Apps",
        "selected": True,
        "notes": "Built from scratch as com.hexnext.oneui9.rro.contacts (priority=10000) to override SemWT_com.samsung.android.app.contacts.",
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
        "anaconda_asset": "assets/anaconda/res/anaconda_17 + crocodile_21",
        "system_rro_package": "com.hexnext.oneui9.rro.messaging",
        "semwt_target": "com.samsung.android.messaging:SemWT_com.samsung.android.messaging",
        "fabricated_name": "HexNextOneUi9Messages",
        "target_package": "com.samsung.android.messaging",
        "label": "Samsung Messages",
        "category": "Samsung System Apps",
        "selected": True,
        "notes": "Built from scratch as com.hexnext.oneui9.rro.messaging (priority=10000) to override SemWT_com.samsung.android.messaging.",
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
        "anaconda_asset": "assets/anaconda/res/anaconda_22 + crocodile_25",
        "system_rro_package": "com.hexnext.oneui9.rro.honeyboard",
        "semwt_target": "com.samsung.android.honeyboard:SemWT_com.samsung.android.honeyboard",
        "fabricated_name": "HexNextOneUi9Keyboard",
        "target_package": "com.samsung.android.honeyboard",
        "label": "Samsung Keyboard (HoneyBoard)",
        "category": "Samsung System Apps",
        "selected": True,
        "notes": "Built from scratch as com.hexnext.oneui9.rro.honeyboard (priority=10000) to override SemWT_com.samsung.android.honeyboard.",
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
        "anaconda_asset": "assets/anaconda/res/anaconda_20 + crocodile_22",
        "system_rro_package": "com.hexnext.oneui9.rro.myfiles",
        "semwt_target": "com.sec.android.app.myfiles:SemWT_com.sec.android.app.myfiles",
        "fabricated_name": "HexNextOneUi9MyFiles",
        "target_package": "com.sec.android.app.myfiles",
        "label": "Samsung My Files",
        "category": "Samsung System Apps",
        "selected": True,
        "notes": "Built from scratch as com.hexnext.oneui9.rro.myfiles (priority=10000) to override SemWT_com.sec.android.app.myfiles.",
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
        "anaconda_asset": "assets/anaconda/res/anaconda_21 + crocodile_20",
        "system_rro_package": "com.hexnext.oneui9.rro.gallery3d",
        "semwt_target": "com.sec.android.gallery3d:SemWT_com.sec.android.gallery3d",
        "fabricated_name": "HexNextOneUi9Gallery",
        "target_package": "com.sec.android.gallery3d",
        "label": "Samsung Gallery",
        "category": "Samsung System Apps",
        "selected": True,
        "notes": "Built from scratch as com.hexnext.oneui9.rro.gallery3d (priority=10000) to override SemWT_com.sec.android.gallery3d.",
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
        "anaconda_asset": "assets/anaconda/res/anaconda_15 + crocodile_15",
        "system_rro_package": "com.hexnext.oneui9.rro.clockpackage",
        "semwt_target": "com.sec.android.app.clockpackage:SemWT_com.sec.android.app.clockpackage",
        "fabricated_name": "HexNextOneUi9Clock",
        "target_package": "com.sec.android.app.clockpackage",
        "label": "Samsung Clock & Alarms",
        "category": "Samsung System Apps",
        "selected": True,
        "notes": "Built from scratch as com.hexnext.oneui9.rro.clockpackage (priority=10000) to override SemWT_com.sec.android.app.clockpackage.",
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
        "anaconda_asset": "assets/anaconda/res/anaconda_16 + crocodile_13",
        "system_rro_package": "com.hexnext.oneui9.rro.popupcalculator",
        "semwt_target": "com.sec.android.app.popupcalculator:SemWT_com.sec.android.app.popupcalculator",
        "fabricated_name": "HexNextOneUi9Calculator",
        "target_package": "com.sec.android.app.popupcalculator",
        "label": "Samsung Calculator",
        "category": "Samsung System Apps",
        "selected": True,
        "notes": "Built from scratch as com.hexnext.oneui9.rro.popupcalculator (priority=10000) to override SemWT_com.sec.android.app.popupcalculator.",
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
        "anaconda_asset": "assets/anaconda/res/anaconda_25 + crocodile_14",
        "system_rro_package": "com.hexnext.oneui9.rro.calendar",
        "semwt_target": "com.samsung.android.calendar:SemWT_com.samsung.android.calendar",
        "fabricated_name": "HexNextOneUi9Calendar",
        "target_package": "com.samsung.android.calendar",
        "label": "Samsung Calendar",
        "category": "Samsung System Apps",
        "selected": True,
        "notes": "Built from scratch as com.hexnext.oneui9.rro.calendar (priority=10000) to override SemWT_com.samsung.android.calendar.",
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
        "anaconda_asset": "assets/anaconda/res/anaconda_27 + crocodile_18",
        "system_rro_package": "com.hexnext.oneui9.rro.lool",
        "semwt_target": "com.samsung.android.lool:SemWT_com.samsung.android.lool",
        "fabricated_name": "HexNextOneUi9DeviceCare",
        "target_package": "com.samsung.android.lool",
        "label": "Samsung Device Care",
        "category": "Samsung System Apps",
        "selected": True,
        "notes": "Built from scratch as com.hexnext.oneui9.rro.lool (priority=10000) to override SemWT_com.samsung.android.lool.",
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
        "system_rro_package": "com.hexnext.oneui9.rro.home",
        "semwt_target": "com.sec.android.app.launcher:SemWT_com.sec.android.app.launcher",
        "fabricated_name": "HexNextOneUi9Launcher",
        "target_package": "com.sec.android.app.launcher",
        "label": "One UI Home Launcher",
        "category": "Samsung System Apps",
        "selected": True,
        "notes": "Built from scratch as com.hexnext.oneui9.rro.home (priority=10000) to override SemWT_com.sec.android.app.launcher.",
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
        "system_rro_package": "com.hexnext.oneui9.rro.aodservice",
        "semwt_target": "com.samsung.android.app.aodservice:SemWT_com.samsung.android.app.aodservice",
        "fabricated_name": "HexNextOneUi9AOD",
        "target_package": "com.samsung.android.app.aodservice",
        "label": "Always On Display & Lockscreen",
        "category": "Samsung System Apps",
        "selected": True,
        "notes": "Built from scratch as com.hexnext.oneui9.rro.aodservice (priority=10000) to override SemWT_com.samsung.android.app.aodservice.",
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
        "anaconda_asset": "assets/anaconda/res/anaconda_33 + crocodile_26",
        "system_rro_package": "com.hexnext.oneui9.rro.smartcapture",
        "semwt_target": "com.samsung.android.app.smartcapture:SemWT_com.samsung.android.app.smartcapture",
        "fabricated_name": "HexNextOneUi9SmartCapture",
        "target_package": "com.samsung.android.app.smartcapture",
        "label": "Samsung Smart Capture",
        "category": "Samsung System Apps",
        "selected": False,
        "notes": "Built from scratch as com.hexnext.oneui9.rro.smartcapture (priority=10000) to override SemWT_com.samsung.android.app.smartcapture.",
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
        "system_rro_package": "com.hexnext.oneui9.rro.routines",
        "semwt_target": "com.samsung.android.app.routines:SemWT_com.samsung.android.app.routines",
        "fabricated_name": "HexNextOneUi9Routines",
        "target_package": "com.samsung.android.app.routines",
        "label": "Modes & Routines",
        "category": "Samsung System Apps",
        "selected": False,
        "notes": "Built from scratch as com.hexnext.oneui9.rro.routines (priority=10000) to override SemWT_com.samsung.android.app.routines.",
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
    l_ratio = max(0.0, min(1.0, tone / 100.0))
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

    groups_out: Dict[str, Any] = {}
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


def resolve_settings_dashboard_icons(
    spec: Dict[str, Any],
    custom_overrides: Optional[Dict[str, str]] = None,
) -> Dict[str, Any]:
    """
    Computes the One UI 9.0 SecSettings Dashboard Icon customization state and generates
    Android VectorDrawable XMLs, dimens.xml, bools.xml, and HexNextSettingsIcons26 fabrication
    commands for all 26 dashboard icons from project.vivid.ui.plugin.niferpitou.
    """
    custom_overrides = custom_overrides or {}
    seed = spec.get("accent", "#00E5FF").upper()
    secondary = spec.get("secondary", "#7C4DFF").upper()
    card = spec.get("card", "#121724").upper()
    owner = "android" if spec.get("privilege_uid", 1000) == 1000 else "com.android.shell"

    tweaks = spec.get("extra_tweaks", {}) or {}
    icon_style = tweaks.get("settings_icon_style", "1sett")
    settframe = bool(tweaks.get("settframe", False))
    bigsetico = bool(tweaks.get("bigsetico", False))
    hidemail = bool(tweaks.get("hidemail", False))
    uselogo = bool(tweaks.get("uselogo", True))
    uidivider = bool(tweaks.get("uidivider", True))
    per_icon_colors = tweaks.get("per_icon_colors", {}) or {}

    style_meta = next(
        (s for s in SETTINGS_ICON_STYLES if s["id"] == icon_style),
        SETTINGS_ICON_STYLES[0],
    )
    active_folder = style_meta["frameless_folder"] if settframe else style_meta["asset_folder"]
    icon_dp = 36 if bigsetico else 28

    resolved_icons = []
    fab_icon_args = []
    for ic in SETTINGS_DASHBOARD_ICONS:
        token_name = f"{ic['res_name']}_tint"
        override_key = f"com.android.settings:{token_name}"
        custom_hex = per_icon_colors.get(ic["id"]) or custom_overrides.get(override_key)
        if custom_hex:
            fg_color = custom_hex.upper()
            badge_color = secondary
        elif icon_style == "1sett":
            fg_color = seed
            badge_color = seed
        elif icon_style == "2sett":
            fg_color = secondary
            badge_color = secondary
        elif icon_style == "3sett":
            fg_color = ic["og_color"].upper()
            badge_color = seed
        elif icon_style == "alt_1sett":
            fg_color = seed
            badge_color = secondary
        else:  # alt_2sett
            fg_color = secondary
            badge_color = seed

        frame_bg = "transparent" if settframe else ("#1F" + fg_color.lstrip("#"))
        frame_border = "transparent" if settframe else ("#4D" + fg_color.lstrip("#"))
        argb_hex = "0xff" + fg_color.lstrip("#").lower()
        fab_icon_args.append(f"com.android.settings:color/{token_name} 0x1c {argb_hex}")

        is_duotone = icon_style in ("alt_1sett", "alt_2sett")
        vector_xml = (
            '<?xml version="1.0" encoding="utf-8"?>\n'
            f'<!-- Rebuilt from project.vivid.ui.plugin.niferpitou ({active_folder}/{ic["res_name"]}.png) for One UI 9.0 (precedence > SemWT_Settings) -->\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            f'    android:width="{icon_dp}dp"\n'
            f'    android:height="{icon_dp}dp"\n'
            '    android:viewportWidth="24"\n'
            '    android:viewportHeight="24">\n'
        )
        if not settframe:
            vector_xml += (
                '    <path\n'
                f'        android:fillColor="#26{fg_color.lstrip("#")}"\n'
                f'        android:strokeColor="#4D{badge_color.lstrip("#") if is_duotone else fg_color.lstrip("#")}"\n'
                '        android:strokeWidth="0.75"\n'
                '        android:pathData="M7,2h10c2.76,0 5,2.24 5,5v10c0,2.76 -2.24,5 -5,5H7c-2.76,0 -5,-2.24 -5,-5V7c0,-2.76 2.24,-5 5,-5z" />\n'
            )
        if is_duotone:
            vector_xml += (
                '    <path\n'
                '        android:strokeWidth="3.2"\n'
                '        android:strokeLineCap="round"\n'
                '        android:strokeLineJoin="round"\n'
                f'        android:strokeColor="#38{badge_color.lstrip("#")}"\n'
                '        android:fillColor="#00000000"\n'
                f'        android:pathData="{ic["svg_path"]}" />\n'
            )
        vector_xml += (
            '    <path\n'
            '        android:strokeWidth="2.0"\n'
            '        android:strokeLineCap="round"\n'
            '        android:strokeLineJoin="round"\n'
            f'        android:strokeColor="{fg_color}"\n'
            '        android:fillColor="#00000000"\n'
            f'        android:pathData="{ic["svg_path"]}" />\n'
            '</vector>\n'
        )

        resolved_icons.append({
            **ic,
            "token_name": token_name,
            "fg_color": fg_color,
            "badge_color": badge_color,
            "argb": argb_hex,
            "frame_bg": frame_bg,
            "frame_border": frame_border,
            "frameless": settframe,
            "size_dp": icon_dp,
            "source_asset": f"assets/anaconda_tints/once/base/lxx1/{active_folder}/{ic['res_name']}.png",
            "oneui9_drawable": f"com.android.settings:drawable/{ic['res_name']}",
            "oneui9_color_token": f"com.android.settings:color/{token_name}",
            "vector_xml": vector_xml,
        })

    dimens_xml = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<!-- One UI 9.0 SecSettings Dashboard Dimensions (HexNext > SemWT_Settings) -->\n'
        '<resources>\n'
        f'    <dimen name="sec_dashboard_tile_image_size">{icon_dp}.0dip</dimen>\n'
        f'    <dimen name="sec_dashboard_tile_image_padding">{"0.0dip" if settframe else "6.0dip"}</dimen>\n'
        f'    <dimen name="sec_widget_list_divider_height">{"1.0dip" if uidivider else "0.0dip"}</dimen>\n'
        '</resources>\n'
    )

    bools_xml = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<!-- One UI 9.0 SecSettings Feature Flags (Niferpitou / Terpsichora Port) -->\n'
        '<resources>\n'
        f'    <bool name="sec_use_about_phone_hexxed_logo">{"true" if uselogo else "false"}</bool>\n'
        f'    <bool name="sec_hide_samsung_account_email">{"true" if hidemail else "false"}</bool>\n'
        f'    <bool name="sec_dashboard_frameless_icons">{"true" if settframe else "false"}</bool>\n'
        '</resources>\n'
    )

    fabricate_command = (
        "cmd overlay fabricate --target com.android.settings --name HexNextSettingsIcons26 "
        + " ".join(fab_icon_args)
    )
    enable_command = (
        f"cmd overlay enable --user 0 {owner}:HexNextSettingsIcons26 && "
        f"cmd overlay set-priority --user 0 {owner}:HexNextSettingsIcons26 highest"
    )

    return {
        "style_id": icon_style,
        "style_label": style_meta["label"],
        "active_asset_folder": active_folder,
        "settframe": settframe,
        "bigsetico": bigsetico,
        "hidemail": hidemail,
        "uselogo": uselogo,
        "uidivider": uidivider,
        "icon_size_dp": icon_dp,
        "dimens_xml": dimens_xml,
        "bools_xml": bools_xml,
        "card_bg": card,
        "fabricate_command": fabricate_command,
        "enable_command": enable_command,
        "overlay_identifier": f"{owner}:HexNextSettingsIcons26",
        "icons": resolved_icons,
    }


def resolve_overlay_tokens(
    spec: Dict[str, Any],
    custom_overrides: Optional[Dict[str, str]] = None,
) -> List[Dict[str, Any]]:
    """
    Resolves concrete One UI 9.0 color tokens for every from-scratch RRO package (`com.hexnext.oneui9.rro.*`)
    that takes highest-priority precedence over Samsung's modern SemWT_* overlays.
    """
    custom_overrides = custom_overrides or {}
    seed = spec.get("accent", "#00E5FF").upper()
    secondary = spec.get("secondary", "#7C4DFF").upper()
    bg = "#000000" if spec.get("pure_black", True) else spec.get("bg", "#080A0F").upper()
    surface = spec.get("surface", "#0C1018").upper()
    card = spec.get("card", "#121724").upper()
    qs_inactive = spec.get("qs_inactive", "#1C2233").upper()
    text_color = "#F3F6FC"

    tweaks = spec.get("extra_tweaks", {}) or {}
    icon_style = tweaks.get("settings_icon_style", "1sett")
    settframe = bool(tweaks.get("settframe", False))
    hidemail = bool(tweaks.get("hidemail", False))
    uidivider = bool(tweaks.get("uidivider", True))

    settings_icon_tint = secondary if icon_style in ("2sett", "alt_2sett") else seed
    settings_icon_frame = "#000000" if settframe else surface
    settings_divider = surface if uidivider else card
    settings_email = card if hidemail else "#94A3B8"

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
        "settings_icon_tint": settings_icon_tint,
        "settings_icon_frame": settings_icon_frame,
        "settings_divider": settings_divider,
        "settings_email": settings_email,
    }

    settings_dashboard = resolve_settings_dashboard_icons(spec, custom_overrides)
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

        if item["id"] == "settings":
            for ic in settings_dashboard["icons"]:
                resolved_resources.append({
                    "name": ic["token_name"],
                    "full_name": f"com.android.settings:color/{ic['token_name']}",
                    "type": "0x1c",
                    "role": "settings_icon_tint",
                    "desc": f"SecSettings Dashboard Icon ({ic['label']}) — {ic['res_name']}",
                    "value": ic["fg_color"],
                    "argb": ic["argb"],
                })
                fab_args.append(f"com.android.settings:color/{ic['token_name']} 0x1c {ic['argb']}")

        fab_cmd = (
            f"cmd overlay fabricate --target {item['target_package']} "
            f"--name {item['fabricated_name']} " + " ".join(fab_args)
        )
        enable_cmd = (
            f"cmd overlay enable --user 0 {owner}:{item['fabricated_name']} && "
            f"cmd overlay set-priority --user 0 {owner}:{item['fabricated_name']} highest"
        )

        # Generate AndroidManifest.xml and colors.xml for from-scratch One UI 9.0 RRO APK (priority=10000 > SemWT_*)
        manifest_xml = (
            '<?xml version="1.0" encoding="utf-8" standalone="no"?>\n'
            f'<!-- Built from scratch by HexNext One UI 9.0 Compiler — overrides {item.get("semwt_target", "SemWT_*")} -->\n'
            '<manifest xmlns:android="http://schemas.android.com/apk/res/android"\n'
            f'    package="{item["system_rro_package"]}"\n'
            '    android:versionCode="909090"\n'
            '    android:versionName="hex_oneui9_semwt_override">\n'
            '    <overlay\n'
            f'        android:targetPackage="{item["target_package"]}"\n'
            '        android:isStatic="false"\n'
            '        android:priority="10000" />\n'
            '    <application\n'
            f'        android:label="{item["label"]} (HexNext One UI 9.0 > SemWT_*)"\n'
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

        extra_meta = {}
        if item["id"] == "settings":
            extra_meta = {
                "dimens_xml": settings_dashboard["dimens_xml"],
                "bools_xml": settings_dashboard["bools_xml"],
                "drawable_count": len(settings_dashboard["icons"]),
                "settings_icons_fabricate_command": settings_dashboard["fabricate_command"],
                "settings_icons_enable_command": settings_dashboard["enable_command"],
                "settings_icons_overlay_identifier": settings_dashboard["overlay_identifier"],
            }

        compiled_packages.append({
            **item,
            **extra_meta,
            "anaconda_archive": item["anaconda_asset"],
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
    Packages the newly built-from-scratch One UI 9.0 RRO overlays (`com.hexnext.oneui9.rro.*`),
    Monet 65-shade table, 26 Niferpitou SecSettings Dashboard Icon VectorDrawables,
    QS toggle drawable XMLs, and UID 0 / UID 1000 installer script that sets priority=highest
    above Samsung's modern SemWT_* overlays into a downloadable ZIP archive.
    """
    compiled = resolve_overlay_tokens(spec, custom_overrides)
    settings_icons = resolve_settings_dashboard_icons(spec, custom_overrides)
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
            "# HexNext — From-Scratch One UI 9.0 RRO Builder (Precedence > SemWT_*)",
            "# Rebuilt from #hex__30 + Niferpitou (terpsichora_) for Android 16",
            "# Privilege Mode: Shizuku (UID 0 / UID 1000) or Direct SU",
            "# High Contrast Theme Overlays: NOT USED (Pure From-Scratch RROs)",
            "# Theme Park Requirement: REMOVED",
            "# ======================================================================",
            "",
            'CALLER_UID=$(id -u)',
            'if [ "$CALLER_UID" != "0" ] && [ "$CALLER_UID" != "1000" ]; then',
            '    echo "[!] Error: UID 0 (Root) or UID 1000 (System) is required (current UID=$CALLER_UID)."',
            '    exit 1',
            'fi',
            'echo "[*] Authenticated as privileged UID $CALLER_UID — building from-scratch One UI 9.0 RROs above SemWT_*..."',
            "",
        ]

        # 1. Add 65 Monet framework shades fabrication command
        monet_args = []
        for res_name, hex_val in monet["flat_shades"].items():
            monet_args.append(f"android:color/{res_name} 0x1c 0xff{hex_val.lstrip('#').lower()}")
        for res_name, hex_val in monet["roles_dark"].items():
            monet_args.append(f"android:color/{res_name} 0x1c 0xff{hex_val.lstrip('#').lower()}")

        script_lines.append("# --- 1. Fabricate 65 Monet Shades + 23 Material 3 Dark Roles above android:SemWT_MonetPalette ---")
        script_lines.append(
            "cmd overlay fabricate --target android --name HexNextMonetShades " + " ".join(monet_args)
        )
        script_lines.append("cmd overlay enable --user 0 com.android.shell:HexNextMonetShades 2>/dev/null || cmd overlay enable --user 0 android:HexNextMonetShades")
        script_lines.append("cmd overlay set-priority --user 0 com.android.shell:HexNextMonetShades highest 2>/dev/null || cmd overlay set-priority --user 0 android:HexNextMonetShades highest")
        script_lines.append("")

        # 2. Add each selected from-scratch One UI 9.0 RRO package
        for pkg in compiled:
            if not pkg["selected"]:
                continue
            folder = f"overlays/{pkg['system_rro_package']}"
            zf.writestr(f"{folder}/AndroidManifest.xml", pkg["manifest_xml"])
            zf.writestr(f"{folder}/res/values/colors.xml", pkg["colors_xml"])
            if pkg["id"] == "systemui":
                zf.writestr(f"{folder}/res/drawable/sec_qs_tile_bg.xml", qs_drawable_xml)
            if pkg["id"] == "settings":
                zf.writestr(f"{folder}/res/values/dimens.xml", settings_icons["dimens_xml"])
                zf.writestr(f"{folder}/res/values/bools.xml", settings_icons["bools_xml"])
                for ic in settings_icons["icons"]:
                    zf.writestr(f"{folder}/res/drawable/{ic['res_name']}.xml", ic["vector_xml"])

            script_lines.append(f"# --- Target: {pkg['target_package']} ({pkg['system_rro_package']} > {pkg.get('semwt_target', 'SemWT_*')}) ---")
            script_lines.append(pkg["fabricate_command"])
            script_lines.append(pkg["enable_command"])
            if pkg["id"] == "settings":
                script_lines.append("# --- Dedicated 26 SecSettings Dashboard Icons Overlay (HexNextSettingsIcons26 > SemWT_Settings) ---")
                script_lines.append(settings_icons["fabricate_command"])
                script_lines.append(settings_icons["enable_command"])
            script_lines.append(f"cmd overlay enable --user 0 {pkg['system_rro_package']} 2>/dev/null || true")
            script_lines.append(f"cmd overlay set-priority --user 0 {pkg['system_rro_package']} highest 2>/dev/null || true")
            script_lines.append("")

        script_lines.append('echo "[✓] All from-scratch One UI 9.0 RRO overlays + 26 Settings Icons fabricated and prioritized above SemWT_*!"')
        zf.writestr("install_oneui9_overlays.sh", "\n".join(script_lines) + "\n")
        zf.writestr(
            "theme_profile_oneui9.json",
            json.dumps(
                {
                    "engine": "HexNext One UI 9.0 From-Scratch RRO Compiler (Precedence > SemWT_*)",
                    "theme_park_required": False,
                    "uses_high_contrast_theme_overlays": False,
                    "semwt_precedence_mode": "priority=10000 (highest > SemWT_*)",
                    "privilege_requirement": "UID 0 (Root) or UID 1000 (System)",
                    "spec": spec,
                    "settings_icons_customization": {
                        "style_id": settings_icons["style_id"],
                        "style_label": settings_icons["style_label"],
                        "active_asset_folder": settings_icons["active_asset_folder"],
                        "settframe": settings_icons["settframe"],
                        "bigsetico": settings_icons["bigsetico"],
                        "hidemail": settings_icons["hidemail"],
                        "uselogo": settings_icons["uselogo"],
                        "uidivider": settings_icons["uidivider"],
                        "icon_size_dp": settings_icons["icon_size_dp"],
                        "overlay_identifier": settings_icons["overlay_identifier"],
                        "icon_count": len(settings_icons["icons"]),
                        "icons": [
                            {
                                "id": ic["id"],
                                "res_name": ic["res_name"],
                                "token_name": ic["token_name"],
                                "fg_color": ic["fg_color"],
                                "badge_color": ic["badge_color"],
                                "frameless": ic["frameless"],
                                "size_dp": ic["size_dp"],
                            }
                            for ic in settings_icons["icons"]
                        ],
                    },
                    "monet_palette": monet,
                    "overlays": [
                        {
                            "id": p["id"],
                            "system_rro_package": p["system_rro_package"],
                            "semwt_overridden": p.get("semwt_target"),
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

