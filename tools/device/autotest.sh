#!/data/data/com.termux/files/usr/bin/bash
# Hex Next device lab runner (Termux, root). Runs test jobs from the private lab repo with no taps:
# installs a CI owner build, scans, applies, screenshots the real One UI screens and the app's own
# live preview of the same screens, runs collect.sh, and pushes everything back to the lab repo.
#
# One-time setup (Termux):
#   pkg install -y git gh jq imagemagick
#   gh auth login            # GitHub.com, HTTPS, log in as Bingblop
#   gh auth setup-git
#   termux-wake-lock         # optional: keeps the watcher alive with the screen off
#   curl -fsSLO https://raw.githubusercontent.com/Bingblop/Hex-Next/claude/project-thread-rje8bm/tools/device/autotest.sh
#   bash autotest.sh watch   # leave it running; Ctrl-C to stop
#
#   bash autotest.sh once    # run any pending jobs, then exit
#   bash autotest.sh local   # run the default job against the installed build (no install)
#
# Safety: a job is data, never shell. The runner only reads these fields: id, ref, run_id,
# artifact_sha256, steps, captures, modes, theme (JSON), confirm. The APK comes only from a
# successful run of Bingblop/Hex-Next whose artifact digest matches the job, and pm only updates
# the owner build in place when it carries the same release key.
set -u
PHASE=""   # "after-" for the captures taken after a System UI restart (step restartui)
LAB_REPO="${LAB_REPO:-Bingblop/hexnext-device-lab}"
APP_REPO=Bingblop/Hex-Next
# The only ref scripts are downloaded from before they run as root. A job's own "ref" is
# logged but never used for code, so a job cannot point the runner at another branch.
# Switch this to main once the Studio branch is merged.
TRUSTED_REF=claude/project-thread-rje8bm
PKG=com.hexnext.themer.owner
HOOK="$PKG/com.hexnext.themer.autotest.AutotestActivity"
LAB="${LAB_DIR:-$HOME/hexnext-device-lab}"
POLL="${POLL:-30}"

log() {
  printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"
  [ -n "${RES:-}" ] && [ -d "$RES" ] && printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*" >>"$RES/runner.log"
  return 0
}
sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1
  else toybox sha256sum "$1" | cut -d' ' -f1; fi
}
root() { su -c "$1"; }
need() { command -v "$1" >/dev/null 2>&1 || { echo "missing $1: pkg install -y $2"; exit 1; }; }

app_dir() { echo "/data/data/$PKG/files"; }

# ---- screen helpers -------------------------------------------------------------------------
wake() {
  root "input keyevent KEYCODE_WAKEUP" >/dev/null 2>&1
  root "svc power stayon true" >/dev/null 2>&1
  sleep 1
}
locked() { root "dumpsys window" 2>/dev/null | grep -qE 'mDreamingLockscreen=true|isKeyguardShowing=true|mShowingLockscreen=true'; }
home() { root "cmd statusbar collapse; input keyevent KEYCODE_HOME" >/dev/null 2>&1; sleep 1; }

# shot <name>: a screenshot (JPEG when ImageMagick is installed) plus the view tree of the screen.
shot() {
  local name="$1" png="$RES/shots/$1.png"
  root "screencap -p" >"$png" 2>/dev/null
  if command -v magick >/dev/null 2>&1 && [ -s "$png" ]; then
    magick "$png" -resize 50% -quality 82 "$RES/shots/$name.jpg" && rm -f "$png"
  fi
  root "uiautomator dump /data/local/tmp/hexnext-ui.xml >/dev/null 2>&1; cat /data/local/tmp/hexnext-ui.xml; rm -f /data/local/tmp/hexnext-ui.xml" \
    >"$RES/ui/$name.xml" 2>/dev/null
}

# tap_desc <regex>: taps the centre of the first node whose content-desc matches.
tap_desc() {
  local xml bounds try
  for try in 1 2 3; do
    xml=$(root "uiautomator dump /data/local/tmp/hexnext-ui.xml >/dev/null 2>&1 && cat /data/local/tmp/hexnext-ui.xml" 2>/dev/null)
    [ -n "$xml" ] && break
    sleep 1
  done
  bounds=$(printf '%s' "$xml" | tr '>' '\n' | grep -iE "content-desc=\"($1)\"" | head -1 | sed -n 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]".*/\1 \2 \3 \4/p')
  [ -n "$bounds" ] || return 1
  set -- $bounds
  root "input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))"
}

# tap_text <regex>: taps the centre of the first node whose text matches.
tap_text() {
  local xml bounds
  xml=$(root "uiautomator dump /data/local/tmp/hexnext-ui.xml >/dev/null 2>&1 && cat /data/local/tmp/hexnext-ui.xml" 2>/dev/null)
  bounds=$(printf '%s' "$xml" | tr '>' '\n' | grep -E " text=\"($1)\"" | head -1 | sed -n 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]".*/\1 \2 \3 \4/p')
  [ -n "$bounds" ] || return 1
  set -- $bounds
  root "input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))"
}

