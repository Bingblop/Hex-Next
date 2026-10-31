import importlib.util
from pathlib import Path
import subprocess
import struct
import tempfile
import unittest
from unittest.mock import patch
import zipfile


spec = importlib.util.spec_from_file_location(
    "release_apk", Path(__file__).resolve().parents[1] / "bin" / "verify-release-apk.py"
)
release_apk = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release_apk)

BADGING = """package: name='com.hexnext.themer' versionCode='210' versionName='2.1.0'
minSdkVersion:'34'
targetSdkVersion:'36'
launchable-activity: name='com.hexnext.themer.MainActivity'
locales: '--_--' 'en'
"""
SIGNATURES = "\n".join(
    f"Verified using v{scheme} scheme (test): true" for scheme in (1, 2, 3)
)

def elf_library(abi, alignment=16384):
    elf_class, machine = release_apk.ELF_ABIS[abi]
    header_size = 64 if elf_class == 2 else 52
    entry_size = 56 if elf_class == 2 else 32
    data = bytearray(header_size + entry_size)
    data[:6] = b"\x7fELF" + bytes((elf_class, 1))
    struct.pack_into("<H", data, 18, machine)
    if elf_class == 2:
        struct.pack_into("<Q", data, 32, header_size)
        struct.pack_into("<HH", data, 54, entry_size, 1)
        struct.pack_into("<Q", data, header_size + 48, alignment)
    else:
        struct.pack_into("<I", data, 28, header_size)
        struct.pack_into("<HH", data, 42, entry_size, 1)
    struct.pack_into("<I", data, header_size, 1)
    return bytes(data)


class ReleaseApkTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.apk = Path(self.directory.name) / "app.apk"
        self.write_apk()

    def write_apk(self, manifest=b"\x03\x00\x08\x00compiled", dex=b"dex\n039\0" + bytes(200), abis=()):
        with zipfile.ZipFile(self.apk, "w") as archive:
            archive.writestr("AndroidManifest.xml", manifest)
            archive.writestr("classes.dex", dex)
            archive.writestr("resources.arsc", b"compiled")
            for abi in abis:
                archive.writestr(f"lib/{abi}/library.so", elf_library(abi))

    def verify(self, badging=BADGING, signatures=SIGNATURES, **kwargs):
        with patch.object(release_apk, "sdk_tool", side_effect=lambda name: name), \
                patch.object(release_apk, "run", side_effect=[badging, "", signatures]) as run:
            result = release_apk.verify(self.apk, **kwargs)
            return result, run

    def test_managed_code_is_universal_and_signatures_are_verified(self):
        result, run = self.verify(expected_version="2.1.0")
        self.assertTrue(result["universal"])
        self.assertTrue(result["signed"])
        self.assertEqual(result["native_abis"], [])
        self.assertEqual(len(result["sha256"]), 64)
        self.assertEqual(run.call_args_list[-1].args[0], "apksigner")

    def test_all_four_native_abis_are_universal(self):
        self.write_apk(abis=release_apk.ABIS)
        result, _ = self.verify()
        self.assertEqual(set(result["native_abis"]), release_apk.ABIS)

    def test_sdk_major_minor_zero_format_is_supported(self):
        result, _ = self.verify(badging=BADGING.replace("'34'", "'34.0'").replace("'36'", "'36.0'"))
        self.assertEqual(result["target_sdk"], 36)

    def test_legacy_sdk_badging_format_is_supported(self):
        result, _ = self.verify(badging=BADGING.replace("minSdkVersion:", "sdkVersion:"))
        self.assertEqual(result["min_sdk"], 34)

    def test_partial_native_abis_are_rejected(self):
        self.write_apk(abis=["arm64-v8a"])
        with self.assertRaisesRegex(ValueError, "not universal"):
            self.verify()

    def test_each_native_library_must_cover_all_abis(self):
        self.write_apk(abis=release_apk.ABIS)
        with zipfile.ZipFile(self.apk, "a") as archive:
            archive.writestr("lib/arm64-v8a/extra.so", elf_library("arm64-v8a"))
        with self.assertRaisesRegex(ValueError, "missing universal ABI"):
            self.verify()

    def test_native_stubs_and_small_page_alignment_are_rejected(self):
        for data in (bytes(128), elf_library("arm64-v8a", alignment=4096)):
            with self.subTest(data=data), self.assertRaises(ValueError):
                release_apk.verify_native_library(data, "arm64-v8a")

    def test_placeholder_manifest_and_dex_are_rejected(self):
        self.write_apk(manifest=b"<manifest/>")
        with self.assertRaisesRegex(ValueError, "binary XML"):
            self.verify()
        self.write_apk(dex=b"dex\n039\0" + bytes(104))
        with self.assertRaisesRegex(ValueError, "bytecode"):
            self.verify()

    def test_unsigned_candidate_never_claims_signing(self):
        result, run = self.verify(unsigned=True)
        self.assertFalse(result["signed"])
        self.assertEqual(run.call_count, 2)

    def test_tag_mismatch_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "does not match"):
            self.verify(expected_version="2.2.0")

    def test_invalid_metadata_is_rejected(self):
        for badging in (
            BADGING.replace("com.hexnext.themer'", "com.other.app'", 1),
            BADGING.replace("minSdkVersion:'34'", "minSdkVersion:'33'"),
            BADGING.replace("targetSdkVersion:'36'", "targetSdkVersion:'35'"),
            BADGING.replace("versionCode='210'", "versionCode='0'"),
            BADGING.replace("com.hexnext.themer.MainActivity", "com.other.Activity"),
            BADGING + "application-debuggable\n",
            BADGING.replace("'en'", "'fr'"),
        ):
            with self.subTest(badging=badging), self.assertRaises(ValueError):
                self.verify(badging=badging)

    def test_missing_signature_schemes_and_debug_keys_are_rejected(self):
        for signatures in (
            SIGNATURES.replace(": true", ": false"),
            SIGNATURES + "\nSigner #1 certificate DN: CN=Android Debug, O=Android",
        ):
            with self.subTest(signatures=signatures), self.assertRaises(ValueError):
                self.verify(signatures=signatures)

    def test_real_tool_failures_are_not_swallowed(self):
        with patch.object(release_apk, "sdk_tool", return_value="aapt2"), \
                patch.object(release_apk, "run", side_effect=subprocess.CalledProcessError(1, "aapt2")):
            with self.assertRaises(subprocess.CalledProcessError):
                release_apk.verify(self.apk)


if __name__ == "__main__":
    unittest.main()
