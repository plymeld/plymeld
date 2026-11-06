# plymeld: philosophy

This page is for anyone deciding whether plymeld is worth using or contributing to: what it is trying to be, and
why.

plymeld is early, and much of what follows is a goal. Every statement about plymeld carries a tag:

| tag | meaning |
|---|---|
| **[Built]** | in the code today; the source is named: a `FEATURES.md` row, a command, or a test |
| **[Partial]** | some of it exists; the text says which part |
| **[Planned]** | not built; the source is named: `FEATURES.md`, a 🎯 in a row or "What may come next" |

Where this page and `FEATURES.md` or the code disagree, `FEATURES.md` and the code are right.

## 1. What plymeld is

A 3D print slicer written from scratch in Kotlin. It takes models (STL, 3MF) and its own config, works out
how to print them, says why, and writes g-code. It has a command line and a desktop window, and is
pre-release. It is licensed under the AGPL-3.0-only, with a commercial license by agreement for closed-source
use.

## 2. Principles

### 2.1 A general product, not one tuned to anyone's setup

plymeld is built for any printer, model, and material: no rule, default, or constant may be justified because it
suits one printer, model, or profile.

- **[Built]** Help text, messages, and the documentation describe plymeld in general terms.
- **[Built]** The bundled filaments (PLA, PETG, TPU, ABS, ASA, PC, nylon, HIPS, PVA) and build plates are
  labeled **placeholders**: plausible, not measured.
  Calibration (3.5) replaces them with measurements.

### 2.2 Every value that changes a print is a setting, with its source

If plymeld supplies a value that changes the print, you can override it: bundled data, built-in defaults, and
numbers inside the planner alike. Each value records where it came from; a value not measured yet says
"placeholder".

- **[Built]** Config files in TOML or JSON, layered with `--config-file` / `-cf` and `--config-value` / `-cv`;
  the later source wins at the same path, and an object's or part's value beats the general one.
- **[Built]** `plymeld config` shows what the config adds up to: each value, the file and line that set it, what
  it overrode, and what each formula works out to.
- **[Built]** One schema declares every path; `plymeld schema` writes it as JSON Schema, so an editor checks
  config files as you type.
- **[Built]** Machine attributes are settings: build volume, motion limits, head geometry, start and end g-code.
- **[Partial]** Not every value is a setting yet: a few planner constants still live in the code, and the list
  of g-code commands a machine accepts comes from a bundled machine description that a config names rather
  than defines.

### 2.3 Decisions come from the model, the hardware and your priorities

plymeld decides how to print from three inputs: the model's geometry; facts about the machine, tool, plate, and
filament; and your priorities (weights on quality, accuracy, speed, material, strength, and reliability) with
anything you lock. A model file supplies only the model.

- **[Built]** `plymeld plan` decides each model's pose on the bed, its technique by height band, whether and
  where to support, and the order of objects on a plate, and says why; `--explain` shows the facts and the
  alternatives.
- **[Built]** `plymeld plan -o plan.toml` writes the decisions as a config file. Passed to `plymeld slice`,
  nothing is decided again; editing a value in it **locks** that decision, and the rest is decided around it.
- **[Built]** The product commands read only plymeld's own config. Another slicer's settings are read only by
  `plymeld investigate`, which exists to compare plymeld with that slicer.
- **[Partial]** The settings for layered printing (walls, infill, speeds) are groups carried over from the first
  pipeline, marked INHERITED in the schema; they are being re-derived from what a layered print physically
  needs.

### 2.4 Settings organized by what they describe

Mixing different kinds of information in one bag of settings makes them hard to find and to reason about.
plymeld separates five kinds of value:

| kind | answers | example path |
|---|---|---|
| Model | what to make | (the model file; nothing else comes from it) |
| Hardware | what you have: machine, plate, tools, filaments, what is loaded where | `hardware.filament.pla-red.melt.flow.max` |
| Job | what you want this time: priorities, hard requirements, which techniques may be used | `job.priorities.quality = "10"` |
| Plan | how to build it: decided by plymeld, lockable by you | `plan.technique.flat.layer.height = "0.2 mm"` |
| Method | how plymeld's own algorithms behave: tolerances, search effort | `method.search.time.max = "120 s"` |

- **[Built]** The five kinds, one path per value, units on values (`"0.2 mm"`, `"4 h"`), formulas in place of
  numbers.
- **[Partial]** The desktop window reads its settings pages from the same schema; more of the window is to
  come.

### 2.5 Your priorities decide

