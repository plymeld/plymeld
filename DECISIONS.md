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

## 5. The first-layer window is two readings per temperature: the lowest good height and the fastest good speed (2026-11-06)

**Context.** The window recorded a grade per tile (good, marginal, or bad) in a table per temperature, and the first
layer was worked out from the grid. Only "good" ever counted: a marginal tile was treated as a bad one, so the third
grade was work that changed nothing. On a printed plate the grades came out alike across speeds and temperatures, and
grading every tile took longer than reading the two values that decided the first layer.

**Decision.** Per temperature, the window holds the lowest nozzle height graded good, every lower one bad, and the
fastest speed graded good (`"210 C" = { height.min = "0.28 mm", speed.max = "120 mm/s" }`). The first layer takes that
height, raised to the lowest the tool lays and not used when above the highest, at that speed; between two measured
temperatures, the higher height and the slower speed. `calibrate first-layer values` takes them as arguments, as the
melt and flow plates' readings are, checks each against the plate's g-code (a height and speed it tried, a tile it
printed), and writes them into the filament's file, recorded for `plymeld journal --undo`.

**Rejected.** The grid of grades (the form before): more to grade, a grade that counted for nothing, a sheet to fill in
and add to a config by hand. The lowest good height alone, the speed left as configured: the plate measures the speed
too, and a filament's first layer can need a slower one. A lowest good height per speed: more to read for a plate whose
heights agree across speeds, as they did when printed.

**Consequences.** A window in the grid form is refused; its readings are entered again with `first-layer values`. The
window no longer records a range of good heights that narrows as speed rises. `first-layer window` (the blank grid) is
gone.

## 6. A command reads the same in the window, the reference, and --help; the window's tests drive it as a user does (2026-11-06)

**Context.** The window's command forms, the command reference, and `--help` each wrote an option their own way
(`--config-file=FILE|GLOB`, `` `-cf FILE|GLOB` ``, `-cf, --config-file=FILE|GLOB`), named arguments differently
(`<PREFIX>`, `PREFIX`, `<prefix>`), and only two of the three said which options were required or what their defaults
were. The window's own checks read its code, not what it shows: `--help` typed in its console printed nothing, and no test
saw it.

**Decision.** `--help` is the one rendering. The command model carries each option's term and description and each
argument's term as Clikt's help formatter writes them, and the window's forms and the reference show those, nothing of
their own beyond what a form needs (several values in one field). `--help` tags required options, and an option with a
default says it in its help (a test holds every option to it). The window is tested by driving it through its own
controls, with Compose's UI test framework (JetBrains, Apache-2.0, test-only): every command opened from the finder and
its form read against `--help`; forms filled and Run, and the job's steps sliced, against the CLI given the same words.
The same tests run on Windows' JDK (`tools/windows-ui-tests.ps1`).

**Rejected.** Each surface rendering from the model's raw names: three renderings drift, as they had. Clikt's default
tags appended for defaults: many helps already say their default, so it would be said twice. Testing the window by
reading its code: it missed what a user sees.

**Consequences.** A new option shows the same everywhere with no work of its own; its help must state its default.
The window's tests take minutes (they slice), run with the UI module's tests.

## 7. A model's filaments reach the loadout through a slot map; `--slot N` prints every part from one (2026-11-06)

**Context.** A 3MF puts each part on a filament number. A number the machine's loadout lacks was refused, and the only
way round it without editing the model was a slot for each part (`model.object.<key>.part.<part>.slot`): 29 lines for a
29-part, five-color model printed in one filament.

**Decision.** `model.slots` maps the model file's filament numbers to the loadout's slots (`{ 4 = 1, 5 = 2 }`, `"*"` for
every number), and `model.object.<key>.slots` does the same for one object, over it. A part's own slot wins over both, a
number's own entry over `"*"`, and a number in no map prints from the model's own. `--slot N` on `slice` and `plan` is
`model.slots."*" = N` for the run, after the `-cv` values. The refusal of an unloaded filament names both, and the
window's Model step offers the refusal's `--slot N`. The setup and the pre-print check's tool list read a part's slot
through one function (`ConfigSetup.partSlot`).

