package plymeld.geom

/**
 * Boolean operations on polygon sets (see DESIGN.md): split all edges at their
 * intersections ([Arrangement]), decide for every piece whether the fill differs
 * across it (winding numbers of A and B on each side, nonzero rule), orient the kept
 * pieces with the filled side on the left, and stitch them into loops.
 *
 * Coordinates must satisfy |c| <= 2^29: side tests run on doubled coordinates.
 *
 * Both inputs may contain overlapping or self-overlapping rings; the nonzero rule
 * makes them well-defined. Output: outers CCW, holes CW, no collinear vertices.
 */
object Booleans {
    enum class Op { UNION, INTERSECTION, DIFFERENCE, XOR }

    fun union(a: List<Polygon>, b: List<Polygon>) = op(a, b, Op.UNION)
    fun intersection(a: List<Polygon>, b: List<Polygon>) = op(a, b, Op.INTERSECTION)
    fun difference(a: List<Polygon>, b: List<Polygon>) = op(a, b, Op.DIFFERENCE)
    fun xor(a: List<Polygon>, b: List<Polygon>) = op(a, b, Op.XOR)

    /** Resolves self-overlap in one set (nonzero rule): union with nothing. */
    fun normalize(a: List<Polygon>) = op(a, emptyList(), Op.UNION)

    /** Union of one set under the POSITIVE rule (inside = winding > 0): keeps only regions wound CCW. */
    fun unionPositive(a: List<Polygon>): List<Polygon> = op(a, emptyList(), Op.UNION, positiveA = true)

