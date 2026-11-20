package plymeld.check

import plymeld.geom.Polygon
import java.util.Locale

/**
 * Reads a layer's lines back from the g-code and measures them against the model's own cross-section (cut from the mesh by
 * the test, never the planner's outline): what a reading of walls by their local width needs (WallsByWidthTest).
 *
 * Reading: every extruding move (G1, and G2 / G3 arcs cut into chords of at most [CHORD_MM]) of the model's features, its
 * bead's width from its plastic per mm and the layer height the file states (the bead model: a cross-section of
 * h (w - h (1 - pi / 4))), its path (consecutive extruding moves of one feature and tool, each starting where the last
 * ended; a travel, a retraction, or a change of feature or tool ends it) and its distance along that path.
 *
 * Measuring one layer ([measure]) on a grid of [Window.cellMm] cells over the cross-section and a margin:
 *  - uncovered: cells inside the cross-section no bead covers (each bead a capsule of its width round its move), mm²; the
 *    widest uncovered spot (the widest disc that fits in what is uncovered, mm); and the uncovered area in places wider
 *    than [floorMm] (the uncovered cells within reach of a disc that wide fitting in the uncovered set);
 *  - outside: cells outside the cross-section a bead covers, mm², and how far the farthest lies from it;
 *  - paths: how many, the shortest (mm), the shortest move (mm; an arc's chords together as its one move);
 *  - doubled: cells covered by two beads (two paths, or one path's places more than 1.5 of its widths apart along it) once
 *    each bead is narrowed by half the usual overlap of two touching beads (h (1 - pi / 4)) and the slicing tolerance a
 *    side, an infill line's also by what it reaches into the walls: overlap beyond the usual, mm².
 * [shift]: how far one layer's lines lie from the layer below's (each line sampled every 0.02 mm inside the window, its
 * distance to the nearest line of the other layer; the larger of the two ways, mm).
 *
 * The grid and the margins are the test's; nothing here is a print value.
 */
object WallCoverage {
    /** An arc is read as chords no longer than this (mm). */
    const val CHORD_MM = 0.01
    /** The model's own lines (not the purge tower, brim, support or the start template's). */
    val MODEL = setOf("Outer wall", "Inner wall", "Overhang wall", "Sparse infill", "Internal solid infill", "Solid infill",
        "Top surface", "Bottom surface", "Bridge", "Internal Bridge", "Gap infill")
    private val INFILL = setOf("Sparse infill", "Internal solid infill", "Solid infill", "Top surface", "Bottom surface", "Bridge", "Internal Bridge")

    /** One straight piece of a bead (mm): its path, where along the path it starts, its g-code move. */
    class Seg(val x0: Double, val y0: Double, val x1: Double, val y1: Double, val w: Double, val feature: String, val tool: Int,
              val path: Int, val s0: Double, val move: Int) {
        val length get() = Math.hypot(x1 - x0, y1 - y0)
    }
    class Path(val id: Int, val tool: Int, val feature: String, var length: Double = 0.0, var closed: Boolean = false)
    class Layer(val z: Double, val h: Double, val segs: List<Seg>)
    class Read(val layers: List<Layer>, val paths: List<Path>, val moveLength: DoubleArray)

