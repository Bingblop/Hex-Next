#!/system/bin/sh
# Hex Next on-device overlay test (One UI 9, root required).
# Installs the debug test build, checks whether a Hex Next fabricated overlay beats
# Samsung's SemWT_com.android.settings overlay, checks whether Samsung re-enables
# SemWT, collects logs, then reverts every overlay change. The app stays installed.
#
# Usage (Termux):  sh test-overlay.sh                 -> writes hexnext-overlay-test.txt
#                  sh test-overlay.sh /sdcard/Download
#                  GH_TOKEN=github_pat_... sh test-overlay.sh   (private repo, as in install.sh)
#                  SKIP_INSTALL=1 sh test-overlay.sh   (test the build already installed)
#                  RUN_ID=... APK_ZIP_SHA256=... sh test-overlay.sh   (another CI run)
set -u
OUT_DIR="${1:-$PWD}"
OUT="$OUT_DIR/hexnext-overlay-test.txt"
# Each run overwrites the report; logcat -d still shows earlier runs.
PKG=com.hexnext.themer
TARGET=com.android.settings
SEMWT="android:SemWT_$TARGET"
PROBE=HexNext_probe
PROBE_ID="com.android.shell:$PROBE"
COLOR=sec_widget_round_and_bgcolor
# Pinned CI artifact and its SHA-256 as reported by the GitHub artifacts API. Keep these
# equal to RUN_ID / APK_ZIP_SHA256 in install.sh so both scripts test the same build
# (tests/test_device_scripts.py fails when they drift apart).
# Empty until a CI run signed with the pinned debug key exists (see install.sh); without them
# the test skips the install and tests the build that is already installed.
RUN_ID="${RUN_ID:-41497205555}"
APK_ZIP_SHA256="${APK_ZIP_SHA256:-80cd68a7a45732e7d09f36dbb7217ffd089dc7ebb82ac72a4a2d6c0f42559de4}"
ARTIFACT="${ARTIFACT:-HexNext-Debug-Test-Installable}"
REPO=Bingblop/Hex-Next
# The build is downloaded and unpacked in the app-private home directory, never in OUT_DIR:
# OUT_DIR may be shared storage (/sdcard/Download), where another app could swap the zip
# after the checksum or plant an APK next to it before root installs it.
WORK="${HOME:-$PWD}/hexnext-apk"

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1
  elif command -v toybox >/dev/null 2>&1; then toybox sha256sum "$1" | cut -d' ' -f1
  elif command -v openssl >/dev/null 2>&1; then openssl dgst -sha256 "$1" | sed 's/.*= //'
  else echo "no-sha256-tool"; fi
}

if ! command -v su >/dev/null 2>&1 || ! su -c id >/dev/null 2>&1; then
  echo "This test needs root (su). Aborting before changing anything."; exit 1
fi

root() { su -c "$*" 2>&1; }
log() { printf '%s\n' "$*" >>"$OUT"; }
step() {
  # step "label" "command (run as root)"
  log ""; log "### $1"; log "# $2"
  res=$(root "$2"); code=$?
  log "(exit $code)"; log "$res"
}
lookup() { root "cmd overlay lookup --user 0 $TARGET $TARGET:color/$COLOR"; }
semwt_line() { root "cmd overlay list --user 0" | grep -E "SemWT_com\.android\.settings$"; }

: >"$OUT"
log "Hex Next overlay test $(date)"
step "Build" "getprop ro.build.version.oneui; getprop ro.product.model; getprop ro.build.display.id"

# 1. Download and install the debug build.
rm -rf "$WORK"; mkdir -p "$WORK"
log ""; log "### Download"
ok=0
if [ -z "${SKIP_INSTALL:-}" ] && { [ -z "$RUN_ID" ] || [ -z "$APK_ZIP_SHA256" ]; }; then
  log "No pinned CI run (RUN_ID / APK_ZIP_SHA256 unset); testing the build that is already installed."
  SKIP_INSTALL=1
fi
if [ -n "${SKIP_INSTALL:-}" ]; then
  log "SKIP_INSTALL set; testing the build that is already installed."
elif [ -n "${GH_TOKEN:-}" ]; then
  # Private repo: resolve the artifact through the API (the token is passed on stdin, never in argv).
  log "# GitHub API: artifact $ARTIFACT of run $RUN_ID"
  LIST=$(printf 'header = "Authorization: Bearer %s"\n' "$GH_TOKEN" | curl --config - -fsSL -H "Accept: application/vnd.github+json" \
    "https://api.github.com/repos/$REPO/actions/runs/$RUN_ID/artifacts") || LIST=""
  APK_URL=$(printf '%s' "$LIST" | tr -d '\n' | sed "s/.*\"name\": *\"$ARTIFACT\"[^}]*\"archive_download_url\": *\"\([^\"]*\)\".*/\1/")
  case "$APK_URL" in
    https://*)
      if printf 'header = "Authorization: Bearer %s"\n' "$GH_TOKEN" | curl --config - -fsSL -o "$WORK/hexnext-debug.zip" "$APK_URL"; then
        ok=1; log "downloaded run $RUN_ID through the GitHub API"
      else log "download failed for run $RUN_ID (GitHub API)"; fi ;;
    *) log "artifact $ARTIFACT not found in run $RUN_ID (token scope? run id?)" ;;
  esac
