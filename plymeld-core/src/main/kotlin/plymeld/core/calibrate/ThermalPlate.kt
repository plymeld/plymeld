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
import plymeld.program.SetTemp
import plymeld.program.Step

/**
 * The thermal plate: how a filament cools on a machine, read by position or go/no-go against gauges printed with it, on one
 * base slab. Five sections:
 *
 *  1. **Strand bridges** (how long a bead stays soft, by fan duty): rows front to back, one per fan duty ([ThermalSpec.strandFans]);
 *     in each row a pair of piers [ThermalGeometry.pierHeight] tall per span ([ThermalSpec.spansUm]), one single line laid across
 *     each gap at the piers' top at the bridge speed, then [ThermalGeometry.capLayers] cap layers on the piers only (holding the
 *     strand's ends). The fan is set to the row's duty and waited for, parked ([ThermalSpec.settleS]), before the row's strands.
 *     Gauge: **the feeler**, a stepped bar printed flat beside the slab, each step as thick as the piers' height less an allowed
 *     sag ([ThermalGeometry.FEELER_SAGS]), each step's place in the bar a tally of notches (1, 2, 3).
 *  2. **Pin towers** (cooling into the layers below; the real minimum layer time): round pins ([ThermalSpec.pinDiametersUm]) on
 *     stout pedestals up to [ThermalGeometry.upperFrom] above the slab, their test lengths above in bands
 *     [ThermalGeometry.pinBand] tall, each band's layers made up to its layer time ([ThermalSpec.pinTimesS], shortest lowest) by a
 *     wait with the head parked clear of the plate; a band stair of posts beside them, each topping out at a band's top, each
 *     band's layer time raised on the slab before its post. Gauge: **the ring card**, a flat card with a hole per pin, the pin's
 *     diameter plus [ThermalGeometry.holeClearance], each hole's diameter raised beside it.
 *  3. **Bond fins** (bond against how hot the layer below was): rows by fan duty ([ThermalSpec.finFans]), a fin per layer time
 *     ([ThermalSpec.finTimesS]); each fin [ThermalGeometry.finWidth] wide, three lines thick below its one test band
 *     ([ThermalGeometry.finBand] tall, a V notch cut into both its edges at the band's middle), single above it for a lever of
 *     [ThermalGeometry.lever]; fin k's band at its own height, its layers made up to its layer time; a stop post
 *     [ThermalGeometry.postBack] behind each fin's top. Each fin's own lines at its row's duty.
 *  4. **Fan compass** (fan reach and shadowing by direction): a round post with eight identical tabs round it, each a ledge
 *     stepping out a layer at a time at [ThermalGeometry.tabLeanDeg] from vertical, two lines thick, its tip standing between two
 *     reference posts whose tops are at the tip's height; the directions numbered 1 to 8 on the slab, 1 toward the front,
 *     clockwise from above. Gauge: the feeler's flat back, laid across a tab's two reference posts.
 *  5. **Fan lag wall** (how long a fan command takes to act): a wall two lines thick along the back of the slab, its outer line
 *     laid left to right at the visible speed; in its lower half the fan off until a marked point, then full; in its upper half
 *     full until the mark, then off; a notch in the slab's back edge at the switch and one every [ThermalGeometry.lagPitch] after
 *     it (every fifth long, [PlateMarks]); the speed raised in front of the wall.
 *
 * **Height zones**: a wait can only be added to a whole layer, and a short layer time is only possible where little prints, so
 * the strands, the compass, the lag wall, the fins and the pins' pedestals stand in the lower zone (the slab's top to
 * [ThermalGeometry.upperFrom]), and only the pins' test lengths and the band stair rise into the upper zone above it. A banded
 * layer's time is made up to its target by a wait with the head parked in front of the plate, computed from plymeld's own time
 * prediction of the layer as written ([Park] then [plymeld.core.Predict]); a target shorter than the layer's natural time is
 * refused when the plate is built, naming the band and both times.
 *
 * **One base**: the slab ([ThermalGeometry.slabLayers] layers, its front-left corner chamfered, the origin) under every section;
 * the two gauges flat on the bed in front of it, each joined to the slab's front edge by thin break-off tabs, so the plate lifts
 * as one piece. Lengths µm, speeds mm/s, accelerations mm/s², times s, fan duties %.
 */

/**
 * The thermal plate's layout and its placeholder values (µm unless named): plymeld's choice, not measured. The
 * values a user steps (fan duties, spans, pin diameters, band and fin layer times, the fan's settle) are [ThermalSpec]'s, with
 * these as their defaults.
 */
object ThermalGeometry {
    /** The slab's layers, the first included (the seam plate's). */
    const val slabLayers = SeamGeometry.slabLayers
    /** The slab round the sections; between two sections; a label to what it names. */
    const val margin = 3_000L
    const val sectionGap = 4_000L
    const val labelGap = 1_500L
    /** The labels stand this many layers on the slab (and on the ring card). */
    const val labelLayers = 2

    // ---- 1. strands
    /** A pier: this long along its strand (the strand's anchor), this deep, this tall above the slab. */
    const val pierX = 3_000L
    const val pierY = 3_000L
    const val pierHeight = 6_000L
    /** Cap layers on each pier over its strand's end. */
    const val capLayers = 2
    /** Between one pair of piers and the next. */
    const val pairGap = 3_000L
    val STRAND_FANS = listOf(0.0, 50.0, 100.0)
    val STRAND_SPANS = listOf(10_000L, 20_000L, 30_000L, 40_000L)
    /** The fan's settle: after a change of duty that a reading depends on, the head waits this long, parked (s). */
    const val SETTLE_S = 5.0

    // ---- the feeler
    /** The sags the feeler's steps allow, thinnest step's first: each step is the piers' height less its sag. */
    val FEELER_SAGS = listOf(2_000L, 1_000L, 500L)
    /** Its width (narrower than the shortest span) and each step's length. */
    const val feelerWidth = 6_000L
    const val feelerStep = 15_000L

    // ---- 2. pins
    val PIN_DIAMETERS = listOf(2_000L, 3_000L, 4_000L, 6_000L)
    /** The pedestals: this across, up to [upperFrom] above the slab, [pinGap] apart. */
    const val pedestal = 8_000L
    const val pinGap = 3_000L
    /** The upper zone: from here (above the slab) only the pins' test lengths and the band stair print. */
    const val upperFrom = 15_000L
    /** Each band's height. */
    const val pinBand = 3_000L
    /**
     * The pin bands' layer times, shortest lowest. Above the pedestals only the pins and the band stair print, a few seconds a
     * layer on a fast machine (about 4 s on a 600 mm/s, 10000 mm/s² one): the shortest band is the first whole second above that,
     * so the defaults build there; a slower machine's plate is refused, naming the band, until longer ones are given.
     */
    val PIN_TIMES = listOf(5.0, 6.0, 8.0, 10.0, 15.0)
    /** A band stair post: this square. */
    const val stairPost = 3_000L
    /** A round part's outline: this many vertices. */
    const val roundSides = 48

    // ---- the ring card
    /** Each hole is its pin's diameter plus this. */
    const val holeClearance = 300L
    /** The card's thickness (rounded to whole layers) and its edge round the holes and labels. */
    const val cardThickness = 1_000L
    const val cardEdge = 2_000L