# ---- network ------------------------------------------------------------------------------
online() { curl -fsS -m 8 -o /dev/null https://api.github.com/zen 2>/dev/null; }

# A soft reboot can leave Wi-Fi and mobile data dead until a full reboot: when GitHub is not
# reachable, reset networking as root (airplane mode on/off, Wi-Fi off/on) and wait for it.
ensure_network() {
  online && return 0
  # Only right after a soft reboot: an ordinary bad signal must never toggle airplane mode.
  local at; at=$(cat "$HOME/.hexnext-softreboot-at" 2>/dev/null || echo 0)
  if [ $(( $(date +%s) - at )) -gt 3600 ]; then sleep 20; online; return; fi
  local round
  for round in 1 2; do
    log "no connection; resetting networking (round $round)"
    root "cmd connectivity airplane-mode enable; sleep 4; cmd connectivity airplane-mode disable" >/dev/null 2>&1
    sleep 8
    root "svc wifi disable; sleep 3; svc wifi enable" >/dev/null 2>&1
    local waited=0
    while [ $waited -lt 60 ]; do
      sleep 5; waited=$((waited + 5))
      online && { log "connection back"; return 0; }
    done
  done
  log "still no connection"
  return 1
}

# push_lab: pushes the lab checkout, retrying with a network reset in between.
push_lab() {
  local try tries=4
  [ "$PHASE" = "reboot-" ] && tries=2   # a full reboot follows a failure there; do not wait long
  for try in $(seq 1 $tries); do
    (cd "$LAB" && git pull -q --rebase && git push -q) 2>>"$HOME/.hexnext-lab-pull.err" && return 0
    ensure_network
    sleep $((try * 10))
  done
  return 1
}

# Unpushed results (a push that failed before a reboot): push them first, root or not.
push_pending() {
  [ -d "$LAB/.git" ] || return 0
  local ahead; ahead=$(cd "$LAB" && git fetch -q 2>/dev/null; git rev-list --count '@{u}..HEAD' 2>/dev/null || echo 0)
  [ "${ahead:-0}" -gt 0 ] || return 0
  log "pushing $ahead saved result commit(s)"
  (cd "$LAB" && git stash -q -u 2>/dev/null; git pull -q --rebase && git push -q; r=$?; git stash pop -q 2>/dev/null; exit $r) \
    && log "saved results pushed" || log "saved results not pushed yet"
}

# After a soft reboot the phone's networking can stay dead until a full reboot. When the results
# cannot be pushed, reboot the phone. The results are committed; a one-shot root boot script
# (KernelSU service.d, root comes back by itself at boot) starts this watcher again, which pushes
# them before anything else, starts Shizuku and goes on with the queue. A Termux:Boot script is
# left as a fallback; the watcher lock keeps it to one watcher.
full_reboot() {
  local me; me=$(readlink -f "$0" 2>/dev/null || echo "$0")
  cat <<SVC | su -c "mkdir -p /data/adb/service.d && cat > /data/adb/service.d/hexnext-lab.sh && chmod 755 /data/adb/service.d/hexnext-lab.sh"
#!/system/bin/sh
# Hex Next device lab: start the Termux watcher once after a full reboot, then remove this script.
rm -f "\$0"
until [ "\$(getprop sys.boot_completed)" = 1 ]; do sleep 5; done
sleep 90
am start-foreground-service --user 0 -n com.termux/com.termux.app.RunCommandService -a com.termux.RUN_COMMAND \\
  --es com.termux.RUN_COMMAND_PATH /data/data/com.termux/files/usr/bin/bash \\
  --esa com.termux.RUN_COMMAND_ARGUMENTS "$me,watch" \\
  --es com.termux.RUN_COMMAND_WORKDIR /data/data/com.termux/files/home \\
  --ez com.termux.RUN_COMMAND_BACKGROUND false
SVC
  mkdir -p "$HOME/.termux/boot"
  cat >"$HOME/.termux/boot/hexnext-lab.sh" <<BOOT
#!/data/data/com.termux/files/usr/bin/sh
# Hex Next device lab (fallback): start the watcher after a full reboot, then remove this script.
rm -f "\$0"
sleep 120
exec bash "$me" watch
BOOT
  chmod 700 "$HOME/.termux/boot/hexnext-lab.sh"
  log "full reboot to bring networking back; the watcher starts again by itself"
  sleep 3
  root "svc power reboot || reboot"
  exit 0
}

# ---- app hook -------------------------------------------------------------------------------
# hook <cmd> [am extras...]: starts the owner build's test hook and waits for its .done file.
hook() {
  local cmd="$1"; shift
  local done_file="$(app_dir)/autotest/$cmd.done" waited=0 limit=900
  [ "$cmd" = preview ] && limit=45
  root "rm -f $done_file"
  root "am start -W -n $HOOK --es cmd $cmd $*" >/dev/null 2>&1
  while [ $waited -lt $limit ]; do
    if root "test -f $done_file"; then
      root "cat $done_file"; echo; return 0
    fi
    sleep 2; waited=$((waited + 2))
  done
  echo "{\"cmd\":\"$cmd\",\"ok\":false,\"detail\":\"timed out after ${limit}s\"}"
  return 1
}

restart_shizuku() {
  local dir lib
  dir=$(root "pm path moe.shizuku.privileged.api" | sed -n 's/^package://p' | head -1)
  dir="${dir%/base.apk}"
  for abi in arm64 arm64-v8a; do
    lib="$dir/lib/$abi/libshizuku.so"
    if root "test -x $lib"; then
      log "restarting Shizuku as root"
      if [ -n "${RES:-}" ] && [ -d "$RES" ]; then root "$lib" >"$RES/shizuku-start.txt" 2>&1
      else root "$lib" >/dev/null 2>&1; fi
      sleep 5
      return 0
    fi
  done
  log "Shizuku starter not found; start Shizuku by hand if the hook reports it not ready"
  return 1
}

# ---- steps ----------------------------------------------------------------------------------
step_install() {
  local run_id want info branch concl aid got zip="$HOME/hexnext-apk/owner.zip"
  run_id=$(jq -r '.run_id // empty' "$JOB"); want=$(jq -r '.artifact_sha256 // empty' "$JOB")
  [ -n "$run_id" ] && [ -n "$want" ] || { log "install: job has no run_id/artifact_sha256"; return 1; }
  # Retried: a flaky connection is far more likely than a token problem.
  local try
  for try in 1 2 3 4 5; do
    info=$(gh api "repos/$APP_REPO/actions/runs/$run_id" --jq '.head_branch + " " + .conclusion' 2>>"$RES/runner.log") && break
    log "install: cannot reach GitHub (try $try of 5)"; info=""; sleep $((try * 10))
  done
  [ -n "$info" ] || { log "install: cannot read run $run_id (no connection, or the token needs Actions: read on $APP_REPO)"; return 1; }
  set -- $info; branch=$1; concl=${2:-}
  [ "$concl" = success ] || { log "install: run $run_id is '$concl'"; return 1; }
  rm -rf "$HOME/hexnext-apk"; mkdir -p "$HOME/hexnext-apk"
  aid=$(gh api "repos/$APP_REPO/actions/runs/$run_id/artifacts" --jq '.artifacts[] | select(.name=="HexNext-Owner-Release") | .id' 2>>"$RES/runner.log")
  if [ -n "$aid" ] && gh api "repos/$APP_REPO/actions/artifacts/$aid/zip" >"$zip" 2>>"$RES/runner.log" && [ -s "$zip" ]; then
    log "install: downloaded artifact $aid through the API"
  else
    log "install: API download failed (artifact '$aid'); trying nightly.link"
    curl -fsSL --retry 3 -o "$zip" "https://nightly.link/$APP_REPO/actions/runs/$run_id/HexNext-Owner-Release.zip" 2>>"$RES/runner.log" \
      || { log "install: download failed"; return 1; }
  fi
  got=$(sha256_of "$zip")
  [ "$got" = "$want" ] || { log "install: sha256 $got != job $want; NOT installing"; return 1; }
  (cd "$HOME/hexnext-apk" && unzip -o -q owner.zip) 2>>"$RES/runner.log" || { log "install: unzip failed"; return 1; }
  local apk; apk=$(find "$HOME/hexnext-apk" -name '*universal*.apk' | head -1)
  [ -n "$apk" ] || apk=$(find "$HOME/hexnext-apk" -name '*.apk' | head -1)
  [ -n "$apk" ] || { log "install: no apk in the zip"; return 1; }
  local size; size=$(wc -c <"$apk" | tr -d ' ')
  log "install: run $run_id ($branch) sha ok, installing $(basename "$apk")"
  su -c "pm install -r -S $size" <"$apk" >"$RES/install.txt" 2>&1
  grep -q Success "$RES/install.txt" || { log "install failed: $(tail -1 "$RES/install.txt")"; return 1; }
  root "dumpsys package $PKG | grep -E 'versionName|versionCode|lastUpdateTime' | head -3" >>"$RES/install.txt"
  restart_shizuku
}

step_theme() {
  jq -e '.theme | type == "object"' "$JOB" >/dev/null 2>&1 || return 0
  local uid; uid=$(root "stat -c %u $(app_dir)")
  log "theme: writing the job's theme.json"
  root "am force-stop $PKG"
  jq -c '.theme' "$JOB" | su -c "cat > $(app_dir)/theme.json && chown $uid:$uid $(app_dir)/theme.json && restorecon $(app_dir)/theme.json"
}

step_scan() { log "scan"; hook scan >>"$RES/hook.jsonl"; }

step_apply() {
  local confirm=false; [ "$(jq -r '.confirm // false' "$JOB")" = true ] && confirm=true
  log "apply (confirm=$confirm)"
  hook apply --ez confirm "$confirm" >>"$RES/hook.jsonl"
  # Samsung re-enables its theme overlays ~20 s after a change and the app re-asserts after that.
  sleep 30
  save_app_state "apply"
}

# The app's own record of what it applied: the stored theme, the restore copy, the apply report and
# its log (apply-<n>/).
save_app_state() {
  local n=1 d log
  while [ -d "$RES/$1-$n" ]; do n=$((n + 1)); done
  d="$RES/$1-$n"; mkdir -p "$d"
  root "cat $(app_dir)/theme.json" >"$d/theme.json" 2>/dev/null
  root "cat $(app_dir)/applied-theme.json" >"$d/applied-theme.json" 2>/dev/null
  root "cat $(app_dir)/reports/apply-last.json" >"$d/apply-last.json" 2>/dev/null
  root "cat $(app_dir)/autotest/apply-ui-log.txt" >"$d/apply-ui-log.txt" 2>/dev/null
  root "cat $(app_dir)/autotest/progress.txt" >"$d/progress.txt" 2>/dev/null
  root "logcat -d -v time" 2>/dev/null | grep -iE "hexnext|AndroidRuntime|ActivityManager.*$PKG|Freecess.*$PKG|ActivityManager.*(kill|froze|freez)" \
    | grep -v "OverlayManager" | tail -8000 >"$d/app-logcat.txt"
  log=$(jq -r '.logFile // empty' "$d/apply-last.json" 2>/dev/null)
  case "$log" in /data/*) root "cat '$log'" >"$d/apply-log.txt" 2>/dev/null ;; esac
  find "$d" -type f -empty -delete 2>/dev/null
}

# Samsung's palette overlays and the live system palette, for the compiler's shade reference.
step_dumps() {
  log "dumps"
  mkdir -p "$RES/dumps"
  local overlay
  for overlay in SemWT_G_MonetPalette SemWT_MonetPalette SemWT_android; do
    root "cmd overlay dump android:$overlay" >"$RES/dumps/$overlay.txt" 2>&1
  done
  root "cmd overlay list --user 0" >"$RES/dumps/overlay-list.txt" 2>&1
  # Every colour name each scanned app ships (small text lists), to find what paints dialogs.
  mkdir -p "$RES/dumps/names"
  root "cd $(app_dir)/scan && for d in */; do for t in color drawable; do [ -f \"\$d/names-\$t.txt\" ] && cp \"\$d/names-\$t.txt\" \"/data/local/tmp/hexnext-names-\${d%/}-\$t.txt\"; done; done; true"
  root "cd /data/local/tmp && tar -cf - hexnext-names-*.txt 2>/dev/null; rm -f hexnext-names-*.txt" | tar -xf - -C "$RES/dumps/names" 2>/dev/null
  root "settings get secure theme_customization_overlay_packages" >"$RES/dumps/theme_customization.txt" 2>&1
  local shade
  for shade in system_accent1_100 system_accent1_200 system_accent1_500 system_accent1_600 system_neutral1_900; do
    root "cmd overlay lookup android android:color/$shade" >>"$RES/dumps/lookups.txt" 2>&1
  done
}

# Restarts System UI (not a reboot: Termux and the runner keep running), waits for it, and
# captures every screen again as after-<mode>-…, to show what only lands after a restart.
step_restartui() {
  log "restart System UI"
  root "pkill -f com.android.systemui" >/dev/null 2>&1
  local waited=0
  while [ $waited -lt 60 ]; do
    sleep 3; waited=$((waited + 3))
    root "pidof com.android.systemui" >/dev/null 2>&1 && break
  done
  sleep 12
  PHASE="after-"; step_capture; PHASE=""
}

# Soft reboot (framework only: the kernel, and so KernelSU root, stays). Termux dies with the
# framework, so a root helper outside the app processes does the restart, waits for System UI
# and for the phone to be unlocked, then relaunches this runner (Termux RunCommandService) with
# `resume`, which runs the job's remaining steps with captures named reboot-<mode>-….
step_softreboot() {
  local rest="$1" props="$HOME/.termux/termux.properties" helper=/data/local/tmp/hexnext-softreboot.sh
  mkdir -p "$HOME/.termux"
  grep -q '^allow-external-apps *= *true' "$props" 2>/dev/null || echo 'allow-external-apps = true' >>"$props"
  printf '%s\n%s\n' "$JOB" "$rest" >"$HOME/.hexnext-resume"
  date +%s >"$HOME/.hexnext-softreboot-at"
  log "soft reboot; remaining steps: $rest (unlock the phone when it comes back)"
  ( cd "$LAB" && git add -A "results/$(jq -r .id "$JOB")" && git commit -qm "Before soft reboot" >/dev/null 2>&1 )
  local me; me=$(readlink -f "$0" 2>/dev/null || echo "$0")
  cat <<HELPER | su -c "cat > $helper && chmod 700 $helper"
#!/system/bin/sh
sleep 3
setprop ctl.restart zygote
i=0
while [ \$i -lt 900 ]; do
  sleep 5; i=\$((i + 5))
  pidof com.android.systemui >/dev/null || continue
  dumpsys window | grep -qE 'mDreamingLockscreen=true|isKeyguardShowing=true|mShowingLockscreen=true' && continue
  break
done
sleep 15
am start-foreground-service --user 0 -n com.termux/com.termux.app.RunCommandService -a com.termux.RUN_COMMAND \
  --es com.termux.RUN_COMMAND_PATH /data/data/com.termux/files/usr/bin/bash \
  --esa com.termux.RUN_COMMAND_ARGUMENTS "$me,resume" \
  --es com.termux.RUN_COMMAND_WORKDIR /data/data/com.termux/files/home \
  --ez com.termux.RUN_COMMAND_BACKGROUND false
HELPER
  su -c "nohup setsid sh $helper >/data/local/tmp/hexnext-softreboot.log 2>&1 &"
  exit 0
}

# What Android's overlay manager holds right now: fabricated overlay files on disk, the overlay
# list, Hex Next's and Samsung's entries, and the overlay/idmap lines of the log since boot.
# Each call writes a new numbered snapshot (overlaystate-1, -2 …).
step_overlaystate() {
  local n=1
  while [ -d "$RES/overlaystate-$n" ]; do n=$((n + 1)); done
  local d="$RES/overlaystate-$n"; mkdir -p "$d"
  log "overlay state $n"
  {
    date; echo "uptime: $(root "cut -d' ' -f1 /proc/uptime")"; getprop sys.boot_completed
    echo "system_server: $(root "pidof system_server")"
  } >"$d/when.txt" 2>&1
  root "ls -la /data/resource-cache/" >"$d/resource-cache.txt" 2>&1
  root "cmd overlay list --user 0" >"$d/overlay-list.txt" 2>&1
  root "grep -c . /data/system/overlays.xml; grep -iE 'fabricated|HexNext|SemWT' /data/system/overlays.xml | head -200" >"$d/overlays-xml.txt" 2>&1
  root "logcat -d -b main -b system -b crash | grep -iE 'overlay|idmap|fabricat|frro' | tail -400" >"$d/logcat-overlay.txt" 2>&1
  root "settings get secure theme_customization_overlay_packages" >"$d/theme_customization.txt" 2>&1
  root "pidof moe.shizuku.privileged.api shizuku_server; ps -A | grep -i shizuku" >"$d/shizuku.txt" 2>&1
}

# A minimal fabricated overlay made with Android's own shell command (owner com.android.shell,
# never rewritten): shows whether any shell-owned fabricated overlay survives a soft reboot.
step_fabtest() {
  log "fabricate probe overlay"
  root "cmd overlay fabricate --target android --name HexNextProbe android:color/system_accent1_10 0x1c 0xffff00ff" >"$RES/fabtest.txt" 2>&1
  root "cmd overlay enable --user 0 com.android.shell:HexNextProbe" >>"$RES/fabtest.txt" 2>&1
  root "cmd overlay list --user 0 | grep -i HexNextProbe" >>"$RES/fabtest.txt" 2>&1
}

step_shizuku() { restart_shizuku; }

# Waits (up to 10 min) for Hex Next to put its overlays back by itself after a restart
# (ThemeRestorer), logging how long it took.
step_waitrestore() {
  local waited=0 n=0
  log "waiting for Hex Next to restore the theme"
  while [ $waited -lt 600 ]; do
    n=$(root "cmd overlay list --user 0" | grep -c ':HexNext_')
    [ "$n" -gt 0 ] && break
    sleep 10; waited=$((waited + 10))
  done
  # The overlays appear one app at a time (about 4 s each): wait for the app's finish line.
  # Stops early when the app crashed, or when the overlay count has not moved for 90 s (a manual
  # apply finished, or the restore stopped without its finish line).
  local last=-1 still=0 since; since=$(date +'%m-%d %H:%M:%S.000')
  while [ $waited -lt 900 ]; do
    root "logcat -d -s HexNextRestore" 2>/dev/null | grep -q 'Restore finished' && break
    if root "logcat -d -b crash -T '$since'" 2>/dev/null | grep -q "Process: $PKG"; then log "restore: Hex Next crashed"; break; fi
    n=$(root "cmd overlay list --user 0" | grep -c ':HexNext_')
    if [ "$n" = "$last" ]; then still=$((still + 10)); else still=0; last=$n; fi
    # One app with many pack drawables can take a few minutes on its own.
    [ $still -ge 240 ] && { log "restore: overlay count steady at $n"; break; }
    sleep 10; waited=$((waited + 10))
  done
  n=$(root "cmd overlay list --user 0" | grep -c ':HexNext_')
  log "restore: $n Hex Next overlays after ${waited}s"
  save_app_state "restore"
  echo "$n overlays after ${waited}s" >"$RES/restore.txt"
  root "logcat -d -s HexNextRestore" >>"$RES/restore.txt" 2>&1
}

# Resolved values of the job's "lookups" (["<package>:color/<name>", …]) as the phone sees them
# now, with every overlay applied: lookup-1.txt, lookup-2.txt … one per call.
step_lookup() {
  local n=1 ref pkg
  while [ -f "$RES/lookup-$n.txt" ]; do n=$((n + 1)); done
  log "lookup $n"
  jq -r '.lookups // [] | .[]' "$JOB" | while read -r ref; do
    case "$ref" in *[!A-Za-z0-9._:/-]*|'') continue ;; esac
    pkg=${ref%%:*}
    printf '%s = %s\n' "$ref" "$(root "cmd overlay lookup $pkg $ref" 2>&1 | tr -d '\n')"
  done >"$RES/lookup-$n.txt"
}

# The resource table of each app in the job's "arsc" list (gzipped, arsc/<pkg>.arsc.gz), to see
# which colours and drawables an app outside the targets (Gmail, Gboard) exposes. Split APKs: the
# base APK only.
step_arsc() {
  local pkg apk
  mkdir -p "$RES/arsc"
  jq -r '.arsc // [] | .[]' "$JOB" | while read -r pkg; do
    case "$pkg" in *[!A-Za-z0-9._]*|'') continue ;; esac
    # base.apk, or the app's only APK when it is not split (Samsung Keyboard's is named after it).
    apk=$(root "pm path $pkg" | sed -n 's/^package://p' | grep -m1 'base.apk')
    [ -n "$apk" ] || apk=$(root "pm path $pkg" | sed -n 's/^package://p' | head -1)
    [ -n "$apk" ] || { log "arsc: $pkg not installed"; continue; }
    root "cat '$apk'" >"$HOME/.hexnext-arsc.apk" 2>/dev/null
    unzip -p "$HOME/.hexnext-arsc.apk" resources.arsc 2>/dev/null | gzip -9 >"$RES/arsc/$pkg.arsc.gz"
    # The compiled XML too (manifest, colour selectors, layouts, drawables), so the lab host can
    # read what a FAB or widget is tinted with (aapt2 dump xmltree). No images.
    local xdir="$HOME/.hexnext-arsc-xml"
    rm -rf "$xdir"; mkdir -p "$xdir"
    unzip -Z1 "$HOME/.hexnext-arsc.apk" 2>/dev/null | grep -E '^(AndroidManifest\.xml|res/.*\.xml)$' >"$xdir.list"
    if [ -s "$xdir.list" ]; then
      (cd "$xdir" && tr '\n' '\0' <"$xdir.list" | xargs -0 unzip -q -o "$HOME/.hexnext-arsc.apk" 2>/dev/null)
      tar -czf "$RES/arsc/$pkg.xml.tgz" -C "$xdir" . 2>/dev/null
    fi
    rm -rf "$xdir" "$xdir.list"
    root "dumpsys package $pkg" 2>/dev/null | grep -m3 -E 'versionName|versionCode' >"$RES/arsc/$pkg.version.txt"
    rm -f "$HOME/.hexnext-arsc.apk"
    log "arsc: $pkg $(du -k "$RES/arsc/$pkg.arsc.gz" | cut -f1) KB"
  done
}

