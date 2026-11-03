# Studio v2, phase 2: the console look on a phone

Status: design spec for two parallel workstreams (UI port, pack rename).
Builds on `docs/design/studio-v2.md` (data model, Colour Studio, live preview, icon styles).
Everything in section 9 of that file still holds.

## 0. Owner direction (2026-11-03)

- The app must look and feel like the AI Studio web build (https://hex-next.ai.studio/).
  It has real-time theme viewing.
- Every feature of the current Kotlin app moves over and works the same way.
- Optimise the UI so that it is easier to navigate and to read.
- Bug-check the result.
- The current onboarding layout stays as it is.
- The AI Studio build uses the system font. No bundled or web fonts.
- Extra plugins (packs) stay locked down. They get names that in no way allude to the legacy
  plugin APKs.

Reference material, read only:
`/mnt/project-files/Hex Next/ai-studio-web/` (README.md, screenshot.png, page.md) and
`/mnt/project-files/Hex Next/Kotlin Google AI Studio/*`.

### 0.1 What "look and feel like" means here

We take the look of the site:

- dark navy console
- cyan and coral accents
- 1 dp outlined cards
- status chips
- phone mockup with tabs
- Color Engine tiles
- per-resource rows with a monospace name, hex value and swatch
- per-target status chips

We do **not** take its desktop density. The site is a 1920 px, three-column console with 11 px
monospace text. The app is a phone app:

- one task per screen
- bottom navigation
- readable type (section 4)
- touch targets of 48 dp or more

### 0.2 Rules that do not change

- English only. No AI model names in code or comments.
- Only Samsung and One UI system packages are themed.
- Quick Settings theming changes colours, opacity and a few sizes One UI itself defines (tile and panel corner radius, slider thickness, notification corner radius), each written only when the phone's scan lists that resource (`quickSettings.values` in `oneui/9.json`). The sizes go in a separate `HexNextValues_<pkg>` overlay that the staged stability monitor turns off first. No QS drawables, tile layouts or blur.
- No plugin templates are decrypted.
- These stay intact:
  - the OtherBuild guard (`safety/OtherBuild.kt`, `ui/BlockedScreen.kt`)
  - ApplyGuard / restart protection / "It works"
  - safe mode (`SafeModeBanner`, `EarlyRecoveryHook`, `BootReceiver`)
  - SemWT handling (`theme/SemWtController.kt`)
  - billing and entitlement checks (`billing/**`, `src/play`, `src/owner`)
  - the owner flavor
- No secrets in the tree.
- **No legacy names anywhere in the UI.** The site shows legacy plugin and brand names:
  "Project X", "Blend", "Glass", "ayOS", "AOSP R", "MIMI", "Terpsichora", "Bodhi", "#hex_",
  "Drive APK", "anaconda_*", "crocodile_*", `project.vivid.*` packages, plugin authors.
  None of these are ported. Pack names come only from the installed packs and the catalogue,
  which the rename workstream regenerates (appendix B).
- No mock data. Every number, chip and list on screen comes from app state. When there is no
  state yet (no scan, no apply), the screen shows an `EmptyState` with the action that produces
  the state.

## 1. Navigation map (phone)

### 1.1 Overview

`Screen.MAIN` becomes a shell. It has:

- a top app bar
- a global status strip
- the content of one bottom-navigation destination
- a persistent Apply bar above the navigation bar

The pushed screens stay as they are: `SETTINGS`, `DIAGNOSTICS`, `AI_PROVIDERS`, `ONBOARDING`,
`RESOURCES`. They keep their own back handling.

```
Onboarding (unchanged, first run and "Setup guide")
        |
        v
+-------------------------------------------------------------+
| Top bar: "Hex Next"  [status pill]              [gear]      |  gear -> App settings
| Safe-mode banner (only when tripped)                         |
| Status strip: Privilege chip | Targets n/m chip | Guard chip |
|-------------------------------------------------------------|
|                 destination content                          |
|-------------------------------------------------------------|
| Apply bar: [Apply theme]  [Remove]  ("It works" when armed)  |
| Bottom nav:  Studio | Skins | Colours | Icons | System       |
+-------------------------------------------------------------+
```

### 1.2 Bottom-navigation destinations

There are 5 destinations, held in `rememberSaveable` and kept by name. Material core icons only
(`material-icons-core`): Home, Star, Create, Face, Build. New vector drawables in
`res/drawable/` are fine instead.

| # | Destination | AI Studio tab it replaces | Holds |
|---|---|---|---|
| 1 | **Studio** (start) | Studio & Live Preview | The full live phone (`PhonePreview`, all `PreviewTab`s) with the light/dark switch. Tapping an element opens the colour sheet for that group *in place*, so the user does not jump to another tab. Under the phone: the "Describe your theme" AI card, a scan prompt before the first scan, and the preview note. |
| 2 | **Skins** | Settings Icons & Tweaks (pack half) + "Theme Plugins" list | The pack list with a search field, palette dots, a lock/unlock state and price, Unlock all, Import pack, the catalogue (Browse / Download), Simple or Advanced mode, the per-element pack rows, and the pack options. |
| 3 | **Colours** | Color Engine card + Monet 65-Shades tab + QS Bars step | A segmented control with **Engine**, **Elements**, **Quick panel**, **Shades**. A compact preview peek sits above it (1.4). |
| 4 | **Icons** | Settings Icons & Tweaks (icon half) + APK .PNG step | A segmented control with **Style**, **Custom icons**, **Fonts**, **Backgrounds**. A preview peek sits above it. |
| 5 | **System** | RRO Matrix + Manifests & CLI | A segmented control with **Targets** and **Tools**. |

### 1.3 Shell rules

- **Back.** On destinations 2 to 5, Back returns to Studio. On Studio, Back leaves the app as
  today. A pushed screen's Back returns to the destination it came from, with its sub-tab and
  scroll position kept (`rememberSaveableStateHolder`, as `MainScreen` does now).
- **Apply bar.** The Apply bar is the current `CompiledThemeCard` logic in a compact bar:
  - same enable rules: `connected = ready && !viaSu && capability != NONE`, `busy`, `serviceBusy`
  - Apply and Remove, and "It works" while `applyUnconfirmed && !applyProtectionExpired`
  - the last progress line while applying
  - tapping the summary opens System > Targets

  Its height is about 64 dp, or about 88 dp while "It works" is showing. It never hides content:
  the destination lists use bottom content padding equal to the bar.
- **Snackbars.** Notices, haptics and actions keep the current `LaunchedEffect(notice?.id)`
  logic and `NoticeAction` handling, unchanged. The snackbar host sits above the Apply bar.
- **Status strip.** The strip is one row of chips that scrolls horizontally if it is too narrow.
  Each chip is 32 dp high inside a 48 dp touch area.
  - **Privilege** shows `statusPillText`, as today. Tap: a sheet with the full `StatusCard`
    (connection, tier, version line, root guide, Request permission).
  - **Targets** shows "Targets 22/26" from `ui.lastApply` (okCount / outcomes.size). It is
    hidden before the first apply. Tap: System > Targets.
  - **Restart protection** shows "Protection on". It appears only while ApplyGuard is armed.
    Tap: "It works".

  The safe-mode banner stays above the strip as today (`SafeModeBanner`).
- **Wide screens (840 dp and over).**
  - A `NavigationRail` replaces the bottom bar.
  - Studio shows the phone on the left and the Studio extras on the right.
  - Colours and Icons show the full phone in a sticky left pane instead of the peek, as the
    current two-pane mode does.
- **Preview peek (Colours, Icons).** The peek is a 200 dp, top-clipped `PhonePreview` card with
  the same state as Studio (shared `previewTab` and `previewDark` hoisted in the shell).
  - "Expand" opens a full-height modal sheet with the whole phone.
  - The peek can be collapsed to a 56 dp row ("Live preview · Settings · Dark"). The choice is
    saved.
  - The Quick panel sub-tab forces the QS preview tab, as today.
- **Element taps from the preview.**
  - On Studio, a tap opens `ColorPickerSheet` for that group right away.
  - On Colours, a tap switches to Elements, or to Quick panel for groups with
    `quickSettings = true`. It scrolls to the row and flashes it (`setHighlightedGroup`, as today).

### 1.4 Pushed screens

| Screen | Reached from | Notes |
|---|---|---|
| `RESOURCES` (`ResourceEditorScreen`) | Colours > Elements "Edit individual resources", System > Targets row tap ("Resources" action) | Restyled with the tokens. Logic unchanged. |
| `DIAGNOSTICS` (`DiagnosticsScreen`) | System > Tools "Diagnostics", snackbar action, Apply bar "Details" | Restyled with the tokens. Every action kept. |
| `SETTINGS` (`AppSettingsScreen`) | Top bar gear | Restyled with the tokens. Every card kept (Appearance, Behaviour, AI providers, Diagnostics, Purchases, About). |
| `AI_PROVIDERS` | AI cards, App settings | Restyled. |
| `ONBOARDING` | First run, About > Setup guide | **Untouched.** Keeps `HexNextTheme` and the current layout. |

## 2. AI Studio element map

Each row maps an AI Studio item to the real function behind it, or drops it.
Paths are relative to `android/app/src/main/java/com/hexnext/themer/` unless they start with
`engine:`, which means `android/engine/src/main/kotlin/com/hexnext/engine/`.
VM means `ui/MainViewModel.kt`.

### 2.1 Header and status strip

| AI Studio element | Real backing | Phone placement |
|---|---|---|
| Top tab bar (5 tabs) | Shell destinations (1.2) | Bottom nav |
| "PRIVILEGE: Shizuku UID 1000 (system)" dropdown | `statusPillText`, `StatusPill`, `StatusCard`, `TierPill` (`ui/MainScreen.kt`); `theme/Capability.kt`; `ShizukuState`; VM `requestPermission` | Privilege chip + sheet |
| "Release & PR CI" | dropped: CI is not a device feature | n/a |
| "Signed APK (64/32-bit)" | dropped: the app cannot build or sign itself | n/a |
| "Build & Apply RROs" | VM `applyTheme`, `CompiledThemeCard` logic (`ui/MainScreen.kt`) | Apply bar |
| "theme.json" button | **new, real**: export the spec with `engine: compile/ThemeExport.export(spec)` (a `.hexnext` zip whose `theme.json` entry is `ThemeSpec.toJson()`) through SAF `CreateDocument`. Import with `ThemeExport.read` → VM `replaceSpec` + `setSeed(spec.source.seedArgb)`, behind `ConfirmDialog`. | System > Tools |
| "Export ZIP" | merged into the export above (a `.hexnext` file is a zip) | n/a |
| "Opening Gate: UID 1000 … / Transaction Owner" | `StatusCard` (uid, tier, version line) | Privilege sheet |
| "Drive APKs Ported: Project X • …" | dropped: legacy names | n/a |
| "Overlay Precedence … > SemWT_*" | Real fact text plus `ApplyReport.semwtDisabledCount`: "Hex Next overlays sit above Samsung theme overlays; n Samsung overlays turned off" | System > Targets header |
| "Active Targets 22/26" | `ui.lastApply.okCount` / `outcomes.size` | Targets chip |
| "One UI 9.0 Tokens: 220" | replaced: "Colours written" = Σ `TargetOutcome.colorCount` of the last apply | Targets header |
| "Auto Safe-Mode: ARMED (1x)" | ApplyGuard: `ui.applyUnconfirmed && !ui.applyProtectionExpired`; VM `confirmWorks`, `refreshGuard` | Protection chip |
| "Disable All RROs (Safe Mode)" | `DiagnosticsViewModel.removeAll` ("Turn off all Hex Next overlays") behind its existing `ConfirmDialog` | System > Tools > Safe mode card |
| Automatic safe mode fallback banner | `SafeModeBanner(ui.safeModeBanner, ui.safeModeFailed)`, VM `dismissSafeMode` | Global banner |
| "Restore Healthy RROs (Skip Crashing Target)" / "Repair All & Restore 26/26" | replaced by the real "Apply again" (`DiagnosticsScreen.onApplyAgain`) and "Open diagnostics" | Banner actions |

### 2.2 Studio & Live Preview tab

| AI Studio element | Real backing | Phone placement |
|---|---|---|
| "Interactive Customizer Mode" steps (All / Skins / Colors / QS Bars / Icons / APK .PNG) | Bottom destinations and sub-tabs. "All" is dropped. "APK .PNG" is the pack element rows (pack PNGs come from packs). | n/a |
| Phone mockup with tabs QS / Settings / Files / Power / Apps / Dialer / Keys | `ui/preview/PhonePreview.kt` + `PreviewFrame`, `PreviewQuickPanel`, `PreviewSettings`, `PreviewMyFiles`, `PreviewDialer`, `PreviewKeyboard`, `PreviewNotifications`, `PreviewPowerMenu`; state from VM `studioPreview` (`StudioPreviewState.colorsOf`). "Apps" is dropped (there is no real app-grid data); Notifications covers the shade. | Studio (full), peek elsewhere |
| QS pill tiles, round toggles, brightness and volume gradient sliders | `PreviewQuickPanel`, colour-only groups `qs_tile_active`, `qs_tile_inactive`, `slider`, `qs_panel_bg` | Studio |
| "Safe One UI 9.0 QS Drawables" card in the phone | dropped: QS is colour-only, so there are no QS drawables | n/a |
| "Theme Plugins: 13 Skins" list with search | `PackCard` (`ui/MainScreen.kt`), `ui.packs`, VM `selectPack`, `importPack`, `deletePack`; the search filters `InstalledPack.name` (new, client-side) | Skins |
| "Google Drive APK Collection (One-Click Apply)" | dropped: legacy names. "One-click" is VM `selectPack` + Apply bar | n/a |
| Three seed dots per skin | `InstalledPack.pack.paletteArgb` (first 3, `or OPAQUE_ARGB`) | Skins row |
| "66 OVERLAYS" / "DRIVE APK" badges | replaced: `drawableCount` and the categories summary (`packSummary`), a "Day & night" badge from `pack.dayNight`, and the lock badge (`PackLocks`, `LockedLabel`) | Skins row |
| Package name under each skin (`project.vivid.…`) | dropped: never shown. Packs carry only `com.hexnext.pack.<id>`, and that is not shown either | n/a |
| Color Engine card: Primary / Secondary / Settings surface / Card surface tiles | `ui/studio/ColorEngineCard.kt`: Primary via VM `setSeed`/`previewSeed`, Secondary/Tertiary via `setSecondarySeed`/`setTertiarySeed`. "Settings surface" = group `background`, "Card surface" = group `surface` via `setGroupColor` (`ColorPickerSheet`) | Colours > Engine |
| AMOLED #000 toggle | `ThemeAdjustments` pitch black via VM `setAdjustments(night = true)` (existing segmented Off / Surfaces / Surfaces and containers) | Colours > Engine |
| Dynamic scheme variant dropdown (Tonal Spot … Fruit Salad) | VM `setPaletteStyle(PaletteStyle)` | Colours > Engine |
| QS panel opacity slider | VM `setQsPanelAlpha` (`QuickPanelCard`, `ui/studio/QuickPanelStep.kt`) | Colours > Quick panel |
| Live Monet seed ribbon | `ui/studio/ShadeRibbon.kt` | Colours > Engine (compact), Colours > Shades (full) |
| Theme profile name (`theme.json`) / Download / Copy JSON / Import | Name: `ThemeSpec.name` via VM `editSpec { it.copy(name = …) }`. Download/Import: export/import in 2.1. Copy JSON: `ThemeSpec.toJson()` to the clipboard (read-only) | System > Tools > Theme file |
| Per-resource list (`qs_background_dark` #000000 + swatch) | `ui/studio/ResourceEditorScreen.kt` + `ResourceEditorModel` (`ColorGroupMatcher.inventory`) | Pushed `RESOURCES` |
| "SemWT_* Precedence Note" with struck-out removed resources | dropped: invented resource history | n/a |
| "All 26 RROs →" | System > Targets | n/a |
| From-Scratch RRO Compiler Console (22/26 active, 2 faults, per-target chips) | System > Targets (2.4) | n/a |
| "Stream Pass 148 ms", "NON-LINEAR cubic-bezier…" | replaced: the real duration `lastApply.finishedAt - startedAt` and per-target `TargetOutcome.millis`. The bezier label is dropped | Targets header |

### 2.3 Settings Icons & Tweaks tab

| AI Studio element | Real backing | Phone placement |
|---|---|---|
| "APK .PNG (FRAMED)" active pack line | Per-element pack: `ElementRow` / `ElementPackDialog` (`ui/MainScreen.kt`), VM `setElementPack`, `setElementEnabled`, `setMode(ADVANCED)` | Skins > per-element rows |
| Fixed .PNG vs palette-tinted switch ("Uncheck to tint all icons") | `IconStyleCard` (`ui/studio/IconStyleCard.kt`): `IconColorStyle.PACK_ORIGINAL` vs `ACCENT` / `MULTICOLOR` / `DUOTONE` / `GRADIENT`, VM `setIconStyle` | Icons > Style |
| Built-in icons for rows no pack covers | VM `setBuiltInIcons` | Icons > Style |
| Preset tiles (Terpsichora 3sett, MIMI, Project X, Blend, Glass, ayOS/AOSP R) | replaced by the user's installed packs, named from the catalogue, as the Settings icons source (`setElementPack(SETTINGS_ICONS, id)`). The legacy names are dropped | Skins |
| "Palette Primary" / "Palette Duotone" tiles | `IconColorStyle.ACCENT` / `DUOTONE` | Icons > Style |
| "Remove Icons Frame (settframe)", "Large icons (bigsetico)", "Hide account (hidemail)", "About logo (uselogo)", "Dividers (uidivider)", 4G/LTE, in-out arrows, switch knob tint, keyboard key border, navbar style, dial button tint | Real **only** when the selected pack offers the option: `OptionList` over `PackOptions` / `LiveOption`, VM `setOption(pack, live, value, elementId)`. Option labels are the pack's own (already scrubbed by the pack tools). There are no hard-coded tweak toggles | Skins > pack options |
| Notification corner radius, hide carrier label | native extras (`extras/native.json`): `roundnoti` sets the notification corner size, `hidcarriernoti` / `qshc` zero the carrier label alpha | pack card |
| QS icon style, QS toggle size, panel blur, QS volume panel layout | dropped: no drawables or layout in QS | n/a |
| 26 SecSettings dashboard icons grid | Preview Settings tab icons (`StudioPreviewState.settingsIcons`), custom icon slots (`CustomIconsCard`, `ui.iconSlots`, VM `assignIcon`) | Icons > Custom icons |
| "Connections — PX Fixed .PNG (28dp)" per-icon colour lock | `CustomIconsCard` assign / unassign; per-icon colour is not a feature (dropped) | Icons > Custom icons |
| "All 7 APK skins + colour palette vector mode across 8 apps" | dropped: legacy names; covered by Style + packs | n/a |
| AI icon generation | `ui/ai/GenerateIconSheet.kt` from `CustomIconsCard` (`onGenerated` → VM `noteGeneratedIcon`), providers via `Screen.AI_PROVIDERS` | Icons > Custom icons |
| Synthesise missing icons | VM `setSynthesizeMissingIcons` (shown in Style; today `showSynthesize = false` hides it in the custom icons card) | Icons > Style |
| Fonts | `FontCard`: VM `importFont`, `setFont`, `setFontBold`, `deleteAsset` (root-only note when `capability == SHELL`) | Icons > Fonts |
| Backgrounds | `BackgroundsCard`: VM `importCustomBackground`, `assignBackground`, `deleteAsset`, `openScan` | Icons > Backgrounds |

### 2.4 RRO Matrix tab

| AI Studio element | Real backing | Phone placement |
|---|---|---|
| Filter chips All / Active / Failed / High-Freq / Disabled | All / OK / Partial / Failed / Skipped from `ApplyReport.outcomes[].result` (`TargetResult`). Add "Off" for `spec.targetEnabled[pkg] == false`. "High-Freq" is dropped | Targets filter chips |
| Target status map (26 targets: name, package, status icon, counts) | One row per `TargetOutcome`: friendly app name (from `engine: compile/Elements.kt` element names whose package list contains the target, or the device's app label via `PackageManager`; else `target`), the package in monospace, a status chip, `colorCount` / `drawableCount` / `resourceCount`, `verified` (Verified / Mismatch), `semwtDisabled`, `millis`, `message` (expandable). Before the first apply: the list of scanned One UI targets from the last `DeviceReport.packages` with a "Not applied yet" chip, else an `EmptyState` with "Scan phone" (VM `openScan`) | System > Targets |
| Per-target on/off | VM `setTargetEnabled(pkg, enabled)` | Target row switch |
| Per-target resource list | Row action "Resources" → `Screen.RESOURCES` preselected to that package (one app at a time; `ResourceEditorModel`) | n/a |
| Automatic safe mode watchdog card, "Simulate system crash", trigger thresholds | dropped as controls: simulation and thresholds are not real. Replaced by a read-only "Restart protection" card: armed / expired state, "It works", and what happens after a failed boot (the existing copy in `CompiledThemeCard`) | Targets top card |
| High-frequency failure monitor, fault spike simulation | dropped: invented telemetry | n/a |
| Shizuku / UID 0 batch repair and sequential recompile | replaced: "Apply again" (VM `applyTheme`), and in Diagnostics "Repair Settings test overlay" (`DiagnosticsViewModel.repairSettings`), "Monitor status" (`refreshMonitor`) | Targets footer + Diagnostics |
| Recent logs: overlay build and idmap2 diagnostics with level filters | `LogCard(ui.log)` with filter chips by `LogLevel` (All / Errors / Warnings / OK / Info), "Clear" (VM `clearLog`), "Share build log" (`ApplyReport.logFile`, existing share in `DiagnosticsScreen`) | System > Tools > Build log |
| "Elevate UID 0" / "Locate card" buttons on log lines | dropped (no action exists); the Privilege sheet covers permission | n/a |

### 2.5 Monet 65-Shades tab

| AI Studio element | Real backing | Phone placement |
|---|---|---|
| 65 tonal shades (5 palettes × 13 stops) | `ShadeRibbon` full grid from `ThemeResolver.resolve` via `StudioPreviewState`. Tap: `ColorPickerSheet`. Result: VM `setShadeOverride(night, "accent1_500", argb)`. A pinned shade shows a dot. "Unpin" or long-press → `setShadeOverride(..., null)` | Colours > Shades |
| Light / dark matrix | Light / Dark segmented (existing per-mode adjustments) | Colours > Shades |
| 23 Material 3 dark dynamic roles (`system_*_dark`) | Real, read-only: computed from `engine: palette/ThemeResolver.tunedScheme(source, isDark, adjustments)` with the engine's material dynamic colour roles (`engine/mcu/dynamiccolor`). It shows the role name (monospace) and hex. If a role is not exposed without engine changes, it is left out; the list is not padded to 23 | Colours > Shades, collapsible "System roles" |
| Android framework table (`android:color/system_*`) | Same shades as the grid, labelled `system_accent1_500` etc. Drawn as a long-press tooltip on each grid cell, not as a separate table | n/a |

### 2.6 Manifests & CLI tab

| AI Studio element | Real backing | Phone placement |
|---|---|---|
| "Select From-Scratch RRO Package" + AndroidManifest.xml + colors.xml listings | dropped as code listings (they are not what the app commits; overlays are fabricated through the service). Replaced by the real per-target summary (2.4) and "Share build log" | n/a |
| Privileged UID 0 / UID 1000 fabrication commands | dropped: the app never asks the user to run commands. The root guide (`RootGuide`, `ui.rootToolLinks`) stays in the Privilege sheet | n/a |
| Shareable `theme.json` configuration | Theme file card: name field, Export (`.hexnext`), Import, Copy JSON (2.1) | System > Tools |
| Emergency RRO rescue (`cmd overlay disable-all`) | Safe mode card: "Turn off all Hex Next overlays" (`DiagnosticsViewModel.removeAll`, existing confirm text), plus VM `removeTheme` | System > Tools |
| Release readiness / GitHub PR CI report, Download signed APK / release ZIP | dropped: not device features | n/a |
| (no site equivalent) Diagnostics | `DiagnosticsScreen`: Scan phone, Share report, Share summary, Settings test overlay, Repair, Monitor, It works, Turn off all, live log | Tools > "Diagnostics" row |

### 2.7 System > Tools, top to bottom

1. **Theme file**: name, Export, Import, Copy JSON
2. **Build log**: last 200 lines, level filter, Clear, Share build log
3. **Safe mode**: turn off all overlays, Remove theme
4. **Diagnostics**: scan and report (opens the screen)
5. **Device**: model and One UI version from `ui.versionLine`

## 3. Visual tokens

`ui/studio/StudioTheme.kt` and `ui/Theme.kt` `consoleColorScheme` move to these values. The
console palette (App settings > Appearance > Console, the default) applies to the whole shell
and every pushed screen except Onboarding. The "Phone colours" and "Custom colour" palettes keep
following `HexNextTheme`, as today.

### 3.1 Colours (dark, the default)

| Token | Hex | M3 slot |
|---|---|---|
| bg | `#07090E` | background, surface |
| surface-1 (cards) | `#121A2B` | surfaceContainer, surfaceContainerLow |
| surface-2 (raised / sheet / selected row) | `#182235` | surfaceContainerHigh, surfaceContainerHighest |
| outline | `#1E293B` | outlineVariant (card borders) |
| outline-strong | `#334155` | outline (inputs, dividers) |
| text-strong | `#F1F5F9` | onBackground, onSurface |
| text-body | `#94A3B8` | onSurfaceVariant |
| text-disabled | `#64748B` | disabled content only, never body text |
| cyan | `#67E8F9` | primary (text and icons on bg) |
| cyan-strong | `#00E5FF` | primaryContainer fills, selected tab indicator, Apply button |
| on-cyan | `#04131A` | onPrimary, onPrimaryContainer |
| coral | `#FF2A54` | secondary, error |
| on-coral | `#FFFFFF` | onSecondary, onError |
| green (OK) | `#22C55E` | status only |
| amber (warn / partial) | `#FBBF24` | status only, tertiary |

Other rules:

- **Pure black** (App settings): bg and surface become `#000000`. Cards keep `#121A2B`.
- **Light** (only when App settings is light):
  - bg `#F4F7FB`, cards `#FFFFFF`, outline `#E2E8F0`
  - text `#0F172A` / `#475569`
  - primary `#0891B2`, coral `#E11D48`, green `#15803D`, amber `#B45309`
- **Status chips.** The fill is the status colour at 14 % alpha on the card. Text and dot are
  the status colour in dark mode. In light mode use the darker light-mode value.
  - OK: green
  - Partial: amber
  - Failed: coral
  - Skipped, Off, Not applied: text-body on surface-2
  - Info and selection: cyan
- **Contrast floor.** Body text needs 4.5:1 on its background and large text 3:1.
  - `#94A3B8` on `#121A2B` is about 6.8:1.
  - `#67E8F9` on `#07090E` is about 13:1.
  - Never put text-body on surface-2 under 14 sp.

### 3.2 Shapes

| Use | Radius |
|---|---|
| Cards (`StudioCard`, `SectionCard`) | 16 dp, 1 dp `outline` border, no elevation |
| Rows inside cards, text fields, swatch tiles | 12 dp |
| Buttons | 12 dp (filled Apply: cyan-strong) |
| Chips and pills (status, filters, badges) | full (50 %) |
| Bottom sheets | 24 dp top |
| Phone mockup | 36 dp body, 28 dp screen (as `PreviewFrame`) |
| Colour swatches | 10 dp squircle (tiles), circle (palette dots 14 dp) |

### 3.3 Spacing

- The base unit is 4 dp.
- The screen gutter is 16 dp.
- Card padding is 16 dp.
- Gap between cards is 12 dp. Gap between rows in a card is 8 dp.
- The touch target minimum is 48 × 48 dp. That covers every chip, swatch, switch row, icon
  button and preview element; visual size may be smaller, `minimumInteractiveComponentSize`.
- List rows are at least 56 dp tall (64 dp with two text lines).
- The bottom padding of every destination list is the Apply bar height + 16 dp.

### 3.4 Type scale (system font only)

`FontFamily.Default` everywhere. `FontFamily.Monospace` is allowed **only** for:

- hex codes
- resource names (`color/qs_background_dark`)
- package names
- log lines

No bundled, downloadable or web fonts (Plus Jakarta Sans, DM Sans, JetBrains Mono are **not**
used). `HexNextTypography` stays the base, with these sizes:

| Role (M3 slot) | Size / line height | Weight | Use |
|---|---|---|---|
| titleLarge | 22 / 28 sp | SemiBold | top bar title, sheet title |
| titleMedium | 18 / 24 sp | SemiBold | card title |
| titleSmall | 16 / 22 sp | Medium | row title, sub-section |
| bodyLarge | 16 / 24 sp | Normal | primary body |
| bodyMedium | 15 / 22 sp | Normal | default body, row summary |
| bodySmall | 14 / 20 sp | Normal | captions, notes (smallest prose) |
| labelLarge | 14 / 20 sp | Medium | buttons, segmented, nav labels |
| labelMedium | 13 / 18 sp | Medium | chips, badges |
| labelSmall | 12 / 16 sp | Medium, +0.4 sp tracking | eyebrows only (max 3 words, may be uppercase) |
| mono | 13 / 18 sp | Normal | hex, resource and package names |

## 4. Readability rules

1. No text below 12 sp. Prose is 14 sp or more. Labels are 12 sp or more. Monospace is 13 sp.
   Bottom-nav labels are 12 sp or more.
2. No monospace walls. Monospace is never used for sentences, descriptions or headings. The
   site's monospace eyebrows ("● INTERACTIVE CUSTOMIZER MODE") become 12 sp labelSmall in the
   system font.
3. One idea per card. Each card has:
   - a title (titleMedium)
   - an optional one-line subtitle (bodySmall)
   - its content
   - at most one primary action

   A second action is a text button.
4. At most 2 lines of summary per row, with ellipsis. Full text goes into a details sheet.
5. Status is never colour-only. Every chip has a word ("OK", "Failed", "Partial") and a dot or
   icon. Switches have a visible label.
6. Swatches always show their hex next to them (monospace 13 sp) and have a
   `contentDescription` of "<group label>, <hex>, edit colour".
7. Plain words over jargon. "RRO" becomes "overlay", "SemWT_*" becomes "Samsung theme
   overlay", "UID 1000" becomes "System (via Shizuku)". The raw value can appear as monospace
   secondary text in details sheets.
8. Sub-tabs are a `SingleChoiceSegmentedButtonRow` with at most 4 segments of 1 to 2 words.
   They scroll horizontally if needed. There are no nested tab rows.
9. Reduce motion (`LocalAppSettings.current.reduceMotion`) turns off crossfades, the
   highlight flash and colour animation, as today.
10. TalkBack:
    - card titles are `heading()`
    - the bottom nav has `selected` semantics
    - the preview elements keep their "edit colour" descriptions
    - the Apply bar progress line is a polite live region
11. Compose lint: `remember` return types, no `remember` in conditionals without keys, stable
    lambdas for `LazyColumn` items, `rememberSaveable` for user choices.

## 5. Features that must remain reachable

Every item below must be reachable from the new shell in at most 2 taps from a bottom
destination. Each item works exactly as today; check the wiring by reading the call site.

| Feature | Where | Backing |
|---|---|---|
| Pack select, import, delete | Skins | VM `selectPack`, `importPack`, `deletePack` |
| Pack catalogue browse and download | Skins > Browse | VM `setCatalogueUrl`, `loadCatalogue`, `downloadPack`; `CatalogueDialog` |
| Pack lock state, Unlock, Unlock all, price, pending | Skins rows | `PackLocks`, `UnlockButton`, `UnlockAllRow`, `LockedLabel` (`ui/PackUnlock.kt`), VM `unlock(activity, productId)` |
| Restore purchases | App settings > Purchases | VM `restorePurchases` |
| Locked pack blocks apply and download | Apply bar / Skins | `PackAccess.lockedPacksIn`, `isPackUnlocked` (unchanged) |
| Simple / Advanced mode, per-element pack, element on/off, pack options | Skins | VM `setMode`, `setElementPack`, `setElementEnabled`, `setOption` |
| Colour Engine (seeds, variant, contrast, adjustments, AMOLED, ribbon) | Colours > Engine | `ColorEngineCard` |
| Element colours, reset all | Colours > Elements | `ElementColorsCard`, VM `setGroupColor`, `setGroupColorBoth`, `resetGroupColors` |
| Quick panel colours and opacity | Colours > Quick panel | `QuickPanelCard`, VM `setQsPanelAlpha` |
| Monet shades and pins | Colours > Shades | `ShadeRibbon`, VM `setShadeOverride` |
| Per-resource editor, pins, target on/off | `RESOURCES` | `ResourceEditorScreen`, VM `setColorPin`, `setTargetEnabled` |
| Icon colour style, built-in icons, synthesise | Icons > Style | `IconStyleCard`, VM `setIconStyle`, `setBuiltInIcons`, `setSynthesizeMissingIcons` |
| Custom icons (import, assign, delete) | Icons > Custom icons | `CustomIconsCard`, VM `importCustomIcon`, `assignIcon`, `deleteAsset` |
| AI icon generation | Icons > Custom icons > Generate | `GenerateIconSheet`, VM `noteGeneratedIcon` |
| Custom fonts (regular and bold) | Icons > Fonts | `FontCard` |
| Custom backgrounds | Icons > Backgrounds | `BackgroundsCard` |
| Describe your theme (AI) | Studio | `DescribeThemeCard` (unlocked packs only), VM `setSeed` + `replaceSpec` |
| AI provider keys | App settings, AI cards | `Screen.AI_PROVIDERS` |
| Live preview, all tabs, light/dark, tap to edit | Studio, peeks | `PhonePreview`, `StudioPreviewState` |
| Apply, Remove, It works, restart protection | Apply bar | VM `applyTheme`, `removeTheme`, `confirmWorks`, `refreshGuard` |
| Safe-mode banner, Open diagnostics, dismiss | Global | `SafeModeBanner`, VM `dismissSafeMode` |
| Connection, tier (root / system / shell), Shizuku permission | Privilege chip / sheet | `StatusCard`, `TierPill`, VM `requestPermission`, `openShizuku` |
| Root guide | Privilege sheet | `RootGuide(ui.rootToolLinks)` |
| Shizuku tier limitations ("Needs root" badges) | Inline | `NeedsRootBadge`, `NeedsRootReason` |
| Scan phone | Studio empty state, Targets, Diagnostics | VM `openScan`, `DiagnosticsViewModel.runScan` |
| Diagnostics (share report and summary, Settings test, repair, monitor, turn off all, live log) | System > Tools > Diagnostics | `DiagnosticsScreen` (unchanged logic) |
| Build log, clear, share | System > Tools | `LogCard`, VM `clearLog`, `ApplyReport.logFile` |
| Theme export / import / copy JSON (new) | System > Tools | `ThemeExport`, VM `replaceSpec` / `setSeed` |
| App settings: Appearance (Console / Phone colours / Custom colour, light/dark/system, pure black), Behaviour (haptics, reduce motion…), Purchases, About, licences, Setup guide, Hex Installer link, privacy policy | Top bar gear | `AppSettingsScreen`, `AppSettingsStore` |
| Privacy policy | App settings > About | existing link (`SafeUriHandler`) |
| Onboarding (unchanged) | First run, About > Setup guide | `OnboardingScreen` |
| OtherBuild block screen | Before the shell | `BlockedScreen` (unchanged) |
| Owner flavor (everything unlocked, no store) | Build flavor | `src/owner/**` (unchanged; `canPurchase == false` hides buy buttons) |

## 6. Bug-check list for the UI port

- Every rewired control has its VM call verified by reading the code. No new state paths for
  billing or apply.
- Rotation and process death keep:
  - the destination
  - the sub-tab
  - the preview tab and dark mode
  - the peek collapsed state
  - the scroll position
- Back from each pushed screen lands on its origin destination. Diagnostics Back still runs
  `invalidateSlots`.
- Snackbar notices fire once, also after switching destinations.
- With the keyboard open (hex fields, pack search, theme name), the field stays visible
  (`WindowInsets.ime`).
- SU-only and no-access states keep Apply disabled. The owner flavor shows no prices.
- No legacy names: `grep -rniE "project x|terpsichora|mimi|miui|ayos|aosp r|bodhi|drive apk|#hex_|anaconda|crocodile|vivid\.ui"`
  over `ui/**` returns nothing user-visible.
- The compile harness reports 0 errors. Engine tests pass.

## Appendix A. File ownership for this phase

- **UI engineer:** `android/app/src/main/java/com/hexnext/themer/ui/**` except
  `ui/onboarding/OnboardingScreen.kt`, plus `MainActivity.kt` and `res/values/themes.xml`. New UI
  files go under `ui/`.
- **Rename engineer:** pack tools, tests, CI, billing, `theme/PackStore.kt`, the engine
  pack-id alias table and its `ThemeSpec` hook (the workflow output lists the exact files).
- Neither touches `ui/onboarding/OnboardingScreen.kt`, `safety/**`, `shizuku/**`,
  `theme/SemWtController.kt`, `theme/ThemeApplier.kt` or `src/owner/**`.

## Appendix B. New pack catalogue names

The pack names come from one neutral scheme: **mineral and gemstone names**.

Each name was checked against these, and has no semantic, phonetic, translational or thematic
link to any of them:

- the source plugin's name, version, package and author
- the old Hex brand
- the old catalogue name of the same pack

The checks were:

- no shared first syllable with any source plugin name
- no fire, glow, light, dark, star, space, metal, eye, speed, dance, animal or hunt meanings
- no famous place names
- one word, no family suffixes

| Source key (`pack_names.json`) | Old catalogue id | New id | New name |
|---|---|---|---|
| aosp-r-78 | riverstone | kyanite | Kyanite |
| aosp-r-8 | riverstone-classic | euclase | Euclase |
| aosp-ui-release-fnal | clearwater | morganite | Morganite |
| asylum-seclusion | moonlit-hollow | citrine | Citrine |
| ayos-6 | aurora | variscite | Variscite |
| ayos-retina-16 | aurora-borealis | topaz | Topaz |
| blend-3-0 | watercolor | fuchsite | Fuchsite |
| blend-day-night-5-0 | dusk-and-dawn | olivine | Olivine |
| broly-super-broly-2-5 | supernova | lazulite | Lazulite |
| darpoint-3-5 | compass-rose | zoisite | Zoisite |
| deliun-4-6 | lagoon | epidote | Epidote |
| delirium-d-2-0 | fever-dream | peridot | Peridot |
| deluxe-3-8 | velvet-rope | iolite | Iolite |
| deluxe-4-2 | velvet-rope-encore | charoite | Charoite |
| dna-bungee-gum-hisoka-6 | bubblegum-bandit | ametrine | Ametrine |
| dolian-3-0 | dandelion | wavellite | Wavellite |
| dounbler-2-9 | double-scoop | kornerupine | Kornerupine |
| drastik-3-5 | thunderclap | sodalite | Sodalite |
| dsp-1-9 | pixel-pop | calcite | Calcite |
| dsp-2-5 | pixel-pop-remix | agate | Agate |
| genisys-dn-6-oneui6-1 | first-light | idocrase | Idocrase |
| haumea-5-0 | stardust | bixbite | Bixbite |
| helling-4-2 | ember-glow | kunzite | Kunzite |
| hilly-1-8 | highland-mist | cerussite | Cerussite |
| homly-3-0 | hearthside | larimar | Larimar |
| hopper-4-9 | leapfrog | chrysocolla | Chrysocolla |
| houndly-2-2 | night-patrol | apatite | Apatite |
| hounter-2-8 | fox-hunt | xenotime | Xenotime |
| hysteria-pro27 | carnival | diopside | Diopside |
| jajanken-gon8 | jackpot | lepidolite | Lepidolite |
| line-ui-1-7 | thin-ice | garnet | Garnet |
| luminosa-illuminated | lantern-light | axinite | Axinite |
| madara-rinnegan | crimson-eye | scolecite | Scolecite |
| miui-11-1-3 | lotus-garden | pectolite | Pectolite |
| nitro-1-6 | rocket-fuel | cordierite | Cordierite |
| oriton-dn-1 | north-star | covellite | Covellite |
| paragus-paragus-pro-v1 | solar-flare | erythrite | Erythrite |
| primeos-prime-2-5 | midnight-express | fayalite | Fayalite |
| quantum-atomic-xiii | particle-zoo | willemite | Willemite |
| roughly-1-1 | sandpaper | okenite | Okenite |
| spark-4-9-delta | firefly | ekanite | Ekanite |
| terpsichora-blythe-16 | ballroom | carnelian | Carnelian |
| titanium-titan-pro | iron-lotus | indicolite | Indicolite |
| uhu-dn-3-2 | owl-hours | zircon | Zircon |
