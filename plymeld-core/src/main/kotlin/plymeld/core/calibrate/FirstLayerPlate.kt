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
 * The first-layer plate: one layer of first-layer tiles laid out as a table, like the melt plate, and a frame over their
 * walls; printed exactly as specified, and peeled off the bed as ONE piece.
 *
 * - **A column per nozzle temperature** ([FirstLayerSpec.tempsC]), coolest first, left to right ("block" k in the cells).
 * - **In each column, a row per test**: a group of rows per first-layer speed (the slowest at the front), and in each group
 *   a row per nozzle height (the lowest at the front). Every row is one **tile**: a strip [FirstLayerGeometry.tileLength]
 *   long and about [FirstLayerGeometry.tileDepth] deep, every tile the same size and its lines running the same way
 *   (along X), its row's speed and its height, the column's temperature. A row is the same test in every column, so a row
 *   read across the plate is that test by temperature.
 * - **Tiles over the melt limit are left out**, rather than suggest something is tested that is not: a tile whose flow
 *   (its speed x the bead area at its height x the flow ratio) would exceed its column's melt limit
 *   ([FirstLayerSpec.capsMm3s]) is not printed at all; no tile is slowed.
 * - **One piece**: every tile touches its neighbors edge to edge, up, down, and across the columns, so the plate's one
 *   layer is one sheet; after every tile, **the frame**: [FirstLayerGeometry.frameLayers] layers of lines over every tile's
 *   wall only, each a layer ([FirstLayerSpec.frameLayerUm]) above the one before, starting a layer above the tile; each
 *   tile's frame ring reaches [FirstLayerGeometry.fuse] past its edge, over its neighbor's wall, so the rings of touching
 *   tiles fuse into one lattice: the stiff, raised edge the plate is peeled by. Each tile's inside, the part read, stays
 *   uncovered. Not read; the plate's ordinary line width, the machine's print speed and acceleration held to the hottest
 *   column's melt limit, after the last column at its temperature.
 * - **The labels** ([PlateMarks]'s glyphs, one layer like the rest, at the prime line's height): each column's temperature
 *   in front of it, standing on its prime line; at the left of the first column, each row's nozzle height, and each speed
 *   group's speed (turned to read from the front, beside its rows). A label with nothing under it stands on a **rail** (one
 *   first-layer line along its glyphs' baseline): each height on a rail into its row's first tile, each speed on a rail that
 *   joins its rows' height rails, so every digit is part of the one piece. A row the first column leaves out has no label.
 *
 * Columns print coolest first, heating between them: after a column the head retracts, rises above every tile, parks in
 * front of the plate, and waits for the next temperature (M109), then lays that column's **prime line** (its own cell,
 * not read: along the column's front, then back along its left edge to touch its first tile) and its temperature label,
 * then its tiles lowest first (so the nozzle never works beside a finished line above it). The first column also lays the
 * row labels before its tiles. Each tile starts (and its wall closes) at the middle of its back edge, its lines following
 * from the back, their ends reaching into its wall by the slicer's line-end overlap ([FirstLayerTiles.wallOverlap]). The
 * part fan is off and the bed is the plate's own, constant. Lengths in µm, speeds mm/s, flows mm³/s, accelerations mm/s².
 */

/** The first-layer plate's layout (µm). plymeld's choices, not measured: sizes and spacing only, no test value. */
object FirstLayerGeometry {
    /** A tile's length along X (its lines' direction), every tile alike: between the melt plate's shortest and longest rows (30 and 60 mm). */
    const val tileLength = 40_000L
    /** A tile's depth along Y, about (as many lines as fit at its height's bead spacing): room for its row's height label beside it. */
    const val tileDepth = 9_000L
    /**
     * The labels: the column's temperature [labelFront] in front of its tiles, [labelIn] right of the prime line's turn; a
     * row's height [labelGap] left of its tile. A label with no base under it stands on a rail (one first-layer line along
     * its glyphs' baseline) that joins it to the plate; rail and glyphs, and a frame ring and its neighbor's, overlap by
     * [fuse] so they print as one.
     */
    const val labelFront = PlateGeometry.markGap; const val labelIn = 2_000L; const val labelGap = 1_000L
    const val fuse = 150L
    /** The prime line's run along a column's front: this long, or longer where the column's temperature label reaches further (it holds it). */
    const val primeLen = 30_000L
    /** The head parks this far in front of the plate between columns (as the filament plate parks for its waits). */
    const val parkFront = 10_000L
    /** The frame's layers over the tiles' walls: enough to make a stiff, raised edge to peel the plate by. */
    const val frameLayers = 3
}

