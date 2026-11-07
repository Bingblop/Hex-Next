"""The catalogue naming table, its generated Kotlin alias table, and the legacy-name audit.

Set HEXNEXT_PACKS_DIR to a folder of generated packs (for example the output of
tools/rename_packs.py) to audit every pack.json and index.json in it as well.
"""
import importlib.util
import json
import os
import re
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TOOLS = ROOT / "tools"
ALIASES_KT = ROOT / "android/engine/src/main/kotlin/com/hexnext/engine/pack/PackIdAliases.kt"

spec = importlib.util.spec_from_file_location("rename_packs", TOOLS / "rename_packs.py")
rename_packs = importlib.util.module_from_spec(spec)
spec.loader.exec_module(rename_packs)

NAMES = json.loads((TOOLS / "pack_names.json").read_text())
ENTRIES = {k: v for k, v in NAMES.items() if isinstance(v, dict)}


def legacy_patterns() -> list[tuple[str, re.Pattern]]:
    """(term, case-insensitive word-boundary pattern) for every legacy string a pack must not show:
    source names, package fragments, source authors, former catalogue names and brand strings."""
    terms = rename_packs.legacy_terms()
    out = []
    for kind in ("sourceNames", "sourceAuthors", "formerNames", "generic"):
        for term in terms[kind]:
            out.append((term, re.compile(rename_packs._term_pattern(term), re.I)))
    out.append(("#hex", re.compile(r"#hex", re.I)))
    out.append(("hex_", re.compile(r"(?<![A-Za-z0-9])hex_", re.I)))
    out.append(("hexpro", re.compile(r"\bhex\s?pro\b", re.I)))
    out.append(("hex", re.compile(r"(?<![A-Za-z0-9])hex\b", re.I)))
    out.append(("project.vivid", re.compile(r"project\.vivid|\bvivid\b", re.I)))
    for entry in ENTRIES.values():
        for part in entry["sourcePackage"].split("."):
            if part not in ("project", "vivid", "ui", "plugin") and len(part) > 2 \
                    and part.lower() not in rename_packs._COMMON_WORDS:
                out.append((part, re.compile(rename_packs._term_pattern(part), re.I)))
    return out


PATTERNS = legacy_patterns()


def leaks(text: str) -> list[str]:
    return [term for term, pattern in PATTERNS if pattern.search(text)]


def visible_texts(pack: dict) -> list[tuple[str, str]]:
    """(field, text) for every pack.json field the app shows."""
    out = [(f, str(pack.get(f, ""))) for f in ("id", "name", "author", "version")]
    for i, option in enumerate(pack.get("options") or []):
        out.append((f"options[{i}].label", str(option.get("label", ""))))
        for j, choice in enumerate(option.get("choices") or []):
            out.append((f"options[{i}].choices[{j}]", str(choice)))
    for key, label in (pack.get("categories") or {}).items():
        out.append((f"categories[{key}]", str(label)))
    return out


def audit_pack(pack: dict) -> list[str]:
    problems = []
    if pack["id"] not in rename_packs.CATALOGUE_IDS:
        problems.append(f"id {pack['id']!r} is not a catalogue id")
    if pack.get("package") != rename_packs.pack_package(pack["id"]):
        problems.append(f"package {pack.get('package')!r}")
    if pack.get("author"):
        problems.append(f"author {pack['author']!r}")
    if "sourcePackage" in pack:
        problems.append("sourcePackage present")
    for field, text in visible_texts(pack):
        for term in leaks(text):
            problems.append(f"{field} {text!r} shows {term!r}")
    for i, option in enumerate(pack.get("options") or []):
        if option.get("type") == "select":
            choices = option.get("choices") or []
            for j, _ in enumerate(option.get("keys") or []):
                if j >= len(choices) or not str(choices[j]).strip():
                    problems.append(f"options[{i}] key {j} has no choice (the app would show the key)")
    return problems


