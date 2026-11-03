package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import plymeld.core.decide.Metric
import plymeld.core.decide.PoseDecision
import plymeld.core.decide.PoseFacts
import plymeld.core.decide.Scoring
import plymeld.core.decide.Weights
import plymeld.model.Overhang

/**
 * A weight is a share of the total, never a magic number: scaling every weight by the same factor
 * changes no order, one goal positive and the rest 0 is full authority, a goal at 0 has none; and the one absolute reading
 * of a score, method.search.pose.keep_margin, is taken per unit of weight in effect.
 */
class WeightScaleTest : StringSpec({
    class C(val name: String, val step: Double, val time: Double)
    val items = listOf(C("A", 0.10, 1000.0), C("B", 0.12, 900.0), C("C", 0.08, 1200.0))
    val values = mapOf<Metric, (C) -> Double>(Metric.SURFACE_STEP to { it.step }, Metric.TIME_ESTIMATE to { it.time })
    val baseline = mapOf(Metric.SURFACE_STEP to 0.10, Metric.TIME_ESTIMATE to 1000.0)

    "the same weights at any scale rank the same, and every score scales with the total" {
        val a = Scoring.rank(items, values, Weights.parse("quality=2,speed=3", "test"), baseline = baseline)
        val b = Scoring.rank(items, values, Weights.parse("quality=200,speed=300", "test"), baseline = baseline)
        a.map { it.item.name } shouldBe b.map { it.item.name }
        for ((x, y) in a.zip(b)) y.total shouldBe (100 * x.total plusOrMinus 1e-9)
    }

    "one goal positive and the rest 0 is full authority; a goal at 0 takes no part" {
        val onlyQuality = Scoring.rank(items, values, Weights.parse("quality=7,speed=0", "test"), baseline = baseline)
        onlyQuality.first().item.name shouldBe "C"                 // the smallest stair-step, however slow
        onlyQuality.all { Metric.TIME_ESTIMATE !in it.parts } shouldBe true
        val onlySpeed = Scoring.rank(items, values, Weights.parse("quality=0,speed=1", "test"), baseline = baseline)
        onlySpeed.first().item.name shouldBe "B"                   // the fastest, however coarse
        onlySpeed.all { Metric.SURFACE_STEP !in it.parts } shouldBe true
    }

    "the keep margin reads the gap per unit of weight in effect: the same at any total" {
        val oh = Overhang.fromAngle(30.0, 0.2, 0.4)
        fun facts(w: String) = PoseFacts(0.0, 0.0, 270.0, 270.0, 256.0, oh, "test", Weights.parse(w, "test"), layerHeightMm = 0.2)
        val small = PoseDecision.weightInEffect(facts("quality=1,speed=1,reliability=1"))
        val big = PoseDecision.weightInEffect(facts("quality=100,speed=100,reliability=100"))
        big shouldBe (100 * small plusOrMinus 1e-9)
        // a goal the pose decision cannot measure (accuracy has height_error only when layers are known; here it is) adds its weight; one with no measure at all does not
        val withStrength = PoseDecision.weightInEffect(facts("quality=1,speed=1,reliability=1,strength=5"))
        withStrength shouldBe (small + 5 plusOrMinus 1e-9)
        PoseDecision.weightInEffect(facts("quality=0,speed=0,reliability=0,material=0,strength=0,accuracy=1")).let { (it > 0) shouldBe true }
    }
})
