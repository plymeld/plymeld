package plymeld.geom

/**
 * Joins hatch lines into continuous polylines by running along the region boundary between
 * the end of one line and the start of the next (the "anchors" along the wall that
 * QIDIStudio prints). Fewer separate runs = fewer retractions, less travel, less stringing.
 *
 * Every hatch endpoint lies on the region boundary (Hatch clips to it). For a chain ending
 * at E, the candidates are the endpoints of lines on the NEXT scanline; the connector is the
 * shorter way around E's boundary ring to the candidate, accepted only when it is shorter
 * than `maxConnectorUm`. Otherwise the chain ends and a new one starts.
 */
object Linker {
    private class RingPos(val ring: Int, val edge: Int, val t: Double, val arc: Double)

    /**
     * [breakAtSplits]: end a chain where the fill merges (below). [breakAtForks] (with it): also where the fill FORKS (the next
     * scanline has two lines beside this one, e.g. at a notch in the region's edge), so no chain carries on into one side
     * of a fork: each side is a block of its own. Without it a chain runs on into one side, and a sweep that runs against the
     * chains (from the merge back to the fork) may print that chain's lines before the fork and then the other side, whose
     * last line then goes down between two printed neighbors. Found on the calibration plates' bases (diagonal
     * lines round a notch in the edge: a 4.2 mm line printed between neighbors beside it for 4.2 and 3.4 mm); used there
     * (CalibrationPlate). The slicing pipeline's monotonic surfaces do not set it (unchanged).
     */
    fun link(segs: LongArray, scanline: IntArray, region: List<Polygon>, maxConnectorUm: Long, breakAtSplits: Boolean = false, breakAtForks: Boolean = false): List<LongArray> {
        val n = segs.size / 4
        if (n == 0) return emptyList()
        // Along-line interval of each segment (all hatch lines share one direction). With
        // `breakAtSplits`, a chain ends where the fill MERGES (the next line overlaps two lines of this
        // scanline, e.g. past a hole): it would otherwise run on past a side not yet printed, and the
        // sweep would have to come back (monotonic surfaces). At a split the chain may carry on
        // into one side; the other side is its own block. Breaking at splits too gave 34 runs on the
        // testplate's top where 19 do (QIDIStudio: 8).
        val lo = DoubleArray(n); val hi = DoubleArray(n)
        run {
            val dx = (segs[2] - segs[0]).toDouble(); val dy = (segs[3] - segs[1]).toDouble(); val l = Math.hypot(dx, dy).coerceAtLeast(1.0)
            val ux = dx / l; val uy = dy / l
            for (k in 0 until n) {
                val a = segs[4 * k] * ux + segs[4 * k + 1] * uy; val b = segs[4 * k + 2] * ux + segs[4 * k + 3] * uy
                lo[k] = minOf(a, b); hi[k] = maxOf(a, b)
            }
        }
        fun overlaps(a: Int, b: Int) = minOf(hi[a], hi[b]) - maxOf(lo[a], lo[b]) > 0.0
        val rings = region
        val ringLen = DoubleArray(rings.size) { rings[it].perimeter() }
        // cumulative arc length at each vertex, per ring
        val cum = Array(rings.size) { r ->
            val p = rings[r]; val c = DoubleArray(p.size + 1)
            for (i in 0 until p.size) { val j = (i + 1) % p.size; c[i + 1] = c[i] + Math.hypot((p.x(j) - p.x(i)).toDouble(), (p.y(j) - p.y(i)).toDouble()) }
            c
        }
        fun locate(x: Long, y: Long): RingPos? {
            var best: RingPos? = null; var bestD = 3.0   // within 3 grid units of the boundary
            for ((r, p) in rings.withIndex()) for (i in 0 until p.size) {
                val j = (i + 1) % p.size
                val ax = p.x(i).toDouble(); val ay = p.y(i).toDouble(); val bx = p.x(j).toDouble(); val by = p.y(j).toDouble()
                val vx = bx - ax; val vy = by - ay; val l2 = vx * vx + vy * vy
                if (l2 == 0.0) continue
                val t = (((x - ax) * vx + (y - ay) * vy) / l2).coerceIn(0.0, 1.0)
                val d = Math.hypot(x - ax - t * vx, y - ay - t * vy)
                if (d < bestD) { bestD = d; best = RingPos(r, i, t, cum[r][i] + t * Math.sqrt(l2)) }
            }
            return best
        }
        val startPos = arrayOfNulls<RingPos>(n); val endPos = arrayOfNulls<RingPos>(n)
        for (k in 0 until n) { startPos[k] = locate(segs[4 * k], segs[4 * k + 1]); endPos[k] = locate(segs[4 * k + 2], segs[4 * k + 3]) }
        val byLine = HashMap<Int, MutableList<Int>>()
        for (k in 0 until n) byLine.getOrPut(scanline[k]) { ArrayList() }.add(k)
        val used = BooleanArray(n)
        val out = ArrayList<LongArray>()
        val lines = byLine.keys.sorted()
        for (line in lines) for (k0 in byLine.getValue(line)) {
            if (used[k0]) continue
            val chain = ArrayList<Long>()
            var k = k0; var reversed = false
            used[k] = true
            addSeg(chain, segs, k, reversed)
            var curLine = line
            while (true) {
                val e = (if (reversed) startPos[k] else endPos[k]) ?: break
                val next = byLine[curLine + 1] ?: break
                if (breakAtForks && next.count { overlaps(k, it) } > 1) break
                var best = -1; var bestRev = false; var bestLen = Double.POSITIVE_INFINITY; var bestDir = 0
                for (c in next) {
                    if (used[c]) continue
                    for (rev in listOf(false, true)) {
                        val s = (if (rev) endPos[c] else startPos[c]) ?: continue
                        if (s.ring != e.ring) continue
                        val fwd = ((s.arc - e.arc) % ringLen[e.ring] + ringLen[e.ring]) % ringLen[e.ring]
                        val back = ringLen[e.ring] - fwd
                        val (len, dir) = if (fwd <= back) fwd to 1 else back to -1
                        if (len < bestLen) { bestLen = len; best = c; bestRev = rev; bestDir = dir }
                    }
                }
                if (best < 0 || bestLen > maxConnectorUm) break
                if (breakAtSplits && byLine.getValue(curLine).count { overlaps(best, it) } > 1) break
                val s = (if (bestRev) endPos[best] else startPos[best])!!
                addArc(chain, rings[e.ring], e, s, bestDir)
                used[best] = true
                k = best; reversed = bestRev; curLine++
                addSeg(chain, segs, k, reversed, skipFirst = true)
            }
            out.add(chain.toLongArray())
        }
        return out
    }

