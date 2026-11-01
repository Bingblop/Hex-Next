# Hex Next architecture (One UI 9 first)

Hex Next is a root / Shizuku (UID 0 or 1000) theming engine for Samsung One UI 7, 8 and 9.
It replaces Samsung's `SemWT_*` wallpaper-theme overlays with overlays it builds on the
device at the highest overlay priority. This document is the build plan; `docs/plugin-catalog/`
is the map of what the legacy Hex plugins themed.

## Why discovery comes before theming

Every One UI version renames resources. Hard-coding resource names (as the AI Studio draft did)
breaks silently: `cmd overlay` accepts a fabricated overlay whose resources do not exist and the
screen simply does not change. The engine therefore **reads the device** before it builds.

What a real One UI 9.0 device showed (Galaxy SM-S948U1, Android 17 / SDK 37, KernelSU, Shizuku
as root; raw data in `docs/device/oneui9-sm-s948u1/`, constants in
`engine/.../device/OneUi9Facts.kt`):

- Samsung's theme overlays are the fabricated `android:SemWT_<target>` overlays, one per
  target package. Their `.frro` files live in **`/data/resource-cache/`**
  (`android-SemWT_<target>-XXXX.frro`, stale copies with other suffixes can linger), not in
  `/data/overlays/` (that directory only holds the theme store's own state).
- Every SemWT overlay is owned by package `android` (`mPackageName: android`), targets no
  `<overlayable>` (`mTargetOverlayableName` empty), is mutable, and sits at
  **`mPriority 2147483647` (`Integer.MAX_VALUE`)**.
- `cmd overlay list` showed 42 enabled SemWT targets (plus `SemWT_MonetPalette`, which targets
  `android` with Monet shades) and 74 more listed as `---`: `STATE_MISSING_TARGET` (the target
  was debloated) or `STATE_NO_IDMAP`.

Discovery, in order:

1. `cmd overlay list --user current` gives every overlay and its target.
2. **Primary:** for each target, `cmd overlay dump android:SemWT_<target>`, parsed by
   `engine/.../device/OverlayDump.kt`. The info block gives owner, state, enabled and priority;
   the `IDMAP OF` section gives every themed resource already resolved against the target
   (`0x7f060020 -> color 0xff0b4d5f    0x7f060020 -> color 0xff6ed4ef (color/actionbar_text_color)`:
   default value, then `night` value) and the `W failed to find resource` warnings (names Samsung
   themes that this target build lacks). This needs only `cmd overlay`, not read access to
   `/data/resource-cache`. `cmd overlay dump <pkg>` without a name prints the info blocks of all
   of that package's overlays; `OverlayDump.parseAll` returns them as a list. The app's
   `DeviceScanner` stores each dump in `files/scan/semwt/<target>.txt` / `.json` and records
   `semwtPriority` / `semwtState` per target in the device report.
3. **Fallback:** the `.frro` itself, parsed by `engine/.../frro/FrroReader.kt`. The version 3
   layout follows AOSP `FabricatedOverlay.cpp` and is verified against Samsung's real
   `SemWT_android.frro` (see `FrroFormat` and `docs/reference/frro-binary-format.md`).
4. The target APK's `resources.arsc`. The union of "resources Samsung themes" and "all
   colour/drawable/dimen/bool resources of the target" becomes the **resource map** for that
   package, cached per package version code in `files/resmap/<pkg>-<version>.json`.
5. The plugin catalog's resource names are matched against the live map; names that no longer
   exist are reported in the build log instead of being applied blindly.

What the priority finding means for applying a theme: Samsung's overlays already sit at
`Integer.MAX_VALUE`, so "highest" cannot be outranked numerically. The on-device runs on
SM-S948U1 (`docs/device/oneui9-sm-s948u1/overlay-test-run2.txt`) settled how ordering works:

- **Every enable moves the overlay to the top.** On this build OverlayManagerService calls
  `setHighestPriority` on every successful enable (logcat shows `setEnabled … enable=true`
  followed by `setHighestPriority overlay=…`), so the most recently enabled overlay of a target
  wins. A fabricated Hex overlay enabled after `android:SemWT_<target>` keeps its colour with
  SemWT still enabled. Samsung's `SWT_ThemeManager` re-enables SemWT the same way when a theme or
  wallpaper is (re)applied, which puts SemWT back on top.
- **`set-priority` does not work for fabricated overlays.** `cmd overlay set-priority
  <owner:name> highest` exits 255 with "Unable to retrieve overlay information for
  com.android.shell:HexNext_probe". `enable`/`disable` print the same error for ids that do
  not exist, so a non-zero `set-priority` exit must never be read as "overlay missing".
- `cmd overlay list` prints each target's overlays in ascending priority: a later line wins.
- `cmd overlay fabricate` takes positional `PKG:TYPE/NAME 0x1c 0xAARRGGBB` triples
  (`--resource-name` does not exist). No SELinux denials were logged.

How the app does this (`ThemeApplier`, `SemWtController`, `ThemeService.enableOnTop`, engine
`device/SemWtTakeover.kt`):

1. `applyOverlay` registers `HexNext_<target>` and enables it, which lands it on top. The
   service still runs `set-priority … highest` once, separately and best-effort (its output is
   logged, its exit code never fails the apply), then checks `cmd overlay list`; only when
   another enabled overlay of the target is listed after ours does it raise ours with a
   disable/enable cycle (an `OverlayManagerTransaction` `setEnabled` pair when privileged and the
   hidden method exists, else the shell).
2. `ThemeApplier` disables `android:SemWT_<target>` (`cmd overlay disable --user current …`,
   options first: the shell command only reads leading options) and, from the list order
   (`SemWtPlanner.raiseNeed`: none / raise / enable / register), raises ours again only if needed.
3. It verifies with `cmd overlay lookup --user current <target> <first plain colour>` that the
   value equals what was set and records `verified` per target in the `ApplyReport`; a mismatch
   makes the target PARTIAL, not FAILED.
4. Each takeover is recorded in `/data/local/tmp/hexnext/semwt_state.json` (`SemWtState`: SemWT id,
   Hex overlay id, whether the SemWT overlay was enabled before the first takeover).
5. The root re-assert guard `semwt_guard.sh` (detached `setsid sh`, like the stability monitor)
   polls every 5 s a stamp of the mtimes of `/data/resource-cache` and `wallpaper_info.xml` plus a
   checksum of `cmd overlay list` (so a re-enable or reorder is noticed even without a wallpaper
   change), and does a full check on change and every 5 minutes: for every enabled Hex overlay it
   disables a re-enabled SemWT overlay and, when any enabled overlay is listed after ours in the
   target's section, runs the disable/enable cycle. `BOOT_COMPLETED` and app start re-assert once
   and restart the guard. When `safe_mode.json` appears, the guard re-enables the SemWT overlays
   it took over and exits.
6. Removal stops the guard, disables every `HexNext*` overlay, re-enables exactly the SemWT
   overlays that were enabled before, and removes the legacy `com.android.shell:HexNextMonetShades`
   overlay of the old skeleton.

## Gradle layout

```
android/
  engine/      pure JVM Kotlin: arsc + frro parsers, pack model, palette, resource mapping,
               theme compiler (unit-tested, builds without the Android SDK)
  app/         Android app: Compose UI, Shizuku service, device discovery, apply, watchdog
```

`engine` depends only on Kotlin stdlib and kotlinx-serialization so it can be tested anywhere.

## Overlay delivery

| Need | Mechanism |
|---|---|
| Colours, dimens, bools, ints, strings | `FabricatedOverlay` (FRRO) registered through `OverlayManagerTransaction` as root |
| PNG drawables (icons, backgrounds) | Colour FRRO committed through OverlayManager, then its `.frro` rewritten as root with `FrroWriter` (see below); nine-patch not yet |
| XML drawables, styles, layouts | Phase 2b: on-device RRO APK compiler (binary `AndroidManifest.xml` + `resources.arsc` writer, APK Signature Scheme v2) installed with `pm install` as root |

One fabricated overlay per target package, named `HexNext_<target>`, owned by the service
UID's owner package, registered in one transaction and then enabled (which puts it on top on
One UI 9; see the takeover section above). Applying a
theme is: compute the desired state, diff against the installed state, re-register only the
overlays whose content hash changed.