Choices are weighed by what you care about for this print. The order in which plymeld approaches a print problem:

1. what the print needs, from physics and geometry;
2. evidence in software, judged against the **print**;
3. where only a print can settle it, a test print with its outcomes written down in advance (3.4).

- **[Built]** The pre-print check judges the g-code against the model: lines outside it, outlines left without
  walls.
- **[Built]** Examples of plymeld's own rules: overhang speed graded smoothly as more of a line hangs over air,
  rather than in steps; every print ending exactly on the model's top, with no setting needed; vertical shells
  by the least plastic that meets every stated thickness in every direction.

### 2.6 From scratch, not a fork

plymeld is not descended from Slic3r, PrusaSlicer, OrcaSlicer, Bambu Studio, or CuraEngine. It is written from
scratch.

- **[Built]** Its own 2D geometry kernel in pure Kotlin, on a 1 µm integer grid with exact integer predicates;
  no third-party polygon library. Tested on hand cases, invariants, and frozen answers from an independent geometry
  library (GEOS) for random polygons.
- **[Built]** Every dependency must allow both the AGPL and the commercial license; the stack is Gradle, Kotlin,
  Clikt, kotlinx-serialization, tomlj, and kotest; Compose Multiplatform and LWJGL for the window; and Apache MINA
  SSHD for SSH to a printer.

### 2.7 A command line and a window from one model of the commands

- **[Built]** The command line: `slice`, `plan`, `config`, `schema`, `layers`, `calibrate`, `render-plate`,
  `printer`, `edit`, `journal`, `commands`, and `investigate`.
- **[Partial]** The desktop window, built from the same declaration of commands so the two cannot differ: open
  or drop models, the job's steps (model, printer, what matters, plan), settings pages with each value's
  source, a finder over commands and settings, asking the printer, sending, updates.

### 2.8 Layers are one way to build, not the only one

Printers execute moves, not layers. plymeld treats planar layers as one **print technique** among several,
chosen per region of an object together with its pose.

- **[Built]** Flat layers, of one height or following the surfaces; the hidden core in thicker layers than the
  walls; planned inserts (a pause above a socket, the next layer laid onto what was placed).
- **[Built]** Surface techniques: wave overhangs (undersides over air grown outward from what is printed beside
  them, without support), ironing, fuzzy skin, Z anti-aliasing of sloped tops (opt-in).
- **[Built]** Curved top bands, layers that follow the underside, and lifted bridges; they need the tool's
  nozzle angle and height set.
