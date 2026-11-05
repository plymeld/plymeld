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
import plymeld.program.PrintObject
import plymeld.program.PrintProgram
import plymeld.program.SetFan
import plymeld.program.SetTemp
import plymeld.program.Step

/**
 * The calibration plates made by band of height, generated from their own specification and
 * printed exactly as specified (no pose, layer-height, technique, or run-time decisions: [PrintProgram.exact]). One
 * plate per step of the dependency order: the **filament plate** (the overhang ledges, the bridges, the stringing ring,
 * the line-width tubes, the fit test; the temperature by band). The melt plate ([MeltPlate]) and the flow plate ([FlowPlate])
 * are their own, a column per temperature. Its parts ([Part]):
 * the ruler post where the plate has one, and its families of tests, each laid out in a frame of its own and packed onto the
 * plate ([PlatePacking]). The first-layer tiles are a plate of their own
 * ([FirstLayerPlate]: temperature varied within the first layer); the motion plate is its own too ([MotionPlate];
 * the motion hexagon, once alone on a plate of this kind, is gone). Lengths in µm, speeds mm/s, flows mm³/s, accelerations mm/s².
 */

/** How one kind of path prints: speed, acceleration, flow ratio, retraction before it. */
class PathValues(val speed: Double, val accel: Double, val flowRatio: Double, val retractMm: Double)

/**
 * One band of the plate by height: [tempC] and [fanPct] from [fromUm] (its first layer's bottom) to
 * [readToUm] (read), then settling layers to [toUm] (not read; the next band's temperature is on
 * its way there).
 */
class Band(val index: Int, val tempC: Int, val fanPct: Double, val fromUm: Long, val readToUm: Long, val toUm: Long)

/**
 * The terraced melt test (in place of the melt loop, which showed little but ringing),
 * alone on the melt plate.
 * One STEP per band, terraced like the bridge decks: band 1 (the hottest) the lowest step at the front, each colder band's
 * step behind it. Band 1's
 * body is [PlateGeometry.meltFirstBody] tall on the base and each later band's step two layers higher
 * ([CalibrationSpec.meltBands]).
 *
 * Each step's top is ONE read layer, and nothing is printed on it afterwards. Now (after the first printed
 * plate, where every strip over the sparse grid came out gappy, and no speed printed 0.8 mm lines well, which threw
 * off the line-width variation) it lies on a **solid floor** (the step's
 * layer below it: [PlateGeometry.frameWalls] walls and front-to-back lines one bead spacing apart, at the frame's values),
 * and what varies is the read layer's **thickness**, not its width: every strip [widthUm] wide (the plate's ordinary line
 * width), strip k [heightsUm][k] thick, printed at the floor's top plus that thickness. The thicknesses ([heights]): from
 * [maxHeightUm] (the tool's layer.height.max) down in steps of a tenth of the bore [boreUm], [COUNT] of them, the thinnest at the
 * front, printed thinnest first (each strip stands no higher than the next, so the nozzle never meets a taller one).
 *
 * On each step three SURFACES side by side, left to right: FAST at [topSpeed], MID at half of it, SLOW at a quarter.
 * FAST follows the filament's expected maximum flow ([fastSpeed], so the rates tested follow the filament's defaults):
 * the machine's print speed [machineSpeed] (hardware.machine.motion.print.speed), or less, so
 * that a line of the middle thickness on it runs at [centerFlow]. [lines] lines a strip, each [PlateGeometry.meltLineLen]
 * long along X, back and forth, laid front to back, so the position across a strip is seconds at that flow. The same
 * geometry on all three surfaces. Under each floor a BODY: two walls and a grid of lines [PlateGeometry.pedestalPitch] apart
 * both ways, never solid. At [accel] (the machine's acceleration). Every move after a strip keeps at least [liftUm] (the
 * travel lift, retract.z_hop) above the strips laid, but on the strip it is printing. The fan is the bands' ([Band.fanPct]): the
 * filament's own in every band, or off.
 */
class MeltTerraces(val topSpeed: Double, val machineSpeed: Double, val centerFlow: Double, val boreUm: Long, val widthUm: Long,
                   val maxHeightUm: Long, val lines: Int, val accel: Double, val liftUm: Long) {
    init { require(lines >= 1) { "the melt test needs a line in each strip" }; require(heightsUm.first() > 0) { "the melt test's thinnest strip would be ${heightsUm.first()} µm thick" } }
    /** The surfaces' speeds (mm/s), left to right: FAST, MID, SLOW. */
    val speeds: DoubleArray get() = doubleArrayOf(topSpeed, topSpeed / 2, topSpeed / 4)
    /** The strips' thicknesses (µm), front to back (thinnest first): [heights]. */
    val heightsUm: LongArray get() = heights(maxHeightUm, boreUm).toLongArray()
    /** The strip count. */
    val count: Int get() = COUNT
    /** Whether FAST is held by the machine's print speed (not by the filament's expected maximum flow): the top of the tested range is the machine's. */
    val machineHeld: Boolean get() = topSpeed >= machineSpeed - 1e-9
    /** The cell label of strip [k] (from the front) of surface [s] (0 FAST, 1 MID, 2 SLOW) on band [band]'s step, all from 1 in the label: "melt 2.mid.3". */
    fun label(band: Int, s: Int, k: Int) = "melt ${band + 1}.${SURFACES[s]}.${k + 1}"
    /** The cell label of the body under surface [s] of band [band]'s step: "melt body 2.mid". */
    fun bodyLabel(band: Int, s: Int) = "melt body ${band + 1}.${SURFACES[s]}"
    /** The cell label of the solid floor under surface [s] of band [band]'s step (the layer below its read layer): "melt floor 2.mid". */
    fun floorLabel(band: Int, s: Int) = "melt floor ${band + 1}.${SURFACES[s]}"
    /** Strip [k]'s bead cross-section (mm²): [widthUm] wide, [heightsUm][k] thick. */
    fun beadMm2(k: Int): Double = bead(widthUm, heightsUm[k])

    companion object {
        /** The surfaces' names, left to right. */
        val SURFACES = listOf("fast", "mid", "slow")
        /**
         * The strip count, and the step between thicknesses as a share of the bore: plymeld's choice: six strips a
         * tenth of the bore apart, from the tool's thickest layer down (0.30 to 0.10 mm on a 0.4 mm nozzle whose layer.height.max is
         * 0.75 of its bore): round thicknesses, and a thinnest still above a common layer.height.min (a fifth of the bore).
         */
        const val COUNT = 6
        const val STEP_OF_BORE = 0.1
        /**
         * The fewest lines a strip has (plymeld's choice: the 10 a strip had at 300 mm/s), so a strip stays about
         * 4 mm deep and readable when FAST is slow (a filament with a low maximum flow), where the hold rule alone would give
         * two or three.
         */
        const val MIN_LINES = 10
        /** The thicknesses (µm), thinnest first: [maxUm] down in [COUNT] steps of [STEP_OF_BORE] x [boreUm]. */
        fun heights(maxUm: Long, boreUm: Long): List<Long> = (0 until COUNT).map { k -> maxUm - Math.round((COUNT - 1 - k) * STEP_OF_BORE * boreUm) }
        /** The bead model's cross-section (mm²) of a [wUm] wide, [hUm] thick line: (w - h) h + pi h² / 4. */
        fun bead(wUm: Long, hUm: Long): Double { val w = wUm / 1000.0; val h = hUm / 1000.0; return (w - h) * h + Math.PI * h * h / 4 }
        /**
         * The FAST surface's speed (mm/s): the machine's print speed [machineSpeed], or less, so that a [wUm] line of the middle
         * thickness (half way between the thinnest and the thickest of [heights]) runs at [centerFlow] (mm³/s, the filament's
         * expected maximum flow) at [flowRatio]: its strips then bracket that flow (about 0.5 to 1.4 times it). plymeld's choice,
         * the centering rule, so the range follows the filament.
         */
        fun fastSpeed(machineSpeed: Double, centerFlow: Double, wUm: Long, maxUm: Long, boreUm: Long, flowRatio: Double): Double {
            val hs = heights(maxUm, boreUm)
            val mid = bead(wUm, (hs.first() + hs.last()) / 2)
            return Math.min(machineSpeed, centerFlow / (mid * flowRatio))
        }
        /** FAST's thickest strip runs at this many times the filament's expected maximum flow (plymeld's choice): see [fastSpeedTop]. */
        const val TOP_OF_EXPECTED = 2.0
        /**
         * The melt plate's FAST speed (mm/s): the machine's print speed [machineSpeed], or less, so that FAST's thickest strip (a
         * [wUm] line [maxUm] thick) runs at [TOP_OF_EXPECTED] times [expected] (mm³/s, the filament's expected maximum flow) at
         * [flowRatio]. FAST's strips then span about 0.74 to 2 times it, MID's (half FAST's speed) about 0.37 to 1, so the
         * plate brackets a maximum flow up to twice the expected one in one print. Plymeld's choice.
         */
        fun fastSpeedTop(machineSpeed: Double, expected: Double, wUm: Long, maxUm: Long, flowRatio: Double): Double =
            Math.min(machineSpeed, TOP_OF_EXPECTED * expected / (bead(wUm, maxUm) * flowRatio))
        /**
         * Lines in a strip: as many as hold the FAST strip's flow for [PlateGeometry.meltHoldS] at [topSpeed] (each line
         * [PlateGeometry.meltLineLen] long; a time, so the same at every thickness), and at least [MIN_LINES]; the same count on
         * all three surfaces.
         */
        fun linesFor(topSpeed: Double): Int = maxOf(MIN_LINES, Math.ceil(PlateGeometry.meltHoldS * topSpeed / (PlateGeometry.meltLineLen / 1000.0) - 1e-9).toInt())
    }
}

/**
 * The overhang test: a spine, its back straight, with a ledge on its front per share in
 * [shares] (low to high, left to right). In every band's read layers a
 * ledge's front steps out by its share of a line (the outer wall's width) past the layer below, so its
 * underside is an overhang at exactly that share; in the settling layers it is cut back to the spine, so
 * each band starts it afresh at its own temperature and fan. At [speed] / [accel], recorded as the condition.
 */
class Ledges(val shares: DoubleArray, val speed: Double, val accel: Double)

/**
 * The bridge test: a row of piers, [spansUm] apart (the
 * shortest on the left), and on every band's top read layer two decks of lines laid across the whole row: the
 * front deck at [flows][0], the back one at [flows][1] (bridge flow ratios). Terraced: band k's
 * decks lie in deck strip k from the front and from the back (band 1's at the very front and back, each higher
 * band's one deck depth further in), so seen from above every deck is a step of its own, and nothing is under
 * a deck between the piers down to the bed. At [speed] / [accel], the band's fan: recorded as the conditions.
 */
class Bridges(val spansUm: LongArray, val flows: DoubleArray, val speed: Double, val accel: Double)

/**
 * The stringing test (it replaced the pairs of posts): a ring of
 * round posts, one more than the retraction lengths in [lengthsMm], evenly round a circle. On every layer the head prints
 * post 1, then hops counter-clockwise to each next post, retracting by hop k's length ([lengthsMm][k], short to long) before
 * it crosses the gap, so any strings across a gap are its hop's, at the band's temperature. The gap from the last post back to
 * post 1 is never crossed: the ring's one open gap, the control, at the
 * ring's front. The retraction speed, travel speed, and lift are the filament's and tool's own, recorded as the conditions.
 */
