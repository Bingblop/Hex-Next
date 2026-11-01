#!/system/bin/sh
# Hex Next device diagnostics (read-only).
# Collects what the theme engine needs to know about a Samsung One UI device:
# One UI version, Samsung's SemWT_* wallpaper-theme overlays, root/Shizuku state,
# and one sample .frro file. Changes nothing on the device.
#
# Usage (Termux or adb shell):  sh diag.sh            -> writes hexnext-diag.txt
#                               sh diag.sh /sdcard/Download
set -u
OUT_DIR="${1:-$PWD}"
OUT="$OUT_DIR/hexnext-diag.txt"
FRRO_COPY="$OUT_DIR/hexnext-sample.frro"

have_su=0
if command -v su >/dev/null 2>&1 && su -c id >/dev/null 2>&1; then have_su=1; fi

run() {
  # run "label" "command": try plainly, then via su if it failed or printed nothing.
  label="$1"; shift
  cmd="$*"
  printf '\n### %s\n$ %s\n' "$label" "$cmd" >>"$OUT"
  res=$(sh -c "$cmd" 2>&1); code=$?
  if [ $code -ne 0 ] || [ -z "$res" ]; then
    if [ $have_su -eq 1 ]; then
      res=$(su -c "$cmd" 2>&1); code=$?
      printf '(via su, exit %s)\n' "$code" >>"$OUT"
    else
      printf '(exit %s, no su)\n' "$code" >>"$OUT"
    fi
  fi
  printf '%s\n' "$res" >>"$OUT"
}

: >"$OUT"
printf 'Hex Next diagnostics %s\n' "$(date)" >>"$OUT"

run "Build" "getprop ro.build.version.oneui; getprop ro.build.version.sem; getprop ro.build.version.sep; getprop ro.build.version.sdk; getprop ro.build.version.release; getprop ro.product.model; getprop ro.build.display.id; getprop ro.build.version.security_patch"
run "Identity" "id; command -v su; su -c id"
run "Shizuku" "pm list packages | grep -iE 'shizuku|rikka'; pidof shizuku_server; ps -A | grep -iE 'shizuku|rikka' | grep -v grep"
run "Overlay list" "cmd overlay list --user current"
run "Fabricated overlay files" "ls -la /data/overlays; find /data/overlays -name '*.frro' -exec ls -la {} \\;"
run "Resource cache" "ls /data/resource-cache | head -60"
run "Package paths" "for p in android com.android.systemui com.android.settings com.sec.android.app.launcher com.samsung.android.honeyboard com.samsung.android.dialer com.samsung.android.incallui com.samsung.android.app.contacts com.sec.android.app.myfiles com.sec.android.gallery3d com.sec.android.app.clockpackage com.sec.android.app.popupcalculator com.samsung.android.calendar com.samsung.android.lool com.samsung.android.app.aodservice com.samsung.android.app.smartcapture com.samsung.android.app.notes com.samsung.android.app.reminder com.samsung.android.messaging; do echo \"\$p -> \$(pm path \$p 2>/dev/null | head -1)\"; done"
run "Uninstalled (debloated) packages" "pm list packages -u | sort > /data/local/tmp/hx_all.txt 2>/dev/null || pm list packages -u | sort > \$PWD/hx_all.txt; pm list packages | sort > /data/local/tmp/hx_inst.txt 2>/dev/null || pm list packages | sort > \$PWD/hx_inst.txt; comm -23 /data/local/tmp/hx_all.txt /data/local/tmp/hx_inst.txt 2>/dev/null || comm -23 \$PWD/hx_all.txt \$PWD/hx_inst.txt"

settings_semwt=$(sh -c "cmd overlay list --user current" 2>/dev/null | grep -i semwt | grep -i 'com.android.settings' | head -1 | sed 's/^\[[ x]\] //; s/^--- //')
[ -z "$settings_semwt" ] && [ $have_su -eq 1 ] && settings_semwt=$(su -c "cmd overlay list --user current" 2>/dev/null | grep -i semwt | grep -i 'com.android.settings' | head -1 | sed 's/^\[[ x]\] //; s/^--- //')
if [ -n "$settings_semwt" ]; then
  run "Dump $settings_semwt" "cmd overlay dump $settings_semwt | head -300"
fi
run "Overlay dump android (first 120 lines)" "cmd overlay dump android | head -120"
run "Lookup sample" "cmd overlay lookup --user current com.android.settings com.android.settings:color/sec_widget_round_and_bgcolor; cmd overlay lookup --user current android android:color/system_accent1_500"

# Copy one SemWT .frro for the parser team (binary, small).
frro=$(sh -c "find /data/overlays -name '*.frro' 2>/dev/null | head -1")
[ -z "$frro" ] && [ $have_su -eq 1 ] && frro=$(su -c "find /data/overlays -name '*.frro' 2>/dev/null | head -1")
if [ -n "$frro" ]; then
  if [ $have_su -eq 1 ]; then su -c "cp '$frro' '$FRRO_COPY' && chmod 644 '$FRRO_COPY'"; else cp "$frro" "$FRRO_COPY" 2>/dev/null; fi
  printf '\n### Sample frro\n%s -> %s\n' "$frro" "$FRRO_COPY" >>"$OUT"
fi

echo "Wrote $OUT"
[ -f "$FRRO_COPY" ] && echo "Sample overlay file: $FRRO_COPY"
