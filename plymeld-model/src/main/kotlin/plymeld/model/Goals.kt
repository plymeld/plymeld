package plymeld.model

/**
 * The six goals, in this order everywhere. Each
 * gets a non-negative weight; the weights may total anything ("quality 5, speed 2, material 0"). A goal is measured by
 * one or more metrics; a weight may also name a single metric directly.
 *
 * - QUALITY: how the finished print looks, judged by the print artifacts expected on it (layer lines and stair-steps,
 *   seams, support scars, drooped or curled overhangs, stringing and blobs, ringing, over- and under-filled surfaces,
 *   color bleed), each weighted by how severe and how visible it is, not merely counted.
 * - ACCURACY: how closely the print matches the model's dimensions (hole sizes and fits, flatness, elephant's foot,
 *   warping out of shape). Split out of quality so a functional part can weight fit up and looks down. Nothing measures
 *   it yet.
 * - SPEED: total time from start to a finished print on the bed: heating, levelling, homing, tool and filament changes
 *   with their purges, temperature changes between objects, and pauses as well as moving. Not the time a failed print
 *   costs (reliability), nor the user's own work afterwards.
 * - MATERIAL: all filament used, by mass (object, support, brims and skirts, prime lines, prime and wipe towers,
 *   purges); mass stands in for cost. Not a failed print's filament (reliability).
 * - STRENGTH: the finished print's ability to withstand the loads it will see in use; with no loads marked, its
 *   weakest direction (usually across the layers). Breaking during the print is reliability.
 * - RELIABILITY: the chance that the print finishes usable, run after run, without a total or partial failure that
 *   would likely need a reprint (lost adhesion, warping that lifts the part, a part knocked loose or tipped over, a
 *   nozzle collision, spaghetti from unsupported areas, a failed tool change, running out of filament). On a plate,
 *   one object failing counts.
 *
 * The boundary rule: reliability asks whether you get a usable print at all; quality, accuracy, and strength ask how good
 * that print is; speed and material ask what a successful print costs. Each [Metric] counts toward exactly one goal.
 */
enum class Goal(val id: String) {
    QUALITY("quality"), ACCURACY("accuracy"), SPEED("speed"), MATERIAL("material"), STRENGTH("strength"), RELIABILITY("reliability")
}

/**
 * A measurable quantity and the ONE goal it counts toward, shown in every decision log (the boundary rule on [Goal]).
 * Support counts under two goals through two different measures: its plastic under MATERIAL
 * (support.mass, or support.volume without a density), its scars under QUALITY (support.interface). With support on,
 * unsupported_area is 0, so the area that gets support is counted under quality once, as scars; with support off it is
 * droop, and support.interface is 0. Each goal's measures cover only part of its definition.
 * Metrics with no goal are measured and reported but count only when a weight names them directly.
 */
