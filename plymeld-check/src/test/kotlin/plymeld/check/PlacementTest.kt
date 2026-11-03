package plymeld.check

import plymeld.model.Overhang
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import plymeld.core.decide.Pose
import plymeld.core.decide.PoseDecision
import plymeld.core.decide.PoseFacts
import plymeld.mesh.TriangleSoup

/** A closed box as 12 outward triangles. */
private fun box(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float): TriangleSoup {
    val v = arrayOf(floatArrayOf(x0, y0, z0), floatArrayOf(x1, y0, z0), floatArrayOf(x1, y1, z0), floatArrayOf(x0, y1, z0),
        floatArrayOf(x0, y0, z1), floatArrayOf(x1, y0, z1), floatArrayOf(x1, y1, z1), floatArrayOf(x0, y1, z1))
    val f = intArrayOf(0, 2, 1, 0, 3, 2, 4, 5, 6, 4, 6, 7, 0, 1, 5, 0, 5, 4, 1, 2, 6, 1, 6, 5, 2, 3, 7, 2, 7, 6, 3, 0, 4, 3, 4, 7)
    val out = FloatArray(12 * 9)
    for (t in 0 until 12) for (k in 0 until 3) for (d in 0 until 3) out[9 * t + 3 * k + d] = v[f[3 * t + k]][d]
    return TriangleSoup(out, 12)
}

// Models built around the origin (e.g. a 3MF whose object sits at -22..155 x -25..78) keep their
// orientation but must land on the bed: `--pose as-given --placement center`.
class PlacementTest : StringSpec({
    val bed = PoseFacts(0.0, 0.0, 256.0, 256.0, 256.0, Overhang.fromAngle(30.0, 0.2, 0.4), "test", layerHeightMm = 0.2)
    // off the bed on purpose: x -50..30, y -40..0, z -10..5
    val offBed = box(-50f, -40f, -10f, 30f, 0f, 5f)

    "as given, centered: orientation kept, bottom at z = 0, footprint centered on the bed" {
        val p = PoseDecision.decide(listOf(offBed), bed, Pose.asGivenCentered()).value
        p.r.contentEquals(Pose.IDENTITY) shouldBe true
        val b = p.apply(offBed).bounds()
        b[2] shouldBe (0.0 plusOrMinus 1e-4)
        ((b[0] + b[3]) / 2) shouldBe (128.0 plusOrMinus 1e-3)
        ((b[1] + b[4]) / 2) shouldBe (128.0 plusOrMinus 1e-3)
        (b[3] - b[0]) shouldBe (80.0 plusOrMinus 1e-4)     // size unchanged: a move, not a turn
        (b[5] - b[2]) shouldBe (15.0 plusOrMinus 1e-4)
    }

    "control: plain as given leaves it where it was, off the bed" {
        val b = PoseDecision.decide(listOf(offBed), bed, Pose.asGiven()).value.apply(offBed).bounds()
        b[0] shouldBe (-50.0 plusOrMinus 1e-4)
        b[2] shouldBe (-10.0 plusOrMinus 1e-4)
    }
})
