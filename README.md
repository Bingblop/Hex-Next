# Samsung One UI 9.0 Modern Theme Engine & Hex Compatibility Suite

A next-generation theming framework, diagnostic suite, and compatibility layer for **Samsung Galaxy devices running One UI 7, 8, and 9.0 (Android 15, 16, and 17)**.

---

## 📱 HexNext app (native, `android/`)

The main product is now a native Kotlin + Jetpack Compose app in [`android/`](android/), built by
GitHub Actions on every push. Grab the APK from the **Android APK** workflow run's artifacts (or from a
release when a `v*` tag is pushed). It needs Android 14+ and [Shizuku](https://shizuku.rikka.app/).

- **Shizuku running as root (or Sui):** HexNext registers real fabricated overlays on the framework:
  all 65 Monet shades (`system_accent1..3_*`, `system_neutral1..2_*`) plus the Android 14+ Material role
  colors, generated from your accent color and palette style. Pure-black dark mode is available.
  Every step shows the real result, and the app reads the color back to verify it applied.
- **Shizuku over wireless debugging (no root):** Android refuses fabricated overlays from the shell user
  (`OverlayManagerService`: "Non-root shell cannot fabricate overlays", and `cmd overlay fabricate`
  requires root). HexNext instead hands the seed color and style to System UI's own Monet engine through
  `theme_customization_overlay_packages`. On One UI 9 this recolors Material You apps but not One UI's own UI.

Build locally with `cd android && ./gradlew assembleDebug`. The old WebView app in `apps/hex-next/`
is kept only as a design reference.

---

## 🌟 Features

- **Fabricated Runtime Resource Overlays (RRO):** Dynamic, instantaneous color and resource styling via native Android `cmd overlay fabricate`—no slow compilation, no reboot required.
- **Hex Installer One UI 9 Fix Suite:** Fully documented reverse engineering and compatibility layer enabling legacy themers to pass all setup prerequisites.
- **Theme Park Stub Engine:** Zero-overhead compatibility stub APK resolving the `com.samsung.android.themepark` ➔ `com.samsung.android.themedesigner` namespace migration.
- **HexBridge Pro App & Web Dashboard:** Native Android WebView client (`com.hexbridge.pro`) + lightweight local web server with Shizuku integration for full remote theming and diagnostics.
- **Theme Profiles:** Ready-to-use JSON theme templates (AMOLED Pure Black, Neon Violet, Cyberpunk Gold).



---
For detailed analysis, refer to [docs/HEX_INSTALLER_REVERSE_ENGINEERING.md](docs/HEX_INSTALLER_REVERSE_ENGINEERING.md).
