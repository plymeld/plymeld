package plymeld.core.calibrate

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
 * The first-layer plate: one layer of first-layer tiles laid out as a table, like the melt plate, a base for its labels,
 * and a frame over the tiles' walls; printed exactly as specified, and peeled off the bed as ONE piece.
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
 * - **The base and its labels**: an L-shaped base along the plate's front and left, [FirstLayerGeometry.baseLayers] layers,
 *   its front-left corner cut off ([PlateMarks.CHAMFER], the origin every plate is read from), its first layer touching the
 *   tiles; on it, raised [FirstLayerGeometry.labelLayers] layers ([PlateMarks]'s glyphs): each column's temperature in front
 *   of it, each row's nozzle height left of it, each speed left of its group, [FirstLayerGeometry.labelGap] apart.
 * - **One piece**: every tile touches its neighbors and the base, so the plate's first layer is one sheet; after all the tiles,
 *   **the frame**: [FirstLayerGeometry.frameLayers] layers of rings over every tile's wall only, each a layer
 *   ([FirstLayerSpec.frameLayerUm]) above the one before, starting a layer above the tile; each ring reaches
 *   [FirstLayerGeometry.fuse] past its tile's edge, over its neighbor's wall, so the rings of touching tiles fuse into one
 *   lattice: stiff, and the raised edge the plate is peeled by. Each tile's inside, the part read, stays uncovered.
 *
 * The order: the first column's prime line and the base's first layer (at the prime height, which no tile is below), then
 * column by column coolest first (after the first, the head retracts, rises above every tile, parks in front of the plate,
 * waits for the column's temperature (M109), and lays the column's **prime line** along the base's front, not read), each
 * column's tiles lowest first (so the nozzle never works beside a finished line above it); then, at the last column's
 * temperature, the base's upper layers, set back [FirstLayerGeometry.baseSetback] from the tiles so the frame's rings
 * reaching over the tiles' edges land on the first layer; the labels; and the frame, lowest first. Each tile starts (and its
 * wall closes) at the middle of its back edge, its lines following from the back, their ends reaching into its wall by
 * the slicer's line-end overlap ([FirstLayerTiles.wallOverlap]). The part fan is off and the bed is the plate's own,
 * constant. Lengths in µm, speeds mm/s, flows mm³/s, accelerations mm/s².
 */

/** The first-layer plate's layout (µm). plymeld's choices, not measured: sizes and spacing only, no test value. */
object FirstLayerGeometry {
    /** A tile's length along X (its lines' direction), every tile alike: between the melt plate's shortest and longest rows (30 and 60 mm). */
    const val tileLength = 40_000L
    /** A tile's depth along Y, about (as many lines as fit at its height's bead spacing): room for its row's height label beside it. */
    const val tileDepth = 9_000L
    /** Between labels, and from a label to the base's edge or a tile. */
    const val labelGap = 1_500L
    /** A temperature label: this far right of its column's left edge. */
    const val labelIn = 2_000L
    /** A frame ring reaches this far past its tile's edge, over its neighbor's wall, so the rings of touching tiles print as one; the prime line overlaps the base's edge by as much. */
    const val fuse = 150L
    /** The base's layers (the first touching the tiles), and the labels' on it: as the melt and flow plates' labels stand. */
    const val baseLayers = 2
    const val labelLayers = 2
    /** The base's upper layers stand back this far from the tiles, so a frame ring reaching over a tile's edge lands on its first layer. */
    const val baseSetback = 300L
    /** The prime line's run along a column's front. */
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
    /** The first layer's height (µm): the prime lines and the base's first layer print at it, or at the lowest tile's height if lower; the tiles at their own. */
    val layerUm: Long,
    val filamentDiameterMm: Double,
    /** The flow ratio every line prints at. */
    val flowRatio: Double,
    val tiles: FirstLayerTiles,
    /** Each column's melt limit (mm³/s): the filament's maximum flow at the column's temperature; a tile over it is left out. */
    val capsMm3s: DoubleArray,
    /** The prime lines', base's, and labels' speed, acceleration, flow ratio, and retraction (the first layer's). */
    val prime: PathValues,
    /** The prime lines' and the base's first layer's line width (µm, the first layer's). */
    val firstWidthUm: Long,
    /**
     * The frame's values: [PathValues.speed] the machine's print speed before the melt limit holds it ([frameSpeed] holds
     * it), its acceleration, flow ratio, and retraction.
     */
    val frame: PathValues,
    /** The frame's, the base's upper layers', and the labels' line width (µm: the plate's ordinary line width) and layer height (µm: the plate's layer height). */
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
    /** The prime lines' and the base's first layer's height (µm): the first layer's, or the lowest tile's where that is lower (everything they touch is no lower). */
    val primeUm: Long get() = minOf(layerUm, tiles.heightsUm[0])
    /** The base's layer [n] (1-based) and label layer [n]'s heights (µm): a layer each above the base's first. */
    fun baseUm(n: Int): Long = primeUm + (n - 1) * frameLayerUm
    fun labelUm(n: Int): Long = baseUm(FirstLayerGeometry.baseLayers) + n * frameLayerUm
    val filamentMm2: Double get() = Math.PI * filamentDiameterMm * filamentDiameterMm / 4
    /** Bead cross-section of a [w] µm line [h] µm high, mm² (the writer's bead model). */
    fun beadMm2(w: Long, h: Long): Double { val hm = h / 1000.0; return hm * (w / 1000.0 - hm * (1 - Math.PI / 4)) }
    /** The labels' text: column [k]'s temperature ("205"), speed [r] ("40", mm/s), height [c] ("0.20", mm: every height to the hundredth, so the column of them reads alike). */
    fun tempText(k: Int): String = tempsC[k].toString()
    fun speedText(r: Int): String = PlateMarks.whole(tiles.speeds[r])
    fun heightText(c: Int): String = String.format(java.util.Locale.ROOT, "%.2f", tiles.heightsUm[c] / 1000.0)
    /**
     * Cell labels: column [k] ("block 1" the coolest) and its tile ([r], [c]), prime line, and frame ring [n] (1-based
     * layer) over tile ([r], [c]); the base's layer [n]; label layer [n] of column [k]'s temperature ("mark"), of speed
     * [r], and of row ([r], [c])'s height.
     */
    fun tileLabel(k: Int, r: Int, c: Int) = "block ${k + 1} ${tiles.label(r, c)}"
    fun primeLabel(k: Int) = "block ${k + 1} prime"
    fun frameLabel(k: Int, r: Int, c: Int, n: Int) = "block ${k + 1} frame ${r + 1}.${c + 1} $n"
    fun baseLabel(n: Int) = "block 1 base $n"
    fun markLabel(k: Int, n: Int) = "block ${k + 1} mark $n"
    fun speedLabelOf(r: Int, n: Int) = "block 1 speed ${r + 1} $n"
    fun heightLabelOf(r: Int, c: Int, n: Int) = "block 1 height ${r + 1}.${c + 1} $n"
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
 * "heightlabel" (row 1's height label left out), "chamfer" (the base's front-left corner square), "base" (the base's
 * upper layer left out), "baseapart" (the base's first layer 1 mm back from its edges, not touching the tiles), "omit" (every
 * tile printed, those over the melt limit too), "rowgap" (the rows 1 mm apart, not
 * touching), "corner" (each tile started at its front-left corner, its lines from the front), "order" (each column's
 * tiles highest first), "frame" (no frame), "framelayers" (one frame layer), "frameinside" (the frame rings 1 mm further
 * in, over the tiles' insides), "framelow" (the frame's first layer at its tiles' own height), "framelift" (no rise off
 * each frame ring before the travel on).
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
    private val tempH: Long get() = (0 until spec.blocks).maxOf { PlateMarks.labelHeight(spec.tempText(it)) }
    private val tempW: Long get() = (0 until spec.blocks).maxOf { PlateMarks.labelWidth(spec.tempText(it)) }
    private val speedW: Long get() = t.speeds.indices.maxOf { PlateMarks.labelWidth(spec.speedText(it)) }
    private val heightW: Long get() = t.heightsUm.indices.maxOf { PlateMarks.labelWidth(spec.heightText(it)) }
    private val heightH: Long get() = t.heightsUm.indices.maxOf { PlateMarks.labelHeight(spec.heightText(it)) }

    /** The base's front edge (µm): the prime lines run along it, overlapping it by [FirstLayerGeometry.fuse]. */
    val baseY0: Long get() = fw - g.fuse
    /** The room left of the tiles for the row labels (µm): a gap, the speeds, a gap, the heights, a gap. */
    val labelRoom: Long get() = g.labelGap + speedW + g.labelGap + heightW + g.labelGap
    /** Where the speed labels and the height labels start (µm). */
    val speedX: Long get() = g.labelGap
    val heightX: Long get() = g.labelGap + speedW + g.labelGap
    /** The tiles' front edge (µm): the base's front strip, the temperature labels on it with a gap either side. */
    val gridY0: Long get() = baseY0 + g.labelGap + tempH + g.labelGap
    /** Row [j]'s front edge (µm): the rows touching, front to back ("rowgap": 1 mm apart). */
    fun rowY(j: Int): Long = gridY0 + spec.rows.take(j).sumOf { (_, c) -> tileDepth(c) } + if ("rowgap" in without) j * 1_000L else 0L
    /** All the rows' depth (µm). */
    val gridD: Long get() = rowY(spec.rows.size) - gridY0
    /** Column [k]'s left edge (µm): the columns touching, left to right. */
    fun colX(k: Int): Long = labelRoom + k * g.tileLength
    /** The row index of tile ([r], [c]). */
    fun rowOf(r: Int, c: Int): Int = r * t.heightsUm.size + c

    /** Whether the labels fit their places: the temperature in its column's front, a height beside each row. */
    val labelsFit: Boolean get() = g.labelIn + tempW + g.labelGap <= g.tileLength && heightH + g.labelGap <= t.heightsUm.indices.minOf { tileDepth(it) }

    /** Tile ([r], [c]) of column [k], as a box (µm: x0, y0, x1, y1), placed before the plate is: printed or not. */
    fun tileBox(k: Int, r: Int, c: Int): LongArray {
        val x0 = colX(k); val y0 = rowY(rowOf(r, c))
        return longArrayOf(x0, y0, x0 + g.tileLength, y0 + tileDepth(c))
    }
    /** Column [k]'s prime line (µm, its center line): along the base's front edge under the column, right to left, ending at the column's left edge. */
    fun primePath(k: Int): LongArray { val x0 = colX(k); val y = fw / 2; return longArrayOf(x0 + g.primeLen - fw / 2, y, x0 + fw / 2, y) }

    /**
     * The base (µm): an L along the plate's front (under the columns' temperatures) and left (under the rows' labels),
     * reaching the tiles' front and left edges, its front-left corner cut off by [PlateMarks.CHAMFER] ("chamfer": square).
     */
    fun baseOutline(): Polygon {
        val x1 = colX(spec.blocks); val y0 = baseY0; val gy = gridY0; val lr = labelRoom; val back = gridY0 + gridD
        val ch = if ("chamfer" in without) 0L else PlateMarks.CHAMFER
        return if (ch > 0) Polygon(longArrayOf(ch, y0, x1, y0, x1, gy, lr, gy, lr, back, 0, back, 0, y0 + ch))
        else Polygon(longArrayOf(0, y0, x1, y0, x1, gy, lr, gy, lr, back, 0, back))
    }
    /** The base's layer [n] (1-based): the outline; above the first, set back [FirstLayerGeometry.baseSetback] from every edge ("baseapart": the first 1 mm back). */
    fun baseLayer(n: Int): List<Polygon> = when {
        n == 1 && "baseapart" in without -> Offset.offset(listOf(baseOutline()), -1_000L)
        n == 1 -> listOf(baseOutline())
        else -> Offset.offset(listOf(baseOutline()), -g.baseSetback)
    }

    /**
     * The labels, by cell stem (µm glyphs, without the layer): each column's temperature on the base's front strip,
     * [FirstLayerGeometry.labelIn] right of the column's left edge ("mark": the last column's showing the first's); on its
     * left strip, each row's height centered on its row, and each speed centered on its group's rows.
     */
    fun labels(): List<Pair<String, List<Polygon>>> {
        val out = ArrayList<Pair<String, List<Polygon>>>()
        for (k in 0 until spec.blocks)
            out += "mark $k" to PlateMarks.label(spec.tempText(if ("mark" in without && k == spec.blocks - 1) 0 else k), colX(k) + g.labelIn, baseY0 + g.labelGap)
        for (r in t.speeds.indices) {
            for (c in t.heightsUm.indices) {
                if ("heightlabel" in without && r == 0 && c == 0) continue
                val ht = spec.heightText(c); val tb = tileBox(0, r, c)
                out += "height $r.$c" to PlateMarks.label(ht, heightX, (tb[1] + tb[3]) / 2 - PlateMarks.labelHeight(ht) / 2)
            }
            if ("speedlabel" in without && r == 0) continue
            val st = spec.speedText(r)
            val y0 = tileBox(0, r, 0)[1]; val y1 = tileBox(0, r, t.heightsUm.size - 1)[3]
            out += "speed $r" to PlateMarks.label(st, speedX, (y0 + y1) / 2 - PlateMarks.labelHeight(st) / 2)
        }
        return out
    }
    /** A label's cell for its layer [n], from its stem in [labels]. */
    private fun labelCell(stem: String, n: Int): String {
        val (kind, id) = stem.split(' ')
        return when (kind) {
            "mark" -> spec.markLabel(id.toInt(), n)
            "speed" -> spec.speedLabelOf(id.toInt(), n)
            else -> id.split('.').let { (r, c) -> spec.heightLabelOf(r.toInt(), c.toInt(), n) }
        }
    }

    /** The plate's extent before it is placed (µm: x0, y0, x1, y1). */
    fun bounds(): LongArray = longArrayOf(0, 0, colX(spec.blocks), gridY0 + gridD)

    /** Where the head waits for each column's temperature: [FirstLayerGeometry.parkFront] in front of the plate, at its middle (bed, µm). */
    fun parkUm(): Pair<Long, Long> = (spec.originXUm + bounds()[2] / 2) to (spec.originYUm - g.parkFront)

    fun program(): PrintProgram {
        require(labelsFit) { "the labels do not fit: a column's temperature along its ${g.tileLength / 1000.0} mm front, a height beside each row" }
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
        val frameTop = maxOf(top + layers * spec.frameLayerUm, spec.labelUm(g.labelLayers))
        /** [outlines] filled at [z] in [w] µm lines [hz] high, as cell [cell] at the prime values, the nozzle rising off it. */
        fun solid(cell: String, outlines: List<Polygon>, z: Long, w: Long, hz: Long, angle: Double) {
            val ex = Exact(cell, doubleArrayOf(pv.speed), pv.accel, pv.flowRatio, pv.retractMm)
            val (rings, lines) = PlateFill.fill(outlines, w, hz, angle)
            for (ring in rings) steps += extrude("Outer wall", ring, true, z, ex, ox, oy, w, hz)
            for (line in lines) steps += extrude("Bottom surface", line, false, z, ex, ox, oy, w, hz)
            steps += LiftTo(z + t.liftUm)
        }
        for (k in 0 until spec.blocks) {
            if (k > 0) {
                // rise clear of every tile, retract, park off the plate, and wait there for this column's temperature (heating: coolest first)
                steps += LiftTo(maxOf(top, spec.baseUm(1)) + t.liftUm)
                val (px, py) = parkUm()
                steps += Park(px, py, 0.0)
                steps += SetTemp(spec.tool, spec.tempsC[k], wait = true)
            }
            // the prime line (un-retracts before it) along the base's front; then (the first column) the base's first layer,
            // at the prime height, before the tiles it touches, which are no lower
            if (!("prime" in without && k == spec.blocks - 1)) {
                steps += extrude("Skirt", primePath(k), false, h, Exact(spec.primeLabel(k), doubleArrayOf(pv.speed), pv.accel, pv.flowRatio, pv.retractMm), ox, oy, fw, h)
                steps += LiftTo(h + t.liftUm)
            }
            if (k == 0) solid(spec.baseLabel(1), baseLayer(1), spec.baseUm(1), fw, h, 45.0)
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
        // at the last column's temperature: the base's upper layers and the labels on it (no higher than the frame they stand
        // beside will be), then the frame, layer by layer, each layer's rings lowest first
        steps += LiftTo(top + t.liftUm)
        val fw2 = spec.frameWidthUm; val lh = spec.frameLayerUm
        if ("base" !in without) for (n in 2..g.baseLayers) solid(spec.baseLabel(n), baseLayer(n), spec.baseUm(n), fw2, lh, if (n % 2 == 0) 135.0 else 45.0)
        val glyphs = labels()
        for (n in 1..g.labelLayers) for ((stem, gl) in glyphs) solid(labelCell(stem, n), gl, spec.labelUm(n), fw2, lh, if (n % 2 == 0) 90.0 else 0.0)
        if (layers > 0) {
            val fv = spec.frame
            for (n in 1..layers) {
                val rings = (0 until spec.blocks).flatMap { k -> spec.rows.filter { (r, c) -> spec.printed(k, r, c) || "omit" in without }.map { (r, c) -> Triple(k, r, c) } }
                    .sortedBy { (_, _, c) -> t.heightsUm[c] }
                for ((k, r, c) in rings) {
                    val z = if ("framelow" in without && n == 1) t.heightsUm[c] else spec.frameUm(c, n)
                    steps += extrude("Outer wall", frameRing(k, r, c), true, z, Exact(spec.frameLabel(k, r, c, n), doubleArrayOf(spec.frameSpeed), fv.accel, fv.flowRatio, fv.retractMm), ox, oy, fw2, lh)
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
