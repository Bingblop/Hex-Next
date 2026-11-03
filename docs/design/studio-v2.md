# Studio v2: design spec

Status: shared contracts are in the tree. Four workstreams build on them in parallel.
Scope: the Android app (`android/app`, package `com.hexnext.themer`) and the engine
(`android/engine`, package `com.hexnext.engine`).

## 0. Why

The owner's feedback on the current build:

1. One seed colour is not enough. Every part of One UI must be colourable.
2. The live preview is not useful.
3. Themes look bland without colourful PNG icons.

The reference is the AI Studio console prototype. It has a dark navy console with cyan and coral
accents, an interactive customizer with steps, a phone mockup with tabs that is drawn in the
theme colours, a Color Engine card, a Monet shade ribbon, and a per-target resource list.
Studio v2 keeps the Kotlin app and its safety model, and brings in that depth.

These rules do not change:

- Only Samsung and One UI system packages are themed.
- Quick Settings theming is colour-only.
- No plugin templates are decrypted.
- Pack names stay original.
- The OtherBuild guard, ApplyGuard, safe mode and SemWT handling stay as they are.

## 1. Data model (implemented, in the engine)

All new fields have defaults. Old `theme.json` files therefore load unchanged, and `FORMAT` stays
at 1. `SpecJson` now sets `coerceInputValues = true`: an enum constant from a newer build (an
unknown `iconStyle`) falls back to the default instead of making the file unreadable.

| Item | Where | Meaning |
|---|---|---|
| `ThemeSpec.colorGroups: Map<String, GroupColor>` | `compile/ThemeSpec.kt` | Maps a `ColorGroup.id` to `GroupColor(light: Int?, dark: Int?)`. A null side means Auto. An unknown id is kept but ignored. |
| `ThemeSpec.iconStyle: IconColorStyle` | same | `PACK_ORIGINAL` (default), `ACCENT`, `MULTICOLOR`, `DUOTONE`, `GRADIENT`. |
| `ThemeSpec.builtInIcons: Boolean` | same | The app's own colourful icons for rows that no pack covers. It is `false` when the key is missing from JSON and `true` in `ThemeSpec.default()`. |
| `ThemeSpec.qsPanelAlpha: Int` | same | 0 … 255 alpha for the Quick panel backdrop resources. 255 = unchanged. |
| `enum ColorGroup` / `ColorGroupSection` | `compile/ColorGroup.kt` | The catalogue: `id`, `label`, `description`, `section`, Auto role and shades, `quickSettings` flag. |
| `ColorGroup.auto(theme, night)` | same | The Auto colour. |
| `ThemeSpec.groupColor` / `effectiveGroupColor` / `effectiveGroupColors` / `withGroupColor` / `withAllGroupsAuto` | same | Pure helpers. `withGroupColor(g, night, null)` returns that mode to Auto and drops empty entries. |
| `ColorGroupMatcher.classify` / `inventory` / `groupCounts`, `ResourceColor`, `GroupCount` | `compile/ColorGroupMatcher.kt` | **Stub.** Filled by engine-colours. |
| `IconColors.keepsPackPalette` (final), `style`, `styleReference`, `rowHue`, `builtInJobs` | `compile/IconColors.kt` | **Stub** except `keepsPackPalette`. Filled by icon-styles. |
| `CompositeStep.slot`, `CompositeStep.gradientEndArgb` | `pack/Pack.kt` | `LayerPlanner` fills `slot`. A gradient end makes the renderer draw a linear gradient tint. |
| `IconBadge`, `PlannedDrawableJob.badge` | `compile/ThemeCompiler.kt` | A built-in icon job (no pack): fill, optional gradient end, glyph colour. |

Compiler hooks already in `ThemeCompiler`:

- `elementJobs` plans icon elements with the pack palette only when `IconColors.keepsPackPalette`
  is true. This is PACK_ORIGINAL, which is now the default, so pack PNGs keep their own colours.
- Each plan passes through `IconColors.style(...)`.
- Synthesis references pass through `IconColors.styleReference(...)`, one per row.
- `compileDrawables` adds `IconColors.builtInJobs(...)` when `spec.builtInIcons` is on. Existing
  keys always win.
