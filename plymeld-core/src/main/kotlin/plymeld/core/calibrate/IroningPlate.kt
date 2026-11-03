package plymeld.core.calibrate

import plymeld.core.Feature
import plymeld.core.Ordering
import plymeld.core.Planner
import plymeld.core.ToolPath
import plymeld.geom.Booleans
import plymeld.geom.Hatch
import plymeld.geom.Linker
import plymeld.geom.Offset
import plymeld.geom.Polygon
import plymeld.program.Exact
import plymeld.program.Extrude
import plymeld.program.LayerStart
import plymeld.program.LiftTo
import plymeld.program.PrintObject
import plymeld.program.PrintProgram
import plymeld.program.SetFan
import plymeld.program.SetTemp
import plymeld.program.Step

/**
 * The ironing plate (one piece testing combinations of each setting throughout): ONE flat slab, a few layers on
 * a solid floor, whose top is divided into **zones**, each zone ironed with its own combination of the ironing values (flow,
 * line spacing, speed, angle, inset), printed exactly as specified. The zones are parts of one top surface: no walls
 * between them, the slab's top fill laid as one surface, then each zone's ironing pass over its own part of it.
 *
 * **The zone plan** ([IroningSpec.zones]): a **block** of zones per ironing speed; in each block a grid of flow (rows of the
 * plan) x spacing (columns of the plan), and over that grid the angle and the inset as two **Latin squares** (zone (r, c)
 * of block b takes angle (r + c + b) mod the angle count and inset (r + 2c + b) mod the inset count): with three values of
 * each, every angle and every inset appears once in each row and once in each column of the grid, so no value of one
 * setting is tied to a value of another, and every value of every setting is read in several zones (nine of 27 with the
 * defaults). Each block's first zone is its **control**: not ironed, the top as laid, for the comparison beside every speed.
 *
 * **The layout**: one piece. [IroningGeometry.rows] **slabs per speed** (block), front to back, each one row of zones
 * [IroningGeometry.zone] square, on a **base** of the first [IroningGeometry.baseLayers] layers that joins them, a groove
 * between neighboring slabs, the **legend** on the base behind the last slab, a **brim** round the base. A block's first
 * zone sits at the front-left of its front slab, the next behind it on the back slab, then the next column: so **both the
 * front and the back edge of every zone are on its slab's perimeter**, where its marks are cut and its inset is read;
 * between neighboring zones of a slab a [IroningGeometry.frame] strip of the top left as laid, so each zone is framed by
 * the control's surface. The print order runs the same way.
 *
 * **The marks** are notches cut into the slabs above the base, each value a count of the legend's one value for that
 * setting: on each zone's front edge its **flow** (square notches) then its **spacing** (V notches), on its back edge its
 * **inset** (round-bottomed notches) then its **angle** (slanted notches; none at 0 degrees); in each slab's left end its
 * **speed** (bar notches); a control has no label. The piece's front-left corner is chamfered (the origin). The **legend**
 * lists each mark's one value in raised glyphs ([StrokeFont]), e.g. `|=15 mm/s  #=5%  v=0.05 mm` and `u=0.1 mm  \=45°`.
 *
 * **Printed exactly as specified**: the first layer at the first layer's values and width, the layers between at the
 * frame's (the machine's print speed held to the filament's maximum flow), the top fill at a visible surface's speed and
 * acceleration (the condition recorded with the readings), each zone's pass at its own speed, flow, spacing, angle and inset,
 * no slow-down and no melt cap. An ironing line's filament per mm is the slicer's own ironing rule (Planner: flow x spacing
 * x layer height over the filament's section, times the flow ratio); the writer lays an exact path by the bead model times
 * the path's flow ratio, so each zone's [Exact.flowRatio] is set to give exactly that ([IroningSpec.exactFlowRatio]).
 * Lengths in µm, speeds mm/s, accelerations mm/s².
 */

/**
 * The ironing plate's layout (µm): spacing and marks only, no test value. The zone size, the brim, and the kinds of marks are
 * the plate's design; the gaps, the notches' sizes, and the legend's lettering are placeholders, not measured.
 */