**Rejected.** One slot per object only: it prints one color and cannot fold five colors onto two filaments or renumber
a model to the loadout. An option alone: it lives in no config file. Folding unloaded numbers onto slot 1 by itself:
it guesses which color matters, where a refusal that names the fix lets the user choose.

**Consequences.** Like `-cv`, `--slot` is not written into a plan file: a plan made with it is sliced with it again, or
with `model.slots` in a config file. Parts mapped onto one filament are sliced as one body where their settings agree.

## 8. The pose search surveys a pose's overhangs only when it details it, and screens poses by their best possible score (2026-11-07)

**Context.** On a 29-part, 4.7-million-triangle trophy with one large flat bottom, the pose search took 220 s of a
5-minute plan. Its first round surveyed support's overhang regions in every pose under every plan, though its candidates
hold no chosen support and their scores do not read the regions; only the poses it details use them, and it detailed
one of the 14 it measured. Then it detailed poses best first until the time limit, with no test of whether a pose could
still win: poses lying on their side, needing support under most of the model, were detailed like the upright one.

**Decision.** The first round leaves the survey for later where support is decided per overhang; a pose is measured
again with it when it is detailed, the same plans giving the same values. Before a pose is detailed, its best possible
score is worked out: its own measures as they are (bed contact, tip, slenderness, height, the first layer's islands);
every measure a plan or support changes at its best (stair-step, height error, border, rough top, support's plastic at
0; layer lines at the thinnest layer; time at one minimum layer time per layer at the thickest; the weakest bond at most
the footprint's box); and what needs support at the thinnest layer costs at least the smaller of droop's and scars'
weights times ln(1 + area / unit), since each face droops or scars, its droop counting at least its scar, and the unit
log is subadditive. A pose whose best possible score is not below the third candidate's among those detailed (the
finalists' full slices reorder the top three) is ruled out, with the bound and the bar in its reason. The as-given pose
is never ruled out (it has standing under method.search.pose.keep_margin).

**Rejected.** Comparing with the best candidate only: a pose that could be second or third would miss the finalists'
slices that reorder them. The first round's best value of each plan-dependent measure as its bound: a mix of techniques
can do better than any single plan the first round measured, so that bound could rule out a pose that wins. A search on
a simplified copy of the mesh: faster still on large models, but its measures would no longer be the model's.

**Consequences.** Where the search runs to completion it decides as before: the bound is a lower bound
(PoseScreenTest checks every ruled-out pose against the search without the screen). Where the time limit cuts it short,
the time goes to poses that can still win. The explanation lists the poses ruled out and why.

## 9. A first look at every pose shortlists the poses the search measures in full (2026-11-07)

**Context.** Choosing a pose took minutes on a large model: every pose was measured under every plan, and the screen
(decision 8) cannot rule out a pose whose sound bound is weak, which on a 4.7-million-triangle trophy left two tilted
poses to be detailed at half a minute each. Slicers that choose a pose by the faces alone do it in seconds; plymeld's
search weighs far more, but most of it is wasted on poses that are plainly worse.

**Decision.** Before the search, every pose is measured under ONE plan (flat layers at the baseline's height, no
support chosen, no overhang survey) and scored against the baseline. The search measures in full only the as-given pose
and the poses scoring within `method.search.pose.shortlist_margin` of the best there, per unit of the weight in effect,
as `keep_margin` is read. The pose decision says how many poses the first look shortlisted and each one's score there,
and lists the poses left out with theirs. Unset, every pose is searched.

**Rejected.** A shortlist of a fixed count of poses: on the trophy every usable pose would have been in it (the rest have
no flat face on the bed), so it saved nothing where it was most needed; a margin follows the scores. Scoring support's
droop and scars as one area, so the screen's bound grows strong enough to rule the tilted poses out soundly: it changed
the support chosen under quality-heavy weights (13 support spots on a 3DBenchy where none were chosen), a change to
what the search prefers, not only to its speed.

**Consequences.** A heuristic, unlike the screen: a pose that looks poor under one plan but would win with its own plans
and support is not searched. The placeholder 0.1 was set from the test models under six weightings: the chosen pose sat
at most 0.068 from the first look's best (the coupon), and every choice is kept. Where the first look measures nothing a
weighting cares about (speed and strength: no time or bond estimate yet), every pose ties there and all are searched.

## 10. One seam vocabulary: `plan.seam` says where loops start, scarf seams are a switch beside it (2026-11-07)

**Context.** Where wall loops start was said three ways: the lock `plan.seam` took styles (`corner_seam`, `back_seam`,
`least_visible_seam`, `scarf_seam`), scarf seams' plain style (`plain_style`) took the same styles, and the
pipeline read a third setting (`seam_start`), with yet other words (`corners`, `back`, `least_visible`). Scarf seams
were one of the styles, so locking them also locked a placement (through the plain style), and locking a placement
locked scarf seams off: neither could be locked while the other was weighed.

**Decision.** One word per placement, `corners`, `back`, `least_visible`, used alike by the lock, the plan file and the
pipeline: `plan.seam` holds it, locked or decided. Scarf seams are a switch, `plan.technique.scarf_seam.enabled`, beside
their `where`, as the other techniques' switches are. Each is a lock of its own: unset, it is decided per object, and the
seam decision weighs only what is not locked, against the same baseline (the back, no scarf). `plain_style` and
`seam_start` are gone: a scarf seam starts where `plan.seam` starts the loop.

**Rejected.** Keeping scarf seams as a style of `plan.seam`: it ties two independent choices into one lock. Old names
kept as aliases: plymeld has no users to migrate yet, and two names for one value is the inconsistency being removed.

**Consequences.** `plan.seam = "corners"` alone no longer means "no scarf seams": scarf seams are then decided; set
`plan.technique.scarf_seam.enabled = "false"` for none. Configs and plan files in the old words are refused as unknown
values or keys.

## 11. A region of an object is chosen by geometry, and its values apply segment by segment (2026-11-07)

**Context.** A value could be scoped to the plate, an object (or one copy), or a part by its 3MF name. Nothing pointed at
part of an object the file does not name: slowing one side, a surface technique on one face, support under one ledge.
And a part with settings of its own is laid as its own unit, walled on its own, even for a value that changes no geometry.

**Decision.** A region is `plan.object.<key>.region.<key>`, chosen by geometry and never by index: a box in the model's own
coordinates (turned with the pose, the turned box's bounding box), a height range as printed from the object's bottom,
the faces its lines lay (`facing`, in the words of `plan.faces`: `flat_top`, `sloped_top`, `side`, `under`), or several of these, a line being in it where every
one given holds. Its values are a scope of their own (`Schema.Scope.REGION`): only the values the code reads per region
may be set there, the motion caps for now; any other is refused, as a value under any scope the code does not read.
A region's caps apply segment by segment: a path crossing a region's side gets a vertex there, and each segment carries
the caps of the regions holding it. A path stays one path and a loop a loop.

**Rejected.** Cutting each path into pieces at the region's sides, the pieces in it capped: simpler in the writer, but a
first layer's outer wall split into open pieces was no longer a loop, and the prime line, which looks for one, was left
out (found by the test's plastic count). A region as a part (a mesh volume): the geometry of a box, a height range, and
the faces covers the cases asked for without a modeled volume, and a part as a region is a later step (#31's remaining
case). Facing by a direction vector: the faces' kinds are the words the config already uses for surfaces.

**Consequences.** Lines in a region are written segment by segment, not fitted to arcs. The quick time estimates and the
minimum-layer-time estimate do not count a region's caps (the full prediction, from the g-code, does). A line does not
say whether the top it lays is flat or sloped, so `flat_top` and `sloped_top` both select top surface lines. More values
per region come one by one, each with the code that reads it.

## 12. Support rules are region values; the switch keeps its three states (2026-11-07)

**Context.** Support could be locked on a box (a locked spot, `locked.<key>` under `plan.support`) but never kept off one, and the region
selector (entry 11) now chooses parts of an object by geometry. The switch `plan.support.enabled = true` supported every
face that needs it, with no way to spare one.

**Decision.** A region's `support = "always"` replaces the locked spot (every downward face in it supported, a
requirement); `support = "never"` keeps support off a region, whatever the switch or the weights say, and its faces'
droop is counted as unsupported area. The switch keeps its three states: unset = weighed per region, `true` = every face
that needs support, `false` = none (with an `"always"` region refused). Exceptions are regions, never another switch.
An `"always"` and a `"never"` region over the same faces are refused. `facing` is not read with a support rule.

**Rejected.** Keeping the locked spots beside the regions: two ways to say the same thing. `true` meaning "allowed,
weighed": it nearly repeats unset, and loses the plain "support on". A second support setting choosing "weighed" beside
the switch: unset already weighs.

**Consequences.** A config with a locked spot is refused (no other users yet, so no migration). A plate-wide
locked spot is gone: each object names its own regions. A blocker keeps support off its faces, but a tree branch may
still pass through the blocked region's air (not routed around it yet).


## 13. The purge tower stands beside the object unless placed; its first layer has a speed of its own (2026-11-09)

**Context.** The purge tower stood at a fixed share of the bed (70 % of its width and depth) unless a config placed it,
wherever the object was. A two-color print's tower, placed at the bed's front-left corner far from the object, peeled off
there twice while the object's first layer held; its brim had the object's first-layer height, flow and speed, so the
place was the difference left. Most beds are flattest, and best leveled, near the middle, where objects go.

**Decision.** With `plan.mechanism.purge.tower.x` and `.y` unset (the default), the tower stands beside the object: on
its right, back, left, then front, the first side where it fits on the bed, centered on the object along that side and
moved along it to stay on the bed, `plan.mechanism.purge.tower.gap` clear of the object and its brim (its own brim
counted in the tower). Set, both are kept as given; one without the other is refused. The tower's first layer, its brim
included, has its own fastest speed, `plan.mechanism.purge.tower.first_layer.speed`, the object's first-layer speed by
default.

**Rejected.** Keeping a fixed place on the bed: it puts the tower wherever the bed is worst for a given object's place.
Placing it by the bed's measured flatness: plymeld has no bed mesh to read. Slowing the tower's first layer by default:
no measurement says by how much; the setting allows it where a print needs it.

**Consequences.** Prints with a tower move it next to the object (the plate's g-code changes where it has one). The
gap's 5 mm is a placeholder. The order of the sides is plymeld's choice, untested against a print.

## 14. A print is looked at from its front unless it says otherwise (2026-11-09)

**Context.** `plan.view_from` was unset by default: looked at from all around, every side of a print counted alike. On a
plain round wall no point hides a seam better than another, so each layer's seam stayed in line with the one below,
wherever an earlier feature had put it. On a two-color nameplate the lettering on its front drew the seams there, and
the column carried on up the plain wall above it to the top edge, on the face the nameplate is made to show.

**Decision.** `plan.view_from` is `front` in plymeld's bundled defaults: the model's own front, its small-y side as
modeled, turned with whatever pose the plan gives it. Its back counts less in quality's measures, and least-visible
seams go there. `all_around` stays a value, for a model with no front of its own (a vase, a figure on a turntable).

**Rejected.** Keeping all around and changing the seams' tie-break to fall back to the back: it moves seams without
saying a print has a front, but the other quality measures would still count a print's back as much as its front.
Deciding the front from the model's geometry: no rule tells a nameplate's face from its back reliably yet.

**Consequences.** Least-visible seams and other quality-weighted decisions change on prints that leave `view_from`
unset: seams move toward the back, and a decision whose benefit lies on faces turned away (a curved top on a round,
symmetric part) may lose. A model modeled facing another way sets its own `view_from` (or `plan.object.<key>.view_from`).
The technique samples and the curved-island tests, round and symmetric, set `all_around`.

## 15. Least-visible seams are planned across layers (2026-11-09)

**Context.** Least-visible seams were placed layer by layer: each loop's seam at its cheapest point, counting a jog
from the seam below. On a plain wall no one layer saves enough to pay the move to a better side, so a seam a feature
drew to the front low on the wall stayed in line on the front face all the way up. On a nameplate it ran from the
lettering to the top edge, even with the print looked at from its front.

**Decision.** The outer walls' least-visible seams are planned over each run of layers where a loop continues one to
one (each loop linked to the loop below whose bounding box matches its own best, by overlap over union: by overlap
alone, a pocket's loop linked to the outside loop round it and broke every run): the line of points through the run
whose cost in all is least, each point as `seam_scar` charges it (how much it is seen times its blemish, a scarf's
where the start gets one) and each move from the point below its length times the line width, times how much the new
point is seen. A run is planned when the placement reaches its first layer, its first move from the seams placed
below it. Each loop is then placed with its planned point as the seam below, so corners, overhangs, and the ramp are
still judged where it is placed. A run of one layer is placed on its own. At most 160 candidate points per loop (every
n-th and the cheapest), a bound on time.

**Rejected.** Looking ahead a fixed number of layers: the move a plain wall needs may pay off only over a hundred
layers. A penalty for the front alone: the view already says which side is seen; the fault was paying the move layer
by layer. Planning where loops split or merge: those layers keep the placement layer by layer.

**Consequences.** Seams on prints with least-visible seams move (the regression cases' outer walls start elsewhere;
nothing else changes). Where loops split or merge, a run ends and a new one starts, so a seam may move there.

## 16. A retraction wipes by default (2026-11-09)

**Context.** The bundled filaments left the wipe off, with no reason recorded. On a two-color nameplate each outer
wall's retraction stood still where its loop ended, on the visible face, and left a mark there beside the seam.

**Decision.** Every bundled filament wipes as it retracts (`retract.wipe.enabled = "true"`): the path just laid is
retraced backwards by the wipe distance, `retract_before` of the retraction first and the rest spread over the wipe.
The distance (1 mm) and the share (70 %) stay placeholders.

**Rejected.** Wiping outer walls only: the same ooze leaves a blob at the end of any line where a retraction follows, and
a hidden line's blob can still lift the nozzle's next pass. Leaving it to a calibration: no plate reads it yet.

**Consequences.** Every print that retracts changes: each retraction after a path takes the wipe's moves and time.

## 17. An outer wall's ends land their ooze on hidden material (2026-11-09)

**Context.** On a two-color nameplate the outer walls' scarf seams showed far more than scarf seams do, though each
scarf was laid as planned (its ramp and closing pass making one layer). The outer wall was laid first in each contour
(`plan.layer.walls.inner_first`, false, an inherited value with no reason recorded): the head came down from a travel
onto the visible face, pushed its 0.8 mm retraction back standing at the seam, where the ramp is meant to be thinnest,
and at the loop's end retracted standing on the face again, with no wipe. Both of the scarf's ends carried a blob.

**Decision.** Three rules, each a setting:
- The walls' order is a choice, `plan.layer.walls.order`: `inner_outer_inner` by default (the walls past the one beside
  the outer wall, then the outer wall, then that one), so a layer's first line and the travel's arrival are on hidden
  walls and the outer wall is still laid free; inner first with fewer than three walls, or where the outer wall hangs out
  past the layer below (the overhang slow-down's start, the same rule the seams read), where it needs the wall beside it.
- An outer wall's restore after a retracted travel is made one line width inside its start, at the layer's top, and the
  head then moves onto the start with nothing pushed (`retract.return.inside`).
- An outer wall's wipe steps one line width inside it before running back along it (`retract.wipe.inward`).

The scarf's own speed, step and flow became settings beside them (`plan.technique.scarf_seam.speed`: half the visible
lines' speed, a placeholder; `.step`: 1 mm, the constant it was; `.flow`: 100 %).

**Rejected.** `inner_first` as the default: the outer wall is then laid pressed against the wall beside it, at some cost
to its surface, and the nozzle still arrives on it from inside, which `inner_outer_inner` keeps. Restoring during the
travel's last stretch: the plastic would land along the travel's path, which may cross the visible face. Leaving the order
inherited and fixing only the restore: the layer's first line would still start on the visible face.

**Consequences.** Every print with three or more walls is laid in a new order; every outer wall reached by a retracted
travel, or left with a retraction, moves its restore or wipe inside it. None of it is measured on a print yet: the next
print of the nameplate is the test.
