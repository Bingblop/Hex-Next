#!/data/data/com.termux/files/usr/bin/bash
# Builds and signs the com.samsung.android.themepark compatibility stub APK

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
mkdir -p "$DIR/build"

echo "[*] Compiling stub APK with aapt2..."
aapt2 link -I /system/framework/framework-res.apk \
    --manifest "$DIR/AndroidManifest.xml" \
    -o "$DIR/build/dummy-unaligned.apk"

if [ ! -f "$DIR/build/debug.keystore" ]; then
    echo "[*] Generating keystore..."
    keytool -genkeypair -v \
        -keystore "$DIR/build/debug.keystore" \
        -alias androiddebugkey \
        -keyalg RSA \
        -keysize 2048 \
        -validity 10000 \
        -storepass android \
        -keypass android \
        -dname "CN=Android Debug,O=Android,C=US"
fi

echo "[*] Signing stub APK..."
apksigner sign --ks "$DIR/build/debug.keystore" \
    --ks-pass pass:android \
    --key-pass pass:android \
    --out "$DIR/build/themepark-stub-signed.apk" \
    "$DIR/build/dummy-unaligned.apk"

echo "[✓] Built signed stub APK at: $DIR/build/themepark-stub-signed.apk"
echo "To install via Shizuku: rish -c 'pm install -r $DIR/build/themepark-stub-signed.apk'"
