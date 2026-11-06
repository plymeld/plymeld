package plymeld.core.calibrate

import plymeld.core.Feature
import plymeld.core.Ordering
import plymeld.core.Planner
import plymeld.core.Scarf
import plymeld.core.ToolPath
import plymeld.geom.Hatch
import plymeld.geom.Linker
import plymeld.geom.Offset
import plymeld.geom.Polygon
import plymeld.program.Exact
import plymeld.program.Extrude
import plymeld.program.LayerStart
import plymeld.program.LiftTo
import plymeld.program.Park
import plymeld.program.PrintObject
import plymeld.program.PrintProgram
import plymeld.program.SetFan
import plymeld.program.SetTemp
import plymeld.program.Step

/**
 * The seam plate: the seam
 * measure's shares, measured. `seam_scar` (SeamScar) counts a point seam on a smooth wall as one line width
 * squared, a scarf seam `method.quality.seam.scarf_share` of that, a point seam in a corner `method.quality.seam.corner_share`
 * of it, and a seam on an underside `method.quality.visibility.under` of a wall's (scaled by the lean) - all four
 * placeholders until now, and they decide where every seam goes. The plate prints the same seam each way, on one slab, and
 * the person grading says how visible each is; the ratios are the shares.
 *
 * **The pieces** ([SeamPiece.STANDARD]): hollow prisms of two walls, [SeamGeometry.height] tall, standing in a row on a
 * slab, each with its outer wall's seam at one place through every layer (no jogs), the inner wall's seam at the back:
 *  1. a round piece, a point seam facing the viewer; 2. the same with a scarf seam;
 *  3. a square piece, a point seam in the middle of its front face; 4. the same with a scarf seam;
 *  5. a square piece, a point seam in its front-left corner; 6. the same with a scarf seam;
 *  7. a square piece whose front face leans out 30 degrees from vertical (an underside facing the viewer), a point seam in
 *     the middle of that face; 8. the same at 45 degrees.
 * Piece 3 is the reference a corner and an underside are read against, pieces 1 and 3 the references of their scarfs.
 * The scarf is plymeld's own ([Scarf.extrudes]: the ramp, the loop, the ramp again), at the config's scarf length and
 * starting thickness; a corner is a corner by the slicer's rule (plan.technique.corner_seam.corner_angle).
 *
 * **The marks, geometry not text**: the slab's front-left corner is chamfered (the origin; the chamfered edge is the
 * front, which faces the viewer); a tick is cut into the slab's front and back edges between each pair of pieces, every
 * [SeamGeometry.tickLongEvery]th long: count the ticks from the chamfer for the piece's number.
 *
 * **Printed exactly as specified**: the slab's first layer at the first layer's values and width, its layers above at the
 * frame's; every outer wall at a visible wall's speed and acceleration (the condition the seams are read under; no
 * overhang slow-down on the leaning faces: their step-out is a 30 or 45 degree overhang's), every inner wall at the
 * machine's print speed, both held to the filament's maximum flow; the slab's first layer at the filament's first-layer temperature, its layers
 * above heating to the pieces' (the filament's print temperature), waited for with the head parked in front of the slab,
 * the part fan off on the first layer and the filament's own from the second. Lengths in µm, speeds mm/s, accelerations mm/s².
 */

/** The seam plate's layout (µm). placeholders, not measured: spacing only, no test value. */
object SeamGeometry {
    /** A piece's width and depth (a round piece's diameter). */
    const val piece = 14_000L
    /** Between two pieces. */
    const val gap = 6_000L
    /** The slab around the pieces. */
    const val margin = 3_000L
    /** The pieces above the slab: a seam line this long to look at. */
    const val height = 10_000L
    /** The slab's layers (the first included): the ticks are cut in the layers above the first. */
    const val slabLayers = 4
    /** A tick in the slab's front and back edges between two pieces: the plates' tally notch ([PlateMarks]), every [tickLongEvery]th [tickLongIn] deep. */
    const val tickW = PlateMarks.TALLY_W; const val tickIn = PlateMarks.TALLY_IN; const val tickLongIn = PlateMarks.TALLY_LONG_IN; const val tickLongEvery = PlateMarks.TALLY_LONG_EVERY
    /** The chamfer at the slab's front-left corner: the plates' origin mark ([PlateMarks.CHAMFER]). */
    const val chamfer = PlateMarks.CHAMFER
    /** A round piece's wall: this many vertices (5 degrees each: 0.6 mm, finer than the scarf's ramp step). */
    const val roundVertices = 72
}