- `hashLine` hashes `gradientEndArgb` and badge jobs. Hashes of existing themes are unchanged.

The groups and their Auto values (light shade / dark shade):

| id | Label | Section | Auto |
|---|---|---|---|
| accent | Accent | Core | accent1 600 / 200 |
| background | Background | Core | neutral1 50 / 900 |
| surface | Surface and cards | Core | neutral1 10 / 800 |
| text_primary | Primary text | Text and icons | neutral1 900 / 50 |
| text_secondary | Secondary text | Text and icons | neutral2 700 / 200 |
| icon_tint | Icon tint | Text and icons | accent2 600 / 200 |
| switch | Switches and toggles | Controls | accent1 600 / 300 |
| button | Buttons | Controls | accent1 600 / 200 |
| slider | Brightness and volume sliders | Controls | accent1 500 / 300 |
| qs_panel_bg | Quick panel background | Quick panel (QS) | neutral1 100 / 900 |
| qs_tile_active | QS tile active | Quick panel (QS) | accent1 500 / 300 |
| qs_tile_inactive | QS tile inactive | Quick panel (QS) | neutral2 100 / 800 |
| notification_bg | Notification background | Quick panel | neutral1 10 / 800 |
| dialog | Dialogs and popups | System | neutral1 10 / 800 |
| keyboard_bg | Keyboard background | Apps | neutral2 50 / 900 |
| keyboard_key | Keyboard keys | Apps | neutral1 10 / 800 |
| dialer | Dialer and in-call | Apps | accent1 600 / 200 |
| lock_screen | Lock screen and AOD clock | System | accent1 100 / 100 |
| system_bars | Navigation and status bar | System | neutral1 50 / 900 |

Precedence on the device, highest first:

1. `colorPins` (per resource)
2. group colour (per group, per mode)
3. role inference (SemWT value re-derived from the palette)
4. Samsung's value, copied verbatim

The System UI Quick Settings filter still runs last.

## 2. Colour Studio (UI: colour-studio-ui)

The Colours step holds these cards, top to bottom.

### 2.1 Color Engine card

- **Seed pickers.** Three slots: Primary, Secondary and Tertiary. Each slot has a swatch, a hex
  field (reuse `HexField` / `parseHexColor` / `toHex`), the 12 presets and a hue/saturation picker
  sheet.
  - Secondary and Tertiary each have an Auto chip (null).
  - Primary still goes through `setSeed`, because the seed lives outside the spec.
- **Scheme variant.** A dropdown over `PaletteStyle.entries`, with friendly names.
- **Contrast.** A slider from -1 to 1 (`ThemeSource.contrast`), with stops at 0, 0.5 and 1.
- **Mode tabs: Light | Dark.** Each tab edits its own `ThemeAdjustments`:
  - accent saturation and background saturation (0 … 200)
  - background lightness (0 … 200)
  - "Vivid shades" switch (`accurateShades = false`)
  - tinted text switch
- **AMOLED.** Dark tab only. A segmented control for `PitchBlack`: Off, Surfaces, Surfaces and
  containers.
- **Monet ribbon.** 5 rows (accent1-3, neutral1-2) of 13 shades for the selected mode, from
  `ThemeResolver.resolve`.
  - Tap a shade to open the picker. The result goes to `shadeOverrides["accent1_500"]`.
  - A pinned shade shows a pin dot. Long-press, or the sheet's "Unpin", removes it.
  - A caption explains that pinning bends the neighbouring shades.

### 2.2 One UI element colours card

- One row per `ColorGroup`, grouped by `ColorGroupSection`.
- Each row shows the label, the description, a light swatch and a dark swatch, the resource count
  (`ColorGroupMatcher.groupCounts`, shown only after a scan), and an Auto/Custom state.
- Tapping a swatch opens the picker sheet: hex, presets, palette shades of the current theme, and
  "Back to Auto".
- A "Same for light and dark" toggle in the sheet writes both modes.
- Groups with `quickSettings = true` carry a "Colour only" badge.
- "Reset all to Auto" calls `withAllGroupsAuto`, behind `ConfirmDialog`.
- When the preview calls `onElementTap(group)`, the Studio switches to the Colours step (or the
  Quick panel step for QS groups), scrolls to that row, flashes it, and sets
  `highlightedGroup`.

