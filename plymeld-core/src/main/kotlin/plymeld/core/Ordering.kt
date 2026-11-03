package plymeld.core

/**
 * Path ordering within a layer to cut travel: greedy nearest-neighbor from the current
 * nozzle position. Open paths may be reversed (whichever end is nearer is entered);
 * closed loops are entered at their seam and never rotated (the seam is a quality choice).
 *
 * Order constraints kept: groups are emitted in the given order (e.g. walls before
 * infill), and ordering happens only inside a group. A uniform grid over path endpoints
 * keeps each nearest query local.
 */
object Ordering {
    const val MAX_IMPROVE = 200

    /**
     * `extra(x0, y0, x1, y1)`, when given, adds a cost (µm) to a travel, e.g. the equivalent
     * distance of the retraction it would need. It is evaluated only for candidates whose plain
     * distance could still win, so it may be expensive. It must be >= 0 (the search stops on
     * distance alone).
     */
    fun order(paths: List<ToolPath>, startX: Long, startY: Long, extra: ((Long, Long, Long, Long) -> Double)? = null, improvePass: Boolean = true): List<ToolPath> {
        if (paths.size <= 1) return paths
        val n = paths.size
        // endpoints: 2 per path (loops: both = seam)
        val ex = LongArray(2 * n); val ey = LongArray(2 * n)
        for ((i, p) in paths.withIndex()) {
            ex[2 * i] = p.xy[0]; ey[2 * i] = p.xy[1]
            if (p.closed) { ex[2 * i + 1] = p.xy[0]; ey[2 * i + 1] = p.xy[1] }
            else { ex[2 * i + 1] = p.xy[p.xy.size - 2]; ey[2 * i + 1] = p.xy[p.xy.size - 1] }
        }
        var minX = Long.MAX_VALUE; var minY = Long.MAX_VALUE; var maxX = Long.MIN_VALUE; var maxY = Long.MIN_VALUE
        for (k in 0 until 2 * n) { minX = minOf(minX, ex[k]); maxX = maxOf(maxX, ex[k]); minY = minOf(minY, ey[k]); maxY = maxOf(maxY, ey[k]) }
        val cells = Math.max(1, Math.sqrt(n.toDouble()).toInt())
        val cw = Math.max(1L, (maxX - minX) / cells + 1); val ch = Math.max(1L, (maxY - minY) / cells + 1)
        val grid = Array(cells * cells) { ArrayList<Int>(4) }
        fun cellOf(x: Long, y: Long): Int {
            val cx = ((x - minX) / cw).toInt().coerceIn(0, cells - 1); val cy = ((y - minY) / ch).toInt().coerceIn(0, cells - 1)
            return cy * cells + cx
        }
        for (k in 0 until 2 * n) grid[cellOf(ex[k], ey[k])].add(k)
        val used = BooleanArray(n)
        val out = ArrayList<ToolPath>(n)
        var px = startX; var py = startY
        repeat(n) {
            // search rings of cells outward until a candidate is found and the ring is beyond it
            val c0x = ((px - minX) / cw).toInt().coerceIn(0, cells - 1); val c0y = ((py - minY) / ch).toInt().coerceIn(0, cells - 1)
            var best = -1; var bestD = Double.POSITIVE_INFINITY
            var r = 0
            while (r <= cells) {
                for (cy in c0y - r..c0y + r) for (cx in c0x - r..c0x + r) {
                    if (cy < 0 || cx < 0 || cy >= cells || cx >= cells) continue
                    if (Math.max(Math.abs(cx - c0x), Math.abs(cy - c0y)) != r) continue
                    for (k in grid[cy * cells + cx]) {
                        if (used[k / 2]) continue
                        var d = Math.hypot((ex[k] - px).toDouble(), (ey[k] - py).toDouble())
                        if (d >= bestD) continue
                        if (extra != null) { d += extra(px, py, ex[k], ey[k]); if (d >= bestD) continue }
                        bestD = d; best = k
                    }
                }
                // a candidate in ring r is final once the next ring cannot be closer
                if (best >= 0 && bestD <= r * Math.min(cw, ch).toDouble()) break
                r++
            }
            val i = best / 2
            used[i] = true
            val p = paths[i]
            val reversed = !p.closed && best % 2 == 1
            val q = if (reversed) p.reversed() else p
            out.add(q)
            px = q.xy[q.xy.size - 2]; py = q.xy[q.xy.size - 1]
            if (q.closed) { px = q.xy[0]; py = q.xy[1] }
        }
        return if (improvePass) improve(out, startX, startY, extra) else out
    }