# Selects the job's "pack" (a catalogue id, downloaded when missing) for the next apply.
step_pack() {
  local id; id=$(jq -r '.pack // empty' "$JOB")
  case "$id" in ''|*[!a-z0-9_-]*) log "pack: no valid pack id"; return 1 ;; esac
  log "pack $id"
  hook pack --es id "$id" >>"$RES/hook.jsonl"
  tail -1 "$RES/hook.jsonl" | grep -q '"ok":true'
}

step_wait() { log "wait 180 s"; sleep 180; }

step_remove() { log "remove"; hook remove >>"$RES/hook.jsonl"; }

# The apps in the job's "bare" list with their Hex overlay switched off (bare-<mode>-real-app-<pkg>),
# to tell the app's own look from the theme's. The overlays are switched back on afterwards.
step_bare() {
  local pkg ov list="" caps=""
  for pkg in $(jq -r '.bare // [] | .[]' "$JOB"); do
    case "$pkg" in *[!A-Za-z0-9._]*|'') continue ;; esac
    ov="com.android.shell:HexNext_$(echo "$pkg" | tr . _)"
    root "cmd overlay disable --user 0 $ov" >>"$RES/bare.txt" 2>&1 && list="$list $ov"
    caps="$caps app:$pkg"
  done
  [ -n "$caps" ] || return 0
  log "bare:$caps"
  sleep 2
  PHASE="bare-"; step_capture "$caps"; PHASE=""
  for ov in $list; do root "cmd overlay enable --user 0 $ov" >>"$RES/bare.txt" 2>&1; done
}

