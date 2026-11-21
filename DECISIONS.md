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
has nothing to spare. A calibration plate's supporting parts on a fast machine come close to it, and ordinary slicing
capped hidden lines at the maximum itself.

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
grade was work that changed nothing, and grading every tile takes longer than reading the two values that decide the
first layer.

**Decision.** Per temperature, the window holds the lowest nozzle height graded good, every lower one bad, and the
fastest speed graded good (`"210 C" = { height.min = "0.28 mm", speed.max = "120 mm/s" }`). The first layer takes that
height, raised to the lowest the tool lays and not used when above the highest, at that speed; between two measured
temperatures, the higher height and the slower speed. `calibrate first-layer values` takes them as arguments, as the
melt and flow plates' readings are, checks each against the plate's g-code (a height and speed it tried, a tile it
printed), and writes them into the filament's file, recorded for `plymeld journal --undo`.

**Rejected.** The grid of grades (the form before): more to grade, a grade that counted for nothing, a sheet to fill in
and add to a config by hand. The lowest good height alone, the speed left as configured: the plate measures the speed
too, and a filament's first layer can need a slower one. A lowest good height per speed: more to read, and the first
layer is laid at one speed.

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
way round it without editing the model was a slot for each part (`model.object.<key>.part.<part>.slot`): a line for every
part of a many-part, many-color model printed in one filament.

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

**Context.** On a large model of many parts and millions of triangles, the pose search took most of the plan's time. Its
first round surveyed support's overhang regions in every pose under every plan, though its candidates hold no chosen
support and their scores do not read the regions; only the poses it details use them, and it details few of those it
measures. Then it detailed poses best first until the time limit, with no test of whether a pose could still win: poses
lying on their side, needing support under most of the model, were detailed like the upright one.

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
(decision 8) cannot rule out a pose whose sound bound is weak, which on a large model leaves tilted poses to be detailed
at length. Choosing a pose by the faces alone takes seconds; plymeld's search weighs far more, but most of it is wasted
on poses that are plainly worse.

**Decision.** Before the search, every pose is measured under ONE plan (flat layers at the baseline's height, no
support chosen, no overhang survey) and scored against the baseline. The search measures in full only the as-given pose
and the poses scoring within `method.search.pose.shortlist_margin` of the best there, per unit of the weight in effect,
as `keep_margin` is read. The pose decision says how many poses the first look shortlisted and each one's score there,
and lists the poses left out with theirs. Unset, every pose is searched.

**Rejected.** A shortlist of a fixed count of poses: on a model with few usable poses (the rest have no flat face on the
bed) every usable pose would be in it, so it saves nothing where it is most needed; a margin follows the scores. Scoring support's
droop and scars as one area, so the screen's bound grows strong enough to rule the tilted poses out soundly: it changed
the support chosen under quality-heavy weights (13 support spots on a 3DBenchy where none were chosen), a change to
what the search prefers, not only to its speed.

**Consequences.** A heuristic, unlike the screen: a pose that looks poor under one plan but would win with its own plans
and support is not searched. The placeholder 0.1 is set from the bundled test models under six weightings: the chosen
pose sits at most 0.068 from the first look's best (the coupon), and every choice is kept. Where the first look measures nothing a
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
wherever the object was. A tower far from the object, at a corner of the bed, stands where the bed may hold worse than
under the object, though its brim has the object's first-layer height, flow and speed: the place is the difference. Most
beds are flattest, and best leveled, near the middle, where objects go.

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
wherever an earlier feature had put it. On a print with a detail low on its front, the detail drew the seams there, and
the column carried on up the plain wall above it to the top edge, on the face the print is made to show.

**Decision.** `plan.view_from` is `front` in plymeld's bundled defaults: the model's own front, its small-y side as
modeled, turned with whatever pose the plan gives it. Its back counts less in quality's measures, and least-visible
seams go there. `all_around` stays a value, for a model with no front of its own (a vase, a figure on a turntable).

**Rejected.** Keeping all around and changing the seams' tie-break to fall back to the back: it moves seams without
saying a print has a front, but the other quality measures would still count a print's back as much as its front.
Deciding the front from the model's geometry: no rule tells a print's face from its back reliably yet.

**Consequences.** Least-visible seams and other quality-weighted decisions change on prints that leave `view_from`
unset: seams move toward the back, and a decision whose benefit lies on faces turned away (a curved top on a round,
symmetric part) may lose. A model modeled facing another way sets its own `view_from` (or `plan.object.<key>.view_from`).
The technique samples and the curved-island tests, round and symmetric, set `all_around`.

## 15. Least-visible seams are planned across layers (2026-11-09)

**Context.** Least-visible seams were placed layer by layer: each loop's seam at its cheapest point, counting a jog
from the seam below. On a plain wall no one layer saves enough to pay the move to a better side, so a seam a feature
drew to the front low on the wall stayed in line on the front face all the way up, to the top edge, even with the print
looked at from its front.

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

**Context.** The bundled filaments left the wipe off, with no reason recorded. Without a wipe, each outer wall's
retraction stands still where its loop ends, on the visible face, and leaves a mark there beside the seam.

**Decision.** Every bundled filament wipes as it retracts (`retract.wipe.enabled = "true"`): the path just laid is
retraced backwards by the wipe distance, `retract_before` of the retraction first and the rest spread over the wipe.
The distance (1 mm) and the share (70 %) stay placeholders.

**Rejected.** Wiping outer walls only: the same ooze leaves a blob at the end of any line where a retraction follows, and
a hidden line's blob can still lift the nozzle's next pass. Leaving it to a calibration: no plate reads it yet.

**Consequences.** Every print that retracts changes: each retraction after a path takes the wipe's moves and time.

## 17. An outer wall's ends land their ooze on hidden material (2026-11-09)

**Context.** A scarf seam laid as planned (its ramp and closing pass making one layer) can still show far more than a
scarf seam should. The outer wall was laid first in each contour (the old switch `inner_first`, false, an inherited
value with no reason recorded): the head comes down from a travel onto the visible face, pushes its retraction back
standing at the seam, where the ramp is meant to be thinnest, and at the loop's end retracts standing on the face again,
with no wipe. Both of the scarf's ends carry a blob.

