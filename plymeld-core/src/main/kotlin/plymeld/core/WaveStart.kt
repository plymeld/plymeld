package plymeld.core

import plymeld.geom.Booleans
import plymeld.geom.Polygon

/**
 * Shapes in a waved bridge's span, in the span's own frame ([Frame]): along runs from the first support (in order of x then
 * y) to the second, across at right angles; the gap between the supports runs along t0..t1, its edges across at c0 (the
 * lower) and c1.
 *
 * The region laid first (plan.technique.bridge.wave.start), before the rest of the span is filled with
 * waves grown from the supports and from what was laid ([region]):
 *  - edge_strips: a strip [widthUm] wide along each edge of the span, foot to foot (a few mm on each
 *    side of the bridge);
 *  - triangles: from each support a triangle as wide as the span at the support, narrowing to a point at the middle of the
 *    c0 edge, both meeting there (the width tapered linearly: two triangles from above);
 *  - opposite_triangles: as triangles, the second support's point on the c1 edge (the two sides opposite).
 *
 * Chevron fronts (plan.technique.bridge.wave.front = "chevron": two triangles along the edges
 * from each support first, the V between them filled as they lead on, a diamond in the middle last) ([chevronFronts]).
 */
object WaveStart {
    /** How far past the gap each shape reaches (µm), so it meets the supports and the edges whole. */
    private const val PAST_UM = 500.0

    /**
     * The span between the supports [first] and [second] (each a support's polygons). The gap runs from the first support's
     * inner edge to the second's (taken from the air's own extent, specks of 0.001 mm2 at a support's far end,
     * under a strip's end, put the middle 7.5 mm towards it, and the triangles came out one large and one small); its edges
     * are [air]'s extent across, between them.
     */
    class Frame(air: List<Polygon>, first: List<Polygon>, second: List<Polygon>) {
        private val a: DoubleArray; private val ax: Double; private val ay: Double
        val t0: Double; val t1: Double; val c0: Double; val c1: Double
        init {
            fun center(g: List<Polygon>) = g[0].bounds().let { bb -> doubleArrayOf((bb[0] + bb[2]) / 2.0, (bb[1] + bb[3]) / 2.0) }
            a = center(first); val b = center(second)
            val len = Math.hypot(b[0] - a[0], b[1] - a[1])
            require(len > 0) { "the supports' centers coincide" }
            ax = (b[0] - a[0]) / len; ay = (b[1] - a[1]) / len
            fun pts(ps: List<Polygon>) = ps.asSequence().flatMap { p -> (0 until p.xy.size / 2).asSequence().map { k -> p.xy[2 * k] to p.xy[2 * k + 1] } }
            t0 = pts(first).maxOf { (x, y) -> t(x, y) }
            t1 = pts(second).minOf { (x, y) -> t(x, y) }
            require(t1 > t0) { "the supports overlap along the span" }
            var lo = Double.POSITIVE_INFINITY; var hi = Double.NEGATIVE_INFINITY
            // the air's points between the supports' inner edges, within the wave code's tolerance (WaveFronts.TOL_UM): the air
            // may reach a few µm past them (6 µm into a foot once the anchors were the supports' tops alone, and a
            // 1 µm window found no point at all)
            for ((x, y) in pts(air)) { val tt = t(x, y); if (tt >= t0 - WaveFronts.TOL_UM && tt <= t1 + WaveFronts.TOL_UM) { val cc = c(x, y); lo = minOf(lo, cc); hi = maxOf(hi, cc) } }
            require(hi > lo) { "no gap between the supports (along the span %.0f..%.0f µm)".format(java.util.Locale.ROOT, t0, t1) }
            c0 = lo; c1 = hi
        }
        fun t(x: Long, y: Long) = (x - a[0]) * ax + (y - a[1]) * ay
        fun c(x: Long, y: Long) = -(x - a[0]) * ay + (y - a[1]) * ax
        val tm get() = (t0 + t1) / 2
        /** A line's points in x y from (along, across) points. */
        fun line(vararg tc: Double): LongArray = poly(*tc).xy
        /** A polygon from (along, across) corners, counter-clockwise there and so in x y (the frame keeps orientation). */
        fun poly(vararg tc: Double): Polygon {
            val xy = LongArray(tc.size)
            for (k in 0 until tc.size / 2) {
                val tt = tc[2 * k]; val cc = tc[2 * k + 1]
                xy[2 * k] = Math.round(a[0] + tt * ax - cc * ay); xy[2 * k + 1] = Math.round(a[1] + tt * ay + cc * ax)
            }
            return Polygon(xy)
        }
    }