    // ---- 3. fins
    const val finWidth = 10_000L
    /** Each fin's test band: this tall, the first starting [finBandFrom] above the slab, each next [finBandPitch] higher. */
    const val finBand = 1_000L
    const val finBandFrom = 1_500L
    const val finBandPitch = 1_500L
    /** The single line above the band: the lever. */
    const val lever = 8_000L
    /** The V notch cut into each edge of the band: this deep at the band's middle. */
    const val notchDepth = 1_000L
    /** The stop post: this far behind the fin's back face, this square. */
    const val postBack = 3_000L
    const val finPost = 2_000L
    /**
     * The fins' layer times, left to right. A fin's band shares its layers with everything else in the lower zone (the piers,
     * the feeler, the compass, the lag wall and its fan's settle, the pedestals), which takes 35 to 45 s a layer on a fast
     * machine: no band can be shorter, so the shortest is above that.
     */
    val FIN_TIMES = listOf(45.0, 60.0, 90.0, 120.0)
    val FIN_FANS = listOf(0.0, 50.0, 100.0)

    // ---- 4. compass
    /** The post: this across and this tall above the slab (the tabs' tips at its top). */
    const val compassPost = 10_000L
    const val compassHeight = 12_000L
    const val tabs = 8
    /** Each tab: this long along its slope, this wide, leaning out this far from vertical. */
    const val tabLen = 6_000L
    const val tabWidth = 5_000L
    const val tabLeanDeg = 50.0
    /** A reference post: this across (one between each pair of neighboring tabs: each tip stands between two). */
    const val refPost = 2_000L

    // ---- 5. lag wall
    const val lagLength = 80_000L
    /** Where the fan switches, along the wall from its left end; the notches from there, this far apart. */
    const val lagSwitch = 20_000L
    const val lagPitch = 5_000L
    const val lagHeight = 10_000L
    /** Between the wall's back face and the long notches' inner end. */
    const val lagEdgeGap = 1_000L
    /** The full duty of the lag wall's switch (%). */
    const val FULL = 100.0

    // ---- the gauges' joints
    /** Between a gauge and the slab; a break-off tab's width along the edge and its layers. */
    const val gaugeGap = 2_000L
    const val breakW = 1_200L
    const val breakLayers = 2
}

