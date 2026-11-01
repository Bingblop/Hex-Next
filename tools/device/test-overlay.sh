#!/system/bin/sh
# Hex Next on-device overlay test (One UI 9, root required).
# Installs the debug test build, checks whether a Hex Next fabricated overlay beats
# Samsung's SemWT_com.android.settings overlay, checks whether Samsung re-enables
# SemWT, collects logs, then reverts every overlay change. The app stays installed.
#
# Usage (Termux):  sh test-overlay.sh                 -> writes hexnext-overlay-test.txt
#                  sh test-overlay.sh /sdcard/Download
#                  APK_URL=... sh test-overlay.sh      (override the download URL)
set -u
OUT_DIR="${1:-$PWD}"
OUT="$OUT_DIR/hexnext-overlay-test.txt"
PKG=com.hexnext.themer
TARGET=com.android.settings
SEMWT="android:SemWT_$TARGET"
PROBE=HexNext_probe
PROBE_ID="com.android.shell:$PROBE"
COLOR=sec_widget_round_and_bgcolor
# Pinned CI artifact (GitHub Actions run 41329717937, artifact HexNext-Debug-Test-Installable)
# and its SHA-256 as reported by the GitHub artifacts API. Override both together:
#   APK_URL=... APK_ZIP_SHA256=... sh test-overlay.sh
APK_URL="${APK_URL:-https://nightly.link/Bingblop/Hex-Next/actions/runs/41329717937/HexNext-Debug-Test-Installable.zip}"
APK_ZIP_SHA256="${APK_ZIP_SHA256:-b34508e95b1c71bd9ff7bdc9f67ef21fa58a166165c7c1df4bda5272509869f6}"
WORK="$OUT_DIR/hexnext-apk"

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
mkdir -p "$WORK"
log ""; log "### Download"
ok=0
log "# curl $APK_URL"
if curl -fsSL -o "$WORK/hexnext-debug.zip" "$APK_URL"; then ok=1; log "downloaded from $APK_URL"; else log "download failed: $APK_URL"; fi
if [ $ok -eq 1 ]; then
  got=$(sha256_file "$WORK/hexnext-debug.zip")
  log "zip sha256: $got (expected $APK_ZIP_SHA256)"
  if [ "$got" != "$APK_ZIP_SHA256" ]; then
    log "Checksum mismatch or no sha256 tool; NOT installing. Continuing with the shell-level overlay test only."
  else
    (cd "$WORK" && unzip -o -q hexnext-debug.zip) && APK=$(find "$WORK" -name '*.apk' | head -1)
    if [ -n "${APK:-}" ]; then
      root "cp '$APK' /data/local/tmp/hexnext-debug.apk && chmod 644 /data/local/tmp/hexnext-debug.apk"
      step "Install" "pm install -r /data/local/tmp/hexnext-debug.apk"
    else
      log "no apk inside zip"
    fi
  fi
else
  log "Could not download the APK; continuing with the shell-level overlay test only."
fi
step "Installed package" "pm list packages | grep -i hexnext; dumpsys package $PKG | grep -E 'versionName|versionCode' | head -2"

# 2. Baseline.
step "Baseline overlay list" "cmd overlay list --user 0 | grep -iE 'hexnext|SemWT_com.android.settings\$|aam_monet'"
baseline_semwt=$(semwt_line); log "baseline SemWT line: $baseline_semwt"
step "Baseline SemWT dump (head)" "cmd overlay dump $SEMWT | head -30"
step "Baseline lookup" "cmd overlay lookup --user 0 $TARGET $TARGET:color/$COLOR"

# 3. Shell-level probe: fabricate, enable, highest priority.
step "Fabricate probe" "cmd overlay fabricate --target $TARGET --name $PROBE --resource-name color/$COLOR --type 0x1c --value 0xffff0000"
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

# 6. Revert.
step "Revert: disable probe" "cmd overlay disable --user 0 $PROBE_ID"
case "$baseline_semwt" in
  "[x]"*) step "Revert: re-enable SemWT" "cmd overlay enable --user 0 $SEMWT";;
  *) log "SemWT was not enabled at baseline; left as is";;
esac
step "Final state" "cmd overlay list --user 0 | grep -iE 'hexnext|SemWT_com.android.settings\$'"
log ""; log "Note: the fabricated probe $PROBE_ID stays registered but disabled; it is harmless and the app removes HexNext* overlays."

echo "Wrote $OUT"
echo "Please attach $OUT (and the two screenshots in /sdcard) to the thread."
