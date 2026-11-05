package plymeld.core.calibrate

import plymeld.core.Feature
import plymeld.core.Ordering
import plymeld.core.Planner
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
 * The melt plate: the filament's maximum flow at each of several temperatures, read as a table printed in plastic. A
 * **column** per temperature (coolest on the left), a **row** per flow (lowest at the front, each the same step above the
 * one before): every row the same bead ([MeltSpec.widthUm] wide, [MeltSpec.readUm] thick, the tool's thickest layer), so
 * what changes from row to row is the flow alone, by the speed. A row is read where its lines stay one closed surface;
 * the first row with gaps, column by column, is where the filament stops melting fast enough at that temperature.
 *
 * Four layers: the base ([MeltGeometry.baseLayers], its front-left corner chamfered: the origin, [PlateMarks]), a solid
 * **floor** under each column, and the **read layer**: every row of every column at one height, the floor's top plus
 * [MeltSpec.readUm]. Each row holds its flow at least [MeltSpec.holdS] (a hot end lays more for a moment, on its stored heat,
 * than it keeps up), so a faster row has more lines; and at least as many as make it as deep as its label.
 *
 * **The marks** ([PlateMarks]): each row's flow ("8.5", mm³/s) printed once at the plate's left, beside the row; each
 * column's temperature ("210") printed in front of it. Rows stand [MeltGeometry.rowGap] apart, so each reads as its own
 * strip with the floor between them.
 *
 * Print order: column by column, coolest first (a nozzle heats faster than it cools). Before each column after the first
 * the head lifts clear, parks in front of the plate and waits for the column's temperature (M109); then the column's prime
 * line in front of it (not read: the nozzle oozes while it waits), then its rows from the front, lowest flow first.
 *
 * Lengths in µm, speeds mm/s, flows mm³/s, accelerations mm/s².
 */
object MeltGeometry {
    /** The base's layers (the first included): two, so the plate comes off the bed in one stiff piece. */
    const val baseLayers = 2
    /**
     * The base's first layer's line width, in nozzle diameters. Plymeld's choice: that layer prints at the first-layer
     * speed, well under the filament's flow, so wider lines cover the plate in fewer passes in less time, and a wide
     * first-layer line holds to the bed at least as well. Not measured.
     */
    const val baseWidthNozzles = 1.5
    /** The base past everything on it at the right and the back. Plymeld's choice. */
    const val margin = 3_000L
    /** A label to what it names. Plymeld's choice. */
    const val labelGap = 2_000L
    /** Between two columns. Plymeld's choice. */
    const val columnGap = 3_000L
    /** Between two rows: the floor shows there, so each row reads as its own strip. Plymeld's choice. */
    const val rowGap = 1_000L
    /** A column's prime line: in front of the column, along it, this far in front of its first row. */
    const val primeFront = 1_500L
    /** The head parks this far in front of the plate for each column's temperature (as the first-layer plate parks). */
    const val parkFront = 10_000L
    /** The longest a row's line is (µm): the old melt surface's; shorter where the columns would not fit the bed. */
    const val lineMax = 60_000L
    /** The shortest a row's line may be (µm) before the plate takes fewer temperatures instead. Plymeld's choice. */
    const val lineMin = 30_000L
}

class MeltSpec(
    /** The program's tool (0-based) and its spool's name. */
    val tool: Int,
    val spool: String,
    /** One column per temperature, in print order: coolest first. */
    val tempsC: IntArray,
    /** The rows' flows (mm³/s), front to back: low to high. */
    val flows: DoubleArray,
    /** The part fan (%) in the floor and read layers (the base's are off). */
    val fanPct: Double,
    /** The plate's layer height (µm; the base above its first layer, the floor) and its first layer's. */
    val layerUm: Long,
    val firstLayerUm: Long,
    /** The plate's ordinary line width (µm: every row's too) and the base's first layer's ([MeltGeometry.baseWidthNozzles]). */
    val widthUm: Long,
    val firstWidthUm: Long,
    /** Every row's thickness (µm): the tool's thickest layer. */
    val readUm: Long,
    /** A row's line length (µm). */
    val lineUm: Long,
    /** How long (s) a row holds its flow at least. */
    val holdS: Double,
    val filamentDiameterMm: Double,
    /** The flow ratio every line prints at. */
    val flowRatio: Double,
    /** The rows' acceleration (mm/s², the machine's). */
    val accel: Double,
    /** The first layer's values (the base's first layer). */
    val adhesion: PathValues,
    /** The frame's values: the base above its first layer, the floor, the labels, the prime lines (held to [frameCap]). */
    val frame: PathValues,
    /** The filament's expected maximum flow (mm³/s): the frame's lines are held to it. */
    val frameCap: Double,
    /** How far the head lifts before a travel (µm, the travel lift). */
    val liftUm: Long,
    /** Where the plate's front-left corner goes on the bed (µm). */
    val originXUm: Long = 0,
    val originYUm: Long = 0,
    /** The machine's bed levelling, run after its start template; null = none. */
    val levelGcode: String? = null,
) {
    init {
        require(tempsC.isNotEmpty()) { "a melt plate needs at least one temperature" }
        require(tempsC.toList().zipWithNext().all { (a, b) -> b > a }) { "the columns print coolest first: ${tempsC.joinToString()}" }
        require(flows.isNotEmpty() && flows.toList().zipWithNext().all { (a, b) -> b > a }) { "the rows run low flow to high: ${flows.joinToString()}" }
        require(lineUm > 0 && holdS > 0) { "a row's line and hold must be above 0" }
    }
    val columns: Int get() = tempsC.size
    val rows: Int get() = flows.size
    /** Every row's bead (mm²): [widthUm] wide, [readUm] thick (the bead model). */
    val beadMm2: Double get() = bead(widthUm, readUm)
    /** Row [r]'s speed (mm/s): its flow through the bead at the flow ratio. */
    fun speed(r: Int): Double = flows[r] / (beadMm2 * flowRatio)
    /** The frame's speed: the machine's print speed, held to the expected maximum flow through the frame's bead. */
    val frameSpeed: Double get() = minOf(frame.speed, frameCap / (bead(widthUm, layerUm) * frame.flowRatio))
    /** Cell labels: column [c] (0-based; "1" the coolest)'s row [r], floor, prime line; the base; the labels. */
    fun rowLabel(c: Int, r: Int) = "melt ${c + 1}.${r + 1}"
    fun floorLabel(c: Int) = "melt floor ${c + 1}"
    fun primeLabel(c: Int) = "melt prime ${c + 1}"
    val baseLabel: String get() = "base melt"
    fun tempMarkLabel(c: Int) = "melt mark temperature ${c + 1}"
    fun flowMarkLabel(r: Int) = "melt mark flow ${r + 1}"
    /** The labels' text: a column's temperature, a row's flow (to the hundredth, trailing zeros dropped). */
    fun tempText(c: Int): String = tempsC[c].toString()
    fun flowText(r: Int): String = PlateMarks.mm(flows[r])

    companion object {
        /** The bead model's cross-section (mm²) of a [wUm] wide, [hUm] thick line: (w - h) h + pi h² / 4. */
        fun bead(wUm: Long, hUm: Long): Double { val w = wUm / 1000.0; val h = hUm / 1000.0; return (w - h) * h + Math.PI * h * h / 4 }
        /** The step between rows' flows (mm³/s) and how many rows plymeld aims for. Plymeld's choices. */
        val STEPS = listOf(0.25, 0.5, 1.0, 2.0, 5.0)
        const val ROWS_AIM = 24
        /**
         * The rows' flows (mm³/s): from half [expected] to twice it, at [step] (given) or the finest of [STEPS] that keeps
         * them to [ROWS_AIM]; on whole steps, low to high.
         */
        fun flows(expected: Double, step: Double? = null): DoubleArray {
            val lo = expected / 2; val hi = expected * 2
            val st = step ?: STEPS.firstOrNull { (hi - lo) / it + 1 <= ROWS_AIM } ?: STEPS.last()
            val from = Math.ceil(lo / st - 1e-9) * st
            return generateSequence(from) { it + st }.takeWhile { it <= hi + 1e-9 }.map { Math.round(it * 1000) / 1000.0 }.toList().toDoubleArray()
        }
    }
}

/**
 * Builds the melt plate's program from its [spec]. [without]: for the read-back check's controls only, which must catch them:
 * "templabel" (column 2's temperature label left out), "flowlabel" (row 2's flow label left out), "chamfer" (the origin's
 * chamfer left out), "wait" (column 2's wait left out: its rows at column 1's temperature), "order" (the columns hottest
 * first), "prime" (column 2's prime line left out), "floor" (no floor under column 1), "rowspeed" (column 1's row 3 at row 4's
 * speed), "rowheight" (column 1's row 3 0.04 mm thicker), "hold" (column 1's row 3 half its lines), "roworder" (column 1's rows
 * highest flow first).
 */
class MeltPlate(private val spec: MeltSpec, private val without: Set<String> = emptySet()) {
    private val g = MeltGeometry
    private val h = spec.layerUm
    private val lw = spec.widthUm

    /** Center-to-center distance of two touching [w] µm lines [hUm] high (the bead model's). */
    private fun spacingOf(w: Long, hUm: Long): Long = Math.round(w - hUm * (1 - Math.PI / 4))
    /** A row's line spacing: the bead model's at its width and thickness. */
    val rowSp: Long get() = spacingOf(lw, spec.readUm)

    // ---- the rows

    /** Row [r]'s lines: as many as hold its flow [MeltSpec.holdS], and at least as many as make it as deep as the tallest flow label. */
    fun lines(r: Int): Int {
        val hold = Math.ceil(spec.holdS * spec.speed(r) / (spec.lineUm / 1000.0) - 1e-9).toInt()
        val labelH = (0 until spec.rows).maxOf { PlateMarks.labelHeight(spec.flowText(it)) }
        val toLabel = Math.ceil((labelH - lw).toDouble() / rowSp).toInt() + 1
        return maxOf(2, hold, toLabel)
    }
    /** Row [r]'s depth (y): its lines at the row spacing, the outer ones' halves included. */
    fun rowD(r: Int): Long = (lines(r) - 1) * rowSp + lw
    /** How long (s) row [r] holds its flow: its lines at its speed. */
    fun holdS(r: Int): Double = lines(r) * spec.lineUm / 1000.0 / spec.speed(r)

    // ---- the layout (µm, before the plate is placed; the base's front-left corner at 0, 0)

    private val flowLabelW: Long get() = (0 until spec.rows).maxOf { PlateMarks.labelWidth(spec.flowText(it)) }
    private val tempLabelH: Long get() = (0 until spec.columns).maxOf { PlateMarks.labelHeight(spec.tempText(it)) }
    /** A column's width: a row's line and the line's own width. */
    val columnW: Long get() = spec.lineUm + lw
    /** The columns' left edge: past the chamfer, the flow labels and their gap. */
    private val columnsX: Long get() = PlateMarks.CHAMFER + flowLabelW + g.labelGap
    /** The prime lines' row: in front of the first row. */
    private val primeY: Long get() = PlateMarks.CHAMFER + tempLabelH + g.labelGap
    /** The first row's front edge. */
    private val rowsY: Long get() = primeY + lw + g.primeFront
    /** Row [r]'s front edge (the same in every column). */
    fun rowY(r: Int): Long = rowsY + (0 until r).sumOf { rowD(it) + g.rowGap }
    /** Column [c]'s row [r] as a box (x0, y0, x1, y1). */
    fun rowBox(c: Int, r: Int): LongArray {
        val x0 = columnsX + c * (columnW + g.columnGap); val y0 = rowY(r)
        return longArrayOf(x0, y0, x0 + columnW, y0 + rowD(r))
    }
    /** Column [c]'s floor (x0, y0, x1, y1): under all its rows. */
    fun floorBox(c: Int): LongArray { val f = rowBox(c, 0); val b = rowBox(c, spec.rows - 1); return longArrayOf(f[0], f[1], b[2], b[3]) }
    /** The base (x0, y0, x1, y1): under every column and label, [MeltGeometry.margin] past the columns at the right and back. */
    fun baseBox(): LongArray { val last = floorBox(spec.columns - 1); return longArrayOf(0, 0, last[2] + g.margin, last[3] + g.margin) }
    /** The base's outline: its front-left corner chamfered (the origin). */
    fun baseOutline(): Polygon { val b = baseBox(); return PlateMarks.box(b[0], b[1], b[2], b[3], if ("chamfer" in without) 0L else PlateMarks.CHAMFER) }
    /** Column [c]'s temperature label: in front of it, centered on its width. */
    fun tempLabel(c: Int): List<Polygon> {
        if ("templabel" in without && c == 1) return emptyList()
        val t = spec.tempText(c); val f = floorBox(c)
        return PlateMarks.label(t, f[0] + (columnW - PlateMarks.labelWidth(t)) / 2, PlateMarks.CHAMFER)
    }
    /** Row [r]'s flow label: left of the first column, centered on the row's depth. */
    fun flowLabel(r: Int): List<Polygon> {
        if ("flowlabel" in without && r == 1) return emptyList()
        val t = spec.flowText(r)
        return PlateMarks.label(t, columnsX - g.labelGap - PlateMarks.labelWidth(t), rowY(r) + (rowD(r) - PlateMarks.labelHeight(t)) / 2)
    }
    /** Every label on the plate, with its cell label. */
    fun labels(): List<Pair<String, List<Polygon>>> =
        (0 until spec.columns).map { spec.tempMarkLabel(it) to tempLabel(it) } + (0 until spec.rows).map { spec.flowMarkLabel(it) to flowLabel(it) }
    /** Column [c]'s prime line (its center line): along its prime row, the column's width. */
    fun primePath(c: Int): LongArray {
        val f = floorBox(c); val y = primeY + lw / 2
        return longArrayOf(f[0] + lw / 2, y, f[2] - lw / 2, y)
    }
    /** Column [c]'s row [r]'s lines: back and forth along X, from the row's front. */
    fun rowPath(c: Int, r: Int): LongArray {
        val b = rowBox(c, r)
        val n = if ("hold" in without && c == 0 && r == 2) maxOf(1, lines(r) / 2) else lines(r)
        val xa = b[0] + lw / 2; val xb = b[2] - lw / 2
        val pts = ArrayList<Long>()
        for (j in 0 until n) {
            val y = b[1] + lw / 2 + j * rowSp
            if (j % 2 == 0) { pts += xa; pts += y; pts += xb; pts += y } else { pts += xb; pts += y; pts += xa; pts += y }
        }
        return pts.toLongArray()
    }

    /** The plate's extent before it is placed (x0, y0, x1, y1). */
    fun bounds(): LongArray = baseBox()
    /** Where the head waits for each column's temperature: in front of the plate, at its middle (bed coordinates). */
    fun parkUm(): Pair<Long, Long> = (spec.originXUm + baseBox()[2] / 2) to (spec.originYUm - g.parkFront)
    /** The z (µm) of a layer's top: the base's first, then [MeltSpec.layerUm] each. */
    fun layerTop(layer: Int): Long = spec.firstLayerUm + layer * h
    /** The floor's top: the rows stand on it. */
    val floorTop: Long get() = layerTop(g.baseLayers)
    /** The rows' height: the floor's top plus their thickness. */
    val readZ: Long get() = floorTop + spec.readUm

    // ---- filling an outline: rings, then one monotonic sweep of lines

    private fun fill(outlines: List<Polygon>, w: Long, hUm: Long, angle: Double): Pair<List<LongArray>, List<LongArray>> {
        val sp = spacingOf(w, hUm)
        val rings = ArrayList<LongArray>()
        var cur = Offset.offset(outlines, -w / 2)
        var n = 0
        while (cur.isNotEmpty() && n < PlateGeometry.frameWalls) { for (r in cur) if (r.size >= 3) rings += r.xy; cur = Offset.offset(cur, -sp); n++ }
        if (rings.isEmpty() || cur.isEmpty()) return rings to emptyList()
        val (segs, idx) = Hatch.indexed(cur, angle, sp)
        if (segs.isEmpty()) return rings to emptyList()
        val chains = Linker.link(segs, idx, cur, Planner.ANCHOR_SPACINGS * sp, breakAtSplits = true, breakAtForks = true)
        val end = rings.last()
        return rings to Ordering.sweep(chains.map { ToolPath(Feature.TOP, it, false, w, hUm) }, end[0], end[1]).map { it.xy }
    }

    fun program(): PrintProgram {
        val steps = ArrayList<Step>()
        val ox = spec.originXUm; val oy = spec.originYUm
        fun put(feature: String, xy: LongArray, closed: Boolean, z: Long, ex: Exact, w: Long, hh: Long) {
            val moved = LongArray(xy.size) { i -> xy[i] + if (i % 2 == 0) ox else oy }
            steps += Extrude(feature, moved, closed, z, w, hh, tool = spec.tool, exact = ex)
        }
        fun lay(feature: String, label: String, outlines: List<Polygon>, z: Long, w: Long, hh: Long, pv: PathValues, speed: Double, angle: Double) {
            val (rings, lines) = fill(outlines, w, hh, angle)
            val ex = Exact(label, doubleArrayOf(speed), pv.accel, pv.flowRatio, pv.retractMm)
            for (r in rings) put("Outer wall", r, true, z, ex, w, hh)
            for (l in lines) put(feature, l, false, z, ex, w, hh)
        }
        spec.levelGcode?.let { steps += plymeld.program.Custom("bed levelling (hardware.machine.<key>.gcode.level)", it) }
        val order = if ("order" in without) (spec.columns - 1 downTo 0).toList() else (0 until spec.columns).toList()
        // the first column's temperature (the start template heats to it: this wait is a guard); the fan off for the base
        steps += SetTemp(spec.tool, spec.tempsC[order[0]], wait = true)
        steps += SetFan(0.0)
        val fz = spec.firstLayerUm
        steps += LayerStart(0, fz, fz)
        lay("Bottom surface", spec.baseLabel, listOf(baseOutline()), fz, spec.firstWidthUm, fz, spec.adhesion, spec.adhesion.speed, 45.0)
        for (layer in 1 until g.baseLayers) {
            steps += LayerStart(layer, layerTop(layer), h)
            lay("Internal solid infill", spec.baseLabel, listOf(baseOutline()), layerTop(layer), lw, h, spec.frame, spec.frameSpeed, if (layer % 2 == 0) 45.0 else 135.0)
        }
        // the floor's layer: the fan on; each column's floor (two walls, lines front to back: across the rows' lines), then the
        // labels' first layer
        val floorZ = floorTop
        steps += LayerStart(g.baseLayers, floorZ, h)
        steps += SetFan(spec.fanPct)
        for (c in 0 until spec.columns) {
            if ("floor" in without && c == 0) continue
            val b = floorBox(c)
            lay("Internal solid infill", spec.floorLabel(c), listOf(PlateMarks.box(b[0], b[1], b[2], b[3])), floorZ, lw, h, spec.frame, spec.frameSpeed, 90.0)
        }
        for ((label, ps) in labels()) if (ps.isNotEmpty()) lay("Outer wall", label, ps, floorZ, lw, h, spec.frame, spec.frameSpeed, 0.0)
        // the read layer: column by column in print order, each after its wait (parked, clear of everything laid), its prime
        // line, then its rows from the front
        val rz = readZ
        steps += LayerStart(g.baseLayers + 1, rz, spec.readUm)
        val pv = spec.frame
        for ((i, c) in order.withIndex()) {
            if (i > 0) {
                steps += LiftTo(rz + spec.liftUm)
                val (px, py) = parkUm()
                steps += Park(px, py, 0.0)
                if (!("wait" in without && c == 1)) steps += SetTemp(spec.tool, spec.tempsC[c], wait = true)
            }
            if (!("prime" in without && c == 1))
                put("Skirt", primePath(c), false, floorZ, Exact(spec.primeLabel(c), doubleArrayOf(spec.frameSpeed), pv.accel, pv.flowRatio, pv.retractMm), lw, h)
            val rows = if ("roworder" in without && c == 0) (spec.rows - 1 downTo 0).toList() else (0 until spec.rows).toList()
            for (r in rows) {
                val odd = c == 0 && r == 2
                val speed = if ("rowspeed" in without && odd && spec.rows > 3) spec.speed(3) else spec.speed(r)
                val z = rz + if ("rowheight" in without && odd) 40 else 0
                put("Top surface", rowPath(c, r), false, z, Exact(spec.rowLabel(c, r), doubleArrayOf(speed), spec.accel, spec.flowRatio, pv.retractMm), lw, spec.readUm)
            }
        }
        // the labels' second layer, at the last column's temperature
        val labelZ = floorZ + h
        steps += LiftTo(maxOf(rz, labelZ) + spec.liftUm)
        for ((label, ps) in labels()) if (ps.isNotEmpty()) lay("Outer wall", label, ps, labelZ, lw, h, spec.frame, spec.frameSpeed, 90.0)
        steps += LiftTo(maxOf(rz, labelZ) + spec.liftUm)
        val bb = bounds().let { longArrayOf(it[0] + ox, it[1] + oy, it[2] + ox, it[3] + oy) }
        val fp = doubleArrayOf(bb[0] / 1000.0, bb[1] / 1000.0, bb[2] / 1000.0, bb[1] / 1000.0, bb[2] / 1000.0, bb[3] / 1000.0, bb[0] / 1000.0, bb[3] / 1000.0)
        return PrintProgram(steps, listOf(PrintObject("melt_plate", fp, (bb[0] + bb[2]) / 2000.0, (bb[1] + bb[3]) / 2000.0)), exact = true, selfPrimed = true)
    }

    /** The plate's g-code: the machine's start and end, everything between exactly as specified. [pressureAdvance]: by tool. */
    fun gcode(s: plymeld.model.SliceSettings, foreignVars: Map<String, List<String>>, version: String, pressureAdvance: Map<Int, Double> = emptyMap()): String =
        java.io.StringWriter().also { plymeld.gcode.KlipperWriter(s, foreignVars, version, pressureAdvance = pressureAdvance).write(program(), it) }.toString()
}
