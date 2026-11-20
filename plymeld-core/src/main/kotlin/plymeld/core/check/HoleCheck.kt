package plymeld.core.check

import plymeld.geom.Polygon

/**
 * The pre-print check's hole rule (ModelCheck): a spot of a layer's cross-section no line covers, closed in by the walls,
 * wider than the narrowest place a line is laid in. The bare-outline rule probes the outline; a hole inside the walls (a
 * lens laid as a loop and a line down its middle, the loop's turn and the line's end leaving 0.4 x 0.25 mm between them on
 * layer after layer) left the outline laid and passed.
 *
 * Read on a grid of [CELL_MM] cells over the band the walls lie in (within the depth of the layer's farthest wall line from
 * the outline, so a narrow island whole): each line of the model a bead of its width from its plastic (ModelCheck.Seg.bead)
 * round its center line; a wall line wider by the fuzzy skin's thickness a side where fuzzy skin is on (its points move in
 * and out by up to that); the uncovered cells in pieces. A piece is a hole when it is closed in by walls alone: it reaches
 * no cell outside the cross-section (that is the outline's, the bare-outline rule's, a first layer's drawn-in edge), no
 * infill or surface line (the gaps an infill leaves, and the corners between it and the walls, are by design), and not the
 * edge of the band (what lies deeper is infill's). Its width: twice the farthest its cells lie from a cell not in it; a
 * piece at least as wide as the walls hand to infill (two inner lines) is the infill region's (a stretch of it no line of a
 * sparse infill crosses on this layer: a two-part ridge's foot, 1.85 mm wide between its walls, read as a hole).
 */
internal object HoleCheck {
    /** The grid's cell (mm): plymeld's choice (a hole the narrowest line's width is a few cells across). */
    const val CELL_MM = 0.025
    /** The deepest band read (mm): plymeld's choice (time; a wall laid deeper is the band's edge there). */
    private const val DEPTH_MAX_MM = 5.0
    /** A tile's side (cells). */
    private const val T = 32

    /** The widest hole: its width (mm, to about a cell), its widest point, its area (mm2). */
    class Hole(val widthMm: Double, val x: Double, val y: Double, val areaMm2: Double)

    /**
     * A tile's cells, a byte of flags each ([INSIDE], [COVERED], [INFILL], [SEEN], [CUR]): a byte a cell, so a layer's band
     * (a two-part model's walls between its parts lie deep inside its outline: the band is most of the cross-section, a few
     * thousand tiles) is a few MB while every core reads a layer of its own.
     */
    private class Tile { val f = ByteArray(T * T) }
    /** In the cross-section. */
    private const val INSIDE = 1
    /** Under a line's bead. */
    private const val COVERED = 2
    /** Under an infill's or a surface's bead. */
    private const val INFILL = 4
    /** Taken into a piece. */
    private const val SEEN = 8
    /** In the piece being read while it may be a hole (its cells for its width; cleared once it is read). */
    private const val CUR = 16

    /** Cell (i, j)'s index in its tile. */
    private fun at(i: Int, j: Int) = Math.floorMod(j, T) * T + Math.floorMod(i, T)

    /**
     * A layer's band: [nti] x [ntj] tiles from tile ([ti0], [tj0]) over the layer's own extent, a tile made only where the
     * band is (an array of references, a few thousand; the cells are the tiles').
     */
    private class Raster(val ti0: Int, val tj0: Int, val nti: Int, val ntj: Int) {
        val tiles = arrayOfNulls<Tile>(nti * ntj)
        /** Whether a row of tiles has any. */
        val rowHas = BooleanArray(ntj)
        fun make(ti: Int, tj: Int) { val k = (tj - tj0) * nti + (ti - ti0); if (tiles[k] == null) { tiles[k] = Tile(); rowHas[tj - tj0] = true } }
        fun tile(ti: Int, tj: Int): Tile? { val a = ti - ti0; val b = tj - tj0; return if (a < 0 || b < 0 || a >= nti || b >= ntj) null else tiles[b * nti + a] }
        fun tileAt(i: Int, j: Int): Tile? = tile(Math.floorDiv(i, T), Math.floorDiv(j, T))
        /** Whether row [j] of cells crosses a tile. */
        fun rowHasTiles(j: Int): Boolean { val b = Math.floorDiv(j, T) - tj0; return b in 0 until ntj && rowHas[b] }
        /** Cells [i0, i1] of row [j] where a tile is: each one's flags xored with [x], then or'ed with [o]. */
        fun apply(i0: Int, i1: Int, j: Int, x: Int, o: Int) {
            if (i0 > i1) return
            val tj = Math.floorDiv(j, T); val off = Math.floorMod(j, T) * T
            for (ti in maxOf(Math.floorDiv(i0, T), ti0)..minOf(Math.floorDiv(i1, T), ti0 + nti - 1)) {
                val t = tile(ti, tj) ?: continue
                for (q in off + maxOf(i0, ti * T) - ti * T..off + minOf(i1, ti * T + T - 1) - ti * T) t.f[q] = ((t.f[q].toInt() xor x) or o).toByte()
            }
        }
    }

