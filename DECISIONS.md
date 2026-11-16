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
(the old switch `inner_first`, false, an inherited value with no reason recorded): the head came down from a travel
onto the visible face, pushed its 0.8 mm retraction back standing at the seam, where the ramp is meant to be thinnest,
and at the loop's end retracted standing on the face again, with no wipe. Both of the scarf's ends carried a blob.

**Decision.** Three rules, each a setting:
- The walls' order is a choice, `plan.layer.walls.order`: `inner_outer_inner` by default (the walls past the one beside
  the outer wall, then the outer wall, then that one), so a layer's first line and the travel's arrival are on hidden
  walls and the outer wall is still laid free; inner first with fewer than three walls, or where the outer wall hangs out
  past the layer below (the overhang slow-down's start, the same rule the seams read), where it needs the wall beside it.
  The wall beside the outer one, laid after it, starts each loop at its point nearest where the outer wall ended (an
  inner wall's start is not seen), and the step onto it does not retract, as the step from an inner wall onto the outer
  wall's seam never has: kept at its own seam, the step was a travel of tens of mm with a retraction on the visible face.
  Likewise the inner wall laid just before the outer one starts nearest the outer wall's start (left at its own seam, the
  step onto the outer wall was an unretracted travel of 36 mm along the inside of the face).
- An outer wall's restore after a retracted travel is made one line width inside its start, at the layer's top, and the
  head then moves onto the start with nothing pushed (`retract.return.inside`).
- A wall loop's wipe steps one line width inside it before running back along it (`retract.wipe.inward`), with the whole
  retraction spread over its moves. The outer wall's wipe first; then the inner walls' too, once a print showed a
  column of scars beside a nameplate's lettering: the wall beside the outer one ended right behind the outer wall's
  scarf, in line with the seams, and its retraction stood there, 0.4 mm behind the face, every layer.
- A step between an inner wall and the outer wall goes unretracted only within two line widths: in a letter's narrow
  stroke, steps of 2 to 5 mm crossed the pocket's edge unretracted and drew strands.

The scarf's own speed, step and flow became settings beside them (`plan.technique.scarf_seam.speed`: half the visible
lines' speed, a placeholder; `.step`: 1 mm, the constant it was; `.flow`: 100 %).

**Rejected.** `inner_first` as the default: the outer wall is then laid pressed against the wall beside it, at some cost
to its surface, and the nozzle still arrives on it from inside, which `inner_outer_inner` keeps. Restoring during the
travel's last stretch: the plastic would land along the travel's path, which may cross the visible face. Leaving the order
inherited and fixing only the restore: the layer's first line would still start on the visible face.

**Consequences.** Every print with three or more walls is laid in a new order; every outer wall reached by a retracted
travel, or left with a retraction, moves its restore or wipe inside it. None of it is measured on a print yet: the next
print of the nameplate is the test.

## 18. Renamed settings are migrated; a setting's kind matches its dimension (2026-11-10)

**Context.** Settings were renamed with no fallback while plymeld had no other users: a file with an old name was
refused. With public releases, a rename breaks people's config and plan files, and every g-code's recorded settings,
which `slice --from` replays with plymeld's own values among them (the old `inner_first`, renamed
`plan.layer.walls.order`, made every earlier g-code unreadable by `--from`). Separately, each setting is declared twice,
in the schema (its dimension, how a value is parsed) and in the catalog (its kind, what the pipeline gets); a share
declared PERCENT in the catalog reached the pipeline times 100 (a scarf's flow, 100 % read as 100), and the test
comparing the two declarations allowed either for a ratio.

**Decision.** One table of changes (`Migrations`): each renamed setting's old path, its new path, how its value converts,
and the last release that read it; each removed one with why. Every config source passes through it as it is read
(`ConfigStack`, before the later source wins), so an old name reads as the new one, under an object's or a part's scope
too, with a warning naming both. `plymeld migrate` rewrites config and plan files in place (`TomlEdit.rename`, comments
kept), journaled. A g-code's recorded settings are migrated as they are read and never rewritten: they reproduce the
print they recorded (`inner_first = false` reads as `outer_first`). An entry stays at least until a release whose version
starts a new year. The catalog's PERCENT kind is gone: every share is FRACTION, 0 to 1, and only the two fan values the
fan commands read are turned into percentages, where `SliceSettings` reads them; the schema test now allows FRACTION
alone for a ratio.

**Rejected.** Dropping a g-code's recorded plymeld values in favor of today's bundle on `--from`: it would no longer
reproduce the print it recorded. Refusing old names with a message: every user would have to edit files by hand on every
release. A fake unit (`1.2x`) for multipliers: the value was parsed right; the second declaration scaled it.

**Consequences.** A rename is now a table entry plus its test, and an old file keeps working with a warning; `migrate`
is a new command (in the window, its form). A JSON config is read with the new names but not rewritten.

## 19. A section of a model: its planned lines cut to a box, or the mesh cut as it is read (2026-11-10)

**Context.** Judging a change to how a detail prints (a line of lettering on a nameplate's face) took a print of the
whole model, hours each time. A section of the model, printed with the same settings, shows the same lines in a
fraction of the time.

**Decision.** `model.object.<key>.section.min` / `.max` give a box in the model's own coordinates, and
`section.faces` says what its cut faces are.

*Open* (the default) answers "how will this part of the print come out": the model stays whole through the plan, the
pose, and the slice, and the object's planned lines are cut to the box last (`SectionClip`), the layers above the
box's top left out. On the bed the object is laid out, spaced, and held to the bed by its box, what prints of it
(`Placed.printed`): the rest of its model may lie over other objects or off the bed, so six sections of a model fit
where two whole models did. The purge tower stands beside the boxes. A line wholly inside is laid as planned; a line the box cuts is laid as its pieces
inside, ending on the box's side, the travel into each later piece (and into the line after a cut one) retracted. The
box travels to the slice as a region of the object, so it is posed with it. The pre-print check reads the model inside
the box only, from the parts cut to it (`SectionCut`, a mesh the size of what prints), and asks for no wall along the
box's sides. A multi-filament object's lines are cut per filament before
its filament order and purge tower are worked out.

*Closed* cuts the model's parts as they are read (`SectionCut`): triangles a side crosses are cut, and each cut face
is closed with a cap triangulated from the cut's own points (hole bridging and ear cutting, no point added on an edge,
so the result is a closed mesh sharing its edges exactly). Everything after reads the section as the object: the plan,
the layout (it stands on the bed), the slice, the pre-print check, the preview. A part with nothing in the box is left
out, and the config is read again over the object as cut. A model not closed where the box cuts it is refused.

**Rejected.** Closed only: a closed section's cut faces get walls and its cut-off top a top surface, with the seams,
wall order, and infill of a different object, so it does not show what the whole print would do there. Clipping each
layer's outlines to the box before planning: the same objection (the planner walls whatever outline it is given), and
the mesh is cut in more than twenty places, each of which would need the clip. A separate command writing a cut model
file: the section would drift from the model it samples, and a 3MF's parts and filaments would have to be written
back.

**Consequences.** An open section starts at the model's bottom (what is under a higher one would not be printed: that
is a closed section) and prints on flat layers; with a curved band, a curved island, or a lifted bridge it is refused
until those are cut too. The string measure reads an open section too high: it takes a layer's islands from its closed
outer walls, and a cut outer wall is an open line, so every unretracted travel inside the section counts as over open
air. The predicted time and the
measures are of the lines laid. A closed section is planned as an object of its own: a plan made for the whole model
still applies as locks (with the note that the model changed), and unlocked decisions are made for the section. In
both, the layers take less time than the whole model's, so the section cools less between layers.

## 20. A plate with multi-filament objects is laid per filament (2026-11-10)

