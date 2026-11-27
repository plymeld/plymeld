#!/usr/bin/env bash
# Runs every plymeld command on small jobs from test-models/, with a built executable, and fails when one does not do its
# work: each case is held to its exit status and to what proves the work ran (a file written, a picture that decodes, a
# line of text). A command that needs a printer, a terminal, or a release feed runs as far as it can without one and is
# held to its refusal there (the printer at a host that does not exist, the feed at an address that does not exist).
#
# The release ships the command line and the window as GraalVM native images, which reach what native-image cannot see
# (the JDK's imaging and its JNI calls back into Java, reflection, resources) only through their reachability metadata;
# the command line's is plymeld-cli/src/main/resources/META-INF/native-image/com.plymeld/plymeld-cli, in its jar, where
# native-image finds it on every platform. The tests run on the JVM, so this is where a native build's gap shows.
#
#   check <plymeld executable> [--window <plymeld-ui executable>] [dir]
#     run every case with that executable (the native command line, or any launcher of it), and with --window the
#     window's executable as a command line (--version, a command, --help, update, an unknown argument refused, its own
#     --icons; it runs the command line beside it, so the window's executable is checked where it is packaged); exit 1
#     when any case fails. CI and the release run it on what they build.
#   trace [dir]
#     run every case on the JVM (`./gradlew :plymeld-cli:installDist` first) under GraalVM's tracing agent, merging what
#     they reach into the command line's metadata; once with a display and once without where there is one, so both of
#     AWT's paths are traced. Needs GRAALVM_HOME (a GraalVM for JDK 21). On Linux and on Windows (under Git's bash):
#     each platform's imaging reaches JNI members of its own, and the metadata holds both platforms'.
# [dir] holds the cases' files (default: a new temporary directory, removed after a passing check).
#
# Every command of `plymeld commands` has a case here (NativeSmokeCoverageTest holds the list to the command tree): a
# command added without one fails the build. A command that cannot run here at all goes in the ledger as
# `not_yet "<command>" "<reason>"`; none does today.
# (no -e: a case that fails is recorded and the rest still run)
set -uo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
# Paths handed to an executable in the platform's own form (on Windows, Git Bash's /d/... as D:/...), and nothing else
# of the arguments rewritten
win=""
if command -v cygpath > /dev/null 2>&1; then win=1; p() { cygpath -m "$1"; }; export MSYS2_ARG_CONV_EXCL='*'; else p() { printf '%s' "$1"; }; fi
usage(){ echo "usage: $0 check <plymeld executable> [--window <plymeld-ui executable>] [dir] | trace [dir]" >&2; exit 2; }
[ $# -ge 1 ] || usage
mode="$1"; shift
window=""
case "$mode" in
  check)
    [ $# -ge 1 ] || usage
    exe="$1"; shift
    if [ "${1:-}" = --window ]; then [ $# -ge 2 ] || usage; window="$2"; shift 2; fi
    [ -x "$exe" ] || { echo "native-smoke: $exe is not an executable" >&2; exit 2; }
    run=("$exe") ;;
  trace)
    graal="${GRAALVM_HOME:?set GRAALVM_HOME to a GraalVM for JDK 21 with native-image}"
    [ -z "$win" ] || graal="$(cygpath -u "$graal")"
    lib="$here/plymeld-cli/build/install/plymeld/lib"
    [ -d "$lib" ] || { echo "no $lib - run ./gradlew :plymeld-cli:installDist first" >&2; exit 1; }
    meta="$here/plymeld-cli/src/main/resources/META-INF/native-image/com.plymeld/plymeld-cli"
    mkdir -p "$meta"
    # the CLI's jars but JNA and the terminal library's JNA interface: on the JVM the terminal library (Mordant) would
    # reach the terminal through JNA, which a native image does not use (there it picks its own interface, with the
    # metadata it ships), so its calls stay out of the metadata
    sep=":"; [ -z "$win" ] || sep=";"
    cp=""; for j in "$lib"/*.jar; do case "${j##*/}" in jna-*|mordant-jvm-jna-*) ;; *) cp="$cp${cp:+$sep}$(p "$j")" ;; esac; done
    # a home of its own, with no font cache in it (~/.java/fonts): the JDK then asks the system's fontconfig through
    # JNI, as an executable does on a machine with no cache yet, so those calls are traced
    run=("$graal/bin/java" -XX:+IgnoreUnrecognizedVMOptions -XX:+UnlockExperimentalVMOptions -XX:-UseJVMCICompiler
      "-agentlib:native-image-agent=config-merge-dir=$(p "$meta")" "-Duser.home=$(p "$(mktemp -d)")" -cp "$cp" plymeld.cli.MainKt) ;;
  *) usage ;;
