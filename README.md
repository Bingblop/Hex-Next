# Samsung One UI 9.0 Modern Theme Engine

A next-generation theming framework, diagnostic suite, and compatibility layer for **Samsung Galaxy devices running One UI 7, 8, and 9.0 (Android 15, 16, and 17)**.

---

## 📱 HexNext app (native, `android/`)

The main product is now a native Kotlin + Jetpack Compose app in [`android/`](android/), built by
GitHub Actions on pushes and pull requests. Install the **signed universal APK from GitHub Releases**;
the unsigned CI candidate is for validation, not installation. It needs Android 14+ and
[Shizuku](https://shizuku.rikka.app/).

- **Shizuku running as root (or Sui):** HexNext registers real fabricated overlays on the framework:
  all 65 Monet shades (`system_accent1..3_*`, `system_neutral1..2_*`) plus the Android 14+ Material role
  colors, generated from your accent color and palette style. Pure-black dark mode is available.
  Every step shows the real result, and the app reads the color back to verify it applied.
- **Shizuku over wireless debugging (no root):** the current native app rejects theme application from
  the ordinary shell UID. Fabricated overlay application requires root or a supported privileged
  system session; there is no non-root Monet fallback in this release.

Build locally with `cd android && ./gradlew assembleDebug`. The old WebView app in `apps/hex-next/`
is kept only as a design reference.

## Universal APK release

The installed application ID is `com.hexnext.bingblop`. The source namespace remains
`com.hexnext.themer`; builds using the former application ID install as a separate app, not an update.

The release is the compiled native app in `android/`, not a web dashboard export. Its universal APK
supports ARM and x86, both 32-bit and 64-bit, and includes English resources. Managed-code builds
need no native `.so` files; any native dependencies must include all four supported ABIs.

Use JDK 17 and the Android SDK with `platforms;android-36` and `build-tools;36.0.0`.
For a local signed build, supply `HEXNEXT_KEYSTORE_PATH` (absolute path recommended),
`HEXNEXT_STORE_PASSWORD`, `HEXNEXT_KEY_ALIAS`, and `HEXNEXT_KEY_PASSWORD` as environment variables,
or in an ignored `android/keystore.properties` file using the same names. Then run:

```bash
./bin/build-and-sign-release-apk.sh
```

The output is `dist/HexNext_OneUI9_Universal_EN_Signed.apk`, with a SHA-256 checksum and a verification
report. The build runs unit tests and release lint, and verifies compiled resources, launcher,
SDK versions, universal architecture coverage, 16 KB ELF/ZIP alignment, and real v1/v2/v3 signatures.
Missing or incomplete signing configuration fails; there is no debug-key or generated-key fallback.

For GitHub publishing, configure repository Actions secrets `HEXNEXT_KEYSTORE_BASE64` (the Base64
encoding of your production keystore), `HEXNEXT_STORE_PASSWORD`, `HEXNEXT_KEY_ALIAS`, and
`HEXNEXT_KEY_PASSWORD`. Keep the production key private and backed up; future updates must use the
same signing identity. Previously committed placeholder keystores have been removed and must not
be used for releases. Never commit real signing keys or passwords.

After merging a passing PR, ensure `versionName` in `android/app/build.gradle.kts` matches the release
tag without its `v` prefix, and increase `versionCode` for every subsequent release.

Run **Actions > Release Universal APK > Run workflow**, select `main`, and enter a tag such as
`v2.1.0`. Manual runs default to a **draft release**, allowing device smoke testing before publication.
Uncheck the draft option only when ready to publish. An existing tag must point to the selected
commit; new releases target that exact commit rather than a moving branch. Alternatively:

```bash
gh workflow run release.yml --ref main -f tag=v2.1.0 -f draft=true
```

Pushing a matching `v*` tag also runs this dedicated workflow and publishes automatically. Versions
with a prerelease suffix such as `-rc.1` are marked as GitHub prereleases. The workflow reuses Android
CI, then builds and verifies the production-signed APK before attaching the APK, checksum,
verification report, and R8 mapping file. Release signing secrets are never passed to reusable CI.
Ordinary CI builds unsigned candidates and separately tests signing with an ephemeral CI-only key;
those test-signed APKs are never uploaded or published.

Before publishing, smoke-test the signed APK on a supported Samsung device: install/upgrade, launch,
grant Shizuku access, apply and remove a theme, and check both privileged and non-root behavior.
Automated packaging checks do not establish Samsung overlay compatibility.

---

## 🌟 Features

- **Fabricated Runtime Resource Overlays (RRO):** Dynamic, instantaneous color and resource styling via native Android `cmd overlay fabricate`—no slow compilation, no reboot required.
- **Native HexNext App:** Kotlin and Jetpack Compose controls for privileged overlay application.
- **Web Preview Dashboard:** Theme configuration, palette previews, and JSON exports; APK releases are built by Gradle.
- **Theme Profiles:** Ready-to-use JSON theme templates (AMOLED Pure Black, Neon Violet, Cyberpunk Gold).



---
For detailed analysis, refer to [docs/HEX_INSTALLER_REVERSE_ENGINEERING.md](docs/HEX_INSTALLER_REVERSE_ENGINEERING.md).
