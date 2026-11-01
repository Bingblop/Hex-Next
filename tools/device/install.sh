#!/system/bin/sh
# Hex Next: install the pinned CI debug build (root required).
# Downloads the GitHub Actions artifact, checks its SHA-256 against the value the GitHub
# artifacts API reported, installs it with `pm install -r` (keeps app data), then prints
# the installed version.
#
# Usage (Termux):  sh install.sh
#   Private repo:  GH_TOKEN=github_pat_... sh install.sh
#                  (a fine-grained token with read access to Contents and Actions; it is
#                  used only for the download and never stored)
#   Overrides:     RUN_ID=... APK_ZIP_SHA256=... sh install.sh
set -u
RUN_ID="${RUN_ID:-41357820412}"
APK_ZIP_SHA256="${APK_ZIP_SHA256:-11f3006bfe25cdd41a0fd8ec3f06293d5457affd109646a4226523db73144296}"
ARTIFACT="${ARTIFACT:-HexNext-Debug-Test-Installable}"
REPO=Bingblop/Hex-Next
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
if [ -n "${GH_TOKEN:-}" ]; then
  # Private repo: resolve the artifact through the API and follow its signed download URL.
  echo "Resolving artifact $ARTIFACT of run $RUN_ID through the GitHub API ..."
  # The token goes to curl through a config on stdin, so it never appears in a process's argv.
  LIST=$(printf 'header = "Authorization: Bearer %s"\n' "$GH_TOKEN" | curl --config - -fsSL -H "Accept: application/vnd.github+json" \
    "https://api.github.com/repos/$REPO/actions/runs/$RUN_ID/artifacts") || { echo "artifact listing failed (token scope? run id?)"; exit 1; }
  APK_URL=$(printf '%s' "$LIST" | tr -d '\n' | sed "s/.*\"name\": *\"$ARTIFACT\"[^}]*\"archive_download_url\": *\"\([^\"]*\)\".*/\1/")
  case "$APK_URL" in https://*) ;; *) echo "artifact $ARTIFACT not found in run $RUN_ID"; exit 1 ;; esac
else
  APK_URL="${APK_URL:-https://nightly.link/$REPO/actions/runs/$RUN_ID/$ARTIFACT.zip}"
fi
download() {
  if [ -n "${GH_TOKEN:-}" ]; then
    printf 'header = "Authorization: Bearer %s"\n' "$GH_TOKEN" | curl --config - -fL --retry 3 -o "$WORK/hexnext-debug.zip" "$APK_URL"
  else
    curl -fL --retry 3 -o "$WORK/hexnext-debug.zip" "$APK_URL"
  fi
}
echo "Downloading run $RUN_ID to $WORK ..."
if ! download; then
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