**Context.** An object on several filaments was planned and written by its own slicer, alone: a plate of two was
refused. Comparing two settings on such an object (a two-color nameplate's lettering) meant a print each, every one
paying all its tool changes.

**Decision.** `MultiFilament.steps`, which put one object's filaments in order layer by layer, became `plateSteps` over
several objects: on each layer each filament in turn lays its lines of every object (each in its own object block), so
a layer costs one change per filament whatever the number of objects. `Job` uses it when any object on the plate is
laid per part; an object on one filament joins with its planned layers under its spool. The objects' layers are merged
by height as on any plate: layers topping at one height, as thick, are one layer of the plate, and an object on other
heights has layers of its own between; layers topping at one height share one layer of the tower. One purge tower, sized
by the plate's changes, stands beside the box round every object (or where it is placed), clear of each. Each object keeps
its own settings (the writer's per-object and per-part settings, as on any plate).

**Rejected.** Each object's layer complete before the next object's (an object's two filaments, then the next object's):
two changes per object per layer. Requiring every object on the same layers: a shorter object's last layer already
differs from the taller's at that height, so only copies of one object would have passed.

**Consequences.** Such a plate prints together, by height, on flat layers; a curved island, a curved band or a lifted
bridge on it is refused, as is one object at a time (each object would need a tower of its own from the bed). Objects each on one filament but not the same one
are still refused. One object on several filaments is written as before, by the same code with one object.

## 21. A tool change is made over where the incoming filament first prints (2026-11-14)

**Context.** A tool or filament change was written with the nozzle standing where the outgoing filament's last line
ended, on the part, at print height: between the last printing move and the T command there was only a standing
retraction and the outgoing tool's idle temperature, and the travel to the incoming filament's first line came after
the change. On a plate printed per filament the outgoing filament's last line on a layer is on the last object, so
every change stood on that object's face; on three test prints of two-color plates (of two and of four objects), the last
object was the one with strings and blobs. The nozzle that stops there and is pulled away leaves its ooze on the face,
and on machines that bring the new tool back to where the old one was, the incoming nozzle arrives there too and
waits for its temperature over the part.

**Decision.** Before a change the outgoing tool leaves the part as it leaves for any retracted travel: the same
retraction and wipe (the writer's one departure, `departForTravel`: the inward wipe after a wall loop, none after a line
left below its highest point, by the settings the outgoing line was laid with), then the lift (the outgoing filament's
`retract.z_hop`), then the travel to where the incoming filament's first line starts (its purge on the purge tower, or,
with the purge into infill or no tower, its first line on the part), routed and cleared like any other lifted travel
(`travelTo`). The change (the outgoing tool's idle temperature, the T command, the incoming tool's wait) is written
there, with the head still lifted. The incoming line then starts as before: the head's place after a change is not
known, so it rises clear of what is printed, goes over the line's start, and comes down. Where no line follows the change
directly (a wait, a pause, a swap by hand) or the head's place is not known (the print's first tool selection), the change
is made where the head is, retracted, as before.

**Rejected.** A fixed park position: a setting nobody can choose well for every plate, and a travel away from where the
tool is needed next. Changing in place with only a lift: the incoming nozzle still arrives over the part, and waits for
its temperature there, on machines that return to the old position. A setting to choose the old behavior: no case for it
is known.

**Consequences.** Every print that changes tools changes: the travel from the part to the incoming line moves ahead of
the change, and the outgoing tool wipes before it (no change wiped before). After the change the head rises and returns
over the same point, a move of no length on a machine that brings the head back, a travel on one that leaves it
elsewhere. The predicted time reads the g-code, so it counts the travel and the wipe where they now are; what the
machine itself moves during a change is its `tool_change.time`, as before. The incoming tool's wait happens over the tower, or over
the part's hidden infill where the purge goes into infill.

## 22. A seam another part covers is a point seam (2026-11-14)

**Context.** A scarf seam lays a closed outer wall as a ramp climbing from a share of the layer to its full thickness,
the loop, and a closing pass back over the ramp: it softens a seam on a wall that shows. On an object of several parts,
a part's seam can land on an edge pressed against another part, where it is hidden. The scarf was decided by whether the
seam lands on a corner (`plan.technique.scarf_seam.where`), not by where its ramp and closing pass run, so such a seam was
scarfed like any other, and from the hidden point the ramp and closing pass ran on along the loop onto the faces that
show. On a printed nameplate, a gray island about 2.1 by 2.4 mm inside a white letter started its wall against the letter,
then ramped round its front corner and across its whole front: its only visible faces got a thin line with a second over
it, and the print showed a hump there. The point needed no softening; the scarf put a double pass on the faces that show.

**Decision.** Where a closed outer wall's seam is covered, another part of the object within one line width of the seam
on that layer, the wall gets a point seam: it starts and ends there, with no ramp and no closing pass. "Another part" is
the object's other parts as laid on the layer (the other filament groups' areas), the one definition the planner already
judges a speck of outer wall in front of another part by (`Planner.near`). It is the default and a setting,
`plan.technique.scarf_seam.covered`: `point`, or `scarf` for a scarf there too, as before; per object or part like the
other scarf values, so a test print can lay both on one plate. The least-visible placement costs a covered point as the
point seam it is laid as (seen at the point itself, a point seam's blemish), not as a scarf seen along its ramp.

**Rejected.** Keeping the scarf but confining its ramp to the hidden stretch: it needs the loop's direction and the hidden
stretch's length, and a short loop may have no room for a ramp there at all. No scarf on any short loop: length is not
what makes the scarf wrong here; a long loop's ramp from a covered seam runs onto the faces that show as well. Judging the
whole stretch the ramp and closing pass run over in the seam's cost, and laying a scarf only where all of it is hidden: a
larger change to the planner, for what a point seam at a covered point already gives. "Another part" as the object's
outline less the part's own: that outline is closed (`method.slicing.close_gaps`), and along a round open edge the closing
leaves hairline slivers outside the part, which read as another part covering points of an open wall.

**Consequences.** Only objects of several parts change, and only their walls whose seam is covered: an object of one part,
and a seam not covered, are laid as before. With least-visible seams, a part's seams go more readily to edges another
part covers (they cost a point seam's blemish there, hidden, with no ramp seen), where before a scarf's ramp counted
against them. A covered seam is a point seam whatever its loop's length, so its blemish, hidden, is a point's; the seam
measure counts it as one. The speck rule now reads the other parts as laid too, not the closed outline less the part.

## 23. Two speed caps by a line's length, one speed for the whole line (2026-11-14)

**Context.** A line laid fast is laid at its set speed only once the head has reached it: it starts at rest, accelerates,
and brakes to a stop at its end. A short line set fast is mostly its start and its stop, and its plastic is laid at
speeds that change from one end to the other. The short walls' cap (`plan.mechanism.motion.short_walls.length` and
`.speed`) was a single step: a wall just under the length printed at the short walls' speed and one just over it at its
own, a jump between two walls of nearly the same length. Nothing capped the other kinds of line by their length.

**Decision.** Two caps, both off until set, each a cap: it never raises a speed, and the lowest of it and every other
speed and cap wins (the region caps, the first layer's, the melt cap, a scarf seam's ramp).

- Short walls slide: with `short_walls.full_length` set, a wall of length L between `length` and `full_length` prints no
  faster than `speed + (own - speed) * (L - length) / (full_length - length)`, own being the wall's speed without the
  cap; at `full_length` and over, no cap. Unset, `full_length` is `length`, the single step as before. A `full_length`
  under `length` is refused. A scarfed loop's length is its loop's, the closing pass left out (the wall is the loop).
- A line's steady share (`plan.mechanism.motion.steady.share`): starting and stopping at rest at acceleration a, a line
  takes v²/(2a) of its length to reach v and as much to stop, so at v it is laid steadily over L − v²/a. It is laid no
  faster than the speed at which that is at least the share of L: v ≤ sqrt(a · L · (1 − share)), with L the line as laid
  (from where it starts pushing plastic to where it stops: a scarfed loop with its ramp and closing pass; the piece laid
  inside an open section's box) and a the lowest acceleration it is laid at (its feature's, under its object's and its
  regions' caps). Every kind of line, except the purge tower and the prime line (purges, laid to push filament through at
  speeds of their own), a lifted bridge's own speeds over air (laid exactly as set), and a calibration plate's lines
  (printed as specified).

The writer applies both, after the program is planned, cut to an open section, and capped by region: they read each line
as it is laid. The whole line gets the one capped speed: a cap is what the line's length allows, so it holds for all of
the line, and a speed changed inside the line would add a change of speed where the cap is meant to remove them.
Within that, the line's own variations stay (a scarf ramp's own speed, a wall slowed where it hangs over air), each no
faster than the cap. The time predicted reads the g-code, so it counts the capped speeds; the minimum layer time's
slow-down is figured from them.

