package plymeld.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import plymeld.geom.Polygon

/**
 * A neck the mitred shrink cuts is joined again in a loop's fit (Walls.necksJoined). The outline: an L-shaped area (a
 * square corner turning into it at the origin) closed by a slanted edge across the corner, 1.76 mm from it along the
 * bisector: a band about 1.8 mm wide bending round the corner (µm). Walls of 0.44 mm on 0.08 mm layers (spacing 0.423 mm),
 * two loops: the inner loop's fit is the outline shrunk by 0.854 mm, whose exact shrink keeps a neck 0.052 mm wide across
 * the bisector (1.76 - 2 x 0.854); mitred, the corner is cut 0.354 mm deeper (to sqrt 2 x 0.854 from the corner),
 * through the neck, and the looser fit (the slicing tolerance further out) is cut too, so the hysteresis does not join it.
 *
 * Measured round the corner: the inner loops, how near two of them (or two stretches of one, more than 3 mm apart along
 * it) come, and (IslandCheck, each bead narrowed by half its flank and the tolerance a side) the plastic two lines both
 * lay and the widest spot no line covers. Control: the necks not joined (the fit as mitred), and the two loops that leaves
 * not cut apart (Walls.apart, which would hold them a spacing apart on its own), lays the band's inner loop as two loops
 * whose ends face each other across the cut closer than a line.
 */
class NecksJoinedTest : StringSpec({
    val outline = listOf(Polygon(longArrayOf(0, 0, 0, -6_000, 8_489, -6_000, -6_000, 8_489, -6_000, 0)).let { if (it.isCcw()) it else it.reversed() })
    val wo = 440L; val wi = 440L; val h = 80L; val tol = 13L
    val si = Walls.spacing(wi, h)
    fun walls(join: Boolean) = Walls.arachne(outline, 2, wo, wi, h, 300, 150, 2.0, 300, toleranceUm = tol, endOverlap = 0.25, maxBeadUm = 880,
        joinNecks = join, loopsApart = join)
    fun beads(r: LayerWalls): List<IslandCheck.Line> = r.walls.flatMapIndexed { k, lv -> lv.mapIndexed { li, p -> val ws = r.widthsOf(k, li)
        IslandCheck.Line(p.xy, DoubleArray(p.size) { (ws?.get(it) ?: (if (k == 0) wo else wi)).toDouble() }, true) } } +
        r.center.map { c -> IslandCheck.Line(c.xy, DoubleArray(c.widths.size) { c.widths[it].toDouble() }, false) }
    // read round the corner and the neck only (the band's far corners lay what they lay either way)
    val box = longArrayOf(-800, -800, 2_000, 2_000)
    val corner = plymeld.geom.Booleans.intersection(outline, listOf(Polygon(longArrayOf(box[0], box[1], box[2], box[1], box[2], box[3], box[0], box[3]))))
    fun measure(r: LayerWalls) = IslandCheck.measure(corner, beads(r), r.inner, h * (1 - Math.PI / 4), 150.0 + 2 * tol, tol.toDouble())
    /**
     * The nearest two inner loops come round the corner, or two stretches of one loop more than 3 mm apart along it (µm):
     * each loop read every 20 µm of its length.
     */
    fun nearestAcross(r: LayerWalls): Double {
        class Pt(val loop: Int, val s: Double, val len: Double, val x: Double, val y: Double)
        val pts = ArrayList<Pt>()
        for ((li, p) in r.walls[1].withIndex()) {
            val len = p.perimeter(); var s = 0.0
            for (e in 0 until p.size) {
                val f = (e + 1) % p.size
                val ex = (p.x(f) - p.x(e)).toDouble(); val ey = (p.y(f) - p.y(e)).toDouble(); val l = Math.hypot(ex, ey)
                val n = maxOf(1, Math.ceil(l / 20.0).toInt())
                for (i in 0 until n) {
                    val x = p.x(e) + ex * i / n; val y = p.y(e) + ey * i / n
                    if (x >= box[0] && x <= box[2] && y >= box[1] && y <= box[3]) pts += Pt(li, s + l * i / n, len, x, y)
                }
                s += l
            }
        }
        var best = Double.MAX_VALUE
        for (a in pts.indices) for (b in a + 1 until pts.size) {
            val p = pts[a]; val q = pts[b]
            if (p.loop == q.loop) { val ds = Math.abs(p.s - q.s); if (minOf(ds, p.len - ds) <= 3_000.0) continue }
            best = minOf(best, Math.hypot(p.x - q.x, p.y - q.y))
        }
        return best
    }

    "a neck the mitre cut is joined: one inner loop through it, its two sides a line apart, less laid twice, no bare spot" {
        val joined = walls(true); val cut = walls(false)
        val mj = measure(joined); val mc = measure(cut)
        val aj = nearestAcross(joined); val ac = nearestAcross(cut)
        println("necks joined: ${joined.walls[1].size} inner loop(s), nearest across ${"%.0f".format(aj)} um; $mj")
        println("necks cut:    ${cut.walls[1].size} inner loop(s), nearest across ${"%.0f".format(ac)} um; $mc")
        // control: cut, the band's inner loop is two loops, their ends across the cut closer than a line's spacing
        cut.walls[1].size shouldBe 2
        (ac < si - 100) shouldBe true
        // joined: one loop, its sides across the neck at least a spacing apart (0.423 mm and the neck's 0.052)
        joined.walls[1].size shouldBe 1
        (aj >= si) shouldBe true
        // no more laid twice (the overlapping ends gone; the joined loop's round turn lies nearer the outer loop's sharp
        // corner, by less), and no wider bare spot than the cut walls leave
        (mj.doubledUm2 < mc.doubledUm2) shouldBe true
        (mj.widestUm <= mc.widestUm + mj.cellUm) shouldBe true
        // the outer loop is the same either way (the outer fit has no neck)
        joined.walls[0].map { it.xy.toList() } shouldBe cut.walls[0].map { it.xy.toList() }
    }
})