    /** The region of [air] (the gap) laid first, between the supports [first] and [second] (each a support's polygons, the first in order of x then y). */
    fun region(air: List<Polygon>, first: List<Polygon>, second: List<Polygon>, mode: String, widthUm: Long?): List<Polygon> {
        val f = Frame(air, first, second)
        val t0 = f.t0; val t1 = f.t1; val c0 = f.c0; val c1 = f.c1; val tm = f.tm; val e = PAST_UM
        val shapes = when (mode) {
            "edge_strips" -> {
                val w = (widthUm ?: error("plan.technique.bridge.wave.start = \"edge_strips\" needs wave.strip.width (each strip's width)")).toDouble()
                listOf(f.poly(t0 - e, c0 - e, t1 + e, c0 - e, t1 + e, c0 + w, t0 - e, c0 + w),
                       f.poly(t0 - e, c1 - w, t1 + e, c1 - w, t1 + e, c1 + e, t0 - e, c1 + e))
            }
            "triangles", "opposite_triangles" -> {
                val a = f.poly(t0 - e, c0 - e, tm, c0 - e, t0 - e, c1 + e)
                val b = if (mode == "triangles") f.poly(t1 + e, c0 - e, t1 + e, c1 + e, tm, c0 - e)
                        else f.poly(t1 + e, c0 - e, t1 + e, c1 + e, tm, c1 + e)
                listOf(a, b)
            }
            else -> error("plan.technique.bridge.wave.start '$mode' is not a known value")
        }
        return Booleans.intersection(air, Booleans.unionPositive(shapes))
    }

    /**
     * The fronts of a waved bridge's span [air] grown from both supports at once with the edges leading the middle by
     * [leadUm]: a point d from its support's inner edge and a share u of the half width from the span's center line (0 at the
     * center, 1 at the edges) is reached when the fronts have come d - lead u from the support. So each side starts with two
     * triangles along the edges, its fronts are chevrons, edge to center to edge, and where the two sides meet the edges
     * close first, leaving a diamond in the middle, laid last. Fronts are [p]'s spacing apart at right angles to the chevron's
     * arms; the first reaches the bead less the overlap past the support at the edges, as grown fronts do. Each front is the
     * area covered so far ([WaveFronts.Front]); a side's covered area stops at the middle of the span.
     */
    fun chevronFronts(air: List<Polygon>, first: List<Polygon>, second: List<Polygon>, leadUm: Long?, p: WaveFronts.Params,
                      middle: Boolean = false): List<WaveFronts.Front> {
        val f = Frame(air, first, second)
        val e = PAST_UM; val h = (f.c1 - f.c0) / 2; val cm = (f.c0 + f.c1) / 2; val lead = leadUm?.toDouble() ?: ((f.t1 - f.t0) / 2)   // unset: half the span
        val reach = lead * (h + e) / h                       // how far past the center's depth a front reaches at the edges (and beyond them)
        val step = p.spacingUm * Math.sqrt(1 + (lead / h) * (lead / h))
        // one side's covered area at depth k, as a polygon in (distance from its support, across), mapped by [at]
        fun side(k: Double, at: (Double, Double) -> Pair<Double, Double>, mirrored: Boolean): List<Polygon> {
            fun poly(corners: List<Pair<Double, Double>>): Polygon {
                val tc = corners.map { (d, c) -> at(d, c) }.let { if (mirrored) it.reversed() else it }
                return f.poly(*tc.flatMap { (t, c) -> listOf(t, c) }.toDoubleArray())
            }
            if (middle) {
                // the middle leading (wave.chevron.leading = "middle", middle-out): a point at distance d
                // from its support and a share u of the half width off the center line is reached when d - lead (1 - u) <= k;
                // first a triangle out of the support's middle, its tip leading, then the whole width, the edges trailing
                val back = lead * e / h                      // how far behind the edges' depth the front lies just past them
                if (k - back >= -e) return listOf(poly(listOf(-e to f.c0 - e, k - back to f.c0 - e, k + lead to cm, k - back to f.c1 + e, -e to f.c1 + e)))
                val u = 1 + (k + e) / lead                   // where the front meets the support's side, as a share of the half width
                if (u <= 0) return emptyList()
                return listOf(poly(listOf(-e to cm - u * h, k + lead to cm, -e to cm + u * h)))
            }
            if (k >= -e / 2) return listOf(poly(listOf(-e to f.c0 - e, k + reach to f.c0 - e, k to cm, k + reach to f.c1 + e, -e to f.c1 + e)))
            // the center not yet reached: two triangles along the edges, from where the front crosses the support's side
            val gap = (-e - k) * h / lead
            if (gap >= h + e) return emptyList()
            return listOf(poly(listOf(-e to f.c0 - e, k + reach to f.c0 - e, -e to cm - gap)),
                          poly(listOf(-e to cm + gap, k + reach to f.c1 + e, -e to f.c1 + e)))
        }
        val halfA = listOf(f.poly(f.t0 - e, f.c0 - e, f.tm, f.c0 - e, f.tm, f.c1 + e, f.t0 - e, f.c1 + e))
        // (chevronRows below: the same regions, laid in rows across instead of along the fronts)
        val halfB = listOf(f.poly(f.tm, f.c0 - e, f.t1 + e, f.c0 - e, f.t1 + e, f.c1 + e, f.tm, f.c1 + e))
        val out = ArrayList<WaveFronts.Front>()
        var k = p.firstUm - lead
        var coveredMm2 = 0.0
        val last = (f.tm - f.t0) + lead + p.spacingUm
        while (k <= last && out.size < p.maxFronts) {
            val parts = ArrayList<Polygon>()
            side(k, { d, c -> (f.t0 + d) to c }, false).let { if (it.isNotEmpty()) parts += Booleans.intersection(it, halfA) }
            side(k, { d, c -> (f.t1 - d) to c }, true).let { if (it.isNotEmpty()) parts += Booleans.intersection(it, halfB) }
            val covered = Booleans.intersection(air, Booleans.unionPositive(parts))
            val area = covered.sumOf { it.signedAreaMm2() }
            if (area - coveredMm2 >= p.minAreaMm2) { out += WaveFronts.Front(covered, area - coveredMm2); coveredMm2 = area }
            k += step
        }
        return out
    }