**Rejected.** A cap at the speed the line can reach (v ≤ sqrt(a · L), the share 0): the head reaches that speed only at
the line's middle and brakes at once, so the line prints as it would with no cap. A speed that changes along the line
(slow at its ends, fast in its middle): that is the firmware's own acceleration, already there. The steady share as a
length (every line under it capped): one line holds its speed on a machine that accelerates hard and not on one that
does not, so the acceleration is in the formula and one share means the same on both. A floor under the caps (the
filament's `cooling.speed.min`, which bounds only the slow-down for the minimum layer time): it would let the shortest
lines, the ones the caps are for, print faster than they can hold.

**Consequences.** Unset, nothing changes. Set, short lines print slower and the print takes longer; there is no floor,
so a line a few mm long may be capped far below its own speed. The pose search's quick time estimate does not count
either cap; the time of the written file does. The caps read the plate's, the object's, or the part's tool's settings
as the writer does: a value set on a part that prints with the object's own tool is the object's.

## 24. No number a printer may refuse leaves plymeld: one rule, where it writes, checks, and sends (2026-11-14)

Amends 23: a line of no length gets no steady-share cap.

**Context.** A program can hold a line of no length: its points all one, so it lays nothing (a thin area's center line
along the outline whose points come out as one, at a lettering's or a star's sharp tips). Its steady-share cap was
sqrt(a · 0 · (1 − share)) = 0, written `G1 F0`, and its time in the preheat's walk back was 0 / 0, written "NaN s". The
printer's firmware stopped the job at the zero feed, mid-print, and the pre-print check had passed the file: it read
feeds only to compare them with the machine's top speeds.

**Decision.** Three layers, each enough on its own for a dead feed:
- At the source: a line of no length gets no steady-share cap (a cap for a length it does not have), and takes 0 s in
  every time estimate (the minimum layer time's, the preheat's), whatever its speed.
- Where plymeld writes: one rule for the numbers of a g-code line (BadNumbers): a parameter that is not a finite
  number where a number stands, a value missing where one is needed, a move at a feed of 0 or below, a temperature, fan,
  or wait below 0, an acceleration of 0 or below. Every line the writer emits passes it (CommandCheck, the templates'
  lines too), or the write fails at that line, naming the feature, object, and layer. Numbers the writer rounds to a
  whole one (a fan's speed, a wait, an acceleration) are refused before rounding when not finite (rounded, NaN reads 0,
  a wrong command that looks right); a feed rate that is not above 0 is refused where it is formatted; a feed above 0
  that rounds to F0 is written F1, the slowest the format writes; a template placeholder that is not finite fails the
  render. A comment of plymeld's that holds NaN or Infinity where a number stands is refused too: a broken estimate.
- Where plymeld checks and sends: the pre-print check applies the same rule to every line of the file, and `plymeld
  printer send` applies it to the commands of the file it is given, of any origin (an older build's, another
  slicer's), before it reads the config or reaches the printer. The window sends through the same command.

**Rejected.** A floor under the steady share's cap (the slowest feed, or the filament's minimum speed): an invented
number, and a line of no length has no length to cap by. Removing lines of no length from the program: it changes every
print that has them, a change of its own to weigh (where they come from is the walls' center lines). A send that
refuses comments too: a firmware ignores them, and a file that would print right would be refused. A separate rule per
place (the writer's, the check's, the send's): three rules drift apart, and the send would refuse what the check passed.

**Consequences.** A slice that would write a bad number fails, its message naming the line, where a file used to come
out; the g-code written up to that line stays on disk, unfinished, and the message says not to print it. With the
steady share unset, the g-code is unchanged. A command the rule does not know is judged only where its values are
written as numbers, so a macro's text arguments, lists, and names pass.

## 25. A curved island over parts in several filaments: every part inside it follows its layers, each by its own tool (2026-11-14)

**Context.** A curved island a plan locks on an object of parts in several filaments or part settings (laid per part)
was refused when its area covered parts of more than one of them ("an island is laid as one"). An island's area is
every cell of the top above its start joined to its cap (CurvedRegions.islandAt): on a small ledge in front of an inlaid
letter (a pocket's lower wall on a block's sloped face) that is the whole object above the start, block and inlay
alike, since both rise behind the ledge. So the island over the ledge could not be laid at all.

**Decision.** The island is laid over every part inside its area. Each part is warped by the island's warp and sliced
in the island's own layers on its own, all in one warped space, so where two parts meet they meet as they do in flat
layers; the parts' loops the island holds are planned part group by part group as flat layers are (MultiFilament.plan:
the areas split by `job.part_overlap`'s precedence, read from the flat layers, each group with its own walls, regions
and paths, judged against the whole object), over the flat layers under the island for what its shells and bottom
read. Each island layer then becomes a layer of the per-part program among the flat ones, after the last flat layer
whose top is under its own warped top: its filaments are ordered, changed and purged as any layer's
(MultiFilament.layerOrder: each layer starts with the filament the one before ended with, then the rest in the
spools' order), each part's lines by its own tool, each path laid back from the warped space as an island layer of an
object laid whole is. The purge tower keeps the flat layers' heights: a change on an island layer purges into the tower
layer of the flat layer before it. The pre-print check reads the tower's lines on an island layer at that flat layer's
top, and each island line against its own part's tool in the island's warped frame.

A lock with no start, where the survey finds no start that removes stair-step from the island's top, takes the start
that steps least (it was refused); the plan's facts say by how much its own layers step more than flat ones. The
unlocked survey and the islands decision are unchanged: they still refuse such a cap, and the decision does not weigh an
island over several tools or part groups (a plan locks it).

**Rejected.** The island as one part's alone (the cap's), the other parts in its area left in flat layers: the island's
area is not a strip round the cap but every part above the start joined to it, so a part beside the cap would have to be
cut along a line inside the material, flat on one side and curved on the other, with walls along a seam no surface
shows; laid together, the parts meet as in flat layers. An island layer's lines laid in the flat layers' turns of each
filament (its block's lines in the flat layer's block turn, its inlay's in the inlay's), to save its changes: a layer
would then be read in two pieces (each with its own layer start), and on the ledge the island takes the whole object's
cross-section over its height, so its layers have no flat layer beside them to share a turn with. A tower layer of its
own for each island layer: it would be as thin as the gap between the island's warped top and the flat top under it.

**Consequences.** Island layers holding two filaments change tool once each, as flat layers do; on the ledge (flat
layers 0.08 mm, the island's own about 0.11), the island's 7 layers over 0.75 mm take 5 changes where the flat layers
there took 7 (44 against 48 over the print). Such an object on a plate of several objects is refused, and support
inside the island's own layers on such an object is refused, both saying so. With no island locked, nothing changes.

## 26. A thin area is laid as one line where two walls do not fit, as two where they do, with no flicker between (2026-11-14)

Amends 24: lines of no length are removed from the program, at the walls.

**Context.** A part narrowing to a ridge a line or two wide, then to nothing over a few layers, came out ragged: on one
layer a stretch of the ridge with no line (with walls on both sides of the gap), on the next ones the crest as fragments
(a short wavy line, a tiny closed loop, a blob), and in places two wall lines nearly on top of each other. The pre-print
check passed the file. On a synthetic ridge (ThinRidgeTest) the causes were, in variable-width walls (Walls.arachne):
a wall loop is laid wherever its bead fits, a hard threshold, and where the area's width hovers at it (an uneven outline:
a sliced curved face, the slicing tolerance) the fit came and went in dots, each a closed loop about 1.2 mm round,
overlapping its neighbors; where a loop ended, the single line beyond it started where the skeleton's fork was pruned,
its round end touching the loop's turn at one point (a notch); the skeleton's dead-end forks were pruned one by one as
the graph changed under the loop, so a short band's two corner forks went first, its trunk then read as a fork of its
own and went too, and one corner fork was left as the band's line (a ridge 0.75 mm wide between two loops, 1.2 mm of it,
laid as a 0.4 mm stub across it); a band cut where it dipped thinner than a line lost the pieces beside the dip as
corner voids; between walls, a leftover thinner than the narrowest line got that line anyway, over-full into both
walls; and on an object of several parts, a short line along the outline
beside another part was dropped as a "speck" (a rule for a sliver of one filament in front of another), though here it
was the whole width of a 0.78 mm ridge for 0.9 mm. The check counted a wall within a whole line of the outline as
walling it, and a run under 2 mm as nothing, so a hole a line or two wide between walls passed.

**Decision.**
- Hysteresis: pieces of a loop's fit region that the looser fit (the slicing tolerance, `method.slicing.tolerance`, a side
  further out: the outline is known no closer) joins are one loop, laid round the looser fit there, its beads that much
  closer. A fit piece alone keeps its loop exactly as before: grown from the looser fit everywhere, loops moved by microns
  at every corner, and what the walls leave to center lines and infill changed by far more (a curved layer's infill
  became center lines).
