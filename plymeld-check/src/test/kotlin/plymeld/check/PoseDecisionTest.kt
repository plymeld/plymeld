package plymeld.check

import plymeld.model.Overhang
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import plymeld.core.decide.Pose
import plymeld.core.decide.PoseDecision
import plymeld.core.decide.PoseFacts
import plymeld.mesh.TriangleSoup

/**
 * An L-shaped prism along Y (its profile in XZ: (0,0) (40,0) (40,5) (5,5) (5,30) (0,30), 20 mm deep): a 40 x 5 mm base and
 * a 5 x 25 mm wall at x 0..5. Closed, outward triangles.
 */
private fun bracket(): TriangleSoup {
    val xz = listOf(0f to 0f, 40f to 0f, 40f to 5f, 5f to 5f, 5f to 30f, 0f to 30f)
    val tris = ArrayList<FloatArray>()
    fun tri(a: FloatArray, b: FloatArray, c: FloatArray) { tris += floatArrayOf(*a, *b, *c) }
    fun p(i: Int, y: Float) = floatArrayOf(xz[i].first, y, xz[i].second)
    // the two L faces (y = 0 facing -Y, y = 20 facing +Y), each as two rectangles in XZ: the base x 0..40 z 0..5 and the
    // wall x 0..5 z 5..30
    fun rect(x0: Float, z0: Float, x1: Float, z1: Float) {
        // y = 0 face, normal -Y
        tri(floatArrayOf(x0, 0f, z0), floatArrayOf(x1, 0f, z0), floatArrayOf(x1, 0f, z1)); tri(floatArrayOf(x0, 0f, z0), floatArrayOf(x1, 0f, z1), floatArrayOf(x0, 0f, z1))
        // y = 20 face, normal +Y
        tri(floatArrayOf(x0, 20f, z0), floatArrayOf(x1, 20f, z1), floatArrayOf(x1, 20f, z0)); tri(floatArrayOf(x0, 20f, z0), floatArrayOf(x0, 20f, z1), floatArrayOf(x1, 20f, z1))
    }
    rect(0f, 0f, 40f, 5f); rect(0f, 5f, 5f, 30f)
    // the side faces: each edge of the profile (counter-clockwise in XZ) swept along Y, its normal pointing out
    for (i in xz.indices) {
        val a = p(i, 0f); val b = p((i + 1) % xz.size, 0f); val c = p((i + 1) % xz.size, 20f); val d = p(i, 20f)
        tri(a, d, c); tri(a, c, b)
    }
    val out = FloatArray(tris.size * 9)
    for ((t, f) in tris.withIndex()) System.arraycopy(f, 0, out, 9 * t, 9)
    return TriangleSoup(out, tris.size)
}

/**
 * Step 2 (pose), on a generated L-shaped bracket whose measures are worked by hand: every face is horizontal or vertical,
 * so a face needs support exactly when it faces down and does not rest on the bed.
 */
