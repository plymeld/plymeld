package plymeld.gcode

import plymeld.geom.Booleans
import plymeld.geom.Offset
import plymeld.geom.Polygon
import plymeld.model.SliceSettings
import plymeld.program.Exact
import plymeld.program.Extrude
import plymeld.program.LayerStart
import plymeld.program.ObjectStart
import plymeld.program.PrintProgram
import plymeld.program.Step

/**
 * The prime line: plymeld lays its own, before the first layer, next to the first object, so it is easy
 * to remember to peel off (near the print, no more than 1 cm away), instead of a start template's line in a corner
 * of the bed. Three kinds (the setting `plan.mechanism.adhesion.prime.line`, made from the config's `prime.enabled` and
 * `prime.line`):
 *  - near: a line along the first object's first-layer edge, `plan.mechanism.adhesion.prime.gap` out from it, `plan.mechanism.adhesion.prime.length` long;
 *  - tab: the same on a peel tab: with a brim, the line against the brim's outer edge (it peels with the brim); without
 *    one, a partial brim fills between the print and the line; the default without a prime tower;
 *  - none (`prime.enabled = false`): no separate prime line; the default with a prime tower (it primes).
 * The line follows the edge, centered on the middle of the object's front (then its back, left, right, where the front has
 * no room: off the bed, or closer than [CLEAR_UM] to another object's first layer), round corners where the side is shorter
 * than the line. With no room on any side, none, and the note says so. A program that primes itself (a calibration plate
 * laying one per block) gets none.
 */
object PrimeLine {
    /** How close the prime line may come to another object's first layer (µm). plymeld's choice: a millimetre, so it never fuses to it. */
    const val CLEAR_UM = 1_000L
    /** The prime line's cell in an exact program (a calibration plate): part of no test on the plate (CalibrationCheck, partCosts). */
    const val CELL = "prime line"

    class Result(val program: PrintProgram, val note: String)

    fun insert(p: PrintProgram, s: SliceSettings): Result {
        if (p.selfPrimed) return Result(p, "none: the program lays its own")
        val steps = p.steps
        val i0 = steps.indexOfFirst { it is LayerStart }
        if (i0 < 0) return Result(p, "none: no layers")
        val layer = steps[i0] as LayerStart
        val i1 = (i0 + 1 until steps.size).firstOrNull { steps[it] is LayerStart } ?: steps.size
        val firstLayer = steps.subList(i0, i1)
        val tower = steps.any { it is Extrude && it.feature == "Prime tower" }
        val brim = firstLayer.any { it is Extrude && it.feature == "Brim" }
        // unset: none with a prime tower (it primes), else on a peel tab joined to the print (near the model,
        // with a brim; before, a free line prime.gap out when there was no brim)
        val mode = s.primeLine ?: when { tower -> "none"; else -> "tab" }
        // these reasons go into the g-code's comments: they name the keys as written in [plan.mechanism.adhesion]
        // (prime.enabled, prime.line, prime.length), so a rename of setting ids leaves the g-code unchanged
        val why = if (s.primeLine == "none") "prime.enabled = false" else if (s.primeLine != null) "prime.line = $mode"
            else when { tower -> "a prime tower primes"; brim -> "there is a brim"; else -> "no prime tower" }
        if (mode == "none") return Result(p, "none ($why)")
        if (s.primeLengthUm <= 0) return Result(p, "none: prime.length is not set")
        require(mode == "near" || mode == "tab") { "plan.mechanism.adhesion.prime.line '$mode' is not near, tab, or none" }
        val firstExtrude = firstLayer.filterIsInstance<Extrude>().firstOrNull() ?: return Result(p, "none: nothing on the first layer")

        // the first object's first-layer extrusions, and everyone else's
        val mine = ArrayList<Extrude>(); val others = ArrayList<Extrude>()
        run {
            var obj: String? = null; var firstObj: String? = null
            for (st in firstLayer) when (st) {
                is ObjectStart -> { obj = st.name; if (firstObj == null) firstObj = st.name }
                is Extrude -> if (obj == null || obj == firstObj) mine += st else others += st
                else -> {}
            }
        }
        val w = s.firstLayerWidthUm
        // the print's edge: its outermost closed lines (outer walls; with a brim, the brim's), their outer edge
        val loops = mine.filter { it.closed && (it.feature == "Outer wall" || it.feature == "Brim") }.map { Polygon(it.xy) }
            .map { if (it.isCcw()) it else it.reversed() }
        if (loops.isEmpty()) return Result(p, "none: the first object has no outer wall on its first layer")
        val edge = Offset.offset(Booleans.normalize(loops), w / 2).filter { it.isCcw() }
        if (edge.isEmpty()) return Result(p, "none: the first object's edge vanished")
        // the print's middle: its bounding box's
        val all = edge.flatMap(::pts)
        val cx = (all.minOf { it[0] } + all.maxOf { it[0] }) / 2.0; val cy = (all.minOf { it[1] } + all.maxOf { it[1] }) / 2.0
        // the prime line's center: against the brim on a tab with a brim, else plan.mechanism.adhesion.prime.gap out
        val primeOut = if (mode == "tab" && brim) w / 2 else s.primeGapUm + w / 2
        for (side in Side.entries) {
            val line = along(Offset.offset(edge, primeOut).filter { it.isCcw() }, side, cx, cy, s.primeLengthUm / 2, w) ?: continue
            if (!onBed(line, s.bedUm, w) || !clearOf(line, others, w)) continue
            val paths = ArrayList<LongArray>()
            paths += line
            // the peel tab without a brim: partial brim lines from the line back to the print, one spacing apart, each
            // only where it lies under the line, so the tab ends where the line does
            if (mode == "tab" && !brim) {
                val sp = w - Math.round(layer.heightUm * (1 - Math.PI / 4))
                var d = primeOut - sp
                while (d >= s.brimGapUm + w / 2 - 1) {
                    along(Offset.offset(edge, d).filter { it.isCcw() }, side, cx, cy, s.primeLengthUm / 2, w) { q -> under(q, line) }
                        ?.let { paths += if (paths.size % 2 == 1) reverse(it) else it }
                    d -= sp
                }
            }
            val exact = firstExtrude.exact?.let { Exact(CELL, doubleArrayOf(it.speed(0)), it.accel, it.flowRatio, it.retractMm) }
            val made: List<Step> = paths.mapIndexed { k, xy ->
                // no retraction into them: the nozzle has nothing to hold back before it is primed, and the tab follows on
                Extrude(if (k == 0) "Prime line" else "Brim", xy, false, layer.zUm, w, layer.heightUm, firstExtrude.tool, retractBefore = false, exact = exact)
            }
            val out = ArrayList<Step>(steps.size + made.size)
            out.addAll(steps.subList(0, i0 + 1)); out.addAll(made); out.addAll(steps.subList(i0 + 1, steps.size))
            val what = if (mode == "near") "near the print" else if (brim) "on a peel tab, against the brim" else "on a peel tab (${paths.size - 1} partial brim lines)"
            return Result(PrintProgram(out, p.objects, p.exact, p.selfPrimed),
                "$what ($why): ${"%.0f".format(java.util.Locale.ROOT, length(line) / 1000.0)} mm along the first object's ${side.label}, " +
                    "${"%.1f".format(java.util.Locale.ROOT, (primeOut - w / 2) / 1000.0)} mm out")
        }
        return Result(p, "none: no room within reach of the first object on any side (the bed's edge, or other objects within ${CLEAR_UM / 1000.0} mm)")
    }

