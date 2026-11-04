# Changelog

What changed in plymeld, for people using it. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

Versions are `0.YYYYMMDD.N`: the release's date as one number, and N counting the releases of that day
(`plymeld --version`).

## [Unreleased]

### Added

- `plymeld printer credential NAME`: sets a printer's credential (a check code, an API key) in the secrets file its
  machine's connection names, typed at a hidden prompt or read from standard input, never from the command line.
- A loadout's `tool.<key>.filament = ""` (or `slot.<n>.filament = ""`) marks that tool or slot empty, so a later config
  file can unload what an earlier one loads.

### Fixed

- `plymeld printer --help` said credentials come from environment variables only; they come from an environment
  variable or a secrets file, as `plymeld init` writes them.
- `plymeld printer loadout -o` now writes an empty tool or slot as `filament = ""`, so the file it writes brings the
  config in line with the printer; before, an empty one was only a comment.
- `plymeld edit` on a secrets file says to use `plymeld printer credential`, instead of refusing the key as an unknown
  config path.

## [0.20261026.0] - first public release

The first release. [README.md](README.md) says what plymeld does and [FEATURES.md](FEATURES.md) lists it feature by
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
  and the loaded filaments compared with your config.
- **A desktop window** beside the command line: a Windows installer (or portable zip) and a Linux AppImage, with the
  command line for Linux as its own download.