object IroningGeometry {
    /** A zone: this square, twice a first-layer tile, so a zone's ironing has room to show its texture away from its edges. */
    const val zone = 2 * PlateGeometry.tileAbout
    /** Rows of zones per speed, each row its own slab (front and back): both a zone's front and back edges are on a perimeter, for its marks. */
    const val rows = 2
    /** The un-ironed top between neighboring zones (the top as laid): every zone framed by the control's surface. */
    const val frame = PlateGeometry.tileGap
    /** The groove between two slabs (one slab per speed), down to the base that joins them into one piece. */
    const val groove = PlateGeometry.tileGap
    /** The base's layers (the first included), under every slab, every groove, and the legend: what makes the plate one piece. */
    const val baseLayers = 2
    /** The brim round the base on the first layer: it holds the wide, thin piece flat on the plate. */
    const val brim = 3_000L
    /**
     * A zone's labels, cut into its slab's layers above the base, each from [labelFrom] past the zone's left end, a group of
     * notches per setting ([IroningSpec.Marks]), [labelPitch] apart, the groups [labelGroupGap] apart. On the front edge,
     * square notches [squareW] wide (the flow), then V notches [vW] wide at the edge (the spacing); on the back edge,
     * round-bottomed notches [roundW] wide (the inset), then slanted notches [squareW] wide along the edge, leaning
     * [notchIn] sideways to their bottom (the angle). All [notchIn] deep.
     */
    const val squareW = 1_000L; const val vW = 1_600L; const val roundW = 1_600L; const val notchIn = 1_500L
    const val labelPitch = 2_200L; const val labelGroupGap = 1_800L; const val labelFrom = 2_000L
    /** A slab's speed: bar notches in its left end, [barW] wide, [barIn] deep, from [barFrom] past its front. */
    const val barW = 800L; const val barIn = 3_000L; const val barFrom = 5_000L
    /**
     * The most notches of one kind a value is marked with: a value is marked as a count of the legend's one value (its
     * values' common unit) when every value needs no more than this many; otherwise by its place in the plan. Four squares
     * and four Vs still fit a zone's edge (19.6 of its 20 mm; [IroningSpec] checks a plan's longest labels fit).
     */
    const val maxMarks = 4
    /** The chamfer at the piece's front-left corner (the origin mark): on the base and on the front slab. */
    const val chamfer = PlateGeometry.chamfer
    /** The plate's layers (the first included): the base's two, then the slabs and the legend; 4 = 0.8 mm at 0.2 mm layers. */
    const val layers = 4
    /**
     * The legend on the base behind the slabs, raised as the slabs are: each mark's symbol = its one value, as many to a line
     * as fit the slabs' width, [legendSep] between them; glyphs [legendH] tall in [legendStroke] strokes ([StrokeFont]: two
     * lines of a 0.4 mm nozzle), lines [legendGap] apart, [legendFrom] behind the last slab.
     */
    const val legendH = 5_000L; const val legendStroke = 800L; const val legendGap = 2_500L; const val legendFrom = 3_000L
    const val legendSep = "  "
    /** Margin of the base past the slabs and the legend (left, right, and back). */
    const val margin = 2_000L
}

/** One zone of the plan: its number [index] (0-based; "zone 1" in the labels), its block (speed), and its values by index; a [control] is not ironed. */
class IroningZone(val index: Int, val block: Int, val control: Boolean, val flowIdx: Int, val spacingIdx: Int, val angleIdx: Int, val insetIdx: Int)

