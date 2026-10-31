"""
One UI Theme Engine
Modern theme manager and dynamic runtime overlay engine for Samsung One UI 6, 7, 8, and 9.
"""

from .shizuku_bridge import ShizukuBridge
from .system_properties import SamsungSystemProperties
from .fabricated import FabricatedOverlayBuilder
from .overlay_builder import RROApkBuilder

__version__ = "1.0.0"
__all__ = [
    "ShizukuBridge",
    "SamsungSystemProperties",
    "FabricatedOverlayBuilder",
    "RROApkBuilder"
]