class FirstLayerSpec(
    /** The program's tool (0-based) and its spool's name. */
    val tool: Int,
    val spool: String,
    /** One column per temperature, in print order: coolest first. */
    val tempsC: IntArray,
    /** The first layer's height (µm): the prime lines and labels print at it, or at the lowest tile's height if lower; the tiles at their own. */
    val layerUm: Long,
    val filamentDiameterMm: Double,
    /** The flow ratio every line prints at. */
    val flowRatio: Double,
    val tiles: FirstLayerTiles,
    /** Each column's melt limit (mm³/s): the filament's maximum flow at the column's temperature; a tile over it is left out. */
    val capsMm3s: DoubleArray,
    /** The prime lines' and labels' speed, acceleration, flow ratio, and retraction (the first layer's). */
    val prime: PathValues,
    /** The prime lines' and labels' line width (µm, the first layer's). */
    val firstWidthUm: Long,
    /**
     * The frame's values: [PathValues.speed] the machine's print speed before the melt limit holds it ([frameSpeed] holds
     * it), its acceleration, flow ratio, and retraction.
     */
    val frame: PathValues,
    /** The frame's line width (µm: the plate's ordinary line width) and its layer height (µm: the plate's layer height). */
    val frameWidthUm: Long,
    val frameLayerUm: Long,
    /** Where the plate's front-left corner goes on the bed (µm). */
    val originXUm: Long = 0,
    val originYUm: Long = 0,
    /** The machine's bed levelling, run after its start template; null = none. */
    val levelGcode: String? = null,
    /** Where each column's melt limit came from (for the page and the command's output); empty = not said. */
    val capFrom: List<String> = emptyList(),
) {
    init {
        require(tempsC.isNotEmpty()) { "a first-layer plate needs at least one temperature" }
        require(tempsC.toList().zipWithNext().all { (a, b) -> b > a }) { "the columns print coolest first: ${tempsC.joinToString()}" }
        require(capsMm3s.size == tempsC.size) { "one melt limit per column: ${capsMm3s.size} for ${tempsC.size} columns" }
        require(frameLayerUm > 0) { "the frame's layer height must be above 0 (got $frameLayerUm µm)" }
        require(frameWidthUm in 1..tiles.widthUm + 2 * FirstLayerGeometry.fuse) { "the frame's line (${frameWidthUm / 1000.0} mm, the ordinary line width) is wider than a tile's wall and the overlap onto its neighbor: it would cover the tile's inside" }
    }
    val blocks: Int get() = tempsC.size
    /** The rows, front to back: (speed, height) indices, a group per speed, a row per height within it. */
    val rows: List<Pair<Int, Int>> get() = tiles.speeds.indices.flatMap { r -> tiles.heightsUm.indices.map { c -> r to c } }
    /** Tile ([r], [c])'s flow (mm³/s): its speed through its bead at its height, times the flow ratio. */
    fun tileFlow(r: Int, c: Int): Double = tiles.flow(r, c, flowRatio)
    /** Whether column [k]'s tile ([r], [c]) is printed: its flow within the column's melt limit; otherwise it is left out (never slowed). */
    fun printed(k: Int, r: Int, c: Int): Boolean = tileFlow(r, c) <= capsMm3s[k] + 1e-9
    /** The prime lines' and labels' height (µm): the first layer's, or the lowest tile's where that is lower (everything they touch is no lower). */
    val primeUm: Long get() = minOf(layerUm, tiles.heightsUm[0])
    val filamentMm2: Double get() = Math.PI * filamentDiameterMm * filamentDiameterMm / 4
    /** Bead cross-section of a [w] µm line [h] µm high, mm² (the writer's bead model). */
    fun beadMm2(w: Long, h: Long): Double { val hm = h / 1000.0; return hm * (w / 1000.0 - hm * (1 - Math.PI / 4)) }
    /** The labels' text: column [k]'s temperature ("205"), speed [r] ("40", mm/s), height [c] ("0.24", mm). */
    fun tempText(k: Int): String = tempsC[k].toString()
    fun speedText(r: Int): String = PlateMarks.whole(tiles.speeds[r])
    fun heightText(c: Int): String = PlateMarks.mm(tiles.heightsUm[c] / 1000.0)
    /**
     * Cell labels: column [k] ("block 1" the coolest) and its tile ([r], [c]), prime line, temperature label ("mark"), and
     * frame ring [n] (1-based layer) over tile ([r], [c]); the row labels (on the first column): speed [r]'s and row
     * ([r], [c])'s height.
     */
    fun tileLabel(k: Int, r: Int, c: Int) = "block ${k + 1} ${tiles.label(r, c)}"
    fun primeLabel(k: Int) = "block ${k + 1} prime"
    fun markLabel(k: Int) = "block ${k + 1} mark"
    fun frameLabel(k: Int, r: Int, c: Int, n: Int) = "block ${k + 1} frame ${r + 1}.${c + 1} $n"
    fun speedLabelOf(r: Int) = "block 1 speed ${r + 1}"
    fun heightLabelOf(r: Int, c: Int) = "block 1 height ${r + 1}.${c + 1}"
    /** Frame ring [n] (1-based)'s height over a tile at height [c]'s (µm): [n] layers above it. */
    fun frameUm(c: Int, n: Int): Long = tiles.heightsUm[c] + n * frameLayerUm
    /** The frame's speed (mm/s): the machine's print speed, held to the hottest column's melt limit through the frame's bead (it prints after the last column, at its temperature). */
    val frameSpeed: Double get() = minOf(frame.speed, capsMm3s.last() / (beadMm2(frameWidthUm, frameLayerUm) * frame.flowRatio))
    /** How far in from a tile's edge its frame ring's center lies (µm): its line reaches [FirstLayerGeometry.fuse] past the edge, over the neighbor's wall. */
    val frameInsetUm: Long get() = frameWidthUm / 2 - FirstLayerGeometry.fuse
}

