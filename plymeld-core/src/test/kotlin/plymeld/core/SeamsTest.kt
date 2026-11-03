package plymeld.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import plymeld.geom.Polygon

/**
 * Seams at corners (plan.seam = "corner_seam"). Loops in µm, counter-clockwise outlines (material on the
 * left). Expected seams worked by hand from the rule: inside corners first, the sharpest, ties to the back-most
 * (largest y, then smallest x); a loop with no corner at its back.
 */
class SeamsTest : StringSpec({
    fun poly(vararg mm: Double) = Polygon(LongArray(mm.size) { Math.round(mm[it] * 1000) })
    fun start(p: Seams.Placed) = p.loop.x(0) / 1000.0 to p.loop.y(0) / 1000.0
    val span = 880.0           // two 0.44 mm lines

    "a square: a corner, the back-most of the four (largest y, then smallest x): (0, 10)" {
        val p = Seams.place(poly(0.0, 0.0, 10.0, 0.0, 10.0, 10.0, 0.0, 10.0), 30.0, span)
        p.atCorner shouldBe true
        start(p) shouldBe (0.0 to 10.0)
    }
    "an L: its one inside corner (6, 4) wins over the five outside ones, equally sharp and further back" {
        val p = Seams.place(poly(0.0, 0.0, 10.0, 0.0, 10.0, 4.0, 6.0, 4.0, 6.0, 10.0, 0.0, 10.0), 30.0, span)
        p.atCorner shouldBe true
        start(p) shouldBe (6.0 to 4.0)
    }
    "a hole (clockwise square): its corners are inside corners of the material around it, the back-most one" {
        val p = Seams.place(poly(0.0, 0.0, 0.0, 10.0, 10.0, 10.0, 10.0, 0.0), 30.0, span)
        Seams.turns(p.loop, span).all { it < 0 } shouldBe true
        start(p) shouldBe (0.0 to 10.0)
    }
    "a circle of 120 facets (radius 10 mm, 3 deg a facet, about 10 deg across the span): no corner, the seam at its back" {
        val n = 120
        val xy = DoubleArray(2 * n) { k -> val a = 2 * Math.PI * (k / 2) / n; if (k % 2 == 0) 10 * Math.cos(a) else 10 * Math.sin(a) }
        val p = Seams.place(poly(*xy), 30.0, span)
        p.atCorner shouldBe false
        start(p).second shouldBe 10.0
    }
    "in line with the layer below: a layer whose back-most corner is (10, 11) keeps the seam at (0, 10), the corner over the seam below" {
        val lower = poly(0.0, 0.0, 10.0, 0.0, 10.0, 10.0, 0.0, 10.0)
        val upper = poly(0.0, 0.0, 10.0, 0.0, 10.0, 11.0, 0.0, 10.0)
        val placed = Seams.alignAll(listOf(listOf(listOf(lower)), listOf(listOf(upper))), 30.0, spanUm = { _, _ -> span })
        start(placed[0][0][0]) shouldBe (0.0 to 10.0)
        start(placed[1][0][0]) shouldBe (0.0 to 10.0)
        // control: the upper layer on its own takes its back-most corner
        start(Seams.place(upper, 30.0, span)) shouldBe (10.0 to 11.0)
    }
    "in line with the layer below: a circle with a seam below at (10, 0) starts at its point nearest it, not at its back" {
        val n = 120
        val xy = DoubleArray(2 * n) { k -> val a = 2 * Math.PI * (k / 2) / n; if (k % 2 == 0) 10 * Math.cos(a) else 10 * Math.sin(a) }
        val p = Seams.place(poly(*xy), 30.0, span, longArrayOf(10_000, 0))
        p.atCorner shouldBe false
        start(p) shouldBe (10.0 to 0.0)
    }
    // An island like a helix's: 2 x 8 mm, one layer (0.1 mm) moved 0.15 mm along +y from the one below, so
    // its +y end hangs over air. Its outer loop 0.22 mm in (half a 0.44 mm line), its inner loop 0.66 mm in. Worked by hand:
    // the outer loop's +y corners lie 0.07 mm inside the lower layer's end, a share of (0.22 - 0.07) / 0.44 = 0.34 over air,
    // past where the overhang slow-down starts (h / 2w = 0.11); its -y corners are 0.22 mm inside the lower layer (its side;
    // share 0). The inner loop's +y corners lie 0.44 mm from the outer loop's +y edge, within 0.44 + 0.22 of it, so they count
    // as behind that end; its -y corners have only held-up outer edges that near. The +y end is also the loops' back (largest
    // y), where seams went before.
    val below = poly(0.0, 0.0, 2.0, 0.0, 2.0, 8.0, 0.0, 8.0)
    val outerLoop = poly(0.22, 0.37, 1.78, 0.37, 1.78, 7.93, 0.22, 7.93)
    val innerLoop = poly(0.66, 0.81, 1.34, 0.81, 1.34, 7.49, 0.66, 7.49)
    val flags = Overhangs.seamOverhangs(listOf(listOf(outerLoop), listOf(innerLoop)), listOf(below), 440, 100)!!
    fun overhangingEnd(p: Seams.Placed) = start(p).second > 7.0

    "an island hanging over its layer below at one end: the outer loop's corners there overhang, the others do not" {
        flags[0][0].toList() shouldBe listOf(false, false, true, true)
        flags[1][0].toList() shouldBe listOf(false, false, true, true)    // the inner loop's corners behind that end
        // control: the same island NOT moved (over the lower layer exactly) has nothing overhanging
        Overhangs.seamOverhangs(listOf(listOf(poly(0.22, 0.22, 1.78, 0.22, 1.78, 7.78, 0.22, 7.78))), listOf(below), 440, 100) shouldBe null
    }
    "an island hanging over its layer below at one end: no loop starts there (corners, the back, and in line with a seam below there)" {
        for ((k, loop) in listOf(outerLoop, innerLoop).withIndex()) {
            val f = flags[k][0]
            overhangingEnd(Seams.place(loop, 30.0, span, overhangs = f)) shouldBe false
            overhangingEnd(Seams.place(loop, null, 0.0, overhangs = f)) shouldBe false                        // plan.seam = "back_seam"
            overhangingEnd(Seams.place(loop, 30.0, span, longArrayOf(1_780, 7_930), f)) shouldBe false         // a seam below at that end
            Seams.place(loop, 30.0, span, overhangs = f).atCorner shouldBe true                                  // still at a corner
        }
    }
    "control: without the overhang test (the fix off) every one of those seams is at the overhanging end" {
        for (loop in listOf(outerLoop, innerLoop)) {
            overhangingEnd(Seams.place(loop, 30.0, span)) shouldBe true
            overhangingEnd(Seams.place(loop, null, 0.0)) shouldBe true
            overhangingEnd(Seams.place(loop, 30.0, span, longArrayOf(1_780, 7_930))) shouldBe true
        }
    }
    "a loop wholly over air keeps its usual seam (no held-up place to prefer)" {
        val all = BooleanArray(4) { true }
        start(Seams.place(outerLoop, 30.0, span, overhangs = all)) shouldBe start(Seams.place(outerLoop, 30.0, span))
    }
    "control: an octagon (45 deg turns) has corners at a 30 deg threshold and none at 50" {
        val xy = DoubleArray(16) { k -> val a = 2 * Math.PI * (k / 2) / 8 + Math.PI / 8; if (k % 2 == 0) 10 * Math.cos(a) else 10 * Math.sin(a) }
        Seams.place(poly(*xy), 30.0, span).atCorner shouldBe true
        Seams.place(poly(*xy), 50.0, span).atCorner shouldBe false
    }

    // back seams in line: a 100 x 50 mm block whose back edge is 21 points within +-0.2 mm of y 50, differing
    // layer to layer (a nearly flat back): placed loop by loop the back-most point jumps along the back; aligned it stays
    "back seams: aligned layer to layer within two line widths of the back; control: loop by loop they scatter" {
        val rnd = java.util.Random(7)
        val layers = List(30) {
            val xy = ArrayList<Long>()
            xy += listOf(0L, 0L, 100_000L, 0L)
            for (k in 20 downTo 0) { xy += k * 5_000L; xy += 50_000L + (rnd.nextInt(401) - 200) }   // back edge, right to left
            Polygon(xy.toLongArray())
        }
        val aligned = Seams.alignAll(layers.map { listOf(listOf(it)) }, null, back = true, spanUm = { _, _ -> 880.0 }).map { it[0][0].loop.x(0) }
        val alone = layers.map { Planner.seamAtBack(it).x(0) }
        val spreadAligned = aligned.max() - aligned.min(); val spreadAlone = alone.max() - alone.min()
        println("seam x over 30 layers: aligned spread $spreadAligned um, loop by loop $spreadAlone um")
        spreadAligned shouldBe 0L
        (spreadAlone > 20_000) shouldBe true                          // control
        // still at the back: every aligned seam within the band of its layer's back-most point
        for ((i, lp) in layers.withIndex()) {
            val placed = Seams.alignAll(layers.map { listOf(listOf(it)) }, null, back = true, spanUm = { _, _ -> 880.0 })[i][0][0].loop
            val maxY = (0 until lp.size).maxOf { lp.y(it) }
            (placed.y(0) >= maxY - 880) shouldBe true
        }
    }
})
