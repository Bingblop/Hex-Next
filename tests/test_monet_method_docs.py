"""Keep palette-only and full-theme removal responsibilities distinct in the public API docs."""

from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "android/app/src/main/java/com/hexnext/themer/ui/MainViewModel.kt"


class MonetMethodDocumentationTest(unittest.TestCase):
    def test_palette_and_removal_docs_match_their_methods(self):
        source = SOURCE.read_text(encoding="utf-8")
        self.assertIn(
            "/** Updates the Monet secure setting through shell access without creating app overlays. */\n"
            "    fun applyMonetPalette()",
            source,
        )
        self.assertIn(
            "/** Removes the compiled theme and gives the targets back to Samsung's SemWT overlays. */\n"
            "    fun removeTheme()",
            source,
        )


if __name__ == "__main__":
    unittest.main()
