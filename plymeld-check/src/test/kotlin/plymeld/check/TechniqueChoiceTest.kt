package plymeld.check

import plymeld.model.Overhang
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import plymeld.core.decide.FlatLayers
import plymeld.core.decide.PrintTechnique
import plymeld.core.decide.TechniqueDecision
import plymeld.core.decide.Pose
import plymeld.core.decide.PoseDecision
import plymeld.core.decide.PoseFacts
import plymeld.core.decide.TechniquePlan
import plymeld.core.decide.Surveys
import plymeld.core.decide.Weights
import plymeld.config.Models
import plymeld.mesh.LayerPlan
import plymeld.mesh.TriangleSoup

/**
 * Pose x technique (first cut): flat layers of one height and flat layers following the
 * surfaces (uniform and adaptive layers, one technique) are each measured in the pose with their own layer
 * heights, and the weights choose. The shape: a 20 x 20 x 5 block with a 45° frustum on top (to z = 9). Flat 0.08 mm
 * layers of one height leave a smaller stair-step on the frustum than layers following the surfaces (step 0.08 / cos 45°
 * = 0.113 mm layers there); following the surfaces needs fewer layers, and with small sections every layer costs the
 * minimum layer time, so it is faster.
 */
class TechniqueChoiceTest : StringSpec({
    val soup = blockWithFrustum()
    fun facts(weights: String) = PoseFacts(0.0, 0.0, 270.0, 270.0, 256.0, Overhang.fromAngle(30.0, 0.08, 0.4), "test", Weights.parse(weights, "test"), false,
        layerHeightMm = 0.08, flowMaxMm3s = 18.0, minLayerTimeS = 8.0, firstLayerMm = 0.2, minLayerMm = 0.08, maxLayerMm = 0.28)
    val flat = listOf(PrintTechnique.FLAT_LAYERS to null)
    fun choose(weights: String, heights: FlatLayers.Heights? = null) = PoseDecision.decideWithTechnique(listOf(soup), facts(weights), flat, Pose.asGiven(), heights = heights)
    val oneLabel = TechniquePlan.labelOf(TechniquePlan.Banded(0.0, 1.0, PrintTechnique.FLAT_LAYERS))
    val followLabel = TechniquePlan.labelOf(TechniquePlan.Banded(0.0, 1.0, PrintTechnique.FLAT_LAYERS, follow = true))

    "speed alone: flat layers following the surfaces (fewer layers, each held to the minimum layer time)" {
        val d = choose("speed=1").technique
        println(d.describe())
        d.value.follows shouldBe true
    }
    "quality alone: flat layers of one height (the smaller stair-step on the frustum)" {
        choose("quality=1").technique.value.oneHeight shouldBe true
    }
    "control: the two candidates really differ on both counts, so the weights decide" {
        val ms = PoseDecision.measureAll(soup, Pose.asGiven(), Overhang.fromAngle(30.0, 0.08, 0.4), f = facts("speed=1"),
            plansFor = { _, _ -> listOf(TechniquePlan.whole(PrintTechnique.FLAT_LAYERS), FlatLayers.following()) })
        val (one, follow) = ms
        (follow.layerTops!!.size < one.layerTops!!.size) shouldBe true
        (follow.timeEstimateS!! < one.timeEstimateS!!) shouldBe true
        (follow.surfaceStepMm!! > one.surfaceStepMm!!) shouldBe true
    }
    "locked heights are used and reported as locked: one height where speed alone would follow the surfaces" {
        val d = choose("speed=1", FlatLayers.Heights.ONE).technique
        d.value.oneHeight shouldBe true
        d.overridden shouldBe true
        d.alternatives.single { it.label == followLabel }.rejected!! shouldContain "locked to one height"
        // and the other way: following the surfaces where quality alone would keep one height
        choose("quality=1", FlatLayers.Heights.FOLLOW).technique.value.followsThroughout shouldBe true
    }
    // Layer height 0.2, largest stair-step 0.08: the vertical block allows 0.28 (thicker), the 45°
    // frustum asks for 0.08 / cos 45° = 0.113 (thinner). The survey proposes both, as two bands.
    fun coarse(weights: String) = PoseFacts(0.0, 0.0, 270.0, 270.0, 256.0, Overhang.fromAngle(30.0, 0.2, 0.4), "test", Weights.parse(weights, "test"), false,
        layerHeightMm = 0.2, flowMaxMm3s = 18.0, minLayerTimeS = 8.0, firstLayerMm = 0.2, minLayerMm = 0.08, maxLayerMm = 0.28, maxStepMm = 0.08)
    "survey: flat layers propose following the surfaces on the block (thicker) and the frustum (thinner), as separate bands" {
        val ps = Surveys.flat(LayerPlan.limits(soup, 0.08, 0.28, 0.08), 0.2, 0.28)
        ps.forEach { println(it.describe()) }
        ps.size shouldBe 2
        ps.all { it.follow && it.technique == PrintTechnique.FLAT_LAYERS } shouldBe true
        (ps[0].layersChange < 0 && ps[0].lo == 0.0 && ps[0].hi > 4.6 && ps[0].hi <= 5.0) shouldBe true   // fewer layers, ends within a window below z 5
        (ps[1].layersChange > 0 && ps[1].lo == ps[0].hi && ps[1].hi > 8.99) shouldBe true                  // more layers, up to the top
    }
    "quality alone, with the survey: one height on the block, following the surfaces on the frustum — neither whole-object plan wins" {
        val d = PoseDecision.decideWithTechnique(listOf(soup), coarse("quality=1"), flat, Pose.asGiven()).technique
        println(d.describe())
        val bands = d.value.bands
        bands.size shouldBe 2
        bands[0].oneHeight shouldBe true
        bands[1].follow shouldBe true
        // control: both whole-object plans were measured and lost
        d.alternatives.count { it.label.startsWith(oneLabel) || it.label == followLabel } shouldBe 2
        d.facts.any { it.startsWith("survey proposes ${TechniquePlan.FOLLOW_SHORT}") } shouldBe true
    }
    "following the surfaces needs the tool's thinnest and thickest layer: blocked by name without them; flat layers stay usable" {
        val screened = TechniqueDecision.screen(Models.bundled.forModel("qidi-q2"), 0).toMap()
        screened.getValue(PrintTechnique.FLAT_LAYERS) shouldBe null
        FlatLayers.followBlocked(PoseFacts(0.0, 0.0, 270.0, 270.0, 256.0, Overhang.fromAngle(30.0, 0.2, 0.4), "test", layerHeightMm = 0.2))!! shouldContain "hardware.tool.<key>.layer.height.min"
    }
    "flat layers do not follow the surfaces with fuzzy skin (its texture follows the layer height); control: they do without it" {
        val base = PoseFacts(0.0, 0.0, 270.0, 270.0, 256.0, Overhang.fromAngle(30.0, 0.2, 0.4), "test", layerHeightMm = 0.2, minLayerMm = 0.08, maxLayerMm = 0.28)
        val fuzzy = PoseFacts(0.0, 0.0, 270.0, 270.0, 256.0, Overhang.fromAngle(30.0, 0.2, 0.4), "test", layerHeightMm = 0.2, minLayerMm = 0.08, maxLayerMm = 0.28, fuzzySkin = true)
        FlatLayers.followBlocked(base) shouldBe null
        FlatLayers.followBlocked(fuzzy)!! shouldContain "fuzzy skin"
        // a locked plan following the surfaces is refused by name with fuzzy skin; control: used without it
        io.kotest.assertions.throwables.shouldThrow<IllegalStateException> {
            PoseDecision.decideWithTechnique(listOf(soup), fuzzy, flat, Pose.asGiven(), overridePlan = FlatLayers.following())
        }.message!! shouldContain "fuzzy skin"
        PoseDecision.decideWithTechnique(listOf(soup), base, flat, Pose.asGiven(), overridePlan = FlatLayers.following()).technique.value.followsThroughout shouldBe true
    }
    "flat layers' controls: the layer height's range and the largest stair-step, locked at the setting" {
        val f = coarse("quality=1")
        FlatLayers.controls(f).map { it.id } shouldBe listOf(FlatLayers.MAX_STEP)   // no layer height control given: the settings' height
        FlatLayers.maxStep(f)!!.describe() shouldContain "step.max locked at 0.08 mm"
    }
})