    enum class Side(val label: String, val dx: Int, val dy: Int) { FRONT("front", 0, -1), BACK("back", 0, 1), LEFT("left", -1, 0), RIGHT("right", 1, 0) }

    /**
     * The stretch of [loops] in the middle of the print's [side]: centered where the line through the print's middle ([cx],
     * [cy]) heading that way crosses the loops furthest out, running [half] along the loop both ways from there (at most
     * half its length less a line's width [w] each way, so it never closes on itself), following the loop round corners.
     * With [keep], it also stops where a point fails it. An open line (µm), or null.
     */
    private fun along(loops: List<Polygon>, side: Side, cx: Double, cy: Double, half: Long, w: Long, keep: ((DoubleArray) -> Boolean)? = null): LongArray? {
        // the middle line runs along y for front / back (at x = cx), along x for left / right (at y = cy)
        val i = if (side.dy != 0) 0 else 1; val m = if (i == 0) cx else cy
        var best: List<DoubleArray>? = null; var bestK = -1; var bestP = doubleArrayOf(); var bestV = Double.NEGATIVE_INFINITY
        for (lp in loops) {
            val q = pts(lp).map { doubleArrayOf(it[0].toDouble(), it[1].toDouble()) }; val n = q.size
            for (k in 0 until n) {
                val a = q[k]; val b = q[(k + 1) % n]
                if (a[i] == b[i] || m < Math.min(a[i], b[i]) || m > Math.max(a[i], b[i])) continue
                val c = lerp(a, b, (m - a[i]) / (b[i] - a[i]))
                val v = c[0] * side.dx + c[1] * side.dy
                if (v > bestV) { bestV = v; best = q; bestK = k; bestP = c }
            }
        }
        val q = best ?: return null
        val n = q.size
        val limit = Math.min(half.toDouble(), (0 until n).sumOf { dist(q[it], q[(it + 1) % n]) } / 2 - w)
        if (limit <= 0 || (keep != null && !keep(bestP))) return null
        // walk one way from the center: forward towards vertex bestK + 1, back towards vertex bestK
        fun walk(dir: Int): List<DoubleArray> {
            val run = ArrayList<DoubleArray>()
            var at = bestP; var left = limit
            var next = if (dir > 0) (bestK + 1) % n else bestK
            repeat(n + 1) {
                val b = q[next]; val l = dist(at, b)
                if (l >= left || (keep != null && !keep(b))) {
                    var t = if (l > 0) Math.min(1.0, left / l) else 0.0
                    if (keep != null && !keep(lerp(at, b, t))) {
                        // the furthest point that keeps, by halving (keep holds at `at`)
                        var lo = 0.0; var hi = t
                        repeat(40) { val mid = (lo + hi) / 2; if (keep(lerp(at, b, mid))) lo = mid else hi = mid }
                        t = lo
                    }
                    run += lerp(at, b, t)
                    return run
                }
                run += b; left -= l; at = b
                next = Math.floorMod(next + dir, n)
            }
            return run
        }
        val line = (walk(-1).reversed() + listOf(bestP) + walk(1)).map { longArrayOf(Math.round(it[0]), Math.round(it[1])) }
            .fold(ArrayList<LongArray>()) { acc, v -> if (acc.isEmpty() || !acc.last().contentEquals(v)) acc += v; acc }
        if (line.size < 2) return null
        return LongArray(line.size * 2) { line[it / 2][it % 2] }
    }