- A loop piece shorter than `plan.layer.walls.transition_length` (its extent) is no loop: the line through the area takes
  its place, unless no line reaches it (an isolated pin keeps its loop).
- A center line's end against a loop's bead (ahead of it, not beside it) reaches into it by `plan.layer.infill.wall_overlap`
  of its width past bead contact, as an interior line's end does: where two lines become one, the line meets the loop's
  turn with a lens of overlap rather than a notch.
- Dead-end forks shorter than the line's width are pruned in rounds, each decided on the graph as it stood at the round's
  start, and only forks that end where the skeleton ended; then a dead end that pruning left (the trunk of a fork whose
  own forks went) goes too only where it is a side branch of a line running on through its junction, never the line.
- A band cut where it dips thinner than a line for longer than the transition length keeps the pieces beside the dip
  (each at least as long as it is narrow): they are the band, not corner voids.
- Between walls (where the line's circle does not reach the model's outline), a leftover gets the narrowest line where
  that line reaches into the walls on each side no further than an interior line may: `plan.layer.infill.wall_overlap` of
  its width, or at least its own rounded flank (h (1 − π/4), the bead model's), whichever is more. At the model's surface
  `plan.layer.walls.feature.min` (half the narrowest line by default) stays the floor: there an over-full line overlaps
  nothing.
- Where the area is narrower than `plan.layer.walls.feature.min` no line is laid, however short the stretch (it was
  printed through at the narrowest line when shorter than the transition length): the line is cut at such a point, and
  at a step of the skeleton that passes the model's surface closer than half that (a neck between two of its points,
  much closer than either), and two lines' ends are not joined across such a neck. Rule 2 along a line, not only for
  whole areas.
- When a round's cuts would take every fork of a junction, or leave a small area less of its skeleton than its own widest
  line (its junction can be several skeleton points microns apart), its two longest forks stay: one line through it, not
  a hook into one corner and not a dot.
- A sliver left between the outer loop and the infill region in an acute corner of the model's outline is a thin area of
  its own (gets its line); elsewhere such corner slivers stay to the infill's anchors, as before.
- A speck (an outer line kept for walling the outline) is left out beside another part only where it runs into a place
  thinner than the narrowest line (its narrowest point widened to that line) and is nowhere wider than two narrowest
  lines (a patch, not a sliver), and its length for that is the thin area's own, not what its ends reach into the walls.
- A center line of no length is dropped where it is made: it lays nothing, yet cost a restart and a retraction.
- The pre-print check probes each outline point where its outer bead's middle belongs (half a wall in): bare when the
  model holds a line there and no line of the model lies within half a line and the tolerance of it. The model holds a
  line where a disc as wide as `plan.layer.walls.feature.min`, and the slicing tolerance a side (a place that close to
  the floor may go either way), fits against the outline; on the first layer against the outline drawn in by
  `hardware.plate.first_layer.squish`, where the walls lie. Bare points within two lines of each other, on one face or
  across a band, and with no line between them, are one stretch, its length the stretch's extent; a stretch longer than
  the layer's walls are wide (their width weighted by length, the middle one: a few wide center lines do not set it) is
  refused. A convex corner (a turn over 45 deg within a line) and a layer laid with wave beads are not judged.

**Rejected.** Laying the two walls of a band narrower than two walls plus the narrowest line as two wider lines (full
Arachne bead redistribution): the loops are fixed-width polygons, and a wider outer bead with the same center line
would stand out past the outline; a change of its own. Opening the fit region (dropping slivers thinner than twice a
margin): the threshold moves but stays a threshold, and noise at it still makes dots. Bridging loop pieces only within
the transition length: two pieces of a loop meeting at a pinch then ended in two turns facing each other, overlapping.
Keeping the narrowest line in every leftover down to `feature.min` between walls (as at the surface): with the infill's
wall overlap at 25 % the floor is the same, but it follows that setting rather than standing apart from it. The rounded
flanks alone as the floor between walls (0.02 to 0.03 mm a side): it left a leftover 0.29 mm wide between two inner
walls empty along 9 mm (the Benchy test model), where the narrowest line, 0.36 mm, reached 0.035 mm into each wall.
Carrying a line on past a junction where pruning would end it, into the fork reaching farthest past its round end: on a
band's slanted end the fork into the acute corner was taken, a hook that left the obtuse corner's face bare. Cutting one
part's center line wherever its bead would reach into another part's place (beyond the slicing tolerance): a sliver of
one part between `feature.min` and the narrowest line wide beside another part then had no line at all, and the model's
face there was bare (4.7 mm along a test ridge's crest); the narrowest line laid in the narrowest place a line is laid in
still stands out of it by half their difference a side, (0.3 − 0.15) / 2 = 0.075 mm by default. Not joining two lines'
ends where the join turns back over one of them (to stop a spur 0.085 mm long laid out and back): it also refused joins
two lines met at an angle needed, and left a ridge's crest under-laid. A check reaching a whole line (the old rule) with
a shorter run: a 1.3 mm hole between two wall turns still reads as walled.

**Consequences.** On the synthetic ridge (0.4 mm lines, 0.08 mm layers), every crest station at least the narrowest
feature wide is laid to at least three quarters of its width (two-wall stretches: within the narrowest line), with no
fragment and no overlap beyond the stated allowances; before, 0.9 mm with no line, 45 short loops and fragments, and
up to the full width laid twice. A ridge lowest in its middle (two loops at its ends, one line between) is laid along
its whole length; before, 10 to 15 stations together with nothing across them. Between walls, a leftover narrower than
the floor is left empty. On the Benchy test model interior wall lines are 67 mm longer (0.2 %) in 34 fewer paths: tiny
loops and the short lines beside them became one line or one loop. Line ends meeting walls are a little longer (the test
plate: 2 mm a layer). Lines cut at necks and not joined across them start and stop more: on a multi-part test print,
1,115 visible restarts against 924 and 3,465 retractions against 3,240. Still open: where a line ends against a loop's
end, its round end leaves the loop's corners a notch about 0.1 mm deep; a band's line ending at a fork whose branch on
down a taper is shorter than the line's width there stops at the fork (the taper's last 0.2 to 0.4 mm, 0.2 to 0.4 mm
wide, unlaid); a Y-shaped leftover's line can still bend into one arm; fixed-width loops leave up to a narrowest line
unfilled where a band is between two and three lines wide; a joined line can run out and back over a short spur; and
an outer loop cuts across a sharp concave corner of its area (into a neighboring part's place there).

## 27. A sliver along the object's surface stays its own part's; the check refuses one filament laying another's surface (2026-11-15)

**Context.** On an object of several filaments, an area of one filament thinner than the walls print
(`plan.layer.sliver.width`, unset: `plan.layer.walls.feature.min` with variable-width walls) where it touches another
filament's went to that neighbor wherever it lay. Inside the object that fills what would be a void. On the surface the
neighbor's plastic showed in the wrong color outside its own part, and its outline stepped out over the layer below for
the layers the sliver was thin, an overhang nobody modeled. On a nameplate whose gray wall leans out at 55 deg over a
white plate, starting at zero width on the white's face, the white's outer wall was laid 0.137 mm out of the white's face
over the gray's place (0.138 mm wide at the layer's middle, under `feature.min`, 0.15 mm) for one layer, its front edge
0.10 mm out from the layer below while the white's own face leaned back 0.04 mm; the gray's first line came on the next
layer. The pre-print check passed the file: its tool rule reads where a line's middle lies, and the white's middle lay in
the white.

