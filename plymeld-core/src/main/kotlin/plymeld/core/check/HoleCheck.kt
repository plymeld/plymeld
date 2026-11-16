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

    private class Tile {
        val inside = BooleanArray(T * T); val covered = BooleanArray(T * T); val infill = BooleanArray(T * T); val piece = IntArray(T * T)
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
        // the band's depth: the farthest a wall line's bead reaches in from the outline
        var depth = 0.0
        for (s in walls) for (e in 0..1) {
            val d = edges.nearest(if (e == 0) s.x0 else s.x1, if (e == 0) s.y0 else s.y1, DEPTH_MAX_MM)
            depth = maxOf(depth, minOf(d, DEPTH_MAX_MM) + s.bead / 2 + fuzzMm)
        }
        depth += 2 * c
        // the tiles within the depth of an outline edge
        val tiles = HashMap<Long, Tile>()
        fun key(ti: Int, tj: Int) = (ti.toLong() shl 32) or (tj.toLong() and 0xffffffffL)
        val span = c * T
        for (p in loops) for (i in 0 until p.size) {
            val ax = p.x(i) / 1000.0; val ay = p.y(i) / 1000.0; val bx = p.x((i + 1) % p.size) / 1000.0; val by = p.y((i + 1) % p.size) / 1000.0
            for (ti in Math.floor((minOf(ax, bx) - depth) / span).toInt()..Math.floor((maxOf(ax, bx) + depth) / span).toInt())
                for (tj in Math.floor((minOf(ay, by) - depth) / span).toInt()..Math.floor((maxOf(ay, by) + depth) / span).toInt())
                    tiles.getOrPut(key(ti, tj)) { Tile() }
        }
        var lastKey = Long.MIN_VALUE; var lastTile: Tile? = null
        fun tileAt(i: Int, j: Int): Tile? {
            val k = key(Math.floorDiv(i, T), Math.floorDiv(j, T))
            if (k != lastKey) { lastKey = k; lastTile = tiles[k] }
            return lastTile
        }
        fun at(i: Int, j: Int) = Math.floorMod(j, T) * T + Math.floorMod(i, T)
        val tileRows = tiles.keys.mapTo(HashSet()) { (it and 0xffffffffL).toInt() }
        fun tilesInRow(j: Int) = Math.floorDiv(j, T) in tileRows
        /** Cells [i0, i1] of row [j], tile by tile where a tile is: [f] with the tile and the cells' first and last index in it. */
        fun rowCells(i0: Int, i1: Int, j: Int, f: (Tile, Int, Int) -> Unit) {
            if (i0 > i1) return
            val tj = Math.floorDiv(j, T); val off = Math.floorMod(j, T) * T
            for (ti in Math.floorDiv(i0, T)..Math.floorDiv(i1, T)) {
                val t = tiles[key(ti, tj)] ?: continue
                f(t, off + maxOf(i0, ti * T) - ti * T, off + minOf(i1, ti * T + T - 1) - ti * T)
            }
        }
        // inside: row by row, the cut's crossings (each edge in the rows it spans)
        val rowEdges = HashMap<Int, ArrayList<DoubleArray>>()
        for (p in loops) for (i in 0 until p.size) {
            val ax = p.x(i) / 1000.0; val ay = p.y(i) / 1000.0; val bx = p.x((i + 1) % p.size) / 1000.0; val by = p.y((i + 1) % p.size) / 1000.0
            if (ay == by) continue
            for (j in Math.ceil(minOf(ay, by) / c - 0.5).toInt()..Math.floor(maxOf(ay, by) / c - 0.5).toInt()) {
                val yc = (j + 0.5) * c
                if ((ay > yc) != (by > yc)) rowEdges.getOrPut(j) { ArrayList() } += doubleArrayOf(ax, ay, bx, by)
            }
        }
        val rowsOfTiles = tileRows.toSortedSet()
        for (tj in rowsOfTiles) for (j in tj * T until tj * T + T) {
            val es = rowEdges[j] ?: continue
            val yc = (j + 0.5) * c
            val xs = DoubleArray(es.size) { k -> val e = es[k]; e[0] + (yc - e[1]) / (e[3] - e[1]) * (e[2] - e[0]) }
            xs.sort()
            var k = 0
            while (k + 1 < xs.size) {
                rowCells(Math.ceil(xs[k] / c - 0.5).toInt(), Math.floor(xs[k + 1] / c - 0.5).toInt(), j) { t, q0, q1 -> for (q in q0..q1) t.inside[q] = !t.inside[q] }
                k += 2
            }
        }
        // the lines' beads
        val out = DoubleArray(2)
        for (s in segs) {
            val r = s.bead / 2 + (if (s.infill) 0.0 else fuzzMm)
            if (r <= 0) continue
            for (j in Math.floor((minOf(s.y0, s.y1) - r) / c - 0.5).toInt()..Math.ceil((maxOf(s.y0, s.y1) + r) / c - 0.5).toInt()) {
                if (!tilesInRow(j)) continue
                if (!plymeld.core.IslandCheck.capsuleSpan(s.x0, s.y0, s.x1, s.y1, r, (j + 0.5) * c, out)) continue
                rowCells(Math.ceil(out[0] / c - 0.5).toInt(), Math.floor(out[1] / c - 0.5).toInt(), j) { t, q0, q1 ->
                    for (q in q0..q1) { t.covered[q] = true; if (s.infill) t.infill[q] = true } }
            }
        }
        // the uncovered pieces, each read once
        var best: Hole? = null
        var next = 0
        val stack = ArrayDeque<Long>()
        val cells = ArrayList<Long>()
        fun cellKey(i: Int, j: Int) = (i.toLong() shl 32) or (j.toLong() and 0xffffffffL)
        for ((tk, tile) in tiles) {
            val ti = (tk shr 32).toInt(); val tj = (tk and 0xffffffffL).toInt()
            for (q in 0 until T * T) {
                if (!tile.inside[q] || tile.covered[q] || tile.piece[q] != 0) continue
                val id = ++next
                var open = false
                cells.clear(); stack.clear()
                tile.piece[q] = id; stack.add(cellKey(ti * T + q % T, tj * T + q / T))
                while (stack.isNotEmpty()) {
                    val ck = stack.removeLast(); cells += ck
                    val i = (ck shr 32).toInt(); val j = (ck and 0xffffffffL).toInt()
                    for (d in 0 until 8) {
                        val ni = i + DI[d]; val nj = j + DJ[d]
                        val nt = tileAt(ni, nj)
                        if (nt == null) { open = true; continue }
                        val nq = at(ni, nj)
                        if (!nt.inside[nq]) { open = true; continue }
                        if (nt.covered[nq]) { if (nt.infill[nq]) open = true; continue }
                        if (d < 4 && nt.piece[nq] == 0) { nt.piece[nq] = id; stack.add(cellKey(ni, nj)) }
                    }
                }
                if (open) continue
                // its width: the farthest its cells lie from a cell not in it (searched out to its own extent)
                var far = 0.0; var fx = 0; var fy = 0
                val limit = Math.ceil(Math.sqrt(cells.size.toDouble())).toInt() + 1
                for (ck in cells) {
                    val i = (ck shr 32).toInt(); val j = (ck and 0xffffffffL).toInt()
                    val d = nearestOut(i, j, limit) { a, b -> tileAt(a, b)?.let { it.piece[at(a, b)] == id } == true }
                    if (d > far) { far = d; fx = i; fy = j }
                }
                val width = 2 * far * c
                if (width >= infillMinMm) continue
                if (best == null || width > best.widthMm) best = Hole(width, (fx + 0.5) * c, (fy + 0.5) * c, cells.size * c * c)
            }
        }
        return best
    }
    private val DI = intArrayOf(1, -1, 0, 0, 1, 1, -1, -1)
    private val DJ = intArrayOf(0, 0, 1, -1, 1, -1, 1, -1)

    /** The distance (cells, between centers) from cell (i, j) to the nearest cell not [inPiece], searched out to [limit]. */
    private inline fun nearestOut(i: Int, j: Int, limit: Int, inPiece: (Int, Int) -> Boolean): Double {
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