class ThermalSpec(
    /** The program's tool (0-based) and its spool's name. */
    val tool: Int,
    val spool: String,
    /** The layer height (µm) and the first layer's. */
    val layerUm: Long, val firstLayerUm: Long,
    /** The plate's line width (µm, the outer wall's), the first layer's. */
    val widthUm: Long, val firstWidthUm: Long,
    val filamentDiameterMm: Double,
    /** The flow ratio every line prints at (a strand's times the bridge flow, [bridge]). */
    val flowRatio: Double,
    /** The nozzle temperature above the first layer, the first layer's, and the part fan where no section sets its own (%; off on the first layer). */
    val tempC: Int, val firstTempC: Int, val fanPct: Double,
    /** The first layer's values; the structural parts' (the machine's print speed); the read surfaces' (a visible wall's); a strand's (the bridge speed and flow). */
    val adhesion: PathValues, val frame: PathValues, val visible: PathValues, val bridge: PathValues,
    val strandFans: List<Double> = ThermalGeometry.STRAND_FANS,
    val spansUm: List<Long> = ThermalGeometry.STRAND_SPANS,
    val pinDiametersUm: List<Long> = ThermalGeometry.PIN_DIAMETERS,
    val pinTimesS: List<Double> = ThermalGeometry.PIN_TIMES,
    val finTimesS: List<Double> = ThermalGeometry.FIN_TIMES,
    val finFans: List<Double> = ThermalGeometry.FIN_FANS,
    val settleS: Double = ThermalGeometry.SETTLE_S,
    /** How far the nozzle rises off the finished plate (µm, the travel lift). */
    val liftUm: Long,
    /** Where the plate's front-left corner (the gauges' front, the slab's left edge) goes on the bed (µm). */
    val originXUm: Long = 0,
    val originYUm: Long = 0,
    /** The machine's bed levelling, run after its start template; null = none. */
    val levelGcode: String? = null,
) {
    private val g = ThermalGeometry

    init {
        require(layerUm > 0 && firstLayerUm > 0 && widthUm > 0 && firstWidthUm > 0) { "the layer heights and line widths are above 0" }
        require(strandFans.isNotEmpty() && strandFans.all { it in 0.0..100.0 }) { "the strand rows' fan duties are 0 to 100 % (got ${strandFans.joinToString()})" }
        require(finFans.isNotEmpty() && finFans.all { it in 0.0..100.0 }) { "the fin rows' fan duties are 0 to 100 % (got ${finFans.joinToString()})" }
        require(spansUm.isNotEmpty() && spansUm.zipWithNext().all { (a, b) -> b > a }) { "the strands' spans rise, short to long (got ${spansUm.joinToString { mm(it) }} mm)" }
        require(spansUm.first() > g.feelerWidth) { "the shortest span, ${mm(spansUm.first())} mm, leaves no room for the ${mm(g.feelerWidth)} mm feeler under its strand" }
        require(pinDiametersUm.isNotEmpty() && pinDiametersUm.zipWithNext().all { (a, b) -> b > a }) { "the pins' diameters rise, small to large (got ${pinDiametersUm.joinToString { mm(it) }} mm)" }
        require(pinDiametersUm.first() >= 2 * widthUm) { "a pin of ${mm(pinDiametersUm.first())} mm is under two ${mm(widthUm)} mm lines" }
        require(pinDiametersUm.last() + 2 * widthUm <= g.pedestal) { "a pin of ${mm(pinDiametersUm.last())} mm does not stand on its ${mm(g.pedestal)} mm pedestal with a line to spare" }
        require(pinTimesS.isNotEmpty() && pinTimesS.all { it > 0 } && pinTimesS.zipWithNext().all { (a, b) -> b > a }) { "the pins' band layer times rise, shortest lowest, all above 0 (got ${pinTimesS.joinToString()} s)" }
        require(finTimesS.isNotEmpty() && finTimesS.all { it > 0 } && finTimesS.zipWithNext().all { (a, b) -> b > a }) { "the fins' layer times rise, left to right, all above 0 (got ${finTimesS.joinToString()} s)" }
        require(settleS >= 0) { "the fan's settle is 0 s or more (got $settleS)" }
        require(finTopAbove(finTimesS.size - 1) <= g.upperFrom) {
            "${finTimesS.size} fins: the last one's top, ${mm(finTopAbove(finTimesS.size - 1))} mm above the slab, rises into the pins' upper zone (from ${mm(g.upperFrom)} mm), where only the pins print: fewer fin layer times (--fin-times)"
        }
        require(g.compassHeight < g.upperFrom && g.lagHeight < g.upperFrom && g.pierHeight + (g.capLayers + 1) * layerUm < g.upperFrom) { "a lower-zone section rises into the upper zone" }
        require(4 * widthUm < g.finWidth) { "a ${mm(widthUm)} mm line leaves no room in a ${mm(g.finWidth)} mm fin" }
    }

    /** A length (µm) in mm, for messages. */
    private fun mm(v: Long) = PlateMarks.mm(v / 1000.0)

    // ================================================================ heights
    /** Layer [layer]'s top (µm): the first layer its own thickness, each layer above [layerUm] more. */
    fun zTop(layer: Int): Long = firstLayerUm + layer * layerUm
    fun heightOf(layer: Int): Long = if (layer == 0) firstLayerUm else layerUm
    /** The slab's top (µm). */
    val slabTop: Long get() = zTop(g.slabLayers - 1)
    /** Layers above the slab for a height [above] (µm above its top), rounded to whole layers. */
    fun layersFor(above: Long): Int = Math.round(above.toDouble() / layerUm).toInt()
    /** The plate layer of the [i]th layer above the slab (0-based). */
    fun plateLayer(i: Int): Int = g.slabLayers + i
    /** The height above the slab (µm) of the top of plate layer [layer]. */
    fun above(layer: Int): Long = zTop(layer) - slabTop
    /** Layers from the bed for a gauge [thick] (µm) thick, at least one. */
    fun gaugeLayers(thick: Long): Int = maxOf(1, 1 + Math.round((thick - firstLayerUm).toDouble() / layerUm).toInt())

    /** Above-slab layer counts: the piers (before the strand), the pedestals, the pins' tops, the compass, the lag wall. */
    val pierLayers: Int get() = layersFor(g.pierHeight)
    /** The strand's layer above the slab (0-based): the one on the piers' top; the caps after it. */
    val strandLayer: Int get() = pierLayers
    val pedestalLayers: Int get() = layersFor(g.upperFrom)
    val pinBandLayers: Int get() = maxOf(1, layersFor(g.pinBand))
    val pinTopLayers: Int get() = pedestalLayers + pinTimesS.size * pinBandLayers
    val compassLayers: Int get() = layersFor(g.compassHeight)
    val lagLayers: Int get() = layersFor(g.lagHeight)
    /** The plate's layers, the slab's included (the pins' tops are the tallest). */
    val layers: Int get() = g.slabLayers + pinTopLayers
    val topZ: Long get() = zTop(layers - 1)

    /** Pin band [b] (0-based): its above-slab layers. */
    fun pinBandRange(b: Int): IntRange = (pedestalLayers + b * pinBandLayers) until (pedestalLayers + (b + 1) * pinBandLayers)
    /** Pin band [b]'s top above the slab (µm): where its stair post tops out. */
    fun pinBandTop(b: Int): Long = (pedestalLayers + (b + 1) * pinBandLayers) * layerUm

    /** Fin [k]'s band: its first and last above-slab layers, and the fin's top layer (its lever's last). */
    fun finBandRange(k: Int): IntRange { val from = layersFor(g.finBandFrom + k * g.finBandPitch); return from until from + maxOf(1, layersFor(g.finBand)) }
    fun finTopLayer(k: Int): Int = finBandRange(k).last + layersFor(g.lever)
    /** Fin [k]'s top above the slab (µm). */
    fun finTopAbove(k: Int): Long = (finTopLayer(k) + 1).toLong() * layerUm

    /** The compass tabs: their layers above the slab (the last at the post's top) and how far each steps out (µm). */
    val tabLayers: Int get() = maxOf(2, layersFor(Math.round(g.tabLen * Math.cos(Math.toRadians(g.tabLeanDeg)))))
    val tabStep: Long get() = Math.round(layerUm * Math.tan(Math.toRadians(g.tabLeanDeg)))
    val tabFrom: Int get() = compassLayers - tabLayers

    /** The lag wall's lower half: its above-slab layers below this (the fan off, then full); the upper half from here. */
    val lagHalf: Int get() = lagLayers / 2

    /** The plate layers whose time is made up to a target (s): each fin's band and each pin band, the plate's last layer excepted. */
    fun bandTargets(): Map<Int, Pair<String, Double>> {
        val out = LinkedHashMap<Int, Pair<String, Double>>()
        for ((k, t) in finTimesS.withIndex()) for (i in finBandRange(k)) out[plateLayer(i)] = finBandName(k) to t
        for ((b, t) in pinTimesS.withIndex()) for (i in pinBandRange(b)) if (plateLayer(i) < layers - 1) out[plateLayer(i)] = pinBandName(b) to t
        return out
    }
    fun finBandName(k: Int) = "fin ${k + 1}'s band (${PlateMarks.mm(finTimesS[k])} s)"
    fun pinBandName(b: Int) = "pin band ${b + 1} (${PlateMarks.mm(pinTimesS[b])} s)"

    // ================================================================ the beads
    /** Center-to-center distance (µm) of two touching lines of the plate's width (the bead model's). */
    val spacingUm: Long get() = Math.round(widthUm - layerUm * (1 - Math.PI / 4))
    val filamentMm2: Double get() = Math.PI * filamentDiameterMm * filamentDiameterMm / 4
    /** Bead cross-section of a [w] µm line [h] µm high, mm² (the writer's bead model). */
    fun beadMm2(w: Long, h: Long): Double { val hm = h / 1000.0; return hm * (w / 1000.0 - hm * (1 - Math.PI / 4)) }

    // ================================================================ the labels' texts
    fun dutyText(pct: Double) = PlateMarks.whole(pct) + "%"
    fun timeText(s: Double) = PlateMarks.mm(s) + "s"
    fun spanText(um: Long) = PlateMarks.mm(um / 1000.0)
    fun holeText(k: Int) = PlateMarks.mm((pinDiametersUm[k] + g.holeClearance) / 1000.0)
    fun speedText() = PlateMarks.whole(visible.speed) + "mm/s"
    fun directionText(k: Int) = "${k + 1}"

    private fun lw(t: String) = PlateMarks.labelWidth(t)
    private fun lh(t: String) = PlateMarks.labelHeight(t)

    // ================================================================ the layout (µm, the slab's front-left corner at 0, 0)
    /** A box: x0, y0, x1, y1. */
    class Box(val x0: Long, val y0: Long, val x1: Long, val y1: Long) {
        val w get() = x1 - x0; val d get() = y1 - y0
        fun arr() = longArrayOf(x0, y0, x1, y1)
    }

    // 1. strands: the duty labels' column, the rows behind the span labels
    // (each worked out once: a label's size is its glyphs' union)
    val strandDutyColW: Long by lazy { strandFans.maxOf { lw(dutyText(it)) } }
    val spanLabelH: Long by lazy { spansUm.maxOf { lh(spanText(it)) } }
    val strandRowPitch: Long by lazy { maxOf(g.pierY, strandFans.maxOf { lh(dutyText(it)) }) + g.labelGap }
    val strandRowsX0: Long by lazy { g.margin + strandDutyColW + g.labelGap }
    val strandRowsY0: Long by lazy { g.margin + spanLabelH + g.labelGap }
    /** Pair [k]'s left pier's left edge (x). */
    fun pairX(k: Int): Long = strandRowsX0 + (0 until k).sumOf { 2 * g.pierX + spansUm[it] + g.pairGap }
    /** Pair [k]'s piers (left and right) in row [r]. */
    fun pier(r: Int, k: Int, right: Boolean): Box {
        val x = pairX(k) + if (right) g.pierX + spansUm[k] else 0L
        val y = strandRowsY0 + r * strandRowPitch
        return Box(x, y, x + g.pierX, y + g.pierY)
    }
    /** Row [r]'s strand line's y. */
    fun strandY(r: Int): Long = strandRowsY0 + r * strandRowPitch + g.pierY / 2
    val strands: Box by lazy { Box(g.margin, g.margin, pairX(spansUm.size) - g.pairGap, strandRowsY0 + strandFans.size * strandRowPitch - g.labelGap) }

    // 3. fins: below the strands, at the left
    val finDutyColW: Long by lazy { finFans.maxOf { lw(dutyText(it)) } }
    val finPitch: Long by lazy { maxOf(g.finWidth, finTimesS.maxOf { lw(timeText(it)) }) + g.labelGap }
    val finTimeLabelH: Long by lazy { finTimesS.maxOf { lh(timeText(it)) } }
    val finsY0: Long by lazy { strands.y1 + g.sectionGap }
    val finRowsY0: Long by lazy { finsY0 + finTimeLabelH + g.labelGap }
    /** A fin's footprint depth: three lines, the gap to its post, the post. */
    val finDepth: Long get() = 2 * spacingUm + widthUm + g.postBack + g.finPost
    val finRowPitch: Long by lazy { maxOf(finDepth, finFans.maxOf { lh(dutyText(it)) }) + g.labelGap }
    /** Fin [k]'s left end (x) and its middle line's y in row [r]. */
    fun finX(k: Int): Long = g.margin + finDutyColW + g.labelGap + k * finPitch + (finPitch - g.labelGap - g.finWidth) / 2
    fun finY(r: Int): Long = finRowsY0 + r * finRowPitch + spacingUm + widthUm / 2
    /** Fin [k]'s stop post in row [r]: [ThermalGeometry.postBack] behind the fin's back face, at its middle. */
    fun finPostBox(r: Int, k: Int): Box {
        val y = finY(r) + spacingUm + widthUm / 2 + g.postBack
        val cx = finX(k) + g.finWidth / 2
        return Box(cx - g.finPost / 2, y, cx + g.finPost / 2, y + g.finPost)
    }
    val fins: Box by lazy { Box(g.margin, finsY0, g.margin + finDutyColW + g.labelGap + finTimesS.size * finPitch - g.labelGap, finRowsY0 + finFans.size * finRowPitch - g.labelGap) }

    // 4. compass: right of the fins
    val tabTipR: Long get() = g.compassPost / 2 - widthUm / 2 + 2 * spacingUm + (tabLayers - 1) * tabStep
    /** The reference posts' radius: the chord between two neighbors passes over the tips' outer line. */
    val refR: Long get() = Math.round(tabTipR / Math.cos(Math.PI / g.tabs))
    val dirLabelHalf: Long by lazy { (0 until g.tabs).maxOf { maxOf(lw(directionText(it)), lh(directionText(it))) } / 2 }
    val dirLabelR: Long by lazy { refR + g.refPost / 2 + g.labelGap + dirLabelHalf }
    val compassHalf: Long by lazy { dirLabelR + dirLabelHalf }
    val compass: Box by lazy { val x = fins.x1 + g.sectionGap; val y = finsY0; Box(x, y, x + 2 * compassHalf, y + 2 * compassHalf) }
    val compassCx: Long by lazy { compass.x0 + compassHalf }
    val compassCy: Long by lazy { compass.y0 + compassHalf }
    /** Tab [k]'s direction (radians): 1 toward the front, counted clockwise from above. */
    fun tabAngle(k: Int): Double = -Math.PI / 2 - k * 2 * Math.PI / g.tabs
    /** Reference post [k]'s center: between tab [k] and the next clockwise. */
    fun refCenter(k: Int): Pair<Long, Long> { val a = tabAngle(k) - Math.PI / g.tabs; return (compassCx + Math.round(refR * Math.cos(a))) to (compassCy + Math.round(refR * Math.sin(a))) }

    val middleY1: Long by lazy { maxOf(fins.y1, compass.y1) }

    // 5. lag wall and 2. pins: the back row
    val backY0: Long by lazy { middleY1 + g.sectionGap }
    val pinsX0: Long get() = g.margin + g.lagLength + g.sectionGap
    fun pinCenter(k: Int): Pair<Long, Long> = (pinsX0 + g.pedestal / 2 + k * (g.pedestal + g.pinGap)) to (backY0 + g.pedestal / 2)
    val stairLabelColW: Long by lazy { pinTimesS.maxOf { lw(timeText(it)) } }
    val stairPitch: Long by lazy { maxOf(g.stairPost, pinTimesS.maxOf { lh(timeText(it)) }) + g.labelGap }
    val stairX0: Long get() = pinsX0 + pinDiametersUm.size * (g.pedestal + g.pinGap) - g.pinGap + g.sectionGap
    /** Pin band [b]'s step of the stair (band 1 at the front): the steps touch, one block. */
    fun stairBox(b: Int): Box {
        val x = stairX0 + stairLabelColW + g.labelGap
        val y = backY0 + b * stairPitch
        return Box(x, y, x + g.stairPost, y + stairPitch)
    }
    val pins: Box by lazy { Box(pinsX0, backY0, stairBox(0).x1, backY0 + maxOf(g.pedestal, pinTimesS.size * stairPitch)) }
    val lagLabelH: Long by lazy { lh(speedText()) }
    val lagDepth: Long by lazy { lagLabelH + g.labelGap + widthUm + spacingUm + g.lagEdgeGap + PlateMarks.TALLY_LONG_IN }
    val backY1: Long by lazy { backY0 + maxOf(lagDepth, pins.d) }

    /** The slab's size. */
    val slabW: Long by lazy { maxOf(strands.x1, compass.x1, pins.x1) + g.margin }
    val slabD: Long by lazy { backY1 + g.margin }
    /** The lag wall: its left end (x), its outer (back) and inner lines' y. */
    val lagX0: Long get() = g.margin
    val lagOuterY: Long get() = slabD - PlateMarks.TALLY_LONG_IN - g.lagEdgeGap - widthUm / 2
    val lagInnerY: Long get() = lagOuterY - spacingUm
    /** The lag notches along the slab's back edge: each one's x (the first at the switch). */
    fun lagNotchX(k: Int): Long = lagX0 + g.lagSwitch + k * g.lagPitch
    val lagNotches: Int get() = ((g.lagLength - g.lagSwitch) / g.lagPitch).toInt() + 1

    // the gauges, in front of the slab
    val feelerSteps: List<Long> get() = g.FEELER_SAGS.map { g.pierHeight - it }
    /** The feeler's steps' actual thicknesses (µm, whole layers) and the sags they allow. */
    fun feelerThick(k: Int): Long = zTop(feelerLayers(k) - 1)
    /** The feeler's step [k]'s layers from the bed: whole layers at or under its thickness, so it allows at least its sag. */
    fun feelerLayers(k: Int): Int = maxOf(1, 1 + ((feelerSteps[k] - firstLayerUm) / layerUm).toInt())
    fun feelerSag(k: Int): Long = pierLayers * layerUm - feelerThick(k)
    val feeler: Box get() { val x = PlateMarks.CHAMFER + g.margin; return Box(x, -g.gaugeGap - g.feelerWidth, x + g.FEELER_SAGS.size * g.feelerStep, -g.gaugeGap) }
    val cardHolePitch: Long by lazy { maxOf(pinDiametersUm.last() + g.holeClearance + 2 * g.cardEdge, pinDiametersUm.indices.maxOf { lw(holeText(it)) } + g.cardEdge) }
    val cardLabelH: Long by lazy { pinDiametersUm.indices.maxOf { lh(holeText(it)) } }
    val card: Box by lazy {
        val x = feeler.x1 + g.sectionGap
        val d = g.cardEdge + cardLabelH + g.labelGap + pinDiametersUm.last() + g.holeClearance + g.cardEdge
        Box(x, -g.gaugeGap - d, x + g.cardEdge + pinDiametersUm.size * cardHolePitch, -g.gaugeGap)
    }
    /** Hole [k]'s center on the card. */
    fun holeCenter(k: Int): Pair<Long, Long> { val c = card; return (c.x0 + g.cardEdge + k * cardHolePitch + cardHolePitch / 2 - g.cardEdge / 2) to (c.y1 - g.cardEdge - (pinDiametersUm.last() + g.holeClearance) / 2) }
    val cardLayers: Int get() = gaugeLayers(g.cardThickness)
    /** The break-off tabs: two per gauge, from its back edge across the gap to the slab's front edge. */
    fun breakOffs(): List<Pair<String, Box>> = listOf("feeler" to feeler, "card" to card).flatMap { (n, b) ->
        listOf(1, 3).mapIndexed { j, q -> val cx = b.x0 + b.w * q / 4; "break-off $n ${j + 1}" to Box(cx - g.breakW / 2, b.y1 - widthUm, cx + g.breakW / 2, widthUm) }
    }

    /** The plate's extent (µm, unplaced): the slab and the gauges. */
    val bounds: Box by lazy { Box(0, minOf(feeler.y0, card.y0), maxOf(slabW, card.x1, feeler.x1), slabD) }

    /** Plate coordinates (µm) to the bed: the plate's front-left corner at the origin. */
    val dx: Long by lazy { originXUm - bounds.x0 }
    val dy: Long by lazy { originYUm - bounds.y0 }
}