    /**
     * 2-opt over the greedy order: reversing a stretch i..j of the sequence (open paths also flipped
     * end to end; closed loops keep their direction and seam) is kept when it lowers the total
     * travel cost. Greedy nearest-first commits early and so depends on where it starts (hole block,
     * a different end point on one layer changed the next layers' travel by ~10 %);
     * the improvement pass removes much of that. Paths with overhang tiers are never flipped.
     * A move is costed with `extra` only when its plain distance could still win.
     */
    fun improve(seq: List<ToolPath>, startX: Long, startY: Long, extra: ((Long, Long, Long, Long) -> Double)? = null, maxPasses: Int = 8): List<ToolPath> {
        val n = seq.size
        // O(n^2) per pass: groups above MAX_IMPROVE paths keep the greedy order (a compute bound,
        // not a quality choice)
        if (n < 3 || n > MAX_IMPROVE) return seq
        val p = seq.toMutableList()
        fun inX(q: ToolPath) = q.xy[0]
        fun inY(q: ToolPath) = q.xy[1]
        fun outX(q: ToolPath) = if (q.closed) q.xy[0] else q.xy[q.xy.size - 2]
        fun outY(q: ToolPath) = if (q.closed) q.xy[1] else q.xy[q.xy.size - 1]
        fun dist(x0: Long, y0: Long, x1: Long, y1: Long) = Math.hypot((x1 - x0).toDouble(), (y1 - y0).toDouble())
        fun cost(x0: Long, y0: Long, x1: Long, y1: Long) = dist(x0, y0, x1, y1) + (extra?.invoke(x0, y0, x1, y1) ?: 0.0)
        val flippable = BooleanArray(n)
        // edge k = travel INTO p[k] (from the start for k = 0)
        val edge = DoubleArray(n)
        fun prevX(k: Int) = if (k == 0) startX else outX(p[k - 1])
        fun prevY(k: Int) = if (k == 0) startY else outY(p[k - 1])
        fun refresh() { for (k in 0 until n) { edge[k] = cost(prevX(k), prevY(k), inX(p[k]), inY(p[k])); flippable[k] = p[k].closed || p[k].overhangShare == null } }
        refresh()
        var pass = 0
        var improved = true
        while (improved && pass < maxPasses) {
            improved = false; pass++
            for (i in 0 until n - 1) {
                if (!flippable[i]) continue
                for (j in i + 1 until n) {
                    if (!flippable[j]) break                 // a stretch may not contain an unflippable path
                    // after reversal: prev(i) -> out(p[j]) (p[j] flipped: enters at its old exit), and
                    // in(p[i]) (old entry, now its exit) -> in(p[j+1])
                    val old = edge[i] + (if (j + 1 < n) edge[j + 1] else 0.0)
                    val ax = prevX(i); val ay = prevY(i)
                    val bx = outX(p[j]); val by = outY(p[j])
                    val cx = inX(p[i]); val cy = inY(p[i])
                    var plain = dist(ax, ay, bx, by)
                    if (j + 1 < n) plain += dist(cx, cy, inX(p[j + 1]), inY(p[j + 1]))
                    if (plain >= old - 1.0) continue          // under 1 µm: not worth a change
                    var new = cost(ax, ay, bx, by)
                    if (j + 1 < n) new += cost(cx, cy, inX(p[j + 1]), inY(p[j + 1]))
                    if (new >= old - 1.0) continue
                    // interior edges keep their cost (distance and hidden-ness are symmetric)
                    val rev = p.subList(i, j + 1).reversed().map { q -> if (q.closed) q else q.reversed() }
                    for (k in rev.indices) p[i + k] = rev[k]
                    // interior edges swap places (new edge i+t = old edge j+1-t); the two ends are new
                    val inner = DoubleArray(j - i) { t -> edge[j - t] }
                    for (t in 1..j - i) edge[i + t] = inner[t - 1]
                    edge[i] = cost(ax, ay, bx, by)
                    if (j + 1 < n) edge[j + 1] = cost(cx, cy, inX(p[j + 1]), inY(p[j + 1]))
                    improved = true
                }
            }
        }
        return p
    }

