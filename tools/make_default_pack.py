#!/usr/bin/env python3
"""Build the free default pack, Quartz, from the original Hex Installer app's own designs.

Quartz is the look Hex Installer applied with no plugin: its default palette (dark slots
`xx*/xy*/xz0`, light slots `lxx*/lxy*/lxz0`), the readable element art it tints itself
(`assets/anaconda_tints/`, `assets/common_tints/`: toast frames, edge handles, text-select
handles, widget resize frame, send button, message masks) and its preview screenshots
(`assets/anaconda_previews/theme_*`). Its encrypted `assets/anaconda/` templates are never read
(AGENTS.md); Hex Next draws those parts itself, as it does for every pack.

The pack ships inside the APK (`android/app/src/main/assets/packs/quartz.zip`), is seeded on
first start, and is free: its id is not in PaidPacks and it is never in the packs catalogue.

Usage:
    python3 tools/make_default_pack.py <decompiled Hex Installer folder> [<out.zip>]
The folder is decompiler output with resources/assets/ and sources/ (see docs/plugin-sources.md).
"""
from __future__ import annotations

import base64
import io
import json
import re
import sys
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from hex_pack_builder import _write_pack  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_OUT = ROOT / "android" / "app" / "src" / "main" / "assets" / "packs" / "quartz.zip"
PACK = {"id": "quartz", "name": "Quartz", "version": "1.0"}
TINT_DIRS = ("anaconda_tints", "common_tints")
PUT = re.compile(r'putIfAbsent\("([^"]+)", "([^"]*)"\)')


def installer_defaults(root: Path) -> dict[str, str]:
    """Hex Installer's default settings map (palette and switches), from its References class."""
    for f in (root / "sources").rglob("References.java"):
        text = f.read_text(errors="replace")
        if '"lxx0"' in text and '"xx0"' in text:
            # The class also maps legacy theme package names; only plain setting keys are defaults.
            return {k: v for k, v in PUT.findall(text) if "." not in k}
    raise SystemExit("References.java with the default palette not found under sources/")


def source_zip(root: Path, defaults: dict[str, str]) -> bytes:
    assets = root / "resources" / "assets"
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_STORED) as zf:
        zf.writestr("assets/default.hex", base64.b64encode(json.dumps(defaults).encode()).decode())
        for d in TINT_DIRS:
            for f in sorted((assets / d).rglob("*")):
                if f.is_file():
                    zf.write(f, f.relative_to(root / "resources").as_posix())
        for f in sorted((assets / "anaconda_previews").glob("theme_*")):
            zf.write(f, f"assets/info_images/{f.name}")
    return buf.getvalue()


def build(root: Path, out: Path) -> dict:
    defaults = installer_defaults(root)
    meta = {"file": "default", "package": None, "label": PACK["name"], "name": PACK["name"],
            "author": "", "version": PACK["version"], "day_night": True, "features": [],
            "encrypted_templates": 0, "categories": {}}
    out.parent.mkdir(parents=True, exist_ok=True)
    tmp = out.with_name(out.name + ".tmp")
    pack = _write_pack(source_zip(root, defaults), tmp, meta, PACK)
    tmp.replace(out)
    return {k: pack.get(k) for k in ("id", "name", "dayNight", "previewCount")} | {
        "palette": len(pack["palette"]), "layers": len(pack["layers"])}


def main(argv: list[str]) -> int:
    if len(argv) not in (2, 3):
        print(__doc__)
        return 2
    out = Path(argv[2]) if len(argv) == 3 else DEFAULT_OUT
    info = build(Path(argv[1]), out)
    print(f"{out}  {out.stat().st_size / 1e6:.2f} MB  {info}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
