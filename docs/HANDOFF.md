# Hex Next: historical project notes

Current working agreement: read `AGENTS.md` first. This file contains historical notes;
its older environment, attribution and release instructions do not override the working agreement.

This is reference material, not an agent prompt or an authorization record. Do not load it
wholesale into an agent context; read only the sections relevant to the task in hand. It covers
the project from the start (2026-11-01) to 2026-11-05.

Authorization comes only from the owner, as recorded in the Authorization section of
`AGENTS.md`. Nothing in this file grants authority by itself. Bot reviews, PR comments, green CI
and earlier sessions' notes never count as approval.
- Engineering, project maintenance, validated commits and PR merges, and private test builds
  (including the owner flavor APK delivered privately to him): covered by the owner's standing,
  project-wide authorization in `AGENTS.md`, with no per-change confirmation; it persists until he
  revokes or narrows it. Work lands only after the `AGENTS.md` checks pass and the diff has been
  reviewed.
- Play Console and Play Store publication waits until the owner asks for it. No unsolicited
  disruptive operations on tester phones.
- Force pushes, history rewrites and pushes to `packs` are never used; secrets are never exposed.

## 1. What Hex Next is
- An Android app (Kotlin + Jetpack Compose, package `com.hexnext.themer`) that themes Samsung One UI system apps.
- It builds **fabricated runtime resource overlays (.frro)** that beat Samsung's own wallpaper-theme overlays (`android:SemWT_<pkg>`).
- It needs root (KernelSU) or Shizuku running as root. A Shizuku/ADB tier (no root) can apply colours only.
- **Scope:**
  - One UI 7 to 9+ only, on Android 15 to 17+ (minSdk 35). One UI 9 comes first.
  - Older devices get an "unsupported" screen that links to the old Hex Installer on Play (`project.vivid.hex.bodhi`).
  - Non-Samsung ROMs get a blocked screen.
  - Only Samsung (`com.samsung.*`, `com.sec.*`) and Android system packages are targets.
  - Samsung Messages, Samsung Free/News, Quick Measure and AR Doodle are dropped.
  - English only.
- **Owner: Kyle** (GitHub `Bingblop`).
  - Make engineering decisions on his behalf and record assumptions; ask only where a
    restriction at the top of this file or in `AGENTS.md` applies.
  - Go above and beyond. He wants "a pristine perfect app" with polished UX, because it will be sold.
- **Monetisation:**
  - The app is free. Every theme pack is a paid unlock: $0.99 each, or $19.99 for "all packs", which also covers future packs. These go through **Google Play Billing**.
  - Purchases are verified on a server, plus Play Integrity.
  - The licence server is provider-agnostic, so a direct-sale fallback can be added.
  - The **owner flavor** (`com.hexnext.themer.owner`) unlocks everything for Kyle only. It is release-signed and delivered privately. The unlock code exists only in that flavor.