**PNG drawables (root `.frro` rewrite).** On One UI 9 a FabricatedOverlay carrying PNG
`ParcelFileDescriptor`s never reaches idmap2d: OverlayManagerService's Binder call dies with
`FAILED_TRANSACTION` because SELinux lets idmap2d accept descriptors from no app, shell or su
domain (`docs/device/oneui9-sm-s948u1/idmap-log-20261101.txt`). `ThemeService` therefore
commits the overlay with its colours only, finds the registered file (`cmd overlay dump`, else
the newest `/data/resource-cache/<owner>-<name>-*.frro`), and as uid 0 writes the full file with
the engine's `FrroWriter`: the PNGs (read from the app's descriptors) are embedded after the
header and referenced by `frro:/<path>?offset=&size=` strings, exactly as idmap2d would have
written them. The new bytes go to `<path>.hexnext.tmp` with the original owner, mode and SELinux
label (`system:system 0644 resourcecache_data_file` on One UI 9) and are renamed over the file.
A disable/enable makes OverlayManager regenerate the idmap (new CRC) and raises the overlay
above SemWT. The service then checks that the idmap exists and records the rewritten CRC,
that `cmd overlay lookup` resolves each drawable to a `frro:/` value, and that `FrroReader`
re-reads every payload; on a hard failure it restores the colour-only file and reports the
drawables as skipped (exit code 3). Nine-patch PNGs are skipped until the aapt2 `npTc`
compilation idmap2d performs is implemented.