    /** [g]'s model lines, layer by layer ([diameterMm], [flow]: the filament's, for the plastic's width). */
    fun read(g: String, diameterMm: Double, flow: Double): Read {
        val af = Math.PI * diameterMm * diameterMm / 4
        val layers = ArrayList<Layer>(); val paths = ArrayList<Path>(); val moveLen = ArrayList<Double>()
        var segs = ArrayList<Seg>(); var z = Double.NaN; var h = Double.NaN; var started = false
        var x = Double.NaN; var y = Double.NaN; var e = 0.0; var relE = true
        var feature = ""; var tool = 0
        var path: Path? = null; var px = Double.NaN; var py = Double.NaN
        fun endPath() {
            val p = path ?: return
            val first = segs.firstOrNull { it.path == p.id }
            if (first != null) p.closed = Math.hypot(first.x0 - px, first.y0 - py) < 1e-6
            path = null
        }
        fun flush() { endPath(); if (started && !z.isNaN()) layers += Layer(z, h, segs); segs = ArrayList() }
        for (raw in g.lineSequence()) {
            val l = raw.trim()
            if (l.isEmpty()) continue
            if (l[0] == ';') {
                when {
                    l.startsWith(";LAYER_CHANGE") -> { flush(); started = true }
                    l.startsWith(";Z:") -> z = l.substringAfter(':').trim().toDouble()
                    l.startsWith(";HEIGHT:") -> h = l.substringAfter(':').trim().toDouble()
                    l.startsWith(";TYPE:") -> { if (l.substringAfter(':').trim() != feature) endPath(); feature = l.substringAfter(':').trim() }
                }
                continue
            }
            val c = l.substringBefore(';').trim()
            if (c.isEmpty()) continue
            val ws = c.split(' ', '\t').filter { it.isNotEmpty() }
            val op = ws[0].uppercase()
            if (Regex("T[0-9]+").matches(op)) { endPath(); tool = op.substring(1).toInt(); continue }
            when (op) { "M82" -> { relE = false; continue }; "M83" -> { relE = true; continue }
                "G92" -> { ws.drop(1).firstOrNull { it[0] == 'E' }?.let { e = it.substring(1).toDouble() }; continue } }
            val arc = op == "G2" || op == "G3"
            if (op != "G0" && op != "G1" && !arc) continue
            fun num(ch: Char) = ws.drop(1).firstOrNull { it[0].uppercaseChar() == ch }?.substring(1)?.toDoubleOrNull()
            val nx = num('X') ?: x; val ny = num('Y') ?: y
            val ne = num('E')
            val de = when { ne == null -> 0.0; relE -> ne; else -> ne - e }
            if (ne != null) e = if (relE) e + ne else ne
            val moved = nx != x || ny != y || arc
            if (started && de > 0 && moved && !x.isNaN() && feature in MODEL && !h.isNaN()) {
                // the chords of this move
                val pts = ArrayList<DoubleArray>()
                if (arc) {
                    val cx = x + (num('I') ?: 0.0); val cy = y + (num('J') ?: 0.0); val r = Math.hypot(x - cx, y - cy)
                    val a0 = Math.atan2(y - cy, x - cx); val a1 = Math.atan2(ny - cy, nx - cx)
                    val cw = op == "G2"
                    var sweep = if (cw) a0 - a1 else a1 - a0
                    while (sweep <= 1e-12) sweep += 2 * Math.PI
                    val n = maxOf(1, Math.ceil(sweep * r / CHORD_MM).toInt())
                    pts += doubleArrayOf(x, y)
                    for (k in 1..n) { val a = a0 + (if (cw) -sweep else sweep) * k / n
                        pts += if (k == n) doubleArrayOf(nx, ny) else doubleArrayOf(cx + r * Math.cos(a), cy + r * Math.sin(a)) }
                } else { pts += doubleArrayOf(x, y); pts += doubleArrayOf(nx, ny) }
                val len = (1 until pts.size).sumOf { Math.hypot(pts[it][0] - pts[it - 1][0], pts[it][1] - pts[it - 1][1]) }
                if (len > 0) {
                    // a new path unless this move starts where the path's last ended
                    if (path == null || path!!.tool != tool || Math.hypot(px - x, py - y) > 1e-6) {
                        endPath(); path = Path(paths.size, tool, feature).also { paths += it }
                    }
                    val p = path!!
                    val w = (de * af / (len * flow)) / h + h * (1 - Math.PI / 4)
                    val mi = moveLen.size; moveLen += len
                    for (k in 1 until pts.size) {
                        val s = Seg(pts[k - 1][0], pts[k - 1][1], pts[k][0], pts[k][1], w, feature, tool, p.id, p.length, mi)
                        segs += s; p.length += s.length
                    }
                    px = nx; py = ny
                }
            } else if (moved || de < 0) endPath()
            x = nx; y = ny
        }
        flush()
        return Read(layers, paths, moveLen.toDoubleArray())
    }