/** A piece's shape: round, square, or square with its front face leaning out [leanDeg] degrees from vertical (an underside). */
enum class SeamShape(val key: String, val leanDeg: Double) {
    ROUND("round", 0.0), SQUARE("square", 0.0), LEAN30("lean30", 30.0), LEAN45("lean45", 45.0);
    companion object { fun of(key: String) = entries.firstOrNull { it.key == key } }
}

/** Where the outer wall's seam is: the middle of the front face (a round piece's front-most point), or the front-left corner. */
enum class SeamAt(val key: String) { FACE("face"), CORNER("corner"); companion object { fun of(key: String) = entries.firstOrNull { it.key == key } } }

/** A point seam (the loop starts and ends at the seam) or plymeld's scarf seam ([Scarf]). */
enum class SeamStyle(val key: String) { POINT("point"), SCARF("scarf"); companion object { fun of(key: String) = entries.firstOrNull { it.key == key } } }

/** One piece of the plate: its number ([index], 0-based; "piece 1" in the labels), its shape, where its seam is, and how it is laid. */
class SeamPiece(val index: Int, val shape: SeamShape, val at: SeamAt, val style: SeamStyle) {
    /** The piece's kind in words, as the conditions block and the readings sheet name it: "square corner scarf". */
    val key: String get() = "${shape.key} ${at.key} ${style.key}"

    /** What the piece reads, in the page's words. */
    fun describe(): String {
        val where = when {
            shape == SeamShape.ROUND -> "a round wall, the seam at its front-most point"
            shape.leanDeg > 0 -> "a square piece whose front face leans out ${n(shape.leanDeg)} degrees from vertical (an underside facing you), the seam in the middle of that face"
            at == SeamAt.CORNER -> "a square piece, the seam in its front-left corner"
            else -> "a square piece, the seam in the middle of its front face"
        }
        return "${if (style == SeamStyle.SCARF) "a scarf seam" else "a point seam"} on $where"
    }

    companion object {
        /** The eight pieces, in print order (left to right from the chamfer). */
        val STANDARD: List<SeamPiece> = listOf(
            SeamPiece(0, SeamShape.ROUND, SeamAt.FACE, SeamStyle.POINT), SeamPiece(1, SeamShape.ROUND, SeamAt.FACE, SeamStyle.SCARF),
            SeamPiece(2, SeamShape.SQUARE, SeamAt.FACE, SeamStyle.POINT), SeamPiece(3, SeamShape.SQUARE, SeamAt.FACE, SeamStyle.SCARF),
            SeamPiece(4, SeamShape.SQUARE, SeamAt.CORNER, SeamStyle.POINT), SeamPiece(5, SeamShape.SQUARE, SeamAt.CORNER, SeamStyle.SCARF),
            SeamPiece(6, SeamShape.LEAN30, SeamAt.FACE, SeamStyle.POINT), SeamPiece(7, SeamShape.LEAN45, SeamAt.FACE, SeamStyle.POINT))

        /** A piece from its key ("square corner scarf") at [index], or null. */
        fun parse(index: Int, key: String): SeamPiece? {
            val w = key.trim().split(Regex("\\s+"))
            if (w.size != 3) return null
            return SeamPiece(index, SeamShape.of(w[0]) ?: return null, SeamAt.of(w[1]) ?: return null, SeamStyle.of(w[2]) ?: return null)
        }
        private fun n(v: Double) = String.format(java.util.Locale.ROOT, "%.1f", v).trimEnd('0').trimEnd('.')
    }
}