    /**
     * Monotonic surfaces: when a surface cannot be one sweep, print its blocks
     * in sweep order anyway, starting at the END of the sweep nearest the nozzle, never in the middle.
     * The sweep runs across the lines (normal to the longest segment). Each open path is turned to
     * advance in the sweep direction; blocks are ordered by where they begin along the sweep (ties by
     * position along the lines). Travel is secondary here.
     * Found: nearest-first started the testplate's top at a hole in the middle and went
     * back and forth (74 of 171 run pairs out of sweep order; QIDIStudio 0 of 28).
     */
    fun sweep(paths: List<ToolPath>, startX: Long, startY: Long): List<ToolPath> {
        if (paths.isEmpty()) return paths
        var best = 0.0; var ux = 1.0; var uy = 0.0
        for (p in paths) for (k in 1 until p.xy.size / 2) {
            val dx = (p.xy[2 * k] - p.xy[2 * k - 2]).toDouble(); val dy = (p.xy[2 * k + 1] - p.xy[2 * k - 1]).toDouble()
            val l = Math.hypot(dx, dy)
            if (l > best) { best = l; ux = dx / l; uy = dy / l }
        }
        val nx = -uy; val ny = ux
        fun across(x: Long, y: Long) = x * nx + y * ny
        fun along(x: Long, y: Long) = x * ux + y * uy
        fun turned(p: ToolPath, ascending: Boolean): ToolPath {
            if (p.closed) return p
            val a = across(p.xy[0], p.xy[1]); val b = across(p.xy[p.xy.size - 2], p.xy[p.xy.size - 1])
            val flip = if (ascending) a > b else a < b
            return if (flip) p.reversed() else p
        }
        // The order: nearest-first, among the blocks that may go next. A block may go next when no
        // block still to print lies wholly behind it (a later block wholly behind an earlier one is
        // the violation monotonic surfaces forbid; blocks side by side, e.g. the two sides of a hole, may go in
        // either order). The block whose far edge is nearest is always allowed, so this never
        // stalls. The first block is one at the start of the sweep. Sorting by where each block
        // begins was also violation-free but sent the nozzle back along the lines after every block
        // beside a hole (testplate z 2.0 top: 1,243 mm of top-to-top travel vs QIDIStudio's 466).
        // Both sweep directions are planned; the one with less travel is kept.
        fun endX(p: ToolPath) = if (p.closed) p.xy[0] else p.xy[p.xy.size - 2]
        fun endY(p: ToolPath) = if (p.closed) p.xy[1] else p.xy[p.xy.size - 1]
        fun ordered(ascending: Boolean): Pair<List<ToolPath>, Double> {
            val s = if (ascending) 1.0 else -1.0
            val ps = paths.map { turned(it, ascending) }
            val lo = DoubleArray(ps.size) { i -> val p = ps[i]; (0 until p.xy.size / 2).minOf { k -> s * across(p.xy[2 * k], p.xy[2 * k + 1]) } }
            val hi = DoubleArray(ps.size) { i -> val p = ps[i]; (0 until p.xy.size / 2).maxOf { k -> s * across(p.xy[2 * k], p.xy[2 * k + 1]) } }
            val start = lo.min()
            val left = ps.indices.toMutableList()
            val out = ArrayList<ToolPath>(ps.size)
            var x = startX; var y = startY; var travel = 0.0
            while (left.isNotEmpty()) {
                // the smallest and second-smallest far edge among the blocks left
                var m1 = -1; var h2 = Double.POSITIVE_INFINITY
                for (i in left) if (m1 < 0 || hi[i] < hi[m1]) { if (m1 >= 0) h2 = hi[m1]; m1 = i } else if (hi[i] < h2) h2 = hi[i]
                var best = -1; var bestD = Double.POSITIVE_INFINITY
                for (i in left) {
                    if (lo[i] > (if (i == m1) h2 else hi[m1])) continue      // something left would lie wholly behind it
                    if (out.isEmpty() && lo[i] != start) continue           // begin at the start of the sweep
                    val d = Math.hypot((ps[i].xy[0] - x).toDouble(), (ps[i].xy[1] - y).toDouble())
                    if (d < bestD || (d == bestD && along(ps[i].xy[0], ps[i].xy[1]) < along(ps[best].xy[0], ps[best].xy[1]))) { bestD = d; best = i }
                }
                left.remove(best)
                out.add(ps[best]); travel += bestD
                x = endX(ps[best]); y = endY(ps[best])
            }
            return out to travel
        }
        val up = ordered(true); val down = ordered(false)
        return if (up.second <= down.second) up.first else down.first
    }
}
