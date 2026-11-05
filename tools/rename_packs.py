#!/usr/bin/env python3
"""Give built packs their catalogue identity (tools/pack_names.json) and scrub their text.

Reads every pack zip in <in-dir> and writes <new-id>.zip into <out-dir> with a rewritten
pack.json, then regenerates index.json there. A pack's id may be a source id (the key a
converter derived from the source plugin), a former catalogue id (formerIds) or its current
catalogue id. The written pack.json:

  * carries the catalogue id, name, version and package com.hexnext.pack.<id>;
  * has a blank author (the app hides it) and never the source plugin's version or package;
  * has every source plugin name, source author, former catalogue name and legacy brand string
    replaced or removed in the text the app shows: option labels and choices and category labels.

Internal keys (option keys, category ids, layer paths and resource names) are left as they are:
the compiler and saved themes (elementOptions) reference them. The source plugin prefixes of the
settings keys are renamed in an order-preserving way (see neutral_settings).

With <in-dir> == <out-dir> an input zip is removed once its renamed copy is written. A zip whose
id is in none of the three forms is refused: nothing is written and the tool exits 1, so a pack
can never ship under its source plugin's id or name. hex_pack_builder.py applies the same
mapping when it converts a plugin.

Usage:
    python3 tools/rename_packs.py <in-dir> <out-dir>
"""
from __future__ import annotations

import json
import os
import re
import sys
import zipfile
from functools import lru_cache
from pathlib import Path

NAMES = json.loads((Path(__file__).resolve().parent / "pack_names.json").read_text())
ENTRIES: dict[str, dict] = {k: v for k, v in NAMES.items() if isinstance(v, dict)}
CATALOGUE_IDS = frozenset(e["id"] for e in ENTRIES.values())
FORMER_IDS: dict[str, dict] = {f: e for e in ENTRIES.values() for f in e.get("formerIds", [])}
DEFAULT_VERSION = "1.0"

# Package parts that say nothing about a plugin, and common English words among the package
# fragments: neither is treated as a legacy name.
_PACKAGE_NOISE = frozenset({"project", "vivid", "ui", "plugin"})
_COMMON_WORDS = frozenset({"free", "glow", "prime", "retina", "spider", "dna", "homely", "hamster",
                           "daynight"})

# Terms that are not one plugin's name but still point at the legacy plugins, and what the
# text says instead.
_GENERIC = {"AOSP": "Android"}

# Labels for the category ids a mis-parsed option list uses as its key.
_CATEGORY_WORDS = {"statusbar": "Status bar", "qs": "Quick panel", "misc": "Miscellaneous",
                   "icons": "Icons", "navbar": "Navigation bar", "home": "Home screen"}


def pack_package(pack_id: str) -> str:
    """The package name a pack ships under: `com.hexnext.pack.<id>` with `-` as `_`."""
    return "com.hexnext.pack." + pack_id.replace("-", "_")


def resolve(pack_id: str) -> dict | None:
    """The pack_names.json entry for a source id, a former catalogue id or a catalogue id."""
    entry = ENTRIES.get(pack_id) or FORMER_IDS.get(pack_id)
    if entry is None:
        entry = next((e for e in ENTRIES.values() if e["id"] == pack_id), None)
    return entry


def catalogue_identity(pack_id: str) -> tuple[str, str] | None:
    """(id, name) the pack with [pack_id] (any accepted form) ships under, or None."""
    entry = resolve(pack_id)
    return (entry["id"], entry["name"]) if entry is not None else None


def catalogue_version(pack_id: str) -> str:
    entry = resolve(pack_id)
    return str(entry.get("version") or DEFAULT_VERSION) if entry is not None else DEFAULT_VERSION


def former_name(former_id: str) -> str:
    """The display name a former catalogue id shipped under ("a-and-b" -> "A & B")."""
    return " ".join("&" if w == "and" else w.capitalize() for w in former_id.split("-"))


def _package_fragments(entry: dict) -> list[str]:
    parts = []
    for part in entry.get("sourcePackage", "").split("."):
        parts += [p for p in part.split("_") if p]
    return [p for p in parts if p.lower() not in _PACKAGE_NOISE | _COMMON_WORDS and len(p) > 2]


