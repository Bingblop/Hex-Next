#!/bin/bash
# Posts a device-lab job for a saved suite (tools/device/suites/<suite>.json) once the owner APK of
# a commit has built.
#
#   tools/device/post-job.sh <suite> [commit] [job-id] [jq-filter]
#
#   suite     restore, keyboard, dialogs-power, statusbar, smoke (or a path to a suite file)
#   commit    default: HEAD of the current checkout
#   job-id    default: the next free lab-NNN in the lab repository
#   jq-filter extra changes to the job, e.g. '.pack="apatite"' or '.modes=["light"]'
#
# Waits for the "Android Universal APK" run of the commit, pins the run id and the APK digest in the
# job (the phone verifies both before installing), and pushes jobs/<id>.json to the lab repository.
# The same suites run on any One UI version: names a phone does not have are reported as n/a by
# check_results.py, not as failures. Needs gh (authenticated) and jq.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
SUITE=${1:?suite}; SHA=${2:-$(git -C "$here" rev-parse HEAD)}; ID=${3:-}; EXTRA=${4:-.}
APP_REPO=${APP_REPO:-Bingblop/Hex-Next}
LAB=${LAB_DIR:-$HOME/workspace/hexnext-device-lab}
REF=${LAB_REF:-$(sed -n 's/^TRUSTED_REF=//p' "$here/autotest.sh")}
file=$SUITE; [ -f "$file" ] || file="$here/suites/$SUITE.json"
[ -f "$file" ] || { echo "no suite $SUITE"; exit 1; }

for _ in $(seq 1 120); do
  run=$(gh api "repos/$APP_REPO/actions/runs?head_sha=$SHA" \
    --jq '.workflow_runs[] | select(.name=="Android Universal APK") | "\(.id) \(.status) \(.conclusion)"' | head -1)
  set -- $run
  if [ "${2:-}" = completed ]; then
    [ "${3:-}" = success ] || { echo "run $1 finished: $3"; exit 1; }
    dig=$(gh api "repos/$APP_REPO/actions/runs/$1/artifacts" \
      --jq '.artifacts[] | select(.name=="HexNext-Owner-Release") | .digest' | sed 's/sha256://')
    [ -n "$dig" ] || { echo "run $1 has no owner APK"; exit 1; }
    git -C "$LAB" pull -q --rebase
    if [ -z "$ID" ]; then
      n=$(ls "$LAB/jobs" | sed -n 's/^lab-\([0-9]*\)\.json$/\1/p' | sort -n | tail -1)
      ID=$(printf 'lab-%03d' $(( 10#${n:-0} + 1 )))
    fi
    jq --arg id "$ID" --arg ref "$REF" --argjson run "$1" --arg sha "$dig" --arg suite "$(basename "$file" .json)" \
      'del(.about) | . + {id:$id, suite:$suite, ref:$ref, run_id:$run, artifact_sha256:$sha}' "$file" \
      | jq "$EXTRA" >"$LAB/jobs/$ID.json"
    git -C "$LAB" add "jobs/$ID.json"
    git -C "$LAB" commit -qm "Job $ID ($(basename "$file" .json), run $1)"
    git -C "$LAB" push -q
    echo "JOB_POSTED $ID suite=$(basename "$file" .json) run=$1"
    exit 0
  fi
  sleep 30
done
echo "timed out waiting for the APK build of $SHA"
exit 1