class PackNamesTest(unittest.TestCase):
    def test_entries_are_complete_and_unique(self):
        ids = [e["id"] for e in ENTRIES.values()]
        self.assertEqual(len(ids), 49)
        self.assertEqual(len(set(ids)), len(ids))
        former = [f for e in ENTRIES.values() for f in e["formerIds"]]
        self.assertEqual(len(set(former)), len(former))
        self.assertFalse(set(former) & set(ids), "a former id is also a current id")
        for key, e in ENTRIES.items():
            self.assertRegex(e["id"], r"^[a-z]+$", key)
            self.assertEqual(e["name"], e["id"].capitalize(), key)
            self.assertEqual(e["version"], "1.0", key)
            # Packs added after the catalogue was renamed (2026-11-06) have no former id.
            self.assertIsInstance(e["formerIds"], list, key)

    def test_new_names_share_nothing_with_their_sources(self):
        for key, e in ENTRIES.items():
            for term in leaks(e["name"]):
                self.fail(f"{key}: {e['name']} matches legacy term {term!r}")
            # The table (docs/design/studio-v2-phase2.md appendix B) checks the first syllable
            # against the source plugin's names; authors are scrubbed from every text instead.
            sources = [key] + e.get("sourceNames", []) + e["formerIds"]
            for source in sources:
                self.assertNotEqual(source.lower()[:3], e["id"][:3], f"{key}: {e['id']} vs {source}")

    def test_aliases_kotlin_matches_former_ids(self):
        expected = {f: e["id"] for e in ENTRIES.values() for f in e["formerIds"]}
        src = ALIASES_KT.read_text()
        block = src[src.index("mapOf("):src.index(")\n", src.index("mapOf("))]
        found = dict(re.findall(r'"([^"]+)"\s+to\s+"([^"]+)"', block))
        self.assertEqual(expected, found, "regenerate PackIdAliases.kt from tools/pack_names.json")

    def test_scrub_removes_every_legacy_term_from_option_text(self):
        # Every source name, author and former name, written into a label, is gone afterwards.
        terms = rename_packs.legacy_terms()
        for kind in ("sourceNames", "sourceAuthors", "formerNames", "generic"):
            for term in terms[kind]:
                text = rename_packs.scrub_text(f"Use {term} Navbar Icons", "iolite")
                self.assertEqual(leaks(text), [], f"{kind} {term!r} -> {text!r}")

    def test_generated_packs_have_no_legacy_text(self):
        folder = os.environ.get("HEXNEXT_PACKS_DIR")
        if not folder:
            self.skipTest("HEXNEXT_PACKS_DIR not set")
        folder = Path(folder)
        problems = []
        zips = sorted(folder.glob("*.zip"))
        for path in zips:
            with zipfile.ZipFile(path) as z:
                pack = json.loads(z.read("pack.json"))
            if path.name != f"{pack['id']}.zip":
                problems.append(f"{path.name}: file name does not match id {pack['id']!r}")
            problems += [f"{path.name}: {p}" for p in audit_pack(pack)]
        index = json.loads((folder / "index.json").read_text())
        for entry in index["packs"]:
            if entry["id"] not in rename_packs.CATALOGUE_IDS:
                problems.append(f"index: {entry['id']!r} is not a catalogue id")
            for field in ("id", "name", "author", "version", "file"):
                for term in leaks(str(entry.get(field, ""))):
                    problems.append(f"index {entry['id']}.{field} shows {term!r}")
            if entry.get("author"):
                problems.append(f"index {entry['id']}: author {entry['author']!r}")
            for category in entry.get("categories", []):
                spaced = category.replace("_", " ").replace("-", " ")
                for term in set(leaks(category) + leaks(spaced)):
                    problems.append(f"index {entry['id']}.categories {category!r} shows {term!r}")
                if re.search(r"plugin", category, re.I):
                    problems.append(f"index {entry['id']}.categories {category!r} shows 'plugin'")
        self.assertEqual(len(index["packs"]), len(zips))
        self.assertEqual(problems, [])


if __name__ == "__main__":
    unittest.main()
