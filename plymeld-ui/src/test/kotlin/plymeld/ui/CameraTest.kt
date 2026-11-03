package plymeld.ui

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The 3D view's camera (pure arithmetic, no display): each control does what it promises, and each claim has a control
 * that breaks it.
 */
class CameraTest : StringSpec({
    val aspect = 1.6
    fun dist(a: DoubleArray, b: DoubleArray) = Math.sqrt((0..2).sumOf { (a[it] - b[it]) * (a[it] - b[it]) })

    "orbit keeps the eye at its distance from the target, and the pitch within straight up and down" {
        val c = Camera(target = doubleArrayOf(10.0, 20.0, 5.0), distance = 150.0)
        c.orbit(123.0, -57.0)
        dist(c.eye(), c.target) shouldBe (150.0 plusOrMinus 1e-9)
        c.orbit(0.0, 10_000.0)
        c.pitchDeg shouldBe 90.0
        // straight down the basis is still whole: right and up perpendicular, unit, the picture's up the bed's +Y at yaw -90
        c.yawDeg = -90.0
        Camera.dot(c.right(), c.up()) shouldBe (0.0 plusOrMinus 1e-12)
        c.up()[1] shouldBe (1.0 plusOrMinus 1e-12)
    }
    "zoom at the cursor keeps the point under the cursor where it was, in perspective and orthographic" {
        for (ortho in listOf(false, true)) {
            val c = Camera(target = doubleArrayOf(0.0, 0.0, 10.0), distance = 200.0, orthographic = ortho)
            val (nx, ny) = 0.6 to -0.35
            // the world point under the cursor, on the plane through the target facing the view
            val h = c.halfHeight(); val r = c.right(); val u = c.up()
            val p = DoubleArray(3) { c.target[it] + r[it] * nx * h * aspect + u[it] * ny * h }
            c.project(p, aspect).let { it[0] shouldBe (nx plusOrMinus 1e-9); it[1] shouldBe (ny plusOrMinus 1e-9) }
            c.zoomAt(0.5, nx, ny, aspect)
            c.distance shouldBe (100.0 plusOrMinus 1e-9)
            c.project(p, aspect).let { it[0] shouldBe (nx plusOrMinus 1e-9); it[1] shouldBe (ny plusOrMinus 1e-9) }
        }
    }
    "control: zooming about the middle of the view moves that point outward" {
        val c = Camera(target = doubleArrayOf(0.0, 0.0, 10.0), distance = 200.0)
        val h = c.halfHeight(); val r = c.right(); val u = c.up()
        val p = DoubleArray(3) { c.target[it] + r[it] * 0.6 * h * aspect + u[it] * -0.35 * h }
        c.zoomAt(0.5, 0.0, 0.0, aspect)
        val q = c.project(p, aspect)
        hypot(q[0] - 0.6, q[1] + 0.35) shouldBeGreaterThan 0.3
    }
    "pan moves the target with the drag: a drag of the whole view's height moves it two half-heights" {
        val c = Camera(distance = 100.0, yawDeg = -90.0, pitchDeg = 0.0)   // from the front: right = +X, up = +Z
        val h = c.halfHeight()
        c.pan(0.0, 1000.0, 1000.0)
        c.target[2] shouldBe (2 * h plusOrMinus 1e-9)
        c.pan(500.0, 0.0, 1000.0)
        c.target[0] shouldBe (-h plusOrMinus 1e-9)
    }
    "fit puts every corner of a box inside the view, in both projections and from every preset" {
        val box = doubleArrayOf(-20.0, 5.0, 0.0, 60.0, 35.0, 48.0)
        for (v in Camera.View.entries) for (ortho in listOf(false, true)) {
            val c = Camera(orthographic = ortho); c.preset(v); c.fit(box, aspect)
            for (i in 0 until 8) {
                val p = doubleArrayOf(box[if (i and 1 == 0) 0 else 3], box[if (i and 2 == 0) 1 else 4], box[if (i and 4 == 0) 2 else 5])
                val s = c.project(p, aspect)
                abs(s[0]) shouldBeLessThan 1.0
                abs(s[1]) shouldBeLessThan 1.0
            }
        }
    }
    "control: before fitting, the same box seen from far too close is partly out of view" {
        val box = doubleArrayOf(-20.0, 5.0, 0.0, 60.0, 35.0, 48.0)
        val c = Camera(target = doubleArrayOf(20.0, 20.0, 24.0), distance = 30.0); c.preset(Camera.View.ISO)
        val out = (0 until 8).any { i ->
            val s = c.project(doubleArrayOf(box[if (i and 1 == 0) 0 else 3], box[if (i and 2 == 0) 1 else 4], box[if (i and 4 == 0) 2 else 5]), aspect)
            abs(s[0]) > 1 || abs(s[1]) > 1
        }
        out shouldBe true
    }
    "the presets look where they say: front from -Y, right from +X, top from +Z" {
        val c = Camera()
        c.preset(Camera.View.FRONT); c.back()[1] shouldBe (-1.0 plusOrMinus 1e-12)
        c.preset(Camera.View.RIGHT); c.back()[0] shouldBe (1.0 plusOrMinus 1e-12)
        c.preset(Camera.View.TOP); c.back()[2] shouldBe (1.0 plusOrMinus 1e-12)
    }
    "the matrices agree with project(): a point through view and projection lands where project() says" {
        for (ortho in listOf(false, true)) {
            val c = Camera(target = doubleArrayOf(3.0, -4.0, 12.0), distance = 120.0, yawDeg = -30.0, pitchDeg = 25.0, orthographic = ortho)
            val m = Renderer.mul(c.projection(aspect, 100.0), c.view())
            val p = doubleArrayOf(15.0, 2.0, 30.0)
            val clip = DoubleArray(4) { r -> m[r] * p[0] + m[4 + r] * p[1] + m[8 + r] * p[2] + m[12 + r] }
            val s = c.project(p, aspect)
            (clip[0] / clip[3]) shouldBe (s[0] plusOrMinus 1e-5)
            (clip[1] / clip[3]) shouldBe (s[1] plusOrMinus 1e-5)
        }
    }
})
