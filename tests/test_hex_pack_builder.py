import importlib.util
import sys
import tempfile
import types
import unittest
from pathlib import Path
from unittest.mock import patch

TOOLS = Path(__file__).resolve().parents[1] / "tools"

# hex_plugin_catalog needs androguard; the builder only uses three names from it.
stub = types.ModuleType("hex_plugin_catalog")
stub.CATEGORY_LABELS = {}
stub.catalog_one = lambda path: None
stub.base_apk_bytes = lambda path: b""
sys.modules.setdefault("hex_plugin_catalog", stub)

spec = importlib.util.spec_from_file_location("hex_pack_builder", TOOLS / "hex_pack_builder.py")
builder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(builder)


class UnmappedPluginTest(unittest.TestCase):
    def test_unmapped_plugin_is_refused_and_writes_nothing(self):
        meta = {"name": "Some Original Plugin", "label": "", "version": "9.9"}
        with tempfile.TemporaryDirectory() as out, \
                patch.object(builder, "catalog_one", return_value=meta), \
                patch.object(builder, "base_apk_bytes", return_value=b""):
            with self.assertRaises(ValueError):
                builder.build_pack(Path("plugin.apk"), Path(out))
            self.assertEqual(list(Path(out).iterdir()), [])

    def test_missing_author_is_written_as_empty_string(self):
        # The app decodes author as a non-null String: a JSON null breaks the whole catalogue.
        import io
        import json
        import zipfile
        source_id = next(k for k, v in builder_names().items() if isinstance(v, dict))
        meta = {"name": source_id, "label": "", "version": "", "author": None,
                "day_night": False, "features": []}
        empty = io.BytesIO()
        zipfile.ZipFile(empty, "w").close()
        with tempfile.TemporaryDirectory() as out, \
                patch.object(builder, "catalog_one", return_value=meta), \
                patch.object(builder, "base_apk_bytes", return_value=empty.getvalue()):
            entry = builder.build_pack(Path("plugin.apk"), Path(out))
            with zipfile.ZipFile(Path(out, entry["file"])) as z:
                pack = json.loads(z.read("pack.json"))
        self.assertEqual(pack["author"], "")
        self.assertEqual(entry["author"], "")

    def test_option_text_never_names_a_source_plugin(self):
        # Fever Dream's options named another plugin ("Deliun App Icons"); the app shows them.
        import io
        import json
        import zipfile
        meta = {"name": "Delirium D", "label": "", "version": "2.0", "author": "a",
                "day_night": False, "features": []}
        raw = io.BytesIO()
        with zipfile.ZipFile(raw, "w") as z:
            z.writestr("assets/extra.preferences",
                       "$selectdialogpreference,natone,datone,alticon=Homescreen: Change AppIcon,"
                       " Tint, Deliun App Icons, Delirium Style\n")
        with tempfile.TemporaryDirectory() as out, \
                patch.object(builder, "catalog_one", return_value=meta), \
                patch.object(builder, "base_apk_bytes", return_value=raw.getvalue()):
            entry = builder.build_pack(Path("plugin.apk"), Path(out))
            with zipfile.ZipFile(Path(out, entry["file"])) as z:
                pack = json.loads(z.read("pack.json"))
        self.assertEqual(pack["id"], "fever-dream")
        self.assertEqual(pack["options"][0]["choices"],
                         [" Tint", " Lagoon App Icons", " Fever Dream Style"])
        self.assertNotIn("deliun", json.dumps(pack["options"]).lower())

    def test_failed_rebuild_keeps_the_existing_pack(self):
        # A bad CRC in one entry raises after the output is opened; the published pack stays.
        import io
        import zipfile
        source_id = next(k for k, v in builder_names().items() if isinstance(v, dict))
        pack_id = builder_names()[source_id]["id"]
        meta = {"name": source_id, "label": "", "version": "", "author": "a",
                "day_night": False, "features": []}
        raw = io.BytesIO()
        with zipfile.ZipFile(raw, "w", zipfile.ZIP_STORED) as z:
            z.writestr("assets/info_images/a.png", b"previewdata" * 10)
        data = bytearray(raw.getvalue())
        at = data.index(b"previewdata")
        data[at] ^= 0xFF
        with tempfile.TemporaryDirectory() as out, \
                patch.object(builder, "catalog_one", return_value=meta), \
                patch.object(builder, "base_apk_bytes", return_value=bytes(data)):
            good = Path(out, f"{pack_id}.zip")
            good.write_bytes(b"published pack")
            with self.assertRaises(zipfile.BadZipFile):
                builder.build_pack(Path("plugin.apk"), Path(out))
            self.assertEqual(good.read_bytes(), b"published pack")
            self.assertEqual([p.name for p in Path(out).iterdir()], [good.name])

    def test_failed_rebuild_keeps_its_index_entry(self):
        # main() must not drop a pack from index.json when its rebuild fails and its zip stays.
        import json
        with tempfile.TemporaryDirectory() as src, tempfile.TemporaryDirectory() as out:
            Path(src, "a.apk").write_bytes(b"")
            Path(src, "b.apk").write_bytes(b"")
            Path(out, "riverstone.zip").write_bytes(b"published pack")
            old = {"id": "riverstone", "name": "Riverstone", "file": "riverstone.zip", "bytes": 14}
            gone = {"id": "gone", "name": "Gone", "file": "gone.zip", "bytes": 1}
            Path(out, "index.json").write_text(json.dumps({"format": 1, "packs": [old, gone]}))
            fresh = {"id": "lagoon", "name": "Lagoon", "file": "lagoon.zip", "bytes": 1}

            def fake_build(path, out_dir):
                if path.name == "a.apk":
                    raise ValueError("bad CRC")
                return fresh

            with patch.object(builder, "build_pack", side_effect=fake_build), \
                    patch.object(sys, "argv", ["hex_pack_builder.py", src, out]):
                self.assertEqual(builder.main(), 1)
            packs = json.loads(Path(out, "index.json").read_text())["packs"]
            self.assertEqual(packs, [fresh, old])
            self.assertFalse(Path(out, "index.json.tmp").exists())

    def test_clean_run_writes_only_built_packs(self):
        import json
        with tempfile.TemporaryDirectory() as src, tempfile.TemporaryDirectory() as out:
            Path(src, "b.apk").write_bytes(b"")
            Path(out, "riverstone.zip").write_bytes(b"old")
            old = {"id": "riverstone", "file": "riverstone.zip"}
            Path(out, "index.json").write_text(json.dumps({"format": 1, "packs": [old]}))
            fresh = {"id": "lagoon", "file": "lagoon.zip", "bytes": 1}
            with patch.object(builder, "build_pack", return_value=fresh), \
                    patch.object(sys, "argv", ["hex_pack_builder.py", src, out]):
                self.assertEqual(builder.main(), 0)
            self.assertEqual(json.loads(Path(out, "index.json").read_text())["packs"], [fresh])

    def test_source_names_are_lists_of_names(self):
        for source_id, entry in builder_names().items():
            if isinstance(entry, dict):
                names = entry.get("sourceNames", [])
                self.assertIsInstance(names, list, source_id)
                self.assertTrue(all(isinstance(n, str) and n.strip() for n in names), source_id)

    def test_every_mapping_has_a_catalogue_id_and_name(self):
        for source_id, entry in builder_names().items():
            if isinstance(entry, dict):
                self.assertTrue(entry.get("id") and entry.get("name"), source_id)
                self.assertEqual(builder.catalogue_identity(source_id), (entry["id"], entry["name"]))


def builder_names():
    import json
    return json.loads((TOOLS / "pack_names.json").read_text())


if __name__ == "__main__":
    unittest.main()
