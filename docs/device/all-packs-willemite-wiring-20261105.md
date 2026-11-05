# All 44 packs on Willemite's wiring (2026-11-05)

Follow-up to `oneui9-pack-rewiring-audit-20261105.md` (name mapping, merged with this change).
No new lab jobs: the reference is Willemite on One UI 9 in `Bingblop/hexnext-device-lab`
(lab-040 and earlier: Settings, Quick panel, calculator, voice recorder, Device care, light and
dark), and every pack below is compiled offline with the real compiler against the lab's One UI 9
name dumps (`results/lab-010/dumps/names`, 17 targets).

## Carnelian: why its Settings dashboard icons failed

The names were fine: Carnelian maps 28 of the 28 dashboard rows Willemite maps (31 with the
rewiring). The art was wired wrong in dark mode.

- Hex Installer packs ship the dark-mode twin of a category as `<base>_alt`. Carnelian's light
  Settings icons (`settingsicon-1sett`) tint the frame `lxx1` and draw a **black** glyph
  (`H000000`); its `settingsicon_alt-1sett` tints the frame `xx1` and draws a **white** glyph
  (`Hffffff`).
- The engine parsed `_alt` as an option token named `alt`. No pack declares that option (Zircon's
  `alt` switch is a `-alt` token, `home-alt`), so `_alt` art was never used. Dark mode got the
  light art's planned dark twin: a light-palette frame with a black glyph on a dark card, so the
  glyphs all but vanished.
