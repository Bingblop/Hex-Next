import contextlib
import importlib.util
import io
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location(
    "debug_apk_signer", ROOT / "bin/verify-debug-apk-signer.py",
)
debug_signer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(debug_signer)

# Public fingerprint/report fixtures only; no key generation or keystore access.
PIN = "6e1e934aa4c68afd15c3cd82f02ff30b468ef5e54d9c8db9da707e5473afc809"
OTHER = "cbf46f3243fbc3c0f8741ede3c7c6f646eeea066d053a4f45c6173863ef982b2"
REPORT = f"""Verifies
Verified using v2 scheme (APK Signature Scheme v2): true
Number of signers: 1
Signer #1 certificate DN: C=US, O=Android, CN=Android Debug
Signer #1 certificate SHA-256 digest: {PIN}
Signer #1 public key SHA-256 digest: {OTHER}
"""


class DebugApkSignerTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.apk = Path(directory.name) / "fixture.apk"
        self.apk.write_bytes(b"public fixture; native verifier is mocked")
        self.pin = Path(directory.name) / "debug-pin.sha256"
        self.pin.write_text(f"# Public certificate fingerprint\n{PIN}\n")

    def verify(self, report=REPORT):
        with patch.object(
            debug_signer.subprocess, "run",
            return_value=subprocess.CompletedProcess([], 0, stdout=report),
        ) as run:
            actual = debug_signer.verify(self.apk, self.pin, Path("/sdk/apksigner"))
        return actual, run

    def test_match_uses_native_verifier_and_ignores_public_key_digest(self):
        actual, run = self.verify()
        self.assertEqual(PIN, actual)
        run.assert_called_once_with(
            ["/sdk/apksigner", "verify", "--verbose", "--print-certs", str(self.apk)],
            check=True, text=True, capture_output=True, timeout=60,
        )

    def test_pin_accepts_comments_blank_lines_uppercase_and_colons(self):
        colon_pin = ":".join(PIN[i:i + 2].upper() for i in range(0, len(PIN), 2))
        self.pin.write_text(f"  # public certificate\n\n{colon_pin}\n")
        self.assertEqual(PIN, self.verify()[0])

    def test_digest_format_normalizes_without_custom_crypto(self):
        digest = ":".join(PIN[i:i + 2].upper() for i in range(0, len(PIN), 2))
        self.assertEqual(PIN, debug_signer.parse_signer(REPORT.replace(PIN, digest)))

    def test_mismatching_certificate_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "signer mismatch"):
            self.verify(REPORT.replace(PIN, OTHER))

    def test_malformed_pin_is_rejected_before_native_execution(self):
        for value in ("not a digest", "ab" * 31, "zz" * 32, PIN + OTHER):
            with self.subTest(value=value):
                self.pin.write_text(value)
                with patch.object(debug_signer.subprocess, "run") as run:
                    with self.assertRaises(ValueError):
                        debug_signer.verify(self.apk, self.pin, "/sdk/apksigner")
                    run.assert_not_called()

    def test_empty_or_comment_only_pin_is_rejected(self):
        for value in ("", "\n # not pinned\n"):
            with self.subTest(value=value), self.assertRaisesRegex(ValueError, "exactly one"):
                self.pin.write_text(value)
                self.verify()

    def test_multiple_pin_entries_are_rejected(self):
        self.pin.write_text(f"{PIN}\n{PIN}\n")
        with self.assertRaisesRegex(ValueError, "exactly one"):
            self.verify()

    def test_missing_pin_file_is_rejected_before_native_execution(self):
        self.pin.unlink()
        with patch.object(debug_signer.subprocess, "run") as run:
            with self.assertRaises(FileNotFoundError):
                debug_signer.verify(self.apk, self.pin, "/sdk/apksigner")
            run.assert_not_called()

    def test_missing_apk_is_rejected(self):
        self.apk.unlink()
        with self.assertRaisesRegex(ValueError, "APK is missing"):
            self.verify()

    def test_missing_certificate_digest_is_rejected(self):
        report = "\n".join(line for line in REPORT.splitlines()
                           if "certificate SHA-256" not in line)
        with self.assertRaisesRegex(ValueError, "exactly one"):
            self.verify(report)

    def test_malformed_certificate_digest_is_rejected(self):
        for value in ("", "invalid", PIN[:-1], "gg" * 32):
            with self.subTest(value=value), self.assertRaises(ValueError):
                self.verify(REPORT.replace(PIN, value))

    def test_multiple_signers_are_rejected(self):
        report = REPORT.replace("Number of signers: 1", "Number of signers: 2")
        report += f"Signer #2 certificate SHA-256 digest: {OTHER}\n"
        with self.assertRaisesRegex(ValueError, "exactly one"):
            self.verify(report)

    def test_duplicate_digest_or_unexpected_signer_number_is_rejected(self):
        for report in (
            REPORT + f"Signer #1 certificate SHA-256 digest: {PIN}\n",
            REPORT.replace("Signer #1", "Signer #2"),
        ):
            with self.subTest(report=report), self.assertRaisesRegex(ValueError, "exactly one"):
                self.verify(report)

    def test_missing_or_inconsistent_count_is_rejected(self):
        for report in (
            REPORT.replace("Number of signers: 1\n", ""),
            REPORT.replace("Number of signers: 1", "Number of signers: 2"),
        ):
            with self.subTest(report=report), self.assertRaisesRegex(ValueError, "exactly one"):
                self.verify(report)

    def test_native_verification_failure_is_never_accepted_by_parsing_stdout(self):
        with patch.object(debug_signer.subprocess, "run",
                          side_effect=subprocess.CalledProcessError(
                              1, "apksigner", output=REPORT,
                          )):
            with self.assertRaises(subprocess.CalledProcessError):
                debug_signer.verify(self.apk, self.pin, "/sdk/apksigner")

    def test_native_timeout_is_not_accepted(self):
        with patch.object(debug_signer.subprocess, "run",
                          side_effect=subprocess.TimeoutExpired("apksigner", 60)):
            with self.assertRaises(subprocess.TimeoutExpired):
                debug_signer.verify(self.apk, self.pin, "/sdk/apksigner")

    def test_cli_returns_nonzero_for_known_failure_boundaries(self):
        arguments = [
            str(self.apk), "--pin", str(self.pin), "--apksigner", "/sdk/apksigner",
        ]
        for error in (
            ValueError("mismatch"),
            FileNotFoundError("verifier missing"),
            subprocess.CalledProcessError(1, "apksigner", output="UNTRUSTED OUTPUT"),
            subprocess.TimeoutExpired("apksigner", 60),
        ):
            with self.subTest(error=error), patch.object(
                debug_signer, "verify", side_effect=error,
            ), contextlib.redirect_stderr(io.StringIO()) as stderr:
                self.assertEqual(1, debug_signer.main(arguments))
                self.assertNotIn("UNTRUSTED OUTPUT", stderr.getvalue())

    def test_cli_reports_only_the_verified_public_fingerprint_on_success(self):
        arguments = [
            str(self.apk), "--pin", str(self.pin), "--apksigner", "/sdk/apksigner",
        ]
        with patch.object(debug_signer, "verify", return_value=PIN), \
                contextlib.redirect_stdout(io.StringIO()) as stdout:
            self.assertEqual(0, debug_signer.main(arguments))
        self.assertIn(PIN, stdout.getvalue())

    def test_unexpected_programming_error_propagates(self):
        with patch.object(debug_signer.subprocess, "run", side_effect=TypeError("bug")):
            with self.assertRaisesRegex(TypeError, "bug"):
                debug_signer.verify(self.apk, self.pin, "/sdk/apksigner")