class Strings(val lengthsMm: DoubleArray, val speed: Double, val accel: Double) {
    /** The ring's posts: a hop each, and the post the open gap leads back to. */
    val posts: Int get() = lengthsMm.size + 1
    /** The retraction (mm) before post [j] (0-based): hop j's length; before post 1, the plate's own ([own]: the travel onto the ring). */
    fun retractBefore(j: Int, own: Double) = if (j == 0) own else lengthsMm[j - 1]

    companion object {
        /** The default lengths, mm. */
        fun defaultLengths(): List<Double> = (2..8).map { Math.round(it * 0.2 * 1000) / 1000.0 }
    }
}

/**
 * The line-width test: a row of square single-wall tubes, one per width in [widthsUm]
 * (narrowest on the left), each tube's one line that wide (its filament
 * by the bead model at that width), [layers] layers tall from the bed (the whole plate, so
 * each band shows them at its temperature). Read per band: the narrowest and widest tubes whose wall comes out
 * even and whole, the tool's bead range. At [speed] / [accel].
 */
class Tubes(val widthsUm: LongArray, val speed: Double, val accel: Double, val layers: Int) {
    companion object {
        /**
         * The default widths (two more tubes each side, "0.25 to 0.75 mm in 0.05 steps on a 0.4 mm nozzle",
         * relative to the nozzle): the bore [boreUm] x 0.625 to 1.875 in steps of 0.125, to 0.01 mm (µm).
         */
        fun defaultWidths(boreUm: Long): List<Long> = (0..10).map { k -> Math.round(boreUm * (0.625 + 0.125 * k) / 10.0) * 10 }.distinct()
    }
}

/**
 * The fit test: a block with a row of round holes, [holesUm] across (smallest on
 * the left), and beside it a round peg [pegUm] across: holes and peg as drawn, no compensation. Read: the
 * tightest hole the peg goes into by hand, so the clearance a fit needs. The block [blockLayers] layers tall from
 * [fromLayer], standing on a pedestal below that (two walls, sparse lines; the holes blind, as deep as the block),
 * so it prints in the band chosen for it; the peg a column from the bed
 * [pegLayers] layers tall, its top end (as tall as the block) the part that is read. 0 = on the bed. At
 * [speed] / [accel].
 */
class Fit(val pegUm: Long, val holesUm: LongArray, val speed: Double, val accel: Double, val blockLayers: Int, val pegLayers: Int, val fromLayer: Int = 0)

/**
 * The first-layer test, a block of it per temperature on the
 * first-layer plate ([FirstLayerPlate]): a grid of single-layer square tiles, each at its own nozzle
 * height: a row per first-layer speed in [speeds] (mm/s, the slowest at the front), a column per first-layer height in
 * [heightsUm] (the lowest on the left). Each tile is one wall and lines back and forth at the bead model's spacing for its
 * height, in [widthUm] lines (the first layer's line width), at its row's speed: a tile whose flow ([flow]) would pass the
 * block's melt limit is left out, never slowed ([FirstLayerSpec.printed]). [accel]: the first layer's
 * acceleration. [liftUm]: how far the nozzle rises off a tile before it travels on (the travel lift, the filament's retract.z_hop).
 * [wallOverlap]: how far each line's END reaches past bead contact with the tile's wall, as a share of [widthUm] (the
 * slicer's own rule for a line end meeting a wall, plan.layer.infill.wall_overlap: an end touches the wall only with its round cap;
 *, after the first plate showed a gap at every second line end along each tile's left and right walls).
 */
class FirstLayerTiles(val speeds: DoubleArray, val heightsUm: LongArray, val widthUm: Long, val accel: Double, val liftUm: Long, val wallOverlap: Double) {
    init {
        require(wallOverlap >= 0) { "the tiles' wall overlap is a share of the line width, 0 or more (got $wallOverlap)" }
        require(speeds.isNotEmpty() && heightsUm.isNotEmpty()) { "first-layer tiles need at least one speed and one height" }
        require(speeds.toList().zipWithNext().all { (a, b) -> b > a }) { "the tiles' speeds rise, slow to fast: ${speeds.joinToString()}" }
        require(heightsUm.toList().zipWithNext().all { (a, b) -> b > a }) { "the tiles' heights rise, low to high: ${heightsUm.joinToString()}" }
    }
    /** Bead cross-section (mm²) of a tile line in column [c]: the bead model at the tile's width and height. */
    fun beadMm2(c: Int): Double { val hm = heightsUm[c] / 1000.0; return hm * (widthUm / 1000.0 - hm * (1 - Math.PI / 4)) }
    /** Tile ([r], [c])'s flow (mm³/s) with [flowRatio]: its row's speed through its bead. */
    fun flow(r: Int, c: Int, flowRatio: Double): Double = speeds[r] * beadMm2(c) * flowRatio
    /** The cell label of the tile in row [r] (from the front) and column [c] (from the left), both from 1 in the label. */
    fun label(r: Int, c: Int) = "tile ${r + 1}.${c + 1}"
}

/**
 * The parts of the plates made by band of height, in the order each layer prints them: the
 * ruler post and the families of tests, each laid out in a frame of its own and packed onto the plate ([PlatePacking]). [key]:
 * the family's name (in `plymeld calibrate --tests` for the filament plate's, and in its base's cell label); [title]: its name
 * in messages and on the page.
 */
enum class Part(val key: String, val title: String) {
    POST("post", "ruler post"), MELT("melt", "terraced melt test"), OVERHANGS("overhangs", "overhang ledges"), BRIDGES("bridges", "bridges"),
    FIT("fit", "fit test"), LINE_WIDTH("line-width", "line-width tubes"),
    STRINGING("stringing", "stringing ring");

    companion object {
        /**
         * The filament plate's families of tests (the filament and temperature tests, on one plate once the melt rate is
         * known), in the plan's order: the default of `--tests`, which picks a subset. The
         * ruler post is not one (it is on every filament plate: the bands' ruler); nor are the melt test and the motion test,
         * each a plate of its own (`calibrate melt`, `calibrate motion`).
         */
        val FILAMENT_TESTS = listOf(OVERHANGS, BRIDGES, STRINGING, LINE_WIDTH, FIT)

        /** Whether a cell is the prime line the writer lays before the first layer (part of no test: [ofCell] has none for it). */
        fun isPrimeLine(label: String) = label == plymeld.gcode.PrimeLine.CELL

        /** The part a cell of the plate's g-code belongs to, by its label (an "adhesion:" prefix aside). */
        fun ofCell(label: String): Part {
            val c = label.removePrefix("adhesion:")
            return when {
                c.startsWith("base ") -> values().firstOrNull { it != POST && it.key == c.removePrefix("base ") } ?: error("no part of the plate has a cell '$label'")
                c == "post" -> POST
                c.startsWith("melt ") -> MELT
                c == "ledges" -> OVERHANGS
                c == "pier" || c.startsWith("deck ") -> BRIDGES
                c == "fit block" || c == "pedestal" || c == "peg" -> FIT
                c.startsWith("tube ") -> LINE_WIDTH
                c.startsWith("post ") -> STRINGING
                else -> error("no part of the plate has a cell '$label'")
            }
        }
    }
}