def _term_pattern(term: str) -> str:
    """Regex for [term]: words matched case-insensitively, any of space, `_`, `/`, `-` or nothing
    between them, a glued trailing `_` included, never inside a longer word."""
    words = [w for w in re.split(r"[\s_/\-]+", term) if w]
    body = r"[\s_/\-]*".join(r"(?:and|&)" if w in ("&", "and") else re.escape(w) for w in words)
    return r"(?<![A-Za-z0-9])" + body + r"_?(?![A-Za-z0-9])"


def _norm(text: str) -> str:
    return re.sub(r"[\s_/\-&]+", " ", text.lower().replace(" and ", " & ")).strip(" _")


def legacy_terms() -> dict[str, list[str]]:
    """Every legacy string by kind: source names (incl. distinctive package fragments), source
    authors, former catalogue names and brand strings. For audits; the scrub uses [_scrubber]."""
    names, authors, former = set(), set(), set()
    for entry in ENTRIES.values():
        names.update(entry.get("sourceNames", []))
        names.update(_package_fragments(entry))
        authors.update(entry.get("sourceAuthors", []))
        former.update(former_name(f) for f in entry.get("formerIds", []))
    return {"sourceNames": sorted(names), "sourceAuthors": sorted(authors),
            "formerNames": sorted(former), "generic": sorted(_GENERIC),
            "brand": ["#hex", "hex_", "hexpro", "hex pro", "project.vivid"]}


@lru_cache(maxsize=None)
def _scrubber(pack_id: str) -> tuple[re.Pattern, dict[str, str], re.Pattern]:
    """The name alternation (longest first) with its replacement by normalised text, and the
    author alternation.

    A source name the pack's own entry lists becomes the pack's own catalogue name; any other
    becomes the catalogue name of the first entry that lists it. A former catalogue name becomes
    the current name of its pack. Authors are removed, with a "by" in front of them.
    """
    own = resolve(pack_id)
    replacement: dict[str, str] = {}
    terms: dict[str, str] = {}
    authors: set[str] = set()

    def add(term: str, new: str, override: bool = False) -> None:
        key = _norm(term)
        if key and (override or key not in replacement):
            replacement[key] = new
            terms[key] = term

    for entry in ENTRIES.values():
        for former in entry.get("formerIds", []):
            add(former_name(former), entry["name"])
        for name in entry.get("sourceNames", []) + _package_fragments(entry):
            add(name, entry["name"])
        authors.update(entry.get("sourceAuthors", []))
    if own is not None:
        for name in own.get("sourceNames", []) + _package_fragments(own):
            add(name, own["name"], override=True)
    # Several source plugins carry "AOSP" in their name; the stock look is called Android here.
    for term, new in _GENERIC.items():
        add(term, new)
    ordered = sorted(terms.values(), key=lambda t: -len(t))
    names = re.compile("|".join(f"(?:{_term_pattern(t)})" for t in ordered), re.I)
    by_author = re.compile(r"(?:\bby\s+)?(?:" + "|".join(
        f"(?:{_term_pattern(a)})" for a in sorted(authors, key=lambda t: -len(t))) + ")", re.I)
    return names, replacement, by_author


# The legacy app's brand: "#hex…", "hex_…", "hex pro", the bare word "hex" (its storage folder and
# app name; "Hexagon" is a shape and stays) and its project.vivid packages. A parenthesis that
# names it (an instruction for the legacy app) goes with it.
_BRAND_TERM = r"(?:#hex\w*|(?<![A-Za-z0-9])hex_\w*|\bhex\s?pro\b|(?<![A-Za-z0-9])/?hex\b|\bproject\.vivid[\w.]*)"
_BRAND = re.compile(r"\([^()]*" + _BRAND_TERM + r"[^()]*\)|(?:\b(?:via|with|from|using|in|on|by)\s+)?"
                    + _BRAND_TERM, re.I)
_PLUGIN = re.compile(r"\b(plugin)(s?)\b", re.I)


def _plugin_to_pack(m: re.Match) -> str:
    word = m.group(1)
    new = "Pack" if word[0].isupper() else "pack"
    return (new.upper() if word.isupper() else new) + m.group(2)


