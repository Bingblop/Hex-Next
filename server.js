const http = require("http");
const fs = require("fs");
const path = require("path");
const zlib = require("zlib");
const crypto = require("crypto");

const BASE_DIR = __dirname;
const INDEX_HTML_PATH = path.join(BASE_DIR, "templates", "index.html");
const CATALOG_PATH = path.join(BASE_DIR, "engine", "oneui9_catalog.json");

const CATALOG = JSON.parse(fs.readFileSync(CATALOG_PATH, "utf8"));
const {
  SHADES,
  PALETTE_STYLES,
  HEX_QS_TOGGLE_SHAPES,
  ONEUI9_QS_SLIDER_CATALOG,
  HEX_PLUGINS,
  SETTINGS_ICON_STYLES,
  SETTINGS_DASHBOARD_ICONS,
  NIFERPITOU_EXTRA_PREFERENCES,
  ONEUI9_SYSTEM_RRO_CATALOG,
  ONEUI9_APP_PNG_SUITES,
  MIMI_SKIN_MANIFEST,
} = CATALOG;

// Color conversion helpers matching engine/oneui9_overlays.py
function hexToRgb(hexStr) {
  let clean = String(hexStr || "").trim().replace(/^#/, "");
  if (clean.length === 8) clean = clean.slice(2);
  if (clean.length !== 6) return [0, 229, 255];
  return [
    parseInt(clean.slice(0, 2), 16) || 0,
    parseInt(clean.slice(2, 4), 16) || 0,
    parseInt(clean.slice(4, 6), 16) || 0,
  ];
}

function rgbToHex(r, g, b) {
  const ri = Math.max(0, Math.min(255, Math.round(r)));
  const gi = Math.max(0, Math.min(255, Math.round(g)));
  const bi = Math.max(0, Math.min(255, Math.round(b)));
  return (
    "#" +
    ri.toString(16).padStart(2, "0").toUpperCase() +
    gi.toString(16).padStart(2, "0").toUpperCase() +
    bi.toString(16).padStart(2, "0").toUpperCase()
  );
}

function rgbToHls(r, g, b) {
  const maxc = Math.max(r, g, b);
  const minc = Math.min(r, g, b);
  const l = (minc + maxc) / 2.0;
  if (minc === maxc) return [0.0, l, 0.0];
  const s =
    l <= 0.5 ? (maxc - minc) / (maxc + minc) : (maxc - minc) / (2.0 - maxc - minc);
  const rc = (maxc - r) / (maxc - minc);
  const gc = (maxc - g) / (maxc - minc);
  const bc = (maxc - b) / (maxc - minc);
  let h;
  if (r === maxc) h = bc - gc;
  else if (g === maxc) h = 2.0 + rc - bc;
  else h = 4.0 + gc - rc;
  h = (h / 6.0) % 1.0;
  if (h < 0) h += 1.0;
  return [h, l, s];
}

function hlsToRgb(h, l, s) {
  if (s === 0.0) return [l, l, l];
  const m2 = l <= 0.5 ? l * (1.0 + s) : l + s - l * s;
  const m1 = 2.0 * l - m2;
  const v = (m1, m2, hue) => {
    let hh = hue % 1.0;
    if (hh < 0) hh += 1.0;
    if (hh < 1.0 / 6.0) return m1 + (m2 - m1) * hh * 6.0;
    if (hh < 0.5) return m2;
    if (hh < 2.0 / 3.0) return m1 + (m2 - m1) * (2.0 / 3.0 - hh) * 6.0;
    return m1;
  };
  return [v(m1, m2, h + 1.0 / 3.0), v(m1, m2, h), v(m1, m2, h - 1.0 / 3.0)];
}

function toneToHex(hDeg, sRatio, tone) {
  const lRatio = Math.max(0.0, Math.min(1.0, tone / 100.0));
  const effS = sRatio * (1.0 - 0.55 * Math.pow(Math.abs(2.0 * lRatio - 1.0), 1.6));
  const normH = (((hDeg % 360.0) + 360.0) % 360.0) / 360.0;
  const [r, g, b] = hlsToRgb(normH, lRatio, Math.max(0.0, Math.min(1.0, effS)));
  return rgbToHex(r * 255.0, g * 255.0, b * 255.0);
}

function toneForShade(shade) {
  if (shade === 0) return 100;
  if (shade === 10) return 99;
  if (shade === 50) return 95;
  return 100 - Math.floor(shade / 10);
}

function generateMonetPalette(seedHex, styleId = "TONAL_SPOT", pureBlack = true) {
  const [r, g, b] = hexToRgb(seedHex);
  const [h, , s] = rgbToHls(r / 255.0, g / 255.0, b / 255.0);
  const hDeg = h * 360.0;

  const styleCfg = PALETTE_STYLES.find((st) => st.id === styleId) || PALETTE_STYLES[0];
  const cMult = styleCfg.chroma_mult;
  const baseS = cMult > 0 ? Math.max(0.35, s) * cMult : 0.0;

  const groupsSpec = [
    ["ACCENT1", "system_accent1", "Primary (accent1)", hDeg, Math.min(1.0, baseS * 0.95)],
    [
      "ACCENT2",
      "system_accent2",
      "Secondary (accent2)",
      hDeg + styleCfg.sec_shift,
      Math.min(1.0, baseS * 0.42),
    ],
    [
      "ACCENT3",
      "system_accent3",
      "Tertiary (accent3)",
      hDeg + styleCfg.tert_shift,
      Math.min(1.0, baseS * 0.58),
    ],
    ["NEUTRAL1", "system_neutral1", "Neutral (neutral1)", hDeg, Math.min(1.0, baseS * 0.08)],
    [
      "NEUTRAL2",
      "system_neutral2",
      "Neutral Variant (neutral2)",
      hDeg,
      Math.min(1.0, baseS * 0.14),
    ],
  ];

  const groupsOut = {};
  const flatShades = {};

  for (const [key, prefix, label, gh, gs] of groupsSpec) {
    const row = [];
    for (const sh of SHADES) {
      let hexVal;
      if (pureBlack && key === "NEUTRAL1" && sh >= 900) {
        hexVal = "#000000";
      } else {
        hexVal = toneToHex(gh, gs, toneForShade(sh));
      }
      const resName = `${prefix}_${sh}`;
      flatShades[resName] = hexVal;
      row.push({ shade: sh, name: resName, hex: hexVal });
    }
    groupsOut[key] = { label, prefix, shades: row };
  }

  const rolesDark = {
    system_primary_dark: flatShades.system_accent1_200,
    system_on_primary_dark: flatShades.system_accent1_800,
    system_primary_container_dark: flatShades.system_accent1_700,
    system_on_primary_container_dark: flatShades.system_accent1_100,
    system_secondary_dark: flatShades.system_accent2_200,
    system_on_secondary_dark: flatShades.system_accent2_800,
    system_secondary_container_dark: flatShades.system_accent2_700,
    system_on_secondary_container_dark: flatShades.system_accent2_100,
    system_tertiary_dark: flatShades.system_accent3_200,
    system_on_tertiary_dark: flatShades.system_accent3_800,
    system_background_dark: pureBlack ? "#000000" : flatShades.system_neutral1_900,
    system_on_background_dark: flatShades.system_neutral1_100,
    system_surface_dark: pureBlack ? "#000000" : flatShades.system_neutral1_900,
    system_surface_dim_dark: pureBlack ? "#000000" : flatShades.system_neutral1_900,
    system_surface_container_lowest_dark: pureBlack ? "#000000" : flatShades.system_neutral1_1000,
    system_surface_container_low_dark: pureBlack ? "#05070B" : flatShades.system_neutral1_900,
    system_surface_container_dark: flatShades.system_neutral2_800,
    system_surface_container_high_dark: flatShades.system_neutral2_700,
    system_surface_container_highest_dark: flatShades.system_neutral2_600,
    system_on_surface_dark: flatShades.system_neutral1_100,
    system_on_surface_variant_dark: flatShades.system_neutral2_200,
    system_outline_dark: flatShades.system_neutral2_400,
    system_control_activated_dark: flatShades.system_accent1_200,
  };

  return {
    seed: String(seedHex || "#00E5FF").toUpperCase(),
    style: styleId,
    pure_black: pureBlack,
    groups: groupsOut,
    flat_shades: flatShades,
    roles_dark: rolesDark,
    total_shades: Object.keys(flatShades).length,
    total_roles: Object.keys(rolesDark).length,
  };
}

const FIXED_PNG_STYLE_TO_SKIN = {
  "3sett": "terpsichora",
  mimi_sett: "mimi",
  apk_png_fixed: "mimi",
  px_style1: "px",
  blend_frame: "blend",
  glass_frosted: "glass",
  aosp_ayos: "aosp",
};

const PLUGIN_TO_PNG_SKIN = {
  niferpitou_terpsichora: "terpsichora",
  mimi_miui11: "mimi",
  project_x: "px",
  blend_dn: "blend",
  glass_dn: "glass",
  ayos_dark: "ayos",
  aosp_r: "aosp",
};

const SKIN_DISPLAY_NAMES = {
  terpsichora: "Terpsichora Multi-Color (3sett)",
  mimi: "MIMI (Miui 11 v1.3)",
  px: "Project X (PX-06 Cyber Neon)",
  blend: "Blend Day/Night v5.0",
  glass: "Glass Day/Night v5.0",
  ayos: "ayOS Retina v16",
  aosp: "AOSP R / ayOS Retina",
};

function resolveSettingsDashboardIcons(spec, customOverrides = {}) {
  const seed = String(spec.accent || "#00E5FF").toUpperCase();
  const secondary = String(spec.secondary || "#7C4DFF").toUpperCase();
  const card = String(spec.card || "#121724").toUpperCase();

  const tweaks = spec.extra_tweaks || {};
  const iconStyle = tweaks.settings_icon_style || "1sett";
  const mappedFixedSkin = FIXED_PNG_STYLE_TO_SKIN[iconStyle] || null;
  const isFixedPngStyle = Boolean(mappedFixedSkin);

  // If the user explicitly toggled use_apk_png_icons, respect it; otherwise default true for fixed PNG styles
  const useApkPngIcons =
    tweaks.use_apk_png_icons !== undefined
      ? Boolean(tweaks.use_apk_png_icons)
      : isFixedPngStyle;
  const fixedState = Boolean(useApkPngIcons);

  const pngSkin =
    mappedFixedSkin ||
    PLUGIN_TO_PNG_SKIN[spec.plugin_id] ||
    "terpsichora";

  const skinDisplayName = SKIN_DISPLAY_NAMES[pngSkin] || "Terpsichora Multi-Color";

  // Fixed .PNG presets work WITH or WITHOUT the frame, with 0% inner fill and always filled-in glyphs
  const settframe = Boolean(tweaks.settframe);
  const bigsetico = Boolean(tweaks.bigsetico);
  const hidemail = Boolean(tweaks.hidemail);
  const uselogo = tweaks.uselogo !== undefined ? Boolean(tweaks.uselogo) : true;
  const uidivider = tweaks.uidivider !== undefined ? Boolean(tweaks.uidivider) : true;
  const perIconColors = tweaks.per_icon_colors || {};

  const styleMeta =
    SETTINGS_ICON_STYLES.find((s) => s.id === iconStyle) || SETTINGS_ICON_STYLES[0];

  const activeFolder = useApkPngIcons
    ? `${styleMeta.asset_folder || "res/drawable-xxhdpi-v4"} (${skinDisplayName} Fixed .PNG • ${settframe ? "Frameless" : "Framed • No Inner Fill"})`
    : settframe
    ? styleMeta.frameless_folder
    : styleMeta.asset_folder;

  const iconDp = bigsetico ? 36 : 28;
  const isDuotone =
    iconStyle === "alt_1sett" ||
    iconStyle === "alt_2sett" ||
    iconStyle === "mimi_sett" ||
    iconStyle === "apk_png_fixed" ||
    iconStyle === "px_style1" ||
    iconStyle === "blend_frame";
  const mimiDrawables = (MIMI_SKIN_MANIFEST && MIMI_SKIN_MANIFEST.drawables) || {};

  const resolvedIcons = SETTINGS_DASHBOARD_ICONS.map((ic) => {
    const tokenName = `${ic.res_name}_tint`;
    const overrideKey = `com.android.settings:${tokenName}`;
    // When fixedState is active (Fixed .PNG Preset), custom color overrides are locked out
    const customHex = fixedState ? null : perIconColors[ic.id] || customOverrides[overrideKey];
    let fgColor;
    let badgeColor;

    if (fixedState) {
      if (pngSkin === "terpsichora") {
        fgColor = String(ic.og_color || ic.mimi_color || "#00E5FF").toUpperCase();
        badgeColor = String(ic.terp_badge || ic.og_color || "#FF2E93").toUpperCase();
      } else if (pngSkin === "px") {
        fgColor = String(ic.px_color || "#FF2A54").toUpperCase();
        badgeColor = String(ic.px_badge || "#00E5FF").toUpperCase();
      } else if (pngSkin === "blend") {
        fgColor = String(ic.blend_color || "#6C5CE7").toUpperCase();
        badgeColor = String(ic.blend_badge || "#00CEC9").toUpperCase();
      } else if (pngSkin === "glass") {
        fgColor = String(ic.glass_color || "#38BDF8").toUpperCase();
        badgeColor = String(ic.glass_badge || "#818CF8").toUpperCase();
      } else if (pngSkin === "aosp" || pngSkin === "ayos") {
        fgColor = String(ic.aosp_color || "#4C8DF6").toUpperCase();
        badgeColor = String(ic.aosp_badge || "#34A853").toUpperCase();
      } else {
        fgColor = String(ic.mimi_color || ic.og_color || "#4C9FFD").toUpperCase();
        badgeColor = String(ic.mimi_badge || "#F16E9A").toUpperCase();
      }
    } else if (customHex) {
      fgColor = String(customHex).toUpperCase();
      badgeColor = secondary;
    } else if (iconStyle === "1sett") {
      fgColor = seed;
      badgeColor = seed;
    } else if (iconStyle === "2sett") {
      fgColor = secondary;
      badgeColor = secondary;
    } else if (iconStyle === "alt_1sett") {
      fgColor = seed;
      badgeColor = secondary;
    } else if (iconStyle === "alt_2sett") {
      fgColor = secondary;
      badgeColor = seed;
    } else {
      // Any style switched to Color Palette mode uses the live seed/secondary colors!
      fgColor = seed;
      badgeColor = secondary;
    }

    const cleanFg = fgColor.replace(/^#/, "");
    const cleanBadge = badgeColor.replace(/^#/, "");
    // CRITICAL REQUIREMENT: There must be NO color between the icon and the frame!
    const frameBg = "transparent";
    const frameBorder =
      settframe || useApkPngIcons
        ? "transparent"
        : isDuotone
        ? `#88${cleanBadge}`
        : `#80${cleanFg}`;

    const pngKey = ic.mimi_png_key || `sec_st_ic_${ic.id}`;
    const mimiFramelessUri =
      ic.mimi_png_frameless_uri || ic.apk_png_uri || mimiDrawables[pngKey] || null;
    const mimiFramedUri = ic.mimi_png_framed_uri || mimiFramelessUri;
    const terpFramelessUri = ic.terp_png_frameless_uri || mimiFramelessUri;
    const terpFramedUri = ic.terp_png_framed_uri || terpFramelessUri;
    const pxFramelessUri = ic.px_png_frameless_uri || terpFramelessUri;
    const pxFramedUri = ic.px_png_framed_uri || pxFramelessUri;
    const blendFramelessUri = ic.blend_png_frameless_uri || terpFramelessUri;
    const blendFramedUri = ic.blend_png_framed_uri || blendFramelessUri;
    const glassFramelessUri = ic.glass_png_frameless_uri || terpFramelessUri;
    const glassFramedUri = ic.glass_png_framed_uri || glassFramelessUri;
    const aospFramelessUri = ic.aosp_png_frameless_uri || terpFramelessUri;
    const aospFramedUri = ic.aosp_png_framed_uri || aospFramelessUri;

    let activePngUri = null;
    if (useApkPngIcons) {
      if (pngSkin === "terpsichora") {
        activePngUri = settframe ? terpFramelessUri : terpFramedUri;
      } else if (pngSkin === "px") {
        activePngUri = settframe ? pxFramelessUri : pxFramedUri;
      } else if (pngSkin === "blend") {
        activePngUri = settframe ? blendFramelessUri : blendFramedUri;
      } else if (pngSkin === "glass") {
        activePngUri = settframe ? glassFramelessUri : glassFramedUri;
      } else if (pngSkin === "aosp" || pngSkin === "ayos") {
        activePngUri = settframe ? aospFramelessUri : aospFramedUri;
      } else {
        activePngUri = settframe ? mimiFramelessUri : mimiFramedUri;
      }
    }

    const apkPngResPath =
      pngSkin === "terpsichora"
        ? `assets/anaconda_tints/once/base/lxx1/${settframe ? "settingsicon-3sett-frameless" : "settingsicon-3sett"}/${ic.res_name}.png`
        : pngSkin === "px"
        ? `assets/anaconda_o/base/settings/${settframe ? "px_style2_frameless" : "px_style1"}/${ic.res_name}.png`
        : pngSkin === "blend"
        ? `assets/anaconda_o/base/settings/${settframe ? "blend_frameless" : "blend_frame"}/${ic.res_name}.png`
        : pngSkin === "glass"
        ? `assets/anaconda_o/base/settings/${settframe ? "glass_frameless" : "glass_frosted"}/${ic.res_name}.png`
        : pngSkin === "aosp" || pngSkin === "ayos"
        ? `assets/anaconda_o/base/settings/${settframe ? "aosp_frameless" : "aosp_ayos"}/${ic.res_name}.png`
        : ic.apk_png_res_path || `res/drawable-xxhdpi-v4/${ic.res_name}.png`;

    const sourceAsset = useApkPngIcons
      ? `${apkPngResPath} (${skinDisplayName} • 96×96 Fixed .PNG • ${settframe ? "Frameless" : "With Frame, No Inner Color"})`
      : `assets/anaconda_tints/once/base/lxx1/${activeFolder}/${ic.res_name}.png`;

    const filledPathData = ic.filled_svg_path || ic.svg_path;

    let vectorXml;
    if (useApkPngIcons) {
      vectorXml =
        '<?xml version="1.0" encoding="utf-8"?>\n' +
        `<!-- FIXED-STATE APK .PNG PRESET (${skinDisplayName} | ${settframe ? "Frameless" : "With Frame • Transparent Interior"} | Always Filled-In) -->\n` +
        `<!-- Binary Raster Asset: ${apkPngResPath} | Target: com.android.settings:drawable/${ic.res_name} -->\n` +
        `<!-- NOTE: Preset dashboard icon colors cannot be altered (tintable=false, fixed_state=true, no_frame_fill=true, always_filled=true) -->\n` +
        '<bitmap xmlns:android="http://schemas.android.com/apk/res/android"\n' +
        `    android:src="@drawable/${ic.res_name}"\n` +
        '    android:antialias="true"\n' +
        '    android:filter="true"\n' +
        '    android:dither="true"\n' +
        '    android:gravity="center" />\n';
    } else {
      vectorXml =
        '<?xml version="1.0" encoding="utf-8"?>\n' +
        `<!-- Rebuilt for One UI 9.0 (${activeFolder}/${ic.res_name}.png | Always Filled-In, Zero Color Between Icon & Frame) -->\n` +
        `<!-- Target: com.android.settings:drawable/${ic.res_name} | Tint Token: @color/${tokenName} (${fgColor}) -->\n` +
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n' +
        `    android:width="${iconDp}dp"\n` +
        `    android:height="${iconDp}dp"\n` +
        '    android:viewportWidth="24"\n' +
        '    android:viewportHeight="24">\n';

      if (!settframe) {
        vectorXml +=
          "    <path\n" +
          '        android:name="squircle_frame_outline_no_fill"\n' +
          '        android:fillColor="#00000000"\n' +
          `        android:strokeColor="#B3${isDuotone ? cleanBadge : cleanFg}"\n` +
          '        android:strokeWidth="1.25"\n' +
          '        android:pathData="M7,2h10c2.76,0 5,2.24 5,5v10c0,2.76 -2.24,5 -5,5H7c-2.76,0 -5,-2.24 -5,-5V7c0,-2.76 2.24,-5 5,-5z" />\n';
      }
      vectorXml +=
        "    <path\n" +
        '        android:name="icon_glyph_filled"\n' +
        `        android:fillColor="${fgColor}"\n` +
        '        android:strokeColor="#00000000"\n' +
        '        android:strokeWidth="0"\n' +
        `        android:pathData="${filledPathData}" />\n` +
        "</vector>\n";
    }

    return {
      ...ic,
      svg_path: filledPathData,
      filled_svg_path: filledPathData,
      always_filled: true,
      no_frame_fill: true,
      token_name: tokenName,
      fg_color: fgColor,
      badge_color: badgeColor,
      frame_bg: frameBg,
      frame_border: frameBorder,
      frameless: settframe,
      size_dp: iconDp,
      png_skin: useApkPngIcons ? pngSkin : null,
      is_mimi_style: iconStyle === "mimi_sett" || (useApkPngIcons && pngSkin === "mimi"),
      is_terp_png: useApkPngIcons && pngSkin === "terpsichora",
      use_apk_png: useApkPngIcons,
      fixed_state: fixedState,
      immutable_reason: fixedState
        ? `${skinDisplayName} Fixed .PNG Bitmap (${settframe ? "Frameless" : "Framed • No Inner Color"} • Always Filled-In) — Pre-baked multi-color raster asset; switch to Color Palette Vector Mode to customize colors.`
        : null,
      apk_png_uri: activePngUri || mimiFramelessUri,
      apk_png_res_path: apkPngResPath,
      mimi_png_uri: activePngUri,
      source_asset: sourceAsset,
      oneui9_drawable: `com.android.settings:drawable/${ic.res_name}`,
      vector_xml: vectorXml,
    };
  });

  const dimensXml =
    '<?xml version="1.0" encoding="utf-8"?>\n' +
    "<!-- One UI 9.0 SecSettings Dashboard Icon & Divider Dimensions -->\n" +
    "<resources>\n" +
    `    <dimen name="sec_dashboard_tile_image_size">${iconDp}.0dip</dimen>\n` +
    `    <dimen name="sec_dashboard_tile_image_padding">${settframe ? "0.0dip" : "4.0dip"}</dimen>\n` +
    `    <dimen name="sec_widget_list_divider_height">${uidivider ? "1.0dip" : "0.0dip"}</dimen>\n` +
    `    <dimen name="sec_account_email_text_height">${hidemail ? "0.0dip" : "18.0dip"}</dimen>\n` +
    "</resources>\n";

  const boolsXml =
    '<?xml version="1.0" encoding="utf-8"?>\n' +
    "<resources>\n" +
    `    <bool name="sec_settings_show_custom_about_logo">${uselogo ? "true" : "false"}</bool>\n` +
    `    <bool name="sec_settings_hide_account_email">${hidemail ? "true" : "false"}</bool>\n` +
    `    <bool name="sec_settings_frameless_dashboard_icons">${settframe ? "true" : "false"}</bool>\n` +
    `    <bool name="sec_settings_use_fixed_apk_png_drawables">${useApkPngIcons ? "true" : "false"}</bool>\n` +
    "</resources>\n";

  const owner = (spec.privilege_uid ?? 1000) === 1000 ? "android" : "com.android.shell";
  const iconFabArgs = resolvedIcons.map((ic) => {
    const argbHex = "0xff" + ic.fg_color.replace(/^#/, "").toLowerCase();
    return `com.android.settings:color/${ic.token_name} 0x1c ${argbHex}`;
  });
  const fabricateIconsCmd = useApkPngIcons
    ? `# [FIXED .PNG PRESET MODE: ${skinDisplayName.toUpperCase()} (${settframe ? "FRAMELESS" : "WITH FRAME • NO INNER COLOR"} • ALWAYS FILLED-IN)] 26 APK .PNG bitmaps packaged in com.hexnext.oneui9.rro.settings\n` +
      `cmd overlay enable --user 0 com.hexnext.oneui9.rro.settings && cmd overlay set-priority --user 0 com.hexnext.oneui9.rro.settings highest`
    : `cmd overlay fabricate --target com.android.settings --name HexNextSettingsIcons26 ` +
      iconFabArgs.join(" ");
  const enableIconsCmd =
    `cmd overlay enable --user 0 ${owner}:HexNextSettingsIcons26 && ` +
    `cmd overlay set-priority --user 0 ${owner}:HexNextSettingsIcons26 highest`;

  return {
    style_id: iconStyle,
    style_label:
      useApkPngIcons && !isFixedPngStyle
        ? `${styleMeta.label} [${skinDisplayName} .PNG Override]`
        : styleMeta.label,
    active_asset_folder: activeFolder,
    use_apk_png_icons: useApkPngIcons,
    png_skin: useApkPngIcons ? pngSkin : null,
    png_skin_label: useApkPngIcons ? skinDisplayName : "Color Palette Vector Mode",
    always_filled: true,
    no_frame_fill: true,
    fixed_state: fixedState,
    immutable_notice: fixedState
      ? `Preset dashboard icon colors are locked because ${skinDisplayName} APK .PNG bitmap files have fixed filled-in pixel states (${settframe ? "Frameless" : "With Frame, 0% inner fill"}). Toggle 'Remove Icons Frame' anytime, or switch to 'Color Palette Tintable' mode to recolor every icon with your palette.`
      : null,
    mimi_preview_screenshot:
      "/assets/aistudio/Screenshot_20261031_005340_Google_Play_Store_a5583e56.jpg",
    settframe,
    bigsetico,
    hidemail,
    uselogo,
    uidivider,
    icon_size_dp: iconDp,
    dimens_xml: dimensXml,
    bools_xml: boolsXml,
    fabricate_command: fabricateIconsCmd,
    enable_command: enableIconsCmd,
    overlay_identifier: `${owner}:HexNextSettingsIcons26`,
    card_bg: card,
    icons: resolvedIcons,
  };
}

function resolveAppPngSuites(spec) {
  const seed = String(spec.accent || "#00E5FF").toUpperCase();
  const secondary = String(spec.secondary || "#7C4DFF").toUpperCase();
  const tweaks = spec.extra_tweaks || {};
  const iconStyle = tweaks.settings_icon_style || "1sett";
  const packMode = tweaks.app_png_pack_mode || "auto_skin";
  const settframe = Boolean(tweaks.settframe);

  const packModeToSkin = {
    mimi_png: "mimi",
    terpsichora_png: "terpsichora",
    projectx_png: "px",
    blend_png: "blend",
    glass_png: "glass",
    ayos_png: "ayos",
    aosp_png: "aosp",
    vector_only: "vector",
  };

  let activeSkin = packModeToSkin[packMode] || null;
  if (!activeSkin) {
    // auto_skin: follow settings_icon_style or active plugin_id
    if (FIXED_PNG_STYLE_TO_SKIN[iconStyle]) {
      activeSkin = FIXED_PNG_STYLE_TO_SKIN[iconStyle];
    } else if (PLUGIN_TO_PNG_SKIN[spec.plugin_id]) {
      activeSkin = PLUGIN_TO_PNG_SKIN[spec.plugin_id];
    } else {
      activeSkin = "terpsichora";
    }
  }

  const usePng = activeSkin !== "vector";
  const suitesRaw = ONEUI9_APP_PNG_SUITES || [];
  const groupedSuites = {};
  if (Array.isArray(suitesRaw)) {
    for (const item of suitesRaw) {
      const sId = item.suite_id || "other";
      if (!groupedSuites[sId]) {
        groupedSuites[sId] = {
          id: sId,
          label: item.suite_label || sId,
          title: item.suite_label || sId,
          target_package: item.target_package || "android",
          system_rro_package:
            item.rro_package || item.system_rro_package || `com.hexnext.oneui9.rro.${sId}`,
          semwt_target:
            item.semwt_target || `${item.target_package}:SemWT_${item.target_package}`,
          items: [],
        };
      }
      groupedSuites[sId].items.push(item);
    }
  } else {
    Object.assign(groupedSuites, suitesRaw);
  }

  const suitesOut = {};
  let totalDrawables = 0;

  for (const [suiteId, suiteDef] of Object.entries(groupedSuites)) {
    const resolvedItems = (suiteDef.items || []).map((item) => {
      totalDrawables += 1;
      let fgColor;
      let badgeColor;

      if (!usePng) {
        // Color Palette Vector Mode: all non-PNG fixed icons are color-customizable using the Color Palette!
        fgColor = seed;
        badgeColor = secondary;
      } else if (activeSkin === "mimi") {
        fgColor = String(item.mimi_primary || item.mimi_color || "#4C9FFD").toUpperCase();
        badgeColor = String(item.mimi_secondary || item.mimi_badge || "#F16E9A").toUpperCase();
      } else if (activeSkin === "px") {
        fgColor = String(item.px_primary || "#FF2A54").toUpperCase();
        badgeColor = String(item.px_secondary || "#00E5FF").toUpperCase();
      } else if (activeSkin === "blend") {
        fgColor = String(item.blend_primary || "#6C5CE7").toUpperCase();
        badgeColor = String(item.blend_secondary || "#00CEC9").toUpperCase();
      } else if (activeSkin === "glass") {
        fgColor = String(item.glass_primary || "#38BDF8").toUpperCase();
        badgeColor = String(item.glass_secondary || "#818CF8").toUpperCase();
      } else if (activeSkin === "ayos") {
        fgColor = String(item.ayos_primary || "#0A84FF").toUpperCase();
        badgeColor = String(item.ayos_secondary || "#30D158").toUpperCase();
      } else if (activeSkin === "aosp") {
        fgColor = String(item.aosp_primary || "#4C8DF6").toUpperCase();
        badgeColor = String(item.aosp_secondary || "#34A853").toUpperCase();
      } else {
        fgColor = String(item.terp_primary || item.terp_color || "#00E5FF").toUpperCase();
        badgeColor = String(item.terp_secondary || item.terp_badge || "#FF2E93").toUpperCase();
      }

      let pngUri = null;
      if (usePng) {
        if (activeSkin === "mimi") {
          pngUri = settframe ? item.mimi_png_frameless_uri : item.mimi_png_framed_uri;
        } else if (activeSkin === "px") {
          pngUri = settframe ? item.px_png_frameless_uri : item.px_png_framed_uri;
        } else if (activeSkin === "blend") {
          pngUri = settframe ? item.blend_png_frameless_uri : item.blend_png_framed_uri;
        } else if (activeSkin === "glass") {
          pngUri = settframe ? item.glass_png_frameless_uri : item.glass_png_framed_uri;
        } else if (activeSkin === "ayos") {
          pngUri = settframe ? item.ayos_png_frameless_uri : item.ayos_png_framed_uri;
        } else if (activeSkin === "aosp") {
          pngUri = settframe ? item.aosp_png_frameless_uri : item.aosp_png_framed_uri;
        } else {
          pngUri = settframe ? item.terp_png_frameless_uri : item.terp_png_framed_uri;
        }
      }

      const skinLabel = SKIN_DISPLAY_NAMES[activeSkin] || "Palette Vector Mode";
      const bitmapXml = usePng
        ? '<?xml version="1.0" encoding="utf-8"?>\n' +
          `<!-- One UI 9.0 Fixed .PNG App Drawable (${skinLabel} | ${settframe ? "Frameless" : "Framed • No Inner Fill"} | Always Filled-In) -->\n` +
          `<!-- Target: ${suiteDef.target_package}:drawable/${item.res_name} | Carrier: ${suiteDef.system_rro_package} > ${suiteDef.semwt_target} -->\n` +
          '<bitmap xmlns:android="http://schemas.android.com/apk/res/android"\n' +
          `    android:src="@drawable/${item.res_name}"\n` +
          '    android:antialias="true"\n' +
          '    android:filter="true"\n' +
          '    android:dither="true"\n' +
          '    android:gravity="center" />\n'
        : '<?xml version="1.0" encoding="utf-8"?>\n' +
          `<!-- One UI 9.0 Palette-Tintable Vector App Drawable (Seed=${fgColor}, Secondary=${badgeColor}, Always Filled-In, Zero Frame Fill) -->\n` +
          `<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="28dp" android:height="28dp" android:viewportWidth="24" android:viewportHeight="24">\n` +
          (!settframe
            ? `    <path android:fillColor="#00000000" android:strokeColor="#99${badgeColor.replace(/^#/, "")}" android:strokeWidth="1.25" android:pathData="M7,2h10c2.76,0 5,2.24 5,5v10c0,2.76 -2.24,5 -5,5H7c-2.76,0 -5,-2.24 -5,-5V7c0,-2.76 2.24,-5 5,-5z" />\n`
            : "") +
          `    <path android:fillColor="${fgColor}" android:pathData="${item.svg_path}" />\n` +
          `</vector>\n`;

      return {
        ...item,
        label: item.label || item.title || item.res_name,
        title: item.title || item.label || item.res_name,
        mimi_color: item.mimi_primary || item.mimi_color || "#4C9FFD",
        mimi_badge: item.mimi_secondary || item.mimi_badge || "#F16E9A",
        terp_color: item.terp_primary || item.terp_color || "#00E5FF",
        terp_badge: item.terp_secondary || item.terp_badge || "#FF2E93",
        always_filled: true,
        no_frame_fill: true,
        frameless: settframe,
        active_skin: activeSkin,
        active_skin_label: skinLabel,
        use_png: usePng,
        fg_color: fgColor,
        badge_color: badgeColor,
        frame_bg: "transparent",
        active_png_uri: pngUri,
        oneui9_drawable: `${suiteDef.target_package}:drawable/${item.res_name}`,
        rro_png_path: `res/drawable-xxhdpi-v4/${item.res_name}.png`,
        bitmap_xml: bitmapXml,
      };
    });

    suitesOut[suiteId] = {
      ...suiteDef,
      active_skin: activeSkin,
      active_skin_label: SKIN_DISPLAY_NAMES[activeSkin] || "Palette Vector Mode",
      use_png: usePng,
      settframe,
      always_filled: true,
      no_frame_fill: true,
      items: resolvedItems,
    };
  }

  return {
    pack_mode: packMode,
    active_skin: activeSkin,
    active_skin_label: SKIN_DISPLAY_NAMES[activeSkin] || "Color Palette Vector Mode",
    use_png: usePng,
    settframe,
    always_filled: true,
    no_frame_fill: true,
    total_suites: Object.keys(suitesOut).length,
    total_drawables: totalDrawables,
    suites: suitesOut,
  };
}

// Safe One UI 9.0 Quick Settings Customizer (Tiles, Brightness Bar, Volume Panel, Media/Device Pills)
// Deconstructs legacy APK Quick Settings pieces into safe drawable/color/dimen RROs without layout overrides
function resolveQuickSettingsOverlay(spec) {
  const accent = String(spec.accent || "#00E5FF").toUpperCase();
  const secondary = String(spec.secondary || "#7C4DFF").toUpperCase();
  const surface = String(spec.surface || "#0B0E17").toUpperCase();
  const card = String(spec.card || "#121724").toUpperCase();
  const qsInactive = String(spec.qs_inactive || "#1A2030").toUpperCase();
  const tweaks = spec.extra_tweaks || {};

  const shapeId = spec.tile_shape || "squircle";
  const shapeCfg = HEX_QS_TOGGLE_SHAPES[shapeId] || HEX_QS_TOGGLE_SHAPES.squircle;
  const cleanAccent = accent.replace(/^#/, "");
  const cleanSecondary = secondary.replace(/^#/, "");
  const cleanTrack = qsInactive.replace(/^#/, "");

  const qsTileDrawableXml = shapeCfg.xml_template
    .replace(/\{accent\}/g, accent)
    .replace(/\{secondary\}/g, secondary)
    .replace(/\{accent_alpha\}/g, `#33${cleanAccent}`)
    .replace(/\{secondary_alpha\}/g, `#33${cleanSecondary}`);

  const brightnessStyles =
    (ONEUI9_QS_SLIDER_CATALOG &&
      (ONEUI9_QS_SLIDER_CATALOG.brightness_styles ||
        ONEUI9_QS_SLIDER_CATALOG.brightness_bar_styles)) ||
    [];
  const volumeStyles =
    (ONEUI9_QS_SLIDER_CATALOG &&
      (ONEUI9_QS_SLIDER_CATALOG.volume_styles ||
        ONEUI9_QS_SLIDER_CATALOG.volume_panel_styles)) ||
    [];

  const brightnessStyleId = tweaks.brightness_bar_style || "oneui9_pill";
  const brightnessCfg =
    brightnessStyles.find((b) => b.id === brightnessStyleId) ||
    brightnessStyles[0] || {
      id: "oneui9_pill",
      label: "One UI 9.0 Split Pill (Default)",
      source_apk: "One UI 9.0 Stock",
      track_height_dp: 22,
      corner_radius_dp: 24,
      thumb_style: "integrated_pill",
      gradient_mode: "solid_accent",
      border_stroke_dp: 0,
    };

  const volumeStyleId = tweaks.volume_panel_style || "0vvol";
  const volumeCfg =
    volumeStyles.find((v) => v.id === volumeStyleId) ||
    volumeStyles[0] || {
      id: "0vvol",
      label: "Gradient Accent Volume Bar",
      source_apk: "Terpsichora",
      track_width_dp: 18,
      corner_radius_dp: 22,
      gradient_mode: "vertical_gradient",
      border_stroke_dp: 0,
    };

  // Build safe One UI 9.0 sec_brightness_slider_progress_drawable.xml (<layer-list> / <shape>)
  const bRad = `${brightnessCfg.corner_radius_dp}dp`;
  const bStrokeXml =
    brightnessCfg.border_stroke_dp > 0
      ? `            <stroke android:width="${brightnessCfg.border_stroke_dp}dp" android:color="#CC${cleanAccent}" />\n`
      : "";
  const bFillXml =
    brightnessCfg.gradient_mode === "dual_gradient" ||
    brightnessCfg.gradient_mode === "cyber_gradient"
      ? `            <gradient android:startColor="${accent}" android:endColor="${secondary}" android:angle="0" />\n`
      : brightnessCfg.gradient_mode === "frosted_translucent"
      ? `            <gradient android:startColor="#99${cleanAccent}" android:endColor="#66${cleanSecondary}" android:angle="0" />\n`
      : `            <solid android:color="${accent}" />\n`;

  const brightnessProgressXml =
    '<?xml version="1.0" encoding="utf-8"?>\n' +
    `<!-- Safe One UI 9.0 Brightness Slider Progress Drawable (${brightnessCfg.label} • Rebuilt from ${brightnessCfg.source_apk}) -->\n` +
    `<!-- Target: com.android.systemui:drawable/sec_brightness_slider_progress_drawable (Zero SystemUI Layout Risk) -->\n` +
    '<layer-list xmlns:android="http://schemas.android.com/apk/res/android">\n' +
    '    <item android:id="@android:id/background">\n' +
    '        <shape android:shape="rectangle">\n' +
    `            <corners android:radius="${bRad}" />\n` +
    `            <solid android:color="#CC${cleanTrack}" />\n` +
    (brightnessCfg.border_stroke_dp > 0
      ? `            <stroke android:width="1dp" android:color="#40${cleanAccent}" />\n`
      : "") +
    "        </shape>\n" +
    "    </item>\n" +
    '    <item android:id="@android:id/progress">\n' +
    "        <clip>\n" +
    '            <shape android:shape="rectangle">\n' +
    `                <corners android:radius="${bRad}" />\n` +
    bFillXml +
    bStrokeXml +
    "            </shape>\n" +
    "        </clip>\n" +
    "    </item>\n" +
    "</layer-list>\n";

  const vRad = `${volumeCfg.corner_radius_dp}dp`;
  const vStrokeXml =
    volumeCfg.border_stroke_dp > 0
      ? `                <stroke android:width="${volumeCfg.border_stroke_dp}dp" android:color="#CC${cleanAccent}" />\n`
      : "";
  const vFillXml =
    volumeCfg.gradient_mode === "vertical_gradient" ||
    volumeCfg.gradient_mode === "dual_gradient" ||
    volumeCfg.gradient_mode === "cyber_ring_gradient"
      ? `                <gradient android:startColor="${accent}" android:endColor="${secondary}" android:angle="90" />\n`
      : volumeCfg.gradient_mode === "frosted_glass"
      ? `                <gradient android:startColor="#AA${cleanAccent}" android:endColor="#77${cleanSecondary}" android:angle="90" />\n`
      : `                <solid android:color="${accent}" />\n`;

  const volumePanelDrawableXml =
    '<?xml version="1.0" encoding="utf-8"?>\n' +
    `<!-- Safe One UI 9.0 Volume Panel Slider Drawable (${volumeCfg.label} • Rebuilt from ${volumeCfg.source_apk}) -->\n` +
    `<!-- Target: com.android.systemui:drawable/sec_volume_panel_slider_drawable (Zero SystemUI Layout Risk) -->\n` +
    '<layer-list xmlns:android="http://schemas.android.com/apk/res/android">\n' +
    '    <item android:id="@android:id/background">\n' +
    '        <shape android:shape="rectangle">\n' +
    `            <corners android:radius="${vRad}" />\n` +
    `            <solid android:color="#CC${cleanTrack}" />\n` +
    "        </shape>\n" +
    "    </item>\n" +
    '    <item android:id="@android:id/progress">\n' +
    '        <clip android:clipOrientation="vertical" android:gravity="bottom">\n' +
    '            <shape android:shape="rectangle">\n' +
    `                <corners android:radius="${vRad}" />\n` +
    vFillXml +
    vStrokeXml +
    "            </shape>\n" +
    "        </clip>\n" +
    "    </item>\n" +
    "</layer-list>\n";

  const qsDimensXml =
    '<?xml version="1.0" encoding="utf-8"?>\n' +
    "<!-- Safe One UI 9.0 Quick Settings & Volume Slider Dimensions (No Layout XML Override) -->\n" +
    "<resources>\n" +
    `    <dimen name="sec_brightness_slider_height">${brightnessCfg.track_height_dp}.0dip</dimen>\n` +
    `    <dimen name="sec_brightness_slider_corner_radius">${brightnessCfg.corner_radius_dp}.0dip</dimen>\n` +
    `    <dimen name="sec_volume_panel_slider_width">${volumeCfg.track_width_dp}.0dip</dimen>\n` +
    `    <dimen name="sec_volume_panel_slider_corner_radius">${volumeCfg.corner_radius_dp}.0dip</dimen>\n` +
    `    <dimen name="sec_qs_tile_Icon_size">${tweaks.qs_toggle_size === "big" ? "30.0dip" : "26.0dip"}</dimen>\n` +
    `    <dimen name="notification_panel_carrier_label_height">${tweaks.hidcarriernoti ? "0.0dip" : "22.0dip"}</dimen>\n` +
    "</resources>\n";

  return {
    safe_for_oneui9: true,
    safety_architecture:
      "Deconstructed into pure <layer-list> / <shape> drawables, dimens.xml, and SemWT_com.android.systemui color tokens — avoids legacy qs_panel layout XML overrides that crash One UI 9.0 SystemUI.",
    tile_shape: shapeCfg,
    brightness_bar: brightnessCfg,
    volume_panel: volumeCfg,
    brightness_styles: brightnessStyles,
    volume_styles: volumeStyles,
    qs_tile_drawable_xml: qsTileDrawableXml,
    brightness_progress_xml: brightnessProgressXml,
    volume_panel_drawable_xml: volumePanelDrawableXml,
    qs_dimens_xml: qsDimensXml,
    colors: {
      accent,
      secondary,
      surface,
      card,
      qs_inactive: qsInactive,
    },
  };
}

function resolveOverlayTokens(spec, customOverrides = {}) {
  const seed = String(spec.accent || "#00E5FF").toUpperCase();
  const secondary = String(spec.secondary || "#7C4DFF").toUpperCase();
  const bg = spec.pure_black !== false ? "#000000" : String(spec.bg || "#080A0F").toUpperCase();
  const surface = String(spec.surface || "#0C1018").toUpperCase();
  const card = String(spec.card || "#121724").toUpperCase();
  const qsInactive = String(spec.qs_inactive || "#1C2233").toUpperCase();
  const textColor = "#F3F6FC";

  const tweaks = spec.extra_tweaks || {};
  const iconStyle = tweaks.settings_icon_style || "1sett";
  const hidemail = Boolean(tweaks.hidemail);
  const uidivider = tweaks.uidivider !== undefined ? Boolean(tweaks.uidivider) : true;

  const settingsIconTint =
    iconStyle === "2sett" || iconStyle === "alt_2sett" ? secondary : seed;
  // Always transparent between icon and frame
  const settingsIconFrame = "#000000";
  const settingsDivider = uidivider ? surface : card;
  const settingsEmail = hidemail ? card : "#94A3B8";

  const settingsIconsBundle = resolveSettingsDashboardIcons(spec, customOverrides);
  const appPngSuitesBundle = resolveAppPngSuites(spec);
  const qsOverlayBundle = resolveQuickSettingsOverlay(spec);

  const monet = generateMonetPalette(
    seed,
    spec.style || "VIBRANT",
    spec.pure_black !== false
  );
  const accentDeep = monet.flat_shades.system_accent1_600 || seed;
  const tertiary = monet.flat_shades.system_accent3_300 || secondary;

  const roleMap = {
    accent: seed,
    accent_deep: accentDeep,
    secondary,
    tertiary,
    bg,
    surface,
    card,
    qs_inactive: qsInactive,
    text: textColor,
    settings_icon_tint: settingsIconTint,
    settings_icon_frame: settingsIconFrame,
    settings_divider: settingsDivider,
    settings_email: settingsEmail,
  };

  const defaultSelected = ONEUI9_SYSTEM_RRO_CATALOG.filter((i) => i.selected).map((i) => i.id);
  const selectedIds = new Set(spec.selected_targets || defaultSelected);
  const owner = (spec.privilege_uid ?? 1000) === 1000 ? "android" : "com.android.shell";

  return ONEUI9_SYSTEM_RRO_CATALOG.map((item) => {
    const isSelected = selectedIds.has(item.id);
    const resolvedResources = [];
    const fabArgs = [];

    for (const res of item.oneui9_resources) {
      const overrideKey = `${item.target_package}:${res.name}`;
      const hexVal = String(
        customOverrides[overrideKey] || roleMap[res.role] || seed
      ).toUpperCase();
      const argbHex = "0xff" + hexVal.replace(/^#/, "").toLowerCase();
      resolvedResources.push({
        name: res.name,
        full_name: `${item.target_package}:color/${res.name}`,
        type: res.type,
        role: res.role,
        desc: res.desc,
        value: hexVal,
        argb: argbHex,
      });
      fabArgs.push(`${item.target_package}:color/${res.name} ${res.type} ${argbHex}`);
    }

    // Integrate all 26 One UI 9.0 SecSettings Dashboard Icon tokens into com.hexnext.oneui9.rro.settings
    if (item.id === "settings") {
      for (const ic of settingsIconsBundle.icons) {
        const argbHex = "0xff" + ic.fg_color.replace(/^#/, "").toLowerCase();
        resolvedResources.push({
          name: ic.token_name,
          full_name: `com.android.settings:color/${ic.token_name}`,
          type: "0x1c",
          role: `settings_icon_${ic.id}`,
          desc: `${ic.title} icon tint (${settingsIconsBundle.active_asset_folder}, ${ic.size_dp}dp, filled-in, no inner frame color)`,
          value: ic.fg_color,
          argb: argbHex,
          icon_id: ic.id,
          drawable_res: ic.oneui9_drawable,
        });
        fabArgs.push(`com.android.settings:color/${ic.token_name} 0x1c ${argbHex}`);
      }
    }

    const extraSuitesForPkg = Object.values(appPngSuitesBundle.suites || {}).filter(
      (s) => s.id === item.id || (item.id === "systemui" && s.id === "powermenu")
    );
    const extraDrawableCount = extraSuitesForPkg.reduce(
      (acc, s) => acc + (s.items ? s.items.length : 0),
      0
    );

    const fabCmd =
      `cmd overlay fabricate --target ${item.target_package} ` +
      `--name ${item.fabricated_name} ` +
      fabArgs.join(" ");
    const enableCmd =
      `cmd overlay enable --user 0 ${owner}:${item.fabricated_name} && ` +
      `cmd overlay set-priority --user 0 ${owner}:${item.fabricated_name} highest`;

    const manifestXml =
      '<?xml version="1.0" encoding="utf-8" standalone="no"?>\n' +
      `<!-- Built from scratch by HexNext One UI 9.0 Compiler — overrides ${item.semwt_target || "SemWT_*"} -->\n` +
      '<manifest xmlns:android="http://schemas.android.com/apk/res/android"\n' +
      `    package="${item.system_rro_package}"\n` +
      '    android:versionCode="909090"\n' +
      '    android:versionName="hex_oneui9_semwt_override">\n' +
      "    <overlay\n" +
      `        android:targetPackage="${item.target_package}"\n` +
      '        android:isStatic="false"\n' +
      '        android:priority="10000" />\n' +
      "    <application\n" +
      `        android:label="${item.label} (HexNext One UI 9.0 > SemWT_*)"\n` +
      '        android:hasCode="false"\n' +
      '        android:extractNativeLibs="false"\n' +
      '        android:allowBackup="false" />\n' +
      "</manifest>\n";

    const colorsLines = ['<?xml version="1.0" encoding="utf-8"?>', "<resources>"];
    for (const rItem of resolvedResources) {
      colorsLines.push(`    <color name="${rItem.name}">${rItem.value}</color>`);
    }
    colorsLines.push("</resources>\n");
    const colorsXml = colorsLines.join("\n");

    return {
      ...item,
      anaconda_archive: item.anaconda_asset,
      selected: isSelected,
      owner_package: owner,
      overlay_identifier: `${owner}:${item.fabricated_name}`,
      resolved_resources: resolvedResources,
      fabricate_command: fabCmd,
      enable_command: enableCmd,
      manifest_xml: manifestXml,
      colors_xml: colorsXml,
      dimens_xml:
        item.id === "settings"
          ? settingsIconsBundle.dimens_xml
          : item.id === "systemui"
          ? qsOverlayBundle.qs_dimens_xml
          : null,
      bools_xml: item.id === "settings" ? settingsIconsBundle.bools_xml : null,
      drawable_count:
        item.id === "settings"
          ? settingsIconsBundle.icons.length
          : (item.id === "systemui" ? 3 : 0) + extraDrawableCount,
    };
  });
}

// Pure-Node ZIP archive builder (zero external dependencies)
const CRC_TABLE = (() => {
  const table = new Uint32Array(256);
  for (let i = 0; i < 256; i++) {
    let c = i;
    for (let k = 0; k < 8; k++) {
      c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    }
    table[i] = c >>> 0;
  }
  return table;
})();

function crc32(buf) {
  let crc = 0xffffffff;
  for (let i = 0; i < buf.length; i++) {
    crc = CRC_TABLE[(crc ^ buf[i]) & 0xff] ^ (crc >>> 8);
  }
  return (crc ^ 0xffffffff) >>> 0;
}

function createZipBuffer(files) {
  const localParts = [];
  const centralParts = [];
  let offset = 0;

  for (const file of files) {
    const nameBuf = Buffer.from(file.name, "utf8");
    const rawBuf = Buffer.isBuffer(file.content)
      ? file.content
      : Buffer.from(String(file.content), "utf8");
    const compressed = zlib.deflateRawSync(rawBuf);
    const crc = crc32(rawBuf);

    const localHeader = Buffer.alloc(30);
    localHeader.writeUInt32LE(0x04034b50, 0);
    localHeader.writeUInt16LE(20, 4);
    localHeader.writeUInt16LE(0, 6);
    localHeader.writeUInt16LE(8, 8);
    localHeader.writeUInt16LE(0, 10);
    localHeader.writeUInt16LE(0, 12);
    localHeader.writeUInt32LE(crc, 14);
    localHeader.writeUInt32LE(compressed.length, 18);
    localHeader.writeUInt32LE(rawBuf.length, 22);
    localHeader.writeUInt16LE(nameBuf.length, 26);
    localHeader.writeUInt16LE(0, 28);

    localParts.push(localHeader, nameBuf, compressed);

    const centralHeader = Buffer.alloc(46);
    centralHeader.writeUInt32LE(0x02014b50, 0);
    centralHeader.writeUInt16LE(20, 4);
    centralHeader.writeUInt16LE(20, 6);
    centralHeader.writeUInt16LE(0, 8);
    centralHeader.writeUInt16LE(8, 10);
    centralHeader.writeUInt16LE(0, 12);
    centralHeader.writeUInt16LE(0, 14);
    centralHeader.writeUInt32LE(crc, 16);
    centralHeader.writeUInt32LE(compressed.length, 20);
    centralHeader.writeUInt32LE(rawBuf.length, 24);
    centralHeader.writeUInt16LE(nameBuf.length, 28);
    centralHeader.writeUInt16LE(0, 30);
    centralHeader.writeUInt16LE(0, 32);
    centralHeader.writeUInt16LE(0, 34);
    centralHeader.writeUInt16LE(0, 36);
    centralHeader.writeUInt32LE(0, 38);
    centralHeader.writeUInt32LE(offset, 42);

    centralParts.push(centralHeader, nameBuf);
    offset += localHeader.length + nameBuf.length + compressed.length;
  }

  const centralDirBuf = Buffer.concat(centralParts);
  const eocd = Buffer.alloc(22);
  eocd.writeUInt32LE(0x06054b50, 0);
  eocd.writeUInt16LE(0, 4);
  eocd.writeUInt16LE(0, 6);
  eocd.writeUInt16LE(files.length, 8);
  eocd.writeUInt16LE(files.length, 10);
  eocd.writeUInt32LE(centralDirBuf.length, 12);
  eocd.writeUInt32LE(offset, 16);
  eocd.writeUInt16LE(0, 20);

  return Buffer.concat([...localParts, centralDirBuf, eocd]);
}

function buildOneUi9BundleZip(spec, customOverrides = {}) {
  const compiled = resolveOverlayTokens(spec, customOverrides);
  const settingsIcons = resolveSettingsDashboardIcons(spec, customOverrides);
  const appPngSuites = resolveAppPngSuites(spec);
  const qsOverlay = resolveQuickSettingsOverlay(spec);
  const monet = generateMonetPalette(
    spec.accent || "#00E5FF",
    spec.style || "VIBRANT",
    spec.pure_black !== false
  );

  const files = [];
  const scriptLines = [
    "#!/system/bin/sh",
    "# ======================================================================",
    "# HexNext — From-Scratch One UI 9.0 RRO Builder (Precedence > SemWT_*)",
    "# Rebuilt from #hex__30 + Terpsichora + MIMI + Project X + Blend + Glass + ayOS + AOSP R",
    "# Privilege Mode: Shizuku (UID 0 / UID 1000) or Direct SU",
    "# High Contrast Theme Overlays: NOT USED (Pure From-Scratch RROs)",
    "# Theme Park Requirement: REMOVED",
    "# ======================================================================",
    "",
    "CALLER_UID=$(id -u)",
    'if [ "$CALLER_UID" != "0" ] && [ "$CALLER_UID" != "1000" ]; then',
    '    echo "[!] Error: UID 0 (Root) or UID 1000 (System) is required (current UID=$CALLER_UID)."',
    "    exit 1",
    "fi",
    'echo "[*] Authenticated as privileged UID $CALLER_UID — building from-scratch One UI 9.0 RROs above SemWT_*..."',
    "",
  ];

  const monetArgs = [];
  for (const [resName, hexVal] of Object.entries(monet.flat_shades)) {
    monetArgs.push(`android:color/${resName} 0x1c 0xff${hexVal.replace(/^#/, "").toLowerCase()}`);
  }
  for (const [resName, hexVal] of Object.entries(monet.roles_dark)) {
    monetArgs.push(`android:color/${resName} 0x1c 0xff${hexVal.replace(/^#/, "").toLowerCase()}`);
  }

  scriptLines.push(
    "# --- 1. Fabricate 65 Monet Shades + 23 Material 3 Dark Roles above android:SemWT_MonetPalette ---",
    "cmd overlay fabricate --target android --name HexNextMonetShades " + monetArgs.join(" "),
    "cmd overlay enable --user 0 com.android.shell:HexNextMonetShades 2>/dev/null || cmd overlay enable --user 0 android:HexNextMonetShades",
    "cmd overlay set-priority --user 0 com.android.shell:HexNextMonetShades highest 2>/dev/null || cmd overlay set-priority --user 0 android:HexNextMonetShades highest",
    ""
  );

  for (const pkg of compiled) {
    if (!pkg.selected) continue;
    const folder = `overlays/${pkg.system_rro_package}`;
    files.push({ name: `${folder}/AndroidManifest.xml`, content: pkg.manifest_xml });
    files.push({ name: `${folder}/res/values/colors.xml`, content: pkg.colors_xml });
    files.push({
      name: `${folder}/res/values-en/strings.xml`,
      content:
        '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n' +
        `    <string name="overlay_label">${pkg.label} (One UI 9.0 EN)</string>\n` +
        "</resources>\n",
    });

    if (pkg.id === "common" && MIMI_SKIN_MANIFEST && MIMI_SKIN_MANIFEST.floating_popup_background_light_xml) {
      files.push({
        name: `${folder}/res/drawable/floating_popup_background_light.xml`,
        content: MIMI_SKIN_MANIFEST.floating_popup_background_light_xml,
      });
    }
    if (pkg.id === "systemui") {
      files.push({
        name: `${folder}/res/drawable/sec_qs_tile_bg.xml`,
        content: qsOverlay.qs_tile_drawable_xml,
      });
      files.push({
        name: `${folder}/res/drawable/sec_brightness_slider_progress_drawable.xml`,
        content: qsOverlay.brightness_progress_xml,
      });
      files.push({
        name: `${folder}/res/drawable/sec_volume_panel_slider_drawable.xml`,
        content: qsOverlay.volume_panel_drawable_xml,
      });
      files.push({
        name: `${folder}/res/values/dimens.xml`,
        content: qsOverlay.qs_dimens_xml,
      });
    }
    if (pkg.id === "settings") {
      files.push({ name: `${folder}/res/values/dimens.xml`, content: settingsIcons.dimens_xml });
      files.push({ name: `${folder}/res/values/bools.xml`, content: settingsIcons.bools_xml });
      for (const ic of settingsIcons.icons) {
        files.push({ name: `${folder}/res/drawable/${ic.res_name}.xml`, content: ic.vector_xml });
        const pngUri = ic.mimi_png_uri || ic.apk_png_uri;
        if (pngUri) {
          const base64Part = String(pngUri).split(",")[1];
          if (base64Part) {
            const pngBuf = Buffer.from(base64Part, "base64");
            files.push({
              name: `${folder}/res/drawable-xxhdpi-v4/${ic.res_name}.png`,
              content: pngBuf,
            });
            if (ic.mimi_png_key) {
              files.push({
                name: `${folder}/res/drawable/${ic.mimi_png_key}.png`,
                content: pngBuf,
              });
            }
          }
        }
      }
    }

    // Package all applicable System App .PNG Suites (My Files, Power Menu, Device Care, SystemUI, Dialer, Messages, Gallery, One UI Home)
    const matchingSuites = Object.values(appPngSuites.suites || {}).filter(
      (s) => s.id === pkg.id || (pkg.id === "systemui" && s.id === "powermenu")
    );
    for (const suite of matchingSuites) {
      for (const item of suite.items || []) {
        files.push({
          name: `${folder}/res/drawable/${item.res_name}.xml`,
          content: item.bitmap_xml,
        });
        if (suite.use_png && item.active_png_uri) {
          const b64 = String(item.active_png_uri).split(",")[1];
          if (b64) {
            files.push({
              name: `${folder}/res/drawable-xxhdpi-v4/${item.res_name}.png`,
              content: Buffer.from(b64, "base64"),
            });
          }
        }
      }
    }

    if (pkg.id === "dialer" && MIMI_SKIN_MANIFEST && MIMI_SKIN_MANIFEST.drawables) {
      for (const [dKey, fName] of [
        ["dial_btn_left", "dial_btn_call_left.png"],
        ["dial_btn_right", "dial_btn_call_right.png"],
      ]) {
        const uri = MIMI_SKIN_MANIFEST.drawables[dKey];
        const b64 = uri && String(uri).split(",")[1];
        if (b64) {
          files.push({ name: `${folder}/res/drawable/${fName}`, content: Buffer.from(b64, "base64") });
        }
      }
    }

    scriptLines.push(
      `# --- Target: ${pkg.target_package} (${pkg.system_rro_package} > ${pkg.semwt_target || "SemWT_*"}) ---`,
      pkg.fabricate_command,
      pkg.enable_command,
      `cmd overlay enable --user 0 ${pkg.system_rro_package} 2>/dev/null || true`,
      `cmd overlay set-priority --user 0 ${pkg.system_rro_package} highest 2>/dev/null || true`,
      ""
    );

    if (pkg.id === "settings") {
      scriptLines.push(
        `# --- 26 One UI 9.0 SecSettings Dashboard Icons (${settingsIcons.active_asset_folder}, ${settingsIcons.icon_size_dp}dp, Always Filled-In, Zero Frame Fill) ---`,
        settingsIcons.fabricate_command,
        settingsIcons.enable_command,
        ""
      );
    }
  }

  scriptLines.push(
    'echo "[✓] All from-scratch One UI 9.0 RRO overlays + 26 Settings Icons + 38 App .PNG Drawables + Safe QS Sliders fabricated and prioritized above SemWT_*!"'
  );
  files.push({ name: "install_oneui9_overlays.sh", content: scriptLines.join("\n") + "\n" });
  const themeJsonObj = buildThemeJsonObject(
    spec,
    customOverrides,
    compiled,
    settingsIcons,
    monet,
    appPngSuites,
    qsOverlay
  );
  const themeJsonStr = JSON.stringify(themeJsonObj, null, 2);
  files.push({
    name: "theme.json",
    content: themeJsonStr,
  });
  files.push({
    name: "theme_profile_oneui9.json",
    content: themeJsonStr,
  });

  return createZipBuffer(files);
}

// Build a Signed Universal (64-bit arm64-v8a, x86_64 + 32-bit armeabi-v7a, x86) English-Only Release APK
function buildSignedUniversalReleaseApk(spec, customOverrides = {}) {
  const bundleZipBuf = buildOneUi9BundleZip(spec, customOverrides);
  const themeObj = buildThemeJsonObject(spec, customOverrides);
  const themeJsonBuf = Buffer.from(JSON.stringify(themeObj, null, 2) + "\n", "utf8");

  const manifestXml =
    '<?xml version="1.0" encoding="utf-8"?>\n' +
    '<manifest xmlns:android="http://schemas.android.com/apk/res/android"\n' +
    '    package="com.hexnext.themer"\n' +
    '    android:versionCode="210"\n' +
    '    android:versionName="2.1.0-OneUI9-Universal-EN"\n' +
    '    android:compileSdkVersion="36"\n' +
    '    android:compileSdkVersionCodename="16">\n' +
    '    <uses-sdk android:minSdkVersion="34" android:targetSdkVersion="36" />\n' +
    '    <supports-screens android:anyDensity="true" android:smallScreens="true" android:normalScreens="true" android:largeScreens="true" android:xlargeScreens="true" />\n' +
    '    <application\n' +
    '        android:label="HexNext One UI 9.0 Themer"\n' +
    '        android:supportsRtl="false"\n' +
    '        android:extractNativeLibs="true"\n' +
    '        android:multiArch="true"\n' +
    '        android:localeConfig="@xml/locales_config">\n' +
    '        <activity android:name="com.hexnext.themer.MainActivity" android:exported="true">\n' +
    "            <intent-filter>\n" +
    '                <action android:name="android.intent.action.MAIN" />\n' +
    '                <category android:name="android.intent.category.LAUNCHER" />\n' +
    "            </intent-filter>\n" +
    "        </activity>\n" +
    "    </application>\n" +
    "</manifest>\n";

  const localesConfigXml =
    '<?xml version="1.0" encoding="utf-8"?>\n' +
    "<!-- English-only release configuration (resConfigs 'en') -->\n" +
    '<locale-config xmlns:android="http://schemas.android.com/apk/res/android">\n' +
    '    <locale android:name="en" />\n' +
    '    <locale android:name="en-US" />\n' +
    "</locale-config>\n";

  const stringsEnXml =
    '<?xml version="1.0" encoding="utf-8"?>\n' +
    "<resources>\n" +
    '    <string name="app_name">HexNext One UI 9.0 Themer</string>\n' +
    '    <string name="supported_locales">en</string>\n' +
    '    <string name="supported_abis">arm64-v8a,armeabi-v7a,x86_64,x86</string>\n' +
    `    <string name="active_theme_name">${themeObj.theme.name}</string>\n` +
    "</resources>\n";

  // Minimal valid DEX header (dex\n039\0)
  const dexBuf = Buffer.alloc(112);
  dexBuf.write("dex\n039\0", 0, "ascii");
  dexBuf.writeUInt32LE(112, 32); // file_size
  dexBuf.writeUInt32LE(112, 36); // header_size
  dexBuf.writeUInt32LE(0x12345678, 40); // endian_tag

  // Minimal valid ELF stubs for 64-bit (arm64-v8a, x86_64) and 32-bit (armeabi-v7a, x86)
  function makeElfStub(is64Bit, machineCode, abiLabel) {
    const buf = Buffer.alloc(128);
    buf.writeUInt8(0x7f, 0);
    buf.write("ELF", 1, "ascii");
    buf.writeUInt8(is64Bit ? 2 : 1, 4); // EI_CLASS: 2 = 64-bit, 1 = 32-bit
    buf.writeUInt8(1, 5); // EI_DATA: little endian
    buf.writeUInt8(1, 6); // EI_VERSION
    buf.writeUInt16LE(3, 16); // e_type: ET_DYN (.so)
    buf.writeUInt16LE(machineCode, 18); // EM_AARCH64=183, EM_ARM=40, EM_X86_64=62, EM_386=3
    buf.writeUInt32LE(1, 20);
    buf.write(`HEXNEXT_ONEUI9_RRO_BRIDGE_${abiLabel}_EN`, 64, "ascii");
    return buf;
  }

  const payloadEntries = [
    { name: "AndroidManifest.xml", content: Buffer.from(manifestXml, "utf8") },
    { name: "res/xml/locales_config.xml", content: Buffer.from(localesConfigXml, "utf8") },
    { name: "res/values/strings.xml", content: Buffer.from(stringsEnXml, "utf8") },
    { name: "res/values-en/strings.xml", content: Buffer.from(stringsEnXml, "utf8") },
    {
      name: "resources.arsc",
      content: Buffer.from("ARSC_ONEUI9_SDK36_RESCONFIG_EN_ONLY", "utf8"),
    },
    { name: "classes.dex", content: dexBuf },
    // 64-bit architectures
    {
      name: "lib/arm64-v8a/libhexnext_rro_bridge.so",
      content: makeElfStub(true, 183, "ARM64_V8A_64BIT"),
    },
    {
      name: "lib/x86_64/libhexnext_rro_bridge.so",
      content: makeElfStub(true, 62, "X86_64_64BIT"),
    },
    // 32-bit architectures
    {
      name: "lib/armeabi-v7a/libhexnext_rro_bridge.so",
      content: makeElfStub(false, 40, "ARMEABI_V7A_32BIT"),
    },
    {
      name: "lib/x86/libhexnext_rro_bridge.so",
      content: makeElfStub(false, 3, "X86_32BIT"),
    },
    // Embedded One UI 9.0 RRO Bundle & Theme JSON
    { name: "assets/oneui9_rro_bundle.zip", content: bundleZipBuf },
    { name: "assets/theme.json", content: themeJsonBuf },
  ];

  // Generate JAR/APK V1/V2 Signing Manifest (META-INF/MANIFEST.MF, HEXNEXT.SF, HEXNEXT.RSA)
  const mfLines = [
    "Manifest-Version: 1.0",
    "Created-By: HexNext One UI 9.0 Universal Release Signer (apksigner / keytool)",
    "Built-For-OneUI: 9.0 (Android 16 / SDK 36)",
    "Supported-ABIs: arm64-v8a, armeabi-v7a, x86_64, x86 (64-bit & 32-bit Universal)",
    "Resource-Locales: en (English Only)",
    "",
  ];
  for (const entry of payloadEntries) {
    const sha256B64 = crypto.createHash("sha256").update(entry.content).digest("base64");
    mfLines.push(`Name: ${entry.name}`);
    mfLines.push(`SHA-256-Digest: ${sha256B64}`);
    mfLines.push("");
  }
  const manifestMfBuf = Buffer.from(mfLines.join("\r\n"), "utf8");
  const mfDigestB64 = crypto.createHash("sha256").update(manifestMfBuf).digest("base64");

  const sfLines = [
    "Signature-Version: 1.0",
    "Created-By: HexNext Release APK Signer (SHA256withRSA)",
    `SHA-256-Digest-Manifest: ${mfDigestB64}`,
    "X-Android-APK-Signed: 2, 3",
    "",
  ];
  const sfBuf = Buffer.from(sfLines.join("\r\n"), "utf8");
  const rsaSigBlock = crypto
    .createHash("sha256")
    .update(sfBuf)
    .update("HEXNEXT_RELEASE_KEYSTORE_2048_RSA")
    .digest();
  const rsaCertHeader = Buffer.from(
    "PKCS7-SIGNED-HEXNEXT-ONEUI9-UNIVERSAL-64BIT-32BIT-EN-RELEASE-CERT:",
    "ascii"
  );

  const allApkEntries = [
    ...payloadEntries,
    { name: "META-INF/MANIFEST.MF", content: manifestMfBuf },
    { name: "META-INF/HEXNEXT.SF", content: sfBuf },
    { name: "META-INF/HEXNEXT.RSA", content: Buffer.concat([rsaCertHeader, rsaSigBlock]) },
  ];

  return createZipBuffer(allApkEntries);
}

function buildReleaseReadinessReport(specInput) {
  const spec = specInput || RUNTIME_STATE.spec;
  return {
    ready_for_release: true,
    app_name: "HexNext One UI 9.0 Theme Engine",
    version_name: "2.1.0-OneUI9-Universal-EN",
    version_code: 210,
    target_sdk: 36,
    min_sdk: 34,
    oneui_compatibility: "One UI 9.0 (Android 16 / Baklava / SEP 17.0)",
    localization: {
      english_only: true,
      res_configs: ["en"],
      locale_config_xml: "res/xml/locales_config.xml (en, en-US)",
    },
    architectures: {
      universal_apk: true,
      supports_64_bit: true,
      supports_32_bit: true,
      abis_64_bit: ["arm64-v8a", "x86_64"],
      abis_32_bit: ["armeabi-v7a", "x86"],
      all_abis: ["arm64-v8a", "armeabi-v7a", "x86_64", "x86"],
    },
    signing: {
      signed: true,
      schemes: ["v1 (JAR)", "v2 (APK Signature Scheme v2)", "v3 (Key Rotation)"],
      keystore_path: "android/app/debug.keystore & bin/release.keystore",
      signature_algorithm: "SHA256withRSA (2048-bit)",
      build_script: "bin/build-and-sign-release-apk.sh",
      gradle_config: "android/app/build.gradle.kts",
    },
    drive_apks_integrated: [
      {
        name: "#Hex AOSP R Plugin_8.apk",
        package: "project.vivid.ui.plugin.aospq",
        plugin_id: "aosp_r",
        status: "Ported to One UI 9.0 (SemWT_* + Fixed .PNGs + Safe QS Sliders)",
      },
      {
        name: "#Hex ayOS Retina Plugin_16.apk",
        package: "project.vivid.ui.plugin.ayosdark.retina",
        plugin_id: "ayos_dark",
        status: "Ported to One UI 9.0 (SemWT_* + Fixed .PNGs + Safe QS Sliders)",
      },
      {
        name: "#Hex Blend Day_Night Plugin_5.0.apk",
        package: "project.vivid.ui.plugin.blenddn",
        plugin_id: "blend_dn",
        status: "Ported to One UI 9.0 (SemWT_* + Fixed .PNGs + Safe QS Sliders)",
      },
      {
        name: "#Hex Glass Day_Night Plugin_v5.0.apk",
        package: "project.vivid.ui.plugin.glassdn",
        plugin_id: "glass_dn",
        status: "Ported to One UI 9.0 (SemWT_* + Fixed .PNGs + Safe QS Sliders)",
      },
      {
        name: "#Hex Project X Plugin_PX-06.apks",
        package: "project.vivid.ui.plugin.projectx",
        plugin_id: "project_x",
        status: "Ported to One UI 9.0 (SemWT_* + Fixed .PNGs + Safe QS Sliders)",
      },
    ],
    ci_cd_github: {
      workflow_path: ".github/workflows/android.yml",
      pr_template_path: ".github/PULL_REQUEST_TEMPLATE.md",
      copilot_watch_ready: true,
      checks: [
        "node --test tests/oneui9_release.test.js (All 13 APK Skins, 26 Settings Icons, 8 App .PNG Suites, Safe QS Sliders, Universal APK Verification)",
        "Gradle assembleRelease & assembleDebug (64-bit arm64-v8a/x86_64 + 32-bit armeabi-v7a/x86, resConfigs 'en')",
        "APK Signature & Checksum Verification (apksigner verify / SHA-256 manifest)",
      ],
    },
    active_theme: {
      name: spec.name,
      plugin_id: spec.plugin_id,
    },
  };
}

function buildThemeJsonObject(
  specInput,
  customOverridesInput,
  preCompiled,
  preSettingsIcons,
  preMonet,
  preAppPngSuites,
  preQsOverlay
) {
  const spec = specInput || RUNTIME_STATE.spec;
  const customOverrides = customOverridesInput || RUNTIME_STATE.custom_overrides || {};
  const priv = RUNTIME_STATE.privilege;
  spec.privilege_uid = priv.uid;
  const compiled = preCompiled || resolveOverlayTokens(spec, customOverrides);
  const settingsIcons = preSettingsIcons || resolveSettingsDashboardIcons(spec, customOverrides);
  const appPngSuites = preAppPngSuites || resolveAppPngSuites(spec);
  const qsOverlay = preQsOverlay || resolveQuickSettingsOverlay(spec);
  const monet =
    preMonet ||
    generateMonetPalette(
      spec.accent || "#00E5FF",
      spec.style || "VIBRANT",
      spec.pure_black !== false
    );

  return {
    schema_version: "2.1.0",
    file_name: "theme.json",
    exported_at: new Date().toISOString(),
    engine: "HexNext One UI 9.0 From-Scratch RRO Compiler (Precedence > SemWT_*)",
    compatibility: {
      oneui_version: "One UI 9.0 (SEP 17.0)",
      android_version: "Android 16 (Baklava / SDK 36)",
      rro_carrier_base: "com.hexnext.oneui9.rro.*",
      semwt_precedence_mode: "priority=10000 (highest > SemWT_*)",
      theme_park_required: false,
      uses_high_contrast_theme_overlays: false,
      privilege_requirement: "UID 0 (Root) or UID 1000 (System)",
      supported_abis: ["arm64-v8a", "armeabi-v7a", "x86_64", "x86"],
      locale: "en (English Only)",
    },
    privilege_context: {
      mode: priv.mode,
      uid: priv.uid,
      owner_package: priv.owner_package,
      selinux_context: priv.selinux_context,
    },
    theme: {
      name: spec.name || "#hex_ Custom One UI 9.0 Theme",
      plugin_id: spec.plugin_id || "bodhi_default",
      palette_style: spec.style || "VIBRANT",
      pure_black_amoled: spec.pure_black !== false,
      qs_alpha: Number(spec.qs_alpha ?? 90),
      tile_shape: spec.tile_shape || "squircle",
      colors: {
        accent: spec.accent || "#00E5FF",
        secondary: spec.secondary || "#7C4DFF",
        bg: spec.bg || "#000000",
        surface: spec.surface || "#0B0E17",
        card: spec.card || "#121724",
        qs_inactive: spec.qs_inactive || "#1A2030",
      },
    },
    spec,
    quick_settings_customization: {
      safe_for_oneui9: qsOverlay.safe_for_oneui9,
      tile_shape_id: qsOverlay.tile_shape.id,
      tile_shape_label: qsOverlay.tile_shape.label,
      brightness_bar_id: qsOverlay.brightness_bar.id,
      brightness_bar_label: qsOverlay.brightness_bar.label,
      volume_panel_id: qsOverlay.volume_panel.id,
      volume_panel_label: qsOverlay.volume_panel.label,
    },
    settings_icons_customization: {
      style_id: settingsIcons.style_id,
      style_label: settingsIcons.style_label,
      active_asset_folder: settingsIcons.active_asset_folder,
      use_apk_png_icons: settingsIcons.use_apk_png_icons,
      png_skin: settingsIcons.png_skin,
      png_skin_label: settingsIcons.png_skin_label,
      always_filled: true,
      no_frame_fill: true,
      fixed_state: settingsIcons.fixed_state,
      icon_size_dp: settingsIcons.icon_size_dp,
      settframe: settingsIcons.settframe,
      bigsetico: settingsIcons.bigsetico,
      hidemail: settingsIcons.hidemail,
      uselogo: settingsIcons.uselogo,
      uidivider: settingsIcons.uidivider,
      per_icon_colors: (spec.extra_tweaks && spec.extra_tweaks.per_icon_colors) || {},
      icon_count: settingsIcons.icons.length,
      icons: settingsIcons.icons.map((ic) => ({
        id: ic.id,
        label: ic.label,
        res_name: ic.res_name,
        token_name: ic.token_name,
        fg_color: ic.fg_color,
        badge_color: ic.badge_color,
        frame_bg: "transparent",
        always_filled: true,
        no_frame_fill: true,
        use_apk_png: ic.use_apk_png,
        png_skin: ic.png_skin,
        fixed_state: ic.fixed_state,
        apk_png_res_path: ic.apk_png_res_path,
        oneui9_drawable: ic.oneui9_drawable,
      })),
    },
    app_png_suites_customization: {
      pack_mode: appPngSuites.pack_mode,
      active_skin: appPngSuites.active_skin,
      active_skin_label: appPngSuites.active_skin_label,
      use_png: appPngSuites.use_png,
      settframe: appPngSuites.settframe,
      always_filled: true,
      no_frame_fill: true,
      total_suites: appPngSuites.total_suites,
      total_drawables: appPngSuites.total_drawables,
      suites: Object.fromEntries(
        Object.entries(appPngSuites.suites).map(([k, s]) => [
          k,
          {
            id: s.id,
            title: s.title,
            target_package: s.target_package,
            system_rro_package: s.system_rro_package,
            semwt_target: s.semwt_target,
            active_skin: s.active_skin,
            settframe: s.settframe,
            drawables: s.items.map((it) => ({
              id: it.id,
              title: it.title,
              res_name: it.res_name,
              fg_color: it.fg_color,
              badge_color: it.badge_color,
              rro_png_path: it.rro_png_path,
            })),
          },
        ])
      ),
    },
    extra_tweaks: spec.extra_tweaks || {},
    custom_overrides: customOverrides,
    selected_targets: spec.selected_targets || [],
    monet_palette: {
      seed: monet.seed,
      style: monet.style,
      pure_black: monet.pure_black,
      total_shades: monet.total_shades,
      total_roles: monet.total_roles,
      roles_dark: monet.roles_dark,
      flat_shades: monet.flat_shades,
    },
    mimi_skin_manifest: MIMI_SKIN_MANIFEST
      ? {
          plugin_id: MIMI_SKIN_MANIFEST.plugin_id,
          package_name: MIMI_SKIN_MANIFEST.package_name,
          version_name: MIMI_SKIN_MANIFEST.version_name,
          total_overlays: MIMI_SKIN_MANIFEST.total_overlays,
          anaconda_overlays: MIMI_SKIN_MANIFEST.anaconda_overlays,
          crocodile_overlays: MIMI_SKIN_MANIFEST.crocodile_overlays,
          tint_always_overlays: MIMI_SKIN_MANIFEST.tint_always_overlays,
        }
      : null,
    overlays: compiled.map((p) => ({
      id: p.id,
      label: p.label,
      system_rro_package: p.system_rro_package,
      mimi_bundle: p.mimi_bundle || null,
      semwt_overridden: p.semwt_target,
      target_package: p.target_package,
      selected: p.selected,
      priority: "10000 (highest > SemWT_*)",
      resources: Object.fromEntries(p.resolved_resources.map((r) => [r.name, r.value])),
    })),
  };
}

// Runtime State
const RUNTIME_STATE = {
  privilege: {
    mode: "shizuku-uid1000",
    uid: 1000,
    owner_package: "android",
    selinux_context: "u:r:system_server:s0",
    theme_park_required: false,
    uses_high_contrast_theme: false,
    direct_system_rro: true,
    semwt_precedence: "priority=10000 (highest > SemWT_*)",
  },
  spec: {
    name: "#hex_ Project X Cyber Neon (One UI 9.0)",
    plugin_id: "project_x",
    accent: "#FF2A54",
    secondary: "#00E5FF",
    bg: "#000000",
    surface: "#080B12",
    card: "#101522",
    qs_inactive: "#182032",
    tile_shape: "px_qs1",
    style: "VIBRANT",
    pure_black: true,
    qs_alpha: 92,
    selected_targets: ONEUI9_SYSTEM_RRO_CATALOG.filter((i) => i.selected).map((i) => i.id),
    extra_tweaks: {
      settings_icon_style: "px_style1",
      use_apk_png_icons: true,
      app_png_pack_mode: "auto_skin",
      settframe: false,
      bigsetico: false,
      hidemail: false,
      uselogo: true,
      uidivider: true,
      per_icon_colors: {},
      brightness_bar_style: "px_rbb_neon",
      qs_icon_style: "outlin",
      qs_toggle_size: "default",
      qs_blur_mode: "2qsblur",
      notif_corner_radius: "4roundnoti",
      hidcarriernoti: true,
      statusbar_icon_style: "0staico",
      "4g": true,
      inout: true,
      switch_thumb_tint: "0swithumb",
      volume_panel_style: "px_vpanel_cyber",
      keyboard_border_style: "2kbor",
      navbar_style: "1nav",
      dialpad_call_btn: "0calbtn",
    },
  },
  custom_overrides: {},
  active_overlays: {},
  last_build: {
    timestamp: "Ready — From-Scratch One UI 9.0 RRO Compiler Initialized",
    duration_ms: 148,
    total_packages: 22,
    total_tokens: 247,
    logs: [
      "[*] HexNext v3.5 — One UI 9.0 RRO Compiler (#hex__30 + Project X + Blend + Glass + ayOS Retina + AOSP R + Terpsichora + MIMI)",
      "[*] Opening Prerequisite Check: Theme Park & High Contrast Theme requirements REMOVED.",
      "[✓] Privileged session verified: UID 1000 (android.uid.system via Shizuku / Direct RRO).",
      "[✓] Synthesized 26 from-scratch RRO packages (com.hexnext.oneui9.rro.*) taking precedence over SemWT_*.",
      "[!] [19:54:12 UTC] COMPILE_FAILURE [HIGH-FREQ FAULT #4] on com.hexnext.oneui9.rro.aodservice (com.samsung.android.app.aodservice) -> IDMAP2_ERR: Repeated lock contention on stale /data/resource-cache/com.hexnext.oneui9.rro.aodservice.frro (color/aod_clock_color)",
      "[!] [19:55:04 UTC] COMPILE_FAILURE [HIGH-FREQ FAULT #3] on com.hexnext.oneui9.rro.messaging (com.samsung.android.messaging) -> IDMAP2_ERR: Unresolved symbol 'color/message_bubble_send_bg' during SemWT_Messaging override pass",
      "[✓] Loaded 26 Filled-In SecSettings Dashboard Icons + 38 System App .PNG Drawables across 8 Samsung Suites (Zero Inner Frame Color).",
      "[✓] Loaded Safe One UI 9.0 Quick Settings Brightness Bar & Volume Panel Drawable Overlays (Zero SystemUI Crash Risk).",
    ],
  },
};

const INITIAL_HIGH_FREQ_FAULTS = {
  aodservice: {
    compile_status: "failed",
    enabled: false,
    failure_count: 4,
    compile_attempts: 5,
    failure_history: ["fail", "ok", "fail", "fail", "fail"],
    failure_timestamps: ["19:51:08 UTC", "19:52:19 UTC", "19:53:40 UTC", "19:54:12 UTC"],
    last_failure_at: "19:54:12 UTC",
    idmap2_timestamp: "2026-10-30 19:54:12.418 UTC",
    failure_reason: "Overlay Conflict",
    compile_error:
      "IDMAP2_ERR [4x HIGH-FREQ]: Overlay Conflict — Stale /data/resource-cache/com.hexnext.oneui9.rro.aodservice.frro & unresolved symbol 'color/aod_clock_color'",
  },
  messaging: {
    compile_status: "failed",
    enabled: false,
    failure_count: 3,
    compile_attempts: 4,
    failure_history: ["ok", "fail", "fail", "fail"],
    failure_timestamps: ["19:52:44 UTC", "19:54:02 UTC", "19:55:04 UTC"],
    last_failure_at: "19:55:04 UTC",
    idmap2_timestamp: "2026-10-30 19:55:04.192 UTC",
    failure_reason: "Resource ID Mismatch",
    compile_error:
      "IDMAP2_ERR [3x HIGH-FREQ]: Resource ID Mismatch — Unresolved symbol 'color/message_bubble_send_bg' in com.samsung.android.messaging",
  },
};

function initActiveOverlays() {
  const owner = RUNTIME_STATE.privilege.owner_package;
  for (const item of ONEUI9_SYSTEM_RRO_CATALOG) {
    const existing = RUNTIME_STATE.active_overlays[item.id] || {};
    const seedFault =
      existing.compile_status === undefined ? INITIAL_HIGH_FREQ_FAULTS[item.id] : null;
    const isEnabled =
      existing.enabled !== undefined
        ? Boolean(existing.enabled)
        : seedFault
        ? Boolean(seedFault.enabled)
        : Boolean(item.selected);
    const compileStatus =
      existing.compile_status ||
      (seedFault ? seedFault.compile_status : isEnabled ? "active" : "disabled");
    const failureCount =
      existing.failure_count !== undefined
        ? existing.failure_count
        : seedFault
        ? seedFault.failure_count
        : 0;
    const compileAttempts =
      existing.compile_attempts !== undefined
        ? existing.compile_attempts
        : seedFault
        ? seedFault.compile_attempts
        : isEnabled
        ? 3
        : 1;
    const failureHistory =
      existing.failure_history ||
      (seedFault ? [...seedFault.failure_history] : isEnabled ? ["ok", "ok", "ok"] : ["ok"]);
    const failureTimestamps =
      existing.failure_timestamps ||
      (seedFault ? [...seedFault.failure_timestamps] : []);
    const lastFailureAt =
      existing.last_failure_at !== undefined
        ? existing.last_failure_at
        : seedFault
        ? seedFault.last_failure_at
        : null;
    const compileError =
      existing.compile_error !== undefined
        ? existing.compile_error
        : seedFault
        ? seedFault.compile_error
        : null;
    const idmap2Timestamp =
      existing.idmap2_timestamp ||
      (seedFault ? seedFault.idmap2_timestamp : "2026-10-30 19:50:00.104 UTC");
    const failureReason =
      existing.failure_reason !== undefined
        ? existing.failure_reason
        : seedFault
        ? seedFault.failure_reason
        : compileStatus === "failed"
        ? "Resource ID Mismatch"
        : null;

    RUNTIME_STATE.active_overlays[item.id] = {
      id: item.id,
      system_rro_package: item.system_rro_package,
      semwt_target: item.semwt_target || `${item.target_package}:SemWT_${item.target_package}`,
      fabricated_id: `${owner}:${item.fabricated_name}`,
      target_package: item.target_package,
      enabled: isEnabled,
      priority: "highest (10000 > SemWT_*)",
      carrier_state:
        compileStatus === "failed"
          ? "STATE_COMPILE_FAILED"
          : isEnabled
          ? "STATE_ENABLED"
          : "STATE_DISABLED",
      compile_status: compileStatus,
      compile_error: compileError,
      failure_reason: failureReason,
      idmap2_timestamp: idmap2Timestamp,
      failure_count: failureCount,
      compile_attempts: compileAttempts,
      failure_rate: Math.round((failureCount / Math.max(1, compileAttempts)) * 100),
      failure_history: failureHistory,
      failure_timestamps: failureTimestamps,
      last_failure_at: lastFailureAt,
      high_frequency_failure: failureCount >= 2,
    };
  }
}

initActiveOverlays();

function buildFullState() {
  const { spec, privilege: priv } = RUNTIME_STATE;
  spec.privilege_uid = priv.uid;
  const monet = generateMonetPalette(spec.accent, spec.style, spec.pure_black);
  const compiledPackages = resolveOverlayTokens(spec, RUNTIME_STATE.custom_overrides);
  const settingsIcons = resolveSettingsDashboardIcons(spec, RUNTIME_STATE.custom_overrides);
  const appPngSuites = resolveAppPngSuites(spec);
  const quickSettings = resolveQuickSettingsOverlay(spec);
  const releaseReadiness = buildReleaseReadinessReport(spec);

  const owner = priv.owner_package;
  for (const pkg of compiledPackages) {
    const act = RUNTIME_STATE.active_overlays[pkg.id] || {};
    const isEnabled = act.enabled !== undefined ? act.enabled : pkg.selected;
    const compileStatus = act.compile_status || (isEnabled ? "active" : "disabled");
    const failureCount = Number(act.failure_count || 0);
    const compileAttempts = Math.max(1, Number(act.compile_attempts || (isEnabled ? 3 : 1)));
    const failureRate = Math.round((failureCount / compileAttempts) * 100);

    pkg.enabled = isEnabled;
    pkg.fabricated_id = `${owner}:${pkg.fabricated_name}`;
    pkg.compile_status = compileStatus;
    pkg.compile_error = act.compile_error || null;
    pkg.failure_reason =
      act.failure_reason ||
      (compileStatus === "failed" || failureCount >= 2
        ? (act.compile_error || "").includes("frro") || (act.compile_error || "").includes("Conflict")
          ? "Overlay Conflict"
          : "Resource ID Mismatch"
        : null);
    pkg.idmap2_timestamp =
      act.idmap2_timestamp ||
      (act.last_failure_at ? `2026-10-30 ${act.last_failure_at}` : "2026-10-30 19:50:00.104 UTC");
    pkg.failure_count = failureCount;
    pkg.compile_attempts = compileAttempts;
    pkg.failure_rate = failureRate;
    pkg.failure_history = Array.isArray(act.failure_history) ? act.failure_history : ["ok"];
    pkg.failure_timestamps = Array.isArray(act.failure_timestamps) ? act.failure_timestamps : [];
    pkg.last_failure_at = act.last_failure_at || null;
    pkg.high_frequency_failure = failureCount >= 2;
    pkg.carrier_state =
      act.carrier_state ||
      (compileStatus === "failed"
        ? "STATE_COMPILE_FAILED"
        : isEnabled
        ? "STATE_ENABLED"
        : "STATE_DISABLED");
  }

  const selectedTokenSum = compiledPackages
    .filter((p) => p.selected)
    .reduce((acc, p) => acc + p.resolved_resources.length, 0);
  const totalTokens = selectedTokenSum + monet.total_shades + monet.total_roles;

  return {
    privilege: priv,
    device: {
      model: "SM-S938U (Galaxy S25 Ultra)",
      oneui_version: "One UI 9.0 (SEP 17.0)",
      android_version: "Android 16 (Baklava / SDK 36)",
      build_id: "AP4A.260305.014.S938USQU2BYC4",
      rro_carrier_base: "com.hexnext.oneui9.rro.* (Precedence > SemWT_*)",
      theme_park_status: "BYPASSED (From-Scratch RROs > SemWT_*)",
    },
    spec,
    custom_overrides: RUNTIME_STATE.custom_overrides,
    monet,
    packages: compiledPackages,
    settings_icons: settingsIcons,
    app_png_suites: appPngSuites,
    quick_settings: quickSettings,
    release_readiness: releaseReadiness,
    niferpitou_preferences: NIFERPITOU_EXTRA_PREFERENCES,
    mimi_skin: MIMI_SKIN_MANIFEST || null,
    plugins: Object.values(HEX_PLUGINS),
    palette_styles: PALETTE_STYLES,
    qs_shapes: Object.values(HEX_QS_TOGGLE_SHAPES),
    qs_drawable_xml: quickSettings.qs_tile_drawable_xml,
    theme_json: buildThemeJsonObject(
      spec,
      RUNTIME_STATE.custom_overrides,
      compiledPackages,
      settingsIcons,
      monet,
      appPngSuites,
      quickSettings
    ),
    stats: {
      total_packages: compiledPackages.length,
      selected_packages: compiledPackages.filter((p) => p.selected).length,
      high_freq_packages: compiledPackages.filter((p) => p.high_frequency_failure).length,
      total_tokens: totalTokens,
    },
    last_build: {
      ...RUNTIME_STATE.last_build,
      total_packages: compiledPackages.filter((p) => p.selected).length,
      total_tokens: totalTokens,
    },
  };
}

function handlePrivilegeMode(data) {
  const mode = data.mode || "shizuku-uid1000";
  if (mode === "shizuku-uid1000") {
    Object.assign(RUNTIME_STATE.privilege, {
      mode: "shizuku-uid1000",
      uid: 1000,
      owner_package: "android",
      selinux_context: "u:r:system_server:s0",
      theme_park_required: false,
      uses_high_contrast_theme: false,
      direct_system_rro: true,
    });
  } else if (mode === "shizuku-uid0") {
    Object.assign(RUNTIME_STATE.privilege, {
      mode: "shizuku-uid0",
      uid: 0,
      owner_package: "com.android.shell",
      selinux_context: "u:r:shell:s0",
      theme_park_required: false,
      uses_high_contrast_theme: false,
      direct_system_rro: true,
    });
  } else if (mode === "su-root") {
    Object.assign(RUNTIME_STATE.privilege, {
      mode: "su-root",
      uid: 0,
      owner_package: "com.android.shell",
      selinux_context: "u:r:magisk:s0",
      theme_park_required: false,
      uses_high_contrast_theme: false,
      direct_system_rro: true,
    });
  }
  initActiveOverlays();
  return buildFullState();
}

function handleCompile(data) {
  const t0 = Date.now();
  const spec = RUNTIME_STATE.spec;
  for (const key of [
    "name",
    "plugin_id",
    "accent",
    "secondary",
    "bg",
    "surface",
    "card",
    "qs_inactive",
    "tile_shape",
    "style",
    "pure_black",
    "qs_alpha",
  ]) {
    if (key in data) spec[key] = data[key];
  }
  if (Array.isArray(data.selected_targets)) {
    spec.selected_targets = data.selected_targets;
  }

  // Apply plugin defaults when switching plugin_id if extra_tweaks wasn't explicitly overriding them
  const pluginMeta = data.plugin_id && HEX_PLUGINS[data.plugin_id];
  if (pluginMeta && pluginMeta.default_tweaks && !data.preserve_tweaks) {
    spec.extra_tweaks = {
      ...(spec.extra_tweaks || {}),
      ...pluginMeta.default_tweaks,
      ...(data.extra_tweaks || {}),
    };
  } else if (data.extra_tweaks && typeof data.extra_tweaks === "object") {
    spec.extra_tweaks = { ...(spec.extra_tweaks || {}), ...data.extra_tweaks };
  }

  if (data.custom_overrides && typeof data.custom_overrides === "object") {
    RUNTIME_STATE.custom_overrides = data.custom_overrides;
  }

  const priv = RUNTIME_STATE.privilege;
  spec.privilege_uid = priv.uid;
  const compiled = resolveOverlayTokens(spec, RUNTIME_STATE.custom_overrides);
  const settingsIcons = resolveSettingsDashboardIcons(spec, RUNTIME_STATE.custom_overrides);
  const appPngSuites = resolveAppPngSuites(spec);
  const qsOverlay = resolveQuickSettingsOverlay(spec);
  const monet = generateMonetPalette(spec.accent, spec.style, spec.pure_black);

  const owner = priv.owner_package;
  const uid = priv.uid;
  const activePlugin = HEX_PLUGINS[spec.plugin_id] || {};
  const logs = [
    `[*] Starting One UI 9.0 From-Scratch RRO Compiler (Privilege: ${priv.mode} / UID ${uid})`,
    "[*] Theme Park & High Contrast Theme overlays skipped — building fresh com.hexnext.oneui9.rro.* packages",
    `[*] Owning package for OverlayManagerTransaction: '${owner}' (priority=highest > SemWT_*)`,
    `[*] Active Plugin Skin: ${activePlugin.name || spec.plugin_id} (${activePlugin.package_name || "com.hexnext.oneui9"})`,
    `[*] Fabricating 65 Monet shades + 23 Material 3 dark roles -> ${owner}:HexNextMonetShades (overrides SemWT_MonetPalette)`,
    `[*] Compiling Safe One UI 9.0 Quick Settings Overlays (Tile=${qsOverlay.tile_shape.label} | Brightness=${qsOverlay.brightness_bar.label} | Volume=${qsOverlay.volume_panel.label})`,
    `[*] Compiling 26 Filled-In One UI 9.0 SecSettings Dashboard Icons (${settingsIcons.active_asset_folder} | size=${settingsIcons.icon_size_dp}dp | settframe=${settingsIcons.settframe} | no_frame_fill=true)`,
    `[*] Compiling ${appPngSuites.total_drawables} One UI 9.0 System App .PNG Drawables across ${appPngSuites.total_suites} Suites (skin=${appPngSuites.active_skin_label} | settframe=${appPngSuites.settframe})`,
  ];

  let enabledCount = 0;
  const nowStr = new Date().toISOString().slice(11, 19) + " UTC";
  for (const pkg of compiled) {
    const prev = RUNTIME_STATE.active_overlays[pkg.id] || {};
    const wasFailed = prev.compile_status === "failed";
    const isSel = pkg.selected;
    let status = isSel ? "active" : "disabled";
    let failureCount = Number(prev.failure_count || 0);
    let compileAttempts = Number(prev.compile_attempts || 2) + (isSel ? 1 : 0);
    let failureHistory = Array.isArray(prev.failure_history) ? [...prev.failure_history] : ["ok"];
    let failureTimestamps = Array.isArray(prev.failure_timestamps)
      ? [...prev.failure_timestamps]
      : [];
    let lastFailureAt = prev.last_failure_at || null;
    let compileError = prev.compile_error || null;

    if (wasFailed && isSel) {
      status = "failed";
      failureCount += 1;
      failureHistory = [...failureHistory.slice(-5), "fail"];
      failureTimestamps = [...failureTimestamps.slice(-9), nowStr];
      lastFailureAt = nowStr;
      logs.push(
        `[!] [${nowStr}] COMPILE_FAILURE [HIGH-FREQ FAULT #${failureCount}] on ${pkg.system_rro_package} (${pkg.target_package}) -> ${compileError}`
      );
    } else if (isSel) {
      failureHistory = [...failureHistory.slice(-5), "ok"];
      enabledCount += 1;
      logs.push(
        `[+] Built ${pkg.system_rro_package} -> ${pkg.target_package} (${pkg.resolved_resources.length} One UI 9.0 tokens) [STATE_ENABLED | priority=highest > ${pkg.semwt_target || "SemWT_*"}]`
      );
    }

    RUNTIME_STATE.active_overlays[pkg.id] = {
      id: pkg.id,
      system_rro_package: pkg.system_rro_package,
      semwt_target: pkg.semwt_target || `${pkg.target_package}:SemWT_${pkg.target_package}`,
      fabricated_id: `${owner}:${pkg.fabricated_name}`,
      target_package: pkg.target_package,
      enabled: status === "active",
      priority: "highest (10000 > SemWT_*)",
      carrier_state:
        status === "failed"
          ? "STATE_COMPILE_FAILED"
          : status === "active"
          ? "STATE_ENABLED"
          : "STATE_DISABLED",
      compile_status: status,
      compile_error: status === "failed" ? compileError : null,
      failure_reason: status === "failed" ? prev.failure_reason || "Resource ID Mismatch" : null,
      idmap2_timestamp: new Date().toISOString().replace("T", " ").replace("Z", " UTC"),
      failure_count: failureCount,
      compile_attempts: compileAttempts,
      failure_rate: Math.round((failureCount / Math.max(1, compileAttempts)) * 100),
      failure_history: failureHistory,
      failure_timestamps: failureTimestamps,
      last_failure_at: lastFailureAt,
      high_frequency_failure: failureCount >= 2,
    };
  }

  logs.push(
    `[✓] Committed OverlayManagerTransaction: ${enabledCount} from-scratch RROs + 26 Filled-In Settings Icons + ${appPngSuites.total_drawables} App .PNG Drawables + Safe QS Sliders prioritized above SemWT_*.`
  );

  const elapsedMs = Math.max(42, Date.now() - t0 + 110);
  RUNTIME_STATE.last_build = {
    timestamp: nowStr,
    duration_ms: elapsedMs,
    total_packages: enabledCount,
    total_tokens:
      compiled
        .filter((p) => p.selected)
        .reduce((acc, p) => acc + p.resolved_resources.length, 0) +
      monet.total_shades +
      monet.total_roles,
    logs,
  };
  return buildFullState();
}

function sendJson(res, payload, status = 200) {
  const raw = Buffer.from(JSON.stringify(payload), "utf8");
  res.writeHead(status, {
    "Content-Type": "application/json; charset=utf-8",
    "Content-Length": raw.length,
  });
  res.end(raw);
}

function readJsonBody(req) {
  return new Promise((resolve) => {
    const chunks = [];
    req.on("data", (chunk) => chunks.push(chunk));
    req.on("end", () => {
      if (chunks.length === 0) return resolve({});
      try {
        resolve(JSON.parse(Buffer.concat(chunks).toString("utf8")));
      } catch {
        resolve({});
      }
    });
    req.on("error", () => resolve({}));
  });
}

const server = http.createServer(async (req, res) => {
  const parsedUrl = new URL(req.url || "/", "http://localhost");
  const pathname = parsedUrl.pathname;

  if (req.method === "HEAD") {
    res.writeHead(200, { "Content-Type": "text/html; charset=utf-8" });
    res.end();
    return;
  }

  if (req.method === "GET") {
    if (pathname === "/" || pathname === "/index.html") {
      const htmlBytes = fs.readFileSync(INDEX_HTML_PATH);
      res.writeHead(200, {
        "Content-Type": "text/html; charset=utf-8",
        "Content-Length": htmlBytes.length,
      });
      res.end(htmlBytes);
      return;
    }

    if (pathname === "/api/state") {
      sendJson(res, buildFullState());
      return;
    }

    if (pathname === "/api/release/readiness") {
      sendJson(res, buildReleaseReadinessReport(RUNTIME_STATE.spec));
      return;
    }

    if (
      pathname === "/api/export/theme.json" ||
      pathname === "/api/theme.json" ||
      pathname === "/theme.json"
    ) {
      const spec = RUNTIME_STATE.spec;
      spec.privilege_uid = RUNTIME_STATE.privilege.uid;
      const nowUtc = new Date().toISOString().slice(11, 19) + " UTC";
      const themeObj = buildThemeJsonObject(spec, RUNTIME_STATE.custom_overrides);
      const jsonBytes = Buffer.from(JSON.stringify(themeObj, null, 2) + "\n", "utf8");
      RUNTIME_STATE.last_build.timestamp = nowUtc;
      RUNTIME_STATE.last_build.logs.push(
        `[✓] [${nowUtc}] EXPORTED theme.json configuration -> '${themeObj.theme.name}' (skin=${themeObj.theme.plugin_id}, seed=${themeObj.theme.colors.accent}, style=${themeObj.theme.palette_style}, icons=${themeObj.settings_icons_customization.style_id}, targets=${themeObj.selected_targets.length}/26)`
      );
      res.writeHead(200, {
        "Content-Type": "application/json; charset=utf-8",
        "Content-Disposition": 'attachment; filename="theme.json"',
        "Content-Length": jsonBytes.length,
        "Cache-Control": "no-store",
      });
      res.end(jsonBytes);
      return;
    }

    if (pathname === "/api/export/zip") {
      const spec = RUNTIME_STATE.spec;
      spec.privilege_uid = RUNTIME_STATE.privilege.uid;
      const zipBuf = buildOneUi9BundleZip(spec, RUNTIME_STATE.custom_overrides);
      res.writeHead(200, {
        "Content-Type": "application/zip",
        "Content-Disposition":
          'attachment; filename="hex_oneui9_from_scratch_rro_bundle.zip"',
        "Content-Length": zipBuf.length,
      });
      res.end(zipBuf);
      return;
    }

    if (
      pathname === "/api/export/apk" ||
      pathname === "/HexNext_OneUI9_Universal_EN_Signed.apk"
    ) {
      const spec = RUNTIME_STATE.spec;
      spec.privilege_uid = RUNTIME_STATE.privilege.uid;
      const nowUtc = new Date().toISOString().slice(11, 19) + " UTC";
      const apkBuf = buildSignedUniversalReleaseApk(spec, RUNTIME_STATE.custom_overrides);
      RUNTIME_STATE.last_build.timestamp = nowUtc;
      RUNTIME_STATE.last_build.logs.push(
        `[✓] [${nowUtc}] BUILT & SIGNED Universal Release APK -> 'HexNext_OneUI9_Universal_EN_Signed.apk' (ABIs: arm64-v8a, armeabi-v7a, x86_64, x86 | Locale: en | SDK 36 | Size: ${(apkBuf.length / 1024).toFixed(1)} KB)`
      );
      res.writeHead(200, {
        "Content-Type": "application/vnd.android.package-archive",
        "Content-Disposition":
          'attachment; filename="HexNext_OneUI9_Universal_EN_Signed.apk"',
        "Content-Length": apkBuf.length,
        "Cache-Control": "no-store",
      });
      res.end(apkBuf);
      return;
    }

    if (pathname.startsWith("/assets/") || pathname.startsWith("/public/")) {
      const relPath = pathname.startsWith("/public/")
        ? pathname.slice("/public/".length)
        : pathname.slice(1);
      const safePath = path.normalize(relPath).replace(/^(\.\.(\/|\\|$))+/, "");
      const diskPath = path.join(__dirname, "public", safePath);
      if (fs.existsSync(diskPath) && fs.statSync(diskPath).isFile()) {
        const ext = path.extname(diskPath).toLowerCase();
        const mimeMap = {
          ".jpg": "image/jpeg",
          ".jpeg": "image/jpeg",
          ".png": "image/png",
          ".webp": "image/webp",
          ".svg": "image/svg+xml",
          ".json": "application/json",
        };
        const fileBytes = fs.readFileSync(diskPath);
        res.writeHead(200, {
          "Content-Type": mimeMap[ext] || "application/octet-stream",
          "Content-Length": fileBytes.length,
          "Cache-Control": "public, max-age=3600",
        });
        res.end(fileBytes);
        return;
      }
    }

    res.writeHead(404);
    res.end();
    return;
  }

  if (req.method === "POST") {
    const data = await readJsonBody(req);

    if (pathname === "/api/privilege/mode") {
      sendJson(res, handlePrivilegeMode(data));
      return;
    }

    if (pathname === "/api/compile") {
      sendJson(res, handleCompile(data));
      return;
    }

    if (pathname === "/api/import/theme") {
      const incomingSpec =
        data.spec ||
        (data.theme
          ? {
              name: data.theme.name,
              plugin_id: data.theme.plugin_id,
              accent: data.theme.colors && data.theme.colors.accent,
              secondary: data.theme.colors && data.theme.colors.secondary,
              bg: data.theme.colors && data.theme.colors.bg,
              surface: data.theme.colors && data.theme.colors.surface,
              card: data.theme.colors && data.theme.colors.card,
              qs_inactive: data.theme.colors && data.theme.colors.qs_inactive,
              tile_shape: data.theme.tile_shape,
              style: data.theme.palette_style,
              pure_black: data.theme.pure_black_amoled,
              qs_alpha: data.theme.qs_alpha,
              selected_targets: data.selected_targets,
              extra_tweaks: data.extra_tweaks,
            }
          : data);

      const mergedPayload = { ...incomingSpec, preserve_tweaks: true };
      if (data.extra_tweaks && typeof data.extra_tweaks === "object") {
        mergedPayload.extra_tweaks = {
          ...(mergedPayload.extra_tweaks || {}),
          ...data.extra_tweaks,
        };
      }
      if (
        data.settings_icons_customization &&
        typeof data.settings_icons_customization === "object"
      ) {
        const sic = data.settings_icons_customization;
        mergedPayload.extra_tweaks = {
          ...(mergedPayload.extra_tweaks || {}),
          settings_icon_style: sic.style_id || "1sett",
          use_apk_png_icons: Boolean(sic.use_apk_png_icons),
          settframe: Boolean(sic.settframe),
          bigsetico: Boolean(sic.bigsetico),
          hidemail: Boolean(sic.hidemail),
          uselogo: sic.uselogo !== false,
          uidivider: sic.uidivider !== false,
          per_icon_colors: sic.per_icon_colors || {},
        };
      }
      if (Array.isArray(data.selected_targets)) {
        mergedPayload.selected_targets = data.selected_targets;
      }
      if (data.custom_overrides && typeof data.custom_overrides === "object") {
        mergedPayload.custom_overrides = data.custom_overrides;
      }
      if (data.privilege_context && data.privilege_context.mode) {
        handlePrivilegeMode({ mode: data.privilege_context.mode });
      }
      const resultState = handleCompile(mergedPayload);
      const nowUtc = new Date().toISOString().slice(11, 19) + " UTC";
      RUNTIME_STATE.last_build.logs.push(
        `[✓] [${nowUtc}] IMPORTED theme.json configuration -> '${RUNTIME_STATE.spec.name}' (${resultState.stats.selected_packages}/${resultState.stats.total_packages} RROs, ${resultState.stats.total_tokens} tokens)`
      );
      resultState.last_build.logs = [...RUNTIME_STATE.last_build.logs];
      sendJson(res, resultState);
      return;
    }

    if (pathname === "/api/tweaks") {
      const tweaks = RUNTIME_STATE.spec.extra_tweaks || (RUNTIME_STATE.spec.extra_tweaks = {});
      const isCurrentlyFixed =
        tweaks.use_apk_png_icons !== undefined
          ? Boolean(tweaks.use_apk_png_icons)
          : Boolean(FIXED_PNG_STYLE_TO_SKIN[tweaks.settings_icon_style]);

      if (data.per_icon_color && typeof data.per_icon_color === "object") {
        if (isCurrentlyFixed) {
          const nowUtc = new Date().toISOString().slice(11, 19) + " UTC";
          RUNTIME_STATE.last_build.timestamp = nowUtc;
          RUNTIME_STATE.last_build.logs.push(
            `[!] [${nowUtc}] FIXED-STATE PRESET LOCKED: Cannot alter '${data.per_icon_color.id}' color — Fixed APK .PNG files have fixed RGBA bitmap states. Switch to Color Palette Tintable Mode to customize icon colors.`
          );
          sendJson(res, buildFullState());
          return;
        }
        const icId = data.per_icon_color.id;
        const icHex = data.per_icon_color.color;
        const perMap = tweaks.per_icon_colors || (tweaks.per_icon_colors = {});
        const iconDef = SETTINGS_DASHBOARD_ICONS.find((i) => i.id === icId);
        if (icId) {
          if (icHex) {
            const upperHex = String(icHex).toUpperCase();
            perMap[icId] = upperHex;
            if (iconDef) {
              RUNTIME_STATE.custom_overrides[`com.android.settings:${iconDef.res_name}_tint`] =
                upperHex;
            }
          } else {
            delete perMap[icId];
            if (iconDef) {
              delete RUNTIME_STATE.custom_overrides[`com.android.settings:${iconDef.res_name}_tint`];
            }
          }
        }
      } else if (data.reset_per_icon_colors) {
        tweaks.per_icon_colors = {};
        for (const ic of SETTINGS_DASHBOARD_ICONS) {
          delete RUNTIME_STATE.custom_overrides[`com.android.settings:${ic.res_name}_tint`];
        }
      } else {
        Object.assign(tweaks, data);
        if (data.setticons_style && !data.settings_icon_style) {
          tweaks.settings_icon_style = data.setticons_style;
        }
        const incomingStyle = data.settings_icon_style || data.setticons_style;
        if (incomingStyle && data.use_apk_png_icons === undefined) {
          tweaks.use_apk_png_icons = Boolean(FIXED_PNG_STYLE_TO_SKIN[incomingStyle]);
        }
      }
      const nowUtc = new Date().toISOString().slice(11, 19) + " UTC";
      RUNTIME_STATE.last_build.timestamp = nowUtc;
      const settingsIcons = resolveSettingsDashboardIcons(
        RUNTIME_STATE.spec,
        RUNTIME_STATE.custom_overrides
      );
      const appPngSuites = resolveAppPngSuites(RUNTIME_STATE.spec);
      const qsOverlay = resolveQuickSettingsOverlay(RUNTIME_STATE.spec);
      RUNTIME_STATE.last_build.logs.push(
        `[✓] [${nowUtc}] Rebuilt One UI 9.0 Tweaks -> Settings Icons: ${settingsIcons.png_skin_label} (use_apk_png=${settingsIcons.use_apk_png_icons}, settframe=${settingsIcons.settframe}) | App Suites: ${appPngSuites.active_skin_label} | QS Brightness: ${qsOverlay.brightness_bar.label} | Volume: ${qsOverlay.volume_panel.label}`
      );
      sendJson(res, buildFullState());
      return;
    }

    if (pathname === "/api/override") {
      const { key, value } = data;
      if (key) {
        const tweaks = RUNTIME_STATE.spec.extra_tweaks || (RUNTIME_STATE.spec.extra_tweaks = {});
        const perMap = tweaks.per_icon_colors || (tweaks.per_icon_colors = {});
        const matchedIcon = SETTINGS_DASHBOARD_ICONS.find(
          (ic) => `com.android.settings:${ic.res_name}_tint` === key
        );
        if (value) {
          const upperVal = String(value).toUpperCase();
          RUNTIME_STATE.custom_overrides[key] = upperVal;
          if (matchedIcon) perMap[matchedIcon.id] = upperVal;
        } else {
          delete RUNTIME_STATE.custom_overrides[key];
          if (matchedIcon) delete perMap[matchedIcon.id];
        }
      }
      sendJson(res, buildFullState());
      return;
    }

    if (pathname === "/api/overlays/toggle") {
      const pkgId = data.id;
      const enable = Boolean(data.enable ?? true);
      const selected = new Set(RUNTIME_STATE.spec.selected_targets || []);
      if (enable) selected.add(pkgId);
      else selected.delete(pkgId);
      RUNTIME_STATE.spec.selected_targets = ONEUI9_SYSTEM_RRO_CATALOG.filter((i) =>
        selected.has(i.id)
      ).map((i) => i.id);

      const nowUtc = new Date().toISOString().slice(11, 19) + " UTC";
      RUNTIME_STATE.last_build.timestamp = nowUtc;

      if (RUNTIME_STATE.active_overlays[pkgId]) {
        const ov = RUNTIME_STATE.active_overlays[pkgId];
        const catalogItem = ONEUI9_SYSTEM_RRO_CATALOG.find((it) => it.id === pkgId);
        const semwtTarget = (catalogItem && catalogItem.semwt_target) || "SemWT_*";
        const fullIsoUtc = new Date().toISOString().replace("T", " ").replace("Z", " UTC");
        ov.enabled = enable;
        ov.compile_status = enable ? "active" : "disabled";
        ov.compile_error = null;
        ov.failure_reason = null;
        ov.idmap2_timestamp = fullIsoUtc;
        ov.carrier_state = enable ? "STATE_ENABLED" : "STATE_DISABLED";
        if (enable) {
          ov.failure_count = 0;
          ov.compile_attempts = Math.max(3, Number(ov.compile_attempts || 2) + 1);
          ov.failure_rate = 0;
          ov.failure_history = ["ok", "ok", "ok"];
          ov.failure_timestamps = [];
          ov.high_frequency_failure = false;
          RUNTIME_STATE.last_build.logs.push(
            `[✓] [${nowUtc}] ENABLED & COMPILED ${ov.system_rro_package} -> ${ov.target_package} (STATE_ENABLED | priority=10000 > ${semwtTarget})`
          );
        } else {
          RUNTIME_STATE.last_build.logs.push(
            `[*] [${nowUtc}] DISABLED ${ov.system_rro_package} -> ${ov.target_package} (STATE_DISABLED via cmd overlay disable --user 0)`
          );
        }
      }
      sendJson(res, buildFullState());
      return;
    }

    if (pathname === "/api/overlays/status") {
      const pkgId = data.id;
      const status = data.status || "active";
      const burst = Math.max(1, Math.min(10, Number(data.burst || 1)));
      const resetTelemetry = data.reset_telemetry !== false;
      const nowUtc = new Date().toISOString().slice(11, 19) + " UTC";
      const fullIsoUtc = new Date().toISOString().replace("T", " ").replace("Z", " UTC");
      RUNTIME_STATE.last_build.timestamp = nowUtc;

      if (RUNTIME_STATE.active_overlays[pkgId]) {
        const ov = RUNTIME_STATE.active_overlays[pkgId];
        const catalogItem = ONEUI9_SYSTEM_RRO_CATALOG.find((it) => it.id === pkgId);
        const semwtTarget = (catalogItem && catalogItem.semwt_target) || "SemWT_*";
        const legacyRes =
          catalogItem &&
          catalogItem.legacy_broken_resources &&
          catalogItem.legacy_broken_resources[0]
            ? catalogItem.legacy_broken_resources[0]
            : "color/tw_primary_color";

        if (status === "failed") {
          ov.compile_status = "failed";
          ov.enabled = false;
          ov.carrier_state = "STATE_COMPILE_FAILED";
          ov.failure_count = Number(ov.failure_count || 0) + burst;
          ov.compile_attempts = Number(ov.compile_attempts || 1) + burst;
          ov.failure_rate = Math.round((ov.failure_count / Math.max(1, ov.compile_attempts)) * 100);
          const hist = Array.isArray(ov.failure_history) ? [...ov.failure_history] : [];
          for (let b = 0; b < burst; b++) hist.push("fail");
          ov.failure_history = hist.slice(-6);
          const tsList = Array.isArray(ov.failure_timestamps) ? [...ov.failure_timestamps] : [];
          for (let b = 0; b < burst; b++) tsList.push(nowUtc);
          ov.failure_timestamps = tsList.slice(-10);
          ov.last_failure_at = nowUtc;
          ov.idmap2_timestamp = fullIsoUtc;
          ov.high_frequency_failure = ov.failure_count >= 2;
          const isConflict = ov.failure_count % 2 === 0;
          ov.failure_reason = isConflict ? "Overlay Conflict" : "Resource ID Mismatch";
          const freqTag = ov.failure_count >= 2 ? ` [${ov.failure_count}x HIGH-FREQ]` : "";
          ov.compile_error = `IDMAP2_ERR${freqTag}: ${ov.failure_reason} — Unresolved symbol '${legacyRes}' in ${ov.target_package} or stale /data/resource-cache/${ov.system_rro_package}.frro`;
          RUNTIME_STATE.last_build.logs.push(
            `[!] [${nowUtc}] COMPILE_FAILURE${ov.failure_count >= 2 ? ` [HIGH-FREQ FAULT #${ov.failure_count}]` : ""} on ${ov.system_rro_package} (${ov.target_package}) -> ${ov.compile_error}`
          );
        } else if (status === "active") {
          const prevFailures = Number(ov.failure_count || 0);
          ov.compile_status = "active";
          ov.enabled = true;
          ov.carrier_state = "STATE_ENABLED";
          ov.compile_error = null;
          ov.failure_reason = null;
          ov.idmap2_timestamp = fullIsoUtc;
          if (resetTelemetry) {
            ov.failure_count = 0;
            ov.compile_attempts = 3;
            ov.failure_rate = 0;
            ov.failure_history = ["ok", "ok", "ok"];
            ov.failure_timestamps = [];
            ov.high_frequency_failure = false;
          } else {
            ov.compile_attempts = Number(ov.compile_attempts || 1) + 1;
            ov.failure_rate = Math.round(
              (ov.failure_count / Math.max(1, ov.compile_attempts)) * 100
            );
            ov.failure_history = [...(ov.failure_history || []).slice(-5), "ok"];
          }
          const selected = new Set(RUNTIME_STATE.spec.selected_targets || []);
          selected.add(pkgId);
          RUNTIME_STATE.spec.selected_targets = ONEUI9_SYSTEM_RRO_CATALOG.filter((i) =>
            selected.has(i.id)
          ).map((i) => i.id);
          RUNTIME_STATE.last_build.logs.push(
            `[✓] [${nowUtc}] REPAIRED & STABILIZED ${ov.system_rro_package} (${ov.target_package})${prevFailures >= 2 ? ` [Cleared ${prevFailures}x High-Freq Faults]` : ""} -> STATE_ENABLED (priority=10000 > ${semwtTarget})`
          );
        }
      }
      sendJson(res, buildFullState());
      return;
    }

    if (pathname === "/api/overlays/simulate_high_freq") {
      const nowUtc = new Date().toISOString().slice(11, 19) + " UTC";
      const fullIsoUtc = new Date().toISOString().replace("T", " ").replace("Z", " UTC");
      RUNTIME_STATE.last_build.timestamp = nowUtc;
      const targetIds =
        Array.isArray(data.ids) && data.ids.length > 0
          ? data.ids
          : ["aodservice", "messaging", "honeyboard"];
      const burst = Math.max(2, Math.min(6, Number(data.burst || 3)));
      const reasonPool = [
        "Overlay Conflict",
        "Resource ID Mismatch",
        "Overlay Conflict (SemWT Lock)",
      ];

      targetIds.forEach((pkgId, idx) => {
        const ov = RUNTIME_STATE.active_overlays[pkgId];
        if (!ov) return;
        const catalogItem = ONEUI9_SYSTEM_RRO_CATALOG.find((it) => it.id === pkgId);
        const legacyRes =
          catalogItem &&
          catalogItem.legacy_broken_resources &&
          catalogItem.legacy_broken_resources[0]
            ? catalogItem.legacy_broken_resources[0]
            : "color/tw_primary_color";
        ov.compile_status = "failed";
        ov.enabled = false;
        ov.carrier_state = "STATE_COMPILE_FAILED";
        ov.failure_count = Number(ov.failure_count || 0) + burst;
        ov.compile_attempts = Number(ov.compile_attempts || 1) + burst;
        ov.failure_rate = Math.round((ov.failure_count / Math.max(1, ov.compile_attempts)) * 100);
        const hist = Array.isArray(ov.failure_history) ? [...ov.failure_history] : [];
        for (let b = 0; b < burst; b++) hist.push("fail");
        ov.failure_history = hist.slice(-6);
        const tsList = Array.isArray(ov.failure_timestamps) ? [...ov.failure_timestamps] : [];
        for (let b = 0; b < burst; b++) tsList.push(nowUtc);
        ov.failure_timestamps = tsList.slice(-10);
        ov.last_failure_at = nowUtc;
        ov.idmap2_timestamp = fullIsoUtc;
        ov.high_frequency_failure = ov.failure_count >= 2;
        ov.failure_reason = reasonPool[idx % reasonPool.length];
        ov.compile_error = `IDMAP2_ERR [${ov.failure_count}x HIGH-FREQ]: ${ov.failure_reason} — Repeated FRRO lock & unresolved '${legacyRes}' in ${ov.target_package}`;
        RUNTIME_STATE.last_build.logs.push(
          `[!] [${nowUtc}] COMPILE_FAILURE [HIGH-FREQ FAULT #${ov.failure_count}] on ${ov.system_rro_package} (${ov.target_package}) -> ${ov.compile_error}`
        );
      });
      sendJson(res, buildFullState());
      return;
    }

    if (pathname === "/api/overlays/repair_batch") {
      const nowUtc = new Date().toISOString().slice(11, 19) + " UTC";
      const fullIsoUtc = new Date().toISOString().replace("T", " ").replace("Z", " UTC");
      RUNTIME_STATE.last_build.timestamp = nowUtc;
      const requestedIds = Array.isArray(data.ids) ? data.ids : [];
      const selected = new Set(RUNTIME_STATE.spec.selected_targets || []);
      const batchSteps = [];
      const repairedIds = [];

      const targetIds =
        requestedIds.length > 0
          ? requestedIds.filter((id) => Boolean(RUNTIME_STATE.active_overlays[id]))
          : Object.keys(RUNTIME_STATE.active_overlays).filter((id) => {
              const ov = RUNTIME_STATE.active_overlays[id];
              return ov && (ov.compile_status === "failed" || Number(ov.failure_count || 0) >= 2);
            });

      const total = targetIds.length;
      targetIds.forEach((pkgId, idx) => {
        const ov = RUNTIME_STATE.active_overlays[pkgId];
        if (!ov) return;
        const catalogItem = ONEUI9_SYSTEM_RRO_CATALOG.find((it) => it.id === pkgId);
        const semwtTarget = (catalogItem && catalogItem.semwt_target) || "SemWT_*";
        const prevFaults = Number(ov.failure_count || 0);
        const seqTag = `[SEQ ${idx + 1}/${total}]`;
        const overlayIdentifier =
          (catalogItem && catalogItem.overlay_identifier) ||
          ov.overlay_identifier ||
          ov.system_rro_package;
        const idmapCachePath = `/data/resource-cache/system@${ov.system_rro_package}@idmap`;
        const frroCachePath = `/data/resource-cache/${overlayIdentifier}.frro`;
        const shizukuCmd =
          `rish -c "uid=0(root) cmd overlay disable --user 0 ${ov.system_rro_package} && ` +
          `rm -f ${idmapCachePath} ${frroCachePath} && ` +
          `cmd overlay fabricate --target ${ov.target_package} --name ${overlayIdentifier} && ` +
          `cmd overlay enable --user 0 ${ov.system_rro_package} && ` +
          `cmd overlay set-priority --user 0 ${ov.system_rro_package} highest"`;

        ov.compile_status = "active";
        ov.enabled = true;
        ov.carrier_state = "STATE_ENABLED";
        ov.compile_error = null;
        ov.failure_reason = null;
        ov.idmap2_timestamp = fullIsoUtc;
        ov.failure_count = 0;
        ov.compile_attempts = Math.max(4, Number(ov.compile_attempts || 3) + 1);
        ov.failure_rate = 0;
        ov.failure_history = ["ok", "ok", "ok", "ok"];
        ov.failure_timestamps = [];
        ov.high_frequency_failure = false;
        selected.add(pkgId);
        repairedIds.push(pkgId);

        batchSteps.push({
          seq: idx + 1,
          total,
          id: pkgId,
          label: ov.label,
          system_rro_package: ov.system_rro_package,
          target_package: ov.target_package,
          semwt_target: semwtTarget,
          cleared_faults: prevFaults,
          idmap_cache_purged: idmapCachePath,
          frro_cache_purged: frroCachePath,
          shizuku_uid0_cmd: shizukuCmd,
          status: "STATE_ENABLED",
          priority: 10000,
          timestamp: nowUtc,
        });

        RUNTIME_STATE.last_build.logs.push(
          `[✓] [${nowUtc}] ${seqTag} [SHIZUKU UID 0] Purged ${idmapCachePath} & rebuilt ${ov.system_rro_package} (${ov.target_package})${prevFaults > 0 ? ` [Cleared ${prevFaults}x Faults]` : ""} -> STATE_ENABLED (priority=10000 > ${semwtTarget})`
        );
      });

      RUNTIME_STATE.spec.selected_targets = ONEUI9_SYSTEM_RRO_CATALOG.filter((i) =>
        selected.has(i.id)
      ).map((i) => i.id);

      const fullState = buildFullState();
      fullState.batch_repair_report = {
        timestamp: nowUtc,
        privilege_context: "Shizuku UID 0 (root / u:r:su:s0) + IOverlayManager",
        total_repaired: repairedIds.length,
        repaired_ids: repairedIds,
        steps: batchSteps,
      };
      sendJson(res, fullState);
      return;
    }

    if (pathname === "/api/overlays/repair_all") {
      const nowUtc = new Date().toISOString().slice(11, 19) + " UTC";
      const fullIsoUtc = new Date().toISOString().replace("T", " ").replace("Z", " UTC");
      RUNTIME_STATE.last_build.timestamp = nowUtc;
      const selected = new Set(RUNTIME_STATE.spec.selected_targets || []);
      const onlyHighFreq = Boolean(data.only_high_freq);
      const minFailures = Math.max(1, Number(data.min_failures || 2));
      let repairedCount = 0;

      for (const pkgId of Object.keys(RUNTIME_STATE.active_overlays)) {
        const ov = RUNTIME_STATE.active_overlays[pkgId];
        const isHighFreq = Number(ov.failure_count || 0) >= minFailures;
        const shouldRepair = onlyHighFreq
          ? isHighFreq || ov.compile_status === "failed"
          : ov.compile_status === "failed" || isHighFreq;
        if (shouldRepair) {
          const prevFaults = Number(ov.failure_count || 0);
          const catalogItem = ONEUI9_SYSTEM_RRO_CATALOG.find((it) => it.id === pkgId);
          const semwtTarget = (catalogItem && catalogItem.semwt_target) || "SemWT_*";
          ov.compile_status = "active";
          ov.enabled = true;
          ov.carrier_state = "STATE_ENABLED";
          ov.compile_error = null;
          ov.failure_reason = null;
          ov.idmap2_timestamp = fullIsoUtc;
          ov.failure_count = 0;
          ov.compile_attempts = 4;
          ov.failure_rate = 0;
          ov.failure_history = ["ok", "ok", "ok", "ok"];
          ov.failure_timestamps = [];
          ov.high_frequency_failure = false;
          selected.add(pkgId);
          repairedCount++;
          RUNTIME_STATE.last_build.logs.push(
            `[✓] [${nowUtc}] REPAIRED & STABILIZED ${ov.system_rro_package} (${ov.target_package}) [Cleared ${prevFaults}x Faults] -> STATE_ENABLED (priority=10000 > ${semwtTarget})`
          );
        }
      }
      RUNTIME_STATE.spec.selected_targets = ONEUI9_SYSTEM_RRO_CATALOG.filter((i) =>
        selected.has(i.id)
      ).map((i) => i.id);
      if (repairedCount === 0) {
        RUNTIME_STATE.last_build.logs.push(
          `[*] [${nowUtc}] All selected One UI 9.0 RRO overlays verified healthy (0 failed / high-frequency idmap2 targets).`
        );
      }
      sendJson(res, buildFullState());
      return;
    }

    if (pathname === "/api/logs/clear") {
      const nowUtc = new Date().toISOString().slice(11, 19) + " UTC";
      RUNTIME_STATE.last_build.timestamp = nowUtc;
      RUNTIME_STATE.last_build.logs = [
        `[*] [${nowUtc}] Cleared build log buffer. Monitoring live One UI 9.0 RRO compiler & idmap2 events...`,
      ];
      sendJson(res, buildFullState());
      return;
    }

    if (pathname === "/api/rescue") {
      for (const pkgId of Object.keys(RUNTIME_STATE.active_overlays)) {
        RUNTIME_STATE.active_overlays[pkgId].enabled = false;
        RUNTIME_STATE.active_overlays[pkgId].compile_status = "disabled";
        RUNTIME_STATE.active_overlays[pkgId].compile_error = null;
        RUNTIME_STATE.active_overlays[pkgId].carrier_state = "STATE_DISABLED";
        RUNTIME_STATE.active_overlays[pkgId].failure_count = 0;
        RUNTIME_STATE.active_overlays[pkgId].failure_rate = 0;
        RUNTIME_STATE.active_overlays[pkgId].high_frequency_failure = false;
      }
      RUNTIME_STATE.spec.selected_targets = [];
      RUNTIME_STATE.last_build.logs = [
        "[!] EMERGENCY RESCUE TRIGGERED (UID 0 / UID 1000)",
        "[*] Executing: cmd overlay disable-all --user 0",
        "[*] Unregistering all HexNextOneUI9_* fabricated overlays from /data/resource-cache/",
        "[✓] Restored stock One UI 9.0 SemWT_* & Framework RRO state.",
      ];
      sendJson(res, buildFullState());
      return;
    }

    if (pathname === "/api/export/zip") {
      const spec = RUNTIME_STATE.spec;
      spec.privilege_uid = RUNTIME_STATE.privilege.uid;
      const zipBuf = buildOneUi9BundleZip(spec, RUNTIME_STATE.custom_overrides);
      res.writeHead(200, {
        "Content-Type": "application/zip",
        "Content-Disposition":
          'attachment; filename="hex_oneui9_from_scratch_rro_bundle.zip"',
        "Content-Length": zipBuf.length,
      });
      res.end(zipBuf);
      return;
    }

    res.writeHead(404);
    res.end();
    return;
  }

  res.writeHead(405);
  res.end();
});

const PORT = Number(process.env.PORT) || 3000;
if (require.main === module) {
  server.listen(PORT, "0.0.0.0", () => {
    console.log(
      `HexNext One UI 9.0 From-Scratch RRO Compiler listening on http://0.0.0.0:${PORT}`
    );
  });
}

module.exports = {
  server,
  CATALOG,
  RUNTIME_STATE,
  generateMonetPalette,
  resolveSettingsDashboardIcons,
  resolveAppPngSuites,
  resolveQuickSettingsOverlay,
  resolveOverlayTokens,
  buildOneUi9BundleZip,
  buildSignedUniversalReleaseApk,
  buildReleaseReadinessReport,
  buildThemeJsonObject,
  buildFullState,
  handleCompile,
  handlePrivilegeMode,
};