class CalibrationSpec(
    /** The program's tool (0-based) and its spool's name. */
    val tool: Int,
    val spool: String,
    val bands: List<Band>,
    val layerUm: Long,
    val widthUm: Long,
    val filamentDiameterMm: Double,
    /** The first layer, under band 0 (adhesion: [adhesion] values, the part fan off, band 0's temperature; not read). */
    val adhesion: PathValues,
    /**
     * The plate's own marks and supports above the first layer (ruler post, bases, pedestals, piers, the melt test's bodies):
     * not read for any value. `plymeld calibrate` prints them at the machine's print
     * speed and acceleration, held to the filament's maximum flow, never the visible-line cap (checked
     * against the config by [CalibrationCheck.frameMotion]).
     */
    val frame: PathValues,
    /** The terraced melt test, or null (left out). */
    val melt: MeltTerraces?,
    /** The flow ratio every test path uses unless the test varies it, and the retraction before them. */
    val flowRatio: Double,
    val retractMm: Double,
    /** Where the plate's front-left corner goes on the bed (µm). */
    val originXUm: Long,
    val originYUm: Long,
    /**
     * The first layer's line width (µm, `plan.mechanism.adhesion.first_layer.line.width`, the configured first-layer width,
     * as the tiles use): every line of layer 0 but the line-width
     * tubes' (their widths are the test) is this wide, each part's outline where it is at [widthUm]. Layers above it are
     * unchanged. (The melt test's strips are never on the first layer: each is on its band's last read layer.)
     */
    val firstWidthUm: Long = widthUm,
    /**
     * Every layer takes at least this long (s): after its paths the head parks beside the plate and waits out
     * the rest, so a layer's
     * plastic cools alike whatever the plate holds, and no speed changes. 0 = no pause. [travelSpeed] (mm/s)
     * for the estimate of a layer's own time.
     */
    val minLayerTimeS: Double = 0.0,
    val travelSpeed: Double = 0.0,
    /** The machine's bed levelling, run after its start template (automatic where possible, disableable); null = none. */
    val levelGcode: String? = null,
    /** The overhang ledges, or null (left out). */
    val ledges: Ledges? = null,
    /** The bridges, or null (left out). */
    val bridges: Bridges? = null,
    /** The stringing posts, or null (left out). */
    val strings: Strings? = null,
    /** The line-width tubes, or null (left out). */
    val tubes: Tubes? = null,
    /** The fit block and peg, or null (left out). */
    val fit: Fit? = null,
    /**
     * The room the parts are packed into (µm, [PlatePacking]): the bed's width, and its depth less the head's parking place
     * in front (on both sides of the center). Unlimited: packed as compactly as the parts allow.
     */
    val packWidthUm: Long = Long.MAX_VALUE,
    val packDepthUm: Long = Long.MAX_VALUE,
    /**
     * A base under every family of tests: this many layers from the bed (0 = none), the bands above
     * them (their first read layer on top of the bases). A base is solid, [PlateGeometry.baseMargin] wider than its test's
     * footprint on every side, with a notch indented in its front edge (in its layers above the first) in front of the test's
     * first item. The ruler post has none (it is not a test).
     */
    val baseLayers: Int = 0,
    /**
     * Whether the plate has the ruler post (the bands' ruler, a notch per band). The melt plate has none (plymeld's choice):
     * its steps mark the bands themselves (band 1 the lowest at the front, each next behind and two layers
     * higher), and its bands are two layers tall, too short for the post's notches (1 mm tall, 1 mm above a band's start).
     */
    val ruler: Boolean = true,
    /**
     * The first layer's height (µm; `plan.mechanism.adhesion.first_layer.height`: a first-layer window's choice, else the
     * setting), the layers above it [layerUm] each. The plate is designed in layers of [layerUm] from z 0; [gcode] shifts every
     * layer up by the difference (the first layer printed this thick), and the check reads the file with the same shift. Seen
     * the filament plate took the window's SPEED (140 mm/s, graded for 0.28 mm) but printed its first layer at the
     * plate's 0.2 mm, graded bad at every speed; its bases lifted at the corners.
     */
    val firstLayerUm: Long = layerUm,
) {
    init {
        // a plate per step of the dependency order: the melt test alone on its plate ("This [melt] is its own
        // plate/test")
        val families = listOfNotNull(melt?.let { Part.MELT }, ledges?.let { Part.OVERHANGS }, bridges?.let { Part.BRIDGES },
            strings?.let { Part.STRINGING }, tubes?.let { Part.LINE_WIDTH }, fit?.let { Part.FIT })
        require(melt == null || families == listOf(Part.MELT)) { "the melt test is a plate of its own (the melt plate): not with ${families.filter { it != Part.MELT }.joinToString { it.title }}" }
        // the melt strips stand on their floor up to the thickest's thickness: within the next layer's top, so the next
        // layer's lines beside them never run below a strip's top
        require(melt == null || melt.maxHeightUm <= 2 * layerUm) { "the melt test's thickest strip (${melt!!.maxHeightUm / 1000.0} mm, the tool's layer.height.max) would stand above the next layer (${layerUm / 1000.0} mm layers): at most twice the layer height" }
    }

    val topUm: Long get() = bands.last().readToUm
    /** Bead cross-section of a [w] µm line at the plate's layer height, mm² (the writer's bead model). */
    fun beadMm2(w: Long): Double { val h = layerUm / 1000.0; val wm = w / 1000.0; return h * (wm - h * (1 - Math.PI / 4)) }
    /** Bead cross-section of the plate's own lines, mm². */
    val beadMm2: Double get() = beadMm2(widthUm)
    /** Bead cross-section of the plate's own lines on the first layer ([firstWidthUm]), mm². */
    val firstBeadMm2: Double get() = beadMm2(firstWidthUm)
    val filamentMm2: Double get() = Math.PI * filamentDiameterMm * filamentDiameterMm / 4
    /** The band whose read part holds a layer whose top is [zUm], or null (adhesion and settling layers). */
    fun readBand(zUm: Long): Band? = bands.firstOrNull { zUm > it.fromUm && zUm <= it.readToUm }
    /**
     * The layer (0-based) that is band [band]'s step's read layer in the melt test: the band's last layer, printed after the
     * wait (M109) for the band's temperature at the band's first layer ([CalibrationSpec.meltBands]).
     */
    fun meltReadLayer(band: Int): Int = (bands[band].readToUm / layerUm).toInt() - 1
    /** The flow (mm³/s) of a [w] µm line at [speed] mm/s at the plate's flow ratio: speed x bead area x flow ratio. */
    fun flowOf(w: Long, speed: Double): Double = speed * beadMm2(w) * flowRatio
    /** The flow (mm³/s) of melt strip [k] on surface [s]: its surface's speed x its bead ([MeltTerraces.beadMm2]: the strips' width, its own thickness) x the flow ratio. */
    fun stripFlow(k: Int, s: Int): Double = melt!!.let { it.speeds[s] * it.beadMm2(k) * flowRatio }

    /** The plate's parts, in print order: the post where the plate has one ([ruler]), then each family this specification has. */
    val parts: List<Part> get() = Part.values().filter { p ->
        when (p) {
            Part.POST -> ruler; Part.MELT -> melt != null; Part.OVERHANGS -> ledges != null; Part.BRIDGES -> bridges != null
            Part.FIT -> fit != null; Part.LINE_WIDTH -> tubes != null; Part.STRINGING -> strings != null
        }
    }

    /**
     * The same specification with only the families in [keep] (the post is always kept), placed at [originXUm], [originYUm]
     * and packed into [packWidthUm] x [packDepthUm]: for trying a split of the plate.
     */
    fun keeping(keep: Set<Part>, originXUm: Long = this.originXUm, originYUm: Long = this.originYUm,
                packWidthUm: Long = this.packWidthUm, packDepthUm: Long = this.packDepthUm) = CalibrationSpec(
        tool, spool, bands, layerUm, widthUm, filamentDiameterMm, adhesion, frame, melt.takeIf { Part.MELT in keep }, flowRatio, retractMm,
        originXUm, originYUm, firstWidthUm, minLayerTimeS, travelSpeed, levelGcode,
        ledges.takeIf { Part.OVERHANGS in keep }, bridges.takeIf { Part.BRIDGES in keep },
        strings.takeIf { Part.STRINGING in keep }, tubes.takeIf { Part.LINE_WIDTH in keep }, fit.takeIf { Part.FIT in keep },
        packWidthUm, packDepthUm, baseLayers, ruler, firstLayerUm)

    /** The same specification with [bands] in place of its own: for the control "the bands not moved up above the bases". */
    fun withBands(bands: List<Band>) = CalibrationSpec(
        tool, spool, bands, layerUm, widthUm, filamentDiameterMm, adhesion, frame, melt, flowRatio, retractMm,
        originXUm, originYUm, firstWidthUm, minLayerTimeS, travelSpeed, levelGcode,
        ledges, bridges, strings, tubes, fit, packWidthUm, packDepthUm, baseLayers, ruler, firstLayerUm)

    /** The same specification with [melt] in place of its own: for choosing the melt test's widths (as many as fit). */
    fun withMelt(melt: MeltTerraces?) = CalibrationSpec(
        tool, spool, bands, layerUm, widthUm, filamentDiameterMm, adhesion, frame, melt, flowRatio, retractMm,
        originXUm, originYUm, firstWidthUm, minLayerTimeS, travelSpeed, levelGcode,
        ledges, bridges, strings, tubes, fit, packWidthUm, packDepthUm, baseLayers, ruler, firstLayerUm)

    companion object {
        /**
         * The melt plate's bands, short, with little height between them: the steps stand side by side and nothing is printed over a
         * finished read layer, so only the temperature change and one read layer separate them. Hot to cold across
         * [lowC]-[highC] in whole-degree steps of [stepC] ([temperatures]), every band at [fanPct]. Band 1 from [startUm] (the
         * bases' top): its step's body [PlateGeometry.meltBodyLayers] layers (1 mm), then its read layer. Each later band two
         * layers: its first printed after the wait (M109) for its temperature at its start (settling, not read), then its read
         * layer. No settling after a band (the next band's wait is at its own first layer).
         */
        fun meltBands(lowC: Int, highC: Int, stepC: Int, fanPct: Double, startUm: Long, layerUm: Long): List<Band> {
            require(highC > lowC) { "the temperature range must rise: $lowC-$highC C" }
            val temps = temperatures(lowC, highC, stepC).reversed()
            var z = startUm
            return temps.mapIndexed { b, t ->
                val from = z; val readTo = from + layerUm * (if (b == 0) PlateGeometry.meltBodyLayers(layerUm) + 1 else 2)
                z = readTo
                Band(b, t, fanPct, from, readTo, readTo)
            }
        }

        /**
         * Temperature bands: whole degrees across [lowC]-[highC] in steps of [stepC]; a
         * step that does not divide the range is refused, with the nearest steps that do. Hot to cold:
         *band 0, at the bottom, is the hottest, so
         * the coldest bands, where the extruder is likeliest to be driven past the melt, print last. The
         * fan alternates band by band, [fans].first on band 0. Heights from [startUm]: [readUm] read,
         * then [settleUm] settling (none above the last band).
         */
        fun bands(lowC: Int, highC: Int, stepC: Int, fans: Pair<Double, Double>, startUm: Long, readUm: Long, settleUm: Long): List<Band> {
            require(stepC > 0) { "the temperature step must be a whole number of degrees above 0 (got $stepC)" }
            require(highC > lowC) { "the temperature range must rise: $lowC-$highC C" }
            val n = temperatures(lowC, highC, stepC).size
            var z = startUm
            return (0 until n).map { b ->
                val from = z; val readTo = from + readUm; val to = if (b == n - 1) readTo else readTo + settleUm
                z = to
                Band(b, highC - b * stepC, if (b % 2 == 0) fans.first else fans.second, from, readTo, to)
            }
        }

        /**
         * The temperatures across [lowC]-[highC] in whole-degree steps of [stepC], coolest first (whole
         * degrees; a step that does not divide the range is refused, with the nearest steps that do). [lowC] = [highC]: that
         * one temperature (a first-layer plate may have one block).
         */
        fun temperatures(lowC: Int, highC: Int, stepC: Int): List<Int> {
            require(stepC > 0) { "the temperature step must be a whole number of degrees above 0 (got $stepC)" }
            require(highC >= lowC) { "the temperature range must rise: $lowC-$highC C" }
            val span = highC - lowC
            if (span % stepC != 0) {
                val near = (1..span).filter { span % it == 0 }.sortedBy { Math.abs(it - stepC) }.take(2).sorted()
                error("a $stepC C step does not divide $lowC-$highC C (a $span C range): try ${near.joinToString(" or ") { "$it C" }}")
            }
            return (0..span / stepC).map { lowC + it * stepC }
        }
    }
}

/**
 * The plate's own dimensions (µm, before it is placed): what the generator draws and what the read-back
 * check measures the g-code against. Each part's are in its own frame; [PlatePacking] places the parts.
 */
object PlateGeometry {
    // the terraced melt test: its strips' lines this long (along X), each strip holding the FAST surface's
    // flow at least this long. plymeld's choices (the print kept fast while still showing results, each segment over
    // 1 cm): a line long enough that
    // the head spends most of it at full speed (at 300 mm/s and 30,000 mm/s2, 1.5 mm to reach the speed and 1.5 to stop: 95 %
    // of a 60 mm line at full speed), and a hold long enough to see a hot end that coasts on stored heat run out. Not measured.
    /** A melt strip's line length (µm, center to center of its ends). */
    const val meltLineLen = 60_000L
    /** How long (s) a FAST strip holds its flow, at least ([MeltTerraces.linesFor]). */
    const val meltHoldS = 2.0
    /**
     * Band 1's melt step body on the base (µm): 1 mm; each later band's step is two layers
     * higher (a settling layer after its wait, and its read layer: [CalibrationSpec.meltBands]).
     */
    const val meltFirstBody = 1_000L
    /**
     * [meltFirstBody] in whole layers of [layerUm] (5 at 0.2 mm): to the nearest layer, at least one (plymeld's choice,
     * for a layer height that does not divide 1 mm: 0.28 mm gives 4 layers, 1.12 mm).
     */
    fun meltBodyLayers(layerUm: Long): Int = maxOf(1, Math.round(meltFirstBody.toDouble() / layerUm).toInt())
    // the melt test's three surfaces stand [meltSurfaceGap] apart; its steps touch (terraced, band 1's at the front, each
    // colder band's directly behind: the bridge decks' terraces)
    const val meltSurfaceGap = 3_000L
    // a marker: a small square this size, this far in front of what it marks (the first-layer plate's dots)
    const val markSize = 3_000L; const val markGap = 1_500L
    // the ruler post: 8 x 8 mm, its front-left corner chamfered (the origin mark); the front face past the chamfer (6 mm)
    // keeps material either side of the long notch. Packed first, at the plate's front-left corner.
    const val postX = 0L; const val postY = 0L; const val post = 8_000L
    const val chamfer = PlateMarks.CHAMFER
    // a notch on the post's front face in each band: 1 mm deep, from 1 mm above the band's start, 1 mm tall;
    // every fourth band's notch long (a ruler: count from the bottom)
    const val notchDepth = 1_000L; const val notchFrom = 1_000L; const val notchTall = 1_000L
    const val notchShort = 1_500L; const val notchLong = 4_000L