    /**
     * One line of [evenRows]: its points in x y; its side (0 = the first support's, 1 = the second's, 2 = the middle line);
     * whether it is an edge piece (along a span's edge from its support's first line up to the middle); its front (the k-th
     * line from its support, the middle line after both sides' last).
     */
    class EvenLine(val xy: LongArray, val side: Int, val edge: Boolean, val front: Int)

    /**
     * A waved bridge's lines evenly spaced along its surface (plan.technique.bridge.wave.spacing = "even": line
     * width and spacing as near identical throughout as they can be, an odd count with the last line in the middle): rows
     * across the span, parallel to the supports' edges, each between the span's edges half a bead in, as a straight-front
     * waved bridge lays them; the first row at each support half a bead less the overlap from it, as grown fronts; between
     * those two rows, the span along the center line divided into an even number of equal steps measured along the surface
     * [heightAt] gives (mm, at x y in mm; null = flat: measured from above), as near [WaveFronts.Params.spacingUm] as a whole
     * number of them allows, a row at each step. The rows short of the middle are their support's, front by front out from it;
     * the row at the middle is the middle line. The edge pieces run along both edges from each side's first row to the middle.
     */
    fun evenRows(air: List<Polygon>, first: List<Polygon>, second: List<Polygon>, p: WaveFronts.Params,
                 heightAt: ((Double, Double) -> Double)?): List<EvenLine> {
        val f = Frame(air, first, second)
        val b = p.beadUm.toDouble(); val cm = (f.c0 + f.c1) / 2
        val lo = f.c0 + b / 2; val hi = f.c1 - b / 2
        require(hi > lo) { "plan.technique.bridge.wave.spacing = \"even\": the span is narrower than a bead" }
        val d0 = b / 2 - p.overlapUm
        val ta = f.t0 + d0; val tb = f.t1 - d0
        require(tb > ta) { "plan.technique.bridge.wave.spacing = \"even\": the span is shorter than its first lines" }
        // the length along the surface from the first row to each point of the center line, sampled (every SAMPLE_UM)
        val n = maxOf(2, Math.ceil((tb - ta) / SAMPLE_UM).toInt())
        val ts = DoubleArray(n + 1) { ta + (tb - ta) * it / n }
        val arc = DoubleArray(n + 1)
        fun z(t: Double): Double = heightAt?.let { h -> val xy = f.line(t, cm); h(xy[0] / 1000.0, xy[1] / 1000.0) * 1000 } ?: 0.0
        var zPrev = z(ts[0])
        for (k in 1..n) { val zk = z(ts[k]); arc[k] = arc[k - 1] + Math.hypot(ts[k] - ts[k - 1], zk - zPrev); zPrev = zk }
        val length = arc[n]
        val steps = maxOf(2, 2 * Math.round(length / p.spacingUm / 2).toInt())
        // the position along the span of a length along the surface (the samples interpolated)
        fun tAt(s: Double): Double {
            var j = java.util.Arrays.binarySearch(arc, s).let { if (it >= 0) it else -it - 2 }.coerceIn(0, n - 1)
            while (j < n - 1 && arc[j + 1] < s) j++
            val span = arc[j + 1] - arc[j]
            return if (span <= 0) ts[j] else ts[j] + (ts[j + 1] - ts[j]) * (s - arc[j]) / span
        }
        val out = ArrayList<EvenLine>()
        val half = steps / 2
        for (k in 0 until half) {
            out += EvenLine(f.line(tAt(length * k / steps), lo, tAt(length * k / steps), hi), 0, false, k)
            out += EvenLine(f.line(tAt(length * (steps - k) / steps), lo, tAt(length * (steps - k) / steps), hi), 1, false, k)
        }
        val tm = tAt(length / 2)
        out += EvenLine(f.line(tm, lo, tm, hi), 2, false, half)
        for (c in doubleArrayOf(lo, hi)) {
            out += EvenLine(f.line(ta, c, tm, c), 0, true, 0)
            out += EvenLine(f.line(tb, c, tm, c), 1, true, 0)
        }
        return out
    }

