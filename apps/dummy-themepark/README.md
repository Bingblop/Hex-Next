# Samsung Theme Park Compatibility Stub for One UI 6 / 7 / 8 / 9

### Background
Starting in modern One UI revisions (including One UI 6.x, 7.x, 8.x, and 9.0), Samsung rebranded and refactored the internal package of Good Lock's Theme Park from `com.samsung.android.themepark` to `com.samsung.android.themedesigner`.

Legacy theming applications like Hex Installer hardcode package checks against `com.samsung.android.themepark`. Without this package present in the package manager query, Hex Installer refuses to proceed and marks the setup as incomplete.

### Purpose
This module provides a lightweight, zero-overhead stub APK (`android:hasCode="false"`, `targetSdkVersion="34"`, zero permissions) that registers the package identifier `com.samsung.android.themepark` in Android's package manager.

### Building & Installing
To build the stub APK on-device using Termux:
```bash
./build.sh
```

To install directly using Shizuku (`rish`):
```bash
rish -c "pm install -r build/themepark-stub-signed.apk"
```
