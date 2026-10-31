#!/data/data/com.termux/files/usr/bin/bash
# HexBridge Pro - Automated Native Android APK Builder
# Compiles Java, resources, and packages/signs a native WebView launcher APK!

BOLD="\033[1m"
GREEN="\033[1;32m"
RED="\033[1;31m"
YELLOW="\033[1;33m"
CYAN="\033[1;36m"
RESET="\033[0m"

WORK_DIR="/data/data/com.termux/files/home/hex-bridge/build-apk"
ANDROID_JAR="/system/framework/framework-res.apk"
KEYSTORE="$WORK_DIR/bin/release.keystore"
OUTPUT_APK="/storage/emulated/0/Download/HexBridge_Pro.apk"

clear
echo -e "${BOLD}${CYAN}==================================================================${RESET}"
echo -e "${BOLD}${GREEN}               HexBridge Pro APK Builder Engine                   ${RESET}"
echo -e "${BOLD}${CYAN}==================================================================${RESET}"
echo

# Step 0: Ensure directories are clean and exist
echo -e "${YELLOW}[*] Preparing workspace directories...${RESET}"
mkdir -p "$WORK_DIR/obj" "$WORK_DIR/bin"
rm -f "$WORK_DIR/obj/res.zip" "$WORK_DIR/bin/"*

# Step 1: Compile Android Resources using AAPT2
echo -e "${YELLOW}[*] Compiling resources (AAPT2 Compile)...${RESET}"
if ! aapt2 compile --dir "$WORK_DIR/res" -o "$WORK_DIR/obj/res.zip"; then
    echo -e "${RED}Error: AAPT2 resource compilation failed!${RESET}"
    exit 1
fi

# Step 2: Link Resources and generate R.java and uncompiled APK
echo -e "${YELLOW}[*] Linking resources & generating R.java (AAPT2 Link)...${RESET}"
if ! aapt2 link --manifest "$WORK_DIR/AndroidManifest.xml" \
    -I "$ANDROID_JAR" \
    -o "$WORK_DIR/bin/uncompiled.apk" \
    --java "$WORK_DIR/src" \
    "$WORK_DIR/obj/res.zip"; then
    echo -e "${RED}Error: AAPT2 linking failed!${RESET}"
    exit 1
fi

# Step 3: Compile Java Sources using ECJ (Eclipse Compiler for Java)
echo -e "${YELLOW}[*] Compiling Java source files (ECJ Compile)...${RESET}"
if ! ecj -d "$WORK_DIR/obj" \
    -bootclasspath "$ANDROID_JAR" \
    "$WORK_DIR/src/com/hexbridge/pro/R.java" \
    "$WORK_DIR/src/com/hexbridge/pro/MainActivity.java"; then
    echo -e "${RED}Error: Java compilation failed!${RESET}"
    exit 1
fi

# Step 4: Convert Java bytecode (.class) to Android Dalvik VM bytecode (classes.dex)
echo -e "${YELLOW}[*] Compiling Dalvik bytecode (D8 Dex)...${RESET}"
if ! d8 --release --output "$WORK_DIR/bin" "$WORK_DIR/obj/com/hexbridge/pro/"*.class; then
    echo -e "${RED}Error: D8 Dexing failed!${RESET}"
    exit 1
fi

# Step 5: Merge classes.dex into uncompiled.apk to create full APK
echo -e "${YELLOW}[*] Packaging Dex bytecode into APK...${RESET}"
cd "$WORK_DIR/bin" || exit 1
cp "uncompiled.apk" "hexbridge-pro.apk"

# Inject classes.dex safely using Python zip library
python3 -c "
import zipfile
with zipfile.ZipFile('hexbridge-pro.apk', 'a') as archive:
    archive.write('classes.dex', 'classes.dex')
"

# Step 6: Generate Keystore if it doesn't exist
if [ ! -f "$KEYSTORE" ]; then
    echo -e "${YELLOW}[*] Generating self-signed release keystore...${RESET}"
    if ! keytool -genkeypair -v \
        -keystore "$KEYSTORE" \
        -keyalg RSA \
        -keysize 2048 \
        -validity 10000 \
        -alias "hexbridge" \
        -storepass "password" \
        -keypass "password" \
        -dname "CN=hexbridge, OU=pro, O=aether, L=local, S=android, C=US" >/dev/null 2>&1; then
        echo -e "${RED}Error: Keystore generation failed!${RESET}"
        exit 1
    fi
fi

# Step 7: Sign the compiled APK using apksigner
echo -e "${YELLOW}[*] Signing APK (apksigner)...${RESET}"
if ! apksigner sign --ks "$KEYSTORE" \
    --ks-pass "pass:password" \
    --out "hexbridge-pro-signed.apk" \
    "hexbridge-pro.apk"; then
    echo -e "${RED}Error: APK signing failed!${RESET}"
    exit 1
fi

# Step 8: Copy the final signed APK to Downloads folder on Internal Storage
echo -e "${YELLOW}[*] Copying completed APK to Internal Storage Downloads...${RESET}"
cp "hexbridge-pro-signed.apk" "$OUTPUT_APK"

echo
echo -e "${BOLD}${GREEN}✔ NATIVE ANDROID APK COMPILED SUCCESSFULLY!${RESET}"
echo -e "   📍 Saved to: ${BOLD}${CYAN}/storage/emulated/0/Download/HexBridge_Pro.apk${RESET}"
echo
echo -e "${YELLOW}Open your device's My Files / Files app, head to the 'Downloads' folder,${RESET}"
echo -e "${YELLOW}and tap on ${BOLD}${GREEN}HexBridge_Pro.apk${RESET}${YELLOW} to install the native dashboard!${RESET}"
echo
echo -e "${BOLD}${CYAN}==================================================================${RESET}"
echo -e "Enjoy your native, custom-themed One UI companion app! 🪄"
echo -e "${BOLD}${CYAN}==================================================================${RESET}"
echo
