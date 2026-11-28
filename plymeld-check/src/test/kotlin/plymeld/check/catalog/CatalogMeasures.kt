package plymeld.check.catalog

import plymeld.check.TechniqueRender
import plymeld.check.catalog.Catalog.Entry
import plymeld.check.catalog.Catalog.Row
import plymeld.core.Predict
import plymeld.model.SliceSettings
import java.util.Locale

/**
 * What the catalog records of one model sliced under one row: every measure a side effect of a change could move, each a
 * name and its value as text (fixed decimals), read from the written file and what the writing measured, with the readers
 * plymeld and its tests already have:
 *  - `check.*`: the pre-print check as `slice` runs it (Slicing.sliceCheck): how many problems, and the first three;
 *  - `laid_twice.*`: that check's laid-twice rule as `slice` reports it (DoubledCheck.Summary): the spots, their area,
 *    the layers holding one, the widest spot's width; and the layers whose reading stopped at its cap, when any;
 *  - `walls.*` (a walls case, its base row): WallCoverage's reading over the layers [Entry.walls] names: uncovered area,
 *    the widest uncovered spot and the area wider than the floor; plastic outside and how far; plastic laid twice; paths,
 *    the shortest path and move; the largest shift from one layer to the next;
 *  - `seams.*`: SeamScar of the planned layers (scar, seams, point and scarf seams, jogs), or "not measured" (a
 *    multi-filament object);
 *  - `strings.*`: StringRisk as the writer measured it (unretracted travel where a string would show, over open air and
 *    across tops; visible restarts and the length printed starved after them; retractions; travel);
 *  - `filament_mm`, `filament.T<n>_mm`, `time_s`: Predict on the file, as `slice` predicts it;
 *  - `feature.<type>.paths`, `feature.<type>.length_mm`: per feature type, how many paths (consecutive extruding moves of one
 *    feature, tool and layer, each starting where the last ended) and their length (TechniqueRender's moves);
 *  - `lines.*` ([jagged]): extruding moves shorter than the slicing tolerance, all and the outer wall's, and the outer wall's
 *    kinks (a turn of more than 150 deg beside a move shorter than the outer line's width), and of those the zigzags (the
 *    line goes on the way it came, not back out of a tip, nor round a square end's corner);
 *  - `sweep.<type>.*` ([sweeps]): per solid fill type (bottom surface, internal solid infill, top surface), its lines and
 *    the lines laid against their fill's sweep (0: every connected fill laid monotonically).
 * Planning time is not recorded: it varies from run to run (NarrowIslandTimeTest holds it).
 */