/** Triangles of the block-with-frustum surface (as in plymeld-mesh's AdaptiveLayersTest); GoalsTest uses it too. */
internal fun blockWithFrustum(): TriangleSoup {
    val t = ArrayList<Float>()
    fun tri(a: DoubleArray, b: DoubleArray, c: DoubleArray) { for (v in listOf(a, b, c)) for (x in v) t.add(x.toFloat()) }
    fun quad(a: DoubleArray, b: DoubleArray, c: DoubleArray, d: DoubleArray) { tri(a, b, c); tri(a, c, d) }
    fun sq(h: Double, z: Double) = listOf(doubleArrayOf(-h, -h, z), doubleArrayOf(h, -h, z), doubleArrayOf(h, h, z), doubleArrayOf(-h, h, z))
    val b0 = sq(10.0, 0.0); val b1 = sq(10.0, 5.0); val f0 = sq(5.0, 5.0); val f1 = sq(1.0, 9.0)
    // outward winding (the pose step measures facing-down area and bed contact)
    quad(b0[0], b0[3], b0[2], b0[1])
    for (k in 0 until 4) {
        val n = (k + 1) % 4
        quad(b0[k], b0[n], b1[n], b1[k])
        quad(b1[k], b1[n], f0[n], f0[k])
        quad(f0[k], f0[n], f1[n], f1[k])
    }
    quad(f1[0], f1[1], f1[2], f1[3])
    return TriangleSoup(t.toFloatArray(), t.size / 9)
}
