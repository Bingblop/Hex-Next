package com.hexnext.themer;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;

// Runs inside the Shizuku user service process (root uid 0 / system uid 1000 / shell uid 2000),
// or inside the app process when ShizukuManager uses direct SU (then only exec() and the
// shell-based paths have root, through `su -c`).
// Every Bundle result carries "code" (int, 0 = success) and "output" (String).
interface IThemeService {
    // Reserved transaction code Shizuku uses to tear the service down.
    void destroy() = 16777114;

    int getUid() = 1;

    Bundle exec(String command) = 2;

    // Registers (or replaces) a fabricated overlay of ARGB colors and enables it. Root only.
    Bundle applyColorOverlay(String name, String targetPackage, in String[] resourceNames, in int[] colors) = 3;

    // Disables and unregisters a fabricated overlay this app created. Root only.
    Bundle removeOverlay(String name) = 4;

    // Sequentially clears stale idmap2/frro caches in /data/resource-cache/ and rebuilds
    // the specified overlays at highest priority (> SemWT_*) under Shizuku UID 0 / root.
    Bundle repairAndRecompileBatch(in String[] overlayNames, in String[] targetPackages) = 5;

    // Automatic Safe Mode Fallback on System Crash: immediately disables all active overlays,
    // purges corrupted /data/resource-cache/*.{idmap,frro} entries, and restores stock SemWT_*.
    Bundle triggerSafeModeFallback(String crashReason) = 6;

    // `cmd overlay list --user current` parsed into "json": [{overlayId, target, enabled,
    // isSamsungWallpaperTheme}], plus "frroPaths" (String[]) of every *.frro found as root.
    Bundle listOverlays() = 7;

    // Read-only descriptor for a (possibly root-only) file. Privileged: opened directly.
    // Otherwise: a pipe fed by `su -c cat`. Null on failure or for unsafe paths.
    ParcelFileDescriptor openFile(String path) = 8;

    // "json": [{pkg, apkPath, installed, debloated}] plus device props as individual keys
    // (ro.build.version.oneui, ro.build.version.sem, ro.build.version.sdk,
    // ro.build.version.release, ro.product.model, ro.build.display.id) and "uid".
    Bundle packageInfo(in String[] packages) = 9;

    // One fabricated overlay holding colours (TYPE_INT_COLOR_ARGB8) and PNG drawables, registered,
    // enabled and raised to highest priority. Drawables need the privileged (uid 0/1000) service;
    // pure-colour requests fall back to `cmd overlay fabricate` otherwise.
    // Extra keys: "resourceCount" (int), "skipped" (JSON array), "millis" (long), "lookup" (String).
    Bundle applyOverlay(String name, String target,
            in String[] colorNames, in int[] colors,
            in String[] drawableNames, in String[] drawableConfigs,
            in ParcelFileDescriptor[] drawableFds, in boolean[] ninePatch) = 10;

    // Disables (and, when privileged, unregisters) every overlay whose name starts with "HexNext".
    // "json": [{overlayId, disabled, unregistered, message}].
    Bundle disableHexOverlays() = 11;

    // Small JSON state files in /data/local/tmp/hexnext/ (name: [A-Za-z0-9_.-]+).
    Bundle writeStateFile(String name, String content) = 12;
    // "content" holds the file text; code 1 when the file does not exist.
    Bundle readStateFile(String name) = 13;

    // Detached POSIX sh watchdog: samples system_server / SystemUI pids every 5 s for `seconds`;
    // two restarts of either disable every HexNext* overlay and write safe_mode.json.
    Bundle startStabilityMonitor(int seconds) = 14;
    Bundle stopStabilityMonitor() = 15;

    // applyOverlay with one FRRO configuration per colour (colorConfigs[i]: "" or null for the
    // default configuration, "night" …). colorConfigs may be null (all default). Configured
    // colours need the privileged service; the `cmd overlay fabricate` fallback skips them.
    Bundle applyOverlayConfigured(String name, String target,
            in String[] colorNames, in int[] colors, in String[] colorConfigs,
            in String[] drawableNames, in String[] drawableConfigs,
            in ParcelFileDescriptor[] drawableFds, in boolean[] ninePatch) = 16;
}