class SeamSpec(
    /** The program's tool (0-based) and its spool's name. */
    val tool: Int,
    val spool: String,
    val pieces: List<SeamPiece>,
    /** The layer height (µm) and the slab's first layer's. */
    val layerUm: Long, val firstLayerUm: Long,
    /** The outer wall's line width (µm), the inner wall's, the slab's first layer's. */
    val widthUm: Long, val innerWidthUm: Long, val firstWidthUm: Long,
    val filamentDiameterMm: Double,
    /** The flow ratio every line prints at. */
    val flowRatio: Double,
    /** The pieces' nozzle temperature (the filament's print temperature), the slab's first layer's (its first-layer temperature), and the part fan from the second layer (the first layer's is off). */
    val tempC: Int, val firstTempC: Int, val fanPct: Double,
    /** The slab's first layer's values; its layers above (the frame); the outer walls' (a visible wall's); the inner walls' (the machine's print speed). */
    val adhesion: PathValues, val frame: PathValues, val outer: PathValues, val inner: PathValues,
    /** The scarf seams' ramp length (µm, plan.technique.scarf_seam.length) and starting thickness (a share of the layer, .start). */
    val scarfUm: Long, val scarfStart: Double,
    /** A corner by the slicer's rule (degrees, plan.technique.corner_seam.corner_angle): for the page. */
    val cornerDeg: Double,
    /** How far the nozzle rises off the finished plate (µm, the travel lift). */
    val liftUm: Long,
    /** Where the slab's front-left corner goes on the bed (µm). */
    val originXUm: Long = 0,
    val originYUm: Long = 0,
    /** The machine's bed levelling, run after its start template; null = none. */
    val levelGcode: String? = null,
) {
    init {
        require(pieces.isNotEmpty()) { "the seam plate needs at least one piece" }
        require(pieces.map { it.index } == pieces.indices.toList()) { "the pieces are numbered in order" }
        require(layerUm > 0 && firstLayerUm > 0 && widthUm > 0 && innerWidthUm > 0 && firstWidthUm > 0) { "the layer heights and line widths are above 0" }
        require(scarfUm >= 2 * widthUm) { "a scarf seam's ramp of ${scarfUm / 1000.0} mm is under two line widths: no scarf (plan.technique.scarf_seam.length)" }
        require(scarfStart in 0.0..1.0) { "the scarf's starting thickness is a share of the layer, 0..1 (got $scarfStart)" }
        require(widthUm < SeamGeometry.piece / 4) { "a ${widthUm / 1000.0} mm wall leaves no room in a ${SeamGeometry.piece / 1000.0} mm piece" }
    }

    private val g = SeamGeometry

    /** The pieces' layers above the slab ([SeamGeometry.height] in whole layers, at least 2). */
    val pieceLayers: Int get() = maxOf(2, Math.round(g.height.toDouble() / layerUm).toInt())
    /** The plate's layers, the slab's included. */
    val layers: Int get() = g.slabLayers + pieceLayers
    /** The steepest lean on the plate (degrees) and how far (µm) a leaning face reaches forward over the pieces' height. */
    val maxLeanDeg: Double get() = pieces.maxOf { it.shape.leanDeg }
    val reachUm: Long get() = Math.round(Math.tan(Math.toRadians(maxLeanDeg)) * pieceLayers * layerUm)
    /** The slab's size (µm): the pieces in a row behind the leaning faces' reach. */
    val slabW: Long get() = 2 * g.margin + pieces.size * g.piece + (pieces.size - 1) * g.gap
    val slabD: Long get() = 2 * g.margin + g.piece + reachUm
    /** Piece [k]'s cell on the slab before the plate is placed (µm: x0, y0, x1, y1): its footprint on the slab's top. */
    fun cell(k: Int): LongArray { val x0 = g.margin + k * (g.piece + g.gap); val y0 = g.margin + reachUm; return longArrayOf(x0, y0, x0 + g.piece, y0 + g.piece) }
    /** The x (µm, before placing) of the tick between pieces [k] and [k]+1. */
    fun tickX(k: Int): Long = g.margin + (k + 1) * (g.piece + g.gap) - g.gap / 2
    fun tickIn(k: Int): Long = PlateMarks.depth(k)
    /** Layer [layer]'s top (µm): the slab's first layer its own thickness, each layer above [layerUm] more. */
    fun zTop(layer: Int): Long = firstLayerUm + layer * layerUm
    fun heightOf(layer: Int): Long = if (layer == 0) firstLayerUm else layerUm
    /** The slab's top (µm), where the pieces stand. */
    val slabTop: Long get() = zTop(g.slabLayers - 1)
    /** The plate's top (µm). */
    val topZ: Long get() = zTop(layers - 1)
    /** The cell labels: piece [k]'s outer wall ("piece 1"), its inner wall ("piece 1 inner"), the slab's layers ("adhesion:slab", "slab 2" ...). */
    fun outerLabel(k: Int) = "piece ${k + 1}"
    fun innerLabel(k: Int) = "piece ${k + 1} inner"
    fun slabLabel(layer: Int) = if (layer == 0) "adhesion:slab" else "slab ${layer + 1}"
    /** Which piece a cell label names (0-based) and whether it is the inner wall's, or null. */
    fun pieceOf(label: String): Pair<Int, Boolean>? = Regex("piece (\\d+)( inner)?").matchEntire(label)?.let { it.groupValues[1].toInt() - 1 to it.groupValues[2].isNotEmpty() }

    /**
     * Piece [k]'s front face on its layer [i] (0-based above the slab): the y (µm, before placing) of its footprint's front
     * edge. A leaning face steps out by its lean per layer, read at the layer's middle: y0 - tan(lean) x (i + 1/2) x the layer.
     */
    fun frontY(k: Int, i: Int): Long {
        val c = cell(k)
        val lean = pieces[k].shape.leanDeg
        return if (lean == 0.0) c[1] else c[1] - Math.round(Math.tan(Math.toRadians(lean)) * (i + 0.5) * layerUm)
    }

    /** Center-to-center distance (µm) of the outer and inner walls (the bead model's: the mean of their widths less the layer's rounding). */
    val wallSpacingUm: Long get() = Math.round((widthUm + innerWidthUm) / 2.0 - layerUm * (1 - Math.PI / 4))

    /**
     * Piece [k]'s wall loop on its layer [i] (µm, placed on the bed, counter-clockwise), its first vertex the seam: the
     * outer wall's at the piece's seam place, the inner wall's at the back (hidden). A round piece's loop has
     * [SeamGeometry.roundVertices] vertices; a square one its corners and the seam vertex.
     */
    fun loop(k: Int, i: Int, inner: Boolean): LongArray {
        val p = pieces[k]
        val c = cell(k)
        val d = widthUm / 2 + if (inner) wallSpacingUm else 0L
        val ox = originXUm; val oy = originYUm
        if (p.shape == SeamShape.ROUND) {
            val cx = (c[0] + c[2]) / 2 + ox; val cy = (c[1] + c[3]) / 2 + oy
            val r = g.piece / 2 - d
            val n = g.roundVertices
            // the seam at the front (angle -90) for the outer wall, at the back (90) for the inner; counter-clockwise from there
            val a0 = if (inner) Math.PI / 2 else -Math.PI / 2
            return LongArray(2 * n) { j -> val a = a0 + 2 * Math.PI * (j / 2) / n; if (j % 2 == 0) cx + Math.round(r * Math.cos(a)) else cy + Math.round(r * Math.sin(a)) }
        }
        val x0 = c[0] + d + ox; val x1 = c[2] - d + ox
        val yf = frontY(k, i) + d + oy; val yb = c[3] - d + oy
        val cx = (x0 + x1) / 2
        return when {
            inner -> longArrayOf(cx, yb, x0, yb, x0, yf, x1, yf, x1, yb)                 // from the back's middle, counter-clockwise
            p.at == SeamAt.CORNER -> longArrayOf(x0, yf, x1, yf, x1, yb, x0, yb)       // from the front-left corner
            else -> longArrayOf(cx, yf, x1, yf, x1, yb, x0, yb, x0, yf)                 // from the front's middle
        }
    }

    /** Piece [k]'s outer-wall seam on its layer [i] (µm, placed): the loop's first vertex. */
    fun seamPoint(k: Int, i: Int): LongArray = loop(k, i, false).let { longArrayOf(it[0], it[1]) }

    val filamentMm2: Double get() = Math.PI * filamentDiameterMm * filamentDiameterMm / 4
    /** Bead cross-section of a [w] µm line [h] µm high, mm² (the writer's bead model). */
    fun beadMm2(w: Long, h: Long): Double { val hm = h / 1000.0; return hm * (w / 1000.0 - hm * (1 - Math.PI / 4)) }

    /** Piece [k] in words for the conditions block and the page: "round face scarf: a scarf seam on a round wall, ...". */
    fun describe(k: Int): String = "${pieces[k].key}: ${pieces[k].describe()}"
}