    /** The region measured (mm) and the grid's cell. */
    class Window(val x0: Double, val y0: Double, val x1: Double, val y1: Double, val cellMm: Double = 0.005) {
        companion object {
            /** Round [section] (µm) with [marginMm] about it. */
            fun around(section: List<Polygon>, marginMm: Double, cellMm: Double = 0.005): Window {
                var a = Long.MAX_VALUE; var b = Long.MAX_VALUE; var c = Long.MIN_VALUE; var d = Long.MIN_VALUE
                for (p in section) { val bb = p.bounds(); a = minOf(a, bb[0]); b = minOf(b, bb[1]); c = maxOf(c, bb[2]); d = maxOf(d, bb[3]) }
                return Window(a / 1000.0 - marginMm, b / 1000.0 - marginMm, c / 1000.0 + marginMm, d / 1000.0 + marginMm, cellMm)
            }
        }
    }

    class Measure(val z: Double, val uncoveredMm2: Double, val widestUncoveredMm: Double, val uncoveredWideMm2: Double,
                  val outsideMm2: Double, val outsideMaxMm: Double, val paths: Int, val shortestPathMm: Double, val shortestMoveMm: Double,
                  val doubledMm2: Double) {
        override fun toString() = String.format(Locale.ROOT,
            "z %.2f: uncovered %.4f mm2 (widest %.3f mm, %.4f mm2 wider than the floor), outside %.4f mm2 (farthest %.3f mm), %d paths (shortest %.2f mm, shortest move %.3f mm), doubled %.4f mm2",
            z, uncoveredMm2, widestUncoveredMm, uncoveredWideMm2, outsideMm2, outsideMaxMm, paths, shortestPathMm, shortestMoveMm, doubledMm2)
    }