class IroningSpec(
    /** The program's tool (0-based) and its spool's name. */
    val tool: Int,
    val spool: String,
    /** The ironing values spread over the zones: speeds (mm/s, a block each), flows (shares of a full line), spacings (µm), angles (degrees), insets (µm). */
    val speeds: DoubleArray, val flows: DoubleArray, val spacingsUm: LongArray, val anglesDeg: DoubleArray, val insetsUm: LongArray,
    /** The slab's layer height (µm), its first layer's, and its layers (the first included). */
    val layerUm: Long, val firstLayerUm: Long, val layers: Int,
    /** The slab's line width (µm), the first layer's, and the nozzle bore (an ironing line is declared that wide). */
    val widthUm: Long, val firstWidthUm: Long, val nozzleUm: Long,
    val filamentDiameterMm: Double,
    /** The flow ratio every line prints at (the ironing lines' plastic is scaled by it too, as the slicer's). */
    val flowRatio: Double,
    /** The plate's one nozzle temperature (the filament's print temperature) and its part fan from the second layer (the first layer's is off). */
    val tempC: Int, val fanPct: Double,
    /** The first layer's values; the layers between it and the top (the frame: the machine's values held to the maximum flow); the top fill's (a visible surface's). */
    val adhesion: PathValues, val frame: PathValues, val top: PathValues,
    /** The ironing passes' acceleration (a visible line's) and the retraction before each pass (mm). */
    val ironAccel: Double, val retractMm: Double,
    /** How far the nozzle rises off the top and off each zone before it travels on (µm, the travel lift). */
    val liftUm: Long,
    /** Where the slab's front-left corner goes on the bed (µm). */
    val originXUm: Long = 0,
    val originYUm: Long = 0,
    /** The machine's bed levelling, run after its start template; null = none. */
    val levelGcode: String? = null,
) {
    init {
        require(speeds.isNotEmpty() && flows.isNotEmpty() && spacingsUm.isNotEmpty() && anglesDeg.isNotEmpty() && insetsUm.isNotEmpty()) { "the ironing plate needs at least one value of each setting" }
        require(speeds.all { it > 0 }) { "ironing speeds are above 0 mm/s: ${speeds.joinToString()}" }
        require(flows.all { it > 0 }) { "ironing flows are above 0: ${flows.joinToString()}" }
        require(spacingsUm.all { it > 0 }) { "ironing spacings are above 0: ${spacingsUm.joinToString()}" }
        require(insetsUm.all { it >= 0 }) { "ironing insets are 0 or more: ${insetsUm.joinToString()}" }
        require(layers >= 2) { "the slab needs a first layer and a top (got $layers layers)" }
        require(spacingsUm.all { it < IroningGeometry.zone / 4 }) { "an ironing spacing of ${spacingsUm.max() / 1000.0} mm leaves too few lines in a ${IroningGeometry.zone / 1000.0} mm zone" }
        require(insetsUm.all { nozzleUm / 2 + it < IroningGeometry.zone / 3 }) { "an inset of ${insetsUm.max() / 1000.0} mm (with half the bore) eats most of a ${IroningGeometry.zone / 1000.0} mm zone" }
        val front = labelEnd(flowMarks.counts.max(), IroningGeometry.squareW, spacingMarks.counts.max(), IroningGeometry.vW)
        require(front <= IroningGeometry.zone) { "a zone's front label (${flowMarks.counts.max()} square and ${spacingMarks.counts.max()} V notches) is ${front / 1000.0} mm long, longer than a ${IroningGeometry.zone / 1000.0} mm zone's edge: fewer flows or spacings" }
        val back = labelEnd(insetMarks.counts.max(), IroningGeometry.roundW, angleMarks.counts.max(), IroningGeometry.squareW + IroningGeometry.notchIn)
        require(back <= IroningGeometry.zone) { "a zone's back label (${insetMarks.counts.max()} round and ${angleMarks.counts.max()} slanted notches) is ${back / 1000.0} mm long, longer than a ${IroningGeometry.zone / 1000.0} mm zone's edge: fewer insets or angles" }
    }

    /** Zones in a block: the control and the flow x spacing grid. */
    val perBlock: Int get() = 1 + flows.size * spacingsUm.size
    /** The zones in print order (zone 1 first): a block per speed, each its control then its grid row by row. */
    val zones: List<IroningZone> = buildList {
        for (b in speeds.indices) {
            add(IroningZone(size, b, true, -1, -1, -1, -1))
            for (r in flows.indices) for (c in spacingsUm.indices)
                add(IroningZone(size, b, false, r, c, (r + c + b) % anglesDeg.size, (r + 2 * c + b) % insetsUm.size))
        }
    }
    /** Columns of zones on each slab (a block's zones over [IroningGeometry.rows] slabs, one zone to each slab per column). */
    val columns: Int get() = (perBlock + IroningGeometry.rows - 1) / IroningGeometry.rows
    /** A slab's size (µm): one row of zones and the un-ironed frames between them. */
    val slabW: Long get() = columns * IroningGeometry.zone + (columns - 1) * IroningGeometry.frame
    val slabD: Long get() = IroningGeometry.zone
    /** The slabs ([IroningGeometry.rows] per speed, the first at the front), each slab's block, its front edge (µm from the piece's front). */
    val slabs: Int get() = speeds.size * IroningGeometry.rows
    fun blockOf(s: Int): Int = s / IroningGeometry.rows
    fun slabY(s: Int): Long = s * (slabD + IroningGeometry.groove)
    /**
     * How a setting's values are marked: a [unit] per mark and each value's count of marks (its value over the unit), so a
     * zone's notches read as a multiple of the one value the legend gives; or, where the values share no unit small enough
     * for a few notches each (more than [IroningGeometry.maxMarks]), the value's place in the plan (1, 2, 3 ...) and the
     * legend lists every count's value ([unit] null).
     */
    class Marks(val unit: Double?, val counts: IntArray)

    /**
     * [Marks] for [values] counted in [step]s (the setting's resolution): their greatest common step, if each needs few
     * marks. [zero]: a value of 0 is marked by no notches (an angle of 0 degrees), the setting's other group on the same edge
     * showing the label is there.
     */
    private fun marksOf(values: DoubleArray, step: Double, zero: Boolean = false): Marks {
        val q = values.map { Math.round(it / step) }
        fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)
        val g = q.fold(0L) { acc, v -> gcd(acc, v) }
        val counts = q.map { if (g > 0) (it / g).toInt() else 0 }
        return if (g > 0 && counts.all { it in (if (zero) 0 else 1)..IroningGeometry.maxMarks }) Marks(g * step, counts.toIntArray())
            else Marks(null, IntArray(values.size) { it + 1 })
    }
    /** The marks of the speeds (bars, per slab), the flows (square notches), the spacings (V notches), the insets (round-bottomed notches), and the angles (slanted notches). */
    val speedMarks: Marks get() = marksOf(speeds, 0.1)
    val flowMarks: Marks get() = marksOf(flows.map { it * 100 }.toDoubleArray(), 0.1)
    val spacingMarks: Marks get() = marksOf(spacingsUm.map { it.toDouble() }.toDoubleArray(), 1.0)
    val insetMarks: Marks get() = marksOf(insetsUm.map { it.toDouble() }.toDoubleArray(), 1.0, zero = true)
    val angleMarks: Marks get() = marksOf(anglesDeg, 1.0, zero = true)

    /**
     * A label's length along its zone's edge (µm, from the zone's left end): [a] notches [aW] wide, then [b] notches [bW]
     * wide along the edge (a group of none takes no room).
     */
    fun labelEnd(a: Int, aW: Long, b: Int, bW: Long): Long {
        val g = IroningGeometry
        fun group(n: Int, w: Long) = if (n == 0) 0L else (n - 1) * g.labelPitch + w
        return g.labelFrom + group(a, aW) + (if (a > 0 && b > 0) g.labelGroupGap else 0L) + group(b, bW)
    }

    /** The legend's entries: each mark's symbol = its value (a count of them is that multiple), or every count's value where the values share no unit. */
    val legendEntries: List<String> get() {
        fun entry(sym: String, m: Marks, text: (Int) -> String, unitText: (Double) -> String) =
            if (m.unit != null) "$sym=${unitText(m.unit)}" else m.counts.indices.joinToString(" ") { sym.repeat(m.counts[it]) + "=" + text(it) }
        return listOf(
            entry("|", speedMarks, { n(speeds[it], 1) + " mm/s" }) { n(it, 1) + " mm/s" },
            entry("#", flowMarks, { n(flows[it] * 100, 1) + "%" }) { n(it, 1) + "%" },
            entry("v", spacingMarks, { n(spacingsUm[it] / 1000.0, 3) + " mm" }) { n(it / 1000.0, 3) + " mm" },
            entry("u", insetMarks, { n(insetsUm[it] / 1000.0, 3) + " mm" }) { n(it / 1000.0, 3) + " mm" },
            entry("\\", angleMarks, { n(anglesDeg[it], 1) + "°" }) { n(it, 1) + "°" })
    }
    /** The legend's lines, front to back as printed: its entries in order, as many to a line as fit the slabs' width (an entry wider than that alone on its line). */
    val legend: List<String> get() = buildList {
        var cur = ""
        for (e in legendEntries) {
            val joined = if (cur.isEmpty()) e else cur + IroningGeometry.legendSep + e
            if (cur.isEmpty() || StrokeFont.width(joined, IroningGeometry.legendH) <= slabW) cur = joined else { add(cur); cur = e }
        }
        if (cur.isNotEmpty()) add(cur)
    }
    /** The legend's front edge (µm from the piece's front), its size. */
    val legendY: Long get() = slabY(slabs - 1) + slabD + IroningGeometry.legendFrom
    val legendW: Long get() = legend.maxOf { StrokeFont.width(it, IroningGeometry.legendH) } + IroningGeometry.legendStroke
    val legendD: Long get() = legend.size * IroningGeometry.legendH + (legend.size - 1) * IroningGeometry.legendGap
    /** The piece (the base: every slab, groove, and the legend), µm, before it is placed; the brim lies outside it. */
    val pieceW: Long get() = maxOf(slabW, legendW) + IroningGeometry.margin
    val pieceD: Long get() = legendY + legendD + IroningGeometry.margin
    /** Zone [k]'s column (from the left) and its row in its block (0 the front), and its slab. */
    fun col(k: Int) = (k - zones[k].block * perBlock) / IroningGeometry.rows
    fun row(k: Int) = (k - zones[k].block * perBlock) % IroningGeometry.rows
    fun slab(k: Int) = zones[k].block * IroningGeometry.rows + row(k)
    /** The zone at column [c] of slab [s], or null (an empty cell at the end of a slab). */
    fun zoneAt(s: Int, c: Int): IroningZone? =
        (c * IroningGeometry.rows + s % IroningGeometry.rows).takeIf { it < perBlock }?.let { zones.getOrNull(blockOf(s) * perBlock + it) }
    /** Zone [k]'s cell on the piece (µm, before the plate is placed: x0, y0, x1, y1). */
    fun zoneBox(k: Int): LongArray {
        val x0 = col(k) * (IroningGeometry.zone + IroningGeometry.frame); val y0 = slabY(slab(k))
        return longArrayOf(x0, y0, x0 + IroningGeometry.zone, y0 + IroningGeometry.zone)
    }
    /** Slab [s]'s box on the piece (µm: x0, y0, x1, y1). */
    fun slabBox(s: Int): LongArray = longArrayOf(0, slabY(s), slabW, slabY(s) + slabD)
    /** The cell labels: zone [k]'s ironing pass ("zone 1"), the slab's layers ("adhesion:slab" on the first, "slab 2", "slab 3" ... between (the layer's number), "top" the top fill). */
    fun zoneLabel(k: Int) = "zone ${k + 1}"
    fun slabLabel(layer: Int) = when { layer == 0 -> "adhesion:slab"; layer == layers - 1 -> "top"; else -> "slab ${layer + 1}" }
    /** The brim's cell label (the first layer's, laid before the base). */
    val brimLabel: String get() = "adhesion:brim"
    /** Layer [layer]'s top (µm): the first layer its own thickness, each layer above [layerUm] more. */
    fun zTop(layer: Int): Long = firstLayerUm + layer * layerUm
    fun heightOf(layer: Int): Long = if (layer == 0) firstLayerUm else layerUm
    /** The top layer's z (µm): where the ironing passes run. */
    val topZ: Long get() = zTop(layers - 1)
    val filamentMm2: Double get() = Math.PI * filamentDiameterMm * filamentDiameterMm / 4
    /** Bead cross-section of a [w] µm line [h] µm high, mm² (the writer's bead model). */
    fun beadMm2(w: Long, h: Long): Double { val hm = h / 1000.0; return hm * (w / 1000.0 - hm * (1 - Math.PI / 4)) }
    /**
     * An ironing line's filament per mm in zone [k] (mm of filament per mm of line): the slicer's ironing rule (Planner): the
     * zone's flow x its spacing x the layer height over the filament's section, times the flow ratio.
     */
    fun ironEPerMm(k: Int): Double { val z = zones[k]; return flows[z.flowIdx] * (spacingsUm[z.spacingIdx] / 1000.0) * (layerUm / 1000.0) / filamentMm2 * flowRatio }
    /** The flow ratio an exact path of a [nozzleUm] line [layerUm] high needs to lay [ironEPerMm] (the writer lays an exact path by the bead model times its flow ratio). */
    fun exactFlowRatio(k: Int): Double = ironEPerMm(k) * filamentMm2 / beadMm2(nozzleUm, layerUm)
    /** Zone [k]'s values in words (the page, the conditions block): "15 mm/s, flow 10 %, spacing 0.1 mm, angle 0 deg, inset 0.2 mm", or "the control (not ironed)". */
    fun describe(k: Int): String { val z = zones[k]
        return if (z.control) "control (not ironed; block ${z.block + 1}, ${n(speeds[z.block], 1)} mm/s)"
        else "${n(speeds[z.block], 1)} mm/s, flow ${n(flows[z.flowIdx] * 100, 1)} %, spacing ${n(spacingsUm[z.spacingIdx] / 1000.0, 3)} mm, angle ${n(anglesDeg[z.angleIdx], 1)} deg, inset ${n(insetsUm[z.insetIdx] / 1000.0, 3)} mm" }
    /** The zones (1-based) each value of each setting is read in: setting name -> value text -> zone numbers. */
    fun coverage(): Map<String, Map<String, List<Int>>> {
        val ironed = zones.filter { !it.control }
        fun by(name: String, count: Int, pick: (IroningZone) -> Int, text: (Int) -> String) = name to (0 until count).associate { i -> text(i) to ironed.filter { pick(it) == i }.map { it.index + 1 } }
        return mapOf(by("speed", speeds.size, { it.block }) { "${n(speeds[it], 1)} mm/s" }, by("flow", flows.size, { it.flowIdx }) { "${n(flows[it] * 100, 1)} %" },
            by("spacing", spacingsUm.size, { it.spacingIdx }) { "${n(spacingsUm[it] / 1000.0, 3)} mm" }, by("angle", anglesDeg.size, { it.angleIdx }) { "${n(anglesDeg[it], 1)} deg" },
            by("inset", insetsUm.size, { it.insetIdx }) { "${n(insetsUm[it] / 1000.0, 3)} mm" })
    }
    private fun n(v: Double, d: Int) = String.format(java.util.Locale.ROOT, "%.${d}f", v).let { if (d > 0) it.trimEnd('0').trimEnd('.') else it }
}

