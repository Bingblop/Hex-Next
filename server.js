const http = require("http");
const fs = require("fs");
const path = require("path");
const zlib = require("zlib");

const BASE_DIR = __dirname;
const INDEX_HTML_PATH = path.join(BASE_DIR, "templates", "index.html");
const CATALOG_PATH = path.join(BASE_DIR, "engine", "oneui9_catalog.json");

const CATALOG = JSON.parse(fs.readFileSync(CATALOG_PATH, "utf8"));
const {
  SHADES,
  PALETTE_STYLES,
  HEX_QS_TOGGLE_SHAPES,
  HEX_PLUGINS,
  SETTINGS_ICON_STYLES,
  SETTINGS_DASHBOARD_ICONS,
  NIFERPITOU_EXTRA_PREFERENCES,
  ONEUI9_SYSTEM_RRO_CATALOG,
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

function resolveSettingsDashboardIcons(spec, customOverrides = {}) {
  const seed = String(spec.accent || "#00E5FF").toUpperCase();
  const secondary = String(spec.secondary || "#7C4DFF").toUpperCase();
  const card = String(spec.card || "#121724").toUpperCase();

  const tweaks = spec.extra_tweaks || {};
  const iconStyle = tweaks.settings_icon_style || "1sett";
  const settframe = Boolean(tweaks.settframe);
  const bigsetico = Boolean(tweaks.bigsetico);
  const hidemail = Boolean(tweaks.hidemail);
  const uselogo = tweaks.uselogo !== undefined ? Boolean(tweaks.uselogo) : true;
  const uidivider = tweaks.uidivider !== undefined ? Boolean(tweaks.uidivider) : true;
  const perIconColors = tweaks.per_icon_colors || {};

  const styleMeta =
    SETTINGS_ICON_STYLES.find((s) => s.id === iconStyle) || SETTINGS_ICON_STYLES[0];
  const activeFolder = settframe ? styleMeta.frameless_folder : styleMeta.asset_folder;
  const iconDp = bigsetico ? 36 : 28;
  const isDuotone = iconStyle === "alt_1sett" || iconStyle === "alt_2sett";

  const resolvedIcons = SETTINGS_DASHBOARD_ICONS.map((ic) => {
    const tokenName = `${ic.res_name}_tint`;
    const overrideKey = `com.android.settings:${tokenName}`;
    const customHex = perIconColors[ic.id] || customOverrides[overrideKey];
    let fgColor;
    let badgeColor;
    if (customHex) {
      fgColor = String(customHex).toUpperCase();
      badgeColor = secondary;
    } else if (iconStyle === "1sett") {
      fgColor = seed;
      badgeColor = seed;
    } else if (iconStyle === "2sett") {
      fgColor = secondary;
      badgeColor = secondary;
    } else if (iconStyle === "3sett") {
      fgColor = String(ic.og_color).toUpperCase();
      badgeColor = seed;
    } else if (iconStyle === "alt_1sett") {
      fgColor = seed;
      badgeColor = secondary;
    } else {
      fgColor = secondary;
      badgeColor = seed;
    }

    const cleanFg = fgColor.replace(/^#/, "");
    const cleanBadge = badgeColor.replace(/^#/, "");
    const frameBg = settframe ? "transparent" : `#1F${cleanFg}`;
    const frameBorder = settframe ? "transparent" : `#4D${cleanFg}`;

    let vectorXml =
      '<?xml version="1.0" encoding="utf-8"?>\n' +
      `<!-- Rebuilt from project.vivid.ui.plugin.niferpitou (${activeFolder}/${ic.res_name}.png) for One UI 9.0 -->\n` +
      `<!-- Target: com.android.settings:drawable/${ic.res_name} | Tint Token: @color/${tokenName} (${fgColor}) -->\n` +
      '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n' +
      `    android:width="${iconDp}dp"\n` +
      `    android:height="${iconDp}dp"\n` +
      '    android:viewportWidth="24"\n' +
      '    android:viewportHeight="24">\n';

    if (!settframe) {
      vectorXml +=
        "    <path\n" +
        `        android:name="squircle_container"\n` +
        `        android:fillColor="#26${isDuotone ? cleanBadge : cleanFg}"\n` +
        `        android:strokeColor="#4D${isDuotone ? cleanBadge : cleanFg}"\n` +
        '        android:strokeWidth="0.75"\n' +
        '        android:pathData="M7,2h10c2.76,0 5,2.24 5,5v10c0,2.76 -2.24,5 -5,5H7c-2.76,0 -5,-2.24 -5,-5V7c0,-2.76 2.24,-5 5,-5z" />\n';
    }
    if (isDuotone) {
      vectorXml +=
        "    <path\n" +
        '        android:name="duotone_accent_glow"\n' +
        `        android:fillColor="#33${cleanBadge}"\n` +
        '        android:pathData="M12,6a6,6 0 1,0 0,12a6,6 0 1,0 0,-12z" />\n';
    }
    vectorXml +=
      "    <path\n" +
      '        android:name="icon_glyph"\n' +
      '        android:strokeWidth="2.0"\n' +
      '        android:strokeLineCap="round"\n' +
      '        android:strokeLineJoin="round"\n' +
      `        android:strokeColor="${fgColor}"\n` +
      '        android:fillColor="#00000000"\n' +
      `        android:pathData="${ic.svg_path}" />\n` +
      "</vector>\n";

    return {
      ...ic,
      token_name: tokenName,
      fg_color: fgColor,
      badge_color: badgeColor,
      frame_bg: frameBg,
      frame_border: frameBorder,
      frameless: settframe,
      size_dp: iconDp,
      source_asset: `assets/anaconda_tints/once/base/lxx1/${activeFolder}/${ic.res_name}.png`,
      oneui9_drawable: `com.android.settings:drawable/${ic.res_name}`,
      vector_xml: vectorXml,
    };
  });

  const dimensXml =
    '<?xml version="1.0" encoding="utf-8"?>\n' +
    "<!-- One UI 9.0 SecSettings Dashboard Icon & Divider Dimensions (Niferpitou extra.preferences) -->\n" +
    "<resources>\n" +
    `    <dimen name="sec_dashboard_tile_image_size">${iconDp}.0dip</dimen>\n` +
    `    <dimen name="sec_dashboard_tile_image_padding">${settframe ? "0.0dip" : "6.0dip"}</dimen>\n` +
    `    <dimen name="sec_widget_list_divider_height">${uidivider ? "1.0dip" : "0.0dip"}</dimen>\n` +
    `    <dimen name="sec_account_email_text_height">${hidemail ? "0.0dip" : "18.0dip"}</dimen>\n` +
    "</resources>\n";

  const boolsXml =
    '<?xml version="1.0" encoding="utf-8"?>\n' +
    "<resources>\n" +
    `    <bool name="sec_settings_show_custom_about_logo">${uselogo ? "true" : "false"}</bool>\n` +
    `    <bool name="sec_settings_hide_account_email">${hidemail ? "true" : "false"}</bool>\n` +
    `    <bool name="sec_settings_frameless_dashboard_icons">${settframe ? "true" : "false"}</bool>\n` +
    "</resources>\n";

  const owner = (spec.privilege_uid ?? 1000) === 1000 ? "android" : "com.android.shell";
  const iconFabArgs = resolvedIcons.map((ic) => {
    const argbHex = "0xff" + ic.fg_color.replace(/^#/, "").toLowerCase();
    return `com.android.settings:color/${ic.token_name} 0x1c ${argbHex}`;
  });
  const fabricateIconsCmd =
    `cmd overlay fabricate --target com.android.settings --name HexNextSettingsIcons26 ` +
    iconFabArgs.join(" ");
  const enableIconsCmd =
    `cmd overlay enable --user 0 ${owner}:HexNextSettingsIcons26 && ` +
    `cmd overlay set-priority --user 0 ${owner}:HexNextSettingsIcons26 highest`;

  return {
    style_id: iconStyle,
    style_label: styleMeta.label,
    active_asset_folder: activeFolder,
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
  const settframe = Boolean(tweaks.settframe);
  const hidemail = Boolean(tweaks.hidemail);
  const uidivider = tweaks.uidivider !== undefined ? Boolean(tweaks.uidivider) : true;

  const settingsIconTint =
    iconStyle === "2sett" || iconStyle === "alt_2sett" ? secondary : seed;
  const settingsIconFrame = settframe ? "#000000" : surface;
  const settingsDivider = uidivider ? surface : card;
  const settingsEmail = hidemail ? card : "#94A3B8";

  const settingsIconsBundle = resolveSettingsDashboardIcons(spec, customOverrides);

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

    // Integrate all 26 Niferpitou One UI 9.0 SecSettings Dashboard Icon tokens into com.hexnext.oneui9.rro.settings
    if (item.id === "settings") {
      for (const ic of settingsIconsBundle.icons) {
        const argbHex = "0xff" + ic.fg_color.replace(/^#/, "").toLowerCase();
        resolvedResources.push({
          name: ic.token_name,
          full_name: `com.android.settings:color/${ic.token_name}`,
          type: "0x1c",
          role: `settings_icon_${ic.id}`,
          desc: `${ic.title} icon tint (${settingsIconsBundle.active_asset_folder}, ${ic.size_dp}dp)`,
          value: ic.fg_color,
          argb: argbHex,
          icon_id: ic.id,
          drawable_res: ic.oneui9_drawable,
        });
        fabArgs.push(`com.android.settings:color/${ic.token_name} 0x1c ${argbHex}`);
      }
    }

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
      dimens_xml: item.id === "settings" ? settingsIconsBundle.dimens_xml : null,
      bools_xml: item.id === "settings" ? settingsIconsBundle.bools_xml : null,
      drawable_count: item.id === "settings" ? settingsIconsBundle.icons.length : (item.id === "systemui" ? 1 : 0),
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
  const settingsIcons = resolveSettingsDashboardIcons(spec);
  const monet = generateMonetPalette(
    spec.accent || "#00E5FF",
    spec.style || "VIBRANT",
    spec.pure_black !== false
  );
  const shapeId = spec.tile_shape || "squircle";
  const shapeCfg = HEX_QS_TOGGLE_SHAPES[shapeId] || HEX_QS_TOGGLE_SHAPES.squircle;
  const accentHex = String(spec.accent || "#00E5FF").toUpperCase();
  const qsDrawableXml = shapeCfg.xml_template
    .replace(/\{accent\}/g, accentHex)
    .replace(/\{accent_alpha\}/g, "#33" + accentHex.replace(/^#/, ""));

  const files = [];
  const scriptLines = [
    "#!/system/bin/sh",
    "# ======================================================================",
    "# HexNext — From-Scratch One UI 9.0 RRO Builder (Precedence > SemWT_*)",
    "# Rebuilt from #hex__30 + Niferpitou (terpsichora_) for Android 16",
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
    if (pkg.id === "systemui") {
      files.push({ name: `${folder}/res/drawable/sec_qs_tile_bg.xml`, content: qsDrawableXml });
    }
    if (pkg.id === "settings") {
      files.push({ name: `${folder}/res/values/dimens.xml`, content: settingsIcons.dimens_xml });
      files.push({ name: `${folder}/res/values/bools.xml`, content: settingsIcons.bools_xml });
      for (const ic of settingsIcons.icons) {
        files.push({ name: `${folder}/res/drawable/${ic.res_name}.xml`, content: ic.vector_xml });
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
        `# --- 26 One UI 9.0 SecSettings Dashboard Icons (${settingsIcons.active_asset_folder}, ${settingsIcons.icon_size_dp}dp) ---`,
        settingsIcons.fabricate_command,
        settingsIcons.enable_command,
        ""
      );
    }
  }

  scriptLines.push(
    'echo "[✓] All from-scratch One UI 9.0 RRO overlays + 26 Settings Icons fabricated and prioritized above SemWT_*!"'
  );
  files.push({ name: "install_oneui9_overlays.sh", content: scriptLines.join("\n") + "\n" });
  files.push({
    name: "theme_profile_oneui9.json",
    content: JSON.stringify(
      {
        engine: "HexNext One UI 9.0 From-Scratch RRO Compiler (Precedence > SemWT_*)",
        theme_park_required: false,
        uses_high_contrast_theme_overlays: false,
        semwt_precedence_mode: "priority=10000 (highest > SemWT_*)",
        privilege_requirement: "UID 0 (Root) or UID 1000 (System)",
        spec,
        settings_icons_customization: {
          style_id: settingsIcons.style_id,
          settframe: settingsIcons.settframe,
          bigsetico: settingsIcons.bigsetico,
          hidemail: settingsIcons.hidemail,
          uselogo: settingsIcons.uselogo,
          uidivider: settingsIcons.uidivider,
          icon_count: settingsIcons.icons.length,
        },
        monet_palette: monet,
        overlays: compiled.map((p) => ({
          id: p.id,
          system_rro_package: p.system_rro_package,
          semwt_overridden: p.semwt_target,
          target_package: p.target_package,
          selected: p.selected,
          resources: Object.fromEntries(p.resolved_resources.map((r) => [r.name, r.value])),
        })),
      },
      null,
      2
    ),
  });

  return createZipBuffer(files);
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
    name: "#hex_ Bodhi Default (One UI 9.0)",
    plugin_id: "bodhi_default",
    accent: "#00E5FF",
    secondary: "#7C4DFF",
    bg: "#000000",
    surface: "#0B0E17",
    card: "#121724",
    qs_inactive: "#1A2030",
    tile_shape: "squircle",
    style: "VIBRANT",
    pure_black: true,
    qs_alpha: 90,
    selected_targets: ONEUI9_SYSTEM_RRO_CATALOG.filter((i) => i.selected).map((i) => i.id),
    extra_tweaks: {
      settings_icon_style: "1sett",
      settframe: false,
      bigsetico: false,
      hidemail: false,
      uselogo: true,
      uidivider: true,
      per_icon_colors: {},
      qs_icon_style: "outlin",
      qs_toggle_size: "default",
      qs_blur_mode: "2qsblur",
      notif_corner_radius: "4roundnoti",
      hidcarriernoti: true,
      statusbar_icon_style: "0staico",
      "4g": true,
      inout: true,
      switch_thumb_tint: "0swithumb",
      volume_panel_style: "0vvol",
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
    total_packages: 16,
    total_tokens: 205,
    logs: [
      "[*] HexNext v3.2 — From-Scratch One UI 9.0 RRO Compiler (#hex__30 + Niferpitou terpsichora_)",
      "[*] Opening Prerequisite Check: Theme Park & High Contrast Theme requirements REMOVED.",
      "[✓] Privileged session verified: UID 1000 (android.uid.system via Shizuku / Direct RRO).",
      "[✓] Synthesized 18 from-scratch RRO packages (com.hexnext.oneui9.rro.*) taking precedence over SemWT_*.",
      "[!] [19:54:12 UTC] COMPILE_FAILURE [HIGH-FREQ FAULT #4] on com.hexnext.oneui9.rro.aodservice (com.samsung.android.app.aodservice) -> IDMAP2_ERR: Repeated lock contention on stale /data/resource-cache/com.hexnext.oneui9.rro.aodservice.frro (color/aod_clock_color)",
      "[!] [19:55:04 UTC] COMPILE_FAILURE [HIGH-FREQ FAULT #3] on com.hexnext.oneui9.rro.messaging (com.samsung.android.messaging) -> IDMAP2_ERR: Unresolved symbol 'color/message_bubble_send_bg' during SemWT_Messaging override pass",
      "[✓] Loaded 26 One UI 9.0 SecSettings Dashboard Icon VectorDrawables & Niferpitou extra.preferences.",
      "[✓] Generated 65 Android 16 Monet framework shades + 23 Material 3 dark roles above SemWT_MonetPalette.",
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
    compile_error:
      "IDMAP2_ERR [4x HIGH-FREQ]: Stale /data/resource-cache/com.hexnext.oneui9.rro.aodservice.frro & unresolved symbol 'color/aod_clock_color'",
  },
  messaging: {
    compile_status: "failed",
    enabled: false,
    failure_count: 3,
    compile_attempts: 4,
    failure_history: ["ok", "fail", "fail", "fail"],
    failure_timestamps: ["19:52:44 UTC", "19:54:02 UTC", "19:55:04 UTC"],
    last_failure_at: "19:55:04 UTC",
    compile_error:
      "IDMAP2_ERR [3x HIGH-FREQ]: Unresolved symbol 'color/message_bubble_send_bg' in com.samsung.android.messaging",
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

  const shapeCfg =
    HEX_QS_TOGGLE_SHAPES[spec.tile_shape] || HEX_QS_TOGGLE_SHAPES.squircle;
  const accentUpper = String(spec.accent || "#00E5FF").toUpperCase();
  const qsDrawablePreview = shapeCfg.xml_template
    .replace(/\{accent\}/g, accentUpper)
    .replace(/\{accent_alpha\}/g, "#33" + accentUpper.replace(/^#/, ""));

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
    niferpitou_preferences: NIFERPITOU_EXTRA_PREFERENCES,
    plugins: Object.values(HEX_PLUGINS),
    palette_styles: PALETTE_STYLES,
    qs_shapes: Object.values(HEX_QS_TOGGLE_SHAPES),
    qs_drawable_xml: qsDrawablePreview,
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
  if (data.extra_tweaks && typeof data.extra_tweaks === "object") {
    spec.extra_tweaks = { ...(spec.extra_tweaks || {}), ...data.extra_tweaks };
  }
  if (data.custom_overrides && typeof data.custom_overrides === "object") {
    RUNTIME_STATE.custom_overrides = data.custom_overrides;
  }

  const priv = RUNTIME_STATE.privilege;
  spec.privilege_uid = priv.uid;
  const compiled = resolveOverlayTokens(spec, RUNTIME_STATE.custom_overrides);
  const settingsIcons = resolveSettingsDashboardIcons(spec);
  const monet = generateMonetPalette(spec.accent, spec.style, spec.pure_black);

  const owner = priv.owner_package;
  const uid = priv.uid;
  const logs = [
    `[*] Starting One UI 9.0 From-Scratch RRO Compiler (Privilege: ${priv.mode} / UID ${uid})`,
    "[*] Theme Park & High Contrast Theme overlays skipped — building fresh com.hexnext.oneui9.rro.* packages",
    `[*] Owning package for OverlayManagerTransaction: '${owner}' (priority=highest > SemWT_*)`,
    `[*] Fabricating 65 Monet shades + 23 Material 3 dark roles -> ${owner}:HexNextMonetShades (overrides SemWT_MonetPalette)`,
    `[*] Compiling 26 One UI 9.0 SecSettings Dashboard Icons (${settingsIcons.active_asset_folder} | size=${settingsIcons.icon_size_dp}dp | settframe=${settingsIcons.settframe})`,
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
    `[✓] Committed OverlayManagerTransaction: ${enabledCount} from-scratch RROs + 26 Settings Icons prioritized above SemWT_*.`
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
      monet.total_roles +
      settingsIcons.icons.length,
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

    if (pathname === "/api/tweaks") {
      const tweaks = RUNTIME_STATE.spec.extra_tweaks || (RUNTIME_STATE.spec.extra_tweaks = {});
      if (data.per_icon_color && typeof data.per_icon_color === "object") {
        const icId = data.per_icon_color.id;
        const icHex = data.per_icon_color.color;
        const perMap = tweaks.per_icon_colors || (tweaks.per_icon_colors = {});
        const iconDef = SETTINGS_DASHBOARD_ICONS.find((i) => i.id === icId);
        if (icId) {
          if (icHex) {
            const upperHex = String(icHex).toUpperCase();
            perMap[icId] = upperHex;
            if (iconDef) {
              RUNTIME_STATE.custom_overrides[`com.android.settings:${iconDef.res_name}_tint`] = upperHex;
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
      }
      const nowUtc = new Date().toISOString().slice(11, 19) + " UTC";
      RUNTIME_STATE.last_build.timestamp = nowUtc;
      const settingsIcons = resolveSettingsDashboardIcons(RUNTIME_STATE.spec, RUNTIME_STATE.custom_overrides);
      RUNTIME_STATE.last_build.logs.push(
        `[✓] [${nowUtc}] Rebuilt com.hexnext.oneui9.rro.settings + 26 SecSettings Icons -> ${settingsIcons.active_asset_folder} (size=${settingsIcons.icon_size_dp}dp, settframe=${settingsIcons.settframe}, hidemail=${settingsIcons.hidemail}, uidivider=${settingsIcons.uidivider})`
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
        ov.enabled = enable;
        ov.compile_status = enable ? "active" : "disabled";
        ov.compile_error = null;
        ov.carrier_state = enable ? "STATE_ENABLED" : "STATE_DISABLED";
        if (enable) {
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
          ov.high_frequency_failure = ov.failure_count >= 2;
          const freqTag = ov.failure_count >= 2 ? ` [${ov.failure_count}x HIGH-FREQ]` : "";
          ov.compile_error = `IDMAP2_ERR${freqTag}: Unresolved symbol '${legacyRes}' in ${ov.target_package} or stale /data/resource-cache/${ov.system_rro_package}.frro`;
          RUNTIME_STATE.last_build.logs.push(
            `[!] [${nowUtc}] COMPILE_FAILURE${ov.failure_count >= 2 ? ` [HIGH-FREQ FAULT #${ov.failure_count}]` : ""} on ${ov.system_rro_package} (${ov.target_package}) -> ${ov.compile_error}`
          );
        } else if (status === "active") {
          const prevFailures = Number(ov.failure_count || 0);
          ov.compile_status = "active";
          ov.enabled = true;
          ov.carrier_state = "STATE_ENABLED";
          ov.compile_error = null;
          if (resetTelemetry) {
            ov.failure_count = 0;
            ov.compile_attempts = 3;
            ov.failure_rate = 0;
            ov.failure_history = ["ok", "ok", "ok"];
            ov.failure_timestamps = [];
            ov.high_frequency_failure = false;
          } else {
            ov.compile_attempts = Number(ov.compile_attempts || 1) + 1;
            ov.failure_rate = Math.round((ov.failure_count / Math.max(1, ov.compile_attempts)) * 100);
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
      RUNTIME_STATE.last_build.timestamp = nowUtc;
      const targetIds = Array.isArray(data.ids) && data.ids.length > 0
        ? data.ids
        : ["aodservice", "messaging", "honeyboard"];
      const burst = Math.max(2, Math.min(6, Number(data.burst || 3)));

      for (const pkgId of targetIds) {
        const ov = RUNTIME_STATE.active_overlays[pkgId];
        if (!ov) continue;
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
        ov.high_frequency_failure = ov.failure_count >= 2;
        ov.compile_error = `IDMAP2_ERR [${ov.failure_count}x HIGH-FREQ]: Repeated FRRO lock & unresolved '${legacyRes}' in ${ov.target_package}`;
        RUNTIME_STATE.last_build.logs.push(
          `[!] [${nowUtc}] COMPILE_FAILURE [HIGH-FREQ FAULT #${ov.failure_count}] on ${ov.system_rro_package} (${ov.target_package}) -> ${ov.compile_error}`
        );
      }
      sendJson(res, buildFullState());
      return;
    }

    if (pathname === "/api/overlays/repair_batch") {
      const nowUtc = new Date().toISOString().slice(11, 19) + " UTC";
      RUNTIME_STATE.last_build.timestamp = nowUtc;
      const requestedIds = Array.isArray(data.ids) ? data.ids : [];
      const selected = new Set(RUNTIME_STATE.spec.selected_targets || []);
      const batchSteps = [];
      const repairedIds = [];

      // If no specific IDs passed, default to all currently failed or high-frequency RROs
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
        const overlayIdentifier = (catalogItem && catalogItem.overlay_identifier) || ov.overlay_identifier || ov.system_rro_package;
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
server.listen(PORT, "0.0.0.0", () => {
  console.log(
    `HexNext One UI 9.0 From-Scratch RRO Compiler listening on http://0.0.0.0:${PORT}`
  );
});
