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
 * The first-layer plate (plates split by temperature regime, the first-layer tiles leave the filament plate and become
 * their own plate, with temperature varied within the first layer). One layer of tiles (and, a frame over their walls:
 * below): a **block** of first-layer tiles per nozzle temperature ([FirstLayerSpec.tempsC]), each block a grid of
 * tiles (a row per first-layer speed, the slowest at the front; a column per nozzle height, the lowest on the left;
 * each tile at its own height), printed exactly as specified.
 *
 * **Tiles over the melt limit are left out**, rather than suggest something is tested that is not: a
 * tile whose flow (its row's speed x the bead area at its height x the flow ratio) would
 * exceed the block's melt limit ([FirstLayerSpec.capsMm3s], the filament's maximum flow at the block's temperature) is
 * not printed at all; no tile is slowed.
 *
 * **Each block is one piece**, so there is less to peel off the bed: a column's tiles touch edge to edge (each column peels as one strip); between columns, two-line
 * **connectors** at the lower column's height join the columns' front and back corners; the prime line ends touching the
 * block's first tile and the marker dots touch the prime line. Each tile starts (and its wall closes) at the middle of
 * its back edge, its lines following from the back, their ends reaching into its wall by the slicer's
 * line-end overlap ([FirstLayerTiles.wallOverlap]).
 *
 * **The frame**: after each
 * block's first layer, a second layer of lines laid ONLY over its tiles' walls, one layer ([FirstLayerSpec.frameLayerUm])
 * above each column's tiles: round each column's printed tiles and along every edge two of its tiles share (one line over
 * both walls), so each tile's inside, the part that is read, stays uncovered. Frame lines across the gaps between columns
 * lie on the connectors, at the higher column's frame height, so the block is one lattice at the frame's level too. Not
 * read; the plate's ordinary line width, the machine's print speed and acceleration held to the block's melt limit (as the
 * filament plate's frame), part fan off (as the whole plate). Columns from the lowest; the nozzle lifts off every frame
 * line.
 *
 * Blocks print coolest first, heating between them (a nozzle heats faster than it cools): after a block the head
 * retracts, rises above every tile and parks in front of the plate, waits for the next temperature (M109), then lays
 * that block's **prime line** (its own cell, not read): every block has one, the first included (the machine's start
 * template primes elsewhere on the bed). In front of each block's tiles, block n's **marker**: n small square dots
 * (coolest = 1), so the blocks are told apart by geometry, not text. Blocks are laid out left to right, wrapping to a
 * further row of blocks behind ([FirstLayerSpec.perRow] to a row). The part fan is off and the bed is the plate's own
 * (the start template's), constant. Lengths in µm, speeds mm/s, flows mm³/s, accelerations mm/s².
 */

/** The first-layer plate's layout (µm). placeholders, not measured: spacing only, no test value. */
object FirstLayerGeometry {
    /** Between blocks, side by side and row to row. */
    const val blockGap = 6_000L
    /** A marker dot: this square; dots this far apart, in one row touching the prime line's back edge; the dots this far in front of the block's tiles. */
    const val dot = PlateGeometry.markSize; const val dotGap = 2_000L; const val dotsFront = PlateGeometry.markGap
    /** The prime line's run along the block's front: this long, or longer where the block's marker dots reach further (it holds them). */
    const val primeLen = 30_000L
    /** The head parks this far in front of the plate between blocks (as the filament plate parks for its waits). */
    const val parkFront = 10_000L
    /** Lines in each connector between two columns (plymeld's choice: two side by side, at each gap's front and back corners). */
    const val connectorLines = 2
}

class FirstLayerSpec(
    /** The program's tool (0-based) and its spool's name. */
    val tool: Int,
    val spool: String,
    /** One block per temperature, in print order: coolest first. */
    val tempsC: IntArray,
    /** The first layer's height (µm): the marker dots print at it, the prime lines at it or the lowest tile's height if lower; the tiles at their own. */
    val layerUm: Long,
    val filamentDiameterMm: Double,
    /** The flow ratio every line prints at. */
    val flowRatio: Double,
    val tiles: FirstLayerTiles,
    /** Each block's melt limit (mm³/s): the filament's maximum flow at the block's temperature; a tile over it is left out. */
    val capsMm3s: DoubleArray,
    /** The prime lines', connectors' and marker dots' speed, acceleration, flow ratio, and retraction (the first layer's). */
    val prime: PathValues,
    /** The prime lines', connectors', and marker dots' line width (µm, the first layer's). */
    val firstWidthUm: Long,
    /**
     * The frame's values: [PathValues.speed] the machine's print speed before the melt limit holds it ([frameSpeed] holds
     * it, block by block), its acceleration, flow ratio, and retraction.
     */
    val frame: PathValues,
    /** The frame's line width (µm: the plate's ordinary line width) and its layer height (µm: the plate's layer height). */
    val frameWidthUm: Long,
    val frameLayerUm: Long,
    /** Blocks to a row (left to right); further rows behind. */
    val perRow: Int,
    /** Where the plate's front-left corner goes on the bed (µm). */
    val originXUm: Long = 0,
    val originYUm: Long = 0,
    /** The machine's bed levelling, run after its start template; null = none. */
    val levelGcode: String? = null,
    /** Where each block's melt limit came from (for the page and the command's output); empty = not said. */
    val capFrom: List<String> = emptyList(),
) {
    init {
        require(tempsC.isNotEmpty()) { "a first-layer plate needs at least one temperature" }
        require(tempsC.toList().zipWithNext().all { (a, b) -> b > a }) { "the blocks print coolest first: ${tempsC.joinToString()}" }
        require(perRow >= 1) { "blocks to a row: at least 1 (got $perRow)" }
        require(capsMm3s.size == tempsC.size) { "one melt limit per block: ${capsMm3s.size} for ${tempsC.size} blocks" }
        require(frameLayerUm > 0) { "the frame's layer height must be above 0 (got $frameLayerUm µm)" }
        // the frame line over two touching tiles' shared edge covers half its width of each wall: no wider than both walls
        require(frameWidthUm in 1..2 * tiles.widthUm) { "the frame's line (${frameWidthUm / 1000.0} mm, the ordinary line width) is wider than two tile walls (${2 * tiles.widthUm / 1000.0} mm): over the edge between two tiles it would cover their insides" }
    }
    val blocks: Int get() = tempsC.size
    /** Tile ([r], [c])'s flow (mm³/s): its row's speed through its bead at its height, times the flow ratio. */
    fun tileFlow(r: Int, c: Int): Double = tiles.flow(r, c, flowRatio)
    /** Whether block [k]'s tile ([r], [c]) is printed: its flow within the block's melt limit; otherwise it is left out (never slowed). */
    fun printed(k: Int, r: Int, c: Int): Boolean = tileFlow(r, c) <= capsMm3s[k] + 1e-9
    /** Block [k]'s printed tiles in column [c], counted from the front (speeds and heights rise, so they are the front rows). */
    fun rowsIn(k: Int, c: Int): Int = tiles.speeds.indices.count { printed(k, it, c) }
    /** The prime lines' height (µm): the first layer's, or the lowest tile's where that is lower (the prime line ends touching that tile). */
    val primeUm: Long get() = minOf(layerUm, tiles.heightsUm[0])
    /** The connectors between columns [c] and [c] + 1: at the lower of the two heights. */
    fun connectorUm(c: Int): Long = minOf(tiles.heightsUm[c], tiles.heightsUm[c + 1])
    val filamentMm2: Double get() = Math.PI * filamentDiameterMm * filamentDiameterMm / 4
    /** Bead cross-section of a [w] µm line [h] µm high, mm² (the writer's bead model). */
    fun beadMm2(w: Long, h: Long): Double { val hm = h / 1000.0; return hm * (w / 1000.0 - hm * (1 - Math.PI / 4)) }
    /** Cell labels: block [k] (0-based; "block 1" is the coolest) and its tile ([r], [c]), prime line, marker dots, and connectors. */
    fun tileLabel(k: Int, r: Int, c: Int) = "block ${k + 1} ${tiles.label(r, c)}"
    fun primeLabel(k: Int) = "block ${k + 1} prime"
    fun markLabel(k: Int) = "block ${k + 1} mark"
    /** Block [k]'s connector between columns [c] and [c] + 1 (from 1 in the label), at their front or [back] corners. */
    fun connectorLabel(k: Int, c: Int, back: Boolean) = "block ${k + 1} connector ${c + 1}-${c + 2} ${if (back) "back" else "front"}"
    /** Block [k]'s frame over column [c]'s tile walls, and its frame line across the gap from column [c] to [c] + 1 at their front or [back] corners. */
    fun frameLabel(k: Int, c: Int) = "block ${k + 1} frame ${c + 1}"
    fun bridgeLabel(k: Int, c: Int, back: Boolean) = "block ${k + 1} frame ${c + 1}-${c + 2} ${if (back) "back" else "front"}"
    /** Column [c]'s frame height (µm): one layer above its tiles' top. */
    fun frameUm(c: Int): Long = tiles.heightsUm[c] + frameLayerUm
    /** The frame line across the gap between columns [c] and [c] + 1: at the higher column's frame height. */
    fun bridgeUm(c: Int): Long = maxOf(frameUm(c), frameUm(c + 1))
    /** The frame's speed in block [k] (mm/s): the machine's print speed, held to the block's melt limit through the frame's bead. */
    fun frameSpeed(k: Int): Double = minOf(frame.speed, capsMm3s[k] / (beadMm2(frameWidthUm, frameLayerUm) * frame.flowRatio))
    /** Where a frame line runs over a tile's wall (µm in from the tile's edge): on the wall's center, or, for a frame line wider than the wall, with its inner edge on the wall's. */
    val frameInsetUm: Long get() = minOf(tiles.widthUm / 2, tiles.widthUm - frameWidthUm / 2)
}

/**
 * Builds the first-layer plate's program from its [spec]. [without]: for the read-back check's controls only, which must
 * catch them: "tilelift" (no rise off each tile before the travel on), "prime" (the last block's prime line left out),
 * "mark" (the last block's marker one dot short), "omit" (every tile printed, those over the melt limit too), "colgap"
 * (a column's tiles 3 mm apart, as before), "connectors" (none), "conninside" (each connector reaching 1 mm
 * into the higher column's tile), "connhigh" (connectors at the higher column's height), "connlate" (each gap's
 * connectors printed after the higher column's tiles), "corner" (each tile started at its front-left corner, its lines
 * from the front, as before), "frame" (no frame), "frameinside" (each column's frame ring 1 mm further in, over
 * its tiles' insides), "framebridges" (no frame lines across the gaps: each column's frame a piece of its own), "framelow"
 * (the frame at its tiles' own height, not a layer above), "framelift" (no rise off each frame line before the travel on).
 */
class FirstLayerPlate(private val spec: FirstLayerSpec, private val without: Set<String> = emptySet()) {
    private val t = spec.tiles
    /** Column to column: a tile and the gap the connectors cross. */
    private val pitch = PlateGeometry.tileAbout + PlateGeometry.tileGap
    private val fw = spec.firstWidthUm
    private val h = spec.layerUm

    /** Center-to-center distance of two touching lines in tile column [c] (the bead model's, at its height and the tiles' width). */
    fun tileSpacing(c: Int): Long = Math.round(t.widthUm - t.heightsUm[c] * (1 - Math.PI / 4))
    /** Lines across a tile in column [c]: as many as make it about [PlateGeometry.tileAbout] square at its bead spacing. */
    fun tileLines(c: Int): Int { val sp = tileSpacing(c); return maxOf(2, ((PlateGeometry.tileAbout - t.widthUm - 2 * sp) / sp).toInt() + 1) }
    /** A tile's outer size in column [c] (µm): its lines at the bead spacing, the wall round them touching them, the wall's own width. */
    fun tileSize(c: Int): Long = (tileLines(c) - 1) * tileSpacing(c) + 2 * tileSpacing(c) + t.widthUm
    /** Row to row in column [c]: the tile's own size, so the column's tiles touch. */
    fun rowPitch(c: Int): Long = tileSize(c) + if ("colgap" in without) PlateGeometry.tileGap else 0L

    /** A block's tile grid: as wide as its columns reach, as deep as its longest column (every row counted, printed or not). */
    val gridW: Long get() = t.heightsUm.indices.maxOf { it * pitch + tileSize(it) }
    val gridD: Long get() = t.heightsUm.indices.maxOf { (t.speeds.size - 1) * rowPitch(it) + tileSize(it) }
    /** Block [k]'s marker dots, end to end (µm). */
    private fun dotsLen(k: Int): Long = (k + 1) * (FirstLayerGeometry.dot + FirstLayerGeometry.dotGap) - FirstLayerGeometry.dotGap
    /** Block [k]'s prime line's run along its front (µm, outer length): [FirstLayerGeometry.primeLen], or as far as its marker dots reach. */
    fun primeRun(k: Int): Long = maxOf(FirstLayerGeometry.primeLen, fw + FirstLayerGeometry.dotGap + dotsLen(k))
    /** Whether every block's prime line and marker dots fit across its block (one row of dots: at most about a dozen blocks). */
    val dotsFit: Boolean get() = (0 until spec.blocks).all { primeRun(it) <= gridW }
    /** A block's footprint: its tiles, its marker dots in front of them, its prime line in front of those (touching them). */
    val blockW: Long get() = gridW
    val blockD: Long get() = fw + FirstLayerGeometry.dot + FirstLayerGeometry.dotsFront + gridD
    /** Rows of blocks, and blocks in the widest row. */
    val blockRows: Int get() = (spec.blocks + spec.perRow - 1) / spec.perRow
    val blockCols: Int get() = minOf(spec.perRow, spec.blocks)

    /** Block [k]'s front-left corner (µm, before the plate is placed): left to right, then the next row behind. */
    fun blockXY(k: Int): Pair<Long, Long> = (k % spec.perRow) * (blockW + FirstLayerGeometry.blockGap) to (k / spec.perRow) * (blockD + FirstLayerGeometry.blockGap)
    /** Block [k]'s tile grid's front edge (µm). */
    fun gridY0(k: Int): Long = blockXY(k).second + blockD - gridD
    /** Tile ([r], [c]) of block [k], as a box (µm: x0, y0, x1, y1), from the block's left edge; printed or not. */
    fun tileBox(k: Int, r: Int, c: Int): LongArray {
        val x0 = blockXY(k).first + c * pitch; val y0 = gridY0(k) + r * rowPitch(c); val sz = tileSize(c)
        return longArrayOf(x0, y0, x0 + sz, y0 + sz)
    }
    /** Dot [i] (0-based) of block [k]'s marker, as a box (µm): one row, touching the prime line's back edge, right of its turn. */
    fun dotBox(k: Int, i: Int): LongArray {
        val (bx, by) = blockXY(k)
        val x0 = bx + fw + FirstLayerGeometry.dotGap + i * (FirstLayerGeometry.dot + FirstLayerGeometry.dotGap); val y0 = by + fw
        return longArrayOf(x0, y0, x0 + FirstLayerGeometry.dot, y0 + FirstLayerGeometry.dot)
    }
    /**
     * Block [k]'s prime line (µm, its center line): along the block's front edge right to left, to its left edge, then
     * back along that edge to end touching the first tile's front edge (at its front-left corner), so it peels off with
     * the block.
     */
    fun primePath(k: Int): LongArray {
        val (bx, by) = blockXY(k)
        val y = by + fw / 2
        return longArrayOf(bx + primeRun(k) - fw / 2, y, bx + fw / 2, y, bx + fw / 2, gridY0(k) - fw / 2)
    }
    /**
     * Block [k]'s connector between columns [c] and [c] + 1 at their front or [back] corners (µm, its center line), or null
     * where either column prints no tile: [FirstLayerGeometry.connectorLines] lines across the gap, back and forth, one
     * bead spacing apart at its height, touching both columns' edges and inside neither. The back one at the back corner
     * of the rows both columns print.
     */
    fun connectorPath(k: Int, c: Int, back: Boolean): LongArray? {
        val rows = minOf(spec.rowsIn(k, c), spec.rowsIn(k, c + 1)); if (rows == 0) return null
        val xa = tileBox(k, 0, c)[2] + fw / 2
        val xb = tileBox(k, 0, c + 1)[0] - fw / 2 + if ("conninside" in without) 1_000L else 0L
        val sp = Math.round(fw - connectorZ(c) * (1 - Math.PI / 4))
        val y0 = if (!back) gridY0(k) + fw / 2 else minOf(tileBox(k, rows - 1, c)[3], tileBox(k, rows - 1, c + 1)[3]) - fw / 2
        val dy = if (back) -sp else sp
        val pts = ArrayList<Long>()
        for (i in 0 until FirstLayerGeometry.connectorLines) { val y = y0 + i * dy; if (i % 2 == 0) { pts += xa; pts += y; pts += xb; pts += y } else { pts += xb; pts += y; pts += xa; pts += y } }
        return pts.toLongArray()
    }
    private fun connectorZ(c: Int): Long = if ("connhigh" in without) maxOf(t.heightsUm[c], t.heightsUm[c + 1]) else spec.connectorUm(c)

    /**
     * Block [k]'s frame over column [c] (µm, center lines), or null where the column prints no tile: a closed ring round the
     * column's printed tiles over their outer walls ([FirstLayerSpec.frameInsetUm] in from their edges), from its front-left
     * corner, then one open line along each edge two of its tiles share (centered on that edge, over both walls).
     */
    fun framePaths(k: Int, c: Int): Pair<LongArray, List<LongArray>>? {
        val rows = spec.rowsIn(k, c); if (rows == 0) return null
        val ins = spec.frameInsetUm + if ("frameinside" in without) 1_000L else 0L
        val f = tileBox(k, 0, c); val b = tileBox(k, rows - 1, c)
        val x0 = f[0] + ins; val x1 = f[2] - ins; val y0 = f[1] + ins; val y1 = b[3] - ins
        val ring = longArrayOf(x0, y0, x1, y0, x1, y1, x0, y1)
        val seams = (0 until rows - 1).map { r -> val y = tileBox(k, r, c)[3]; if (r % 2 == 0) longArrayOf(x1, y, x0, y) else longArrayOf(x0, y, x1, y) }
        return ring to seams
    }

    /**
     * Block [k]'s frame line across the gap between columns [c] and [c] + 1 at their front or [back] corners (µm, its center
     * line), or null where either column prints no tile: straight across, from over column [c] + 1's left wall to over
     * column [c]'s right wall, on top of that corner's connector (in line with the frame's front line, or the back line of
     * the rows both columns print).
     */
    fun bridgePath(k: Int, c: Int, back: Boolean): LongArray? {
        val rows = minOf(spec.rowsIn(k, c), spec.rowsIn(k, c + 1)); if (rows == 0) return null
        val ins = spec.frameInsetUm
        val y = if (!back) gridY0(k) + ins else minOf(tileBox(k, rows - 1, c)[3], tileBox(k, rows - 1, c + 1)[3]) - ins
        return longArrayOf(tileBox(k, 0, c + 1)[0] + ins, y, tileBox(k, 0, c)[2] - ins, y)
    }

    /** The plate's extent before it is placed (µm: x0, y0, x1, y1). */
    fun bounds(): LongArray = longArrayOf(0, 0, blockCols * blockW + (blockCols - 1) * FirstLayerGeometry.blockGap, blockRows * blockD + (blockRows - 1) * FirstLayerGeometry.blockGap)

    /** Where the head waits for each block's temperature: [FirstLayerGeometry.parkFront] in front of the plate, at its middle (bed coordinates, µm). */
    fun parkUm(): Pair<Long, Long> = (spec.originXUm + bounds()[2] / 2) to (spec.originYUm - FirstLayerGeometry.parkFront)

    fun program(): PrintProgram {
        require(dotsFit) { "block ${spec.blocks}'s ${spec.blocks} marker dots do not fit across its ${gridW / 1000.0} mm block in one row" }
        val steps = ArrayList<Step>()
        val ox = spec.originXUm; val oy = spec.originYUm
        spec.levelGcode?.let { steps += plymeld.program.Custom("bed levelling (hardware.machine.<key>.gcode.level)", it) }
        // the coolest block's temperature (the start template heats to it: this wait is a guard), the part fan off
        steps += SetTemp(spec.tool, spec.tempsC[0], wait = true)
        steps += SetFan(0.0)
        steps += LayerStart(0, h, h)
        val pv = spec.prime
        val top = t.heightsUm.max()
        // the highest line on the plate: a frame, one layer above the highest tiles (or those tiles, without a frame)
        val frameTop = if ("frame" in without) top else top + spec.frameLayerUm
        for (k in 0 until spec.blocks) {
            if (k > 0) {
                // rise clear of every tile and frame, retract, park off the plate, and wait there for this block's temperature (heating: coolest first)
                steps += LiftTo(frameTop + t.liftUm)
                val (px, py) = parkUm()
                steps += Park(px, py, 0.0)
                steps += SetTemp(spec.tool, spec.tempsC[k], wait = true)
            }
            // the prime line (un-retracts before it), in front of the block, ending touching its first tile; nothing printed
            // is on its way there, so no lift off it (a lift where a travel crosses a finished tile)
            if (!("prime" in without && k == spec.blocks - 1))
                steps += extrude("Skirt", primePath(k), false, spec.primeUm, Exact(spec.primeLabel(k), doubleArrayOf(pv.speed), pv.accel, pv.flowRatio, pv.retractMm), ox, oy, fw, spec.primeUm)
            // the tiles: column by column from the left (lowest first), each column from the front, each tile at its own nozzle
            // height and its row's speed; the nozzle rises by the travel lift off each tile and connector (the travel on crosses
            // finished lines) and comes down to the next one's height over it. After each column, the connectors to the next
            // (at this column's height, the lower), before the next column's tiles, so the nozzle never works at a lower height
            // beside a finished tile
            val late = ArrayList<Step>()
            for (c in t.heightsUm.indices) {
                val hz = t.heightsUm[c]
                for (r in t.speeds.indices) {
                    if (!spec.printed(k, r, c) && "omit" !in without) continue
                    val ex = Exact(spec.tileLabel(k, r, c), doubleArrayOf(t.speeds[r]), t.accel, spec.flowRatio, pv.retractMm)
                    val (ring, lines) = tilePaths(k, r, c)
                    steps += extrude("Outer wall", ring, true, hz, ex, ox, oy, t.widthUm, hz)
                    steps += extrude("Bottom surface", lines, false, hz, ex, ox, oy, t.widthUm, hz)
                    if ("tilelift" !in without) steps += LiftTo(hz + t.liftUm)
                }
                steps += late; late.clear()
                if (c + 1 < t.heightsUm.size && "connectors" !in without) {
                    val cz = connectorZ(c)
                    for (back in listOf(true, false)) {
                        val path = connectorPath(k, c, back) ?: continue
                        val ex = Exact(spec.connectorLabel(k, c, back), doubleArrayOf(pv.speed), pv.accel, pv.flowRatio, pv.retractMm)
                        val mine = listOf(extrude("Skirt", path, false, cz, ex, ox, oy, fw, cz), LiftTo(cz + t.liftUm))
                        if ("connlate" in without) late += mine else steps += mine
                    }
                }
            }
            // the marker: k + 1 dots touching the prime line (one short for the control "mark", on the last block)
            val dots = if ("mark" in without && k == spec.blocks - 1) k else k + 1
            for (i in 0 until dots) for (ring in squareRings(dotBox(k, i)))
                steps += extrude("Outer wall", ring, true, h, Exact(spec.markLabel(k), doubleArrayOf(pv.speed), pv.accel, pv.flowRatio, pv.retractMm), ox, oy, fw, h)
            if ("frame" !in without) steps += frameSteps(k, top, ox, oy)
        }
        // above every line on the plate before the end template moves away
        steps += LiftTo(frameTop + t.liftUm)
        val bb = bounds().let { longArrayOf(it[0] + ox, it[1] + oy, it[2] + ox, it[3] + oy) }
        val fp = doubleArrayOf(bb[0] / 1000.0, bb[1] / 1000.0, bb[2] / 1000.0, bb[1] / 1000.0, bb[2] / 1000.0, bb[3] / 1000.0, bb[0] / 1000.0, bb[3] / 1000.0)
        return PrintProgram(steps, listOf(PrintObject("first_layer_plate", fp, (bb[0] + bb[2]) / 2000.0, (bb[1] + bb[3]) / 2000.0)), exact = true, selfPrimed = true)
    }

    /**
     * Block [k]'s frame, after its first layer (its tiles, connectors, prime line, and marker), at the block's temperature:
     * first a rise above every tile ([top] + the lift: the travel on crosses finished tiles), then column by column from the
     * lowest, each column's ring and shared-edge lines one layer above its tiles, and after each column (from the second)
     * the two frame lines across the gap to the column before it, at this column's frame height (the higher): so every
     * frame line goes down after every lower one it touches and the nozzle never works beside a finished line below its top.
     * The nozzle rises by the lift off every frame line (the travel on crosses finished frame lines).
     */
    private fun frameSteps(k: Int, top: Long, ox: Long, oy: Long): List<Step> {
        val out = ArrayList<Step>()
        val fv = spec.frame
        val v = spec.frameSpeed(k)
        val fwid = spec.frameWidthUm; val fl = spec.frameLayerUm
        out += LiftTo(top + t.liftUm)
        fun lay(label: String, xy: LongArray, closed: Boolean, z: Long) {
            out += extrude("Outer wall", xy, closed, z, Exact(label, doubleArrayOf(v), fv.accel, fv.flowRatio, fv.retractMm), ox, oy, fwid, fl)
            if ("framelift" !in without) out += LiftTo(z + t.liftUm)
        }
        for (c in t.heightsUm.indices) {
            val (ring, seams) = framePaths(k, c) ?: continue
            val z = if ("framelow" in without) t.heightsUm[c] else spec.frameUm(c)
            lay(spec.frameLabel(k, c), ring, true, z)
            for (s in seams) lay(spec.frameLabel(k, c), s, false, z)
            if (c > 0 && "framebridges" !in without) for (back in listOf(false, true)) {
                val path = bridgePath(k, c - 1, back) ?: continue
                lay(spec.bridgeLabel(k, c - 1, back), path, false, if ("framelow" in without) maxOf(t.heightsUm[c - 1], t.heightsUm[c]) else spec.bridgeUm(c - 1))
            }
        }
        return out
    }

    /** The plate's g-code: the machine's start and end, everything between exactly as specified. [pressureAdvance]: by tool. */
    fun gcode(s: plymeld.model.SliceSettings, foreignVars: Map<String, List<String>>, version: String, pressureAdvance: Map<Int, Double> = emptyMap()): String =
        java.io.StringWriter().also { plymeld.gcode.KlipperWriter(s, foreignVars, version, pressureAdvance = pressureAdvance).write(program(), it) }.toString()

    /**
     * Tile ([r], [c]) of block [k]'s paths: its wall (a closed ring half a line in from its edge, starting and ending at the
     * middle of its back edge) and its lines along X (one open path, back and forth from the back, each
     * one bead spacing from the next and from the wall at its sides; their ends past that by [FirstLayerTiles.wallOverlap]
     * of a line, into the wall at the left and right: a line end touches the wall only with its round cap, and on the first
     * plate every second end left a gap there, where the turn was on the other side).
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

    /** A square's rings in the first layer's lines, outermost first, solid: the first half a line in, each next a bead spacing further in. */
    private fun squareRings(b: LongArray): List<LongArray> {
        val rings = ArrayList<LongArray>()
        val sp = Math.round(fw - h * (1 - Math.PI / 4))
        var cur = Offset.offset(listOf(Polygon(longArrayOf(b[0], b[1], b[2], b[1], b[2], b[3], b[0], b[3]))), -fw / 2)
        while (cur.isNotEmpty()) { for (r in cur) if (r.size >= 3) rings += r.xy; cur = Offset.offset(cur, -sp) }
        return rings
    }

    /** A path of the plate, placed on the bed. */
    private fun extrude(feature: String, xy: LongArray, closed: Boolean, zUm: Long, ex: Exact, ox: Long, oy: Long, widthUm: Long, heightUm: Long): Extrude {
        val moved = LongArray(xy.size) { i -> xy[i] + if (i % 2 == 0) ox else oy }
        return Extrude(feature, moved, closed, zUm, widthUm, heightUm, tool = spec.tool, exact = ex)
    }
}