    fun op(a: List<Polygon>, b: List<Polygon>, op: Op, positiveA: Boolean = false): List<Polygon> {
        val kept = keptPieces(a, b, op, null, positiveA)
        val t = System.nanoTime()
        val r = try { assemble(kept) } catch (e: IllegalStateException) {
            // crash dump: the exact operands, replayable with CrashDump.read (then rethrow — never swallow)
            val dir = java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "plymeld-crash")
            java.nio.file.Files.createDirectories(dir)
            val f = dir.resolve("boolean-${System.nanoTime()}.txt")
            java.nio.file.Files.writeString(f, CrashDump.write(a, b, op, positiveA))
            throw IllegalStateException("${e.message}; operands dumped to $f", e)
        }
        Profile.assembleNs.add(System.nanoTime() - t)
        Profile.ops.increment()
        return r
    }

    /** Cheap phase counters (always on; a few nanoTime calls per op). Safe from several threads. */
    object Profile {
        val ops = java.util.concurrent.atomic.LongAdder(); val inputEdges = java.util.concurrent.atomic.LongAdder(); val pieces = java.util.concurrent.atomic.LongAdder()
        val splitNs = java.util.concurrent.atomic.LongAdder(); val windingNs = java.util.concurrent.atomic.LongAdder(); val assembleNs = java.util.concurrent.atomic.LongAdder()
        fun reset() { for (c in listOf(ops, inputEdges, pieces, splitNs, windingNs, assembleNs)) c.reset() }
        fun report() = "ops=${ops.sum()} inputEdges=${inputEdges.sum()} pieces=${pieces.sum()} split=%.0fms winding=%.0fms assemble=%.0fms".format(splitNs.sum() / 1e6, windingNs.sum() / 1e6, assembleNs.sum() / 1e6)
    }

    /** Kept, oriented pieces (x0 y0 x1 y1 ...); `log` receives one line per piece when given. */
    internal fun keptPieces(a: List<Polygon>, b: List<Polygon>, op: Op, log: StringBuilder?, positiveA: Boolean = false): LongArrayListG {
        fun inA(w: Int) = if (positiveA) w > 0 else w != 0
        val t0 = System.nanoTime()
        val input = Arrangement.edgesOf(a, b)
        val pieces = Arrangement.split(input)
        val t1 = System.nanoTime()
        Profile.splitNs.add(t1 - t0); Profile.inputEdges.add(input.size.toLong()); Profile.pieces.add(pieces.size.toLong())
        // Windings are measured against the SPLIT pieces of each input, not its original
        // edges: split points are rounded, so a piece's midpoint can sit a fraction of a
        // grid unit off the original edge and the side test there has an arbitrary sign.
        // Each input's pieces still form closed chains, and every piece contains its own
        // midpoint exactly. (Found by fuzzing against an independent reference: an empty union.)
        val edgesA = subset(pieces, 0)
        val edgesB = subset(pieces, 1)
        val idxA = BandIndex(edgesA)
        val idxB = BandIndex(edgesB)
        val kept = LongArrayListG()
        val seen = HashSet<PieceKey>()   // EXACT coordinates: a hash key once dropped 4 distinct pieces (rig plate)
        for (i in 0 until pieces.size) {
            val p = pieces.p(i); val q = pieces.q(i)
            val wa = windingBoth(edgesA, idxA, p, q); val wb = windingBoth(edgesB, idxB, p, q)
            val wla = (wa shr 32).toInt(); val wra = wa.toInt()
            val wlb = (wb shr 32).toInt(); val wrb = wb.toInt()
            val fl = fill(op, inA(wla), wlb != 0); val fr = fill(op, inA(wra), wrb != 0)
            log?.append("piece $i tag=${pieces.tag[i]} $p->$q  wA(L,R)=($wla,$wra) wB(L,R)=($wlb,$wrb) fill(L,R)=($fl,$fr) ${if (fl == fr) "drop" else if (fl) "keep" else "keep-reversed"}\n")
            if (fl == fr) continue
            val (s, t) = if (fl) p to q else q to p
            if (!seen.add(PieceKey(s.x, s.y, t.x, t.y))) continue   // identical kept piece from the other input
            kept.add(s.x); kept.add(s.y); kept.add(t.x); kept.add(t.y)
        }
        Profile.windingNs.add(System.nanoTime() - t1)
        return kept
    }

    /** Debug: runs the op, writes an SVG of inputs / kept pieces / result, returns a text log. */
    fun explain(a: List<Polygon>, b: List<Polygon>, op: Op, svg: java.nio.file.Path): String {
        val log = StringBuilder()
        val kept = keptPieces(a, b, op, log)
        val keptArr = LongArray(kept.size) { kept[it] }
        val result = assemble(kept)
        log.append("kept pieces: ${kept.size / 4}; result loops: ${result.size}; areas: ${result.map { "%.3f".format(it.signedAreaMm2()) }}\n")
        DebugSvg.write(svg,
            DebugSvg.Layer("A", "#1f77b4", a), DebugSvg.Layer("B", "#ff7f0e", b),
            DebugSvg.Layer("kept pieces", "#d62728", pieces = keptArr), DebugSvg.Layer("result", "#2ca02c", result))
        return log.toString()
    }

    private fun subset(e: Arrangement.Edges, tag: Int): Arrangement.Edges {
        val n = e.tag.count { it == tag }
        val xy = LongArray(4 * n); val t = IntArray(n) { tag }
        var w = 0
        for (i in 0 until e.size) if (e.tag[i] == tag) { System.arraycopy(e.xy, 4 * i, xy, 4 * w, 4); w++ }
        return Arrangement.Edges(xy, t)
    }

    private fun fill(op: Op, inA: Boolean, inB: Boolean) = when (op) {
        Op.UNION -> inA || inB
        Op.INTERSECTION -> inA && inB
        Op.DIFFERENCE -> inA && !inB
        Op.XOR -> inA != inB
    }

    /**
     * Winding number of the polygon set (given as its original edges) at a point
     * infinitesimally to the left/right of the midpoint of p->q. Sunday's crossing
     * sum with a horizontal ray to +x, evaluated with symbolic perturbation: all
     * comparisons are made on doubled coordinates against the midpoint 2m = p + q,
     * with ties broken by the perturbation direction n (the left or right normal).
     */
    internal fun windingBeside(edges: Arrangement.Edges, index: BandIndex, p: IntPoint, q: IntPoint, left: Boolean): Int {
        val mx2 = p.x + q.x; val my2 = p.y + q.y            // doubled midpoint
        val dx = q.x - p.x; val dy = q.y - p.y
        val nx = if (left) -dy else dy                         // normal: left = (-dy, dx), right = (dy, -dx)
        val ny = if (left) dx else -dx
        var w = 0
        val band = index.bandOf2(my2)
        for (k in 0 until band.size) {
            val i = band[k]
            val ux2 = 2 * edges.x0(i); val uy2 = 2 * edges.y0(i)
            val vx2 = 2 * edges.x1(i); val vy2 = 2 * edges.y1(i)
            // uy <= Py  with Py = my + eps*ny
            val uLeq = if (uy2 != my2) uy2 < my2 else ny >= 0
            val vGt = if (vy2 != my2) my2 < vy2 else ny < 0
            val vLeq = if (vy2 != my2) vy2 < my2 else ny >= 0
            val uGt = if (uy2 != my2) my2 < uy2 else ny < 0
            if (uLeq && vGt) {
                // upward edge: count if P is left of u->v
                if (sideOf(ux2, uy2, vx2, vy2, mx2, my2, nx, ny) > 0) w++
            } else if (vLeq && uGt) {
                // downward edge: count if P is right of u->v
                if (sideOf(ux2, uy2, vx2, vy2, mx2, my2, nx, ny) < 0) w--
            }
        }
        return w
    }

    /**
     * Left and right winding in ONE pass over the band (same maths as [windingBeside], both
     * perturbations evaluated per edge). Packed: left in the high 32 bits, right in the low.
     */
    internal fun windingBoth(edges: Arrangement.Edges, index: BandIndex, p: IntPoint, q: IntPoint): Long {
        val mx2 = p.x + q.x; val my2 = p.y + q.y
        val dx = q.x - p.x; val dy = q.y - p.y
        var wl = 0; var wr = 0
        val band = index.bandOf2(my2)
        for (k in 0 until band.size) {
            val i = band[k]
            val ux2 = 2 * edges.x0(i); val uy2 = 2 * edges.y0(i)
            val vx2 = 2 * edges.x1(i); val vy2 = 2 * edges.y1(i)
            // quick reject: edge entirely above or below the midpoint line
            if ((uy2 > my2 && vy2 > my2) || (uy2 < my2 && vy2 < my2)) continue
            for (side in 0..1) {
                val nx = if (side == 0) -dy else dy
                val ny = if (side == 0) dx else -dx
                val uLeq = if (uy2 != my2) uy2 < my2 else ny >= 0
                val vGt = if (vy2 != my2) my2 < vy2 else ny < 0
                val vLeq = if (vy2 != my2) vy2 < my2 else ny >= 0
                val uGt = if (uy2 != my2) my2 < uy2 else ny < 0
                if (uLeq && vGt) { if (sideOf(ux2, uy2, vx2, vy2, mx2, my2, nx, ny) > 0) { if (side == 0) wl++ else wr++ } }
                else if (vLeq && uGt) { if (sideOf(ux2, uy2, vx2, vy2, mx2, my2, nx, ny) < 0) { if (side == 0) wl-- else wr-- } }
            }
        }
        return (wl.toLong() shl 32) or (wr.toLong() and 0xFFFF_FFFFL)
    }

    /** Sign of orientation(u, v, P) for P = m + eps*n, all in doubled coordinates. */
    private fun sideOf(ux: Long, uy: Long, vx: Long, vy: Long, mx: Long, my: Long, nx: Long, ny: Long): Int {
        val ex = vx - ux; val ey = vy - uy
        val o = ex * (my - uy) - ey * (mx - ux)
        if (o != 0L) return if (o > 0) 1 else -1
        val o2 = ex * ny - ey * nx
        return if (o2 > 0) 1 else if (o2 < 0) -1 else 0
    }

    /** Stitch directed pieces (x0 y0 x1 y1 ...) into loops, taking the tightest left turn at junctions. */
    private fun assemble(kept: LongArrayListG): List<Polygon> {
        val m = kept.size / 4
        val starts = HashMap<Long, MutableList<Int>>()
        fun key(x: Long, y: Long) = x * 0x9E3779B97F4A7C15uL.toLong() xor (y + 0x632BE59BD9B4E019L)
        for (s in 0 until m) starts.getOrPut(key(kept[4 * s], kept[4 * s + 1])) { ArrayList(2) }.add(s)
        val used = BooleanArray(m)
        val out = ArrayList<Polygon>()
        val chain = ArrayList<Long>()
        for (s0 in 0 until m) {
            if (used[s0]) continue
            chain.clear()
            var s = s0
            used[s] = true
            chain.add(kept[4 * s]); chain.add(kept[4 * s + 1])
            var closed = false
            while (true) {
                val ex = kept[4 * s + 2]; val ey = kept[4 * s + 3]
                if (ex == kept[4 * s0] && ey == kept[4 * s0 + 1]) { closed = true; break }
                chain.add(ex); chain.add(ey)
                val cands = starts[key(ex, ey)]
                var best = -1; var bestAngle = 0.0
                if (cands != null) {
                    val inx = (ex - kept[4 * s]).toDouble(); val iny = (ey - kept[4 * s + 1]).toDouble()
                    for (t in cands) {
                        if (used[t] || kept[4 * t] != ex || kept[4 * t + 1] != ey) continue
                        val ox = (kept[4 * t + 2] - ex).toDouble(); val oy = (kept[4 * t + 3] - ey).toDouble()
                        // turn angle from incoming to outgoing, in (-pi, pi]; prefer the sharpest left turn
                        val ang = Math.atan2(inx * oy - iny * ox, inx * ox + iny * oy)
                        if (best < 0 || ang > bestAngle) { best = t; bestAngle = ang }
                    }
                }
                if (best < 0) break
                used[best] = true
                s = best
            }
            // A dangling chain means the split or the winding is wrong; dropping it would silently
            // lose a whole polygon (it did: rig plate). Fail loudly instead.
            if (!closed) error("boolean assembly: open chain of ${chain.size / 2} vertices starting at (${chain[0]}, ${chain[1]}) — kernel bug")
            val poly = Polygon(chain.toLongArray()).simplified()
            if (poly.size >= 3 && poly.signedArea2() != 0L) out.add(poly)
        }
        return out
    }
}