/**
 * Builds the thermal plate's program from its [spec]. [dwellS]: the wait added to each banded layer (by plate layer), worked
 * out by [gcode] from the program written with none; absent = no wait (the head still parks there). [without]: for the
 * read-back check's controls only, which must catch them: "waits" (the banded layers' waits left out), "strandfan" (the second
 * strand row at the first row's duty), "strandz" (the strands laid a layer above the piers' top), "strandshort" (each strand
 * stopping 2 mm short of its far pier), "finband" (the first fin's band a layer of the lever higher, the waits where specified),
 * "finfan" (every fin row at the first row's duty), "tabs" (the compass's eighth tab left out), "lagswitch" (the lag wall's
 * switch 5 mm late in its upper half), "lagdir" (the lag wall's outer line laid right to left), "gaugetab" (the card's break-off
 * tabs left out), "offslab" (the first fin row's stop posts mirrored to in front of the slab, off it), "settle" (no fan settle
 * waits).
 */
class ThermalPlate(private val spec: ThermalSpec, private val without: Set<String> = emptySet()) {
    private val g = ThermalGeometry
    private val ox get() = spec.dx
    private val oy get() = spec.dy

    // ================================================================ outlines (µm, unplaced)
    private fun box(b: ThermalSpec.Box) = PlateMarks.box(b.x0, b.y0, b.x1, b.y1)
    /** A circle of [sides] vertices, counter-clockwise, its first vertex at angle 0. */
    fun circle(cx: Long, cy: Long, r: Long, sides: Int = g.roundSides): Polygon =
        Polygon(LongArray(2 * sides) { i -> val a = 2 * Math.PI * (i / 2) / sides; if (i % 2 == 0) cx + Math.round(r * Math.cos(a)) else cy + Math.round(r * Math.sin(a)) })

