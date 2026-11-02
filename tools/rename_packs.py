#!/usr/bin/env python3
"""Give already-built packs their catalogue names (tools/pack_names.json).

Reads every pack zip in <in-dir>, rewrites its pack.json with the catalogue id, name and com.hexnext.pack.* package
(dropping the source plugin's package and replacing source plugin names in option text),
writes <new-id>.zip into <out-dir> and regenerates index.json there. With <in-dir> == <out-dir>
the source zip is removed once its renamed copy is written. A zip whose id is
already a catalogue id is rewritten with duplicate entries removed. A zip whose id is neither
a mapped source id nor a catalogue id is refused: nothing is written and the tool exits 1,
so a pack can never ship under its source plugin's id or name. hex_pack_builder.py applies the same mapping
when it converts a plugin, so this is only needed for packs built before the mapping.

Usage:
    python3 tools/rename_packs.py <in-dir> <out-dir>
"""
from __future__ import annotations

import json
import os
import re
import sys
import zipfile
from pathlib import Path

NAMES = json.loads((Path(__file__).resolve().parent / "pack_names.json").read_text())
CATALOGUE_IDS = frozenset(e["id"] for e in NAMES.values() if isinstance(e, dict))


def pack_package(pack_id: str) -> str:
    """The package name a pack ships under: `com.hexnext.pack.<id>` with `-` as `_`."""
    return "com.hexnext.pack." + pack_id.replace("-", "_")


def catalogue_identity(source_id: str) -> tuple[str, str] | None:
    """(id, name) a pack converted from the plugin with [source_id] ships under, or None."""
    entry = NAMES.get(source_id)
    return (entry["id"], entry["name"]) if isinstance(entry, dict) else None


def _source_name_replacements(pack_id: str) -> list[tuple[re.Pattern, str]]:
    """(pattern, catalogue name) for every source plugin name in pack_names.json, longest first.

    A name the pack's own entry lists becomes the pack's own catalogue name; any other becomes
    the catalogue name of the first entry that lists it.
    """
    own = next((e for e in NAMES.values() if isinstance(e, dict) and e["id"] == pack_id), None)
    by_name: dict[str, str] = {}
    for entry in NAMES.values():
        if isinstance(entry, dict):
            for name in entry.get("sourceNames", []):
                by_name.setdefault(name.lower(), entry["name"])
    if own is not None:
        for name in own.get("sourceNames", []):
            by_name[name.lower()] = own["name"]
    # A name may be glued to a trailing "_" ("jajanken_ Navbar Icons"); drop it with the name.
    return [(re.compile(r"(?<![A-Za-z0-9])" + re.escape(name) + r"_?(?![A-Za-z0-9])", re.I), new)
            for name, new in sorted(by_name.items(), key=lambda kv: -len(kv[0]))]


def scrub_text(text: str, replacements: list[tuple[re.Pattern, str]]) -> str:
    for pattern, new in replacements:
        text = pattern.sub(new, text)
    return text


def scrub_options(options: list, pack_id: str) -> list:
    """Options with every source plugin name in their label and choices replaced (the app shows both)."""
    replacements = _source_name_replacements(pack_id)
    scrubbed = []
    for option in options:
        option = dict(option)
        if isinstance(option.get("label"), str):
            option["label"] = scrub_text(option["label"], replacements)
        if isinstance(option.get("choices"), list):
            option["choices"] = [scrub_text(c, replacements) if isinstance(c, str) else c
                                 for c in option["choices"]]
        scrubbed.append(option)
    return scrubbed


def strip_source(pack: dict) -> None:
    """Removes every trace of the source plugin from a pack.json dict and sets the pack's package."""
    pack.pop("sourcePackage", None)
    pack["package"] = pack_package(pack["id"])
    if isinstance(pack.get("options"), list):
        pack["options"] = scrub_options(pack["options"], pack["id"])


