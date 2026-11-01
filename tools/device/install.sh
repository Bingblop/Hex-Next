#!/system/bin/sh
# Hex Next: install the pinned CI debug build (root required).
# Downloads the GitHub Actions artifact through nightly.link, checks its SHA-256
# against the value the GitHub artifacts API reported, installs it with `pm install -r`
# (keeps app data), then prints the installed version.
#
# Usage (Termux):  sh install.sh
#                  APK_URL=... APK_ZIP_SHA256=... sh install.sh   (override both together)
set -u
RUN_ID="${RUN_ID:-41346852714}"
APK_URL="${APK_URL:-https://nightly.link/Bingblop/Hex-Next/actions/runs/$RUN_ID/HexNext-Debug-Test-Installable.zip}"
APK_ZIP_SHA256="${APK_ZIP_SHA256:-a6e0aa37db618fca3eb635a3b9d4fe465796dc537c61a4b81d9efd27816528c7}"
PKG=com.hexnext.themer
# Download into the home directory: Termux's $TMPDIR can be small or unwritable
# (curl error 23 "client returned ERROR on write" means exactly that).
WORK="${HOME:-$PWD}/hexnext-apk"

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1
  elif command -v toybox >/dev/null 2>&1; then toybox sha256sum "$1" | cut -d' ' -f1
  elif command -v openssl >/dev/null 2>&1; then openssl dgst -sha256 "$1" | sed 's/.*= //'
  else echo "no-sha256-tool"; fi
}

rm -rf "$WORK"; mkdir -p "$WORK" || { echo "cannot create $WORK"; exit 1; }
echo "Downloading run $RUN_ID to $WORK ..."
if ! curl -fL --retry 3 -o "$WORK/hexnext-debug.zip" "$APK_URL"; then
  echo "download failed: $APK_URL"
  echo "free space:"; df -h "$WORK" 2>/dev/null || df "$WORK"
  exit 1
fi
got=$(sha256_file "$WORK/hexnext-debug.zip")
echo "zip sha256: $got"
echo "expected  : $APK_ZIP_SHA256"
if [ "$got" != "$APK_ZIP_SHA256" ]; then
  echo "Checksum mismatch; NOT installing."; exit 2
fi
(cd "$WORK" && unzip -o -q hexnext-debug.zip) || { echo "unzip failed"; exit 3; }
APK=$(find "$WORK" -name '*.apk' | head -1)
[ -n "$APK" ] || { echo "no apk inside zip"; exit 4; }
su -c "cp '$APK' /data/local/tmp/hexnext-debug.apk && chmod 644 /data/local/tmp/hexnext-debug.apk && pm install -r /data/local/tmp/hexnext-debug.apk"
su -c "dumpsys package $PKG | grep -E 'versionName|versionCode|lastUpdateTime' | head -3"
echo "Done. Open Hex Next, then run: sh collect.sh"
