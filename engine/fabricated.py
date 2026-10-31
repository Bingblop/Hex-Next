from typing import List, Optional
from .shizuku_bridge import ShizukuBridge


class FabricatedOverlayBuilder:
    """
    Constructs and manages dynamic Fabricated Runtime Resource Overlays (RRO)
    on top of Samsung's system RRO stack using UID 0 (root) or UID 1000 (system)
    privileges on One UI 9.0 (Android 16).
    """

    TYPE_COLOR = "0x1c"
    TYPE_INT = "0x10"
    TYPE_BOOL = "0x12"
    TYPE_STRING = "0x03"
    TYPE_DIMEN = "0x05"

    def __init__(
        self,
        target_package: str,
        overlay_name: str,
        bridge: Optional[ShizukuBridge] = None,
        rro_carrier: Optional[str] = None,
    ):
        self.target = target_package
        self.name = overlay_name
        self.bridge = bridge or ShizukuBridge()
        self.rro_carrier = rro_carrier or f"com.samsung.High_contrast_theme_II.{overlay_name}"
        self.entries: List[str] = []

    def add_color(self, resource_name: str, hex_color: str):
        """
        Adds a color override (`<package>:color/<name>` or `color/<name>`).
        hex_color: '#RRGGBB' or '#AARRGGBB' or integer hex '0xFFRRGGBB'
        """
        clean = hex_color.replace("#", "").replace("0x", "").replace("0X", "").strip()
        if len(clean) == 6:
            clean = "ff" + clean
        val = f"0x{clean.lower()}"
        res_ref = resource_name if ":" in resource_name else f"{self.target}:color/{resource_name}"
        self.entries.append(f"{res_ref} {self.TYPE_COLOR} {val}")
        return self

    def add_bool(self, resource_name: str, val: bool):
        """Adds a boolean resource override."""
        bool_val = "1" if val else "0"
        res_ref = resource_name if ":" in resource_name else f"{self.target}:bool/{resource_name}"
        self.entries.append(f"{res_ref} {self.TYPE_BOOL} {bool_val}")
        return self

    def add_integer(self, resource_name: str, val: int):
        """Adds an integer resource override."""
        res_ref = resource_name if ":" in resource_name else f"{self.target}:integer/{resource_name}"
        self.entries.append(f"{res_ref} {self.TYPE_INT} {val}")
        return self

    def build_command(self) -> str:
        """Returns the One UI 9.0 cmd overlay fabricate command string."""
        entries_str = " ".join(self.entries)
        return f"cmd overlay fabricate --target {self.target} --name {self.name} {entries_str}"

    def build_and_apply(self) -> bool:
        """Fabricates and enables the overlay using UID 0 or UID 1000 access."""
        if not self.entries:
            return False

        cmd = self.build_command()
        code, out, err = self.bridge.run(cmd)
        if code != 0:
            print(f"Fabrication failed: {err or out}")
            return False

        for owner in (self.bridge.owner_package, "com.android.shell", "android"):
            full_overlay_id = f"{owner}:{self.name}"
            code_en, _, _ = self.bridge.run(f"cmd overlay enable --user 0 {full_overlay_id}")
            if code_en == 0:
                self.bridge.run(f"cmd overlay set-priority --user 0 {full_overlay_id} highest")
                return True
        return False

    @classmethod
    def remove(cls, overlay_name: str, bridge: Optional[ShizukuBridge] = None) -> bool:
        """Disables a fabricated or Samsung system RRO overlay across UID 0 and UID 1000 owners."""
        b = bridge or ShizukuBridge()
        for owner in ("com.android.shell", "android"):
            full_overlay_id = f"{owner}:{overlay_name}"
            b.run(f"cmd overlay disable --user 0 {full_overlay_id}")
        return True