    /** The slab's outline on [layer]: the base, its front-left corner chamfered, the lag notches in its back edge above the first layer. */
    fun slabOutline(layer: Int): List<Polygon> = if (layer == 0) listOf(PlateMarks.base(0, 0, spec.slabW, spec.slabD)) else notchedSlab
    private val notchedSlab: List<Polygon> by lazy {
        val b = longArrayOf(0, 0, spec.slabW, spec.slabD)
        val notches = (0 until spec.lagNotches).map { k -> PlateMarks.notch(b, PlateMarks.Edge.BACK, spec.lagNotchX(k), PlateMarks.depth(k)) }
        Booleans.difference(listOf(PlateMarks.base(0, 0, spec.slabW, spec.slabD)), notches)
    }

    /** The feeler's outline on gauge layer [layer]: the steps still rising there, each step's tally (k + 1 notches) in its front edge above the first layer. */
    fun feelerOutline(layer: Int): List<Polygon> {
        val f = spec.feeler
        val steps = g.FEELER_SAGS.indices.filter { spec.zTop(layer) <= spec.feelerThick(it) }
        if (steps.isEmpty()) return emptyList()
        // the steps thinnest first, left to right: what still rises is the right end
        val x0 = f.x0 + steps.first() * g.feelerStep
        val body = PlateMarks.box(x0, f.y0, f.x1, f.y1)
        if (layer == 0) return listOf(body)
        val b = f.arr()
        val notches = steps.flatMap { k ->
            val n = g.FEELER_SAGS.size - k
            val mid = k * g.feelerStep + g.feelerStep / 2 - PlateMarks.countLength(n) / 2 + PlateMarks.TALLY_W / 2
            PlateMarks.count(b, PlateMarks.Edge.FRONT, mid, n)
        }
        return Booleans.difference(listOf(body), notches)
    }

    /** The ring card's outline: the card less its holes. */
    fun cardOutline(): List<Polygon> = cardOnce
    private val cardOnce: List<Polygon> by lazy {
        val holes = spec.pinDiametersUm.indices.map { k -> val (cx, cy) = spec.holeCenter(k); circle(cx, cy, (spec.pinDiametersUm[k] + g.holeClearance) / 2, 72) }
        Booleans.difference(listOf(box(spec.card)), holes)
    }

    // ================================================================ labels (µm, unplaced)
    /** A label centered at ([cx], [cy]). */
    private fun labelAt(text: String, cx: Long, cy: Long): List<Polygon> = PlateMarks.label(text, cx - PlateMarks.labelWidth(text) / 2, cy - PlateMarks.labelHeight(text) / 2)
    /** A label left-aligned at [x0], vertically centered on [cy]. */
    private fun labelLeft(text: String, x0: Long, cy: Long): List<Polygon> = PlateMarks.label(text, x0, cy - PlateMarks.labelHeight(text) / 2)

    /** Every label on the slab: its cell name and its glyphs. */
    fun slabLabels(): List<Pair<String, List<Polygon>>> = slabLabelsOnce
    private val slabLabelsOnce: List<Pair<String, List<Polygon>>> by lazy {
        val out = ArrayList<Pair<String, List<Polygon>>>()
        for ((r, d) in spec.strandFans.withIndex()) out += "label strand row ${r + 1}" to labelLeft(spec.dutyText(d), g.margin, spec.strandY(r))
        for (k in spec.spansUm.indices) {
            val cx = spec.pairX(k) + g.pierX + spec.spansUm[k] / 2
            out += "label span ${k + 1}" to labelAt(spec.spanText(spec.spansUm[k]), cx, g.margin + spec.spanLabelH / 2)
        }
        for ((k, t) in spec.finTimesS.withIndex()) out += "label fin ${k + 1}" to labelAt(spec.timeText(t), spec.finX(k) + g.finWidth / 2, spec.finsY0 + spec.finTimeLabelH / 2)
        for ((r, d) in spec.finFans.withIndex()) out += "label fin row ${r + 1}" to labelLeft(spec.dutyText(d), g.margin, spec.finY(r))
        for (k in 0 until g.tabs) {
            val a = spec.tabAngle(k)
            out += "label direction ${k + 1}" to labelAt(spec.directionText(k), spec.compassCx + Math.round(spec.dirLabelR * Math.cos(a)), spec.compassCy + Math.round(spec.dirLabelR * Math.sin(a)))
        }
        for ((b, t) in spec.pinTimesS.withIndex()) { val p = spec.stairBox(b); out += "label band ${b + 1}" to labelLeft(spec.timeText(t), spec.stairX0, (p.y0 + p.y1) / 2) }
        out += "label lag speed" to labelAt(spec.speedText(), spec.lagX0 + g.lagLength / 2, spec.lagInnerY - spec.widthUm / 2 - g.labelGap - spec.lagLabelH / 2)
        out
    }

