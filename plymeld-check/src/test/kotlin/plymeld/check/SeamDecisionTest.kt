package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import plymeld.core.SeamScar
import plymeld.core.decide.SeamDecision
import plymeld.core.decide.Visibility
import plymeld.core.decide.Weights
import plymeld.geom.Polygon

/**
 * The seam decision: placement and scarf seams weighed on an object's walls. The honesty test: all the
 * weight on quality scarfs a cylinder's seams, all of it on speed does not; a lock is kept as it is.
 */
class SeamDecisionTest : StringSpec({
    fun ring(n: Int, r: Double, cx: Double = 20.0, cy: Double = 20.0) = Polygon(LongArray(2 * n) { i ->
        val a = 2 * Math.PI * (i / 2) / n
        Math.round(1000 * (if (i % 2 == 0) cx + r * Math.cos(a) else cy + r * Math.sin(a))) })
    fun square(side: Double, x0: Double = 10.0, y0: Double = 10.0) = Polygon(longArrayOf(
        Math.round(x0 * 1000), Math.round(y0 * 1000), Math.round((x0 + side) * 1000), Math.round(y0 * 1000),
        Math.round((x0 + side) * 1000), Math.round((y0 + side) * 1000), Math.round(x0 * 1000), Math.round((y0 + side) * 1000)))
    /** [layers] layers of one wall loop [loop] each (the outer wall), printed area the loop itself. */
    fun walls(loop: Polygon, layers: Int) = SeamDecision.Walls(List(layers) { listOf(listOf(loop)) }, List(layers) { listOf(loop) },
        LongArray(layers) { (it + 1) * 200L }, 400, 400, 400)
    val params = SeamDecision.Params(30.0, 10_000, 0.25, 60.0, Visibility(0.3, 0.0, "test"), SeamScar.Shares(0.25, 0.25, 30.0), 1000.0, "test")

    "a cylinder: all the weight on quality scarfs its seams; all of it on speed does not" {
        val w = walls(ring(64, 8.0), 20)
        val quality = SeamDecision.decide("cyl", w, params, Weights.parse("quality=3,speed=0", "test"), null, null, null)
        quality.value.scarf shouldBe "smooth"   // the smooth loops are all of them: smooth and all tie, the first wins
        quality.overridden shouldBe false
        val speed = SeamDecision.decide("cyl", w, params, Weights.parse("quality=0,speed=1", "test"), null, null, null)
        speed.value.scarf shouldBe "none"
        // the measure behind it: a scarf's seam counts a quarter, 19 layers above the first scarfed
        val m = SeamDecision.measure(w, params, SeamDecision.Choice(SeamDecision.Placement.BACK, "all"))
        m.scar.scarfSeams shouldBe 19
        m.scarfExtraS shouldBe (19 * 10.0 / 60.0)
    }

    "a box under equal weights keeps point seams in its corners: the corner hides them and a scarf costs a pass" {
        val d = SeamDecision.decide("box", walls(square(20.0), 20), params, Weights.parse("quality=1,speed=1", "test"), null, null, null)
        d.value.scarf shouldBe "none"
        d.describe() shouldContain "seam scar"
    }

    "a lock is kept, nothing weighed" {
        val d = SeamDecision.decide("cyl", walls(ring(64, 8.0), 5), params, Weights.parse("quality=1", "test"), "scarf_seam", "corner_seam", "plan.seam (test)")
        d.overridden shouldBe true
        d.value.scarf shouldBe "locked"
        d.value.placement shouldBe SeamDecision.Placement.CORNERS
    }

    "the candidates follow what the settings allow" {
        SeamDecision.candidates(params).map { it.toString() } shouldBe listOf("back/none", "corners/none", "least_visible/none", "back/smooth", "corners/smooth", "least_visible/smooth", "back/all", "corners/all", "least_visible/all")
        SeamDecision.candidates(SeamDecision.Params(null, null, null, null, null, SeamScar.Shares.DEFAULT, null)).map { it.toString() } shouldBe listOf("back/none")
    }

    "seen from the front, a D-shape (corners on the front, a round back): least_visible weighs each point's viewing directions by quadrant and counts a corner as the measure does, so the weights and the corner share decide between a front corner and the back" {
        val sample = plymeld.core.decide.Exposure.Sample(16, 3, 60.0, 20_000, 250)
        fun view(q: Visibility.Quadrants) = Visibility(0.3, 0.0, "test", view = Visibility.viewOf("front"), sample = sample, quadrants = q)
        val front = Visibility.Quadrants(8.0, 2.0, 2.0, 1.0)     // front 8, left and right 2, back 1
        // counter-clockwise: the front edge from (0,0) to (20,0), then the arc over the back down to (0,0)
        val n = 32
        val pts = ArrayList<Long>()
        pts += 0L; pts += 0L; pts += 20000L; pts += 0L
        for (k in 1 until n) { val a = Math.PI * k / n; pts += Math.round(10000 + 10000 * Math.cos(a)); pts += Math.round(10000 * Math.sin(a)) }
        val d = Polygon(pts.toLongArray())
        val backY = d.xy.filterIndexed { i, _ -> i % 2 == 1 }.max()
        fun place(loop: Polygon, vis: Visibility, corner: Double) = plymeld.core.Seams.place(loop, 30.0, 800.0, leastVisible = true, vis = vis, cornerShare = corner,
            exposure = vis.exposure(List(20) { listOf(loop) }, LongArray(20) { (it + 1) * 200L }), layer = 10).loop.y(0)
        // the back reads 0.207 of an open wall facing the viewer (ExposureTest: by hand); a front corner faces the front / side
        // edge, about 0.80, so 0.20 in a corner at the measured corner share 0.25: a near-tie, which goes to the point the
        // viewer sees least, the back
        place(d, view(front), 0.25) shouldBe backY
        place(d, view(front), 0.3) shouldBe backY            // the corner at 0.24: the back
        place(d, view(Visibility.Quadrants(8.0, 2.0, 2.0, 0.5)), 0.25) shouldBe backY   // the back weighted less: the back
        // from all around every open point reads 1: the corner share alone puts it in a front corner (the control)
        place(d, Visibility(0.3, 0.0, "test", sample = sample, quadrants = front), 0.25) shouldBe 0L
        // the box seen from the front: a back corner, the least seen of all
        place(square(20.0), view(front), 0.25) shouldBe 30000L
        // the measure agrees with the placement: least_visible never reads worse than back, nor worse than corners by more
        // than the tie band it may give up for the point seen least (Seams.TIE_V of a point seam per layer: 20 layers, a
        // line 0.4 mm wide)
        for (q in listOf(front, Visibility.Quadrants(8.0, 2.0, 2.0, 0.5), Visibility.Quadrants.EQUAL)) {
            val p = SeamDecision.Params(30.0, 10_000, 0.25, 60.0, view(q), SeamScar.Shares(0.25, 0.25, 30.0), 1000.0, "test")
            val w = walls(d, 20)
            fun scar(pl: SeamDecision.Placement, scarf: String = "none") = SeamDecision.measure(w, p, SeamDecision.Choice(pl, scarf)).scar.scarMm2
            (scar(SeamDecision.Placement.LEAST_VISIBLE) <= scar(SeamDecision.Placement.BACK) + 1e-9) shouldBe true
            (scar(SeamDecision.Placement.LEAST_VISIBLE) <= scar(SeamDecision.Placement.CORNERS) + 20 * plymeld.core.Seams.TIE_V * 0.4 * 0.4 + 1e-9) shouldBe true
            // quality alone scarfs where the back is seen less than the front (a scarf is a quarter of a point seam wherever it
            // sits, at the back cheaper than a point in a front corner); with every quadrant alike a front corner hides a point
            // seam as well as a scarf hides it at the back (both a quarter): a tie, no scarf first
            SeamDecision.decide("d", w, p, Weights.parse("quality=1,speed=0", "test"), null, null, null).value.scarf shouldBe
                (if (q === Visibility.Quadrants.EQUAL) "none" else "smooth")
        }
        // seen from all around, nothing tells the corners apart: the box keeps corner seams with no scarf as before
        val around = SeamDecision.decide("box", walls(square(20.0), 20), params, Weights.parse("quality=1,speed=1", "test"), null, null, null)
        around.value.scarf shouldBe "none"
    }
})
