import base64
import importlib.util
import io
import json
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

TOOLS = Path(__file__).resolve().parents[1] / "tools"
sys.path.insert(0, str(TOOLS))
spec = importlib.util.spec_from_file_location("decompiled_plugin", TOOLS / "decompiled_plugin.py")
dp = importlib.util.module_from_spec(spec)
spec.loader.exec_module(dp)

MANIFEST = """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    android:versionName="2.0" package="project.vivid.ui.plugin.sample">
  <uses-permission android:name="com.android.vending.CHECK_LICENSE"/>
  <application android:label="@string/app_name">
    <meta-data android:name="HEX_PLUGIN_NAME" android:value="@string/plugin_name"/>
    <meta-data android:name="HEX_PLUGIN_AUTHOR" android:value="Someone"/>
    <meta-data android:name="HEX_DAY_NIGHT_PLUGIN" android:value="true"/>
    <meta-data android:name="HEX_COMMON_QS" android:value="true"/>
    <meta-data android:name="HEX_COMMON_NAVBAR" android:value="false"/>
  </application>
</manifest>
"""


def make_plugin(root: Path) -> Path:
    res = root / "resources"
    (res / "res" / "values").mkdir(parents=True)
    (res / "res" / "values" / "strings.xml").write_text(
        '<resources><string name="app_name">Sample App</string>'
        '<string name="plugin_name">Sample</string></resources>')
    (res / "AndroidManifest.xml").write_text(MANIFEST)
    layer = res / "assets" / "anaconda_tints" / "once" / "first" / "xx1" / "settingsicon"
    layer.mkdir(parents=True)
    (layer / "sec_ic_settings_wifi.png").write_bytes(b"\x89PNG")
    (res / "assets" / "default.hex").write_text(base64.b64encode(json.dumps({"xx1": "#112233"}).encode()).decode())
    (res / "assets" / "anaconda").mkdir()
    (res / "assets" / "anaconda" / "anaconda_1").write_bytes(b"encrypted")
    (res / "res" / "drawable-xxhdpi").mkdir()
    (res / "res" / "drawable-xxhdpi" / "card.png").write_bytes(b"\x89PNG")
    return root


class DecompiledPluginTest(unittest.TestCase):
    def test_metadata_matches_the_apk_catalogue_fields(self):
        with tempfile.TemporaryDirectory() as d:
            root = make_plugin(Path(d) / "sample")
            self.assertTrue(dp.is_decompiled_plugin(root))
            meta = dp.catalog_decompiled(root)
            self.assertEqual(meta["package"], "project.vivid.ui.plugin.sample")
            self.assertEqual(meta["name"], "Sample")
            self.assertEqual(meta["label"], "Sample App")
            self.assertEqual(meta["version"], "2.0")
            self.assertTrue(meta["day_night"])
            self.assertTrue(meta["license_checked"])
            self.assertEqual(meta["features"], ["qs"])
            self.assertEqual(meta["encrypted_templates"], 1)
            self.assertEqual(meta["categories"]["settingsicon"]["resources"], ["sec_ic_settings_wifi"])

    def test_zip_has_apk_paths_and_never_the_encrypted_templates(self):
        with tempfile.TemporaryDirectory() as d:
            root = make_plugin(Path(d) / "sample")
            names = zipfile.ZipFile(io.BytesIO(dp.plugin_zip_bytes(root))).namelist()
            self.assertIn("assets/anaconda_tints/once/first/xx1/settingsicon/sec_ic_settings_wifi.png", names)
            self.assertIn("assets/default.hex", names)
            self.assertIn("res/drawable-xxhdpi/card.png", names)
            self.assertFalse([n for n in names if n.startswith("assets/anaconda/")])

    def test_a_folder_without_a_manifest_is_not_a_plugin(self):
        with tempfile.TemporaryDirectory() as d:
            (Path(d) / "resources").mkdir()
            self.assertFalse(dp.is_decompiled_plugin(Path(d)))


if __name__ == "__main__":
    unittest.main()