/**
 * Builds the ironing plate's program from its [spec]. [without]: for the read-back check's controls only, which must catch
 * them: "control" (each block's control zone ironed too, at the block's speed and the first flow, spacing, angle, and inset),
 * "labels" (no notches: no zone labels, no speed bars), "legend" (no legend), "brim" (no brim), "chamfer" (no chamfer),
 * "lift" (no rise off the top and off each zone before the
 * travel on), "order" (the zones in reverse order), "inset" (zone 2's pass 0.3 mm further in from the slab's edge than its
 * inset), "angle" (zone 2's lines at its angle plus 30 degrees), "spacing" (zone 2's lines at 1.5 x its spacing, the same
 * plastic per mm), "topspeed" (the top fill at the frame's speed, not a visible surface's), "topangle" (the top fill at 45
 * degrees, like the layer under it, not along the slab).
 */
class IroningPlate(private val spec: IroningSpec, private val without: Set<String> = emptySet()) {
    private val g = IroningGeometry

    /** Center-to-center distance of two touching [lw] µm lines [hUm] high (the bead model's). */
    fun spacingOf(lw: Long, hUm: Long): Long = Math.round(lw - hUm * (1 - Math.PI / 4))

    /** A rectangle (µm, counter-clockwise), its front-left corner cut by [ch]. */
    private fun rect(x0: Long, y0: Long, x1: Long, y1: Long, ch: Long = 0L): Polygon =
        if (ch > 0) Polygon(longArrayOf(x0 + ch, y0, x1, y0, x1, y1, x0, y1, x0, y0 + ch)) else Polygon(longArrayOf(x0, y0, x1, y0, x1, y1, x0, y1))

