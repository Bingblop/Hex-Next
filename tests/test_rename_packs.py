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
            self.assertNotIn("sourcePackage", pack)
            index = json.loads((out / "index.json").read_text())
            self.assertEqual([p["name"] for p in index["packs"]], [entry["name"]])

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
