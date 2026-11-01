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
   layout is verified against Samsung's real `SemWT_android.frro` (see `FrroFormat`); binary
   payloads are still an assumption.
4. The target APK's `resources.arsc`. The union of "resources Samsung themes" and "all
   colour/drawable/dimen/bool resources of the target" becomes the **resource map** for that
   package, cached per package version code in `files/resmap/<pkg>-<version>.json`.
5. The plugin catalog's resource names are matched against the live map; names that no longer
   exist are reported in the build log instead of being applied blindly.

What the priority finding means for applying a theme: Samsung's overlays already sit at
`Integer.MAX_VALUE`, so "highest" cannot be outranked numerically. Hex Next must (a) run
`cmd overlay set-priority <hexnext overlay> highest` **after** Samsung's overlays are in place,
so it is ordered above them, and (b) for every target it themes, disable
`android:SemWT_<target>`, then re-assert that (and its own priority) whenever Samsung's
wallpaper-theme service re-enables the SemWT overlay, e.g. after a wallpaper or palette change.

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
| PNG / 9-patch drawables (icons, backgrounds) | FRRO `setResourceValue(name, ParcelFileDescriptor, config)` (Android 14+), 9-patch via `setNinePatchResourceValue` where present |
| XML drawables, styles, layouts | Phase 2b: on-device RRO APK compiler (binary `AndroidManifest.xml` + `resources.arsc` writer, APK Signature Scheme v2) installed with `pm install` as root |

One fabricated overlay per target package, named `HexNext_<target>`, owned by the service
UID's owner package, enabled and set to `highest` priority in one transaction. Applying a
theme is: compute the desired state, diff against the installed state, re-register only the
overlays whose content hash changed.

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
