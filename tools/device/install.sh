#!/system/bin/sh
# Hex Next: install the pinned CI debug build (root required).
# Downloads the GitHub Actions artifact, checks its SHA-256 against the value the GitHub
# artifacts API reported, installs it with `pm install -r` (keeps app data), then prints
# the installed version. Exits non-zero when pm rejects the APK. Test builds update in place
# only when they are signed with the pinned CI debug key (android.yml: the
# HEXNEXT_DEBUG_KEYSTORE_BASE64 secret or the cached pin whose certificate is in
# debug-keystore.sha256). A build signed with another key (a run that generated its own key, or
# a local build) fails with INSTALL_FAILED_UPDATE_INCOMPATIBLE and needs `pm uninstall` first
# (loses app data). The CI key was pinned in run 41511487946 (main): builds from that run on
# update in place; installing the first of them over an older build needs one uninstall.
#
# Usage (Termux):  sh install.sh
#   Private repo:  GH_TOKEN=github_pat_... sh install.sh
#                  (a fine-grained token with read access to Contents and Actions; it is
#                  used only for the download and never stored)
#   Overrides:     RUN_ID=... APK_ZIP_SHA256=... sh install.sh
#   Owner build:   ARTIFACT=HexNext-Owner-Release RUN_ID=... APK_ZIP_SHA256=... sh install.sh
#                  (release-signed, all packs unlocked; installs beside the Play build as
#                  com.hexnext.themer.owner, but only one of the two may theme the phone)
# When you change the pin, change RUN_ID / APK_ZIP_SHA256 in test-overlay.sh too.
set -u
RUN_ID="${RUN_ID:-41654728164}"
APK_ZIP_SHA256="${APK_ZIP_SHA256:-93c470aba390ebbd45395aaf3bd3d5c200db3828c35f5fab9e53782f877a1d7d}"
ARTIFACT="${ARTIFACT:-HexNext-Owner-Release}"
REPO=Bingblop/Hex-Next
case "$ARTIFACT" in *Owner*) PKG=com.hexnext.themer.owner ;; *) PKG=com.hexnext.themer ;; esac
# Download into the home directory: Termux's $TMPDIR can be small or unwritable
# (curl error 23 "client returned ERROR on write" means exactly that).
WORK="${HOME:-$PWD}/hexnext-apk"

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1
  elif command -v toybox >/dev/null 2>&1; then toybox sha256sum "$1" | cut -d' ' -f1
  elif command -v openssl >/dev/null 2>&1; then openssl dgst -sha256 "$1" | sed 's/.*= //'
  else echo "no-sha256-tool"; fi
}

if [ -z "$RUN_ID" ] || [ -z "$APK_ZIP_SHA256" ]; then
  echo "No CI build signed with the pinned debug key is pinned yet, so there is nothing to install by default."
  echo "Every build so far has its own throwaway key: installing one over another fails, and the"
  echo "uninstall it then needs deletes Hex Next's themes, packs, AI keys and settings."
  echo "To install a specific run anyway:  RUN_ID=... APK_ZIP_SHA256=... sh install.sh"
  exit 1
fi
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
# Release artifacts hold one APK per ABI plus a universal one: install the universal APK.
APK=$(find "$WORK" -name '*universal*.apk' | head -1)
[ -n "$APK" ] || APK=$(find "$WORK" -name '*.apk' | head -1)
[ -n "$APK" ] || { echo "no apk inside zip"; exit 4; }
# Stream the APK to pm on stdin instead of staging it in the shell-writable /data/local/tmp,
# where a planted symlink could redirect a root copy and the file could be swapped before pm.
SIZE=$(wc -c <"$APK" | tr -d ' ')
case "$SIZE" in ''|*[!0-9]*) echo "cannot read the size of $APK"; exit 4 ;; esac
OUT=$(su -c "pm install -r -S $SIZE" <"$APK" 2>&1)
code=$?
echo "$OUT"
# pm prints "Success" on success; anything else means the old build (if any) is still installed.
if [ $code -ne 0 ] || ! printf '%s\n' "$OUT" | grep -q 'Success'; then
  echo "Install FAILED (exit $code); run $RUN_ID is NOT installed."
  case "$OUT" in
    *INSTALL_FAILED_UPDATE_INCOMPATIBLE*)
      echo "The installed Hex Next is signed with a different key (an older CI build or a local build)."
      echo "Uninstall it first (this deletes its app data), then run this script again:"
      echo "  su -c 'pm uninstall $PKG'" ;;
  esac
  exit 5
fi
su -c "dumpsys package $PKG | grep -E 'versionName|versionCode|lastUpdateTime' | head -3"
echo "Done. Open Hex Next, then run: sh collect.sh"