    /** How finely [evenRows] samples the surface along the span (µm). */
    private const val SAMPLE_UM = 50.0

    /** One line of [chevronRows]: its points in x y, and whether its direction is its own (from the leading region out). */
    class Row(val xy: LongArray, val ownDirection: Boolean)

    /**
     * A chevron's lines across (plan.technique.bridge.wave.chevron.lines = "across": the lines in the same
     * direction as the others, parallel to the foot's edge, not rows laid piece by piece as they grow): rows
     * parallel to the supports' edges, [WaveFronts.Params.spacingUm] apart, as a straight-front waved bridge lays them (the
     * first row's center half a bead less the overlap from its support, each row's ends half a bead inside the span's edges);
     * each row split where [chevronFronts]' leading region ends at k = 0 (the middle piece with [middle], else the two edge
     * pieces): first the leading parts, row by row from each support outward, then the rest of each row the same way, each
     * of those from the leading region out. A piece shorter than a spacing is not laid on its own: the row's other piece(s)
     * take its place (so the rows nearest the center, where the middle's tips meet, are two edge pieces meeting there).
     * Returned as fronts: each a row's pieces from both supports, first support first.
     */
    fun chevronRows(air: List<Polygon>, first: List<Polygon>, second: List<Polygon>, leadUm: Long?, p: WaveFronts.Params,
                    middle: Boolean): List<List<Row>> {
        val f = Frame(air, first, second)
        val b = p.beadUm.toDouble(); val sp = p.spacingUm.toDouble()
        val h = (f.c1 - f.c0) / 2; val cm = (f.c0 + f.c1) / 2; val half = (f.t1 - f.t0) / 2
        val lead = leadUm?.toDouble() ?: half
        val lo = f.c0 + b / 2; val hi = f.c1 - b / 2
        val leading = ArrayList<List<Row>>(); val rest = ArrayList<List<Row>>()
        var d = b / 2 - p.overlapUm
        while (d <= half - sp / 4) {
            // the middle piece's half width: the leading middle's, or what the leading edges leave
            val hw = h * (if (middle) (1 - d / lead) else d / lead).coerceIn(0.0, 1.0)
            var m = maxOf(lo, cm - hw) to minOf(hi, cm + hw)
            var edges = (m.first - lo >= sp && hi - m.second >= sp)
            if (m.second - m.first < sp) { m = cm to cm; edges = true }        // no middle piece: the edges meet at the center
            if (!edges) m = lo to hi                                            // no edge pieces: the middle piece is the row
            val lead1 = ArrayList<Row>(); val rest1 = ArrayList<Row>()
            for (t in doubleArrayOf(f.t0 + d, f.t1 - d)) {
                val mid = if (m.second - m.first >= sp) Row(f.line(t, m.first, t, m.second), false) else null
                // the edge pieces: from the middle out with the middle leading (laid after it), else as grown from the edge in
                val left = if (edges) Row(if (middle) f.line(t, m.first, t, lo) else f.line(t, lo, t, m.first), middle) else null
                val right = if (edges) Row(if (middle) f.line(t, m.second, t, hi) else f.line(t, hi, t, m.second), middle) else null
                if (middle) { mid?.let(lead1::add); listOfNotNull(left, right).let(rest1::addAll) }
                else { listOfNotNull(left, right).let(lead1::addAll); mid?.let(rest1::add) }
            }
            if (lead1.isNotEmpty()) leading += lead1
            if (rest1.isNotEmpty()) rest += rest1
            d += sp
        }
        return leading + rest
    }
}