else
  APK_URL="${APK_URL:-https://nightly.link/$REPO/actions/runs/$RUN_ID/$ARTIFACT.zip}"
  log "# curl $APK_URL"
  if curl -fsSL -o "$WORK/hexnext-debug.zip" "$APK_URL"; then ok=1; log "downloaded from $APK_URL"
  else log "download failed: $APK_URL (a private repo needs GH_TOKEN=...)"; fi
fi
if [ -n "${SKIP_INSTALL:-}" ]; then
  :
elif [ $ok -eq 1 ]; then
  got=$(sha256_file "$WORK/hexnext-debug.zip")
  log "zip sha256: $got (expected $APK_ZIP_SHA256)"
  if [ "$got" != "$APK_ZIP_SHA256" ]; then
    log "Checksum mismatch or no sha256 tool; NOT installing. Continuing with the shell-level overlay test only."
  else
    # Unpack into a fresh directory in the private WORK, so the only APK there is the one
    # from the verified zip.
    rm -rf "$WORK/apk"; mkdir -p "$WORK/apk" &&
      (cd "$WORK/apk" && unzip -o -q ../hexnext-debug.zip) &&
      APK=$(find "$WORK/apk" -type f -name '*.apk' | head -1)
    if [ -n "${APK:-}" ]; then
      # Stream the APK to pm on stdin instead of staging it in the shell-writable
      # /data/local/tmp, where a planted symlink could redirect a root copy.
      size=$(wc -c <"$APK" | tr -d ' ')
      case "$size" in ''|*[!0-9]*) size=0 ;; esac
      log ""; log "### Install"; log "# pm install -r -S $size < $APK"
      if [ "$size" -gt 0 ]; then
        res=$(su -c "pm install -r -S $size" <"$APK" 2>&1); code=$?
      else
        res="cannot read the size of $APK"; code=1
      fi
      log "(exit $code)"; log "$res"
      case "$res" in
        *Success*) log "installed run $RUN_ID" ;;
        *) log "WARNING: install of run $RUN_ID FAILED; the app sections below describe the build that was already installed."
           log "(INSTALL_FAILED_UPDATE_INCOMPATIBLE: installed build has another signing key (older CI or local build); pm uninstall $PKG first, or use SKIP_INSTALL=1.)" ;;
      esac
    else
      log "no apk inside zip"
    fi
  fi
else
  log "Could not download the APK; continuing with the shell-level overlay test only."
fi
step "Installed package" "pm list packages | grep -i hexnext; dumpsys package $PKG | grep -E 'versionName|versionCode|lastUpdateTime' | head -3"

# 2. Baseline.
step "Baseline overlay list" "cmd overlay list --user 0 | grep -iE 'hexnext|SemWT_com.android.settings\$|aam_monet'"
baseline_semwt=$(semwt_line); log "baseline SemWT line: $baseline_semwt"
# Enabled Hex Next overlays on the target that sit above SemWT (the list is in priority
# order, lowest first). Re-enabling SemWT in revert() puts it on top (last-enabled wins and
# set-priority does not work on fabricated overlays), so revert() cycles these afterwards,
# in this order, to restore the order they had before the run.
baseline_above_semwt=$(root "cmd overlay list --user 0 $TARGET" | sed 's/^[[:space:]]*//' \
  | sed -n '/SemWT_com\.android\.settings$/,$p' | sed -n 's/^\[x\] //p' \
  | grep -i hexnext | grep -v ":$PROBE\$")
log "baseline Hex Next overlays above SemWT: ${baseline_above_semwt:-none}"

