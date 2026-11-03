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
LAB_REPO="${LAB_REPO:-Bingblop/hexnext-device-lab}"
APP_REPO=Bingblop/Hex-Next
PKG=com.hexnext.themer.owner
HOOK="$PKG/com.hexnext.themer.autotest.AutotestActivity"
LAB="${LAB_DIR:-$HOME/hexnext-device-lab}"
POLL="${POLL:-30}"

log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }
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
  local xml bounds
  xml=$(root "uiautomator dump /data/local/tmp/hexnext-ui.xml >/dev/null 2>&1; cat /data/local/tmp/hexnext-ui.xml")
  bounds=$(printf '%s' "$xml" | tr '>' '\n' | grep -iE "content-desc=\"($1)\"" | head -1 | sed -n 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]".*/\1 \2 \3 \4/p')
  [ -n "$bounds" ] || return 1
  set -- $bounds
  root "input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))"
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
      root "$lib" >"$RES/shizuku-start.txt" 2>&1
      sleep 5
      return 0
    fi
  done
  log "Shizuku starter not found; start Shizuku by hand if the hook reports it not ready"
  return 1
}

# ---- steps ----------------------------------------------------------------------------------
step_install() {
  local run_id want info branch concl aid got
  run_id=$(jq -r '.run_id // empty' "$JOB"); want=$(jq -r '.artifact_sha256 // empty' "$JOB")
  [ -n "$run_id" ] && [ -n "$want" ] || { log "install: job has no run_id/artifact_sha256"; return 1; }
  info=$(gh api "repos/$APP_REPO/actions/runs/$run_id" --jq '.head_branch + " " + .conclusion + " " + .path') || return 1
  set -- $info; branch=$1; concl=$2
  [ "$concl" = success ] || { log "install: run $run_id is $concl"; return 1; }
  aid=$(gh api "repos/$APP_REPO/actions/runs/$run_id/artifacts" --jq '.artifacts[] | select(.name=="HexNext-Owner-Release") | .id')
  [ -n "$aid" ] || { log "install: run $run_id has no owner artifact (expired?)"; return 1; }
  rm -rf "$HOME/hexnext-apk"; mkdir -p "$HOME/hexnext-apk"
  gh api "repos/$APP_REPO/actions/artifacts/$aid/zip" >"$HOME/hexnext-apk/owner.zip" || return 1
  got=$(sha256sum "$HOME/hexnext-apk/owner.zip" | cut -d' ' -f1)
  [ "$got" = "$want" ] || { log "install: sha256 $got != job $want; NOT installing"; return 1; }
  (cd "$HOME/hexnext-apk" && unzip -o -q owner.zip) || return 1
  local apk; apk=$(find "$HOME/hexnext-apk" -name '*universal*.apk' | head -1)
  [ -n "$apk" ] || apk=$(find "$HOME/hexnext-apk" -name '*.apk' | head -1)
  local size; size=$(wc -c <"$apk" | tr -d ' ')
  log "install: run $run_id ($branch) sha ok, installing"
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
}

step_remove() { log "remove"; hook remove >>"$RES/hook.jsonl"; }

# capture_real <what>: drives One UI to the screen and screenshots it.
capture_real() {
  case "$1" in
    settings) root "am start -W -a android.settings.SETTINGS" >/dev/null 2>&1; sleep 3; shot "$MODE-real-settings"
              root "input swipe 540 1800 540 600 300"; sleep 1; shot "$MODE-real-settings-2" ;;
    qs)       root "cmd statusbar expand-settings"; sleep 2; shot "$MODE-real-qs" ;;
    shade)    root "cmd notification post -S bigtext -t 'Hex Next test' hexnext_lab 'Checks the notification card colours against the preview.'" >/dev/null 2>&1
              root "cmd statusbar expand-notifications"; sleep 2; shot "$MODE-real-shade" ;;
    power)    root "cmd statusbar expand-settings"; sleep 2
              if tap_desc 'Power[^"]*|Power off[^"]*|Power menu'; then sleep 2; shot "$MODE-real-power"
              else log "power: no power button found in the quick panel"; fi
              root "input keyevent KEYCODE_BACK" ;;
    myfiles)  root "monkey -p com.sec.android.app.myfiles -c android.intent.category.LAUNCHER 1" >/dev/null 2>&1; sleep 3; shot "$MODE-real-myfiles" ;;
    dialer)   root "monkey -p com.samsung.android.dialer -c android.intent.category.LAUNCHER 1" >/dev/null 2>&1; sleep 3; shot "$MODE-real-dialer" ;;
  esac
  home
}