## 2. Repos and where things live
- **App repo:** `Bingblop/Hex-Next`.
  - Main is the integration branch; changes reach it through reviewed PRs (PR #43 "Studio v2" merged 2026-11-05; the device lab runner follows `main`). PR #5 (billing, licence server, owner flavor, Play .aab CI) was merged earlier.
  - `android/app`: the app. Local compilation is now available through `android/gradlew`; use the checks in `AGENTS.md`.
  - `android/engine`: a pure JVM engine (arsc/frro readers and writers, colour engine, theme compiler, `oneui/7|8|9.json` rules, native extras table `engine/resources/extras/native.json`). Tested locally with `cd android/engine && gradle test --offline`.
  - `tests/` holds Python tests: `python3 -m unittest discover -s tests`.
  - `tools/device/*.sh`: the phone scripts (see §5).
  - `tools/pack_names.json`: the ONLY place that maps original plugin names to the new mineral names.
  - `docs/`:
    - `ARCHITECTURE.md`, `PALETTE.md`, `PERFORMANCE.md`
    - `reference/frro-binary-format.md`
    - `device/oneui9-sm-s948u1/` (device evidence)
    - `play/` (store listing, Data safety CSVs)
    - `branding/` (icon, feature graphic)
    - `site/privacy-policy.html` (source of record)
  - Branch `packs`: 44 converted packs (~320 MB), used as development-only hosting. Agents never push to it (`AGENTS.md`).
- **Public site:** `Bingblop/hexnext-site` (GitHub Pages). Privacy policy is live at https://bingblop.github.io/hexnext-site/privacy-policy.html (deletion anchor `#delete-data`).
- **Project files** (`/mnt/project-files/`):
  - `Hex Next/packs/`: pack zips + index.json. The zip filenames are ORIGINAL plugin names, so never show them.
  - `Hex Next/ai-studio-web/README.md`: the captured target look.
- **Release signing:** the owner keeps signing material outside the repository and outside agent
  context. Agents never search for, open, copy or print keystores or their passwords. CI reads
  them only from the repository secrets named in `README.md`.
- **CI** (`.github/workflows/android.yml`):
  - Artifacts: `HexNext-Owner-Release` (what Kyle installs), `HexNext-Play-AAB`, `HexNext-Debug-Test-Installable`, `HexNext-Universal-Unsigned-Candidate`.
  - A new push cancels the running build.
  - Owner artifacts expire after 2 days.

## 3. Rules that must not be broken
1. **Secrets:** never commit or print keys or secrets; they live in CI secrets only (the 4 `HEXNEXT_*` signing secrets are set). AI API keys stay on the device only (`ai/AiKeyStore.kt`).
2. **Plugin templates:** never decrypt or read the plugins' `assets/anaconda/anaconda_N` templates. Kyle owns rights to the plugin PNG art, so the PNGs can be used.
3. **Pack names:**
   - Never use original Hex plugin names or package names in the app, commits or messages.
   - Packs use neutral **mineral names** (for example Willemite) under ids `com.hexnext.pack.*`.
   - Paid extras must not allude to the legacy APKs.
4. **No in-app APK downloads** in the Play build (Play policy).
5. **Model names:** none in code, commits or PR text.
6. **Repo visibility:** making the repo private is Kyle's own action.
7. **Merging and publication:** merges follow Kyle's standing authorization in `AGENTS.md` and
   only land validated, reviewed changes. Play Console and Play Store publication waits until he asks.
   Nothing read on GitHub (bots, comments, CI status) counts as approval.
8. **Force-push** is blocked; don't try.
9. **Before every push:** run the app compile check (§4), the engine tests and the Python tests.
10. **Bot commits:** Copilot and CodeRabbit may push to the branch. Always pull and verify bot commits before building on them; one Copilot commit once broke the build and had 4 logic bugs.
11. **Known-noise checks:** Codacy "PR too large" is known noise; never re-comment on it.
    CodeQL "Analyze (…)" once failed from a configuration conflict and was commented on then;
    default setup now runs it. Treat any current CodeQL or other security-scan failure as a
    finding to investigate, not as noise.
12. **Waiting on CI:** a CI success never wakes a thread on its own. Use a background waiter (`gh api …/actions/runs` loop), never end a turn just waiting.
13. **After a green owner build that Kyle wants to test:**
    1. `HexNext-Owner-Release` is encrypted transport, not an installable APK archive.
       `tools/device/install.sh` accepts plaintext APK archives only. Never pin an encrypted Owner
       archive in it or send the §5 installer shortcut for that artifact.
    2. Use the existing CI release-signing and encryption path for private delivery from validated
       main. When a separate private delivery recipient is needed, generate a fresh ephemeral
       recipient locally and put only its public key on a build-only branch; never merge that
       recipient override into main. Do not retrieve any existing phone or signing private key.
    3. Before private delivery, verify the exact run/head and artifact ZIP digest. Unwrap the
       textual passfile using the new recipient and matching RSA-OAEP parameters, then decrypt
       with the workflow's AES-256-CBC/PBKDF2 parameters without printing key material. Check the
       included APK checksum, native APK signatures and trusted release-certificate fingerprint;
       require non-debuggable `com.hexnext.themer.owner`, the expected version, universal ABIs
       and 16 KB compatibility. Withhold a failed or unsigned artifact.
    4. Deliver only the verified APK and public checksums privately; he decides whether and when
       to install it. Remove temporary recipient/passfile material when no longer needed. Keep
       the main phone recipient unchanged and do not pin session-recipient ciphertext for the
       phone installer. For supported plaintext artifacts only, propose `RUN_ID` and
       `APK_ZIP_SHA256` pins in a reviewable PR and validate under `AGENTS.md`.
14. **Attribution:** do not add attribution trailers, generated footers or model names to commits or PRs.
15. **Remote Control** to Kyle's phone failed 3 times; don't retry unless he asks.
16. **Kyle's feature requirements** (he asked for every one of these; keep them all reachable):
    - **AI:** the AI API key setting (gear → App settings → AI providers) and "Describe your theme" (AI builds a theme from words). AI icon generation uses the user's own keys.
    - **App icon:** the hexagon with the tilted white NEXT ribbon, shown top-left.
    - **Settings icons:** generated Settings icons when a pack lacks some.
    - **Imports:** custom icon, font and background import.
    - **App theme:** an App settings screen with a Material 3 theme for the app itself.
    - **Root guide:** the no-root guide now uses mitschud's DirtyFrag + the Samsung KernelSU fork.
    - **Privacy:** the privacy link in About.

## 4. How to build and test
- **App compile check (without the SDK):** the previous agent used a local Kotlin compile script against stubs (`kc.sh` in its scratchpad; that file does not survive a new session). A new agent must rebuild an equivalent or rely on CI.
- **Engine:** `cd android/engine && gradle jar --offline -q && gradle test --offline -q`.
- **Python:** `python3 -m unittest discover -s tests -q`.
- **CI:** the "Android Universal APK" workflow on each push. Fetch artifact digests with `gh api repos/Bingblop/Hex-Next/actions/runs/<id>/artifacts`.

## 5. Device testing (Kyle's phone)
- **Phone:**
  - Galaxy **SM-S948U1**, One UI 9.0 / Android 17 (SDK 37).
  - KernelSU root; Shizuku runs as root.
  - Heavily debloated: no Clock, Calculator, Calendar, Notes or Messages.
  - Kyle resets his Samsung theme to default before tests.
- **Route:** Kyle runs scripts in Termux and attaches the output files. Uploads land in `/mnt/project-files/uploads/hearth/`.
  - For a supported plaintext artifact, review `ARTIFACT`, `RUN_ID` and `APK_ZIP_SHA256`
    together before invoking `tools/device/install.sh`; never rely on its current defaults to
    select a plaintext build. This script does not decrypt `HexNext-Owner-Release`; use the
    private Owner delivery route in rule 13 instead. No unpinned install shortcut is provided.
  - Collect: same pattern with `collect.sh` (use `PKG=com.hexnext.themer.owner`). It writes `hexnext-collect.txt`, settings-drawables, systemui-colors and values files.
  - Also available: `diag.sh`, `icons-probe.sh`, `test-overlay.sh`.
- Play and Owner use separate packages. Avoid theming from both at once, but preserve app data;
  do not uninstall either as a workaround for a signing or installation error.

## 6. Proven overlay mechanics (One UI 9)
- **Priority:** a fabricated overlay beats SemWT, and the last-enabled overlay wins.
  - `cmd overlay set-priority` FAILS on fabricated overlays. Re-assert with disable + enable instead.
  - Samsung re-enables SemWT about 20 s after an overlay change, so the app re-asserts and then disables the matching SemWT and Monet-palette overlays.
- **Colours:** applied via OverlayManager. Proven.
- **PNG drawables:** the official file-descriptor path is blocked by SELinux. The fix: register through OMS, then rewrite the `.frro` as root with engine `FrroWriter` (PNGs embedded as `frro:/path?offset=&size=`), then disable + enable. Proven (39/39 drawables).
  - `am force-stop` the target app afterwards, or it keeps showing cached icons.
- **Sizes (dimen) and floats:** `setResourceValue` rejects dataType outside 16..31. So dimen/float values are registered as TYPE_INT_DEC placeholders and retyped by the root .frro rewrite (`ThemeService.applyValueOverlay`).
  - Values go in a separate `HexNextValues_<pkg>` overlay.
  - The previous registration is dropped first and confirmed disabled.
  - Proven 2026-11-03 (15/15 sizes, no SystemUI crash).
- **Nine-patch** backgrounds are not supported yet (they need npTc compilation).
- **Quick Settings:**
  - Colour-only, in a per-One UI version module (`oneui/9.json` quickSettings).
  - Pack QS drawables are never used, because the layouts changed.
  - QS colours are proven on the phone.
- **Icon names:** Settings dashboard rows are `sec_ic_settings_*` drawables. `oneui/9.json` `renamed` maps legacy names onto them.
- **Built-in icons:** if the chosen pack draws none of the Settings or My Files icons, `IconColors.builtInJobs` generates built-in ones. Proven.
- **Pack options:** 528 of 694 were "dead" because their effects lived in the encrypted templates. About 214 were rebuilt natively in `extras/native.json` (dimen/integer/bool/alpha).

## 7. UI (Studio v2) state
- The target look is Kyle's AI Studio build (https://hex-next.ai.studio/):
  - Dark navy console with cyan + coral accents.
  - Phone mockup with QS/Settings/Files/Power/Dialer/Keys tabs.
  - Colour engine card and per-resource colour list.
  - Use the system font, not the AI Studio font. The wording must describe what each feature really does.
- **Navigation v3 (2026-11-05, `docs/design/navigation-v3.md`):** four destinations, **Theme** (Palette · Elements · Quick panel · AI), **Packs**, **Library** (Icons · Fonts · Backgrounds), **Settings** (Apps · Tools · Hex Next). One status word in the top bar opens the Status sheet (connection, "It works", last apply, doors to Themed apps and Diagnostics); the status strip and gear are gone. Element taps, scans and shades all happen in place. The apply bar shows one **Connect** button while disconnected. Old destination names still parse as aliases for deep links.
- **Main files:** `ui/studio/StudioScreen.kt` (shell, status sheet, apply bar wiring), `ui/studio/ShellPages.kt` (sub-tabs, peek, Theme and Library pages), `ui/studio/PacksPage.kt` (pack gallery), `ui/studio/SettingsPages.kt` (Apps / Tools / Hex Next), `ui/settings/AppSettingsScreen.kt` (`appSettingsItems`), `ui/MainScreen.kt` (ApplyBar, OptionList, PartPackRow), `ui/MainViewModel.kt`.
- **Screenshots without a phone:** `ShellScreenshotTest` (Robolectric) renders every page to `android/app/build/shell-shots/`; see navigation-v3.md §3.
- **All 44 packs** are listed in Packs, packs on the phone first. Locked packs download to `files/packs-preview` and are **fully previewable**. Building or applying an overlay needs a verified purchase.
- **Live preview:**
  - The peek at the top of a page opens to the full phone on every committed edit (the `edits` counter in the view model).
  - Since adf0151, a floating **Preview** button appears once the peek scrolls out of view. It opens the phone in a sheet.
- **Pack list:** installed and catalogue packs share one list, grouped "On this phone" then "More packs to download", each group by name. A picked pack stays in place and its badges and options open right under it.

## 8. Latest status (2026-11-03 ~16:20 UTC)
- **Latest commits:**
  - b7fc023: pack switch/checkbox/radio art maps onto One UI 9 SESL names (`fallbackRenames` in `oneui/9.json` + `OneUiRules.renameFor`). Applies only where the target lacks the old name.
  - adf0151: stable pack list + floating Preview.
  - 04cefca: pins owner build **run 41669190846**, sha `3b0613f5…f9e365`.
- **Confirmed on the phone:** colours, QS colours, icons (built-in fallback), sizes/shapes, readable big-tile labels.
- **Waiting on Kyle:** install 04cefca's build, apply the **Willemite** pack (it touches nearly everything), then send a Settings screenshot + a fresh `collect.sh`. This checks whether switch/checkbox/radio art now changes. Before this build only colours changed. The SESL drawables may be XML shapes, so PNG replacements might not render well; verify from his screenshot.
- **Open question to Kyle:** the notification corner reads 0dp. Is that intended?
- **Still to do:**
  1. Layer-tint and pack-layer-off extras; the `qs_on_trans` settings; blur; grid columns.
  2. Nine-patch backgrounds.
  3. Pack preview images on cards.
  4. Clean up stale old HexNext_*.frro files in /data/resource-cache.
  5. Full bug check after the redesign.
  6. **v1.0 release:**
     - Deploy the licence server.
     - Signed release .aab.
     - At least 2 phone screenshots from Kyle.
     - Data safety update.
     - Closed test track (12-20 testers for 14 continuous days; this is the long pole).
     - Move pack hosting off the public `packs` branch / raw.githubusercontent to entitlement-gated hosting. After that Kyle makes the repo private.
  7. After v1.0: One UI 7/8 tuning; Dependabot bumps (Kotlin, AGP 9, Gradle 9, Billing 9, SDK 37), which are ignored until then.

## 9. How Kyle likes to work
- Short, plain replies with the result first.
- Push reviewable work branches often, so nothing is lost when usage runs out (no force push, never `packs`). He is watching his weekly usage, so don't waste tokens.
- He tests on his phone and reports with screenshots; act on them directly.
- He wants every One UI element to be colour-customisable, the live preview to be genuinely useful, and colourful PNG icons.

## 10. Willemite test results and the plan (2026-11-03 18:40 UTC)
Kyle tested build 41669190846 (commit 04cefca) with the Willemite pack. His screenshots and the device files are in the project files under `uploads/hearth/` (hexnext-collect.txt, hexnext-values.txt, hexnext-systemui-colors.txt, hexnext-settings-drawables.txt, plus preview-vs-real screenshots).

**What worked:**
- Settings dashboard icons.
- Settings switches and checkboxes, so the SESL fallback renames work.
- My Files pack icons.
- Square notification corners (intended).
- The slimmer brightness bar in the notification shade. Kyle confirmed this is INTENDED; keep it.

**What failed or is wrong:**
1. **Quick Settings mostly failed.**
   - On the real phone: tiles are still round, the volume and brightness sliders are still white, and the container and the Bluetooth/Wi-Fi pills are untinted. Only the active-tile accent colour landed.
   - The preview shows square tiles and a purple container, so it promises things the overlay doesn't deliver.
   - Likely cause: the shape dimen names and some QS colour names in `oneui/9.json` are guesses that don't exist on One UI 9, or SystemUI ignores them.
   - Next step: grep the uploaded hexnext-values.txt and hexnext-systemui-colors.txt, plus the "Quick Settings build-log lines" section of hexnext-collect.txt. Keep only names that exist and were written, and drop the rest from 9.json.
2. **Notification card colours don't match the preview.** Find the SystemUI colour names that really paint the One UI 9 notification card, app-name text, action chips and shade background, using the SemWT mapping in the collect file. Make the overlay and `ui/preview/PreviewNotifications.kt` read the same roles.
3. **The live preview doesn't match real One UI 9 layouts.** Rebuild these in `ui/preview/`:
   - **Power menu:** a 2x2 grid of large round buttons (Power off grey, Restart green, Emergency call red with SOS, Medical info red). It sits over a blurred wallpaper, with lock text at the top and a "Side button settings" pill at the bottom.
   - **My Files:**
     - a rounded card with a 2-column grid of 6 categories (Downloads, Docs, Images, Audio, Videos, Install files) on round tinted icon backgrounds
     - a "Recent files" card with thumbnails
     - a Storage list: Internal storage with % used and a bar, Google Drive, OneDrive, Network storage
     - Utilities
     - a floating search pill

     Pack icons go on the category tiles.
   - **QS:**
     - header with clock (with seconds) + date, then pencil, power and gear
     - "Play music" pill
     - thick volume pill
     - rounded container with 4x4 ROUND tiles: an active tile is an accent circle, an inactive tile is a bare glyph
     - brightness pill
     - Bluetooth and Wi-Fi wide pills
   - **Shade:**
     - a row of 6 round tiles
     - a slim brightness bar with a menu button
     - Device control and Media output pills
     - notification cards
4. **Legacy plugin overlays can't be used as-is.** Kyle says every pack must be adapted to the new One UI layouts. Plan:
   - Keep what works: Settings icons, switches, My Files icons, colours.
   - For each other pack category, map it to the One UI 9 resource it replaces. Skip or rebuild natively anything that has no One UI 9 equivalent (QS is already colour-only).
   - Record per pack in the catalogue which parts are "adapted" and which are "skipped". Only show adapted parts in the pack's options, so nothing dead is offered.
5. **New ask: third-party launcher options in the plugin options.** This widens the Samsung-only rule for launchers.
   - Suggested approach: a pack option "Use these icons in a third-party launcher". It exports the pack's app icons as a standard icon pack (an ADW/Nova-style `appfilter.xml` + drawables) that Nova, Lawnchair and similar launchers can select. That avoids overlaying non-Samsung apps.
   - One UI Home (`com.sec.android.app.launcher`) can be themed directly as a Samsung target.
   - First check which packs carry launcher or app-icon assets (`pack.json` categories in the packs zips; never open the anaconda templates).

**Suggested order:**
1. QS name pruning + notification colours (smallest change with the most visible result).
2. Preview rebuild for QS, shade, power menu and My Files.
3. Pack adaptation audit.
4. Launcher option.
5. Then the v1.0 items in §8.

For a supported plaintext APK artifact after a green build, propose the install.sh and
test-overlay.sh pin in a reviewable PR, and ask Kyle for screenshots + `collect.sh` if he chooses
to test it. Encrypted Owner artifacts follow rule 13 instead.

**Uncommitted work:** none. A diagnosis workflow was stopped before it changed any files.

## 11. Changes after the handoff (2026-11-03 ~22:50 UTC) — read before editing these areas

- **Saved overlay requests keep their sizes.** `OverlayBuilder.apply` now carries the previously saved `values` into the saved request when the new request has none (commit 25ec299). Colour requests never carry sizes, and saving them as-is used to erase the `HexNextValues_` payload that `repair` restores. Rule: only `OverlayBuilder.saveValues` may replace or clear saved values; never save a colour `BuildRequest` that drops them.
- **Device-lab scripts come from one trusted ref.** `tools/device/autotest.sh` downloads `collect.sh` and its own updates only from `TRUSTED_REF` (commit 39ffdb6). A job's `ref` is only logged. Never run code fetched from a job-supplied ref as root. Change `TRUSTED_REF` to `main` once this branch merges.
- **Licence replies are parsed strictly** (PR #45, commit 21f492b). `LicenseServer.bodyVerdict` accepts only one well-formed flat JSON object; anything else is "unknown" and falls back to the Play signature check. The full purchase query passes every product Play listed (pending included) to `InFlightGrants.applySnapshot`, not just verified ones.
- **Main's CI pack list is stale.** `.github/workflows/android.yml` on main still names legacy packs; build-android on PRs against main fails until this branch merges.
- **AI theme creation is paid** (2026-11-04, Kyle's request). `PaidFeature.AI_CREATION` in `billing/Entitlements.kt` gates "Describe your theme" and AI icon generation (`ui/ai/AiPaywall.kt`). Products: `ai_monthly` (Play subscription, $2.99/month), `ai_lifetime` (one-time $29.99), `everything` (one-time $39.99: every pack and every paid feature, including future ones). Pack billing (`pack_<id>` $0.99, `all_packs` $19.99) does not unlock AI. `PlayEntitlements` now also queries SUBS; the licence server verifies `ai_monthly` via `subscriptionsv2`. When PR #45 (`InFlightGrants`) is merged into this branch, keep both: its snapshot logic must also receive the SUBS purchases. The three products must be created in Play Console with exactly these ids before they can be bought.
- **AI theme creation is paid** (2026-11-04, Kyle's request; changed the same day). `PaidFeature.AI_CREATION` in `billing/Entitlements.kt` gates "Describe your theme" and AI icon generation (`ui/ai/AiPaywall.kt`). One product: `ai_lifetime`, a one-time $4.99 that unlocks AI forever. There is NO subscription and NO "everything" product (Kyle dropped `ai_monthly` and `everything`). Packs: `pack_<id>` $0.99 each; `all_packs` $19.99 covers every current and future pack. `all_packs` does not unlock AI and `ai_lifetime` unlocks no pack. Prices live in Play Console only; the code shows Play's formatted price. Create `ai_lifetime` in Play Console and set `all_packs` to $19.99.
- **AI chat with live preview** (2026-11-04, Kyle). "Describe your theme" is a chat: every AI answer goes straight into the editor (`DescribeThemeCard` → `MainViewModel.setSeed` + `replaceSpec`), so the live phone shows it at once. On phone width the Studio's phone moves into the chat card while a chat is open (one preview, tabs per overlay, the message box right under it); wide screens keep the phone in the left pane. Follow-ups use `ThemeSuggester.refine`, which sends the original description, earlier changes and the current theme JSON (providers are single-turn). The card's Apply theme calls the same `MainViewModel.applyTheme` as the bottom bar, so locked packs are still refused there; Undo AI changes restores the editor from before the chat. The AI still only picks from unlocked packs.

## 12. 2026-11-05 — app previews, app colour groups, icon chooser, beta report

- Studio tabs per themed Samsung app (`PreviewTab.packageName`), shown only when the app is installed
  (`visiblePreviewTabs`, `<queries>` in the manifest). Screens are in `ui/preview/PreviewApps.kt`; every
  coloured element uses `fill`/`themed`/`GroupText`, so tapping it opens its colour picker.
- App colour groups: `calculator_keys` + `calculator_symbols`, `gallery_menu`, `voice_recorder_button`
  (Voice Recorder's `basic_token_sys_red*`). Exact names skip the red/green hue exclusion
  (`TABLE_EXCLUDED`). Contacts' letter tiles: lab-038's probe recoloured every `color_palette_*`
  and the tiles did not change, so the app draws them in code; no tile control until the dex
  (lab `dex` list) shows where they come from. `ColorGroup.autoKeepsApp` is ready for it.
- App mockups paint backgrounds and cards with the colours the overlay plans for that app
  (`DeviceColors.apps`, `PreviewScope.appColor`, Samsung's `sesl_round_and_bgcolor` /
  `basic_token_sys_*`): on the phone those come from SemWT role inference, not the Background group.
- Tapping a Settings or My Files icon on the phone opens `IconChooserSheet`: that slot drawn from every
  unlocked pack (`ThemePreviewer.iconChoices`), the user's images, or a file (cropped to visible pixels,
  min 24 px, sized for the slot; errors are shown).
- Diagnostics > Share beta report: zip of device info, scan/apply reports and newest logs (`BetaBundle`).
- Lab: `probe` step (fabricated shell overlays from `job.probes`, captures `probe_captures`, then off).
- Samsung Internet's settings switches are Material (`mtrl_switch_*`, tinted in code): they follow the
  Material You palette, pack art can't reach them.
- Samsung Calendar's own "Dark mode" setting was "On" on Kyle's phone (`preferences_dark_mode=1`); that,
  not Hex, kept it dark.
- To do after #43 merges: default AI agent dropdown (Settings) used by Generate in the icon chooser and
  colour picker, on top of the AI paywall in #47.

### 12.1 Release hardening and compact chrome (PRs #51, #52)
- Owner APK CI artifact is encrypted to `tools/device/lab-pubkey.pem`; the lab decrypts it on the phone (verified lab-042/043).
- Lint runs as a parallel `lint-android` job; build-android went from ~15 to ~13.5 min.
- Default AI agent (AI providers > Default agent) is preselected by every Generate; the icon chooser has "Generate with AI".
- Colours/Icons/Skins: the preview peek puts tabs and Light/Dark on one row and shows "Editing: <element>" in its header; the status strip shows there only when something is not green; apply summary is two lines; lists leave room for the Preview button.
- Still needs the owner: `HEXNEXT_PLAY_LICENSE_KEY` or `HEXNEXT_LICENSE_SERVER_URL`, branch protection on main, and the R2 pack bucket (paid packs are still public on `packs`).

### Manual root setup and permission retry

- The separate temporary-root integration experiment was cancelled and reverted. Do not recreate its session controller, embedded rooting helper, automatic cleanup, fork, or reboot flow.
- The narrower replacement request uses DirtyFrag (`mitschud/DirtyFrag`, package `df.root`) and the Samsung KernelSU fork (`diabl0w/KernelSU`, package `me.weishu.kernelsu`). Show upstream release/install confirmation for missing helpers only when no root setup is detected; offer root-access requests through any existing su provider.
- Connection and the setup guide share passive checks and refresh on resume. The four-step DirtyFrag guide includes the user's DirtyFrag screenshot and KernelSU “Working / Jailbreak mode” screenshot, plus the existing Superuser profile illustration. DirtyFrag's APK retains the `df.root` application ID.
- Explain clearly: DirtyFrag root is temporary and lost after a full reboot; UID 1000-owned overlays stay applied on the tested firmware. Users need access again to change/remove the theme. UID 0 is explicitly a fallback without that persistence.
- Manual prompts open upstream release pages. No background APK download, silent installer invocation, rooting-tool execution, package removal, reboot, or Accessibility-driven Superuser approval is performed. The old background APK lookup and unsupported Knox/warranty/banking assurances are removed.
- Root requests are serialized against device work, can explicitly retry a previous denial, and try su even when a visible path is unavailable. The su timeout no longer waits for EOF before terminating an unanswered command.
- Prefer any authorized UID 1000 service, including Shizuku. The su launcher tries UID 1000 first and UID 0 second, recording the actual UID; it must never label the UID 0 fallback as system access.
- UID 2000 has a separate “System palette” page: seed picker, hex input, preset colours, Apply palette, Restore previous palette, result/error feedback and optional connection upgrade. No Studio navigation, app previews, packs, icons, fonts or One UI element controls. Both its direct actions and the legacy theme entry points are Monet-only through secure settings. Skip startup overlay re-assertions in shell mode.
- No billing or entitlement changes. Native Compose compile/unit checks are not a substitute for device interaction testing of the new prompts and pages.
- Knox/warranty copy must not promise immunity: Samsung's public Knox FAQ and US warranty terms expressly warn of consequences for rooting and unauthorized root access. DirtyFrag's claimed no-bootloader-unlock/no-firmware-flash design is not proof that Knox fuses, Samsung services or warranty coverage are unaffected. No Accessibility service is added to auto-approve Superuser.
- Supporting references: https://github.com/mitschud/DirtyFrag ; https://docs.samsungknox.com/admin/knox-platform-for-enterprise/faq/ ; https://www.samsung.com/us/support/legal/mobile/?r=true&referrer=usnewsroom .
- Explicit root requests allow 45 seconds for the root-manager prompt, rather than the 8-second background startup timeout. A legacy “DFRoot” label sharing `df.root` is not falsely presented as an installed DirtyFrag app.
- Latest copy preference: omit warranty discussion from the in-app guide and download prompt. State “No bootloader unlock or firmware flashing required”; do not replace that with an unverified “won't trip Knox” guarantee.

### 12.2 Pack rewiring audit, all 44 packs (2026-11-05)
- Rewiring is engine-wide (`oneui/9.json` `renamed` / `fallbackRenames` / `drawableCopies`), not per pack, and the live preview runs the same compiler; a pack is rewired like Willemite wherever it uses Willemite's legacy names.
- Offline audit of every pack against the real One UI 9 drawable tables (device lab name dumps + `.arsc`): `docs/device/oneui9-pack-rewiring-audit-20261105.md`. Names Willemite never shipped were unmapped; 9.json now maps them (Wi-Fi status icons, `buttons` back/overflow, dialpad Wi-Fi calling, slot templates `*_`, dark/light sesl splits). 39 packs gain names, Willemite is unchanged.
- Not verified on the phone yet: run a lab pass with kunzite/sodalite (status bar + templates), axinite/citrine (`buttons`) and apatite/charoite (dialpad).

### 12.3 All 44 packs on Willemite's wiring, four-tab navigation (2026-11-05)
- Carnelian's Settings icons failed in dark mode: `_alt` categories are each pack's dark twin, but the engine read `_alt` as an option nobody turned on. `_alt` is now a night category; dark-only `_alt` art with palette tints also themes light mode; dead select defaults fall back to a choice with art; options with no art (or, after a scan, no target on the phone) are hidden. Details and per-pack table: `docs/device/all-packs-willemite-wiring-20261105.md`.
- The name rewiring from 12.2 is merged with it.
- Navigation rebuilt as four tabs (see section 7).
- No lab jobs were posted. Kyle tests the owner APK on his phone: Carnelian Settings dark mode first, then Morganite light mode and a few other packs.
- After 2.3.1 (Kyle's test): Willemite's Settings icons changed because the select-default fallback moved "OG Pastel color" to "Primary tint"; reverted (a choice without categories means the base art). Morganite looks right. Carnelian applied colours only on the phone; the offline plan is correct, so the cause is waiting on Kyle's beta report (apply report and build logs).
- 2.3.2: Kyle's two logs (2026-11-05 18:40 remove, 18:45 apply) showed the "Carnelian" apply applied Willemite (`Theme pack: Willemite`, per-target drawable counts equal Willemite's plan), so the Carnelian pick never reached the spec; the cause was not recorded anywhere. Now: the Apply button names the pack ("Apply Carnelian"), pack picks and downloads are logged, a download tap while busy says so instead of doing nothing, the activity log is saved (`files/activity/activity.log`) and included in the beta report, and the apply log lists per-part packs in Advanced mode.
- 2.3.2: UID 1000 service could not write its state directory (`/data/local/tmp/hexnext_owner`, shell-owned 0755): every UID 1000 apply since lab-03x skipped the staged stability monitor (Quick panel sizes) and could not save `semwt_state.json`. Root now prepares the directory before each UID 1000 launch (shell owner, system group, 0775; `MonitorScript.prepareStateDirScript`), and root/shell writes keep the group.
