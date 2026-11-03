package plymeld.core.decide

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import plymeld.mesh.LayerPlan
import plymeld.mesh.TriangleSoup

/**
 * The band depth (CurvedDepth) on a block 20 x 10 mm whose top ramps from 8.0 (x = 0) to 8.5 (x = 20). The band must
 * start below the top's lowest point (8.0), and there a layer of planned thickness p is p (8 - zs) / (8.5 - zs) thick. The
 * plan (first 0.28, then 0.2) keeps its grid up to the last top under the start and divides the rest evenly (evenAbove, as
 * the slicer plans a curved band): a start between 7.48 and 7.68 leaves 1.02 mm in 6 layers of 0.17, which hold while
 * 0.17 (8 - zs) >= 0.08 (8.5 - zs), zs <= 68 / 9 = 7.556 by hand; a higher base leaves none (7.68: 5 of 0.164, zs <= 7.52).
 * (Earlier, the plan left a remainder of two 0.11 mm layers at the top, which bound the start at 6.667.)
 */
class CurvedDepthTest : StringSpec({
    val c = ArrayList<Float>()
    fun tri(vararg p: Double) { p.forEach { c += it.toFloat() } }
    fun quad(a: DoubleArray, b: DoubleArray, cc: DoubleArray, d: DoubleArray) { tri(*a, *b, *cc); tri(*a, *cc, *d) }
    fun v(x: Double, y: Double, z: Double) = doubleArrayOf(x, y, z)
    quad(v(0.0, 0.0, 8.0), v(20.0, 0.0, 8.5), v(20.0, 10.0, 8.5), v(0.0, 10.0, 8.0))   // top, normal up
    quad(v(0.0, 0.0, 0.0), v(0.0, 10.0, 0.0), v(20.0, 10.0, 0.0), v(20.0, 0.0, 0.0))   // bottom, normal down
    quad(v(0.0, 0.0, 0.0), v(20.0, 0.0, 0.0), v(20.0, 0.0, 8.5), v(0.0, 0.0, 8.0))     // front (-y)
    quad(v(20.0, 10.0, 0.0), v(0.0, 10.0, 0.0), v(0.0, 10.0, 8.0), v(20.0, 10.0, 8.5)) // back (+y)
    quad(v(0.0, 10.0, 0.0), v(0.0, 0.0, 0.0), v(0.0, 0.0, 8.0), v(0.0, 10.0, 8.0))     // left (-x)
    quad(v(20.0, 0.0, 0.0), v(20.0, 10.0, 0.0), v(20.0, 10.0, 8.5), v(20.0, 0.0, 8.5)) // right (+x)
    val ramp = TriangleSoup(c.toFloatArray(), c.size / 9)

    // the test's own reading of a start: the thinnest real layer at the lowest top (8.0), from the planned layer tops
    fun thinnestAt(zs: Double): Double {
        val tops = LayerPlan.uniform(0.0, 8.5, 0.28, 0.2, 0.08).evenAbove(maxOf(zs, 0.28), 0.2).tops
        fun real(z: Double) = if (z <= zs) z else zs + (z - zs) * (8.0 - zs) / (8.5 - zs)
        var out = Double.POSITIVE_INFINITY
        for (k in tops.indices) if (tops[k] > zs + 1e-9) out = minOf(out, real(tops[k]) - real(if (k == 0) 0.0 else tops[k - 1]))
        return out
    }

    "the band starts below the whole top, as high as keeps every layer at least the thinnest there" {
        val d = CurvedDepth.decide(listOf(ramp), 0.28, 0.2, 0.08).shouldNotBeNull()
        d.fromMm shouldBeLessThan 68.0 / 9 + 1e-6      // the hand bound
        d.fromMm shouldBeGreaterThan 68.0 / 9 - 0.02   // within two search steps of it
        thinnestAt(d.fromMm) shouldBeGreaterThan 0.08 - 1e-6
    }
    "control: a start one step higher leaves a layer thinner than the tool's thinnest" {
        val d = CurvedDepth.decide(listOf(ramp), 0.28, 0.2, 0.08).shouldNotBeNull()
        thinnestAt(d.fromMm + CurvedDepth.STEP_MM) shouldBeLessThan 0.08 - 1e-9
    }
    "control: a start just under the crown (8.5), as the first version picked, bends layers too thin at the low end" {
        thinnestAt(8.4) shouldBeLessThan 0.08
    }
    "no start at all when the thinnest allowed is thicker than a layer" {
        CurvedDepth.decide(listOf(ramp), 0.28, 0.2, 0.25).shouldBeNull()
    }
})
