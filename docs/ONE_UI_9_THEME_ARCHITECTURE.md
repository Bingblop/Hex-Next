# Samsung One UI 9.0 Theming Architecture & Internals

## 1. Executive Overview

Samsung's One UI theming subsystem has evolved significantly from legacy One UI (1.0 - 5.x) through modern Android releases (Android 14, 15, and 16 / One UI 6 - 9.0). 

Legacy tools like Hex Installer (`project.vivid.hex.bodhi`) relied on older assumptions:
1. Hardcoded package names for Samsung Good Lock modules (e.g. `com.samsung.android.themepark`).
2. Legacy overlay naming conventions and static APK compilation via older AAPT asset packaging.
3. Legacy Android system settings values (`current_sec_active_themepackage`).

Modern One UI introduces updated namespaces, Runtime Resource Overlay (RRO) frameworks, Fabricated Overlays (`cmd overlay fabricate`), and dynamic Monet palette integration.

---

## 2. Key Architecture Shifts in One UI 6 - 9.0

### A. Theme Park Package Renaming
- **Legacy Package:** `com.samsung.android.themepark`
- **Modern Package:** `com.samsung.android.themedesigner`
- **Impact:** Any legacy application querying `PackageManager.getPackageInfo("com.samsung.android.themepark", ...)` fails with `PackageManager.NameNotFoundException`.

### B. Monet Wizard Overlay Prefix (`SemWT_`)
Modern Samsung theming organizes wizard-generated overlays under the target package namespace with a `SemWT_` prefix:
- `android:SemWT_android` (Framework theme overlay)
- `android:SemWT_com.android.systemui` (SystemUI quick settings & notification styling)
- `android:SemWT_MonetPalette` (Dynamic palette base)
- `android:SemWT_G_MonetPalette` (Google dynamic color harmonization)

### C. System Theme State Setting
Samsung uses the system property `current_sec_active_themepackage` in `Settings.System`:
- **Default Stock Theme:** `null` (entry does not exist).
- **Theme Designer / Theme Park Theme:** `com.samsung.themedesigner.<ThemeName>`.
- Hex Installer evaluates this string via `.startsWith("com.samsung.themedesigner")`.

---

## 3. Fabricated Overlays vs RRO APKs

| Feature | Legacy RRO APKs | Modern Fabricated Overlays (`cmd overlay fabricate`) |
| :--- | :--- | :--- |
| **Delivery** | Signed APK installed via `pm install` | Runtime registration via shell IPC (`cmd overlay`) |
| **Installation Time** | Requires compilation, packaging, and APK staging | Instantaneous (< 50ms) |
| **Root/Privilege Requirement** | Shizuku / ADB shell (`pm install`) | Shizuku / ADB shell (`cmd overlay fabricate`) |
| **AAPT Dependency** | Requires AAPT2 / resources.arsc table | No asset compiler needed |
| **Reversibility** | Requires APK uninstall | Single command disable/remove |
