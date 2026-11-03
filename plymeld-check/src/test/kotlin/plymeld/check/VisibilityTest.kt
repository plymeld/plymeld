package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import plymeld.core.decide.Cavities
import plymeld.core.decide.Metric
import plymeld.core.decide.Pose
import plymeld.core.decide.PoseDecision
import plymeld.core.decide.PoseFacts
import plymeld.core.decide.Visibility
import plymeld.mesh.TriangleSoup
import plymeld.model.Overhang

/** A closed box as 12 outward triangles. */
private fun box(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float): FloatArray {
    val v = arrayOf(floatArrayOf(x0, y0, z0), floatArrayOf(x1, y0, z0), floatArrayOf(x1, y1, z0), floatArrayOf(x0, y1, z0),
        floatArrayOf(x0, y0, z1), floatArrayOf(x1, y0, z1), floatArrayOf(x1, y1, z1), floatArrayOf(x0, y1, z1))
    val f = intArrayOf(0, 2, 1, 0, 3, 2, 4, 5, 6, 4, 6, 7, 0, 1, 5, 0, 5, 4, 1, 2, 6, 1, 6, 5, 2, 3, 7, 2, 7, 6, 3, 0, 4, 3, 4, 7)
    val out = FloatArray(12 * 9)
    for (t in 0 until 12) for (k in 0 until 3) for (d in 0 until 3) out[9 * t + 3 * k + d] = v[f[3 * t + k]][d]
    return out
}

/**
 * A bracket: the xz triangle (0,0) (0,10) (10,10) extruded over y 0..10. Its faces: a vertical wall at x = 0, a flat top at
 * z = 10, and a 45 deg UNDERSIDE from (0,0) up to (10,10) (facing down and +x), two triangular ends. Outward winding.
 */
private fun bracket(): FloatArray {
    val a = floatArrayOf(0f, 0f, 0f); val b = floatArrayOf(0f, 0f, 10f); val c = floatArrayOf(10f, 0f, 10f)
    val a2 = floatArrayOf(0f, 10f, 0f); val b2 = floatArrayOf(0f, 10f, 10f); val c2 = floatArrayOf(10f, 10f, 10f)
    fun tri(vararg p: FloatArray) = p.fold(FloatArray(0)) { acc, q -> acc + q }
    return tri(a, c, b) +                       // end at y = 0 (normal -y): a -> c -> b is clockwise seen from -y... checked by volume below
        tri(a2, b2, c2) +                       // end at y = 10 (normal +y)
        tri(a, b, b2) + tri(a, b2, a2) +        // wall x = 0 (normal -x)
        tri(b, c, c2) + tri(b, c2, b2) +        // top z = 10 (normal +z)
        tri(a, a2, c2) + tri(a, c2, c)          // underside (normal +x, -z)
}

private fun soup(vararg parts: FloatArray) = TriangleSoup(parts.fold(FloatArray(0)) { x, y -> x + y }, parts.sumOf { it.size } / 9)

private fun signedVolume(s: TriangleSoup): Double {
    val c = s.coords; var v = 0.0
    for (i in 0 until s.count) { val o = 9 * i
        v += c[o].toDouble() * (c[o + 4].toDouble() * c[o + 8] - c[o + 5].toDouble() * c[o + 7]) -
            c[o + 1].toDouble() * (c[o + 3].toDouble() * c[o + 8] - c[o + 5].toDouble() * c[o + 6]) +
            c[o + 2].toDouble() * (c[o + 3].toDouble() * c[o + 7] - c[o + 4].toDouble() * c[o + 6]) }
    return v / 6
}

