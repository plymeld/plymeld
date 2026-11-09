# plymeld

<img src="docs/logo.svg" width="96" height="96" alt="the plymeld mark" align="right">

**A 3D print slicer that works out the best settings for your model based on your priorities, printer, and
filaments, then shows you everything and lets you override anything.**

Instead of a profile of hundreds of settings to tune by hand, plymeld starts from the model's
geometry, the facts about your printer and filament, and what you care about for this print (quality,
accuracy, speed, material, strength, and reliability). It weighs the options (pose on the bed, layer heights,
support, how each region is laid down, and the order of objects) and shows each choice with the facts and
alternatives behind it. What it chooses are settings you can see and change, each with the file and line it
came from; change or lock one, and the rest is worked out again around it.

**Not another fork.** plymeld is not descended from Slic3r, PrusaSlicer, OrcaSlicer, Bambu Studio, or
CuraEngine. It is written from scratch in Kotlin: its own geometry kernel, its own config format, and its own
planner.

## Status

**plymeld is pre-release.** Expect rough edges and missing pieces. Builds for Windows and Linux are on the
[releases page](https://github.com/plymeld/plymeld/releases); macOS is on the way.

**Bundled filament and build-plate values are starting points**, not measurements. The calibration plates
replace them with yours.

## What it does

[FEATURES.md](FEATURES.md) is the detailed list.

**Works out the settings and shows its work.**
- Picks each model's pose on the bed, its layer heights (one height, or following the surfaces), the
  technique for each region, whether and where to support, and the order of objects on a plate, scored
  against your priorities. `--explain` shows the facts, the alternatives, and why each lost.
- Measures what it can before printing: stair-steps, visible layer lines and seams, support scars,
  stringing risk, the weakest bond between layers, bed contact, time, and filament.

**Leaves you in control.**
- Settings can be overridden, bundled machine and filament values included, and `plymeld config` shows
  where each one came from and what it overrode.
- Lock any decision (a pose, a technique, a support spot, or a layer height), and the rest is planned around
  it. Values go per object and per part, and several printers live in one config.

**Prints with what you would expect, and some things you would not.**
- Classic and variable-width walls; sparse infill in rectilinear, grid, cubic, and gyroid; top and bottom
  surfaces monotonic, rectilinear, concentric, or Archimedean chords; adaptive layers; every print ending
  exactly on the model's top.
- Bridges with a graded overhang slow-down; tree support, decided overhang by overhang; **wave overhangs**,
  undersides over air grown outward from what is printed beside them, without support.
- Corner, back, and scarf seams; ironing; fuzzy skin; Z anti-aliasing of sloped tops (opt-in); arc fitting;
  exclude-object markers; one object at a time.
- Multi-color on a tool changer, with a purge tower and purging into the object's infill; several spools on
  one tool swapped by hand at a pause; pauses at a height or around an object; planned inserts (a pause
  above a pocket for a magnet or a nut).
- Pressure advance per machine, tool, or filament; a preview picture for the printer's screen; a prime line
  placed next to the print, so you don't forget to remove it.
- Curved techniques: curved top bands, layers that follow the underside, and lifted bridges.

**Checks before you print.** `plymeld slice` reads its own g-code back and checks it against the machine
(limits, retraction, travels, tools, start, and end) and against the model (lines outside it, and outlines
left without walls), and predicts time and filament.

**Calibrates.** `plymeld calibrate` makes melt, flow, first-layer, motion, filament, ironing, and seam plates, in
the order their results depend on each other; the readings go back in as your filament's and machine's values.

**Talks to printers:** `plymeld printer status | watch | camera | files | send | pause | resume | cancel`
through one interface, with adapters so far for Flashforge's LAN API and Moonraker (Klipper). `plymeld printer
loadout` compares the filaments a printer has loaded with your config. The first time a command reaches a printer
over SSH, a few lines starting `SLF4J:` about a "no-operation (NOP) logger" may appear: that is normal.

**Reproducible.** Every g-code file ends with the version, models, config, and plan it was made from, so it can
be made again exactly.

**Future plans include** an automatic filament changer (spools swapped by the machine); changing spool or
tool between objects on a plate; modifier, negative, and other non-solid 3MF parts; model formats other than
STL and 3MF; g-code flavors other than Klipper; more printer connections (OctoPrint, PrusaLink, Bambu, and
others); an open interchange format; and more of the desktop window. [FEATURES.md](FEATURES.md#what-may-come-next)
has what may come next.

## Quick start

### Install

From the [releases page](https://github.com/plymeld/plymeld/releases), nothing else needed:

- **Windows:** `plymeld-win-setup.exe`. It installs the window and the command line, and puts `plymeld` on your
  `PATH` for new terminals. Or the portable zip, which installs nothing: its top `plymeld.exe` opens the window, and
  the command line is `current\cli\plymeld.exe` (not on your `PATH`: run it by that path, or add the folder yourself).
- **Linux:** the AppImage for the window, and `plymeld-linux-x64.tar.gz` for the command line.

To hear about new releases: watch [Announcements](https://github.com/plymeld/plymeld/discussions/categories/announcements)
in the Discussions (a post per release), or the repository (Watch → Custom → Releases), or turn on plymeld's own check,
`plymeld updates --set notify`, which says a newer release after a command, at most once a day. `plymeld update`
installs it (the window's bar does too).

### Build from source

With **JDK 21** (the Gradle wrapper fetches everything else):

```
./gradlew :plymeld-cli:installDist            # -> plymeld-cli/build/install/plymeld/bin/plymeld
plymeld-cli/build/install/plymeld/bin/plymeld --version
```

`tools/build-native.sh` (`.cmd` on Windows) and `tools/build-native-ui.sh` (`.cmd`) build native executables
of the command line and the window with GraalVM; each script's header says what it needs.

### A first slice

`plymeld` below is the installed command line, or the launcher built above (put its `bin` folder on your `PATH`).
Set up your printers and filaments once (in the window: **Set up a printer**):

```
plymeld init                                   # asks about your printers and filaments, slices a test cube, writes your config
plymeld slice -m model.stl -o model.gcode      # plans and slices with that config
plymeld printer send --print model.gcode       # uploads it and starts the print
```

A clone of this repository also has the [3DBenchy](https://www.3dbenchy.com/) and other models in `test-models/` to
try things with.

## Documentation

| what | where |
|---|---|
| What plymeld is trying to be | [docs/philosophy.md](docs/philosophy.md) |
| Features, and what may come next | [FEATURES.md](FEATURES.md) |
| The published work plymeld builds on | [CREDITS.md](CREDITS.md) |
| What changed | [CHANGELOG.md](CHANGELOG.md) |
| Building, testing, and contributing | [CONTRIBUTING.md](CONTRIBUTING.md) |
| Reporting a security problem | [SECURITY.md](SECURITY.md) |

## Principles

- **General.** Built for any printer, model, and material.
- **Every value that changes the print is a setting**, with its source, and can be overridden, bundled
  printer models included.
- **Decisions come from the model's geometry, facts about the hardware, and your priorities and locks.**
  A model file supplies only the model.
- **Your priorities decide.** Choices come from the physics and geometry of the print, weighed by what you
  care about, and are checked in software against the model itself.

## Project layout

| module | role |
|---|---|
| `plymeld-geom` | pure-Kotlin 2D geometry: integer coordinates, polygons, boolean operations, offsetting, hatching, linking, and skeletons |
| `plymeld-mesh` | STL / 3MF readers, layer heights, and plane slicing into closed loops |
| `plymeld-model` | the concept model: entities, the settings catalog, and values with provenance |
| `plymeld-config` | config files (TOML / JSON, units, formulas, layering, and the schema), bundled defaults, filaments, and plates; importers of other slicers' settings, for `plymeld investigate` only |
| `plymeld-machine` | what a printer model is and accepts (limits, toolheads, and g-code commands with evidence), read from config; g-code dialects |
| `plymeld-core` | the decisions and the pipeline: slice → walls → regions → paths → print program, as cached stages; the read-back checks |
| `plymeld-program` | the print program: an ordered list of steps and events that a writer turns into g-code |
| `plymeld-gcode` | the g-code writer (Klipper), arc fitting, custom g-code templates, and the command check |
| `plymeld-predict` | print-time estimate, applied the same way to any slicer's g-code |
| `plymeld-printer` | talking to printers: one `Printer` interface, adapters for Flashforge's LAN HTTP API and Moonraker, and the loadout comparison |
| `plymeld-commands` | every command, declared once: the CLI parses against it and the window builds from it |
| `plymeld-cli` | the command line |
| `plymeld-check` | g-code reader, renders, calibration-plate pictures, and the test suites |
| `plymeld-ui` | the desktop window (Compose Multiplatform, an OpenGL 3D view through LWJGL) |
| `tools/` | build, packaging, regression, and measurement scripts |
| `test-models/` | models the tests slice, with independently computed reference values ([test-models/MANIFEST.md](test-models/MANIFEST.md)), and the config they slice with |
| `docs/` | what plymeld is trying to be, and the logo |

## Contributing

Bug reports, ideas, and pull requests are welcome: see [CONTRIBUTING.md](CONTRIBUTING.md). `plymeld report` makes a
bug report about what you are working on and opens the issue for you. Contributions
come under a contributor license agreement (CLA), accepted once through a bot on your first pull request.

## Safety

plymeld writes g-code that heats, moves, and extrudes on a real machine, and its `printer` commands can start, pause,
and cancel prints. Check what it makes before you print, stay with the printer while it runs, and keep within what your
machine and materials are rated for. plymeld comes with no warranty, and its license limits liability for any damage
it causes: see sections 15 and 16 of the [LICENSE](LICENSE).

## License

Copyright (C) 2026 Russell Tech Strategy LLC. plymeld is licensed under the **GNU Affero General Public
License, version 3 only** (`AGPL-3.0-only`): see [LICENSE](LICENSE) and [NOTICE](NOTICE), and
[LICENSING.md](LICENSING.md) for a plain summary. The libraries it includes keep their own licenses:
[THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).

To modify plymeld, or build it into a product, without publishing your source under the AGPL, a separate
commercial license is available from Russell Tech Strategy LLC by agreement:
[legal@plymeld.org](mailto:legal@plymeld.org).

#3DBenchy by CreativeTools, [3DBenchy.com](https://www.3dbenchy.com/), CC0 1.0.
