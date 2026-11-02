#!/system/bin/sh
# Hex Next: find the real Settings dashboard icon names and test whether Settings caches icons.
# Root required. Run it right AFTER "Run Settings test" in the app (the test overlay must be enabled).
#
# Usage (Termux):  sh icons-probe.sh            -> writes hexnext-icons.txt in the current dir
#                                                   and hexnext-settings-restart.png in /sdcard/Download
set -u
OUT_DIR="${1:-$PWD}"
OUT="$OUT_DIR/hexnext-icons.txt"
PKG=com.hexnext.themer
NAMES=/data/data/$PKG/files/scan/com.android.settings/names-drawable.txt
SHOT=/sdcard/Download/hexnext-settings-restart.png

root() { su -c "$1" 2>&1; }
section() { printf '\n\n===== %s =====\n' "$1" >>"$OUT"; }
run() { section "$1"; printf '$ %s\n' "$2" >>"$OUT"; root "$2" >>"$OUT"; }

: >"$OUT"
printf 'Hex Next icons probe %s\n' "$(date)" >>"$OUT"

run "Settings drawable count" "wc -l $NAMES"
run "Names that start like dashboard icons" \
  "grep -iE '^(ic_settings|sec_ic_settings|sec_ic_|homepage|sec_homepage|ic_homepage|settings_homepage|ic_dashboard|dashboard|top_level|sec_top|ic_sec_)' $NAMES | head -300"
run "Names with dashboard menu words (whole file)" \
  "grep -iE 'connections|connected_devices|display|wallpaper|lock_screen|lockscreen|home_screen|homescreen|sounds|sound_and|notifications|modes_and|routines|safety|emergency|location|google|themes|software_update|about_phone|wellbeing|device_care|general_management|accounts_and|advanced_features|security_and|privacy|accessibility|^ic_apps|galaxy_ai' $NAMES | grep -viE 'bluetooth_|battery_tips|_buds|earbuds|wifi_|signin|aspect_ratio' | head -400"
run "Names with SF-symbol style suffixes (whole file)" \
  "grep -iE '(gearshape|trianglehead|_circle$|slider_horizontal|rectangle_and|heart_circle|phone_circle|bell|speaker|wifi$|antenna|paintbrush|paintpalette|lock$|shield|house|person|globe|sun_max|moon|hand_raised|star$|arrow_down_to_line|exclamationmark)' $NAMES | head -300"

APK=$(root "pm path com.android.settings" | head -1 | sed 's/^package://')
section "Settings APK"
printf 'apk: %s\n' "$APK" >>"$OUT"
run "Homepage xml candidates inside the APK" "unzip -l '$APK' | grep -iE 'res/xml[^ ]*(top_level|home|dashboard|sec_settings)' | head -40"

# Icon cache test: the test overlay is enabled, so a freshly started Settings must draw the new icons
# if the overridden names are the ones the dashboard uses.
run "Hex Next overlays before" "cmd overlay list --user current | grep -i hexnext"
run "Lookups of overridden icons" \
  "for n in ic_advanced_features_plus_gearshape ic_accounts_and_backup_arrow_trianglehead_2_counterclockwise_rotate_90 ic_about_phone_info ic_apps ic_accessibility; do printf '%s: ' \$n; cmd overlay lookup --user current com.android.settings com.android.settings:drawable/\$n; done"
run "Settings process before" "ps -A -o PID,STIME,NAME | grep -E ' com.android.settings\$'"
run "Force-stop Settings, reopen, screenshot" \
  "am force-stop com.android.settings; sleep 2; am start -W -a android.settings.SETTINGS | grep -E 'Status|TotalTime'; sleep 5; screencap -p $SHOT && ls -la $SHOT"
run "Settings process after" "ps -A -o PID,STIME,NAME | grep -E ' com.android.settings\$'"
run "Dashboard view ids (uiautomator)" \
  "uiautomator dump /sdcard/Download/hexnext-ui.xml >/dev/null 2>&1; grep -oE 'resource-id=\"[^\"]+\"' /sdcard/Download/hexnext-ui.xml | sort | uniq -c | sort -rn | head -40"
run "Settings resource-loading errors (logcat)" \
  "logcat -d 2>/dev/null | grep -iE 'frro|Resources.*com.android.settings|Drawable.*settings|VectorDrawable|BitmapFactory' | tail -40"

echo "Wrote $OUT and $SHOT. Attach both."