/**
 * Builds the seam plate's program from its [spec]. [without]: for the read-back check's controls only, which must catch
 * them: "scarf" (piece 2 printed with a point seam, not its scarf), "point" (piece 1 scarfed, not its point seam), "seam"
 * (piece 3's seam at the back of the piece, not the middle of its front), "corner" (piece 5's seam in the middle of its
 * front face, not its corner), "lean" (piece 7 vertical, not leaning), "inner" (piece 3's inner wall seamed at the front,
 * under the outer wall's seam), "ticks" (no ticks), "chamfer" (no chamfer), "order" (the pieces in reverse order on every
 * layer), "outerfirst" (each piece's outer wall before its inner), "slabspeed" (the slab's upper layers at the outer wall's
 * speed, not the frame's), "firsttemp" (the slab's first layer at the pieces' temperature), "piecewait" (no wait for the
 * pieces' temperature before them).
 */
class SeamPlate(private val spec: SeamSpec, private val without: Set<String> = emptySet()) {
    private val g = SeamGeometry

    /** Center-to-center distance of two touching [lw] µm lines [hUm] high (the bead model's). */
    fun spacingOf(lw: Long, hUm: Long): Long = Math.round(lw - hUm * (1 - Math.PI / 4))

    /** The slab's outline on [layer] (µm, before placing, counter-clockwise): the rectangle, its front-left corner chamfered, ticks in its front and back edges above the first layer. */
    fun outline(layer: Int): Polygon {
        val w = spec.slabW; val d = spec.slabD
        val pts = ArrayList<Long>()
        fun p(x: Long, y: Long) { pts += x; pts += y }
        val ch = if ("chamfer" in without) 0L else g.chamfer
        val ticks = layer > 0 && "ticks" !in without
        val n = spec.pieces.size
        p(ch, 0)
        if (ticks) for (c in 0 until n - 1) { val x = spec.tickX(c); val t = spec.tickIn(c); p(x - g.tickW / 2, 0); p(x - g.tickW / 2, t); p(x + g.tickW / 2, t); p(x + g.tickW / 2, 0) }
        p(w, 0); p(w, d)
        if (ticks) for (c in n - 2 downTo 0) { val x = spec.tickX(c); val t = spec.tickIn(c); p(x + g.tickW / 2, d); p(x + g.tickW / 2, d - t); p(x - g.tickW / 2, d - t); p(x - g.tickW / 2, d) }
        p(0, d)
        if (ch > 0) p(0, ch) else p(0, 0)
        return Polygon(pts.toLongArray())
    }