### 2.3 Advanced: per-app resource editor

- A new `Screen.RESOURCES`, reached from a "Edit individual resources" row. It requires a scan;
  without one it shows `EmptyState` with "Scan phone".
- **Layout:**
  - App picker: a dropdown of scanned targets with their resource count. One UI system packages
    only, as scanned.
  - Search field: name substring.
  - Filter chips: All, Pinned, In a group, Kept fixed.
  - A `LazyColumn` of `ResourceColor` rows.
- **Each row:**
  - `color/name` in monospace
  - group label
  - observed swatch with hex
  - planned (or derived) swatch with hex
  - day/night badge
  - pin button: an editor sheet with Fixed colour or Palette shade (`ColorPin.Fixed` /
    `ColorPin.Shade`)
  - unpin
- A target on/off switch at the top (`targetEnabled`).
- **Data:** `ResourceEditorModel` (screen-scoped ViewModel) loads
  `SnapshotBuilder(ctx).build(only = setOf(pkg))` on `Dispatchers.IO`, then
  `ColorGroupMatcher.inventory(target, snapshot.systemShades, spec, OneUiRules.forProfile(...))`.
  It loads one app at a time (memory).

### 2.4 Quick panel step

- The QS groups (`qs_panel_bg`, `qs_tile_active`, `qs_tile_inactive`, `notification_bg`,
  `slider`).
- A QS panel opacity slider (`qsPanelAlpha`, shown as 0-100 %).
- The preview is forced to the Quick panel tab while this step is open.
- A note: "Quick Settings is colour-only on One UI 9."

## 3. Live preview (UI: live-preview-ui)

`PhonePreview(state, tab, onTabChange, dark, onDarkChange, onElementTap, modifier)` lives in
`ui/preview/PhonePreview.kt`. It is drawn only from `StudioPreviewState`
(`ui/studio/StudioPreviewState.kt`).

- **Frame.**
  - Rounded phone body: 9:19.5 aspect ratio, a maximum height of 640dp, scaled down on phones.
  - Status bar: time 09:41, battery, signal.
  - A tab row above the screen with `PreviewTab.entries`. The row scrolls.
  - A light/dark segmented switch.
  - A gesture bar.
- **Screens.** Every colour comes from `state.scheme(dark).color(group)`.
  - **Settings.**
    - Large title "Settings" (`TEXT_PRIMARY`) on `BACKGROUND`.
    - A search pill.
    - The account card on `SURFACE`.
    - 8 grouped row cards (`SURFACE`). Each row has an icon from `settingsIcons` (fallback: a
      circle of `fallbackArgb`), a title (`TEXT_PRIMARY`) and a summary (`TEXT_SECONDARY`).
    - One row with a `SWITCH` toggle, one `BUTTON`, and a link in `ACCENT`.
  - **Quick panel.**
    - Backdrop `QS_PANEL_BG` at `qsPanelAlpha` over `background` or a gradient.
    - Two large tiles (Wi-Fi active, Bluetooth active) in `QS_TILE_ACTIVE`.
    - A 4 × 2 grid of round tiles, mixed active and inactive.
    - Brightness and volume sliders in `SLIDER`.
    - Media card on `NOTIFICATION_BG`.
  - **Notifications.** Shade of `QS_PANEL_BG` with 3 notification cards on `NOTIFICATION_BG`,
    with an app icon dot, title and text, and action chips in `ACCENT`.
  - **My Files.**
    - `BACKGROUND` with a category grid of `myFilesIcons` (fallback circles).
    - A storage card on `SURFACE` with a progress bar in `ACCENT`.
    - A recent files list.
  - **Dialer.** A keypad on `BACKGROUND` / `SURFACE` with digits in `TEXT_PRIMARY`, a call button
    in `DIALER`, and a mini in-call strip.
  - **Keyboard.** A chat bubble stub on top. The keyboard area is `KEYBOARD_BG` with 3 rows of
    keys in `KEYBOARD_KEY`, key text in `TEXT_PRIMARY`, and the enter key in `ACCENT`.
  - **Power menu.** A dim scrim with a `DIALOG` sheet holding Power off, Restart and Emergency
    buttons (`BUTTON`), a lock-screen clock sample in `LOCK_SCREEN`, and the navigation bar in
    `SYSTEM_BARS`.