esac
keep=1
if [ $# -ge 1 ]; then work="$1"; mkdir -p "$work"; else work="$(mktemp -d)"; keep=0; fi
work="$(cd "$work" && pwd)"

models="$(p "$here/test-models")"
# a copy of the sample config: the calibration values commands write into it
mkdir -p "$work/config" && cp "$here/test-models/config/"0*.toml "$work/config/" || { echo "native-smoke: cannot set up $work" >&2; exit 2; }
# the config named by one glob, unquoted on the command line the executable gets (Git Bash does not quote an argument
# for a * in it), so a Windows executable that expanded wildcards itself would fail every case that reads the config.
# Under `trace` on Windows the files are named one by one: the JDK's launcher there expands an unquoted wildcard
# before the program reads it, which a run on the JVM cannot turn off.
config="$(p "$work/config")/0*.toml"
cf=(-cf "$config")
if [ "$mode" = trace ] && [ -n "$win" ]; then cf=(); for f in "$work/config/"0*.toml; do cf+=(-cf "$(p "$f")"); done; fi
W="$(p "$work")"
# plymeld's own folders (config, state, secrets) for these runs alone, never the user's
export PLYMELD_HOME="$W/home"
# each case stopped after 15 minutes where GNU timeout is there (not Windows' own timeout.exe, which only waits)
limit=(); if timeout --version > /dev/null 2>&1; then limit=(timeout 900); fi
failed=()
fail() { failed+=("$1"); echo "FAILED $1" >&2; }

# ok "<command>" NAME ARGS...: the command run with ARGS, its output in NAME.out and NAME.err, standard input empty (or
# the file $input names); it must exit 0
ok() {
  local name="$2"; shift 2
  local code=0
  "${limit[@]}" "${run[@]}" "$@" > "$work/$name.out" 2> "$work/$name.err" < "${input:-/dev/null}" || code=$?
  if [ $code -ne 0 ]; then fail "$name: exit $code: $(head -n 1 "$work/$name.err")"; return 1; fi
  echo "ok     $name"
}
# refused "<command>" NAME PATTERN ARGS...: the command must stop with exit 1 and say what PATTERN (an extended regular
# expression) matches on standard error: the refusal it gives where there is no printer, terminal, or feed
refused() {
  local name="$2" pattern="$3"; shift 3
  local code=0
  "${limit[@]}" "${run[@]}" "$@" > "$work/$name.out" 2> "$work/$name.err" < /dev/null || code=$?
  if [ $code -ne 1 ]; then fail "$name: exit $code where its refusal (exit 1) was expected: $(head -n 1 "$work/$name.err")"; return 1; fi
  grep -Eq "$pattern" "$work/$name.err" || { fail "$name: its refusal does not say /$pattern/: $(head -n 1 "$work/$name.err")"; return 1; }
  echo "ok     $name (refused as expected)"
}
# text NAME PATTERN: the case's standard output says what PATTERN matches
text() { grep -Eq "$2" "$work/$1.out" || fail "$1: its output does not say /$2/"; }
# file NAME FILE [PATTERN]: FILE was written, not empty (and holds PATTERN)
file() {
  [ -s "$2" ] || { fail "$1: no file $2"; return 0; }
  [ -z "${3:-}" ] || grep -Eq "$3" "$2" || fail "$1: $2 does not hold /$3/"
}
# picture NAME FILE: FILE is a picture (a PNG's signature, or an SVG's root element)
picture() {
  case "$2" in
    *.png) [ -s "$2" ] && [ "$(head -c 4 "$2" | tail -c 3)" = PNG ] && return 0 ;;
    *.svg) [ -s "$2" ] && grep -q "<svg" "$2" && return 0 ;;
  esac
  fail "$1: no picture $2"
}
# pictures "<command>" NAME ARGS...: `plymeld render` with ARGS into its own directory; every file it prints is a
# picture, and there is one
pictures() {
  local name="$2"; shift 2
  local n=0 f
  ok "plymeld render" "$name" render "$@" --print-files -o "$W/render/$name" || return 0
  while IFS= read -r f; do f="${f%$'\r'}"; [ -n "$f" ] || continue; n=$((n + 1)); picture "$name" "$f"; done < "$work/$name.out"
  [ $n -gt 0 ] || fail "$name: no file printed"
}
# not_yet "<command>" REASON: a command no case can run here, and why (the ledger NativeSmokeCoverageTest reads)
not_yet() { echo "not yet $1: $2"; }

