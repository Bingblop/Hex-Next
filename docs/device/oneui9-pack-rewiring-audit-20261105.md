# One UI 9 pack rewiring audit (2026-11-05)

Question: Willemite was the only pack tested on One UI 9. Are the other 43 packs rewired the same way?

## How packs are rewired

There is no per-pack rewiring. Every pack goes through the same path: a layer's file name becomes a
resource name, `OneUiRules.renameFor` applies `renamed` then `fallbackRenames` from
`android/engine/src/main/resources/oneui/9.json`, `drawableCopies` paints the art onto extra One UI 9
names, and the planner keeps only names the phone's resource table lists. The live preview
(`ThemePreviewer`) runs the same `ThemeCompiler` on the same rules, so whatever lands in the overlay for a
pack is what the preview renders; there is no Willemite-specific preview path.

So a pack is rewired exactly as Willemite is when it ships the same legacy names Willemite ships
(`switch_thumb_on`, `checkbox_NNN`, `ic_settings_*`, `textinput_qwerty_btn`, `0bg`, `dialog_`, `popup_`,
`tw_ic_do_*`). Names Willemite never had were never mapped. That is the gap this audit measured.

## Method (offline, no phone)

- 44 packs read from the `packs` branch (`pack.json` layers, default options as the app resolves them).
- One UI 9 drawable tables for all 23 targets from the device lab: `lab-010/dumps/names/*` (17 apps)
  and the `.arsc` dumps in `lab-026` / `lab-036` (Notes, Internet, Email, Music, Voice Recorder, Health).
- For every pack resource: element and targets from `Elements`, then `renameFor` + `drawableCopies`
  against the real table, honouring the System UI colours-only policy (`outsideDrawables`).
- Quick Settings tiles, config-specific categories (`land`, `sw600dp`) and options that are off by
  default are excluded, as the compiler excludes them.

The audit replicates the engine's name resolution in Python; it does not render art or push to a phone.

## Result

Across the elements below, 2608 of 6903 unique pack names resolved on One UI 9 before this change and 2844 after it; 39 of 44 packs gain names, Willemite is unchanged. Cells are `landed/shipped`; `a→b` shows the change from the new rules.

