"""Settings resource boundaries, without requiring an Android device."""
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

APP = Path(__file__).resolve().parents[1] / "android/app/src/main"
SCREEN = APP / "java/com/hexnext/themer/ui/settings/AppSettingsScreen.kt"
RESOURCES = APP / "res/values/strings_settings.xml"


class SettingsResourcesTest(unittest.TestCase):
    def test_all_settings_resources_are_unique_and_referenced(self):
        root = ET.parse(RESOURCES).getroot()
        names = [entry.attrib["name"] for entry in root]
        self.assertEqual(len(names), len(set(names)))
        self.assertTrue(all(name.startswith("settings_") for name in names))
        types = {"string": "string", "plurals": "plurals", "string-array": "array"}
        declared = {(types[entry.tag], entry.attrib["name"]) for entry in root}
        referenced = set(re.findall(r"R\.(string|plurals|array)\.(settings_\w+)", SCREEN.read_text()))
        self.assertEqual(declared, referenced)
        for values in (APP / "res/values").glob("*.xml"):
            if values != RESOURCES:
                other_names = {entry.get("name") for entry in ET.parse(values).getroot()}
                self.assertFalse(set(names) & other_names, values.name)

    def test_only_technical_string_literals_remain_in_screen(self):
        source = re.sub(r"/\*.*?\*/", "", SCREEN.read_text(), flags=re.DOTALL)
        source = re.sub(r"^\s*//.*$", "", source, flags=re.MULTILINE)
        self.assertEqual(
            re.findall(r'"([^"\n]*)"', source),
            [
                "https://github.com/Bingblop/Hex-Next",
                "https://bingblop.github.io/hexnext-site/privacy-policy.html",
                "https://",
            ],
        )
        self.assertNotRegex(source, r"\b(?:mode|level|PaletteSource\.\w+)\.label\b")

    def test_dynamic_text_has_positional_placeholders(self):
        root = ET.parse(RESOURCES).getroot()
        self.assertEqual(
            root.find("string[@name='settings_version']").text,
            "Version %1$s (%2$d)",
        )
        packs = root.find("plurals[@name='settings_purchases_packs_unlocked']")
        self.assertEqual(
            {item.attrib["quantity"]: item.text for item in packs},
            {"one": "%1$d pack unlocked.", "other": "%1$d packs unlocked."},
        )
        self.assertIn(
            "stringResource(R.string.settings_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)",
            SCREEN.read_text(),
        )
        self.assertEqual(
            SCREEN.read_text().count(
                "pluralStringResource(R.plurals.settings_purchases_packs_unlocked, packCount, packCount)"
            ),
            2,
        )

    def test_resource_text_and_accessibility_boundaries(self):
        root = ET.parse(RESOURCES).getroot()
        for entry in root.iter():
            if entry.text:
                self.assertNotRegex(entry.text, r"(?<!\\)'", entry.get("name"))
        licences = root.find("string-array[@name='settings_third_party_libraries']")
        self.assertEqual(len(licences), 9)
        self.assertEqual(licences[0].text, "Hex Next · MIT License")
        source = SCREEN.read_text()
        self.assertIn("contentDescription = stringResource(R.string.settings_back)", source)
        self.assertIn(
            "Icons.AutoMirrored.Filled.KeyboardArrowRight,\n            contentDescription = null,",
            source,
        )


if __name__ == "__main__":
    unittest.main()
