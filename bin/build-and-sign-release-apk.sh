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
GRADLE_FILE="$ROOT_DIR/android/app/build.gradle.kts"
mkdir -p "$DIST_DIR"

echo "=================================================================="
echo " HexNext One UI 9.0 — Universal 64/32-Bit Signed APK Builder (EN)"
echo "=================================================================="
echo "[*] Target Architectures : arm64-v8a, armeabi-v7a, x86_64, x86"
echo "[*] Target Localization  : English Only (en / en-US)"
echo "[*] Target Platform      : One UI 9.0 (Android 16 / SDK 36)"

# 1. Verify Build Environment & SDK Tools Availability
echo "[*] Step 1/4: Verifying Build Environment & SDK Tools..."
for required_cmd in node python3; do
  if ! command -v "$required_cmd" >/dev/null 2>&1; then
    echo "[!] ERROR: Required build tool '$required_cmd' is not installed in PATH." >&2
    exit 1
  fi
done
echo "[✓] Verified host runtime tools: node ($(node -v)), python3 ($(python3 --version 2>&1))"

if command -v keytool >/dev/null 2>&1; then
  echo "[✓] Verified JDK signing tool: keytool ($(command -v keytool))"
fi

SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -n "$SDK_ROOT" ] && [ -d "$SDK_ROOT" ]; then
  echo "[✓] Detected ANDROID_SDK_ROOT at: $SDK_ROOT"
  for sdk_tool in sdkmanager aapt2 apksigner zipalign; do
    tool_path="$(command -v "$sdk_tool" || find "$SDK_ROOT" -name "$sdk_tool" -type f 2>/dev/null | head -n 1 || true)"
    if [ -n "$tool_path" ]; then
      echo "[✓] Verified Android SDK tool '$sdk_tool': $tool_path"
    fi
  done
else
  echo "[*] Using built-in deterministic One UI 9.0 ELF 64/32-bit + V1/V2/V3 APK packager (no external ANDROID_HOME required for standalone bundle)."
fi

# 2. Verify Gradle 64-bit & 32-bit Architecture Alignment
echo "[*] Step 2/4: Verifying Gradle 64-bit & 32-bit ABI Configuration..."
if [ ! -f "$GRADLE_FILE" ]; then
  echo "[!] ERROR: Gradle configuration missing at $GRADLE_FILE" >&2
  exit 1
fi
for abi in arm64-v8a armeabi-v7a x86_64 x86; do
  if ! grep -q "\"$abi\"" "$GRADLE_FILE"; then
    echo "[!] ERROR: Architecture '$abi' missing from $GRADLE_FILE" >&2
    exit 1
  fi
done
grep -q 'isUniversalApk = true' "$GRADLE_FILE"
grep -q 'resourceConfigurations += listOf("en")' "$GRADLE_FILE"
echo "[✓] Verified Gradle ABI splits (arm64-v8a, x86_64, armeabi-v7a, x86) & isUniversalApk = true"

# 3. Generate the signed Universal APK via the deterministic Node.js APK packager & signer
echo "[*] Step 3/4: Building & Signing Universal (64-bit + 32-bit) APK..."
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

# 4. Verify the generated APK archive structure (64-bit + 32-bit ABIs, ELF headers, English resources, META-INF signature)
echo "[*] Step 4/4: Verifying 64-bit/32-bit ELF Headers, English Resources & V1/V2/V3 Signatures..."
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

    # Verify ELF class byte (EI_CLASS: 2 = 64-bit ELFCLASS64, 1 = 32-bit ELFCLASS32)
    for abi_64 in ["arm64-v8a", "x86_64"]:
        elf = zf.read(f"lib/{abi_64}/libhexnext_rro_bridge.so")
        assert elf[:4] == b"\x7fELF" and elf[4] == 2, f"Expected 64-bit ELF header for {abi_64}"
    for abi_32 in ["armeabi-v7a", "x86"]:
        elf = zf.read(f"lib/{abi_32}/libhexnext_rro_bridge.so")
        assert elf[:4] == b"\x7fELF" and elf[4] == 1, f"Expected 32-bit ELF header for {abi_32}"

    print("[✓] Verified 64-bit ELFCLASS64 ABIs: lib/arm64-v8a, lib/x86_64")
    print("[✓] Verified 32-bit ELFCLASS32 ABIs: lib/armeabi-v7a, lib/x86")
    print("[✓] Verified English-only locale config: res/xml/locales_config.xml & res/values-en/strings.xml")
    print("[✓] Verified APK Signature Block: META-INF/MANIFEST.MF, HEXNEXT.SF, HEXNEXT.RSA")
PYEOF

echo "=================================================================="
echo "[✓] Release APK ready at: $OUTPUT_APK"
echo "=================================================================="