    /** The ring card's labels: each hole's diameter in front of it. */
    fun cardLabels(): List<Pair<String, List<Polygon>>> = cardLabelsOnce
    private val cardLabelsOnce: List<Pair<String, List<Polygon>>> by lazy { spec.pinDiametersUm.indices.map { k ->
        val (cx, _) = spec.holeCenter(k)
        "card label ${k + 1}" to labelAt(spec.holeText(k), cx, spec.card.y0 + g.cardEdge + spec.cardLabelH / 2)
    } }

    // ================================================================ the paths of one layer
    /** A path: its cell, its feature, its points (µm, unplaced), closed or not, its values, its width. */
    class Path(val cell: String, val feature: String, val xy: LongArray, val closed: Boolean, val pv: PathValues, val widthUm: Long)

    /** The fills worked out so far, by outline, width, height and angle (most parts repeat every other layer). */
    private val fills = HashMap<String, Pair<List<LongArray>, List<LongArray>>>()

    /** [outlines] filled ([PlateFill]): its rings, then its lines; [hollow]: the rings only (a light part, hollow inside). */
    private fun fillPaths(cell: String, outlines: List<Polygon>, pv: PathValues, w: Long, h: Long, angle: Double, first: Boolean = false, hollow: Boolean = false): List<Path> {
        if (outlines.isEmpty()) return emptyList()
        val key = "$w/$h/$angle/" + outlines.joinToString(",") { it.xy.contentHashCode().toString() + ":" + it.xy.size }
        val (rings, lines) = fills.getOrPut(key) { PlateFill.fill(outlines, w, h, angle) }
        return rings.mapIndexed { i, r -> Path(cell, if (i == 0) "Outer wall" else "Inner wall", r, true, pv, w) } +
            (if (hollow) emptyList() else lines.map { Path(cell, if (first) "Bottom surface" else "Internal solid infill", it, false, pv, w) })
    }

    /** The structural paths of plate layer [layer] at the base fan: the slab, the gauges, the labels, piers, posts, pedestals, the pins, the compass. */
    fun basePaths(layer: Int): List<Path> {
        val out = ArrayList<Path>()
        val first = layer == 0
        val h = spec.heightOf(layer)
        val pv = if (first) spec.adhesion else spec.frame
        val w = if (first) spec.firstWidthUm else spec.widthUm
        val angle = if (layer % 2 == 0) 45.0 else 135.0
        // the slab, then the gauges and their break-off tabs
        if (layer < g.slabLayers) out += fillPaths(if (first) "adhesion:base thermal" else "base thermal", slabOutline(layer), pv, w, h, angle, first)
        // the feeler hollow between its two bottom layers and its steps' solid tops ([capLayers] + 1 layers under the thinnest's top)
        val feelerOut = feelerOutline(layer)
        if (feelerOut.isNotEmpty()) out += fillPaths(if (first) "adhesion:feeler" else "feeler", feelerOut, pv, w, h, angle, first,
            hollow = layer >= 2 && layer < spec.feelerLayers(0) - g.capLayers - 1)
        if (layer < spec.cardLayers) out += fillPaths(if (first) "adhesion:card" else "card", cardOutline(), pv, w, h, angle, first)
        if (layer in spec.cardLayers until spec.cardLayers + g.labelLayers)
            for ((cell, glyphs) in cardLabels()) out += fillPaths(cell, glyphs, spec.frame, spec.widthUm, h, if (layer % 2 == 0) 0.0 else 90.0)
        if (layer < g.breakLayers) for ((cell, b) in spec.breakOffs()) {
            if ("gaugetab" in without && cell.startsWith("break-off card")) continue
            out += breakOff(if (first) "adhesion:$cell" else cell, b, pv, w)
        }
        if (layer < g.slabLayers) return out
        val i = layer - g.slabLayers
        // the labels on the slab
        if (i < g.labelLayers) for ((cell, glyphs) in slabLabels()) out += fillPaths(cell, glyphs, spec.frame, spec.widthUm, h, if (i % 2 == 0) 0.0 else 90.0)
        // 1. the piers: solid up to their top; on the strand's layer each pier's lines along the strand but its middle one
        // (the strand lays that); then the caps
        // (the supporting parts hollow, two walls, where nothing rests on them: they only stand, and a layer's time is short only
        // where little prints)
        if (i < spec.strandLayer + g.capLayers + 1) for (r in spec.strandFans.indices) for (k in spec.spansUm.indices) for (right in listOf(false, true)) {
            val p = spec.pier(r, k, right)
            val cell = "pier ${r + 1}.${k + 1} ${if (right) "right" else "left"}"
            if (i == spec.strandLayer) out += anchorLines(cell, p, r) else out += fillPaths(cell, listOf(box(p)), spec.frame, spec.widthUm, h, angle, hollow = i < spec.strandLayer)
        }
        // 3. the fins' stop posts, each as tall as its fin
        for (r in spec.finFans.indices) for (k in spec.finTimesS.indices) if (i <= spec.finTopLayer(k)) {
            val p = spec.finPostBox(r, k).let { if ("offslab" in without && r == 0) ThermalSpec.Box(it.x0, -it.y1, it.x1, -it.y0) else it }
            out += fillPaths("fin post ${r + 1}.${k + 1}", listOf(box(p)), spec.frame, spec.widthUm, h, angle, hollow = true)
        }
        // 4. the compass: its post, the reference posts, the tabs
        if (i < spec.compassLayers) {
            out += fillPaths("compass post", listOf(circle(spec.compassCx, spec.compassCy, g.compassPost / 2)), spec.frame, spec.widthUm, h, angle, hollow = true)
            for (k in 0 until g.tabs) {
                val (rx, ry) = spec.refCenter(k)
                out += fillPaths("compass reference ${k + 1}", listOf(circle(rx, ry, g.refPost / 2, 24)), spec.frame, spec.widthUm, h, angle, hollow = true)
            }
            if (i >= spec.tabFrom) for (k in 0 until g.tabs) {
                if ("tabs" in without && k == g.tabs - 1) continue
                out += Path("compass tab ${k + 1}", "Overhang wall", tabLines(k, i - spec.tabFrom), false, spec.visible, spec.widthUm)
            }
        }
        // 2. the pedestals, then the pins (the outer loop at the visible speed, the core inside at the frame's), and the band stair
        for ((k, d) in spec.pinDiametersUm.withIndex()) {
            val (cx, cy) = spec.pinCenter(k)
            if (i < spec.pedestalLayers) out += fillPaths("pedestal ${k + 1}", listOf(circle(cx, cy, g.pedestal / 2, 72)), spec.frame, spec.widthUm, h, angle, hollow = i < spec.pedestalLayers - g.capLayers - 1)
            else if (i < spec.pinTopLayers) fillPaths("pin ${k + 1} core", listOf(circle(cx, cy, d / 2)), spec.frame, spec.widthUm, h, angle).forEachIndexed { j, p ->
                out += if (j == 0) Path("pin ${k + 1}", p.feature, p.xy, p.closed, spec.visible, p.widthUm) else p
            }
        }
        // the band stair: one block, its steps front to back, each step's top at its band's top (the steps still rising, as one)
        val rising = spec.pinTimesS.indices.filter { i < spec.layersFor(spec.pinBandTop(it)) }
        if (rising.isNotEmpty()) { val a = spec.stairBox(rising.first()); val b = spec.stairBox(rising.last())
            out += fillPaths("stair", listOf(PlateMarks.box(a.x0, a.y0, b.x1, b.y1)), spec.frame, spec.widthUm, h, angle, hollow = true).take(1) }
        return out
    }