    /** A stack of cell keys in a primitive array (a piece can be a whole infill region's hundreds of thousands of cells). */
    private class LongStack {
        var a = LongArray(256); var size = 0
        fun push(v: Long) { if (size == a.size) a = a.copyOf(size * 2); a[size++] = v }
        fun pop(): Long = a[--size]
        operator fun get(i: Int) = a[i]
        fun clear() { size = 0 }
    }

    /**
     * The widest hole of the layer cut [loops] (µm) its lines [segs] leave; [edges]: the cut's outline (mm); null: none.
     * [infillMinMm]: the narrowest area the walls hand to infill (two inner lines): a piece at least that wide is the infill
     * region's (a stretch of it no infill line crosses on this layer: a sparse infill's gaps are as wide as its spacing).
     */
    fun widest(loops: List<Polygon>, segs: List<ModelCheck.Seg>, edges: ModelCheck.Grid, fuzzMm: Double, infillMinMm: Double = Double.MAX_VALUE): Hole? {
        val walls = segs.filter { !it.infill }
        if (walls.isEmpty() || loops.isEmpty()) return null
        val c = CELL_MM
        // the band's depth: the farthest a wall line's bead reaches in from the outline. An end with an edge nearer than the
        // depth so far less its bead leaves it as it is: read within that first (most ends; the search's cost, and the boxed
        // keys of its lookups, go as its radius squared)
        var depth = 0.0
        for (s in walls) for (e in 0..1) {
            val x = if (e == 0) s.x0 else s.x1; val y = if (e == 0) s.y0 else s.y1
            val within = depth - s.bead / 2 - fuzzMm
            if (within > 0 && edges.nearest(x, y, within) <= within) continue
            depth = maxOf(depth, minOf(edges.nearest(x, y, DEPTH_MAX_MM), DEPTH_MAX_MM) + s.bead / 2 + fuzzMm)
        }
        depth += 2 * c
        // the tiles within the depth of an outline edge, on a grid of tiles over the layer's own extent (its outline and the
        // depth round it)
        val span = c * T
        var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
        for (p in loops) for (i in 0 until p.size) { val x = p.x(i) / 1000.0; val y = p.y(i) / 1000.0; x0 = minOf(x0, x); x1 = maxOf(x1, x); y0 = minOf(y0, y); y1 = maxOf(y1, y) }
        val ti0 = Math.floor((x0 - depth) / span).toInt(); val tj0 = Math.floor((y0 - depth) / span).toInt()
        val r = Raster(ti0, tj0, Math.floor((x1 + depth) / span).toInt() - ti0 + 1, Math.floor((y1 + depth) / span).toInt() - tj0 + 1)
        for (p in loops) for (i in 0 until p.size) {
            val ax = p.x(i) / 1000.0; val ay = p.y(i) / 1000.0; val bx = p.x((i + 1) % p.size) / 1000.0; val by = p.y((i + 1) % p.size) / 1000.0
            for (ti in Math.floor((minOf(ax, bx) - depth) / span).toInt()..Math.floor((maxOf(ax, bx) + depth) / span).toInt())
                for (tj in Math.floor((minOf(ay, by) - depth) / span).toInt()..Math.floor((maxOf(ay, by) + depth) / span).toInt())
                    r.make(ti, tj)
        }
        // inside: row by row, the cut's crossings (each edge, one array, in the rows it spans)
        val rowEdges = HashMap<Int, ArrayList<DoubleArray>>()
        for (p in loops) for (i in 0 until p.size) {
            val ax = p.x(i) / 1000.0; val ay = p.y(i) / 1000.0; val bx = p.x((i + 1) % p.size) / 1000.0; val by = p.y((i + 1) % p.size) / 1000.0
            if (ay == by) continue
            val e = doubleArrayOf(ax, ay, bx, by)
            for (j in Math.ceil(minOf(ay, by) / c - 0.5).toInt()..Math.floor(maxOf(ay, by) / c - 0.5).toInt()) {
                val yc = (j + 0.5) * c
                if ((ay > yc) != (by > yc)) rowEdges.getOrPut(j) { ArrayList() } += e
            }
        }
        for (b in 0 until r.ntj) if (r.rowHas[b]) for (j in (b + tj0) * T until (b + tj0) * T + T) {
            val es = rowEdges[j] ?: continue
            val yc = (j + 0.5) * c
            val xs = DoubleArray(es.size) { k -> val e = es[k]; e[0] + (yc - e[1]) / (e[3] - e[1]) * (e[2] - e[0]) }
            xs.sort()
            var k = 0
            while (k + 1 < xs.size) {
                r.apply(Math.ceil(xs[k] / c - 0.5).toInt(), Math.floor(xs[k + 1] / c - 0.5).toInt(), j, INSIDE, 0)
                k += 2
            }
        }
        // the lines' beads
        val out = DoubleArray(2)
        for (s in segs) {
            val rad = s.bead / 2 + (if (s.infill) 0.0 else fuzzMm)
            if (rad <= 0) continue
            val add = if (s.infill) COVERED or INFILL else COVERED
            for (j in Math.floor((minOf(s.y0, s.y1) - rad) / c - 0.5).toInt()..Math.ceil((maxOf(s.y0, s.y1) + rad) / c - 0.5).toInt()) {
                if (!r.rowHasTiles(j)) continue
                if (!plymeld.core.IslandCheck.capsuleSpan(s.x0, s.y0, s.x1, s.y1, rad, (j + 0.5) * c, out)) continue
                r.apply(Math.ceil(out[0] / c - 0.5).toInt(), Math.floor(out[1] / c - 0.5).toInt(), j, 0, add)
            }
        }
        // the uncovered pieces, each read once. A piece's cells are kept (marked CUR, listed) only while it may be a hole:
        // once it is open, those marks are cleared and the rest of it is only marked SEEN (an infill region's piece is most
        // of the band)
        var best: Hole? = null
        val stack = LongStack()
        val cells = LongStack()
        fun cellKey(i: Int, j: Int) = (i.toLong() shl 32) or (j.toLong() and 0xffffffffL)
        fun unmark(ck: Long) { val i = (ck shr 32).toInt(); val j = (ck and 0xffffffffL).toInt(); val t = r.tileAt(i, j)!!; val q = at(i, j)
            t.f[q] = (t.f[q].toInt() and CUR.inv()).toByte() }
        for (b in 0 until r.ntj) for (a in 0 until r.nti) {
            val tile = r.tiles[b * r.nti + a] ?: continue
            val ti = a + ti0; val tj = b + tj0
            for (q in 0 until T * T) {
                val fq = tile.f[q].toInt()
                if (fq and INSIDE == 0 || fq and COVERED != 0 || fq and SEEN != 0) continue
                var open = false
                cells.clear(); stack.clear()
                tile.f[q] = (fq or SEEN or CUR).toByte(); stack.push(cellKey(ti * T + q % T, tj * T + q / T))
                while (stack.size > 0) {
                    val ck = stack.pop()
                    if (open) unmark(ck) else cells.push(ck)
                    val i = (ck shr 32).toInt(); val j = (ck and 0xffffffffL).toInt()
                    for (d in 0 until 8) {
                        val ni = i + DI[d]; val nj = j + DJ[d]
                        val nt = r.tileAt(ni, nj)
                        if (nt == null) { open = true; continue }
                        val nq = at(ni, nj)
                        val nf = nt.f[nq].toInt()
                        if (nf and INSIDE == 0) { open = true; continue }
                        if (nf and COVERED != 0) { if (nf and INFILL != 0) open = true; continue }
                        if (d < 4 && nf and SEEN == 0) { nt.f[nq] = (nf or SEEN or (if (open) 0 else CUR)).toByte(); stack.push(cellKey(ni, nj)) }
                    }
                    // just found open: what it has kept is no longer a candidate
                    if (open && cells.size > 0) { for (k in 0 until cells.size) unmark(cells[k]); cells.clear() }
                }
                if (open) continue
                // its width: the farthest its cells lie from a cell not in it (searched out to its own extent)
                var far = 0.0; var fx = 0; var fy = 0
                val limit = Math.ceil(Math.sqrt(cells.size.toDouble())).toInt() + 1
                for (k in 0 until cells.size) {
                    val ck = cells[k]
                    val i = (ck shr 32).toInt(); val j = (ck and 0xffffffffL).toInt()
                    val d = nearestOut(i, j, limit) { pi, pj -> val t = r.tileAt(pi, pj); t != null && t.f[at(pi, pj)].toInt() and CUR != 0 }
                    if (d > far) { far = d; fx = i; fy = j }
                }
                val n = cells.size
                for (k in 0 until n) unmark(cells[k])
                val width = 2 * far * c
                if (width >= infillMinMm) continue
                if (best == null || width > best.widthMm) best = Hole(width, (fx + 0.5) * c, (fy + 0.5) * c, n * c * c)
            }
        }
        return best
    }
    private val DI = intArrayOf(1, -1, 0, 0, 1, 1, -1, -1)
    private val DJ = intArrayOf(0, 0, 1, -1, 1, -1, 1, -1)

    /** The distance (cells, between centers) from cell (i, j) to the nearest cell not [inPiece], searched out to [limit]. */
    internal inline fun nearestOut(i: Int, j: Int, limit: Int, inPiece: (Int, Int) -> Boolean): Double {
        var best2 = Long.MAX_VALUE
        var k = 1
        while (k <= limit) {
            for (dj in -k..k) {
                val step = if (dj == -k || dj == k) 1 else 2 * k
                var di = -k
                while (di <= k) {
                    if (!inPiece(i + di, j + dj)) { val d2 = di.toLong() * di + dj.toLong() * dj; if (d2 < best2) best2 = d2 }
                    di += step
                }
            }
            if (best2 != Long.MAX_VALUE && k.toLong() * k >= best2) break
            k++
        }
        return if (best2 == Long.MAX_VALUE) (limit + 1).toDouble() else Math.sqrt(best2.toDouble())
    }
}