| Pack | Settings icons | Controls | My Files icons | Keyboard | Dialogs/buttons | Dialpad | Dialer | Launcher home | Status bar | System drawables | Misc |
|---|---|---|---|---|---|---|---|---|---|---|---|
| willemite | 24/25 | 26/72 | – | 5/23 | 2/4 | 2/6 | – | 1/26 | – | 88/251 | 0/33 |
| agate | – | 1/2 | – | 3/12 | 2/2 | 3→4/9 | 2/11 | 2/18 | 10→20/23 | 2/2 | 1→4/4 |
| ametrine | – | 13/56 | – | 1/2 | 2/2 | 3→4/8 | – | – | – | 29→31/41 | – |
| apatite | – | – | – | 3/8 | 2/2 | 5→6/8 | 1/13 | 1/9 | 10→20/27 | – | 1→2/4 |
| axinite | – | 22/26 | – | – | 0→4/4 | 4→5/9 | – | 0/4 | – | 7/23 | 2/19 |
| bixbite | 25/25 | 24/70 | – | 3/10 | 2→6/6 | 2/6 | – | 0/44 | – | 45→48/153 | 2/14 |
| calcite | – | – | – | 3/13 | 2/2 | 3→4/9 | 2/11 | 2/18 | 10→20/23 | 1/1 | 2→6/6 |
| carnelian | 27→28/29 | 7/8 | – | – | – | 1/6 | – | 0/4 | – | 19/36 | – |
| cerussite | – | – | – | 3/9 | – | 5→6/10 | 1/10 | – | – | – | 1→4/10 |
| charoite | 28/29 | 14/18 | 17/35 | 3/8 | – | 5→6/14 | 2/14 | 2/6 | – | 14/25 | 1→2/2 |
| chrysocolla | – | 14/18 | – | 2/6 | – | 5→6/13 | 0/5 | 2/15 | 10→20/25 | 21/57 | – |
| citrine | 24/24 | – | – | 1/3 | 0→4/4 | 1/1 | – | 0/27 | – | 36→37/88 | 2/10 |
| cordierite | – | 12/54 | – | – | 0→1/1 | – | – | – | – | 13→14/20 | – |
| covellite | – | 20/88 | – | 3/9 | 2/2 | 1/1 | – | 4/21 | – | 72→76/212 | – |
| diopside | 27→28/29 | 15/58 | – | 4/16 | 2→6/6 | 2/7 | – | 1/11 | – | 74→78/159 | – |
| ekanite | 24/25 | 12/54 | – | 3/10 | 0→4/4 | 2/6 | – | 1/5 | – | 26→28/57 | – |
| epidote | – | 2/4 | – | 3/7 | 2/2 | 1/5 | 1/6 | 2/21 | 0/5 | 21/55 | 0→2/3 |
| erythrite | – | 6/12 | – | – | – | 3→4/8 | – | – | – | 10→12/25 | – |
| euclase | – | 6/12 | – | – | – | 0/4 | – | – | – | 2/3 | – |
| fayalite | 26→27/28 | 26/72 | – | 1/10 | 2/2 | 4/9 | – | 1/10 | – | 162→168/368 | 0/3 |
| fuchsite | 22/22 | – | – | – | 0→4/4 | 0/4 | – | 0/6 | – | 17→18/75 | 0/7 |
| garnet | – | 9/16 | – | 1/7 | – | 2/6 | – | – | – | 9→11/17 | – |
| idocrase | 29/32 | 21/68 | – | 3/16 | 2→6/10 | 2/6 | – | 1/20 | – | 198→204/499 | – |
| indicolite | – | 8/16 | – | 1/2 | 2/2 | 4→5/9 | – | – | – | 37→39/99 | – |
| iolite | 28/29 | 14/18 | 17/35 | 3/10 | – | 5→6/14 | 2/15 | 2/6 | – | 16/32 | 1→2/3 |
| kornerupine | – | – | – | 3/9 | 2/2 | 3→4/4 | 1/8 | 1/8 | 0/3 | – | 1→4/8 |
| kunzite | 28/29 | 26/72 | 16/30 | 3/16 | 2/2 | 5→6/11 | 2/10 | 2/15 | 10→20/30 | 51→53/127 | 7→10/16 |
| kyanite | – | 6/12 | – | – | – | 0/4 | – | – | – | 2/3 | – |
| larimar | – | – | – | 4/21 | 2/2 | 3→4/10 | 2/23 | 2/14 | 0/5 | 4/7 | 1→4/8 |
| lazulite | – | – | – | – | – | 3→4/8 | – | – | – | 11→13/14 | – |
| lepidolite | – | 14/58 | – | 3/10 | 0→4/4 | 1/5 | – | 0/5 | – | 43→44/84 | – |
| morganite | – | 6/12 | – | – | – | 1/1 | – | 0/14 | – | 19/49 | – |
| okenite | – | 14/18 | – | 4/13 | 2→6/6 | 1/1 | – | 0/34 | – | 46→48/101 | 0/7 |
| olivine | 24/24 | – | – | – | 0→4/4 | 1/5 | – | 0/6 | – | 19→20/92 | 1/17 |
| pectolite | – | – | – | – | – | – | – | – | – | – | – |
| peridot | – | – | – | 3/12 | 2/12 | 5→6/12 | – | 0/2 | – | – | 1/18 |
| scolecite | – | 10/51 | – | 1/4 | 2/2 | 7→8/13 | – | – | – | 23→26/37 | – |
| sodalite | – | – | – | 4/19 | 2/2 | 3→4/8 | 2/21 | 2/21 | 10→20/28 | 5/17 | 3→6/7 |
| topaz | – | 6/14 | – | – | 0→1/1 | 2/6 | – | – | – | 32→33/122 | – |
| variscite | – | 6/14 | – | – | 0→2/2 | 1/1 | – | – | – | 18/90 | – |
| wavellite | – | – | – | 3/9 | 2/2 | 6→7/14 | 1/8 | 0/2 | 0/8 | 4/7 | 1→4/13 |
| xenotime | – | – | – | 3/11 | 2/2 | 5→6/12 | 3/23 | 0/9 | 10→20/29 | 7/13 | 1→4/4 |
| zircon | 29/32 | 18/63 | – | 3/11 | 2/2 | 10→11/28 | – | 4/27 | – | 54→58/181 | – |
| zoisite | – | 14/18 | 34/40 | 2/6 | 2/2 | 5→6/14 | 2/22 | 2/41 | 16→26/34 | 76→78/174 | 2/3 |

`–` means the pack ships nothing for that element. Settings icons: 13 packs ship them and every one lands
22-29 of its icons through the same `ic_settings_*` → `sec_ic_settings_*` renames as Willemite. Controls
(switches, checkboxes, radios): every control name that fails to land for another pack also fails for
Willemite (frames past `_020`, the `_dark_*` switch states), so no pack is behind Willemite there. Keyboard, dialogs and dialpad art land the same way as Willemite wherever
the pack uses Willemite's names.