object CatalogMeasures {
    private fun f(v: Double, d: Int) = String.format(Locale.ROOT, "%.${d}f", v)
    /** A problem's text as one baseline value: tabs and line ends as spaces, at most 240 characters. */
    private fun text(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').let { if (it.length > 240) it.take(240) + "..." else it }

    /** [e] sliced under [row] and measured; the problems the check found come back too. */
    class Measured(val values: LinkedHashMap<String, String>, val problems: List<String>)

    fun measure(e: Entry, row: Row): Measured {
        val w = Catalog.write(e, row, seams = true)
        val out = LinkedHashMap<String, String>()
        val checked = Slicing.sliceCheck(w)
        val problems = checked.problems
        out["check.problems"] = problems.size.toString()
        for ((i, p) in problems.take(3).withIndex()) out["check.problem.${i + 1}"] = text(p)
        val twice = checked.laidTwice
        out["laid_twice.spots"] = twice.spots.toString(); out["laid_twice.area_mm2"] = f(twice.areaMm2, 3)
        out["laid_twice.layers"] = twice.spotLayers.toString(); out["laid_twice.widest_mm"] = f(twice.widest?.second?.widthMm ?: 0.0, 3)
        if (twice.cappedLayers > 0) out["laid_twice.capped_layers"] = twice.cappedLayers.toString()
        if (e.walls != null && row === Catalog.BASE) {
            val r = Slicing.wallReading(e.name, w, e.walls)
            out["walls.layers"] = r.layers.size.toString()
            out["walls.uncovered_mm2"] = f(r.sum { it.uncoveredMm2 }, 4)
            out["walls.uncovered_widest_mm"] = f(r.max { it.widestUncoveredMm }, 3)
            out["walls.uncovered_wider_than_floor_mm2"] = f(r.sum { it.uncoveredWideMm2 }, 4)
            out["walls.outside_mm2"] = f(r.sum { it.outsideMm2 }, 4)
            out["walls.outside_farthest_mm"] = f(r.max { it.outsideMaxMm }, 3)
            out["walls.doubled_mm2"] = f(r.sum { it.doubledMm2 }, 4)
            out["walls.paths"] = r.layers.sumOf { it.paths }.toString()
            out["walls.shortest_path_mm"] = f(r.layers.minOf { it.shortestPathMm }, 3)
            out["walls.shortest_move_mm"] = f(r.layers.minOf { it.shortestMoveMm }, 3)
            out["walls.largest_shift_mm"] = f(r.shifts.maxOrNull() ?: 0.0, 3)
        }
        val sc = w.seamScar
        if (sc == null) out["seams"] = "not measured" else {
            out["seams.scar_mm2"] = f(sc.scarMm2, 3); out["seams.count"] = sc.seams.toString(); out["seams.point"] = sc.pointSeams.toString()
            out["seams.scarf"] = sc.scarfSeams.toString(); out["seams.jog_mm"] = f(sc.jogMm, 3)
        }
        val st = w.strings
        if (st == null) out["strings"] = "not measured" else {
            out["strings.unretracted_shown_mm"] = f(st.stringMm, 2); out["strings.unretracted_over_air_mm"] = f(st.openMm, 2)
            out["strings.unretracted_across_tops_mm"] = f(st.topMm, 2); out["strings.unretracted_travels"] = st.unretractedTravels.toString()
            out["strings.visible_restarts"] = st.visibleRestarts.toString(); out["strings.restart_starved_mm"] = f(st.restartStarvedMm, 2)
            out["strings.retractions"] = st.retractions.toString(); out["strings.travel_mm"] = f(st.travelMm, 2)
        }
        val s = SliceSettings.of(w.setup)
        val changeS = (plymeld.model.Resolver.resolveOrNull(w.setup, "hardware.machine.tool_change.time")?.value as Number?)?.toDouble()
        val pr = Predict.gcode(w.g, s.machineDescription, changeS, Predict.limits(s))
        out["filament_mm"] = f(pr.filamentMm, 2)
        for ((t, mm) in pr.filamentByToolMm.toSortedMap()) out["filament.T${t}_mm"] = f(mm, 2)
        out["time_s"] = pr.timeS?.let { f(it, 1) } ?: "not predicted"
        val tp = TechniqueRender.read(w.g)
        val ms = tp.moves
        val paths = java.util.TreeMap<String, Int>(); val length = java.util.TreeMap<String, Double>()
        for (k in ms.indices) {
            val m = ms[k]
            val name = m.feature.ifEmpty { "unnamed" }
            val joined = k > 0 && ms[k - 1].let { p -> p.feature == m.feature && p.tool == m.tool && p.layer == m.layer && Math.hypot(p.x1 - m.x0, p.y1 - m.y0) < 1e-6 }
            if (!joined) paths.merge(name, 1, Int::plus)
            length.merge(name, m.length, Double::plus)
        }
        for ((name, n) in paths) { out["feature.$name.paths"] = n.toString(); out["feature.$name.length_mm"] = f(length.getValue(name), 2) }
        val j = jagged(ms, s.resolutionUm / 1000.0, s.outerWallWidthUm / 1000.0)
        out["lines.tiny_moves"] = j.tiny.toString(); out["lines.tiny_moves.outer_wall"] = j.tinyOuter.toString(); out["lines.outer_kinks"] = j.kinks.toString()
        out["lines.outer_zigzags"] = j.zigzags.toString()
        for ((type, sw) in sweeps(ms, SOLID_FILLS)) if (sw.lines > 0) { out["sweep.$type.lines"] = sw.lines.toString(); out["sweep.$type.reversed_lines"] = sw.reversed.toString() }
        return Measured(out, problems)
    }

    /** The solid fills [sweeps] reads: every fill laid as lines across its whole area. */
    val SOLID_FILLS = listOf("Bottom surface", "Internal solid infill", "Top surface")

    /** [sweeps]'s counts for one fill type: its lines, and the lines laid against their fill's sweep. */
    class Sweep(val lines: Int, val reversed: Int)

    /**
     * How the lines of each fill type of [types] were laid across their area, from a file's extruding moves [ms]. On each
     * layer, tool and type, the fill's lines are its moves along the fill's direction (the direction holding the most of its
     * length, to within 2 degrees) at least two of their own widths long (shorter, a move is a link along the edge or a
     * stub), loops left out (a path ending where it starts: a narrow band's rings, laid round, not across); two lines are neighbors where they lie on scanlines next to each other (their distance across the lines between
     * half and one and a half of the fill's spacing, the spacing the median distance from a line to its nearest other line)
     * and overlap along them. Neighbors joined make a connected fill. A line is laid against its fill's sweep where a
     * neighbor ahead of it (in the sweep's sense) was laid before it: in a monotonic sweep each line is laid against the one
     * behind it only, pieces side by side (the two sides of a hole) in either order. The sweep's sense is, for each
     * connected fill, the one with fewer lines against it. 0: every connected fill laid in one sweep. (2 degrees, two widths,
     * a half and one and a half spacings: plymeld's choice, geometric bounds of this measure, not print values.)
     */
    fun sweeps(ms: List<TechniqueRender.Move>, types: List<String>): Map<String, Sweep> {
        val lines = HashMap<String, Int>(); val reversed = HashMap<String, Int>()
        for (t in types) { lines[t] = 0; reversed[t] = 0 }
        val groups = LinkedHashMap<Triple<Int, Int, String>, MutableList<TechniqueRender.Move>>()
        // a loop (a path ending where it starts: a narrow band's rings) is not laid as lines
        var k = 0
        while (k < ms.size) {
            var e = k
            while (e + 1 < ms.size && ms[e + 1].let { n -> val p = ms[e]; n.feature == p.feature && n.tool == p.tool && n.layer == p.layer && Math.hypot(p.x1 - n.x0, p.y1 - n.y0) < 1e-6 }) e++
            val loop = e > k && Math.hypot(ms[e].x1 - ms[k].x0, ms[e].y1 - ms[k].y0) < 1e-6
            if (!loop) for (j in k..e) { val m = ms[j]; if (m.feature in types && m.length > 0) groups.getOrPut(Triple(m.layer, m.tool, m.feature)) { ArrayList() }.add(m) }
            k = e + 1
        }
        for ((key, moves) in groups) {
            // the fill's direction: the 1-degree bin (mod 180) holding the most length, then the moves within 2 degrees of it
            fun deg(m: TechniqueRender.Move) = ((Math.toDegrees(Math.atan2(m.y1 - m.y0, m.x1 - m.x0)) % 180.0) + 180.0) % 180.0
            fun apart(a: Double, b: Double) = Math.abs(a - b).let { minOf(it, 180.0 - it) }
            val bins = DoubleArray(180)
            for (m in moves) bins[deg(m).toInt() % 180] += m.length
            val peak = bins.indices.maxBy { bins[it] } + 0.5
            var sx = 0.0; var sy = 0.0
            for (m in moves) if (apart(deg(m), peak) <= 2.0) { val a = Math.toRadians(2 * deg(m)); sx += m.length * Math.cos(a); sy += m.length * Math.sin(a) }
            val dir = Math.atan2(sy, sx) / 2
            val ux = Math.cos(dir); val uy = Math.sin(dir)
            val dirDeg = ((Math.toDegrees(dir) % 180.0) + 180.0) % 180.0
            val ls = moves.filter { apart(deg(it), dirDeg) <= 2.0 && it.length >= 2 * (if (it.width.isNaN()) 0.0 else it.width) }
            lines.merge(key.third, ls.size, Int::plus)
            if (ls.size < 2) continue
            // across (the normal) and the interval along, in print order
            val p = DoubleArray(ls.size) { k -> val m = ls[k]; -uy * (m.x0 + m.x1) / 2 + ux * (m.y0 + m.y1) / 2 }
            val a0 = DoubleArray(ls.size) { k -> val m = ls[k]; minOf(ux * m.x0 + uy * m.y0, ux * m.x1 + uy * m.y1) }
            val a1 = DoubleArray(ls.size) { k -> val m = ls[k]; maxOf(ux * m.x0 + uy * m.y0, ux * m.x1 + uy * m.y1) }
            val byP = ls.indices.sortedBy { p[it] }
            fun overlap(i: Int, j: Int) = minOf(a1[i], a1[j]) - maxOf(a0[i], a0[j]) > 0
            // the spacing: the median distance across from a line to its nearest other line overlapping it (not on its scanline)
            val floor = 0.25 * (ls.mapNotNull { if (it.width.isNaN()) null else it.width }.minOrNull() ?: 0.1)
            val nearest = ArrayList<Double>()
            for ((r, i) in byP.withIndex()) {
                var best = Double.POSITIVE_INFINITY
                for (dr in listOf(-1, 1)) { var q = r + dr
                    while (q in byP.indices && Math.abs(p[byP[q]] - p[i]) < best) { val j = byP[q]; val d = Math.abs(p[j] - p[i]); if (d > floor && overlap(i, j)) best = d; q += dr } }
                if (best.isFinite()) nearest += best
            }
            if (nearest.isEmpty()) continue
            val spacing = nearest.sorted()[nearest.size / 2]
            // neighbors (i < j in print order) and the connected fills
            val root = IntArray(ls.size) { it }
            fun find(a: Int): Int { var r = a; while (root[r] != r) r = root[r]; var b = a; while (root[b] != r) { val nb = root[b]; root[b] = r; b = nb }; return r }
            val pairs = ArrayList<IntArray>()
            for ((r, i) in byP.withIndex()) { var q = r + 1
                while (q < byP.size && p[byP[q]] - p[i] < 1.5 * spacing) { val j = byP[q]
                    if (p[j] - p[i] > 0.5 * spacing && overlap(i, j)) { pairs += intArrayOf(minOf(i, j), maxOf(i, j)); root[find(i)] = find(j) }
                    q++ } }
            // against the sweep, per sense: a line with a neighbor ahead of it laid before it
            val up = BooleanArray(ls.size); val down = BooleanArray(ls.size)
            for ((i, j) in pairs) { if (p[i] > p[j]) up[j] = true else down[j] = true }
            val upBy = HashMap<Int, Int>(); val downBy = HashMap<Int, Int>()
            for (k in ls.indices) { val c = find(k); if (up[k]) upBy.merge(c, 1, Int::plus); if (down[k]) downBy.merge(c, 1, Int::plus) }
            val comps = ls.indices.map { find(it) }.toSet()
            reversed.merge(key.third, comps.sumOf { c -> minOf(upBy[c] ?: 0, downBy[c] ?: 0) }, Int::plus)
        }
        return types.associateWith { Sweep(lines.getValue(it), reversed.getValue(it)) }
    }

    /** [jagged]'s counts: moves shorter than the tolerance (all, the outer wall's), the outer wall's kinks, and of those the zigzags. */
    class Jagged(val tiny: Int, val tinyOuter: Int, val kinks: Int, val zigzags: Int)

    /**
     * The jagged lines of a file's extruding moves [ms] (arcs written as lines): the moves shorter than [tolMm] (the slicing
     * tolerance: a move that short says nothing about where the line runs, and the printer is sent it for nothing), and the
     * outer wall's kinks: a vertex where the line turns by more than 150 deg beside a move shorter than [widthMm] (the outer
     * line's width), a spike or a step back the surface shows. A vertex is where one move ends and the next of the same
     * path (feature, tool and layer) starts. A kink is a zigzag where the line goes on the way it came: its direction over
     * [widthMm] of its length before the vertex (from the point that far back to the vertex) and after it (from the vertex to
     * the point that far on), each cut short at the path's end, differ by 120 deg or less. Otherwise the line goes back where
     * it came from: a narrow island's tip, which its loop runs into and out of by design (decision 28), turns so. Nor is a
     * square end's corner a zigzag: where the loop runs into each corner of a tip's square end, turning a right angle there
     * (decision 28's corners), its direction over [widthMm] turns more than 60 deg, over twice [widthMm] it goes back the way
     * it came (more than 120 deg: the end's second corner within a line or so), and the spike points out of the turn, into
     * the corner (the move into the vertex against the difference of the two directions). A spike at a single corner, the
     * line not turning back within two widths (a stub at a center line's junction where it turns), stays a zigzag.
     * (60 deg and twice the width: plymeld's choice, geometric bounds of this measure, not print values.)
     */
    fun jagged(ms: List<TechniqueRender.Move>, tolMm: Double, widthMm: Double): Jagged {
        var tiny = 0; var tinyOuter = 0; var kinks = 0; var zigzags = 0
        val cosLimit = Math.cos(Math.toRadians(150.0))
        val cosBack = Math.cos(Math.toRadians(120.0))
        val cosCorner = Math.cos(Math.toRadians(60.0))
        fun joined(k: Int): Boolean = k > 0 && ms[k - 1].let { p -> val m = ms[k]
            p.feature == m.feature && p.tool == m.tool && p.layer == m.layer && Math.hypot(p.x1 - m.x0, p.y1 - m.y0) <= 1e-6 }
        // the point [d] mm back from the start of move [k] (back) or on from it (not back), along its path
        fun along(k: Int, d: Double, back: Boolean): DoubleArray {
            var left = d; var j = if (back) k - 1 else k
            while (true) {
                val q = ms[j]
                if (q.length >= left) { val f = left / q.length
                    return if (back) doubleArrayOf(q.x1 + (q.x0 - q.x1) * f, q.y1 + (q.y0 - q.y1) * f) else doubleArrayOf(q.x0 + (q.x1 - q.x0) * f, q.y0 + (q.y1 - q.y0) * f) }
                left -= q.length
                if (back) { if (!joined(j)) return doubleArrayOf(q.x0, q.y0); j-- } else { if (j + 1 >= ms.size || !joined(j + 1)) return doubleArrayOf(q.x1, q.y1); j++ }
            }
        }
        for (k in ms.indices) {
            val m = ms[k]
            if (m.length < tolMm) { tiny++; if (m.feature == OUTER) tinyOuter++ }
            if (k == 0 || m.feature != OUTER || !joined(k)) continue
            val p = ms[k - 1]
            if (p.length == 0.0 || m.length == 0.0 || minOf(p.length, m.length) >= widthMm) continue
            val c = ((p.x1 - p.x0) * (m.x1 - m.x0) + (p.y1 - p.y0) * (m.y1 - m.y0)) / (p.length * m.length)
            if (c >= cosLimit) continue
            kinks++
            val b = along(k, widthMm, true); val a = along(k, widthMm, false)
            val bx = m.x0 - b[0]; val by = m.y0 - b[1]; val ax = a[0] - m.x0; val ay = a[1] - m.y0
            val lb = Math.hypot(bx, by); val la = Math.hypot(ax, ay)
            if (lb == 0.0 || la == 0.0) continue
            val turn = (bx * ax + by * ay) / (lb * la)
            if (turn < cosBack) continue
            // a square end's corner: a right angle here, back the way it came within two widths, the spike into the corner
            val b2 = along(k, 2 * widthMm, true); val a2 = along(k, 2 * widthMm, false)
            val ux = m.x0 - b2[0]; val uy = m.y0 - b2[1]; val vx = a2[0] - m.x0; val vy = a2[1] - m.y0
            val lu = Math.hypot(ux, uy); val lv = Math.hypot(vx, vy)
            val back2 = lu > 0 && lv > 0 && (ux * vx + uy * vy) / (lu * lv) < cosBack
            val into = (m.x0 - p.x0) * (bx / lb - ax / la) + (m.y0 - p.y0) * (by / lb - ay / la)
            if (turn < cosCorner && back2 && into > 0) continue
            zigzags++
        }
        return Jagged(tiny, tinyOuter, kinks, zigzags)
    }
    private const val OUTER = "Outer wall"
}