    private val chamfer: Long get() = if ("chamfer" in without) 0L else g.chamfer

    /** The base (the plate's first [IroningGeometry.baseLayers] layers): one piece under every slab, groove, and the legend, its front-left corner chamfered. */
    fun baseOutline(): Polygon = rect(0, 0, spec.pieceW, spec.pieceD, chamfer)

    /**
     * The notches cut into slab [s] above the base (µm): each zone's labels ([IroningGeometry.labelFrom]): on its front edge
     * square notches (its flow) then V notches (its spacing), on its back edge round-bottomed notches (its inset) then slanted
     * notches (its angle); a control has none. And the slab's speed as bar notches in its left end. Each notch reaches a
     * little past the edge so the cut is clean.
     */
    fun notches(s: Int): List<Polygon> {
        if ("labels" in without) return emptyList()
        val out = ArrayList<Polygon>()
        val sb = spec.slabBox(s); val past = 200L
        fun ccw(xy: LongArray) = Polygon(xy).let { if (it.isCcw()) it else it.reversed() }
        for (z in spec.zones) {
            val k = z.index
            if (z.control || spec.slab(k) != s) continue
            val zb = spec.zoneBox(k)
            for (front in listOf(true, false)) {
                val fy = if (front) sb[1] else sb[3]; val into = if (front) 1L else -1L
                fun yIn(d: Long) = fy + into * d
                val a = if (front) spec.flowMarks.counts[z.flowIdx] else spec.insetMarks.counts[z.insetIdx]
                val b = if (front) spec.spacingMarks.counts[z.spacingIdx] else spec.angleMarks.counts[z.angleIdx]
                var x = zb[0] + g.labelFrom
                repeat(a) {
                    out += if (front) rect(x, minOf(yIn(-past), yIn(g.notchIn)), x + g.squareW, maxOf(yIn(-past), yIn(g.notchIn)))
                    else {
                        // straight sides, then a half circle the notch's width across at its bottom
                        val r = g.roundW / 2
                        val arc = (0..12).flatMap { i -> val t = Math.PI * i / 12
                            listOf(x + r + Math.round(r * Math.cos(t)), yIn(g.notchIn - r + Math.round(r * Math.sin(t)))) }
                        ccw((listOf(x, yIn(-past), x + g.roundW, yIn(-past)) + arc).toLongArray())
                    }
                    x += g.labelPitch
                }
                if (a > 0) x += g.labelGroupGap - g.labelPitch + if (front) g.squareW else g.roundW
                repeat(b) {
                    out += if (front) ccw(longArrayOf(x, yIn(-past), x + g.vW, yIn(-past), x + g.vW / 2, yIn(g.notchIn)))
                    // leaning as far sideways as it is deep, so it reads as a slant from above
                    else ccw(longArrayOf(x - past, yIn(-past), x + g.squareW - past, yIn(-past), x + g.squareW + g.notchIn, yIn(g.notchIn), x + g.notchIn, yIn(g.notchIn)))
                    x += g.labelPitch
                }
            }
        }
        repeat(spec.speedMarks.counts[spec.blockOf(s)]) { i -> val y = sb[1] + g.barFrom + i * g.labelPitch; out += rect(-past, y, g.barIn, y + g.barW) }
        return out
    }

