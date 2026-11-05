import contextlib
import importlib.util
import io
import json
import os
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

TOOLS = Path(__file__).resolve().parents[1] / "tools"

spec = importlib.util.spec_from_file_location("rename_packs", TOOLS / "rename_packs.py")
rename_packs = importlib.util.module_from_spec(spec)
spec.loader.exec_module(rename_packs)


def write_pack(path: Path, pack_id: str, name: str) -> None:
    pack = {"id": pack_id, "name": name, "author": "a", "version": "1",
            "categories": ["settings"], "dayNight": False, "previewCount": 0,
            "sourcePackage": "example.source.package"}
    with zipfile.ZipFile(path, "w") as z:
        z.writestr("pack.json", json.dumps(pack))
        z.writestr("drawables/a.png", b"x")


def run_main(in_dir: Path, out_dir: Path) -> tuple[int, str]:
    err = io.StringIO()
    with patch.object(sys, "argv", ["rename_packs.py", str(in_dir), str(out_dir)]), \
            contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(err):
        rc = rename_packs.main()
    return rc, err.getvalue()


def first_mapping() -> tuple[str, dict]:
    return next((k, v) for k, v in rename_packs.NAMES.items() if isinstance(v, dict))


class RenamePacksTest(unittest.TestCase):
    def test_unmapped_source_pack_is_refused_and_writes_nothing(self):
        with tempfile.TemporaryDirectory() as tmp:
            src, out = Path(tmp, "in"), Path(tmp, "out")
            src.mkdir()
            write_pack(src / "some-plugin-1-2.zip", "some-plugin-1-2", "Some Plugin")
            rc, err = run_main(src, out)
            self.assertEqual(rc, 1)
            self.assertIn("some-plugin-1-2", err)
            self.assertFalse(out.exists())

    def test_one_unmapped_pack_blocks_the_whole_run(self):
        source_id, _ = first_mapping()
        with tempfile.TemporaryDirectory() as tmp:
            src, out = Path(tmp, "in"), Path(tmp, "out")
            src.mkdir()
            write_pack(src / "a.zip", source_id, "Original")
            write_pack(src / "b.zip", "some-plugin-1-2", "Some Plugin")
            rc, _ = run_main(src, out)
            self.assertEqual(rc, 1)
            self.assertFalse(out.exists())

    def test_former_catalogue_id_is_renamed(self):
        # A pack built under an earlier catalogue name (the old packs branch) gets its current one;
        # option text that named the former pack names the current one.
        with tempfile.TemporaryDirectory() as tmp:
            src, out = Path(tmp, "in"), Path(tmp, "out")
            src.mkdir()
            pack = {"id": "velvet-rope", "name": "Velvet Rope", "author": "Charlie_Dii_", "version": "3.8",
                    "package": "com.hexnext.pack.velvet_rope", "categories": {"navbar": "Navigation bar"},
                    "dayNight": False, "previewCount": 0,
                    "options": [{"type": "switch", "key": "x", "label": "Use Velvet Rope Encore Navbar"}]}
            with zipfile.ZipFile(src / "velvet-rope.zip", "w") as z:
                z.writestr("pack.json", json.dumps(pack))
            rc, _ = run_main(src, out)
            self.assertEqual(rc, 0)
            self.assertEqual(sorted(p.name for p in out.iterdir()), ["index.json", "iolite.zip"])
            with zipfile.ZipFile(out / "iolite.zip") as z:
                pack = json.loads(z.read("pack.json"))
        self.assertEqual((pack["id"], pack["name"], pack["version"], pack["author"]), ("iolite", "Iolite", "1.0", ""))
        self.assertEqual(pack["package"], "com.hexnext.pack.iolite")
        self.assertEqual(pack["options"][0]["label"], "Use Charoite Navbar")

    def test_author_and_version_never_come_from_the_source(self):
        source_id, entry = first_mapping()
        with tempfile.TemporaryDirectory() as tmp:
            src, out = Path(tmp, "in"), Path(tmp, "out")
            src.mkdir()
            pack = {"id": source_id, "name": "Original", "author": "Original Author", "version": "Super_9",
                    "categories": ["settings"], "dayNight": False, "previewCount": 0}
            with zipfile.ZipFile(src / "x.zip", "w") as z:
                z.writestr("pack.json", json.dumps(pack))
            rc, _ = run_main(src, out)
            self.assertEqual(rc, 0)
            with zipfile.ZipFile(out / f"{entry['id']}.zip") as z:
                pack = json.loads(z.read("pack.json"))
            index = json.loads((out / "index.json").read_text())["packs"]
        self.assertEqual((pack["author"], pack["version"]), ("", entry["version"]))
        self.assertEqual([(p["author"], p["version"]) for p in index], [("", entry["version"])])
        self.assertNotIn("Original", json.dumps(index))

    def test_scrub_removes_authors_brand_and_former_names(self):
        options = rename_packs.scrub_options([
            {"type": "switch", "key": "a", "label": "Icons by Timmah"},
            {"type": "switch", "key": "b", "label": "Gradient Background (hex app style)"},
            {"type": "switch", "key": "c", "label": "Disable Plugin Custom Clock Font"},
            {"type": "switch", "key": "d", "label": "Fox Hunt and Owl Hours icons"},
            {"type": "switch", "key": "e", "label": "Use project.vivid.ui.plugin.spark assets via #hex_pro"},
            {"type": "switch", "key": "qs", "label": "0style,1quantum,5spark"},
            {"type": "select", "keys": ["x", "y", "z"], "label": "Style", "choices": [" Madara", " "]},
        ], "zircon")
        labels = [o["label"] for o in options]
        self.assertEqual(labels[:6], ["Icons", "Gradient Background", "Disable Pack Custom Clock Font",
                                      "Xenotime and Zircon icons", "Use assets", "Quick panel options"])
        self.assertEqual(options[6]["choices"], [" Scolecite", "Option 2", "Option 3"])
        self.assertEqual(options[6]["keys"], ["x", "y", "z"])
        self.assertEqual([o.get("key") for o in options[:6]], ["a", "b", "c", "d", "e", "qs"])

    def test_category_labels_are_scrubbed_and_ids_kept(self):
        cats = rename_packs.scrub_categories({"navbar": "Navigation bar", "timmah": "timmah",
                                              "x": "Spark extras"}, "ekanite")
        self.assertEqual(cats, {"navbar": "Navigation bar", "timmah": "Extra layers", "x": "Ekanite extras"})

    def test_settings_prefixes_are_neutral_and_keep_their_order(self):
        settings = {"zeta:a": "true", "asylum:b": "true", "drasticko:a": "false", "hex_pro_mix": "false", "gsb": "true"}
        self.assertEqual(rename_packs.neutral_settings(settings),
                         {"p03:a": "true", "p01:b": "true", "p02:a": "false", "gsb": "true"})

    def test_mapped_pack_is_renamed(self):
        source_id, entry = first_mapping()
        with tempfile.TemporaryDirectory() as tmp:
            src, out = Path(tmp, "in"), Path(tmp, "out")
            src.mkdir()
            write_pack(src / "x.zip", source_id, "Original")
            rc, _ = run_main(src, out)
            self.assertEqual(rc, 0)
            with zipfile.ZipFile(out / f"{entry['id']}.zip") as z:
                pack = json.loads(z.read("pack.json"))
            self.assertEqual((pack["id"], pack["name"]), (entry["id"], entry["name"]))
            self.assertEqual(pack["package"], "com.hexnext.pack." + entry["id"])
            self.assertNotIn("sourcePackage", pack)
            index = json.loads((out / "index.json").read_text())
            self.assertEqual([p["name"] for p in index["packs"]], [entry["name"]])

    def test_null_author_is_repaired(self):
        source_id, entry = first_mapping()
        with tempfile.TemporaryDirectory() as tmp:
            src, out = Path(tmp, "in"), Path(tmp, "out")
            src.mkdir()
            pack = {"id": source_id, "name": "Original", "author": None, "version": "1",
                    "categories": ["settings"], "dayNight": False, "previewCount": 0}
            with zipfile.ZipFile(src / "x.zip", "w") as z:
                z.writestr("pack.json", json.dumps(pack))
            rc, _ = run_main(src, out)
            self.assertEqual(rc, 0)
            with zipfile.ZipFile(out / f"{entry['id']}.zip") as z:
                self.assertEqual(json.loads(z.read("pack.json"))["author"], "")
            index = json.loads((out / "index.json").read_text())
            self.assertEqual([p["author"] for p in index["packs"]], [""])

    def test_option_text_source_names_are_replaced(self):
        with tempfile.TemporaryDirectory() as tmp:
            src, out = Path(tmp, "in"), Path(tmp, "out")
            src.mkdir()
            pack = {"id": "peridot", "name": "Peridot", "author": "a", "version": "1",
                    "categories": ["settings"], "dayNight": False, "previewCount": 0,
                    "options": [
                        {"type": "select", "keys": ["natone", "alticon"], "label": "Clock Widget Deliun",
                         "choices": [" Tint", " Deliun App Icons"]},
                        {"type": "switch", "key": "jk", "label": "Use jajanken_ Navbar Icons"},
                        {"type": "switch", "key": "x", "label": "Use AOSP Navbar Icons"}]}
            with zipfile.ZipFile(src / "peridot.zip", "w") as z:
                z.writestr("pack.json", json.dumps(pack))
            rc, _ = run_main(src, out)
            self.assertEqual(rc, 0)
            with zipfile.ZipFile(out / "peridot.zip") as z:
                options = json.loads(z.read("pack.json"))["options"]
        self.assertEqual(options[0]["label"], "Clock Widget Epidote")
        self.assertEqual(options[0]["choices"], [" Tint", " Epidote App Icons"])
        self.assertEqual(options[0]["keys"], ["natone", "alticon"])
        self.assertEqual(options[1]["label"], "Use Lepidolite Navbar Icons")
        self.assertEqual(options[2]["label"], "Use Android Navbar Icons")

    def test_own_source_name_becomes_the_pack_name(self):
        options = rename_packs.scrub_options(
            [{"type": "switch", "key": "b", "label": "Use Original Blend Keyboard Style"}],
            "olivine")
        self.assertEqual(options[0]["label"], "Use Original Olivine Keyboard Style")

    def test_catalogue_pack_passes_through(self):
        _, entry = first_mapping()
        with tempfile.TemporaryDirectory() as tmp:
            src, out = Path(tmp, "in"), Path(tmp, "out")
            src.mkdir()
            write_pack(src / "y.zip", entry["id"], entry["name"])
            rc, _ = run_main(src, out)
            self.assertEqual(rc, 0)
            self.assertTrue((out / f"{entry['id']}.zip").exists())

    def test_in_place_pass_keeps_large_catalogue_pack(self):
        # <in-dir> == <out-dir>: a catalogued pack's output path is its own input file.
        _, entry = first_mapping()
        payload = os.urandom(4 * 1024 * 1024)
        with tempfile.TemporaryDirectory() as tmp:
            packs = Path(tmp, "packs")
            packs.mkdir()
            pack = {"id": entry["id"], "name": entry["name"], "author": "a", "version": "1",
                    "categories": ["settings"], "dayNight": False, "previewCount": 0}
            with zipfile.ZipFile(packs / f"{entry['id']}.zip", "w") as z:
                z.writestr("pack.json", json.dumps(pack))
                z.writestr("drawables/big.png", payload)
            rc, _ = run_main(packs, packs)
            self.assertEqual(rc, 0)
            with zipfile.ZipFile(packs / f"{entry['id']}.zip") as z:
                self.assertEqual(z.read("drawables/big.png"), payload)
            self.assertEqual(sorted(p.name for p in packs.iterdir()),
                             sorted([f"{entry['id']}.zip", "index.json"]))

    def test_in_place_pass_removes_renamed_source_zip(self):
        # <in-dir> == <out-dir> with a mapped source pack: no zip may keep the source identity,
        # and a second pass must not list the pack twice.
        source_id, entry = first_mapping()
        with tempfile.TemporaryDirectory() as tmp:
            packs = Path(tmp, "packs")
            packs.mkdir()
            write_pack(packs / f"{source_id}.zip", source_id, "Original")
            for _ in range(2):
                rc, _ = run_main(packs, packs)
                self.assertEqual(rc, 0)
                self.assertEqual(sorted(p.name for p in packs.iterdir()),
                                 sorted([f"{entry['id']}.zip", "index.json"]))
                index = json.loads((packs / "index.json").read_text())
                self.assertEqual([p["id"] for p in index["packs"]], [entry["id"]])

    def test_duplicate_ids_are_indexed_once(self):
        # A leftover source zip next to its renamed copy (from an older run) is listed once.
        source_id, entry = first_mapping()
        with tempfile.TemporaryDirectory() as tmp:
            src, out = Path(tmp, "in"), Path(tmp, "out")
            src.mkdir()
            write_pack(src / f"{source_id}.zip", source_id, "Original")
            write_pack(src / f"{entry['id']}.zip", entry["id"], entry["name"])
            rc, _ = run_main(src, out)
            self.assertEqual(rc, 0)
            index = json.loads((out / "index.json").read_text())
            self.assertEqual([p["id"] for p in index["packs"]], [entry["id"]])
            self.assertEqual(len(list(src.iterdir())), 2)

    def test_rename_zip_refuses_unknown_id(self):
        with tempfile.TemporaryDirectory() as tmp:
            write_pack(Path(tmp, "z.zip"), "some-plugin-1-2", "Some Plugin")
            out = Path(tmp, "out")
            out.mkdir()
            with self.assertRaises(ValueError):
                rename_packs.rename_zip(Path(tmp, "z.zip"), out)
            self.assertEqual(list(out.iterdir()), [])


if __name__ == "__main__":
    unittest.main()
