# Using plymeld from the command line

plymeld is one program, `plymeld`, with a command for each job: set up, slice, see why, calibrate, and talk to the
printer. This page shows how the commands fit together. The [command reference](reference/README.md) lists every
command and option, and `plymeld <command> --help` prints the same for one command.

## Set up

```
plymeld init
```

`plymeld init` asks, in the terminal, which printers you have (each a model plymeld ships), their tools and build
plates, your filaments, and what is loaded where. It writes the answers to plymeld's config folder and ends by
slicing a test cube with them. Arrow keys choose, Enter confirms, and Ctrl+C stops without writing anything. A
command run in a terminal with no `-cf` and an empty config folder starts it by itself.

## The config

Every value plymeld uses is a setting with a path, such as `job.machine` or `plan.mechanism.motion.melt.buffer`, and
every setting has a source: plymeld's bundled files, your config files, or the command line.

- With no `-cf`, plymeld reads every `.toml` file in its config folder (`secrets.toml` aside), after its bundled files.
- `-cf FILE|GLOB` names config files instead, read in order; later files win. Giving `-cf` replaces the config folder,
  so name your own files too when you add one: `-cf 'DIR/*.toml' -cf extra.toml`.
- `-cv PATH=VALUE` sets one value and wins over every file, for example `-cv plan.technique.flat.layer.height=0.12mm`.
- `--machine KEY` picks the machine when the config has several and `job.machine` names none.

To see what the config adds up to:

```
plymeld config
plymeld config hardware.machine
```

`plymeld config` prints each value, where it was set, the values it overrode, and what each formula works out to;
a prefix narrows it. `plymeld schema` prints every path the config may hold as JSON Schema, for an editor that checks
config files (`plymeld schema -o plymeld.schema.json`).

`plymeld edit FILE PATH=VALUE...` writes values into a config file and keeps the rest of it as it was: comments,
order, and the other values. With `--journal`, the edit is recorded, and `plymeld journal --undo` takes it back.

## Slice

```
plymeld slice -m bracket.stl -o bracket.gcode
plymeld slice -m 'parts/*.stl' -o plate.gcode
```

`-m` adds a model file (STL or 3MF) or a glob of them to the plate; repeat it for more. For a 3MF project with several
plates, `--plate N` picks one. `-o` names the g-code file to write. Before writing it, plymeld checks the g-code against
the machine (its limits, retraction, travels, tools, start, and end) and against the model.

To see how plymeld would print the models, and why:

```
plymeld plan -m bracket.stl
plymeld plan -m bracket.stl -o bracket.plan.toml
```

`plymeld plan` prints each decision and the reason for it. With `-o`, it writes the decisions as a config file. Change
a decision there with `plymeld edit` (`--keep` keeps it when plymeld plans the rest again), then slice with the file
among your config files. `plymeld slice --explain` prints how each object's techniques were chosen.

A g-code file that `plymeld slice` wrote records the config and plan it was made from: `plymeld slice --from
OLD.gcode -m bracket.stl -o NEW.gcode` slices again from them, with any `-cf` and `-cv` added.

## Talk to the printer

The machine's connection is in the config (`hardware.machine.<key>.connection`); its credentials come from the
environment variables or secrets files named there. `plymeld printer credential` sets one in its secrets file. Every
`printer` command says what it sends.

```
plymeld printer status
plymeld printer send bracket.gcode
plymeld printer send bracket.gcode --print
```

`printer send` uploads the file and confirms it by the printer's own file list. With `--print` it also starts the
print, so the bed must be clear, the right filament loaded, and someone with the printer. `printer loadout` compares
what the printer reports loaded with the machine's loadout in the config. `status`, `watch`, `camera`, `files`,
`pause`, `resume`, and `cancel` do what their names say.

## Calibrate

`plymeld calibrate` makes a calibration plate for one filament, printed exactly as specified. There is one subcommand
per plate, run in this order, because each plate prints within values the plates before it measured:

| Order | Plate | Measures | Read back with |
|---|---|---|---|
| 1 | `calibrate melt` | the filament's maximum flow at each temperature | `calibrate melt values` |
| 2 | `calibrate flow` | the filament's flow ratio at each temperature | `calibrate flow values` |
| 3 | `calibrate first-layer` | the first layer's nozzle height and speed at each temperature | `calibrate first-layer values` |
| 4 | `calibrate motion` | the speed and acceleration a visible wall holds without ringing | `calibrate motion readings`, then `calibrate motion values` |
| 5 | `calibrate filament` | overhangs, bridges, stringing, line widths, and fit, by temperature band | not read back yet |
| 6 | `calibrate ironing` | the ironing flow, spacing, speed, angle, and inset | `calibrate ironing readings`, then `calibrate ironing values` |
| 6 | `calibrate seams` | where seams show least | `calibrate seams readings`, then `calibrate seams values` |

Each plate is made the same way:

```
plymeld calibrate melt --filament pla-generic --tool 1 -o melt.gcode
```

`--filament` names any filament the config or plymeld's bundled set defines, loaded or not; a new one takes
`--like` another to start from. `--tool` is the machine's tool to print with, counted from 1. The plate's values are
printed on the plate itself (a column's temperature, a row's flow), and every plate's base is marked the same way,
its front-left corner cut off as the place every count starts from. Every plate but the melt and flow plates also
checks its g-code against its specification and writes an instructions page beside it: what to look for, how to grade
it, and what each result means. `plymeld render-plate PLATE.gcode` draws a plate from its g-code, to review before
printing.

The melt, flow, and first-layer plates take their readings as arguments, one per column, and write them into the config
file that defines the filament (the first-layer plate's: the lowest good nozzle height and the fastest good speed, as
the filament's window on the build plate it printed on):

```
plymeld calibrate melt values 200=24 210=26 220=28 --tool 1
plymeld calibrate flow values 200=0.98 210=1 220=1 --tool 1
plymeld calibrate first-layer values 210=0.28,120 220=0.28,120 230=0.28,120 --plate-gcode first-layer.gcode
```

Each prints the change and checks it before writing; `--dry-run` writes nothing, and `plymeld journal --undo` takes
the change back. The other plates write a sheet to grade (`readings`), then turn the graded sheet into a config file
(`values`) to add with `-cf`.

## Every command

The [command reference](reference/README.md) is written from the commands themselves, so it says what `--help` says.
