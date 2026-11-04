# Changelog

What changed in plymeld, for people using it. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

Versions are `0.YYYYMMDD.N`: the release's date (UTC) as one number, and N counting the releases of that day
(`plymeld --version`). Changes collect under Unreleased; a release is a tag, and the change after it gives that
section the release's version and date.

## [Unreleased]

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