## New rules added to `oneui/9.json`

All target names were checked against the One UI 9 tables before being added. Fallback renames only
apply when the target lacks the pack's own name, and copies never replace a pack's exact-name art.

| Pack name(s) | Packs | One UI 9 name(s) | Rule |
|---|---|---|---|
| `sec_quickpannel_wifi_signal_0..4`, `_lock_0..4` | 8 | `sec_ic_wifi_signal_N`, `_lock_N` (System UI, allowed outside the QS policy) | fallbackRenames |
| `menu_dark`, `menu_light` (`buttons` layer) | 13 | `sesl_ic_menu_overflow_dark/light` | fallbackRenames |
| `back_dark`, `back_light` (`buttons` layer) | 10 | `sesl_ic_ab_back_dark/light` | fallbackRenames |
| `phone_keypad_wificall` | 22 | `phone_keypad_wificall_ic_comm` + copies to `_ic_spr`, `_ic_vzw` | both |
| `sesl_ic_ab_back`, `sesl_ic_menu_overflow` | 8 | the `_dark` and `_light` pair | drawableCopies |
| `sesl_tablayout_subtab_indicator_background[_]` | 12 | the `_dark` and `_light` pair | drawableCopies |
| `sesl_tablayout_maintab_indicator_opentheme_background_` | 7 | `sesl_tablayout_maintab_indicator_opentheme_background`, `sem_maintab_indicator_opentheme_background` | drawableCopies |
| `sem_toast_frame_mtrl_`, `toast_frame_` | 11 | `sem_toast_frame_mtrl[_light]`, `toast_frame` | both |
| `sesl_index_bar_bg_` | 10 | `sesl_index_bar_bg` | fallbackRenames |
| `tw_spinner_mtrl_am_alpha_` | 8 | `tw_spinner_mtrl_am_alpha`, `sesl_spinner_mtrl_am_alpha_dark/light` | drawableCopies |
| `floating_popup_background_dark/light` | 8 | `sem_floating_popup_background_dark/light`, `tw_floating_popup_background[_dark]`, `floating_popup_background` | drawableCopies |
| `tw_text_select_handle_{left,middle,right}_mtrl_alpha` | 14 | `text_select_handle_*_mtrl_alpha` | fallbackRenames |
| `sec_ic_settings_coverscreen` | 3 | the three `sec_ic_settings_coverscreen_*_cover` icons | drawableCopies |

## Known gaps left alone (same for Willemite, or no One UI 9 equivalent)

- Launcher: `homescreen_badge`, `noti_` badges, home-options icons (`ic_homeoption_theme`, `ic_wallpapers`,
  `ic_widgets`, `ic_settings`), `google_search_*`, `folder_expanded_bg` have no One UI 9 launcher name.
  Willemite lands 1 of 26 launcher home names too.
- Launcher icon packs (`icons` layer, 9 packs, `ic_phone`, `ic_camera` …) are app icons, not resources;
  they need the icon-style path, not a rename.
- Keyboard: `textinput_qwerty_btn_number/_option/_capslock`, `textinput_qwerty_preview_popup`,
  `candidate*`, `extra_range_bg*`, `0*_dark` are unmapped for every pack including Willemite
  (Honeyboard has `key_bg`, `key_bg_with_stroke`, `preview_bg` only). `textinput_popup_01_pressed`
  (7 packs) and `pie_keypopup` (3) might map onto `preview_bg`; not done without a look at the art.
- Status bar / notifications / volume / lock screen art is dropped by the System UI colours-only policy
  for all packs; only the Wi-Fi signal, `stat_sys_*` and power-menu names get through.
- `places_start_button_bg_`, `sec_switchbar_background[_checked]_`, `edge_handler_` sit in the launcher
  `search` category, so they only ever target the launcher, which has none of them.
- Messaging bubbles (`bubble_*`, `balloon_*`) target a deprecated package.
- `dual_left360/411`, `dual_right360/411` (dialpad, 31 packs including Willemite), `background1/2`,
  `keypad_custom*` are legacy composites with no resource behind them.
- `sec_ic_settings_remotesupport` is gone from One UI 9 Settings (Willemite ships it too).
- One UI 7 and 8 rule files have no renames or copies at all yet; this audit covers 9 only.

## What this does not prove

Name resolution is necessary, not sufficient. The art itself (nine-patch padding, glyph size, tint slot)
has only been looked at on the phone for Willemite. A device-lab pass with two or three packs that use
the new names (kunzite, sodalite or xenotime for the status bar and templates; axinite or citrine for
the `buttons` layer; apatite or charoite for the dialpad) would close that.
