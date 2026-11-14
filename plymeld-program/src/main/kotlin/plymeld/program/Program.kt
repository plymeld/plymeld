package plymeld.program

/**
 * The PrintProgram: an ordered list of steps that a
 * planner produces and a writer turns into a machine dialect. Layers are not part of the model;
 * a planar-layer planner marks them with [LayerStart] events, and a different planner (non-planar,
 * free-air strands) simply emits different steps.
 *
 * Coordinates are µm on the geometry grid.
 */
sealed interface Step

/**
 * One extrusion along a polyline at height [zUm]. [ePerMmOverride]: filament per mm when the bead model
 * does not apply (ironing). [overhangShare]: per-edge overhang share of a wall loop in thousandths (the
 * writer grades the speed from it), or null.
 *
 * A curved layer, and a flat layer's line lowered onto a sloped top (Z anti-aliasing), give
 * each vertex its own Z ([zs]) and layer thickness ([heights]); [zUm] is then the path's highest Z (so a lift above it
 * clears the whole path) and [heightUm] its thickest (so a speed capped for it holds everywhere), as [widthUm] is the widest
 * of [widths]. With [ePerMmOverride] the thicknesses do not set the plastic.
 */
class Extrude(
    val feature: String,
    val xy: LongArray,
    val closed: Boolean,
    val zUm: Long,
    val widthUm: Long,
    val heightUm: Long,
    val tool: Int = 0,
    val overhangShare: IntArray? = null,
    val ePerMmOverride: Double? = null,
    /** Retract (and lift) for the travel into this path; false = the planner found that travel hidden inside the part, or inside its island crossing no wall. */
    val retractBefore: Boolean = true,
    /** Line width at each vertex (variable-width walls); null = [widthUm] throughout. A segment takes the mean of its ends. */
    val widths: LongArray? = null,
    /**
     * Printed exactly as given (a calibration plate); null = the writer's own rules. An exact path
     * may carry [zs] and [heights] too (the seam plate prints a scarf seam's ramp, Scarf.extrudes, as specified).
     */
    val exact: Exact? = null,
    /** Z at each vertex (µm), a curved layer; null = [zUm] throughout. */
    val zs: LongArray? = null,
    /** Layer thickness at each vertex (µm), a curved layer; null = [heightUm] throughout. A segment takes the mean of its ends. */
    val heights: LongArray? = null,
    /** Points (x, y, x, y ... µm) the travel INTO this path goes through, a detour around the layer's other islands (TravelRouter); null = straight. */
    val via: LongArray? = null,
    /**
     * Each segment's own print speed (mm/s), segment k from vertex k; NaN = the usual speed. An arched lifted bridge's layers
     *: its slow speed only where a segment is over air, ramping up layer by layer. The melt cap
     * still applies. null = the usual speeds throughout.
     */
    val segmentSpeeds: DoubleArray? = null,
    /**
     * The travel into this path lifts over what it crosses but does not retract: an outer-wall speck
     * reached from its own wall, whose last hop crosses that wall; a retraction and prime for a dot left a blob.
     */
    val hopOnly: Boolean = false,
    /**
     * Each segment's fastest speed (mm/s), segment k from vertex k, over its own: a region's cap
     * (plan.mechanism.motion.speed.max, MotionRegions); NaN = none. null = no segment has one.
     */
    val speedMax: DoubleArray? = null,
    /** Each segment's most acceleration (mm/s²), a region's cap (plan.mechanism.motion.acceleration.max); NaN = none; null = no segment has one. */
    val accelMax: DoubleArray? = null,
    /**
     * A run of a closed wall loop laid as several open paths (a scarf seam's ramp, the rest of the loop, its closing pass:
     * Scarf.extrudes): its material on its left, as a closed loop's, for where the writer restores and wipes.
     */
    val ofLoop: Boolean = false,
    /** A scarf seam's ramp or its closing pass: laid at the scarf's own speed and flow (plan.technique.scarf_seam.speed, .flow). */
    val scarf: Boolean = false,
) : Step {
    init {
        val m = xy.size / 2
        require(segmentSpeeds == null || segmentSpeeds.size == (if (closed) m else m - 1)) { "segmentSpeeds has ${segmentSpeeds!!.size} values for ${if (closed) m else m - 1} segments" }
        require(speedMax == null || speedMax.size == (if (closed) m else m - 1)) { "speedMax has ${speedMax!!.size} values for ${if (closed) m else m - 1} segments" }
        require(accelMax == null || accelMax.size == (if (closed) m else m - 1)) { "accelMax has ${accelMax!!.size} values for ${if (closed) m else m - 1} segments" }
        require(zs == null || zs.size == m) { "zs has ${zs!!.size} values for $m vertices" }
        require(heights == null || heights.size == m) { "heights has ${heights!!.size} values for $m vertices" }
        require(zs == null || zs.max() == zUm) { "zUm must be the highest of zs (lifts clear the whole path)" }
        require(heights == null || heights.max() == heightUm) { "heightUm must be the thickest of heights (speed caps)" }
    }

    /**
     * Whether the travel into this path, [straightMm] long as the crow flies, is taken as a speck's hop ([hopOnly]): lifted, not
     * retracted. Only a hop: combed inside the island up to its last step ([via]), or no longer than two of the path's widths;
     * any other travel to a speck (a straight one along the wall to the next speck, 12.5 mm on a test model) retracts as usual.
     */
    fun hops(straightMm: Double): Boolean = hopOnly && (via != null || straightMm <= HOP_WIDTHS * widthUm / 1000.0)

    /** Whether any vertex leaves the path's single Z or thickness: a curved layer's path. */
    val curved: Boolean get() = zs != null || heights != null

    /** The same path with the travel into it going through [via] (null = straight). */
    fun withVia(via: LongArray?) = Extrude(feature, xy, closed, zUm, widthUm, heightUm, tool, overhangShare, ePerMmOverride, retractBefore, widths, exact, zs, heights, via, segmentSpeeds, hopOnly, speedMax, accelMax, ofLoop, scarf)
    /** The same path with [hopOnly] set: false where the travel into it is not a hop after all (routed along the wall, TravelRouter.alongWall). */
    fun withHopOnly(hop: Boolean) = Extrude(feature, xy, closed, zUm, widthUm, heightUm, tool, overhangShare, ePerMmOverride, retractBefore, widths, exact, zs, heights, via, segmentSpeeds, hop, speedMax, accelMax, ofLoop, scarf)
    /** The same path with [retractBefore] set (the retraction decision clearing the planner's hidden travels). */
    fun withRetract(retract: Boolean) = Extrude(feature, xy, closed, zUm, widthUm, heightUm, tool, overhangShare, ePerMmOverride, retract, widths, exact, zs, heights, via, segmentSpeeds, hopOnly, speedMax, accelMax, ofLoop, scarf)
    fun withSegmentSpeeds(speeds: DoubleArray?) = Extrude(feature, xy, closed, zUm, widthUm, heightUm, tool, overhangShare, ePerMmOverride, retractBefore, widths, exact, zs, heights, via, speeds, hopOnly, speedMax, accelMax, ofLoop, scarf)
}