- **[Partial]** The core still plans layer by layer; a move-based core is planned ([FEATURES.md](../FEATURES.md#under-the-hood)).
- **[Planned]** A seamless outer wall, staggered walls, spiral (vase) mode, conical layers, free-air strands.

## 3. How plymeld checks its own work

### 3.1 Output read back before printing

- **[Built]** `plymeld slice` and `plymeld calibrate` read their own g-code back and check it against the config
  (machine limits, retraction, travels over visible lines, tools, start, and end) and against the model (lines
  outside it, outlines left without walls). A layer that fails gets a picture; a file that fails is reported as
  not to be printed until the problems are understood.
- **[Built]** The g-code writer checks every line against the commands the machine is known to accept, and
  refuses a command with no evidence behind it.
- **[Built]** Time and filament are predicted with one estimator, applied the same way to any slicer's g-code.

### 3.2 Checks that can fail

The test suite is a deliverable in its own right: every check has a control case, something deliberately wrong
that it must catch; a feature's presence is asserted, not assumed; a reference must be independent of the code
under test (reference values computed outside plymeld: the Benchy's mesh facts, the geometry kernel's answers on
random polygons); the same model, settings, and plan file give byte-identical g-code (a plan search stopped by its
time limit can end elsewhere on a busier machine, so a plan written with `plan -o` is what repeats exactly).

- **[Built]** Determinism is asserted for incremental re-slicing, which must match a from-scratch slice byte
  for byte, and `tools/regress.sh` compares a fixed set of slices byte for byte with a baseline (the version that
  wrote each, and where each value sits in its config file, left out).

### 3.3 Incremental re-slicing

The pipeline is a graph of cached stages, each keyed on the model and the settings it reads, so a settings
change re-runs only the stages it affects.

- **[Built]** Cached stages, tested to re-run exactly the expected stages and give the same output as a fresh
  slice; a slice session kept across edits in the window.
- **[Partial]** Re-slicing whenever a file changes (`--watch`) exists only on `plymeld investigate slice`.

### 3.4 Test prints are experiments

A test print happens only after the software suite passes, and each one must teach something whichever way it
turns out: the question and why software cannot answer it; the possible outcomes written down in advance,
failure modes included; a test piece designed so the outcomes look different; what to measure; and how the
result is written back.


### 3.5 Calibration that measures, printed exactly as specified

plymeld's physical values are placeholders until measured: how fast a filament melts, how far a line holds out
over air, how fast a visible wall may print. Calibration plates measure them. A plate is **printed exactly as
specified**: none of plymeld's usual decisions apply to it, and its g-code is read back to confirm each cell's
speed, flow, temperature, and fan.

- **[Built]** `plymeld calibrate`: melt, flow, first-layer, motion, filament, ironing, and seam plates, in the
  order their results depend on each other; every plate but the melt and flow plates with an instructions page.
- **[Partial]** Reading results back: the melt rate and the flow ratio are written from the readings given as
  arguments; the first-layer window, the ironing values, the seam shares, and the machine's visible speed and
  acceleration from graded sheets; the filament plate's results are not read back yet.

## 4. Goals

What plymeld sets out to get right, and where each stands:

| goal | plymeld |
|---|---|
| Efficient in memory and CPU | **[Partial]** flat primitive arrays, an integer grid, per-layer stages on all cores with identical output; memory and time budgets checked in CI are planned |
| No crashes or hangs on large files | **[Partial]** progress counted in layers with a cancel, a bounded per-layer cache; streaming throughout and mesh fuzzing planned |
| No lag on a minor setting change | **[Partial]** cached stages (3.3) |
| Filament sync with printers | **[Partial]** `plymeld printer loadout` reads what a printer has loaded and compares it with the config (Flashforge's LAN API; the QIDI Box over SSH on a Moonraker machine) |
| Sane settings organization | **[Partial]** the settings model and schema (2.4); the window's settings pages |
| An open, documented interchange format | **[Planned]** |
| Transparent printer connectivity | **[Partial]** `plymeld printer` over Flashforge's LAN API and Moonraker, each command saying what it sends |
| No fragmented toolpaths (a surface broken into micro-islands, each with its own walls, fill, and seams) | **[Partial]** a shared fill grid, a near-monotonic sweep order, loops for narrow regions; a fragmentation metric is planned |

## 5. Today's feature set at a glance

A summary of plymeld's column in [FEATURES.md](../FEATURES.md), which has the details.

| feature | plymeld |
|---|---|
| Classic and variable-width walls; sparse infill rectilinear, grid, cubic, gyroid; top and bottom monotonic, rectilinear, concentric, Archimedean chords | **[Built]** |
| Tree support, decided overhang by overhang; support locked on a spot | **[Built]** (no painted enforcers or blockers) |
| Bridges, internal bridges, graded overhang speed; wave overhangs | **[Built]** |
| Layer heights following the surfaces; every print ending on the model's top | **[Built]** |
| Corner, back, and scarf seams | **[Built]** |
| Ironing; fuzzy skin; Z anti-aliasing | **[Built]** (some ironing and fuzzy-skin modes not yet) |
| Arc fitting; exclude-object markers; one object at a time | **[Built]** |
| Pauses; planned inserts | **[Built]** |
| Per-object and per-part values; several printers in one config | **[Built]** |
| Multi-color on a tool changer with a purge tower; purge into infill or the object; several spools on one tool swapped by hand at a pause | **[Built]** |
| Pressure advance per machine, tool or filament (none set: the firmware's own, nothing written) | **[Built]** |
| Calibration plates | **[Built]** (some results not read back yet) |
| Talking to printers | **[Partial]** (Flashforge's LAN API and Moonraker so far) |
| An automatic filament changer; changing spool or tool between objects | **[Planned]** |
| G-code flavors other than Klipper; model formats other than STL / 3MF | **[Planned]** |
| Non-planar layers beyond the curved techniques; free-air strands | **[Planned]** |

## 6. Should you use it, or contribute?

- **Using it to print:** it is pre-release. The bundled hardware values are placeholders until you calibrate,
  and the layered-printing settings are still being re-derived. Every file is checked before it is offered for
  printing, but expect to look over what it makes before you print it.
- **Contributing:** welcome; see [CONTRIBUTING.md](../CONTRIBUTING.md). Contributions come under a contributor
  license agreement. What may come next is in
  [FEATURES.md](../FEATURES.md#what-may-come-next).

## 7. Where to read more

| topic | file |
|---|---|
| Feature by feature, and what may come next | [FEATURES.md](../FEATURES.md) |

