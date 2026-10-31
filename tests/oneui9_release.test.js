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

test('Preview cannot claim that configuration checks prove a signed release is ready', () => {
  const { buildReleaseReadinessReport } = require('../server.js');
  const report = buildReleaseReadinessReport({});
  assert.equal(report.ready_for_release, false);
  assert.equal(report.signing.signed, false);
  assert.match(report.sdk_environment_check.verification_scope, /configuration-only/);
  assert.equal(require('../server.js').buildSignedUniversalReleaseApk, undefined);
});

test('Deprecated APK export endpoints return an explicit error instead of a placeholder APK', async () => {
  const { server } = require('../server.js');
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  try {
    const base = `http://127.0.0.1:${server.address().port}`;
    for (const endpoint of ['/api/export/apk', '/HexNext_OneUI9_Universal_EN_Signed.apk']) {
      const response = await fetch(base + endpoint);
      assert.equal(response.status, 410);
      assert.match((await response.json()).error, /native app/);
    }
  } finally {
    await new Promise(resolve => server.close(resolve));
  }
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

test('Release configuration uses native Gradle builds and external signing without manufacturing keystores', () => {
  const { verifyCiCdAndSdkEnvironment } = require('../server.js');
  const audit = verifyCiCdAndSdkEnvironment();

  assert.equal(audit.all_passed, true, 'Expected all CI/CD, GitHub secrets signing, and SDK environment checks to pass');
  assert.equal(audit.gradle_64_32_bit_aligned, true, 'Expected Gradle 64-bit & 32-bit ABI filters & splits to be aligned');
  assert.equal(audit.gradle_build_optimized, true, 'Expected Gradle build & R8 minification/resource shrinking optimizations to be enabled');
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
  assert.ok(audit.keystore_env_vars.includes('HEXNEXT_KEYSTORE_BASE64'));
  assert.equal(fs.existsSync(path.join(__dirname, '..', 'android', 'app', 'debug.keystore')), false);
  assert.equal(fs.existsSync(path.join(__dirname, '..', 'bin', 'release.keystore')), false);
  const legacyBuilder = fs.readFileSync(path.join(__dirname, '..', 'apps', 'hex-next', 'compile.sh'), 'utf8');
  assert.match(legacyBuilder, /exec .*bin\/build-and-sign-release-apk\.sh/);
  assert.equal(legacyBuilder.includes('libhexnext_rro_bridge.so'), false);
});

test('Obsolete HexBridge and Theme Park compatibility stub are absent and not advertised', () => {
  const root = path.join(__dirname, '..');
  for (const directory of ['apps/hex-bridge', 'apps/dummy-themepark']) {
    assert.equal(fs.existsSync(path.join(root, directory)), false);
  }
  const readme = fs.readFileSync(path.join(root, 'README.md'), 'utf8');
  assert.doesNotMatch(readme, /HexBridge|Hex Bridge|Theme Park Stub|compatibility stub/i);
  const research = fs.readFileSync(path.join(root, 'docs', 'HEX_INSTALLER_REVERSE_ENGINEERING.md'), 'utf8');
  assert.doesNotMatch(research, /compatibility stub|deploy a dummy/i);
});

test('Recent Logs widget classifies and isolates IDMAP2 Conflicts, SystemUI Resource Mismatches, and Permission Denied compilation failures', () => {
  const {
    classifyRecentLogFailureType,
    filterRecentDiagnosticLogs,
    RUNTIME_STATE,
  } = require('../server.js');

  const sampleLogs = [
    '[*] HexNext v3.5 — One UI 9.0 RRO Compiler Initialized',
    '[✓] Synthesized 26 from-scratch RRO packages (com.hexnext.oneui9.rro.*) taking precedence over SemWT_*.',
    '[!] [19:54:12 UTC] COMPILE_FAILURE [IDMAP2_CONFLICT] [HIGH-FREQ FAULT #4] on com.hexnext.oneui9.rro.aodservice (com.samsung.android.app.aodservice) -> IDMAP2_ERR: Overlay Conflict — Repeated lock contention on stale /data/resource-cache/com.hexnext.oneui9.rro.aodservice.frro (color/aod_clock_color)',
    '[!] [19:54:48 UTC] COMPILE_FAILURE [SYSTEMUI_RESOURCE_MISMATCH] on com.hexnext.oneui9.rro.systemui (com.android.systemui) -> IDMAP2_ERR: SystemUI Resource Mismatch — Unresolved symbol \'dimen/qs_tile_background_radius\' in SemWT_SystemUI target table',
    '[!] [19:55:04 UTC] COMPILE_FAILURE [SYSTEMUI_RESOURCE_MISMATCH] [HIGH-FREQ FAULT #3] on com.hexnext.oneui9.rro.messaging (com.samsung.android.messaging) -> IDMAP2_ERR: Resource ID Mismatch — Unresolved symbol \'color/message_bubble_send_bg\' during SemWT_Messaging override pass',
    '[!] [19:55:19 UTC] COMPILE_FAILURE [PERMISSION_DENIED] on com.hexnext.oneui9.rro.framework (android) -> PERMISSION_DENIED: SELinux avc: denied { write } for uid=2000 on /data/resource-cache/ (SecurityException: IOverlayManager.commit requires UID 1000 system or UID 0 root)',
  ];

  assert.equal(filterRecentDiagnosticLogs(sampleLogs, 'all').length, 6);
  assert.equal(filterRecentDiagnosticLogs(sampleLogs, 'errors').length, 4);
  assert.equal(filterRecentDiagnosticLogs(sampleLogs, 'success').length, 1);

  const idmapConflicts = filterRecentDiagnosticLogs(sampleLogs, 'idmap2_conflicts');
  assert.equal(idmapConflicts.length, 1);
  assert.equal(idmapConflicts[0].failureCategory, 'idmap2_conflicts');
  assert.equal(idmapConflicts[0].failureCategoryLabel, 'IDMAP2 Conflict');
  assert.ok(idmapConflicts[0].raw.includes('aodservice'));

  const systemUiMismatches = filterRecentDiagnosticLogs(sampleLogs, 'systemui_mismatches');
  assert.equal(systemUiMismatches.length, 2);
  assert.ok(systemUiMismatches.every(item => item.failureCategory === 'systemui_mismatches'));
  assert.equal(systemUiMismatches[0].failureCategoryLabel, 'SystemUI Resource Mismatch');

  const permissionDenied = filterRecentDiagnosticLogs(sampleLogs, 'permission_denied');
  assert.equal(permissionDenied.length, 1);
  assert.equal(permissionDenied[0].failureCategory, 'permission_denied');
  assert.equal(permissionDenied[0].failureCategoryLabel, 'Permission Denied');
  assert.ok(permissionDenied[0].raw.includes('SELinux avc: denied'));

  // Verify classifyRecentLogFailureType directly
  const cPerm = classifyRecentLogFailureType('[!] SecurityException: Permission Denied by IOverlayManager');
  assert.equal(cPerm.isError, true);
  assert.equal(cPerm.failureCategory, 'permission_denied');

  const cSys = classifyRecentLogFailureType('[!] IDMAP2_ERR: Unresolved symbol in com.android.systemui');
  assert.equal(cSys.isError, true);
  assert.equal(cSys.failureCategory, 'systemui_mismatches');

  const cIdmap = classifyRecentLogFailureType('[!] IDMAP2_ERR: Overlay Conflict on stale .frro lock');
  assert.equal(cIdmap.isError, true);
  assert.equal(cIdmap.failureCategory, 'idmap2_conflicts');

  assert.ok(Array.isArray(RUNTIME_STATE.last_build.logs));
});

test('Automatic Safe Mode Fallback on System Crash intercepts SystemUI/system_server crashes, disables RROs, quarantines offending package, and restores healthy targets', () => {
  const {
    evaluateSystemCrashForSafeMode,
    executeAutomaticSafeModeFallback,
    restoreFromSafeModeFallback,
    RUNTIME_STATE,
  } = require('../server.js');

  // 1. Pure crash evaluation test
  const evalCrash = evaluateSystemCrashForSafeMode(
    { preset: 'systemui_inflate' },
    { enabled: true, crash_threshold: 1, crash_count: 0 }
  );
  assert.equal(evalCrash.is_system_crash, true);
  assert.equal(evalCrash.should_trigger_fallback, true);
  assert.equal(evalCrash.offending_id, 'systemui');
  assert.equal(evalCrash.offending_rro, 'com.hexnext.oneui9.rro.systemui');

  // 2. Threshold > 1 waits until threshold is reached
  const evalBelowThreshold = evaluateSystemCrashForSafeMode(
    { preset: 'system_server_dead' },
    { enabled: true, crash_threshold: 2, crash_count: 0 }
  );
  assert.equal(evalBelowThreshold.should_trigger_fallback, false);
  assert.equal(evalBelowThreshold.crash_count, 1);

  // 3. Execute automatic safe mode fallback on SystemUI crash
  RUNTIME_STATE.safe_mode_watchdog.enabled = true;
  RUNTIME_STATE.safe_mode_watchdog.crash_threshold = 1;
  RUNTIME_STATE.safe_mode_watchdog.auto_quarantine_offending = true;

  const fallbackState = executeAutomaticSafeModeFallback({ preset: 'systemui_inflate' });
  assert.equal(fallbackState.safe_mode_watchdog.in_safe_mode, true);
  assert.equal(fallbackState.spec.selected_targets.length, 0);
  assert.equal(fallbackState.stats.selected_packages, 0);
  assert.ok(fallbackState.safe_mode_watchdog.quarantined_targets.includes('systemui'));
  assert.equal(fallbackState.safe_mode_watchdog.last_crash_event.offending_id, 'systemui');
  assert.ok(
    fallbackState.last_build.logs.some(line => line.includes('AUTOMATIC SAFE MODE FALLBACK COMPLETE'))
  );

  // 4. Restore from Safe Mode (quarantining offending SystemUI RRO first, then full restore)
  const restoredSafe = restoreFromSafeModeFallback({ include_offending: false });
  assert.equal(restoredSafe.safe_mode_watchdog.in_safe_mode, false);
  assert.ok(restoredSafe.spec.selected_targets.length > 0);
  assert.ok(!restoredSafe.spec.selected_targets.includes('systemui'));

  const restoredAll = restoreFromSafeModeFallback({ include_offending: true });
  assert.equal(restoredAll.safe_mode_watchdog.in_safe_mode, false);
  assert.ok(restoredAll.spec.selected_targets.includes('systemui'));
});

test('Compiler Console progress bar uses non-linear cubic-bezier CSS transitions and phase-weighted progression matching perceived RRO build speed', () => {
  const fs = require('node:fs');
  const path = require('node:path');
  const { computeCompilerProgressCurve, buildFullState } = require('../server.js');

  const fullState = buildFullState();
  const curve = fullState.compiler_progress_curve || computeCompilerProgressCurve(fullState.packages);

  assert.equal(curve.mode, 'non-linear-perceived-rro-speed');
  assert.equal(curve.total_steps, 26);
  assert.equal(curve.steps.length, 26);

  // Verify strictly monotonic non-linear progression ending at 100%
  for (let i = 0; i < curve.steps.length; i++) {
    const step = curve.steps[i];
    assert.ok(step.non_linear_pct > 0 && step.non_linear_pct <= 100);
    assert.ok(step.css_easing.startsWith('cubic-bezier('));
    assert.ok(step.transition_duration_ms >= 180 && step.transition_duration_ms <= 450);
    if (i > 0) {
      assert.ok(
        step.non_linear_pct > curve.steps[i - 1].non_linear_pct,
        `Expected step ${i + 1} non_linear_pct (${step.non_linear_pct}) > step ${i} (${curve.steps[i - 1].non_linear_pct})`
      );
    }
  }
  assert.equal(curve.steps[curve.steps.length - 1].non_linear_pct, 100);
  assert.equal(curve.steps[curve.steps.length - 1].phase, 'commit');

  // Verify non-linearity compared to uniform linear progression
  const hasNonLinearStep = curve.steps.some(s => s.non_linear_pct !== s.linear_pct);
  assert.equal(hasNonLinearStep, true, 'Expected non-linear progress curve to differ from uniform linear steps');

  // Verify heavy RRO targets (e.g. systemui, settings, aodservice) use heavy phase transition
  const heavySteps = curve.steps.filter(s => s.phase === 'heavy');
  assert.ok(heavySteps.length >= 4, 'Expected heavy RRO targets to use heavy idmap2 build phase');
  assert.ok(heavySteps.every(s => s.css_class === 'compiler-progress-phase-heavy' && s.transition_duration_ms === 340));

  // Verify CSS classes exist in templates/index.html
  const html = fs.readFileSync(path.join(__dirname, '..', 'templates', 'index.html'), 'utf8');
  assert.ok(html.includes('.compiler-progress-smooth'));
  assert.ok(html.includes('--compiler-progress-duration'));
  assert.ok(html.includes('--compiler-progress-easing'));
  assert.ok(html.includes('.compiler-progress-phase-bootstrap'));
  assert.ok(html.includes('.compiler-progress-phase-heavy'));
  assert.ok(html.includes('.compiler-progress-phase-sweep'));
  assert.ok(html.includes('.compiler-progress-phase-commit'));
});




