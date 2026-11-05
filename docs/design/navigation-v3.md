# Navigation v3: four destinations, nothing jumps

Status: shipped on `ui/navigation-rebuild` (2026-11-05). Supersedes sections 1.2 to 1.4 of
`studio-v2-phase2.md` (the 5-tab shell, the status strip and the cross-tab jumps).

## 0. Why

Kyle's review of the 5-tab shell (Studio / Skins / Colours / Icons / System): "it feels a little
difficult to navigate and links send you all over the place". The audit found:

- the connection status was shown three times (pill, strip, apply-bar summary) with three
  different tap targets;
- tapping an element on the phone switched tabs; "All 65 shades", "Edit individual resources",
  "Scan phone" and Diagnostics each left the page you were on;
- the pack dropdown held 44 entries, the sub-tab rows truncated on a 360 dp phone, and the
  preview had three ways to be opened (Expand, Shorter, the FAB).

## 1. Shell

```
┌─ Hex Next ────────────────────────────── [● Ready] ─┐   status word → Status sheet
│  Live preview · tap to edit      ⤢  ˄                │   peek (220 dp), opens the full phone
│  [Settings] [Quick panel] [N…   ☾ Dark]              │   screens scroll + fade, one mode pill
│  ...page content...                                  │
│                                            (Preview) │   FAB once the peek scrolls away
├─ summary · Remove · Apply theme ─────────────────────┤   apply bar; "Connect" while disconnected
│  Theme        Packs        Library        Settings   │   4 destinations
└──────────────────────────────────────────────────────┘
```

| Destination | Sub-tabs | Holds |
|---|---|---|
| **Theme** (start) | Palette · Elements · Quick panel · AI | Colour engine (shades scroll into view below it, no jump), One UI element colours, Quick panel, "Describe your theme". |
| **Packs** | — | Mode card (One pack / Pack per part, preview-only chip, Unlock all), Parts card in per-part mode, search, "Colours only", then **On this phone** and **More packs to download** groups, "More packs" (import / catalogue source). |
| **Library** | Icons · Fonts · Backgrounds | Icon style + custom icons, system font, backgrounds. |
| **Settings** | Apps · Tools · Hex Next | Themed apps (scan runs in place), theme file / backup / build log / safe mode / **Advanced** (Diagnostics, Per-app colours) / device, and Hex Next's own settings. |

`Destination.parse` keeps the old names as aliases (`STUDIO`/`COLOURS` → Theme, `SKINS` → Packs,
`ICONS` → Library, `SYSTEM` → Settings) so `hexnext:` deep links and `tools/device/autotest.sh`
still work.

## 2. Rules

- **One status word.** The top bar shows one plain word (`shellStatus()`: Applying…, Scanning…,
  Allow Shizuku, Start Shizuku, Not connected, Palette only, Check your phone, Needs attention,
  Partly applied, Applied, Ready). Tapping it opens the Status sheet: connection card, the
  "Check your phone / It works" card, the last apply facts, and the two doors out (Themed apps,
  Diagnostics). The status strip and the gear are gone.
- **Edits happen in place.** Tapping an element on the phone opens the group colour sheet over
  the current page on every tab. Scans run where they were asked for and end with a snackbar.
  Shades are a card below the engine, not another page.
- **The apply bar tells you what to do.** While disconnected (and nothing to unlock) it shows
  "Not connected. Start Shizuku with root, then apply." and a single **Connect** button instead
  of two buttons that cannot be tapped. It is busy while a service task runs.
- **Packs read as a gallery.** Rows with palette dots and a one-line summary; packs on the phone
  first, the catalogue after, with a download icon and "Not on this phone · Day and night · 6 MB".
  The picked row expands with its badges and options. Per-part assignment is a card that appears
  right under the mode choice.
- **Plain language, 48 dp targets, status never colour-only**, as `docs/ENGINEERING_RULES.md`.

## 3. Checking it without a phone

`android/app/src/test/.../ShellScreenshotTest.kt` renders every page with Robolectric
(`GraphicsMode.NATIVE`, 360×780 dp, dark and light) into `app/build/shell-shots/`:

```
./gradlew :app:testOwnerDebugUnitTest --tests '*ShellScreenshotTest*'
```

It needs the debug test packs in `app/src/debug/assets/packs/` (CI copies them; locally take them
from the `packs` branch). Look at the PNGs: truncation, overlap and contrast problems show there
before they reach Kyle's phone.