    /** A break-off tab: three lines along y across the gap, as one path. */
    private fun breakOff(cell: String, b: ThermalSpec.Box, pv: PathValues, w: Long): Path {
        val sp = spec.spacingUm
        val cx = (b.x0 + b.x1) / 2
        val xs = listOf(cx - sp, cx, cx + sp)
        val pts = ArrayList<Long>()
        for ((j, x) in xs.withIndex()) { if (j % 2 == 0) { pts += x; pts += b.y0; pts += x; pts += b.y1 } else { pts += x; pts += b.y1; pts += x; pts += b.y0 } }
        return Path(cell, "Outer wall", pts.toLongArray(), false, pv, w)
    }

    /** A pier's lines on the strand's layer: along the strand, one spacing apart, all but the middle one (the strand's). */
    private fun anchorLines(cell: String, p: ThermalSpec.Box, r: Int): List<Path> {
        val sp = spec.spacingUm; val w = spec.widthUm
        val ys = ArrayList<Long>()
        val mid = spec.strandY(r)
        var y = mid - sp; while (y - w / 2 >= p.y0 - 1) { ys += y; y -= sp }
        y = mid + sp; while (y + w / 2 <= p.y1 + 1) { ys += y; y += sp }
        ys.sort()
        val pts = ArrayList<Long>()
        for ((j, yy) in ys.withIndex()) { val a = p.x0 + w / 2; val b = p.x1 - w / 2; if (j % 2 == 0) { pts += a; pts += yy; pts += b; pts += yy } else { pts += b; pts += yy; pts += a; pts += yy } }
        return if (pts.isEmpty()) emptyList() else listOf(Path(cell, "Internal solid infill", pts.toLongArray(), false, spec.frame, w))
    }

    /**
     * Compass tab [k]'s two lines on its tab layer [j] (0 = against the post): across the tab's direction, the inner line one
     * spacing out from the post's outer line and stepping out [ThermalSpec.tabStep] a layer, the outer line a spacing beyond it;
     * each held to its 45 degree sector near the post (the tabs meet there). One path: the inner line, then back along the outer.
     */
    fun tabLines(k: Int, j: Int): LongArray {
        val a = spec.tabAngle(k); val ux = Math.cos(a); val uy = Math.sin(a); val tx = -uy; val ty = ux
        val sp = spec.spacingUm; val w = spec.widthUm
        val c1 = g.compassPost / 2 - w / 2 + sp + j * spec.tabStep
        val pts = ArrayList<Long>()
        for ((n, c) in listOf(c1, c1 + sp).withIndex()) {
            val half = minOf(g.tabWidth / 2 - w / 2, Math.round(c * Math.tan(Math.PI / g.tabs) - w / 2.0))
            val ends = if (n == 0) listOf(-half, half) else listOf(half, -half)
            for (t in ends) { pts += spec.compassCx + Math.round(c * ux + t * tx); pts += spec.compassCy + Math.round(c * uy + t * ty) }
        }
        return pts.toLongArray()
    }

    /**
     * Fin [k]'s line(s) on above-slab layer [i] in row [r]: three lines below its band (a back-and-forth path), one in the band
     * (shortened at both ends by the V notch: deepest at the band's middle), one above it; none past its top.
     */
    fun finPath(r: Int, k: Int, i: Int): LongArray? {
        if (i > spec.finTopLayer(k)) return null
        val band = bandOf(k)
        val x0 = spec.finX(k) + spec.widthUm / 2; val x1 = spec.finX(k) + g.finWidth - spec.widthUm / 2
        val y = spec.finY(r); val sp = spec.spacingUm
        val rev = i % 2 == 1
        if (i < band.first) {
            val ys = listOf(y - sp, y, y + sp)
            val pts = ArrayList<Long>()
            for ((n, yy) in ys.withIndex()) { val fwd = (n % 2 == 0) != rev; if (fwd) { pts += x0; pts += yy; pts += x1; pts += yy } else { pts += x1; pts += yy; pts += x0; pts += yy } }
            return pts.toLongArray()
        }
        val cut = if (i in band) {
            // the V: 0 at the band's edges, [notchDepth] at its middle (the layer's middle height against the band's)
            val n = band.last - band.first + 1
            val f = 1 - Math.abs((i - band.first + 0.5) / n * 2 - 1)
            Math.round(g.notchDepth * f)
        } else 0L
        return if (rev) longArrayOf(x1 - cut, y, x0 + cut, y) else longArrayOf(x0 + cut, y, x1 - cut, y)
    }

    /** Fin [k]'s band (above-slab layers), a control applied ("finband": a layer higher). */
    fun bandOf(k: Int): IntRange = spec.finBandRange(k).let { if ("finband" in without && k == 0) (it.first + 1)..(it.last + 1) else it }

    /** Row [r]'s duty for the fins and the strands, the controls applied. */
    fun finDuty(r: Int): Double = if ("finfan" in without) spec.finFans[0] else spec.finFans[r]
    fun strandDuty(r: Int): Double = if ("strandfan" in without && r == 1) spec.strandFans[0] else spec.strandFans[r]

    /** The strand of row [r] over gap [k]: from the left pier's left edge to the right pier's right edge, half a line in. */
    fun strand(r: Int, k: Int): LongArray {
        val a = spec.pier(r, k, false); val b = spec.pier(r, k, true)
        val y = spec.strandY(r)
        val end = b.x1 - spec.widthUm / 2 - if ("strandshort" in without) spec.spansUm[k] / 2 + g.pierX else 0L
        return longArrayOf(a.x0 + spec.widthUm / 2, y, end, y)
    }

    /** The lag wall's lines on above-slab layer [i]: the inner right to left, the outer left to right split at the switch. */
    fun lagSwitchX(i: Int): Long = spec.lagX0 + g.lagSwitch + if ("lagswitch" in without && i >= spec.lagHalf) 5_000L else 0L

    // ================================================================ the program
    /**
     * Where the head parks for a wait (bed, µm): [PlateGeometry.parkFront] in front of the plate at its middle (the strands' and
     * the fins' settles, the temperature's wait), or as far behind the slab, behind the pins (the lag wall's settle, the bands'
     * waits): the nearer side, clear of the plate.
     */
    fun parkFrontUm(): Pair<Long, Long> = (spec.originXUm + spec.bounds.w / 2) to (spec.originYUm - PlateGeometry.parkFront)
    fun parkBackUm(): Pair<Long, Long> = ((spec.pins.x0 + spec.pins.x1) / 2 + ox) to (spec.slabD + oy + PlateGeometry.parkFront)

