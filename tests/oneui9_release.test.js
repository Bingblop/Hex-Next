'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const {
  CATALOG,
  DRIVE_APK_PNG_GALLERY,
  resolveSettingsDashboardIcons,
  resolveAppPngSuites,
  resolveQuickSettingsOverlay,
  verifyAllPluginsForOneUi9,
  buildThemeJsonObject,
  buildSignedUniversalReleaseApk,
  handleCompile,
  filterThemePluginsByName,
} = require('../server.js');

test('Theme Plugins search filters plugins by name (case-insensitive), id, and handles empty/non-matching queries', () => {
  const allPlugins = Object.values(CATALOG.HEX_PLUGINS);
  assert.equal(allPlugins.length, 13);

  // Empty query returns all 13 plugins
  assert.equal(filterThemePluginsByName(allPlugins, '').length, 13);
  assert.equal(filterThemePluginsByName(allPlugins, '   ').length, 13);

  // Case-insensitive search by plugin name
  const pxMatches = filterThemePluginsByName(allPlugins, 'project x');
  assert.equal(pxMatches.length, 1);
  assert.equal(pxMatches[0].id, 'project_x');

  const dayNightMatches = filterThemePluginsByName(allPlugins, 'Day/Night');
  assert.equal(dayNightMatches.length, 2);
  assert.deepEqual(dayNightMatches.map(p => p.id).sort(), ['blend_dn', 'glass_dn']);

  const mimiMatches = filterThemePluginsByName(allPlugins, 'MIMI');
  assert.equal(mimiMatches.length, 1);
  assert.equal(mimiMatches[0].id, 'mimi_miui11');

  // Non-matching query returns empty array
  assert.equal(filterThemePluginsByName(allPlugins, 'nonexistent_theme_plugin_xyz').length, 0);
});

