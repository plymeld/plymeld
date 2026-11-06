# Changelog

What changed in plymeld, for people using it. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

Versions are `0.YYYYMMDD.N`: the release's date (UTC) as one number, and N counting the releases of that day
(`plymeld --version`). Changes collect under Unreleased; a release is a tag, and the change after it gives that
section the release's version and date.

## [Unreleased]

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
  row's height, and each speed are printed on the plate, where marker dots were. Every tile touches its neighbors, and a
  frame of three layers over their walls is the edge to peel the plate by; the thin connectors between columns, which
  were hard to remove, are gone. The defaults are tighter: the filament's print temperature up to its maximum at 10 C
  steps (210, 220, and 230 C for the bundled generic PLA), three speeds around the first layer's (half, as set, and half
  again), and four heights around its height (one step below, two above, a step a tenth of the nozzle). The plate's
  conditions describe the new layout, so `first-layer window` reads only plates made from now on.
- `--help` names every option's value (`-o FILE`, `--layer MM`, ...), and `plymeld --help` lists `printer credential`
  and `investigate predict`, which it left out.

### Fixed

- `plymeld render-plate` crashed ("negative width") on a first-layer plate of many blocks, such as the default nine.

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
