from .shizuku_bridge import ShizukuBridge
from typing import Optional, Dict

class SamsungSystemProperties:
    """Manages Samsung One UI theme system settings and overlay states."""

    def __init__(self, bridge: Optional[ShizukuBridge] = None):
        self.bridge = bridge or ShizukuBridge()

    def get_active_theme_package(self) -> Optional[str]:
        """Reads current_sec_active_themepackage from system settings."""
        code, out, _ = self.bridge.run("settings get system current_sec_active_themepackage")
        if code == 0:
            val = out.strip()
            return val if val and val != "null" else None
        return None

    def set_active_theme_package(self, package_name: str = "com.samsung.themedesigner.Hex") -> bool:
        """Sets current_sec_active_themepackage in system settings."""
        code, _, _ = self.bridge.run(f"settings put system current_sec_active_themepackage {package_name}")
        return code == 0

    def reset_active_theme_package(self) -> bool:
        """Resets active theme package to default stock."""
        code, _, _ = self.bridge.run("settings delete system current_sec_active_themepackage")
        return code == 0

    def list_overlays(self, target: Optional[str] = None) -> str:
        """Returns the output of cmd overlay list."""
        cmd = f"cmd overlay list {target}" if target else "cmd overlay list"
        code, out, err = self.bridge.run(cmd)
        return out if code == 0 else err

    def enable_overlay(self, overlay_name: str) -> bool:
        """Enables a system overlay."""
        code, _, _ = self.bridge.run(f"cmd overlay enable --user current {overlay_name}")
        return code == 0

    def disable_overlay(self, overlay_name: str) -> bool:
        """Disables a system overlay."""
        code, _, _ = self.bridge.run(f"cmd overlay disable --user current {overlay_name}")
        return code == 0
