# Fabricated Overlays Guide for Samsung One UI (Android 12+)

## 1. Introduction

Fabricated Overlays (`FabricatedOverlay`) are a native Android capability introduced in Android 12 that allows creating Runtime Resource Overlays (RRO) dynamically via shell without building or signing APK packages.

## 2. Shell Command Syntax

```bash
cmd overlay fabricate \
  --target <TARGET_PACKAGE> \
  --name <OVERLAY_NAME> \
  <RESOURCE_TYPE>/<RESOURCE_NAME> <DATA_TYPE> <VALUE> ...
```

### Supported Data Types:
- `0x1c` : Color (`0xAARRGGBB` or `0xFFRRGGBB`)
- `0x10` : Integer (Decimal integer, e.g. `12`)
- `0x12` : Boolean (`0` for false, `1` for true)
- `0x03` : String (Plain text)
- `0x05` : Dimension (DP / SP unit encoded)

## 3. Practical Examples for Samsung One UI

### Example A: OLED Pure Black Quick Settings & SystemUI
```bash
cmd overlay fabricate \
  --target com.android.systemui \
  --name amoled_qs \
  color/qs_background_dark 0x1c 0xff000000 \
  color/quick_settings_panel_background_color 0x1c 0xff000000 \
  color/qs_tile_active_color 0x1c 0xff00e5ff

cmd overlay enable --user current com.android.shell:amoled_qs
```

### Example B: Accent Color Harmonization
```bash
cmd overlay fabricate \
  --target android \
  --name custom_accent \
  color/system_accent1_600 0x1c 0xff8a2be2 \
  color/system_accent1_100 0x1c 0xffede7f6

cmd overlay enable --user current com.android.shell:custom_accent
```

## 4. Reverting Changes
```bash
cmd overlay disable --user current com.android.shell:<OVERLAY_NAME>
```
Fabricated overlays created by `com.android.shell` can be re-enabled, replaced, or disabled on-the-fly without requiring a device reboot.