    /**
     * Joins lines of ANY direction (e.g. both angles of a grid) along the region boundary. Every line
     * end is placed on its boundary ring; only ends that are NEIGHBORS along a ring may be joined,
     * so each stretch of boundary is walked at most once. Gaps are taken shortest first, up to
     * `maxConnectorUm`, and never join a chain to itself (chains stay open polylines).
     *
     * Why (seen on a test model): linking each grid direction on its own ran each direction along
     * the whole region edge, so every edge was printed twice as sparse; sparse came out 1.30x
     * QIDIStudio's on four models. The two directions meet near each other at the wall, so joining
     * across them needs only a short step along it.
     */
    fun linkAll(segs: LongArray, region: List<Polygon>, maxConnectorUm: Long): List<LongArray> {
        val n = segs.size / 4
        if (n == 0) return emptyList()
        val rings = region
        val ringLen = DoubleArray(rings.size) { rings[it].perimeter() }
        val cum = Array(rings.size) { r ->
            val p = rings[r]; val c = DoubleArray(p.size + 1)
            for (i in 0 until p.size) { val j = (i + 1) % p.size; c[i + 1] = c[i] + Math.hypot((p.x(j) - p.x(i)).toDouble(), (p.y(j) - p.y(i)).toDouble()) }
            c
        }
        fun locate(x: Long, y: Long): RingPos? {
            var best: RingPos? = null; var bestD = 3.0
            for ((r, p) in rings.withIndex()) for (i in 0 until p.size) {
                val j = (i + 1) % p.size
                val ax = p.x(i).toDouble(); val ay = p.y(i).toDouble(); val bx = p.x(j).toDouble(); val by = p.y(j).toDouble()
                val vx = bx - ax; val vy = by - ay; val l2 = vx * vx + vy * vy
                if (l2 == 0.0) continue
                val t = (((x - ax) * vx + (y - ay) * vy) / l2).coerceIn(0.0, 1.0)
                val d = Math.hypot(x - ax - t * vx, y - ay - t * vy)
                if (d < bestD) { bestD = d; best = RingPos(r, i, t, cum[r][i] + t * Math.sqrt(l2)) }
            }
            return best
        }
        // end e = 2k (start of line k) or 2k+1 (its end)
        val pos = arrayOfNulls<RingPos>(2 * n)
        for (k in 0 until n) { pos[2 * k] = locate(segs[4 * k], segs[4 * k + 1]); pos[2 * k + 1] = locate(segs[4 * k + 2], segs[4 * k + 3]) }
        // candidate gaps between neighboring ends on each ring (a gap from a forward to b)
        class Gap(val a: Int, val b: Int, val len: Double)
        val gaps = ArrayList<Gap>()
        val byRing = HashMap<Int, MutableList<Int>>()
        for (e in 0 until 2 * n) pos[e]?.let { byRing.getOrPut(it.ring) { ArrayList() }.add(e) }
        for ((r, ends) in byRing) {
            if (ends.size < 2) continue
            ends.sortBy { pos[it]!!.arc }
            for (i in ends.indices) {
                val a = ends[i]; val b = ends[(i + 1) % ends.size]
                val len = ((pos[b]!!.arc - pos[a]!!.arc) % ringLen[r] + ringLen[r]) % ringLen[r]
                if (len <= maxConnectorUm) gaps.add(Gap(a, b, len))
            }
        }
        gaps.sortBy { it.len }
        val partner = IntArray(2 * n) { -1 }
        val forward = BooleanArray(2 * n)       // the connector leaving this end runs forward along the ring
        val root = IntArray(n) { it }
        fun find(k: Int): Int { var x = k; while (root[x] != x) { root[x] = root[root[x]]; x = root[x] }; return x }
        for (g in gaps) {
            if (partner[g.a] >= 0 || partner[g.b] >= 0) continue
            val ra = find(g.a / 2); val rb = find(g.b / 2)
            if (ra == rb) continue
            root[ra] = rb
            partner[g.a] = g.b; partner[g.b] = g.a
            forward[g.a] = true; forward[g.b] = false
        }
        // walk each chain from a free end
        val used = BooleanArray(n)
        val out = ArrayList<LongArray>()
        for (start in 0 until 2 * n) {
            val k0 = start / 2
            if (used[k0] || partner[start] >= 0) continue
            val chain = ArrayList<Long>()
            var entry = start
            var first = true
            while (true) {
                val k = entry / 2
                used[k] = true
                addSeg(chain, segs, k, reversed = entry % 2 == 1, skipFirst = !first)
                first = false
                val exit = entry xor 1
                val next = partner[exit]
                if (next < 0) break
                addArc(chain, rings[pos[exit]!!.ring], pos[exit]!!, pos[next]!!, if (forward[exit]) 1 else -1)
                entry = next
            }
            out.add(chain.toLongArray())
        }
        return out
    }

