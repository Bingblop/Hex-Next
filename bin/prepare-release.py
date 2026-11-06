#!/usr/bin/env python3
"""Validate a release tag and pin it to the checked-out Android version and commit."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys


def run(*args):
    return subprocess.run(args, check=True, text=True, capture_output=True).stdout.strip()


def prepare_release(tag, gradle_file):
    if not re.fullmatch(r"v\d+\.\d+\.\d+(?:-[0-9A-Za-z][0-9A-Za-z.-]*)?(?:\+[0-9A-Za-z][0-9A-Za-z.-]*)?", tag):
        raise ValueError("Release tag must be a version such as v2.1.0 or v2.1.0-rc.1.")
    match = re.search(r'^\s*versionName\s*=\s*"([^"]+)"', gradle_file.read_text(), re.MULTILINE)
    if not match or tag != f"v{match[1]}":
        raise ValueError("Release tag must match versionName in android/app/build.gradle.kts.")
    commit = run("git", "rev-parse", "HEAD")
    tag_ref = f"refs/tags/{tag}"
    references = run("git", "ls-remote", "--tags", "origin", tag_ref, f"{tag_ref}^{{}}")
    refs = {}
    for line in references.splitlines():
        sha, ref = line.split()
        refs[ref] = sha
    existing_commit = refs.get(f"{tag_ref}^{{}}") or refs.get(tag_ref)
    if existing_commit and existing_commit != commit:
        raise ValueError("Existing release tag points to a different commit; select that tag's ref or use a new version.")
    return {
        "tag": tag,
        "version": match[1],
        "commit": commit,
        "prerelease": "-" in match[1].split("+", 1)[0],
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("tag")
    args = parser.parse_args()
    try:
        release = prepare_release(args.tag, Path("android/app/build.gradle.kts"))
        if os.environ.get("GITHUB_OUTPUT"):
            with open(os.environ["GITHUB_OUTPUT"], "a") as output:
                for key, value in release.items():
                    output.write(f"{key}={json.dumps(value) if isinstance(value, bool) else value}\n")
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        print(f"Release preparation failed: {error}", file=sys.stderr)
        if isinstance(error, subprocess.CalledProcessError):
            print(error.stdout + error.stderr, file=sys.stderr)
        return 1
    print(json.dumps(release, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