# capture_real <what>: drives One UI to the screen and screenshots it.
capture_real() {
  case "$1" in
    settings) root "am start -W -a android.settings.SETTINGS" >/dev/null 2>&1; sleep 3; shot "$PHASE$MODE-real-settings"
              root "input swipe 540 1800 540 600 300"; sleep 1; shot "$PHASE$MODE-real-settings-2" ;;
    qs)       root "cmd statusbar expand-settings"; sleep 2; shot "$PHASE$MODE-real-qs" ;;
    shade)    root "cmd notification post -S bigtext -t 'Hex Next test' hexnext_lab 'Checks the notification card colours against the preview.'" >/dev/null 2>&1
              root "cmd statusbar expand-notifications"; sleep 2; shot "$PHASE$MODE-real-shade" ;;
    power)    root "cmd statusbar expand-settings"; sleep 2
              if tap_desc 'Power[^"]*|Power off[^"]*|Power menu'; then sleep 2; shot "$PHASE$MODE-real-power"
              else log "power: no power button found in the quick panel"; fi
              # The power menu is a system dialog: back twice and close system dialogs, or it
              # stays over the preview hook that follows.
              root "input keyevent KEYCODE_BACK; sleep 1; input keyevent KEYCODE_BACK; am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS" >/dev/null 2>&1 ;;
    myfiles)  root "monkey -p com.sec.android.app.myfiles -c android.intent.category.LAUNCHER 1" >/dev/null 2>&1; sleep 3; shot "$PHASE$MODE-real-myfiles" ;;
    dialogs)  # A Settings alert dialog (App info > Force stop, cancelled) and a popup menu.
              root "am start -W -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:com.android.chrome" >/dev/null 2>&1; sleep 3
              if tap_text 'Force stop'; then sleep 2; shot "$PHASE$MODE-real-dialog"; tap_text 'Cancel' || root "input keyevent KEYCODE_BACK"; fi
              root "am start -W -a android.settings.SETTINGS" >/dev/null 2>&1; sleep 3
              if tap_desc 'More options'; then sleep 2; shot "$PHASE$MODE-real-popup"; root "input keyevent KEYCODE_BACK"; fi ;;
    keyboard) # Samsung Keyboard over a text field (Settings search), then closed.
              root "am start -W -a android.settings.SETTINGS" >/dev/null 2>&1; sleep 3
              if tap_desc 'Search[^"]*' || tap_text 'Search'; then sleep 3; shot "$PHASE$MODE-real-keyboard"; fi
              root "input keyevent KEYCODE_BACK; sleep 1; input keyevent KEYCODE_BACK" ;;
    dialer)   root "monkey -p com.samsung.android.dialer -c android.intent.category.LAUNCHER 1" >/dev/null 2>&1; sleep 3; shot "$PHASE$MODE-real-dialer" ;;
    app:*)    # Any installed app by package (app:com.sec.android.app.sbrowser): its launcher screen;
              # app:<pkg>/<activity> opens that screen (apps without a launcher entry, Device Care).
              local spec="${1#app:}" pkg="${1#app:}"; pkg="${pkg%%/*}"
              if root "pm path $pkg" 2>/dev/null | grep -q package:; then
                local how
                if [ "$spec" != "$pkg" ]; then how=$(root "am start -n $spec" 2>&1)
                else how=$(root "monkey -p $pkg -c android.intent.category.LAUNCHER 1" 2>&1); fi
                if echo "$how" | grep -qiE 'No activities found|Error|does not exist'; then
                  log "capture: $spec did not open; its activities are in activities-$pkg.txt"
                  root "dumpsys package $pkg" 2>/dev/null | grep -oE "$pkg/[A-Za-z0-9_.\$]+" | sort -u >"$RES/activities-$pkg.txt"
                fi
                sleep 4
                shot "$PHASE$MODE-real-app-$pkg"
                root "am force-stop $pkg" >/dev/null 2>&1
              else log "capture: $pkg not installed"; fi ;;
  esac
  home
}