/** A speck's hop ([Extrude.hops]) reaches at most this many of its line widths when not combed. */
const val HOP_WIDTHS = 2.0

/**
 * A path's values when it prints exactly as specified: no minimum-layer-time slow-down, no melt-rate
 * cap, no overhang or bridge handling, no feature speeds. [label]: the cell it belongs to (written to
 * the g-code, so the plate's read-back check finds it). [speeds]: mm/s, one per segment, or one for
 * the whole path. [accel]: mm/s². [flowRatio]: the extrusion multiplier on the bead model. [retractMm]:
 * the retraction for the travel into this path.
 */
class Exact(val label: String, val speeds: DoubleArray, val accel: Double, val flowRatio: Double, val retractMm: Double) {
    fun speed(segment: Int): Double = if (speeds.size == 1) speeds[0] else speeds[segment]
}

/** Markers the planner places; the writer decides what each becomes in its dialect. */
sealed interface Event : Step

/**
 * A planar layer begins: progress markers, layer-change template, per-layer policies (fan,
 * cooling). [index] counts layers across the whole job (progress); [objectLayer] counts within
 * the object being printed, so first-layer rules apply to each object when objects are printed
 * one after another.
 */
class LayerStart(val index: Int, val zUm: Long, val heightUm: Long, val objectLayer: Int = index,
                 /**
                  * A layer of an ARCHED lifted bridge (above what holds it up; its first layer arching above or below straight
                  * between its supports): the writer prints it at the bridge's own speeds, and its object at the bridge's
                  * temperature, when set.
                  */
                 val archedBridge: Boolean = false,
                 /**
                  * An arched lifted bridge's top layer printed as usual (plan.technique.bridge.top_layer = normal): not [archedBridge], and at
                  * the print temperature, waited for, although its object is at the bridge's temperature after it.
                  */
                 val printTemp: Boolean = false,
                 /**
                  * A curved island's layer: the island's key. The writer marks
                  * the layer with it; the pre-print check reads the layer in that island's warp (a flat layer marked so lies off the
                  * island's surface, an island layer unmarked off its flat height: either is refused).
                  */
                 val island: String? = null) : Event {
    companion object {
        /** The comment line, after the layer change, that marks a curved island's layer: this, then the island's key. */
        const val ISLAND_MARK = "; CURVED_ISLAND: "
    }
}

