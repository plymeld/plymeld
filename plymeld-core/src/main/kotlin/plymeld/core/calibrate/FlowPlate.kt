package plymeld.core.calibrate

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
 * The flow plate: the filament's flow ratio at each of several temperatures, read off the tops of square patches. A
 * **column** per temperature (coolest on the left), a **patch** per flow ratio in each column (leanest at the front): every
 * patch the same square, its lines exactly the bead model's spacing apart, so the ratio alone decides whether its top
 * closes up (too lean: grooves between the lines) or rises (too rich: ridges, plastic piled at the edges).
 *
 * Layers: the base ([FlowGeometry.baseLayers], its front-left corner chamfered: the origin, [PlateMarks]); then
 * [FlowSpec.solidLayers] solid layers of every patch at its own ratio, at the filament's print temperature, the lines turning
 * 90 degrees layer by layer; then the **top layer**, read, column by column at the column's temperature, its lines along X.
 * Only the top is printed at the column's temperature: the layers under it are the same in every column, so what differs
 * from column to column is the temperature the read surface was laid at.
 *
 * In front of each column a **rail** (two lines wide, as tall as the patches) carries the column's prime line on the top
 * layer: the nozzle oozes while it waits, and the prime line takes that up before the patches, at the patches' own height.
 *
 * **The marks** ([PlateMarks]): each patch row's ratio ("0.94") printed once at the plate's left, beside the row; each
 * column's temperature ("210") printed in front of it.
 *
 * Temperatures: the base at the filament's own (its first-layer temperature, then its print temperature), the solid layers
 * at its print temperature. Print order on the top layer: coolest column first (a nozzle heats faster than it cools).
 * Before each column the head lifts clear, parks in front of the plate and waits for the column's temperature (M109); then
 * the column's prime line, then its patches from the front.
 *
 * Lengths in µm, speeds mm/s, accelerations mm/s².
 */
object FlowGeometry {
    /** The base's layers (the first included): two, so the plate comes off the bed in one stiff piece. */
    const val baseLayers = 2
    /** A patch: about this square (exactly: a whole number of lines at the bead spacing). Plymeld's choice. */
    const val patchAbout = 12_000L
    /** Between two patches in a column. Plymeld's choice. */
    const val patchGap = 3_000L
    /** Between two columns. Plymeld's choice. */
    const val columnGap = 3_000L
    /** The base past everything on it at the right and the back. Plymeld's choice. */
    const val margin = 3_000L
    /** A label to what it names. Plymeld's choice. */
    const val labelGap = 2_000L
    /** The rail's front to the first patch. Plymeld's choice. */
    const val railGap = 1_500L
    /** The rail's lines (side by side, front to back). */
    const val railLines = 2
    /** The head parks this far in front of the plate for each column's temperature (as the melt plate parks). */
    const val parkFront = 10_000L
}