**Decision.** Three rules, each a setting:
- The walls' order is a choice, `plan.layer.walls.order`: `inner_outer_inner` by default (the walls past the one beside
  the outer wall, then the outer wall, then that one), so a layer's first line and the travel's arrival are on hidden
  walls and the outer wall is still laid free; inner first with fewer than three walls, or where the outer wall hangs out
  past the layer below (the overhang slow-down's start, the same rule the seams read), where it needs the wall beside it.
  The wall beside the outer one, laid after it, starts each loop at its point nearest where the outer wall ended (an
  inner wall's start is not seen), and the step onto it does not retract, as the step from an inner wall onto the outer
  wall's seam never has: kept at its own seam, the step can be a travel of tens of mm with a retraction on the visible
  face. Likewise the inner wall laid just before the outer one starts nearest the outer wall's start (left at its own
  seam, the step onto the outer wall can be an unretracted travel of tens of mm along the inside of the face).
- An outer wall's restore after a retracted travel is made one line width inside its start, at the layer's top, and the
  head then moves onto the start with nothing pushed (`retract.return.inside`).
- A wall loop's wipe steps one line width inside it before running back along it (`retract.wipe.inward`), with the whole
  retraction spread over its moves. The outer wall's wipe, and the inner walls' too: the wall beside the outer one can
  end right behind the outer wall's scarf, in line with the seams, and a retraction standing there, a line width behind
  the face, every layer, shows through as a column of scars.
- A step between an inner wall and the outer wall goes unretracted only within two line widths: in a narrow
  stroke, a longer step crosses the pocket's edge unretracted and draws strands.

The scarf's own speed, step and flow became settings beside them (`plan.technique.scarf_seam.speed`: half the visible
lines' speed, a placeholder; `.step`: 1 mm, the constant it was; `.flow`: 100 %).

**Rejected.** `inner_first` as the default: the outer wall is then laid pressed against the wall beside it, at some cost
to its surface, and the nozzle still arrives on it from inside, which `inner_outer_inner` keeps. Restoring during the
travel's last stretch: the plastic would land along the travel's path, which may cross the visible face. Leaving the order
inherited and fixing only the restore: the layer's first line would still start on the visible face.

**Consequences.** Every print with three or more walls is laid in a new order; every outer wall reached by a retracted
travel, or left with a retraction, moves its restore or wipe inside it. None of it is measured yet.

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

**Context.** Judging a change to how a detail prints (a small feature on one face) took a print of the
whole model, hours each time. A section of the model, printed with the same settings, shows the same lines in a
fraction of the time.

**Decision.** `model.object.<key>.section.min` / `.max` give a box in the model's own coordinates, and
`section.faces` says what its cut faces are.

*Open* (the default) answers "how will this part of the print come out": the model stays whole through the plan, the
pose, and the slice, and the object's planned lines are cut to the box last (`SectionClip`), the layers above the
box's top left out. On the bed the object is laid out, spaced, and held to the bed by its box, what prints of it
(`Placed.printed`): the rest of its model may lie over other objects or off the bed, so several sections of a model fit
where few whole models do. The purge tower stands beside the boxes. A line wholly inside is laid as planned; a line the box cuts is laid as its pieces
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
refused. Comparing two settings on such an object (how its two filaments meet on its face) meant a print each, every one
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
every change stood on that object's face, and that object collected the plate's strings and blobs. The nozzle that stops
there and is pulled away leaves its ooze on the face,
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
show. A small island of one filament inside another's part starts its wall against that part, then ramps round its
front corner and across its whole front: its only visible faces get a thin line with a second over it, a hump. The point
needs no softening; the scarf puts a double pass on the faces that show.

**Decision.** Where a closed outer wall's seam is covered, another part of the object within one line width of the seam
on that layer, the wall gets a point seam: it starts and ends there, with no ramp and no closing pass. "Another part" is
the object's other parts as laid on the layer (the other filament groups' areas), the one definition the planner already
judges a speck of outer wall in front of another part by (`Planner.near`). It is the default and a setting,
`plan.technique.scarf_seam.covered`: `point`, or `scarf` for a scarf there too, as before; per object or part like the
other scarf values, so one plate can lay both side by side. The least-visible placement costs a covered point as the
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
along the outline whose points come out as one, at sharp tips such as a star's). Its steady-share cap was
sqrt(a · 0 · (1 − share)) = 0, written `G1 F0`, and its time in the preheat's walk back was 0 / 0, written "NaN s". A
printer's firmware stops the job at a zero feed, mid-print, and the pre-print check passed the file: it read feeds only
to compare them with the machine's top speeds.

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
every cell of the top above its start joined to its cap (CurvedRegions.islandAt): on a small ledge in front of an inlay
(a pocket's lower wall on a block's sloped face) that is the whole object above the start, block and inlay
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
there took 7 (44 against 48 over the object: CurvedIslandLedgeTest's reading). Such an object on a plate of several objects is refused, and support
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
plate: 2 mm a layer). Lines cut at necks and not joined across them start and stop more, with more visible restarts
and retractions. Still open: where a line ends against a loop's
end, its round end leaves the loop's corners a notch about 0.1 mm deep; a band's line ending at a fork whose branch on
down a taper is shorter than the line's width there stops at the fork (the taper's last 0.2 to 0.4 mm, 0.2 to 0.4 mm
wide, unlaid); a Y-shaped leftover's line can still bend into one arm; fixed-width loops leave up to a narrowest line
unfilled where a band is between two and three lines wide; a joined line can run out and back over a short spur; and
an outer loop cuts across a sharp concave corner of its area (into a neighboring part's place there).

## 27. A sliver along the object's surface stays its own part's; the check refuses one filament laying another's surface (2026-11-15)

**Context.** On an object of several filaments, an area of one filament thinner than the walls print
(`plan.layer.sliver.width`, unset: `plan.layer.walls.feature.min` with variable-width walls) where it touches another
filament's went to that neighbor wherever it lay. Inside the object that fills what would be a void. On the surface the
neighbor's plastic shows in its color outside its own part, and its outline steps out over the layer below for the
layers the sliver is thin, an overhang nobody modeled. Where one filament's wall leans out over another filament's
face, starting at zero width on that face (ExposedSliverTest's exposed wedge, leaning out at 55 deg over a face leaning
back), the face's outer wall was laid out of its face over the leaning wall's place on the layer where that place is
under `feature.min` at the layer's middle, its front edge stepping out from the layer below while its own face leans
back; the leaning wall's first line came on the next layer. The pre-print check passed the file: its tool rule reads
where a line's middle lies, and that line's middle lay in its own part.

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
void the width of the sliver (a thin tongue of one part under another's edge, too thin for a line, is left
unprinted). Laying an
exposed sliver at the narrowest line anyway: under `feature.min` the line stands out of the sliver into the neighbor
and out of the model further than decision 26 allows. Counting any contact with the outline as along it: a tongue meeting
the surface end on would leave a notch in the surface and a void behind it. A refusal by the covered outline's total on
a layer (0.5 mm, as the tool rule): where features narrower than a line meet another part (a tip of one part reaching
into another's face, a wall passing over it, a center line's round end in the narrowest place a line is laid in), such
pieces add up to millimeters on a layer; one stretch a handed-over sliver lays is what shows. Reading the bead from
`;WIDTH`: a variable-width line read as its widest everywhere, refused where it lays nothing of the kind.

**Consequences.** On ExposedSliverTest's wedge, the face's outer wall stays on its own face on every layer, the
wedge's place is left empty where it is too narrow for a line, and the leaning wall's first line comes where it did; the
file's check passes on that object and refuses the earlier behavior there (the test's control, `neighbor`). On the
two-color Benchy
(0.2 mm layers, the split alone) the filaments' areas differ on 85 of 240 layers: 24.5 mm² in all, summed over the
layers, of slivers along the surface stays its own and is left empty where it is narrower than `feature.min`, a
recess in the surface where the neighbor's color filled it before; 0.2 mm² inside the object still goes over. The
check reads the flat layers of an object with a curved island with the tool in force: an island layer's tool change
was left out of the flat layers' reading, and the new rule refused a correct file on it (the curved-island ledge test).
Still open: a narrow tip of one part reaching into another's face is passed over by that part's wall (that face's wall,
dipping round the tip, lays a millimeter or more of the tip's surface in its own filament, as before this change; the
check now refuses it); a sliver along a top or bottom face only (the layers above or below
leave it showing) is judged inside the object, the layer's outline alone telling the surface; and a part's own
`plan.object.<key>.part.<part>.layer.sliver.*` is not read (the object's is), as for `sliver.width` before.


## 28. An island too narrow anywhere for its walls is laid by its local width, every pair of lines a loop (2026-11-15)

Amends 26: in such an island the loops, the center lines and their rules give way to this construction.

**Context.** An area of a layer between about one and three lines wide was laid badly. A lens about 2.4 mm long,
growing layer by layer from 0.7 to 1.2 mm deep against another part's straight edge, was one line along it while one
line filled it; one layer higher, a small loop in its middle and two short lines at its ends: a hole in the loop's middle,
gaps between the loop and the end pieces, the lens's long edge bare by up to 0.25 mm, an end piece 0.1 mm outside the
model, the lines chopped into moves of hundredths of a millimetre. The cause was 26's construction: a loop is laid where
its fixed-width bead fits, the rest of the area gets center lines, so the change from one line to two is a loop appearing
whole, and a band between two and three lines wide holds two fixed-width beads and a gap. A ring 1 mm wide had a strip
0.12 mm wide bare between its two loops all round. The pre-print check passed the lens.

A first construction (lines traced along each side, one line carrying on as the other side's line where one became two,
the new line starting beside it) covered the lens but laid such an island as open lines on some layers and a loop on
others: an open line has no seam, so the next layer's loop read its jog from a seam elsewhere, and the seam measure
counted a jog at every such change.

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

**Consequences.** On the catalog's synthetic models (0.44 mm lines, 0.08 mm layers, three walls), against 26's walls: a lens over 12 layers, uncovered
0.26 mm² against 1.11, its widest spot 0.064 mm against 0.29, nothing uncovered wider than the floor (0.28 mm² before);
one closed loop a layer (the shortest 4.4 mm) against up to three paths (0.43 mm); the largest shift between layers 0.039
mm against 0.29; plastic laid twice beyond the usual overlap 0.70 mm² against 0.05, where the loop runs into its tips at the
narrowest width and out and back along their ends. A lens 1.4 mm deep: one loop a layer, widest spot 0.036 mm. A ring 1 mm
wide: 0.91 mm² uncovered against 17.8 (widest 0.020 against 0.135), two loops 0.51 mm wide. A wedge to 2 mm: 1.19 against
1.89 mm² (widest 0.100 against 0.112), laid twice 0.26 against 1.24 mm². A strip in steps from 0.2 to 2 mm: 1.18 against
6.62 mm², laid twice 0.61 against 1.20 mm², plastic outside 3.95 against 2.98 mm² (the narrowest steps' lines at the
narrowest line). The ridges of 26 stay laid edge to edge.

Planning takes longer where many narrow islands are laid: each one's lines are built and measured, and some measured
both ways. On the 3DBenchy regression case the walls alone change: inner wall +1.5 % plastic, outer wall +0.3 %; its
cabin posts are such islands, each a loop and a middle line as before (a ring of four lines where an L-post's corner
reaches four lines wide was taken back to three by the short-stretch rule above).

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
in front of a seam hid nothing. A construction that lays narrow islands as lines on some layers and loops on others
read a jog of centimeters on a single loop, and scored worse than one laying the same islands as many short open lines,
whose ends counted nothing.

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

**Consequences.** Walls laid as open center lines score far more than before (their ends now count). Tests with controls in SeamScarTest (a loop over
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
allowance and is refused without it (both on a hand-written print and on the planner's). Over the regression models, as
first run, every refusal read was a real hole, all present before walls by local width too: webs between an opening and
the outside where both sets of inner loops end (decision 31), pockets inside an inner loop, the gap between an outer and
an inner loop's turns at a sharp end; benchy 83 layers, the test plate 30. A stretch of a two-part ridge's sparse-infill region read as a hole
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

**Consequences.** Regression models: benchy 83 refused layers to 1, the test plate 30 to 0; what is left are small
pockets (0.25 to 0.45 mm) where a center line's fork into a corner is pruned. More short lines: more retractions and a
longer print. Open: the pruned forks' pockets; a slit narrower than the floor between two
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

**Consequences.** Loops with nothing under them take a corner; fewer seams are scarfed (decision 35). Test in SeamsTest,
with its control.

## 33. A thin area's fork is pruned only where no hole is left; a third line from a junction gives way to the other two (2026-11-15)

**Context.** The center lines of a thin area drop a dead-end fork shorter than its width: the fork into a corner, where a
line would only add a start and a stop. Where the area is wide at the fork, pruning it left a hole the pre-print check
(decision 30) refuses: pockets 0.25 to 0.45 mm wide where three thin strips meet beside another part, in the Benchy a
strip's line ending in a head 1 mm across with its point bare (0.34 mm), and the
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

**Consequences.** Fewer of the check's hole refusals; some pockets 0.25 to 0.45 mm wide stay open, and planning takes
longer. Tests: JunctionPocketTest (a
strip into a head, a triangle of three forks; controls: the exception off leaves the pockets, an end reaching in fully lays
plastic twice), ThinRidgeTest's dipped ridge (its pocket at the narrow end, 0.30 mm, gone). The walls of one filament's
areas read the object's whole cut (Planner.walls `whole`), and a narrow island's cached decision is keyed by it too.

## 34. Two loops with a slit between them too narrow for a line share it (2026-11-15)

**Context.** Between two loops (a hole's outer loop and the block's beside it, in a web just over two lines wide) the
leftover narrower than the interior minimum gets no line, as an interior line there would overlap both walls by more than
an infill line may. A web 1 mm wide (WallsByWidthTest's web 1.0) left a slit 0.12 mm wide along 2 mm, layer after layer.

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

Amended by 66: an outer wall takes no share of a slit; an inner loop beside one takes all of it, and the web 1.0's slit
between its two outer walls stays.

## 35. The scarf seams decision 32 gave up are covered seams, point seams by decision 22 (2026-11-15)

**Context.** Decision 32 put the fall in scarf seams down to loops with nothing under them taking a corner. Counted per outer loop, with the alignment of 32 on and off: the loops that lost their scarf are small
loops laid by width whose seam now lies where another part of the object covers it.

**Decision.** No change: a covered seam is a point seam (decision 22), and the placement of 32 finds such hidden points.
This corrects 32's consequence, not its decision.

**Consequences.** None beyond 32's: the seams that lose their scarf are hidden points, where a scarf's ramp would run
onto the faces that show.

## 36. A dot of one part's outer line beside another part is left out only on the object's surface (2026-11-15)

**Context.** On an object of several parts, the paths leave out an outer-wall speck shorter than its own width where
another part lies within a width of it: on the object's surface such a dot, stopped and restarted every layer, makes a
column of blobs, and the part behind fills the outline there. The rule read the part's own outline, so it held along a
part's edge inside the object too. Where a narrow stroke of one filament between two pockets of the other meets a wider
band of its own through a neck too narrow for its loop, the pocket between the band's loop, the stroke's loop and the
pockets is laid by one short line, which the rule left out: the pre-print check (decision 30) refused a hole there.

**Decision.** The speck is left out only where it runs along the whole object's outline (its surface), as
Walls.alongOutline reads it; along a part's edge inside the object it is laid. The paths' layer cache reads the whole
object's outline.

**Rejected.** Leaving the rule out altogether: on the surface the dots return, with their restarts and seams.

**Consequences.** A few more seams and restarts inside the object. Test: WallsByWidthTest's finger of one part inside
another (a neck 0.36 to 0.58 mm wide and 0.5 mm long, a layer each): with the rule as before, holes on 6 of 23 layers;
control, its lines taken out, refused on all.

Amended by 63: the rule reads each line as it was joined with the stubs at junctions that were left out of it
(`CenterLine.withStubs`), the lines its bounds were set on. A known limitation, kept for that calibration; it may be
revisited.

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

**Context.** Decisions 28 to 35 made planning slower. Timed per layer, the walls of each part ran as long as their
slowest stretch: an object's few slowest layers, several times slower than the rest, fell in one chunk of the parallel
stream and ran one after another on one core.

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

**Consequences.** Planning is faster where a few layers are slow, the g-code byte for byte the same. Test: ParallelLockTest (the
check of 2 to 200 layers of two parts finishes; control: with the cuts a synchronized lazy again, 16 layers hung).

## 39. A path whose travel would retract is laid beside a path that ends within a travel that never retracts (2026-11-16)

**Context.** The short lines kept by decisions 31, 33 and 36 close holes, and each costs retractions (a section's cut
pieces, each entered by a retracted travel, add their own). Read by the path on each side of a retracted travel: the lines in a thin area's chain were reached one by one from far off, the order
greedy (nearest-first) left a short line for last and crossed the part to it, a line was entered at its nearer end
whatever that left for its exit, and the pieces of one thin area between two contours went with each one's nearest
contour, laid apart. The order costed every travel that crosses a wall a retraction, the ones no longer than
`hardware.filament.retract.travel.min` too, which the writer never retracts.

**Decision.**
- Ordering takes that travel: no longer than it, a travel costs its length alone.
- The improvement pass over the greedy order also turns a single open path end to end, and moves a path whose travel in
  or out costs more than its length beside a path that ends within that travel of it (an open one either way round),
  when the total cost falls. Only such places are tried.
- Open inner-wall lines whose ends meet within that travel go with one contour's walls (the longest line's contour).

**Rejected.** Trying every place in the group for every path: the right order, at a large cost in planning time.
Joining a short line to the line it touches into one path: a fork starts in from the middle of the line it meets
(decision 33), not at an end. Laying a center line after the outer wall with the wall beside it: with two walls, the
contour's first line would be the outer wall (inner_outer_inner keeps the first line and its restart on hidden walls).

**Consequences.** Fewer retractions; planning time unchanged. What is left: chains of a thin area each entered once;
isolated pockets and corner forks with nothing within the travel; and lines a section's cut leaves. Test: OrderingTest (a short line beside a long line's end, left for last by the greedy order;
control: no free travel, the retracted travel stays).

## 40. A loop giving way to a middle line goes on forward (2026-11-16)

**Context.** Where a middle line starts (decision 28: the count rises from two lines to three) the loop beside it gives
way to its round start: each point of the loop moves across the track by what it gives way, on its own. A point moved
farther than the one before it can land behind it along the track: on the test wedge the outer line stepped back and
out again, 15 to 30 µm, turning about 159 degrees, on every layer (22 such turns). Taken alone, a move shorter than the
slicing tolerance remained after it.

**Decision.** After giving way, a moved point that lies behind the last point kept (along the track) or within the
slicing tolerance of it is left out; the line goes on to the next point ahead.

**Rejected.** Simplifying every line of a narrow island again at the tolerance: the narrow islands' lines are already
simplified once (Walls.narrowBeads); twice, the errors added, the wedge's uncovered area went from 1.19 to 3.0 mm² and
the pre-print check refused its first layer. Smoothing the shifts along the track: the giving way is what keeps the two
lines from laying plastic twice.

**Consequences.** The wedge: no such turn (22 before), no move shorter than the tolerance on its outer line; its
coverage unchanged by the self-check's measure. Such turns outside the narrow islands are not read here, and a tip's
run-in turns back by design. Test: WallsByWidthTest (no such spike
on the wedge; control: the old layer's line is counted).

## 41. The pre-print check reads a curved layer's line widths on the layer's own thickness where they lie (2026-11-16)

**Context.** The check reads each line's width along it from its plastic per mm: the path's widest is its stated
width, the rest scaled by their plastic on one layer height (decision 30's hole rule and decision 27's surface rule read
these widths). A curved layer, read flat in warped space, is not one height: the Benchy's bow island is 0.13 to 0.20 mm
thick along one loop, and the planner lays its walls 0.44 mm wide there with less plastic where the layer is thinner.
Read on one height, those walls were 0.32 mm wide where it was thin, and the hole rule refused slits 0.29 to 0.32 mm
wide between them on three island layers.

**Decision.** The curved check hands the model's check each curved layer's real thickness at a point (from its own
warp: the layer's surface less the surface it is laid on, as its thickness rule reads it), for the island frames, the
band's layers, and the lifted bridges' frames. A segment's cross-section is its plastic per mm times one factor, t (w -
t (1 - pi/4)) on its own thickness t; the factor makes the path's widest bead its stated width. On one thickness
throughout it is the reading as before.

**Rejected.** Leaving the hole rule out of curved layers: the rule reads them, and a wall left out of an island layer is
a hole there as anywhere. Rewriting the warped g-code's E to one thickness: the g-code rules read the same text, and
its flow is what prints.

**Consequences.** CurvedIslandBenchyTest reads back clean again; its control, a stretch of an inner wall 3 mm long left
out of an island layer, is a hole. CurvedBeadTest: a wall on a layer 0.2 mm thick along half and 0.1 mm along the other
reads 0.44 mm all along; control: on the stated height, the thin half reads under 0.3 mm.

## 42. The hole rule's grid is a byte a cell over the layer's own extent (2026-11-16)

**Context.** Decision 38 made every layer of the pre-print check a task of its own, so each core reads a layer at once.
The hole rule (decision 30) kept four arrays a cell (7 KB a tile of 32 x 32 cells) in a map of tiles with boxed keys,
and every cell of every uncovered piece in boxed lists, an infill region's piece most of the band. On a model of two
parts the band is most of the cross-section (the walls between the parts lie far inside the outline): on the dual
Benchy up to 3,400 tiles a layer. On 18 cores the check alone no longer fit beside the plan in a 384 MB heap (it had),
and in a 512 MB test JVM it ran with dozens of full collections or out of memory.

**Decision.** A cell is a byte of flags; the tiles are an array over the layer's outline and the band's depth round it,
made where the band is; a piece's cells are kept (in a primitive stack) only while it may be a hole, cleared once it
opens onto the outline, an infill line or the band's edge. The band's depth is read for each wall end within the depth
found so far first. The holes found are the same; of two equally wide, the first in row order is named.

**Rejected.** Fewer layers at once for the check: the rule's memory is what grew. A larger test heap: the tests' 512 MB
is the limit that catches this.

**Consequences.** The check alone of the dual Benchy (18 cores), its model rules' time and the heap's collections: at
512 MB, 2.1 s and 82 (before: 4.0 s and 344; the version before the hole rule: 1.4 s and 69); at 352 MB, 3.1 s and 407
(before: out of memory at 384 MB; the version before the hole rule: 2.2 s and 315). The rule's allocations on that
model fell from about 2.5 GB to under 0.2 GB of the check's 7.5 GB.

## 43. One catalog of small models, held to their own limits and to a recorded baseline of every measure (2026-11-16)

**Context.** Each technique's test models were built inside its own test, and each test read only what its technique
is about. A change to the walls moved retractions, seam counts and planning time on other models, and nothing showed
it until a large model was sliced.

**Decision.** The slicing tests' small models live in one catalog (plymeld-check's tests, `catalog/`), each with a
stable name, what it is for, the rig it is sliced with and a few rows of settings that matter to it; the technique
tests build their models from it. `CatalogTest` slices every model under every row and records every measure a side
effect could move (the pre-print check, the walls' coverage for the walls cases, the seam scar, stringing and
restarts, retractions, filament, predicted time, and each feature type's paths and length) in a committed text file,
one line per model, row and measure. A run that differs from it fails, listing each measure old -> new; the baseline
changes only through `-Pplymeld.refreshCatalogBaseline`, so the change shows in the commit's diff. Every measure is
exact: the slice is deterministic. Absolute limits stay where they are known, in the technique tests; the catalog
itself asserts only the pre-print check, with a model's known refusal named in its entry so the test turns red when
it is fixed.

**Rejected.** Comparing the files byte for byte (as the regression cases do): every change to a file would fail with
nothing said about what moved or how much, and a reviewer would read g-code diffs. Tolerances on the measures: a
deterministic slice needs none, and a tolerance would hide the small moves that are the side effects looked for.
Recording planning time: it varies from run to run on a shared machine; its own test holds it. Absolute limits for
every measure of every model: most have no known right value; the baseline makes their movement visible instead.

**Consequences.** Every change that moves a model's slice fails the catalog until it is explained and accepted, and
the accepted diff says what moved. The baseline is measured one model after another: side by side on the parallel
pool, as a test JVM's first slices, a few models' files can change from run to run (an open finding, printed
by the test). A JDK whose math functions round differently in the last place could move a slice; the baseline would
show that too.

## 44. Slices side by side read exactly as one after another; a thread-safety test runs its code interpreted (2026-11-16)

**Context.** Decision 43's open finding: the catalog's models sliced side by side as a JVM's first work changed other
models' files in a third to a half of the runs (a two-color piece with no walls along an outline, a wedge with nothing
printed on 18 layers), from before decision 38's tasks too. The mesh slicer, one object for the whole process, wrote
each cut triangle's crossing point to one buffer of its own and read it back: two threads slicing at once overwrote
each other's points. Once compiled, the JIT kept the point in registers and the fault vanished, so it showed only
while the code ran interpreted, mostly in a process's first slices.

**Decision.** A function plymeld runs on several threads keeps its working buffers per call, never on an object or a
companion; a cache shared by slices is keyed by everything its result depends on, a model by a number never reused
(not its identity hash code). `CatalogTest` asserts that every unit sliced side by side on the parallel pool as the
JVM's first work reads exactly as one after another. A test of code run on several threads at once also runs it in a
JVM of its own with that code kept interpreted (`-XX:CompileCommand=exclude`), where a shared buffer shows on every
run instead of now and then: `SlicerThreadsTest`.

**Rejected.** Slicing the catalog's units one after another only: it hid a fault that slices in one process (the
window, a plate's objects, the parallel work of one slice and its check) can meet. A lock around the slicer: the
buffer has no reason to be shared. Running the whole test JVM interpreted: minutes for what one class needs.

**Consequences.** The side-by-side pass is an assertion (the catalog's units read the same in 6 cold runs of 6, where
2 of 6 differed with the shared buffer); the slicer test's control counted 5743 to 5755 wrong layers of 5760 in each
of 6 runs. Also fixed by the same reading: the support survey's base areas were cached by the model's identity hash
code; a printer connection's HTTP clients sat in an unsynchronized map; the planner kept a figure of every plan's
dropped slivers on its shared object, which nothing read (removed).

## 45. A hole belongs to the smallest outer that holds it, read off that outer's boundary (2026-11-16)

**Context.** The catalog's inlay on a leaning face (decision 43) was refused on its buried plate's first layer: a hole
0.65 mm wide in the first filament's web, 1.5 mm wide, between the block's side and the plate's end. The walls laid
there an outer line on each side; the inner lines, with no room for two, turned back at both ends of the web; the
0.65 mm between them got no line. A block with a slot of the same size, its outline exactly square, did get one. The
difference was in the leftover the walls' bands leave: on the model's slightly slanted side the bands of the outer and
inner lines missed each other by a few microns, and the leftover was a hairline ring round the block, with the web's
pocket on it, plus a sliver a micron tall along the slot. Polygons.group gave each hole to the smallest outer
containing its first vertex, a point on an outer's boundary counting as inside; the ring's hole started at a corner of
the slot's sliver, so it went to the sliver. The ring was read as a solid area 15 by 8 mm with no hole, and its center
lines (up to 0.9 mm wide, 63 mm of them on that layer) were laid along its middle, over the infill; the sliver with the
ring's hole as its own was a region whose skeleton held nothing in the pocket.

**Decision.** A hole goes to the smallest outer at least as large as it that holds it, judged at the hole's first
vertex that is not on that outer's boundary (inside or outside, exactly); a hole lying wholly on the boundary is that
outer's. Polygons.contains is unchanged: a point on the boundary still counts as inside.

**Rejected.** Cleaning the walls' leftover of slivers thinner than a micron or two before its pieces are grouped: it
would hide this case and leave the rule wrong for every other caller (the boolean results of the whole planner are
grouped by it). Testing the hole's centroid: a hole's centroid can lie outside it, and in an outer's other hole.

**Consequences.** The inlay passes the pre-print check under both its rows. Only its plate's first layer changes: the
inner and outer wall lines over the block's infill go (inner wall 137.6 to 74.1 mm on that layer, its lines 0.8 mm wide
or more from 106 segments to none), the web gets its line; on the whole model filament 659.5 to 654.7 mm, seams 155 to
141, retractions 335 to 328. No other model of the catalog moves, and the regression cases write the same files. The
pre-print check did not refuse the lines laid
over the infill (plastic laid twice is not among its rules): open.

## 46. An island that keeps its walls shares their slits, a loop no wider than its own facing stretch allows (2026-11-16)

Amends 34.

**Context.** Decision 34 left the slits of a narrow island (decision 28: the set walls fit nowhere all in it) unshared,
its walls being judged against its beads as laid. Where the beads were not taken, the island kept those walls with
their slits: a strip whose inner wall's two sides lie about a line apart left a slit bare between them. Sharing those slits too, the catalog's two blobs joined by a neck (walls kept on
four layers) laid more twice than the slits it closed: in a lens-shaped inner loop the two sides converge toward its
ends, a side's normal there meets the other side at a slant, and a bead widened by the gap seen along it reached over
that side.

**Decision.**
- The slits are shared after the narrow islands are decided, in every island but those laid by their beads. The
  islands' beads are still judged against their walls unshared.
- A loop takes no more of a slit than its own stretch facing it is away, measured straight: the nearest point of the
  loop's edges more than its line's width away along it, turned more than a right angle from the edge read, ahead on the
  slit's side.

**Rejected.** Limiting the share by the straight distance to every line ahead: a slit between two loops beside a center
line's end is shared less (tried with another change at once; on the web 1 mm wide the widest bare spot went from 0.020
to 0.110 mm). Limiting it by the distance to the line the normal hits: the converging stretch is the loop's own, within
the stretch the normal skips (the two blobs laid as much twice as without a limit).

