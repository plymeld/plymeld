#!/usr/bin/env bash
# One command to regenerate a calibration plate from this checkout: installs plymeld (Gradle's installDist, a few seconds
# when nothing changed), runs `plymeld calibrate <kind>` with the config files named in PLYMELD_CONFIG or --config,
# and with --render draws it too (`plymeld render-plate --iso --layers`, the pictures beside the g-code).
#
# usage: tools/plate.sh <kind> [--config 'FILE|GLOB[:FILE|GLOB...]'] [--render] -o PLATE.gcode [any other `plymeld calibrate` option]
#   e.g. PLYMELD_CONFIG="$HOME/.plymeld/*.toml" tools/plate.sh melt --filament pla-generic --plate pei -o melt.gcode --render
# PLYMELD_CONFIG / --config: config files or globs, ':'-separated, each passed to plymeld as -cf (globs unexpanded: plymeld
# expands them). Nothing is sent to a printer.
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
[ $# -ge 1 ] || { sed -n '6,7p' "$0" | sed 's/^# \{0,1\}//' >&2; exit 2; }
kind="$1"; shift
config="${PLYMELD_CONFIG:-}"; render=0; out=""; pass=()
while [ $# -gt 0 ]; do
  case "$1" in
    --config) config="$2"; shift 2 ;;
    --config=*) config="${1#--config=}"; shift ;;
    --render) render=1; shift ;;
    -o|--output) out="$2"; pass+=("$1" "$2"); shift 2 ;;
    --output=*) out="${1#--output=}"; pass+=("$1"); shift ;;
    *) pass+=("$1"); shift ;;
  esac
done
[ -n "$config" ] || { echo "plate.sh: no config: set PLYMELD_CONFIG or pass --config (config files or globs, ':'-separated)" >&2; exit 2; }
[ -n "$out" ] || { echo "plate.sh: -o PLATE.gcode is required" >&2; exit 2; }
cf=(); IFS=':' read -r -a parts <<< "$config"
for p in "${parts[@]}"; do [ -n "$p" ] && cf+=(-cf "$p"); done

"$here/gradlew" -p "$here" -q :plymeld-cli:installDist
bin="$here/plymeld-cli/build/install/plymeld/bin/plymeld"
"$bin" calibrate "$kind" "${cf[@]}" "${pass[@]}"
[ "$render" = 1 ] && "$bin" render-plate "$out" --iso --layers
exit 0