def scrub_text(text: str, pack_id: str) -> str:
    """[text] with every legacy term replaced (source names, former names) or removed (authors,
    brand strings) and "plugin" said as "pack". Leading and trailing whitespace is kept."""
    names, replacement, by_author = _scrubber(pack_id)
    new = by_author.sub("", text)
    new = names.sub(lambda m: replacement.get(_norm(m.group(0)), ""), new)
    new = _BRAND.sub("", new)
    new = _PLUGIN.sub(_plugin_to_pack, new)
    if new == text:
        return text
    lead = re.match(r"\s*", text).group(0)
    trail = text[len(text.rstrip()):]
    body = re.sub(r"\(\s*\)|\[\s*\]", "", new.strip())
    body = re.sub(r"\s+([,.;:)\]])", r"\1", body)
    body = re.sub(r"([(\[])\s+", r"\1", body)
    body = re.sub(r"^[\s,;:&+\-]+|[\s,;:&+\-]+$", "", body)
    body = re.sub(r"\s{2,}", " ", body).strip()
    return (lead + body + trail) if body else ""


def _humanise(key: str) -> str:
    return _CATEGORY_WORDS.get(key) or (re.sub(r"[_\-]+", " ", key).strip().capitalize() or "Option")


_KEY_LIST = re.compile(r"^\s*[A-Za-z0-9_]+(?:\s*,\s*[A-Za-z0-9_]+)+\s*$")


def scrub_options(options: list, pack_id: str) -> list:
    """Options with legacy terms scrubbed from label and choices (the app shows both).

    A switch whose label is a bare list of option keys (a category line the converter read as a
    switch) is labelled after its key instead. Every select key gets a non-blank choice, because
    the app shows the internal key when the choice is missing or blank.
    """
    scrubbed = []
    for option in options:
        option = dict(option)
        label = option.get("label")
        if isinstance(label, str):
            if _KEY_LIST.match(label):
                label = _humanise(str(option.get("key") or "")) + " options"
            label = scrub_text(label, pack_id)
            if not label.strip():
                label = _humanise(str(option.get("key") or "")) if option.get("type") == "switch" else "Style"
            option["label"] = label
        if isinstance(option.get("choices"), list) or option.get("type") == "select":
            choices = [scrub_text(c, pack_id) if isinstance(c, str) else c
                       for c in option.get("choices") or []]
            keys = option.get("keys") if isinstance(option.get("keys"), list) else []
            while len(choices) < len(keys):
                choices.append("")
            choices = [c if not isinstance(c, str) or c.strip() else f"Option {i + 1}"
                       for i, c in enumerate(choices)]
            option["choices"] = choices
        scrubbed.append(option)
    return scrubbed


def scrub_categories(categories: dict, pack_id: str) -> dict:
    """Category id -> label with legacy terms scrubbed from the label; ids stay as they are."""
    out = {}
    for key, label in categories.items():
        if isinstance(label, str):
            # A category without a label of its own is labelled with its raw id, which may be a
            # legacy plugin's or author's name; it gets a neutral label instead.
            label = "Extra layers" if label == key else (scrub_text(label, pack_id).strip() or "Extra layers")
        out[key] = label
    return out


def neutral_settings(settings: dict) -> dict:
    """[settings] with the source plugin prefixes of `<plugin>:<option key>` keys renamed to
    p01, p02 … in the sorted order of the original prefixes, and unprefixed keys that name a legacy
    plugin or brand dropped.

    The engine reads settings only for Pack.defaultOptions, which needs just the option key after
    the colon and ranks prefixes by how many option keys they name, then by prefix: keeping the
    prefixes' sort order keeps every default as it was. Unprefixed keys are never read.
    """
    prefixes = sorted({k.split(":", 1)[0] for k in settings if ":" in k and k.index(":") > 0})
    width = max(2, len(str(len(prefixes))))
    alias = {old: f"p{i + 1:0{width}d}" for i, old in enumerate(prefixes)}
    names, _, by_author = _scrubber("")
    out = {}
    for key, value in settings.items():
        if ":" in key and key.index(":") > 0:
            prefix, rest = key.split(":", 1)
            out[f"{alias[prefix]}:{rest}"] = value
        elif not (names.search(key) or by_author.search(key) or _BRAND.search(key)
                  or re.search(r"hex|vivid", key, re.I)):
            out[key] = value
    return out