**Decision.**
- `plan.layer.sliver.at_surface`: `own` (bundled) keeps a sliver lying along the object's outer surface its own part's,
  laid by its own filament where it is wide enough for a line and left empty where it is not; `neighbor` hands it over
  as before. Per object, like `sliver.width`.
- Along the surface, told on each layer: the object's outline is the outline of all its parts' areas together, gaps
  narrower than 0.04 mm closed (the seams of parts sliced one by one are not surface). Of a sliver, the pieces within the
  hand-over width of that outline whose stretch of it (the 0.02 mm band round the piece outside the object, by its width)
  is longer than the hand-over width are along it: they stay. The rest of the sliver goes over. So one partly along the
  surface keeps the part along it and hands over the part inside; one that meets the outline only end on (a tongue
  between two parts reaching the surface, whose stretch of it is its own width, under the hand-over width) goes over
  whole, and the neighbor's walls run across its end on the true outline.
- Between two settings groups of one filament every sliver goes over, wherever it lies: nothing of another color shows.
- The pre-print check's tool rule gains a surface rule: no stretch of the object's outline (its cut at the layer's
  middle) longer than 1 mm that belongs to other tools' parts alone (beyond the check's tolerance; with `neighbor`,
  beyond the tolerance and the hand-over width) is laid by one tool's beads, a bead laying an outline point within half
  its width and 0.02 mm of its middle. Each line's width is read from its plastic, its share of its path's widest
  extrusion per mm (the file's `;WIDTH` is a variable-width line's widest).

**Rejected.** Handing every sliver to the neighbor, as before: the wrong color on the surface, and the outline stepping
out where nothing was modeled. Keeping every sliver its own: inside the object, where a sliver is too thin for a line, it leaves a
void the width of the sliver (the hand-over's origin: a 0.32 mm tongue of one part under another's edge, printed as
nothing by the walls of the time, left a strip 2.8 mm long unprinted). Laying an
exposed sliver at the narrowest line anyway: under `feature.min` the line stands out of the sliver into the neighbor
and out of the model further than decision 26 allows. Counting any contact with the outline as along it: a tongue meeting
the surface end on would leave a notch in the surface and a void behind it. A refusal by the covered outline's total on
a layer (0.5 mm, as the tool rule): on the nameplates, where a feature narrower than a line meets another part (a gray tip
reaching 0.15 mm into a white face, a wall passing over it, a center line's round end in the narrowest place a line is
laid in), such pieces added up to 3 mm on a layer; one stretch a handed-over sliver lays is what shows. Reading the bead
from `;WIDTH`: a variable-width line read as its widest everywhere, 48 refusals on the nameplates.

**Consequences.** On the nameplate above, the white's outer wall stays on the white's face on every layer (its front
0.007 mm inside it where the gray wedge is 0.138 mm wide), the wedge's place is left empty on that one layer, and the
gray's first line comes where it did; the file's check passes on that object (it refused the earlier behavior there:
5.9 mm of the gray's surface laid by the white on that layer, 1.3 to 4.6 mm on five others). On the two-color Benchy
(0.2 mm layers, the split alone) the filaments' areas differ on 85 of 240 layers: 24.5 mm² in all, summed over the
layers, of slivers along the surface stays its own and is left empty where it is narrower than `feature.min`, a
recess in the surface where the neighbor's color filled it before; 0.2 mm² inside the object still goes over. The
check reads the flat layers of an object with a curved island with the tool in force: an island layer's tool change
was left out of the flat layers' reading, and the new rule refused a correct file on it (the curved-island ledge test).
Still open: a narrow tip of one part reaching into another's face is passed over by that part's wall (on a nameplate,
gray tips 0.15 mm into a white face, the white's wall dipping round them laid 1.3 and 1.4 mm of their surface white, as
before this change; the check now refuses it); a sliver along a top or bottom face only (the layers above or below
leave it showing) is judged inside the object, the layer's outline alone telling the surface; and a part's own
`plan.object.<key>.part.<part>.layer.sliver.*` is not read (the object's is), as for `sliver.width` before.


## 28. An island too narrow anywhere for its walls is laid by its local width, every pair of lines a loop (2026-11-15)

Amends 26: in such an island the loops, the center lines and their rules give way to this construction.

**Context.** An area of a layer between about one and three lines wide was laid badly. A gray lens about 2.4 mm long,
growing layer by layer from 0.7 to 1.2 mm deep against another part's straight edge, was one line along it while one
line filled it; one layer higher, a small loop in its middle and two short lines at its ends: a hole in the loop's middle,
gaps between the loop and the end pieces, the lens's long edge bare by up to 0.25 mm, an end piece 0.1 mm outside the
model, the lines chopped into moves of hundredths of a millimetre. The cause was 26's construction: a loop is laid where
its fixed-width bead fits, the rest of the area gets center lines, so the change from one line to two is a loop appearing
whole, and a band between two and three lines wide holds two fixed-width beads and a gap. A ring 1 mm wide had a strip
0.12 mm wide bare between its two loops all round. The pre-print check passed the lens.

A first construction (lines traced along each side, one line carrying on as the other side's line where one became two,
the new line starting beside it) covered the lens but laid such an island as open lines on some layers and a loop on
others: an open line has no seam, so the next layer's loop read its jog from a seam elsewhere, and on a real three-object
plate the seam measure rose from 65.5 to 159 mm² (all of it jogs).

**Decision.**
- An island (an outer polygon and its holes) where the set walls nowhere all fit (the innermost loop has no fit region in
  it) is laid by bead count from its local width (Beads). The walls of 26 are still computed for the whole outline and
  what they laid in such an island dropped: every other island's walls are the same objects as before.
- The width: the island's medial axis from the Delaunay triangulation of its outline samples, each node's distance to the
  outline its half-width.
- The count: across a place W wide, the n lines (n up to twice the walls' count) whose widths are all between
  `plan.layer.walls.line.width.min` and `plan.layer.walls.line.width.max` and nearest the set widths (the largest ratio
  between a line's width and its set width, smallest). The sharing: one line is as wide as the place (down to
  `feature.min`, as before); two share it equally; from three on, the outer two keep the outer wall's width (they are what
  shows) and the inner ones share the rest equally. Neighboring lines overlap by the bead's rounded flank, h (1 - π/4).
- `plan.layer.walls.line.width.max`, new: the widest a wall line may be; unset, twice the inner wall's width (the cap the
  center lines already had, so nothing else changes), and at least twice `line.width.min`; set below that, refused, since
  one line could not become two each at least the narrowest. No measured value: a bound for the count.
- Every pair of lines is a loop, built from the outline: loop k (0 the outer) runs wherever a place holds 2k + 2 lines or
  more, at its share's distance from the outline on each rib (node to outline sample), traced sample by sample round each
  ring of the outline. Where the ring passes to a place too narrow for it (a tip, a neck, a sliver) the loop turns back
  across that place's mouth (its two sides there about one narrowest line apart) and carries on along the other side: the
  run of the outline beginning on the same node's rib to the other end of the triangle edge the narrow part hangs from. So
  the outer loop exists on every layer the island is two narrowest lines wide anywhere, whatever the count does inside it,
  and goes through the ordinary seam path (alignment, visibility, scarf). Where its pair is the innermost, each line takes
  its share no narrower than the narrowest line; elsewhere its set width.
- Where 2k + 1 lines fit, the middle line of loop k lies along the axis; it never runs across a loop's turn (only along
  places of its own count). Its ends meeting a loop (a tip's or a neck's line where the outer loop turns) stop where the
  round end reaches into the loop's line by `plan.layer.infill.wall_overlap` of its width past bead contact, as an interior
  line's end reaches into the walls; a middle line between two turns close together is shortened before anything gives
  way to it, and is gone where nothing is left of it.