/** Before the next move, rise to at least [zUm] (clearing finished objects when moving between them). */
class LiftTo(val zUm: Long) : Step

/** Printing of an object begins / ends here (Klipper exclude_object, per-object progress). */
class ObjectStart(val name: String) : Event
class ObjectEnd(val name: String) : Event

/**
 * From here on, extrusions use [tool] with [spool] loaded in it (a tool changer picks up another
 * nozzle; the writer emits the machine's own sequence, made over the start of the next extrusion after the travel there,
 * and purging is planned separately).
 * [tempC]: the new tool's printing temperature, waited for; [fromTool] / [fromIdleC]: the tool put
 * down and the temperature it idles at (null = left as it is).
 */
class ToolChange(val tool: Int, val spool: String, val tempC: Int? = null, val fromTool: Int? = null, val fromIdleC: Int? = null) : Event

/**
 * The print stops here for the user (swap a filament, drop in a magnet ...); the writer emits the
 * machine's own pause command. Placed right after a [LayerStart], before anything on that layer
 * prints (as Flash Studio places a project's pause). [reason]: what asked for it, for the g-code comment.
 */
class Pause(val reason: String) : Event

/**
 * From here on [tool] holds [toSpool] (filament number [toId]) in place of [fromSpool] (filament [fromId]): a filament the
 * user swaps by hand at a pause. plymeld writes no filament changer yet, so Pauses.insert requires a [Pause] between the old
 * spool's last line on the tool and this. The writer notes it for the file's filament accounting (the header and the
 * pre-print check read the note) and brings the new filament to its temperature ([tempC]; null when the tool change just
 * before it did).
 */
class FilamentSwap(val tool: Int, val fromSpool: String, val toSpool: String, val fromId: Int, val toId: Int, val tempC: Int? = null) : Event

/** The nozzle of [tool] to [tempC]; [wait] = hold until it is there (M109) rather than carry on (M104). */
class SetTemp(val tool: Int, val tempC: Int, val wait: Boolean) : Event

/** The part fan to [pct] % from here on. */
class SetFan(val pct: Double) : Event

/**
 * Lift clear (retracted, the tool's z-hop above the layer), move to [xUm], [yUm] and wait [dwellS]
 * seconds there (0 = no wait): a calibration plate's cooling pause, so a layer takes at least the
 * filament's minimum layer time without any of its speeds changing, and a place away from the print for
 * the temperature waits that follow.
 */
class Park(val xUm: Long, val yUm: Long, val dwellS: Double) : Step

/** A machine template run here ([gcode] in plymeld's template language, e.g. the machine's bed levelling), labeled [label] in the file. */
class Custom(val label: String, val gcode: String) : Step

/** An object on the plate: name, footprint polygon (mm, flat x y ...), center (mm). */
class PrintObject(val name: String, val footprint: DoubleArray, val centerX: Double, val centerY: Double)

/**
 * [exact]: the program prints exactly as specified (a calibration plate): every path carries its
 * [Exact] values, the fan and temperatures change only at [SetFan] / [SetTemp], and the writer applies
 * none of its own per-layer policies.
 */
/** [selfPrimed]: the program lays its own prime lines (a calibration plate's, per block): the writer adds none. */
class PrintProgram(val steps: List<Step>, val objects: List<PrintObject>, val exact: Boolean = false, val selfPrimed: Boolean = false) {
    val layerStarts: List<LayerStart> get() = steps.filterIsInstance<LayerStart>()
}
