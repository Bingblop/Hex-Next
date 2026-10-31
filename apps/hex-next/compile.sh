#!/data/data/com.termux/files/usr/bin/bash
# HexNext Themer - Automated Native Android APK Builder
# Compiles modern One UI 9 theme installer with embedded engine and assets!

BOLD="\033[1m"
GREEN="\033[1;32m"
RED="\033[1;31m"
YELLOW="\033[1;33m"
CYAN="\033[1;36m"
RESET="\033[0m"

WORK_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ANDROID_JAR="/system/framework/framework-res.apk"
KEYSTORE="$WORK_DIR/bin/release.keystore"
OUTPUT_APK="/storage/emulated/0/Download/HexNext_OneUI_Themer.apk"

clear
echo -e "${BOLD}${CYAN}==================================================================${RESET}"
echo -e "${BOLD}${GREEN}               HexNext Themer APK Builder Engine                  ${RESET}"
echo -e "${BOLD}${CYAN}==================================================================${RESET}"
echo

# Step 0: Clean
echo -e "${YELLOW}[*] Preparing build workspace...${RESET}"
mkdir -p "$WORK_DIR/obj" "$WORK_DIR/bin"
rm -f "$WORK_DIR/obj/res.zip" "$WORK_DIR/bin/"*

# Step 1: AAPT2 Compile Resources
echo -e "${YELLOW}[*] Compiling resources (AAPT2 Compile)...${RESET}"
if ! aapt2 compile --dir "$WORK_DIR/res" -o "$WORK_DIR/obj/res.zip"; then
    echo -e "${RED}Error: AAPT2 resource compilation failed!${RESET}"
    exit 1
fi

# Step 2: AAPT2 Link Resources & Assets
echo -e "${YELLOW}[*] Linking resources and assets (AAPT2 Link)...${RESET}"
if ! aapt2 link --manifest "$WORK_DIR/AndroidManifest.xml" \
    -I "$ANDROID_JAR" \
    -A "$WORK_DIR/assets" \
    -o "$WORK_DIR/bin/uncompiled.apk" \
    --java "$WORK_DIR/src" \
    "$WORK_DIR/obj/res.zip"; then
    echo -e "${RED}Error: AAPT2 linking failed!${RESET}"
    exit 1
fi

# Step 3: Compile Java with ECJ
echo -e "${YELLOW}[*] Compiling Java source files (ECJ Compile)...${RESET}"
if ! ecj -d "$WORK_DIR/obj" \
    -bootclasspath "$ANDROID_JAR" \
    "$WORK_DIR/src/com/hexnext/themer/R.java" \
    "$WORK_DIR/src/com/hexnext/themer/MainActivity.java"; then
    echo -e "${RED}Error: Java compilation failed!${RESET}"
    exit 1
fi

# Step 4: Convert Java bytecode to Dalvik bytecode (classes.dex)
echo -e "${YELLOW}[*] Compiling Dalvik bytecode (D8 Dex)...${RESET}"
if ! d8 --release --output "$WORK_DIR/bin" "$WORK_DIR/obj/com/hexnext/themer/"*.class; then
    echo -e "${RED}Error: D8 Dexing failed!${RESET}"
    exit 1
fi

# Step 5: Merge classes.dex into APK
echo -e "${YELLOW}[*] Packaging Dex bytecode into APK...${RESET}"
cd "$WORK_DIR/bin" || exit 1
cp "uncompiled.apk" "hexnext.apk"

python3 -c "
import zipfile
with zipfile.ZipFile('hexnext.apk', 'a') as archive:
    archive.write('classes.dex', 'classes.dex')
"

# Step 6: Keystore
if [ ! -f "$KEYSTORE" ]; then
    echo -e "${YELLOW}[*] Generating release keystore...${RESET}"
    if ! keytool -genkeypair -v \
        -keystore "$KEYSTORE" \
        -keyalg RSA \
        -keysize 2048 \
        -validity 10000 \
        -alias "hexnext" \
        -storepass "password" \
        -keypass "password" \
        -dname "CN=hexnext, OU=themer, O=oneui, L=local, S=android, C=US" >/dev/null 2>&1; then
        echo -e "${RED}Error: Keystore generation failed!${RESET}"
        exit 1
    fi
fi

# Step 7: Sign APK
echo -e "${YELLOW}[*] Signing APK (apksigner)...${RESET}"
if ! apksigner sign --ks "$KEYSTORE" \
    --ks-pass "pass:password" \
    --out "hexnext-signed.apk" \
    "hexnext.apk"; then
    echo -e "${RED}Error: APK signing failed!${RESET}"
    exit 1
fi

# Step 8: Copy to Downloads
echo -e "${YELLOW}[*] Copying completed APK to Internal Storage Downloads...${RESET}"
cp "hexnext-signed.apk" "$OUTPUT_APK"

echo
echo -e "${BOLD}${GREEN}✔ HEXNEXT THEMER APK COMPILED & SIGNED SUCCESSFULLY!${RESET}"
echo -e "   📍 Saved to: ${BOLD}${CYAN}/storage/emulated/0/Download/HexNext_OneUI_Themer.apk${RESET}"
echo
echo -e "${YELLOW}Open your device's Files/My Files app, go to Downloads,${RESET}"
echo -e "${YELLOW}and tap on ${BOLD}${GREEN}HexNext_OneUI_Themer.apk${RESET}${YELLOW} to install your new One UI themer!${RESET}"
echo
echo -e "${BOLD}${CYAN}==================================================================${RESET}"