class PoseDecisionTest : StringSpec({
    val q2 = PoseFacts(0.0, 0.0, 270.0, 270.0, 256.0, Overhang.fromAngle(30.0, 0.2, 0.4), "test", layerHeightMm = 0.2)
    val overhang = Overhang.fromAngle(30.0, 0.2, 0.4)
    // pose -> (flat on the bed, needs support, height), by hand from the faces: bottom 800 mm2, the base's top 700 (z 5), the
    // wall's top 100 (z 30), x = 0 600, x = 40 100, the wall's face x = 5 500, each L face 325
    val reference = listOf(
        Triple(800.0, 0.0, 30.0),     // as given: on its bottom
        Triple(100.0, 700.0, 30.0),   // upside down: on the wall's top; the base's top hangs 25 mm up
        Triple(325.0, 0.0, 20.0),     // -Y down: on an L face
        Triple(325.0, 0.0, 20.0),     // +Y down
        Triple(600.0, 0.0, 40.0),     // -X down: on the wall's back
        Triple(100.0, 500.0, 40.0),   // +X down: on the base's end; the wall's face hangs 35 mm up
    )

    "the bracket: every orientation measured as worked by hand" {
        val soup = bracket()
        for ((i, p) in PoseDecision.candidates().withIndex()) {
            val m = PoseDecision.measure(soup, p, overhang, layerMm = 0.2)
            m.contactMm2 shouldBe (reference[i].first plusOrMinus 0.01)
            m.needsSupportMm2 shouldBe (reference[i].second plusOrMinus 0.01)
            m.heightMm shouldBe (reference[i].third plusOrMinus 0.01)
        }
    }

    "the chosen pose is placed centered on the bed, bottom at z = 0" {
        val d = PoseDecision.decide(listOf(bracket()), q2)
        println("bracket pose: ${d.why}")
        val b = d.value.apply(bracket()).bounds()
        b[2] shouldBe (0.0 plusOrMinus 1e-4)
        ((b[0] + b[3]) / 2) shouldBe (135.0 plusOrMinus 1e-3)
        ((b[1] + b[4]) / 2) shouldBe (135.0 plusOrMinus 1e-3)
    }

    "the starting orientation does not matter: an upside-down bracket ends as well placed" {
        val upright = bracket()
        val flipped = PoseDecision.candidates()[1].apply(upright)
        val a = PoseDecision.measure(upright, PoseDecision.decide(listOf(upright), q2).value, overhang, layerMm = 0.2)
        val m = PoseDecision.measure(flipped, PoseDecision.decide(listOf(flipped), q2).value, overhang, layerMm = 0.2)
        m.needsSupportMm2 shouldBe (a.needsSupportMm2 plusOrMinus 0.01)
        m.contactMm2 shouldBe (a.contactMm2 plusOrMinus 0.01)
    }

    "override locks the pose: as given leaves the mesh untouched" {
        val soup = bracket()
        val d = PoseDecision.decide(listOf(soup), q2, Pose.asGiven())
        d.overridden shouldBe true
        d.value.apply(soup).coords.contentEquals(soup.coords) shouldBe true
    }

    "center of mass over the contact: on its bottom it is, upside down (on the wall's top) it is not; a weighed risk, not a rejection" {
        val soup = bracket()
        val poses = PoseDecision.candidates()
        PoseDecision.measure(soup, poses[0], overhang, layerMm = 0.2).stable shouldBe true
        PoseDecision.measure(soup, poses[1], overhang, layerMm = 0.2).stable shouldBe false   // its center of mass is at x 13.3, the contact x 0..5
        // parts are adhered to the bed: the pose is scored, not rejected
        val d = PoseDecision.decide(listOf(soup), q2)
        d.alternatives.first { it.label == poses[1].label }.rejected shouldBe null
    }

    "weights decide: weighting only reliability picks the pose with the most bed contact (as given)" {
        val reliability = PoseFacts(0.0, 0.0, 270.0, 270.0, 256.0, overhang, "test", plymeld.core.decide.Weights.parse("reliability=1", "test"), layerHeightMm = 0.2)
        PoseDecision.decide(listOf(bracket()), reliability).value.label shouldBe "as given (-Z down)"
    }

    "control: weights must be non-negative and name known goals or metrics" {
        shouldThrow<IllegalArgumentException> { plymeld.core.decide.Weights.parse("quality=-1", "test") }
        shouldThrow<IllegalStateException> { plymeld.core.decide.Weights.parse("beauty=3", "test") }
        shouldThrow<IllegalArgumentException> { plymeld.core.decide.Weights.parse("quality=0,speed=0", "test") }
    }

    "control: no pose fits a bed smaller than the part" {
        val tiny = PoseFacts(0.0, 0.0, 10.0, 10.0, 256.0, overhang, "test", layerHeightMm = 0.2)
        shouldThrow<IllegalStateException> { PoseDecision.decide(listOf(bracket()), tiny) }.message shouldContain "exceeds the bed"
    }
})
