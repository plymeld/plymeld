package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import plymeld.core.decide.Metric
import plymeld.core.decide.Pose
import plymeld.core.decide.PoseDecision
import plymeld.core.decide.PoseFacts
import plymeld.core.decide.PrintTechnique
import plymeld.core.decide.Scoring
import plymeld.core.decide.Weights
import plymeld.mesh.TriangleSoup
import plymeld.model.Overhang

/**
 * Bed contact's scale where the baseline has none on the bed: the area of the model's
 * largest flat face, coplanar triangles grouped. Expected areas are worked by hand from the box's sides.
 */
class BedContactScaleTest : StringSpec({
    /** A box [x] x [y] x [z] mm, two triangles a side, outward normals. */
    fun box(x: Float, y: Float, z: Float): FloatArray {
        val v = arrayOf(floatArrayOf(0f, 0f, 0f), floatArrayOf(x, 0f, 0f), floatArrayOf(x, y, 0f), floatArrayOf(0f, y, 0f),
            floatArrayOf(0f, 0f, z), floatArrayOf(x, 0f, z), floatArrayOf(x, y, z), floatArrayOf(0f, y, z))
        val f = intArrayOf(0, 2, 1, 0, 3, 2, 4, 5, 6, 4, 6, 7, 0, 1, 5, 0, 5, 4, 1, 2, 6, 1, 6, 5, 2, 3, 7, 2, 7, 6, 3, 0, 4, 3, 4, 7)
        return FloatArray(12 * 9).also { out -> for (t in 0 until 12) for (k in 0 until 3) for (d in 0 until 3) out[9 * t + 3 * k + d] = v[f[3 * t + k]][d] }
    }
    /** [c] turned [deg] about X and lifted so its lowest point is at z = 0: no side of it lies flat. */
    fun tilted(c: FloatArray, deg: Double): TriangleSoup {
        val a = Math.toRadians(deg); val cs = Math.cos(a); val sn = Math.sin(a)
        val out = FloatArray(c.size)
        for (i in 0 until c.size / 3) {
            val y = c[3 * i + 1].toDouble(); val z = c[3 * i + 2].toDouble()
            out[3 * i] = c[3 * i]; out[3 * i + 1] = (cs * y - sn * z).toFloat(); out[3 * i + 2] = (sn * y + cs * z).toFloat()
        }
        val zMin = (0 until out.size / 3).minOf { out[3 * it + 2] }
        for (i in 0 until out.size / 3) out[3 * i + 2] -= zMin
        return TriangleSoup(out, out.size / 9)
    }

    // a 30 x 20 x 10 mm box: its sides are 600, 300 and 200 mm² (each twice)
    val boxSoup = TriangleSoup(box(30f, 20f, 10f), 12)
    val tiltedBox = tilted(box(30f, 20f, 10f), 30.0)
    val oh = Overhang.fromAngle(30.0, 0.2, 0.4)

    "the largest flat face of a 30 x 20 x 10 box is its 30 x 20 side, 600 mm², in any orientation" {
        PoseDecision.largestFlatFaceMm2(boxSoup) shouldBe (600.0 plusOrMinus 1e-3)
        PoseDecision.largestFlatFaceMm2(tiltedBox) shouldBe (600.0 plusOrMinus 0.01)
    }

    "coplanar faces count together, apart or not; parallel faces in other planes do not" {
        // two 10 x 10 x 5 blocks side by side 5 mm apart: their bottoms are one plane (200 mm²), and their tops another;
        // a third block standing 1 mm higher has a bottom parallel to theirs, in another plane
        fun shifted(c: FloatArray, dx: Float, dz: Float) = FloatArray(c.size) { i -> c[i] + if (i % 3 == 0) dx else if (i % 3 == 2) dz else 0f }
        val b = box(10f, 10f, 5f)
        PoseDecision.largestFlatFaceMm2(TriangleSoup(b + shifted(b, 15f, 0f), 24)) shouldBe (200.0 plusOrMinus 1e-3)
        // control: the second block 1 mm up, its bottom another plane: each face is 100 mm²
        PoseDecision.largestFlatFaceMm2(TriangleSoup(b + shifted(b, 15f, 1f), 24)) shouldBe (100.0 plusOrMinus 1e-3)
    }

    // the tilted box, reliability only, against the baseline (flat layers 0.2 mm in the as-given pose, which touches the
    // bed along an edge: bed contact 0)
    val f = PoseFacts(0.0, 0.0, 270.0, 270.0, 256.0, oh, "test", Weights.parse("reliability=1", "test"), false,
        layerHeightMm = 0.2, minLayerMm = 0.08, baselineLayerMm = 0.2, baselineWhat = "test")
    val uniform = listOf(PrintTechnique.FLAT_LAYERS to null)

    "the as-given pose of the tilted box has no flat face on the bed: the baseline's bed contact is 0" {
        PoseDecision.measure(tiltedBox, Pose.asGiven(), oh, layerMm = 0.2).contactMm2 shouldBe 0.0
    }

    "bed contact is scored across poses against the largest flat face, and the 600 mm² side wins" {
        val d = PoseDecision.decideWithTechnique(listOf(tiltedBox), f, uniform).pose
        d.facts.none { it.startsWith("not scored") && "bed_contact" in it } shouldBe true
        d.facts.single { it.startsWith("scoring by the size") } shouldContain "bed_contact 600.0 mm² (derived: the model's largest flat face"
        val chosen = d.alternatives.single { it.chosen }
        chosen.score shouldContain "on bed 600.0 mm²"
        // linear from 0 (the baseline's is 0): -600 / 600 = -1 for the whole reliability weight's share (four measures since
        // bed contact, tip offset, slenderness, first-layer islands: 0.25)
        chosen.score shouldContain "bed_contact -0.250 [+600.0 mm² from 0 against 600.0, better]"
        // the baseline touches the bed along an edge, so it has no tip offset (no contact to be over): not a number
        d.facts.single { it.startsWith("baseline") } shouldContain "tip_offset (none), "
    }

    "control: with the scale removed, bed contact is not scored and the choice ignores it" {
        // the same candidates the decision scores (every axis and face pose with a flat face on the bed)
        val cands = PoseDecision.candidates(tiltedBox).map { it to PoseDecision.measure(tiltedBox, it, oh, f = f, withSections = false) }
            .filter { it.second.contactMm2 > 0 }
        val values = listOf(Metric.BED_CONTACT, Metric.TIP_OFFSET).associateWith { m -> { c: Pair<Pose, PoseDecision.Measure> -> PoseDecision.valuesOf(c.second, f).getValue(m) } }
        val baseline = mapOf(Metric.BED_CONTACT to 0.0, Metric.TIP_OFFSET to 0.0)
        val scales = PoseDecision.zeroScales(tiltedBox, f)
        scales.getValue(Metric.BED_CONTACT) shouldBe (600.0 plusOrMinus 0.01)
        val with = Scoring.rank(cands, values, f.weights, scales, baseline)
        with.first().item.second.contactMm2 shouldBe (600.0 plusOrMinus 0.01)
        val without = Scoring.rank(cands, values, f.weights, scales - Metric.BED_CONTACT, baseline)
        without.first().unscored.keys shouldBe setOf(Metric.BED_CONTACT)
        Scoring.unscoredFact(without)!! shouldContain "bed_contact: the baseline's value is 0 and there is no scale for it"
        without.first().parts.keys shouldBe setOf(Metric.TIP_OFFSET)
        // every pose ties (each box side is over its own center of mass), so the first candidate stands: a 200 mm² side
        (without.first().item.second.contactMm2 < 599.0) shouldBe true
        without.first().item.first.label shouldNotContain "600"
    }
})
