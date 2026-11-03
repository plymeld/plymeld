package plymeld.mesh

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe

/**
 * Adaptive layer height (flat layers following the surfaces, LayerPlan.adaptive) on a shape whose answer is known
 * from its geometry: a 20 x 20 x 5 mm block (vertical sides) carrying a 45° frustum from a 10 x 10
 * base at z = 5 to a 2 x 2 top at z = 9. The block's top is a flat ring at z = 5; the frustum's top
 * is flat at z = 9.
 */
class AdaptiveLayersTest : StringSpec({
    val soup = blockWithFrustum()
    val first = 0.2; val min = 0.08; val max = 0.28; val step = 0.08
    val p = LayerPlan.adaptive(soup, first, min, max, step)
    fun heights(t: DoubleArray) = DoubleArray(t.size) { i -> t[i] - (if (i == 0) 0.0 else t[i - 1]) }
    // the deepest stair-step any layer leaves on the frustum's 45° sides (|nz| = cos 45°)
    fun worstStepOnFrustum(t: DoubleArray): Double {
        val h = heights(t)
        return t.indices.filter { i -> t[i] > 5.0 + 1e-9 && t[i] - h[i] < 9.0 - 1e-9 }.maxOf { h[it] } * Math.cos(Math.toRadians(45.0))
    }

    "layer tops land on both flat faces: the block's top ring and the frustum's top" {
        p.tops.any { Math.abs(it - 5.0) < 1e-6 } shouldBe true
        p.tops.last() shouldBe (9.0 plusOrMinus 1e-6)
    }
    "a landing height (a pause) is a layer top; control: without it, no top is there" {
        LayerPlan.adaptive(soup, first, min, max, step, listOf(2.345)).tops.any { Math.abs(it - 2.345) < 1e-6 } shouldBe true
        p.tops.any { Math.abs(it - 2.345) < 1e-6 } shouldBe false
    }
    "a landing wins over a flat face closer to it than the thinnest layer" {
        val q = LayerPlan.adaptive(soup, first, min, max, step, listOf(5.05)).tops
        q.any { Math.abs(it - 5.05) < 1e-6 } shouldBe true
        q.any { Math.abs(it - 5.0) < 1e-6 } shouldBe false
    }
    "composite: uniform 0.2 below z 5, adaptive above — the hand-over at 5 is a layer top; the uniform band's layers are all 0.2" {
        val q = LayerPlan.composite(soup, first, listOf(LayerPlan.Band(0.0, 5.0, false), LayerPlan.Band(5.0, 9.0, true)), 0.2, min, max, step)
        val h = heights(q.tops)
        q.tops.any { Math.abs(it - 5.0) < 1e-6 } shouldBe true
        for (i in 1 until h.size) if (q.tops[i] <= 5.0 + 1e-9) h[i] shouldBe (0.2 plusOrMinus 1e-6)
        (worstStepOnFrustum(q.tops) <= step + 1e-9) shouldBe true
    }
    "composite: adaptive below z 3, uniform 0.08 above — the uniform band ignores the flat ring at 5 and keeps one height; control: adaptive alone lands on 5" {
        val q = LayerPlan.composite(soup, first, listOf(LayerPlan.Band(0.0, 3.0, true), LayerPlan.Band(3.0, 9.0, false)), 0.08, min, max, step)
        val h = heights(q.tops)
        q.tops.any { Math.abs(it - 3.0) < 1e-6 } shouldBe true
        for (i in 1 until h.size) if (q.tops[i] > 3.0 + 1e-9) h[i] shouldBe (0.08 plusOrMinus 1e-6)
        // 5.0 is a whole number of 0.08 layers above 3, so it is a top here by arithmetic, not by landing:
        // the control is that the band's layers are all equal, which landing on a flat would break elsewhere
        q.tops.count { it > 3.0 + 1e-9 } shouldBe 75
    }
    "composite: a landing inside a uniform band is reached by evening the band out, not by a thick last layer" {
        val q = LayerPlan.composite(soup, first, listOf(LayerPlan.Band(0.0, 3.0, true), LayerPlan.Band(3.0, 9.0, false)), 0.08, min, max, step, listOf(5.05))
        val h = heights(q.tops)
        q.tops.any { Math.abs(it - 5.05) < 1e-6 } shouldBe true
        // 3 -> 5.05 in equal layers (the layer height is the tool's thinnest, so a little over it)
        val stretch = h.indices.filter { q.tops[it] > 3.0 + 1e-9 && q.tops[it] <= 5.05 + 1e-9 }.map { h[it] }
        (stretch.max() - stretch.min() < 1e-6) shouldBe true
        (stretch.min() >= min - 1e-9) shouldBe true
    }
    // the transition between layer heights (heightRatio): the block's sides are vertical, so a band following the
    // surfaces there prints 0.28 mm layers, and a band of one 0.08 mm height beside it is a 3.5x jump without it
    fun ratios(t: DoubleArray): List<Double> { val h = heights(t); return (2 until h.size).map { maxOf(h[it] / h[it - 1], h[it - 1] / h[it]) } }
    "transition up: 0.08 mm one-height band below z 3, following above; every layer after the first at most 1.5x the one below; control: without the ratio the first layer above 3 is 3.5x" {
        val bands = listOf(LayerPlan.Band(0.0, 3.0, false), LayerPlan.Band(3.0, 9.0, true))
        val q = LayerPlan.composite(soup, first, bands, 0.08, min, max, step, heightRatio = 1.5)
        val h = heights(q.tops)
        q.tops.any { Math.abs(it - 3.0) < 1e-6 } shouldBe true
        q.tops.last() shouldBe (9.0 plusOrMinus 1e-6)
        for (i in 2 until h.size) (h[i] <= h[i - 1] * 1.5 + 1e-9) shouldBe true
        for (i in 1 until h.size) { (h[i] >= min - 1e-9) shouldBe true; (h[i] <= max + 1e-9) shouldBe true }
        (worstStepOnFrustum(q.tops) <= step + 1e-9) shouldBe true
        // the first layers above 3 ramp: 0.12, 0.18, 0.27 (the fourth could be 0.28; it is evened out towards the flat ring at 5)
        val up = h.indices.filter { q.tops[it] > 3.0 + 1e-9 }.take(4).map { h[it] }
        up[0] shouldBe (0.12 plusOrMinus 1e-6); up[1] shouldBe (0.18 plusOrMinus 1e-6); up[2] shouldBe (0.27 plusOrMinus 1e-6)
        (up[3] <= 0.28 + 1e-9 && up[3] >= 0.2) shouldBe true
        val cq = LayerPlan.composite(soup, first, bands, 0.08, min, max, step)
        // the control's first layer above 3 is 0.25 (3 to the flat ring at 5 evened into 8 layers): over 3x the 0.08 below it
        (heights(cq.tops)[cq.tops.indexOfFirst { it > 3.0 + 1e-9 }] / 0.08 > 3.0) shouldBe true
    }
    "transition down: following below z 3 (0.28 mm layers), 0.08 mm one-height band above; the layers before 3 step down by about 1.5x each, landing on 3; control: without the ratio the layer under 3 is 0.28" {
        val bands = listOf(LayerPlan.Band(0.0, 3.0, true), LayerPlan.Band(3.0, 9.0, false))
        val q = LayerPlan.composite(soup, first, bands, 0.08, min, max, step, heightRatio = 1.5)
        val h = heights(q.tops)
        q.tops.any { Math.abs(it - 3.0) < 1e-6 } shouldBe true
        val below = h.indices.filter { q.tops[it] <= 3.0 + 1e-9 }
        // the layer under the boundary is at most 1.5 x 0.08
        (h[below.last()] <= 0.12 + 1e-6) shouldBe true
        // no step down steeper than the ratio (to a few percent: the descent fills the room exactly), none up either
        for (r in ratios(q.tops.copyOfRange(0, below.last() + 2))) (r <= 1.5 * 1.05) shouldBe true
        for (i in 1 until h.size) { (h[i] >= min - 1e-9) shouldBe true; (h[i] <= max + 1e-9) shouldBe true }
        for (i in 1 until h.size) if (q.tops[i] > 3.0 + 1e-9) h[i] shouldBe (0.08 plusOrMinus 1e-6)
        val cq = LayerPlan.composite(soup, first, bands, 0.08, min, max, step)
        heights(cq.tops)[cq.tops.indexOfLast { it <= 3.0 + 1e-9 }] shouldBe (0.28 plusOrMinus 1e-6)
    }
    "the ratio leaves a plan without a thinner band unchanged, and a ratio of 1 or less is refused" {
        LayerPlan.composite(soup, first, listOf(LayerPlan.Band(0.0, 9.0, false)), 0.2, min, max, step, heightRatio = 1.5).tops.toList() shouldBe
            LayerPlan.composite(soup, first, listOf(LayerPlan.Band(0.0, 9.0, false)), 0.2, min, max, step).tops.toList()
        io.kotest.assertions.throwables.shouldThrow<IllegalArgumentException> { LayerPlan.composite(soup, first, listOf(LayerPlan.Band(0.0, 9.0, true)), 0.2, min, max, step, heightRatio = 1.0) }
    }
    "no layer on the sloped sides leaves a deeper stair-step than asked" {
        (worstStepOnFrustum(p.tops) <= step + 1e-9) shouldBe true
    }
    "control: the same check fails on fixed 0.28 mm layers, so it can fail" {
        (worstStepOnFrustum(LayerPlan.fixed(0.0, 9.0, first, max).tops) <= step + 1e-9) shouldBe false
    }
    "the vertical block prints in thick layers: far fewer layers than fixed thin ones" {
        val below = p.tops.count { it <= 5.0 + 1e-9 }
        below shouldBeLessThanOrEqual Math.ceil((5.0 - first) / max).toInt() + 2
        LayerPlan.fixed(0.0, 5.0, first, min).count shouldBeGreaterThan 2 * below
    }
    "every layer within the tool's limits; the first is the first-layer height; slices mid-layer" {
        val h = heights(p.tops)
        h[0] shouldBe (first plusOrMinus 1e-9)
        for (i in 1 until h.size) { (h[i] >= min - 1e-9) shouldBe true; (h[i] <= max + 1e-9) shouldBe true }
        for (i in p.tops.indices) p.slices[i] shouldBe ((p.tops[i] - h[i] / 2) plusOrMinus 1e-9)
    }
})

