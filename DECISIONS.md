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

## 3. Every move's flow keeps a buffer below the filament's maximum flow, 10 % by default (2026-11-05)

**Context.** A measured maximum flow is the highest flow that printed without gaps on a melt plate: a move run at it
has nothing to spare. With a measured value in place, a plate's supporting parts ran at about 92 % of it on a fast
machine, and ordinary slicing caps hidden lines at the maximum itself.

**Decision.** Every move's flow is capped at the filament's maximum flow less a buffer,
`plan.mechanism.motion.melt.buffer`, bundled at 10 % (capped at 90 % of it); a config may change it, 0 % for none. It
applies wherever the maximum flow caps a speed: slicing, the pose decision's time estimate, every calibration plate's
supporting parts and first layer, the first-layer plate's per-block limit, the motion plate's fastest speed. The tests
that probe the limit itself are left out: the melt plate's rows run from half the maximum flow to twice it, as the
config has it. The start template's `max_filament_flow` stays the filament's maximum flow, the filament's property.

**Rejected.** The buffer on the calibration plates' supporting parts only: two meanings of the usable melt rate,
depending on where it is used. No buffer (the rule before): no margin at a measured limit. A buffer per filament: a
filament with a reason for its own can be given one later; one setting first.

**Consequences.** Any job a melt rate limits prints those moves 10 % slower than before, the rest unchanged. The
regression jobs differ in speeds only. The motion plate's six speeds come out lower where the melt rate holds its
fastest.

## 4. The calibration plates run melt, flow, first layer, motion, then the rest (2026-11-06)

**Context.** Each plate prints within values the plates before it measured, so the order is a chain of dependencies.
The motion plate came before the first-layer plate. But the first-layer plate uses only the melt rate, the flow ratio,
and the machine's own speeds; the motion plate's 30 mm tower is shaken at up to the machine's full acceleration on a
slab that has to stay down.

**Decision.** The plates run melt, flow, first layer, motion, filament, ironing, seams: `calibrate`'s subcommands, its
help, and its recommendation list them in that order.

**Rejected.** Motion before the first layer (the order before): the tower's results would lean on a first layer not yet
measured, while the first-layer plate needs nothing from the motion plate.

**Consequences.** The flow plate prints its patches at a visible surface's speed, which the motion plate measures later:
a flow ratio read before the motion plate is read at the configured speed, not the measured one. Small for a flow
ratio; `calibrate flow` states the speed its patches print at.
