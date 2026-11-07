# Changelog

What changed in plymeld, for people using it. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

Versions are `0.YYYYMMDD.N`: the release's date (UTC) as one number, and N counting the releases of that day
(`plymeld --version`). Changes collect under Unreleased; a release is a tag, and the change after it gives that
section the release's version and date.

## [Unreleased]

### Added

- A machine's Z step (`hardware.machine.<key>.motion.z.step`, its Z motor's step as configured): flat layers are held
  to it, their height a whole number of steps (the nearest) and every top on a step. A layer height between two
  multiples otherwise prints alternating between them, in bands. Unset, layers are as planned; curved bands, layers
  that follow the underside, and lifted bridges keep heights of their own.
- A purge tower stands steadier: a brim around its first layer by default (`plan.mechanism.purge.tower.brim.*`, as wide
  as the object's brim would be, whether or not the object has one), and each side at least half its height
  (`plan.mechanism.purge.tower.side_share.min`): a tower its purge would leave narrower is widened and deepened, its
  bands' lines beyond the purge laid at the sparse spacing. Both are placeholders, not tested on a print. The tower's
  bed and overlap checks count its brim.
- A config may spell a setting's name or a chosen value the British way: `colour` names `color`, and `centre` is read
  as `center` (`plan.placement`, a machine's `build.origin`, `--placement`), wherever the American spelling is what
  the setting takes. A key of your own (a filament called `grey`) is never respelled.
- The pre-print check refuses a layer with wave beads that leaves part of its area over air unlaid (a gap wider than a
  line between beads), saying how much, where, and with the layer's picture.

### Changed

- `plan.bridge_order = "one_at_a_time"` lays the bridges from the side the head reaches least (the tools' head size and
  nozzle position, else their machine model's), as objects printed one at a time are placed, so the head never passes
  over a finished bridge. It always went from +X, which suited only a head reaching least towards +X.
- G-code files are shorter: a line width or acceleration already in force is not written again, and no travel goes to
  where the head already is (a scarf seam's ramp, loop and down pass each wrote all three). The motion is unchanged:
  every move runs with the same width, acceleration and feed rate as before. On the 3DBenchy, 1,570 width lines,
  2,590 acceleration lines and 1,055 zero-length travels fewer.
- One seam vocabulary. `plan.seam` says where wall loops start, in the same words everywhere: `corners`, `back`,
  `least_visible` (were `corner_seam`, `back_seam`, `least_visible_seam`). Scarf seams are a switch beside it,
  `plan.technique.scarf_seam.enabled` (was `plan.seam = "scarf_seam"` with `scarf_seam.plain_style`, now gone).
  Each is locked on its own, or decided when unset: `plan.seam = "corners"` alone leaves scarf seams decided (set
  `scarf_seam.enabled = "false"` for none). Configs and plan files in the old words are refused; `plan -o` writes the new.
- A part whose own settings only restate what it would have anyway (a project's per-part keys repeating the object's)
  is laid with the parts beside it, as one area, with no walls or seams where they touch. It was laid part by part, each
  part walled on its own. A config already left such values out.
- The bundled filaments' idle temperature (a parked nozzle on a machine with several) is 100 C below printing, was
  40 C below (170 C for the bundled PLA), where a parked nozzle may ooze onto the print and the bed. The rise back to
  printing is the next tool's pre-heat (`hardware.tool.heat.time`) or its wait.

### Fixed

- A purge tower's first layer is laid at the first layer's speed, no faster than the model's own first layer. It was laid
  at the tower's own speed (the hidden lines', about 250 mm/s on a Creator 5 where the model's first layer went at
  100-120), and a tower came loose during a print.
- Wave overhangs: where the fronts close in from two sides (the roof over a narrow slot), the strip they leave between
  them gets a bead along its middle. It was left unlaid and not bridged: on the 3DBenchy, a strip 0.6 mm wide and 6 mm
  long over the stern's slot.
- Several lifted bridges in one object: the flat layers above a strip's end are laid onto the strip as onto a printed
  surface. They were planned as if over air, so wave beads or bridge lines were laid onto solid strip.

## [0.20261107.1] - 2026-11-07

### Changed

- Choosing a pose takes a first look at every pose under one plan (flat layers at the baseline's layer height) and
  searches in full only the as-given pose and those scoring within `method.search.pose.shortlist_margin` (0.1 per unit
  of weight, a placeholder) of the best there. `plan` lists each pose left out with its first-look score; unset the
  margin to search every pose. On the test models every choice is the same as before.
- A model of many parts slices faster, with the same g-code: the parts' outlines are merged layer by layer side by
  side, and the pose search merges each cross-section it reads once, not once per candidate plan. On a 29-part,
  4.7-million-triangle model the slice took half the time (54 s to 28 s) and the seam decision less than half. The
  search reuses one placed copy of the model per pose across its measures, and the retraction decision routes its two
  sides at once (on that model the pose search 154 s to 129 s, the retraction decision 27 s to 20 s).
- The pose search spends its time where a pose can still win: the overhangs of a pose are surveyed only when the pose
  is looked at in detail, and a pose whose best possible score cannot reach the best candidates (lying on its side,
  needing support under most of the model) is ruled out without its strength and time estimates. `plan` says which
  poses were ruled out and why. Where the search runs to completion it chooses as before.

## [0.20261107.0] - 2026-11-07

### Added

- A multi-color model printed with the filaments you have, without editing it: `model.slots` maps the filament numbers
  the model file gives its parts to the loadout's slots (`model.slots = { 4 = 1, 5 = 2 }`; `"*"` for every number; an
  object's own `model.object.<key>.slots` first), and `--slot N` on `slice` and `plan` prints every part from slot N. A
  part on a filament that is not loaded is refused with both named, and the window's Model step offers "Print every
  part from slot N". plymeld says which of the model's filaments print from another slot.

### Changed

- A 3MF whose parts are all on one filament is said to have its parts "all on one filament" (it said "sliced as one
  body", which depends on the config as well).
- Deciding how to print a model is faster: on 3DBenchy the pose and technique search takes about a quarter less time,
  with the same decisions and the same g-code. The slicer prepares each posed model once rather than for each cut, and
  finding what an overhang stands on looks only at the faces under it. On a large model, where the search stops at its
  time limit (`method.search.time.max`), it measures more candidates in that time.
- `plymeld calibrate ironing` and `plymeld calibrate seams` print their first layer at the filament's first-layer
  temperature (`temperature.first_layer`), as a print does, and the rest at its print temperature: the head waits,
  parked in front of the plate, for the print temperature before the ironing plate's top and before the seam plate's
  pieces. Each plate's check and page name both temperatures.
- `slice` from a plan file plans each object's layers once: the in-island retraction the plan locks is no longer
  planned for again before the slice. Where the layers do not all fit the layer cache (a large model), this saves
  planning the whole model a second time.

### Fixed

- `plymeld plan -o` wrote the first layer's height twice in an object's table when a first-layer window decided it,
  and `slice` refused the plan file ("previously defined"); it is written once, with the window's reason.
- The prime line no longer follows the first object's edge in between two of its feet (an arch's, a bridge's piers),
  where it lay under the span: a side whose line would turn in there is skipped for the next.

## [0.20261106.3] - 2026-11-06

### Added

- Command-line docs: a guide to how the commands fit together (`docs/cli/README.md`: setup, the config, slicing,
  the printer, calibration), and a command reference (`docs/cli/reference/`), a page per command with every option,
  written from the commands themselves. `plymeld commands --markdown DIR` writes the reference.

### Changed

- The calibration plates run melt, flow, first layer, motion, then the rest: the first-layer plate now comes before the
  motion plate, whose tower needs a first layer that holds. `plymeld calibrate --help` and its recommendations list
  them in that order.
- `plymeld calibrate motion`: every face's speed stands raised on the slab in front of it, read from outside that face,
  so the plate is read without its page; the slab reaches 10.6 mm past the cross on every side (4 mm before) to hold them. The arm
  notches take the size every plate's tally notches have. The slab's first layer prints at the filament's first-layer
  temperature (`temperature.first_layer`, worked out from `--print-temp` when given), and the head waits, parked, for
  the print temperature before the tower.
- `plymeld calibrate first-layer` prints a table in one piece, like the melt plate: a column per temperature and a row per
  first-layer speed and nozzle height, every tile a 40 mm strip with its lines along it. Each column's temperature, each
  row's height, and each speed are raised two layers on a two-layer base along the plate's front and left (its
  front-left corner cut off, as on the other plates), where marker dots were. Every tile touches its neighbors and the
  base, and a frame of three layers over their walls, with as tall a border round the base, is the edge to peel the
  plate by on every side; the thin connectors between columns, which were hard to remove, are gone. The page adds a way
  to read the tiles: once peeled, held up to a light, gaps between a tile's lines show as bright streaks and pinholes. The defaults are tighter: the filament's print temperature up to its maximum at 10 C
  steps (210, 220, and 230 C for the bundled generic PLA), three speeds around the first layer's (half, as set, and half
  again), and four heights from its height up (three steps above it, a step a tenth of the nozzle: 0.2 to 0.32 mm for a 0.2 mm first layer on a 0.4 mm nozzle). The plate's
  conditions describe the new layout, so `first-layer values` reads only plates made from now on.
- The first-layer window is two readings per temperature, the lowest good nozzle height (every lower one bad) and the
  fastest good speed (`"210 C" = { height.min = "0.28 mm", speed.max = "120 mm/s" }`), in place of a grade per tile.
  `plymeld calibrate first-layer values 210=0.28,120 ... --plate-gcode PLATE` writes it into the filament's file, checked
  against the plate and recorded for `plymeld journal --undo`; it replaces `first-layer window`. A window in the old
  grid form is refused: enter its readings again.
- `--help` names every option's value (`-o FILE`, `--layer MM`, ...), and `plymeld --help` lists `printer credential`
  and `investigate predict`, which it left out.
- `--help` marks a required option `(required)`, a subcommand that may be left out `[<command>]` (a plate runs without
  its `values`), and every option with a default says it in its help (`--tool`'s 1,
  `layers --layer`'s 0.2, ...).
- The window shows each command as `--help` does: a form's fields are labeled with the option as `--help` writes it
  (`-cf, --config-file=FILE|GLOB`, `--machine=<key>`) and described in its words, an argument as the usage line writes it
  (`[<prefix>]`, `<temp=flow>...`); a group's form lists its commands, each opening its own form, and offers Run only
  where the command runs on its own. The command reference (`docs/cli/reference/`) writes them the same way.
- The window's console shows every command it runs as a terminal would, `$ plymeld ...`, the job's own steps included.

### Fixed

- `plymeld render-plate` crashed ("negative width") on a first-layer plate of many blocks, such as the default nine.
- On Windows, a config glob (`-cf "C:\printers\*.toml"`) was refused ("Illegal char <*>") by every command, and the
  window could not open a job given one; a glob now reads on Windows as it does elsewhere, and the window opens the
  files it names.
- In the window, `--help` and `--version` printed nothing (a form's Run, or a line typed in the console).
- The window's Slice & check wrote another file than `plymeld slice` on the same job: the same toolpaths, but its source
  block left out the seams and the in-island retraction decided (the plan was decided twice, the second time over the
  first one's answers) and listed the filament's color among the values the print used (the window reads it to tint the
  view). The window and the command line now write the same file, byte for byte.
- In the window, the bar's search box cut its hint off mid-word, and the finder cut each hit's description at a fixed
  length mid-word; both now end in an ellipsis where they do not fit. A command's form said the job's files were filled
  in when no job was open.

## [0.20261106.2] - 2026-11-06

### Fixed

- `plymeld calibrate --help` said every plate writes an instructions page (the melt and flow plates do not yet) and
  left `melt values` and `flow values` out of the plates' write-back commands.

## [0.20261106.0] - 2026-11-06

### Added

- `plymeld calibrate flow`: the flow ratio on a plate of its own, after the melt plate. A column per temperature, a
  square patch per flow ratio (`--flow-ratios`), each patch's ratio and each column's temperature printed on the plate;
  only the patches' top layer is laid at the column's temperature, after a wait.
- `plymeld calibrate melt values TEMP=FLOW...`: a read melt plate's results written into the config file that defines
  the filament, as its maximum flow on the machine: a formula through every reading, straight between neighboring
  temperatures and flat past the ends, with the readings in the comment above it. The change is printed and checked
  first, `--dry-run` writes nothing, and `plymeld journal --undo` takes it back.
- `plymeld calibrate flow values TEMP=RATIO...`: a read flow plate's results, the best patch in each column, written as
  the filament's flow ratio in the same way: one value when the columns agree, else a formula through every reading.

### Changed

- Every move's flow keeps a buffer below the filament's maximum flow: `plan.mechanism.motion.melt.buffer`, 10 % by
  default (moves capped at 90 % of `melt.flow.max`), 0 % for none. Moves the melt rate limits print 10 % slower; the
  melt plate's rows, which probe the limit itself, are not held to it.
- `plymeld calibrate melt` prints a table: a column per temperature (10 °C apart by default), a row per flow from half
  the filament's expected maximum flow to twice it (`--flow-step`), each row's flow and each column's temperature
  printed on the plate. Its base prints at the filament's own temperatures; the columns are waited for one by one.
- The filament plate no longer has flow-ratio patches (`--tests flow`, `--flow-ratios`): the flow plate measures the flow
  ratio before the plates that depend on it.

### Fixed

- The filament plate's recommendation named the key to enter a measured melt rate under as `melt.flow.maxw`; it is
  `melt.flow.max`.

## [0.20261104.3] - 2026-11-04

### Fixed

- `plymeld init` drew its choice lists blank in a terminal that reports no width (a pseudo-terminal whose size was
  never set); it now draws them 80 columns wide there.
- `plymeld init` said Esc stops it; Esc does nothing in its lists and prompts. It now says Ctrl+C, which stops it at
  any question with nothing written.
- `plymeld init --folder DIR` ended by saying no `-cf` is needed; that is so only for plymeld's own config folder. For
  another folder it now says to pass the files with `-cf 'DIR/*.toml'`.

## [0.20261104.2] - 2026-11-04

### Added

- `plymeld printer send --print --level` asks the printer to level the bed before it prints, where its API takes that
  request (a Flashforge printer); `connection.start.level = true` on a machine makes it that machine's default, and
  `--no-level` prints without. A printer that levels from g-code instead (a Klipper printer, over Moonraker) refuses it
  before anything is sent.

## [0.20261104.1] - 2026-11-04

### Changed

- `plymeld init` sets up a Flashforge Creator 5 with Flashforge's own start sequence (the first tool selected and
  heated, the fans off) and an end that lifts clear of the print and switches every tool's heater off; a print speed of
  300 mm/s and a travel speed of 600 mm/s (the maker's figures); `M25` to pause; the 140x110 preview its screen shows;
  a nozzle limit of 320 C; and the shape of its heads.
- For any printer without its own start and end, `plymeld init`'s start now selects the print's first tool and
  heats that one (on a printer that selects tools by `T`), and its end lifts the head 10 mm clear of the print,
  within the build height, and turns the fans off.
- `plymeld init` offers travel at the printer's top speed rather than its print speed, and asks for its pause
  command, its preview pictures and each nozzle's top temperature where the printer's model names them.

### Fixed

- `plymeld slice` no longer calls a machine's `motion.acceleration.max` unused: the print and travel accelerations
  `plymeld init` writes are formulas of it.

## [0.20261104.0] - 2026-11-04

The first public release. [README.md](README.md) says what plymeld does and [FEATURES.md](FEATURES.md) lists it feature by
feature. In short:

- **Plans each print**: the pose on the bed, layer heights, the technique for each region, support, and the order of
  objects, weighed against your priorities (quality, accuracy, speed, material, strength, and reliability);
  `--explain` shows the facts, the alternatives, and why each lost.
- **Every value is a setting** with its source, bundled printer and filament values included; lock any decision and
  the rest is planned around it.
- **Slices to Klipper g-code** and checks the result against the machine and the model before you print.
- **Calibration plates** for melt, motion, filament, first layer, ironing, and seams, whose readings go back in as your
  filament's and machine's values.
- **Talks to printers** over Flashforge's LAN API and Moonraker: status, camera, files, send, pause, resume, cancel,
  and the loaded filaments compared with your config (`loadout -o` writes what the printer has, empty tools included,
  as a config file). Credentials stay out of config files: `plymeld printer credential` sets one in a secrets file,
  never from the command line.
- **A desktop window** beside the command line: a Windows installer (or portable zip) and a Linux AppImage, with the
  command line for Linux as its own download.
