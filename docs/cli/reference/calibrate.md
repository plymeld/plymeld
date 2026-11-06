<!-- Written by `plymeld commands --markdown docs/cli/reference` from the commands themselves: do not edit; change a command's help and write it again. -->

# `plymeld calibrate`

[Command reference](README.md)

Make a calibration plate for one filament (--filament, --tool), printed exactly as specified: one subcommand per plate, in the order to run them (each plate's results lean on the ones before it): 'melt' (the melt rate: a column per temperature, a row per flow, each row's flow printed beside it. Run it first: an accurate melt rate drives so much of the rest, and the other plates are not reliable until it is measured); 'flow' (the flow ratio: a column per temperature, a patch per ratio, each patch solid at its own ratio and its top laid at its column's temperature; after the melt plate); 'first-layer' (the first layer: nozzle height x first-layer speed by temperature, one layer of tiles in one piece, a column per temperature and a row per speed and height (a frame over their walls and a border round the base to peel it by); after the flow plate); 'motion' (the machine: the speed and acceleration a visible wall holds without ringing, per direction: one cross-shaped tower, twelve faces at six speeds (one along X and one along Y each), a band per acceleration, the top band's fastest faces at the machine's limits; graded per cell, the readings become the machine's visible speed and acceleration; after the first-layer plate (the tower, shaken at the machine's limits, needs a first layer that holds)); 'filament' (the filament's other tests by temperature band, once its melt rate and flow ratio are measured: overhangs, bridges, stringing, line widths, fit (--tests picks a subset)); 'ironing' (the ironing values: one flat slab whose top is divided into zones, each ironed with its own combination of flow, line spacing, speed, angle, and inset, a control zone per speed left as laid; graded zone by zone, the readings become the ironing values); 'seams' (the seam measure's shares: eight hollow pieces on one slab, each with its outer wall's seam laid one way (a point seam or plymeld's scarf, on a round wall, mid-face, in a corner, on a face leaning 30 and 45 degrees); graded piece by piece for how far away the seam shows, the ratios become method.quality.seam.scarf\_share, .corner\_share and method.quality.visibility.under). Every plate but the melt and flow plates also checks its g-code against its specification and writes an instructions page. Readings and write-backs sit under their plate: `melt values`, `flow values`, `first-layer values`, `motion readings`, `motion values`, `ironing readings`, `ironing values`, `seams readings`, `seams values`.

Usage: `plymeld calibrate [OPTIONS] <command>`

Subcommands:

- [`melt`](#plymeld-calibrate-melt): The melt plate, run first: the filament's maximum flow at each temperature, as a table printed in plastic: a column per temperature (--temps, --step), a row per flow (--flow-step), each row the same line at its own speed, its flow printed beside it and each column's temperature in front of it.
- [`flow`](#plymeld-calibrate-flow): The flow plate, after the melt plate: the filament's flow ratio at each temperature: a column per temperature (--temps, --step), a square patch per flow ratio (--flow-ratios), each patch solid at its own ratio with its lines exactly the bead spacing apart, its top laid at its column's temperature; each patch's ratio printed beside its row and each column's temperature in front of it.
- [`first-layer`](#plymeld-calibrate-first-layer): The first-layer plate: one layer of first-layer tiles laid out as a table, one piece: a column per temperature (--temps, --step; coolest first, heating and priming between columns), and in each column a group of rows per speed (--first-speeds), a row per nozzle height in each (--first-heights), every tile a strip of the same size; each column's temperature, each speed, and each row's height raised on a base along the plate's front and left.
- [`motion`](#plymeld-calibrate-motion): The motion plate, once per printer: one hollow cross-shaped tower whose twelve faces print at six speeds (--motion-speeds; one face along X and one along Y for each) and whose bands print at one acceleration each (--motion-accels), the fastest face of the top band at the machine's limits (the control), each face's speed printed on the slab in front of it; the tower at one temperature (the filament's temperature.print, or --print-temp), the slab's first layer at the filament's first-layer temperature.
- [`filament`](#plymeld-calibrate-filament): The filament plate, once its melt rate and flow ratio are measured: the filament's other families of tests (--tests: overhangs, bridges, stringing, line widths, fit) with the temperature varied by band of height, each family on a base of its own, packed to fit the bed, with each family's predicted time and filament.
- [`ironing`](#plymeld-calibrate-ironing): The ironing plate: zones of flat tops, each ironed with its own combination of the ironing flow, line spacing, speed, angle, and inset (--ironing-flows, --ironing-spacings, --ironing-speeds, --ironing-angles, --ironing-insets; every value in several zones, a control zone per speed left as laid): two slabs per speed (a row of zones each) on one base with a brim, a strip as laid between neighboring zones, each zone notched with its flow and spacing (front edge) and its inset and angle (back edge), each slab with its speed, and a legend of the marks.
- [`seams`](#plymeld-calibrate-seams): The seam plate: eight hollow pieces on one slab, each with its outer wall's seam laid one way on every layer (a point seam or plymeld's scarf seam; on a round wall, in the middle of a flat face, in a corner, on a face leaning 30 and 45 degrees), the pieces marked by ticks in the slab's edges and a chamfered corner.

Options:

- `-h`, `--help`: show the command's help and exit

## `plymeld calibrate melt`

The melt plate, run first: the filament's maximum flow at each temperature, as a table printed in plastic: a column per temperature (--temps, --step), a row per flow (--flow-step), each row the same line at its own speed, its flow printed beside it and each column's temperature in front of it. Read each column for the first row with gaps; `melt values` writes the readings into the filament's config. An accurate melt rate drives so much of the rest.

Usage: `plymeld calibrate melt [OPTIONS] [<command>]`

Subcommands:

- [`values`](#plymeld-calibrate-melt-values): Write a read melt plate's results into the config file that defines the filament (among the files -cf names, e.g. -cf 'DIR/\*.toml'; else plymeld's config folder's): its maximum flow on this machine as a formula through every reading (straight between neighboring temperatures, flat past the ends), the readings in the comment above it.

Options:

- `-cf FILE|GLOB`, `--config-file FILE|GLOB` (repeatable): a config file (.toml or .json) or a glob of them; repeatable, read in order (a glob's matches in natural order); later files win; none given: every .toml file in plymeld's config folder (secrets.toml aside)
- `-cv PATH=VALUE`, `--config-value PATH=VALUE` (repeatable): one config value, e.g. job.priorities.speed=5 or plan.technique.flat.layer.height=0.2mm; repeatable; wins over every file
- `--machine KEY`: the machine that prints (hardware.machine.&lt;key&gt;; its loadout gives the plate, tools, and filaments); default: job.machine, else the only machine the config defines
- `-o FILE`, `--output FILE`: g-code file to write (required for a plate)
- `--filament NAME`: the filament to calibrate: any the config or plymeld's bundled set defines (e.g. pla-generic), loaded or not; a new one with --like; default: the one the machine's loadout puts on --tool
- `--like NAME`: for a --filament the config does not define: start it from this one's values (placeholders until measured)
- `--tool N` (default: `1`): the machine's tool (its index, from 1) to print with
- `--plate KEY`: the build plate (hardware.plate.&lt;key&gt;): its bed temperature for the filament; plymeld's own are pei and cool; default: the one loaded on the machine (hardware.machine.&lt;key&gt;.loadout.plate), else the only one the config defines itself
- `--no-level`: do not run the machine's bed levelling (hardware.machine.&lt;key&gt;.gcode.level) before the plate
- `--temps LOW-HIGH`: the temperature range, whole degrees C, e.g. 190-230: a column each, from LOW up by --step; default: the filament's (temperature.min to temperature.max, the spool's label)
- `--step C`: whole degrees between columns; default: 10
- `--melt-fan on|off`: the part fan in every column at the filament's own (cooling.fan; 'on', the default) or off ('off')
- `--flow-step MM3S`: mm3/s between rows (each row that much more flow than the one in front of it); the rows run from half the filament's expected maximum flow to twice it; default: the finest of 0.25, 0.5, 1, 2, 5 that keeps them to about 24
- `-h`, `--help`: show the command's help and exit

### `plymeld calibrate melt values`

Write a read melt plate's results into the config file that defines the filament (among the files -cf names, e.g. -cf 'DIR/\*.toml'; else plymeld's config folder's): its maximum flow on this machine as a formula through every reading (straight between neighboring temperatures, flat past the ends), the readings in the comment above it. Prints the change, checks it, writes it, and records it for `plymeld journal --undo`.

Usage: `plymeld calibrate melt values [OPTIONS] TEMP=FLOW...`

Arguments:

- `TEMP=FLOW...` (required, one or more): a column's reading: its temperature (C) = the highest row's flow (mm3/s) printed without gaps, e.g. 210=26; FLOW+ when the column was clean up to the plate's last row (at least that), e.g. 230=30+ (or &gt;=FLOW, quoted for the shell)

Options:

- `-cf FILE|GLOB`, `--config-file FILE|GLOB` (repeatable): a config file (.toml or .json) or a glob of them; repeatable, read in order (a glob's matches in natural order); later files win; none given: every .toml file in plymeld's config folder (secrets.toml aside)
- `-cv PATH=VALUE`, `--config-value PATH=VALUE` (repeatable): one config value, e.g. job.priorities.speed=5 or plan.technique.flat.layer.height=0.2mm; repeatable; wins over every file
- `--machine KEY`: the machine that prints (hardware.machine.&lt;key&gt;; its loadout gives the plate, tools, and filaments); default: job.machine, else the only machine the config defines
- `--tool N`: the machine's tool (its index, from 1) the plate printed with; default: 1 (the first-layer plate: the tool its g-code names)
- `--filament NAME`: the filament the plate printed; default: the one the machine's loadout puts on --tool
- `--plate-gcode PLATE`: the melt plate's g-code, as plymeld wrote it: named, with the plymeld that made it, in the comment
- `--dry-run`: print the change and check it; write nothing
- `--journal JOURNAL`: record the change in this journal file; default: the config journal in plymeld's state folder (config.journal.json)
- `-h`, `--help`: show the command's help and exit

## `plymeld calibrate flow`

The flow plate, after the melt plate: the filament's flow ratio at each temperature: a column per temperature (--temps, --step), a square patch per flow ratio (--flow-ratios), each patch solid at its own ratio with its lines exactly the bead spacing apart, its top laid at its column's temperature; each patch's ratio printed beside its row and each column's temperature in front of it. Read each column for its best patch (closed, no gaps, no ridges); `flow values` writes the readings into the filament's config.

Usage: `plymeld calibrate flow [OPTIONS] [<command>]`

Subcommands:

- [`values`](#plymeld-calibrate-flow-values): Write a read flow plate's results into the config file that defines the filament (among the files -cf names, e.g. -cf 'DIR/\*.toml'; else plymeld's config folder's): its flow ratio, the best patch's in each column, as one value when they agree, else a formula through every reading (straight between neighboring temperatures, flat past the ends); the readings, the machine and the tool in the comment above it.

Options:

- `-cf FILE|GLOB`, `--config-file FILE|GLOB` (repeatable): a config file (.toml or .json) or a glob of them; repeatable, read in order (a glob's matches in natural order); later files win; none given: every .toml file in plymeld's config folder (secrets.toml aside)
- `-cv PATH=VALUE`, `--config-value PATH=VALUE` (repeatable): one config value, e.g. job.priorities.speed=5 or plan.technique.flat.layer.height=0.2mm; repeatable; wins over every file
- `--machine KEY`: the machine that prints (hardware.machine.&lt;key&gt;; its loadout gives the plate, tools, and filaments); default: job.machine, else the only machine the config defines
- `-o FILE`, `--output FILE`: g-code file to write (required for a plate)
- `--filament NAME`: the filament to calibrate: any the config or plymeld's bundled set defines (e.g. pla-generic), loaded or not; a new one with --like; default: the one the machine's loadout puts on --tool
- `--like NAME`: for a --filament the config does not define: start it from this one's values (placeholders until measured)
- `--tool N` (default: `1`): the machine's tool (its index, from 1) to print with
- `--plate KEY`: the build plate (hardware.plate.&lt;key&gt;): its bed temperature for the filament; plymeld's own are pei and cool; default: the one loaded on the machine (hardware.machine.&lt;key&gt;.loadout.plate), else the only one the config defines itself
- `--no-level`: do not run the machine's bed levelling (hardware.machine.&lt;key&gt;.gcode.level) before the plate
- `--temps LOW-HIGH`: the temperature range, whole degrees C, e.g. 190-230: a column each, from LOW up by --step; default: the filament's (temperature.min to temperature.max, the spool's label)
- `--step C`: whole degrees between columns; default: 10
- `--flow-ratios R,R,...`: the patches' flow ratios, low to high (e.g. 0.94,0.97,1,1.03), a patch each in every column; default: the filament's flow ratio x 0.94 to 1.06 in 0.02 steps
- `-h`, `--help`: show the command's help and exit

### `plymeld calibrate flow values`

Write a read flow plate's results into the config file that defines the filament (among the files -cf names, e.g. -cf 'DIR/\*.toml'; else plymeld's config folder's): its flow ratio, the best patch's in each column, as one value when they agree, else a formula through every reading (straight between neighboring temperatures, flat past the ends); the readings, the machine and the tool in the comment above it. Prints the change, checks it, writes it, and records it for `plymeld journal --undo`.

Usage: `plymeld calibrate flow values [OPTIONS] TEMP=RATIO...`

Arguments:

- `TEMP=RATIO...` (required, one or more): a column's reading: its temperature (C) = the ratio of its best patch (closed, no gaps, and no ridges), as printed beside the row, e.g. 210=0.98 (or 98%)

Options:

- `-cf FILE|GLOB`, `--config-file FILE|GLOB` (repeatable): a config file (.toml or .json) or a glob of them; repeatable, read in order (a glob's matches in natural order); later files win; none given: every .toml file in plymeld's config folder (secrets.toml aside)
- `-cv PATH=VALUE`, `--config-value PATH=VALUE` (repeatable): one config value, e.g. job.priorities.speed=5 or plan.technique.flat.layer.height=0.2mm; repeatable; wins over every file
- `--machine KEY`: the machine that prints (hardware.machine.&lt;key&gt;; its loadout gives the plate, tools, and filaments); default: job.machine, else the only machine the config defines
- `--tool N`: the machine's tool (its index, from 1) the plate printed with; default: 1 (the first-layer plate: the tool its g-code names)
- `--filament NAME`: the filament the plate printed; default: the one the machine's loadout puts on --tool
- `--plate-gcode PLATE`: the flow plate's g-code, as plymeld wrote it: named, with the plymeld that made it, in the comment
- `--dry-run`: print the change and check it; write nothing
- `--journal JOURNAL`: record the change in this journal file; default: the config journal in plymeld's state folder (config.journal.json)
- `-h`, `--help`: show the command's help and exit

## `plymeld calibrate first-layer`

The first-layer plate: one layer of first-layer tiles laid out as a table, one piece: a column per temperature (--temps, --step; coolest first, heating and priming between columns), and in each column a group of rows per speed (--first-speeds), a row per nozzle height in each (--first-heights), every tile a strip of the same size; each column's temperature, each speed, and each row's height raised on a base along the plate's front and left. A tile whose flow would pass the filament's maximum flow at its column's temperature is left out; every tile touches its neighbors, and a frame of three layers over their walls, with as tall a border round the base (neither read), is the raised edge to peel the plate by. `first-layer values` writes the read plate into the filament's first-layer window: per column, the lowest good nozzle height and the fastest good speed.

Usage: `plymeld calibrate first-layer [OPTIONS] [<command>]`

Subcommands:

- [`values`](#plymeld-calibrate-first-layer-values): Write a read first-layer plate's results into the config file that defines the filament (among the files -cf names, e.g. -cf 'DIR/\*.toml'; else plymeld's config folder's): its first-layer window on the build plate the plate printed on, per column the lowest good nozzle height and the fastest good speed, for the tool's bore; the plate (--plate-gcode) in the comment above it.

Options:

- `-cf FILE|GLOB`, `--config-file FILE|GLOB` (repeatable): a config file (.toml or .json) or a glob of them; repeatable, read in order (a glob's matches in natural order); later files win; none given: every .toml file in plymeld's config folder (secrets.toml aside)
- `-cv PATH=VALUE`, `--config-value PATH=VALUE` (repeatable): one config value, e.g. job.priorities.speed=5 or plan.technique.flat.layer.height=0.2mm; repeatable; wins over every file
- `--machine KEY`: the machine that prints (hardware.machine.&lt;key&gt;; its loadout gives the plate, tools, and filaments); default: job.machine, else the only machine the config defines
- `-o FILE`, `--output FILE`: g-code file to write (required for a plate)
- `--filament NAME`: the filament to calibrate: any the config or plymeld's bundled set defines (e.g. pla-generic), loaded or not; a new one with --like; default: the one the machine's loadout puts on --tool
- `--like NAME`: for a --filament the config does not define: start it from this one's values (placeholders until measured)
- `--tool N` (default: `1`): the machine's tool (its index, from 1) to print with
- `--plate KEY`: the build plate (hardware.plate.&lt;key&gt;): its bed temperature for the filament; plymeld's own are pei and cool; default: the one loaded on the machine (hardware.machine.&lt;key&gt;.loadout.plate), else the only one the config defines itself
- `--no-level`: do not run the machine's bed levelling (hardware.machine.&lt;key&gt;.gcode.level) before the plate
- `--temps LOW-HIGH`: the temperature range, whole degrees C, e.g. 200-230, or a single temperature, e.g. 215: a column each, coolest first; default: the filament's print temperature up to its maximum by --step, as far as the maximum goes (temperature.print to temperature.max: a first layer runs at or above the print temperature), or its whole range (temperature.min to temperature.max) where it has no print temperature
- `--step C`: whole degrees between columns; default: 10
- `--first-speeds S,S,...`: first-layer plate: the tiles' speeds (mm/s), a group of rows each, slow to fast; default: the first layer's speed (plan.mechanism.motion.first\_layer.speed) halved, as it is, and half as fast again
- `--first-heights MM,MM,...`: first-layer plate: the tiles' nozzle heights (mm), a row each in every speed's group, low to high; default: the first layer's height (plan.mechanism.adhesion.first\_layer.height) and three steps above it, a step a tenth of the nozzle's bore (hardware.tool.&lt;key&gt;.bore: 0.2 to 0.32 mm for a 0.2 mm first layer on a 0.4 mm nozzle)
- `-h`, `--help`: show the command's help and exit

### `plymeld calibrate first-layer values`

Write a read first-layer plate's results into the config file that defines the filament (among the files -cf names, e.g. -cf 'DIR/\*.toml'; else plymeld's config folder's): its first-layer window on the build plate the plate printed on, per column the lowest good nozzle height and the fastest good speed, for the tool's bore; the plate (--plate-gcode) in the comment above it. A window already there for that plate is replaced. Prints the change, checks it, writes it, and records it for `plymeld journal --undo`.

Usage: `plymeld calibrate first-layer values [OPTIONS] TEMP=HEIGHT,SPEED...`

Arguments:

- `TEMP=HEIGHT,SPEED...` (required, one or more): a column's reading: its temperature (C) = the lowest nozzle height (mm) graded good there, every lower one bad, and the fastest speed (mm/s) graded good, each one of the plate's own, e.g. 210=0.28,120; TEMP=none for a column with no good tile (the window leaves that temperature out)

Options:

- `-cf FILE|GLOB`, `--config-file FILE|GLOB` (repeatable): a config file (.toml or .json) or a glob of them; repeatable, read in order (a glob's matches in natural order); later files win; none given: every .toml file in plymeld's config folder (secrets.toml aside)
- `-cv PATH=VALUE`, `--config-value PATH=VALUE` (repeatable): one config value, e.g. job.priorities.speed=5 or plan.technique.flat.layer.height=0.2mm; repeatable; wins over every file
- `--machine KEY`: the machine that prints (hardware.machine.&lt;key&gt;; its loadout gives the plate, tools, and filaments); default: job.machine, else the only machine the config defines
- `--tool N`: the machine's tool (its index, from 1) the plate printed with; default: 1 (the first-layer plate: the tool its g-code names)
- `--filament NAME`: the filament the plate printed; default: the one the machine's loadout puts on --tool
- `--plate-gcode PLATE`: the first-layer plate's g-code, as plymeld wrote it: named, with the plymeld that made it, in the comment
- `--dry-run`: print the change and check it; write nothing
- `--journal JOURNAL`: record the change in this journal file; default: the config journal in plymeld's state folder (config.journal.json)
- `-h`, `--help`: show the command's help and exit

## `plymeld calibrate motion`

The motion plate, once per printer: one hollow cross-shaped tower whose twelve faces print at six speeds (--motion-speeds; one face along X and one along Y for each) and whose bands print at one acceleration each (--motion-accels), the fastest face of the top band at the machine's limits (the control), each face's speed printed on the slab in front of it; the tower at one temperature (the filament's temperature.print, or --print-temp), the slab's first layer at the filament's first-layer temperature. Graded per band, speed, and direction for ringing; `motion readings` writes the sheet to grade, `motion values` turns the graded sheet into the machine's visible speed and acceleration (hardware.machine.&lt;key&gt;.motion.visible.speed, .visible.acceleration).

Usage: `plymeld calibrate motion [OPTIONS] [<command>]`

Subcommands:

- [`readings`](#plymeld-calibrate-motion-readings): Write the readings sheet to grade for a printed motion plate: two grids (faces along X, faces along Y), a row per band (acceleration) and a column per speed, every cell '?'.
- [`values`](#plymeld-calibrate-motion-values): Turn a graded motion readings sheet into the machine's visible speed and acceleration (hardware.machine.&lt;key&gt;.motion.visible.speed, .visible.acceleration): per band the fastest speed clean in both directions (and every slower speed), and of those the pair that lays a face of the plate's length quickest, with where it came from.

Options:

- `-cf FILE|GLOB`, `--config-file FILE|GLOB` (repeatable): a config file (.toml or .json) or a glob of them; repeatable, read in order (a glob's matches in natural order); later files win; none given: every .toml file in plymeld's config folder (secrets.toml aside)
- `-cv PATH=VALUE`, `--config-value PATH=VALUE` (repeatable): one config value, e.g. job.priorities.speed=5 or plan.technique.flat.layer.height=0.2mm; repeatable; wins over every file
- `--machine KEY`: the machine that prints (hardware.machine.&lt;key&gt;; its loadout gives the plate, tools, and filaments); default: job.machine, else the only machine the config defines
- `-o FILE`, `--output FILE`: g-code file to write (required for a plate)
- `--filament NAME`: the filament to calibrate: any the config or plymeld's bundled set defines (e.g. pla-generic), loaded or not; a new one with --like; default: the one the machine's loadout puts on --tool
- `--like NAME`: for a --filament the config does not define: start it from this one's values (placeholders until measured)
- `--tool N` (default: `1`): the machine's tool (its index, from 1) to print with
- `--plate KEY`: the build plate (hardware.plate.&lt;key&gt;): its bed temperature for the filament; plymeld's own are pei and cool; default: the one loaded on the machine (hardware.machine.&lt;key&gt;.loadout.plate), else the only one the config defines itself
- `--no-level`: do not run the machine's bed levelling (hardware.machine.&lt;key&gt;.gcode.level) before the plate
- `--motion-speeds S,S,S,S,S,S`: motion plate: the six speeds (mm/s), slow to fast, each on one face along X and one along Y; default: the machine's print speed held to the filament's maximum flow on the outer wall (the control), and a sixth of it, two sixths ... rounded down to 5 mm/s (plymeld's choice)
- `--motion-accels A,A,...`: motion plate: one acceleration (mm/s2) per band, bottom to top, rising; default: 6 bands, the machine's print acceleration at the top (the control) and each band below half the one above (plymeld's choice)
- `--print-temp C`: motion plate: the tower's temperature (the slab's first layer follows the filament's first-layer temperature, worked out from it); filament plate: the fit test stands on a pedestal up to the band nearest this temperature; default: the filament's temperature.print
- `-h`, `--help`: show the command's help and exit

### `plymeld calibrate motion readings`

Write the readings sheet to grade for a printed motion plate: two grids (faces along X, faces along Y), a row per band (acceleration) and a column per speed, every cell '?'. Replace each '?' with hidden, faint, or plain (how far away the face's ripples show), then turn the sheet into the machine's visible speed and acceleration with `calibrate motion values`.

Usage: `plymeld calibrate motion readings [OPTIONS] PLATE`

Arguments:

- `PLATE` (required): the motion plate's g-code, as `plymeld calibrate motion` wrote it

Options:

- `-o FILE`, `--output FILE`: write the sheet to this TOML file (default: print it)
- `-h`, `--help`: show the command's help and exit

### `plymeld calibrate motion values`

Turn a graded motion readings sheet into the machine's visible speed and acceleration (hardware.machine.&lt;key&gt;.motion.visible.speed, .visible.acceleration): per band the fastest speed clean in both directions (and every slower speed), and of those the pair that lays a face of the plate's length quickest, with where it came from. Add the written file with -cf.

Usage: `plymeld calibrate motion values [OPTIONS] READINGS`

Arguments:

- `READINGS` (required): the graded readings sheet, as `plymeld calibrate motion readings` wrote it and you graded it

Options:

- `--clean GRADE` (default: `hidden`): the worst grade that still counts as clean: hidden or faint (default: hidden, no ripples even close up)
- `-o FILE`, `--output FILE`: write the values to this TOML config file (default: print it)
- `-h`, `--help`: show the command's help and exit

## `plymeld calibrate filament`

The filament plate, once its melt rate and flow ratio are measured: the filament's other families of tests (--tests: overhangs, bridges, stringing, line widths, fit) with the temperature varied by band of height, each family on a base of its own, packed to fit the bed, with each family's predicted time and filament.

Usage: `plymeld calibrate filament [OPTIONS]`

Options:

- `-cf FILE|GLOB`, `--config-file FILE|GLOB` (repeatable): a config file (.toml or .json) or a glob of them; repeatable, read in order (a glob's matches in natural order); later files win; none given: every .toml file in plymeld's config folder (secrets.toml aside)
- `-cv PATH=VALUE`, `--config-value PATH=VALUE` (repeatable): one config value, e.g. job.priorities.speed=5 or plan.technique.flat.layer.height=0.2mm; repeatable; wins over every file
- `--machine KEY`: the machine that prints (hardware.machine.&lt;key&gt;; its loadout gives the plate, tools, and filaments); default: job.machine, else the only machine the config defines
- `-o FILE`, `--output FILE`: g-code file to write (required for a plate)
- `--filament NAME`: the filament to calibrate: any the config or plymeld's bundled set defines (e.g. pla-generic), loaded or not; a new one with --like; default: the one the machine's loadout puts on --tool
- `--like NAME`: for a --filament the config does not define: start it from this one's values (placeholders until measured)
- `--tool N` (default: `1`): the machine's tool (its index, from 1) to print with
- `--plate KEY`: the build plate (hardware.plate.&lt;key&gt;): its bed temperature for the filament; plymeld's own are pei and cool; default: the one loaded on the machine (hardware.machine.&lt;key&gt;.loadout.plate), else the only one the config defines itself
- `--no-level`: do not run the machine's bed levelling (hardware.machine.&lt;key&gt;.gcode.level) before the plate
- `--temps LOW-HIGH`: the temperature range, whole degrees C, e.g. 195-225: a band each, hottest at the bottom; default: the filament's (temperature.min to temperature.max, the spool's label)
- `--step C`: whole degrees between bands; default: 5
- `--fans LOW,HIGH`: filament plate: the two part-fan levels (%) the bands alternate between; default: half the filament's fan, and 1.5x it (at most 100)
- `--tests T,T,...`: filament plate: the families of tests on it, any of overhangs,bridges,stringing,line-width,fit (default: all of them); this is the way to leave a family out. The melt test and the motion test are plates of their own (`calibrate melt`, `calibrate motion`). The ruler post is on every filament plate (the bands' ruler); every family stands on a base of its own; the families are packed to fit the bed, and a plate that does not fit is refused with a split to make instead
- `--overhangs S,S,...`: filament plate: the overhang ledges' shares of a line, low to high (e.g. 0.3,0.5,0.7); default: the filament's overhang.share (hardware.filament.&lt;key&gt;.overhang.share) x 0.7, 1, 1.3, 1.6, 1.9 (below 1)
- `--print-temp C`: motion plate: the tower's temperature (the slab's first layer follows the filament's first-layer temperature, worked out from it); filament plate: the fit test stands on a pedestal up to the band nearest this temperature; default: the filament's temperature.print
- `--peg MM`: filament plate: the fit test's peg diameter (mm); default: 5
- `--holes MM,MM,...`: filament plate: the fit test's hole diameters (mm), small to large; default: the peg and 0.1 to 0.4 mm larger
- `--widths MM,MM,...`: filament plate: the line-width tubes' widths (mm), narrow to wide; default: the nozzle bore x 0.625 to 1.875 in steps of 0.125 (0.25 to 0.75 mm on a 0.4 mm nozzle)
- `--retractions MM,MM,...`: filament plate: the stringing ring's hops' retraction lengths (mm), short to long, a hop each; default: 0.4 to 1.6 in 0.2 steps
- `--spans MM,MM,...`: filament plate: the bridges' spans (mm), short to long; default: 8,14,20,26,32
- `--bridge-flows FRONT,BACK`: filament plate: the two bridge decks' flow ratios; default: the bridge flow (plan.layer.bridge.flow) and 0.85x it
- `-h`, `--help`: show the command's help and exit

## `plymeld calibrate ironing`

The ironing plate: zones of flat tops, each ironed with its own combination of the ironing flow, line spacing, speed, angle, and inset (--ironing-flows, --ironing-spacings, --ironing-speeds, --ironing-angles, --ironing-insets; every value in several zones, a control zone per speed left as laid): two slabs per speed (a row of zones each) on one base with a brim, a strip as laid between neighboring zones, each zone notched with its flow and spacing (front edge) and its inset and angle (back edge), each slab with its speed, and a legend of the marks. `ironing readings` writes its sheet to grade; `ironing values` turns the graded sheet into the ironing values.

Usage: `plymeld calibrate ironing [OPTIONS] [<command>]`

Subcommands:

- [`readings`](#plymeld-calibrate-ironing-readings): Write the readings sheet to grade for a printed ironing plate: an entry per zone with its values, its grade '?'.
- [`values`](#plymeld-calibrate-ironing-values): Turn a graded ironing readings sheet into the ironing values (plan.technique.ironing.\*): per setting the value whose zones graded best, with where it came from, and the best single zone beside them.

Options:

- `-cf FILE|GLOB`, `--config-file FILE|GLOB` (repeatable): a config file (.toml or .json) or a glob of them; repeatable, read in order (a glob's matches in natural order); later files win; none given: every .toml file in plymeld's config folder (secrets.toml aside)
- `-cv PATH=VALUE`, `--config-value PATH=VALUE` (repeatable): one config value, e.g. job.priorities.speed=5 or plan.technique.flat.layer.height=0.2mm; repeatable; wins over every file
- `--machine KEY`: the machine that prints (hardware.machine.&lt;key&gt;; its loadout gives the plate, tools, and filaments); default: job.machine, else the only machine the config defines
- `-o FILE`, `--output FILE`: g-code file to write (required for a plate)
- `--filament NAME`: the filament to calibrate: any the config or plymeld's bundled set defines (e.g. pla-generic), loaded or not; a new one with --like; default: the one the machine's loadout puts on --tool
- `--like NAME`: for a --filament the config does not define: start it from this one's values (placeholders until measured)
- `--tool N` (default: `1`): the machine's tool (its index, from 1) to print with
- `--plate KEY`: the build plate (hardware.plate.&lt;key&gt;): its bed temperature for the filament; plymeld's own are pei and cool; default: the one loaded on the machine (hardware.machine.&lt;key&gt;.loadout.plate), else the only one the config defines itself
- `--no-level`: do not run the machine's bed levelling (hardware.machine.&lt;key&gt;.gcode.level) before the plate
- `--ironing-flows F,F,...`: ironing plate: the ironing flows (shares of a full line, e.g. 0.05,0.1,0.15 or 5%,10%,15%), low to high, a row of the plan each; default: the current ironing flow (plan.technique.ironing.flow) x 0.5, 1, 1.5 (a placeholder spread, not measured)
- `--ironing-spacings MM,MM,...`: ironing plate: the ironing line spacings (mm), fine to wide, a column of the plan each; default: the current spacing (plan.technique.ironing.spacing) x 1, 1.5, 2 (a placeholder spread, not measured)
- `--ironing-speeds S,S,...`: ironing plate: the ironing speeds (mm/s), slow to fast, a block of zones each; default: the current speed (plan.technique.ironing.speed) x 0.5, 1, 1.5 (a placeholder spread, not measured)
- `--ironing-angles DEG,DEG,...`: ironing plate: the ironing directions (degrees; 0 = along the slab and its top fill's lines, 90 = across them), spread over the plan as a Latin square; default: the current angle (plan.technique.ironing.angle), +45, and +90 (plymeld's choice, not measured)
- `--ironing-insets MM,MM,...`: ironing plate: how far inside the slab's edge each zone's ironing stays (mm, on top of half the bore), small to large, spread over the plan as a Latin square; default: the current inset (plan.technique.ironing.inset) x 0.5, 1, 2 (a placeholder spread, not measured)
- `-h`, `--help`: show the command's help and exit

### `plymeld calibrate ironing readings`

Write the readings sheet to grade for a printed ironing plate: an entry per zone with its values, its grade '?'. Replace each '?' with good, marginal, or bad, then turn the sheet into the ironing values with `calibrate ironing values`.

Usage: `plymeld calibrate ironing readings [OPTIONS] PLATE`

Arguments:

- `PLATE` (required): the ironing plate's g-code, as `plymeld calibrate ironing` wrote it

Options:

- `-o FILE`, `--output FILE`: write the sheet to this TOML file (default: print it)
- `-h`, `--help`: show the command's help and exit

### `plymeld calibrate ironing values`

Turn a graded ironing readings sheet into the ironing values (plan.technique.ironing.\*): per setting the value whose zones graded best, with where it came from, and the best single zone beside them. Add the written file with -cf.

Usage: `plymeld calibrate ironing values [OPTIONS] READINGS`

Arguments:

- `READINGS` (required): the graded readings sheet, as `plymeld calibrate ironing readings` wrote it and you graded it

Options:

- `-o FILE`, `--output FILE`: write the values to this TOML config file (default: print it)
- `-h`, `--help`: show the command's help and exit

## `plymeld calibrate seams`

The seam plate: eight hollow pieces on one slab, each with its outer wall's seam laid one way on every layer (a point seam or plymeld's scarf seam; on a round wall, in the middle of a flat face, in a corner, on a face leaning 30 and 45 degrees), the pieces marked by ticks in the slab's edges and a chamfered corner. Graded piece by piece for how far away the seam shows; `seams readings` writes the sheet to grade, `seams values` turns the graded sheet into the seam measure's shares (method.quality.seam.scarf\_share, .corner\_share, method.quality.visibility.under), which decide where plymeld puts every seam.

Usage: `plymeld calibrate seams [OPTIONS] [<command>]`

Subcommands:

- [`readings`](#plymeld-calibrate-seams-readings): Write the readings sheet to grade for a printed seam plate: an entry per piece with its kind, its grade '?'.
- [`values`](#plymeld-calibrate-seams-values): Turn a graded seam readings sheet into the seam measure's shares (method.quality.seam.scarf\_share, .corner\_share, method.quality.visibility.under): each one piece's score over its reference's, with where it came from; a share the readings cannot give is left out and said so.

Options:

- `-cf FILE|GLOB`, `--config-file FILE|GLOB` (repeatable): a config file (.toml or .json) or a glob of them; repeatable, read in order (a glob's matches in natural order); later files win; none given: every .toml file in plymeld's config folder (secrets.toml aside)
- `-cv PATH=VALUE`, `--config-value PATH=VALUE` (repeatable): one config value, e.g. job.priorities.speed=5 or plan.technique.flat.layer.height=0.2mm; repeatable; wins over every file
- `--machine KEY`: the machine that prints (hardware.machine.&lt;key&gt;; its loadout gives the plate, tools, and filaments); default: job.machine, else the only machine the config defines
- `-o FILE`, `--output FILE`: g-code file to write (required for a plate)
- `--filament NAME`: the filament to calibrate: any the config or plymeld's bundled set defines (e.g. pla-generic), loaded or not; a new one with --like; default: the one the machine's loadout puts on --tool
- `--like NAME`: for a --filament the config does not define: start it from this one's values (placeholders until measured)
- `--tool N` (default: `1`): the machine's tool (its index, from 1) to print with
- `--plate KEY`: the build plate (hardware.plate.&lt;key&gt;): its bed temperature for the filament; plymeld's own are pei and cool; default: the one loaded on the machine (hardware.machine.&lt;key&gt;.loadout.plate), else the only one the config defines itself
- `--no-level`: do not run the machine's bed levelling (hardware.machine.&lt;key&gt;.gcode.level) before the plate
- `-h`, `--help`: show the command's help and exit

### `plymeld calibrate seams readings`

Write the readings sheet to grade for a printed seam plate: an entry per piece with its kind, its grade '?'. Replace each '?' with hidden, faint, plain, or glaring (how far away its seam shows), then turn the sheet into the seam measure's shares with `calibrate seams values`.

Usage: `plymeld calibrate seams readings [OPTIONS] PLATE`

Arguments:

- `PLATE` (required): the seam plate's g-code, as `plymeld calibrate seams` wrote it

Options:

- `-o FILE`, `--output FILE`: write the sheet to this TOML file (default: print it)
- `-h`, `--help`: show the command's help and exit

### `plymeld calibrate seams values`

Turn a graded seam readings sheet into the seam measure's shares (method.quality.seam.scarf\_share, .corner\_share, method.quality.visibility.under): each one piece's score over its reference's, with where it came from; a share the readings cannot give is left out and said so. Add the written file with -cf.

Usage: `plymeld calibrate seams values [OPTIONS] READINGS`

Arguments:

- `READINGS` (required): the graded readings sheet, as `plymeld calibrate seams readings` wrote it and you graded it

Options:

- `-o FILE`, `--output FILE`: write the shares to this TOML config file (default: print it)
- `-h`, `--help`: show the command's help and exit
