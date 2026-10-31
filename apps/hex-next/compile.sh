#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
echo "The WebView app is a design reference; building the native Gradle release instead." >&2
exec "$ROOT_DIR/bin/build-and-sign-release-apk.sh" "$@"