enum class Metric(val id: String, val goal: Goal?, val better: Better, val unit: String, val meaning: String) {
    UNSUPPORTED_AREA("unsupported_area", Goal.QUALITY, Better.LESS, "mm²", "droop-equivalent area: each downward face too steep to hold itself up that prints over air, weighted by how far past the allowed hang its layers step out (1 at the limit, 10 for a horizontal face over nothing), as air: support off, it droops (0 with support on, where it gets support and counts as support.interface) — part of quality, with support.interface, surface_step, and layer_lines; each face weighted by how much it is seen (the visibility map)"),
    SUPPORT_INTERFACE("support.interface", Goal.QUALITY, Better.LESS, "mm²", "the support interface (0 with support off): where support meets the model, the whole patch under each overhang region it holds up (the tops of the trees) plus the model surface their columns stand on (their bottoms; nothing on the bed); the faces support touches and scars, each weighted by how much it is seen (the visibility map) — part of quality; support's plastic is support.mass or support.volume, under material"),
    HEIGHT("height", null, Better.LESS, "mm", "object height; not time"),
    TRAVEL("travel", null, Better.LESS, "mm", "travel between objects over the whole print; not time"),
    TIME("time.prediction", Goal.SPEED, Better.LESS, "s", "predicted print time (step 8: the plan's g-code through the machine's motion limits): moves, dwells, and the machine's own time per tool change (hardware.machine.<key>.tool_change.time); not heating, homing, levelling, or pauses — part of speed"),
    FILAMENT_MASS("filament.mass", Goal.MATERIAL, Better.LESS, "g", "predicted filament used, everything the g-code extrudes, by mass: each tool's length x its filament's cross-section x density"),
    FILAMENT_VOLUME("filament.volume", Goal.MATERIAL, Better.LESS, "mm³", "predicted filament used, everything the g-code extrudes, by volume (each tool's length x its filament's cross-section): in place of filament.mass when a filament has no density (equal to mass up to density; exact for one filament)"),
    BED_CONTACT("bed_contact", Goal.RELIABILITY, Better.MORE, "mm²", "flat area on the bed (adhesion)"),
    TIP_OFFSET("tip_offset", Goal.RELIABILITY, Better.LESS, "mm", "how far the center of mass lies beyond the bed contact (0 = over it)"),
    // Quick estimates for comparing poses: cheap, made
    // from the mesh and settings, never from another slicer; the full prediction replaces them.
    SURFACE_STEP("surface_step", Goal.QUALITY, Better.LESS, "mm", "mean stair-step on the part's sloped faces: each face's terrace edges per mm (the sine of its angle to its layer / the layer's height) x each edge's depth (the layer's height x |normal's vertical part|) to method.quality.stair_step.depth_power (mm at the bundled 2), in the technique's own layers, weighted by the face's area as seen (the visibility map; derived: terrace geometry; the power plymeld's choice, not measured)"),
    LAYER_LINES("layer_lines", Goal.QUALITY, Better.LESS, "mm", "mean layer height over the part's non-flat faces, weighted by each face's area as seen (the visibility map): how coarse the layer lines on its sides are, vertical walls included (the stair-step misses them) — layer height as part of quality"),
    WEAKEST_BOND("bond.area", Goal.STRENGTH, Better.MORE, "mm²", "STAND-IN while no loads are marked: the smallest island cross-section with material above it, i.e. the thinnest bond between layers, the weak direction of a print; islands smaller than the outer wall's line width squared are not counted (they cannot be printed)"),
    TIME_ESTIMATE("time.estimate", Goal.SPEED, Better.LESS, "s", "quick estimate, per layer under the technique's own layers: walls and sparse infill of each group of parts at their speeds (or what the flow allows at the layer's height), at least the minimum layer time; top and bottom shells; a purge and the machine's own change time per filament change; plus support at the maximum flow (settings; for ranking, not the full prediction); not heating, homing, levelling, pauses, or travel"),
    SUPPORT_MASS("support.mass", Goal.MATERIAL, Better.LESS, "g", "quick estimate of support's plastic by mass: support.volume x the support filament's density (the part's own plastic is the same in every pose)"),
    SUPPORT_VOLUME("support.volume", Goal.MATERIAL, Better.LESS, "mm³", "quick estimate of support's plastic: each unsupported face's area x its height, the columns support would fill; in place of support.mass when the support filament has no density (the part's own plastic is the same in every pose)"),
    /** Measured only where the flat tops' surface technique is weighed: ironing makes it 0, as laid leaves the whole area. */
    ROUGH_TOP_AREA("rough_top_area", Goal.QUALITY, Better.LESS, "mm²", "flat tops above the first layer left as laid, their top lines not smoothed (0 where ironing is their surface technique): measured only where the flat tops' surface technique is weighed, so the surface technique counts under quality once, as rough area or as none, and under speed as ironing's pass — part of quality"),
    /** how tall and narrow the print stands: its height over the square root of its bed contact; the wobble stand-in (a tall print on a small foot shakes and may be knocked loose). */
    SLENDERNESS("slenderness", Goal.RELIABILITY, Better.LESS, "", "the print's height over the square root of its bed contact: how tall and narrow it stands, the stand-in for wobble and for being knocked loose"),
    /** the separate islands of the first layer (its cut at mid height): each is a part that can lift or be dragged on its own. */
    FIRST_LAYER_ISLANDS("first_layer_islands", Goal.RELIABILITY, Better.LESS, "", "how many separate islands the first layer is made of (the model cut at the first layer's middle): each lifts or is dragged loose on its own"),
    /**
     * The weakest bond between layers as laid: its area (bond.area) times the bond factor of the cooling and
     * nozzle temperature it is laid with (BondCooling.factor; its coefficients method.strength.bond.*, placeholders until
     * measured). Measured only in the bond-first cooling decision, where the same area is laid two ways.
     */
    BOND_STRENGTH("bond.strength", Goal.STRENGTH, Better.MORE, "mm²", "the weakest bond between layers as laid: its area (bond.area) times a bond factor for the part fan, the nozzle temperature, and how long the layer below cools (method.strength.bond.*, PLACEHOLDER coefficients until measured); measured where bond-first cooling is weighed"),
    /** what bond-first cooling gives up: the side area printed with less cooling than the filament asks, times the share given up. */
    COOLING_SHORTFALL("cooling_shortfall", Goal.QUALITY, Better.LESS, "mm²", "the object's side area (its non-flat faces) printed with less cooling than its filament asks, times the share of cooling given up (the larger of the part fan's and the minimum layer time's shortfall): 0 with the filament's own cooling; a stand-in for the sagging, curling, and blobs of walls laid too hot"),
    /** the model's flat horizontal faces are printed to the nearest layer top; how far they are moved, area-weighted (mm). */
    HEIGHT_ERROR("height_error", Goal.ACCURACY, Better.LESS, "mm", "how far the model's flat horizontal faces above the bed are moved to the nearest layer top, area-weighted mean (mm): a face between two layer tops is printed at one of them; the first accuracy measure"),
    /** measured on the print program as written (StringRisk), so only where a slice is made: the finalists, and reported by slice. */
    STRING_RISK("string_risk", Goal.QUALITY, Better.LESS, "mm", "the length of travels made without a retraction where a string would show: over open air between the layer's islands (a string hangs there), and across a top surface; a travel hidden inside an island under later layers counts nothing — part of quality; measured on the print program as written"),
    RESTART_STARVED("restart_starved", Goal.QUALITY, Better.LESS, "mm", "visible wall or surface printed starved after a restart: each outer wall, top or bottom surface started after a retraction counts the filament's restart lag (hardware.filament.retract.restart_lag, a placeholder until measured) — part of quality; measured on the print program as written"),
    /** measured on planned layers (SeamScar), so it reaches a decision only where a slice is made: the finalists. */
    SEAM_SCAR("seam_scar", Goal.QUALITY, Better.LESS, "mm²", "the blemish the seams of the outer walls leave, as seen: a point seam one line width squared, a scarf seam method.quality.seam.scarf_share of that whatever its length, a seam further than a line width from the nearest seam below its jog x the line width; on a shelf the layer below leaves exposed, a point seam a band as wide as the shelf and a scarf's ramp its length x the shelf beyond a line; each weighted by how much the face at the seam is seen (the visibility map: a seam on an edge stepping out over the layer below sits on an underside) — part of quality; measured on the planned layers, so only where a slice is made (the finalists' slices, and reported by slice)"),
    CURVED_BORDER("curved_border", Goal.QUALITY, Better.LESS, "mm²", "the line round a curved island's sides where its own layers start over the flat ones (a change of layers, seen as a line), as a blemish one outer line wide along it, each piece weighted by how much the side there is seen (the visibility map); on the seam scar's scale (the same kind of blemish: a line on the side) — part of quality; the form (one line wide) is a placeholder, not measured"),
    ;
    enum class Better { LESS, MORE }
}