/**
 * Builds the first-layer plate's program from its [spec]. [without]: for the read-back check's controls only, which must
 * catch them: "tilelift" (no rise off each tile before the travel on), "prime" (the last column's prime line left out),
 * "mark" (the last column's temperature label showing the first column's), "speedlabel" (speed 1's label left out),
 * "heightlabel" (row 1's height label left out), "omit" (every tile printed, those over the melt limit too), "rowgap" (the
 * rows 1 mm apart, not touching), "corner" (each tile started at its front-left corner, its lines from the front), "order"
 * (each column's tiles highest first), "frame" (no frame), "framelayers" (one frame layer), "frameinside" (the frame
 * rings 1 mm further in, over the tiles' insides), "framelow" (the frame's first layer at its tiles' own height),
 * "framelift" (no rise off each frame ring before the travel on).
 */
class FirstLayerPlate(private val spec: FirstLayerSpec, private val without: Set<String> = emptySet()) {
    private val t = spec.tiles
    private val g = FirstLayerGeometry
    private val fw = spec.firstWidthUm
    private val h = spec.primeUm

    /** Center-to-center distance of two touching lines in a tile at height [c] (the bead model's, at the tiles' width). */
    fun tileSpacing(c: Int): Long = Math.round(t.widthUm - t.heightsUm[c] * (1 - Math.PI / 4))
    /** Lines across a tile at height [c]: as many as make it about [FirstLayerGeometry.tileDepth] deep at its bead spacing. */
    fun tileLines(c: Int): Int { val sp = tileSpacing(c); return maxOf(2, ((g.tileDepth - t.widthUm - 2 * sp) / sp).toInt() + 1) }
    /** A tile's depth at height [c] (µm): its lines at the bead spacing, the wall round them touching them, the wall's own width. */
    fun tileDepth(c: Int): Long = (tileLines(c) - 1) * tileSpacing(c) + 2 * tileSpacing(c) + t.widthUm

