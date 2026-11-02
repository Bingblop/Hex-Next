# Hex Next

Hex Next is a theme engine for Samsung Galaxy phones running One UI 7, 8 and 9. It is a native
Android app (Kotlin, Jetpack Compose, Material 3) that recolours and re-skins One UI system apps
with fabricated runtime resource overlays built on the phone itself. Nothing is flashed, no
system partition is modified, and every change can be removed from inside the app.

The app is the product. The engine that plans and compiles overlays is a pure-JVM Kotlin module
that is unit-tested without an Android SDK.

## What it does

- **Colours.** A Material You palette (seed colour, style, contrast, pure black) written into One
  UI packages only, so Google and third-party apps keep the system palette.
- **Icons and artwork.** Theme packs supply greyscale layers that the app tints with the palette
  and composites per density into Settings dashboard icons, My Files icons and other drawables.
- **Custom icons.** Any icon slot can take an imported image, or an icon generated from a short
  text prompt with your own AI provider key (bring your own key; keys stay encrypted on the phone).
- **Quick Settings.** Colours only, matched to the One UI version's layout. Quick Settings
  artwork from packs is never applied because the panel layout changes with every One UI release.
- **Live preview.** The editor renders the same output the phone will receive before you apply.
- **Safety.** Apply reports, a safe-mode watchdog, one-tap "Remove everything", and a
  diagnostics screen that collects what support needs.

## Supported phones

| One UI | Android | Status |
|---|---|---|
| 9.0 | 16 / 17 | Primary target, tested on a Galaxy S26 Ultra (SM-S948U1) |
| 8.x | 16 | Supported, own rule file |
| 7.x | 15 | Supported, own rule file |

The app requires Android 15 or newer (`minSdk 35`). Phones on One UI 6 or older should use the
original Hex Installer from Google Play (`project.vivid.hex.bodhi`); Hex Next does not target them.

## Access tiers

Hex Next talks to the system through [Shizuku](https://shizuku.rikka.app/) and adapts to the
privilege it is given.

| Tier | How | What works |
|---|---|---|
| Full (root) | Shizuku started with root, Sui, or a root manager | Everything: colours, PNG icons and backgrounds, fonts, repair, watchdog |
| Colours only (Shizuku) | Shizuku over wireless debugging (shell uid 2000) | Fabricated colour overlays and the Monet seed; icon and artwork features are shown disabled with a "Needs root" badge |
| Not connected | Shizuku not running or permission denied | Preview and editing only |

The status pill in the top bar shows the current tier, Android release and One UI version.

## Build from source

Requirements: JDK 17, Android SDK with `platforms;android-36` and `build-tools;36.0.0`.

```bash
cd android
./gradlew assembleDebug                                   # installable debug build
./gradlew :app:testDebugUnitTest :app:lintRelease         # app unit tests and release lint
cd engine && gradle test                                  # engine tests (no Android SDK needed)
```

Debug builds are signed with the debug key and unobfuscated. Release builds are shrunk and
obfuscated by R8 (`android/app/proguard-rules.pro`) and must be signed with your own key:

```bash
export HEXNEXT_KEYSTORE_PATH=/absolute/path/release.keystore
export HEXNEXT_STORE_PASSWORD=... HEXNEXT_KEY_ALIAS=... HEXNEXT_KEY_PASSWORD=...
./bin/build-and-sign-release-apk.sh
```

The script runs the tests and lint, builds the universal APK (arm64-v8a, armeabi-v7a, x86_64,
x86; English resources), verifies it with `bin/verify-release-apk.py` (SDK versions, launcher,
16 KB alignment, v1/v2/v3 signatures) and writes `dist/HexNext_OneUI9_Universal_EN_Signed.apk`
with a SHA-256 checksum and a JSON report. Signing values may also live in an ignored
`android/keystore.properties`. Never commit a keystore or password.

### Continuous integration and releases

- `.github/workflows/android.yml` runs the tooling tests (`tests/`), the app unit tests, release
  lint, an unsigned universal candidate, an installable debug build, and a signing dry run with
  an ephemeral CI-only key.
- `.github/workflows/release.yml` publishes a GitHub Release from a `v*` tag or a manual run:
  `python3 bin/prepare-release.py` checks the tag against `versionName`, then the signed APK,
  checksum, report and R8 mapping are attached. Secrets: `HEXNEXT_KEYSTORE_BASE64`,
  `HEXNEXT_STORE_PASSWORD`, `HEXNEXT_KEY_ALIAS`, `HEXNEXT_KEY_PASSWORD`.
- Before publishing, smoke-test the signed APK on a supported phone: install or upgrade, grant
  Shizuku, apply and remove a theme, and check both the root and the colours-only tier.

## Testing on a phone

Scripts in `tools/device/` run from Termux (or `adb shell`) on the phone. All need root except
`diag.sh`.

| Script | Purpose |
|---|---|
| `install.sh` | Download the pinned CI debug build, check its SHA-256, `pm install -r` |
| `pack.sh` | Copy or download a theme pack zip into the app's pack folder |
| `diag.sh` | Read-only diagnostics: One UI version, Samsung `SemWT_*` overlays, root/Shizuku state, one sample `.frro` |
| `collect.sh` | Collect the app's reports, logs and overlay state into one file after a test |
| `icons-probe.sh` | Find the real Settings dashboard icon names and check whether Settings caches icons |
| `test-overlay.sh` | End-to-end overlay precedence test against `SemWT_com.android.settings`, then revert |

Captured device data lives in `docs/device/`.

## Repository layout

```
android/
  app/        Android app: Compose UI, settings, AI icon generation, Shizuku service,
              device discovery, apply, watchdog, diagnostics
  engine/     pure-JVM Kotlin: arsc and frro parsers, pack model, palette, resource mapping,
              theme compiler and One UI rule files (android/engine/src/main/resources/oneui/)
bin/          release tooling: build-and-sign-release-apk.sh, prepare-release.py,
              verify-release-apk.py
tests/        unit tests for the release tooling (python3 -m unittest discover -s tests -p 'test_*.py')
tools/        pack builder, catalogue tools, glyph generator, device scripts (see tools/README.md)
docs/         ARCHITECTURE.md, PALETTE.md, PERFORMANCE.md, reference/ notes, device/ captures,
              plugin-catalog/ (resource map used by engine tests), branding/, site/ (privacy policy)
```

Documentation starts at [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) and
[docs/PALETTE.md](docs/PALETTE.md). The fabricated overlay file format is in
[docs/reference/frro-binary-format.md](docs/reference/frro-binary-format.md).

## Licence

MIT, see [LICENSE](LICENSE). Hex Next ships only original artwork and glyphs. Theme packs are
converted from content the user already owns; the repository stores no third-party artwork.