    // the bases and the packing, in one place:
    /** The gap between the plate's parts (each family's base, and the ruler post), packed: 1 cm. */
    const val familyGap = 10_000L
    /** How far a base reaches past its test's footprint on every side: 1 cm. */
    const val baseMargin = 10_000L
    /** A base's layers. */
    const val baseLayers = 3
    /**
     * A base's notch, indented (never embossed, never text): its first layer whole, the layers above it
     * empty there; this wide along the edge and cut this far in from it (inside the margin, so never under a test). A group
     * of notches would stand [baseNotchGap] apart (each base has one so far, so none is grouped). plymeld's choice:
     *not measured.
     */
    const val baseNotchW = 2_000L; const val baseNotchIn = 3_000L; const val baseNotchGap = 2_000L
    /** The head's parking place (the cooling pause, the temperature waits): this far in front of the plate, counted on both sides of its center on the bed. */
    const val parkFront = 10_000L
    /** Less than this to spare at a bed's edge gets a warning (check the bed's clips or wiper are clear); the packing keeps it where the bed allows. */
    const val bedSpare = 10_000L

    // the overhang spine: this thick; a ledge this long every this far, the spine running this far past the first and last
    const val ledgeBase = 3_000L; const val ledgeLen = 8_000L; const val ledgeGap = 3_000L; const val ledgeEnd = 3_000L

    // the bridges: piers this wide (x); two decks per band each about this deep (exactly: a whole number of lines),
    // terraced: band k's in deck strip k from the front and from the back, the innermost (the top band's) this far apart
    // (the piers span the row)
    const val pierW = 3_000L; const val deckAbout = 5_000L; const val deckGap = 5_000L
    // the stringing ring: posts this far across (round), this far apart (the gap a hop crosses, between neighbors' sides
    // along the line between their centers: 15 mm); the posts' sizes before the ring
    const val ringPost = 4_000L; const val ringPostGap = 15_000L
    /** The ring's radius (µm, to its posts' centers) for [posts] posts: neighbors a post and a gap apart, center to center. */
    fun ringR(posts: Int): Double = (ringPost + ringPostGap) / (2 * Math.sin(Math.PI / posts))
    /** Post [j]'s angle round the ring (counter-clockwise from +X): the open gap (from the last post back to post 1) centered at the ring's front. */
    fun ringAngle(j: Int, posts: Int): Double = -Math.PI / 2 + (j + 0.5) * 2 * Math.PI / posts
    // the line-width tubes: this square (outside), this far apart
    const val tube = 8_000L; const val tubeGap = 3_000L
    // the fit block: this much material round and between its holes; the peg this far right of the block; circles drawn
    // with this many sides
    const val fitWall = 2_500L; const val pegGap = 5_000L; const val circleSides = 72
    /** A pedestal's sparse lines, and the melt test's body grid's front-to-back lines: this far apart. */
    const val pedestalPitch = 2_000L
    /**
     * The walls of the plate's frame: a pedestal's two, a melt body's two and, a base's outline rings (the
     * pedestals' two reused for the bases, which read no wall count: outlines and monotonic
     * lines on alternating diagonals).
     */
    const val frameWalls = 2
    // the first-layer tiles (on the first-layer plate, FirstLayerPlate): about this square (about 10 mm
    // on a side; exactly a whole number of lines at the bead spacing for the tile's height), in a grid one cell this far
    // from the next
    const val tileAbout = 10_000L; const val tileGap = 3_000L

    /** The band whose layers (read or settling) hold a layer with top [zUm], or null (the adhesion layer). */
    fun bandAt(spec: CalibrationSpec, zUm: Long): Band? = spec.bands.firstOrNull { zUm > it.fromUm && zUm <= it.toUm }

    /** How far a ledge stands out of the spine on the layer whose top is [zUm] (µm): [i] layers into a band's read part, [i] steps of its share; else none. */
    fun ledgeDepth(spec: CalibrationSpec, share: Double, zUm: Long): Long {
        val b = spec.readBand(zUm) ?: return 0
        val i = (zUm - b.fromUm) / spec.layerUm
        return Math.round(i * share * spec.widthUm)
    }

    fun notchWidth(band: Band) = if ((band.index + 1) % 4 == 0) notchLong else notchShort
    /** The band whose notch a layer with this top cuts, or null. */
    fun notchAt(spec: CalibrationSpec, zUm: Long): Band? = spec.bands.firstOrNull { zUm > it.fromUm + notchFrom && zUm <= it.fromUm + notchFrom + notchTall }
}

/**
 * Builds the plate's program from its [spec]. [without]: features left out ("notches", "pause", "lean", "decks"; "untiered": the top band's bridge decks where the row before the terraces had them; "deckoverlap": the top band's decks half a deck over the band below's; "widths": the tubes at the plate's own width; "clearance": the holes at the peg's size; "firstwidth": the
 * first layer's ordinary lines at the plate's own width, not the first layer's; "tubefirst": one tube's first layer at the
 * first layer's width, not its own; the melt test's: "meltmidfast": the MID surface at the FAST speed; "meltwidth": band 1's
 * FAST strip 3 0.05 mm wider than the plate's line width; "meltheight": that strip 0.04 mm above its thickness; "melt2read":
 * band 1's strips laid again on the layer above its read layer; "meltnofloor": the grid under each read layer instead of its
 * solid floor; "meltsolid": a second solid layer, under the floor; "meltthickfirst": band 1's FAST strips printed thickest
 * first; "meltnolift": no lift before each surface's strips (the travel from the last surface's thickest strip with only the
 * writer's own lift); "meltwaitlate": band 2's
 * temperature set without a wait (M104) at its start and waited for (M109) only after its read layer, so its settling and read
 * layers print before the temperature is reached; "meltstep": band 2's step one layer low (its read layer on its settling
 * layer, its body one layer shorter);
 * "familygap": the parts packed with no gap between them; "ringreverse": the stringing ring's lengths in reverse order;
 * "basefill": the bases without their notches; "basenotch1": the notches cut through the bases' first layer too;
 * "baseshort": the first family's base 5 mm short on its right; the bases' fill ([basePaths]): "baserings" concentric rings to the
 * middle (the bases earlier), "basesameangle" every layer's lines at the same angle, "basehalves" the lines out of
 * monotonic order (the back half of the sweep first), "basenotchfill" lines across the notch): only for the read-back check's
 * controls, which must catch them.
 * Every control's plate is laid out like the plate itself (the same packing), so only the feature it alters differs.
 * [local]: every part in its own frame, unplaced (for working out the parts' footprints).
 */
class CalibrationPlate(private val spec: CalibrationSpec, private val without: Set<String> = emptySet(), private val local: Boolean = false) {
    private val w = spec.widthUm
    private val h = spec.layerUm
    /** Center-to-center distance of two touching [lw] µm lines (the bead model's). */
    private fun spacingOf(lw: Long) = Math.round(lw - h * (1 - Math.PI / 4))
    /** Center-to-center distance of two touching lines of the plate's own width. */
    private val spacing = spacingOf(w)
    /** The width (µm) of the plate's ordinary lines on [layer]: the first layer's on layer 0, the plate's own above. */
    private fun widthOn(layer: Int) = if (layer == 0 && "firstwidth" !in without) spec.firstWidthUm else w

    private val chamfer = PlateGeometry.chamfer
    private val notchDepth = PlateGeometry.notchDepth

    /** The plate's parts, in print order. */
    val parts: List<Part> = spec.parts

    /**
     * Where each part goes: packed from the parts' footprints ([footprints], the same for every control's plate), the
     * families [PlateGeometry.familyGap] apart ("familygap": none), into the specification's room.
     */
    val layout: PlatePacking.Layout by lazy {
        if (local) PlatePacking.Layout(parts.associateWith { longArrayOf(0, 0) }, 0, 0, true)
        else PlatePacking.pack(footprints(spec), if ("familygap" in without) 0 else PlateGeometry.familyGap, spec.packWidthUm, spec.packDepthUm, PlateGeometry.bedSpare)
    }
    private fun ox(p: Part): Long = layout.offsets.getValue(p)[0]
    private fun oy(p: Part): Long = layout.offsets.getValue(p)[1]

    /** Part [p]'s footprint on the plate (µm, before the plate is placed on the bed: x0, y0, x1, y1). */
    fun footprint(p: Part): LongArray = footprints(spec).getValue(p).let { f -> longArrayOf(f[0] + ox(p), f[1] + oy(p), f[2] + ox(p), f[3] + oy(p)) }

    fun program(): PrintProgram {
        stripTop = 0L
        val steps = ArrayList<Step>()
        val ox = spec.originXUm; val oy = spec.originYUm
        val layers = (spec.topUm / h).toInt()
        require(layers * h == spec.topUm) { "the plate's height ${spec.topUm} µm is not whole layers of $h µm" }
        // the plate prints with one tool only and changes none: the machine's start template selects and heats
        // it (initial_tool, the tool of the plate's first path)
        spec.levelGcode?.let { steps += plymeld.program.Custom("bed levelling (hardware.machine.<key>.gcode.level)", it) }
        // layer 0: adhesion, at band 0's temperature, the part fan off
        steps += SetTemp(spec.tool, spec.bands[0].tempC, wait = true)
        steps += SetFan(0.0)
        var bandAt = -1
        for (layer in 0 until layers) {
            val top = (layer + 1) * h
            steps += LayerStart(layer, top, h)
            val band = spec.bands.firstOrNull { top > it.fromUm && top <= it.toUm }
            if (band != null && band.index != bandAt) {
                // a band begins: its temperature, waited for (M109; on the filament plate reached during the settling layers,
                // so a guard; on the melt plate the band's one wait, before its settling layer), its fan
                bandAt = band.index
                steps += SetTemp(spec.tool, band.tempC, wait = !("meltwaitlate" in without && band.index == 1))
                steps += SetFan(band.fanPct)
            }
            if (band != null && band.index + 1 < spec.bands.size) {
                // settling toward the next band: heat (or cool) from its first layer without waiting, wait for the
                // temperature halfway through, then print the rest at it, so the band is not read during an overshoot
                val settleLayers = ((band.toUm - band.readToUm) / h).toInt()
                val next = spec.bands[band.index + 1].tempC
                if (top == band.readToUm + h) steps += SetTemp(spec.tool, next, wait = false)
                if (settleLayers >= 2 && top == band.readToUm + h * (settleLayers / 2 + 1)) steps += SetTemp(spec.tool, next, wait = true)
            }
            // every part, in print order; then the melt test's strips, the last on their layer, lifted clear of each other
            for (p in parts) steps += partPaths(p, layer)
            if (spec.melt != null) steps += meltStripSteps(layer)
            // the control "meltwaitlate": band 2's wait only after its read layer
            if ("meltwaitlate" in without && spec.bands.size > 1 && layer == spec.meltReadLayer(1)) steps += SetTemp(spec.tool, spec.bands[1].tempC, wait = true)
            // the cooling pause: park beside the plate and wait out the rest of the filament's minimum layer time
            // (also where the next layer's temperature waits happen: away from the print; with no minimum layer time the head
            // still parks before a wait, so it never waits over the print)
            if ((spec.minLayerTimeS > 0 || (layer + 1 < layers && waitsAt(layer + 1))) && "pause" !in without) {
                val park = parkUm()
                val paths = steps.subList(steps.indexOfLast { it is LayerStart }, steps.size).filterIsInstance<Extrude>()
                steps += plymeld.program.Park(park.first, park.second, maxOf(0.0, spec.minLayerTimeS - layerTimeS(paths, park)))
            }
        }
        val bb = bounds().let { longArrayOf(it[0] + ox, it[1] + oy, it[2] + ox, it[3] + oy) }
        val fp = doubleArrayOf(bb[0] / 1000.0, bb[1] / 1000.0, bb[2] / 1000.0, bb[1] / 1000.0, bb[2] / 1000.0, bb[3] / 1000.0, bb[0] / 1000.0, bb[3] / 1000.0)
        return PrintProgram(steps, listOf(PrintObject("calibration", fp, (bb[0] + bb[2]) / 2000.0, (bb[1] + bb[3]) / 2000.0)), exact = true)
    }

