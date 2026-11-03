package plymeld.ui

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.random.Random

/**
 * Picking on the stage (in the Model and Layers tabs too) projects with its own inlined
 * arithmetic, for speed over every triangle and line in view. It must put a point where the camera does ([Camera.project],
 * what the overlays draw with), in both projections, or a click would pick what is drawn somewhere else.
 */
class PickProjectionTest : StringSpec({
    fun check(cam: Camera) {
        val rnd = Random(7)
        val out = DoubleArray(3)
        for (n in 0 until 200) {
            val p = doubleArrayOf(rnd.nextDouble(-80.0, 80.0), rnd.nextDouble(-80.0, 80.0), rnd.nextDouble(0.0, 120.0))
            JobState.Pick(cam, 0.0, 0.0, 1.6).at(p[0], p[1], p[2], out)
            val want = cam.project(p, 1.6)
            out[0] shouldBe (want[0] plusOrMinus 1e-9)
            out[1] shouldBe (want[1] plusOrMinus 1e-9)
        }
    }
    "the pick projection agrees with the camera's, in perspective" { check(Camera(doubleArrayOf(10.0, -5.0, 20.0), 320.0, -40.0, 25.0, false)) }
    "the pick projection agrees with the camera's, orthographic" { check(Camera(doubleArrayOf(0.0, 0.0, 0.0), 250.0, 120.0, 60.0, true)) }
    "a point in front of the eye has a positive depth, one behind it a negative one (control)" {
        val cam = Camera(doubleArrayOf(0.0, 0.0, 0.0), 200.0, 0.0, 0.0, false)
        val out = DoubleArray(3)
        JobState.Pick(cam, 0.0, 0.0, 1.0).at(0.0, 0.0, 0.0, out); (out[2] > 0) shouldBe true
        val e = cam.eye(); val b = cam.back()
        JobState.Pick(cam, 0.0, 0.0, 1.0).at(e[0] + b[0] * 10, e[1] + b[1] * 10, e[2] + b[2] * 10, out); (out[2] < 0) shouldBe true
    }
})
