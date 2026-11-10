package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import plymeld.core.Feature
import plymeld.core.Planner
import plymeld.geom.Polygon
import plymeld.model.SliceSettings

/**
 * Least-visible seams planned across layers: a round column 80 mm across, looked at from its front, with a slot in its front
 * face for its first 10 layers (a recess the seams hide in, 1.5 mm deep) and plain above for 120 more. Placed layer by
 * layer, each seam stayed in line with the one below rather than pay the 80 mm move to the back once (on a plain wall no
 * single layer's saving outweighs it), so above the slot the seams ran up the front face, the most-seen place on the
 * column (on a nameplate: from its lettering to its top edge; here 118 of 118 plain layers). Planned across the layers, the
 * move is paid once (here at the bottom: the slot hides less than starting at the back saves) and the plain layers' seams
 * are at the back. Control: they stay in line there, not wandering after the least-seen point layer by layer.
 */
class SeamColumnTest : StringSpec({
    val s = SliceSettings.of(testSetup("plan.seam" to "least_visible", "plan.view_from" to "front"))
    // a circle of 144 points, radius 40 mm, around (50, 50) mm; the slot at the front (small y), 1.5 mm deep
    fun circle(slot: Boolean): Polygon {
        val pts = ArrayList<Long>()
        for (k in 0 until 144) {
            val a = 2 * Math.PI * k / 144 - Math.PI / 2
            val r = if (slot && (k <= 1 || k >= 143)) 38_500.0 else 40_000.0
            pts += Math.round(50_000 + r * Math.cos(a)); pts += Math.round(50_000 + r * Math.sin(a))
        }
        return Polygon(pts.toLongArray())
    }
    val n = 130
    val tops = LongArray(n) { s.firstLayerHeightUm + it * 200L }
    val layers = Planner.plan(s, List(n) { i -> listOf(circle(slot = i < 10)) }, tops)
    /** Each layer's outer-wall start (x, y in µm). */
    val starts = (2 until n).map { i -> layers[i].paths.first { it.feature == Feature.OUTER_WALL }.xy.let { it[0] to it[1] } }

    "above the slot no seam runs up the front face, and the seams stay in line (no wandering)" {
        val above = starts.drop(10)
        val front = above.count { it.second < 50_000 }
        // how far the seams move from layer to layer above the slot, in all (mm)
        val jogs = above.zipWithNext { a, b -> Math.hypot((a.first - b.first).toDouble(), (a.second - b.second).toDouble()) / 1000.0 }.sum()
        println("above the slot: ${front} of ${above.size} seams on the front half, %.1f mm of jogs in all; first ${above.first()}".format(java.util.Locale.ROOT, jogs))
        front shouldBe 0
        // placed layer by layer they stayed on the front, in line (118 of 118 there); planned, they stay in line at the back
        (jogs < 10.0) shouldBe true
    }
})
