#!/usr/bin/env python3
"""Verify a Gradle-built universal release APK with Android SDK tooling."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import zipfile


ABIS = {"arm64-v8a", "armeabi-v7a", "x86_64", "x86"}


def sdk_tool(name):
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        raise ValueError("Set ANDROID_HOME or ANDROID_SDK_ROOT to the Android SDK.")
    tool = Path(sdk) / "build-tools" / "36.0.0" / name
    if not tool.is_file():
        raise ValueError(f"Required SDK tool is missing: {tool}")
    return str(tool)


def run(*args):
    return subprocess.run(args, check=True, text=True, capture_output=True).stdout


def verify(apk, unsigned=False, expected_version=None):
    with zipfile.ZipFile(apk) as archive:
        if archive.testzip() is not None:
            raise ValueError("Corrupt APK archive.")
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError("APK contains duplicate ZIP entries.")
        for name in ("AndroidManifest.xml", "classes.dex", "resources.arsc"):
            if name not in names:
                raise ValueError(f"APK is missing {name}.")
        if archive.read("AndroidManifest.xml")[:4] != b"\x03\x00\x08\x00":
            raise ValueError("APK manifest is not compiled binary XML.")
        dex = archive.read("classes.dex")
        if not dex.startswith(b"dex\n") or len(dex) <= 112:
            raise ValueError("APK does not contain compiled application bytecode.")
        native_abis = {name.split("/")[1] for name in names if name.startswith("lib/") and name.endswith(".so")}
        # A managed-code APK with no native libraries runs on every ABI.
        if native_abis and native_abis != ABIS:
            raise ValueError(f"APK is not universal: native ABIs are {sorted(native_abis)}.")

    badging = run(sdk_tool("aapt2"), "dump", "badging", str(apk))
    package = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
    if not package or package[1] != "com.hexnext.themer":
        raise ValueError("Unexpected application ID or missing package metadata.")
    if expected_version and package[3] != expected_version:
        raise ValueError(f"Tag version {expected_version} does not match APK version {package[3]}.")
    if int(package[2]) <= 0:
        raise ValueError("APK versionCode must be positive.")
    if "sdkVersion:'34'" not in badging or "targetSdkVersion:'36'" not in badging:
        raise ValueError("Expected minSdk 34 and targetSdk 36.")
    if "application-debuggable" in badging:
        raise ValueError("Release APK must not be debuggable.")
    if "launchable-activity: name='com.hexnext.themer.MainActivity'" not in badging:
        raise ValueError("APK does not have the native HexNext launcher activity.")
    locales = re.search(r"^locales: (.+)$", badging, re.MULTILINE)
    if locales:
        unexpected = {locale for locale in re.findall(r"'([^']*)'", locales[1]) if locale not in ("", "--_--") and not locale.startswith("en")}
        if unexpected:
            raise ValueError(f"Unexpected non-English locales: {sorted(unexpected)}.")

    run(sdk_tool("zipalign"), "-c", "-P", "16", "-v", "4", str(apk))
    signatures = None
    if not unsigned:
        signatures = run(sdk_tool("apksigner"), "verify", "--verbose", "--print-certs", str(apk))
        for scheme in (1, 2, 3):
            if not re.search(rf"Verified using v{scheme} scheme .*: true", signatures):
                raise ValueError(f"APK is missing a verified v{scheme} signature.")
        if "CN=Android Debug" in signatures:
            raise ValueError("Release APK must not use an Android debug certificate.")
    return {
        "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
        "application_id": package[1],
        "version_code": int(package[2]),
        "version_name": package[3],
        "universal": True,
        "native_abis": sorted(native_abis),
        "min_sdk": 34,
        "target_sdk": 36,
        "signed": not unsigned,
        "signature_verification": signatures,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--unsigned", action="store_true", help="Validate a CI candidate, not a publishable release.")
    parser.add_argument("--expected-version")
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    try:
        report = verify(args.apk, args.unsigned, args.expected_version)
    except (OSError, ValueError, zipfile.BadZipFile, subprocess.CalledProcessError) as error:
        print(f"APK verification failed: {error}", file=sys.stderr)
        if isinstance(error, subprocess.CalledProcessError):
            print(error.stdout + error.stderr, file=sys.stderr)
        return 1
    if args.report:
        args.report.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