class FlowSpec(
    /** The program's tool (0-based) and its spool's name. */
    val tool: Int,
    val spool: String,
    /** One column per temperature, in print order: coolest first. */
    val tempsC: IntArray,
    /** The patches' flow ratios, front to back: lean to rich. */
    val ratios: DoubleArray,
    /** The base's temperatures (C): the filament's own first-layer and print temperatures; the solid layers at the latter. */
    val baseFirstC: Int,
    val basePrintC: Int,
    /** The part fan (%) from the first layer above the base (the base's are off). */
    val fanPct: Double,
    /** The plate's layer height (µm) and its first layer's. */
    val layerUm: Long,
    val firstLayerUm: Long,
    /** The plate's line width (µm: every patch's too) and the base's first layer's (the first-layer line width). */
    val widthUm: Long,
    val firstWidthUm: Long,
    /** The patches' solid layers under their top (at the print temperature): at least 2, so the labels have their 2 layers. */
    val solidLayers: Int,
    /** The patches' speed and acceleration (a visible surface's, held to the filament's maximum flow at the richest ratio). */
    val speed: Double,
    val accel: Double,
    /** The first layer's values (the base's first layer). */
    val adhesion: PathValues,
    /** The frame's values: the base above its first layer, the rails, the labels, the prime lines. */
    val frame: PathValues,
    /** How far the head lifts before a travel (µm, the travel lift). */
    val liftUm: Long,
    /** Where the plate's front-left corner goes on the bed (µm). */
    val originXUm: Long = 0,
    val originYUm: Long = 0,
    /** The machine's bed levelling, run after its start template; null = none. */
    val levelGcode: String? = null,
) {
    init {
        require(tempsC.isNotEmpty()) { "a flow plate needs at least one temperature" }
        require(tempsC.toList().zipWithNext().all { (a, b) -> b > a }) { "the columns print coolest first: ${tempsC.joinToString()}" }
        require(ratios.size >= 2 && ratios.toList().zipWithNext().all { (a, b) -> b > a }) { "the patches run lean to rich, at least two: ${ratios.joinToString()}" }
        require(solidLayers >= 2) { "the patches need at least 2 solid layers under their top (got $solidLayers)" }
    }
    val columns: Int get() = tempsC.size
    val rows: Int get() = ratios.size
    /** Cell labels: column [c] (0-based; "1" the coolest)'s patch [r] (from the front); its rail and prime line; the base; the labels. */
    fun patchLabel(c: Int, r: Int) = "flow ${c + 1}.${r + 1}"
    fun railLabel(c: Int) = "flow rail ${c + 1}"
    fun primeLabel(c: Int) = "flow prime ${c + 1}"
    val baseLabel: String get() = "base flow"
    fun tempMarkLabel(c: Int) = "flow mark temperature ${c + 1}"
    fun ratioMarkLabel(r: Int) = "flow mark ratio ${r + 1}"
    /** The labels' text: a column's temperature, a patch row's ratio (to the thousandth, trailing zeros dropped). */
    fun tempText(c: Int): String = tempsC[c].toString()
    fun ratioText(r: Int): String = java.math.BigDecimal(ratios[r]).setScale(3, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    companion object {
        /** The default ratios: the filament's [ratio] x 0.94 to 1.06 in 0.02 steps, to 0.001. */
        fun defaultRatios(ratio: Double): List<Double> = (-3..3).map { k -> Math.round(ratio * (1 + 0.02 * k) * 1000) / 1000.0 }
        /** Solid layers under the top: about 1 mm in all with the top, at least 2. Plymeld's choice. */
        fun solidLayersFor(layerUm: Long): Int = maxOf(2, Math.round(1000.0 / layerUm).toInt() - 1)
    }
}

/**
 * Builds the flow plate's program from its [spec]. [without]: for the tests' controls only, which must catch them:
 * "templabel" (column 2's temperature label left out), "ratiolabel" (row 2's ratio label left out), "chamfer" (the origin's
 * chamfer left out), "wait" (column 2's wait left out: its top at column 1's temperature), "order" (the columns hottest
 * first), "prime" (column 2's prime line left out), "ratio" (column 1's patch 3's top at patch 4's ratio), "spacing" (column
 * 1's patch 3's top lines 0.02 mm closer than the bead spacing).
 */
class FlowPlate(private val spec: FlowSpec, private val without: Set<String> = emptySet()) {
    private val g = FlowGeometry
    private val h = spec.layerUm
    private val lw = spec.widthUm
    /** The bead model's line spacing at the plate's width and layer height. */
    val spacing: Long get() = PlateFill.spacing(lw, h)

    // ---- the layout (µm, before the plate is placed; the base's front-left corner at 0, 0)

    /** Lines across a patch: as many as make it about [FlowGeometry.patchAbout] square at the bead spacing. */
    val patchLines: Int get() = maxOf(2, ((g.patchAbout - lw - 2 * spacing) / spacing).toInt() + 1)
    /** A patch's outer size: its lines at the bead spacing, the wall round them touching them, the wall's own width. */
    val patchSize: Long get() = (patchLines - 1) * spacing + 2 * spacing + lw
    private val ratioLabelW: Long get() = (0 until spec.rows).maxOf { PlateMarks.labelWidth(spec.ratioText(it)) }
    private val tempLabelH: Long get() = (0 until spec.columns).maxOf { PlateMarks.labelHeight(spec.tempText(it)) }
    /** The columns' left edge: past the chamfer, the ratio labels and their gap. */
    private val columnsX: Long get() = PlateMarks.CHAMFER + ratioLabelW + g.labelGap
    /** The rails' front edge: behind the temperature labels. */
    private val railY: Long get() = PlateMarks.CHAMFER + tempLabelH + g.labelGap
    /** A rail's depth: its lines side by side at the bead spacing. */
    private val railD: Long get() = (g.railLines - 1) * spacing + lw
    /** The first patch row's front edge. */
    private val rowsY: Long get() = railY + railD + g.railGap
    /** Column [c]'s patch [r] as a box (x0, y0, x1, y1). */
    fun patchBox(c: Int, r: Int): LongArray {
        val x0 = columnsX + c * (patchSize + g.columnGap); val y0 = rowsY + r * (patchSize + g.patchGap)
        return longArrayOf(x0, y0, x0 + patchSize, y0 + patchSize)
    }
    /** Column [c]'s rail (x0, y0, x1, y1): in front of its patches, as wide as the column. */
    fun railBox(c: Int): LongArray { val p = patchBox(c, 0); return longArrayOf(p[0], railY, p[2], railY + railD) }
    /** The base (x0, y0, x1, y1): under every column and label, [FlowGeometry.margin] past the patches at the right and back. */
    fun baseBox(): LongArray { val last = patchBox(spec.columns - 1, spec.rows - 1); return longArrayOf(0, 0, last[2] + g.margin, last[3] + g.margin) }
    fun baseOutline(): Polygon { val b = baseBox(); return PlateMarks.box(b[0], b[1], b[2], b[3], if ("chamfer" in without) 0L else PlateMarks.CHAMFER) }
    fun tempLabel(c: Int): List<Polygon> {
        if ("templabel" in without && c == 1) return emptyList()
        val t = spec.tempText(c); val p = patchBox(c, 0)
        return PlateMarks.label(t, p[0] + (patchSize - PlateMarks.labelWidth(t)) / 2, PlateMarks.CHAMFER)
    }
    fun ratioLabel(r: Int): List<Polygon> {
        if ("ratiolabel" in without && r == 1) return emptyList()
        val t = spec.ratioText(r); val p = patchBox(0, r)
        return PlateMarks.label(t, columnsX - g.labelGap - PlateMarks.labelWidth(t), p[1] + (patchSize - PlateMarks.labelHeight(t)) / 2)
    }
    fun labels(): List<Pair<String, List<Polygon>>> =
        (0 until spec.columns).map { spec.tempMarkLabel(it) to tempLabel(it) } + (0 until spec.rows).map { spec.ratioMarkLabel(it) to ratioLabel(it) }

    /** The plate's extent before it is placed (x0, y0, x1, y1). */
    fun bounds(): LongArray = baseBox()
    /** Where the head waits for each column's temperature: in front of the plate, at its middle (bed coordinates). */
    fun parkUm(): Pair<Long, Long> = (spec.originXUm + baseBox()[2] / 2) to (spec.originYUm - g.parkFront)
    /** The z (µm) of layer [layer]'s top: the first layer's, then [FlowSpec.layerUm] each. */
    fun layerTop(layer: Int): Long = spec.firstLayerUm + layer * h
    /** The top layer (0-based): the read one. */
    val topLayer: Int get() = g.baseLayers + spec.solidLayers

    // ---- paths

    /**
     * A patch's paths on one layer: its wall (a closed ring half a line in from its edge) and its lines (one open path,
     * back and forth, each line [sp] from the next and from the wall), along X or Y.
     */
    private fun patchPaths(b: LongArray, alongX: Boolean, sp: Long): Pair<LongArray, LongArray> {
        val r0x = b[0] + lw / 2; val r0y = b[1] + lw / 2; val r1x = b[2] - lw / 2; val r1y = b[3] - lw / 2
        val ring = longArrayOf(r0x, r0y, r1x, r0y, r1x, r1y, r0x, r1y)
        val f0x = r0x + spacing; val f0y = r0y + spacing; val f1x = r1x - spacing; val f1y = r1y - spacing
        val pts = ArrayList<Long>()
        val lines = if (sp == spacing) patchLines else ((if (alongX) f1y - f0y else f1x - f0x) / sp).toInt() + 1
        for (i in 0 until lines) {
            val across = i * sp
            if (alongX) { val y = f0y + across; if (i % 2 == 0) { pts += f0x; pts += y; pts += f1x; pts += y } else { pts += f1x; pts += y; pts += f0x; pts += y } }
            else { val x = f0x + across; if (i % 2 == 0) { pts += x; pts += f0y; pts += x; pts += f1y } else { pts += x; pts += f1y; pts += x; pts += f0y } }
        }
        return ring to pts.toLongArray()
    }
    /** Column [c]'s rail lines (along X, front to back) and its prime line (the rail's middle, along X). */
    private fun railPaths(c: Int): LongArray {
        val b = railBox(c); val pts = ArrayList<Long>()
        for (j in 0 until g.railLines) { val y = b[1] + lw / 2 + j * spacing
            if (j % 2 == 0) { pts += b[0] + lw / 2; pts += y; pts += b[2] - lw / 2; pts += y } else { pts += b[2] - lw / 2; pts += y; pts += b[0] + lw / 2; pts += y } }
        return pts.toLongArray()
    }
    fun primePath(c: Int): LongArray { val b = railBox(c); val y = (b[1] + b[3]) / 2; return longArrayOf(b[0] + lw / 2, y, b[2] - lw / 2, y) }

    fun program(): PrintProgram {
        val steps = ArrayList<Step>()
        val ox = spec.originXUm; val oy = spec.originYUm
        fun put(feature: String, xy: LongArray, closed: Boolean, z: Long, ex: Exact, w: Long, hh: Long) {
            val moved = LongArray(xy.size) { i -> xy[i] + if (i % 2 == 0) ox else oy }
            steps += Extrude(feature, moved, closed, z, w, hh, tool = spec.tool, exact = ex)
        }
        fun lay(feature: String, label: String, outlines: List<Polygon>, z: Long, w: Long, hh: Long, pv: PathValues, angle: Double) {
            val (rings, lines) = PlateFill.fill(outlines, w, hh, angle)
            val ex = Exact(label, doubleArrayOf(pv.speed), pv.accel, pv.flowRatio, pv.retractMm)
            for (r in rings) put("Outer wall", r, true, z, ex, w, hh)
            for (l in lines) put(feature, l, false, z, ex, w, hh)
        }
        fun patch(c: Int, r: Int, layer: Int, z: Long, top: Boolean) {
            val odd = c == 0 && r == 2
            val ratio = if ("ratio" in without && odd && top && spec.rows > 3) spec.ratios[3] else spec.ratios[r]
            val sp = if ("spacing" in without && odd && top) spacing - 20 else spacing
            // the top's lines along X, the layers under it turning 90 degrees each
            val (ring, lines) = patchPaths(patchBox(c, r), alongX = (topLayer - layer) % 2 == 0, sp = sp)
            val ex = Exact(spec.patchLabel(c, r), doubleArrayOf(spec.speed), spec.accel, ratio, spec.frame.retractMm)
            put("Outer wall", ring, true, z, ex, lw, h)
            put(if (top) "Top surface" else "Internal solid infill", lines, false, z, ex, lw, h)
        }
        spec.levelGcode?.let { steps += plymeld.program.Custom("bed levelling (hardware.machine.<key>.gcode.level)", it) }
        // the filament's first-layer temperature (the start template heats to it: this wait is a guard); the fan off for the base
        steps += SetTemp(spec.tool, spec.baseFirstC, wait = true)
        steps += SetFan(0.0)
        val fz = spec.firstLayerUm
        steps += LayerStart(0, fz, fz)
        lay("Bottom surface", spec.baseLabel, listOf(baseOutline()), fz, spec.firstWidthUm, fz, spec.adhesion, 45.0)
        // the rest at the filament's print temperature, reached as it prints (no wait: the head would stand on the plate)
        if (spec.basePrintC != spec.baseFirstC) steps += SetTemp(spec.tool, spec.basePrintC, wait = false)
        for (layer in 1 until g.baseLayers) {
            steps += LayerStart(layer, layerTop(layer), h)
            lay("Internal solid infill", spec.baseLabel, listOf(baseOutline()), layerTop(layer), lw, h, spec.frame, if (layer % 2 == 0) 45.0 else 135.0)
        }
        // the solid layers: every patch at its own ratio, the rails, the labels' two layers; the fan on
        for (layer in g.baseLayers until topLayer) {
            val z = layerTop(layer)
            steps += LayerStart(layer, z, h)
            if (layer == g.baseLayers) steps += SetFan(spec.fanPct)
            for (c in 0 until spec.columns) {
                put("Internal solid infill", railPaths(c), false, z, Exact(spec.railLabel(c), doubleArrayOf(spec.frame.speed), spec.frame.accel, spec.frame.flowRatio, spec.frame.retractMm), lw, h)
                for (r in 0 until spec.rows) patch(c, r, layer, z, top = false)
            }
            if (layer < g.baseLayers + 2) for ((label, ps) in labels()) if (ps.isNotEmpty()) lay("Outer wall", label, ps, z, lw, h, spec.frame, if (layer == g.baseLayers) 0.0 else 90.0)
        }
        // the top layer: column by column in print order, each after its wait (parked, clear of everything laid), its prime
        // line along its rail, then its patches from the front
        val tz = layerTop(topLayer)
        steps += LayerStart(topLayer, tz, h)
        val order = if ("order" in without) (spec.columns - 1 downTo 0).toList() else (0 until spec.columns).toList()
        for (c in order) {
            steps += LiftTo(tz + spec.liftUm)
            val (px, py) = parkUm()
            steps += Park(px, py, 0.0)
            if (!("wait" in without && c == 1)) steps += SetTemp(spec.tool, spec.tempsC[c], wait = true)
            // the prime line at the patches' speed, not the frame's: it prints at the column's temperature, where the
            // filament's maximum flow (the frame's limit, at the print temperature) may not hold
            if (!("prime" in without && c == 1))
                put("Skirt", primePath(c), false, tz, Exact(spec.primeLabel(c), doubleArrayOf(spec.speed), spec.accel, spec.frame.flowRatio, spec.frame.retractMm), lw, h)
            for (r in 0 until spec.rows) patch(c, r, topLayer, tz, top = true)
        }
        steps += LiftTo(tz + spec.liftUm)
        val bb = bounds().let { longArrayOf(it[0] + ox, it[1] + oy, it[2] + ox, it[3] + oy) }
        val fp = doubleArrayOf(bb[0] / 1000.0, bb[1] / 1000.0, bb[2] / 1000.0, bb[1] / 1000.0, bb[2] / 1000.0, bb[3] / 1000.0, bb[0] / 1000.0, bb[3] / 1000.0)
        return PrintProgram(steps, listOf(PrintObject("flow_plate", fp, (bb[0] + bb[2]) / 2000.0, (bb[1] + bb[3]) / 2000.0)), exact = true, selfPrimed = true)
    }

    /** The plate's g-code: the machine's start and end, everything between exactly as specified. [pressureAdvance]: by tool. */
    fun gcode(s: plymeld.model.SliceSettings, foreignVars: Map<String, List<String>>, version: String, pressureAdvance: Map<Int, Double> = emptyMap()): String =
        java.io.StringWriter().also { plymeld.gcode.KlipperWriter(s, foreignVars, version, pressureAdvance = pressureAdvance).write(program(), it) }.toString()
}
