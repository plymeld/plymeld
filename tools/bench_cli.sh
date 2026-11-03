#!/usr/bin/env bash
# Whole-process wall time of the plymeld CLI, JVM launcher vs native binary, on one model; 3 runs each.
# Also checks the two produce byte-identical g-code.
# usage: tools/bench_cli.sh <model.3mf|stl> <settings>
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
model="$1"; settings="$2"
tmp="$(mktemp -d)"
for kind in jvm native; do
  if [ "$kind" = jvm ]; then bin="$here/plymeld-cli/build/install/plymeld/bin/plymeld"; else bin="$here/plymeld-cli/build/native/plymeld"; fi
  for i in 1 2 3; do
    t0=$(date +%s%N)
    "$bin" investigate slice "$model" --settings "$settings" -o "$tmp/$kind.gcode" > /dev/null
    t1=$(date +%s%N)
    echo "$kind run $i: $(( (t1 - t0) / 1000000 )) ms"
  done
done
if cmp -s "$tmp/jvm.gcode" "$tmp/native.gcode"; then echo "outputs identical"; else echo "OUTPUTS DIFFER"; fi
rm -rf "$tmp"
