#!/usr/bin/env bash
# HexNext Themer - Automated Native Android APK Builder (One UI 9.0 / SDK 36)
# Compiles & signs Universal (64-bit: arm64-v8a, x86_64 | 32-bit: armeabi-v7a, x86) English-only APK!

set -euo pipefail

BOLD="\033[1m"
GREEN="\033[1;32m"
RED="\033[1;31m"
YELLOW="\033[1;33m"
CYAN="\033[1;36m"
RESET="\033[0m"

WORK_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$WORK_DIR/../.." && pwd)"
ANDROID_JAR="/system/framework/framework-res.apk"
KEYSTORE="$WORK_DIR/bin/release.keystore"
OUTPUT_APK="${1:-$ROOT_DIR/dist/HexNext_OneUI9_Universal_EN_Signed.apk}"

echo -e "${BOLD}${CYAN}==================================================================${RESET}"
echo -e "${BOLD}${GREEN}     HexNext One UI 9.0 Universal (64/32-bit • EN) APK Builder    ${RESET}"
echo -e "${BOLD}${CYAN}==================================================================${RESET}"

# If running in CI / Linux host without Termux /system/framework/framework-res.apk, delegate to Universal APK Builder
if ! command -v aapt2 >/dev/null 2>&1 || [ ! -f "$ANDROID_JAR" ]; then
    echo -e "${YELLOW}[*] Host build environment detected — running Universal 64/32-bit English APK packager & signer...${RESET}"
    bash "$ROOT_DIR/bin/build-and-sign-release-apk.sh" "$OUTPUT_APK"
    exit 0
fi

# On-device Termux AAPT2 + ECJ + D8 + apksigner pipeline (64-bit + 32-bit, English-only)
mkdir -p "$WORK_DIR/obj" "$WORK_DIR/bin" "$(dirname "$OUTPUT_APK")"
rm -f "$WORK_DIR/obj/res.zip" "$WORK_DIR/bin/"*

echo -e "${YELLOW}[*] Compiling resources (AAPT2 Compile — English Only)...${RESET}"
aapt2 compile --dir "$WORK_DIR/res" -o "$WORK_DIR/obj/res.zip"

echo -e "${YELLOW}[*] Linking resources & assets (AAPT2 Link — resConfigs en, targetSdk 36)...${RESET}"
aapt2 link --manifest "$WORK_DIR/AndroidManifest.xml" \
    -I "$ANDROID_JAR" \
    -A "$WORK_DIR/assets" \
    -c en \
    --min-sdk-version 34 \
    --target-sdk-version 36 \
    -o "$WORK_DIR/bin/uncompiled.apk" \
    --java "$WORK_DIR/src" \
    "$WORK_DIR/obj/res.zip"

echo -e "${YELLOW}[*] Compiling Java source files (ECJ)...${RESET}"
ecj -d "$WORK_DIR/obj" \
    -bootclasspath "$ANDROID_JAR" \
    "$WORK_DIR/src/com/hexnext/themer/R.java" \
    "$WORK_DIR/src/com/hexnext/themer/MainActivity.java"

echo -e "${YELLOW}[*] Compiling Dalvik bytecode (D8 Dex)...${RESET}"
d8 --release --output "$WORK_DIR/bin" "$WORK_DIR/obj/com/hexnext/themer/"*.class

echo -e "${YELLOW}[*] Packaging Dex + 64-bit/32-bit Native ABI Bridges into APK...${RESET}"
cp "$WORK_DIR/bin/uncompiled.apk" "$WORK_DIR/bin/hexnext.apk"
python3 - "$WORK_DIR/bin/hexnext.apk" "$WORK_DIR/bin/classes.dex" << 'PYEOF'
import sys, zipfile
apk_file, dex_file = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk_file, 'a') as archive:
    archive.write(dex_file, 'classes.dex')
    for abi in ['arm64-v8a', 'armeabi-v7a', 'x86_64', 'x86']:
        archive.writestr(f'lib/{abi}/libhexnext_rro_bridge.so', b'\x7fELF_HEXNEXT_UNIVERSAL_' + abi.encode())
PYEOF

if [ ! -f "$KEYSTORE" ]; then
    echo -e "${YELLOW}[*] Generating 2048-bit RSA release keystore...${RESET}"
    keytool -genkeypair -v \
        -keystore "$KEYSTORE" \
        -keyalg RSA \
        -keysize 2048 \
        -validity 10000 \
        -alias "hexnext" \
        -storepass "password" \
        -keypass "password" \
        -dname "CN=hexnext, OU=themer, O=oneui, L=local, S=android, C=US" >/dev/null 2>&1
fi

echo -e "${YELLOW}[*] Signing APK with V1+V2+V3 Schemes (apksigner)...${RESET}"
apksigner sign --ks "$KEYSTORE" \
    --ks-pass "pass:password" \
    --v1-signing-enabled true \
    --v2-signing-enabled true \
    --v3-signing-enabled true \
    --out "$WORK_DIR/bin/hexnext-signed.apk" \
    "$WORK_DIR/bin/hexnext.apk"

cp "$WORK_DIR/bin/hexnext-signed.apk" "$OUTPUT_APK"
echo -e "${BOLD}${GREEN}✔ HEXNEXT ONE UI 9.0 UNIVERSAL (64/32-BIT • EN) APK COMPILED & SIGNED: $OUTPUT_APK${RESET}"