test('Catalog contains all 5 Google Drive APKs + MIMI + Terpsichora for One UI 9.0 with valid logo URLs on disk', () => {
  const pluginIds = Object.keys(CATALOG.HEX_PLUGINS);
  const requiredPlugins = [
    'project_x',
    'blend_dn',
    'glass_dn',
    'ayos_dark',
    'aosp_r',
    'mimi_miui11',
    'niferpitou_terpsichora',
  ];
  for (const id of requiredPlugins) {
    assert.ok(pluginIds.includes(id), `Expected HEX_PLUGINS to include ${id}`);
  }
  for (const driveId of ['project_x', 'blend_dn', 'glass_dn', 'ayos_dark', 'aosp_r']) {
    const p = CATALOG.HEX_PLUGINS[driveId];
    assert.ok(p.logo_url && p.logo_url.startsWith('/assets/apk_previews/'), `Expected logo_url on ${driveId}`);
    const diskPath = path.join(__dirname, '..', 'public', p.logo_url.replace(/^\//, ''));
    assert.ok(fs.existsSync(diskPath), `Expected ${diskPath} to exist on disk`);
  }
});

test('One UI 9.0 Extensive Plugin Verification Suite passes all 104 checks across all 13 plugins and verifies all extracted APK .PNG files', () => {
  const report = verifyAllPluginsForOneUi9();
  assert.equal(report.all_passed, true);
  assert.equal(report.total_plugins, 13);
  assert.equal(report.passed_plugins, 13);
  assert.equal(report.total_checks, 104);
  assert.equal(report.passed_checks, 104);
  assert.equal(report.disk_assets.all_present, true);
  assert.ok(report.disk_assets.present >= 34);

  for (const pluginResult of report.results) {
    assert.equal(pluginResult.compatible_oneui9, true, `Plugin ${pluginResult.plugin_id} failed One UI 9.0 compatibility check`);
    assert.equal(pluginResult.passed_count, 8);
  }
});

test('Drive APK Fixed .PNG Gallery exposes all 28 interactive .PNG options across 5 categories with real extracted PNG assets on disk', () => {
  assert.equal(DRIVE_APK_PNG_GALLERY.length, 28);
  const categories = new Set(DRIVE_APK_PNG_GALLERY.map(i => i.category));
  for (const expectedCat of ['settings', 'qs', 'statusbar', 'comms', 'sysapps']) {
    assert.ok(categories.has(expectedCat), `Expected category ${expectedCat}`);
  }

  for (const item of DRIVE_APK_PNG_GALLERY) {
    assert.ok(item.id && item.title && item.plugin_id && item.preview_url);
    const assetDiskPath = path.join(__dirname, '..', 'public', item.preview_url.replace(/^\//, ''));
    assert.ok(fs.existsSync(assetDiskPath), `Expected gallery PNG ${assetDiskPath} to exist on disk`);
    const stat = fs.statSync(assetDiskPath);
    assert.ok(stat.size > 100, `Expected non-empty PNG file for ${item.id}`);
  }
});

test('Settings Dashboard Icons support both Fixed APK .PNGs (Framed & Frameless, zero inner fill, always filled) and Color Palette Vector mode', () => {
  assert.equal(CATALOG.SETTINGS_DASHBOARD_ICONS.length, 26);

  const pngModes = [
    { plugin: 'niferpitou_terpsichora', style: '3sett', expectedSkin: 'terpsichora' },
    { plugin: 'mimi_miui11', style: 'mimi_sett', expectedSkin: 'mimi' },
    { plugin: 'project_x', style: 'px_style1', expectedSkin: 'px' },
    { plugin: 'blend_dn', style: 'blend_frame', expectedSkin: 'blend' },
    { plugin: 'glass_dn', style: 'glass_frosted', expectedSkin: 'glass' },
    { plugin: 'ayos_dark', style: 'aosp_ayos', expectedSkin: 'aosp' },
    { plugin: 'aosp_r', style: 'aosp_ayos', expectedSkin: 'aosp' },
  ];

  for (const { plugin, style, expectedSkin } of pngModes) {
    for (const settframe of [true, false]) {
      const bundle = resolveSettingsDashboardIcons({
        plugin_id: plugin,
        accent: '#6366F1',
        secondary: '#EC4899',
        surface: '#0D0E12',
        card: '#171923',
        extra_tweaks: {
          settings_icon_style: style,
          settframe,
          use_apk_png_icons: true,
        },
      }, {});

      assert.equal(bundle.icons.length, 26);
      assert.equal(bundle.always_filled, true);
      assert.equal(bundle.no_frame_fill, true);
      assert.equal(bundle.use_apk_png_icons, true);

      for (const ic of bundle.icons) {
        assert.equal(ic.use_apk_png, true);
        assert.equal(ic.fixed_state, true);
        assert.equal(ic.always_filled, true);
        assert.equal(ic.no_frame_fill, true);
        assert.equal(ic.frame_bg, 'transparent', `Expected zero color between icon and frame for ${ic.id}`);
        assert.equal(ic.frameless, settframe);
        assert.equal(ic.png_skin, expectedSkin);
        assert.ok(ic.apk_png_uri && ic.apk_png_uri.startsWith('data:image/png;base64,'), `Expected valid PNG URI for ${ic.id} in ${expectedSkin}`);
      }
    }
  }

  // Test Color Palette Vector mode (when use_apk_png_icons is false)
  const vectorBundle = resolveSettingsDashboardIcons({
    plugin_id: 'project_x',
    accent: '#10B981',
    secondary: '#F43F5E',
    surface: '#0B0F19',
    card: '#111827',
    extra_tweaks: {
      settings_icon_style: '1sett',
      settframe: false,
      use_apk_png_icons: false,
      per_icon_colors: {
        connections: '#FF8800',
      },
    },
  }, {});

  assert.equal(vectorBundle.icons.length, 26);
  const conn = vectorBundle.icons.find(i => i.id === 'connections');
  assert.equal(conn.use_apk_png, false);
  assert.equal(conn.fixed_state, false);
  assert.equal(conn.fg_color, '#FF8800');
  assert.equal(conn.frame_bg, 'transparent');
  assert.equal(conn.always_filled, true);
});

test('8 Samsung System App Suites (38 drawables) resolve Fixed APK .PNGs (Framed & Frameless), Color Palette Vector mode, and Per-Suite Overrides', () => {
  const suitesBundle = resolveAppPngSuites({
    plugin_id: 'project_x',
    accent: '#00E5FF',
    secondary: '#FF2A85',
    extra_tweaks: {
      settings_icon_style: 'px_style1',
      settframe: false,
      use_apk_png_icons: true,
      app_png_pack_mode: 'auto_skin',
    },
  });

  assert.equal(suitesBundle.total_suites, 8);
  assert.equal(suitesBundle.total_drawables, 38);
  assert.equal(suitesBundle.active_skin, 'px');
  assert.equal(suitesBundle.vector_only, false);

  for (const suite of Object.values(suitesBundle.suites)) {
    assert.equal(suite.active_skin, 'px');
    assert.equal(suite.vector_only, false);
    for (const item of suite.items) {
      assert.equal(item.use_png, true);
      assert.equal(item.always_filled, true);
      assert.equal(item.no_frame_fill, true);
      assert.equal(item.frame_bg, 'transparent');
      assert.ok(item.active_png_uri.startsWith('data:image/png;base64,'));
      assert.ok(item.filled_svg_path && item.filled_svg_path.length > 10);
    }
  }

  // Test Per-Suite individual mode override (e.g., My Files in Vector mode, Power Menu in Glass .PNG mode)
  const mixedSuitesBundle = resolveAppPngSuites({
    plugin_id: 'project_x',
    accent: '#00E5FF',
    secondary: '#FF2A85',
    extra_tweaks: {
      settings_icon_style: 'px_style1',
      settframe: false,
      use_apk_png_icons: true,
      app_png_pack_mode: 'projectx_png',
      per_suite_modes: {
        myfiles: 'vector',
        powermenu: 'glass',
      },
    },
  });

  assert.equal(mixedSuitesBundle.suites.myfiles.vector_only, true);
  assert.equal(mixedSuitesBundle.suites.myfiles.items[0].use_png, false);
  assert.equal(mixedSuitesBundle.suites.powermenu.vector_only, false);
  assert.equal(mixedSuitesBundle.suites.powermenu.active_skin, 'glass');
  assert.equal(mixedSuitesBundle.suites.powermenu.items[0].use_png, true);

  // Test Color Palette Vector override across all 8 suites
  const vectorSuitesBundle = resolveAppPngSuites({
    plugin_id: 'project_x',
    accent: '#00E5FF',
    secondary: '#FF2A85',
    extra_tweaks: {
      settings_icon_style: '1sett',
      settframe: true,
      use_apk_png_icons: false,
      app_png_pack_mode: 'vector_only',
    },
  });

  assert.equal(vectorSuitesBundle.use_png, false);
  assert.equal(vectorSuitesBundle.vector_only, true);
  for (const suite of Object.values(vectorSuitesBundle.suites)) {
    assert.equal(suite.vector_only, true);
    for (const item of suite.items) {
      assert.equal(item.use_png, false);
      assert.equal(item.fg_color, '#00E5FF');
      assert.equal(item.frame_bg, 'transparent');
      assert.equal(item.always_filled, true);
    }
  }
});

test('Safe One UI 9.0 Quick Settings Overlay generates crash-free drawable XML for all tiles, brightness bars, and volume panels (including aliases)', () => {
  const shapes = Object.keys(CATALOG.HEX_QS_TOGGLE_SHAPES);
  const baseQs = resolveQuickSettingsOverlay({});
  const bStyles = baseQs.brightness_styles.map(b => b.id);
  const vStyles = baseQs.volume_styles.map(v => v.id);

  assert.ok(shapes.length >= 11);
  assert.equal(bStyles.length, 7);
  assert.equal(vStyles.length, 6);

  for (const tile_shape of shapes) {
    for (const brightness_bar_style of [...bStyles, 'px_rbb_neon', 'blend_gradient_bar', 'glass_frosted_bar']) {
      for (const volume_panel_style of [...vStyles, 'px_vpanel_cyber', 'blend_vpanel_grad', 'glass_vpanel_frost']) {
        const qs = resolveQuickSettingsOverlay({
          tile_shape,
          accent: '#38BDF8',
          secondary: '#F472B6',
          card: '#1E293B',
          extra_tweaks: {
            brightness_bar_style,
            volume_panel_style,
          },
        });
        assert.equal(qs.safe_for_oneui9, true);
        assert.ok(qs.brightness_style && qs.brightness_style.id);
        assert.ok(qs.volume_style && qs.volume_style.id);
        assert.ok(qs.qs_tile_drawable_xml.includes('<shape') || qs.qs_tile_drawable_xml.includes('<layer-list'));
        assert.ok(qs.brightness_progress_xml.includes('<layer-list') && !qs.brightness_progress_xml.includes('undefined'));
        assert.ok(qs.volume_panel_drawable_xml.includes('<layer-list') && !qs.volume_panel_drawable_xml.includes('undefined'));
        assert.ok(qs.qs_dimens_xml.includes('sec_brightness_slider_height'));
      }
    }
  }
});

test('Signed Universal Release APK supports 64-bit & 32-bit ABIs, English-only localization, and META-INF V1/V2/V3 signing', () => {
  const spec = {
    accent: '#00E5FF',
    secondary: '#FF2A85',
    surface: '#080B12',
    card: '#121826',
    pure_black: true,
    plugin_id: 'project_x',
    tile_shape: 'px_qs1',
    extra_tweaks: {
      settings_icon_style: 'px_style1',
      brightness_bar_style: 'px_rbb_neon',
      volume_panel_style: 'px_vpanel_cyber',
      settframe: false,
      use_apk_png_icons: true,
      app_png_pack_mode: 'auto_skin',
    },
  };

  const apkBuffer = buildSignedUniversalReleaseApk(spec, {});
  assert.ok(Buffer.isBuffer(apkBuffer));
  assert.ok(apkBuffer.length > 2000, 'Expected non-trivial signed APK binary size');
  // Check PK\x03\x04 ZIP magic header
  assert.equal(apkBuffer[0], 0x50);
  assert.equal(apkBuffer[1], 0x4b);
  assert.equal(apkBuffer[2], 0x03);
  assert.equal(apkBuffer[3], 0x04);

  const apkText = apkBuffer.toString('latin1');
  // Verify 64-bit & 32-bit native libraries
  assert.ok(apkText.includes('lib/arm64-v8a/libhexnext_rro_bridge.so'));
  assert.ok(apkText.includes('lib/x86_64/libhexnext_rro_bridge.so'));
  assert.ok(apkText.includes('lib/armeabi-v7a/libhexnext_rro_bridge.so'));
  assert.ok(apkText.includes('lib/x86/libhexnext_rro_bridge.so'));
  // Verify English-only resources
  assert.ok(apkText.includes('res/xml/locales_config.xml'));
  assert.ok(apkText.includes('res/values-en/strings.xml'));
  // Verify META-INF JAR/V2/V3 signing entries
  assert.ok(apkText.includes('META-INF/MANIFEST.MF'));
  assert.ok(apkText.includes('META-INF/HEXNEXT.SF'));
  assert.ok(apkText.includes('META-INF/HEXNEXT.RSA'));
});

test('Full handleCompile and buildThemeJsonObject include all One UI 9.0 metadata, Drive APK gallery, and plugin verification report', () => {
  const fullState = handleCompile({
    plugin_id: 'blend_dn',
    accent: '#6C5CE7',
    secondary: '#00CEC9',
    surface: '#0A0D18',
    card: '#131728',
    pure_black: false,
    tile_shape: 'blend_gradient_pill',
    extra_tweaks: {
      settings_icon_style: 'blend_frame',
      brightness_bar_style: 'blend_gradient',
      volume_panel_style: 'blend_vol',
      settframe: false,
      use_apk_png_icons: true,
      app_png_pack_mode: 'auto_skin',
    },
  });

  assert.equal(fullState.settings_icons.icons.length, 26);
  assert.equal(fullState.app_png_suites.total_suites, 8);
  assert.equal(fullState.app_png_suites.total_drawables, 38);
  assert.equal(fullState.quick_settings.safe_for_oneui9, true);
  assert.equal(fullState.qs_overlay.safe_for_oneui9, true);
  assert.equal(fullState.drive_apk_gallery.total_items, 28);
  assert.equal(fullState.plugin_verification_report.all_passed, true);
  assert.equal(fullState.plugin_verification_report.passed_checks, 104);
  assert.equal(fullState.monet.total_shades, 65);
  assert.equal(fullState.monet.total_roles, 23);
  assert.deepEqual(fullState.release_readiness.architectures.abis_64_bit, ['arm64-v8a', 'x86_64']);
  assert.deepEqual(fullState.release_readiness.architectures.abis_32_bit, ['armeabi-v7a', 'x86']);
  assert.deepEqual(fullState.release_readiness.localization.res_configs, ['en']);
  assert.equal(fullState.release_readiness.drive_apks_integrated.length, 5);

  // Verify GitHub CI workflow and PR template exist
  assert.ok(fs.existsSync(path.join(__dirname, '..', '.github', 'workflows', 'android.yml')));
  assert.ok(fs.existsSync(path.join(__dirname, '..', '.github', 'PULL_REQUEST_TEMPLATE.md')));
});

test('GitHub CI/CD pipeline and Gradle configuration enforce 64-bit & 32-bit architectures, verify SDK tools via Gradle check task, and pull signing credentials from GitHub repository secrets without log exposure', () => {
  const { verifyCiCdAndSdkEnvironment } = require('../server.js');
  const audit = verifyCiCdAndSdkEnvironment();

  assert.equal(audit.all_passed, true, 'Expected all CI/CD, GitHub secrets signing, and SDK environment checks to pass');
  assert.equal(audit.gradle_64_32_bit_aligned, true, 'Expected Gradle 64-bit & 32-bit ABI filters & splits to be aligned');
  assert.equal(audit.gradle_keystore_env_configured, true, 'Expected Gradle build script to resolve keystore credentials');
  assert.equal(audit.gradle_github_secrets_configured, true, 'Expected Gradle build script to pull signing credentials from secure GitHub repository secrets');
  assert.equal(audit.gradle_sdk_tools_task_configured, true, 'Expected Gradle build script to define verifyAndroidSdkTools check task for aapt2, apksigner, and zipalign');
  assert.equal(audit.ci_64_32_bit_pipeline_verified, true, 'Expected CI workflow to verify 64-bit & 32-bit ABIs');
  assert.equal(audit.ci_keystore_env_passed, true, 'Expected CI workflow to provision keystore secrets to Gradle');
  assert.equal(audit.ci_secrets_masked_no_log_exposure, true, 'Expected CI workflow to mask signing secrets and avoid log exposure');
  assert.equal(audit.ci_gradle_sdk_tools_step_present, true, 'Expected CI workflow to execute :app:verifyAndroidSdkTools before assembleDebug/assembleRelease');
  assert.equal(audit.sdk_tools_check_present, true, 'Expected CI workflow to verify SDK tools (sdkmanager, aapt2, apksigner, zipalign, keytool)');
  assert.equal(audit.release_pipeline_verified, true, 'Expected CI release job to verify 64/32-bit artifacts before publishing');
  assert.deepEqual(audit.abis_64_bit, ['arm64-v8a', 'x86_64']);
  assert.deepEqual(audit.abis_32_bit, ['armeabi-v7a', 'x86']);
  assert.deepEqual(audit.required_sdk_tools, ['sdkmanager', 'aapt2', 'apksigner', 'zipalign', 'keytool']);
  assert.ok(audit.keystore_env_vars.includes('HEXNEXT_KEYSTORE_PATH'));
  assert.ok(audit.keystore_env_vars.includes('KEYSTORE_PATH'));
  assert.ok(audit.keystore_env_vars.includes('HEXNEXT_KEYSTORE_BASE64'));
});




