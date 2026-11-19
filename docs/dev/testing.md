# Testing notes

Notes for working on plymeld's tests. Every test reads only what is in the repository: models built in code, the
models in `test-models/`, and test resources.

## The catalog of small models

The catalog ([Catalog.kt](../../plymeld-check/src/test/kotlin/plymeld/check/catalog/Catalog.kt)) is one list of small
models built in code, each with a stable name and a line saying what it is for: one technique, or one known weakness
(a lens growing from one line to two, a stroke about as wide as two lines become three, a web between a hole and the side, a thin ridge, a sliver of one filament on
another's face, an inlay on a leaning face, small teeth hanging off a second part's edge, slivers of one filament ending inside another's area, seams on a cylinder and a post, an overhang ladder, bridges, sloped and
domed tops, an oval's top laid in round rings, a stepped top whose fills of several kinds share edges, a small pillar, holes and pins). Each is sliced with a rig (the machine and plan values over the slicing
tests' setup) under a few rows of settings that matter to it, the first always the rig as it is.

It is for catching side effects during development, not for judging a printer. `CatalogTest` slices every model
under every row, one after another, and measures each file: the pre-print check as `slice` runs it, and its laid-twice
measure as `slice` reports it (the spots, their area, the layers holding one, the widest); for the walls cases,
the uncovered area, plastic outside, plastic laid twice, paths and the shift between layers; the seam scar, seams,
scarf seams and jogs; unretracted travel over open air and across tops, visible restarts, retractions; filament and
predicted time; the paths and length of every feature type; and the jagged lines: extruding moves shorter than the
slicing tolerance (all, and the outer wall's), the outer wall's kinks (a turn of more than 150 degrees beside a move
shorter than the outer line's width), and of those its zigzags (the line's direction over a line width before the kink
and after it within 120 degrees: it goes on the way it came, where a tip's run-in goes back where it came from; nor a
square end's corner, where the loop turns a right angle into the corner and goes back within two widths). Planning
time is not among them (it varies from run
to run). The measures are compared with the recorded baseline,
[baseline.tsv](../../plymeld-check/src/test/resources/plymeld/check/catalog/baseline.tsv): one line per model, row
and measure, sorted, so a change to it reads as a plain diff.

The models' own limits stay with their techniques' tests (`WallsByWidthTest`, `ThinRidgeTest`, `ExposedSliverTest`,
`SpeckNearPartTest`), which build their models from the catalog. `CatalogTest` holds the pre-print check: every
model passes it under every row, or is refused only as its entry names. A named refusal is a finding not yet fixed;
the test turns red when it goes, and the entry is updated with the fix. It also holds the outer wall of the models made
of straight sides and true arcs (the block, posts, cylinder, holes, pins, fins, decks, slopes, dome, oval, pillar and
the two-color piece) to no move shorter than the slicing tolerance and no kink, and the outer wall of the models of narrow
islands laid by their beads (the lenses, wedge, strip, ring, blob, letter E, pointed blob, bent stroke and teeth) to no
zigzag (a tip's line runs in and turns back, and a square end's corners are run into, by design, so their kinks are
held by the baseline, as are the other features).

**Adding a model.** Build it in
[Models.kt](../../plymeld-check/src/test/kotlin/plymeld/check/catalog/Models.kt) (synthetic, in code, never a user's
model; [Shapes.kt](../../plymeld-check/src/test/kotlin/plymeld/check/catalog/Shapes.kt) has prisms, lofts, tubes,
blocks with holes, and slabs for a solid described by its section at each height). Add an entry to `Catalog.entries`
with its name, what it is for, its rig, and only the rows that matter to it: each row is one more slice in the fast
suite. Then accept the baseline (below) and read the new lines.

**Reading a failure.** The message lists every measure that moved, old -> new, by model and row; a measure not in the
baseline; and a baseline line no model measures any more. Every measure is exact: the slice is deterministic, so any
move is a change of the slice. Explain each move as a side effect of the change or as intended before accepting it.
The test also slices every unit side by side on the parallel pool first, as the JVM's first work, and fails for any
unit that reads otherwise than one after another: a slice that reads another's working state. Such a fault may show
only while the code still runs interpreted (the JIT compiler can keep a shared buffer's values in registers), so it
comes and goes from run to run; the code at fault gets a test of its own that does not depend on luck
(`SlicerThreadsTest` runs the mesh slicer's threads in a JVM of their own with its code kept interpreted).

**Accepting a change.** Run

```
./gradlew :plymeld-check:test --tests 'plymeld.check.catalog.CatalogTest' -Pplymeld.refreshCatalogBaseline
```

which writes the run as the new baseline, then read the file's diff and commit it with the change that moved it.