# From here on the script changes overlay state. revert() puts it back; the trap runs it on
# any exit, including Ctrl-C, closing the Termux session (HUP) and TERM, so an interrupted
# run does not leave the red probe on and SemWT off. (SIGKILL cannot be trapped; if Android
# kills Termux outright, run: su -c 'cmd overlay disable --user 0 com.android.shell:HexNext_probe'
# and re-enable android:SemWT_com.android.settings if it was on before.)
reverted=0
revert() {
  [ "$reverted" -eq 1 ] && return 0
  reverted=1
  step "Revert: disable probe" "cmd overlay disable --user 0 $PROBE_ID"
  case "$baseline_semwt" in
    "[x]"*) step "Revert: re-enable SemWT" "cmd overlay enable --user 0 $SEMWT"
      for id in $baseline_above_semwt; do
        step "Revert: put $id back above SemWT" "cmd overlay disable --user 0 $id; cmd overlay enable --user 0 $id"
      done;;
    *) step "Revert: SemWT was disabled at baseline, disabling again" "cmd overlay disable --user 0 $SEMWT";;
  esac
}
on_exit() {
  if [ "$reverted" -eq 0 ]; then
    log ""; log "### Interrupted: reverting overlay changes"
    revert
    echo "Interrupted; overlay changes reverted. Partial report: $OUT"
  fi
}
trap on_exit EXIT
trap 'exit 130' INT
trap 'exit 129' HUP
trap 'exit 143' TERM
step "Baseline SemWT dump (head)" "cmd overlay dump $SEMWT | head -30"
step "Baseline lookup" "cmd overlay lookup --user 0 $TARGET $TARGET:color/$COLOR"
case "$baseline_semwt" in
  "[x]"*) ;;
  *) step "SemWT was disabled at baseline; enabling it for the comparison" "cmd overlay enable --user 0 $SEMWT; sleep 2; cmd overlay lookup --user 0 $TARGET $TARGET:color/$COLOR";;
esac

# 3. Shell-level probe: fabricate, enable, highest priority.
# AOSP syntax: fabricate --target PKG --name NAME PKG:TYPE/NAME ENCODED-TYPE ENCODED-VALUE
step "Fabricate probe" "cmd overlay fabricate --target $TARGET --name $PROBE $TARGET:color/$COLOR 0x1c 0xffff0000 || cmd overlay help 2>&1 | grep -A6 fabricate"
step "Probe registered?" "cmd overlay list --user 0 | grep -E 'HexNext_probe\$'; cmd overlay dump $PROBE_ID | head -20"
step "Enable probe" "cmd overlay enable --user 0 $PROBE_ID"
step "Lookup after enable (SemWT still on)" "cmd overlay lookup --user 0 $TARGET $TARGET:color/$COLOR"
step "Probe priority highest" "cmd overlay set-priority $PROBE_ID highest"
step "Lookup after highest (SemWT still on)" "cmd overlay lookup --user 0 $TARGET $TARGET:color/$COLOR"
step "Order now" "cmd overlay list --user 0 | grep -nE 'HexNext_probe|SemWT_com.android.settings\$'"
root "am start -n com.android.settings/.Settings >/dev/null 2>&1; sleep 2; screencap -p /sdcard/hexnext-semwt-on.png"
step "Disable SemWT" "cmd overlay disable --user 0 $SEMWT"
step "Lookup after SemWT disabled" "cmd overlay lookup --user 0 $TARGET $TARGET:color/$COLOR"
root "am start -n com.android.settings/.Settings >/dev/null 2>&1; sleep 2; screencap -p /sdcard/hexnext-semwt-off.png"
log "screenshots: /sdcard/hexnext-semwt-on.png /sdcard/hexnext-semwt-off.png"

# 4. Does Samsung re-enable SemWT?
sleep 30
step "SemWT after 30 s" "cmd overlay list --user 0 | grep -E 'SemWT_com.android.settings\$'"
step "Nudge wallpaper service" "am broadcast -a android.intent.action.WALLPAPER_CHANGED"
sleep 10
step "SemWT after wallpaper broadcast" "cmd overlay list --user 0 | grep -E 'SemWT_com.android.settings\$'"

# 5. App data and logs (if the app ran).
step "App files" "ls -R /data/data/$PKG/files 2>/dev/null | head -60"
step "App build logs" "for f in /data/data/$PKG/files/logs/*.jsonl; do [ -f \"\$f\" ] && { echo \"== \$f\"; tail -40 \"\$f\"; }; done"
step "logcat" "logcat -d | grep -iE 'hexnext|FabricatedOverlay|OverlayManager|idmap' | tail -120"
step "SELinux denials" "dmesg | grep -i avc | grep -iE 'hexnext|shizuku|overlay' | tail -40"

# 6. Revert (the same function the EXIT trap runs if the test is interrupted).
revert
step "Final state" "cmd overlay list --user 0 | grep -iE 'hexnext|SemWT_com.android.settings\$'"
step "Final $TARGET order (lowest first)" "cmd overlay list --user 0 $TARGET | grep -iE 'hexnext|SemWT_com.android.settings\$'"
log ""; log "Note: the fabricated probe $PROBE_ID stays registered but disabled; it is harmless and the app removes HexNext* overlays."

echo "Wrote $OUT"
echo "Please attach $OUT (and the two screenshots in /sdcard) to the thread."
