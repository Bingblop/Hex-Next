# Colour engine specification (ColorBlendr parity, One UI scope)

Hex Next's colour engine reproduces ColorBlendr's adjustable Monet pipeline, but writes the
result only into One UI packages. It never touches `android:color/system_*`, so Google apps
and third-party Material You apps keep the system palette.

## Inputs (all persisted per theme, separately for light and dark)

| Setting | Values | ColorBlendr equivalent |
|---|---|---|
| Seed | wallpaper colour (default), picked colour, preset | seed colour |
| Secondary / tertiary seed | optional colours | secondary/tertiary override |
| Style | Tonal Spot, Vibrant, Expressive, Spritz (Neutral), Rainbow, Fruit Salad, Monochrome, Fidelity, Content, CMF | `MONET` |
| Colour spec | 2021, 2025, 2026 | `specVersion` |
| Contrast | -1.0 … 1.0 (standard, medium, high, custom) | `contrast` |
| Accent saturation | 0 … 200, neutral 100 | `accentSaturation` |
| Background saturation | 0 … 200 | `backgroundSaturation` |
| Background lightness | 0 … 200 (darkest / brightest tone curves) | `backgroundLightness` |
| Accurate shades | on/off (off = vivid: chroma × 1.25, tone shift above 50) | `accurateShades` |
| Pitch black | off / surfaces / surfaces + containers | `pitchBlack`, `pitchBlackContainers` |
| Tinted text | on/off (off forces pure white/black text) | `tintedText` |
| Shade overrides | any of the 5 × 13 shades | `shadeOverrides` |
| Token overrides | any Material role | `tokenOverrides` |

ColorBlendr is GPL-3.0, so Hex Next (a paid app) does not copy its code. The behaviours
below are re-implemented independently in `android/engine` from this specification:

* Saturation: multiply palette chroma by `level / 100`; vivid mode (accurate shades off)
  multiplies by a further 1.25 and lowers tones above 50 by up to 6 tone steps, tapering
  within 10 tones of 50 and of 100.
* Background lightness: blend each neutral tone toward a "darkest" curve
  (0→0, 6→0, 12→2, 90→80, 100→100) when the level is below 100, or a "brightest" curve
  (0→0, 10→20, 88→98, 100→100) when above, by `|100 - level| / 100`.
* Out-of-gamut colours are clipped by reducing chroma, never by shifting hue.
* Pitch black (dark mode only): neutral surface tones become `(tone - surfaceTone) × 1.15`
  clamped at 0, the 900 neutral shade becomes pure black, and containers follow when the
  container option is on.
* Tinted text off: `on*` text roles become pure white or black by lightness, keeping alpha.
* Shade overrides shift neighbouring tones of the same role in CIELAB so the scale stays
  smooth around a pinned shade.

Material Color Utilities is Apache-2.0 (material-foundation/material-color-utilities) and is
vendored as Java sources into `android/engine/src/main/java` for the 2021/2025/2026 specs.

## Output: a resolved theme

`ResolvedTheme` = 65 Monet shades (`accent1..3`, `neutral1..2` × 13 tones) + all Material
roles for light and dark, plus the tuned surface tones. This is the same object ColorBlendr
builds; the difference is where it goes.

## Writing colours into One UI only

1. **Samsung's own map.** For each One UI target the device scan reads the `SemWT_<pkg>`
   fabricated overlay. Samsung's wallpaper-theme service writes each themed colour resource of
   that app (e.g. `sec_widget_round_and_bgcolor`) from Samsung's wallpaper palette. Those
   `(resourceName → argb)` pairs are exactly the resources Samsung expects to be themable.
2. **Role inference.** Each SemWT colour is matched to the nearest shade of the *system
   palette active when the scan ran* (read from `android:color/system_*` at scan time). The
   match records role (accent1/neutral1/…), tone index and alpha. Matches worse than ΔE 6
   are kept as "fixed" (not recoloured) and listed in the log.
3. **Recolouring.** The resource is written into `HexNext_<pkg>` from the Hex Next
   `ResolvedTheme` using the inferred role/tone, with the user's adjustments applied, keeping
   the alpha. Rules (pitch black, tinted text) apply by role as in ColorBlendr.
4. **Extras.** The catalog in `docs/plugin-catalog/` adds Samsung resources the plugins
   themed that Samsung's theme service does not (status bar, navbar, keyboard key colours).
   They are recoloured only if present in the target's `resources.arsc`.
5. **Advanced mode.** Every colour resource of a target (from its resource table) is listed
   and can be pinned to any role/tone or a fixed colour; pins win over inference.

## Quick settings policy

One UI 7, 8 and 9 restructured quick settings. Hex Next themes tile colours and the panel
background only (resources present in `SemWT_com.android.systemui` plus `qs_*` colours), and
never dimensions, layouts or tile shapes. This keeps the panel usable across versions.

## Live preview

The preview renders Compose mock-ups of Settings, quick settings, a notification, the dialer
and the keyboard from the same `ResolvedTheme` + pack layers that the device writer uses.
