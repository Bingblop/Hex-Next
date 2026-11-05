"""Source checks for the six Kotlin compiler warnings fixed in the app module."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "android/app/src/main/java/com/hexnext/themer"


def source(relative):
    return (APP / relative).read_text(encoding="utf-8")


def function_body(text, signature):
    start = text.index(signature)
    open_brace = text.index("{", start)
    depth = 0
    for index in range(open_brace, len(text)):
        if text[index] == "{":
            depth += 1
        elif text[index] == "}":
            depth -= 1
            if depth == 0:
                return text[open_brace:index + 1]
    raise AssertionError(f"unterminated body for {signature}")


class KotlinWarningRegressionTest(unittest.TestCase):
    def test_no_optstring_with_null_fallback(self):
        # Android's optString(name, fallback) is declared with a non-null fallback and turns JSON
        # null into the string "null"; read the raw value with opt() and check its type instead.
        pattern = re.compile(r"optString\([^()]*,\s*null\s*\)")
        offenders = [
            path.relative_to(ROOT).as_posix()
            for path in (ROOT / "android/app/src").rglob("*.kt")
            if pattern.search(path.read_text(encoding="utf-8"))
        ]
        self.assertEqual([], offenders)

    def test_pack_label_migration_only_rewrites_string_labels(self):
        text = source("theme/PackStore.kt")
        self.assertIn(
            "internal fun migratedLabel(label: Any?): String? = (label as? String)?.let { replaceFormerNames(it) }",
            text,
        )
        body = function_body(text, "internal fun migratedPackJson(")
        self.assertIn('migratedLabel(option.opt("label"))?.let { option.put("label", it) }', body)

    def test_settings_import_reads_enum_names_as_json_strings(self):
        text = source("ui/settings/AppSettingsStore.kt")
        self.assertIn("internal fun jsonString(value: Any?): String? = value as? String", text)
        body = function_body(text, "fun importJson(json: String)")
        for key, field in (("KEY_THEME_MODE", "themeMode"), ("KEY_PALETTE", "palette"),
                           ("KEY_CONTRAST", "contrast")):
            with self.subTest(key=key):
                self.assertIn(f"{field} = enumOrDefault(jsonString(o.opt({key})), s.{field}),", body)

    def test_device_scanner_passes_the_known_output_directory(self):
        text = source("device/DeviceScanner.kt")
        self.assertIn('val out = File(outDir, "resources.arsc")', text)
        self.assertIn("runCatching { writeValues(out, outDir) }", text)
        self.assertNotIn("writeValues(out, out.parentFile)", text)

    def test_preview_keyboard_uses_the_auto_mirrored_list_icon(self):
        text = source("ui/preview/PreviewKeyboard.kt")
        self.assertIn("import androidx.compose.material.icons.automirrored.filled.List", text)
        self.assertIn("Icons.AutoMirrored.Filled.List", text)
        self.assertNotIn("import androidx.compose.material.icons.filled.List\n", text)
        self.assertNotIn("Icons.Filled.List", text)

    def test_fixes_use_no_suppressions_or_non_null_assertions(self):
        for relative in ("device/DeviceScanner.kt", "theme/PackStore.kt",
                         "ui/settings/AppSettingsStore.kt", "ui/preview/PreviewKeyboard.kt"):
            text = source(relative)
            with self.subTest(file=relative):
                self.assertNotRegex(text, r"@(?:file:)?Suppress\(\s*\"DEPRECATION\"")
        for relative, needle in (("theme/PackStore.kt", "migratedLabel"),
                                 ("ui/settings/AppSettingsStore.kt", "jsonString")):
            for line in source(relative).splitlines():
                if needle in line:
                    with self.subTest(file=relative, line=line.strip()):
                        self.assertNotIn("!!", line)


if __name__ == "__main__":
    unittest.main()
