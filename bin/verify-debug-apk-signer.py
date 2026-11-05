#!/usr/bin/env python3
"""Compare a natively verified APK's public signer with the committed debug pin."""
import argparse
from pathlib import Path
import re
import subprocess
import sys


def normalize_fingerprint(value):
    fingerprint = re.sub(r"[\s:]", "", value).lower()
    if not re.fullmatch(r"[0-9a-f]{64}", fingerprint):
        raise ValueError("Expected a SHA-256 certificate fingerprint (64 hexadecimal digits).")
    return fingerprint


def read_pin(path):
    values = [
        line.strip() for line in path.read_text(encoding="utf-8").splitlines()
        if line.strip() and not line.lstrip().startswith("#")
    ]
    if len(values) != 1:
        raise ValueError("The debug pin must contain exactly one certificate fingerprint.")
    return normalize_fingerprint(values[0])


def parse_signer(report):
    counts = re.findall(r"^Number of signers:[ \t]*([0-9]+)[ \t]*\r?$", report, re.MULTILINE)
    certificates = re.findall(
        r"^Signer #([0-9]+) certificate SHA-256 digest:[ \t]*([^\r\n]*)",
        report, re.MULTILINE,
    )
    if counts != ["1"] or len(certificates) != 1 or certificates[0][0] != "1":
        raise ValueError("Expected exactly one APK signer in apksigner's verified report.")
    return normalize_fingerprint(certificates[0][1])


def verify(apk, pin, apksigner):
    expected = read_pin(pin)
    if not apk.is_file():
        raise ValueError("The Play debug APK is missing.")
    result = subprocess.run(
        [str(apksigner), "verify", "--verbose", "--print-certs", str(apk)],
        check=True, text=True, capture_output=True, timeout=60,
    )
    actual = parse_signer(result.stdout)
    if actual != expected:
        raise ValueError(f"Debug APK signer mismatch: expected {expected}, got {actual}.")
    return actual


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--pin", type=Path, required=True)
    parser.add_argument("--apksigner", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        actual = verify(args.apk, args.pin, args.apksigner)
    except subprocess.CalledProcessError:
        print("Debug APK signature verification failed; artifact withheld.", file=sys.stderr)
        return 1
    except subprocess.TimeoutExpired:
        print("Debug APK signature verification timed out; artifact withheld.", file=sys.stderr)
        return 1
    except (OSError, UnicodeError, ValueError) as error:
        print(f"Debug APK signer continuity failed: {error}", file=sys.stderr)
        return 1
    print(f"Play debug APK certificate SHA-256 matches the pin: {actual}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
