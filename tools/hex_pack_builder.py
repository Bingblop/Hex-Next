#!/usr/bin/env python3
"""Convert legacy Hex plugins into Hex Next theme packs.

A Hex Next pack is a zip that the app downloads on demand:

    pack.json                     metadata, colour palette, options and layers
    layers/<pass>/<stage>/<tint>/<category>/<resource>.png
    previews/<n>.<ext>            plugin preview screenshots (if any)
    drawables/<resource>.png      plugin UI drawables (navbar/QS previews etc.)

Layer model (reverse-engineered from the readable plugin assets):
  * pass  - "once" (first render) or "twice" (second composite pass)
  * stage - "base", "first" or "second": z-order of the layer within a pass
  * tint  - colour applied to the greyscale PNG before compositing:
            a palette slot such as "xx9"/"xy1" (dark) whose light twin is "l"+slot,
            a fixed colour "Haarrggbb", or "default" (draw untinted)

Only readable PNG assets are copied. Encrypted `anaconda/anaconda_N` templates are
never touched; Hex Next generates those overlay parts itself.

Usage:
    python3 tools/hex_pack_builder.py <plugin-dir> <out-dir>
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
from hex_plugin_catalog import CATEGORY_LABELS, catalog_one, base_apk_bytes  # noqa: E402
from rename_packs import catalogue_identity, pack_package  # noqa: E402

SLOT_RE = re.compile(r"^l?(x[xyz]|lx[xyz]|sx|lz[xy]|xg|lxg)\d+$")
FIXED_RE = re.compile(r"^H([0-9A-Fa-f]{8})$")


def slug(text: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", text.lower()).strip("-") or "pack"


def parse_extra_preferences(text: str) -> tuple[list[dict], dict[str, list[str]]]:
    options, category_options = [], {}
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        key, value = key.strip(), value.strip()
        if key.startswith("$selectdialogpreference"):
            folders = key.split(",")[1:]
            labels = value.split(",")
            options.append({"type": "select", "keys": folders, "label": labels[0],
                            "choices": labels[1:]})
        elif value and ":" in value or (value and key not in CATEGORY_LABELS and key.isalnum()
                                        and not set(value) <= set("abcdefghijklmnopqrstuvwxyz,")):
            options.append({"type": "switch", "key": key, "label": value})
        else:
            category_options[key] = [v for v in value.split(",") if v]
    return options, category_options


def tint_descriptor(tint: str) -> dict:
    m = FIXED_RE.match(tint)
    if m:
        return {"kind": "fixed", "argb": "#" + m.group(1).upper()}
    if tint == "default":
        return {"kind": "none"}
    return {"kind": "slot", "slot": tint}


def build_pack(path: Path, out_dir: Path) -> dict:
    meta = catalog_one(path)
    raw = base_apk_bytes(path)
    source_id = slug(f"{meta['name'] or meta['label']}-{meta['version']}")
    # The catalogue never shows the source plugin's name (tools/pack_names.json).
    pack_id, pack_name = catalogue_identity(source_id) or (source_id, meta["name"] or meta["label"])
    palette, defaults, options, category_options = {}, {}, [], {}
    layers: dict[tuple, dict] = {}
    ui_drawables: dict[str, zipfile.ZipInfo] = {}
    out_zip = out_dir / f"{pack_id}.zip"
    previews = drawables = 0

    with zipfile.ZipFile(io.BytesIO(raw)) as src, \
            zipfile.ZipFile(out_zip, "w", zipfile.ZIP_STORED) as dst:
        names = src.namelist()
        if "assets/default.hex" in names:
            try:
                defaults = json.loads(base64.b64decode(src.read("assets/default.hex")))
            except Exception:  # noqa: BLE001
                defaults = {}
        for key, value in defaults.items():
            if isinstance(value, str) and value.startswith("#") and SLOT_RE.match(key):
                palette[key] = value.upper()
        if "assets/extra.preferences" in names:
            options, category_options = parse_extra_preferences(
                src.read("assets/extra.preferences").decode("utf-8", "replace"))

        for info in src.infolist():
            name = info.filename
            parts = name.split("/")
            if (len(parts) >= 7 and parts[0] == "assets" and parts[1].endswith("_tints")
                    and name.endswith(".png")):
                _, _, pass_, stage, tint, *category_path, file_name = parts
                category = "/".join(category_path)
                dst.writestr(f"layers/{pass_}/{stage}/{tint}/{category}/{file_name}", src.read(info))
                key = (pass_, stage, tint, category)
                entry = layers.setdefault(key, {
                    "pass": pass_, "stage": stage, "category": category,
                    "tint": tint_descriptor(tint), "path": f"layers/{pass_}/{stage}/{tint}/{category}",
                    "resources": [],
                })
                entry["resources"].append(re.sub(r"(\.9)?\.png$", "", file_name))
            elif name.startswith("assets/info_images/") and not name.endswith("/"):
                previews += 1
                ext = Path(name).suffix or ".png"
                dst.writestr(f"previews/{previews:03d}{ext}", src.read(info))
            elif (name.startswith("res/drawable") and name.endswith(".png")
                  and "mipmap" not in name):
                # Several densities share one basename; keep the largest so the zip has no
                # duplicate entry names (Android's ZipFile rejects those).
                kept = ui_drawables.get(Path(name).name)
                if kept is None or info.file_size > kept.file_size:
                    ui_drawables[Path(name).name] = info

        for file_name, info in ui_drawables.items():
            drawables += 1
            dst.writestr(f"drawables/{file_name}", src.read(info))

        pack = {
            "format": 1,
            "id": pack_id,
            "name": pack_name,
            "author": meta["author"],
            "version": meta["version"],
            "package": pack_package(pack_id),
            "dayNight": meta["day_night"],
            "features": meta["features"],
            "palette": dict(sorted(palette.items())),
            "settings": {k: v for k, v in sorted(defaults.items())
                         if not k.startswith("HEX_") and k not in palette
                         and k not in ("hex", "plugin", "plugin_name")},
            "options": options,
            "categoryOptions": category_options,
            "categories": {c: CATEGORY_LABELS.get(c, c)
                           for c in sorted({k[3] for k in layers})},
            "layers": sorted(layers.values(),
                             key=lambda l: (l["category"], l["pass"], l["stage"], l["path"])),
            "previewCount": previews,
            "drawableCount": drawables,
        }
        for layer in pack["layers"]:
            layer["resources"].sort()
        dst.writestr("pack.json", json.dumps(pack, indent=1))
    return {"id": pack_id, "name": pack["name"], "author": pack["author"],
            "version": pack["version"], "file": out_zip.name,
            "bytes": out_zip.stat().st_size, "categories": sorted(pack["categories"]),
            "dayNight": pack["dayNight"], "previewCount": previews}


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__)
        return 2
    src, out = Path(sys.argv[1]), Path(sys.argv[2])
    out.mkdir(parents=True, exist_ok=True)
    index, errors = [], []
    for path in sorted(src.iterdir()):
        if path.suffix.lower() not in (".apk", ".apks"):
            continue
        try:
            index.append(build_pack(path, out))
            print(f"  {index[-1]['file']}  {index[-1]['bytes'] / 1e6:.1f} MB")
        except Exception as exc:  # noqa: BLE001
            errors.append({"file": path.name, "error": repr(exc)})
            print("  error", path.name, exc)
    (out / "index.json").write_text(json.dumps({"format": 1, "packs": index}, indent=1) + "\n")
    print(f"built {len(index)} packs, {len(errors)} errors")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