- **Interaction.**
  - Every drawn element is wrapped in a `Modifier.clickable` that calls `onElementTap(group)`,
    with `semantics { contentDescription = "<group label>, edit colour" }`.
  - `highlightedGroup` elements get a 2dp dashed or animated outline in the console cyan.
  - Light/dark switching uses `animateColorAsState`, or no animation when
    `LocalAppSettings.current.reduceMotion` is set.
- **Speed.** Colours update on every edit with no compile. The VM recomputes
  `StudioPreviewState.colorsOf(spec, seed)`, which is one resolve (about 1 ms). Icons come from
  the debounced compile preview (400 ms). While icons load, the previous bitmaps stay on screen.
- Text uses `fontFile` when present (reuse the `rememberFontFamily` pattern, copied locally).

## 4. Icon colour styles (icon-styles)

| Style | Pack icons | Synthesised icons | Built-in icons |
|---|---|---|---|
| Pack original (default) | Pack palette only, never theme-tinted | Copy the reference (pack look) | Drawn as Multicolour |
| Accent tint | Today's derived palette | Copy the reference | Accent1 badge, white glyph |
| Multicolour | Accent-slot layers get a per-row hue (`rowHue`), keeping tone, chroma ≥ 48 | Per-row hue through `styleReference` | Per-row hue |
| Duotone | 1st accent step accent1, later ones accent3, same shade | Same | accent1 badge, accent3 glyph |
| Gradient | Multicolour plus `gradientEndArgb` (hue + 40°) | Same | Gradient badge |

- Only steps whose `slot` matches `[l]?(xx|xy|xz)<digit>` and maps to an ACCENT role in
  `SlotPalette.ROLES` change. Neutral, fixed and untinted layers keep the pack's contrast design.
- Row hues follow stock One UI, as in the reference screenshot:
  - connections blue
  - sound teal-green
  - notifications orange or cyan
  - display yellow-green
  - wallpaper pink
  - lockscreen coral
  - biometrics violet
  - accounts cyan
  - Google multicolour (use blue)
  - unknown rows: a stable hash on a 12-step wheel
- The renderer draws gradients with `ComposeShader(BitmapShader, LinearGradient, MULTIPLY)`, and
  `IconBadge` jobs as a squircle with the `hx_glyph_<row>` glyph at about 52 %. New My Files
  glyphs are added as `hx_glyph_myfiles_*`.
- `StyledIconPreview` (app) renders the built-in icons for the preview when there is no scan or
  pack.
- System UI never gets drawables (`qsBlocked` and the `customJobs` drop stay).

## 5. Visual refresh (colour-studio-ui)

- **Console palette.** New `ui/studio/StudioTheme.kt`. It applies only to the editor, so the
  other screens keep `HexNextTheme` and do not change.
  - Dark: background `#0B1220` (navy), surface `#111A2B`, card `#162238`, outline `#24324A`,
    primary cyan `#22D3EE`, secondary coral `#FF5A6E`, tertiary amber `#FBBF24`, on-surface
    `#E6EDF7`, muted `#8A9BB4`.
  - Light (when the app theme is light): background `#F4F7FB`, primary `#0891B2`, coral
    `#E11D48`.
  - Build it as a full M3 `ColorScheme` wrapped in `MaterialTheme(colorScheme = ...,
    typography = HexNextTypography, shapes = MaterialTheme.shapes)`.
  - When the user chose dynamic or seed app colours in Appearance, the editor follows them.
    "Console" becomes the new default palette option. This is an additive
    `AppSettings.palette` value; keep the old values working.