/** The visibility map and its weight on the pose measures. */
class VisibilityTest : StringSpec({
    val oh = Overhang.fromAngle(30.0, 0.2, 0.4)
    fun facts(v: Visibility?) = PoseFacts(0.0, 0.0, 270.0, 270.0, 256.0, oh, "test", layerHeightMm = 0.2, firstLayerMm = 0.2, minLayerMm = 0.08, maxLayerMm = 0.3, visibility = v)
    val map = Visibility(0.3, 0.0, "test")

    "the weights: bed 0, a horizontal underside 0.3, a 45 deg underside half-way, tops and walls 1, a covered face 0" {
        map.of(1.0, true, 0.0) shouldBe (0.0 plusOrMinus 1e-12)
        map.of(1.0, false, 0.0) shouldBe (0.3 plusOrMinus 1e-12)
        map.of(Math.sqrt(0.5), false, 0.0) shouldBe (1 - 0.7 * Math.sqrt(0.5) plusOrMinus 1e-12)
        map.of(0.0, false, 0.0) shouldBe (1.0 plusOrMinus 1e-12)
        map.of(-1.0, false, 0.0) shouldBe (1.0 plusOrMinus 1e-12)
        map.of(0.0, false, 1.0) shouldBe (0.0 plusOrMinus 1e-12)
        map.of(0.0, false, 0.5) shouldBe (0.5 plusOrMinus 1e-12)
        Visibility.NONE.of(1.0, true, 0.0) shouldBe (1.0 plusOrMinus 1e-12)
    }

    "a view: faces turned away from the viewer count the away weight by how squarely they face away" {
        val front = Visibility(0.3, 0.0, "test", away = 0.3, view = Visibility.viewOf("front"))
        front.awayTerm(front.awayDot(0.0, 1.0, 0.0)) shouldBe (0.3 plusOrMinus 1e-12)      // the back face (normal +y) turned straight away
        front.awayTerm(front.awayDot(0.0, -1.0, 0.0)) shouldBe (1.0 plusOrMinus 1e-12)     // the front face, toward the viewer
        front.awayTerm(front.awayDot(1.0, 0.0, 0.0)) shouldBe (1.0 plusOrMinus 1e-12)      // a side face, edge on
        front.awayTerm(front.awayDot(0.0, Math.sqrt(0.5), 0.0)) shouldBe (1 - 0.7 * Math.sqrt(0.5) plusOrMinus 1e-12)
        // a cube seen from the front: the back face at 0.3, the bottom 0, the rest 1
        val m = PoseDecision.measure(soup(box(0f, 0f, 0f, 10f, 10f, 10f)), Pose.asGiven(), oh, f = facts(front), withSections = false)
        m.visibleMm2 shouldBe (400.0 + 30.0 plusOrMinus 1e-6)
        // all around: no face turned away
        Visibility(0.3, 0.0, "test").awayTerm(1.0) shouldBe (1.0 plusOrMinus 1e-12)
        Visibility.viewOf("all_around") shouldBe null
    }

    // "front" is the model's own front, turned with the pose: a 20 x 10 x 10 box seen from the
    // front (its long faces front and back, 200 mm² each; the ends 100 each; the top 200): as given its back counts 0.3 x 200,
    // 660 mm² seen. Turned 90 deg about z the same faces are seen the same, the view turned with it; read on the bed's axes
    // (the control) an END would face the bed's back instead and count 0.3, 730 mm², the turn hiding the long back
    "a view from the front is the model's own front: a box turned about z is seen as it was; on the bed's axes it would not (the control)" {
        val front = Visibility(0.3, 0.0, "test", away = 0.3, view = Visibility.viewOf("front"))
        val turn = Pose("turned 90 deg", doubleArrayOf(0.0, -1.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0), doubleArrayOf(0.0, 0.0, 0.0))
        val b = soup(box(0f, 0f, 0f, 20f, 10f, 10f))
        PoseDecision.measure(b, Pose.asGiven(), oh, f = facts(front), withSections = false).visibleMm2 shouldBe (200.0 + 400.0 + 60.0 plusOrMinus 1e-6)
        PoseDecision.measure(b, turn, oh, f = facts(front), withSections = false).visibleMm2 shouldBe (660.0 plusOrMinus 1e-6)
        // the control: the bed's front, as the measure read it before (the view already turned back, so turning it again cancels)
        val bedFront = front.posed(doubleArrayOf(0.0, 1.0, 0.0, -1.0, 0.0, 0.0, 0.0, 0.0, 1.0))
        PoseDecision.measure(b, turn, oh, f = facts(bedFront), withSections = false).visibleMm2 shouldBe (200.0 + 400.0 + 100.0 + 30.0 plusOrMinus 1e-6)
        // the decided value the slice reads: the view turned, as three numbers; none where the pose leaves it as it was
        Visibility.viewOf(Visibility.posedView("front", turn.r))!!.toList().map { Math.round(it * 1e6) / 1e6 } shouldBe listOf(-1.0, 0.0, 0.0)
        Visibility.posedView("front", Pose.asGiven().r) shouldBe null
        Visibility.posedView("all_around", turn.r) shouldBe null
    }

    "a cube on the bed: five of six faces are seen" {
        val m = PoseDecision.measure(soup(box(0f, 0f, 0f, 10f, 10f, 10f)), Pose.asGiven(), oh, f = facts(map), withSections = false)
        m.surfaceMm2 shouldBe (600.0 plusOrMinus 1e-6)
        m.visibleMm2 shouldBe (500.0 plusOrMinus 1e-6)
        m.seenShare shouldBe (5.0 / 6 plusOrMinus 1e-9)
    }

    "a table: the slab's underside counts 0.3; its droop under quality is 0.3 of the unweighted, the raw area unchanged" {
        val legs = listOf(box(0f, 0f, 0f, 2f, 2f, 10f), box(18f, 0f, 0f, 20f, 2f, 10f), box(0f, 18f, 0f, 2f, 20f, 10f), box(18f, 18f, 0f, 20f, 20f, 10f))
        val table = soup(*legs.toTypedArray(), box(0f, 0f, 10f, 20f, 20f, 12f))
        val covered = Cavities.coveredShare(table, Cavities.manifoldShells(table))
        val seen = PoseDecision.measure(table, Pose.asGiven(), oh, covered = covered, f = facts(map), withSections = false)
        val alike = PoseDecision.measure(table, Pose.asGiven(), oh, covered = covered, f = facts(null), withSections = false)
        // the underside over air: 400 - 4 x 4 = 384 mm², the same raw area either way
        seen.needsSupportMm2 shouldBe (alike.needsSupportMm2 plusOrMinus 1e-6)
        seen.needsSupportMm2 shouldBe (384.0 plusOrMinus 1.0)
        // what quality counts (support off: droop) is 0.3 of it
        val d = PoseDecision.supportValues(seen, facts(map)).getValue(Metric.UNSUPPORTED_AREA)
        val d0 = PoseDecision.supportValues(alike, facts(null)).getValue(Metric.UNSUPPORTED_AREA)
        d shouldBe (0.3 * d0 plusOrMinus 0.02)
        // removed from the seen surface: the legs' bed faces (16 mm²), the legs' tops under the slab and the slab's underside
        // over them (covered, 16 + 16 mm²), and 0.7 of the underside over air
        seen.visibleMm2 shouldBe (seen.surfaceMm2 - 16.0 - 16.0 - 16.0 - 0.7 * 384.0 plusOrMinus 2.0)
    }

    "a bracket: the 45 deg underside's stair-step counts less than its wall; the control at every face alike is the old value" {
        val s = soup(bracket())
        (signedVolume(s) > 0) shouldBe true   // outward winding
        val seen = PoseDecision.measure(s, Pose.asGiven(), oh, f = facts(map), withSections = false)
        val alike = PoseDecision.measure(s, Pose.asGiven(), oh, f = facts(null), withSections = false)
        val none = PoseDecision.measure(s, Pose.asGiven(), oh, f = facts(Visibility.NONE), withSections = false)
        // faces that count toward the stair-step: the wall (100 mm², step 0), the two ends (50 mm² each, step 0), the
        // underside (100 sqrt 2 mm², at 45 deg its edges sin 45 / h per mm, each h x cos 45 deep, squared: h x sqrt 0.5 x 0.5;
        // PoseDecision.stairStep); the top and the bed face are flat
        val under = 100 * Math.sqrt(2.0); val others = 200.0; val h = 0.2
        val step45 = h * Math.sqrt(0.5) * 0.5
        val stepAlike = under * step45 / (under + others)
        alike.surfaceStepMm!! shouldBe (stepAlike plusOrMinus 1e-6)
        none.surfaceStepMm!! shouldBe (stepAlike plusOrMinus 1e-6)
        val w = map.direction(Math.sqrt(0.5))
        seen.surfaceStepMm!! shouldBe (under * w * step45 / (under * w + others) plusOrMinus 1e-6)
        (seen.surfaceStepMm!! < alike.surfaceStepMm!!) shouldBe true
    }

    "the pose decision says what the map does and how much of the surface counts" {
        val d = PoseDecision.decide(listOf(soup(box(0f, 0f, 0f, 10f, 10f, 10f))), facts(map))
        val text = d.describe()
        text shouldContain "visibility: quality's measures"
        text shouldContain "seen: 83 % of the model's 600 mm² surface"
    }
})
