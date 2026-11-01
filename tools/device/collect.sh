#!/system/bin/sh
# Hex Next: collect everything the app wrote plus the overlay state into one file (root required).
# Read-only. Run it after Diagnostics -> Run scan -> Run Settings test, and after Apply theme.
#
# Usage (Termux):  sh collect.sh                 -> writes hexnext-collect.txt in the current dir
#                  sh collect.sh /sdcard/Download
set -u
OUT_DIR="${1:-$PWD}"
OUT="$OUT_DIR/hexnext-collect.txt"
PKG=com.hexnext.themer
APP=/data/data/$PKG/files

root() { su -c "$1" 2>&1; }
section() { printf '\n\n===== %s =====\n' "$1" >>"$OUT"; }
run() { section "$1"; printf '$ %s\n' "$2" >>"$OUT"; root "$2" >>"$OUT"; }

: >"$OUT"
printf 'Hex Next collect %s\n' "$(date)" >>"$OUT"
run "Build" "getprop ro.build.version.oneui; getprop ro.product.model; getprop ro.build.display.id"
run "App version" "dumpsys package $PKG | grep -E 'versionName|versionCode|lastUpdateTime' | head -3"
run "App files" "ls -laR $APP 2>/dev/null | head -200"

# The apply report (progressive; status started/running/finished/aborted).
run "apply-last.json" "cat $APP/reports/apply-last.json 2>/dev/null || echo '(none)'"

# Build logs: the newest three, in full.
section "Build logs (newest 3)"
for f in $(root "ls -t $APP/logs/*.jsonl 2>/dev/null | head -3"); do
  printf '\n--- %s ---\n' "$f" >>"$OUT"
  root "cat '$f'" >>"$OUT"
done

# Drawable names the Settings arsc really has (written by the scan since build 53).
# The whole list goes to its own file; the collect file only gets the dashboard-looking names.
run "Settings drawable count" "wc -l $APP/scan/com.android.settings/names-drawable.txt 2>/dev/null || echo '(no names-drawable.txt; re-run the scan)'"
root "cat $APP/scan/com.android.settings/names-drawable.txt 2>/dev/null" >"$OUT_DIR/hexnext-settings-drawables.txt"
run "Settings dashboard-looking drawable names" "grep -iE '^(ic_settings|sec_ic_|homepage|sec_homepage|ic_homepage|dashboard|top_level)|connections|display|wallpaper|lock_screen|home_screen|sound|notification|modes|routine|safety|emergency|location|google|theme|software_update|about_phone|wellbeing|device_care|general_management|accounts_and|advanced_features|security|privacy|accessibility|^ic_apps' $APP/scan/com.android.settings/names-drawable.txt 2>/dev/null | grep -viE 'bluetooth_|battery_tips|_buds|earbuds|wifi_|signin' | head -400"

# Overlay state.
run "Hex Next + SemWT overlays (user 0)" "cmd overlay list --user 0 | grep -iE 'hexnext|SemWT_|aam_monet'"
run "Hex Next overlays (user current)" "cmd overlay list --user current | grep -i hexnext"
run "Settings colour lookup" "cmd overlay lookup --user current com.android.settings com.android.settings:color/sec_widget_round_and_bgcolor"
run "Hex Next frro files" "ls -laZ /data/resource-cache/ | grep -i hexnext"
run "Hex Next idmap files" "ls -la /data/resource-cache/ | grep -i hexnext | grep -i idmap"

# Which drawables the test overlay claims to override, and whether lookups resolve to frro:/ paths.
section "Drawable lookups from the newest build log"
newest=$(root "ls -t $APP/logs/*.jsonl 2>/dev/null | head -1")
if [ -n "$newest" ]; then
  for name in $(root "cat '$newest'" | grep -oE 'drawable\\?/[A-Za-z0-9_]+' | sed 's#\\##' | sort -u | head -20); do
    printf '$ cmd overlay lookup --user current com.android.settings com.android.settings:%s\n' "$name" >>"$OUT"
    root "cmd overlay lookup --user current com.android.settings com.android.settings:$name" >>"$OUT"
  done
else
  echo "(no build log)" >>"$OUT"
fi

# Crash and idmap evidence.
run "Crashes (logcat crash buffer, Hex Next only)" "logcat -d -b crash 2>/dev/null | grep -A40 -i hexnext | tail -120"
run "idmap / OverlayManager log lines" "logcat -d 2>/dev/null | grep -iE 'idmap|OverlayManager|FabricatedOverlay' | tail -80"
run "Hex Next app log lines" "logcat -d 2>/dev/null | grep -iE 'HexNext|ThemeService|ThemeApplier|FrroWriter' | tail -150"

echo "Wrote $OUT"