    /** Slab [s] above the base (µm): its rectangle (the front slab's front-left corner chamfered) less its [notches]. */
    fun slabOutline(s: Int): List<Polygon> {
        val sb = spec.slabBox(s)
        val r = rect(sb[0], sb[1], sb[2], sb[3], if (s == 0) chamfer else 0L)
        val cut = notches(s)
        return if (cut.isEmpty()) listOf(r) else Booleans.difference(listOf(r), cut)
    }

    /** The legend (µm): its lines as raised glyphs ([StrokeFont]) on the base behind the slabs, the first line at the back. */
    fun legendOutline(): List<Polygon> {
        if ("legend" in without) return emptyList()
        val lines = spec.legend
        return lines.withIndex().flatMap { (i, text) ->
            val y = spec.legendY + (lines.size - 1 - i) * (g.legendH + g.legendGap)
            // half a stroke in from the base's left edge, so no glyph overhangs it
            StrokeFont.polygons(text, g.legendStroke / 2, y, g.legendH, g.legendStroke)
        }
    }

    /** The plate's outline on [layer] (µm): the base on the first [IroningGeometry.baseLayers], then every slab and the legend. */
    fun outlines(layer: Int): List<Polygon> =
        if (layer < g.baseLayers) listOf(baseOutline()) else (0 until spec.slabs).flatMap { slabOutline(it) } + legendOutline()

    /** The brim round the base on the first layer, in [lw] µm lines [hUm] high: rings out from the base, the nearest first, [IroningGeometry.brim] wide. */
    fun brimRings(lw: Long, hUm: Long): List<LongArray> {
        if ("brim" in without) return emptyList()
        val sp = spacingOf(lw, hUm)
        val n = maxOf(1, Math.round(g.brim.toDouble() / sp).toInt())
        return (0 until n).flatMap { i -> Offset.offset(listOf(baseOutline()), lw / 2 + i * sp).filter { it.size >= 3 }.map { it.xy } }
    }

    /** The slab's fill angle on [layer]: the top along the slab (0 degrees), the layers below alternating 45 and 135 so neighbors cross. */
    fun fillAngle(layer: Int): Double = if (layer == spec.layers - 1 && "topangle" !in without) 0.0 else if (layer % 2 == 0) 45.0 else 135.0