capture_preview() {
  local tab try
  case "$1" in
    settings) tab=SETTINGS ;; qs) tab=QUICK_PANEL ;; shade) tab=NOTIFICATIONS ;;
    power) tab=POWER_MENU ;; myfiles) tab=MY_FILES ;; dialer) tab=DIALER ;; keyboard) tab=KEYBOARD ;; *) return 0 ;;
  esac
  local dark=true; [ "$MODE" = light ] && dark=false
  # The hook's activity can lose the race with a closing panel: retry once, and only screenshot
  # when the hook is really the screen on top.
  for try in 1 2; do
    if hook preview --es tab "$tab" --ez dark "$dark" >>"$RES/hook.jsonl" &&
      root "dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity'" | grep -q AutotestActivity; then
      shot "$PHASE$MODE-preview-$1"; break
    fi
    log "preview $1: the hook was not on screen (try $try)"
    home; root "am force-stop $PKG"; sleep 2
  done
  root "am force-stop $PKG"
}

step_capture() {
  local orig captures modes
  captures=${1:-$(jq -r '(.captures // ["settings","qs","shade","power","myfiles"]) | join(" ")' "$JOB")}
  modes=$(jq -r '(.modes // ["dark"]) | join(" ")' "$JOB")
  orig=$(root "cmd uimode night" | sed 's/.*: //')
  case "$orig" in yes|no|auto) ;; *) orig=auto ;; esac
  wake
  if locked; then log "capture: the phone is locked; unlock it and keep it awake"; echo locked >"$RES/capture-warning.txt"; fi
  for MODE in $modes; do
    case "$MODE" in dark) root "cmd uimode night yes" ;; light) root "cmd uimode night no" ;; *) continue ;; esac
    sleep 3
    for c in $captures; do log "capture $MODE $c"; capture_real "$c"; capture_preview "$c"; done
  done
  root "cmd uimode night $orig" >/dev/null 2>&1
  root "cmd notification cancel hexnext_lab 0" >/dev/null 2>&1
  root "svc power stayon false" >/dev/null 2>&1
}