/**
 * Edges bucketed into horizontal bands by their closed y-range, so a horizontal ray at
 * height y only visits edges whose y-range could contain y. Band count ~ sqrt(edges).
 */
class BandIndex(edges: Arrangement.Edges) {
    private val y0: Long
    private val h: Long
    private val bands: Array<IntArray>
    init {
        var lo = Long.MAX_VALUE; var hi = Long.MIN_VALUE
        for (i in 0 until edges.size) { lo = minOf(lo, edges.y0(i), edges.y1(i)); hi = maxOf(hi, edges.y0(i), edges.y1(i)) }
        if (edges.size == 0) { lo = 0; hi = 0 }
        val n = Math.max(1, Math.sqrt(edges.size.toDouble()).toInt())
        y0 = 2 * lo
        h = Math.max(1, (2 * hi - 2 * lo) / n + 1)          // doubled units
        val lists = Array(n + 1) { ArrayList<Int>() }
        for (i in 0 until edges.size) {
            val a = ((2 * minOf(edges.y0(i), edges.y1(i)) - y0) / h).toInt()
            val b = ((2 * maxOf(edges.y0(i), edges.y1(i)) - y0) / h).toInt()
            for (k in a..b) lists[k.coerceIn(0, n)].add(i)
        }
        bands = Array(n + 1) { lists[it].toIntArray() }
    }
    private val empty = IntArray(0)
    /** Edges whose y-range may contain the (doubled) height y2. */
    fun bandOf2(y2: Long): IntArray {
        if (y2 < y0) return empty
        val k = ((y2 - y0) / h).toInt()
        return if (k >= bands.size) empty else bands[k]
    }
}