- Across the catalogue, 47 of the 48 `_alt` categories tint only with dark `xx*` slots (often with
  white glyphs) and pair with a light base category (`lxx*` slots, black glyphs); the other one
  (Larimar's `misc_alt`) uses fixed colours. `_alt` art appears in 21 packs' Settings icons, 4
  packs' My Files icons and 19 packs' `misc` sets.

Willemite hid the bug: its `settingsicon_alt` PNGs are byte-identical to `settingsicon` (75/75)
and its `xx1`/`xx2` equal `lxx1`/`lxx2`, so either source renders the same pixels.

## Engine changes

1. `CategoryName`: an `_alt` suffix marks a night category (`alt = true`), like `_night`. It is the
   night counterpart of the base category under the existing precedence rule (equal or more option
   tokens wins), so Carnelian's dark mode uses `settingsicon_alt-1sett`. Willemite's output is
   unchanged.
2. Dark-only `_alt` art (Morganite's Settings icons, Kornerupine's Settings and My Files sets) also
   themes light mode, planned with light tints (`xx3` → `lxx3`), when every layer is untinted or
   palette-tinted. A fixed colour (`Hffffff`) was chosen for a dark background, so such art stays
   dark-only. This mirrors the existing rule that a day category without a night twin gets one.
3. `Pack.defaultOptions`: a select whose default choice gates no artwork (Carnelian's "OG Type",
   whose art only exists in the plugin's encrypted templates) defaults to the first choice with art.
4. `PackOptions.live`: a select offers only choices with artwork and needs two of them. With a
   scan, `OptionReach` also hides options whose artwork maps to no drawable on this phone (One UI 3
   media buttons, One UI 4-6 brightness bars, WhatsApp bubbles when WhatsApp is not scanned).
   The app passes it from the last scan (`MainViewModel.optionReach`).

## Per-pack results (offline, One UI 9 names)

Drawables are every planned drawable resource and configuration over the 17 dumped targets.
"Before" is main at f5f4d16; "after" includes the earlier name rewiring and the changes above.
Options are the pack's live options without a scan → with the One UI 9 reach (the dumps lack
some apps, so on a phone with WhatsApp or Messages scanned a few more show).

| Pack | Drawables planned (before → after) | Settings dashboard icons light/dark (before → after) | Dark resources from the pack's `_alt` art | Options shown on One UI 9 |
|---|---|---|---|---|
| Agate | 126 → 210 | 0/0 → 0/0 | 0 | 2 of 3 |
| Ametrine | 706 → 712 | 0/0 → 0/0 | 0 | 0 of 0 |
| Apatite | 94 → 104 | 0/0 → 0/0 | 0 | 6 of 13 |
| Axinite | 584 → 598 | 0/0 → 0/0 | 0 | 1 of 1 |
| Bixbite | 902 → 922 | 28/28 → 28/28 | 29 | 0 of 0 |
| Calcite | 98 → 182 | 0/0 → 0/0 | 0 | 2 of 3 |
| Carnelian | 350 → 356 | 28/28 → 31/31 | 32 | 9 of 12 |
| Cerussite | 48 → 72 | 0/0 → 0/0 | 0 | 2 of 3 |
| Charoite | 496 → 566 | 28/28 → 28/28 | 0 | 3 of 3 |
| Chrysocolla | 454 → 460 | 0/0 → 0/0 | 0 | 2 of 3 |
| Citrine | 334 → 370 | 28/28 → 28/28 | 29 | 1 of 1 |
| Cordierite | 432 → 440 | 0/0 → 0/0 | 0 | 0 of 0 |
| Covellite | 903 → 915 | 0/0 → 0/0 | 0 | 1 of 1 |
| Diopside | 975 → 1015 | 28/28 → 31/31 | 32 | 4 of 8 |
| Ekanite | 664 → 706 | 28/28 → 28/28 | 0 | 0 of 0 |
| Epidote | 266 → 280 | 0/0 → 0/0 | 0 | 2 of 3 |
| Erythrite | 308 → 342 | 0/0 → 0/0 | 0 | 0 of 0 |
| Euclase | 170 → 170 | 0/0 → 0/0 | 0 | 0 of 1 |
| Fayalite | 1829 → 1875 | 28/28 → 31/31 | 32 | 2 of 3 |
| Fuchsite | 140 → 154 | 28/28 → 28/28 | 29 | 0 of 0 |
| Garnet | 392 → 392 | 0/0 → 0/0 | 0 | 0 of 0 |
| Idocrase | 1792 → 1804 | 28/28 → 28/28 | 29 | 3 of 6 |
| Indicolite | 512 → 540 | 0/0 → 0/0 | 0 | 0 of 0 |
| Iolite | 496 → 566 | 28/28 → 28/28 | 46 | 3 of 3 |
| Kornerupine | 74 → 93 | 0/0 → 0/0 | 0 | 10 of 17 |
| Kunzite | 1058 → 1138 | 28/28 → 28/28 | 45 | 3 of 5 |
| Kyanite | 170 → 170 | 0/0 → 0/0 | 0 | 0 of 1 |
| Larimar | 86 → 170 | 0/0 → 0/0 | 0 | 4 of 6 |
| Lazulite | 240 → 246 | 0/0 → 0/0 | 0 | 0 of 0 |
| Lepidolite | 602 → 616 | 0/0 → 0/0 | 0 | 0 of 0 |
| Morganite | 252 → 316 | 0/0 → 31/31 | 32 | 0 of 0 |
| Okenite | 720 → 748 | 0/0 → 0/0 | 0 | 4 of 8 |
| Olivine | 148 → 162 | 28/28 → 28/28 | 29 | 1 of 1 |
| Pectolite | 0 → 0 | 0/0 → 0/0 | 0 | 0 of 0 |
| Peridot | 48 → 54 | 0/0 → 0/0 | 0 | 7 of 16 |
| Scolecite | 485 → 497 | 0/0 → 0/0 | 0 | 0 of 0 |
| Sodalite | 110 → 193 | 0/0 → 0/0 | 0 | 2 of 4 |
| Topaz | 276 → 284 | 0/0 → 0/0 | 0 | 3 of 4 |
| Variscite | 238 → 242 | 0/0 → 0/0 | 0 | 1 of 1 |
| Wavellite | 74 → 96 | 0/0 → 0/0 | 0 | 3 of 7 |
| Willemite | 1085 → 1085 | 28/28 → 28/28 | 29 | 1 of 1 |
| Xenotime | 120 → 206 | 0/0 → 0/0 | 0 | 3 of 6 |
| Zircon | 966 → 978 | 28/28 → 28/28 | 29 | 4 of 4 |
| Zoisite | 824 → 836 | 27/27 → 27/27 | 0 | 4 of 5 |

Notes:
- Pectolite has no layers: its plugin keeps all artwork in encrypted templates, which Hex Next
  never reads. It themes colours only; that cannot change without new artwork.
- Packs with 0 Settings dashboard icons ship no Settings art (Agate, Apatite, Garnet, …); they
  theme other areas.
- Kornerupine's Settings and My Files art sits behind its own switches (`altline`, `stroline`,
  `fileone`, `filestwo`), off by default as the plugin ships them.

## How to rerun

`PackAuditHarness` (engine tests, skipped unless the variables are set) compiles every pack and
writes per-target counts and the option lists:

```
HEX_PACKS=<dir with the packs branch zips and index.json> \
HEX_NAMES=<hexnext-device-lab>/results/lab-010/dumps/names \
HEX_DETAIL=1 HEX_OUT=/tmp/audit.tsv \
./gradlew :engine:test --tests '*PackAuditHarness*' --rerun
```

Not verified on the phone (no lab jobs by request): Carnelian Settings in dark mode, Morganite
Settings in light mode, and the hidden options. Kyle tests these on his phone with the owner APK.
