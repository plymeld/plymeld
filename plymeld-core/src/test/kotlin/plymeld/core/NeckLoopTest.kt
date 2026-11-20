package plymeld.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import plymeld.geom.Polygon

/**
 * A narrow island's loop does not run through a place its medial axis reads narrower than the narrowest line (Beads,
 * smoothCounts). The island (µm): a band 2 mm long and 1 mm wide with a slit 40 microns wide cut 150 microns into it from
 * one edge (a sliver of another part between two strokes), the slit's inner corner carrying a 1 micron step (as a sliced
 * outline's corner can), laid by its beads (0.44 mm lines, the narrowest 0.3 mm, three walls: two lines across). The
 * outline sampled every 75 microns, the axis runs round the slit's inner corner through nodes a few tens of microns from
 * the outline: a stretch with a count of one line from its width, between two of two lines. Short, it would take their
 * count, and the outer loop's points on its ribs, each at its place from the outline but no further than the node, would
 * be drawn onto the node: a spike toward the outline. Measured:
 * the outer loop's nearest point to the outline; IslandCheck's coverage (each line a bead of its width: the widest spot
 * no line covers, plastic outside the island). Control: the stretch taking its neighbors' count as before, the loop is
 * drawn onto it.
 */
class NeckLoopTest : StringSpec({
    val island = listOf(Polygon(longArrayOf(0, 0, 600, 0, 600, 150, 601, 149, 640, 150, 640, 0, 2000, 0, 2000, 1000, 0, 1000))
        .let { if (it.isCcw()) it else it.reversed() })
    val tol = 13L; val flank = 80 * (1 - Math.PI / 4); val floor = 150.0 + 2 * tol
    val rule = Beads.Rule(440.0, 440.0, 3, flank, 300.0, 880.0)
    fun lay(necksNarrow: Boolean) = Beads.lay(island, Beads.Params(rule, 150, 300, tol, 75, 0.25, 2.0, necksNarrow = necksNarrow))
    /** The nearest any of [ls]'s points comes to the island's outline (µm). */
    fun nearest(ls: List<CenterLine>): Double {
        var best = Double.MAX_VALUE
        for (c in ls) for (k in 0 until c.xy.size / 2) for (p in island) for (e in 0 until p.size) { val f = (e + 1) % p.size
            best = minOf(best, segDist(c.xy[2 * k].toDouble(), c.xy[2 * k + 1].toDouble(), p.x(e).toDouble(), p.y(e).toDouble(), p.x(f).toDouble(), p.y(f).toDouble())) }
        return best
    }
    fun measure(ls: List<CenterLine>) = IslandCheck.measure(island, ls.map { c ->
        IslandCheck.Line(c.xy, DoubleArray(c.widths.size) { c.widths[it].toDouble() }, c.xy[0] == c.xy[c.xy.size - 2] && c.xy[1] == c.xy[c.xy.size - 1]) },
        emptyList(), flank, floor, tol.toDouble())
    "the outer loop is not drawn onto a place the axis reads narrower than the narrowest line; nothing bare, nothing far outside" {
        val ls = lay(true)
        val d = nearest(ls.filter { it.wall == 0 }); val m = measure(ls)
        println("slit: ${ls.size} lines, the outer loop's nearest to the outline ${"%.0f".format(d)} um; $m")
        (d >= 100.0) shouldBe true
        (m.widestUm <= floor + m.cellUm) shouldBe true
        (m.outsideUm <= 90.0 + m.cellUm) shouldBe true
    }
    "control: the narrow stretch taking its neighbors' count, the outer loop is drawn onto it" {
        val ls = lay(false)
        val d = nearest(ls.filter { it.wall == 0 })
        println("slit control: ${ls.size} lines, the outer loop's nearest to the outline ${"%.0f".format(d)} um; ${measure(ls)}")
        (d < 50.0) shouldBe true
    }
})

private fun segDist(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
    val dx = bx - ax; val dy = by - ay; val l2 = dx * dx + dy * dy
    val t = if (l2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / l2).coerceIn(0.0, 1.0)
    return Math.hypot(px - ax - t * dx, py - ay - t * dy)
}