    private fun addSeg(chain: ArrayList<Long>, segs: LongArray, k: Int, reversed: Boolean, skipFirst: Boolean = false) {
        val (ax, ay, bx, by) = if (!reversed) listOf(segs[4 * k], segs[4 * k + 1], segs[4 * k + 2], segs[4 * k + 3])
            else listOf(segs[4 * k + 2], segs[4 * k + 3], segs[4 * k], segs[4 * k + 1])
        if (!skipFirst) { chain.add(ax); chain.add(ay) } else if (chain.size < 2 || chain[chain.size - 2] != ax || chain[chain.size - 1] != ay) { chain.add(ax); chain.add(ay) }
        chain.add(bx); chain.add(by)
    }

    /** Appends the boundary vertices strictly between e and s walking in `dir`, then s itself. */
    private fun addArc(chain: ArrayList<Long>, p: Polygon, e: RingPos, s: RingPos, dir: Int) {
        val m = p.size
        if (dir > 0) {
            // forward from e: vertices e.edge+1 .. s.edge
            if (!(e.edge == s.edge && s.t >= e.t)) {
                var v = (e.edge + 1) % m
                while (true) { chain.add(p.x(v)); chain.add(p.y(v)); if (v == s.edge) break; v = (v + 1) % m }
            }
        } else {
            // backward from e: vertices e.edge .. s.edge+1
            if (!(e.edge == s.edge && s.t <= e.t)) {
                var v = e.edge
                while (true) { chain.add(p.x(v)); chain.add(p.y(v)); if (v == (s.edge + 1) % m) break; v = (v - 1 + m) % m }
            }
        }
    }
}