def unique_entries(infos: list[zipfile.ZipInfo]) -> list[zipfile.ZipInfo]:
    """Drops duplicate entry names, keeping the largest (highest-density) copy.

    The legacy plugins ship `res/drawable-*dpi/<name>.png` in several densities, which the first
    builder flattened to one `drawables/<name>.png` entry per density. Android's ZipFile refuses a
    zip with duplicate names ("Duplicate entry name"), so such a pack could not be installed.
    """
    best: dict[str, zipfile.ZipInfo] = {}
    for info in infos:
        kept = best.get(info.filename)
        if kept is None or info.file_size > kept.file_size:
            best[info.filename] = info
    return [best[info.filename] for info in infos if best[info.filename] is info]


def unknown_id(src: Path) -> str | None:
    """The pack id of [src] when it is neither a mapped source id nor a catalogue id, else None."""
    with zipfile.ZipFile(src) as zin:
        pack_id = json.loads(zin.read("pack.json"))["id"]
    if catalogue_identity(pack_id) is None and pack_id not in CATALOGUE_IDS:
        return pack_id
    return None


def rename_zip(src: Path, out_dir: Path) -> tuple[dict, bool]:
    with zipfile.ZipFile(src) as zin:
        pack = json.loads(zin.read("pack.json"))
        identity = catalogue_identity(pack["id"])
        if identity is None and pack["id"] not in CATALOGUE_IDS:
            raise ValueError(f"{src.name}: id {pack['id']!r} is not in pack_names.json; "
                             "add the source plugin to the naming table first")
        if identity is not None:
            pack["id"], pack["name"] = identity
        strip_source(pack)
        # The app reads author as a non-null string; repair packs built with a null one.
        pack["author"] = pack.get("author") or ""
        dst = out_dir / f"{pack['id']}.zip"
        # Write beside dst and rename over it only when complete: with <in-dir> == <out-dir> a
        # catalogued pack's dst is src itself, and opening it for writing would truncate the
        # zip zin is still reading (a large pack was left as an empty archive).
        tmp = dst.with_name(dst.name + ".tmp")
        try:
            with zipfile.ZipFile(tmp, "w") as zout:
                for info in unique_entries(zin.infolist()):
                    if info.filename == "pack.json":
                        continue
                    zout.writestr(info, zin.read(info))
                zout.writestr("pack.json", json.dumps(pack, indent=1), zipfile.ZIP_DEFLATED)
        except BaseException:
            tmp.unlink(missing_ok=True)
            raise
    os.replace(tmp, dst)
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
    zips = sorted(src.glob("*.zip"))
    # Check every zip before writing anything, so a refused run leaves <out-dir> untouched.
    unknown = [(path.name, pack_id) for path in zips if (pack_id := unknown_id(path)) is not None]
    if unknown:
        for name, pack_id in unknown:
            print(f"error: {name}: id {pack_id!r} is neither a source id nor a catalogue id "
                  "in tools/pack_names.json; add it to the naming table first", file=sys.stderr)
        print(f"refused: {len(unknown)} unmapped pack(s); nothing was written", file=sys.stderr)
        return 1
    out.mkdir(parents=True, exist_ok=True)
    in_place = src.resolve() == out.resolve()
    index: dict[str, dict] = {}
    unmapped = []
    for path in zips:
        entry, mapped = rename_zip(path, out)
        # Two inputs with one catalogue id (a source zip and an earlier rename of it) write the
        # same <id>.zip; list it once.
        index[entry["id"]] = entry
        if not mapped:
            unmapped.append(path.name)
        print(f"  {path.name} -> {entry['file']}  ({entry['name']})")
        # With <in-dir> == <out-dir> the source zip would stay next to its renamed copy and
        # still carry the source plugin's id, name and package; remove it.
        if in_place and path.resolve() != (out / entry["file"]).resolve():
            path.unlink()
    index = sorted(index.values(), key=lambda e: e["name"].lower())
    (out / "index.json").write_text(json.dumps({"format": 1, "packs": index}, indent=1) + "\n")
    # An unmapped pack here carries a catalogue id (checked above; a second pass over the
    # catalogue itself); it is rewritten with duplicate entries removed and reported only.
    print(f"renamed {len(index) - len(unmapped)} packs; already catalogued: {unmapped or 'none'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
