# Features

What plymeld does, feature by feature, and, under [What may come next](#what-may-come-next), what it may do later.

Legend: ✅ built · partial: some of it built, the rest said · 🎯 not built

| feature | plymeld |
|---|---|
| Walls, surfaces, and infill | ✅ classic and variable-width walls (variable width by default: its thresholds from the tool's narrowest line, or three quarters of the bore as a stand-in; a center line along the edge prints as outer wall); sparse infill rectilinear, grid, cubic, or gyroid (the gyroid's curves are not linked to each other); top and bottom surfaces in line patterns, concentric outside-in (loops following the outline), concentric inside-out (round rings around the middle, arcs where they do not fit), or Archimedean chords; internal solid in plain lines |
| Tree support | ✅ one style, without look-ahead steering. Support is a technique: with `plan.support.enabled` unset it is decided per overhang region, its scars and plastic weighed against the droop without it. Locked spots (`plan.support.locked.<key>`, a box in the model's coordinates) are requirements, refused where nothing can be supported; support goes only where it is chosen; `plan -o` locks the chosen spots (`plan.object.<key>.support.spot.<n>`). A support column's print order beside the model's layers is searched (`first` where the head clears it and speed has weight, else `interleaved`) |
| Support enforcers and blockers | partial: a locked spot is a box enforcer (every downward face in it supported), and the chosen spots act as blockers elsewhere; no painting, no blocker of the user's own |
| Bridges and overhangs | ✅ bridge direction per region, internal bridges; overhang speed graded from the wall's speed down to the bridge speed as more of the line hangs over air |
| Seams | partial: placements `corner_seam` (at a corner when the loop has one, inside corners first, in line with the seam below), `back_seam` (the back-most point), and `least_visible_seam` (the point the seam measure counts least); never on an edge that hangs over the layer below when the loop has another place; inner loops seamed behind their outer wall. Scarf seams on outer walls (`plan.technique.scarf_seam.where`: loops with no corner, the default, or all), the two passes laying one layer's plastic, its values placeholders. With `plan.seam` unset the seams are decided per object: the placement and the scarf seams weighed by `seam_scar` (quality) against the scarf's extra pass (speed); `plan.seam` locks it, and `plan -o` writes the decision. 🎯 staggered seams |
| Where quality is seen: the visibility map and `plan.view_from` | ✅ every quality measure counts a face by how much it is seen: faces on the bed by `method.quality.visibility.bed` (0), undersides by `.under` (1) scaled by their lean, faces covered by another part of the object not at all, and faces turned away from `plan.view_from` (`all_around`, the default; `front`, `back`, `left`, `right`, `above`: the model's own sides, turned with its pose) by `.away` (0.25). `.bed` and `.away` are placeholders. Seams are also counted by what hides them nearby: viewing directions sampled around and above each seam (16 around at 3 heights up to 60 degrees, each followed 20 mm through the layers' outlines; `method.quality.visibility.sample.*`, placeholders), used by the least-visible placement and by `seam_scar`; with a view from one side, each direction is weighed by the quadrant it looks from (`method.quality.visibility.quadrant.*`: front 8, left and right 2, back 1) in place of `.away`. Only the object's own geometry hides its seams |
| Seam, string, and restart measures (`seam_scar`, `string_risk`, `restart_starved`) | ✅ the outer walls' seams as seen (a point seam one line width squared, a scarf `method.quality.seam.scarf_share` of that, a seam in a corner `.corner_share` of it, a jog from the seam below its length times the width; a seam on a shelf the layer below leaves exposed counts a band as wide as the shelf, a scarf's ramp on it its length times the shelf beyond a line, and no scarf goes where it does not pay), measured on the planned layers; unretracted travel where a string would show, and visible walls or surfaces printed starved after a restart (`hardware.filament.retract.restart_lag`, a placeholder), measured on the program as written; reported by `slice`, scored in the finalists and by `job.priorities.measure.<measure>` |
| Retraction inside an island | ✅ `plan.mechanism.motion.no_retract_in_island` unset: decided per object when sliced, both ways measured on the object's own program (`string_risk`, `restart_starved`, the retractions' time) and weighed; set: a lock. The decision is not written to the plan file |
| Support specks dropped | ✅ an overhang is supported only where the downward faces stacked over a column's height around it are a column wide (`plan.support.column.width`, unset = a tree tip, two support lines): a sloped face keeps its support, while a speck (an engraved letter's ceiling a fraction of a line wide) and a face narrower than a column get none; a locked spot is never filtered |
| Layer heights that follow the surfaces | ✅ part of flat layers: each layer's height from each face's stair-step (`plan.technique.flat.step.max`), layer tops on flat faces and pauses, shells counted by thickness; chosen with the pose by the weights, by height band where it pays and one height elsewhere, or locked (`plan.technique.flat.heights = "follow"`, or per band under `plan.object.<key>.band`, as `plymeld plan -o` writes them). 🎯 smoothing between layers, heights per region, a manual height profile |
| Ironing | ✅ top surfaces (🎯 `topmost`, `solid`): a surface technique the search weighs where the job locks nothing for flat tops, its pass's time against the rough flat-top area it removes; `plan.faces.flat_top.technique` (`ironing` or `as_laid`) locks it |
| Fuzzy skin | partial: classic noise, displacement mode, on the outer walls, all walls, or everything (`external`, `allwalls`, `all`); 🎯 other modes and ripple noise |
| Exact print height | ✅ every print ends on the model's top: a remainder the tool can lay is a layer of its own, and a thinner one is shared with the layer below; no setting |
| Arc fitting (G2/G3) | ✅ outer walls above the first layer fitted to arcs in runs of one speed and fan; not variable-width center lines or curved paths |
| Exclude object | ✅ |
| One object at a time | ✅ an object order chosen by the weights or locked; every object but the last under the nozzle-to-rod height, shortest first or as given (`plan.objects.order = "as_given"`); one object can be locked to print on its own; objects spaced by the head's outline when the tools give it (those printed first on the side the head reaches least, each object's box grown by the reach, the brim missing every earlier one), else by the machine's clearance radius; a locked placement checked the same way; the pre-print check reads each object's run against that object |
| Pressure advance per tool or filament | ✅ the machine's, unless the tool's, unless the filament's; none set = the firmware's own, and none written |
| Calibration prints | partial: `plymeld calibrate`, plates printed exactly as specified, a subcommand per kind, each but the melt and flow plates with an instructions page: the melt plate (a column per temperature, coolest first, a row per flow from half the filament's expected maximum flow to twice it, every row the same line at its own speed, each row's flow and each column's temperature printed on the plate, the base at the filament's own temperatures), the flow plate (a column per temperature, a square patch per flow ratio, each patch solid at its own ratio with its lines the bead spacing apart, only its top laid at its column's temperature after a wait, each patch row's ratio and each column's temperature printed on the plate), the first-layer plate (one layer of tiles laid out as a table, one piece: a column per nozzle temperature, by default from the filament's print temperature to its maximum at 10 C steps, coolest first, a wait and a prime line before each; a row per first-layer speed and nozzle height, by default around the configured speed and from the configured height up; every tile a 40 mm strip, its lines along it; each column's temperature, each row's height, and each speed raised on a two-layer base along the plate's front and left, its front-left corner cut off; every tile touching its neighbors and the base, under a three-layer frame over their walls and as tall a border round the base, the edge to peel it by; read under a raking light and, once peeled, held up to a light), the motion plate (a cross-shaped tower, twelve faces at six speeds, one face along X and one along Y for each, a band per acceleration, the top band's fastest faces at the machine's limits as the control, its arms numbered by notches, each face's speed printed in front of it, the slab's first layer at the filament's first-layer temperature and the tower at its print temperature after a wait), the filament plate (temperature and fan bands, overhang ledges, bridges, a stringing ring, line-width tubes, and a fit test; the tests chosen with `--tests`, each on a notched base, packed to fit the bed or refused with a split, each test's predicted time and filament shown), the ironing plate (two slabs per speed with a brim, each zone ironed with its own flow, line spacing, speed, angle, and inset on a Latin-square plan, a control zone per speed, each zone's values notched into its edges with a raised legend, the first layer at the filament's first-layer temperature and the top and its ironing at its print temperature after a wait), and the seam plate (eight hollow pieces on one slab, each with its outer wall's seam laid one way: a point seam or a scarf, on a round wall, mid-face, in a corner, and on faces leaning 30 and 45 degrees; graded for how far away each seam shows; the slab's first layer at the filament's first-layer temperature and the pieces at its print temperature after a wait). Read back in: the flow plate as the filament's flow ratio (`calibrate flow values`: the best patch per column as arguments, one value or a formula through every reading, written as `melt values` writes), the melt plate as the filament's maximum flow on the machine (`calibrate melt values`: the readings as arguments, written into the file that defines the filament as a formula through every reading, with the readings in its comment; undone with `plymeld journal --undo`), the first-layer plate as the first-layer window (`calibrate first-layer values`: per column the lowest good height and the fastest good speed as arguments), the ironing plate as the filament's `technique.ironing.*` values, the seam plate as `method.quality.seam.scarf_share`, `.corner_share`, and `method.quality.visibility.under`, and the motion plate as the machine's visible speed and acceleration (`calibrate <kind> readings` and `values`). 🎯 the filament plate's results read back |
| A filament's melt rate (maximum volumetric flow) per machine and tool | ✅ `hardware.filament.<key>.machine.<machine>.melt.flow.max` and `….tool.<tool>.melt.flow.max` over the filament's own; the tool's over the machine's; every flow cap, calibration plate, and `plymeld config` reads the one for the job's machine and tool. Every move's flow is capped at it less a buffer (`plan.mechanism.motion.melt.buffer`, bundled 10 %; 0 % for none), the melt plate's rows aside, which probe the limit itself. 🎯 the same for the filament's first-layer speed |
| Several printers in one config | ✅ each machine carries its own loadout (plate, tools, filaments); `--machine` or `job.machine` names the one that prints (refused when there are several and none is named); two config files of one numbered group setting the same value are refused, naming both |
| First-layer window (the first-layer plate's results used by the slice) | ✅ the plate's readings recorded on the filament per build plate (`first_layer.window.<plate>`: per temperature the lowest good nozzle height, every lower one bad, and the fastest good speed; `plymeld calibrate first-layer values` writes it, checked against the plate, undone with `plymeld journal --undo`); the first layer's height and speed taken from it at the first layer's temperature (between two, good only where good at both), below a plan lock and over the filament's and machine's speeds; used only with the nozzle bore it was measured with; shown by `plymeld plan`, `plan -o`, and `plymeld config`. |
| Machine file header for the printer's screen | ✅ for machines whose dialect has one (`flashforge`): print time and filament per tool. 🎯 grams and cost |
| Preview picture on the printer's screen | ✅ `plymeld slice` writes one PNG per size in `hardware.machine.<key>.gcode.pictures` (for example `"140x110"`; unset = none) after the file's header lines: the plate's parts in their printed pose, drawn by plymeld's renderer (a fixed view from the front-left above, flat shading in each part's filament color, a transparent background). 🎯 calibration plates, formats other than PNG |
| Setup in the terminal | ✅ `plymeld init` asks for your printers (each a model plymeld ships), their tools and build plates, your filaments, and what is loaded where, writes them to plymeld's config folder, and ends by slicing a test cube with them; a command run in a terminal with no `-cf` and an empty config folder starts it |
| Every value a setting with its source | ✅ plymeld's bundled files, then your config files (`-cf`, else every `.toml` in the config folder), then values on the command line (`-cv`), later winning; a setting's name or chosen value may be spelled the British way (`colour` is read as `color`, `centre` as `center`); `plymeld config` shows each value, where it was set, what it overrode, and what each formula works out to; `plymeld schema` writes the JSON Schema for editors; `plymeld edit` writes values into a config file keeping its comments and order, and `plymeld journal` lists, undoes, and redoes the edits made with `--journal` (and by `calibrate melt values` and `flow values`) |
| The plan shown, explained, and editable | ✅ `plymeld plan` prints each decision and why; `plan -o` writes them as a config file to edit (`plymeld edit --keep` keeps a line through re-planning) and slice with; `--explain` and `--explain-json` show how each object's techniques were chosen; `plymeld slice --from` slices again from what a g-code was made from |
| The desktop window | partial: the same commands and settings as the command line (a test fails when a command is in neither the window nor its list of what is not in it yet): a job's steps from opening it to sending it (the plan's decisions and why, slice and check, the layers on the stage, the printer's state, loadout, and camera, send); setup as a form; a finder over every command and every setting, each command's form in the drawer, a setting's chain of values; edits to the plan recorded and undone. 🎯 calibration grading on the stage, the printer's own buttons, macOS builds |
| Command-line docs | ✅ a guide to how the commands fit together (`docs/cli/README.md`) and a command reference with every command and option (`docs/cli/reference/`), written from the commands themselves by `plymeld commands --markdown` and checked against them by a test. 🎯 documentation for the window |
| Pictures of a calibration plate from its g-code | partial: `plymeld render-plate` draws a plate as written, to review before printing: a top view by flow, the front by temperature, every temperature command, an isometric diagram labeled from the plate's page (`--iso`), a melt step in section (`--section`), each base's layers (`--layers`); the first-layer plate its own picture by block. 🎯 the motion plate's front view and isometric diagram |
| Output read back and checked before printing | ✅ `plymeld slice` and `plymeld calibrate` read their g-code back against the config (machine limits, retraction, travels over visible lines, start and end) and against the model (lines outside it, outlines left without walls, the area over air of a layer with wave beads left unlaid), with a picture of each layer that fails |
| Prime line placed by the slicer, next to the print | ✅ laid before the first layer along the middle of the first object's front (else its back, left, or right: on the bed, 1 mm clear of other objects, and outside the object's outer hull, so never in between two of its feet under what spans them), `prime.length` long and `prime.gap` out (`plan.mechanism.adhesion.prime`); `prime.line` is `near`, or `tab` (on a peel tab: against a brim's outer edge, or joined to the print by partial brim lines; the default without a prime tower); none with `prime.enabled = false` (the default with a prime tower). Length and gap are placeholders; a start template keeps any priming of its own |
| Pauses | ✅ at a layer, at a height, before or after an object or part, and above a planned insert's top (`plan.insert.<key>`: the layer over the socket laid onto the insert, no support in it) |
| Several filaments on one nozzle; purging into infill or objects; purge tower | partial: several spools on one tool swapped by hand at a pause you ask for (a loadout slot beside the tool's own filament; the g-code notes the swap and the header counts filament by number; a missing pause is refused); on a tool changer, a purge tower (banded or compact) and purging into the object's own infill, or its infill and hidden solid, first (`plan.mechanism.purge.into`), the tower taking what is left. 🎯 an automatic filament changer |
| Several tools (tool changer) | ✅ each part prints with its filament's tool; the start template selects the tool of the first line printed; the pre-print check refuses a line printed by another tool than its part's, and a filament-use header that does not match the lines |
| Per-object and per-part settings from 3MF | ✅; each part on the filament its 3MF names, mapped to the loaded slots by `model.slots` (a slot per model filament number, `"*"` for every one; an object's own `model.object.<key>.slots` first; a part's own `.part.<part>.slot` over both), `--slot N` on `slice` and `plan` printing every part from slot N (a multi-color model in one filament), the window's step 1 offering it when a part's filament is not loaded |
| Incremental re-slice on a settings change | ✅ cached stages; `--watch` (re-slice when a file changes) on `plymeld investigate slice`. 🎯 `--watch` on `plymeld slice` |
| Filament and spool sync with the printer | partial: read-only. `plymeld printer loadout` compares what the printer reports in each tool or slot (material, color, loaded or empty) with the machine's loadout, names the matching config filament (same kind, exactly the same color), and writes the matches as a config file (`-o`, an empty tool or slot as `filament = ""`, which unloads it); it never changes the config or the printer, and `slice` never uses it. Over Flashforge's LAN API, and on a Moonraker machine a QIDI Box's slots over SSH (refused without an SSH user). 🎯 other printers, a check before sending, spools with their remaining amount |
| Coherent surface toolpaths (no fragmented fill islands or concentric micro-regions) | partial: a shared fill grid, a near-monotonic sweep order, loops for narrow regions |
| Printer connectivity | partial: one `Printer` interface with a capability set and a typed state (every field the printer's own or "not reported"); adapters for Flashforge's LAN HTTP API and Moonraker over one HTTP transport; the connection as settings of the machine (`connection.*`), credentials from the environment or a local secrets file, set with `plymeld printer credential` (a hidden prompt or standard input); `plymeld printer status`, `watch`, `camera`, `files`, `send` (with `--print`, the tool-to-slot mapping, a loadout warning, and levelling as the print starts where the API takes it: `--level`, or the machine's `connection.start.level`), `pause`, `resume`, `cancel`, and `loadout`, the printer named by its machine key; SSH (Apache MINA SSHD) for what an API does not report (a QIDI Box's slots). 🎯 OctoPrint, PrusaLink, and Duet; Elegoo SDCP; Bambu and Anycubic over MQTT; serial and copy-to-drive sending; the OS keychain |
| Overhangs grown outward from an anchor | partial: wave overhangs, a surface technique of undersides over air (`plan.faces.under.technique = "wave_overhang"`, the default), implemented from the published method (Andersons, Sanchez, Vaneker, 2026): each flat layer's area over air grown from the material beside it by fronts one bead spacing apart (offsets, so they round corners, holes, and necks), each front's new edge laid as a bead half a bead in (where fronts close in from two sides, what they leave gets a bead along its middle), at the technique's speed and a flow per mm from the bead's section (`plan.technique.wave_overhang`, placeholders); the walls over air give way to the beads, and what no front reaches is bridged. A lifted bridge's first layer can be laid as waves too (`plan.technique.bridge.first_layer = "wave_overhang"`), with its own options under `plan.technique.bridge.wave.*`: side order, edge pieces, line direction, crossings beside the span, retract, wipe, and wait per line, two waved layers or a second layer of Xs, edge strips or triangles laid first, chevron fronts, the strip over the supports first, even spacing along the surface with a middle line last, the head away between lines with waits by zone, a second pass over zones of the span, and Xs and a layer along the span laid over it. 🎯 the waves' own fan and temperature, retraction at line ends, the order within a front, wave overhangs weighed by the search; arc overhangs |
| Z anti-aliasing of sloped tops (lines lowered onto the top within a flat layer) | partial: locked only (`plan.faces.sloped_top.technique = "z_antialiasing"`; as laid by default): in flat layers above the first, a line where the model's top lies within its layer is lowered onto it with a bead of its own thickness, never thinner than the tool's thinnest layer, and the layer reaches out to where the top is that thin above its bottom; walls, top, bottom, solid, and sparse fill, and ironing, not bridges or support; the pre-print check accepts a line below its layer only on the model's own top. On a tile with slopes up to 17 degrees at 0.2 mm layers, the stair-step's median falls from 0.200 to 0.079 mm and the top's RMS error from 0.054 to 0.033 mm; with 0.1 mm layers and a 0.08 mm thinnest layer, next to nothing can be lowered. 🎯 weighed by the search; objects planned part by part (several filaments or part settings) |
| Non-planar layers and free strands | partial: curved top layers, layers that follow an arch's underside (the first over the hollow bridged in the direction anchored on the bed), and lifted bridges (a part that does not reach the bed printed after what holds it up, along its underside: ramps, dips, and arches between anchors at different heights, several in one object, the flat layers above a strip's end laid onto it as onto a printed surface). Curved tops by region are weighed by the plan for a single object once the tool's nozzle angle and height are set (their stair-step against layer lines, the border with the flat top, and time; `job.faces.sloped_top.look`), or locked; whole curved top bands and the other curved techniques are locked only (a band's start chosen by plymeld with `depth = "auto"`); a curved top is laid in one sweep or uphill from each side to its highest line (`plan.technique.curved.top_order`); all are checked by the pre-print check, and refused until the tool's nozzle angle and height are set. 🎯 curved tops chosen on plates of several objects; free strands |
| Decisions weighed by the job's priorities: six goals (quality, accuracy, speed, material, strength, reliability) | ✅ pose, technique, and object order scored by weights on the six goals, each measure counting toward one goal; support counts under quality (its scars) and material (its plastic). The goal rows below say what each does not measure yet |
| Scored against a fixed baseline; techniques offer a range of outcomes | partial: every decision measures differences against flat layers at `method.search.baseline.layer.height_share` (50 %) of the object's nozzle; scores on a log scale, ln(value / baseline's), linear for measures that can be 0, each goal's weight split over its measured measures; the pose decision in the as-given pose, the object order with every object flat at its baseline. Techniques offer ranges through one interface (`TechniqueModel`: controls with ranges, outcomes measured by the pose decision): flat layers' height ranges over the tool's thinnest to thickest layer, sampled coarse to fine and picked by the weights, a lock collapsing it; their largest stair-step (`plan.technique.flat.step.max`) is a control locked at the setting; their survey proposes height bands. Neighboring portions are chosen together: each band sampled alone against one height, a dynamic program over the bands, the best sets re-scored exactly, the finalists predicted in full. Each object keeps its own techniques and layer heights, and objects printed together have their layers merged by height. 🎯 each region's own layers printed together (a visible shell around a hidden core); the curved techniques on the interface; the largest stair-step as a range; estimates from a technique before layout |
| Coordinating techniques: portions, proposals, handoffs; `--explain` | partial: portions are volumes (height bands, regions within a height range, and the air under an overhang for support); proposals come from flat layers (the whole object, its survey's bands), the curved techniques (where locked), and support (the overhang regions its survey finds), each with its reason and the cuts it allows, and each handoff's contract is data. The poses searched are shortlisted by a first look (every pose under one plan, flat layers at the baseline's height: the as-given pose and those within `method.search.pose.shortlist_margin` of the best), each pose's overhangs are surveyed only when it is detailed, and a pose whose best possible score cannot reach the best three candidates is ruled out before its estimates. The joint search samples each proposal on its own volume against flat layers, runs a dynamic program along the print order with the handoff as its state, re-scores the best three sets per height exactly, slices the finalists through the full pipeline (their predicted time and filament decide), and refines the layer height round the leader; how each search stopped (short-circuited, time limit, everything tried, budget, or locked) is recorded per decision, shown by `--explain`, and kept in the search log (`method.search.log.*`). A surface technique the job locks nothing for is weighed against as laid where it has an outcome (ironing). Techniques are typed by what they decide: volume; surface (one per exposed surface by what it faces: ironing flat tops, Z anti-aliasing sloped tops, fuzzy skin walls); curve (seams; free-air strands declared, not built). `--explain` and `--explain-json` on `plan` and `slice`. 🎯 a visible shell around a hidden core; a satisfaction threshold for the search; pictures in `--explain` |
| Quality: artifacts weighted by severity and visibility | partial: measured: stair-steps, layer lines on the sides, droop with support off, support scars (the area support touches), seams as seen (`seam_scar`), strings from unretracted travel and starved restarts (`string_risk`, `restart_starved`), each face counted by how much it is seen (the visibility map). 🎯 blobs, ringing, over- and under-filled surfaces, color bleed, curling, weighting by severity |
| Accuracy: how closely the print keeps the model's dimensions | partial: flat horizontal faces moved to the nearest layer top (`height_error`), weighed in the pose and technique decisions. 🎯 hole sizes and fits, flatness, elephant's foot, warping out of shape |
| Speed: total time to a finished print on the bed | partial: the moves and dwells of the generated g-code with the machine's own tool-change time (object order), and a quick per-layer estimate (pose and technique). 🎯 heating the bed and nozzles (waits count 0), homing, levelling, pauses, waits for temperature changes between objects; travel in the quick estimate |
| Material: all filament by mass | partial: by mass with each filament's density (else by volume, said so): support's plastic (pose and technique), and everything the g-code extrudes (object order). 🎯 purges a machine makes outside the g-code; the object's own plastic as it changes between poses and techniques; a filament's density per object |
| Strength: the loads the print will see in use | partial: the thinnest bond between layers, and how the layers are laid (`bond.strength`: the thinnest bond times a bond factor for the part fan, nozzle temperature, and minimum layer time, its coefficients placeholders), weighed per object against the cooling given up (bond-first cooling, `plan.bond_cooling`). 🎯 loads you mark, bead directions against them, the bond factor fitted from a test print |
| Reliability: the chance the print finishes usable | partial: bed contact, and how far the center of mass lies beyond it. 🎯 warping, a part knocked loose, nozzle collisions with printed material, spaghetti from unsupported areas, failed tool changes, running out of filament, one object failing on a plate |

## What may come next

Each item carries a label, set from the project's own plans. It says how likely the item is, not when
it will happen:

| label | meaning |
|---|---|
| **Next** | decided, and among the next pieces of work |
| **Planned** | intended, with a design or a place in the plan, but not scheduled |
| **Exploring** | worth trying; built only if an experiment or a test print says it pays |
| **Possible** | an idea worth keeping; no commitment |

Plans change. An item can move between labels or drop off as experiments come back and priorities shift.

### Deciding how to print

- **Next — Point at part of a model by its geometry.** Give a value to one face, region, or feature that the plan has not
  named (a surface technique on one face, support under one ledge, a seam kept off one side), chosen by geometry,
  never by index, and documented in full.
- **Next — Wave overhangs weighed against support.** Today wave overhangs only fill what the support search left
  unsupported. They become a choice per overhang region, weighed against support and against leaving it as laid, by
  what each prevents and what each costs.
- **Next — Curved tops chosen by the search everywhere.** The search already weighs curved tops for a single object.
  Plates of several objects and whole curved top bands are still used only when locked; they become a choice the search
  weighs too.
- **Planned — Z anti-aliasing weighed whenever it could help**, against thinner layers, instead of opt-in only.
- **Planned — Visible-surface speed and acceleration decided by the job's priorities**, from the motion plate's measured
  limits, and the inner walls behind a visible wall slowed when that shows.
- **Planned — Layer heights the Z axis can make**: a machine's Z step as a setting, layer heights held to its multiples.
- **Planned — One surface made of several sub-surfaces**, each with its own surface technique and pattern, with no
  walls where they meet.
- **Planned — Hard requirements in the job** (no support on a face, a tolerance on a hole), and measures for the
  accuracy goal (hole sizes, flatness, elephant's foot) and for reliability (warping, a part knocked loose).
- **Planned — Head clearance in the pose search**, so a pose is judged by whether the print head can reach what it
  needs.
- **Planned — The search shown live**: the best plan so far printed while the search runs.
- **Planned — Plans compared side by side**: every measure and score for two or more candidate plans in one table.

### Print techniques

- **Planned — A visible shell at its own layer heights beside a thicker hidden core** (a hidden core in thicker layers
  is built; the shell's own heights are not).
- **Planned — A seamless outer wall**: the outer wall climbs one layer per turn with no seam while the inside stays
  flat; spiral (vase) mode as its empty case.
- **Planned — One region before the next**: tall features standing apart each finished in turn within a height band,
  for fewer travels and strings.
- **Exploring — Brick layers**: neighboring wall loops offset by half a layer so no layer boundary runs straight
  through a wall, for strength between layers.
- **Exploring — Z-pinning**: vertical holes through a part's layers filled by long vertical extrusions, for strength in
  the build direction.
- **Exploring — Continuous spiral fills**: one connected path per region per layer, for fewer starts, stops, and
  strings.
- **Exploring — Wave layers** for steep sloped walls that flat layers cut into short pieces: each layer rising and
  falling across the wall within the head's clearance.
- **Possible — Wave overhangs thicker at their anchors**, for strength and less sag near the supports: thicker or more
  layers near an anchor, where a waved arch sags most (more than bending alone predicts, at the soft joint where the
  one-layer strip meets its support). Needs wave fronts whose line size or layer count varies with distance from the
  anchor, and the cooling and speed to go with it (from settings and a test plate until the physics track can size them).
- **Possible — Color by layer**, each layer one color with the wanted color's outer wall widened to show.
- **Possible — Conical layers**, so a flared cap needs no support.
- **Possible — Free-air strands and strand lattices**, drawn through the air: the nozzle rises while it extrudes, so a
  strand is drawn upward from a point instead of laid in a layer. It could make lattices and trusses, strand or "hair"
  textures, and supports without layers. Needs extrusion moves that carry Z (shared with non-planar printing), a model
  of when a strand is stiff enough to stand (cooling, fan, and speed: the physics track), and a head-collision model so
  the nozzle misses the strands already standing. Tried first as an experiment: strand height reached against speed and
  fan.

### Toolpaths and print quality

- **Next — One seam vocabulary**: one word per seam placement, used alike by settings, plan files, and the pipeline.
- **Planned — Fewer travels across seen faces**: the travels still left crossing a visible surface routed inside the
  part, and travels between islands that would cross a seen face avoided.
- **Planned — A scarf seam's length decided per loop**, by what the ramp shows against its time.
- **Planned — A cooling column**: a sacrificial tower printed while a short layer would otherwise wait.
- **Planned — Curved fills simplified without losing their arcs**: a simplified gyroid trace does not fit as arcs, so
  the two are used apart; combined, a gyroid prints with fewer, longer moves.
- **Planned — Infill anchors**, decided by a test plate.

### Multi-material

- **Planned — Automatic filament changers** (several spools fed to one nozzle by the machine).
- **Planned — Spool or tool changes between objects on a plate**.
- **Planned — A smarter wipe tower**: purge volume per pair of filaments, the tower placed where the travels to it
  cross least, and printed as a cooling target.

### Machines and printers

- **Planned — G-code flavors beyond Klipper.**
- **Planned — More printer protocols over HTTP**: OctoPrint, PrusaLink, Duet.
- **Possible — Printers spoken to over MQTT** (Bambu, Anycubic) or Elegoo's SDCP, and serial or copy-to-drive sending.
- **Planned — More printer control**: temperatures, part fan, light, speed, Z offset, each where the machine's API
  allows.
- **Planned — Spool sync**: spools as entities, with what is left on each, read from a connected printer and reconciled
  with the job before slicing (today `plymeld printer loadout` reads and compares, read-only).
- **Planned — Pressure advance**: a calibration plate for it, and a machine setting so plymeld writes none even when a
  filament or tool sets a value, for a printer that calibrates its own.

### Calibration

- **Planned — The filament plate's results read back** (overhangs, bridges, stringing, line widths, fit), as the melt,
  flow, first-layer, motion, ironing, and seam plates' results already are.
- **Planned — A tool's own flow ratio** over the filament's (a flow ratio belongs to the toolhead and its nozzle as much
  as to the filament), written there by `calibrate flow values`.
- **Planned — Instructions pages for the melt and flow plates**, as the other plates have.
- **Planned — The bridging limit per filament**, measured instead of a placeholder.
- **Exploring — An arched-bridge plate** over temperature, span, and speed.

### Files and formats

- **Planned — OBJ, AMF, and PLY** beside STL and 3MF.
- **Planned — An open, documented interchange format** for projects, carrying plymeld's settings and decisions.
- **Planned — Re-slice on every change** (`--watch`) for `plymeld slice`.
- **Possible — Exact geometry from CAD** (STEP, IGES, or a documented 3MF extension): true arcs, exact hole sizes,
  feature-aware defaults for press fits, threads, and cosmetic faces.

### The desktop window

- **Planned — The rest of the window**: calibration grading on the stage, the printer's own buttons, a handle for
  pointing at part of a model, and copy, paste, and keyboard navigation throughout.
- **Planned — User documentation for the window**, as the command line has (`docs/cli/`).
- **Planned — macOS builds**, and signed builds on every system.
- **Exploring — A config editor that knows how values resolve**: for any setting at any scope, the value in force,
  where it comes from, what it overrides, and what could override it.

### Under the hood

- **Planned — Robustness on any input**: mesh fuzzing, streaming throughout, memory and time budgets checked on every
  change.
- **Planned — A core that plans moves, not layers**, so non-planar techniques are first-class.
- **Planned — Print techniques developed outside plymeld, in any language.** A documented, versioned technique
  interface across a process boundary (the model's region, the hardware's facts, and the job's settings in; the
  technique's paths and its predicted outcomes out), so a researcher or a vendor can write a technique in the language
  they work in, without building plymeld. What comes back is checked like plymeld's own output and weighed by the same
  search.
- **Possible — A stable library API and plugin points** (infill patterns, support strategies, planners, g-code
  dialects, post-processors).

### Long-term tracks

- **Possible — Physics in the planner**: a heat model (each bead's temperature when the next lands on it), open
  material files, speeds, fan, and order chosen to suit cooling, and loads with a stress analysis driving walls and
  infill. A goal from the start; it needs measured temperatures to validate.
- **Possible — Non-planar printing beyond tops, undersides, and lifted bridges**: curved layers limited by head
  clearance, free-air strands.