    /**
     * [layer]'s lines of [tool] (all tools: null) in [win] against [section] (µm, the model's cut at the layer's middle);
     * [floorMm]: the narrowest place a line is laid in; [tolMm]: the slicing tolerance; [infillOverlap]: what an infill line
     * reaches into the walls, a share of its width.
     */
    fun measure(read: Read, layer: Layer, section: List<Polygon>, win: Window, tool: Int?, floorMm: Double, tolMm: Double, infillOverlap: Double,
                /** A picture of the cells to write (inside covered gray, inside uncovered red, outside covered orange, doubled blue); null: none. */
                map: java.nio.file.Path? = null): Measure {
        val cell = win.cellMm
        val nx = Math.ceil((win.x1 - win.x0) / cell).toInt(); val ny = Math.ceil((win.y1 - win.y0) / cell).toInt()
        val n = nx * ny
        // inside the section: even-odd by rows
        val inside = BooleanArray(n)
        for (j in 0 until ny) {
            val yc = (win.y0 + (j + 0.5) * cell) * 1000
            val xs = ArrayList<Double>()
            for (p in section) for (i in 0 until p.size) {
                val ax = p.x(i).toDouble(); val ay = p.y(i).toDouble(); val bx = p.x((i + 1) % p.size).toDouble(); val by = p.y((i + 1) % p.size).toDouble()
                if ((ay > yc) != (by > yc)) xs += ax + (yc - ay) / (by - ay) * (bx - ax)
            }
            xs.sort()
            var k = 0
            while (k + 1 < xs.size) {
                val i0 = maxOf(0, Math.ceil((xs[k] / 1000 - win.x0) / cell - 0.5).toInt()); val i1 = minOf(nx - 1, Math.floor((xs[k + 1] / 1000 - win.x0) / cell - 0.5).toInt())
                for (i in i0..i1) inside[j * nx + i] = true
                k += 2
            }
        }
        val segs = layer.segs.filter { (tool == null || it.tool == tool) &&
            maxOf(it.x0, it.x1) + it.w >= win.x0 && minOf(it.x0, it.x1) - it.w <= win.x1 && maxOf(it.y0, it.y1) + it.w >= win.y0 && minOf(it.y0, it.y1) - it.w <= win.y1 }
        val covered = BooleanArray(n)
        val firstPath = IntArray(n) { -1 }; val firstS = DoubleArray(n); val firstW = DoubleArray(n); val doubled = BooleanArray(n)
        val flank = layer.h * (1 - Math.PI / 4)
        fun capsule(s: Seg, r: Double, mark: (Int, Double) -> Unit) {
            if (r <= 0) return
            val i0 = maxOf(0, Math.floor((minOf(s.x0, s.x1) - r - win.x0) / cell).toInt()); val i1 = minOf(nx - 1, Math.ceil((maxOf(s.x0, s.x1) + r - win.x0) / cell).toInt())
            val j0 = maxOf(0, Math.floor((minOf(s.y0, s.y1) - r - win.y0) / cell).toInt()); val j1 = minOf(ny - 1, Math.ceil((maxOf(s.y0, s.y1) + r - win.y0) / cell).toInt())
            val dx = s.x1 - s.x0; val dy = s.y1 - s.y0; val l2 = dx * dx + dy * dy
            for (j in j0..j1) for (i in i0..i1) {
                val px = win.x0 + (i + 0.5) * cell; val py = win.y0 + (j + 0.5) * cell
                val u = if (l2 == 0.0) 0.0 else (((px - s.x0) * dx + (py - s.y0) * dy) / l2).coerceIn(0.0, 1.0)
                if (Math.hypot(px - s.x0 - u * dx, py - s.y0 - u * dy) <= r) mark(j * nx + i, s.s0 + u * Math.sqrt(l2))
            }
        }
        for (s in segs) {
            capsule(s, s.w / 2) { c, _ -> covered[c] = true }
            val narrow = flank / 2 + tolMm + (if (s.feature in INFILL) infillOverlap * s.w else 0.0)
            val pl = read.paths[s.path]
            capsule(s, s.w / 2 - narrow) { c, at ->
                val f = firstPath[c]
                if (f < 0) { firstPath[c] = s.path; firstS[c] = at; firstW[c] = s.w }
                else if (f != s.path) doubled[c] = true
                else {
                    var d = Math.abs(at - firstS[c]); if (pl.closed) d = minOf(d, pl.length - d)
                    if (d > 1.5 * maxOf(s.w, firstW[c])) doubled[c] = true
                }
            }
        }
        val area = cell * cell
        var unc = 0; var out = 0; var dbl = 0
        val uncovered = BooleanArray(n) { inside[it] && !covered[it] }
        for (c in 0 until n) { if (uncovered[c]) unc++; if (covered[c] && !inside[c]) out++; if (doubled[c]) dbl++ }
        // the widest uncovered spot: twice the largest distance from an uncovered cell to a cell that is not
        val dUnc = edt(uncovered, nx, ny)
        var widest = 0.0
        for (c in 0 until n) if (uncovered[c]) widest = maxOf(widest, dUnc[c])
        // the uncovered area wider than the floor: within reach of a cell where a disc that wide fits
        val r = floorMm / 2 / cell
        val core = BooleanArray(n) { uncovered[it] && dUnc[it] >= r }
        val dCore = edt(BooleanArray(n) { !core[it] }, nx, ny)
        var wide = 0
        for (c in 0 until n) if (uncovered[c] && dCore[c] <= r) wide++
        // how far outside: the distance from a covered cell outside to the nearest cell inside
        val dOut = edt(BooleanArray(n) { !inside[it] }, nx, ny)
        var farthest = 0.0
        for (c in 0 until n) if (covered[c] && !inside[c]) farthest = maxOf(farthest, dOut[c])
        if (map != null) {
            val img = java.awt.image.BufferedImage(nx, ny, java.awt.image.BufferedImage.TYPE_INT_RGB)
            for (j in 0 until ny) for (i in 0 until nx) {
                val c = j * nx + i
                val rgb = when { doubled[c] -> 0x2050e0; uncovered[c] -> 0xe02020; covered[c] && !inside[c] -> 0xf0a020; covered[c] -> 0xb0b0b0; inside[c] -> 0xffe0e0; else -> 0xffffff }
                img.setRGB(i, ny - 1 - j, rgb)
            }
            javax.imageio.ImageIO.write(img, "png", map.toFile())
        }
        val pathIds = segs.map { it.path }.distinct()
        return Measure(layer.z, unc * area, 2 * widest * cell, wide * area, out * area, farthest * cell, pathIds.size,
            pathIds.minOfOrNull { read.paths[it].length } ?: 0.0, segs.minOfOrNull { read.moveLength[it.move] } ?: 0.0, dbl * area)
    }