    /**
     * Whether layer [layer] begins with a wait (M109) for a temperature, as [program] writes them: at a band's first layer,
     * and half way through the settling layers before the next band.
     */
    private fun waitsAt(layer: Int): Boolean {
        val top = (layer + 1) * h
        fun bandOf(z: Long) = spec.bands.firstOrNull { z > it.fromUm && z <= it.toUm }
        val band = bandOf(top) ?: return false
        if (bandOf(layer * h)?.index != band.index) return true
        if (band.index + 1 >= spec.bands.size) return false
        val settleLayers = ((band.toUm - band.readToUm) / h).toInt()
        return settleLayers >= 2 && top == band.readToUm + h * (settleLayers / 2 + 1)
    }

    /** The adhesion layer's values for a cell (not read). */
    private fun adhesionEx(label: String) = Exact("adhesion:$label", doubleArrayOf(spec.adhesion.speed), spec.adhesion.accel, spec.adhesion.flowRatio, spec.adhesion.retractMm)

    /** Part [part]'s paths on layer [layer], placed on the bed. */
    private fun partPaths(part: Part, layer: Int): List<Extrude> {
        val out = ArrayList<Extrude>()
        val top = (layer + 1) * h
        val first = layer == 0
        val frame = if (first) spec.adhesion else spec.frame
        // the ordinary lines' width on this layer (the first layer's on layer 0); every part's outline stays where it is
        val lw = widthOn(layer)
        // a family's base, below its test: outline rings, then solid diagonal lines in monotonic order ([basePaths]); notched
        // above its first layer
        if (part != Part.POST && layer < spec.baseLayers) {
            val ex = if (first) adhesionEx(baseLabel(part)) else Exact(baseLabel(part), doubleArrayOf(frame.speed), frame.accel, frame.flowRatio, frame.retractMm)
            val (rings, lines) = basePaths(part, layer, lw)
            for ((i, ring) in rings.withIndex()) out += extrude(if (i == 0) "Outer wall" else "Inner wall", ring, true, top, ex, lw)
            for (line in lines) out += extrude(if (first) "Bottom surface" else "Internal solid infill", line, false, top, ex, lw)
            return out
        }
        when (part) {
            Part.POST -> for (ring in postRings(top, lw)) out += extrude("Outer wall", ring, true, top, Exact(if (first) "adhesion:post" else "post", doubleArrayOf(frame.speed), frame.accel, frame.flowRatio, frame.retractMm), lw)
            // the terraced melt test: per band's step, below its read layer a body under each surface (two walls and the grid,
            // at the frame's values), its top layer the solid floor; the strips on the read layer are [meltStrips], printed
            // after everything else on their layer ([program]); above them nothing
            Part.MELT -> {
                val mt = spec.melt!!
                for (b in spec.bands.indices) {
                    // the control "meltstep": band 2's step one layer low
                    val read = meltRead(b)
                    if (layer >= read) continue
                    for (s in 0 until 3) {
                        // the solid floor directly under the read layer (the control "meltnofloor": the grid there instead); the
                        // control "meltsolid": a second solid layer under it
                        val floor = layer == read - 1 && "meltnofloor" !in without
                        val solid = floor || ("meltsolid" in without && layer == read - 2)
                        val label = (if (first) "adhesion:" else "") + (if (floor) mt.floorLabel(b, s) else mt.bodyLabel(b, s))
                        val ex = Exact(label, doubleArrayOf(frame.speed), frame.accel, frame.flowRatio, frame.retractMm)
                        val (rings, grid) = meltBodyPaths(b, s, lw, solid)
                        for (ring in rings) out += extrude("Outer wall", ring, true, top, ex, lw)
                        for (g in grid) out += extrude(if (solid) "Internal solid infill" else "Sparse infill", g, false, top, ex, lw)
                    }
                }
            }
            // the overhang spine and its ledges, every layer
            Part.OVERHANGS -> {
                val lg = spec.ledges!!
                val ex = if (first) adhesionEx("ledges") else Exact("ledges", doubleArrayOf(lg.speed), lg.accel, spec.flowRatio, spec.retractMm)
                for (ring in ledgeRings(top, lw)) out += extrude("Outer wall", ring, true, top, ex, lw)
            }
            // the bridges: the piers every layer; on every band's top read layer that band's two decks across the row,
            // in its own strips (terraced)
            Part.BRIDGES -> {
                val br = spec.bridges!!
                val deckBand = if (!first && "decks" !in without) deckBandAt(top) else null
                val pierEx = if (first) adhesionEx("pier") else Exact("pier", doubleArrayOf(frame.speed), frame.accel, frame.flowRatio, frame.retractMm)
                for (k in 0..br.spansUm.size) for (ring in pierRings(k, deckBand, lw)) out += extrude("Outer wall", ring, true, top, pierEx, lw)
                if (deckBand != null) for (d in 0 until 2) out += extrude("Bridge", deckPath(d, deckBand), false, top,
                    Exact("deck ${d + 1}", doubleArrayOf(br.speed), br.accel, br.flows[d], spec.retractMm), w)
            }
            // the fit block (its holes as drawn) and the peg
            Part.FIT -> {
                val f = spec.fit!!
                fun ex(cell: String) = if (first) adhesionEx(cell) else Exact(cell, doubleArrayOf(f.speed), f.accel, spec.flowRatio, spec.retractMm)
                if (layer >= f.fromLayer && layer < f.fromLayer + f.blockLayers) for (ring in fitBlockRings(lw)) out += extrude("Outer wall", ring, true, top, ex("fit block"), lw)
                else if (layer < f.fromLayer) {
                    // the block's pedestal, up to where the block starts
                    val (rings, lines) = pedestalPaths(fitBox(), alongX = layer % 2 == 1, lw = lw)
                    val pe = Exact(if (first) "adhesion:pedestal" else "pedestal", doubleArrayOf(frame.speed), frame.accel, frame.flowRatio, frame.retractMm)
                    for (ring in rings) out += extrude("Outer wall", ring, true, top, pe, lw)
                    out += extrude("Sparse infill", lines, false, top, pe, lw)
                }
                // the peg: a column from the bed; its top end, as tall as the block, is the part that is read
                if (layer < f.pegLayers) { val (cx, cy) = pegCenter(); for (ring in solidRings(listOf(circle(cx, cy, f.pegUm, ccw = true)), lw)) out += extrude("Outer wall", ring, true, top, ex("peg"), lw) }
            }
            // the line-width tubes: one line each, at its own width, from the bed
            Part.LINE_WIDTH -> {
                val tb = spec.tubes!!
                // the control "tubefirst": one tube (the first not already that wide) at the first layer's width on the first layer
                val forced = if ("tubefirst" in without) tb.widthsUm.indexOfFirst { it != spec.firstWidthUm } else -1
                if (layer < tb.layers) for (k in tb.widthsUm.indices) {
                    val ex = if (first) adhesionEx("tube ${k + 1}") else Exact("tube ${k + 1}", doubleArrayOf(tb.speed), tb.accel, spec.flowRatio, spec.retractMm)
                    // their own widths on every layer, the first included: the width is the test
                    val tw = if (first && k == forced) spec.firstWidthUm else if ("widths" in without) w else tb.widthsUm[k]
                    out += extrude("Outer wall", tubeRing(k), true, top, ex, tw)
                }
            }
            // the stringing ring: post 1, then counter-clockwise post by post, each post's retraction before it (hop k's
            // length before post k + 1), so the travel across each gap is its hop's; never back across the open gap
            Part.STRINGING -> {
                val st = spec.strings!!
                val lengths = if ("ringreverse" in without) st.lengthsMm.reversedArray() else st.lengthsMm
                for (j in 0 until st.posts) {
                    val label = "post ${j + 1}"
                    val retract = if (j == 0) spec.retractMm else lengths[j - 1]
                    val ex = if (first) adhesionEx(label) else Exact(label, doubleArrayOf(st.speed), st.accel, spec.flowRatio, retract)
                    val (cx, cy) = ringPost(j)
                    for (ring in solidRings(listOf(circle(Math.round(cx), Math.round(cy), PlateGeometry.ringPost, ccw = true)), lw)) out += extrude("Outer wall", ring, true, top, ex, lw)
                }
            }
        }
        return out
    }

    /** The cell label of [p]'s base. */
    private fun baseLabel(p: Part) = "base ${p.key}"

    /** Part [p]'s test on the plate, without its base (µm: x0, y0, x1, y1): every line it lays above the bases. */
    fun testFootprint(p: Part): LongArray = testFootprints(spec).getValue(p).let { f -> longArrayOf(f[0] + ox(p), f[1] + oy(p), f[2] + ox(p), f[3] + oy(p)) }

    /**
     * Where [p]'s base notch stands along its front edge (x, µm, on the plate): in front of the test's first item (the first
     * ledge, the first span, the first tube, hole 1, post 1 of the stringing ring, the melt
     * test's FAST surface).
     */
    fun notchX(p: Part): Long = when (p) {
        Part.MELT -> meltBox(0, 0).let { (it[0] + it[2]) / 2 }
        Part.OVERHANGS -> ledgeCell(0).let { (a, b) -> (a + b) / 2 }
        Part.BRIDGES -> (pierX(0).second + pierX(1).first) / 2
        Part.FIT -> holeCenter(0).first
        Part.LINE_WIDTH -> tubeBox(0).let { (it[0] + it[2]) / 2 }
        Part.STRINGING -> Math.round(ringPost(0).first)
        Part.POST -> error("the post has no base")
    }

    /**
     * [p]'s base on [layer] (one of the base's): its test's footprint and [PlateGeometry.baseMargin] round it, the notch in its
     * front edge ([PlateGeometry.baseNotchW] wide, [PlateGeometry.baseNotchIn] deep) on every layer but the first. Controls:
     * "basefill" no notch; "basenotch1" the notch through the first layer too; "baseshort" the first family's base 5 mm
     * short on its right. [notch] false: the outline without its notch on any layer (the control "basenotchfill"'s lines).
     */
    private fun baseOutline(p: Part, layer: Int, notch: Boolean = true): Polygon {
        val t = testFootprint(p); val m = PlateGeometry.baseMargin
        val x0 = t[0] - m; val y0 = t[1] - m; val y1 = t[3] + m
        val x1 = t[2] + m - if ("baseshort" in without && p == parts.first { it != Part.POST }) 5_000 else 0
        val notched = notch && "basefill" !in without && (layer > 0 || "basenotch1" in without)
        val pts = ArrayList<Long>()
        fun pt(x: Long, y: Long) { pts += x; pts += y }
        pt(x0, y0)
        if (notched) {
            val nx = notchX(p); val nw = PlateGeometry.baseNotchW
            pt(nx - nw / 2, y0); pt(nx - nw / 2, y0 + PlateGeometry.baseNotchIn); pt(nx + nw / 2, y0 + PlateGeometry.baseNotchIn); pt(nx + nw / 2, y0)
        }
        pt(x1, y0); pt(x1, y1); pt(x0, y1)
        return Polygon(pts.toLongArray())
    }