    private fun lerp(a: DoubleArray, b: DoubleArray, t: Double) = doubleArrayOf(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t)

    private fun dist(a: DoubleArray, b: DoubleArray) = Math.hypot(b[0] - a[0], b[1] - a[1])

    private fun length(xy: LongArray): Double = (1 until xy.size / 2).sumOf { Math.hypot((xy[2 * it] - xy[2 * it - 2]).toDouble(), (xy[2 * it + 1] - xy[2 * it - 1]).toDouble()) }

    /** Whether [q] lies under the open line [xy]: its nearest point on the line is not one of the line's two ends. */
    private fun under(q: DoubleArray, xy: LongArray): Boolean {
        val m = xy.size / 2
        var best = Double.POSITIVE_INFINITY; var end = true
        for (s in 1 until m) {
            val ax = xy[2 * s - 2].toDouble(); val ay = xy[2 * s - 1].toDouble(); val dx = xy[2 * s] - ax; val dy = xy[2 * s + 1] - ay
            val l2 = dx * dx + dy * dy
            val t = if (l2 == 0.0) 0.0 else (((q[0] - ax) * dx + (q[1] - ay) * dy) / l2).coerceIn(0.0, 1.0)
            val d = Math.hypot(q[0] - ax - t * dx, q[1] - ay - t * dy)
            if (d < best) { best = d; end = (s == 1 && t == 0.0) || (s == m - 1 && t == 1.0) }
        }
        return !end
    }

    private fun pts(p: Polygon): List<LongArray> = List(p.size) { longArrayOf(p.x(it), p.y(it)) }

    private fun reverse(xy: LongArray): LongArray { val m = xy.size / 2; return LongArray(xy.size) { val v = m - 1 - it / 2; xy[2 * v + it % 2] } }

    private fun onBed(xy: LongArray, bed: LongArray?, w: Long): Boolean {
        if (bed == null) return true
        for (i in 0 until xy.size / 2) {
            val x = xy[2 * i]; val y = xy[2 * i + 1]
            if (x - w / 2 < bed[0] || y - w / 2 < bed[1] || x + w / 2 > bed[2] || y + w / 2 > bed[3]) return false
        }
        return true
    }

    /** Whether every point of [xy] keeps [CLEAR_UM] plus a line's width from every segment of [others]. */
    private fun clearOf(xy: LongArray, others: List<Extrude>, w: Long): Boolean {
        val need = (CLEAR_UM + w).toDouble()
        for (o in others) {
            val m = o.xy.size / 2; val segs = if (o.closed) m else m - 1
            for (s in 0 until segs) {
                val t = (s + 1) % m
                val ax = o.xy[2 * s].toDouble(); val ay = o.xy[2 * s + 1].toDouble(); val bx = o.xy[2 * t].toDouble(); val by = o.xy[2 * t + 1].toDouble()
                for (i in 0 until xy.size / 2) if (dist(xy[2 * i].toDouble(), xy[2 * i + 1].toDouble(), ax, ay, bx, by) < need) return false
            }
        }
        return true
    }

    private fun dist(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax; val dy = by - ay; val l2 = dx * dx + dy * dy
        val t = if (l2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / l2).coerceIn(0.0, 1.0)
        return Math.hypot(px - ax - t * dx, py - ay - t * dy)
    }
}