**Consequences.** The two blobs: uncovered 0.681 to 0.664
mm², laid twice 0.983 to 0.984 mm² over its read layers. Elsewhere the catalog moves by a few hundredths of a
millimetre of line on five models (finger in a second part, inlay, four ridges), where the limit trims a share. Test:
SlitSharingTest (the planner's cut of the two blobs on a layer that keeps its walls: bare inside the lens 10,650 to 4,375
µm², laid twice unchanged; controls: the walls unshared leave 10,650 µm²; without the limit, the bare area went to 0 and
6,300 µm² more was laid twice).
Open from 34: a pocket with three sides (WallsByWidthTest's web 1.6 mm, up to 0.14 mm where its middle line turns
into the corner) is not shared: a ray from the hole's loop through the pocket's middle leaves through its mouth to the
wider area beyond, and where a ray does meet the line's turn, the line seen back along its last segment's normal touches
the hole's loop.

## 47. The pre-print check finds plastic laid twice beyond the planned overlaps (2026-11-16)

**Context.** Decision 45's walls laid 63 mm of lines up to 0.9 mm wide across a layer's infill, and the check passed
them: it had rules for plastic missing (outline, bare outline, holes) and none for plastic laid where plastic of the same
layer already lies.

**Decision.** On each layer the check reads, on the hole rule's 25 µm grid, where the beads (each line's width from its
plastic) of two runs lie over each other; a run is what the nozzle lays without stopping. Each bead is narrowed by the
overlaps the planner makes on purpose, each from the settings: half the rounded flanks of two touching beads a side
(t (1 - pi/4), on the line's own thickness), the slicing tolerance a side, fuzzy skin's thickness for a wall line, and
`plan.layer.infill.wall_overlap` of its width off each end of an open run (a line end's reach into the walls). Not read:
ironing (laid over a top on purpose), wave beads (the over-air rule's), one run over itself (a scarf's ramps, a tip's
run-in, a loop's end over its start), and two sparse infill lines (a grid's families cross). A spot laid twice wider than
the narrowest place a line is laid in (the hole rule's floor) and two cells counts, with its layer, place, area and
the two kinds of line (reported, not refused: decision 48). The layer is read in windows of 256 x 256 cells, one at a
time; only the doubled cells are kept, at most 100,000 a layer (62.5 mm², far past a spot that counts; at a million the
curved Benchy's check ran out of a 512 MB test heap); a layer with more is read that far and said to be cut short.

**Rejected.** Narrowing an interior line's sides by its reach into the walls too: a whole interior line laid over another
then read narrower than the floor (the control on a hand-written print passed). Judging every overlap however thin: a
thin area's center line between walls overlaps them by up to `wall_overlap` of the narrowest line a side by design, and
an interior line's linking runs lie that far past contact along the walls; a sliver thinner than the floor is left.

**Consequences.** Controls: a hand-written box with a loop laid again, a wall line along an infill line, and infill ends
laid through the innermost loop are found, and the file the inlay's walls of decision 45 were written to (its one layer,
a fixture) is found at that layer (29 spots, 45.9 mm²). The first reading of the catalog found spots on 27 model rows,
nearly all on the internal bridge layer: a bridge's lines were clipped with their middle on the edge of the area they fill, half
a line over the walls and the infill beside it (the planner's own rule for every other interior line places them at bead
contact); fixed, for bridges over air too (a bridge laid across a tooth's own wall loop). Still found,
and recorded in the catalog's baseline: where an internal bridge meets the internal solid or sparse infill of its own
layer, both fills place their lines against the shared edge as against a wall's bead, and their linking runs lie over each
other along it (the bridges and the sloped top). It also finds teeth along the outline laid as loops of their own over
the main outer loop, and wall junctions in narrow webs. Decision 48 lists what is still open.

## 48. Plastic laid twice is reported, not refused, until the planner lays none (2026-11-16)

**Context.** Decision 47's rule, as a refusal, refused what the planner has always laid: a plain Benchy, a two-part
Benchy, and dozens of the test suite's own slices, at overlaps of 0.25 to 0.55 mm between
lines the planner placed. They are real overlaps, to be fixed in the planner one cause at a time; a slicer that refuses
an ordinary model in the meantime cannot be used.

**Decision.** The check reads the rule on every layer as before, and its spots are a measure, not problems: `slice` does
not fail on them and writes the file. The slice's report says them in one line beside the seams and strings, `laid
twice:` the number of spots, their area, the number of layers holding one, and the widest (its layer, place, width, area,
and the two kinds of line), with a picture of each layer that has one beside the check's others; a layer whose reading
stopped at the rule's 100,000 cells says so in the line, so a count cut short does not read as whole. The window's
console shows the same line. The test catalog records the measure for every model and row (`laid_twice.spots`,
`.area_mm2`, `.layers`, `.widest_mm`, and `.capped_layers` when any), so each later fix shows as a movement of the
baseline and a new overlap fails it. One switch in the code decides report or refuse (`DoubledCheck.REFUSES`), turned
on when the catalog, the regression cases, and the slow suite's models all read zero spots; `DoubledCheckTest` holds
its effect. It is not a user setting: whether a file with plastic laid twice may print is not a preference, and a
setting would outlive the planner's fixes.

**Rejected.** Keeping the refusal and naming each known overlap as an expected finding (the catalog's `expect`): a
refused Benchy is not landable, and the tests that slice real models would each need the same list. Raising the rule's
floor until the planner's overlaps pass: they are 0.25 mm and wider, as wide as the faults the rule is for.

**Consequences.** Open, each a planner cause the rule finds:
- two fills sharing an edge (an internal bridge beside internal solid or sparse infill, a top beside internal solid):
  both place their lines against the edge as against a wall's bead, and their linking runs and ends overlap by 0.25 to
  0.30 mm along it (the commonest);
- teeth along an outline (protrusions about a line wide) laid as outer loops of their own over the main outer loop;
- wall junctions in narrow webs, where loops and center lines meet;
- a concentric solid ring laid over an inner wall along a side;
- two parts' fills over each other on a two-part model (not yet read);
- a curved island's rising rim: its outer wall over its inner wall.

## 49. An island laid by its beads hands nothing to infill; a line's move whose plastic rounds to nothing is merged (2026-11-16)

Amends 28 and 48.

**Context.** Decision 48 listed teeth along an outline laid as outer loops of their own over the main outer loop among
the planner's causes of plastic laid twice: spots read "outer wall over outer wall", "bridge over outer wall" or
"internal solid over outer wall" at small teeth of one filament, a couple of millimeters long, hanging off another
filament's edge. Read path by path, neither was a loop over a loop:
- Each tooth is an island too narrow for the set walls, laid by its beads (decision 28): one loop. Where the walls of 26
  laid no loop in it (its loop's fit shorter than the transition length, a center line in its place), they handed most of
  the tooth to infill, and that piece was taken back only when its first point lay inside the tooth. Its first point is a
  mitred tip, a few hundredths of a millimetre past the tooth's sharp apex: the piece stayed, and a bridge (the tooth's
  first layers hang over air; its overshoot past the outline reads as over air on every layer) or solid infill was laid
  across the tooth's own loop.
- The tooth's loop is scarfed. Its closing pass ends in segments a few microns long at a sliver of the line's width, whose
  plastic the file's E (5 decimals) rounds to 0: written as `G1 X.. Y.. E0`. The pre-print check, like any reader, takes
  a move without plastic for a travel: the closing pass broke there, and its last stretch, a path of its own, was read at
  the line's full width (a path's widest plastic per mm is its stated width) over the loop's start.

**Decision.**
- What the walls hand to infill in an island laid by its beads is the beads': it is kept only within the islands not so
  laid, by area (Walls.arachne), not by a piece's first point. A piece that loses nothing is kept as it was.
- A move inside a line whose plastic rounds to nothing in the file is not written where its point lies within the
  slicing tolerance (`method.slicing.tolerance`: the path is known no closer) of the straight move that replaces it and
  the next; its plastic goes with the next move (KlipperWriter). A point farther off is kept however little plastic its
  move carries (an ironing line's step to the next line: merged, the next line would run askew), and a line's last move
  is always written. The `E0` moves left are at a line's end, or off the straight move.

**Rejected.** Reading an `E0` move inside a line as part of the line in the check: the check would read the file
otherwise than the printer and every other reader do, and the file would still say a travel there. Carrying every
move's rounding into the next (the written E summing to the exact plastic over a path): moves of a few microns still
round to nothing, and every E in every file would move by a digit. Testing a piece's membership by a point inside it
rather than by area: the piece also pokes past the outline at the tip, which the area rule takes off too. Merging only
moves shorter than the slicing tolerance: closing passes still break at moves a few microns longer.

**Consequences.** The catalog's new model, small teeth under a second part (WALLS rig, with and without scarf
seams), reads no spot; taken out by first point it read 33 spots, 6.5 mm², bridge over outer wall, on 19 of the teeth's
20 layers (BeadIslandInfillTest, with a control on each tooth's walls). ZeroPlasticMoveTest writes a scarf-like closing
pass and reads it back: no `E0` move inside the line and its plastic kept, and nothing laid twice; control, the merged
moves written as `E0`: outer wall over outer wall, 0.4 mm wide. Elsewhere in the catalog only the domed top's ironed row
moves: its ironing read as 3 paths, now 1 (19.74 to 19.75 mm long: the merged moves now count as laid).
Still open from 48: two fills sharing an edge, wall junctions in narrow webs, a concentric solid ring on an inner wall,
two parts' fills over each other, a curved island's rising rim.

## 50. A sliver handed over takes the gap to its neighbor along (2026-11-16)

Amends 27.

**Context.** The laid-twice measure (decision 48) reads spots where lines of one filament converge at the tip of a thin
sliver of the other. Read spot by spot, with the planner's areas per filament at those layers, one cause is the
largest: a tongue of one part too thin for a line,
inside the other filament's area, goes over to that neighbor (decision 27), and the parts meet a few microns apart
(sliced one by one), so the gap between the tongue and its slot went to no one. In the neighbor's area it was a crack 5 to
10 µm wide and a few tenths of a millimetre long, a hole of its own or opening onto the neighbor's edge, and the
neighbor's walls looped round it: its outer loop ran up the crack and back, its inner loops on both sides met at the
crack's end, three or four lines over each other.

**Decision.** A sliver handed over takes the gap between it and the area it joins along: the places within twice the
touching distance of the sliver (the distance at which it counts as touching that area, 20 µm) that a closing by that
distance fills, where no other area lies (FilamentAreas.handOverSlivers).

**Rejected.** Closing each filament's whole area by the slice's closing radius (`method.slicing.close_gaps`) where no
other area lies: where the closings met another area's edge at a corner they left spurs a few microns long in the
outline, and the walls' mitred offsets spiked out of them, laying more twice; with only the gaps no other area touches,
the cracks opening onto an edge stayed. Cutting each wall loop to its inset of the outline less the slicing tolerance,
for the next cause below: it removed more spots, but in the catalog's two blobs and a neck it left a spot 0.134 mm wide bare at the neck (the walls' tests allow 0.105) and failed the slit-sharing test.

**Consequences.** The cracks' spots go. The catalog's new model, slivers ending inside a second part (tongues 0.10 and 0.13 mm wide, straight and wedged, 5 µm from
their slots, and blunt spikes the other way), is held by SliverTipTest: no slot left to no area (control, the tongue
handed over alone: 0.002 to 0.013 mm² a slot); sliced, it reads 24 spots, 20.7 mm², on 13 layers, none along a tongue
(the spikes' case below, not read further there). Elsewhere in the catalog two models move: the buried fin 0.1 mm thick
(handed over, now with its gap) by a few hundredths of a millimetre of line; the exposed wedge handed to its neighbor
lays one outer loop fewer on a layer (the sliver now joins it with no gap), and that loop's seam jogs 18.8 mm (seam scar
1.41 to 3.13 mm²).
Still open: a blunt spike of one filament into the other's area leaves a narrow notch, and the walls (decision 26),
growing a loop back from its fit by a mitred offset, run it across the notch's tip, in places a little outside the area,
over the loop inside it; wall junctions in narrow webs between pockets (inner loops of two pieces of an inset meeting at
a neck, and two outer loops at a V between two pockets); small inner loops of a narrow island; two outer loops of one
narrow island crossing; fills; one short inner line beside a long one.

## 51. What a slice no longer needs is let go: the check streams its file, keeps its grids flat, and holds a layer only while reading it (2026-11-16)

**Context.** A test slicing and checking a two-part model of 262k triangles in the tests' 512 MB heap
(`ToolAssignmentTest`) ran past its time limit on CI, ran out of memory locally once in four runs, and took 213 s here
with 45 full collections and 5,776 young ones; the most the heap held after a full collection was 410 MB. Read in heap
histograms and dumps, what was held and need not be:
- `slice` kept its slicer (every stage: each layer's outlines, walls and paths) through the pre-print check, for a next
  run it never makes (a session kept across runs is the window's);
- the check's g-code rule held the whole file as a list of line strings (tens of MB) beside the model rules' own;
- the model rules kept a look-up grid of every layer's outline and of each part's for the whole check, as maps of boxed
  indices and an array a segment (about 70 MB on this model);
- the test itself held the file as lines across its next check, renumbered the whole file three times for its control,
  and decided the job again (planning its walls and paths) for each of its two checks.

**Decision.** The readers stream and each structure lives as long as its step: `slice` lets its slicers go once the file
is written (SliceSession's `keepSlicers`; the window keeps them, for its re-slicing); the g-code rule reads the file in
passes over its lines (GcodeCheck), never as a list; the check's grid is flat (sorted primitive arrays of cells, rows
and segment indices: ModelCheck.Grid) and a layer's grids and its cut below are let go once its rules have read it
(made again where asked for later). The file written is byte for byte the same. The test (not the product) holds one
whole file at a time, swaps the tools in one pass, and slices with SliceSession's two halves, as `slice` does, with the
job decided once for the file and both checks; its other cases keep the command's own parsing.

**Rejected.** A larger test heap or time limit: 512 MB is the limit that finds this, and the time limit was passed by
waste. Pictures of laid-twice spots only on request: they are drawn one at a time (one image's memory, about 1 s on this
model) and decision 48 gives them with the line; not the cause. One merged copy of a several-part object shared by the
steps that merge it: tried, and the heap dump during planning held as many copies as before (the steps merge different
lists: the parts as read, and as placed). A shared bound over all the soup caches (five, each an eighth of the heap):
their contents are what the planner reuses; left for a measure of what each saves in time.

**Consequences.** The test's case, here (18 cores): 213 s to 85 s, full collections 45 to 9, young 5,776 to 1,575, the
most held after a full collection 410 to 241 MB (under half the heap); its two checks take about 5 s each with no full
collection. `CurvedIslandBenchyTest`: 49 s to 43 s, full collections 11 to 6, the most held after one 386 to 353 MB
(both at its last check; a heap dump there held a curved frame's refined copy of the model, 70 MB, and about 20 of the
check's picture images, 4 MB each, still reachable after they were written: open). `plymeld slice` of the dual model
alone at 512 MB: 79 s for 81, the heap after its full collections before the check 194 to 271 MB for 277 to 290, the
check's young collections 92 to 42, the file the same byte for byte. What the planning holds is the rest, about 220 to
270 MB after a full collection: the mesh as read and as placed, the slicer's prepared forms of the parts and of the
whole (about 45 MB), the overhang survey's footprints (about 20 MB), and each layer's stages. Held by tests: `CheckMemoryTest` (a grid under 64 bytes a segment retained, 49 measured, the boxed
form 94; a layer's grids let go after the model check) and `SliceSessionSlicersTest` (`slice`'s session holds no slicer
after writing; the window's holds and reuses them).

## 52. A loop's fit keeps a line's distance from a sharp notch's tip, and the necks its mitred shrink cuts (2026-11-16)

Amends 26.

**Context.** Decision 50 left spots of plastic laid twice. The catalog's slivers ending inside a second part read 24
spots, 20.7 mm², on 13 layers. Read loop by loop, two causes were in the
walls' fit regions (decision 26: loop k is laid round the outline shrunk by its inset and half its spacing, mitred so its
corners stay sharp):
- Past the miter limit (twice the shrink) the offset joined the two moved edges with a bevel. Shrinking at a notch
  sharper than 60 degrees, the bevel keeps ground d cos(a/2) from the tip (a the turn): 0.21 mm for a shrink of 0.8 mm
  at a tip of 30 degrees. The loop grown back from that fit ran across the notch's tip, over the loop inside it.
- At a turn into the area within the limit the mitred shrink cuts deeper than d, to the miter point. A band narrowing
  past such a turn was cut through where the exact shrink (every point at least d from the outline) keeps a neck: the
  fit fell into two pieces, or two holes' grown outlines met, and the loops grown from them ended against each other
  across the cut, or crossed it twice, closer than a line.

**Decision.**
- The offset takes `roundBeyondLimit`: past the miter limit (a reversal too) a round arc instead of the bevel, the rest
  mitred as before; every other caller's offset is unchanged. The walls' fits use it.
- A fit's necks are joined again (Walls.necksJoined), in the strict fit and in the looser one: each piece of the exact
  (round) shrink that the mitred one leaves out and that meets it along two separate stretches (an edge of the piece lying
  along the mitred fit's, within 3 µm, the two shrinks' grid rounding) is added. A lune in one corner meets it along
  the two moved edges through the corner, one stretch, and stays out: elsewhere a loop keeps its sharp corners. A loop
  the hysteresis (decision 26) joins round the looser fit is cut to the inset less the tolerance round, not mitred,
  where its fit had a neck joined.
- The exact shrink is made only where a neck can be: a turn within the limit whose miter reaches more than 3 µm past d,
  whose lune (in the triangle of the moved edges' ends and the miter point) another edge comes within d of or another
  turn's kite overlaps, with the mitred fit within the miter's reach. A turn on a smooth curve is none: its neighbors'
  edges stay more than d from its lune.

**Rejected.** Rounding every join of the fit: an inner loop's concave corner would pull away from the sharp corner of
the loop outside it, and their beads overlap there. Telling a cut neck by the exact and mitred shrinks' numbers of
pieces and holes, each mitred piece assigned to the exact piece holding its first point (as first written): the two share
their moved edges, a mitred piece's first point can lie on the exact piece's boundary, and spots stayed (one read layer
by layer: an inner loop cut in two at a V between two pockets). Joining necks in the looser fit alone: the hysteresis
then made one loop round the looser fit, and its mitred cut to the inset less the tolerance cut it again beside the neck
(spots came back, one an outer loop broken there). Making the exact shrink of the outline cut to boxes round the turns:
hundreds of overlapping boxes made the booleans the slowest step (planning nearly twice as long), and one spot appeared
that the whole shrink does not make. A neck only where the miter point itself is cut from the fit: an edge inside the
kite can part the lune with the corner kept (spots stayed). Cutting every joined loop to the round inset, as first
written: arcs at every turn of the outline (and, the exact shrink made on every layer, a longer planning).

**Consequences.** The catalog's slivers inside a second part: 24 spots, 20.7 mm², to none. A spike's tip beyond its
notch, which the loop ran across, is laid by a short outer line of its own on each of its 13 layers (outer wall paths 39
to 52, seams 52 to 78, visible restarts 15 to 28, seam scar 0.68 to 1.17 mm²); the loops that ended at the neck are one
(inner wall paths 131 to 118). Elsewhere in the catalog walls move by up to 0.026 mm on a few layers of the web 2.2 mm,
the finger in a second part and the inlay, where a neck at a polygon's vertex is kept; on one layer of the inlay a neck
joins two inner loops into one, whose round turn there lies nearer the loop outside it (no spot). Planning takes a little
longer: the exact shrink and its difference with the mitred one (where narrow islands are many, most layers have a turn
the gate keeps; a cache of the necks by outline found almost no outline repeated, and was dropped). Open: that cost. Tests: OffsetTest (a thin
triangle grown past the limit, its area in closed form, the bevel's as the control; a notched square shrunk, nothing of
it nearer the outline than d less the arc's chord sag, the bevel 0.21 mm from the tip as the control; random polygons
against the sleeve method with the arc), NecksJoinedTest (a band round a square corner whose inner fit's neck the mitre
cuts: one inner loop, its sides a spacing apart; control, the necks not joined: two loops whose ends lie 0.305 mm apart).
Still open: a loop crossing a web between two pockets twice, not a neck the mitre cut (the exact and
mitred shrinks agree there); outer loops at a V between two pockets; small inner loops of a narrow island; two outer
loops of one narrow island crossing; two fills sharing an edge; one internal solid line over another.

## 53. A line's points closer together than the slicing tolerance are one, its width keeping the plastic (2026-11-16)

**Context.** Extruding moves shorter than `method.slicing.tolerance` gathered on the outer wall, with outer-wall
vertices turning by more than 150 degrees beside a move shorter than the outer line. Read move by move: nearly all of the
outer wall's short moves lay in lines whose plastic per millimeter varies, the lines of narrow islands laid by their
beads (decision 28), none in an arc and none at an arc's join. Such a line takes a point on every rib of an outline
sample, at its bead's distance from the outline: a sample touches two or three triangles, so the loop had two or three
points to a sample a few microns apart, every few tens of microns, each with its own node's width. Walls.simplified drops a point only where
the width stays within the tolerance, so it kept every one. The sharp turns were mostly the designed run-in of a tip
(out along the axis and back), and some steps back and forth across the track where a place is two to three lines wide.

**Decision.** Before simplifying a line by its edges, each run of neighboring points within the slicing tolerance of
one another is made one point (Walls.merged): halfway between the run's ends, or on a closed line's first vertex where
the run holds it (it keeps its place); its width is the one that keeps the plastic of the moves on either side (each
move is extruded at the mean of its ends' widths), within the run's widths. An open line's end and the points beside
it are left as they are. The fix is in the walls stage (the lines' points), not in the writer: no point moves as far as
the tolerance, the outline being known no closer.

**Rejected.** Moving an open line's end too: where open lines meet at a junction, which ends join reads the way each
runs out at its end (joinEnds), and a junction can then choose otherwise and lay two outer lines over each other.
Turning a closed line to start elsewhere for the merge, its first vertex moved: its scarf seam's closing pass got a move
with no plastic in a step back across the track that decision 49's merge cannot take (off the straight move), read as a
line over the loop's start. Keeping the loop's points in order along the outline (the fewest left out so that each lies
no farther back than the one before): the steps back went, but more plastic was laid twice, and the tips' turns
sharpened. A filter in the writer:
the cause is the line's points.

**Consequences.** Far fewer moves shorter than the tolerance, plastic laid twice the same. The outer wall's sharp turns
beside a short move, counted vertex by vertex, rise: a tip's turn back that was spread over moves of a few microns now
lies on one vertex. Planning time unchanged; the file a little smaller. What is left: the steps back and forth in narrow places two to three lines wide (their cause in the beads'
ribs is not fixed), an open line's last move, the infill's links along a round outline (a link starting a few microns
from the outline's vertex: 307 on the catalog's holes), and outline jogs of a few microns where two parts meet. The
catalog records, per model and row, the moves shorter than the tolerance (all and the outer wall's) and the outer
wall's kinks, and holds the outer wall of its models of straight sides and true arcs to none of either; a hand-written
square with a short move and a spike inserted is counted (control). Test: CenterLineSimplifyTest (a narrow island's rib
pairs, open and closed: no move shorter than the tolerance, no point moved as far, the plastic within half a micron of
width; control: without the merge the moves of 5 µm stay).

## 54. Where two fills share an edge, one owns it; a band's loops stay in the band (2026-11-16)

Amends 47 and 48.

**Context.** Decision 48 named two fills sharing an edge as the commonest cause of plastic laid twice. Read spot by spot on
a plain Benchy (92 spots, 58.6 mm², 19 layers) and a two-part Benchy (52 spots, 45.8 mm², 12 layers), with each layer's
regions dumped:
- Every fill of a layer (internal solid, sparse, an internal bridge, a top, a bridge over air) placed its lines against
  each edge of its region as against a wall's bead: bead contact, then the line-end overlap
  (`plan.layer.infill.wall_overlap`). Where two fills meet there is no wall, and both did so: their line ends and linking
  runs lay over each other along the edge (top beside internal solid, internal solid beside sparse, a bridge beside
  internal solid; 0.25 to 0.38 mm wide).
- The largest spots were another cause. A band of internal solid up to five lines wide with a hole (round a sparse core,
  or between a deck's top and a cabin's walls) is laid as loops; they were the band's outer edge moved in, so where the
  band is narrower than its mean width they ran on over its hole: over the sparse infill (12.9 mm² on one layer of the
  Benchy, 13.8 mm² on the two-part one: the same place, not two parts' fills) and over a cabin's inner walls (9.1 mm²).
  Decision 48's "concentric solid ring over an inner wall" and "two parts' fills over each other" are this.

**Decision.**
- The fills of a layer are decided before any is laid. Where two share an edge, one owns it, in the order a top, a bottom,
  a bridge over air, an internal bridge, internal solid, a narrow sparse piece laid solid, sparse infill. The owner lays
  its lines against the edge as against a wall's bead; the other lays its lines against the owner's beads as against a
  wall's: bead contact with them, then the line-end overlap (Planner.reachUm). A sparse piece's single loop runs along
  the owner's beads at bead contact. A top gives a bridge's anchor area up, as internal solid and sparse do.
- A band's loops are cut where their middle leaves the band (SurfaceFill.outOfHoles: the cut the outside-in concentric
  pattern makes at a hole grown by half a spacing, here with none, so a loop is kept wherever it covers more of the band
  than of the hole).

**Rejected.** Both fills at bead contact with each other and no overlap (each line half a spacing from the edge, the first
version): where the gaps between both fills' round line ends met, it left holes about 0.3 mm across along the edge (a
seam: 1.07 mm² bare along 20 mm between two fills hatched at 45 and -45 degrees). Both fills reaching the line-end
overlap past bead contact with each other: the linking runs along the edge lie over each other by that overlap twice,
more than a fill and a wall overlap by plan. A line of its own along the shared edge, laid as a wall between the fills:
it would close the gaps between line ends too, at a path and its ends more on every such edge; not built.

**Consequences.** The plain Benchy: 92 spots, 58.6 mm², on 19 layers to 16, 4.4 mm², on 8 (no fill over another fill or a
wall left); the two-part Benchy: 52, 45.8 mm², 12 layers to 14, 4.0 mm², 6. What is left there: internal solid over
internal solid where a narrow piece's loops (each the piece's edge moved in) come within a spacing of one another, and
two bridges' lines at one place on the first bridge layer. Filament: Benchy 4060 to 4022 mm (internal solid -4.7 % of
its length, of it 3.6 % the band loops' stretches outside their band, sparse -3.0 %); the two-part Benchy internal solid
-3.3 %, sparse -2.0 %. The catalog: the bridges' 7 spots and the sloped top's 28 to none; sparse infill shorter on
most models (up to half on the narrow ridges, where a narrow sparse piece beside solid shells gives up its edge to them);
no wall, seam, or check measure moves. Its new model, a stepped top (a top beside internal solid, an internal bridge
beside internal solid and sparse), reads none. FillContactTest: a stepped block and a table, no plastic laid twice
between fills; controls on two fills side by side: both placed against the edge lay 1.12 mm² twice, both at bead contact
leave 1.07 mm² bare. Planning time within the runs' spread (a layer with one fill makes no extra boolean). Open: where both fills' gaps between line ends meet, holes up to 0.275 mm across remain (0.15 mm
where the same fill meets a wall on that layer; the hole rule's floor there is 0.176 mm, and the rule does not read
infill).

## 55. A narrow island's beads and their measure take less work, with the same output (2026-11-16)

**Context.** NarrowIslandTimeTest (a 55 mm strip laid by its beads and measured against its walls, decision 28, at most
3.5 times the same call answered by the narrow-island cache) failed once in CI. Measured over 150 islands after 60 to
warm up, the ratio had not moved across the commits since the test was written (2.45 to 2.68 at each); the test's own
reading, 15 islands after 30, varies by about 0.3 from run to run (2.3 to 3.1 on the development machine), and a CI
runner reads it on fewer cores. Profiled, the uncached call spent 60 % of its time on the narrow island: the island's
measure 27 % (both measures run on this strip: the beads alone exceed the absolute limits, so the walls are measured too;
almost all of it whole-grid passes over about 550,000 cells, not the distance searches), the beads' construction 26 %
(a third of it the Delaunay triangulation the cached path's skeleton uses too), the simplification of the beads' lines
7 %.

**Decision.** The same results, with less work:
- IslandCheck.measure finds each row's crossings edge by edge over the rows the edge spans (flat arrays, not a list of
  boxed numbers per row scanning every edge), fills the covered area straight into the cover, marks a cell's owner as
  the line's number plus one (zero: none, the array's own zeros), counts the cells two lines own as each is first
  found, and makes one pass over the grid for the uncovered cells inside and the covered cells outside, in order.
- Walls.simplified keeps the narrowest and widest width of a move's points as the move grows (every width lies within
  twice the half-tolerance of the move's exactly where these two do), reads the points between newest first, and settles
  a point whose distance from the move is clearly within or beyond the half-tolerance (by more than a millionth of its
  square) from the exact whole-µm cross and dot products, the full computation as before for the rest.
- The beads' grids (the middle lines' ends against the loops, giving way, the loops' turns) are primitive hash tables of
  cell lists, not maps of boxed keys to lists; each look-up's result does not depend on the order it reads a cell's items.

**Rejected.** Raising the test's limit or measuring over more islands: the limit is the measure of the narrow path's
cost, and the test is not to change. A faster triangulation: it takes as much off the cached path's skeleton as off the
beads, so it moves the ratio the wrong way (an option for planning time on its own).

**Consequences.** The strip, over 150 islands after 60: 27.5 ms an island against 10.5 cached (2.6 times) to 22.2 against
10.6 (2.1 times); the test's own reading, ten runs: 2.39, 2.29, 2.43, 2.53, 2.75, 2.51, 2.53, 2.29, 2.52, 2.61 (2.6 to 3.1
before), and 2.4 inside the fast suite, beside the other tests. The regress cases and the catalog are byte for byte the
same; planning time moves no more than the runs' spread shows.

## 56. The outer loop of a narrow island gives way across the outline; a cut whose ends would overlap is not made (2026-11-17)

Amends 28 and 40.

**Context.** A narrow island's outer line stepped back and forth over itself where a place is two to three lines wide:
kinks on the outer wall (a turn of more than 150 degrees beside a move shorter than the outer line's width) where the
line goes on the way it came, besides those where it turns back out of a tip by design (decision 28). Read point by point, from the construction's own points:
- Loop 0 lies at half the place from the outline where the place holds two lines, at half the outer line's width where
  it holds three or more. Where the count rises from two to three, the loop's points step that difference (60 to 80 µm)
  toward the outline between two ribs of one outline sample, a step across the track with almost nothing along it.
- There the loop gives way to the middle line's round start (decision 28), each point along the way across from the
  points beside it. On the step that way runs along the track: the points before the step moved toward the outline, the
  points resampled along the step moved along it or not at all, and the line went down, back up the step and down again.
  Decision 40 leaves out a moved point that lands behind, not an unmoved one ahead of it.
- Where the loop stops short of a corner (a corner's end, on no rib), its points had no outline sample, and the outer
  loop's points of no sample were not moved at all (the side to give way on was read from the sample): beside a corner's
  node, its points moved and the corner ends between them stayed.
Why only two to three lines wide: only there does the outer loop's distance from the outline change with the count (from
two lines' share to the outer line's own width); from three lines on it stays half the outer width (loop 1 likewise only
at four to five).

**Decision.**
- The outer loop gives way across the outline at the point's outline sample (the direction from the sample before it
  to the sample after), at every point: so the step's points move across the track as their neighbors do, and decision
  40's "behind or within the tolerance of the last point kept" reads along the outline. A corner's end takes the sample
  of the rib beside it. An inner loop and a middle line keep the way across from the points beside them.
- A cut (decision 28: where a bead gave way entirely, it is not laid) whose two ends would lie within half the narrower
  one's width of each other is not made: each end's round reaches past the other, so the two lay each other twice and
  cover the gap anyway. The points given way are left out and the line goes on across.

**Rejected.** Keeping only the longest run of a loop's points that never steps back along the outline (tried with
decision 53): it works on the points before they give way, where the step has no point behind another, so most of the
zigzags stayed, while more plastic was laid twice. Read layer by layer, the new spots lay in narrow islands that had kept their walls: the island's self-check (decision 28) now took
their beads, which lay twice elsewhere (an outer line still zigzagging, a line over the outer loop). Giving every loop
the outline's way across: an inner loop gives way on both sides to the loop outside it near a turn, where its own track
leaves the outline's direction (a tip, a corner); there it gave way entirely to the outer loop at a slant and left a bare
area (an island then kept its walls). Hysteresis on the count along the outline: the count already changes
once where the width passes a threshold (decision 28); the step is the loop's place changing with the count, not the
count flickering.

**Consequences.** Most outer-wall zigzags go. Fewer narrow islands keep their walls (none newly keeps them; their beads
had been refused mostly for the plastic their own zigzags laid twice), so there are a few more tip run-ins and moves
shorter than the tolerance; plastic laid twice the same; planning time within the runs' spread. On the 3DBenchy regression cases the walls alone change, on 16 layers of
its cabin posts (inner wall +7 mm of 40 m). What is left: an unmoved point beside
moved ones at a tip's run-in (the ray from it misses the middle line's round start that its neighbors' rays meet), and in
the catalog the two blobs and a neck, the dipped ridges, the finger in a second part and the exposed wedge. The catalog
records the outer wall's zigzags per model and row (lines.outer_zigzags) and holds the narrow islands' models to none; a
new model, a stroke bent round three quarters of a circle about as wide as two lines become three, had two such zigzags
on one layer and has none. Tests: WallsByWidthTest (no zigzag on the bent stroke; control: that layer's outer line as it
was is counted), CatalogTest (a spike in a square's side is a zigzag, a run in and back out over itself is not).

## 57. A junction of a thin area's line joins one pair through it, the straightest that leaves no branch's pocket bare (2026-11-17)

Amends 33.

**Context.** On a layer holding a fine pattern of square cells of one part among bands of another, the pre-print check
refused a hole inside the walls, and plastic was laid twice. Read on the walls alone, from the layer's own outline:
- The cells, and the bands of the other part between them, are areas too narrow for two walls, laid as center lines.
  Where four branches of the skeleton meet (a cell's middle, where its corner forks all reach out; a crossing of two
  bands), the ends on one point were joined pair after pair, the straightest first, with no test of whether a line already
  ran through the point: both pairs were joined, and two lines lay over each other across the junction's whole width
  (up to 0.9 mm). A cell's branches also joined across the gap between two of its corners after one pair ran through the
  middle, so the line came back to the middle as its own branch and joined there again.
- A tooth of the outline between two notches, its neck too narrow for the second loop: the leftover inside it is a
  blob running into the neck. Its skeleton forks in the blob: two branches into the blob's corners, one up the neck. The
  corners' pair runs on straighter through the fork, so it was joined, and the neck's branch, shorter than the junction is
  wide, gave way to it along all its length (decision 33: a line within that reach throughout is dropped). Nothing was
  laid in the neck.

**Decision.**
- On one point, as across a gap, an end joins nothing where a line already runs through the point: one pair of a
  junction's branches runs through it, every other one gives way to it (decision 33's cut back). A line counts as running
  through where it was joined through the point before, the end's own line too (a branch joined at its far end to the line
  through the junction is still a branch there).
- The pair is the straightest of those that leave no other branch lost. A branch is lost where it would give way to the
  pair along all its length while, where it is wider than the narrowest line, its bead reaches out past theirs by more
  than the narrowest place a line is laid in and the slicing tolerance a side (decision 31's floor): left bare, a hole. A
  branch into a corner narrows to the narrowest line at its end: what it leaves is a corner void, as where a fork is pruned.
- Across a gap nothing changes.

**Rejected.** Cutting a lost branch back less (into the pair's bead past the wall overlap): its end laid over the junction
twice, and in a short neck the branch has no length left to lay. Reaching the lost branch's far end into the band ahead
of it (where the neck meets the next loop): it fixes a neck between walls, not a crossing's stub against another part.
Weighing every branch's pocket without the narrowest-line condition: a branch into an acute corner was taken for a hole,
and the corner pair given up laid the line in two paths and another seam a layer (a catalog model's seams 120 to 168).
The same rule in a narrow island's beads (Beads): not needed by any known case, and it moved a narrow island to its
beads, which lay a loop over itself.

**Consequences.** The hole refusal and most of the plastic laid twice go. The catalog moves nowhere; the regression
cases (tools/regress.sh) are byte for byte the same. Left: the outer loops of two fit pieces of a level turning back at
a pinch narrower than the loop's fit (between two pockets, between four cells); a bottom surface's line over its own on
the first layer. Tests: JunctionJoinTest (a crossing with a
stub, a blob into a short neck; control: a junction's ends joined pair after pair lay the crossing twice and leave the
neck bare).

## 58. Two loops of a level turning back at a pinch are kept a spacing apart, the smaller cut back (2026-11-18)

Amends 26 and 52.

**Context.** After decision 57, plastic was still laid twice, outer wall over outer wall: between two pockets cut into a
face (on layers where the pockets' lowest parts are holes just inside the face), and where four cells of a fine pattern
meet at a corner. Read on the walls alone: the area narrows between two notches to less than the outer loop's fit
needs, so the fit is cut there into two pieces, each with its own loop. Each loop is its piece grown by half a spacing,
and two pieces less than two spacings apart grow loops that come closer than a line: their turns face each other, the
mitred tips reaching into the pinch. Decision 26 named this ("two pieces of a loop meeting at a pinch then ended in
two turns facing each other") as what joining loop pieces across a short gap left; nothing kept pieces too far apart to
join from overlapping.

**Decision.** A level's loops are read as islands; each island that comes within a spacing of a larger one (by area) is
cut back from it: its parts within a spacing of the larger island taken out, so its line turns against the larger
loop's bead, the two touching with the overlap of neighboring beads. A loop shorter than a circle of the spacing after
the cut is dropped as before. Where no island is cut the loops are the same polygons.

**Rejected** (reasoned, not tried). Cutting both loops back from each other: both would retreat up to a spacing and
leave a slit across the pinch, a line there over both. Joining the two pieces through the
pinch as one loop: its two sides would lie closer than a line through the pinch, the same plastic twice.

**Consequences.** Such pinches lay nothing twice. The catalog moves nowhere. Of the regression cases the 3DBenchy's two change, on their first two layers only:
on the first, a small loop beside the hull's loop at a pinch is cut back from it; on the second, the scarf seam of the
loop above it moves with it (seams follow what lies under them, decision 32). Tests: PinchLoopsTest (a band pinched between two V notches: the outer
loops of its halves 0.419 mm apart at the pinch and nothing bare; control: not cut apart, 0.186 mm).

## 59. A fill line only grazing a corner outside its area is laid through it as one line (2026-11-18)

Amends 54.

**Context.** A bottom surface's line can be laid over another of its own. Read on the g-code: one diagonal line of the
surface passes the corner of a wall loop. The fill's area there (its region pulled in to bead contact with the wall, then
out by the line-end overlap, decision 54) has that corner reaching a few microns across the line, so the hatch cuts the
line in two at it: one line ends there and runs along the wall to the next line, another starts a few microns on. Both
round ends lie against the corner, over each other by nearly a line's width. Any line of a
fill that passes a corner of a wall, or of the fill that owns an edge it shares, closer than a few microns is cut so.

**Decision.** Two lines of one scanline whose ends are closer together than the line is wide are made one line where that
lays less plastic twice: laid through, the line's center comes off its area by at most its farthest point's distance from
the area's edge (read every micron along the gap, among the area's edges near it); cut, the two ends overlap by the line's
width less the gap; they are made one where the first is less (Hatch.throughGrazes). The surfaces, internal solid, sparse
infill and bridges do this; support lines and ironing do not (support is placed and joined by its own rules, ironing is
laid over a top on purpose). The comparison takes no number of its own: both sides are plastic laid twice, measured
across the line, and the lesser is laid. Since the line laid through is never farther from the area than half the gap,
every gap shorter than two thirds of a line is laid through, and a longer one only where the corner is flat.

**Rejected.** A gap shorter than the slicing tolerance only (the line off the area by less than the outline is known):
it fixes a cut a few microns deep, but a line passing a corner 20 to 100 microns deep lays its two ends over each other
as much. Pulling both ends back until their round ends no longer meet: it leaves a bare spot at every such corner, up to
a line wide, where the line laid through covers the corner at a few microns more overlap with the wall.

**Consequences.** Regression cases: the plain and ironed Benchy 16 spots (4.429 mm²) on 8 layers to 13
(4.119 mm²) on 6, the test plate 8 spots (1.309 mm²) to none, filament 1 mm less each; the hole block and its fuzzy-skin
case lay one line of a layer through where it was cut (12 paths to 10). The catalog: no measure of the check, plastic laid
twice or the walls moves; the holes' bottom surface 13 paths to 11 (4 fewer visible restarts), the pins' top 12 to 11;
the overhang ladder's top 9 paths to 12 (2 more visible restarts, 31 mm more travel): its top's lines graze the corners
of four square holes' walls with thin strips of the top between the holes and the outline, and a line laid through
changes where the monotonic sweep's chains split and merge (Linker.link). Tests: HatchTest (a line grazing a diamond
hole's corner by 5 microns is one, the lines crossing the hole two; a line 0.4 mm short of a flat corner is one, across a
slot as wide it stays two; controls: clipped alone, the grazing lines are two with their ends closer than a line; with the
depth not compared, the slot's lines would be one).

## 60. The catalog's zigzag measure does not count a square end's corners (2026-11-18)

Amends 56.

**Context.** The catalog's outer-wall zigzag (decision 56: a kink where the line's direction over a line width before and
after it stays within 120 degrees, the line going on the way it came) counted the corners of a narrow island's square end.
Where a loop turns a tip's square end (a bar 0.65 mm wide, laid by its beads), decision 28's corners run it into each of
the end's two corners and back, 0.07 to 0.09 mm, turning a right angle there: the turn over a width is 100 to 105 degrees,
so each read as a zigzag.

**Decision.** A kink is no zigzag where the loop turns a square end's corner: its direction over a line width turns more
than 60 degrees there (about a right angle, not going on straight), over two widths it goes back the way it came (more
than 120 degrees: the end's second corner within a line or so), and the spike points out of the turn, into the corner.
60 degrees and two widths are plymeld's choice, bounds of the measure's geometry, not print values. A spike at a single
corner, the line not turning back within two widths, stays a zigzag: a center line's stub at a junction where the line
turns a right angle (the pattern's square cells) reads so, and is a defect.

**Rejected.** Exempting every spike at a right-angle turn: a center line's stubs at a right-angle junction turn the same
way and would no longer count. Reading the path's being closed: a narrow island's loop is closed, but so is a loop that
spikes onto a node a few tens of microns from the outline, which is a defect.

**Consequences.** A square end's corners no longer count. A spike that does not turn a right angle (the loop going on
past it, as where a loop spikes onto a node at an acute tip's mouth, close beside the outline) still counts. The
catalog: no measure moves. Test:
CatalogTest's control (a bar's square end run into at both corners: two kinks, no zigzag; a spike at the square's
corner: a kink and a zigzag; controls: the exemption taken out, the bar reads two zigzags; the two-width reach taken out,
the corner spike reads none).

## 61. A neck a narrow island's axis reads narrower than the narrowest line never takes a count of loops (2026-11-18)

Amends 28.

**Context.** A narrow island's outer loop dipped toward the outline and back where a slit narrower than the outline's
sampling (75 microns) is cut into the island from its edge (a sliver of another part between two strokes). Its medial
axis, from the triangulation of the samples, ran round the slit's inner corner through nodes a few tens of microns from
the outline, between wider junctions: a stretch one line wide by its width, shorter than the transition length. Decision
28's smoothing gave it its neighbors' count of two, so the outer loop ran over its ribs, each point at its place from
the outline but no further than the node, and was drawn onto the node.

**Decision.** A short stretch takes its neighbors' count, as before, except a count of loops (two lines or more) in a
neck that the axis reads narrower somewhere than the narrowest line (`plan.layer.walls.line.width.min`): a stretch the
axis leaves at two places or more, not an end. A loop's two sides cannot both lie in such a place, each at its place from
its own side; the stretch keeps its count, and the loop turns at its mouth, as at any place too narrow for it. A short
narrow end still takes the two lines beside it, a little narrower than the narrowest, so they turn round it. No number of
its own: the narrowest line is the bound.

**Rejected.** Leaving out a loop's point on a rib whose node lies nearer the outline than its place (as a corner's end
is): where hysteresis keeps two lines across a short dip a little narrower than they need, the loop's two sides are drawn
toward each other there by design, and without those points the loop would run straight across the dip, past the
outline. Sampling the outline finer near a narrow slit: a change to every island's axis for one reading of it.

**Consequences.** Only such islands change. The catalog and the regression cases: unchanged. Test:
NeckLoopTest (a band 2 by 1 mm with a slit 40 microns wide cut 150 microns into it, its inner corner carrying a 1 micron
step: the outer loop's nearest point to the outline 154 microns, nothing bare wider than the floor, no plastic outside;
control, the neck taking its neighbors' count: 16 microns, 34 microns of plastic outside; the test's own check seen to
fail with the rule taken out).

## 62. A narrow island's loop leaves out a run into a tip's end that reaches no further than a line's end overlap past its bead (2026-11-18)

Amends 28.

**Context.** Outer-line zigzags appeared as spikes in the loops of narrow islands, read one by one on the islands as the
walls hand them over. A narrow island's loop runs into a tip (decision 28: the tip's two sides at the narrowest width,
then out and back along the axis where the tip is narrower than the narrowest line), and where the tip ends within the
loop's bead beside it, the run goes tens of microns out and back, its bead reaching past the loop's by less than an
interior line's end overlap of its width, while the loop goes on past the tip, turning less than 60 degrees over a line
width either side. A sliver's center line of the walls can spike the same way near its end: a stub at a junction
(decisions 33 and 57), left with those.

**Decision.** A loop's run of points on the axis of a tip it runs into (the tip's place narrower than the narrowest
line) is left out where every point of it lies within the bead of the loop's point beside it, the nearer of the two,
but for `plan.layer.infill.wall_overlap` of its own width (no further past it than an interior line's end reaches into
the walls, the bound a junction's third line and a middle line's end give way by), and the loop turns no more than 60
degrees from the way it runs in to the way it runs out, over a line width each. Where it turns more, it turns a corner
there (a square end two lines wide turns a right angle within a line width), and its run into the corner fills the
outside of its turn, as decision 28 meant (decision 60 found those to be no zigzag). 60 degrees is plymeld's choice, the
square-end bound of decision 60's measure; read across the runs' joins (a tip's end lies where one run of the loop ends
and the next begins).

**Rejected.** The end overlap alone, every run within it left out: the square end's corners of a band 0.6 mm wide were
dropped too (corners the loop turns). Reading the loop's turn at the run's two neighbors
only, without the line width (reasoned, not tried): a loop can turn more than 60 degrees between the points beside its
run while going on past the tip, and would keep its spike.

**Consequences.** Such spikes go; nothing else moves. The catalog and the regression cases: unchanged. Test: TipRunInTest (a band 2 by 0.7 mm tapering over 0.6 mm to a blunt end 0.24 mm wide:
no zigzag, nothing bare wider than the floor; control, the run laid: one zigzag; the test seen to fail with the rule
taken out; a band 0.6 mm wide keeps its runs into its square end's corners, seen to lose them with the turn's bound
taken out).

## 63. A small thin area keeps its own two longest forks; a narrow island's stub at a junction is left out (2026-11-18)

Amends 28, 33 and 36.

**Context.** Most of the remaining outer-line zigzags were one thing: a short dead end at a junction that the line ran
out along and back, tens of microns, in the walls' center lines (in small cells, and in narrow islands) and in a narrow
island's middle beads.
- The walls (decision 33): where a round of pruning would leave a small area less of its skeleton than its widest line,
  the two longest of the forks that round cut stayed. In a cell about half a millimeter across (JunctionStubTest's),
  those were a short trunk whose own corner
  forks went in an earlier round, beside two longer forks that stay anyway: the line ran down the trunk and back.
- The beads (decision 28, step 6): a piece from a middle line's junction to a dead end was joined as any end, closest
  first: it joined one of the other pieces there, and the third piece's end then joined its dead end, so the line ran out
  along it and back.

**Decision.**
- A small area keeps its own two longest forks (of all its forks with a dead end), not the two longest of those cut.
- A narrow island's piece is a stub at a junction where one end lies where two or more other pieces end and the other on
  no other piece, within the joins' reach, its bead within the junction's (the widest of the others' ends there) but for
  `plan.layer.infill.wall_overlap` of its own width at every point (the bound a junction's third line gives way by,
  decision 33), and its side of the axis reaching no further past the junction's bead than the narrowest place a line is
  laid in (decision 31's floor, read on the axis's nodes beyond its dead end as decision 33's pruning reads a fork's
  pocket). A stub is left out, not joined; at a junction the shortest go while two of its ends stay.
- Decision 36's rule for a dot beside another part reads each line as it was joined with the stubs left out of it
  (`CenterLine.withStubs`, carried through every change made to the line after: its ends reaching into the walls, its
  simplification). The rule's bounds were set on those lines: without its stub a short line it kept laid becomes a dot
  (a V-shaped line on a ridge's dipped crest beside a second part, 0.4 mm long, left a hole when left out). This is a known
  limitation, kept for that calibration, and may be revisited. The paths' layer cache reads those lines too.
- Beads' joins no longer mark a piece closed in place when it joins round on itself (the pieces are joined twice, with
  the stubs and without); the line still closes on its first point.

**Rejected.**
- Cutting the walls' stub where its ends join (decision 33's give-way): the line through the junction gave way to the
  stub first (a catalog model's finger lost a 0.22 mm line).
- Leaving out every narrow island's stub whose bead lies within the junction's, with no reading of the pocket beyond it:
  a middle bead's run into a corner arm went too (an island's widest bare spot grew), and more islands fell back to their
  walls.
- Reading the dot rule on the line as laid: the ridge's crest left bare. On the span of its medial axis instead: the
  crest faults where the line is curved; read as the extent of the axis's discs, many more dots laid, with more visible
  restarts and a new spot laid twice.
- One reading of covered ground for both the walls' bands and IslandCheck (below), either by moving the walls' band half
  a flank toward the loop (small cells stayed bare by IslandCheck, and the catalog's wall reading of one model grew
  worse: doubled 0.95 to 1.06 mm², outside 0.19 to 0.24 mm²), or by IslandCheck reading a line at its full width (the
  narrow islands decided under it opened a hole 0.30 mm wide in the Benchy, which the pre-print check refused).

**Consequences.** Most such zigzags go (left: a loop turning across a tab a little wider than a line, and beads stubs
whose pocket reaches past the floor); planning time as before. The catalog: zigzags fewer (an exposed wedge 1 to 0, a finger inside a second part 15 to 0, a dipped ridge 5 to 0, one
beside a second part 6 to 1), lines through small areas laid as one where a trunk and a fragment were (fewer paths), no
check, laid-twice or wall-reading measure moved. The regression cases: the Benchy's and the test plate's short corner
lines follow the area's two longest forks (most 0.05 to 0.6 mm shorter); the Benchy passes the check, laid twice as
before, filament 4022 to 4021 mm.

Open inconsistency: IslandCheck reads a line narrower than the pre-print check's hole rule (HoleCheck) and the catalog's
wall reading (WallCoverage) do, its bead less half its rounded flanks a side against their full width. By IslandCheck
alone the walls can leave small cells bare wider than the floor where WallCoverage reads nothing wider than the floor and
HoleCheck no hole. Those slivers are accepted for now.

Tests: JunctionStubTest (a cell 0.52 by 0.58 mm between walls, laid by its center line; an L of a strip 0.34 mm wide with
a stub 0.1 mm long at its corner, laid by its beads: no line turning back, nothing bare wider than the floor, each
line's `withStubs` turning back where the stub was; controls, each rule off, lay the stub out and back; the test seen to
fail with each rule taken out); ThinRidgeTest seen to fail with the dot rule reading the line without its stubs.

## 64. A ring of a concentric-inside-out surface runs on past its arc along a wall, narrowed to the ground left (2026-11-19)

**Context.** A top in round rings (`concentric-inside-out`) printed a dashed slit along its edge. Each circle is cut
where its center leaves the area a line's center may lie in (bead contact with the walls, then the line-end overlap,
decision 54), and where the walls run nearly along the rings (an oval's sides and ends, a cylinder's edge) the arc inside
the last one cut was left as the line nearest the wall: between its bead and the wall lay a sawtooth of wedges, each from
nothing to nearly a spacing wide, that no line covered. On a quarter of the catalog's oval (24 by 14 mm, its top in
rings), the top layer read 0.298 mm² bare beside the walls, the widest spot 0.120 mm (RingTopTest's reading of that
code). The pre-print check passed it (its hole rule leaves out ground a surface line borders, decision 30).

**Decision.** Each circle runs on past where its arc ends, its line narrowed to its own bead's cross-section (taken
across the circle) within the ground a line may lay plastic on (the area grown by the line's reach past the walls,
`Planner.reachUm`, less the slicing tolerance, the wall known no closer), its center moved to that part's middle: the
circle's bead with what lies past the walls' reach shaved off, so it lays nothing over the ring inside it beyond what two
neighboring lines share. A narrower line reaches into the wall the line-end overlap's share of its own width
(`plan.layer.infill.wall_overlap`, as the walls' narrow lines and the laid-twice rule read it). It runs on while that is
at least half a line (the hatch's own bound for slivers), and where the ground is narrower than half a line by no more
than half a line's end overlap, at half a line reaching over the ring inside it by the difference, as a line end reaches
into what it meets. Where it runs round to its next arc the two are one line; round the whole circle, the circle is
whole (and is joined with the rings beside it into one path, as whole rings are); where its band reaches the ground with no arc of its circle
there (past an oval's tip), it is a piece of its own. A free end is narrowed until its round end reaches past that ground
by no more than the slicing tolerance, the points before it no wider than the next by more than twice the tolerance (the
file lays a move at its ends' mean width); points are taken a quarter of a line apart, more where the width changes
faster (at most 16 between two, a work bound). Where a circle crosses the wall steeply its round end already fits and the
arc ends as before, as a hatch line's. plymeld's choices: the quarter-line step and the 16-point bound (work bounds, not
print values); half a line is the planner's sliver bound, the overlap and tolerance are settings.

**Rejected.** A loop round the surface's edge, the circles laid against it: the same wedges, one line in. Filling the
wedges afterward with lines of their own: a short line per wedge (hundreds on a large top), each a start and a stop on the
top. Running each cut circle on along the wall at its full width: up to a whole line laid over the ring inside it where
the two come close. Reaching a narrowed line as far into the wall as the full line (its end overlap of the full width):
the catalog's oval read specks of plastic laid twice all along its walls by the measure that reads the overlap of each
line's own width. Narrowing to the tool's narrowest line (`plan.layer.walls.line.width.min`, three quarters of the bore
as a stand-in) instead of half a line: it stops each run where the wedge is still about 0.19 mm wide with 0.44 mm lines (computed
from the bead model, not measured), and that setting is the variable-width walls'. Fitting arcs to a path's runs of one width in the writer (so a
narrowed ring keeps its arcs): a change to every variable-width wall's file, outside this fix.

**Consequences.** A path with a narrowed stretch is written as straight moves, not fitted to arcs (the writer fits only a
path of one width); a run of whole rings that a narrowed ring joins is too, so the file grows. Line patterns leave the
same wedge where their lines run nearly along a wall: not changed here. The regression cases: unchanged (none lays this pattern). The catalog: a new model, an oval 24 by 14 mm
with its top in rings (its top layer read by the walls' measure on 0.02 mm cells: the whole oval on the walls' 5-micron
cells ran the catalog out of memory); no other measure moves. Tests: RingTopTest (a quarter of the catalog's oval, its
top layer by WallCoverage against the model's cut: 0.087 mm² uncovered, hairlines where beads meet, the widest 0.020 mm,
within 0.15 mm² and a quarter line; control, its narrowed moves left out: 0.293 mm², the widest 0.120 mm, as the code
before read 0.298 mm² and 0.120 mm; plastic laid twice no more than the control's but for hairlines; the check passes
with nothing laid twice; the test seen to fail on the code before); SurfaceFillTest (circles on an oval with a square hole
run on beside both, each narrowed point's cross-section within the reach, none narrower than half a line; control,
without the reach: none narrowed).

## 65. `plymeld render` draws any g-code, its options held to seven rules (2026-11-19)

**Context.** Every picture of a g-code was a test's or a calibration plate's (`render-plate`, the techniques' sample
toolpaths, the check's failure pictures), each with its own reader, scale, and words. Looking at a layer, a spot a check
message names, or a feature of any file meant writing code. A general command needs options a stranger can read without
the code beside them.

**Decision.** `plymeld render FILE.gcode` draws what a file lays down, from the file alone: no slicing, no printer, any
file, plymeld's or not. Its options follow seven rules. (1) One option, one meaning: no option takes two kinds of value, and no
value has a language beyond a list, a range (`A..B`), or a point (`X,Y`). (2) The name says what the number is: a layer
number and a height never share an option (`--layer` against `--layer-at-z`, as `slice` has `--pause-at-layer` and
`--pause-at-z`). (3) Three families told apart by their first word: a view option makes a picture (`--layer...`), an
`--only-...` option chooses lines, everything else styles or frames every picture of the run. (4) Order never matters.
(5) Nothing is silently ignored: an option that applies to no picture asked for is refused, naming it and what it needs,
and two that cannot both hold are refused, naming both; a default the file cannot honor falls back and says so (a file
naming no features is drawn in one color, with a note), where the same value asked for explicitly is refused. (6) Units
and numbering are fixed and in the help: lengths in mm (a bare number or `5mm`), the first layer 1, tools by the file's
own numbers. (7) Every caption states exactly what was drawn: the layer and its height and thickness, the side it is
seen from, the scale, the frame, and how lines are drawn, colored, chosen, and marked.

A line is as wide as the plastic it lays: the bead model's cross-section (one definition, `plymeld.program.Bead`, which
the techniques' sample pictures now use too) from the filament the move feeds, on its declared height or its layer's
thickness. The default scale sets the closest lines in view at least 3 px apart (beads side by side at the bead model's
spacing, leaving out a move that lays less than half its path's declared width and then the narrowest 1 % of the length
laid in view: a scarf's ramp or a line's tapered end, which lie on or into a line rather than beside it), the drawing between 1000 and 4096 px on its longer side; where 3 px cannot be
had, each feature's area is drawn as a flat region with a darker outline and the run names the `--span` at which the
lines would resolve; `--allow-unresolved-lines` draws them anyway. Such a picture at the default scale is at most 2048
px on its longer side (plymeld's choice, half the largest drawing's): a region and its outline read at that size, and
the largest size drew four times the pixels for nothing finer (a whole-print 3D view's command took about two thirds of
the time at 2048 px); a scale given (`--px-per-mm`, `--picture-size`) or `--allow-unresolved-lines` keeps its own size, and a
picture seen from the side whose layers alone are too thin to resolve is made no larger either. Consecutive moves of one color turning
less than 60 degrees are drawn as one stroke, its edge darker and drawn round the stroke as a whole, so neighbors (a
fill's next pass, a stroke of its own) read apart at their true width; in flat regions each bead is widened by a pixel
and a half, so lines too close to show close up into one area rather than a pattern of gaps. Arcs are chorded through the
check's own arc code (`ModelCheck.arcChords`) to within half a micron of the curve. The file is streamed: read once
to index its layers (each layer's byte offset and the reader's state there), then each picture reads only the layers it
draws. A
file plymeld wrote gives its filaments' colors and diameters from its source block. Pictures go to `<file>.render/` (the
file's name without `.gcode`), one file a view named from it (`layer-0060.png`); PNG by default, SVG on request, from
the same drawing code.

**Rejected.** Growing `render-plate` into the general command: its pictures are built for one plate's layout (a front
by temperature, an isometric labeled from the plate's page); it stays as it is and may become views of `render` later.
One `--view` option with a small language (`--view layer:60`, `--view cut:y=110`): every view's values in one string,
read by rules a stranger has to learn. Accepting a layer or a height in one option (`--layer 60` or `--layer 12.4mm`):
a number alone could be either. A fixed scale or picture size: a large part's layer drawn too coarse for its lines to
read, aliased into patterns that look like defects. Drawing lines at a share of their width so neighbors read apart (the
sample pictures' way): the gaps shown are not in the print; a darker edge keeps the true width. The strict minimum
spacing for the scale: a scarf's ramp, laid on the wall at nearly no width, asked for 800 px per mm on a 30 mm part.
Reading the whole file into memory: a file of hundreds of megabytes would need gigabytes for its moves.

A cut draws each line crossing its plane as its bead's cross-section where the plane meets it: the bead model's section
swept along the line and cut by the plane (an affine image of the section, so a line crossing at a slant is drawn as
wide as the plane meets it); a line meeting the plane at less than 3 degrees runs along it and is drawn only within
`--cut-depth`, from the side. A cut seen from the side has its heights stretched so the thinnest layer crossed is at
least 3 px tall (the factor in the caption); a level cut (`--cut-z`) is seen from above, each bead whose height range it
crosses drawn as wide as the bead is at that height, whichever layer laid it. A cut seen from the side reads every
layer it may cross, in pieces of 8 layers on the processors available, put together in print order: the moves one
reading gives, the picture the same bytes on any number of threads.

The outside (`--outside`) and 3D (`--3d`) views are orthographic and drawn into a depth buffer: every chosen move a
round bead swept along it (as wide as its plastic, as tall as its layer, stretched as the heights are), the nearest kept
at each pixel and shaded as a tube, the file read a run of 64 layers at a time; where the lines cannot be resolved, flat
colors with an outline at every change of color and every step in depth of more than 1 mm. Each pixel shows the
nearest bead, and of beads at exactly the same depth the one printed first, so the picture does not depend on the order
the beads are drawn in. They are drawn nearest first where print order allows it: seen from above, in exactly the
reverse of print order (the top layers first), from below in print order; a coarse map of 16 px tiles holds each tile's
farthest depth drawn, and a bead behind the whole of a tile (its middle line's nearest depth over the stretch beside the
tile, less its bulge) passes the tile over without reading its pixels. Seen level from a side, print order is not
nearest first and no tile is passed over: working out a tile's farthest depth again after every bead cost more than it
saved (an outside view from the front took three times as long). The work is shared out over the processors available:
the file is read in pieces of 8 layers, 8 pieces at once (each from its own offset through the index, so 64 layers are
in memory at a time as before, and the pieces are the same whatever the number of threads), and each batch's beads are
drawn by bands of 16 rows (one tile) at once, every band drawing the beads that reach it in the same order; a pixel belongs to one
band, which draws into it in the order a single thread would, so the picture is the same bytes on any number of threads
(no option sets the number: plymeld has none for such work, and the result does not depend on it). Rejected: a buffer
for each thread merged by depth at the end, a whole picture's buffers again for every thread, which a 256 MB heap does
not hold at 18 threads. The first pass a whole-print view needs (the extent it draws as it sees it, the gradient's
range, the closest lines' spacing and the thinnest layer) is made in the index's own reading, so the file is read once
before it is drawn rather than twice. The index reads before it knows all the file states, so those sums are made on
what is known at each move and kept only where that held to the end; the view reads the file again for its first pass
where a filament diameter is stated after moves of its tool (a settings block at the file's
end), where a layer's thickness changed while it was read (a height declared after moves that declare none), where a
layer counted from is one the view does not read (`--z-range` against a layer's own heights), or for a tool numbered 64
or more. A bead that runs at least 4 brush widths across and up is drawn along each row's span, the columns its stadium
(the brush swept along the move) reaches on that row worked out exactly, one more each side, and only those pixels
tested, not its whole bounding box, which is mostly empty; a shorter or level bead takes whole rows of its box, which
cost less than its spans (plymeld's choice of 4: spans for every bead drew a small print's 3D view slower, and a large
file's faster). A row of tiles the bead is hidden in all through is passed over before its spans are worked
out. The shading is kept in 256 steps a pixel (a byte, so a
whole-print picture's buffers stay under 11 bytes a pixel); against the floating shade it replaced, a shaded pixel's
channel moves by at most one level (the step is 1/510 of the bulge, a quarter of a level at most before rounding), and
flat pictures are unchanged. `--compare-with` draws the
other file with the same options, both in the frame that holds the two at the finer scale, and compares them pixel by
pixel (alike within 24 levels a channel, for an edge's smoothing).

`--mark-bare` and `--mark-laid-twice` are the pre-print check's own readings, not a second measure: ModelCheck.marks runs
the check's layer reading (the bare-outline, hole, and laid-twice rules) on the layers drawn, with the settings the check
reads for the object (resolved from the file's source block and the model, planned again with every decision locked as
`slice --from` does). Both need the model (`--outline-of`): the bare rules read its cross-section, and the settings the
check reads are the object's. A file of one object on flat layers only: the check reads a plate's objects and a curved
band each in its own frame, not built here yet, and refused. `--outline-of` cuts the model's triangles by the picture's
plane; a file without a source block takes the model as its file places it, and says so.

**Consequences.** Every view, framing, choosing lines, coloring, marks, the model's outline and the check's measures,
the sheet, PNG and SVG are built. `--only-part` is refused for every file plymeld reads today: none names its parts. `render-plate`
and its pictures are unchanged; the techniques' sample pictures are unchanged (the bead width they read is the same
number by the same formula). Tests on synthetic g-code only: RenderLayerTest (lines where they lie and nowhere else,
control: a line left out; widths from the plastic, control: as declared; arcs against the same path in 4000 short lines,
fewer than 0.1 % of pixels differing, control: a half circle, and the test seen to fail with the arc reader taken out;
the lines `--only-object` chooses; the legibility rule's regions and `--span`; a file that marks no layers and names
nothing; `--layer-at-z`; the sheet; the same bytes for the same options, PNG and SVG), RenderRefusalTest (every refusal,
naming its options; control: each option accepted where it applies), RenderCutTest (a cut through a box of two walls
shows four bead sections a layer at the walls' places, three layers tall, control: one wall, two sections; the side
each cut is seen from, control: a line drawn the other way; a level cut meets a lowered line of the layer above,
control: a higher cut does not meet the layer below; `--cut-depth`; the cut options' refusals), RenderSolidTest (the
outside from the front shows the front wall's tool, control: from the back the other; from the top the top layer, and 3D
from straight above draws the same, control: from the front-left above it does not; `--compare-with` marks the line two
files differ by, control: a file against itself differs nowhere; the refusals), RenderMarksTest (on the generated hole
block sliced with the sample config: the model's outline along the block's sides, control: none without
`--outline-of`; a wall move laid again marked laid twice and one left out marked bare, control: the file as sliced marks
neither; the refusals), RenderSolidBytesTest (eight whole-print views of a synthetic print, PNG and SVG, the same bytes
as recorded from the plain one-pass depth buffer; seen to fail with the tie rule for beads at the same depth taken out;
five views and a cut of a 40-layer print, and of the same print marking no layers, the same bytes on 1, 3, and 8
threads, seen to fail with the pieces used out of order on several threads, for the views and for the cut), IndexSurveyTest (the first pass made in the index's reading is the view's own pass, bit for bit, for
every coloring, both line widths, lines chosen and kept, several views, and a file marking no layers, seen to fail with
the part fan taken at double precision; a diameter stated at the end and a height declared late give none, control: the
same print stating them first gives one), CapsuleSpanTest (4000 random beads, of no length, under a pixel, off the
picture, steep, shallow, and long, and 300 heaps of them with beads at the same depth: the same pixels and values as
the bounding-box routine drawn in print order, drawn in print order, in reverse, with tiles passed over, and by bands;
seen to fail with one round end left out of the span; control: in reverse without the same-depth rule, two beads at
one depth differ), RenderLargeTest (a slow suite: 2500 layers, 15 MB, drawn in 3D in a JVM of its own with a 64 MB
heap within 30 s, 1.5 s measured; control: a 24 MB heap runs out).

## 66. A line between two walls meets a third across its way as a line meets a wall; a loop widens only beside its slit; an outer wall shares no slit (2026-11-20)

A fix to two rules that the code fell short of: decision 26's (a center line's end against a loop reaches into it by
`plan.layer.infill.wall_overlap` of its width past bead contact) and decision 34's (two loops share a slit too narrow
for a line, each half of it wider where it lies).

**Context.** Where a part of another filament lies buried just behind a face, the area in front of it can be too narrow
for all its walls: about three lines across holds the face's outer wall, the buried part's (the area's hole), and a
center line between them, while the face's inner walls turn away from the face at each end of the part and run behind
it. The center line ran on toward that turn and stopped at bead contact: Walls.reachInto read the band nearest its end,
which is the outer walls' beside it, found it not ahead, and left the end as it was. Its round end, the turn, and the two
outer walls closed in a pocket behind the outer wall at each end of the part, on every layer it spans (0.13 to 0.15 mm
across by IslandCheck's measure on BuriedPartFaceTest's section, under the pre-print check's hole floor). shareSlits read
that pocket as a slit beside the face's outer wall and widened the wall there; along an edge it kept only the edge's
start and the points beside the slit, so the widening ran from the start of the face's long edge to the pocket at its
far end: the face's outer wall laid wider (up to 0.045 mm) and further in (up to 0.023 mm), slanting along the whole
face on every layer the part spans, with a step at its end, and back to its place on the layers above and below.

**Decision.**
- Where the band nearest a center line's end lies beside it, and a band lies within the same reach (half its width and
  the skeleton's sampling) straight out from each side of the end (a line between two walls), the end reads straight on
  along its own way: a band whose edge that way crosses within that reach, entering the band and within 60 deg of head
  on, is the wall it ends against, and it reaches in by the same overlap as against a wall ahead. A side wall the line
  runs along, or one closing in on it at a slant (a line thinning out between two walls), is not crossed so, and such an
  end stays as it was; so does the end of a line along the outline (the outer line of a narrow tip), with a wall on one
  side only. Only in an island wide enough for its walls somewhere: a narrow island's walls (decision 28) stay as they
  were, so its beads are judged against the same walls as before.
- Along a loop's edge, shareSlits keeps the last point read before a slit as well as the first after it: the loop widens
  and moves over one step (the skeleton's sampling) where a slit begins and ends, and keeps its place and width elsewhere.
- An outer wall is never widened or moved for a slit (amends 34). The outer wall is the level-0 loop, the wall seen from
  outside: round the outline's outer edge and round each of its holes alike (a hole's surface is seen through the hole),
  a narrow island's outer loop laid by its beads included (its widths are its own construction's, never a slit's). It
  keeps the width and place the walls gave it along a face, whatever lies behind it. An inner loop beside a slit whose
  line across is an outer wall takes the whole slit (all of it wider, half of it nearer, its other edge in place, within
  `plan.layer.walls.line.width.max` and its own facing stretch as before); two inner loops share one half each as before.
  What the inner lines cannot take stays a slit inside the part, and the pre-print check's hole rule judges it: where only
  outer walls cross an area (a web about two lines wide between a hole and the side), the whole slit between them.
- The catalog's wall reading measures the shift between layers only at the points inside its window (a long move reaching
  out of a fixed window was read out to its far end, against the other layer's moves inside the window: a move one layer
  ends at a point the other runs straight through read as a shift of millimeters).

**Rejected.** Joining the center line into the inner loop it meets as one path (the line a chord of the loop, laid as
one path from one end of the part to the other): the walls keep loops and open lines apart (seams, order, scarf), and
the line's end reaching into the loop covers the pocket. Laying the line in front of the part at the inner wall's place
and width so the line behind the outer wall does not change where the part begins: the narrow area's leftover then
holds a slit between it and the buried part's wall (about 0.03 mm at three lines across), which only that wall could
take; the center line's face-side edge already lies where the inner wall's did (within 0.01 mm), its far side
taking the rest. Spreading a shared slit's widening over `plan.layer.walls.transition_length`: started before the slit,
the loop overlaps its neighbor where there is no slit; started inside it, it leaves the slit's ends bare.

**Consequences.** BuriedPartFaceTest (the catalog's plate behind a leaning face, its section on a layer below the plate
and one through it): the face's outer wall straight and even through the part's ends and between the two layers (no
change of its distance from the face or its width between points or layers, against 0.023 and 0.045 mm with both rules
as before, the control); along the face's middle 6 mm, beside a slit at the edge's end, unchanged against 0.020 and
0.040 mm without the point before the slit (control); the widest uncovered spot in front of each end of the part 0.085
and 0.067 mm, against 0.150 and 0.127 mm reaching in only where the nearest band is ahead (control). The pre-print
check's hole rule is unchanged: the pocket was narrower than its floor, a spot a line could not have been laid in. The
catalog: more center lines reach into the walls they meet (inner walls a few tenths of a millimeter longer a layer on the
ridges, webs, lens, pins, sliver tips, and inlay); the web 2.2 mm's widest uncovered spot 0.045 to 0.014 mm, the web
1.0 mm's plastic laid twice 1.16 to 0.79 mm²; loops widen only beside their slits (a little less filament on the
buried fin, exposed wedge, speck, finger, and ridges). Where line ends moved, paths are ordered differently: the web
2.2 mm has one more retraction on most layers (87 to 98) and 1.7 s more, the steep and pointed ridges one more each,
the inlay two fewer, the web 1.0 mm four fewer. Read on along its way in a narrow island too, a line made that island's
walls measure better than its beads on the wedge's first layer, which then kept walls that leave its tip's outline bare
(the pre-print check refused it): hence only in wide islands. The outer wall's rule: BuriedPartFaceTest's face wall with
the pocket left behind it (line ends as before) keeps one width and one distance from the face exactly, against 0.023
and 0.045 mm with the outer wall sharing (control); the web 1.0 mm's two outer walls leave its slit (widest uncovered
0.020 to 0.120 mm, nothing wider than the floor, no problem from the pre-print check; plastic outside 0.165 to 0.097 mm²
and laid twice 0.79 to 0.64 mm²), held now to the floor as the web 1.6 mm is; the 1.6 and 2.2 mm webs and decision 34's
lens pass as before. Outer walls that took a share are laid at their own width: a little less filament on the finger,
inlay, ridges, speck, and webs; the sliver tips' outer wall keeps the points its outline's offset gives it closer than the
slicing tolerance (26 moves; a loop that shared a slit was rebuilt and simplified to the tolerance, which merged them),
and its predicted time is 4 s longer; the wobbly ridge beside a second part has six more such moves on its outer wall.
Seams moved with the outer walls' points: seam scar lower on the webs and dipped ridges, the dipped ridge beside a second
part's jogs 0.4 mm longer in all; the speck model two more retractions. Still open: the line behind the face's outer wall
changes from an inner wall to the narrow area's center line where a buried part begins (its face-side edge in place,
its middle about 0.02 mm further in, 0.03 mm wider); a shared slit's widening still changes width over one step. The
buried part's underside is not a surface of the object (the regions are classified against the whole object, all its
parts together), so the shell rules put no solid layers under it and none as its bottom: its first layer and the first
filament's lines beside it lie over the sparse infill below, as the interior of a part of one filament does.

## 67. Every executable the release ships runs every command before the draft; the command line carries traced metadata (2026-11-20)

**Context.** The tests run on the JVM, and the release ships the command line and the window as GraalVM native images.
A native image reaches what its analysis cannot see (the JDK's imaging, AWT, whose libraries call back into Java through
JNI; reflection; resources) only through reachability metadata. Without it, a command that draws with AWT (`render`,
`render-plate`, `layers --png`, the calibration pages, the pre-print check's pictures) stops in a native image as AWT's
library loads; a test on the JVM cannot see that, only a run of the built executable can.

**Decision.** The command line carries metadata traced by GraalVM's agent over a run of every command
(`plymeld-cli/src/main/resources/META-INF/native-image`, in its jar, so native-image reads it on both platforms;
`tools/native-smoke.sh trace`, with and without a display, leaving out what the terminal library and JNA reach on the
JVM, since in a native image the terminal library picks its own interface; in a home with no font cache, so the
system's fontconfig is asked through JNI). As the window does, the command line takes its own folder as java.home, and
the folder holds a `lib/` (with the JDK's `fontconfig.bfc` where the JDK has one): the JDK's fonts read java.home, and on
Linux ask fontconfig only when its `lib/` is there. `tools/native-smoke.sh check` runs every
command with a built executable, each held to its exit status and to what proves its work ran, a command that needs a
printer, a terminal, or a release feed held to its refusal without one. CI runs it on every pull request with the native
command line it builds; the release runs it on every executable it packages, on Linux (the tar's command line, the
AppImage's command line and window) and on Windows (the package's command line and window), and creates the draft only
after both platforms pass. `NativeSmokeCoverageTest` holds the smoke run to the command tree, with a not-yet ledger
for a command that cannot run there.

**Rejected.** Hand-written metadata for AWT: it covers what someone thought of, and the agent records what a run
reaches. A check of the picture-writing commands alone: the same gap can open on any library a command reaches (a
protocol, a parser, a terminal), and the cost of running every command is minutes. Building the window natively in CI
as well: it takes most of a runner's memory and much longer than the command line; the release runs it on both
platforms before any draft. Creating the draft first and running the checks after: a failed check would leave a draft
holding packages that were never checked.

**Consequences.** A pull request waits for a native build of the command line beside the tests. A release waits for
both platforms' checks before its draft exists, and the draft gets every package at once. A change that reaches
something new natively traces again and commits the metadata's diff. Windows' own imaging library is checked by the
Windows run, not traced: a gap there fails the release, to be traced on Windows.

## 68. A bridged area hanging off one side is laid outward from its supported edge; the check reports a bridge line held at neither end (2026-11-20)

**Context.** Where undersides are laid as bridges (`plan.faces.under.technique = "as_laid"`), a flat layer's area over
air that hangs off one side (joined to printed material along one edge, nothing under it) was bridged across itself.
The bridge direction scores each angle, every 5 degrees, by the share of line length landing on the layer below at both
ends (probed one line width past each end). A bridge's region reaches two line widths into the material around it, to
anchor its lines; over an area hanging off one side, no line over air lands on printed material at both ends at any
angle, so the only lines scoring were those lying on that anchor strip, over printed material, along the supported
edge. The edge's direction won: every such area got it, areas hanging off one edge at different angles alike, and
their lines across them rested on nothing at either end. The pre-print check had no rule for bridge lines and passed
the file.

**Decision.** The bridge direction of an area over air, where the layer's undersides are laid as bridges (as laid, or a
layer outside the wave overhangs' heights, `plan.faces.under.from` and `.to`), is chosen per area as before, by the
share anchored at both ends, unless no line over air (its middle not on the layer below) lands on printed material at
both ends at any angle while some lands on it at one end: then it is the angle with the largest share of its lines over
air anchored at one end at least, ties to the shortest longest line, the existing tie rule. Each area hanging off one
side is laid outward from its own supported edge. It needs no number beyond the score's own (the probe of one line
width, the 5 degree step). A span, where some line over air is anchored at both ends, keeps the score by both ends; an
area with nothing under it at any end keeps it too. The pre-print check finds a bridge line over air (a straight run of
a bridge's moves longer than three line widths, the planner linking lines no further apart than three spacings, so a
link along an edge is never one; over air where the model's cut below the layer is neither under its middle nor within
half its width and the tolerance of it, so a line beside a step, its bead on the step's edge, is not) when nothing holds
either end: an end is held where the model's cut below the layer (within the same reach), or a support line in a layer
below with no surface of the model between them (a ray down the mesh), lies under it or one line width past it. It is
reported, not refused, as decision 48's rule is: a warning line of the slice names the layer, the count and length of
such lines, and where the longest lies, with a picture of the layer. A move read in warped space (a curved layer, a
lifted bridge) is not judged: the cut below a warped layer is not what lies under it.

**Rejected.** The outward rule for every bridge over air, the wave overhangs' leftovers included: the default
technique's output stays as it is; what the waves leave to bridges (a sliver hanging off one side narrower than the
neck the fronts cross, a piece with no anchor) is a question of its own. A fixed fallback angle, or the direction
square to the supported edge: an arm leaving an edge at a slant laid square to the edge starts most lines on its side,
over air; the one-end share finds the arm's own direction. Refusing the file: the planner still lays such lines where
the plan accepts them or leaves them by another rule, each a cause of its own (Consequences), and the test suite slices
several; refused, those plans could not be printed at all, and a user who locked support off over an area has already
been warned it prints over air. Counting any support under an end however far below: support under a lower part of the
model, with that part between, holds that part, not the line.

**Consequences.** An as-laid underside hanging off one side gets lines landing on what is printed at one end; nothing
is held up at the other, and the area still needs support or wave overhangs to print well (the search requires support
under such a cantilever where support is decided per overhang). The check reads support's lines for this (only the
model's were read before). Open, each a planner cause the rule reports:
- with the pose locked and one way of laying the object at one layer height, the decision measures nothing, and
  support decided per overhang is not surveyed: no support is chosen and no cantilever is said;
- a plan file's support spots lock support to those spots alone: a re-plan keeping one of two chosen spots leaves the
  other overhang, a cantilever, without support, and says nothing;
- a region whose support is kept off (`support = "never"`), and support locked off: what only support lays prints over
  air (the plan warns of the second);
- a sliver hanging off one side that the wave overhangs leave to bridges, laid along itself;
- a branching area (arms over air joined to each other away from the supported edge) takes one direction for the whole
  area: an arm joined only to other arms over air has no line landing on printed material at either end at any angle,
  and could be anchored only on lines laid before it in the same layer, which the bridge's lines are not ordered for.
Tests: BridgesTest (three arms at 45, 90, and 135 degrees off a bar laid along themselves, seen at 0 degrees each
before; outward off, 0 degrees as before; spans bridged across, either way), HeldBridgeCheckTest (bridge lines across an
arm found and not among the problems; controls: along it from the bar, a line beside the bar with its bead on the
bar's edge, over support one layer of gap down, and links shorter than three widths, none found; the line beside the
bar 1 mm further out, a line ending beside a ledge of the model in its own layer that is itself over air, and support
under a plate of the model below the arm, found), CantileverBridgeTest (the arms sliced as laid with support off: each
arm's lines along it, nothing reported, seen at 0 degrees each before; a span bridged across; a slab held only from
above reported, with support unset and locked off).

## 69. An overhang region standing on the bed is read for hanging off one side at each level face of it higher up (2026-11-20)

**Context.** Support decided per overhang requires support under a cantilever: an overhang region whose layer reaches
over air past a line width, anchored on one side only (the Cantilever rule), whatever the weights. The faces needing
support are clustered where they share a vertex, and the rule read each region at its lowest layer only, skipping a
region whose lowest layer lies on the bed. An underside too flat to hold itself up that rises from the bed (a ramp) and
leads into an underside level over air higher up (an arm reaching far out from where the ramp ends) is one region: read
on the bed, it was never read at all, and the level underside, however far it reached over air, was left to the weights,
which could leave it unheld. Separately, the plan's count of the overhang area support holds took each face's middle
from a sum of three single-precision coordinates, which rounds; a level face on the region's top (the box's own top,
from the same vertices) read up to a few tenths of a micron above the box, outside the spot under it, and was counted
unheld, in the plan's figure and in the droop the weights read.

**Decision.** A region whose lowest layer lies within the first layer (it stands on the bed, and that layer is not read)
is read at the lowest layer of each level face of it higher up (its faces whose three corners lie within a micron of one
height, the survey's own tolerance for one position), each reading the region's box from that height; it is a
cantilever where any reading is, and its words name the layer read. A region above the first layer is read at its
lowest layer, as before. No new number. A face's middle for the spot and region tests is summed in double precision.

**Rejected.** Reading every region at each of its level faces: a level face in the middle of a curved underside (a hull,
a cabin roof's edge) reads the stair-step of the slope around it, a little past a line width on layers near the thickest
(0.5 to 0.7 mm at 0.3 mm layers), as a cantilever; the search then rejected plans it had chosen for such models, and the
plan it chose instead laid a hole inside the walls the pre-print check refuses. Whether a stair-step a little past a
line width is a cantilever is the rule's own question, not this one's. Reading every layer of every region: a sloped
overhang's every layer steps out by the same amount, and a reading per layer per region multiplies the cuts of a pose
search. Splitting a region at a change of slope: the cluster is support's unit of choice (a spot holds a region whole),
and a region cut where the ramp meets the level underside would let the weights hold one and not the other. Widening
the box test's tolerance: the faces' middles would still be rounded, only hidden.

**Consequences.** A region rising from the bed into an underside level over air is supported whatever the weights, as
any overhang hanging off one side past a line width is; a sloped region with no level face is read as before (on the
bed, not at all), and a region above the first layer as before. Decisions can change for models with such regions, and
the held figures of plans whose level faces sit at heights a float holds only roughly rise to what support holds.
Open: an underside level over air higher up in a region that starts above the first layer, where its lowest layer reads
no cantilever, is still left to the weights. Tests: CantileverBridgeTest (a block, a ramp
whose underside rises from the bed to 5.06 mm, and an arm level at 5.06 mm reaching 30 mm out: with weights that would
rather pay no support, all 366.6 mm² needing support is held; on main none was; with the faces' middles summed as
floats, 126.6 mm²; control: the ramp alone, no level underside, is weighed and left unheld by those weights).

## 70. The window's program runs the command line's commands through the command line beside it and refuses what it does not know; an AppImage's updater runs from a copy outside it (2026-11-20)

**Context.** The AppImage starts one program, the window's (`plymeld-ui`); the command line (`plymeld`) is inside it,
beside the window's program, and nothing outside the AppImage reaches it. The window's program read only the options it
knew and opened the window for anything else, so a command given to the AppImage (`--version`, `slice`, `update`)
opened the window instead, and an argument it did not know was ignored. `plymeld update` has a path for an AppImage
install that only the command line inside the AppImage can take.

**Decision.** The window's program decides what its arguments ask before anything of the window starts. Velopack's
hooks come first. A first argument that is a command of the command tree (the names read off the tree, never a list
kept beside it) or `--version` runs the command line installed beside the window's program (`plymeld` in its folder;
`cli\plymeld.exe` under it on Windows) with the same arguments, standard input, output, and error, and the exit status
is the command line's. Where no command line is beside it (a development run), that is said in one line, exit status
1. `--help` or `-h` prints the command line's help, then the window's own options. The window's own options are a list
the program declares, each read through it, and a test holds the reads to the list; anything else (an option or a
word it does not know, an option missing its value) is refused in one line naming it, exit status 1, as the command
line refuses a usage error. A command run this way runs as the install's own command line, so `update` finds the
install as that command line does (the AppImage's layout and `$APPIMAGE`, which the child inherits); the window's
program that started it, a process running from the install that waits for it, is not counted among the other
processes holding the install, and the updater waits for the outermost such program to exit. An AppImage's updater
(`UpdateNix`) runs from a copy outside the AppImage, `updater/usr/bin/` beside the install's downloaded packages with
the install's `sq.version` (the updater finds its install from a `usr/bin/` folder holding it, and the AppImage from
`$APPIMAGE`): the AppImage's files are readable only while the AppImage runs, the updater outlives it, and run from
inside it the updater stopped as the AppImage exited, before replacing anything. Each install updates itself alone:
the AppImage's `update` leaves the command line's download as it was, and the download's leaves the AppImage.
`tools/native-smoke.sh check --window` holds the window's program to `--version`, a command, `--help`, `update`, and
refusing an unknown argument, on both platforms in the release.

**Rejected.** Running the command in the window's own process: the window's native image carries the window's
reachability metadata, not the command line's (decision 67), and `update` from inside the window's process is the
window's bar's, which stops the window. A second launcher in the AppImage, or packing the command line as Velopack's
main program: Velopack starts one main program, and that one must open the window from a desktop entry. Copying the
updater to a new temporary folder at each update: the copies would pile up; one place, replaced each time, does not.
Ignoring unknown arguments, as before: a mistyped command opened the window and said nothing. Exit status 2 for a
usage error: the command line uses 1.

**Consequences.** `./plymeld.AppImage <command>` is `plymeld <command>`. A positional argument is refused like any
unknown word: the window takes models by `-m`, and its desktop entry passes none. On Windows the window's program is
a windowed program, so a command's output shows where its output is redirected (a script, a pipe), not in an
interactive console; the installed `plymeld` is the command line there.

## 71. Pressure advance is set with the machine model's own command; a machine may keep it as its own (2026-11-20)

**Context.** The writer wrote Klipper's `SET_PRESSURE_ADVANCE ADVANCE=` itself, whatever the machine. Other firmware
sets it with other commands and units (Marlin `M900 K`, RepRapFirmware `M572 D S`), and a calibration plate that
measures pressure advance must set it mid-print, before each element. Some machines measure or keep their own value,
and a value in the g-code would replace it.

**Decision.** The printer model describes how pressure advance is set mid-print, as it describes the commands it
accepts: `hardware.model.<key>.pressure_advance.command`, a template with `{pressure_advance}` standing for the value,
and `.unit`, the unit the command takes it in (`s` or `ms`; unset = `s`), with their evidence beside them. The bundled
models carry Klipper's command in seconds (Klipper's G-Codes and Config Reference). The writer renders that template
when a tool starts with a value set, and wherever a program sets one (`SetPressureAdvance`, a calibration plate's
step); a value set for a model with no command is refused, naming the setting. `hardware.machine.<key>.firmware.own_pressure_advance = true`
says the machine sets its own: plymeld then writes none, whatever the machine, a tool, or a filament sets. The g-code
check reads every such command back through the same template and holds it to the config's value for the tool in hand
(none where the config sets none or the machine sets its own), except on a plate that sets its own values on purpose.

**Rejected.** A command per firmware flavor in the writer: the command is the machine's fact, with evidence, and a
user's own model may need another. The command on the machine rather than its model: every other g-code fact about
what a printer accepts is the model's, and a machine's config may override a bundled model's values as it may any.
Dropping a value silently where no command is known: the print would differ from what the config says. A machine's own
value as a third source under the filament's, tool's, and machine's: plymeld cannot read it, so it can only be left
alone.

**Consequences.** A machine of a bundled model writes the same files as before. A model of the user's own needs
`pressure_advance.command` before a pressure advance can be set for it. Whether a machine's own value survives a
command in the file is not known per machine: `own_pressure_advance` is the user's statement, and the pressure-advance
plate offers such a machine only a check of its value (decision 72).

## 72. Pressure advance is read as the sign of a defect on straight walls whose speed steps, each value twice (2026-11-20)

**Context.** The usual tests read pressure advance as the best-looking of near-identical neighbors, on corners that
also carry ringing and corner-speed limits, with the value changing by height (cooling and first-layer squish change
with it), one sample per value, and one speed, acceleration, and flow.

**Decision.** The value is read as the sign of a defect that reverses through it: a straight single-line wall printed
the same way on every layer, slow, fast, slow, at its own value set just before it. Set short of the true value, the
line just after the speed-up is thin (a groove stacked up the wall) and just after the slow-down fat (a ridge); set
long, the other way round; the answer is where the grooves turn into ridges, bracketed from both sides. Values stand
side by side on one base, never by height, the first layer and the base unread. Every value is printed twice, the back
row in the opposite order, and rows that disagree by more than a step refuse the result; a corner beside each wall
confirms it and is never read alone; a wall whose two marks read alike, readings out of order, and no flip are
refused. A first plate runs from 0 to the tool's ceiling, a setting (`hardware.tool.<key>.calibrate.pressure_advance.max`)
defaulting to the top of the typical range in Klipper's guide; a fine plate runs inside the bracket; a confirm plate
prints the value and its neighbors at three flow steps and reports whether it holds, depends on the flow (the medium
step's written, the range noted; a value that follows the flow is a later decision on this evidence), or gives none.
The speeds are the config's (a visible wall's, the machine's print speed, each held to the maximum flow), the
acceleration the machine's. A pressure model in plymeld's test tooling (a first-order lag of the true value, the set
value run ahead as Klipper runs it) proves the flip falls exactly at the true value. The slow-down mark reads opposite
to the speed-up mark only where the pressure settles within the wall's lead-in and middle, so these grow together (from
10 and 30 mm) until, by the same model at the plate's speeds, it settles at the plate's highest value (the tool's
ceiling on a first plate); walls too long for the bed are refused, naming the ceiling. The shortest stretches, the
tail, the wall's height, the pitch, the values per row, and the stretch a reading looks at are plymeld's choices.

**Rejected.** A tower with the value rising by height: cooling, layer time, and wall stiffness change with it. A row of
corners or chevrons read for the best one: a judgment between near-identical neighbors, and corners carry other
defects. One sample per value: a blob or a draft reads as a result. Calipers: the reading needs none. Averaging rows
that disagree: a trend across the bed would pass as the value.

**Rejected (the stretches).** Fixed stretches with the plate stating the true value they settle up to: a tool with a
long filament path would read alike at both marks on every wall and be refused.

**Consequences.** A higher ceiling makes longer walls: at 100 and 170 mm/s and 10,000 mm/s2 (the repository's sample
config) a lead-in and middle of 10 and 30 mm up to 0.1 s, 22 and 64 mm at 0.5 s, 43 and 127 mm at 1 s; two rows of the
last do not fit a 250 mm bed, so with the default ceiling of 1 s such a machine is refused until the tool's ceiling,
`--values`, or `--bracket` asks for less. The pilot print decides whether the stacked defect reads by eye and nail at
this size.