    /**
     * How far [a]'s lines lie from [b]'s (and back), the larger (mm): each line sampled every 0.02 mm, the points inside [win]
     * read against the other layer's lines within [SHIFT_REACH_MM] of it (a long move reaching out of the window is read
     * only where it is in it: one layer's move may stop at a point the other's runs straight through); [tool]: one tool's
     * lines.
     */
    fun shift(a: Layer, b: Layer, win: Window, tool: Int?): Double {
        fun pick(l: Layer, m: Double) = l.segs.filter { (tool == null || it.tool == tool) && maxOf(it.x0, it.x1) >= win.x0 - m && minOf(it.x0, it.x1) <= win.x1 + m &&
            maxOf(it.y0, it.y1) >= win.y0 - m && minOf(it.y0, it.y1) <= win.y1 + m }
        val sa = pick(a, 0.0); val sb = pick(b, 0.0)
        if (sa.isEmpty() || sb.isEmpty()) return if (sa.isEmpty() && sb.isEmpty()) 0.0 else Double.POSITIVE_INFINITY
        val ra = pick(a, SHIFT_REACH_MM); val rb = pick(b, SHIFT_REACH_MM)
        fun far(from: List<Seg>, to: List<Seg>): Double {
            var worst = 0.0
            for (s in from) {
                val k = maxOf(1, Math.ceil(s.length / 0.02).toInt())
                for (t in 0..k) {
                    val px = s.x0 + (s.x1 - s.x0) * t / k; val py = s.y0 + (s.y1 - s.y0) * t / k
                    if (px < win.x0 || px > win.x1 || py < win.y0 || py > win.y1) continue
                    var best = Double.MAX_VALUE
                    for (q in to) {
                        val dx = q.x1 - q.x0; val dy = q.y1 - q.y0; val l2 = dx * dx + dy * dy
                        val u = if (l2 == 0.0) 0.0 else (((px - q.x0) * dx + (py - q.y0) * dy) / l2).coerceIn(0.0, 1.0)
                        best = minOf(best, Math.hypot(px - q.x0 - u * dx, py - q.y0 - u * dy))
                    }
                    worst = maxOf(worst, best)
                }
            }
            return worst
        }
        return maxOf(far(sa, rb), far(sb, ra))
    }
    /** How far round [shift]'s window the other layer's lines are read (mm): the test's margin, not a print value. */
    const val SHIFT_REACH_MM = 2.0

    /**
     * The exact Euclidean distance (in cells) from each cell where [mask] is true to the nearest cell where it is false
     * (0 where false): the two-pass lower envelope of parabolas.
     */
    fun edt(mask: BooleanArray, nx: Int, ny: Int): DoubleArray {
        val inf = 1e20
        val f = DoubleArray(maxOf(nx, ny)); val d = DoubleArray(maxOf(nx, ny)); val v = IntArray(maxOf(nx, ny)); val zz = DoubleArray(maxOf(nx, ny) + 1)
        fun pass(len: Int) {
            var k = 0; v[0] = 0; zz[0] = -Double.MAX_VALUE; zz[1] = Double.MAX_VALUE
            for (q in 1 until len) {
                fun cross(p: Int) = ((f[q] + q.toDouble() * q) - (f[p] + p.toDouble() * p)) / (2.0 * q - 2.0 * p)
                var s = cross(v[k])
                while (s <= zz[k]) { k--; s = cross(v[k]) }
                k++; v[k] = q; zz[k] = s; zz[k + 1] = Double.MAX_VALUE
            }
            k = 0
            for (q in 0 until len) { while (zz[k + 1] < q) k++; d[q] = (q - v[k]).toDouble() * (q - v[k]) + f[v[k]] }
        }
        val g = DoubleArray(nx * ny)
        for (j in 0 until ny) {
            for (i in 0 until nx) f[i] = if (mask[j * nx + i]) inf else 0.0
            pass(nx)
            for (i in 0 until nx) g[j * nx + i] = d[i]
        }
        for (i in 0 until nx) {
            for (j in 0 until ny) f[j] = g[j * nx + i]
            pass(ny)
            for (j in 0 until ny) g[j * nx + i] = Math.sqrt(d[j])
        }
        return g
    }
}
