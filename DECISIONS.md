# Decisions

Decisions about plymeld's design, from the first public release on, newest last. A decision is changed only by a new
entry that says which one it replaces.

The reasoning behind what plymeld already is lives with the code it explains. This file records what is decided from
here on.

Each entry:

```
## <number>. <the decision, in one line> (<date>)

**Context.** What prompted it.
**Decision.** What was decided.
**Rejected.** The alternatives, and why each lost.
**Consequences.** What changes, and what it costs.
```

## 1. A calibration plate's temperatures print coolest first side by side, hottest first stacked (2026-11-05)

**Context.** Plates that test several nozzle temperatures (the melt plate's columns, the first-layer plate's blocks, the
filament plate's bands) each chose their own order. A new plate needs a rule, with its reason, to choose by.

**Decision.** Where the temperatures stand side by side (columns, blocks), they print coolest first, with a wait (M109)
before each. A hotend heats under power but mostly cools on its own, so cooling is the slow direction. The nozzle has to
reach the coolest temperature from the one the plate's base prints at in any order, and coolest first makes that the only
cool-down. Where they are stacked (a tower of bands), they print hottest first. A band that fails (the extruder driven
past what the filament melts, most likely at the coldest temperatures) spoils everything above it, so the riskiest bands
go last, where a failure spoils only what remains.

**Rejected.** Hottest first side by side: it starts nearer a hot base's temperature, but every step after that is a
cool-down, so the plate cools from the hottest temperature to the coolest instead of from the base's temperature. Coolest
first in a tower: a failed cold band at the bottom ruins the whole tower.

**Consequences.** The melt and first-layer plates print coolest first and the filament plate's tower hottest first, as
before. A flat plate that replaces a tower, such as a flow-ratio plate split off the filament plate, prints coolest
first.

## 2. A calibration test read on one surface gets a column per temperature; the flow ratio is its own plate (2026-11-05)

**Context.** The filament plate read its flow-ratio patches in its tower of temperature bands: each band's row of patches
stood on a sparse pedestal as tall as the bands below it, so most of its plastic was never read, and the flow ratio, which
the plate's other tests depend on, was read alongside them instead of before them. The melt plate had just become a flat
plate with a column per temperature.

**Decision.** A test read on one surface (the melt rate's rows, the flow ratio's patch tops, the first layer's tiles) lays
its temperatures side by side, a column (or block) each, on one low plate. Where the surface stands on layers of its own,
those layers print at the filament's own temperature in every column, and only the read surface is laid at the column's
temperature, after a wait. The flow ratio leaves the filament plate for a plate of its own, `calibrate flow`, run after the
melt plate and before the plates that rely on it. Tests that need height at their own temperature (overhangs, bridges,
stringing) stay in the filament plate's tower.

**Rejected.** Each column printed whole, one after another, every layer at its own temperature: the nozzle would print
beside finished columns, which is safe only where the head clears them, and it travels more; the read surface is what
the temperature decides. Keeping the patches in the tower: tall pedestals of unread plastic, and a flow ratio read with
the tests that depend on it. Columns for the tall tests: the pieces are too tall for the print head to clear on many
machines.

**Consequences.** The flow plate prints in minutes instead of riding a tower; its patches below the top share one
temperature, so a difference between columns is the read surface's. The filament plate loses `--flow-ratios` and its flow
patches; the flow plate takes the option.
