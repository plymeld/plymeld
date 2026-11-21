package plymeld.core.calibrate

import plymeld.geom.Booleans
import plymeld.geom.Polygon
import plymeld.program.Exact
import plymeld.program.Extrude
import plymeld.program.LayerStart
import plymeld.program.LiftTo
import plymeld.program.Park
import plymeld.program.PrintObject
import plymeld.program.PrintProgram
import plymeld.program.SetFan
import plymeld.program.SetPressureAdvance
import plymeld.program.SetTemp
import plymeld.program.Step

/**
 * The pressure-advance plates: the value read off as the SIGN of a defect that reverses through the right value, on
 * straight walls whose speed steps up and down along their length.
 *
 * **A step wall**: one line, straight along +Y, printed the same way on every layer (the head travels back to its front
 * between layers), with no retraction inside it: a slow lead-in ([PressureAdvanceSpec.leadUm] at the slow speed), a fast
 * middle ([PressureAdvanceSpec.fastUm]), a slow tail ([PressureAdvanceGeometry.tail]), the lead-in and the middle long enough
 * for the pressure to settle within them at the plate's highest value (worked out by the caller with the pressure model; at
 * least [PressureAdvanceGeometry.leadMin] and [PressureAdvanceGeometry.fastMin]). Where the set pressure advance is
 * short of the true one, the line just after the speed-up is thin (a groove, stacked up the wall) and just after the
 * slow-down fat (a ridge); set long, the other way round. Each wall prints at its own value, set by the machine's command just
 * before it and changed by nothing else until the next wall's.
 *
 * **The find plate** ([PressureAdvanceSpec.Kind.FIND]): two rows of walls, the front row's values rising from the left, the
 * back row's the same values falling (each value twice, in another place and another order, so a trend across the bed cannot
 * pass as the flip), and beside each wall a sharp corner (two short lines meeting square, at the wall's value and its fast
 * speed: a bulge there says too low, a rounded or gapped corner too high), read only to confirm the walls. **The confirm
 * plate** ([PressureAdvanceSpec.Kind.CONFIRM]): one row, each value at three flow steps (three fast speeds), no corners; on a
 * machine that sets its own, one "value" (the machine's, no command written) at the three steps.
 *
 * **The marks** ([PlateMarks]): the base's front-left corner chamfered (the origin); beside each wall, on its left, a hole cut
 * through the base's upper layers at each speed change (where the head starts to speed up, and where it starts to slow
 * down: the defect is just after each, in the direction of travel); in front of each wall an arrow pointing the way it
 * prints, and in front of that its value in milliseconds, written along the wall ("40" = 0.04 s); a tally between the walls
 * in the base's front edge (the front row) and back edge (the back row), so a wall is found by counting from the chamfer; on
 * the confirm plate the front edge carries the wall's flow step instead (one notch small, two medium, three large).
 *
 * **One base**, one piece ([PressureAdvanceGeometry.baseLayers] layers, the first whole, the marks cut in the ones above),
 * at the first layer's and the frame's values; the walls start above it, so neither the first layer nor the base is read.
 * The base and the labels print at [PressureAdvanceSpec.framePa]. Lengths in µm, speeds mm/s, accelerations mm/s².
 */