capture_preview() {
  local tab
  case "$1" in
    settings) tab=SETTINGS ;; qs) tab=QUICK_PANEL ;; shade) tab=NOTIFICATIONS ;;
    power) tab=POWER_MENU ;; myfiles) tab=MY_FILES ;; dialer) tab=DIALER ;; *) return 0 ;;
  esac
  local dark=true; [ "$MODE" = light ] && dark=false
  hook preview --es tab "$tab" --ez dark "$dark" >>"$RES/hook.jsonl"
  shot "$MODE-preview-$1"
  root "am force-stop $PKG"
}

step_capture() {
  local orig captures modes
  captures=$(jq -r '(.captures // ["settings","qs","shade","power","myfiles"]) | join(" ")' "$JOB")
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
  local ref; ref=$(jq -r '.ref // "claude/project-thread-rje8bm"' "$JOB")
  log "collect ($ref)"
  gh api "repos/$APP_REPO/contents/tools/device/collect.sh?ref=$ref" --jq .content | base64 -d >"$RES/.collect.sh" || return 1
  PKG=$PKG sh "$RES/.collect.sh" "$RES" >"$RES/collect-run.txt" 2>&1
  rm -f "$RES/.collect.sh"
}

# ---- jobs -----------------------------------------------------------------------------------
run_job() {
  JOB="$1"
  local id; id=$(jq -r .id "$JOB")
  case "$id" in ''|*[!A-Za-z0-9._-]*) log "skipping job with bad id: $JOB"; return ;; esac
  RES="$LAB/results/$id"; mkdir -p "$RES/shots" "$RES/ui"
  log "job $id"
  local started; started=$(date +%s)
  local steps; steps=$(jq -r '(.steps // ["scan","apply","capture","collect"]) | join(" ")' "$JOB")
  local failed=""
  for s in $steps; do
    case "$s" in install|theme|scan|apply|remove|capture|collect) "step_$s" || failed="$failed $s" ;; esac
    [ "$s" = install ] && [ -n "$failed" ] && break
  done
  jq -n --arg id "$id" --arg failed "${failed# }" --arg model "$(getprop ro.product.model)" \
    --arg oneui "$(getprop ro.build.version.oneui)" --argjson secs $(( $(date +%s) - started )) \
    '{id:$id, ok:($failed==""), failed:$failed, model:$model, oneui:$oneui, seconds:$secs}' >"$RES/status.json"
  ( cd "$LAB" && git add -A "results/$id" && git commit -qm "Results for $id" && git push -q ) \
    && log "job $id pushed" || log "job $id: push failed; results kept in $RES"
}

pending_jobs() {
  for j in "$LAB"/jobs/*.json; do
    [ -f "$j" ] || continue
    local id; id=$(jq -r .id "$j" 2>/dev/null)
    [ -n "$id" ] && [ ! -f "$LAB/results/$id/status.json" ] && echo "$j"
  done
}

sync_lab() {
  if [ -d "$LAB/.git" ]; then (cd "$LAB" && git pull -q --rebase) || log "lab pull failed"
  else gh repo clone "$LAB_REPO" "$LAB" -- -q || { log "cannot clone $LAB_REPO"; exit 1; }; fi
}

need jq jq; need gh gh; need git git
su -c true >/dev/null 2>&1 || { echo "root (su) is required"; exit 1; }
case "${1:-watch}" in
  local)
    LAB="${LAB_DIR:-$HOME/hexnext-device-lab}"; mkdir -p "$LAB/results"
    tmp="$HOME/.hexnext-local-job.json"
    echo "{\"id\":\"local-$(date +%Y%m%d-%H%M%S)\"}" >"$tmp"
    run_job "$tmp" ;;
  once)  sync_lab; for j in $(pending_jobs); do run_job "$j"; done ;;
  watch)
    log "watching $LAB_REPO every ${POLL}s (Ctrl-C to stop)"
    while true; do
      sync_lab
      for j in $(pending_jobs); do run_job "$j"; done
      sleep "$POLL"
    done ;;
  *) echo "usage: bash autotest.sh [watch|once|local]"; exit 1 ;;
esac