step_collect() {
  local ref; ref=$(jq -r '.ref // "-"' "$JOB" | tr -cd 'A-Za-z0-9._/-' | cut -c1-100)
  log "collect (job ref $ref, script from $TRUSTED_REF)"
  gh api "repos/$APP_REPO/contents/tools/device/collect.sh?ref=$TRUSTED_REF" --jq .content | base64 -d >"$RES/.collect.sh" || return 1
  PKG=$PKG sh "$RES/.collect.sh" "$RES" >"$RES/collect-run.txt" 2>&1
  rm -f "$RES/.collect.sh"
  # Samsung's SemWT mappings (the scan's dumps and the ones the app remembers), for the bundled
  # One UI fallback used when a phone has no palette theme.
  mkdir -p "$RES/semwt"
  root "cd $(app_dir) && tar -cf - scan/semwt semwt-known 2>/dev/null" | tar -xf - -C "$RES/semwt" 2>/dev/null
  root "cmd overlay list --user 0 | grep -i semwt" >"$RES/semwt/overlays.txt" 2>&1
}

# ---- jobs -----------------------------------------------------------------------------------
run_job() {
  JOB="$1"
  local id; id=$(jq -r .id "$JOB")
  case "$id" in ''|*[!A-Za-z0-9._-]*) log "skipping job with bad id: $JOB"; return ;; esac
  RES="$LAB/results/$id"; mkdir -p "$RES/shots" "$RES/ui"
  log "job $id"
  if [ "$PHASE" = "reboot-" ]; then
    root "cat /data/local/tmp/hexnext-softreboot.log" >"$RES/softreboot-helper.txt" 2>/dev/null
  fi
  local started; started=$(date +%s)
  local steps; steps=${2:-$(jq -r '(.steps // ["scan","apply","capture","collect"]) | join(" ")' "$JOB")}
  local failed="" s rest
  set -- $steps
  while [ $# -gt 0 ]; do
    s=$1; shift
    case "$s" in
      install|theme|scan|apply|remove|capture|collect|dumps|restartui|overlaystate|wait|fabtest|shizuku|waitrestore|lookup|arsc|pack|bare) "step_$s" || failed="$failed $s" ;;
      softreboot) step_softreboot "$*" ;;
    esac
    [ "$s" = install ] && [ -n "$failed" ] && break
  done
  # Any app crash during the job: the app's own record and the system crash log.
  root "cat $(app_dir)/last-crash.txt" >"$RES/app-crash.txt" 2>/dev/null; [ -s "$RES/app-crash.txt" ] || rm -f "$RES/app-crash.txt"
  root "logcat -d -b crash" >"$RES/crash-log.txt" 2>/dev/null; [ -s "$RES/crash-log.txt" ] || rm -f "$RES/crash-log.txt"
  jq -n --arg id "$id" --arg failed "${failed# }" --arg model "$(getprop ro.product.model)" \
    --arg oneui "$(getprop ro.build.version.oneui)" --argjson secs $(( $(date +%s) - started )) \
    '{id:$id, ok:($failed==""), failed:$failed, model:$model, oneui:$oneui, seconds:$secs}' >"$RES/status.json"
  ( cd "$LAB" && git add -A "results/$id" && git commit -qm "Results for $id" ) >/dev/null 2>&1
  if push_lab; then log "job $id pushed"
  else
    log "job $id: push failed; results kept in $RES"
    # Only after this job's own soft reboot (that is what breaks networking until a reboot).
    [ "$PHASE" = "reboot-" ] && full_reboot
  fi
}

