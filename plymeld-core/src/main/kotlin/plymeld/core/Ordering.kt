package plymeld.core

/**
 * Path ordering within a layer to cut travel (and, given the cost of a retraction, retractions): greedy nearest-neighbor
 * from the current nozzle position, then an improvement pass ([improve]). Open paths may be reversed (whichever end is
 * nearer is entered, the pass may turn one), unless the caller names the end one must be entered at ([order]'s entry);
 * closed loops are entered at their seam and never rotated (the seam is a
 * quality choice).
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
     * distance alone). A travel no longer than [freeUm] costs its length alone (one the writer never retracts:
     * hardware.filament.retract.travel.min), and the improvement pass moves a path whose travel costs extra beside a path
     * that ends within it ([improve]).
     *
     * [entry], when given, names the end an open path must be entered at: 0 = its first point (as given), 1 = its last (the
     * path turned), -1 = either (plan.layer.walls.open_start = hidden_end: a line with one end on a corner the viewer sees
     * is entered at the other). The order never enters a path at the other end, and the improvement pass never turns it.
     */
    fun order(paths: List<ToolPath>, startX: Long, startY: Long, extra: ((Long, Long, Long, Long) -> Double)? = null, improvePass: Boolean = true, freeUm: Double = 0.0,
              entry: ((ToolPath) -> Int)? = null): List<ToolPath> {
        if (paths.size <= 1) {
            // one path: entered at the end it must be, if it must
            val p = paths.singleOrNull() ?: return paths
            return if (entry != null && !p.closed && entry(p) == 1) listOf(p.reversed()) else paths
        }
        val n = paths.size
        val must = IntArray(n) { i -> if (entry == null || paths[i].closed) -1 else entry(paths[i]) }
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
                        if (must[k / 2] >= 0 && k % 2 != must[k / 2]) continue
                        var d = Math.hypot((ex[k] - px).toDouble(), (ey[k] - py).toDouble())
                        if (d >= bestD) continue
                        if (extra != null && d > freeUm) { d += extra(px, py, ex[k], ey[k]); if (d >= bestD) continue }
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
        return if (improvePass) improve(out, startX, startY, extra, freeUm = freeUm, fixed = if (entry == null) null else { q -> !q.closed && entry(q) >= 0 }) else out
    }

    /**
     * 2-opt over the greedy order: reversing a stretch i..j of the sequence (open paths also flipped
     * end to end; closed loops keep their direction and seam) is kept when it lowers the total
     * travel cost. Greedy nearest-first commits early and so depends on where it starts (a different end point on one
     * layer changes the next layers' travel); the improvement pass removes much of that. A stretch of one open path is that path turned end to end. Paths with
     * overhang tiers are never flipped. With [freeUm] (the travel that never retracts), a path whose travel costs extra is
     * also moved beside a path ending within it (relocate, below). A move is costed with `extra` only when its plain
     * distance could still win, and a travel no longer than [freeUm] not at all. A path [fixed] names (one entered at the end
     * it must be: [order]'s entry) is never turned, as one with overhang tiers.
     */
    fun improve(seq: List<ToolPath>, startX: Long, startY: Long, extra: ((Long, Long, Long, Long) -> Double)? = null, maxPasses: Int = 8, freeUm: Double = 0.0,
                fixed: ((ToolPath) -> Boolean)? = null): List<ToolPath> {
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
        fun cost(x0: Long, y0: Long, x1: Long, y1: Long): Double { val d = dist(x0, y0, x1, y1); return if (d <= freeUm || extra == null) d else d + extra(x0, y0, x1, y1) }
        fun turnable(q: ToolPath) = q.closed || (q.overhangShare == null && fixed?.invoke(q) != true)
        val flippable = BooleanArray(n)
        // edge k = travel INTO p[k] (from the start for k = 0)
        val edge = DoubleArray(n)
        fun prevX(k: Int) = if (k == 0) startX else outX(p[k - 1])
        fun prevY(k: Int) = if (k == 0) startY else outY(p[k - 1])
        fun refresh() { for (k in 0 until n) { edge[k] = cost(prevX(k), prevY(k), inX(p[k]), inY(p[k])); flippable[k] = turnable(p[k]) } }
        refresh()
        // A path reached or left by a travel that costs more than its length (one [extra] prices, a retraction) is taken out
        // and put back beside a path whose end lies within [freeUm] of one of its own ends (an open one either way round), a
        // travel that costs nothing extra, when that lowers the total. A stretch reversal cannot move a path the greedy order
        // left for later: a short line between walls, beside the line it meets, reached once everything round it was laid by a
        // retracted travel. Only such places are tried: every place in the group, each costed with [extra], would make
        // the planning much slower.
        fun relocate(): Boolean {
            if (freeUm <= 0.0 || extra == null) return false
            var moved = false
            var i = 0
            while (i < n) {
                val q = p[i]
                val retractedIn = edge[i] > dist(prevX(i), prevY(i), inX(q), inY(q)) + 1.0
                val retractedOut = i + 1 < n && edge[i + 1] > dist(outX(q), outY(q), inX(p[i + 1]), inY(p[i + 1])) + 1.0
                if (!retractedIn && !retractedOut) { i++; continue }
                // what taking it out saves: its two travels, less the one that joins its neighbors (that one's plain length
                // first, an upper bound on the saving; costed in full only when a place for it could win)
                val bridgePlain = if (i + 1 < n) dist(prevX(i), prevY(i), inX(p[i + 1]), inY(p[i + 1])) else 0.0
                val savedAtMost = edge[i] + (if (i + 1 < n) edge[i + 1] else 0.0) - bridgePlain
                if (savedAtMost <= 1.0) { i++; continue }
                var bridgeKnown = i + 1 >= n
                var bridge = bridgePlain
                var saved = savedAtMost
                val ways = if (q.closed || !flippable[i]) listOf(q) else listOf(q, q.reversed())
                var bestAdd = saved - 1.0; var bestT = -1; var bestQ = q
                // slot t: before r[t] of the sequence without q (r[t] = p[t] for t < i, p[t + 1] after); t = n - 1: at the end
                for (t in 0 until n) {
                    if (t == i) continue                                       // where it is
                    val ax = if (t == 0) startX else if (t - 1 < i) outX(p[t - 1]) else outX(p[t])
                    val ay = if (t == 0) startY else if (t - 1 < i) outY(p[t - 1]) else outY(p[t])
                    val b = if (t >= n - 1) null else if (t < i) p[t] else p[t + 1]
                    val e = if (b == null) 0.0 else if (t < i) edge[t] else if (t == i) bridge else edge[t + 1]
                    for (w in ways) {
                        val dIn = dist(ax, ay, inX(w), inY(w)); val dOut = if (b != null) dist(outX(w), outY(w), inX(b), inY(b)) else Double.POSITIVE_INFINITY
                        if (dIn > freeUm && dOut > freeUm) continue
                        val plain = dIn - e + (if (b != null) dOut else 0.0)
                        if (plain >= bestAdd) continue
                        var add = cost(ax, ay, inX(w), inY(w)) - e
                        if (b != null) add += cost(outX(w), outY(w), inX(b), inY(b))
                        if (add >= bestAdd) continue
                        if (!bridgeKnown) {
                            bridge = cost(prevX(i), prevY(i), inX(p[i + 1]), inY(p[i + 1])); bridgeKnown = true
                            saved = savedAtMost + bridgePlain - bridge
                            if (bestT < 0) bestAdd = saved - 1.0
                            if (add >= bestAdd) continue
                        }
                        bestAdd = add; bestT = t; bestQ = w
                    }
                }
                if (bestT < 0) { i++; continue }
                val old = p.toList(); val oldEdge = edge.copyOf()
                p.removeAt(i)
                p.add(bestT, bestQ)
                // the travels between paths that were neighbors in the same order keep their cost; the rest (round the old
                // place and the new) are costed again
                val at = java.util.IdentityHashMap<ToolPath, Int>(n).also { m -> for ((k, x) in old.withIndex()) m[x] = k }
                for (k in 0 until n) {
                    val cur = at[p[k]] ?: -1
                    val kept = cur >= 0 && (if (k == 0) cur == 0 else at[p[k - 1]].let { it != null && it == cur - 1 })
                    edge[k] = if (kept) oldEdge[cur] else cost(prevX(k), prevY(k), inX(p[k]), inY(p[k]))
                    flippable[k] = turnable(p[k])
                }
                moved = true
                // the paths between its old and new places moved by one: look again from the earlier of the two
                i = minOf(i, bestT)
            }
            return moved
        }
        var pass = 0
        var improved = true
        while (improved && pass < maxPasses) {
            improved = false; pass++
            for (i in 0 until n) {
                if (!flippable[i]) continue
                // j = i: one open path turned end to end (greedy enters it at its nearer end, whatever that leaves for its exit)
                for (j in (if (p[i].closed) i + 1 else i) until n) {
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
            if (relocate()) improved = true
        }
        return p
    }

    /**
     * Monotonic surfaces: when a surface cannot be one sweep, print its blocks
     * in sweep order anyway, starting at the END of the sweep nearest the nozzle, never in the middle.
     * The sweep runs across the lines (normal to the longest segment). Each open path is turned to
     * advance in the sweep direction; blocks are ordered by where they begin along the sweep (ties by
     * position along the lines). Travel is secondary here.
     * Nearest-first would start a top with holes (the testplate's) at a hole in the middle and go back and forth.
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
        // begins is also violation-free but sends the nozzle back along the lines after every block
        // beside a hole.
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
