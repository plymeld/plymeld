package plymeld.model

import plymeld.model.Entity.INTENT
import plymeld.model.Entity.MACHINE
import plymeld.model.Entity.MATERIAL
import plymeld.model.Entity.MATERIAL_TOOL
import plymeld.model.Entity.SPOOL
import plymeld.model.Entity.TOOL
import plymeld.model.Kind.ACCEL
import plymeld.model.Kind.AREA
import plymeld.model.Kind.ANGLE
import plymeld.model.Kind.BOOL
import plymeld.model.Kind.CHOICE
import plymeld.model.Kind.COUNT
import plymeld.model.Kind.FLOW
import plymeld.model.Kind.FRACTION
import plymeld.model.Kind.LENGTH
import plymeld.model.Kind.PERCENT
import plymeld.model.Kind.SPEED
import plymeld.model.Kind.TEMP
import plymeld.model.Kind.TEXT
import plymeld.model.Kind.TIME

/**
 * Every setting plymeld knows, declared once: its id, the entity it lives on, its kind and unit, and
 * what it means. The pipeline reads settings by id; plymeld's config (plymeld-config's Schema) and the
 * importers set them. Adding a setting starts here.
 *
 * An id is exposed (it is shown by `investigate config`, in messages and provenance, and typed in
 * `--set`), so it follows the config keys: **an id is the config key that sets it, without
 * the names you give** (the entry key after `hardware.<kind>`, a filament's `bed.<plate>`, an object
 * or part scope): `hardware.tool.<key>.layer.height.min` sets `hardware.tool.layer.height.min`,
 * `plan.object.<key>.technique.scarf_seam.length` sets `plan.technique.scarf_seam.length`. A setting no one key sets (made
 * from several values, a constant, or read only from another slicer's settings) is named where its
 * key would be, in the group of the keys it is made from or belongs with (`hardware.machine.build.left`
 * beside `build.x`). Schema.conventionProblems holds the two to it.
 */
object Catalog {
    private val all = LinkedHashMap<String, Setting>()
    private fun s(id: String, e: Entity, k: Kind, meaning: String, default: Any? = null, reason: String? = null) {
        require(id !in all) { "duplicate setting $id" }
        all[id] = Setting(id, e, k, meaning, default, reason)
    }

