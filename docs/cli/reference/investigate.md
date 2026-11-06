<!-- Written by `plymeld commands --markdown docs/cli/reference` from the commands themselves: do not edit; change a command's help and write it again. -->

# `plymeld investigate`

[Command reference](README.md)

INVESTIGATION ONLY: slice, plan, check, and config with the settings in a project or g-code file (--settings), and predict the time and filament of any g-code file, to compare plymeld's result with that file. Never how a print is set up.

Usage: `plymeld investigate [<options>] <command> [<args>]...`

Subcommands:

- [`slice`](#plymeld-investigate-slice): INVESTIGATION ONLY: slice with the settings in a project or g-code file (--settings), to compare plymeld's result with that file.
- [`plan`](#plymeld-investigate-plan): INVESTIGATION ONLY (the settings in a project or g-code file): show each decision for printing the model: the value, the facts behind it, the alternatives, and any stand-in rules.
- [`check`](#plymeld-investigate-check): INVESTIGATION ONLY (limits from the settings in a project or g-code file): check g-code against the machine's limits before printing: bed area, excluded area, Z, temperatures, extrusion before heating, feed rates, extrusion amounts, and whether the head would hit printed material.
- [`config`](#plymeld-investigate-config): INVESTIGATION ONLY: list plymeld's settings by entity; with --settings, show each value and where it came from.
- [`predict`](#plymeld-investigate-predict): INVESTIGATION ONLY: plymeld's time and filament prediction for any g-code file, whatever wrote it, with a machine description's motion limits.

Options:

- `-h, --help`: Show this message and exit

## `plymeld investigate slice`

INVESTIGATION ONLY: slice with the settings in a project or g-code file (--settings), to compare plymeld's result with that file. Klipper flavor.

Usage: `plymeld investigate slice [<options>] <models>...`

Arguments:

- `<models>`: STL or 3MF, one or more (a plate of objects)

Options:

- `--copies=<int>`: print each model this many times (a plate of copies; default: 1)
- `-o, --output=<file>`: g-code file to write (required)
- `--settings=<text>`: a project .3mf, a .gcode file that carries its settings (; CONFIG\_BLOCK\_START ... \_END), or machine.json,process.json (required)
- `--repeat=<int>`: benchmark: slice N times from scratch in this process (default: 1)
- `--pose=<text>`: derive (default): choose how the object sits; as-given: keep the model's own orientation
- `--technique=<text>`: derive (default: chosen with the pose), one (flat layers of one height), or follow (flat layers following the surfaces)
- `--placement=<text>`: center: bottom on the bed, footprint centered; as-given: keep the model's own position (default: as-given with --pose as-given, else center)
- `--plate=<int>`: which plate of a 3MF project to slice (needed when several have objects)
- `--watch`: keep running: re-slice when a settings file or the model changes, re-running only affected stages
- `--set=<text>`: override an intent setting for this slice: id=value, repeatable (e.g. --set plan.mechanism.purge.tower.layout=banded); a project's per-object overrides still win
- `--pause-at-layer=<int>`: pause before layer N prints (the first layer is 1); repeatable
- `--pause-at-z=<float>`: pause before the first layer whose top is at or above Z mm; repeatable
- `--pause-before=<text>`: pause before the first layer an object or part (by name) prints on; repeatable
- `--pause-after=<text>`: pause after the last layer an object or part (by name) prints on; repeatable
- `-h, --help`: Show this message and exit

## `plymeld investigate plan`

INVESTIGATION ONLY (the settings in a project or g-code file): show each decision for printing the model: the value, the facts behind it, the alternatives, and any stand-in rules.

Usage: `plymeld investigate plan [<options>] <models>...`

Arguments:

- `<models>`: STL or 3MF, one or more

Options:

- `--copies=<int>`: each model this many times (default: 1)
- `--settings=<text>`: a project .3mf, a .gcode file that carries its settings (; CONFIG\_BLOCK\_START ... \_END), or machine.json,process.json (required)
- `--pose=<text>`: derive (default) or as-given
- `--technique=<text>`: derive (default: chosen with the pose), one (flat layers of one height), or follow (flat layers following the surfaces)
- `--placement=<text>`: center or as-given (default: as-given with --pose as-given, else center)
- `--plate=<int>`: which plate of a 3MF project (needed when several have objects)
- `--set=<text>`: override an intent setting: id=value, repeatable (e.g. --set job.priorities=quality=10,speed=3)
- `-h, --help`: Show this message and exit

## `plymeld investigate check`

INVESTIGATION ONLY (limits from the settings in a project or g-code file): check g-code against the machine's limits before printing: bed area, excluded area, Z, temperatures, extrusion before heating, feed rates, extrusion amounts, and whether the head would hit printed material.

Usage: `plymeld investigate check [<options>] <file>`

Arguments:

- `<file>`: .gcode or .gcode.3mf

Options:

- `--settings=<text>`: a project .3mf, a .gcode file that carries its settings (; CONFIG\_BLOCK\_START ... \_END), or machine.json,process.json (required)
- `-h, --help`: Show this message and exit

## `plymeld investigate config`

INVESTIGATION ONLY: list plymeld's settings by entity; with --settings, show each value and where it came from.

Usage: `plymeld investigate config [<options>] [<id>]`

Arguments:

- `<id>`: setting id or prefix (e.g. plan.layer.walls.count, hardware.filament.retract.)

Options:

- `--settings=<text>`: a project .3mf, a .gcode file that carries its settings (; CONFIG\_BLOCK\_START ... \_END), or machine.json,process.json
- `-h, --help`: Show this message and exit

## `plymeld investigate predict`

INVESTIGATION ONLY: plymeld's time and filament prediction for any g-code file, whatever wrote it, with a machine description's motion limits.

Usage: `plymeld investigate predict [<options>] <file>`

Arguments:

- `<file>`: .gcode or .gcode.3mf

Options:

- `--machine=<text>`: the machine description to time it with, by its model's key (e.g. qidi-q2, flashforge-c5) (required)
- `--tool-change-time=<float>`: seconds the machine spends on one filament change beyond the g-code's moves (default: not counted)
- `-h, --help`: Show this message and exit
