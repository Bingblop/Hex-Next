# Hex Installer Reverse Engineering & Failure Analysis on One UI 9.0

## 1. Decompilation Analysis

Target Package: `project.vivid.hex.bodhi`  
Decompiled with `jadx` v1.5.3 on OpenJDK 21.

### Key Classes & Entry Points:
1. `project.vivid.hex.core.app.HexCoreSetupActivity`: Setup wizard managing prerequisites (ADB, Sideloader, Theme Park theme).
2. `project.vivid.hex.bodhi.references.References`: Hardcoded strings, system settings lookups, and package definitions.
3. `project.vivid.hex.bodhi.services.HexBuilderService`: Background foreground service compiling asset overlays.
4. `o_.hex._o.eeeh$$$$eeexe$$_exe$h`: Obfuscated overlay compilation pipeline and AAPT wrapper.

---

## 2. Setup Screen Checks

### Check 1: Theme Park Theme Verification
Located in `project/vivid/hex/bodhi/references/References.java`:
```java
public static boolean isThemeParkThemeApplied(boolean z) {
    String activeTheme = Settings.System.getString(
        context.getContentResolver(), 
        "current_sec_active_themepackage"
    );
    if (activeTheme != null && activeTheme.startsWith("com.samsung.themedesigner")) {
        return true; // Green checkmark ✔
    }
    return false;    // Red X ❌
}
```

### Check 2: Theme Park Installation Verification
Hex Installer checks if `com.samsung.android.themepark` is installed on device. On One UI 9.0, Theme Park was refactored into `com.samsung.android.themedesigner`, causing Hex Installer to mark the prerequisite missing.

**Resolution:** Deploy a dummy, zero-overhead compatibility stub APK registered as `com.samsung.android.themepark`.

---

## 3. Build & Install Failure Analysis ("Compiling Assets Failed")

During the "Build & Install" phase, Hex Installer attempts to:
1. Extract precompiled Android resource assets from `.hex_temp/asset_factory`.
2. Invoke internal 32-bit/64-bit native binary tools to build overlay APKs with hardcoded resource IDs for One UI 3/4/5 framework resources.
3. On One UI 9.0 (Android 16 SDK 37), Samsung refactored internal resource IDs in `framework-res.apk` and `SystemUI`, causing legacy resource table generation to fail for all targets.

---

## 4. Modern Architecture Solution

Instead of relying on legacy hardcoded AAPT asset compilers:
1. Use **Fabricated Overlays** (`cmd overlay fabricate`) for immediate color and dimension tweaks directly at runtime.
2. Use dynamic AAPT2 linking against the device's live `/system/framework/framework-res.apk` to guarantee accurate resource symbol mapping on any One UI version.
