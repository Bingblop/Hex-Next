#!/system/bin/sh
# Hex Next: install the pinned CI debug build (root required).
# Downloads the GitHub Actions artifact through nightly.link, checks its SHA-256
# against the value the GitHub artifacts API reported, installs it with `pm install -r`
# (keeps app data), then prints the installed version.
#
# Usage (Termux):  sh install.sh
#                  APK_URL=... APK_ZIP_SHA256=... sh install.sh   (override both together)
set -u
RUN_ID="${RUN_ID:-41343388106}"
APK_URL="${APK_URL:-https://nightly.link/Bingblop/Hex-Next/actions/runs/$RUN_ID/HexNext-Debug-Test-Installable.zip}"
APK_ZIP_SHA256="${APK_ZIP_SHA256:-0005fdcfef255dfa41170744df44ef931a72517b6dd29c4e664bb5805162fb9a}"
PKG=com.hexnext.themer
WORK="${TMPDIR:-$PWD}/hexnext-apk"

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1
  elif command -v toybox >/dev/null 2>&1; then toybox sha256sum "$1" | cut -d' ' -f1
  elif command -v openssl >/dev/null 2>&1; then openssl dgst -sha256 "$1" | sed 's/.*= //'
  else echo "no-sha256-tool"; fi
}

mkdir -p "$WORK"
echo "Downloading run $RUN_ID ..."
if ! curl -fsSL -o "$WORK/hexnext-debug.zip" "$APK_URL"; then
  echo "download failed: $APK_URL"; exit 1
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
