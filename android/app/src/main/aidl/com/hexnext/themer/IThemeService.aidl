package com.hexnext.themer;

import android.os.Bundle;

// Runs inside the Shizuku user service process, as shell (uid 2000) or root (uid 0).
// Every call returns a Bundle with "code" (int, 0 = success) and "output" (String).
interface IThemeService {
    // Reserved transaction code Shizuku uses to tear the service down.
    void destroy() = 16777114;

    int getUid() = 1;

    Bundle exec(String command) = 2;

    // Registers (or replaces) a fabricated overlay of ARGB colors and enables it. Root only.
    Bundle applyColorOverlay(String name, String targetPackage, in String[] resourceNames, in int[] colors) = 3;

    // Disables and unregisters a fabricated overlay this app created. Root only.
    Bundle removeOverlay(String name) = 4;
}