    /**
     * The angle (degrees) of a base's lines on [layer]: 45 on the first and every other layer, 135 (-45) on the ones between
     * (alternating diagonals; which of the two comes first is plymeld's choice).
     */
    fun baseAngle(layer: Int): Double = if (layer % 2 == 0) 45.0 else 135.0

    /**
     * [p]'s base on [layer] in [lw] µm lines (outlines and monotonic lines on alternating diagonals, in place of a
     * concentric base): [PlateGeometry.frameWalls] outline rings following its outline ([baseOutline], round
     * the notch above the first layer), outermost first; then the inside solid in lines one bead spacing apart at
     * [baseAngle], their ends one bead spacing inside the innermost ring, laid as the slicing pipeline lays its monotonic
     * surfaces (Planner, `bottom = "monotonic"`): hatched on the shared grid ([Hatch]), joined only to the adjacent line and
     * broken where the fill merges ([Linker.link], connectors at most [Planner.ANCHOR_SPACINGS] spacings) and, unlike the
     * pipeline's, where it forks too (round the notch: else one side's last line went down between two printed neighbors),
     * and printed as one
     * sweep across the base, from whichever end of it needs less travel from where the rings end ([Ordering.sweep]). Its rings, then its lines, in print
     * order. Controls (for the read-back check): "baserings" the base as it was before (rings to the middle, no lines);
     * "basesameangle" every layer's lines at 45; "basehalves" the lines one by one, the back half of the sweep first, then the
     * front half; "basenotchfill" the lines hatched as if the base had no notch (its rings still round it).
     */
    private fun basePaths(p: Part, layer: Int, lw: Long): Pair<List<LongArray>, List<LongArray>> {
        val outline = baseOutline(p, layer)
        if ("baserings" in without) return solidRings(listOf(outline), lw) to emptyList()
        val sp = spacingOf(lw)
        val (rings, inside0) = ringsAndInside(listOf(outline), lw, PlateGeometry.frameWalls)
        val inside = if ("basenotchfill" in without) ringsAndInside(listOf(baseOutline(p, layer, notch = false)), lw, PlateGeometry.frameWalls).second else inside0
        if (rings.isEmpty() || inside.isEmpty()) return rings to emptyList()
        val angle = if ("basesameangle" in without) 45.0 else baseAngle(layer)
        val (segs, idx) = Hatch.indexed(inside, angle, sp)
        if (segs.isEmpty()) return rings to emptyList()
        if ("basehalves" in without) {
            val order = (0 until segs.size / 4).sortedBy { idx[it] }
            val mid = (idx.min() + idx.max()) / 2
            return rings to (order.filter { idx[it] > mid } + order.filter { idx[it] <= mid }).map { segs.copyOfRange(4 * it, 4 * it + 4) }
        }
        val chains = Linker.link(segs, idx, inside, Planner.ANCHOR_SPACINGS * sp, breakAtSplits = true, breakAtForks = true)
        val end = rings.last()
        return rings to Ordering.sweep(chains.map { ToolPath(Feature.BOTTOM, it, false, lw, h) }, end[0], end[1]).map { it.xy }
    }

    /** Post [j] (0-based) of the stringing ring: its center (µm, on the plate). */
    fun ringPost(j: Int): Pair<Double, Double> {
        val n = spec.strings!!.posts; val r = PlateGeometry.ringR(n); val a = PlateGeometry.ringAngle(j, n)
        return (ox(Part.STRINGING) + r * Math.cos(a)) to (oy(Part.STRINGING) + r * Math.sin(a))
    }

    /** Every part's test in its own frame (µm: x0, y0, x1, y1): every line it lays above the bases (the post: on every layer), widened by half its width. */
    private fun ownFootprints(): Map<Part, LongArray> {
        val layers = (spec.topUm / h).toInt()
        return parts.associateWith { p ->
            val b = longArrayOf(Long.MAX_VALUE, Long.MAX_VALUE, Long.MIN_VALUE, Long.MIN_VALUE)
            for (layer in (if (p == Part.POST) 0 else spec.baseLayers) until layers) for (e in partPaths(p, layer) + (if (p == Part.MELT) meltStrips(layer) else emptyList())) {
                val hw = (e.widthUm + 1) / 2
                for (i in 0 until e.xy.size / 2) {
                    val x = e.xy[2 * i] - spec.originXUm; val y = e.xy[2 * i + 1] - spec.originYUm
                    b[0] = minOf(b[0], x - hw); b[1] = minOf(b[1], y - hw); b[2] = maxOf(b[2], x + hw); b[3] = maxOf(b[3], y + hw)
                }
            }
            check(b[0] <= b[2]) { "the plate's ${p.title} lays no line" }
            b
        }
    }

    companion object {
        private val footprintCache = java.util.Collections.synchronizedMap(java.util.WeakHashMap<CalibrationSpec, Map<Part, LongArray>>())

        /**
         * Each part's test in its own frame (µm: x0, y0, x1, y1): every line it lays above the bases, widened by half its
         * width, the first layer's at the plate's own width (each part's outline is where it is whatever the first layer's
         * width, so the layout does not move with it).
         */
        fun testFootprints(spec: CalibrationSpec): Map<Part, LongArray> =
            footprintCache.getOrPut(spec) { CalibrationPlate(spec, setOf("firstwidth"), local = true).ownFootprints() }

        /** Each part's footprint in its own frame, its base included ([PlateGeometry.baseMargin] round its test): what the packing places. */
        fun footprints(spec: CalibrationSpec): Map<Part, LongArray> = testFootprints(spec).mapValues { (p, f) ->
            val m = if (spec.baseLayers > 0 && p != Part.POST) PlateGeometry.baseMargin else 0L
            longArrayOf(f[0] - m, f[1] - m, f[2] + m, f[3] + m)
        }
    }

    // ---- the terraced melt test's geometry (µm, on the plate)

    /** The strips' width: a surface is a line's length and this wide, so every strip's lines lie within it. */
    private val meltWMax: Long get() = spec.melt!!.widthUm
    /** A melt surface's width (x): a strip line's length and the strips' width. */
    val meltSurfaceW: Long get() = PlateGeometry.meltLineLen + meltWMax
    /** The bead spacing of strip [k]'s lines: the bead model's for its width and its own thickness. */
    private fun meltSp(k: Int) = spec.melt!!.let { mt -> Math.round(mt.widthUm - mt.heightsUm[k] * (1 - Math.PI / 4)) }
    /** Between strip [k]'s last line and strip [k] + 1's first: the mean of their two spacings. */
    private fun meltBoundarySp(k: Int) = (meltSp(k) + meltSp(k + 1)) / 2
    /**
     * Line [j] of strip [k]: its center's distance (y) from its step's front edge. The first strip's first line half its width
     * in from the front; each next line one bead spacing on, each next strip the mean of the two spacings on.
     */
    fun meltLineY(k: Int, j: Int): Long {
        val mt = spec.melt!!
        var y = mt.widthUm / 2
        for (i in 0 until k) y += (mt.lines - 1) * meltSp(i) + meltBoundarySp(i)
        return y + j * meltSp(k)
    }
    /** A step's depth (y): its strips' lines, the last strip's last line half its width from the back. */
    val meltStepD: Long get() = spec.melt!!.let { mt -> meltLineY(mt.count - 1, mt.lines - 1) + mt.widthUm / 2 }
    /** Band [b]'s read layer (0-based) as this plate prints it: the specification's (the control "meltstep": band 2's one layer low). */
    fun meltRead(b: Int): Int = spec.meltReadLayer(b) - if ("meltstep" in without && b == 1) 1 else 0

    /**
     * The melt strips on [layer], in print order: per band whose read layer it is, FAST, MID, SLOW, each surface's strips
     * thinnest first (front to back), strip k at the floor's top (the layer's bottom) plus its own thickness, [MeltTerraces.widthUm]
     * wide, at its surface's speed. Controls: "meltmidfast" the MID surface at FAST's speed; "meltwidth" band 1's FAST strip 3
     * 0.05 mm wider; "meltheight" band 1's FAST strip 3 printed 0.04 mm higher than its thickness (its filament unchanged);
     * "meltthickfirst" band 1's FAST strips thickest first; "melt2read" band 1's strips laid again on the layer above.
     */
    private fun meltStrips(layer: Int): List<Extrude> {
        val mt = spec.melt ?: return emptyList()
        val out = ArrayList<Extrude>()
        for (b in spec.bands.indices) {
            val read = meltRead(b)
            if (layer != read && !("melt2read" in without && b == 0 && layer == read + 1)) continue
            val floorTop = layer * h
            for (s in 0 until 3) {
                val speed = if (s == 1 && "meltmidfast" in without) mt.speeds[0] else mt.speeds[s]
                val order = if ("meltthickfirst" in without && b == 0 && s == 0) (mt.count - 1 downTo 0) else (0 until mt.count)
                for (k in order) {
                    val odd = b == 0 && s == 0 && k == 2
                    val sw = mt.widthUm + if ("meltwidth" in without && odd) 50 else 0
                    val z = floorTop + mt.heightsUm[k] + if ("meltheight" in without && odd) 40 else 0
                    out += extrude("Top surface", meltStripPath(b, s, k), false, z,
                        Exact(mt.label(b, s, k), doubleArrayOf(speed), mt.accel, spec.flowRatio, spec.retractMm), sw, mt.heightsUm[k])
                }
            }
        }
        return out
    }

    /** The tallest melt strip's top laid so far in [program] (µm). */
    private var stripTop = 0L

    /**
     * [meltStrips] on [layer] with the lifts that keep the nozzle clear of the strips already laid (they stand above the layer
     * grid, up to the tool's layer.height.max above their floor): before each surface's first strip, a lift to the tallest strip laid so
     * far plus the travel lift ([MeltTerraces.liftUm]), so the travel there passes above every strip it crosses; within a
     * surface each strip follows the thinner one beside it (no lift: the nozzle steps off its own last line onto the floor and
     * rises); after the layer's last strip the same lift, before the head parks or the next layer starts. The control
     * "meltnolift": the lifts before the surfaces left out (the writer's own lift, from the strip it leaves, then applies).
     */
    private fun meltStripSteps(layer: Int): List<Step> {
        val strips = meltStrips(layer)
        if (strips.isEmpty()) return emptyList()
        val lift = spec.melt!!.liftUm
        val out = ArrayList<Step>()
        var surface: String? = null
        for (e in strips) {
            val sf = e.exact!!.label.substringBeforeLast('.')
            if (sf != surface && stripTop > 0 && "meltnolift" !in without) out += plymeld.program.LiftTo(stripTop + lift)
            surface = sf
            out += e
            stripTop = maxOf(stripTop, e.zUm)
        }
        out += plymeld.program.LiftTo(stripTop + lift)
        return out
    }
    /**
     * Band [band]'s step's surface [s] (0 FAST, 1 MID, 2 SLOW, left to right), as a box (µm: x0, y0, x1, y1): the body under
     * it and the strips on it. The surfaces [PlateGeometry.meltSurfaceGap] apart; the steps touching, band 1's at the front.
     */
    fun meltBox(band: Int, s: Int): LongArray {
        val x0 = ox(Part.MELT) + s * (meltSurfaceW + PlateGeometry.meltSurfaceGap); val y0 = oy(Part.MELT) + band * meltStepD
        return longArrayOf(x0, y0, x0 + meltSurfaceW, y0 + meltStepD)
    }
    /** Strip [k]'s lines on band [band]'s step's surface [s]: back and forth along X, each [PlateGeometry.meltLineLen] long, centered in the surface. */
    private fun meltStripPath(band: Int, s: Int, k: Int): LongArray {
        val mt = spec.melt!!; val b = meltBox(band, s)
        val xa = b[0] + meltWMax / 2; val xb = xa + PlateGeometry.meltLineLen
        val pts = ArrayList<Long>()
        for (j in 0 until mt.lines) {
            val y = b[1] + meltLineY(k, j)
            // the line's direction by its place in the whole surface, so each strip carries on where the last one ended
            if ((k * mt.lines + j) % 2 == 0) { pts += xa; pts += y; pts += xb; pts += y } else { pts += xb; pts += y; pts += xa; pts += y }
        }
        return pts.toLongArray()
    }
    /** The y (µm, on the plate) of the boundary between strip [k] and strip [k] + 1 on band [band]'s step: half way between their lines. */
    fun meltBoundaryY(band: Int, k: Int): Long = meltBox(band, 0)[1] + (meltLineY(k, spec.melt!!.lines - 1) + meltLineY(k + 1, 0)) / 2