    /** The slab's fill angle on [layer]: alternating 45 and 135 so neighbors cross. */
    fun fillAngle(layer: Int): Double = if (layer % 2 == 0) 45.0 else 135.0

    /**
     * The slab on [layer] in [lw] µm lines [hUm] high: [PlateGeometry.frameWalls] outline rings (outermost first), then the inside
     * solid in lines one bead spacing apart at [fillAngle], laid as the slicer's monotonic surfaces are.
     */
    fun slabPaths(layer: Int, lw: Long, hUm: Long): Pair<List<LongArray>, List<LongArray>> {
        val sp = spacingOf(lw, hUm)
        val rings = ArrayList<LongArray>()
        var cur = Offset.offset(listOf(outline(layer)), -lw / 2)
        var n = 0
        while (cur.isNotEmpty() && n < PlateGeometry.frameWalls) { for (r in cur) if (r.size >= 3) rings += r.xy; cur = Offset.offset(cur, -sp); n++ }
        if (rings.isEmpty() || cur.isEmpty()) return rings to emptyList()
        val (segs, idx) = Hatch.indexed(cur, fillAngle(layer), sp)
        if (segs.isEmpty()) return rings to emptyList()
        val chains = Linker.link(segs, idx, cur, Planner.ANCHOR_SPACINGS * sp, breakAtSplits = true, breakAtForks = true)
        val end = rings.last()
        return rings to Ordering.sweep(chains.map { ToolPath(Feature.SOLID, it, false, lw, hUm) }, end[0], end[1]).map { it.xy }
    }