object PressureAdvanceGeometry {
    /** The base's layers (the first whole, the marks cut through the ones above): one piece, at least two. */
    const val baseLayers = 3
    /**
     * A wall's shortest slow lead-in and fast middle, and its slow tail, along it. plymeld's choices; the lead-in and the
     * middle grow together, in this proportion, until the pressure settles within them at the plate's highest value.
     */
    const val leadMin = 10_000L
    const val fastMin = 30_000L
    const val tail = 10_000L
    /** The walls' height above the base. plymeld's choice. */
    const val wallHeight = 10_000L
    /** Between two walls' cells, across the row (a fingernail's room between a wall and its neighbor's corner). plymeld's choice. */
    const val pitch = 13_000L
    /** A wall from its cell's left edge. */
    const val wallX = 4_500L
    /** The base past the cells at the left and right. */
    const val margin = 3_000L
    /** A row's labels from its front edge: clear of the tally's long notches ([PlateMarks.TALLY_LONG_IN]). */
    const val front = 4_000L
    /** Label to arrow, arrow to wall. */
    const val gap = 1_500L
    /** A wall's end to its row's back edge. */
    const val back = 4_000L
    /** A speed-change mark: a hole [PlateMarks.TALLY_W] along the wall and [PlateMarks.TALLY_IN] across, this far from the wall's edge. */
    const val holeGap = 1_000L
    /** The corner beside a wall: its first line this far right of the wall, along the middle of the wall's fast middle, this long; its second this long to the right. */
    const val cornerX = 3_500L
    const val cornerUp = 10_000L
    const val cornerOut = 5_000L
    /** The labels' and arrows' layers (on the first wall layers). */
    const val labelLayers = 2
    /** The arrow's head, a share (%) of its length. plymeld's choice. */
    const val arrowHead = 45L
    /** The head parks this far in front of the plate for the walls' temperature. */
    const val parkFront = 10_000L
    /** The find plate's values per row: plymeld's choice (0 and ten from the tool's lowest value to its ceiling). */
    const val values = 11
    /** The stretch after a mark a reading looks at (the first few millimeters, where the defect is). plymeld's choice. */
    const val readLength = 5_000L
}

/** One wall: its row (0 the front) and place in it (0 the left), its pressure advance (s; null = none written), its fast speed (mm/s), and its flow step (0, 1, 2; 0 on the find plate). */
class PressureAdvanceWall(val row: Int, val position: Int, val value: Double?, val fastSpeed: Double, val step: Int = 0) {
    /** Its cell's label, "wall 1.3" (row 1, the front, third from the left), and its corner's. */
    val label: String get() = "wall ${row + 1}.${position + 1}"
    val cornerLabel: String get() = "corner ${row + 1}.${position + 1}"
}