    /** The labels' sizes (µm): the widest and tallest of each kind, so the layout does not depend on which are printed. */
    private val tempW: Long get() = (0 until spec.blocks).maxOf { PlateMarks.labelWidth(spec.tempText(it)) }
    private val tempH: Long get() = (0 until spec.blocks).maxOf { PlateMarks.labelHeight(spec.tempText(it)) }
    private val heightW: Long get() = t.heightsUm.indices.maxOf { PlateMarks.labelWidth(spec.heightText(it)) }
    private val heightH: Long get() = t.heightsUm.indices.maxOf { PlateMarks.labelHeight(spec.heightText(it)) }
    /** A speed label, turned to read from the front up its group: its glyphs' height across X, its length along Y. */
    private val speedThick: Long get() = t.speeds.indices.maxOf { PlateMarks.labelHeight(spec.speedText(it)) }
    private val speedLen: Long get() = t.speeds.indices.maxOf { PlateMarks.labelWidth(spec.speedText(it)) }

    /** The speed labels' rail (its center line's X, µm): along their glyphs' baseline, the height labels starting on it. */
    val speedRailX: Long get() = speedThick + fw / 2 - g.fuse
    /** Where the height labels start (µm), on the speed rail. */
    val heightX: Long get() = speedRailX + fw / 2 - g.fuse
    /** The room left of the tiles for the row labels (µm). */
    val labelRoom: Long get() = heightX + heightW + g.labelGap
    /** The tiles' front edge (µm): the prime line, the temperature label standing on it, and its gap in front of them. */
    val gridY0: Long get() = fw - g.fuse + tempH + g.labelFront
    /** Row [j]'s front edge (µm): the rows touching, front to back ("rowgap": 1 mm apart). */
    fun rowY(j: Int): Long = gridY0 + spec.rows.take(j).sumOf { (_, c) -> tileDepth(c) } + if ("rowgap" in without) j * 1_000L else 0L
    /** All the rows' depth (µm). */
    val gridD: Long get() = rowY(spec.rows.size) - gridY0
    /** Column [k]'s left edge (µm): the columns touching, left to right. */
    fun colX(k: Int): Long = labelRoom + k * g.tileLength
    /** The row index of tile ([r], [c]). */
    fun rowOf(r: Int, c: Int): Int = r * t.heightsUm.size + c

    /** Whether the labels fit their places: the temperature along its column's prime line, a height beside each row, a speed beside each group. */
    val labelsFit: Boolean get() = primeRun <= g.tileLength - g.labelIn && heightH + g.labelGap <= t.heightsUm.indices.minOf { tileDepth(it) } &&
        speedLen <= t.heightsUm.indices.sumOf { tileDepth(it) }

    /** Tile ([r], [c]) of column [k], as a box (µm: x0, y0, x1, y1), placed before the plate is: printed or not. */
    fun tileBox(k: Int, r: Int, c: Int): LongArray {
        val x0 = colX(k); val y0 = rowY(rowOf(r, c))
        return longArrayOf(x0, y0, x0 + g.tileLength, y0 + tileDepth(c))
    }
    /** A column's prime line's run along its front (µm, outer length, from its left edge): [FirstLayerGeometry.primeLen], or as far as its temperature label reaches. */
    val primeRun: Long get() = maxOf(g.primeLen, fw + g.labelIn + tempW)
    /**
     * Column [k]'s prime line (µm, its center line): along the column's front right to left, to its left edge, then back
     * along that edge to end touching its first tile's front edge (at its front-left corner), so it peels off with the plate.
     */
    fun primePath(k: Int): LongArray {
        val x0 = colX(k); val y = fw / 2
        return longArrayOf(x0 + primeRun - fw / 2, y, x0 + fw / 2, y, x0 + fw / 2, gridY0 - fw / 2)
    }