pending_jobs() {
  for j in "$LAB"/jobs/*.json; do
    [ -f "$j" ] || continue
    local id; id=$(jq -r .id "$j" 2>/dev/null)
    [ -n "$id" ] && [ ! -f "$LAB/results/$id/status.json" ] && echo "$j"
  done
}

# A pull that fails (an interrupted rebase, results left uncommitted after a failed push, a
# diverged branch): finish any half-done git operation, commit the waiting results, retry, and
# as a last resort back the results up and reset to the lab repo, then push the results again.
repair_lab() {
  log "lab pull failed: $(tr '\n' ' ' <"$HOME/.hexnext-lab-pull.err" | cut -c1-200); repairing"
  (
    cd "$LAB" || exit 1
    git rebase --abort >/dev/null 2>&1; git merge --abort >/dev/null 2>&1
    git add -A results >/dev/null 2>&1
    git diff --cached --quiet || git commit -qm "Results kept by the runner" >/dev/null 2>&1
    # Results must be committed before anything is set aside; they are never stashed.
    [ -z "$(git status --porcelain -- results)" ] || exit 1
    # Anything else changed in the checkout (never ours to keep) is set aside so the pull can run.
    git stash push -u -q -m "set aside by the runner" -- . ':(exclude)results' >/dev/null 2>&1
    if git pull -q --rebase 2>>"$HOME/.hexnext-lab-pull.err"; then git push -q >/dev/null 2>&1; exit 0; fi
    git rebase --abort >/dev/null 2>&1
    local backup="$HOME/hexnext-lab-backup-$(date +%Y%m%d-%H%M%S)"
    # Reset only once the backup exists and the remote is reachable.
    cp -r results "$backup" || exit 1
    git fetch -q origin 2>>"$HOME/.hexnext-lab-pull.err" || exit 1
    git reset -q --hard origin/HEAD 2>/dev/null || git reset -q --hard origin/main || exit 1
    # The runner's captures win over the remote copies.
    cp -r "$backup"/. results/ || exit 1
    git add -A results && { git diff --cached --quiet || git commit -qm "Results restored by the runner" >/dev/null 2>&1; }
    git push -q >/dev/null 2>&1 || exit 1
    echo "reset to the lab repo; results backed up in $backup"
  ) && log "lab repaired" || log "lab repair failed; results left in place; see $HOME/.hexnext-lab-pull.err"
}

# Commits need an identity; use the signed-in account's no-reply address when git has none.
lab_identity() {
  (cd "$LAB" && git var GIT_AUTHOR_IDENT >/dev/null 2>&1) && return
  local login uid
  login=$(gh api user --jq .login 2>/dev/null); uid=$(gh api user --jq .id 2>/dev/null)
  [ -n "$login" ] && (cd "$LAB" && git config --local user.name "$login" && git config --local user.email "$uid+$login@users.noreply.github.com")
}

sync_lab() {
  if [ -d "$LAB/.git" ]; then
    lab_identity
    (cd "$LAB" && git pull -q --rebase 2>"$HOME/.hexnext-lab-pull.err") || repair_lab
  else gh repo clone "$LAB_REPO" "$LAB" -- -q || { log "cannot clone $LAB_REPO"; exit 1; }; fi
  lab_identity
}

# Re-runs itself when the branch has a newer runner (the watcher then never needs a re-download).
self_update() {
  local me new
  me=$(readlink -f "$0" 2>/dev/null || echo "$0"); new="$me.new"
  gh api "repos/$APP_REPO/contents/tools/device/autotest.sh?ref=$TRUSTED_REF" --jq .content 2>/dev/null | base64 -d >"$new" 2>/dev/null
  if [ -s "$new" ] && bash -n "$new" 2>/dev/null && ! cmp -s "$new" "$me"; then
    mv "$new" "$me"; log "runner updated; restarting"; exec bash "$me" "$@"
  fi
  rm -f "$new"
}

need jq jq; need gh gh; need git git
push_pending
# After a reboot root comes back by itself, but only after a while: wait up to 15 minutes for it.
if ! su -c true >/dev/null 2>&1; then
  echo "waiting for root (su) to come back"
  w=0
  while [ $w -lt 900 ] && ! su -c true >/dev/null 2>&1; do sleep 15; w=$((w + 15)); done
  su -c true >/dev/null 2>&1 || { echo "root (su) is required"; exit 1; }
fi
# Earlier runners turned Wi-Fi sign-in (captive portal) detection off around soft reboots: put the
# saved value back once and remove their helper.
su -c 'f=/data/local/tmp/hexnext-captive-mode; if [ -f $f ]; then v=$(cat $f); if [ "$v" = null ] || [ -z "$v" ]; then settings delete global captive_portal_mode; else settings put global captive_portal_mode "$v"; fi; rm -f $f; fi; rm -f /data/local/tmp/hexnext-wifi.sh /data/local/tmp/hexnext-wifi.log /data/local/tmp/hexnext-wifi-ui.xml' >/dev/null 2>&1
case "${1:-watch}" in
  local)
    LAB="${LAB_DIR:-$HOME/hexnext-device-lab}"; mkdir -p "$LAB/results"
    tmp="$HOME/.hexnext-local-job.json"
    echo "{\"id\":\"local-$(date +%Y%m%d-%H%M%S)\"}" >"$tmp"
    run_job "$tmp" ;;
  resume)
    # Back from a soft reboot: finish the saved job, then keep watching.
    if [ -f "$HOME/.hexnext-resume" ]; then
      job=$(sed -n 1p "$HOME/.hexnext-resume"); rest=$(sed -n 2p "$HOME/.hexnext-resume")
      rm -f "$HOME/.hexnext-resume"
      PHASE="reboot-"; log "resuming after soft reboot: $rest"
      ensure_network
      [ -f "$job" ] && run_job "$job" "$rest"
      PHASE=""
    fi
    exec bash "$0" watch ;;
  once)  sync_lab; for j in $(pending_jobs); do run_job "$j"; done ;;
  watch)
    # A soft reboot that has not been resumed yet (the watcher was started by hand first).
    [ -f "$HOME/.hexnext-resume" ] && exec bash "$0" resume
    # One watcher at a time (a boot script may start a second one).
    lock="$HOME/.hexnext-watch.pid"
    if [ -f "$lock" ] && [ "$(cat "$lock")" != "$$" ] && kill -0 "$(cat "$lock")" 2>/dev/null \
       && grep -q autotest "/proc/$(cat "$lock")/cmdline" 2>/dev/null; then
      echo "another watcher is running (pid $(cat "$lock"))"; exit 0
    fi
    echo $$ >"$lock"
    rm -f "$HOME/.termux/boot/hexnext-lab.sh"; su -c "rm -f /data/adb/service.d/hexnext-lab.sh" >/dev/null 2>&1
    # Shizuku does not start by itself after a reboot.
    if ! root "pidof shizuku_server" >/dev/null 2>&1; then restart_shizuku; fi
    log "watching $LAB_REPO every ${POLL}s (Ctrl-C to stop)"
    while true; do
      self_update "$@"
      online || ensure_network
      sync_lab
      for j in $(pending_jobs); do run_job "$j"; done
      sleep "$POLL"
    done ;;
  *) echo "usage: bash autotest.sh [watch|once|local]"; exit 1 ;;
esac
