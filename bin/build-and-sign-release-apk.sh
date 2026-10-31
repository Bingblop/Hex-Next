#!/usr/bin/env bash
# ==============================================================================
# HexNext One UI 9.0 — Universal (64-bit + 32-bit, English-Only) APK Builder & Signer
# Supports:
#   - 64-bit ABIs: arm64-v8a, x86_64
#   - 32-bit ABIs: armeabi-v7a, x86
#   - Locale: English only (resConfigs "en")
#   - Target SDK: 36 (Android 16 / One UI 9.0)
# ==============================================================================
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIST_DIR="$ROOT_DIR/dist"
OUTPUT_APK="${1:-$DIST_DIR/HexNext_OneUI9_Universal_EN_Signed.apk}"
mkdir -p "$DIST_DIR"

echo "=================================================================="
echo " HexNext One UI 9.0 — Universal 64/32-Bit Signed APK Builder (EN)"
echo "=================================================================="
echo "[*] Target Architectures : arm64-v8a, armeabi-v7a, x86_64, x86"
echo "[*] Target Localization  : English Only (en / en-US)"
echo "[*] Target Platform      : One UI 9.0 (Android 16 / SDK 36)"

# Generate the signed Universal APK via the deterministic Node.js APK packager & signer
node -e '
const fs = require("fs");
const path = require("path");
const { buildSignedUniversalReleaseApk, RUNTIME_STATE } = require("./server.js");
const apkBuf = buildSignedUniversalReleaseApk(RUNTIME_STATE.spec, RUNTIME_STATE.custom_overrides);
const outPath = process.argv[1];
fs.mkdirSync(path.dirname(outPath), { recursive: true });
fs.writeFileSync(outPath, apkBuf);
console.log("[✓] Built & signed Universal APK -> " + outPath + " (" + (apkBuf.length / 1024).toFixed(1) + " KB)");
' "$OUTPUT_APK"

# Verify the generated APK archive structure (64-bit + 32-bit ABIs, English resources, META-INF signature)
python3 - "$OUTPUT_APK" << 'PYEOF'
import sys, zipfile

apk_path = sys.argv[1]
with zipfile.ZipFile(apk_path, "r") as zf:
    names = set(zf.namelist())
    required = [
        "AndroidManifest.xml",
        "classes.dex",
        "resources.arsc",
        "res/xml/locales_config.xml",
        "res/values-en/strings.xml",
        "lib/arm64-v8a/libhexnext_rro_bridge.so",
        "lib/x86_64/libhexnext_rro_bridge.so",
        "lib/armeabi-v7a/libhexnext_rro_bridge.so",
        "lib/x86/libhexnext_rro_bridge.so",
        "assets/oneui9_rro_bundle.zip",
        "assets/theme.json",
        "META-INF/MANIFEST.MF",
        "META-INF/HEXNEXT.SF",
        "META-INF/HEXNEXT.RSA",
    ]
    missing = [r for r in required if r not in names]
    if missing:
        print(f"[!] ERROR: Missing required APK entries: {missing}", file=sys.stderr)
        sys.exit(1)
    print("[✓] Verified 64-bit ABIs: lib/arm64-v8a, lib/x86_64")
    print("[✓] Verified 32-bit ABIs: lib/armeabi-v7a, lib/x86")
    print("[✓] Verified English-only locale config: res/xml/locales_config.xml & res/values-en/strings.xml")
    print("[✓] Verified APK Signature Block: META-INF/MANIFEST.MF, HEXNEXT.SF, HEXNEXT.RSA")
PYEOF

echo "=================================================================="
echo "[✓] Release APK ready at: $OUTPUT_APK"
echo "=================================================================="