    init {
        // ---- machine: the printer as built
        s("hardware.machine.model", MACHINE, TEXT, "the printer model, by its key: one plymeld ships (e.g. flashforge-c5) or one described in your config: its commands, head shape, and default g-code dialect")
        s("hardware.machine.firmware.flavor", MACHINE, TEXT, "firmware family the g-code is written for")
        s("hardware.machine.firmware.dialect", MACHINE, TEXT, "the g-code dialect (comments and acceleration command, by maker: flashforge, qidi) its files are written with; empty = its model's default", "", "optional: the model names its own")
        s("hardware.machine.tool_change.time", MACHINE, TIME, "time the machine spends on one tool change beyond the moves in the g-code (its own change routine); unset = not counted in time predictions")
        s("hardware.machine.pressure_advance", MACHINE, TIME, "pressure advance (Klipper's, seconds) every tool prints with unless the tool (hardware.tool.pressure_advance) or its filament (hardware.filament.pressure_advance) sets its own; none set = the firmware's own, and plymeld writes none")
        s("hardware.machine.build.z", MACHINE, LENGTH, "tallest printable Z")
        s("hardware.machine.build.left", MACHINE, LENGTH, "printable bed area: lowest X")
        s("hardware.machine.build.front", MACHINE, LENGTH, "printable bed area: lowest Y")
        s("hardware.machine.build.right", MACHINE, LENGTH, "printable bed area: highest X")
        s("hardware.machine.build.back", MACHINE, LENGTH, "printable bed area: highest Y")
        s("hardware.machine.gcode.start", MACHINE, TEXT, "start g-code template")
        s("hardware.machine.gcode.end", MACHINE, TEXT, "end g-code template")
        s("hardware.machine.gcode.layer_change", MACHINE, TEXT, "g-code template run at each layer change", "", "optional: most machines need none")
        s("hardware.machine.gcode.pause", MACHINE, TEXT, "g-code that pauses the print for the user (empty = the machine has none)", "", "optional: needed only when a pause is asked for")
        s("hardware.machine.gcode.level", MACHINE, TEXT, "g-code that levels the bed (e.g. a bed mesh), run after the start template when a print asks for levelling (a calibration plate does unless told not to); empty = none plymeld knows for the machine", "", "optional: a machine levels in its own way, or the user does")
        s("hardware.machine.gcode.pictures", MACHINE, TEXT, "the preview pictures the machine's screen reads from the g-code file, as pixel sizes WIDTHxHEIGHT separated by commas (e.g. 96x96, 300x300); each is written as a PNG in a `; thumbnail begin` comment block; empty = none", "", "optional: only some machines' screens show a picture of the file")
        s("hardware.machine.firmware.exclude_object", MACHINE, BOOL, "firmware can cancel single objects mid-print; emit object markers")
        s("hardware.machine.firmware.arcs", MACHINE, BOOL, "firmware accepts G2/G3 arcs; fit arcs to curved paths")
        s("hardware.plate.first_layer.squish", MACHINE, LENGTH, "shrink the first layer's outline by this (the bed squashes it outward)")
        s("hardware.machine.motion.travel.speed", MACHINE, SPEED, "speed of non-printing moves")
        s("hardware.machine.motion.print.speed", MACHINE, SPEED, "the machine's fastest printing move (travel: hardware.machine.motion.travel.speed): hidden lines (inner walls, infill, solid, support) print at it unless the filament's melt rate caps them first")
        s("hardware.machine.motion.print.acceleration", MACHINE, ACCEL, "acceleration when no feature sets one")
        s("hardware.machine.motion.travel.acceleration", MACHINE, ACCEL, "acceleration of non-printing moves")
        s("hardware.machine.motion.z.speed.max", MACHINE, SPEED, "fastest Z move, for time predictions; unset = the machine's model's")
        s("hardware.machine.motion.z.acceleration.max", MACHINE, ACCEL, "highest Z acceleration, for time predictions; unset = the machine's model's")
        s("hardware.machine.motion.corner.speed", MACHINE, SPEED, "speed kept through a square corner, for time predictions; unset = the machine's model's")
        // ---- tool: one hotend
        s("hardware.tool.bore", TOOL, LENGTH, "nozzle bore")
        s("hardware.tool.layer.height.max", TOOL, LENGTH, "thickest layer this nozzle prints (bounds a purge tower's gap between visits, flat layers' layer height, and the layers that follow the surfaces)")
        s("hardware.tool.layer.height.min", TOOL, LENGTH, "thinnest layer this nozzle prints (bounds flat layers' layer height and the layers that follow the surfaces)")
        s("hardware.tool.head.nozzle.angle", TOOL, ANGLE, "the nozzle's cone: the angle of its side from horizontal, so a surface no steeper than it clears the cone (curved layers)")
        s("hardware.tool.head.nozzle.height", TOOL, LENGTH, "how far the nozzle tip sits below the lowest other part of the loaded head (heater block, sock, duct, carriage): a curved band's whole rise must stay under it")
        s("hardware.tool.head.size.x", TOOL, LENGTH, "the loaded head's size along the machine's X axis, seen from below")
        s("hardware.tool.head.size.y", TOOL, LENGTH, "the loaded head's size along the machine's Y axis, seen from below")
        s("hardware.tool.head.nozzle.x", TOOL, LENGTH, "the nozzle's position within the head along X: its distance from the head's edge on the machine's -X side")
        s("hardware.tool.head.nozzle.y", TOOL, LENGTH, "the nozzle's position within the head along Y: its distance from the head's edge on the machine's -Y side")
        s("hardware.tool.temperature.max", TOOL, TEMP, "hottest this tool's nozzle may be set (the machine maker's figure); above it plymeld warns")
        s("hardware.tool.pressure_advance", TOOL, TIME, "pressure advance this tool prints with (Klipper's, seconds) unless its filament overrides it (hardware.filament.pressure_advance); unset = the machine's (hardware.machine.pressure_advance)")
        // ---- material: the plastic
        s("hardware.filament.diameter", MATERIAL, LENGTH, "filament diameter")
        s("hardware.filament.density", MATERIAL, Kind.DENSITY, "filament density (mass per volume): the decisions compare material by mass with it; unset = by volume, and the decision says so")
        s("hardware.filament.bed.temperature.print", MATERIAL, TEMP, "bed temperature after the first layer")
        s("hardware.filament.bed.temperature.first_layer", MATERIAL, TEMP, "bed temperature for the first layer")
        s("hardware.filament.cooling.slowdown.enabled", MATERIAL, BOOL, "slow down layers that would print faster than the minimum layer time")
        s("hardware.filament.cooling.layer.time.min", MATERIAL, TIME, "a layer should take at least this long, so it cools")
        s("hardware.filament.cooling.speed.min", MATERIAL, SPEED, "never slow below this for layer cooling")
        s("hardware.filament.cooling.fan_off_layers", MATERIAL, COUNT, "part fan stays off for this many first layers")
        s("hardware.filament.cooling.fan", MATERIAL, PERCENT, "part fan speed once it is on")
        // ---- spool: one physical reel
        s("hardware.filament.color", SPOOL, TEXT, "filament color as #RRGGBB")
        // ---- material x tool: values that depend on both the plastic and the hotend
        s("hardware.filament.temperature.print", MATERIAL_TOOL, TEMP, "nozzle temperature after the first layer")
        s("hardware.filament.temperature.first_layer", MATERIAL_TOOL, TEMP, "nozzle temperature for the first layer")
        s("hardware.filament.temperature.min", MATERIAL_TOOL, TEMP, "lowest nozzle temperature the filament is meant to print at (the spool's label); below it plymeld warns")
        s("hardware.filament.temperature.max", MATERIAL_TOOL, TEMP, "highest nozzle temperature the filament is meant to print at (the spool's label); above it plymeld warns")
        s("hardware.filament.flow", MATERIAL_TOOL, FRACTION, "extrusion multiplier")
        s("hardware.filament.pressure_advance", MATERIAL_TOOL, TIME, "pressure advance for this filament in this tool (Klipper's, seconds), overriding the tool's (hardware.tool.pressure_advance); written when its tool starts printing")
        s("hardware.filament.melt.flow.max", MATERIAL_TOOL, FLOW, "fastest the hotend can melt this plastic; every move is capped to it")
        s("hardware.filament.bridge.span.max", MATERIAL_TOOL, LENGTH, "the longest span this filament bridges cleanly between two anchors (mm): a bridge longer than this needs support under its middle")
        s("hardware.filament.overhang.share", MATERIAL_TOOL, FRACTION, "how far this filament's outer wall line may hang past the line below and hold, as a share of the line's width, cooled as it prints in this tool; the flattest face that holds follows from each layer's height (flatter faces get support, with support on, and count as unsupported in planning)")
        s("hardware.filament.overhang.fan", MATERIAL_TOOL, PERCENT, "part fan while a line hangs past the one below: the cooling hardware.filament.overhang.share holds at; the fan returns to the layer's own after")
        s("hardware.filament.overhang.speed", MATERIAL_TOOL, SPEED, "fastest a line that hangs past the one below prints: the speed hardware.filament.overhang.share holds at; unset = not known (only the slow-down graded toward the bridge speed)")
        s("hardware.filament.retract.length", MATERIAL_TOOL, LENGTH, "filament pulled back before a travel")
        s("hardware.filament.retract.speed", MATERIAL_TOOL, SPEED, "retraction speed")
        s("hardware.filament.retract.return.speed", MATERIAL_TOOL, SPEED, "speed pushing filament back after a travel")
        s("hardware.filament.retract.travel.min", MATERIAL_TOOL, LENGTH, "travels shorter than this do not retract")
        // the lift goes with the retraction (it was the plan's hardware.filament.retract.z_hop, a tool setting)
        s("hardware.filament.retract.z_hop", MATERIAL_TOOL, LENGTH, "lift the nozzle this much for a travel that retracts")
        s("hardware.filament.retract.wipe.enabled", MATERIAL_TOOL, BOOL, "wipe the nozzle along the path while retracting")
        s("hardware.filament.retract.wipe.distance", MATERIAL_TOOL, LENGTH, "length of the wipe")
        s("hardware.filament.retract.wipe.speed", MATERIAL_TOOL, SPEED, "speed of the wipe")
        s("hardware.filament.retract.wipe.retract_before", MATERIAL_TOOL, FRACTION, "share of the retraction done before the wipe starts")
        // the restart after a retraction: how far the line runs thin before the flow is back
        s("hardware.filament.retract.restart_lag", MATERIAL_TOOL, LENGTH,
            "how far a line prints starved after a retraction before the flow is back (the restart measure, restart_starved: this length per visible path started after a retraction)",
            5.0, "placeholder: the band after the seam on a photographed base read 20-30 mm with a scarf's thin start on top of the restart; a plain restart's starved length is shorter; not measured — a restart coupon would")
        // ---- intent: what the user wants from the print (overridable per object/part)
        s("job.priorities", INTENT, TEXT,
            "non-negative weight per goal (quality, accuracy, speed, material, strength, reliability), any total, e.g. quality=10,speed=3,strength=5,material=1; and per measure, its share of its goal relative to the goal's other measures (default 1, e.g. layer_lines=4)",
            "quality=1,accuracy=1,speed=1,material=1,strength=1,reliability=1",
            "equal weights: the earlier 5/2/0/1 were made-up values (use whatever makes sense); set per intent or object")
        s("method.search.pose.keep_margin", INTENT, FRACTION,
            "the model's own orientation has standing: another pose must beat the as-given pose's best candidate by more than this score PER UNIT OF WEIGHT in effect (the score gap divided by the sum of the goal weights the decision measures, so the margin means the same at any total: a weight is a share) to turn the model",
            0.1, "placeholder: a tenth of a point per unit of weight, about a 10 % difference on one fully weighted measure")
        // the visibility map: how much a face counts toward quality's measures by how much it is seen on the finished
        // print. The underside share is MEASURED (the seam plate); the bed and turned-away shares are PLACEHOLDERS
        // (plymeld's choice), and the plan names them among its stand-ins
        s("method.quality.visibility.under", INTENT, FRACTION,
            "how much a face seen only from below counts toward quality's measures (surface_step, layer_lines, unsupported_area, support.interface): a horizontal underside counts this share, a face leaning a little below level nearly in full (1 - (1 - underside) x its downward lean); the visibility map every quality measure is weighted by",
            1.0, "measured: a seam plate (`plymeld calibrate seams`) printed in PLA and graded by eye from the front (the plate's shares are the shipped defaults); one plate, one filament, grades in whole steps (hidden, faint, plain, glaring). A point seam on a face leaning out 30 degrees read as plain as one on a vertical wall, one leaning out 45 degrees plainer, so an underside counts in full (the share is never above 1; the extra roughness of a leaning face is the overhang's)")
        s("method.quality.visibility.bed", INTENT, FRACTION,
            "how much a face standing on the bed (the first layer's contact faces) counts toward quality's measures: the visibility map every quality measure is weighted by",
            0.0, "placeholder: the print stands on it; not measured")
        s("method.quality.visibility.away", INTENT, FRACTION,
            "how much a face turned squarely away from where the print is looked at from (plan.view_from) counts toward quality's measures; a face turned part way counts between this and 1 by how squarely it faces away; nothing with plan.view_from = all_around. Not a seam's: its viewing directions are weighed by quadrant (method.quality.visibility.quadrant.*)",
            0.25, "placeholder, not measured: how much a print's back counts is a preference, which no plate reads. Held equal to the seam measure's corner share until the corner share was measured; no longer linked, the value kept (0.25, plymeld's choice then)")
        // a seam's viewing directions weighed by the quadrant they look from: 90-degree quadrants (front, right,
        // back, left), front 8, left and right 2 each, back 1; with no side given, every side alike
        for ((side, w) in listOf("front" to 8.0, "left" to 2.0, "right" to 2.0, "back" to 1.0))
            s("method.quality.visibility.quadrant.$side", INTENT, FRACTION,
                "the least-visible seam (the seam placement and the seam measure): how much the sampled viewing directions from the $side quadrant count, the 90 degrees around the print's $side as seen from plan.view_from (front = the viewer's side; a direction on a quadrant's edge counts the mean of the two); the most-seen open wall reads 1 (with the bundled weights, one facing the viewer squarely). With plan.view_from all_around or above every direction counts alike. Seams only: the other quality measures weigh a face turned away by method.quality.visibility.away",
                w, "front 8, left and right 2 each, back 1")
        // what hides a seam nearby (plymeld.core.decide.Exposure): the least-visible placement accounts for nearby geometry,
        // sampled from a spread of horizontal and vertical angles rather than by a heavy visibility computation.
        // PLACEHOLDERS: coarse enough to stay cheap, not tuned against a print
        s("method.quality.visibility.sample.azimuths", INTENT, COUNT,
            "how many viewing directions around the print a seam's point is sampled from (evenly spaced), for what hides it nearby: the seam placement and the seam measure",
            16, "placeholder: every 22.5 degrees; not tuned")
        s("method.quality.visibility.sample.elevations", INTENT, COUNT,
            "how many heights each viewing direction is sampled at, evenly from level up to method.quality.visibility.sample.elevation.max (1 = level only)",
            3, "placeholder: level, halfway, and the highest; not tuned")
        s("method.quality.visibility.sample.elevation.max", INTENT, ANGLE,
            "the highest viewing direction sampled above level for what hides a seam nearby",
            60.0, "placeholder: a print looked at from a little above eye level down to a desk; not measured")
        s("method.quality.visibility.sample.reach", INTENT, LENGTH,
            "how far from a seam's point a viewing ray is followed for material that hides it (0 = nothing hides it: every wall open)",
            20.0, "placeholder: nearby geometry only, to stay cheap; not measured")
        s("method.quality.visibility.sample.cell", INTENT, LENGTH,
            "the grid each layer's outline is read on when following a viewing ray (one step of the ray)",
            0.25, "placeholder: about half a line width; not tuned")
        s("method.quality.visibility.show_through", INTENT, LENGTH,
            "how deep behind a visible surface a seam still shows through it: a seam whose viewing ray meets only material it leaves within this distance counts 1 - that distance / this (a bead's bulge behind a thin wall pushes the wall out); 0 = any material hides a seam",
            2.0, "placeholder from one print: a part's seam 1.4 mm behind a visible wall showed as two faint vertical lines, where the visibility map counted it hidden; not measured")
        // the stair-step measure (surface_step): each face's terrace edges per mm x each edge's depth
        // to this power. PLACEHOLDER: how fast a shallower edge fades from view is not measured
        s("method.quality.stair_step.depth_power", INTENT, FRACTION,
            "the stair-step measure (surface_step): each face's terrace edges per mm (the sine of its angle to its layer / the layer's height) x each edge's depth to this power: 2 = an edge half as deep counts a quarter, so thinner layers score better while a face that follows its layer (a curved island's cap) shows few edges; 1 = depth and spacing trade evenly (the layer height no longer matters); the measure is in mm to this power less one",
            2.0, "placeholder (edges x depth squared): how fast a shallower terrace edge fades from view is not measured; a graded plate of domes in flat and curved layers at several heights would read it")
        s("method.quality.seam.scarf_share", INTENT, FRACTION,
            "the seam measure (seam_scar): a scarf seam's blemish as a share of a point seam's (one line width squared), whatever the scarf's length: the joint spread over the length, not a line that long",
            0.0, "measured: a seam plate (`plymeld calibrate seams`) printed in PLA and graded by eye from the front (the plate's shares are the shipped defaults); one plate, one filament, grades in whole steps (hidden, faint, plain, glaring). Scarf seams read hidden on a round wall and on a flat face, where point seams on the same walls read plain and faint")
        s("method.quality.seam.corner_share", INTENT, FRACTION,
            "the seam measure (seam_scar): a point seam tucked into a corner (a turn of at least plan.technique.corner_seam.corner_angle) as a share of a point seam on a smooth wall",
            0.0, "measured: a seam plate (`plymeld calibrate seams`) printed in PLA and graded by eye from the front (the plate's shares are the shipped defaults); one plate, one filament, grades in whole steps (hidden, faint, plain, glaring). A point seam in a 90 degree corner read hidden, where one in the middle of a flat face read faint")
        // the bond between layers as the strength goal measures it (bond.strength): the weakest bond's area times a
        // factor for how it was laid. PLACEHOLDERS: no measurement here
        // yet; the helix-layer coupon is the first that can fit them
        s("method.strength.bond.fan_loss", INTENT, FRACTION,
            "how much of the bond between layers a full part fan costs, against no fan, the loss growing with the fan's share: the bond factor's fan term is 1 - fan_loss x fan",
            0.3, "placeholder: part cooling weakens the bond between layers (widely reported for FDM, magnitude varies); not measured here")
        s("method.strength.bond.temperature_gain", INTENT, FRACTION,
            "how much the bond between layers gains for each 10 C the nozzle runs above the filament's print temperature (and loses below it): the bond factor's temperature term is 1 + temperature_gain x (nozzle - print temperature) / 10 C",
            0.1, "placeholder: a hotter nozzle bonds better (widely reported for FDM, magnitude varies); not measured here")
        s("method.strength.bond.cooling_loss", INTENT, FRACTION,
            "how much of the bond between layers is lost when the layer below has cooled for bond.cooling_time or longer before the next goes on: the bond factor's cooling term is 1 - cooling_loss x min(1, the layer time / cooling_time)",
            0.2, "placeholder: a layer laid on a colder one bonds less; not measured here")
        s("method.strength.bond.cooling_time", INTENT, TIME,
            "the layer time after which the layer below counts as fully cooled for the bond (bond.cooling_loss)",
            10.0, "placeholder; not measured here")
        s("method.strength.bond.enough_area", INTENT, AREA,
            "a weakest bond between layers this large (after the bond factor) counts as strong enough: bond.strength gains nothing above it, so cooling is given up for the bond only on objects whose thinnest section is smaller",
            50.0, "placeholder: without it the plan traded cooling for bond strength on every object under equal weights, a 10 x 10 mm block included; not measured here")
        s("method.search.time.max", INTENT, TIME,
            "how long the search for how to build (poses, techniques) may run before it settles on its best plan so far (wall-clock)",
            120.0, "120 s (was 30 s), overridable")
        s("method.search.layer.heights", INTENT, COUNT,
            "how many layer heights of flat layers (one height throughout) the search tries per pose across the tool's range (its thinnest to thickest layer), coarse to fine: the range's ends and the baseline (else the middle) in every pose, then, round by round, the midpoint of the interval next to the leading candidate that could still win; unset = the layer height as set")
        s("method.search.settle_rounds", INTENT, COUNT,
            "how many refinement rounds of layer height in a row may leave the winner as it was before the search stops there; 0 = rounds go on until nothing could win, the time limit, or method.search.layer.heights",
            2, "search effort only")
        s("method.search.log.enabled", INTENT, BOOL,
            "whether every technique decision appends one line (date, model, stop reason, time, rounds) to the search log, so how often the search short-circuits is counted over real use",
            true, "keep a log of how often the short-circuited path is taken; whether a satisfaction threshold is needed is read off it")
        s("method.search.log.file", INTENT, TEXT,
            "the search log's file; empty = search.log in plymeld's state folder; a leading ~/ is the user's home directory; made with its directories when missing",
            "", "plymeld's state folder, the system's place for a log a program keeps for itself (was ~/.cache/plymeld/search.log); point it elsewhere, or switch the log off")
        s("method.search.zero_scale", INTENT, TEXT,
            "reference scales for scoring a count whose baseline value is 0, as metric=value in the metric's unit (e.g. unsupported_area=500); unset = derived from the model")
        s("method.search.baseline.layer.height_share", INTENT, FRACTION,
            "the baseline every decision's differences are measured against: flat layers of one height, this share of the nozzle diameter of the tool that prints the object (its own filament's tool); each measure's scale is its value for that baseline",
            0.5, "flat layers at 50 % of the nozzle diameter (0.20 mm for a 0.4 mm nozzle)")
        s("plan.technique.flat.layer.height", INTENT, LENGTH, "layer height")
        s("plan.mechanism.adhesion.first_layer.height", INTENT, LENGTH, "first layer height")
        s("plan.technique.flat.step.max", INTENT, LENGTH,
            "flat layers following the surfaces (what adaptive layers were): the deepest stair-step a layer may leave on a sloped face; unset = the layer height (plan.technique.flat.layer.height)")
        s("plan.technique.flat.core.layers", INTENT, COUNT,
            "the hidden core in thicker layers: every this many layers, the sparse infill the layers share is laid once on the last of them, the layers' height thick; 1 = off; bundled: as many layers as fit the tool's thickest layer")
        s("plan.technique.flat.height_ratio", INTENT, FRACTION,
            "the transition between layer heights: no layer after the first more than this much thicker than the one below it, and the layers before a band whose layers start thinner stepping down by about this much each; 0 = no limit (the bundled 1.5 is a placeholder)")
        s("method.slicing.tolerance", INTENT, LENGTH, "outline simplification tolerance")
        s("method.slicing.close_gaps", INTENT, LENGTH, "close gaps in slices narrower than twice this")
        s("plan.layer.walls.count", INTENT, COUNT, "wall loops")
        s("plan.layer.walls.style", INTENT, CHOICE, "wall style: fixed_width (loops of one width) or variable_width (loops only where they fit, variable-width center lines in thin areas); another slicer's file says classic or arachne",
            "classic", "a file from another slicer without the key: fixed-width walls, what plymeld always did")
        s("plan.layer.walls.line.width.min", INTENT, LENGTH, "variable-width walls: narrowest line a thin area's center line is printed at (min_bead_width; read only with variable_width)")
        s("plan.layer.walls.feature.min", INTENT, LENGTH, "variable-width walls: thinnest area that still gets a center line (min_feature_size; read only with variable_width)")
        s("plan.layer.walls.length_factor.min", INTENT, FRACTION, "variable-width walls: an open center line shorter than this times its narrowest width is dropped (min_length_factor; read only with variable_width)",
            0.0, "a profile without min_length_factor applies no such filter: 0 = off, not a chosen value")
        s("plan.layer.walls.transition_length", INTENT, LENGTH, "variable-width walls: center lines stay continuous through thinner stretches and pinches up to this long (wall_transition_length; read only with variable_width)")
        s("plan.layer.walls.outer.line.width", INTENT, LENGTH, "outer wall line width")
        s("plan.layer.walls.inner.line.width", INTENT, LENGTH, "inner wall line width")
        s("plan.layer.walls.inner_first", INTENT, BOOL, "print inner walls before the outer wall")
        s("plan.seam_start", INTENT, CHOICE, "where wall loops start (from plan.seam, or with scarf seams their plan.technique.scarf_seam.plain_style, or decided): corners (at a corner when the loop has one, inside corners first, each layer's seam nearest the one below), back (the back-most point) or least_visible (the point the seam measure counts least: its visibility, from the sampled viewing directions weighed by the quadrant they look from (method.quality.visibility.quadrant.*, around plan.view_from) and what hides it nearby, or an underside, times method.quality.seam.corner_share in a corner; ties to the point the viewer sees least first (a turned-away face before a front corner), then to a corner, then to the seam below, then the back); either way never on an edge hanging over the layer below when the loop has another place")
        s("plan.view_from", INTENT, CHOICE,
            "where the finished print is looked at from, for quality's measures (the visibility map): all_around (no face counts as turned away), front, back, left, right, above: the model's own sides as given (front = its small-y side, the side toward the printer's door when it prints as given), turned with whatever pose the plan gives it; a face turned away from the viewer counts method.quality.visibility.away by how squarely it faces away; a seam's viewing directions count by the quadrant they look from (method.quality.visibility.quadrant.*: front, left, right, back as the viewer sees them; alike with all_around or above); per object plan.object.<key>.view_from",
            "all_around", "every face counts as seen from somewhere unless the job says where the print is looked at from (plymeld's choice)")
        s("plan.technique.corner_seam.corner_angle", INTENT, ANGLE, "the smallest turn of a wall loop that counts as a corner for its seam")
        s("plan.technique.scarf_seam.where", INTENT, CHOICE, "scarf seams on outer walls (the loop starts thin and low, rising to the layer over plan.technique.scarf_seam.length, and ends re-printing that stretch thinning to nothing): none (plan.seam not scarf_seam), smooth (only loops whose seam does not land on a corner: with corner_seam as the plain style, the loops with no corner), all")
        s("plan.technique.scarf_seam.length", INTENT, LENGTH, "how long a scarf seam's ramp is (at most half the loop)")
        s("plan.technique.scarf_seam.start", INTENT, FRACTION, "a scarf ramp's starting thickness, as a share of the layer")
        s("plan.mechanism.adhesion.first_layer.line.width", INTENT, LENGTH, "line width on the first layer")
        s("plan.layer.top.under_parts", INTENT, BOOL, "a top surface that is mostly exposed runs under the OTHER parts standing on it (not under its own part rising further): its pattern unbroken, the top shell solid under those footprints too; objects of several parts only",
            true, "the top surface in the same pattern as with nothing printed on top of it")
        s("plan.faces.sloped_top.z_antialiasing", INTENT, BOOL, "the sloped tops' surface technique is Z anti-aliasing (plan.faces.sloped_top.technique = \"z_antialiasing\"), flat layers only: where the model's top lies within a layer, that layer's lines are lowered onto it with as much less plastic (never thinner than the tool's thinnest layer), and the layer reaches out to where the top is that thin above its bottom, so a slope keeps no stair step of a whole layer",
            false, "built opt-in, off until a print decides it")
        s("plan.layer.top.thickness", INTENT, LENGTH, "solid shell thickness under a top surface (the number of layers follows from the layer heights)")
        s("plan.layer.bottom.thickness", INTENT, LENGTH, "solid shell thickness over a bottom surface (the number of layers follows from the layer heights)")
        s("plan.layer.infill.density", INTENT, FRACTION, "sparse infill density")
        s("plan.layer.infill.pattern", INTENT, CHOICE, "sparse infill pattern")
        s("plan.layer.top.pattern", INTENT, CHOICE, "top surface pattern: line patterns (monotonic ...), concentric-outside-in, concentric-inside-out, or archimedeanchords",
            "monotonic", "files from slicers without the key; the line pattern plymeld always used")
        s("plan.layer.bottom.pattern", INTENT, CHOICE, "bottom surface pattern: line patterns (monotonic ...), concentric-outside-in, concentric-inside-out, or archimedeanchords",
            "monotonic", "files from slicers without the key; the line pattern plymeld always used")
        s("plan.layer.infill.area.min", INTENT, Kind.AREA, "a sparse infill piece smaller than this is printed as internal solid infill instead",
            0.0, "files from slicers without the key: off, as plymeld always printed them")
        s("plan.layer.infill.narrow.cells", INTENT, FRACTION, "a sparse piece narrower than this many cells of its pattern (the spacing of one family of lines) is narrow: the pattern cannot form there",
            1.0, "plymeld's rule: a pattern needs about one of its cells to form; an option")
        s("plan.layer.infill.narrow.fill", INTENT, CHOICE, "what a narrow sparse piece gets: loop (one loop at bead contact inside its walls) or solid",
            "loop", "keep things sparse by default, solid as an option")
        s("plan.layer.infill.line.width", INTENT, LENGTH, "sparse infill line width")
        s("plan.layer.solid.line.width", INTENT, LENGTH, "internal solid infill line width")
        s("plan.layer.top.line.width", INTENT, LENGTH, "top surface line width")
        s("plan.layer.infill.angle", INTENT, ANGLE, "infill line direction")
        s("plan.layer.infill.chord_tolerance", INTENT, LENGTH, "curved infill chord tolerance")
        s("plan.layer.infill.wall_overlap", INTENT, FRACTION, "how far infill reaches into the walls, as a share of its width")
        s("plan.layer.infill.anchor_length.max", INTENT, LENGTH, "longest walk along the boundary that joins two infill lines: another slicer's (investigation); unset = plymeld's rule, three line spacings")
        // speeds: visible lines are capped by a speed of their own,
        // hidden ones print at the machine's fastest move; every move is capped by the melt rate
        s("plan.mechanism.motion.visible.speed", INTENT, SPEED, "speed of visible lines: outer walls, top and bottom surfaces")
        s("plan.mechanism.cooling.slowdown.style", INTENT, CHOICE, "how a layer is slowed to its minimum layer time: hidden_first (the hidden lines first, the visible only for the rest) or even (every line by one factor)",
            "hidden_first", "plymeld's rule (a gap found reviewing a slice): a visible line's speed changing layer to layer shows as bands; a hidden line's does not")
        s("plan.mechanism.cooling.dwell.enabled", INTENT, BOOL, "a layer still short of its minimum layer time at the minimum speed: the head waits out the rest (true) or not",
            true, "a lift dwell by default")
        s("plan.mechanism.cooling.dwell.style", INTENT, CHOICE, "how the head waits: lift (straight up where the layer ended) or park (beside the layer)",
            "lift", "'lift head (lower bed). Keeps distance from the model and might reduce stringing/ooze'")
        s("plan.mechanism.cooling.dwell.lift", INTENT, LENGTH, "how far the head rises for a lift dwell",
            5.0, "'5mm default is fine' (plymeld's figure, accepted)")
        s("plan.mechanism.motion.first_layer.speed", INTENT, SPEED, "speed of everything on the first layer (adhesion)")
        s("plan.mechanism.motion.bridge.speed", INTENT, SPEED, "bridge speed; a wall line slows toward it as more of it hangs over air")
        s("plan.technique.ironing.speed", INTENT, SPEED, "ironing speed")
        s("plan.mechanism.motion.visible.acceleration", INTENT, ACCEL, "acceleration of visible lines (outer walls, top and bottom surfaces); the rest print at the machine's printing acceleration")
        s("plan.mechanism.motion.detour.margin", INTENT, LENGTH, "avoid_crossing: how far outside an island's outer wall a detour runs (beyond the wall's center line plus half its width)",
            1.0, "a placeholder ('hard-code good default sub options for now'); a setting with provenance")
        s("plan.mechanism.motion.detour.ratio.max", INTENT, FRACTION, "avoid_crossing: a detour longer than this many times the straight travel plus detour.extra.max is not taken",
            2.0, "a placeholder")
        s("plan.mechanism.motion.detour.extra.max", INTENT, LENGTH, "avoid_crossing: the length added to detour.ratio.max times the straight travel that a detour may still have",
            20.0, "a placeholder")
        s("plan.mechanism.motion.detour.speck.ratio.max", INTENT, FRACTION, "avoid_crossing: a travel to or from an outer-wall speck routed along the wall it lines (where combing finds no way inside) is not taken when longer than this many times the straight travel plus detour.speck.extra.max; such a route runs over printed wall, so it may be longer than other detours",
            5.0, "5 x the straight travel + 50 mm (travel at the machine's speed: 100 mm is about 0.2 s at 600 mm/s)")
        s("plan.mechanism.motion.detour.speck.extra.max", INTENT, LENGTH, "avoid_crossing: the length added to detour.speck.ratio.max times the straight travel that a route along the wall to or from a speck may still have",
            50.0, "plymeld's choice")
        s("plan.mechanism.motion.detour.inset", INTENT, LENGTH, "avoid_crossing: how far inside an island's outer wall (its center line) a travel within the island runs, so it stays over the island's own walls and fill; unset = the widest outer wall's line width on the layer (plymeld's choice: over the inner walls, clear of the outer one)")
        s("plan.mechanism.motion.avoid_crossing", INTENT, BOOL, "travels go around the layer's other islands (their outer walls' convex hulls, detour.margin outside the wall) rather than straight over them, so a string lies over nothing, and a travel within one island stays inside it (detour.inset inside its outer walls: never out over an edge or across a notch in its outline;); a travel to or from an outer-wall speck that no route inside reaches runs along the outer wall the speck lines, retracting as usual, within detour.speck.ratio.max and detour.speck.extra.max (travels between a raised name's letter tips strung across the gaps); an island the travel leaves or enters cannot be avoided; a detour or route over detour.ratio.max times the straight travel plus detour.extra.max is not taken",
            true, "on by default: travels between a purge tower and a part left strings embedded in the part between them, and travels across an engraved name within one island strung too; the margin and the cap are the detour.* settings (their bundled values placeholders, not measured)")
        s("plan.mechanism.motion.no_retract_in_island", INTENT, BOOL, "a travel whose straight path stays inside the island being printed (the layer's piece it is on), crosses no wall line already laid on the layer nor any outer wall of the layer, and neither leaves nor enters a top surface or ironing, does not retract or lift; off = it retracts and lifts like any travel over hardware.filament.retract.travel.min; unset = decided per object by the plan on its planned layers, by the job's weights (the strings those travels risk, string_risk, against the restarts and the time of retracting them), said in the slice's output; earlier bundled true (a helix island's three runs each retracted, lifted, and restored for 1-2 mm moves inside it)")
        s("plan.layer.bridge.flow", INTENT, FRACTION, "bridge extrusion multiplier")
        s("plan.mechanism.adhesion.brim.where", INTENT, CHOICE, "brim placement: no_brim (plan.mechanism.adhesion.brim.enabled off) or outer_only")
        s("plan.mechanism.adhesion.brim.width", INTENT, LENGTH, "brim width")
        s("plan.mechanism.adhesion.brim.gap", INTENT, LENGTH, "gap between brim and object")
        s("plan.mechanism.adhesion.prime.line", INTENT, CHOICE, "the prime line: near the print, near it on a peel tab (alongside the brim), or none (plan.mechanism.adhesion.prime.enabled off); unset = by the print: none with a prime tower, tab otherwise")
        s("plan.mechanism.adhesion.prime.length", INTENT, LENGTH, "the prime line's length")
        s("plan.mechanism.adhesion.prime.gap", INTENT, LENGTH, "how far the prime line lies from the print")
        s("plan.technique.bridge.ramp_speeds", INTENT, TEXT, "an arched bridge's speeds over air in its layers after its first, in mm/s separated by commas; then ramp_growth; the usual speeds once the bridge speed is reached")
        s("plan.technique.bridge.ramp_growth", INTENT, FRACTION, "after ramp_speeds, an arched bridge's speed over air in each layer this many times the last, up to the bridge speed (1 = none)")
        s("plan.technique.bridge.temperature", INTENT, TEMP, "the nozzle temperature of an arched lifted bridge's own layers (above or below straight between its supports), waited for; the rest of the object at the print temperature")
        s("plan.technique.bridge.print.speed", INTENT, SPEED, "an arched lifted bridge's first layer over the hollow at this speed (its segments over the supports at the usual speeds), its next layers ramping up from it (bridge.ramp_speeds); unset = the usual speeds")
        s("plan.technique.bridge.travel.speed", INTENT, SPEED, "every head move that extrudes nothing in an arched lifted bridge's layers (travels, lifts, drops; not retractions) at most this fast; unset = the usual speeds")
        s("plan.technique.bridge.layer.height", INTENT, LENGTH, "a lifted bridge's own layer height (several lifted bridges in one object); unset = the object's")
        s("plan.technique.bridge.top_layer", INTENT, CHOICE, "an arched lifted bridge's top layer: normal (usual speeds, the print temperature) or bridge (the bridge's own)")
        s("plan.bridge_order", INTENT, CHOICE, "objects printed together: by_height = every layer merged by height; hot_to_cold = every object's layers below its lifted bridge first, then the bridges one bridge temperature at a time, hottest first; one_at_a_time = the layers below first, then each bridge whole, from +X to -X")
        s("plan.technique.bridge.first_layer", INTENT, CHOICE, "a lifted bridge's first layer: lines (bridge lines) or wave_overhang (wave beads grown from every support at once, one side then the other)")
        s("plan.technique.bridge.wave.sides", INTENT, CHOICE, "a waved first layer's sides: one_then_other (one side whole, then the other), alternate (front by front) or one_support (grown from one support over the peak to the other)")
        s("plan.technique.bridge.wave.edges.order", INTENT, CHOICE, "when a waved first layer's edge pieces are laid: with_each_side (after that side's beads) or last (after both sides')")
        s("plan.technique.bridge.wave.edges.enabled", INTENT, BOOL, "whether a waved first layer's edge pieces are laid at all; unset = laid")
        s("plan.technique.bridge.wave.line.direction", INTENT, CHOICE, "which edge a waved first layer's beads start from: mirrored, matched, or zigzag")
        s("plan.technique.bridge.wave.crossing", INTENT, CHOICE, "how the head travels between a waved first layer's beads: over, beside, or away (round the object at a distance)")
        s("plan.technique.bridge.wave.away.distance", INTENT, LENGTH, "how far from the object the head goes after each bead with wave.crossing = away")
        s("plan.technique.bridge.wave.wait_zones", INTENT, TEXT, "waits after a waved first layer's beads by zone along the span, in percent from the first support")
        s("plan.technique.bridge.wave.second_pass.zones", INTENT, TEXT, "a waved first layer's lines laid a second time over it, by zone along the span: height above and share of plastic")
        s("plan.technique.bridge.wave.second_pass.speed", INTENT, SPEED, "the speed of a waved first layer's second pass")
        s("plan.technique.bridge.wave.second_pass.approach", INTENT, LENGTH, "how far above each second-pass line's start the head comes straight down from")
        s("plan.technique.bridge.wave.x_layer.enabled", INTENT, BOOL, "Xs over a waved first layer, laid after it, from the supports to the middle")
        s("plan.technique.bridge.wave.x_layer.raise", INTENT, LENGTH, "how far above a waved first layer's top its Xs are laid")
        s("plan.technique.bridge.wave.x_layer.share", INTENT, FRACTION, "the Xs' plastic as a share of the waved layer's lines'")
        s("plan.technique.bridge.wave.x_layer.inset", INTENT, LENGTH, "how far in from the span's edges the Xs' ends stop")
        s("plan.technique.bridge.wave.x_layer.middle", INTENT, CHOICE, "when a waved layer's middle X is laid: after the sides' Xs, or first")
        s("plan.technique.bridge.wave.x_layer.speed", INTENT, SPEED, "the speed of the Xs over a waved first layer")
        s("plan.technique.bridge.wave.span_layer.enabled", INTENT, BOOL, "a full layer of lines along the span over a waved first layer, laid after it")
        s("plan.technique.bridge.wave.span_layer.raise", INTENT, LENGTH, "how far above a waved first layer's top its layer along the span is laid")
        s("plan.technique.bridge.wave.span_layer.speed", INTENT, SPEED, "the speed of the layer along the span over a waved first layer")
        s("plan.technique.bridge.wave.spacing", INTENT, CHOICE, "a waved first layer's line spacing: as grown, or even along the bridge's surface with a middle line laid last")
        s("plan.technique.bridge.wave.crossing_clearance", INTENT, LENGTH, "how far past the span's edge a crossing beside the bridge goes")
        s("plan.technique.bridge.wave.retract", INTENT, LENGTH, "the retraction before each travel between a waved first layer's beads (unset = the filament's)")
        s("plan.technique.bridge.wave.wipe", INTENT, BOOL, "whether to wipe before each travel between a waved first layer's beads (unset = the filament's)")
        s("plan.technique.bridge.wave.wait", INTENT, TIME, "a wait before each bead of a waved first layer but its first (unset = none)")
        s("plan.technique.bridge.wave.layers", INTENT, CHOICE, "how many of a lifted bridge's first layers are laid as waves, together: 1 or 2")
        s("plan.technique.bridge.wave.second_layer", INTENT, CHOICE, "with two waved layers: the second full, or only an X over each section of the first")
        s("plan.technique.bridge.wave.x_length", INTENT, LENGTH, "the section of the first waved layer each X of the second covers, along the span")
        s("plan.technique.bridge.wave.start", INTENT, CHOICE, "the region a waved first layer lays first, the rest filled with waves after: whole, edge strips, triangles, or opposite triangles")
        s("plan.technique.bridge.wave.strip.width", INTENT, LENGTH, "each edge strip's width when a waved first layer starts with edge strips")
        s("plan.technique.bridge.wave.front", INTENT, CHOICE, "the shape of a waved first layer's fronts: straight, or chevrons")
        s("plan.technique.bridge.wave.chevron.lead", INTENT, LENGTH, "how far a chevron front's leading part leads the rest along the span")
        s("plan.technique.curved.top_order", INTENT, CHOICE, "the order of a curved band's top lines: one sweep, or uphill from each side to the highest line")
        s("plan.technique.bridge.wave.chevron.leading", INTENT, CHOICE, "which part of a chevron front leads: the edges, or the middle")
        s("plan.technique.bridge.wave.chevron.lines", INTENT, CHOICE, "how a chevron's lines run: along the fronts, or across, parallel to the support's edge")
        s("plan.technique.bridge.wave.after_supported", INTENT, BOOL, "a waved first layer's lines after the strip it lays over the supports")
        s("plan.technique.bridge.fill_angles", INTENT, CHOICE, "a lifted bridge's fill directions: along and across its span, its top along it; or the object's own")
        s("plan.technique.bridge.fill_along", INTENT, BOOL, "a lifted bridge's layers all along its bridge: every fill at its first layer's bridge angle, no crossing lines")
        s("plan.technique.bridge.tack.layers", INTENT, COUNT, "a lifted bridge's layers after its first that are tacked to it (0 = none): each dips to where the first would lie pulled straight, fuses, and rises")
        s("plan.technique.bridge.tack.length", INTENT, LENGTH, "how long a tack runs at its low height")
        s("plan.technique.bridge.tack.rise", INTENT, LENGTH, "how far along the line a tack rises back to the layer")
        s("plan.technique.bridge.tack.spacing", INTENT, LENGTH, "how far apart tacked lines lie across the bridge (its outermost lines first)")
        s("plan.support.enabled", INTENT, BOOL, "generate support")
        s("plan.support.gap.side", INTENT, LENGTH, "horizontal gap between support and object; unset = plymeld's rule, one support line")
        s("plan.support.gap.top.value", INTENT, LENGTH, "vertical gap between support top and object, rounded to whole layers; unset = one layer, at least gap.top.min")
        s("plan.support.gap.top.min", INTENT, LENGTH, "the least gap between support's top and the object when gap.top.value is unset (one layer, at least this)",
            0.2, "one layer with a minimum; 0.2 mm the top gap that held in another slicer's print where 0.1 mm fused")
        s("plan.support.gap.bottom", INTENT, LENGTH, "vertical gap between support standing on the model and the model's top under it (the support's lowest interface.bottom_layers above it bottom interface); unset = one layer, 0 = laid onto it")
        s("plan.support.column.width", INTENT, LENGTH, "the narrowest column of support laid: the narrowest overhang that gets support, judged with the overhang within this height above and below it (a sloped face is a stack of thin strips); narrower = no support, a speck; unset = plymeld's rule, a tree tip (two support lines)")
        s("plan.support.interface.top_layers", INTENT, COUNT, "interface layers under the object")
        s("plan.support.interface.bottom_layers", INTENT, COUNT, "interface layers on top of what support stands on")
        s("plan.support.spacing", INTENT, LENGTH, "gap between support lines")
        s("plan.support.interface.spacing", INTENT, LENGTH, "gap between interface lines; unset = plymeld's rule, lines at bead contact")
        s("plan.support.from_bed_only", INTENT, BOOL, "only support from the build plate")
        s("plan.support.line.width", INTENT, LENGTH, "support line width")
        s("plan.support.angle", INTENT, ANGLE, "support line direction")
        s("plan.support.slot", INTENT, COUNT, "filament slot that prints support (0 = whichever filament is loaded)",
            0, "by default print support with whichever filament is currently loaded; allow one to be specified")
        s("plan.support.interface.slot", INTENT, COUNT, "filament slot that prints support interface (0 = whichever filament is loaded)",
            0, "by default print support with whichever filament is currently loaded; allow one to be specified")
        s("plan.support.tree.branch_angle", INTENT, ANGLE, "tree support: steepest lean of a branch from vertical: another slicer's (investigation); unset = plymeld's rule, the filament's overhang step per layer")
        s("plan.support.tree.tip_diameter", INTENT, LENGTH, "tree support: branch diameter at the tip: another slicer's (investigation); unset = plymeld's rule, two support lines")
        s("plan.support.tree.branch_diameter", INTENT, LENGTH, "tree support: largest branch diameter")
        s("plan.support.tree.widening", INTENT, ANGLE, "tree support: how fast a branch widens going down")
        s("plan.support.tree.tip_spacing", INTENT, LENGTH, "tree support: spacing of the tips under an overhang")
        s("plan.technique.fuzzy_skin.where", INTENT, CHOICE, "fuzzy skin on: none (plan.faces.side.technique not fuzzy_skin), external (outside outlines), all (+ holes), allwalls")
        s("plan.technique.fuzzy_skin.thickness", INTENT, LENGTH, "fuzz amplitude (points move up to ± this)")
        s("plan.technique.fuzzy_skin.point_distance", INTENT, LENGTH, "average spacing of fuzz points")
        s("plan.technique.fuzzy_skin.first_layer", INTENT, BOOL, "fuzz the first layer too")
        s("plan.technique.fuzzy_skin.mode", INTENT, CHOICE, "how fuzz is made", "displacement", "the only mode implemented; files from older slicers omit it")
        s("plan.technique.fuzzy_skin.noise", INTENT, CHOICE, "fuzz noise", "classic", "the only noise implemented; files from older slicers omit it")
        s("plan.technique.ironing.type", INTENT, CHOICE, "which surfaces are ironed (none, top: plan.faces.flat_top.technique = \"ironing\")")
        s("job.faces.sloped_top.look", INTENT, CHOICE, "how a rounded top should look where a curved island could print it: no_preference (the weights decide per island), lines_follow_curve (every island, unweighed), concentric_steps (no island)",
            "no_preference", "no preference as the default")
        s("job.part_overlap", INTENT, CHOICE, "where parts on different filaments overlap, who prints the shared volume: started_first (the part that starts lowest: a part embedded in another prints only where it comes out), lower_filament, higher_filament, earlier_part, later_part",
            "started_first", "by default simplify the print and stick with the filament already printing there (no cut-outs for embedded parts); overridable, e.g. for translucency (before: lower_filament)")
        // purge tower (multi-color): read only when a print changes filament
        s("plan.mechanism.purge.tower.enabled", INTENT, BOOL, "print a purge tower for filament changes")
        s("plan.mechanism.purge.into", INTENT, CHOICE, "where the purge after a tool change goes: tower; infill = the new filament's own sparse infill on the layer first, the tower taking what is left; infill_and_object = its sparse then its hidden solid infill first",
            "tower", "the behavior before the choice existed; a purge into infill shows through thin walls of a light part, so it is chosen per print or per part")
        s("plan.mechanism.purge.tower.layout", INTENT, CHOICE, "compact: visit the tower only on layers with a change (or when a gap would exceed the nozzle's max layer height), wall counted in the purge; banded: wall and every band on every layer",
            "compact", "compact by default, overridable")
        s("plan.mechanism.purge.tower.x", INTENT, LENGTH, "purge tower position: X of its lower-left corner")
        s("plan.mechanism.purge.tower.y", INTENT, LENGTH, "purge tower position: Y of its lower-left corner")
        s("plan.mechanism.purge.tower.width", INTENT, LENGTH, "purge tower width (along X): another slicer's (investigation); unset = plymeld's rule, a square tower sized by the purge (PurgeTower.square)")
        s("hardware.filament.purge.volume", MATERIAL_TOOL, Kind.VOLUME, "plastic purged into the tower after a change to this filament in this nozzle (hardware-owned)")
        s("hardware.tool.heat.time", INTENT, TIME, "on a multi-nozzle machine, heat the next nozzle this long (print time) before its change")
        s("hardware.filament.temperature.idle_drop", INTENT, TEMP, "how far below printing temperature an idle nozzle is held on a multi-nozzle machine (0 = keep it at printing temperature)")
        s("plan.faces.under.wave_overhang", INTENT, BOOL, "the undersides over air are wave overhangs (plan.faces.under.technique = \"wave_overhang\"): each flat layer's bottom over air hanging off one side grown outward from the supported material beside it, bead by bead, each leaning on the last; a span (every bridge line landing on what is printed at both ends) stays a bridge, an area with support under it is the support's; what no front reaches is bridged",
            false, "off without the bundled config (an imported profile alone has no wave_overhang values to lay beads with); the bundled config turns it on (wave overhang, support, or nothing for an underside hanging off one side)")
        s("plan.faces.under.from", INTENT, LENGTH, "the underside surface technique only on layers whose top is above this height (from the object's bottom); unset = from the bottom")
        s("plan.faces.under.to", INTENT, LENGTH, "the underside surface technique only on layers whose bottom is below this height (from the object's bottom); unset = to the top")
        s("plan.technique.wave_overhang.spacing", INTENT, LENGTH, "wave overhangs: how far each front lies from the last (the beads' center spacing, under a line width)")
        s("plan.technique.wave_overhang.overlap", INTENT, LENGTH, "wave overhangs: how far the first bead reaches back over the supported material")
        s("plan.technique.wave_overhang.neck.min", INTENT, LENGTH, "wave overhangs: a neck of the overhang narrower than this is not spanned")
        s("plan.technique.wave_overhang.area.min", INTENT, Kind.AREA, "wave overhangs: growth stops when a front adds less than this")
        s("plan.technique.wave_overhang.line.area", INTENT, Kind.AREA, "wave overhangs: the plastic per mm of a bead, as its cross-section (laid in the air, the layer height does not set it)")
        s("plan.technique.wave_overhang.speed", INTENT, SPEED, "wave overhangs: every bead at this speed")
        s("plan.technique.ironing.spacing", INTENT, LENGTH, "gap between ironing lines")
        s("plan.technique.ironing.flow", INTENT, FRACTION, "ironing flow, as a share of a full line")
        // bond-first cooling: what an object gets when the search (or plan.bond_cooling = on) chooses the bond between
        // its layers over the filament's own cooling; bundled as shares of the filament's values, placeholders
        s("plan.technique.bond_cooling.fan", INTENT, PERCENT, "bond-first cooling: the part fan for the object's lines (overhangs keep the filament's overhang fan)")
        s("plan.technique.bond_cooling.layer.time.min", INTENT, TIME, "bond-first cooling: the object's minimum layer time (its layers stretched to it less than the filament's asks)")
        s("plan.technique.bond_cooling.temperature", INTENT, TEMP, "bond-first cooling: the object's nozzle temperature after the first layer (capped at the filament's temperature.max where set)")
        s("plan.technique.ironing.inset", INTENT, LENGTH, "keep ironing this far inside the edge (on top of half a line)")
        s("plan.technique.ironing.angle", INTENT, ANGLE, "ironing line direction")
    }

    operator fun get(id: String): Setting = all[id] ?: error("unknown setting '$id'")
    val settings: Collection<Setting> get() = all.values
}