/** Text dump of boolean operands: one polygon per line, "A|B x y x y ...", plus a header. */
object CrashDump {
    fun write(a: List<Polygon>, b: List<Polygon>, op: Booleans.Op, positiveA: Boolean): String = buildString {
        append("op=$op positiveA=$positiveA\n")
        for (p in a) append("A ").append(p.xy.joinToString(" ")).append('\n')
        for (p in b) append("B ").append(p.xy.joinToString(" ")).append('\n')
    }
    class Dump(val a: List<Polygon>, val b: List<Polygon>, val op: Booleans.Op, val positiveA: Boolean)
    fun read(text: String): Dump {
        val lines = text.lines().filter { it.isNotBlank() }
        val head = lines[0]
        val op = Booleans.Op.valueOf(head.substringAfter("op=").substringBefore(' '))
        val pos = head.substringAfter("positiveA=").trim().toBoolean()
        fun poly(l: String) = Polygon(l.substring(2).trim().split(' ').map { it.toLong() }.toLongArray())
        return Dump(lines.drop(1).filter { it.startsWith("A ") }.map(::poly), lines.drop(1).filter { it.startsWith("B ") }.map(::poly), op, pos)
    }
}

private data class PieceKey(val x0: Long, val y0: Long, val x1: Long, val y1: Long)

/** Growable Long list without boxing (geom-local; the mesh module has its own). */
class LongArrayListG(cap: Int = 64) {
    private var a = LongArray(cap)
    var size = 0; private set
    operator fun get(i: Int) = a[i]
    fun add(v: Long) { if (size == a.size) a = a.copyOf(a.size * 2); a[size++] = v }
}
