package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import plymeld.core.decide.Control
import plymeld.core.decide.SurfaceTechnique
import plymeld.core.decide.FlatLayers
import plymeld.core.decide.Pose
import plymeld.core.decide.PoseDecision
import plymeld.core.decide.PoseFacts
import plymeld.core.decide.PrintTechnique
import plymeld.core.decide.TechniqueExplain
import plymeld.core.decide.Weights
import plymeld.model.Overhang

/**
 * The joint search on the block with a frustum
 * (TechniqueChoiceTest): how it stops, and the flat tops' surface technique weighed against as laid.
 *
 * - The short-circuit stop fires where the bound proves it: with quality the only goal, every scored measure is best at
 *   the thinnest layer, so no interval between tried heights can beat it (the bound, each measure's best at the
 *   interval's ends, is the winner itself). With quality and speed trading, the bound cannot prove it (an interval's
 *   bound takes the better end of each measure), and the search runs to the per-pose budget instead; a deadline already
 *   past stops it at the time limit.
 * - The flat tops' surface technique: with no lock, ironing is a variant of every candidate; quality-heavy weights take it (the rough
 *   flat-top area it removes outweighs its pass's time), speed-heavy weights leave the tops as laid. Without ironing's
 *   spacing and speed it cannot be weighed, and the decision says so.
 */
class JointSearchTest : StringSpec({
    val soup = blockWithFrustum()
    val range = Control(FlatLayers.LAYER_HEIGHT, "mm", Control.Range.Continuous(0.08, 0.28), "test: the tool's thinnest and thickest layer")
    fun facts(weights: String, weigh: Set<SurfaceTechnique> = emptySet(), ironing: Boolean = true, budget: Int = 9, settle: Int = 0) =
        PoseFacts(0.0, 0.0, 270.0, 270.0, 256.0, Overhang.fromAngle(30.0, 0.08, 0.4), "test", Weights.parse(weights, "test"), false,
            layerHeightMm = 0.2, flowMaxMm3s = 18.0, minLayerTimeS = 8.0, firstLayerMm = 0.2, minLayerMm = 0.08, maxLayerMm = 0.28,
            layerHeightCandidates = FlatLayers.coarse(range, 0.2, budget), layerHeightControl = range, layerHeightBudget = budget, settleRounds = settle, baselineLayerMm = 0.2, baselineWhat = "test",
            weighSurfaceTechniques = weigh, ironingSpacingMm = if (ironing) 0.1 else null, ironingSpeedMmS = if (ironing) 30.0 else null, ironingSource = "test")
    val flat = listOf(PrintTechnique.FLAT_LAYERS to null)
    fun decide(f: PoseFacts, deadline: Long? = null) = PoseDecision.decideWithTechnique(listOf(soup), f, flat, Pose.asGiven(), deadlineNanos = deadline, heights = FlatLayers.Heights.ONE)

    "the short circuit fires where the bound proves it (quality alone: every measure best at the thinnest layer); control: quality and speed trading, the bound cannot, and the budget stops it" {
        val q = decide(facts("quality=1"))
        q.stop.reason shouldBe TechniqueExplain.StopReason.SHORT_CIRCUIT
        q.stop.rounds shouldBe 1
        q.technique.value.layerHeightMm shouldBe 0.08
        q.explain.text() shouldContain "stopped: short_circuited"
        // control: with speed against quality no interval's bound can prove the winner safe (each measure's better end
        // adds up below the winner), so the search refines until the per-pose budget
        val qs = decide(facts("quality=1,speed=1"))
        qs.stop.reason shouldBe TechniqueExplain.StopReason.BUDGET
        (qs.stop.rounds > 1) shouldBe true
        qs.stop.winnerScoreByRound.size shouldBe qs.stop.rounds
        // the same weights with method.search.settle_rounds 2: it stops at the second round in a row that leaves the winner
        // as it was, before the budget (the control is qs above: settle_rounds 0 runs to the budget)
        val settled = decide(facts("quality=1,speed=1", settle = 2))
        println("quality=1,speed=1: budget ${qs.stop.rounds} round(s) -> ${qs.technique.value.layerHeightMm} mm; settled ${settled.stop.rounds} -> ${settled.technique.value.layerHeightMm} mm")
        settled.stop.reason shouldBe TechniqueExplain.StopReason.SETTLED
        (settled.stop.rounds < qs.stop.rounds) shouldBe true
        settled.explain.text() shouldContain "stopped: settled"
        // a deadline already past: the time limit
        decide(facts("quality=1,speed=1"), System.nanoTime() - 1).stop.reason shouldBe TechniqueExplain.StopReason.TIME_LIMIT
        // locked: nothing to search
        PoseDecision.decideWithTechnique(listOf(soup), facts("quality=1"), flat, Pose.asGiven(), overridePlan = FlatLayers.plan(0.2)).stop.reason shouldBe TechniqueExplain.StopReason.LOCKED
    }

    "the flat tops' surface technique weighed: quality-heavy weights iron, speed-heavy leave them as laid; control: without ironing's spacing and speed it is not weighed, and said" {
        val weigh = setOf(SurfaceTechnique.IRONING)
        val looks = decide(facts("quality=10,speed=1", weigh))
        looks.surfaceTechniques shouldBe setOf(SurfaceTechnique.IRONING)
        looks.technique.why shouldContain "· ironing"
        looks.technique.facts.single { it.startsWith("the flat tops' surface technique weighed") } shouldContain "ironing won"
        val fast = decide(facts("quality=1,speed=10", weigh))
        fast.surfaceTechniques shouldBe emptySet()
        fast.technique.facts.single { it.startsWith("the flat tops' surface technique weighed") } shouldContain "as laid won"
        // the rough flat-top area is measured only where the surface technique is weighed: the block's ledge and top, 304 mm²
        val unweighed = decide(facts("quality=10,speed=1"))
        unweighed.surfaceTechniques shouldBe emptySet()
        unweighed.technique.facts.none { "the flat tops' surface technique weighed" in it } shouldBe true
        PoseDecision.poseMetrics(facts("quality=1")).contains(plymeld.core.decide.Metric.ROUGH_TOP_AREA) shouldBe false
        PoseDecision.poseMetrics(facts("quality=1", weigh)).contains(plymeld.core.decide.Metric.ROUGH_TOP_AREA) shouldBe true
        // control: no spacing and speed for ironing: not weighed, the tops as laid, the reason given
        val blind = decide(facts("quality=10,speed=1", weigh, ironing = false))
        blind.surfaceTechniques shouldBe emptySet()
        blind.technique.facts.single { it.startsWith("ironing not weighed") } shouldContain "plan.technique.ironing.spacing"
    }
})
