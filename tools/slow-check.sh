#!/usr/bin/env bash
# The slow suites run locally (CI runs them on every push, so this is opt-in): the suites listed in tools/slow-suites.txt (`gradlew slowTest`) run after a commit, queued: one run at a
# time for the whole repository (every checkout and worktree of it shares one queue and one lock), each run checked out
# at its commit, its result kept under that commit's hash. tools/git-hooks/post-commit queues each commit when
# PLYMELD_SLOW_CHECK=1; `failed-for` reads the newest finished run among a commit's ancestors.
#
# usage:
#   tools/slow-check.sh enqueue <commit>   queue a commit (its hash) and start the runner if none is running
#   tools/slow-check.sh run                the runner: take the queue in order until it is empty (one at a time, locked)
#   tools/slow-check.sh status [n]         the queue, the run in progress and the last n results (default 10)
#   tools/slow-check.sh failed-for <rev>   the newest finished run among <rev>'s ancestors: nothing when it passed or
#                                          none has finished, else its failed test classes, one a line (ALL: unknown)
#
# State: ${PLYMELD_SLOW_CHECK_DIR:-~/.cache/plymeld-slow-check}/<repository>/: queue, lock, results/<hash>.status
# (queued, running, pass, fail), .log (the build's output), .failed (the failed classes), history.log (one line a run),
# worktree/ (where runs are checked out). Optional: PLYMELD_SLOW_CHECK_NOTIFY, a command run as `cmd <hash> <pass|fail>`
# after each run (e.g. a desktop notification).
set -euo pipefail
unset GIT_INDEX_FILE GIT_DIR GIT_WORK_TREE GIT_PREFIX
here="$(cd "$(dirname "$0")" && pwd)"
top="$(git -C "$here" rev-parse --show-toplevel)"
common="$(git -C "$top" rev-parse --path-format=absolute --git-common-dir)"
state="${PLYMELD_SLOW_CHECK_DIR:-${XDG_CACHE_HOME:-$HOME/.cache}/plymeld-slow-check}/${common//\//_}"
mkdir -p "$state/results"
queue="$state/queue"; touch "$queue"

# the queue's own lock (appending and popping), separate from the runner's
with_queue() { ( flock 8; "$@" ) 8>"$state/queue.lock"; }
push() { echo "$1" >> "$queue"; echo queued > "$state/results/$1.status"; }
pop() { head -n 1 "$queue"; sed -i '1d' "$queue"; }

run_one() {
  local hash="$1" wt="$state/worktree" log="$state/results/$1.log" ok=0 start=$SECONDS
  if [ -e "$wt/.git" ]; then
    git -C "$wt" checkout -q --detach --force "$hash"
    git -C "$wt" clean -q -f -d
  else
    git -C "$top" worktree prune
    git -C "$top" worktree add -q --detach --force "$wt" "$hash"
  fi
  # a commit from before the split (no tools/slow-suites.txt) has no slow suites: nothing to run, nothing failed
  if [ ! -f "$wt/tools/slow-suites.txt" ]; then
    echo pass > "$state/results/$hash.status"; : > "$state/results/$hash.failed"
    echo "$(date -Is) ${hash:0:12} none 0s (no slow suites at this commit)" >> "$state/history.log"
    return
  fi
  echo running > "$state/results/$hash.status"
  "$wt/gradlew" -p "$wt" slowTest --continue --console=plain > "$log" 2>&1 || ok=$?
  : > "$state/results/$hash.failed"
  local result=pass
  if [ "$ok" != 0 ]; then
    result=fail
    # the failed classes, from the run's own reports; none found (it did not compile, or failed before testing): ALL
    for f in "$wt"/*/build/test-results/slowTest/*.xml; do
      [ -e "$f" ] || continue
      if grep -q '<failure' "$f"; then sed -n 's/.*<testsuite name="\([^"]*\)".*/\1/p' "$f" | head -n 1 >> "$state/results/$hash.failed"; fi
    done
    [ -s "$state/results/$hash.failed" ] || echo ALL > "$state/results/$hash.failed"
  fi
  echo "$result" > "$state/results/$hash.status"
  echo "$(date -Is) ${hash:0:12} $result $((SECONDS - start))s" >> "$state/history.log"
  if [ -n "${PLYMELD_SLOW_CHECK_NOTIFY:-}" ]; then $PLYMELD_SLOW_CHECK_NOTIFY "$hash" "$result" || true; fi
}

case "${1:-}" in
  enqueue)
    hash="$(git -C "$top" rev-parse --verify "${2:?a commit}^{commit}")"
    with_queue push "$hash"
    setsid nohup "$here/slow-check.sh" run > /dev/null 2>&1 < /dev/null &
    echo "plymeld slow check: ${hash:0:12} queued (tools/slow-check.sh status)" >&2 ;;
  run)
    exec 9>"$state/lock"
    flock -n 9 || exit 0
    while true; do
      hash="$(with_queue pop)"
      [ -n "$hash" ] || break
      run_one "$hash"
    done
    # released before a last look: a commit queued while this runner was finishing would otherwise wait for the next one
    exec 9>&-
    if [ -s "$queue" ]; then exec "$here/slow-check.sh" run; fi ;;
  status)
    n="${2:-10}"
    echo "queue: $(wc -l < "$queue") commit(s)$( [ -s "$queue" ] && printf ' (next %s)' "$(head -c 12 "$queue")")"
    for s in "$state"/results/*.status; do [ -e "$s" ] && [ "$(cat "$s")" = running ] && echo "running: $(basename "$s" .status | head -c 12)"; done
    echo "last results (when, commit, result, time):"
    tail -n "$n" "$state/history.log" 2>/dev/null || echo "  none yet" ;;
  failed-for)
    for c in $(git -C "$top" rev-list --max-count=200 "${2:?a revision}"); do
      s="$state/results/$c.status"
      [ -e "$s" ] || continue
      case "$(cat "$s")" in
        pass) exit 0 ;;
        fail) cat "$state/results/$c.failed"; exit 0 ;;
      esac
    done ;;
  *) sed -n '9,17p' "$0" >&2; exit 2 ;;
esac