/** Triangles of the block-with-frustum surface (winding does not matter to the layer planner). */
private fun blockWithFrustum(): TriangleSoup {
    val t = ArrayList<Float>()
    fun tri(vararg v: Double) { for (x in v) t.add(x.toFloat()) }
    fun quad(a: DoubleArray, b: DoubleArray, c: DoubleArray, d: DoubleArray) { tri(*a, *b, *c); tri(*a, *c, *d) }
    fun sq(h: Double, z: Double) = listOf(doubleArrayOf(-h, -h, z), doubleArrayOf(h, -h, z), doubleArrayOf(h, h, z), doubleArrayOf(-h, h, z))
    val b0 = sq(10.0, 0.0); val b1 = sq(10.0, 5.0); val f0 = sq(5.0, 5.0); val f1 = sq(1.0, 9.0)
    quad(b0[0], b0[1], b0[2], b0[3])
    for (k in 0 until 4) {
        val n = (k + 1) % 4
        quad(b0[k], b0[n], b1[n], b1[k])     // block side
        quad(b1[k], b1[n], f0[n], f0[k])     // flat ring at z = 5
        quad(f0[k], f0[n], f1[n], f1[k])     // frustum side, 45°
    }
    quad(f1[0], f1[1], f1[2], f1[3])
    return TriangleSoup(t.toFloatArray(), t.size / 9)
}
