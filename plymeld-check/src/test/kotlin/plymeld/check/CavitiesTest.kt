package plymeld.check

import plymeld.model.Overhang
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import plymeld.core.decide.Cavities
import plymeld.core.decide.Pose
import plymeld.core.decide.PoseDecision
import plymeld.mesh.TriangleSoup

/** A closed box as 12 triangles; [inward] flips every face (a void's surface). */
private fun box(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, inward: Boolean = false): FloatArray {
    val v = arrayOf(floatArrayOf(x0, y0, z0), floatArrayOf(x1, y0, z0), floatArrayOf(x1, y1, z0), floatArrayOf(x0, y1, z0),
        floatArrayOf(x0, y0, z1), floatArrayOf(x1, y0, z1), floatArrayOf(x1, y1, z1), floatArrayOf(x0, y1, z1))
    // outward (counter-clockwise seen from outside)
    val f = intArrayOf(0, 2, 1, 0, 3, 2, 4, 5, 6, 4, 6, 7, 0, 1, 5, 0, 5, 4, 1, 2, 6, 1, 6, 5, 2, 3, 7, 2, 7, 6, 3, 0, 4, 3, 4, 7)
    val out = FloatArray(12 * 9)
    for (t in 0 until 12) for (k in 0 until 3) {
        val vi = f[3 * t + if (inward) 2 - k else k]
        for (d in 0 until 3) out[9 * t + 3 * k + d] = v[vi][d]
    }
    return out
}

private fun soup(vararg boxes: FloatArray) = TriangleSoup(boxes.fold(FloatArray(0)) { a, b -> a + b }, boxes.sumOf { it.size } / 9)

class CavitiesTest : StringSpec({
    "a sealed void inside a box is a cavity; the outer shell is not" {
        val s = soup(box(0f, 0f, 0f, 20f, 20f, 20f), box(5f, 5f, 5f, 15f, 15f, 15f, inward = true))
        val m = Cavities.mask(s)
        m.take(12).any { it } shouldBe false
        m.drop(12).all { it } shouldBe true
        // its ceiling (10 x 10 mm at z 15) faces down: unsupported, and all of it sealed
        val meas = PoseDecision.measure(s, Pose.asGiven(), Overhang.fromAngle(30.0, 0.2, 0.4), m, layerMm = 0.2)
        meas.sealedMm2 shouldBe (100.0 plusOrMinus 1e-6)
        meas.needsSupportMm2 shouldBe (100.0 plusOrMinus 1e-6)
    }

    // bf_tier1 in the user's BIG-TEST.3mf is 36 touching shells: a 95 x 95 plate sits on a 100 x 100
    // slab, and the plate's underside was counted as 9025 mm² over air
    "a plate resting on a slab of the same object is not over air; its overhanging rim is" {
        val slab = box(0f, 0f, 0f, 20f, 20f, 10f)
        val onTop = soup(slab, box(2f, 2f, 10f, 18f, 18f, 12f))
        PoseDecision.measure(onTop, Pose.asGiven(), Overhang.fromAngle(30.0, 0.2, 0.4), covered = Cavities.coveredShare(onTop, Cavities.manifoldShells(onTop)), layerMm = 0.2).needsSupportMm2 shouldBe (0.0 plusOrMinus 1e-9)
        // control: judged as if it were one shell, the resting underside counts in full (the old reading)
        PoseDecision.measure(onTop, Pose.asGiven(), Overhang.fromAngle(30.0, 0.2, 0.4), covered = null, layerMm = 0.2).needsSupportMm2 shouldBe (256.0 plusOrMinus 1e-6)
        // a plate 5 mm wider than the slab on each side in x: 200 of its 600 mm² hang over air
        val wide = soup(slab, box(-5f, 0f, 10f, 25f, 20f, 12f))
        PoseDecision.measure(wide, Pose.asGiven(), Overhang.fromAngle(30.0, 0.2, 0.4), covered = Cavities.coveredShare(wide, Cavities.manifoldShells(wide)), layerMm = 0.2).needsSupportMm2 shouldBe (200.0 plusOrMinus 20.0)
    }

    // the covered share is computed once in the model's frame and reused for every pose
    "covered share holds in another pose: upside down, the slab's underside rests on the plate only where they meet" {
        val slab = box(0f, 0f, 0f, 20f, 20f, 10f)
        val onTop = soup(slab, box(2f, 2f, 10f, 18f, 18f, 12f))
        val covered = Cavities.coveredShare(onTop, Cavities.manifoldShells(onTop))
        val flipped = PoseDecision.candidates().first { it.label.startsWith("+Z down") }
        // facing down now: the slab's 400 mm² top, 256 of it on the plate; the plate's own 256 mm² top is on the bed
        PoseDecision.measure(onTop, flipped, Overhang.fromAngle(30.0, 0.2, 0.4), covered = covered, layerMm = 0.2).needsSupportMm2 shouldBe (144.0 plusOrMinus 15.0)
        // control: without the covered share the whole slab top counts
        PoseDecision.measure(onTop, flipped, Overhang.fromAngle(30.0, 0.2, 0.4), covered = null, layerMm = 0.2).needsSupportMm2 shouldBe (400.0 plusOrMinus 1e-6)
    }

    "a sealed cavity's ceiling is still over air when the mesh is judged as several shells" {
        val s = soup(box(0f, 0f, 0f, 20f, 20f, 20f), box(5f, 5f, 5f, 15f, 15f, 15f, inward = true))
        PoseDecision.measure(s, Pose.asGiven(), Overhang.fromAngle(30.0, 0.2, 0.4), Cavities.mask(s), covered = Cavities.coveredShare(s, Cavities.manifoldShells(s)), layerMm = 0.2).needsSupportMm2 shouldBe (100.0 plusOrMinus 1e-6)
    }

    "control: a solid box, and two separate boxes, have no cavity" {
        Cavities.mask(soup(box(0f, 0f, 0f, 20f, 20f, 20f))).any { it } shouldBe false
        Cavities.mask(soup(box(0f, 0f, 0f, 20f, 20f, 20f), box(30f, 0f, 0f, 40f, 10f, 10f))).any { it } shouldBe false
    }
})
