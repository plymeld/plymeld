package plymeld.core.calibrate

/**
 * Lays the calibration plate's parts out automatically and deterministically. Each part is a rectangle (its footprint,
 * its base included: [CalibrationPlate.footprints]), never turned (each test's front is its front); parts a gap apart.
 *
 * Skyline packing (bottom-left): the parts go down one at a time, each where its front edge is nearest the plate's front (then
 * furthest left) on top of what is already down, within a width. The ruler post goes first, so it stands at the plate's
 * front-left corner (its chamfer the plate's origin). The width and the order of the rest are chosen, not fixed: every width at
 * which a row of parts could end (each run of consecutive parts side by side, and the room itself) is tried with the rest
 * deepest first, widest first, and largest first (ties in [Part] order); of the layouts that fit the room with [spare] to spare
 * at every edge (else that fit the room at all, else any) the one of least area is taken, then the squarer, then the narrower,
 * then the earlier order. So the plate comes out compact, and the same parts always pack the same way.
 */
object PlatePacking {
    /**
     * A layout: each part's offset (µm) from its own frame to the plate's; the plate's width and depth (µm); [fits]: within
     * the room it was given (with or without the spare at the edges); [spared]: with it.
     */
    class Layout(val offsets: Map<Part, LongArray>, val widthUm: Long, val depthUm: Long, val fits: Boolean, val spared: Boolean = fits)

    /**
     * Packs the parts whose footprints (in their own frames, µm: x0, y0, x1, y1) are [foot], [gapUm] apart, into
     * [maxWidthUm] x [maxDepthUm], keeping [spareUm] to spare at each edge where the room allows.
     */
    fun pack(foot: Map<Part, LongArray>, gapUm: Long, maxWidthUm: Long, maxDepthUm: Long, spareUm: Long): Layout {
        require(foot.isNotEmpty()) { "nothing to lay out" }
        fun wd(p: Part) = foot.getValue(p).let { it[2] - it[0] }
        fun dp(p: Part) = foot.getValue(p).let { it[3] - it[1] }
        val rest = foot.keys.filter { it != Part.POST }
        val head = foot.keys.filter { it == Part.POST }
        val orders = listOf(compareBy<Part>({ -dp(it) }, { it.ordinal }), compareBy({ -wd(it) }, { it.ordinal }), compareBy({ -wd(it).toDouble() * dp(it) }, { it.ordinal }))
            .map { head + rest.sortedWith(it) }.distinct()

        // each part's place within [limit]: a skyline of (x0, x1, top) pieces; every part occupies its footprint and the gap
        // right of and behind it
        fun skyline(order: List<Part>, limit: Long): Layout? {
            val sky = ArrayList<LongArray>().apply { add(longArrayOf(0, Long.MAX_VALUE / 4, 0)) }
            val at = LinkedHashMap<Part, LongArray>()
            var width = 0L; var depth = 0L
            for (p in order) {
                val w = wd(p); val d = dp(p)
                if (w > limit) return null
                var best: LongArray? = null       // x, y
                for (piece in sky) {
                    val x = piece[0]
                    if (x + w > limit) continue
                    val y = sky.filter { it[1] > x && it[0] < x + w + gapUm }.maxOf { it[2] }
                    if (best == null || y < best[1] || (y == best[1] && x < best[0])) best = longArrayOf(x, y)
                }
                val (x, y) = best ?: return null
                val f = foot.getValue(p)
                at[p] = longArrayOf(x - f[0], y - f[1])
                width = maxOf(width, x + w); depth = maxOf(depth, y + d)
                // the skyline over [x, x + w + gap) rises to y + d + gap
                val x1 = x + w + gapUm; val top = y + d + gapUm
                val next = ArrayList<LongArray>()
                for (s in sky) {
                    if (s[1] <= x || s[0] >= x1) { next += s; continue }
                    if (s[0] < x) next += longArrayOf(s[0], x, s[2])
                    if (s[1] > x1) next += longArrayOf(x1, s[1], s[2])
                }
                next += longArrayOf(x, x1, top)
                next.sortBy { it[0] }
                sky.clear(); sky.addAll(next)
            }
            return Layout(at, width, depth, false)
        }
        // every width at which a row could end: each run of consecutive parts side by side, in each order; and the room
        val limits = sortedSetOf<Long>()
        for (order in orders) for (a in order.indices) { var s = 0L; for (b in a until order.size) { s += wd(order[b]) + if (b > a) gapUm else 0L; limits += s } }
        if (maxWidthUm < Long.MAX_VALUE / 4) { limits += maxWidthUm; limits += maxWidthUm - 2 * spareUm }
        class Tried(val limit: Long, val order: Int, val layout: Layout)
        val tried = ArrayList<Tried>()
        for ((oi, order) in orders.withIndex()) for (lim in limits) skyline(order, lim)?.let { tried += Tried(lim, oi, it) }
        fun within(l: Layout, spare: Long) = l.widthUm <= maxWidthUm - 2 * spare && l.depthUm <= maxDepthUm - 2 * spare
        val pick = compareBy<Tried>({ it.layout.widthUm.toDouble() * it.layout.depthUm }, { maxOf(it.layout.widthUm, it.layout.depthUm) }, { it.limit }, { it.order })
        tried.filter { within(it.layout, spareUm) }.minWithOrNull(pick)?.let { return Layout(it.layout.offsets, it.layout.widthUm, it.layout.depthUm, true, true) }
        tried.filter { within(it.layout, 0) }.minWithOrNull(pick)?.let { return Layout(it.layout.offsets, it.layout.widthUm, it.layout.depthUm, true, false) }
        return tried.minWith(pick).layout
    }
}