def finalize_pack(pack: dict, entry: dict) -> None:
    """Gives a pack.json dict its catalogue identity and removes every trace of the source plugin
    from the text the app shows. Internal keys and layer paths stay."""
    pack.pop("sourcePackage", None)
    pack["id"], pack["name"] = entry["id"], entry["name"]
    pack["version"] = str(entry.get("version") or DEFAULT_VERSION)
    pack["author"] = ""
    pack["package"] = pack_package(entry["id"])
    if isinstance(pack.get("options"), list):
        pack["options"] = scrub_options(pack["options"], entry["id"])
    if isinstance(pack.get("categories"), dict):
        pack["categories"] = scrub_categories(pack["categories"], entry["id"])
    if isinstance(pack.get("settings"), dict):
        pack["settings"] = neutral_settings(pack["settings"])


def strip_source(pack: dict) -> None:
    """[finalize_pack] for a pack.json dict whose id is any accepted form (kept for callers)."""
    entry = resolve(pack["id"])
    if entry is None:
        raise ValueError(f"id {pack['id']!r} is not in pack_names.json")
    finalize_pack(pack, entry)


def _names_legacy(category_id: str) -> bool:
    names, _, by_author = _scrubber("")
    text = category_id.replace("_", " ").replace("-", " ")
    return bool(names.search(text) or by_author.search(text) or _BRAND.search(text)
                or re.search(r"plugin|hex|vivid", category_id, re.I))


def index_entry(pack: dict, file: Path) -> dict:
    """The index.json entry of [pack]. Its category list is only read for the Settings icon
    categories (`settingsicon*`); internal category ids that name a legacy plugin, author or
    brand are left out of it."""
    categories = sorted(c for c in (pack.get("categories") or []) if not _names_legacy(c))
    return {"id": pack["id"], "name": pack["name"], "author": "", "version": pack["version"],
            "file": file.name, "bytes": file.stat().st_size,
            "categories": categories, "dayNight": pack.get("dayNight", False),
            "previewCount": pack.get("previewCount", 0)}


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
    """The pack id of [src] when it is in none of the accepted forms, else None."""
    with zipfile.ZipFile(src) as zin:
        pack_id = json.loads(zin.read("pack.json"))["id"]
    return pack_id if resolve(pack_id) is None else None


def rename_zip(src: Path, out_dir: Path) -> tuple[dict, bool]:
    """Writes the renamed copy of [src]; returns its index entry and whether the id changed."""
    with zipfile.ZipFile(src) as zin:
        pack = json.loads(zin.read("pack.json"))
        entry = resolve(pack["id"])
        if entry is None:
            raise ValueError(f"{src.name}: id {pack['id']!r} is not in pack_names.json; "
                             "add the source plugin to the naming table first")
        renamed = pack["id"] != entry["id"]
        finalize_pack(pack, entry)
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
    return index_entry(pack, dst), renamed


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
            print(f"error: {name}: id {pack_id!r} is neither a source id, a former id nor a "
                  "catalogue id in tools/pack_names.json; add it to the naming table first",
                  file=sys.stderr)
        print(f"refused: {len(unknown)} unmapped pack(s); nothing was written", file=sys.stderr)
        return 1
    out.mkdir(parents=True, exist_ok=True)
    in_place = src.resolve() == out.resolve()
    index: dict[str, dict] = {}
    unchanged = []
    for path in zips:
        entry, renamed = rename_zip(path, out)
        # Two inputs with one catalogue id (a source zip and an earlier rename of it) write the
        # same <id>.zip; list it once.
        index[entry["id"]] = entry
        if not renamed:
            unchanged.append(path.name)
        print(f"  {path.name} -> {entry['file']}  ({entry['name']})")
        # With <in-dir> == <out-dir> the input zip would stay next to its renamed copy and
        # still carry its old id, name and package; remove it.
        if in_place and path.resolve() != (out / entry["file"]).resolve():
            path.unlink()
    packs = sorted(index.values(), key=lambda e: e["name"].lower())
    (out / "index.json").write_text(json.dumps({"format": 1, "packs": packs}, indent=1) + "\n")
    # A pack already under its catalogue id is rewritten (text scrubbed, duplicate entries
    # removed) and reported only.
    print(f"renamed {len(packs) - len(unchanged)} packs; already catalogued: {unchanged or 'none'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