    /** The plate's extent before it is placed (µm: x0, y0, x1, y1). */
    fun bounds(): LongArray = longArrayOf(0, 0, spec.slabW, spec.slabD)

    /** Where the head waits for the pieces' temperature: [PlateGeometry.parkFront] in front of the slab, at its middle (bed, µm). */
    fun parkUm(): Pair<Long, Long> = (spec.originXUm + spec.slabW / 2) to (spec.originYUm - PlateGeometry.parkFront)

    /** Whether piece [k] prints with a scarf seam (its style, or a control's swap). */
    fun scarfed(k: Int): Boolean = when {
        "scarf" in without && k == 1 -> false
        "point" in without && k == 0 -> true
        else -> spec.pieces[k].style == SeamStyle.SCARF
    }

    /** Piece [k]'s outer-wall loop on its layer [i], the controls applied ("seam", "corner", "lean"). */
    fun outerLoop(k: Int, i: Int): LongArray {
        if ("lean" in without && k == 6) return spec.loop(k, 0, false).let { l -> l.copyOf() }
        val l = spec.loop(k, i, false)
        if ("seam" in without && k == 2) {
            // the seam at the back's middle: the loop rotated to start there (the square's back edge is its third)
            val m = l.size / 2; val x0 = l[6]; val yb = l[7]; val x1 = l[4]
            val cx = (x0 + x1) / 2
            val out = ArrayList<Long>(); out += cx; out += yb
            // continue counter-clockwise: back-left, front-left, front-middle(seam), front-right, back-right
            out += x0; out += yb; out += l[8]; out += l[9]; out += l[0]; out += l[1]; out += l[2]; out += l[3]; out += x1; out += yb
            check(m == 5)
            return out.toLongArray()
        }
        if ("corner" in without && k == 4) {
            // the seam in the middle of the front face: as a face piece's loop
            val x0 = l[0]; val yf = l[1]; val x1 = l[2]; val yb = l[5]
            return longArrayOf((x0 + x1) / 2, yf, x1, yf, x1, yb, x0, yb, x0, yf)
        }
        return l
    }

    fun innerLoop(k: Int, i: Int): LongArray {
        val l = spec.loop(k, i, true)
        if ("inner" in without && k == 2) {
            // the inner seam at the front's middle, under the outer wall's: the loop rotated
            val cx = l[0]; val yb = l[1]; val x0 = l[2]; val yf = l[5]; val x1 = l[6]
            return longArrayOf(cx, yf, x1, yf, x1, yb, x0, yb, x0, yf)
        }
        return l
    }