    /** Column [k]'s temperature label (µm): standing on its prime line, right of its turn ("mark": the last column's showing the first's). */
    fun tempLabel(k: Int): List<Polygon> =
        PlateMarks.label(spec.tempText(if ("mark" in without && k == spec.blocks - 1) 0 else k), colX(k) + g.labelIn, fw - g.fuse)

    /**
     * The row labels, by cell (µm): each its glyphs and its rail (an open center line). Each row's height left of its first
     * tile, standing on a rail into it; each speed group's speed turned to read from the front (its glyphs' tops toward
     * -X), centered on its rows, standing on a rail along X = [speedRailX] that joins its rows' height rails. A row the first
     * column leaves out has none, and a group with none has no speed label.
     */
    fun rowLabels(): List<Triple<String, List<Polygon>, LongArray>> {
        val out = ArrayList<Triple<String, List<Polygon>, LongArray>>()
        for (r in t.speeds.indices) {
            val rails = ArrayList<Long>()
            for (c in t.heightsUm.indices) {
                if (!spec.printed(0, r, c) || ("heightlabel" in without && r == 0 && c == 0)) continue
                val ht = spec.heightText(c); val hh = PlateMarks.labelHeight(ht)
                val tb = tileBox(0, r, c)
                val y0 = (tb[1] + tb[3]) / 2 - hh / 2
                val ry = y0 - fw / 2 + g.fuse
                rails += ry
                out += Triple(spec.heightLabelOf(r, c), PlateMarks.label(ht, heightX, y0), longArrayOf(heightX - g.fuse, ry, tb[0] + g.fuse, ry))
            }
            if (rails.isEmpty() || ("speedlabel" in without && r == 0)) continue
            val st = spec.speedText(r); val w = PlateMarks.labelWidth(st)
            val yc = (rails.min() + rails.max()) / 2
            val xb = speedRailX - fw / 2 + g.fuse
            val glyphs = PlateMarks.label(st, 0, 0).map { p -> Polygon(LongArray(p.xy.size) { i -> val u = p.xy[i - i % 2]; val v = p.xy[i - i % 2 + 1]; if (i % 2 == 0) xb - v else yc - w / 2 + u }) }
            val y0 = minOf(rails.min(), yc - w / 2); val y1 = maxOf(rails.max(), yc + w / 2)
            out += Triple(spec.speedLabelOf(r), glyphs, longArrayOf(speedRailX, y0, speedRailX, y1))
        }
        return out
    }

    /** The plate's extent before it is placed (µm: x0, y0, x1, y1). */
    fun bounds(): LongArray = longArrayOf(0, 0, colX(spec.blocks), gridY0 + gridD)

    /** Where the head waits for each column's temperature: [FirstLayerGeometry.parkFront] in front of the plate, at its middle (bed, µm). */
    fun parkUm(): Pair<Long, Long> = (spec.originXUm + bounds()[2] / 2) to (spec.originYUm - g.parkFront)