class DebugSigningWiringTest(unittest.TestCase):
    def test_debug_override_is_environment_only_and_optional(self):
        build = (ROOT / "android/app/build.gradle.kts").read_text()
        self.assertIn(
            'providers.environmentVariable("HEXNEXT_CI_DEBUG_KEYSTORE_PATH").orNull?.let',
            build,
        )
        self.assertNotIn('signingValue("HEXNEXT_CI_DEBUG_KEYSTORE_PATH")', build)
        self.assertIn("require(store.isAbsolute && store.isFile)", build)
        self.assertIn(
            'ciDebugStoreFile?.let { getByName("debug").storeFile = it }', build,
        )

    def test_checked_store_path_is_exported_only_after_the_pin_check(self):
        workflow = (ROOT / ".github/workflows/android.yml").read_text()
        check = workflow[
            workflow.index("- name: Check debug keystore against the pinned certificate"):
            workflow.index("- name: Save pinned debug keystore to cache")
        ]
        export = 'echo "HEXNEXT_CI_DEBUG_KEYSTORE_PATH=$HOME/.android/debug.keystore" >> "$GITHUB_ENV"'
        self.assertIn(export, check)
        self.assertLess(check.index('elif [ "$actual" != "$expected" ]'), check.index(export))

    def test_mismatch_guidance_preserves_tester_data_and_requires_approved_migration(self):
        workflow = (ROOT / ".github/workflows/android.yml").read_text()
        message = next(line for line in workflow.splitlines()
                       if 'echo "::error::The debug keystore' in line)
        self.assertIn("Restore the original debug.keystore", message)
        self.assertIn("HEXNEXT_DEBUG_KEYSTORE_BASE64", message)
        self.assertIn("Preserve tester data", message)
        self.assertIn(
            "do not uninstall the app or change tools/device/debug-keystore.sha256 "
            "without an intentional owner-approved signing migration",
            message,
        )
        self.assertNotIn("uninstall once", message)
        self.assertNotIn("or update tools/device/debug-keystore.sha256", message)

    def test_bootstrap_documentation_does_not_promise_an_unpinned_artifact(self):
        pin = (ROOT / "tools/device/debug-keystore.sha256").read_text()
        workflow = (ROOT / ".github/workflows/android.yml").read_text()
        self.assertIn("no debug APK is uploaded until a valid pin is committed", pin)
        self.assertIn("bootstrap can seed the cache but cannot bypass the APK guard", workflow)
        self.assertNotIn("a bootstrap run on the default branch passes", workflow)
        self.assertNotIn("testers must uninstall before installing it", workflow)
        self.assertNotIn("pin that run in tools/device/install.sh", workflow)

    def test_actual_apk_guard_immediately_precedes_the_play_debug_upload(self):
        workflow = (ROOT / ".github/workflows/android.yml").read_text()
        guard_name = "- name: Verify Play debug APK signer continuity"
        upload_name = "- name: Upload installable debug-signed test build"
        self.assertIn(guard_name, workflow)
        guard = workflow[workflow.index(guard_name):workflow.index(upload_name)]
        self.assertNotIn("- name:", guard[len(guard_name):])
        self.assertNotIn("continue-on-error", guard)
        self.assertNotIn("\n        if:", guard)
        self.assertIn("python3 ../bin/verify-debug-apk-signer.py", guard)
        self.assertIn("--pin ../tools/device/debug-keystore.sha256", guard)
        self.assertIn('--apksigner "$ANDROID_HOME/build-tools/36.0.0/apksigner"', guard)
        self.assertIn(
            "app/build/outputs/apk/play/debug/app-play-universal-debug.apk", guard,
        )
        self.assertNotIn("owner", guard.lower())

    def test_release_signing_and_private_owner_upload_are_not_replaced(self):
        build = (ROOT / "android/app/build.gradle.kts").read_text()
        release = build[
            build.index('create("release")'):
            build.index("// Release signing (below)")
        ]
        self.assertIn("storeFile = releaseStoreFile", release)
        self.assertNotIn("ciDebugStoreFile", release)
        self.assertIn('signingConfig = signingConfigs.findByName("release")', build)
        workflow = (ROOT / ".github/workflows/android.yml").read_text()
        self.assertEqual(1, workflow.count("name: HexNext-Owner-Release"))
        self.assertIn("path: ${{ runner.temp }}/owner-enc/*", workflow)
        self.assertNotIn("name: HexNext-Owner-Debug", workflow)


if __name__ == "__main__":
    unittest.main()