    /**
     * The plate on [layer] in [lw] µm lines [hUm] high: [PlateGeometry.frameWalls] rings round each outline (outermost first),
     * then the inside solid in lines one bead spacing apart at [fillAngle], laid as the slicer's monotonic surfaces are
     * (hatched on the shared grid, joined only to the adjacent line, broken where the fill forks at a notch, one sweep from
     * the rings' end).
     */
    fun slabPaths(layer: Int, lw: Long, hUm: Long): Pair<List<LongArray>, List<LongArray>> {
        val sp = spacingOf(lw, hUm)
        val rings = ArrayList<LongArray>()
        var cur = Offset.offset(outlines(layer), -lw / 2)
        var n = 0
        while (cur.isNotEmpty() && n < PlateGeometry.frameWalls) { for (r in cur) if (r.size >= 3) rings += r.xy; cur = Offset.offset(cur, -sp); n++ }
        if (rings.isEmpty() || cur.isEmpty()) return rings to emptyList()
        val (segs, idx) = Hatch.indexed(cur, fillAngle(layer), sp)
        if (segs.isEmpty()) return rings to emptyList()
        val chains = Linker.link(segs, idx, cur, Planner.ANCHOR_SPACINGS * sp, breakAtSplits = true, breakAtForks = true)
        val end = rings.last()
        return rings to Ordering.sweep(chains.map { ToolPath(Feature.TOP, it, false, lw, hUm) }, end[0], end[1]).map { it.xy }
    }

    /**
     * Zone [k]'s ironing region (µm, polygons): its slab's top (the slab's outline, notches and all) shrunk by half the bore
     * plus the zone's inset, within the zone's cell; on a side facing a neighbor (across the un-ironed frame) the region
     * stops half the bore short of the cell's edge, so the ironed lines end at the cell and the frame stays as laid. Empty
     * for a control.
     */
    fun ironRegion(k: Int): List<Polygon> {
        val z = spec.zones[k]
        if (z.control && "control" !in without) return emptyList()
        val insetIdx = if (z.control) 0 else z.insetIdx
        val d = spec.nozzleUm / 2 + spec.insetsUm[insetIdx] + if ("inset" in without && k == 1) 300L else 0L
        val slab = Offset.offset(slabOutline(spec.slab(k)), -d)
        val half = spec.nozzleUm / 2
        val b = spec.zoneBox(k); val far = 10 * g.zone
        val x0 = if (spec.col(k) == 0) b[0] - far else b[0] + half
        val x1 = if (spec.col(k) == spec.columns - 1) b[2] + far else b[2] - half
        // a slab is one row of zones: the front and back always its perimeter
        val y0 = b[1] - far; val y1 = b[3] + far
        return Booleans.intersection(slab, listOf(Polygon(longArrayOf(x0, y0, x1, y0, x1, y1, x0, y1))))
    }

    /** Zone [k]'s ironing lines (µm, open paths in print order): hatched at its angle and spacing over [ironRegion], joined as the slicer joins ironing, swept from the zone's front-left corner. */
    fun ironPaths(k: Int): List<LongArray> {
        val z = spec.zones[k]
        val region = ironRegion(k)
        if (region.isEmpty()) return emptyList()
        val spIdx = if (z.control) 0 else z.spacingIdx; val aIdx = if (z.control) 0 else z.angleIdx
        var sp = spec.spacingsUm[spIdx]; var angle = spec.anglesDeg[aIdx]
        if ("spacing" in without && k == 1) sp = sp * 3 / 2
        if ("angle" in without && k == 1) angle += 30.0
        val (segs, idx) = Hatch.indexed(region, angle, sp)
        if (segs.isEmpty()) return emptyList()
        val chains = Linker.link(segs, idx, region, 3 * sp, breakAtSplits = true)
        val b = spec.zoneBox(k)
        return Ordering.sweep(chains.map { ToolPath(Feature.IRONING, it, false, spec.nozzleUm, spec.layerUm) }, b[0], b[1]).map { it.xy }
    }

    /** The plate's extent before it is placed (µm: x0, y0, x1, y1), the brim included. */
    fun bounds(): LongArray { val br = if ("brim" in without) 0L else g.brim; return longArrayOf(-br, -br, spec.pieceW + br, spec.pieceD + br) }