- A tip the loop runs into: a part with one line across (or 2k + 1) hanging off the loop at one mouth, no longer than
  twice its middle's width at the mouth, where running into it lays twice no more than half the disc of the mouth's width
  (what a separate line's round start over the loop's turn would): the loop runs on into it, its two sides at the narrowest
  width at least, then out and back along the axis to where the place is `feature.min` wide. A lens's tip so: a separate
  line there was shorter than it is wide (Planner drops such a speck beside another part) and a turn without the run-in
  left the tip's last 0.11 to 0.14 mm bare. A longer tip, a neck, a sliver: the loop turns at its mouth, the part gets its
  own line.
- Where the count rises from even to odd (two lines to three), the middle line starting there is new within
  `plan.layer.walls.transition_length` of the change: it starts with its round end and the lines beside it give way to it
  across their own track (the outer loop on its inner side only, its outer edge staying on the outline, no narrower than
  the narrowest line, never cut). So the outer line's width changes from its share of two lines (about 0.59 mm at the
  switch for 0.44 mm lines) to the set width along the middle's round start and the transition length after it, not in a
  step. A loop inside another gives way to the one outside it where they meet (a fast narrowing puts an inner loop's turn
  close behind the outer loop's), and may be cut there.
- Hysteresis: the count where the width lies within the slicing tolerance of a threshold is that of its decided
  neighbors; a stretch with a count of its own shorter than `transition_length` takes its neighbors' count; a middle line
  with fewer lines all round it shorter than `plan.layer.walls.length_factor.min` of its width takes the count below (the
  loop round it widens to fill it: a lens just deep enough for three lines in its middle laid a dot-like 0.24 mm line), and
  so does a short inner loop with fewer lines all round it (a ring round nothing at an L-shaped post's corner).
- Corners: a dead-end branch of the axis shorter than the width where it joins, narrowing at least 0.6 of its length
  (about 74 deg), runs into a corner; it keeps the count and places of its junction, so the loops turn the corner, ending at
  the point of the axis that far from the outline.
- A self-check: each such island's lines and its walls of 26 are measured on a 15 µm grid (IslandCheck: the widest
  uncovered spot, the uncovered area wider than the floor, plastic outside, plastic laid twice); the lines are taken
  where they leave nothing wider than the floor bare, no spot wider than 0.105 mm, nothing farther than 0.09 mm outside,
  no more than 0.06 mm² laid twice, or else where they are no worse than the walls (limits in Walls); otherwise the island
  keeps its walls. Islands over 4 million cells are not measured (kept as walls). The decision is cached by the island's
  exact outline (an extruded shape repeats it layer after layer): the same output, decided once.

**Rejected.** Assembling the outer loop from the traced pieces afterwards (joining side pieces across a narrowing): the
joins doubled the spur's start and the trimming ate lines in contact with the loop. The walls' own fixed-width outer
loop with lines inside it: where the island is two to about 2.7 lines wide it leaves an inside 0 to 0.3 mm wide, 26's
hole again. Blending the lines' places and widths over the transition length where the count changes (the published
framework's transition): where an area's two sides are not parallel, as a lens's, a node's ribs meet the two sides at
different places along it and the lines overlapped by up to 0.23 mm; the outer line narrowing from two lines' share to the
set width over the transition length before the middle line exists: a gap up to 0.3 mm wide, or a middle line narrower
than the narrowest. A flat turn and a separate line in every tip: see the tip rule. Laying every island this way: a
classic loop's mitred corners and its seam alignment would change on every ordinary island.

**Consequences.** On the test models (0.44 mm lines, 0.08 mm layers, three walls), against 26's walls: a lens over 12 layers, uncovered
0.26 mm² against 1.11, its widest spot 0.064 mm against 0.29, nothing uncovered wider than the floor (0.28 mm² before);
one closed loop a layer (the shortest 4.4 mm) against up to three paths (0.43 mm); the largest shift between layers 0.039
mm against 0.29; plastic laid twice beyond the usual overlap 0.70 mm² against 0.05, where the loop runs into its tips at the
narrowest width and out and back along their ends. A lens 1.4 mm deep: one loop a layer, widest spot 0.036 mm. A ring 1 mm
wide: 0.91 mm² uncovered against 17.8 (widest 0.020 against 0.135), two loops 0.51 mm wide. A wedge to 2 mm: 1.19 against
1.89 mm² (widest 0.100 against 0.112), laid twice 0.26 against 1.24 mm². A strip in steps from 0.2 to 2 mm: 1.18 against
6.62 mm², laid twice 0.61 against 1.20 mm², plastic outside 3.95 against 2.98 mm² (the narrowest steps' lines at the
narrowest line). The ridges of 26 stay laid edge to edge.

On a real plate of three objects (a nameplate base in two filaments and two variants of it; 0.08 mm layers) against 26:
by the measure of 29, seam_scar 247.0 mm² against 379.6 (5,908 seams, 333 scarfed, jogs 331 mm, against 7,822, 362, 305
mm; by the measure before 29, 48.2 mm² against 65.5); visible restarts 3,233 against 3,658, retractions 8,121 against
8,572; predicted 150.6 min against 150.4; the pre-print check passes all three objects (26's walls: two refusals, one
filament's bead on the other's surface at z 6.36 and 10.12). Planning 53 s against 38 (+40 %): about 4,500 narrow islands
laid (CPU: 51 s building their lines, 46 s measuring them, 5 s measuring the walls of the 940 measured both ways). On the
3DBenchy regression case the walls alone change: inner wall +1.5 % plastic, outer wall +0.3 %; its cabin posts are such
islands, each a loop and a middle line as before (a ring of four lines where an L-post's corner reaches four lines wide
was taken back to three by the short-stretch rule above).
The first construction (open lines on some layers) read 159 mm² of seam scar on the same
plate.

Closed from 26: the notch where a line meets a loop's turn (in such an island a line ends against a turn reaching into it
as an interior line does), a line stopping short on a taper (a short tip is the loop's), fixed-width loops leaving up to a
narrowest line between them (the ring, the band between two and three lines), an outer loop cutting across a sharp
concave corner (the loop follows the outline at its share's distance). Still open: an island wide enough for its walls
somewhere keeps 26's construction throughout, its narrow parts too (a fin off a block, a hairline between two loops);
where an area narrows fast from four lines to one (two blobs joined by a neck), the inner loop's turn close behind the
outer's and the neck's line reaching into the outer's turn lay up to 0.24 mm² a layer twice, and the self-check keeps the
walls on such layers; a loop's seam is placed against every seam of the layer below (Seams.place), while the measure reads
only what lies under it; between layers the count has no memory, so an area whose width hovers within the tolerance of a
threshold over many layers can switch more than once; and the pre-print check has no rule for an area left uncovered
inside the walls (it passed 26's hole in the lens).

## 29. seam_scar counts what is physically a seam: open lines' ends, and a jog only over what lies under it (2026-11-15)

**Context.** The measure read only closed outer-wall loops. An open outer-wall line (a line along the outline where no
loop fits) starts and stops on the surface, two blemishes, yet counted nothing; a loop's jog was read from the nearest
seam anywhere on the layer below, so a loop over a place where the layer below had only an open line, or nothing, read a
jog from a seam centimetres away on another island; and what hides a seam was read from the loops alone, so an open line
in front of a seam hid nothing. On a real plate a construction that laid narrow islands as lines on some layers and loops
on others read a jog of 50 mm on a single loop, and was scored worse than the one laying the same islands as many short
open lines, whose ends counted nothing.

**Decision.**
- An open outer-wall line's two ends are point seams: a line width squared each (on a shelf, as wide as the shelf), seen
  as the face there is (the larger of its two sides' exposure: a line's outline side is not known), with no jog.
- A loop's jog is read from the seams (a loop's start, a line's two ends) of the paths under it on the layer below in its
  stack: an outer loop whose inside it lies on, or a loop or line whose bead its bead overlaps or comes within a line width
  of (its points within half its width, half the other's and a line of the other's center line). Nothing under it: no jog.
- Open outer lines' beads are material for what hides a seam (the exposure raster ORs their footprints into the layer's
  loops) and part of the layer's printed area for the layer above (shelf, step out).
- The seam placement (Seams.place) still weighs a jog to every seam of the layer below: there the jog keeps the seams of a
  column of loops together, and a new island's candidates are all far from the seams of other islands alike.

**Rejected.** Counting an open line's ends as a scarf or at a share of a point seam: an end is a start or a stop on the
surface, the same blemish as a point seam. Reading the jog from every seam below within some distance: a seam on another
island close by is no step on this wall.

**Consequences.** On the plate of 28: 26's walls 65.5 mm² by the old measure, 379.6 by this one (7,822 seams: their open
center lines' ends now count); 28's lines 48.2 and 247.0 (5,908 seams). Tests with controls in SeamScarTest (a loop over
nothing, a line's ends, a line hiding a seam; each control run and seen to fail without the rule). A seam decision on an
unlocked object may choose differently where its candidates' jogs were read from unrelated seams.

## 30. The pre-print check refuses a hole inside the walls (2026-11-15)

**Context.** The check's outline rules read the model's outline: a spot no line covers, closed in by the walls, passed.
A lens laid as a loop and a line down its middle left a hole 0.25 to 0.29 mm wide between the loop's turn and the line's
end on layer after layer, and the check found no problem.

**Decision.** On each layer the check reads the uncovered spots on a 25 µm grid over the band the walls lie in (each line a
bead of its width from its plastic). A spot is a hole when walls alone close it in: it reaches no point outside the
cross-section (the outline's rules, a first layer's drawn-in edge), no infill or surface line (an infill's gaps and its
corners against the walls are by design), not the edge of the band, and is narrower than two inner wall lines (what the walls hand to infill: a stretch of the infill region that no line of a sparse infill crosses on
that layer). A hole wider than the narrowest place a line is laid in (plan.layer.walls.feature.min and the slicing
tolerance a side) and two grid cells is refused, where it is. Where fuzzy skin is on, each wall line reads its thickness
wider a side (its points move in and out by up to it). A layer laid with wave beads is left to the over-air rule.

**Rejected.** Reading the planner's infill region to tell infill from walls: the check would trust what it checks. A floor
of the line's width: a hole a line could not have filled is still bare plastic missing; the floor is the walls' own.

**Consequences.** The old lens (the file that version wrote for the test model, kept as a test fixture) is refused at its
top layers; a hand-written print with a stretch of a loop left out is refused where it is; fuzzy skin passes with its
allowance and is refused without it (both on a hand-written print and on the planner's). Over the regression models and a
real plate of three objects, as first run, every refusal read was a real hole, all present before walls by local width
too: webs between an opening and the outside where both sets of inner loops end (decision 31), pockets inside an inner
loop, the gap between an outer and an inner loop's turns at a sharp end; benchy 83 layers, the test plate 30, the real
plate 104 (191 with the walls before decision 28). A stretch of a two-part ridge's sparse-infill region read as a hole
until the infill-width rule.

## 31. A short line between walls is kept where its area is wider than the narrowest place a line is laid in (2026-11-15)

**Context.** The walls drop an open center line shorter than plan.layer.walls.length_factor.min times its width, and one
shorter than twice its width as a "corner void", the void round beads leave in a sharp corner between loops. A web 1.6 mm
wide between a hole and the block's side, where the inner loops of both end, left 0.72 mm bare between the two outer loops
over 1.4 mm: its line, 0.68 mm long, was dropped as short; the V where the inner loops part left pockets the same way.
The check of decision 30 refused them.

**Decision.** A short open line (and a piece of a band cut by a thin dip) is kept where its area is wider somewhere than
the narrowest place a line is laid in (feature.min and the slicing tolerance a side, the check's floor): a spot that wide
left empty is a hole, not a corner void. Narrower voids stay empty as before.

**Rejected.** Laying the web by local width as narrow islands are (decision 28): the larger change, to be done (open
items below). Lowering length_factor.min: every short line everywhere, not the ones that leave holes.

**Consequences.** Regression models: benchy 83 refused layers to 1, the test plate 30 to 0; the real plate 104 to 46, the
rest small pockets (0.25 to 0.45 mm) where a center line's fork into a corner is pruned. More short lines: on the real
plate 706 more retractions, 2.3 min longer. Open: the pruned forks' pockets; a slit narrower than the floor between two
outer loops in a web just over two lines wide (0.12 to 0.14 mm on test webs of 1.0 and 1.6 mm), where a line is barred
by the interior minimum; the narrow-island pocket at a ridge's end (ThinRidgeTest's dipped ridge, 0.30 mm).

## 32. A seam is aligned only with the seams of what lies under its loop (2026-11-15)

**Context.** Decision 29 made seam_scar read a loop's jog only from the paths under it, and kept the placement weighing a
jog to every seam of the same wall on the layer below. A new island beside another object's column aligned its seam with
that column's, a step the measure does not count and no surface shows.

**Decision.** This replaces 29's last point. The placement (Seams.alignAll, and the first move of a planned column) gives
each loop only the seams of the same wall's loops below that lie under it, by the measure's own test (SeamScar.isUnder).
Nothing under it: the loop is placed as on a first layer (its sharpest corner, ties to the back; or least-visible).

**Rejected.** Keeping every seam below as a weak pull: what the measure does not count, the placement should not chase.

**Consequences.** Real plate (with 31): seam_scar 247.4 to 244.5 mm², jogs 328.8 to 325.5 mm, scarf seams 283 to 143
(loops with nothing under them take a corner), predicted 152.8 to 152.3 min. Test in SeamsTest, with its control.

## 33. A thin area's fork is pruned only where no hole is left; a third line from a junction gives way to the other two (2026-11-15)

**Context.** The center lines of a thin area drop a dead-end fork shorter than its width: the fork into a corner, where a
line would only add a start and a stop. Where the area is wide at the fork, pruning it left a hole the pre-print check
(decision 30) refuses: on the real plate 45 pockets 0.25 to 0.45 mm wide where three thin strips meet between letters and
the rim, in the repository's Benchy a strip's line ending in a head 1 mm across with its point bare (0.34 mm), and the
pocket at a ridge's end in ThinRidgeTest (0.30 mm). Kept, a fork is a third line from a junction the other two run through,
laid from the junction over the others' plastic; and joining ends across a gap then joined that branch to another fork's
far end, laying it out and back.

**Decision.**
- A fork is kept where its area reaches out past the line at its junction by more than the narrowest place a line is laid
  in (feature.min and the slicing tolerance a side, the check's floor): a node's reach is how far its disc lies beyond the
  junction's line end, at most the disc's width; a junction stands for the forks pruned at it before. Only between walls:
  at the model's surface what is bare is the outline's (kept there, the forks round a loop's turn at a ridge's tip laid a
  line over the loop).
- Ends on one point join straightest first; an end that springs from another line (on its center line, away from its
  ends) joins nothing across a gap, and is cut back to reach into that line by the infill's wall overlap of its width past
  bead contact, as an end against a wall does; a line within that reach throughout is dropped.

**Rejected.** Lowering the prune length: every corner fork everywhere. Laying the third fork out and back from the
junction: twice over its own length.

**Consequences.** With 34, on a three-object two-color plate the check's hole refusals fell from 46 to 7 (the rest
pockets 0.25 to 0.45 mm on the base object, open); planning took 61 s against 46 s before. Tests: JunctionPocketTest (a
strip into a head, a triangle of three forks; controls: the exception off leaves the pockets, an end reaching in fully lays
plastic twice), ThinRidgeTest's dipped ridge (its pocket at the narrow end, 0.30 mm, gone). The walls of one filament's
areas read the object's whole cut (Planner.walls `whole`), and a narrow island's cached decision is keyed by it too.

## 34. Two loops with a slit between them too narrow for a line share it (2026-11-15)

**Context.** Between two loops (a hole's outer loop and the block's beside it, in a web just over two lines wide) the
leftover narrower than the interior minimum gets no line, as an interior line there would overlap both walls by more than
an infill line may. A web 1 mm wide left a slit 0.12 mm wide along 2 mm, layer after layer.

**Decision.** Where a loop's band and another band (a loop's, or a center line's) leave such a slit between them, seen from
both sides (along each line's normal), each of the two lines takes half of it: half the slit wider, a quarter nearer it,
its other edge where it was (the outer loop's on the outline), within plan.layer.walls.line.width.max. A loop so widened
carries its widths (laid as every loop is: seams, scarf over varying width). Not in a narrow island (its walls are judged
against its beads as laid).

**Rejected.** A line in the slit: wider than the slit, it overlaps both walls. One side alone (the nearest band): a loop's
sharp inner corner and the next loop's round turn leave a void only one side sees; widened there, every corner of every
block changed.

**Consequences.** WallsByWidthTest's web 1.0: widest bare 0.120 to 0.020 mm; the block control unchanged byte for byte.
Open: a pocket 0.13 mm wide where three lines meet at the end of a web 1.6 mm wide's center line (no slit: three sides).
On the real plate, slits shared closed 4 of the 11 holes left without it and took about 6 s of the planning.

## 35. The scarf seams decision 32 gave up are covered seams, point seams by decision 22 (2026-11-15)

**Context.** Decision 32 put the fall in scarf seams (283 to 143 on the real plate) down to loops with nothing under them
taking a corner. Counted per outer loop, with the alignment of 32 on and off: the loops that lost their scarf are small
loops laid by width whose seam now lies where another part of the object covers it.

**Decision.** No change: a covered seam is a point seam (decision 22), and the placement of 32 finds such hidden points.
This corrects 32's consequence, not its decision.

**Consequences.** Real plate, 32 off and on: scarf seams 284 to 143, seam_scar 247.6 to 244.6 mm², jogs 327.4 to 322.9
mm, visible restarts 3115 to 3078, retractions 8790 to 8747, predicted 152.7 to 152.2 min.

## 36. A dot of one part's outer line beside another part is left out only on the object's surface (2026-11-15)

**Context.** On an object of several parts, the paths leave out an outer-wall speck shorter than its own width where
another part lies within a width of it: on the object's surface such a dot, stopped and restarted every layer, made a
column of blobs, and the part behind fills the outline there. The rule read the part's own outline, so it held along a
part's edge inside the object too. On a three-object two-color plate, a stroke of the base's filament between two
letters met the rim through a neck too narrow for its loop; the pocket between the rim's loop, the stroke's loop and the
letters was laid by one short line, 0.44 mm long and up to 0.45 mm wide, which the rule left out: the pre-print check
(decision 30) refused a hole there, 0.25 to 0.45 mm wide, on 7 layers.

**Decision.** The speck is left out only where it runs along the whole object's outline (its surface), as
Walls.alongOutline reads it; along a part's edge inside the object it is laid. The paths' layer cache reads the whole
object's outline.

**Rejected.** Leaving the rule out altogether: on the surface the dots return (on the real plate 80 more restarts,
seam_scar 244.6 to 261.6 mm²).

**Consequences.** Real plate: check refusals 7 to 0; seams 5916 to 5974, seam_scar 244.6 to 247.7 mm², visible restarts
3078 to 3097, retractions 8747 to 8764, predicted 152.2 to 152.3 min. Test: WallsByWidthTest's finger of one part inside
another (a neck 0.36 to 0.58 mm wide and 0.5 mm long, a layer each): with the rule as before, holes on 6 of 23 layers;
control, its lines taken out, refused on all.

## 37. A gap opening onto the area handed to infill is not a slit (2026-11-15)

**Context.** Decision 34 widens two lines that leave a slit between them too narrow for a line. In ThinRidgeTest's ridge
beside a second part, an inner loop round a triangle 0.80 mm across (the area its walls hand to infill, with a short line
in its corner) was widened 0.02 mm toward that corner line: the triangle, left bare by the sparse infill as an infill gap,
became 0.79 mm, narrower than the walls hand to infill (two inner lines), and the pre-print check read it as a hole
inside the walls.

**Decision.** A gap whose middle lies in a piece of what the loops' bands leave that holds some of the infill region is
that region's corner, not a slit, and is not shared.

**Rejected.** Changing the check's two-inner-lines limit: the area is the infill's by the walls' own rule; the loop was
widened into it after that rule was read. A distance from the infill region: the gap here lay 0.6 mm from it, in the
same leftover.

**Consequences.** ThinRidgeTest passes; WallsByWidthTest's web 1.0 keeps its shared slit (0.020 mm), web 1.6 unchanged.
Benchy and the test plate lay a few slits fewer shared (the regression's walls differ slightly from 34's).

## 38. Each unit of parallel work is a task of its own (2026-11-15)

**Context.** Decisions 28 to 35 took the planning of a three-object two-color plate from 46 s to about 60 s. Timed per
layer, the walls of each part ran as long as their slowest stretch: an object's first five layers (0.5 to 3 s each,
the rest well under 1 s) fell in one chunk of the parallel stream and ran one after another on one core.

**Decision.** Parallel.map runs each index as a task of its own (the range split in halves down to one index), results
in index order as before. And, the output unchanged byte for byte: joining center line ends keeps whether an end springs
from another line from one join to the next (redone only near the join) and passes over lines far from the end; a
travel's island is found among the islands whose box holds the point; a wall's overhang share measures only the edges of
the layer below within half the bead, read once per layer; a thin piece's lines are kept from the walls' first build for
their second; a slit's neighbours are read once per range of cells. No unit starts parallel work while it holds a lock
other units wait on: with single-index tasks, the pre-print check's model cuts (a synchronized lazy that cut every layer
in parallel, first read from inside a layer's task) stopped the pool, a worker joining the cuts' tasks having taken up a
layer's task that waited on the lock; the cuts are now made before the layers' tasks, under no lock.

**Rejected.** Ordering the layers by expected cost: a guess where splitting to single units needs none.

**Consequences.** Real plate: planning 61.8 s (profiled) to 42 to 43.5 s (two runs), the g-code byte for byte the same;
the per-index tasks alone took the walls of the three objects from 23.9 s to 16.9 s. Test: ParallelLockTest (the
check of 2 to 200 layers of two parts finishes; control: with the cuts a synchronized lazy again, 16 layers hung).
