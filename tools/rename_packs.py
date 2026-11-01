#!/usr/bin/env python3
"""Give already-built packs their catalogue names (tools/pack_names.json).

Reads every pack zip in <in-dir>, rewrites its pack.json with the catalogue id, name and com.hexnext.pack.* package
(dropping the source plugin's package),
writes <new-id>.zip into <out-dir> and regenerates index.json there. Zips without a
mapping are copied unchanged and reported. hex_pack_builder.py applies the same mapping
when it converts a plugin, so this is only needed for packs built before the mapping.

Usage:
    python3 tools/rename_packs.py <in-dir> <out-dir>
"""
from __future__ import annotations

import json
import sys
import zipfile
from pathlib import Path

NAMES = json.loads((Path(__file__).resolve().parent / "pack_names.json").read_text())


def pack_package(pack_id: str) -> str:
    """The package name a pack ships under: `com.hexnext.pack.<id>` with `-` as `_`."""
    return "com.hexnext.pack." + pack_id.replace("-", "_")


def catalogue_identity(source_id: str) -> tuple[str, str] | None:
    """(id, name) a pack converted from the plugin with [source_id] ships under, or None."""
    entry = NAMES.get(source_id)
    return (entry["id"], entry["name"]) if isinstance(entry, dict) else None


def strip_source(pack: dict) -> None:
    """Removes every trace of the source plugin from a pack.json dict and sets the pack's package."""
    pack.pop("sourcePackage", None)
    pack["package"] = pack_package(pack["id"])


def rename_zip(src: Path, out_dir: Path) -> tuple[dict, bool]:
    with zipfile.ZipFile(src) as zin:
        pack = json.loads(zin.read("pack.json"))
        identity = catalogue_identity(pack["id"])
        if identity is None:
            dst = out_dir / src.name
            dst.write_bytes(src.read_bytes())
        else:
            pack["id"], pack["name"] = identity
            strip_source(pack)
            dst = out_dir / f"{identity[0]}.zip"
            with zipfile.ZipFile(dst, "w") as zout:
                for info in zin.infolist():
                    if info.filename == "pack.json":
                        continue
                    zout.writestr(info, zin.read(info))
                zout.writestr("pack.json", json.dumps(pack, indent=1), zipfile.ZIP_DEFLATED)
    entry = {"id": pack["id"], "name": pack["name"], "author": pack["author"],
             "version": pack["version"], "file": dst.name, "bytes": dst.stat().st_size,
             "categories": sorted(pack["categories"]), "dayNight": pack["dayNight"],
             "previewCount": pack["previewCount"]}
    return entry, identity is not None


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__)
        return 2
    src, out = Path(sys.argv[1]), Path(sys.argv[2])
    out.mkdir(parents=True, exist_ok=True)
    index, unmapped = [], []
    for path in sorted(src.glob("*.zip")):
        entry, mapped = rename_zip(path, out)
        index.append(entry)
        if not mapped:
            unmapped.append(path.name)
        print(f"  {path.name} -> {entry['file']}  ({entry['name']})")
    index.sort(key=lambda e: e["name"].lower())
    (out / "index.json").write_text(json.dumps({"format": 1, "packs": index}, indent=1) + "\n")
    print(f"renamed {len(index) - len(unmapped)} packs; unmapped: {unmapped or 'none'}")
    return 1 if unmapped else 0


if __name__ == "__main__":
    raise SystemExit(main())