class PressureAdvanceSpec(
    /** The program's tool (0-based) and its spool's name. */
    val tool: Int,
    val spool: String,
    val kind: Kind,
    /** Every wall, in print order: the front row left to right, then the back row. */
    val walls: List<PressureAdvanceWall>,
    /** The layer height (µm) and the base's first layer's; the line width (µm) and the first layer's. */
    val layerUm: Long, val firstLayerUm: Long, val widthUm: Long, val firstWidthUm: Long,
    val filamentDiameterMm: Double,
    val flowRatio: Double,
    /** The walls' nozzle temperature (the filament's print temperature), the base's first layer's, and the part fan from the second layer. */
    val tempC: Int, val firstTempC: Int, val fanPct: Double,
    /** The base's first layer's values, and the frame's (the base above it, the labels, the arrows). */
    val adhesion: PathValues, val frame: PathValues,
    /** The walls' slow speed (mm/s) and acceleration (mm/s², the machine's). */
    val slowSpeed: Double, val accel: Double,
    /** The pressure advance the base and the labels print at (s); null = none written (a machine that sets its own). */
    val framePa: Double?,
    /** How far the head lifts off the plate (µm, the travel lift). */
    val liftUm: Long,
    val originXUm: Long = 0, val originYUm: Long = 0,
    /** The machine's bed levelling, run after its start template; null = none. */
    val levelGcode: String? = null,
    /** The walls' slow lead-in and fast middle (µm): long enough for the pressure to settle within them at the plate's highest value. */
    val leadUm: Long = PressureAdvanceGeometry.leadMin, val fastUm: Long = PressureAdvanceGeometry.fastMin,
) {
    /** Find (two rows, replicates, corners) or confirm (one row, three flow steps a value). */
    enum class Kind(val key: String) { FIND("find"), CONFIRM("confirm") }

    companion object {
        /** The labels' resolution (s): a value is written in milliseconds to a tenth ([labelText]), so every wall's value is held to it. */
        const val LABEL_STEP_S = 0.0001
        /** [v] at the labels' resolution: the value a wall prints at and its label agree. */
        fun atLabel(v: Double): Double = java.math.BigDecimal(v).setScale(4, java.math.RoundingMode.HALF_UP).toDouble()
    }

    init {
        require(walls.isNotEmpty()) { "a pressure-advance plate needs at least one wall" }
        require(walls.all { it.value == null || it.value == atLabel(it.value) }) { "every wall's value is at the labels' resolution, ${LABEL_STEP_S * 1000} ms: ${walls.filter { it.value != null && it.value != atLabel(it.value) }.joinToString { "${it.label} ${it.value}" }}" }
        require(walls.all { it.fastSpeed > slowSpeed }) { "every wall's fast speed is above its slow speed ($slowSpeed mm/s): ${walls.filter { it.fastSpeed <= slowSpeed }.joinToString { "${it.label} ${it.fastSpeed}" }}" }
        require(walls.all { it.value == null || it.value >= 0 }) { "pressure advance is 0 s or more" }
        require(slowSpeed > 0 && accel > 0) { "the slow speed and the acceleration are above 0" }
        // the head reaches each wall's fast speed before it starts to slow down again
        require(leadUm >= PressureAdvanceGeometry.leadMin && fastUm >= PressureAdvanceGeometry.fastMin) { "the lead-in and the middle are at least ${PressureAdvanceGeometry.leadMin / 1000} and ${PressureAdvanceGeometry.fastMin / 1000} mm" }
        require(walls.all { 2 * decelUm(it) <= fastUm }) {
            "the fast middle (${fastUm / 1000.0} mm) is too short to reach ${walls.maxOf { it.fastSpeed }} mm/s from $slowSpeed mm/s and slow down again at $accel mm/s2"
        }
    }

    private val g get() = PressureAdvanceGeometry
    val corners: Boolean get() = kind == Kind.FIND
    val rows: Int get() = walls.maxOf { it.row } + 1
    val perRow: Int get() = walls.maxOf { it.position } + 1
    /** The walls' layers above the base: [PressureAdvanceGeometry.wallHeight] in whole layers, at least the labels'. */
    val wallLayers: Int get() = maxOf(g.labelLayers, Math.round(g.wallHeight.toDouble() / layerUm).toInt())
    val layers: Int get() = g.baseLayers + wallLayers
    fun zTop(layer: Int): Long = firstLayerUm + layer * layerUm
    fun heightOf(layer: Int): Long = if (layer == 0) firstLayerUm else layerUm
    val baseTop: Long get() = zTop(g.baseLayers - 1)
    val topZ: Long get() = zTop(layers - 1)

    /** A value as its label: milliseconds, to a tenth, trailing zeros dropped ("40", "42.5"); "0" for 0. */
    fun labelText(w: PressureAdvanceWall): String? = w.value?.let { java.math.BigDecimal(it * 1000).setScale(1, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() }
    /** A value in seconds as the conditions block and the readings write it (to the hundred-thousandth). */
    fun valueText(v: Double): String = java.math.BigDecimal(v).setScale(5, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    /** The longest label, along Y (µm): every row's walls start behind it. */
    val labelLength: Long get() = walls.mapNotNull { labelText(it) }.maxOfOrNull { PlateMarks.labelWidth(it) } ?: 0L
    /** The arrow in front of each wall: as long as a label is tall ([PlateMarks.LABEL_H]); see [PressureAdvancePlate.arrow]. */
    val arrowLength: Long get() = PlateMarks.LABEL_H
    val wallLength: Long get() = leadUm + fastUm + g.tail
    val rowDepth: Long get() = g.front + labelLength + g.gap + arrowLength + g.gap + wallLength + g.back
    /** The base's size (µm). */
    val baseW: Long get() = 2 * g.margin + perRow * g.pitch
    val baseD: Long get() = rows * rowDepth

    /** Wall [w]'s line (µm, before placing): its x, where it starts and ends along +Y. */
    fun wallXUm(w: PressureAdvanceWall): Long = g.margin + w.position * g.pitch + g.wallX
    fun wallStartY(w: PressureAdvanceWall): Long = w.row * rowDepth + g.front + labelLength + g.gap + arrowLength + g.gap
    fun wallEndY(w: PressureAdvanceWall): Long = wallStartY(w) + wallLength
    /** How far the head travels slowing from [w]'s fast speed to the slow one (µm). */
    fun decelUm(w: PressureAdvanceWall): Long = Math.round((w.fastSpeed * w.fastSpeed - slowSpeed * slowSpeed) / (2 * accel) * 1000)
    /** Where the head starts to speed up along wall [w] (µm from its start): the fast middle's start. */
    fun markUp(@Suppress("UNUSED_PARAMETER") w: PressureAdvanceWall): Long = leadUm
    /** Where the head starts to slow down (µm from its start): the fast middle's end, less the slowing. */
    fun markDown(w: PressureAdvanceWall): Long = leadUm + fastUm - decelUm(w)

    /** The flow step of [w]: (fast - slow) x the bead x the flow ratio (mm³/s). */
    fun flowStep(w: PressureAdvanceWall): Double = (w.fastSpeed - slowSpeed) * beadMm2(widthUm, layerUm) * flowRatio
    val filamentMm2: Double get() = Math.PI * filamentDiameterMm * filamentDiameterMm / 4
    fun beadMm2(w: Long, h: Long): Double { val hm = h / 1000.0; return hm * (w / 1000.0 - hm * (1 - Math.PI / 4)) }

    /** The base's front edge for a row's tally ([PlateMarks.between]): the cells' far edges from the left. */
    fun cellBounds(): List<Long> = (0 until perRow).map { g.margin + (it + 1) * g.pitch }
    val baseLabel: String get() = "base"
    fun labelCell(w: PressureAdvanceWall) = "label ${w.row + 1}.${w.position + 1}"
}

/**
 * Builds a pressure-advance plate's program from its [spec]. [without]: for the read-back check's controls only, which must
 * catch them: "value" (wall 1.3 set to wall 1.4's value), "command" (no command before wall 2.2: it prints at wall
 * 2.1's value), "order" (the back row in the front row's order), "direction" (wall 1.2 printed back to front on odd layers),
 * "speed" (wall 1.1's middle at the slow speed: no speed change), "holes" (no speed-change marks), "chamfer" (no chamfer),
 * "tally" (no tally), "corner" (wall 1.2's corner left out).
 */
class PressureAdvancePlate(private val spec: PressureAdvanceSpec, private val without: Set<String> = emptySet()) {
    private val g = PressureAdvanceGeometry
    private val lw = spec.widthUm

    /** The value wall [w] prints at, the controls applied. */
    fun valueOf(w: PressureAdvanceWall): Double? {
        if ("value" in without && w.row == 0 && w.position == 2) spec.walls.firstOrNull { it.row == 0 && it.position == 3 }?.let { return it.value }
        if ("order" in without && w.row == 1) spec.walls.firstOrNull { it.row == 0 && it.position == w.position }?.let { return it.value }
        return w.value
    }

    /** The base's outline on [layer] (µm, before placing): its rectangle, chamfered at the front-left; above the first layer the tallies and the speed-change holes cut out. */
    fun baseOutline(layer: Int): List<Polygon> {
        val b = longArrayOf(0, 0, spec.baseW, spec.baseD)
        val outline = PlateMarks.box(b[0], b[1], b[2], b[3], if ("chamfer" in without) 0L else PlateMarks.CHAMFER)
        if (layer == 0) return listOf(outline)
        val cuts = ArrayList<Polygon>()
        if ("tally" !in without) {
            if (spec.kind == PressureAdvanceSpec.Kind.FIND) {
                cuts += PlateMarks.between(b, PlateMarks.Edge.FRONT, spec.cellBounds())
                if (spec.rows > 1) cuts += PlateMarks.between(b, PlateMarks.Edge.BACK, spec.cellBounds())
            } else {
                // the flow step under each wall: 1, 2, or 3 notches, centered on the wall
                for (w in spec.walls) { val n = w.step + 1; val x = spec.wallXUm(w); cuts += PlateMarks.count(b, PlateMarks.Edge.FRONT, x - PlateMarks.countLength(n) / 2 + PlateMarks.TALLY_W / 2, n) }
                cuts += PlateMarks.between(b, PlateMarks.Edge.BACK, spec.cellBounds())
            }
        }
        if ("holes" !in without) for (w in spec.walls) cuts += holes(w)
        return Booleans.difference(listOf(outline), cuts)
    }

    /** Wall [w]'s speed-change marks: a hole at each, on the wall's left (µm, before placing). */
    fun holes(w: PressureAdvanceWall): List<Polygon> {
        val x1 = spec.wallXUm(w) - lw / 2 - g.holeGap; val x0 = x1 - PlateMarks.TALLY_IN
        val y0 = spec.wallStartY(w)
        return listOf(spec.markUp(w), spec.markDown(w)).map { m -> PlateMarks.box(x0, y0 + m - PlateMarks.TALLY_W / 2, x1, y0 + m + PlateMarks.TALLY_W / 2) }
    }

    /** Wall [w]'s label (its value, written along +Y, reading from the front) and arrow (pointing +Y), as raised polygons (µm, before placing). */
    fun labelAndArrow(w: PressureAdvanceWall): List<Polygon> {
        val x = spec.wallXUm(w); val y0 = w.row * spec.rowDepth + g.front
        val out = ArrayList<Polygon>()
        spec.labelText(w)?.let { t ->
            // drawn flat, then turned a quarter left about its box's front-left corner: its baseline along +Y, its glyphs' tops to -X
            val flat = PlateMarks.label(t, 0, 0)
            val h = PlateMarks.labelHeight(t)
            for (p in flat) out += Polygon(LongArray(p.xy.size) { i -> if (i % 2 == 0) x + h / 2 - p.xy[i + 1] else y0 + p.xy[i - 1] })
        }
        out += arrow(x, y0 + spec.labelLength + g.gap)
        return out
    }

    /**
     * An arrow pointing +Y whose tail is at ([x], [y0]) (µm): one outline (a shaft a label's stroke wide, a head as wide as a
     * digit), laid as one shape, so no strokes' ends meet at its tip; [PressureAdvanceSpec.arrowLength] long, the head
     * [PressureAdvanceGeometry.arrowHead] of it.
     */
    fun arrow(x: Long, y0: Long): Polygon {
        val len = spec.arrowLength; val head = len * g.arrowHead / 100
        val s = PlateMarks.LABEL_STROKE / 2; val b = PlateMarks.labelWidth("0") / 2
        return Polygon(longArrayOf(x - s, y0, x + s, y0, x + s, y0 + len - head, x + b, y0 + len - head, x, y0 + len, x - b, y0 + len - head, x - s, y0 + len - head))
    }

    /** Wall [w]'s line on wall layer [i] (µm, placed): along +Y from its start ("direction": wall 1.2 the other way on odd layers). */
    fun wallPath(w: PressureAdvanceWall, i: Int): LongArray {
        val x = spec.wallXUm(w) + spec.originXUm; val y0 = spec.wallStartY(w) + spec.originYUm
        val ys = longArrayOf(y0, y0 + spec.leadUm, y0 + spec.leadUm + spec.fastUm, y0 + spec.wallLength)
        val back = "direction" in without && w.row == 0 && w.position == 1 && i % 2 == 1
        val order = if (back) ys.reversedArray() else ys
        return LongArray(8) { k -> if (k % 2 == 0) x else order[k / 2] }
    }

    /** Wall [w]'s corner (µm, placed): along +Y beside the wall, then square to the right. */
    fun cornerPath(w: PressureAdvanceWall): LongArray {
        val x = spec.wallXUm(w) + g.cornerX + spec.originXUm; val y0 = spec.wallStartY(w) + spec.leadUm + (spec.fastUm - g.cornerUp) / 2 + spec.originYUm
        return longArrayOf(x, y0, x, y0 + g.cornerUp, x + g.cornerOut, y0 + g.cornerUp)
    }

    fun bounds(): LongArray = longArrayOf(0, 0, spec.baseW, spec.baseD)
    fun parkUm(): Pair<Long, Long> = (spec.originXUm + spec.baseW / 2) to (spec.originYUm - g.parkFront)

    fun program(): PrintProgram {
        val steps = ArrayList<Step>()
        val ox = spec.originXUm; val oy = spec.originYUm
        fun lay(feature: String, label: String, outlines: List<Polygon>, z: Long, w: Long, hh: Long, pv: PathValues, angle: Double) {
            val (rings, lines) = PlateFill.fill(outlines, w, hh, angle)
            val ex = Exact(label, doubleArrayOf(pv.speed), pv.accel, pv.flowRatio, pv.retractMm)
            fun put(f: String, xy: LongArray, closed: Boolean) = steps.add(Extrude(f, LongArray(xy.size) { k -> xy[k] + if (k % 2 == 0) ox else oy }, closed, z, w, hh, tool = spec.tool, exact = ex))
            for (r in rings) put("Outer wall", r, true)
            for (l in lines) put(feature, l, false)
        }
        spec.levelGcode?.let { steps += plymeld.program.Custom("bed levelling (hardware.machine.<key>.gcode.level)", it) }
        steps += SetTemp(spec.tool, spec.firstTempC, wait = true)
        steps += SetFan(0.0)
        spec.framePa?.let { steps += SetPressureAdvance(spec.tool, it) }
        for (layer in 0 until g.baseLayers) {
            val z = spec.zTop(layer); val hz = spec.heightOf(layer)
            steps += LayerStart(layer, z, hz)
            if (layer == 1) { steps += SetFan(spec.fanPct); if (spec.firstTempC != spec.tempC) steps += SetTemp(spec.tool, spec.tempC, wait = false) }
            val first = layer == 0
            lay(if (first) "Bottom surface" else "Internal solid infill", spec.baseLabel, baseOutline(layer), z,
                if (first) spec.firstWidthUm else lw, hz, if (first) spec.adhesion else spec.frame, if (layer % 2 == 0) 45.0 else 135.0)
        }
        // the walls at their one temperature, waited for with the head parked in front of the plate
        if (spec.firstTempC != spec.tempC) {
            steps += LiftTo(spec.baseTop + spec.liftUm)
            val (px, py) = parkUm()
            steps += Park(px, py, 0.0)
            steps += SetTemp(spec.tool, spec.tempC, wait = true)
        }
        val h = spec.layerUm
        for (i in 0 until spec.wallLayers) {
            val z = spec.baseTop + (i + 1) * h
            steps += LayerStart(g.baseLayers + i, z, h)
            if (i < g.labelLayers) {
                spec.framePa?.let { steps += SetPressureAdvance(spec.tool, it) }
                for (w in spec.walls) lay("Outer wall", spec.labelCell(w), labelAndArrow(w), z, lw, h, spec.frame, if (i == 0) 0.0 else 90.0)
            }
            for ((k, w) in spec.walls.withIndex()) {
                val v = valueOf(w)
                if (v != null && !("command" in without && w.row == 1 && w.position == 1)) steps += SetPressureAdvance(spec.tool, v)
                val fast = if ("speed" in without && k == 0) spec.slowSpeed else w.fastSpeed
                steps += Extrude("Outer wall", wallPath(w, i), false, z, lw, h, tool = spec.tool,
                    exact = Exact(w.label, doubleArrayOf(spec.slowSpeed, fast, spec.slowSpeed), spec.accel, spec.flowRatio, spec.frame.retractMm))
                if (spec.corners && !("corner" in without && w.row == 0 && w.position == 1))
                    steps += Extrude("Outer wall", cornerPath(w), false, z, lw, h, tool = spec.tool,
                        exact = Exact(w.cornerLabel, doubleArrayOf(w.fastSpeed, w.fastSpeed), spec.accel, spec.flowRatio, spec.frame.retractMm))
            }
        }
        steps += LiftTo(spec.topZ + spec.liftUm)
        val bb = bounds().let { longArrayOf(it[0] + ox, it[1] + oy, it[2] + ox, it[3] + oy) }
        val fp = doubleArrayOf(bb[0] / 1000.0, bb[1] / 1000.0, bb[2] / 1000.0, bb[1] / 1000.0, bb[2] / 1000.0, bb[3] / 1000.0, bb[0] / 1000.0, bb[3] / 1000.0)
        return PrintProgram(steps, listOf(PrintObject("pressure_advance_plate", fp, (bb[0] + bb[2]) / 2000.0, (bb[1] + bb[3]) / 2000.0)), exact = true)
    }

    /** The plate's g-code: the machine's start and end, everything between exactly as specified; pressure advance only where the plate sets it. */
    fun gcode(s: plymeld.model.SliceSettings, foreignVars: Map<String, List<String>>, version: String): String =
        java.io.StringWriter().also { plymeld.gcode.KlipperWriter(s, foreignVars, version).write(program(), it) }.toString()
}