    fun program(dwellS: Map<Int, Double> = emptyMap()): PrintProgram {
        val steps = ArrayList<Step>()
        val (px, py) = parkFrontUm()
        val (bx, by) = parkBackUm()
        // the duty commanded, and whether the head has waited since it was (a reading that depends on the duty waits for it once)
        var fan = 0.0; var settled = true
        fun setFan(pct: Double, settle: Boolean, back: Boolean = false) {
            if (pct != fan) { steps += SetFan(pct); fan = pct; settled = false }
            if (settle && !settled && spec.settleS > 0 && "settle" !in without) { steps += if (back) Park(bx, by, spec.settleS) else Park(px, py, spec.settleS); settled = true }
        }
        fun emit(p: Path, layer: Int) {
            val h = spec.heightOf(layer); val z = spec.zTop(layer)
            val xy = LongArray(p.xy.size) { i -> p.xy[i] + if (i % 2 == 0) ox else oy }
            steps += Extrude(p.feature, xy, p.closed, z, p.widthUm, h, tool = spec.tool, exact = Exact(p.cell, doubleArrayOf(p.pv.speed), p.pv.accel, p.pv.flowRatio, p.pv.retractMm))
        }
        spec.levelGcode?.let { steps += plymeld.program.Custom("bed levelling (hardware.machine.<key>.gcode.level)", it) }
        steps += SetTemp(spec.tool, spec.firstTempC, wait = true)
        steps += SetFan(0.0)
        val targets = spec.bandTargets()
        for (layer in 0 until spec.layers) {
            steps += LayerStart(layer, spec.zTop(layer), spec.heightOf(layer))
            // the base fan from the second layer; the nozzle to the plate's temperature, waited for before anything above the
            // slab's first layer is read
            val base = if (layer == 0) 0.0 else spec.fanPct
            if (layer == 1 && spec.firstTempC != spec.tempC) steps += SetTemp(spec.tool, spec.tempC, wait = false)
            if (layer == g.slabLayers && spec.firstTempC != spec.tempC) { steps += LiftTo(spec.zTop(layer - 1) + spec.liftUm); steps += Park(px, py, 0.0); steps += SetTemp(spec.tool, spec.tempC, wait = true) }
            setFan(base, false)
            for (p in basePaths(layer)) emit(p, layer)
            if (layer < g.slabLayers) continue
            val i = layer - g.slabLayers
            val h = spec.layerUm; val z = spec.zTop(layer)
            // 3. the fins, row by row, each row at its duty, set as the row starts (no settle: a wait for each row would make the
            // fins' bands' layers longer than their times; the rows stand apart on the slab)
            for (r in spec.finFans.indices) {
                val ps = spec.finTimesS.indices.mapNotNull { k -> finPath(r, k, i)?.let { k to it } }
                if (ps.isEmpty()) continue
                setFan(finDuty(r), false)
                for ((k, xy) in ps) emit(Path("fin ${r + 1}.${k + 1}", "Outer wall", xy, false, spec.visible, spec.widthUm), layer)
            }
            // 5. the lag wall: its starting duty settled, the inner line right to left, the outer left to right with the fan
            // switched where it passes the mark
            if (i < spec.lagLayers) {
                val lower = i < spec.lagHalf
                val (from, to) = if (lower) 0.0 to g.FULL else g.FULL to 0.0
                setFan(from, true, back = true)
                val xa = spec.lagX0 + spec.widthUm / 2; val xb = spec.lagX0 + g.lagLength - spec.widthUm / 2
                emit(Path("lag inner", "Inner wall", longArrayOf(xb, spec.lagInnerY, xa, spec.lagInnerY), false, spec.frame, spec.widthUm), layer)
                val sx = lagSwitchX(i)
                if ("lagdir" in without) {
                    emit(Path("lag outer", "Outer wall", longArrayOf(xb, spec.lagOuterY, sx, spec.lagOuterY), false, spec.visible, spec.widthUm), layer)
                    setFan(to, false)
                    emit(Path("lag outer", "Outer wall", longArrayOf(sx, spec.lagOuterY, xa, spec.lagOuterY), false, spec.visible, spec.widthUm), layer)
                } else {
                    emit(Path("lag outer", "Outer wall", longArrayOf(xa, spec.lagOuterY, sx, spec.lagOuterY), false, spec.visible, spec.widthUm), layer)
                    setFan(to, false)
                    emit(Path("lag outer", "Outer wall", longArrayOf(sx, spec.lagOuterY, xb, spec.lagOuterY), false, spec.visible, spec.widthUm), layer)
                }
            }
            // 1. the strands, row by row, each row's duty set and settled, parked, first
            val strandAt = spec.strandLayer + if ("strandz" in without) 1 else 0
            if (i == strandAt) for (r in spec.strandFans.indices) {
                setFan(strandDuty(r), true)
                for (k in spec.spansUm.indices) {
                    val xy = strand(r, k).let { a -> LongArray(a.size) { j -> a[j] + if (j % 2 == 0) ox else oy } }
                    steps += Extrude("Bridge", xy, false, z, spec.widthUm, h, tool = spec.tool, exact = Exact("strand ${r + 1}.${k + 1}", doubleArrayOf(spec.bridge.speed), spec.bridge.accel, spec.bridge.flowRatio, spec.bridge.retractMm))
                }
            }
            // back to the base fan, then a banded layer's wait, parked
            setFan(spec.fanPct, false)
            targets[layer]?.let { if ("waits" !in without) { val d = dwellS[layer] ?: 0.0; steps += Park(bx, by, d); if (d >= spec.settleS) settled = true } }
        }
        steps += LiftTo(spec.topZ + spec.liftUm)
        val b = spec.bounds
        val x0 = (b.x0 + ox) / 1000.0; val y0 = (b.y0 + oy) / 1000.0; val x1 = (b.x1 + ox) / 1000.0; val y1 = (b.y1 + oy) / 1000.0
        return PrintProgram(steps, listOf(PrintObject("thermal_plate", doubleArrayOf(x0, y0, x1, y0, x1, y1, x0, y1), (x0 + x1) / 2, (y0 + y1) / 2)), exact = true)
    }

    /**
     * The plate's g-code: the machine's start and end, everything between exactly as specified, each banded layer's time made up
     * to its target. The program is written once with no waits and its layers' times predicted (plymeld's own prediction, the
     * machine's motion limits); each banded layer's wait is its target less that time. A target shorter than its layer's time is
     * refused, naming the band and both times. [pressureAdvance]: by tool.
     */
    fun gcode(s: plymeld.model.SliceSettings, foreignVars: Map<String, List<String>>, version: String, pressureAdvance: Map<Int, Double> = emptyMap()): String {
        fun write(p: PrintProgram) = java.io.StringWriter().also { plymeld.gcode.KlipperWriter(s, foreignVars, version, pressureAdvance = pressureAdvance).write(p, it) }.toString()
        val bare = write(program())
        val pred = plymeld.core.Predict.gcode(bare, s.machineDescription, null, plymeld.core.Predict.limits(s))
        require(pred.layerS.size >= spec.layers) { "the thermal plate's layer times cannot be predicted for this machine (${pred.why}): its bands' waits are worked out from them" }
        val dwell = LinkedHashMap<Int, Double>()
        val short = ArrayList<String>()
        for ((layer, t) in spec.bandTargets()) {
            val natural = pred.layerS[layer]
            if (natural > t.second + 1e-9) short += "${t.first}: layer ${layer + 1} takes ${fmt(natural)} s without a wait, longer than its ${fmt(t.second)} s"
            else dwell[layer] = t.second - natural
        }
        require(short.isEmpty()) {
            "the thermal plate cannot make these layers as short as their band's time:\n  " + short.distinctBy { it.substringBefore(':') }.joinToString("\n  ") +
                "\n(longer layer times: --pin-times, --fin-times; a shorter fan settle: --fan-settle)"
        }
        return write(program(dwell))
    }

    private fun fmt(v: Double) = String.format(java.util.Locale.ROOT, "%.1f", v)
}
