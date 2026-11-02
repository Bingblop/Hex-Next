import json
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


class PaidPacksTest(unittest.TestCase):
    def test_paid_pack_ids_match_catalogue_names(self):
        names = json.loads((ROOT / "tools" / "pack_names.json").read_text())
        expected = {v["id"] for k, v in names.items() if not k.startswith("_") and isinstance(v, dict) and "id" in v}
        src = (ROOT / "android/app/src/main/java/com/hexnext/themer/billing/PaidPacks.kt").read_text()
        block = src[src.index("setOf("):src.index(")", src.index("setOf("))]
        found = set(re.findall(r'"([^"]+)"', block))
        self.assertEqual(expected, found, "regenerate PaidPacks.kt from tools/pack_names.json")


if __name__ == "__main__":
    unittest.main()