    /**
     * The body under band [band]'s step's surface [s] on one layer, in [lw] µm lines: two walls inward from its edge, and
     * inside them a grid: front-to-back (Y) lines and left-right (X) lines, each about [PlateGeometry.pedestalPitch] apart,
     * spread evenly between the two inner walls, both in every layer (a grid within the layer). [solid]: the step's floor (the
     * layer under its read layer): the two walls and front-to-back lines one bead spacing apart, perpendicular to
     * the strips' lines, each crossing the grid's X lines below every pitch. Its rings, then its grid's paths (each back and
     * forth, joined along the inner wall: the Y lines' joins at its front and back, the X lines' at its left and right, so the
     * two never lie on each other).
     *
     * Earlier, the X lines stood only under the strip boundaries, where the line width changed (front-to-back
     * lines as sparse as they could be without making it a bridging test), and no layer was solid. With every strip the same width that reason is gone, and the floor's
     * lines run front to back, parallel to the grid's Y lines: what holds them up across the body is the X lines, so those now
     * stand every pitch (plymeld's choice: the pedestals' pitch, so the floor bridges about 2 mm, not the 5 to 6 mm
     * between strip boundaries).
     */
    private fun meltBodyPaths(band: Int, s: Int, lw: Long, solid: Boolean): Pair<List<LongArray>, List<LongArray>> {
        val b = meltBox(band, s); val sp = spacingOf(lw)
        val rings = squareRings(b, maxRings = PlateGeometry.frameWalls, lw = lw)
        // where a third ring would be: lines here touch the inner wall
        val in0x = b[0] + lw / 2 + PlateGeometry.frameWalls * sp; val in1x = b[2] - lw / 2 - PlateGeometry.frameWalls * sp
        val in0y = b[1] + lw / 2 + PlateGeometry.frameWalls * sp; val in1y = b[3] - lw / 2 - PlateGeometry.frameWalls * sp
        fun zigzag(along: Boolean, at: List<Long>, from: Long, to: Long): LongArray {
            val pts = ArrayList<Long>()
            for ((i, a) in at.withIndex()) {
                val (s0, s1) = if (i % 2 == 0) from to to else to to from
                if (along) { pts += s0; pts += a; pts += s1; pts += a } else { pts += a; pts += s0; pts += a; pts += s1 }
            }
            return pts.toLongArray()
        }
        if (solid) {
            // the floor: front-to-back lines from the inner wall's left to its right, as many as make them one bead spacing
            // apart, spread evenly (so the last touches the wall too: within a fraction of a percent of the spacing)
            val n = maxOf(2, Math.round((in1x - in0x).toDouble() / sp).toInt() + 1)
            return rings to listOf(zigzag(false, (0 until n).map { in0x + it * (in1x - in0x) / (n - 1) }, in0y, in1y))
        }
        // the Y lines: between the two inner walls' center lines, about the pitch apart (the walls the outermost of the grid)
        val wx0 = b[0] + lw / 2 + sp; val wx1 = b[2] - lw / 2 - sp
        val m = maxOf(2, Math.round((wx1 - wx0).toDouble() / PlateGeometry.pedestalPitch).toInt())
        val ys = zigzag(false, (1 until m).map { wx0 + it * (wx1 - wx0) / m }, in0y, in1y)
        // the X lines: the same way, front to back
        val wy0 = b[1] + lw / 2 + sp; val wy1 = b[3] - lw / 2 - sp
        val mm = maxOf(2, Math.round((wy1 - wy0).toDouble() / PlateGeometry.pedestalPitch).toInt())
        val xAt = (1 until mm).map { wy0 + it * (wy1 - wy0) / mm }
        return rings to listOf(ys, zigzag(true, xAt, in0x, in1x))
    }

    /** The overhang spine's left end and its back (µm, on the plate); its front [PlateGeometry.ledgeBase] in front of the back, the ledges standing out of it. */
    val ledgeX0: Long get() = ox(Part.OVERHANGS)
    val ledgeBack: Long get() = oy(Part.OVERHANGS) + PlateGeometry.ledgeBase
    /** Ledge [k]'s x range (µm). */
    fun ledgeCell(k: Int): Pair<Long, Long> { val a = ledgeX0 + PlateGeometry.ledgeEnd + k * (PlateGeometry.ledgeLen + PlateGeometry.ledgeGap); return a to a + PlateGeometry.ledgeLen }
    /** The spine's right end (µm). */
    val ledgeX1: Long get() = ledgeCell(spec.ledges!!.shares.size - 1).second + PlateGeometry.ledgeEnd

    /** The spine's outline on the layer whose top is [zUm]: its straight back, its front with each ledge standing out by its depth (counter-clockwise). */
    private fun ledgeOutline(zUm: Long): Polygon {
        val l = spec.ledges!!
        val front = ledgeBack - PlateGeometry.ledgeBase
        val pts = ArrayList<Long>()
        fun p(x: Long, y: Long) { pts += x; pts += y }
        p(ledgeX0, front)
        for (k in l.shares.indices) {
            val d = if ("lean" in without) 0 else PlateGeometry.ledgeDepth(spec, l.shares[k], zUm)
            if (d <= 0) continue
            val (a, b) = ledgeCell(k)
            p(a, front); p(a, front - d); p(b, front - d); p(b, front)
        }
        p(ledgeX1, front); p(ledgeX1, ledgeBack); p(ledgeX0, ledgeBack)
        return Polygon(pts.toLongArray())
    }

    /** The spine's rings on a layer, outermost first, in [lw] µm lines. */
    private fun ledgeRings(zUm: Long, lw: Long): List<LongArray> = solidRings(listOf(ledgeOutline(zUm)), lw)

    /**
     * A pedestal under box [b], in [lw] µm lines: two walls inward from its edge, and sparse lines inside them about
     * [PlateGeometry.pedestalPitch] apart, back and forth along X or Y (turning layer by layer), so what stands on it
     * has lines below it every couple of millimetres. Its rings, then its lines.
     */
    private fun pedestalPaths(b: LongArray, alongX: Boolean, lw: Long): Pair<List<LongArray>, LongArray> {
        val rings = squareRings(b, maxRings = PlateGeometry.frameWalls, lw = lw)
        val inset = lw / 2 + PlateGeometry.frameWalls * spacingOf(lw)
        val f0x = b[0] + inset; val f0y = b[1] + inset; val f1x = b[2] - inset; val f1y = b[3] - inset
        val span = if (alongX) f1y - f0y else f1x - f0x
        val n = maxOf(2, Math.round(span.toDouble() / PlateGeometry.pedestalPitch).toInt() + 1)
        val pts = ArrayList<Long>()
        for (i in 0 until n) {
            val a = (if (alongX) f0y else f0x) + i * span / (n - 1)
            val (s0, s1) = if (i % 2 == 0) (if (alongX) f0x to f1x else f0y to f1y) else (if (alongX) f1x to f0x else f1y to f0y)
            if (alongX) { pts += s0; pts += a; pts += s1; pts += a } else { pts += a; pts += s0; pts += a; pts += s1 }
        }
        return rings to pts.toLongArray()
    }

    /** A rectangle's rings in [lw] µm lines, outermost first: solid, or at most [maxRings] (the piers; a pedestal's walls). */
    private fun squareRings(b: LongArray, maxRings: Int = Int.MAX_VALUE, lw: Long): List<LongArray> =
        solidRings(listOf(Polygon(longArrayOf(b[0], b[1], b[2], b[1], b[2], b[3], b[0], b[3]))), lw, maxRings)

    /** Lines across a bridge deck: as many as make it about [PlateGeometry.deckAbout] deep at the bead spacing. */
    val deckLines: Int get() = maxOf(2, ((PlateGeometry.deckAbout - w) / spacing).toInt() + 1)
    /** A deck's depth (µm): its lines at the bead spacing and a line's width. */
    val deckDepth: Long get() = (deckLines - 1) * spacing + w
    /**
     * The bridge row's front edge (µm, on the plate). Its depth: a deck strip per band from the front and another from the
     * back (terraced), and the gap between the innermost two.
     */
    val bridgeY0: Long get() = oy(Part.BRIDGES)
    val bridgeDepth: Long get() = 2 * spec.bands.size * deckDepth + PlateGeometry.deckGap
    /**
     * Band [band]'s deck [d] (0 = front, 1 = back) as a y range (µm: front edge, back edge): the front deck in deck strip
     * [band] from the row's front, the back deck in strip [band] from its back. The controls: "untiered" lays the top
     * band's decks where the row before the terraces had them (the front deck at the front, the back one
     * [PlateGeometry.deckGap] behind it); "deckoverlap" moves the top band's decks half a deck outward, over the band
     * below's.
     */
    fun deckY(band: Int, d: Int): Pair<Long, Long> {
        val top = spec.bands.size - 1
        if (band == top && "untiered" in without) {
            val y0 = bridgeY0 + d * (deckDepth + PlateGeometry.deckGap)
            return y0 to y0 + deckDepth
        }
        val shift = if (band == top && "deckoverlap" in without) deckDepth / 2 else 0L
        return if (d == 0) (bridgeY0 + band * deckDepth - shift).let { it to it + deckDepth }
            else (bridgeY0 + bridgeDepth - band * deckDepth + shift).let { it - deckDepth to it }
    }
    /** Pier [k]'s x range (µm), from the row's left edge, the spans between them in order. */
    fun pierX(k: Int): Pair<Long, Long> {
        var x = ox(Part.BRIDGES)
        for (i in 0 until k) x += PlateGeometry.pierW + spec.bridges!!.spansUm[i]
        return x to x + PlateGeometry.pierW
    }
    /** The band whose decks the layer whose top is [zUm] carries (its top read layer), or null. */
    fun deckBandAt(zUm: Long): Int? = spec.bands.firstOrNull { it.readToUm == zUm }?.index

    /**
     * Pier [k]'s rings: the whole row's depth; on band [deckBand]'s deck layer the row less that band's two decks' strips
     * (the decks lie on the pier there): what is in front of the front deck, between the decks, and behind the back one,
     * each piece on its own. Two rings: a pier need not be solid.
     */
    private fun pierRings(k: Int, deckBand: Int?, lw: Long): List<LongArray> {
        val (x0, x1) = pierX(k)
        val row = bridgeY0 to bridgeY0 + bridgeDepth
        val pieces = if (deckBand == null) listOf(row) else {
            val f = deckY(deckBand, 0); val b = deckY(deckBand, 1)
            listOf(row.first to f.first, f.second to b.first, b.second to row.second).filter { it.second > it.first }
        }
        return pieces.flatMap { (y0, y1) -> squareRings(longArrayOf(x0, y0, x1, y1), maxRings = 2, lw = lw) }
    }

