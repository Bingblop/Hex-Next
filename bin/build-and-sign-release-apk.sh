#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUTPUT_APK="${1:-$ROOT_DIR/dist/HexNext_OneUI9_Universal_EN_Signed.apk}"
mkdir -p "$(dirname "$OUTPUT_APK")"
OUTPUT_APK="$(cd "$(dirname "$OUTPUT_APK")" && pwd)/$(basename "$OUTPUT_APK")"

cd "$ROOT_DIR/android"
./gradlew --no-daemon --console=plain -PrequireReleaseSigning=true \
  :app:testDebugUnitTest :app:lintRelease :app:assembleRelease

SOURCE_APK="$ROOT_DIR/android/app/build/outputs/apk/release/app-universal-release.apk"
python3 "$ROOT_DIR/bin/verify-release-apk.py" "$SOURCE_APK"
cp "$SOURCE_APK" "$OUTPUT_APK"
python3 "$ROOT_DIR/bin/verify-release-apk.py" "$OUTPUT_APK" \
  --report "$OUTPUT_APK.json"
(cd "$(dirname "$OUTPUT_APK")" && sha256sum "$(basename "$OUTPUT_APK")" > "$(basename "$OUTPUT_APK").sha256")
echo "Verified signed universal APK: $OUTPUT_APK"
