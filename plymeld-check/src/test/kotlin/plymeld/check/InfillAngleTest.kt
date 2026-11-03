package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import plymeld.core.decide.InfillAngle
import plymeld.mesh.TriangleSoup

/** A box x0..x1, y0..y1, z0..z1, outward-facing (its top counter-clockwise from above). */
private fun box(x0: Float, y0: Float, x1: Float, y1: Float, z1: Float): TriangleSoup {
    val v = arrayOf(floatArrayOf(x0, y0, 0f), floatArrayOf(x1, y0, 0f), floatArrayOf(x1, y1, 0f), floatArrayOf(x0, y1, 0f),
        floatArrayOf(x0, y0, z1), floatArrayOf(x1, y0, z1), floatArrayOf(x1, y1, z1), floatArrayOf(x0, y1, z1))
    val f = intArrayOf(0, 2, 1, 0, 3, 2, 4, 5, 6, 4, 6, 7, 0, 1, 5, 0, 5, 4, 1, 2, 6, 1, 6, 5, 2, 3, 7, 2, 7, 6, 3, 0, 4, 3, 4, 7)
    val out = FloatArray(12 * 9)
    for (t in 0 until 12) for (k in 0 until 3) for (d in 0 until 3) out[9 * t + 3 * k + d] = v[f[3 * t + k]][d]
    return TriangleSoup(out, 12)
}

/** [s] turned [deg] about the vertical through the origin. */
private fun turned(s: TriangleSoup, deg: Double): TriangleSoup {
    val c = Math.cos(Math.toRadians(deg)); val n = Math.sin(Math.toRadians(deg))
    val out = s.coords.copyOf()
    for (i in 0 until s.count * 3) {
        val x = s.coords[3 * i].toDouble(); val y = s.coords[3 * i + 1].toDouble()
        out[3 * i] = (c * x - n * y).toFloat(); out[3 * i + 1] = (n * x + c * y).toFloat()
    }
    return TriangleSoup(out, s.count)
}

/** The infill direction: 45 deg to the footprint's long axis. */
class InfillAngleTest : StringSpec({
    val bar = box(-20f, -5f, 20f, 5f, 3f)                  // 40 x 10 mm: long axis along X

    "a bar along X: long axis 0 deg, infill 45 deg; turned 30 deg: 30 and 75" {
        InfillAngle.longAxisDeg(listOf(bar)) shouldBe (0.0 plusOrMinus 1e-3)
        InfillAngle.degrees(listOf(bar)) shouldBe (45.0 plusOrMinus 1e-3)
        InfillAngle.longAxisDeg(listOf(turned(bar, 30.0))) shouldBe (30.0 plusOrMinus 1e-3)
        InfillAngle.degrees(listOf(turned(bar, 30.0))) shouldBe (75.0 plusOrMinus 1e-3)
        InfillAngle.degrees(listOf(turned(bar, 150.0))) shouldBe (15.0 plusOrMinus 1e-3)   // 150 + 45 = 195 -> 15
    }

    "control: a square has no long axis (any turn): 45 deg, the same as before the rule" {
        InfillAngle.degrees(listOf(turned(box(-10f, -10f, 10f, 10f, 3f), 20.0))) shouldBe (45.0 plusOrMinus 1e-3)
    }

    "only the footprint counts: a tall bar along Y on a wide slab along X takes the slab's axis" {
        val slab = box(-30f, -5f, 30f, 5f, 2f); val post = box(-2f, -4f, 2f, 4f, 40f)
        InfillAngle.longAxisDeg(listOf(slab, post)) shouldBe (0.0 plusOrMinus 1e-3)
    }
})