    fun program(): PrintProgram {
        val steps = ArrayList<Step>()
        val ox = spec.originXUm; val oy = spec.originYUm
        spec.levelGcode?.let { steps += plymeld.program.Custom("bed levelling (hardware.machine.<key>.gcode.level)", it) }
        // the one temperature (the start template heats to it: this wait is a guard); the part fan off on the first layer
        steps += SetTemp(spec.tool, spec.tempC, wait = true)
        steps += SetFan(0.0)
        for (layer in 0 until spec.layers) {
            val z = spec.zTop(layer); val hz = spec.heightOf(layer)
            steps += LayerStart(layer, z, hz)
            if (layer == 1) steps += SetFan(spec.fanPct)
            val first = layer == 0; val top = layer == spec.layers - 1
            val pv = if (first) spec.adhesion else if (top && "topspeed" !in without) spec.top else spec.frame
            val lw = if (first) spec.firstWidthUm else spec.widthUm
            val ex = Exact(spec.slabLabel(layer), doubleArrayOf(pv.speed), pv.accel, pv.flowRatio, pv.retractMm)
            if (first) {
                val bx = Exact(spec.brimLabel, doubleArrayOf(pv.speed), pv.accel, pv.flowRatio, pv.retractMm)
                for (ring in brimRings(lw, hz)) steps += extrude("Brim", ring, true, z, bx, ox, oy, lw, hz)
            }
            val (rings, lines) = slabPaths(layer, lw, hz)
            for ((i, ring) in rings.withIndex()) steps += extrude(if (i == 0) "Outer wall" else "Inner wall", ring, true, z, ex, ox, oy, lw, hz)
            for (line in lines) steps += extrude(if (first) "Bottom surface" else if (top) "Top surface" else "Internal solid infill", line, false, z, ex, ox, oy, lw, hz)
        }
        // the ironing passes, zone by zone in order, over the finished top: the nozzle rises by the travel lift off the top and
        // off each zone before it travels on (the travel crosses finished visible lines)
        val tz = spec.topZ
        if ("lift" !in without) steps += LiftTo(tz + spec.liftUm)
        val order = if ("order" in without) spec.zones.indices.reversed() else spec.zones.indices
        for (k in order) {
            val paths = ironPaths(k)
            if (paths.isEmpty()) continue
            val ex = Exact(spec.zoneLabel(k), doubleArrayOf(spec.speeds[spec.zones[k].block]), spec.ironAccel, spec.exactFlowRatio(if (spec.zones[k].control) controlLike(k) else k), spec.retractMm)
            for (p in paths) steps += extrude("Ironing", p, false, tz, ex, ox, oy, spec.nozzleUm, spec.layerUm)
            if ("lift" !in without) steps += LiftTo(tz + spec.liftUm)
        }
        steps += LiftTo(tz + spec.liftUm)
        val bb = bounds().let { longArrayOf(it[0] + ox, it[1] + oy, it[2] + ox, it[3] + oy) }
        val fp = doubleArrayOf(bb[0] / 1000.0, bb[1] / 1000.0, bb[2] / 1000.0, bb[1] / 1000.0, bb[2] / 1000.0, bb[3] / 1000.0, bb[0] / 1000.0, bb[3] / 1000.0)
        return PrintProgram(steps, listOf(PrintObject("ironing_plate", fp, (bb[0] + bb[2]) / 2000.0, (bb[1] + bb[3]) / 2000.0)), exact = true)
    }

    /** For the control "control": the first ironed zone of the control zone [k]'s block, whose flow its pass takes (the first flow, spacing 1). */
    private fun controlLike(k: Int): Int = k + 1

    /** The plate's g-code: the machine's start and end, everything between exactly as specified. [pressureAdvance]: by tool. */
    fun gcode(s: plymeld.model.SliceSettings, foreignVars: Map<String, List<String>>, version: String, pressureAdvance: Map<Int, Double> = emptyMap()): String =
        java.io.StringWriter().also { plymeld.gcode.KlipperWriter(s, foreignVars, version, pressureAdvance = pressureAdvance).write(program(), it) }.toString()

    /** A path of the plate, placed on the bed. */
    private fun extrude(feature: String, xy: LongArray, closed: Boolean, zUm: Long, ex: Exact, ox: Long, oy: Long, widthUm: Long, heightUm: Long): Extrude {
        val moved = LongArray(xy.size) { i -> xy[i] + if (i % 2 == 0) ox else oy }
        return Extrude(feature, moved, closed, zUm, widthUm, heightUm, tool = spec.tool, exact = ex)
    }
}

/**
 * Edits that make the ironing plate's "lift" control able to fail (as [MeltControls] for the melt plate): the writer rises by
 * the travel lift on every retracting travel of its own, so a plate written without its lifts is lifted anyway. [text] with the
 * writer's rise into every zone taken out: each Z-only move after a zone's cell marker and before its first extrusion, and the
 * Z of a travel that rises as it goes.
 */
object IroningControls {
    fun withoutZoneLifts(text: String): String {
        val out = ArrayList<String>()
        var dropping = false
        for (l in text.lines()) {
            if (ZONE.matches(l)) { dropping = true; out += l; continue }
            if (dropping && EXTRUSION.matches(l)) dropping = false
            if (dropping && Z_ONLY.matches(l)) continue
            if (dropping && l.startsWith("G1 X")) { out += l.replace(Regex(" Z[0-9.]+"), ""); continue }
            out += l
        }
        return out.joinToString("\n")
    }
    private val ZONE = Regex("; CELL: zone \\d+")
    private val Z_ONLY = Regex("G1 Z[0-9.]+( F[0-9.]+)?")
    private val EXTRUSION = Regex("G1 X[-0-9.]+ Y[-0-9.]+ E[0-9.]+.*")
}
