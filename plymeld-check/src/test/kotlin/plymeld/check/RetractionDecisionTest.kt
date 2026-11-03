package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import plymeld.core.decide.RetractionDecision
import plymeld.core.decide.Weights
import plymeld.gcode.StringRisk

/**
 * The in-island retraction decision: the hidden travels kept or retracting, weighed on the measures of
 * both. The honesty test: all the weight on speed keeps them (fewer retractions); all of it on quality retracts them when
 * their strings outweigh the restarts they add, and keeps them when they do not.
 */
class RetractionDecisionTest : StringSpec({
    // the hidden travels kept: 60 mm of string over open air, 100 visible restarts (500 mm starved at a 5 mm lag), 400 retractions
    val on = StringRisk.Result(60.0, 60.0, 0.0, 300, 100, 500.0, 400, 20_000.0)
    // every travel retracting: no string, 140 visible restarts (700 mm), 700 retractions
    val off = StringRisk.Result(0.0, 0.0, 0.0, 0, 140, 700.0, 700, 20_000.0)
    val retractS = RetractionDecision.retractS(0.8, 40.0, 40.0, 0.2, 200.0)

    "one retraction's time: the pull, the push back, the hop up and down" {
        retractS shouldBe (0.8 / 40 + 0.8 / 40 + 2 * 0.2 / 200 plusOrMinus 1e-12)
    }

    "all the weight on speed keeps the hidden travels; all of it on quality retracts them when the strings outweigh the restarts" {
        val speed = RetractionDecision.decide("x", on, off, retractS, 1000.0, "test", Weights.parse("quality=0,speed=1", "test"), null, null)
        speed.value shouldBe true
        // quality: the strings (60 of 20 000 mm travel against the baseline's 0) weigh against the restarts (500 vs 700 mm): the
        // string share is scaled by the whole travel, the restart share by the baseline's 700 mm, so the restarts dominate: kept
        val quality = RetractionDecision.decide("x", on, off, retractS, 1000.0, "test", Weights.parse("quality=1,speed=0", "test"), null, null)
        quality.value shouldBe true
        // with far more string the quality weight retracts them
        val stringy = StringRisk.Result(6000.0, 6000.0, 0.0, 300, 100, 500.0, 400, 20_000.0)
        RetractionDecision.decide("x", stringy, off, retractS, 1000.0, "test", Weights.parse("quality=1,speed=0", "test"), null, null).value shouldBe false
        quality.describe() shouldContain "string_risk"
    }

    "a lock is recorded, nothing weighed" {
        val d = RetractionDecision.decide("x", on, off, retractS, 1000.0, "test", Weights.parse("quality=1", "test"), false, "plan.mechanism.motion.no_retract_in_island (test)")
        d.overridden shouldBe true
        d.value shouldBe false
    }
})