    fun program(): PrintProgram {
        val steps = ArrayList<Step>()
        val ox = spec.originXUm; val oy = spec.originYUm
        spec.levelGcode?.let { steps += plymeld.program.Custom("bed levelling (hardware.machine.<key>.gcode.level)", it) }
        // the slab's first layer at the first-layer temperature ("firsttemp": at the pieces'), waited for; heating to the
        // pieces' from its second layer
        val firstC = if ("firsttemp" in without) spec.tempC else spec.firstTempC
        steps += SetTemp(spec.tool, firstC, wait = true)
        steps += SetFan(0.0)
        for (layer in 0 until g.slabLayers) {
            val z = spec.zTop(layer); val hz = spec.heightOf(layer)
            steps += LayerStart(layer, z, hz)
            if (layer == 1) { steps += SetFan(spec.fanPct); if (firstC != spec.tempC) steps += SetTemp(spec.tool, spec.tempC, wait = false) }
            val first = layer == 0
            val pv = if (first) spec.adhesion else if ("slabspeed" in without) spec.outer else spec.frame
            val lw = if (first) spec.firstWidthUm else spec.widthUm
            val ex = Exact(spec.slabLabel(layer), doubleArrayOf(pv.speed), pv.accel, pv.flowRatio, pv.retractMm)
            val (rings, lines) = slabPaths(layer, lw, hz)
            for ((i, ring) in rings.withIndex()) steps += placed(if (i == 0) "Outer wall" else "Inner wall", ring, true, z, ex, ox, oy, lw, hz)
            for (line in lines) steps += placed(if (first) "Bottom surface" else "Internal solid infill", line, false, z, ex, ox, oy, lw, hz)
        }
        // the pieces at their one temperature from their first line: where the slab's first layer was at another, lift, park in
        // front of the slab and wait for it ("piecewait": no wait)
        if (firstC != spec.tempC && "piecewait" !in without) {
            steps += LiftTo(spec.slabTop + spec.liftUm)
            val (px, py) = parkUm()
            steps += Park(px, py, 0.0)
            steps += SetTemp(spec.tool, spec.tempC, wait = true)
        }
        // the pieces, layer by layer, each piece its inner wall then its outer (the slicer's order), the outer's seam as specified
        val h = spec.layerUm
        for (i in 0 until spec.pieceLayers) {
            val z = spec.slabTop + (i + 1) * h
            steps += LayerStart(g.slabLayers + i, z, h)
            val order = if ("order" in without) spec.pieces.indices.reversed() else spec.pieces.indices.toList()
            for (k in order) {
                val exIn = Exact(spec.innerLabel(k), doubleArrayOf(spec.inner.speed), spec.inner.accel, spec.inner.flowRatio, spec.inner.retractMm)
                val exOut = Exact(spec.outerLabel(k), doubleArrayOf(spec.outer.speed), spec.outer.accel, spec.outer.flowRatio, spec.outer.retractMm)
                val innerStep = Extrude("Inner wall", innerLoop(k, i), true, z, spec.innerWidthUm, h, tool = spec.tool, exact = exIn)
                val tp = ToolPath(Feature.OUTER_WALL, outerLoop(k, i), true, spec.widthUm, h, scarfUm = if (scarfed(k)) spec.scarfUm else 0L, scarfStart = spec.scarfStart)
                val outerSteps = Scarf.extrudes(tp, z, spec.tool).map { e -> Extrude(e.feature, e.xy, e.closed, e.zUm, e.widthUm, e.heightUm, spec.tool, null, null, true, null, exOut, e.zs, e.heights) }
                if ("outerfirst" in without) { steps += outerSteps; steps += innerStep } else { steps += innerStep; steps += outerSteps }
            }
        }
        steps += LiftTo(spec.topZ + spec.liftUm)
        val bb = bounds().let { longArrayOf(it[0] + ox, it[1] + oy, it[2] + ox, it[3] + oy) }
        val fp = doubleArrayOf(bb[0] / 1000.0, bb[1] / 1000.0, bb[2] / 1000.0, bb[1] / 1000.0, bb[2] / 1000.0, bb[3] / 1000.0, bb[0] / 1000.0, bb[3] / 1000.0)
        return PrintProgram(steps, listOf(PrintObject("seam_plate", fp, (bb[0] + bb[2]) / 2000.0, (bb[1] + bb[3]) / 2000.0)), exact = true)
    }

    /** The plate's g-code: the machine's start and end, everything between exactly as specified. [pressureAdvance]: by tool. */
    fun gcode(s: plymeld.model.SliceSettings, foreignVars: Map<String, List<String>>, version: String, pressureAdvance: Map<Int, Double> = emptyMap()): String =
        java.io.StringWriter().also { plymeld.gcode.KlipperWriter(s, foreignVars, version, pressureAdvance = pressureAdvance).write(program(), it) }.toString()

    /** A path of the slab, placed on the bed. */
    private fun placed(feature: String, xy: LongArray, closed: Boolean, zUm: Long, ex: Exact, ox: Long, oy: Long, widthUm: Long, heightUm: Long): Extrude {
        val moved = LongArray(xy.size) { i -> xy[i] + if (i % 2 == 0) ox else oy }
        return Extrude(feature, moved, closed, zUm, widthUm, heightUm, tool = spec.tool, exact = ex)
    }
}