## Theme model

```
Theme
 ├─ palette: ColorBlendr-compatible (seed colour or wallpaper, style, saturation,
 │           background lightness, contrast, text tint, per-slot overrides)
 ├─ scope:   which target packages are on (debloated packages are greyed and skipped)
 ├─ simple:  one Pack for every element
 └─ advanced: Pack per element (settings icons, navbar, QS tiles, keyboard, dialer, My Files,
              power menu, notifications, status bar, backgrounds …) plus per-resource colours
```

A **Pack** is the zip produced by `tools/hex_pack_builder.py`: greyscale PNG layers grouped by
`pass/stage/tint/category`, a palette, options. The compiler composites layers into final PNGs
(tint slot → palette colour) per density, then hands bytes to the FRRO builder.

## Safety

* **Build log**: every overlay build writes `files/logs/<timestamp>.jsonl` with target, resource
  count, skipped names, result, and `cmd overlay lookup` read-back of one sentinel resource.
* **Repair**: a failed or stale overlay is disabled, its idmap purged from `/data/resource-cache`,
  and rebuilt from the saved theme.
* **Safe mode watchdog**: before applying, the service writes `applying.json` (boot id, time).
  A root shell watchdog started with `setsid` watches `system_server` and `com.android.systemui`
  PIDs for 120 s; two restarts disable every `HexNext_*` overlay. On `BOOT_COMPLETED`, if the
  boot id changed twice within 10 minutes of an unconfirmed apply, all Hex overlays are disabled
  and the app opens in Safe Mode. The user confirms "it works" to clear the marker.
* **Panic**: `Remove everything` disables and unregisters all `HexNext_*` overlays.

## One UI version handling

`DeviceProfile` reads `ro.build.version.oneui` / `SEM_PLATFORM_INT`, Android SDK, and the
device model. Version-specific rules live in `engine/src/main/resources/oneui/<major>.json`:
target package list, quick-settings policy (tiles only on 7/8/9), known renamed resources. One
UI 9 ships first; 7 and 8 reuse the same discovery path with their own rule files.

## Monetisation-ready extras

Theme export/import (`.hexnext` zip), scheduled day/night themes, per-app preview rendering
from the same compiler output used on device, and crash-free telemetry kept local.
