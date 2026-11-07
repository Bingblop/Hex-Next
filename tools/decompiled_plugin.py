#!/usr/bin/env python3
"""Read a legacy Hex plugin that was exported as a decompiled folder instead of an APK.

Decompiler output (jadx / decompiler.com) has the plugin's files under `resources/`:
a text AndroidManifest.xml, `res/` and `assets/`. hex_pack_builder.py works on APK bytes,
so this module gives it the same two things catalog_one() and base_apk_bytes() give it for
an APK: the plugin metadata, and a zip whose entries are the plugin's `assets/...` and
`res/...` paths. Only readable files are copied; encrypted `assets/anaconda/` templates are
left out, as they are for APKs.

No androguard needed: the manifest is plain XML and `@string/...` references resolve from
`res/values/strings.xml`.
"""
from __future__ import annotations

import io
import re
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

ANDROID_NS = "{http://schemas.android.com/apk/res/android}"


def is_decompiled_plugin(path: Path) -> bool:
    return path.is_dir() and (path / "resources" / "AndroidManifest.xml").is_file()


def _strings(res: Path) -> dict[str, str]:
    out: dict[str, str] = {}
    f = res / "values" / "strings.xml"
    if f.is_file():
        for el in ET.parse(f).getroot().iter("string"):
            name = el.get("name")
            if name:
                out[name] = "".join(el.itertext())
    return out


def _resolve(value: str | None, strings: dict[str, str]) -> str | None:
    if value and value.startswith("@string/"):
        return strings.get(value[len("@string/"):], value)
    return value


def _manifest_meta(root: Path) -> tuple[ET.Element, dict[str, str]]:
    manifest = ET.parse(root / "resources" / "AndroidManifest.xml").getroot()
    meta = {}
    for el in manifest.iter("meta-data"):
        name = el.get(ANDROID_NS + "name")
        if name and name.startswith("HEX_"):
            meta[name] = el.get(ANDROID_NS + "value") or el.get(ANDROID_NS + "resource") or ""
    return manifest, meta


def plugin_zip_bytes(root: Path) -> bytes:
    """A zip of the plugin's readable `assets/` and `res/` files, as they sit in its APK."""
    res_root = root / "resources"
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_STORED) as zf:
        for sub in ("assets", "res"):
            base = res_root / sub
            if not base.is_dir():
                continue
            for f in sorted(base.rglob("*")):
                rel = f.relative_to(res_root).as_posix()
                if f.is_file() and not rel.startswith("assets/anaconda/"):
                    zf.write(f, rel)
    return buf.getvalue()


def catalog_decompiled(root: Path) -> dict:
    """The fields of hex_plugin_catalog.catalog_one() for a decompiled plugin folder."""
    res = root / "resources" / "res"
    strings = _strings(res)
    manifest, meta = _manifest_meta(root)
    app = manifest.find("application")
    label = _resolve(app.get(ANDROID_NS + "label") if app is not None else None, strings)
    perms = {el.get(ANDROID_NS + "name") for el in manifest.iter("uses-permission")}
    assets = root / "resources" / "assets"
    encrypted = len(list((assets / "anaconda").glob("*"))) if (assets / "anaconda").is_dir() else 0
    categories: dict[str, set[str]] = {}
    tints = [p for p in assets.glob("*_tints")] if assets.is_dir() else []
    asset_bytes = sum(f.stat().st_size for f in assets.rglob("*") if f.is_file()) if assets.is_dir() else 0
    for t in tints:
        for f in t.rglob("*"):
            if f.is_file():
                categories.setdefault(f.parent.name, set()).add(
                    re.sub(r"\.(9\.)?png$|\.xml$", "", f.name))
    features = sorted(k[len("HEX_COMMON_"):].lower() for k, v in meta.items()
                      if k.startswith("HEX_COMMON_") and v == "true")
    return {
        "file": root.name,
        "package": manifest.get("package"),
        "label": label,
        "name": _resolve(meta.get("HEX_PLUGIN_NAME"), strings),
        "author": _resolve(meta.get("HEX_PLUGIN_AUTHOR"), strings),
        "version": manifest.get(ANDROID_NS + "versionName"),
        "signer": None,
        "license_checked": "com.android.vending.CHECK_LICENSE" in perms,
        "day_night": meta.get("HEX_DAY_NIGHT_PLUGIN") == "true",
        "features": features,
        "encrypted_templates": encrypted,
        "asset_megabytes": round(asset_bytes / 1_048_576, 1),
        "categories": {c: {"label": c, "resources": sorted(r)} for c, r in sorted(categories.items())},
    }