    fun program(): PrintProgram {
        require(labelsFit) { "the labels do not fit: a column's temperature along its ${g.tileLength / 1000.0} mm front, a height beside each row, a speed beside each group" }
        val steps = ArrayList<Step>()
        val ox = spec.originXUm; val oy = spec.originYUm
        spec.levelGcode?.let { steps += plymeld.program.Custom("bed levelling (hardware.machine.<key>.gcode.level)", it) }
        // the coolest column's temperature (the start template heats to it: this wait is a guard), the part fan off
        steps += SetTemp(spec.tool, spec.tempsC[0], wait = true)
        steps += SetFan(0.0)
        steps += LayerStart(0, h, h)
        val pv = spec.prime
        val top = t.heightsUm.max()
        val layers = if ("frame" in without) 0 else if ("framelayers" in without) 1 else g.frameLayers
        val frameTop = top + layers * spec.frameLayerUm
        fun label(cell: String, glyphs: List<Polygon>, rail: LongArray?) {
            val ex = Exact(cell, doubleArrayOf(pv.speed), pv.accel, pv.flowRatio, pv.retractMm)
            rail?.let { steps += extrude("Skirt", it, false, h, ex, ox, oy, fw, h) }
            val (rings, lines) = PlateFill.fill(glyphs, fw, h, 0.0)
            for (ring in rings) steps += extrude("Outer wall", ring, true, h, ex, ox, oy, fw, h)
            for (line in lines) steps += extrude("Bottom surface", line, false, h, ex, ox, oy, fw, h)
            steps += LiftTo(h + t.liftUm)
        }
        for (k in 0 until spec.blocks) {
            if (k > 0) {
                // rise clear of every tile, retract, park off the plate, and wait there for this column's temperature (heating: coolest first)
                steps += LiftTo(top + t.liftUm)
                val (px, py) = parkUm()
                steps += Park(px, py, 0.0)
                steps += SetTemp(spec.tool, spec.tempsC[k], wait = true)
            }
            // the prime line (un-retracts before it) and the temperature label on it, then (the first column) the row labels:
            // all at the prime height, before the tiles they touch, which are no lower
            if (!("prime" in without && k == spec.blocks - 1))
                steps += extrude("Skirt", primePath(k), false, h, Exact(spec.primeLabel(k), doubleArrayOf(pv.speed), pv.accel, pv.flowRatio, pv.retractMm), ox, oy, fw, h)
            label(spec.markLabel(k), tempLabel(k), null)
            if (k == 0) for ((cell, glyphs, rail) in rowLabels()) label(cell, glyphs, rail)
            // the tiles, lowest first (by height, then speed), each at its own height and speed; the nozzle rises by the travel
            // lift off each (the travel on crosses finished lines) and comes down to the next one's height
            val order = spec.rows.sortedWith(compareBy({ it.second }, { it.first })).let { if ("order" in without) it.reversed() else it }
            for ((r, c) in order) {
                if (!spec.printed(k, r, c) && "omit" !in without) continue
                val hz = t.heightsUm[c]
                val ex = Exact(spec.tileLabel(k, r, c), doubleArrayOf(t.speeds[r]), t.accel, spec.flowRatio, pv.retractMm)
                val (ring, lines) = tilePaths(k, r, c)
                steps += extrude("Outer wall", ring, true, hz, ex, ox, oy, t.widthUm, hz)
                steps += extrude("Bottom surface", lines, false, hz, ex, ox, oy, t.widthUm, hz)
                if ("tilelift" !in without) steps += LiftTo(hz + t.liftUm)
            }
        }
        // the frame, after every tile, at the last column's temperature: layer by layer, each layer's rings lowest first
        if (layers > 0) {
            steps += LiftTo(top + t.liftUm)
            val fv = spec.frame
            for (n in 1..layers) {
                val rings = (0 until spec.blocks).flatMap { k -> spec.rows.filter { (r, c) -> spec.printed(k, r, c) || "omit" in without }.map { (r, c) -> Triple(k, r, c) } }
                    .sortedBy { (_, _, c) -> t.heightsUm[c] }
                for ((k, r, c) in rings) {
                    val z = if ("framelow" in without && n == 1) t.heightsUm[c] else spec.frameUm(c, n)
                    steps += extrude("Outer wall", frameRing(k, r, c), true, z, Exact(spec.frameLabel(k, r, c, n), doubleArrayOf(spec.frameSpeed), fv.accel, fv.flowRatio, fv.retractMm), ox, oy, spec.frameWidthUm, spec.frameLayerUm)
                    if ("framelift" !in without) steps += LiftTo(z + t.liftUm)
                }
            }
        }
        // above every line on the plate before the end template moves away
        steps += LiftTo(frameTop + t.liftUm)
        val bb = bounds().let { longArrayOf(it[0] + ox, it[1] + oy, it[2] + ox, it[3] + oy) }
        val fp = doubleArrayOf(bb[0] / 1000.0, bb[1] / 1000.0, bb[2] / 1000.0, bb[1] / 1000.0, bb[2] / 1000.0, bb[3] / 1000.0, bb[0] / 1000.0, bb[3] / 1000.0)
        return PrintProgram(steps, listOf(PrintObject("first_layer_plate", fp, (bb[0] + bb[2]) / 2000.0, (bb[1] + bb[3]) / 2000.0)), exact = true, selfPrimed = true)
    }

