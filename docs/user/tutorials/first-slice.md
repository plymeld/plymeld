# Your first print

This tutorial sets plymeld up for your printer and filament, slices a model, and prints it. It takes about ten minutes,
plus the print. You need plymeld [installed](install.md), a terminal, and a printer: plymeld ships descriptions of some
printer models, and a generic one for any printer running Klipper (`plymeld init` lists them).

## 1. Tell plymeld about your printer and filament

```
plymeld init
```

`plymeld init` asks its questions in the terminal (arrow keys choose, Enter confirms; Ctrl+C stops, and nothing is
written until the end):

- **Your printer:** which model it is, a name for it, and its values (its build area, speeds and start g-code), each
  offered with the model's own as the default (the generic Klipper printer has none for its build area and speeds:
  you type yours); its address on your network, if you want plymeld to send prints to it;
  the nozzle on each tool; and the build plate on it.
- **Your filaments:** each one's kind (PLA, PETG and so on: it starts from plymeld's own values for that kind), a name,
  and its color.
- **What is loaded where:** which filament is in each tool.

Before writing anything, it slices a test cube with your answers, then shows the files it will write and asks before
writing them. They go in plymeld's config folder (`~/.config/plymeld` on Linux, `%APPDATA%\plymeld` on Windows), where
every command reads them. If you gave your printer's address, it then asks the printer for its state, to show it can
reach it.

## 2. Get a model

Any STL or 3MF file. This tutorial uses the [3DBenchy](https://www.3dbenchy.com/), a small boat made for testing
printers (free to use, CC0): download `3DBenchy.stl` into the folder your terminal is in.

## 3. See how plymeld would print it

```
plymeld plan -m 3DBenchy.stl
```

plymeld works out how to print the model from its shape, your printer and filament, and what you care about (by
default, quality, accuracy, speed, material, strength and reliability count alike): how it stands on the bed, its layer
heights, how each part of it is laid, and whether it needs support. `plymeld plan` prints each decision with the facts
behind it and the alternatives it weighed. Nothing is written.

## 4. Slice it

```
plymeld slice -m 3DBenchy.stl -o 3DBenchy.gcode
```

`plymeld slice` plans the model as above, printing each decision it makes, and writes the g-code your printer runs. Then
it says how long the print will take and how much filament it uses, and reads the g-code back to check it against your
printer (its limits, retraction, travels, start and end) and against the model (no line outside it, no outline left
without a wall). It ends with the check's result: `the g-code reads back as the config and the model say`, or the
problems it found and a reminder not to print the file before they are understood.

## 5. Print it

If you gave `plymeld init` your printer's address:

```
plymeld printer send 3DBenchy.gcode --print
```

uploads the file, confirms it by the printer's own file list, and starts the print. The bed must be clear, the right
filament loaded, and someone with the printer. Without an address, copy `3DBenchy.gcode` to the printer the way you
usually do.

## What you did

- Your config: `plymeld config` shows every value plymeld used, where each was set, and what it overrode.
- Every g-code file plymeld writes ends with the version, the model, the config and the plan it was made from, so it
  can be made again exactly.

The [command-line guide](../../cli/README.md) shows how the commands fit together, and the
[command reference](../../cli/reference/README.md) lists every command and option.