    /** Tube [k]'s outside, as a box (µm): in a row from the left, [PlateGeometry.tubeGap] apart. */
    fun tubeBox(k: Int): LongArray {
        val x0 = ox(Part.LINE_WIDTH) + k * (PlateGeometry.tube + PlateGeometry.tubeGap); val y0 = oy(Part.LINE_WIDTH)
        return longArrayOf(x0, y0, x0 + PlateGeometry.tube, y0 + PlateGeometry.tube)
    }
    /** Tube [k]'s one line: its center half its own width in from the outside. */
    private fun tubeRing(k: Int): LongArray {
        val b = tubeBox(k); val hw = spec.tubes!!.widthsUm[k] / 2
        return longArrayOf(b[0] + hw, b[1] + hw, b[2] - hw, b[1] + hw, b[2] - hw, b[3] - hw, b[0] + hw, b[3] - hw)
    }

    /** The fit block's box (µm, on the plate): its holes in a row, the peg right of it. */
    fun fitBox(): LongArray {
        val f = spec.fit!!
        val maxHole = f.holesUm.max()
        val wd = f.holesUm.size * (maxHole + PlateGeometry.fitWall) + PlateGeometry.fitWall
        val dp = maxHole + 2 * PlateGeometry.fitWall
        val x0 = ox(Part.FIT); val y0 = oy(Part.FIT)
        return longArrayOf(x0, y0, x0 + wd, y0 + dp)
    }
    /** Hole [k]'s center (µm): one every largest hole and a wall, from the block's left wall. */
    fun holeCenter(k: Int): Pair<Long, Long> {
        val f = spec.fit!!; val b = fitBox(); val maxHole = f.holesUm.max()
        return (b[0] + PlateGeometry.fitWall + k * (maxHole + PlateGeometry.fitWall) + maxHole / 2) to (b[1] + b[3]) / 2
    }
    /** The peg's center (µm): right of the block, level with its holes. */
    fun pegCenter(): Pair<Long, Long> { val b = fitBox(); return (b[2] + PlateGeometry.pegGap + spec.fit!!.pegUm / 2) to (b[1] + b[3]) / 2 }

    /** A circle [d] across round ([cx], [cy]), counter-clockwise (an outline) or clockwise (a hole). */
    private fun circle(cx: Long, cy: Long, d: Long, ccw: Boolean): Polygon {
        val n = PlateGeometry.circleSides
        val pts = LongArray(2 * n)
        for (i in 0 until n) {
            val a = 2 * Math.PI * (if (ccw) i else n - i) / n
            pts[2 * i] = cx + Math.round(d / 2.0 * Math.cos(a)); pts[2 * i + 1] = cy + Math.round(d / 2.0 * Math.sin(a))
        }
        return Polygon(pts)
    }

    /**
     * Rings of [polys] (outlines and holes) in [lw] µm lines, outermost first: the first half a line in from the outline
     * (so the outline stays where it is whatever the width), each next one a bead spacing further in (and, round holes,
     * out); solid, or at most [maxRings].
     */
    private fun solidRings(polys: List<Polygon>, lw: Long, maxRings: Int = Int.MAX_VALUE): List<LongArray> = ringsAndInside(polys, lw, maxRings).first

    /**
     * [solidRings] of [polys], at most [maxRings], and what is left inside them: the region one bead spacing inside the
     * innermost ring (where a further ring would lie: lines clipped to it touch that ring).
     */
    private fun ringsAndInside(polys: List<Polygon>, lw: Long, maxRings: Int): Pair<List<LongArray>, List<Polygon>> {
        val rings = ArrayList<LongArray>()
        val sp = spacingOf(lw)
        var cur = Offset.offset(polys, -lw / 2)
        var n = 0
        while (cur.isNotEmpty() && n < maxRings) { for (r in cur) if (r.size >= 3) rings += r.xy; cur = Offset.offset(cur, -sp); n++ }
        return rings to cur
    }

    /** The fit block's rings in [lw] µm lines: its outline and its holes (all at the peg's size for the control "clearance"). */
    private fun fitBlockRings(lw: Long): List<LongArray> {
        val f = spec.fit!!; val b = fitBox()
        val outline = Polygon(longArrayOf(b[0], b[1], b[2], b[1], b[2], b[3], b[0], b[3]))
        val holes = f.holesUm.indices.map { k -> val (cx, cy) = holeCenter(k); circle(cx, cy, if ("clearance" in without) f.pegUm else f.holesUm[k], ccw = false) }
        return solidRings(listOf(outline) + holes, lw)
    }

    /** Band [band]'s deck [d]'s lines (0 = front): back and forth along the row from the first pier to the last, in its strip ([deckY]), each one bead spacing from the next. */
    private fun deckPath(d: Int, band: Int): LongArray {
        val y0 = deckY(band, d).first + w / 2
        val xa = pierX(0).first + w / 2; val xb = pierX(spec.bridges!!.spansUm.size).second - w / 2
        val pts = ArrayList<Long>()
        for (i in 0 until deckLines) {
            val y = y0 + i * spacing
            if (i % 2 == 0) { pts += xa; pts += y; pts += xb; pts += y } else { pts += xb; pts += y; pts += xa; pts += y }
        }
        return pts.toLongArray()
    }

    /**
     * The plate's g-code: the machine's start and end, everything between exactly as specified.
     * [pressureAdvance]: by tool (Setup.pressureAdvanceByTool); the plate's tool unlisted = the firmware's own.
     */
    fun gcode(s: plymeld.model.SliceSettings, foreignVars: Map<String, List<String>>, version: String, pressureAdvance: Map<Int, Double> = emptyMap()): String =
        java.io.StringWriter().also { plymeld.gcode.KlipperWriter(s, foreignVars, version, pressureAdvance = pressureAdvance).write(shifted(program()), it) }.toString()

    /**
     * The program with its first layer [CalibrationSpec.firstLayerUm] thick: every layer's Z up by the difference to [h], the
     * first layer's paths and layer start at that thickness (the writer's bead model then lays the filament for it).
     */
    fun shifted(p: PrintProgram): PrintProgram {
        val delta = spec.firstLayerUm - h
        if (delta == 0L) return p
        val steps = p.steps.map { st ->
            when (st) {
                is LayerStart -> LayerStart(st.index, st.zUm + delta, if (st.index == 0) spec.firstLayerUm else st.heightUm, st.objectLayer, st.archedBridge, st.printTemp)
                is Extrude -> Extrude(st.feature, st.xy, st.closed, st.zUm + delta, st.widthUm, if (st.zUm == h) spec.firstLayerUm else st.heightUm, st.tool,
                    st.overhangShare, st.ePerMmOverride, st.retractBefore, st.widths, st.exact, st.zs, st.heights, st.via)
                is plymeld.program.LiftTo -> plymeld.program.LiftTo(st.zUm + delta)
                else -> st
            }
        }
        return PrintProgram(steps, p.objects, p.exact, p.selfPrimed)
    }

    /** Where the head waits out a layer's cooling pause: [PlateGeometry.parkFront] in front of the plate, at its middle (bed coordinates, µm). */
    fun parkUm(): Pair<Long, Long> = bounds().let { b -> (spec.originXUm + (b[0] + b[2]) / 2) to (spec.originYUm + b[1] - PlateGeometry.parkFront) }

    /**
     * A layer's own time (s), a lower bound: its paths at their speeds, the travels between them and to the
     * park and back at the travel speed, accelerations left out (so the pause is, if anything, long).
     */
    private fun layerTimeS(paths: List<Extrude>, park: Pair<Long, Long>): Double {
        var t = 0.0
        var lx = Double.NaN; var ly = Double.NaN
        val travel = if (spec.travelSpeed > 0) spec.travelSpeed else Double.POSITIVE_INFINITY
        for (p in paths) {
            val m = p.xy.size / 2
            val sx = p.xy[0] / 1000.0; val sy = p.xy[1] / 1000.0
            if (!lx.isNaN()) t += Math.hypot(sx - lx, sy - ly) / travel
            for (k in 0 until if (p.closed) m else m - 1) {
                val j = (k + 1) % m
                t += Math.hypot((p.xy[2 * j] - p.xy[2 * k]) / 1000.0, (p.xy[2 * j + 1] - p.xy[2 * k + 1]) / 1000.0) / p.exact!!.speed(k)
            }
            lx = if (p.closed) sx else p.xy[2 * m - 2] / 1000.0; ly = if (p.closed) sy else p.xy[2 * m - 1] / 1000.0
        }
        val first = paths.firstOrNull() ?: return 0.0
        val px = park.first / 1000.0; val py = park.second / 1000.0
        return t + (Math.hypot(px - lx, py - ly) + Math.hypot(first.xy[0] / 1000.0 - px, first.xy[1] / 1000.0 - py)) / travel
    }

    /** The plate's extent before it is placed (µm: x0, y0, x1, y1): every part's footprint, the ledges, and the bases included. */
    fun bounds(): LongArray {
        val b = longArrayOf(Long.MAX_VALUE, Long.MAX_VALUE, Long.MIN_VALUE, Long.MIN_VALUE)
        for (p in parts) { val f = footprint(p); b[0] = minOf(b[0], f[0]); b[1] = minOf(b[1], f[1]); b[2] = maxOf(b[2], f[2]); b[3] = maxOf(b[3], f[3]) }
        return b
    }

    /** A path of the plate, placed on the bed, [widthUm] wide (every caller says which width: the layer's, or a part's own). */
    private fun extrude(feature: String, xy: LongArray, closed: Boolean, zUm: Long, ex: Exact, widthUm: Long, heightUm: Long = h): Extrude {
        val ox = spec.originXUm; val oy = spec.originYUm
        val moved = LongArray(xy.size) { i -> xy[i] + if (i % 2 == 0) ox else oy }
        return Extrude(feature, moved, closed, zUm, widthUm, heightUm, tool = spec.tool, exact = ex)
    }

    /** The post's outline at a layer whose top is [zUm]: a square, its front-left corner chamfered, notched in a band. */
    private fun postOutline(zUm: Long): Polygon {
        val notch = if ("notches" in without) null else PlateGeometry.notchAt(spec, zUm)
        val x0 = PlateGeometry.postX + ox(Part.POST); val y0 = PlateGeometry.postY + oy(Part.POST); val x1 = x0 + PlateGeometry.post; val y1 = y0 + PlateGeometry.post
        val pts = ArrayList<Long>()
        fun p(x: Long, y: Long) { pts += x; pts += y }
        // counter-clockwise from the chamfer's front end
        p(x0 + chamfer, y0)
        if (notch != null) {
            val nw = PlateGeometry.notchWidth(notch)
            val nx0 = x0 + chamfer + (x1 - x0 - chamfer - nw) / 2; val nx1 = nx0 + nw
            p(nx0, y0); p(nx0, y0 + notchDepth); p(nx1, y0 + notchDepth); p(nx1, y0)
        }
        p(x1, y0); p(x1, y1); p(x0, y1); p(x0, y0 + chamfer)
        return Polygon(pts.toLongArray())
    }

    /** The post solid in [lw] µm lines: concentric rings inward from its outline, outermost first. */
    private fun postRings(zUm: Long, lw: Long): List<LongArray> = solidRings(listOf(postOutline(zUm)), lw)
}