    /** The frame's ring over tile ([r], [c]) of column [k] (µm, closed): [FirstLayerSpec.frameInsetUm] in from its edges ("frameinside": 1 mm further in). */
    fun frameRing(k: Int, r: Int, c: Int): LongArray {
        val b = tileBox(k, r, c); val d = spec.frameInsetUm + if ("frameinside" in without) 1_000L else 0L
        return longArrayOf(b[0] + d, b[1] + d, b[2] - d, b[1] + d, b[2] - d, b[3] - d, b[0] + d, b[3] - d)
    }

    /** The plate's g-code: the machine's start and end, everything between exactly as specified. [pressureAdvance]: by tool. */
    fun gcode(s: plymeld.model.SliceSettings, foreignVars: Map<String, List<String>>, version: String, pressureAdvance: Map<Int, Double> = emptyMap()): String =
        java.io.StringWriter().also { plymeld.gcode.KlipperWriter(s, foreignVars, version, pressureAdvance = pressureAdvance).write(program(), it) }.toString()

    /**
     * Tile ([r], [c]) of column [k]'s paths: its wall (a closed ring half a line in from its edge, starting and ending at
     * the middle of its back edge) and its lines along X (one open path, back and forth from the back, each one bead
     * spacing from the next and from the wall at its sides; their ends past that by [FirstLayerTiles.wallOverlap] of a
     * line, into the wall at the left and right: a line end touches the wall only with its round cap).
     */
    private fun tilePaths(k: Int, r: Int, c: Int): Pair<LongArray, LongArray> {
        val b = tileBox(k, r, c); val tw = t.widthUm; val sp = tileSpacing(c)
        val r0x = b[0] + tw / 2; val r0y = b[1] + tw / 2; val r1x = b[2] - tw / 2; val r1y = b[3] - tw / 2
        val corner = "corner" in without
        val ring = if (corner) longArrayOf(r0x, r0y, r1x, r0y, r1x, r1y, r0x, r1y)
            else ((r0x + r1x) / 2).let { mx -> longArrayOf(mx, r1y, r0x, r1y, r0x, r0y, r1x, r0y, r1x, r1y) }
        val ov = Math.round(t.wallOverlap * tw)
        val f0x = r0x + sp - ov; val f1x = r1x - sp + ov
        val pts = ArrayList<Long>()
        for (i in 0 until tileLines(c)) {
            val y = if (corner) r0y + sp + i * sp else r1y - sp - i * sp
            if (i % 2 == 0) { pts += f0x; pts += y; pts += f1x; pts += y } else { pts += f1x; pts += y; pts += f0x; pts += y }
        }
        return ring to pts.toLongArray()
    }

    /** A path of the plate, placed on the bed. */
    private fun extrude(feature: String, xy: LongArray, closed: Boolean, zUm: Long, ex: Exact, ox: Long, oy: Long, widthUm: Long, heightUm: Long): Extrude {
        val moved = LongArray(xy.size) { i -> xy[i] + if (i % 2 == 0) ox else oy }
        return Extrude(feature, moved, closed, zUm, widthUm, heightUm, tool = spec.tool, exact = ex)
    }
}
