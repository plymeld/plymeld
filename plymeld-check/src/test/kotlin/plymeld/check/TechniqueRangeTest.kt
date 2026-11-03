package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import plymeld.core.decide.Control
import plymeld.core.decide.Decision
import plymeld.core.decide.Metric
import plymeld.core.decide.Portion
import plymeld.core.decide.Pose
import plymeld.core.decide.PoseDecision
import plymeld.core.decide.PoseFacts
import plymeld.core.decide.PrintTechnique
import plymeld.core.decide.TechniquePlan
import plymeld.core.decide.FlatLayers
import plymeld.core.decide.Weights
import plymeld.mesh.TriangleSoup
import plymeld.model.Overhang

/**
 * A technique's range of outcomes from its controls, first cut: flat layers' layer height (one height) ranges over the
 * tool's thinnest to thickest layer, is sampled coarse to fine, and the weights pick the point. The shape: the 20 x 20 x
 * 5 block with a 45° frustum on top (TechniqueChoiceTest). Small sections, so every layer takes the minimum layer time:
 * thicker layers are faster, thinner ones leave a smaller stair-step.
 */
class TechniqueRangeTest : StringSpec({
    val soup = blockWithFrustum()
    val range = Control(FlatLayers.LAYER_HEIGHT, "mm", Control.Range.Continuous(0.08, 0.28), "test: the tool's thinnest and thickest layer")
    fun facts(weights: String, c: Control = range, budget: Int = 9) = PoseFacts(0.0, 0.0, 270.0, 270.0, 256.0, Overhang.fromAngle(30.0, 0.08, 0.4), "test",
        Weights.parse(weights, "test"), false, layerHeightMm = 0.2, flowMaxMm3s = 18.0, minLayerTimeS = 8.0, firstLayerMm = 0.2, minLayerMm = 0.08, maxLayerMm = 0.28,
        layerHeightCandidates = FlatLayers.coarse(c, 0.2, budget), layerHeightControl = c, layerHeightBudget = budget, baselineLayerMm = 0.2, baselineWhat = "test")
    val flat = listOf(PrintTechnique.FLAT_LAYERS to null)
    fun decide(weights: String, c: Control = range): Decision<TechniquePlan> =
        PoseDecision.decideWithTechnique(listOf(soup), facts(weights, c), flat, Pose.asGiven(), heights = FlatLayers.Heights.ONE).technique
    fun chosen(d: Decision<TechniquePlan>) = d.value.layerHeightMm!!
    fun sampled(d: Decision<TechniquePlan>) = d.facts.single { it.startsWith("flat layers' layer height, sampled") }

    "coarse to fine: the ends and the baseline first (three points); then midpoints next to the best" {
        FlatLayers.coarse(range, 0.2, 9) shouldBe listOf(0.08, 0.28, 0.2)
        FlatLayers.coarse(range, 0.2, 2) shouldBe listOf(0.08, 0.28)
        // a baseline outside the range is not a point on it: the middle instead
        FlatLayers.coarse(range, 0.3, 9) shouldBe listOf(0.08, 0.28, 0.18)
        FlatLayers.coarse(range, null, 9) shouldBe listOf(0.08, 0.28, 0.18)
        FlatLayers.refine(range, listOf(0.08, 0.14, 0.2, 0.24, 0.28), 0.2) shouldBe listOf(0.17, 0.22)
        FlatLayers.refine(range, listOf(0.08, 0.14, 0.2, 0.24, 0.28), 0.28) shouldBe listOf(0.26)
    }

    "a locked control collapses the range to its point; control: unlocked, more than one point is tried" {
        val locked = decide("quality=1,speed=1", Control(FlatLayers.LAYER_HEIGHT, "mm", Control.Range.Steps(listOf(0.16)), "test lock"))
        chosen(locked) shouldBe 0.16
        locked.facts.single { it.startsWith("flat layers' layer height") } shouldContain "layer.height locked at 0.16 mm"
        val free = decide("quality=1,speed=1")
        free.alternatives.count { it.rejected == null } shouldBe 9   // the heights measured (following the surfaces is listed as not tried: locked to one height)
        sampled(free) shouldContain "in every pose 0.08, 0.28, 0.2 mm"
        sampled(free) shouldContain "refined where an unexplored interval could still win"
    }

    "the weights pick the point: speed moves the layer height up, quality down; equal weights land between" {
        val q = chosen(decide("quality=10,speed=1"))
        val s = chosen(decide("quality=1,speed=10"))
        val e = chosen(decide("quality=1,speed=1"))
        println("chosen layer height: quality 10 / speed 1 -> $q mm, equal -> $e mm, quality 1 / speed 10 -> $s mm")
        (q < s) shouldBe true
        (e in q..s) shouldBe true
        // and the report says where on the range it chose
        sampled(decide("quality=1,speed=10")) shouldContain "chose $s mm"
    }

    "outcomes come from measuring: another layer height or another shape changes them; control: the same inputs give the same" {
        val f = facts("quality=1")
        val portion = Portion(soup, Pose.asGiven())
        val at02 = FlatLayers.outcomes(portion, f, mapOf(FlatLayers.LAYER_HEIGHT to 0.2))
        val at01 = FlatLayers.outcomes(portion, f, mapOf(FlatLayers.LAYER_HEIGHT to 0.1))
        (at01.getValue(Metric.SURFACE_STEP) < at02.getValue(Metric.SURFACE_STEP)) shouldBe true
        (at01.getValue(Metric.TIME_ESTIMATE) > at02.getValue(Metric.TIME_ESTIMATE)) shouldBe true
        // the same shape twice as tall: twice the layers at the minimum layer time
        val tall = TriangleSoup(FloatArray(soup.count * 9) { i -> if (i % 3 == 2) soup.coords[i] * 2 else soup.coords[i] }, soup.count)
        val tallAt02 = FlatLayers.outcomes(Portion(tall, Pose.asGiven()), f, mapOf(FlatLayers.LAYER_HEIGHT to 0.2))
        (tallAt02.getValue(Metric.TIME_ESTIMATE) > at02.getValue(Metric.TIME_ESTIMATE) * 1.5) shouldBe true
        tallAt02.getValue(Metric.HEIGHT) shouldBe 18.0
        FlatLayers.outcomes(portion, f, mapOf(FlatLayers.LAYER_HEIGHT to 0.2)) shouldBe at02
    }
})