run_all() {
  local f g="$W/holeblock.gcode" printer=(-cv hardware.machine.example-corexy.connection.api=moonraker -cv hardware.machine.example-corexy.connection.host=printer.invalid)
  ok "plymeld" version --version && text version "^plymeld version "
  ok "plymeld calibrate" calibrate-help calibrate --help && text calibrate-help "melt"
  ok "plymeld printer" printer-help printer --help && text printer-help "status"
  ok "plymeld investigate" investigate-help investigate --help && text investigate-help "predict"

  # the config, its schema, and the command model
  ok "plymeld config" config config "${cf[@]}" hardware.machine && text config "example-corexy"
  # on Windows, the glob as it is typed in cmd or PowerShell: the folder's own separators, unquoted
  if [ -n "$win" ] && [ "$mode" = check ]; then
    ok "plymeld config" config-windows-glob config -cf "$(cygpath -w "$work/config")\\0*.toml" hardware.machine && text config-windows-glob "example-corexy"
  fi
  ok "plymeld schema" schema schema -o "$W/schema.json" && file schema "$work/schema.json" '"\$schema"'
  printf '# settings\n\n%s\n' '<!-- generated by `plymeld schema --markdown`: everything below this line; do not edit by hand -->' > "$work/settings.md"
  ok "plymeld schema" schema-markdown schema --markdown "$W/settings.md" && file schema-markdown "$work/settings.md" "plan\.seam"
  ok "plymeld commands" commands commands --json -o "$W/commands.json" && file commands "$work/commands.json" '"commands"'
  ok "plymeld commands" commands-markdown commands --markdown "$W/reference" && file commands-markdown "$work/reference/slice.md"

  # slicing: the g-code the pictures are drawn from, with the machine's preview pictures; a plan; a slice from a g-code
  ok "plymeld slice" slice slice "${cf[@]}" -cv "hardware.machine.example-corexy.gcode.pictures=64x64" -m "$models/holeblock.stl" -o "$g" \
    && file slice "$work/holeblock.gcode" "^; thumbnail begin 64x64"
  ok "plymeld slice" slice-fuzzy slice "${cf[@]}" -cv "plan.faces.side.technique=fuzzy_skin" -m "$models/holeblock.stl" -o "$W/fuzzy.gcode" \
    && file slice-fuzzy "$work/fuzzy.gcode" "^;LAYER_CHANGE|^;Z:"
  ok "plymeld slice" slice-from slice "${cf[@]}" -m "$models/holeblock.stl" --from "$g" -o "$W/from.gcode" && file slice-from "$work/from.gcode"
  # the pre-print check's pictures: one for each layer with a finding (here, plastic laid twice, reported, not refused);
  # a slice with no finding draws none, which is named, not failed
  if ok "plymeld slice" slice-check slice "${cf[@]}" -cf "$(p "$here/tools/regress/benchy.plan.toml")" -m "$models/3DBenchy.stl" -o "$W/benchy.gcode"; then
    local checked=0
    for f in "$work/benchy.gcode.check"/*.png; do [ -e "$f" ] || continue; checked=1; picture slice-check "$f"; done
    [ $checked = 1 ] || echo "note   slice-check: the pre-print check found nothing to draw, so its pictures were not exercised" >&2
  fi
  ok "plymeld plan" plan plan "${cf[@]}" -m "$models/holeblock.stl" -o "$W/holeblock.plan.toml" && file plan "$work/holeblock.plan.toml"
  ok "plymeld layers" layers layers "$models/holeblock.stl" --quiet --png "$W/layers.png" --svg "$W/layers-svg" && picture layers "$work/layers.png"

  # the calibration plates (each but melt and flow with its page's picture), their readings and values; values written
  # into the copy of the config
  local plate
  for plate in melt flow; do
    ok "plymeld calibrate $plate" "calibrate-$plate" calibrate "$plate" "${cf[@]}" -o "$W/$plate.gcode" && file "calibrate-$plate" "$work/$plate.gcode"
  done
  for plate in filament first-layer motion ironing seams; do
    ok "plymeld calibrate $plate" "calibrate-$plate" calibrate "$plate" "${cf[@]}" -o "$W/$plate.gcode" && picture "calibrate-$plate" "$work/$plate.png"
  done
  ok "plymeld calibrate melt values" melt-values calibrate melt values 210=12 "${cf[@]}" && file melt-values "$work/config/01_FILAMENTS.toml" "melt plate"
  ok "plymeld calibrate flow values" flow-values calibrate flow values 210=0.98 "${cf[@]}" && file flow-values "$work/config/01_FILAMENTS.toml" "flow plate"
  ok "plymeld calibrate first-layer values" first-layer-values calibrate first-layer values 205=0.2,40 215=0.2,40 225=none "${cf[@]}" \
    --plate-gcode "$W/first-layer.gcode" && file first-layer-values "$work/config/01_FILAMENTS.toml" "first-layer plate"
  local kind grade
  for kind in motion:hidden ironing:good seams:faint; do
    grade="${kind#*:}"; kind="${kind%%:*}"
    ok "plymeld calibrate $kind readings" "$kind-readings" calibrate "$kind" readings "$W/$kind.gcode" -o "$W/$kind-readings.toml" \
      && file "$kind-readings" "$work/$kind-readings.toml" "\?"
    [ -f "$work/$kind-readings.toml" ] && sed "s/?/$grade/g" "$work/$kind-readings.toml" > "$work/$kind-graded.toml"
    ok "plymeld calibrate $kind values" "$kind-values" calibrate "$kind" values "$W/$kind-graded.toml" -o "$W/$kind-values.toml" \
      && file "$kind-values" "$work/$kind-values.toml"
  done
  mkdir -p "$work/plate"
  if ok "plymeld render-plate" render-plate render-plate "$W/seams.gcode" -o "$W/plate" --layers; then
    local any=0
    for f in "$work/plate"/*.png; do [ -e "$f" ] || continue; any=1; picture render-plate "$f"; done
    [ $any = 1 ] || fail "render-plate: no picture"
  fi

  # render: every view, color mode, mark, the sheet, a comparison, the outline and the check's marks, PNG and SVG
  pictures "plymeld render" render-layer-bare "$g" --layer 5 --no-annotations
  pictures "plymeld render" render-layer "$g" --layer 5,10 --show travels --show seams --show retractions --show starts --show tool-changes --layers-below 2
  for kind in feature tool speed flow width height fan temperature order layer-time; do
    pictures "plymeld render" "render-color-$kind" "$g" --layer 5 --color-by "$kind"
  done
  pictures "plymeld render" render-line-width "$g" --layer-at-z 1 --line-width center --only-feature "Outer wall" --others faint
  pictures "plymeld render" render-range "$g" --layer-range 2..6 --layer-step 2 --same-frame --sheet sheet.png
  pictures "plymeld render" render-flat "$g" --layer 3 --picture-size 200x200
  pictures "plymeld render" render-unresolved "$g" --layer 3 --picture-size 300x300 --allow-unresolved-lines
  pictures "plymeld render" render-around "$g" --layer 3 --around 135,135 --span 20
  pictures "plymeld render" render-cut-z "$g" --cut-z 2 --bed-window 110,110,160,160
  pictures "plymeld render" render-cut-side "$g" --cut-y 135 --cut-x 135 --cut-depth 0.5 --height-stretch 4
  pictures "plymeld render" render-cut-line "$g" --cut-line 115,120:155,150 --z-range 0..3
  pictures "plymeld render" render-cut-plane "$g" --cut-plane 135,135,2:1,1,0.5
  pictures "plymeld render" render-outside "$g" --outside front --outside top
  pictures "plymeld render" render-3d "$g" --3d --3d-from 20,45
  pictures "plymeld render" render-compare "$g" --layer 5 --compare-with "$W/fuzzy.gcode"
  pictures "plymeld render" render-outline-marks "$g" --layer 5 --outline-of "$models/holeblock.stl" --mark-bare --mark-laid-twice
  pictures "plymeld render" render-cut-outline "$g" --cut-y 135 --outline-of "$models/holeblock.stl"
  pictures "plymeld render" render-svg-layer "$g" --layer 5 --format svg --show travels
  pictures "plymeld render" render-svg-views "$g" --cut-y 135 --outside left --3d --format svg --sheet sheet.svg
  pictures "plymeld render" render-svg-compare "$g" --layer 5 --compare-with "$W/fuzzy.gcode" --outline-of "$models/holeblock.stl" --format svg
  ok "plymeld render" render-features render "$g" --list-features && text render-features "Outer wall"

  # editing config files, the journal of edits, migration
  printf '[plan.layer]\nwalls.count = "2"\n' > "$work/edit.toml"
  ok "plymeld edit" edit edit "$W/edit.toml" plan.seam=back --journal "$W/edits.journal" && file edit "$work/edit.toml" '^seam = "back"'
  ok "plymeld journal" journal journal "$W/edits.journal" && text journal "applied"
  ok "plymeld journal" journal-undo journal "$W/edits.journal" --undo && text journal-undo "undone"
  ok "plymeld journal" journal-redo journal "$W/edits.journal" --redo && text journal-redo "redone"
  ok "plymeld migrate" migrate migrate "$W/edit*.toml" --dry-run && text migrate "nothing to change"

  # the investigate commands, with the settings of another slicer's project (a test project, completed where it leaves
  # a setting out)
  local settings="$(p "$here/plymeld-config/src/test/resources/orca/mock-project.json"),$W/project-extra.json"
  printf '{"nozzle_temperature_range_low": "190", "nozzle_temperature_range_high": "240"}\n' > "$work/project-extra.json"
  ok "plymeld investigate slice" investigate-slice investigate slice "$models/holeblock.stl" --settings "$settings" -o "$W/investigate.gcode" \
    --set plan.technique.scarf_seam.step=0.5 --set plan.mechanism.motion.inner_walls.as_visible=false --set plan.mechanism.motion.melt.buffer=0.1 \
    --set plan.mechanism.motion.speed.factor=1 --set hardware.filament.retract.wipe.inward=false && file investigate-slice "$work/investigate.gcode"
  ok "plymeld investigate plan" investigate-plan investigate plan "$models/holeblock.stl" --settings "$settings" && text investigate-plan "^\[pose\]"
  ok "plymeld investigate check" investigate-check investigate check "$g" --settings "$settings" && text investigate-check "PASS"
  ok "plymeld investigate config" investigate-config investigate config plan.layer.walls.count --settings "$settings" && text investigate-config "walls\.count"
  ok "plymeld investigate predict" investigate-predict investigate predict "$g" --machine qidi-q2 && text investigate-predict "^predicted: "

  # the printer: a saved answer read through the protocol; everything else at a host that does not exist, refused
  # where it would reach the printer; a credential written to a secrets file of these runs
  ok "plymeld printer status" printer-status printer status "${cf[@]}" "${printer[@]}" \
    --from-response "$(p "$here/plymeld-printer/src/test/resources/moonraker/objects-query-standby.json")" && text printer-status "^status: idle"
  ok "plymeld printer loadout" printer-loadout printer loadout "${cf[@]}" --api flashforge-http \
    --from-response "$(p "$here/plymeld-printer/src/test/resources/flashforge/creator5-detail-2026-10-09.json")" && text printer-loadout "^tool 1: "
  local verb
  for verb in watch files pause resume cancel; do
    refused "plymeld printer $verb" "printer-$verb" "could not reach printer\.invalid" printer "$verb" "${cf[@]}" "${printer[@]}"
  done
  ok "plymeld printer clear" printer-clear printer clear "${cf[@]}" "${printer[@]}" && text printer-clear "nothing to clear"
  refused "plymeld printer camera" printer-camera "could not reach printer\.invalid" printer camera -o "$W/frame.jpg" "${cf[@]}" "${printer[@]}" \
    -cv hardware.machine.example-corexy.connection.camera.url=http://printer.invalid/snapshot
  refused "plymeld printer send" printer-send "could not reach printer\.invalid" printer send "$g" "${cf[@]}" "${printer[@]}"
  printf 'code-1\n' > "$work/credential.txt"
  input="$work/credential.txt" ok "plymeld printer credential" printer-credential printer credential check_code "${cf[@]}" \
    -cv hardware.machine.example-corexy.connection.api=flashforge-http -cv hardware.machine.example-corexy.connection.host=printer.invalid \
    -cv "hardware.machine.example-corexy.connection.credential.check_code.file=$W/secrets.toml" && file printer-credential "$work/secrets.toml" 'check_code = "code-1"'

  # a problem report; set-up, update checks, and the update, as far as they go without a terminal or a release feed
  ok "plymeld report" report report "${cf[@]}" -m "$models/holeblock.stl" -o "$W/report.zip" && file report "$work/report.zip"
  # (under `trace` this one fails: without JNA on the JVM the terminal library finds no terminal interface to ask with)
  refused "plymeld init" init "asks its questions in a terminal" init --folder "$W/init"
  ok "plymeld updates" updates-set updates --set notify && text updates-set "notify"
  [ -f "$work/home/config/updates.txt" ] && printf 'feed=https://updates.invalid/releases\n' >> "$work/home/config/updates.txt"
  ok "plymeld updates" updates updates && text updates "updates\.invalid"
  refused "plymeld updates" updates-check "updates\.invalid" updates --check
  refused "plymeld update" update "updates\.invalid|not an install plymeld can update" update
}

run_all
if [ "$mode" = trace ] && [ -n "${DISPLAY:-}${WAYLAND_DISPLAY:-}" ]; then
  echo "again without a display (AWT's headless path)"
  (failed=(); unset DISPLAY WAYLAND_DISPLAY; run_all; [ ${#failed[@]} -eq 0 ]) || failed+=("without a display: the failures above")
fi
if [ -n "$window" ]; then
  # the window's executable as the command line: what an install that starts only the window (the AppImage, the Windows
  # launcher) runs. A command, --version, and --help go to the command line beside it; an argument it does not know is
  # refused; its own --icons draws and writes the logo. None of these opens a window, so each is stopped after two
  # minutes where GNU timeout is there: a window opened fails the case instead of waiting for someone to close it.
  run=("$window")
  [ ${#limit[@]} -eq 0 ] || limit=(timeout 120)
  ok "plymeld-ui" window-version --version && text window-version "^plymeld version "
  ok "plymeld-ui" window-command config "${cf[@]}" hardware.machine && text window-command "example-corexy"
  ok "plymeld-ui" window-help --help && text window-help "^Usage: plymeld " && text window-help "^Window options:"
  # `update` is the install's own command line's, never the window's refusal (the window's finder points to its bar)
  refused "plymeld-ui" window-update "updates\.invalid|not an install plymeld can update" update
  refused "plymeld-ui" window-unknown "no such command or window option: --no-such-option" --no-such-option
  ok "plymeld-ui" window-icons --icons "$W/icons" && picture window-icons "$work/icons/plymeld-256.png"
fi

if [ ${#failed[@]} -gt 0 ]; then
  echo "native-smoke: ${#failed[@]} failure(s), the cases' files in $work:" >&2
  printf '  %s\n' "${failed[@]}" >&2
  exit 1
fi
echo "native-smoke: every case passed"
[ $keep = 1 ] || rm -rf "$work"
