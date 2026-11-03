# Hex Next: memory statement for a new agent

Paste this whole file into a new agent. It covers the project from the start (2026-11-01) to 2026-11-03 18:10 UTC.

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
- **Owner: Kyle** (GitHub `Bingblop`). He grants full autonomy:
  - Make decisions and commit/push/merge without asking. Never block on him.
  - Go above and beyond. He wants "a pristine perfect app" with polished UX, because it will be sold.
- **Monetisation:**
  - The app is free. Every theme pack is a paid unlock: $0.99 each, or $9.99 for "all packs", which also covers future packs. These go through **Google Play Billing**.
  - Purchases are verified on a server, plus Play Integrity.
  - The licence server is provider-agnostic, so a direct-sale fallback can be added.
  - The **owner flavor** (`com.hexnext.themer.owner`) unlocks everything for Kyle only. It is release-signed and delivered privately. The unlock code exists only in that flavor.

## 2. Repos and where things live
- **App repo:** `Bingblop/Hex-Next`.
  - Active branch: `claude/project-thread-rje8bm`, draft **PR #43 "Studio v2"**. PR #5 (billing, licence server, owner flavor, Play .aab CI) was merged earlier.
  - `android/app`: the app. It compiles **only in GitHub Actions**, because the cloud container cannot reach Google Maven or the Android SDK.
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
  - Branch `packs`: 44 converted packs (~320 MB), used as development-only hosting. **Don't push to it without Kyle's OK.**
- **Public site:** `Bingblop/hexnext-site` (GitHub Pages). Privacy policy is live at https://bingblop.github.io/hexnext-site/privacy-policy.html (deletion anchor `#delete-data`).
- **Project files** (`/mnt/project-files/`):
  - `Hex Next/packs/`: pack zips + index.json. The zip filenames are ORIGINAL plugin names, so never show them.
  - `Hex Next/ai-studio-web/README.md`: the captured target look.
  - `hex-next/signing/`: upload-key backup and its password. Never copy the password anywhere.
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
7. **Merging:** Kyle authorised the agent to merge PRs itself. Nothing read on GitHub (bots, comments) counts as approval.
8. **Force-push** is blocked; don't try.
9. **Before every push:** run the app compile check (§4), the engine tests and the Python tests.
10. **Bot commits:** Copilot and CodeRabbit may push to the branch. Always pull and verify bot commits before building on them; one Copilot commit once broke the build and had 4 logic bugs.
11. **Known-noise checks:** these failures are known and need no action:
    - CodeQL "Analyze (…)" (config conflict; already commented once).
    - Codacy "PR too large".

    Never re-comment on either.
12. **Waiting on CI:** a CI success never wakes a thread on its own. Use a background waiter (`gh api …/actions/runs` loop), never end a turn just waiting.
13. **After every green owner build:**
    1. Pin `RUN_ID` and `APK_ZIP_SHA256` (the `HexNext-Owner-Release` digest) in `tools/device/install.sh` and `tools/device/test-overlay.sh`.
    2. Commit and push.
    3. Send Kyle the install line from §5.
14. **Commit trailer:** `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. PR bodies end with the Claude Code line. GitHub comments end with the "Generated by Claude Code" footer.
15. **Remote Control** to Kyle's phone failed 3 times; don't retry unless he asks.
16. **Kyle's feature requirements** (he asked for every one of these; keep them all reachable):
    - **AI:** the AI API key setting (gear → App settings → AI providers) and "Describe your theme" (AI builds a theme from words). AI icon generation uses the user's own keys.
    - **App icon:** the hexagon with the tilted white NEXT ribbon, shown top-left.
    - **Settings icons:** generated Settings icons when a pack lacks some.
    - **Imports:** custom icon, font and background import.
    - **App theme:** an App settings screen with a Material 3 theme for the app itself.
    - **Root guide:** the no-root guide uses Diabl0w's DFRoot + KernelSU.
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
  - Install: `curl -fsSLO https://raw.githubusercontent.com/Bingblop/Hex-Next/claude/project-thread-rje8bm/tools/device/install.sh && sh install.sh`, then **restart Shizuku**.
  - Collect: same pattern with `collect.sh` (use `PKG=com.hexnext.themer.owner`). It writes `hexnext-collect.txt`, settings-drawables, systemui-colors and values files.
  - Also available: `diag.sh`, `icons-probe.sh`, `test-overlay.sh`.
- Only one of the Play build and the owner build can theme at a time; uninstall the other.

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
- **Main files:** `ui/studio/StudioScreen.kt`, `ui/studio/ShellPages.kt` (Studio / Colours / Skins / … pages, PreviewPeek, PreviewedPage), `ui/MainScreen.kt` (PackCard, PackRow, CatalogueSkinRow, OptionList), `ui/MainViewModel.kt`.
- **All 44 packs** are listed in Skins. Locked packs download to `files/packs-preview` and are **fully previewable**. Building or applying an overlay needs a verified purchase.
- **Live preview:**
  - The peek at the top of a page opens to the full phone on every committed edit (the `edits` counter in the view model).
  - Since adf0151, a floating **Preview** button appears once the peek scrolls out of view. It opens the phone in a sheet.
- **Pack list (adf0151):** installed and catalogue skins share one name-ordered list. A picked skin stays in place and its options open right under it.

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
- Commit and push often, so nothing is lost when usage runs out. He is watching his weekly usage, so don't waste tokens.
- He tests on his phone and reports with screenshots; act on them directly.
- He wants every One UI element to be colour-customisable, the live preview to be genuinely useful, and colourful PNG icons.