- **Editor layout.**
  - Top app bar: "Hex Next" with the status pill.
  - Always visible above the steps: the safe-mode banner and a compact connection strip.
  - Step tabs, as a scrollable `PrimaryScrollableTabRow` or `SingleChoiceSegmentedButtonRow` in a
    horizontal scroll:
    1. **Skins**: AI describe card, packs, catalogue
    2. **Colours**: Color Engine, element colours, advanced editor entry
    3. **Quick panel**
    4. **Icons**: icon style, built-in icons switch, synthesise switch, custom icons
    5. **Fonts & backgrounds**
  - Width under 840dp: the phone preview is a collapsible card under the tabs (collapsed height
    280dp, tap to expand).
  - Width 840dp and over: two panes, preview left (sticky), steps right.
  - The Apply card is pinned at the bottom of every step.
  - The selected step survives rotation (`rememberSaveable`).
- Monospace labels for resource names (`FontFamily.Monospace`), small caps section headers in
  muted text, and 1dp outline cards. This matches the reference without copying its chrome.
- Unchanged and still reachable: Settings, Diagnostics, AI providers, Onboarding, billing and
  unlock UI, the log, snackbars, and Back handling.

## 6. ViewModel API (colour-studio-ui adds to `MainViewModel`)

Everything goes through the existing private `updateSpec`, which persists and schedules the
compile preview. Drags use the draft API so the store is written once, on release.

```kotlin
val studioPreview: StateFlow<StudioPreviewState>        // colorsOf(draftOrSpec, seed) + icons from PreviewState
fun editSpec(block: (ThemeSpec) -> ThemeSpec)            // public wrapper of updateSpec
fun previewDraft(spec: ThemeSpec?)                       // live colours only, no save; null clears
fun setSecondarySeed(argb: Int?)
fun setTertiarySeed(argb: Int?)
fun setPaletteStyle(style: PaletteStyle)
fun setContrast(contrast: Double)
fun setAdjustments(night: Boolean, block: (ThemeAdjustments) -> ThemeAdjustments)
fun setShadeOverride(night: Boolean, key: String, argb: Int?)
fun setGroupColor(group: ColorGroup, night: Boolean, argb: Int?)
fun setGroupColorBoth(group: ColorGroup, argb: Int?)
fun resetGroupColors()
fun setColorPin(resourceName: String, pin: ColorPin?)
fun setTargetEnabled(packageName: String, enabled: Boolean)
fun setIconStyle(style: IconColorStyle)
fun setBuiltInIcons(enabled: Boolean)
fun setQsPanelAlpha(alpha: Int)
fun setHighlightedGroup(group: ColorGroup?)
```

How `studioPreview` is built:

- It combines `_ui` (spec, seed, preview) with a `MutableStateFlow<ThemeSpec?>` draft.
- It maps on `Dispatchers.Default` with `stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), …)`.
- Icons come from `PreviewState`: `ThemePreviewer.PreviewIcon.bitmap?.asImageBitmap()`.
- When there is no icon or scan, it falls back to `StyledIconPreview` icons. The conversion is
  cached per bitmap identity.

## 7. Workstreams

Owned files are disjoint. The shared contract files below are frozen: only additive changes, made
by the named owner.

- `ColorGroup.kt`, `ThemeSpec.kt`, `ColorGroupMatcher.kt` signatures: engine-colours.
- `IconColors.kt` signatures, `Pack.kt` `CompositeStep`: icon-styles.
- `StudioPreviewState.kt`: colour-studio-ui.
- `PhonePreview` signature: live-preview-ui.

The briefs are in the workflow output, and section 1 lists the exact APIs.

## 8. Acceptance (whole feature)

- `cd android/engine && gradle test` passes. The new tests cover:
  - group classification on the Settings SemWT fixture
  - precedence (pin > group > inference)
  - QS policy still enforced
  - PACK_ORIGINAL keeps pack tints
  - MULTICOLOR gives distinct row hues
  - old JSON loads
- A fresh install shows:
  - a console-styled editor with 5 steps
  - a phone preview with 7 tabs that changes colour instantly when any control moves
  - colourful Settings icons in the preview even before a scan
- Setting "QS tile active" to coral and applying (root) recolours the active QS tiles only.
  Settings switches follow "Switches"; Samsung Keyboard follows the keyboard groups.
- Diagnostics, onboarding, settings, AI generate and describe, pack unlock and purchase, safe mode
  and "It works" behave as before.
- No Compose lint errors (`lintRelease`).
