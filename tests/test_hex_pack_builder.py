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
