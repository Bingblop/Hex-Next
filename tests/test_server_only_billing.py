from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]


class ServerOnlyBillingTest(unittest.TestCase):
    def test_play_has_no_local_signature_debug_or_indefinite_token_grant(self):
        path = ROOT / "android/app/src/play/java/com/hexnext/themer/billing/PlayEntitlements.kt"
        source = path.read_text()
        for forbidden in ("PurchaseSignature.verify(", "purchase.originalJson", "purchase.signature",
                          "verifiedTokens", "granting the purchase unverified", "private val debuggable"):
            self.assertNotIn(forbidden, source)
        self.assertIn("ServerPurchasePolicy.allowedProducts(", source)
        self.assertIn("context.applicationContext", source)

    def test_owner_grants_remain_separate_from_play(self):
        play = ROOT / "android/app/src/play"
        self.assertFalse(list(play.rglob("OwnerEntitlements.kt")))
        self.assertTrue((ROOT / "android/app/src/owner/java/com/hexnext/themer/billing/OwnerEntitlements.kt").is_file())


if __name__ == "__main__":
    unittest.main()
