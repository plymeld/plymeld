package plymeld.core.calibrate

import plymeld.geom.Polygon

/**
 * The marks every calibration plate reads by, one scheme for all of them, so a plate is read the same way whichever it is:
 *
 * - **The origin**: the base's front-left corner cut off ([CHAMFER]). Every count starts from it: "from the front", "from
 *   the left" mean from the chamfered corner's edges.
 * - **A label is the value it names**, printed as raised glyphs ([StrokeFont], [LABEL_H] tall in [LABEL_STROKE] strokes) beside
 *   what it names: a temperature "215", a speed "189", a thickness "0.18". No code to look up in a legend. A label stands on
 *   the base, as tall as the parts beside it, so it adds no layers.
 * - **A tally where a label does not fit**: notches cut into an edge, [TALLY_W] wide, [TALLY_IN] deep, every [TALLY_LONG_EVERY]th
 *   [TALLY_LONG_IN] deep, so a count past four is read in fives. Two uses: [between] marks the boundaries of a row of items
 *   (count the notches from the origin to an item for its number), [count] states a number at one place (n notches together).
 *
 * Lengths in µm. The sizes are plymeld's choices (the seam plate's ticks and the ironing plate's legend, which printed and read
 * on a 0.4 mm nozzle), not measured per nozzle.
 */
object PlateMarks {
    /** The origin's chamfer: this far along each edge from the base's front-left corner. */
    const val CHAMFER = 2_000L
    /** A label's glyphs: this tall, in strokes this wide (two lines of a 0.4 mm nozzle: [StrokeFont]'s legible minimum). */
    const val LABEL_H = 5_000L
    const val LABEL_STROKE = 800L
    /** A tally notch: this wide along its edge, cut this far in; every [TALLY_LONG_EVERY]th one [TALLY_LONG_IN] in. */
    const val TALLY_W = 1_000L
    const val TALLY_IN = 1_500L
    const val TALLY_LONG_IN = 3_000L
    const val TALLY_LONG_EVERY = 5
    /** Notches of one [count] stand this far apart (center to center); a notch reaches this far past its edge so the cut is clean. */
    const val TALLY_PITCH = 2_000L
    private const val PAST = 200L

    /** Which edge of a box a tally is cut into; the notches run from the end nearest the origin (left, or front). */
    enum class Edge { FRONT, BACK, LEFT, RIGHT }

    /** A box (µm: x0, y0, x1, y1) as a counter-clockwise rectangle, its front-left corner cut by [chamfer] (0 = square). */
    fun box(x0: Long, y0: Long, x1: Long, y1: Long, chamfer: Long = 0L): Polygon =
        if (chamfer > 0) Polygon(longArrayOf(x0 + chamfer, y0, x1, y0, x1, y1, x0, y1, x0, y0 + chamfer))
        else Polygon(longArrayOf(x0, y0, x1, y0, x1, y1, x0, y1))

    /** A base (µm), its front-left corner the origin: [box] with [CHAMFER]. */
    fun base(x0: Long, y0: Long, x1: Long, y1: Long): Polygon = box(x0, y0, x1, y1, CHAMFER)

    /** How deep the [k]th notch (from 0) of a tally is: every [TALLY_LONG_EVERY]th long. */
    fun depth(k: Int): Long = if ((k + 1) % TALLY_LONG_EVERY == 0) TALLY_LONG_IN else TALLY_IN

    /**
     * One notch cut into [edge] of box [b] (x0, y0, x1, y1) at [at] (µm along the edge from its end nearest the origin to the
     * notch's middle), [deep] in: a rectangle reaching a little past the edge, to subtract from the box's outline.
     */
    fun notch(b: LongArray, edge: Edge, at: Long, deep: Long): Polygon {
        val h = TALLY_W / 2
        return when (edge) {
            Edge.FRONT -> box(b[0] + at - h, b[1] - PAST, b[0] + at + h, b[1] + deep)
            Edge.BACK -> box(b[0] + at - h, b[3] - deep, b[0] + at + h, b[3] + PAST)
            Edge.LEFT -> box(b[0] - PAST, b[1] + at - h, b[0] + deep, b[1] + at + h)
            Edge.RIGHT -> box(b[2] - deep, b[1] + at - h, b[2] + PAST, b[1] + at + h)
        }
    }

    /**
     * The tally marking a row of items along [edge] of box [b]: a notch at each boundary between neighbors, the items' far
     * edges at [bounds] (µm along the edge from its origin end; the last item's has none). Count the notches from the
     * origin to an item: item n has n - 1 before it. Every [TALLY_LONG_EVERY]th notch long.
     */
    fun between(b: LongArray, edge: Edge, bounds: List<Long>): List<Polygon> =
        bounds.dropLast(1).mapIndexed { k, at -> notch(b, edge, at, depth(k)) }

    /**
     * The number [n] as a tally at one place on [edge] of box [b]: n notches [TALLY_PITCH] apart, the first at [at], every
     * [TALLY_LONG_EVERY]th long. 0 = none.
     */
    fun count(b: LongArray, edge: Edge, at: Long, n: Int): List<Polygon> =
        (0 until n).map { k -> notch(b, edge, at + k * TALLY_PITCH, depth(k)) }

    /** How long a [count] of [n] runs along its edge (µm, first notch's start to last notch's end). */
    fun countLength(n: Int): Long = if (n <= 0) 0L else (n - 1) * TALLY_PITCH + TALLY_W

    /** [text]'s glyphs drawn at the origin, and their outer box (µm: x0, y0, x1, y1): a diagonal's square end reaches past a straight one's. */
    private fun drawn(text: String): Pair<List<Polygon>, LongArray> {
        val ps = StrokeFont.polygons(text, 0, 0, LABEL_H, LABEL_STROKE)
        val b = longArrayOf(Long.MAX_VALUE, Long.MAX_VALUE, Long.MIN_VALUE, Long.MIN_VALUE)
        for (p in ps) { val q = p.bounds(); b[0] = minOf(b[0], q[0]); b[1] = minOf(b[1], q[1]); b[2] = maxOf(b[2], q[2]); b[3] = maxOf(b[3], q[3]) }
        return ps to b
    }

    /** A label's width (µm, its glyphs' outer box). */
    fun labelWidth(text: String): Long = drawn(text).second.let { it[2] - it[0] }

    /** A label's height (µm, its glyphs' outer box). */
    fun labelHeight(text: String): Long = drawn(text).second.let { it[3] - it[1] }

    /**
     * [text] as a raised label (µm): its glyphs' outer box starting at ([x0], [y0]), [labelWidth] wide and [labelHeight]
     * deep. Refused for a character [StrokeFont] has not.
     */
    fun label(text: String, x0: Long, y0: Long): List<Polygon> {
        val (ps, b) = drawn(text)
        val dx = x0 - b[0]; val dy = y0 - b[1]
        return ps.map { p -> Polygon(LongArray(p.xy.size) { i -> p.xy[i] + if (i % 2 == 0) dx else dy }) }
    }

    /** A whole number as a label's text: "215". */
    fun whole(v: Double): String = Math.round(v).toString()

    /** A length (mm) as a label's text, to the hundredth, trailing zeros dropped: 0.18, 0.2, 1. */
    fun mm(v: Double): String = java.math.BigDecimal(v).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
}
