#!/usr/bin/env python3
"""Build a metadata catalog of legacy Hex Installer plugins.

The catalog records what each plugin themes (feature flags, target categories and
the Android resource names it replaces) so Hex Next knows which One UI elements
users expect to customise. It deliberately copies no artwork: the plugins are
third-party, license-checked Play Store apps, and their templates are encrypted.

Usage:
    python3 tools/hex_plugin_catalog.py <plugin-dir> <out-dir>

Requires: pip install androguard colorama loguru
"""
from __future__ import annotations

import io
import json
import re
import sys
import zipfile
from collections import defaultdict
from pathlib import Path

from loguru import logger

logger.remove()
from androguard.core.apk import APK  # noqa: E402

ANDROID_NS = "{http://schemas.android.com/apk/res/android}"

# Hex asset folder name -> One UI element it themes.
CATEGORY_LABELS = {
    "settingsicon": "Settings dashboard icons",
    "myfilesicon": "My Files category icons",
    "misc-myfiles": "My Files misc icons",
    "navbar": "Navigation bar buttons",
    "control": "Quick panel controls",
    "dialer": "Phone app",
    "dialer_night": "Phone app (dark)",
    "dialpad": "Dialpad keys",
    "dialpad-addbackground": "Dialpad key backgrounds",
    "dialpad-trasnpa": "Transparent dialpad",
    "keyboard": "Samsung Keyboard",
    "keyboard_night": "Samsung Keyboard (dark)",
    "home": "One UI Home",
    "badge": "App icon badges",
    "lockscreenHr": "Lock screen",
    "search": "Search",
    "drawable-xxhdpi-v26": "Framework / System UI drawables",
    "drawable-xxhdpi-v29": "Framework / System UI drawables (Android 10+)",
    "drawable-night-xxhdpi-v26": "Framework / System UI drawables (dark)",
}


def base_apk_bytes(path: Path) -> bytes:
    """Return the base APK bytes for a plain .apk or a split .apks bundle."""
    data = path.read_bytes()
    with zipfile.ZipFile(io.BytesIO(data)) as zf:
        names = zf.namelist()
        if "AndroidManifest.xml" in names:
            return data
        for candidate in ("base.apk", "base-master.apk"):
            if candidate in names:
                return zf.read(candidate)
        apks = [n for n in names if n.endswith(".apk")]
        if apks:
            return zf.read(max(apks, key=lambda n: zf.getinfo(n).file_size))
    raise ValueError(f"no base APK in {path.name}")


def resolve(apk: APK, value: str | None) -> str | None:
    if not value or not value.startswith("@"):
        return value
    try:
        res_id = int(value[1:], 16)
        arsc = apk.get_android_resources()
        names = arsc.get_resolved_res_configs(res_id)
        if names:
            return str(names[0][1])
    except Exception:  # noqa: BLE001 - catalog is best-effort metadata
        pass
    return value


def catalog_one(path: Path) -> dict:
    raw = base_apk_bytes(path)
    apk = APK(raw, raw=True)
    manifest = apk.get_android_manifest_xml()
    meta = {}
    for el in manifest.iter("meta-data"):
        name = el.get(ANDROID_NS + "name")
        if name and name.startswith("HEX_"):
            meta[name] = el.get(ANDROID_NS + "value")

    certs = apk.get_certificates()
    signer = certs[0].subject.human_friendly if certs else None

    categories: dict[str, set[str]] = defaultdict(set)
    encrypted_templates = 0
    asset_bytes = 0
    with zipfile.ZipFile(io.BytesIO(raw)) as zf:
        for info in zf.infolist():
            parts = info.filename.split("/")
            if info.filename.startswith("assets/"):
                asset_bytes += info.file_size
            if len(parts) >= 3 and parts[1].startswith("anaconda") and not parts[1].endswith("_tints"):
                encrypted_templates += 1
            if len(parts) >= 6 and parts[1].endswith("_tints") and parts[-1]:
                category = parts[-2]
                res_name = re.sub(r"\.(9\.)?png$|\.xml$", "", parts[-1])
                categories[category].add(res_name)

    features = sorted(k[len("HEX_COMMON_"):].lower() for k, v in meta.items()
                      if k.startswith("HEX_COMMON_") and v == "true")
    return {
        "file": path.name,
        "package": apk.get_package(),
        "label": apk.get_app_name(),
        "name": resolve(apk, meta.get("HEX_PLUGIN_NAME")),
        "author": resolve(apk, meta.get("HEX_PLUGIN_AUTHOR")),
        "version": apk.get_androidversion_name(),
        "signer": signer,
        "license_checked": "com.android.vending.CHECK_LICENSE" in apk.get_permissions(),
        "day_night": meta.get("HEX_DAY_NIGHT_PLUGIN") == "true",
        "features": features,
        "encrypted_templates": encrypted_templates,
        "asset_megabytes": round(asset_bytes / 1_048_576, 1),
        "categories": {
            c: {"label": CATEGORY_LABELS.get(c, c), "resources": sorted(r)}
            for c, r in sorted(categories.items())
        },
    }


def write_markdown(entries: list[dict], union: dict[str, set[str]], out: Path) -> None:
    lines = [
        "# Legacy Hex plugin catalog",
        "",
        "Generated by `tools/hex_plugin_catalog.py`. Metadata only: no plugin artwork is",
        "stored in this repository. Plugins are third-party, license-checked apps whose",
        "templates are encrypted, so Hex Next ships its own original content and uses this",
        "catalog as a map of which One UI elements and resource names to theme.",
        "",
        "| Plugin | Author | Version | Features | Encrypted templates |",
        "|---|---|---|---|---|",
    ]
    for e in entries:
        lines.append(f"| {e['name'] or e['label']} | {e['author'] or '?'} | {e['version']} | "
                     f"{', '.join(e['features']) or '-'} | {e['encrypted_templates']} |")
    lines += ["", "## Resource names by element (union across plugins)", ""]
    for cat, names in sorted(union.items()):
        lines.append(f"### {CATEGORY_LABELS.get(cat, cat)} (`{cat}`, {len(names)})")
        lines.append("")
        lines.append(", ".join(f"`{n}`" for n in sorted(names)))
        lines.append("")
    out.write_text("\n".join(lines) + "\n")


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__)
        return 2
    src, out = Path(sys.argv[1]), Path(sys.argv[2])
    out.mkdir(parents=True, exist_ok=True)
    entries, errors = [], []
    for path in sorted(src.iterdir()):
        if path.suffix.lower() not in (".apk", ".apks"):
            continue
        try:
            entries.append(catalog_one(path))
        except Exception as exc:  # noqa: BLE001
            errors.append({"file": path.name, "error": str(exc)})
    union: dict[str, set[str]] = defaultdict(set)
    for e in entries:
        for cat, info in e["categories"].items():
            union[cat].update(info["resources"])
    (out / "catalog.json").write_text(json.dumps({
        "plugins": entries,
        "errors": errors,
        "resource_union": {c: sorted(n) for c, n in sorted(union.items())},
    }, indent=2) + "\n")
    write_markdown(entries, union, out / "README.md")
    print(f"catalogued {len(entries)} plugins, {len(errors)} errors -> {out}")
    for err in errors:
        print("  error:", err)
    return 0


if __name__ == "__main__":
    sys.exit(main())
