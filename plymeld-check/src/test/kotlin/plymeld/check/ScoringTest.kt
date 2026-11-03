package plymeld.check

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import plymeld.core.decide.Metric
import plymeld.core.decide.Scoring
import plymeld.core.decide.Weights

/**
 * Scoring by the size of the difference: weights are exchange rates. Against a fixed baseline, on a
 * log scale, with every measured metric sharing its goal's weight. Expected totals are worked by hand from the
 * rule, not read back from the code.
 */
class ScoringTest : StringSpec({
    // two plans: A finer (stair-step 0.037) but slower (500 s), B coarser (0.038) and faster (450 s)
    class P(val name: String, val step: Double, val time: Double, val unsupported: Double, val lines: Double = 0.2)
    val a = P("A", 0.037, 500.0, 265.5); val b = P("B", 0.038, 450.0, 265.5)
    // step and time only: the exchange rate between two metrics, one per goal
    val stepTime = mapOf<Metric, (P) -> Double>(Metric.SURFACE_STEP to { it.step }, Metric.TIME_ESTIMATE to { it.time })
    val values = stepTime + mapOf<Metric, (P) -> Double>(Metric.UNSUPPORTED_AREA to { it.unsupported })

    // ---- without a baseline (the earlier rule, kept where no baseline can be measured): relative to the best, at most 1
    "no baseline, quality 10, speed 3: B's 2.7 % coarser step (x10 = 0.270) loses to A's 11.1 % longer time (x3 = 0.333)" {
        val r = Scoring.rank(listOf(a, b), stepTime, Weights.parse("quality=10,speed=3", "test"))
        r.first().item.name shouldBe "B"
        r.first { it.item.name == "B" }.total shouldBe ((0.001 / 0.037) * 10 plusOrMinus 1e-9)
        r.first { it.item.name == "A" }.total shouldBe ((50.0 / 450.0) * 3 plusOrMinus 1e-9)
    }
    "control: quality 20 tips it the other way (0.541 > 0.333)" {
        Scoring.rank(listOf(a, b), stepTime, Weights.parse("quality=20,speed=3", "test")).first().item.name shouldBe "A"
    }
    "a tiny difference counts a tiny amount, not the whole weight" {
        val c = P("C", 0.037, 499.0, 265.5)
        val r = Scoring.rank(listOf(a, c), values, Weights.parse("speed=1", "test"))
        r.first { it.item.name == "A" }.total shouldBe (1.0 / 499.0 plusOrMinus 1e-9)
    }
    "no baseline, best value 0: the difference is a fraction of the reference scale" {
        val z = P("Z", 0.037, 500.0, 0.0); val u = P("U", 0.037, 500.0, 50.0)
        val r = Scoring.rank(listOf(z, u), values, Weights.parse("quality=1", "test"), mapOf(Metric.UNSUPPORTED_AREA to 1000.0))
        // quality's weight is split over its two measured metrics: 0.5 x 50 / 1000
        r.first { it.item.name == "U" }.total shouldBe (0.025 plusOrMinus 1e-9)
        Scoring.parseZeroScale("unsupported_area=500, tip_offset=20", "test") shouldBe mapOf(Metric.UNSUPPORTED_AREA to 500.0, Metric.TIP_OFFSET to 20.0)
    }

    // ---- against a fixed baseline: flat layers at half the nozzle, the same for every candidate; ln
    val base = mapOf(Metric.SURFACE_STEP to 0.04, Metric.TIME_ESTIMATE to 1000.0, Metric.UNSUPPORTED_AREA to 265.5, Metric.LAYER_LINES to 0.2)

    "baseline: time on a log scale, ln(value / baseline); the stair-step (it can be 0) linear, relative to the baseline's" {
        val r = Scoring.rank(listOf(a, b), stepTime, Weights.parse("quality=10,speed=3", "test"), baseline = base)
        // A: 10 x (0.037 - 0.04) / 0.04 + 3 x ln(500 / 1000) = -0.75 - 2.07944; B: 10 x -0.002 / 0.04 + 3 x ln(0.45) = -0.5 - 2.39552
        r.first { it.item.name == "A" }.total shouldBe (-0.75 + 3 * Math.log(0.5) plusOrMinus 1e-9)
        r.first { it.item.name == "B" }.total shouldBe (-0.5 + 3 * Math.log(0.45) plusOrMinus 1e-9)
        r.first().item.name shouldBe "B"
    }

    "twice as good and twice as bad count equally and oppositely (±ln 2), more- and less-is-better alike" {
        val half = P("half", 0.04, 500.0, 265.5); val double = P("double", 0.04, 2000.0, 265.5)
        val r = Scoring.rank(listOf(half, double), stepTime, Weights.parse("speed=1", "test"), baseline = base).associate { it.item.name to it.total }
        r.getValue("half") shouldBe (-Math.log(2.0) plusOrMinus 1e-9)
        r.getValue("double") shouldBe (Math.log(2.0) plusOrMinus 1e-9)
        (r.getValue("half") + r.getValue("double")) shouldBe (0.0 plusOrMinus 1e-12)
        class Q(val bond: Double)
        val bond = Scoring.rank(listOf(Q(200.0), Q(50.0)), mapOf<Metric, (Q) -> Double>(Metric.WEAKEST_BOND to { it.bond }),
            Weights.parse("strength=1", "test"), baseline = mapOf(Metric.WEAKEST_BOND to 100.0))
        bond.first().item.bond shouldBe 200.0
        bond.first().total shouldBe (-Math.log(2.0) plusOrMinus 1e-9)
        bond.last().total shouldBe (Math.log(2.0) plusOrMinus 1e-9)
    }

    "the exchange rate, against the baseline: with quality 10 and speed 3, 1 % worse on quality only if at least 3.3 % faster" {
        val lt = mapOf<Metric, (P) -> Double>(Metric.LAYER_LINES to { it.lines }, Metric.TIME_ESTIMATE to { it.time })
        val even = P("baseline-like", 0.04, 1000.0, 265.5, 0.2)
        val w = Weights.parse("quality=10,speed=3", "test")
        // 1 % worse layer lines (0.202) and 3.2 % faster (968 s): 10 ln 1.01 + 3 ln 0.968 = 0.0995 - 0.0975 = +0.002: loses
        Scoring.rank(listOf(even, P("3.2 %", 0.04, 968.0, 265.5, 0.202)), lt, w, baseline = base).first().item.name shouldBe "baseline-like"
        // control: 3.3 % faster (967 s): 0.0995 - 0.1007 = -0.001: wins
        Scoring.rank(listOf(even, P("3.3 %", 0.04, 967.0, 265.5, 0.202)), lt, w, baseline = base).first().item.name shouldBe "3.3 %"
    }

    "a part is shown with its plain difference from the baseline" {
        val r = Scoring.rank(listOf(a), stepTime, Weights.parse("speed=1", "test"), baseline = base)
        Scoring.explain(r.single()) shouldContain "time.estimate -0.693 [-50.0 %, better]"
    }

    // Independence of irrelevant alternatives, the weight split. A matches the baseline; B has 10 % coarser layer
    // lines and is 20 % faster. C adds nothing but a different unsupported area (300 mm², A and B 265.5), the second
    // quality metric. Quality 10, speed 3. Every measured metric shares its goal's weight: quality 5 on layer lines and 5 on
    // unsupported area, with C or without. B = 5 ln 1.1 + 3 ln 0.8 = 0.4766 - 0.6694 = -0.193 < A = 0: B before A, both
    // ways. Under the old rule (only the metrics that differ share), without C layer lines take all 10: B = 0.953 - 0.669 =
    // +0.284 > 0: A before B; C's arrival halves it and reorders them.
    val lt3 = mapOf<Metric, (P) -> Double>(Metric.LAYER_LINES to { it.lines }, Metric.TIME_ESTIMATE to { it.time }, Metric.UNSUPPORTED_AREA to { it.unsupported })
    val ia = P("A", 0.04, 1000.0, 265.5, 0.2); val ib = P("B", 0.04, 800.0, 265.5, 0.22); val ic = P("C", 0.04, 1000.0, 300.0, 0.2)
    val w = Weights.parse("quality=10,speed=3", "test")
    fun abOrder(r: List<Scoring.Scored<P>>) = r.map { it.item.name }.filter { it == "A" || it == "B" }

    "independence: a candidate that makes a second quality metric differ does not reorder the other two" {
        // droop is a unit-log measure: it needs its unit (one pad, derived in a decision; given here)
        val pad = mapOf(Metric.UNSUPPORTED_AREA to 4.0)
        abOrder(Scoring.rank(listOf(ia, ib), lt3, w, pad, baseline = base)) shouldBe listOf("B", "A")
        abOrder(Scoring.rank(listOf(ia, ib, ic), lt3, w, pad, baseline = base)) shouldBe listOf("B", "A")
        val two = Scoring.rank(listOf(ia, ib), lt3, w, pad, baseline = base).associate { it.item.name to it.total }
        val three = Scoring.rank(listOf(ia, ib, ic), lt3, w, pad, baseline = base).associate { it.item.name to it.total }
        two.getValue("B") shouldBe (5 * Math.log(1.1) + 3 * Math.log(0.8) plusOrMinus 1e-9)
        three.getValue("A") shouldBe (two.getValue("A") plusOrMinus 1e-12)
        three.getValue("B") shouldBe (two.getValue("B") plusOrMinus 1e-12)
        // a metric that does not differ still takes its share, and its part is 0
        Scoring.rank(listOf(ia, ib), lt3, w, pad, baseline = base).first { it.item.name == "B" }.parts.keys shouldBe
            setOf(Metric.LAYER_LINES, Metric.TIME_ESTIMATE, Metric.UNSUPPORTED_AREA)
    }

    // A near-perfect, very slow candidate does not reorder others either, with the old rule as control
    val c = P("C", 0.025, 5000.0, 265.5)
    "independence: adding a near-perfect, very slow candidate does not reorder A and B" {
        val wq = Weights.parse("quality=10,speed=3", "test")
        val two = Scoring.rank(listOf(a, b), stepTime, wq, baseline = base).associate { it.item.name to it.total }
        val three = Scoring.rank(listOf(a, b, c), stepTime, wq, baseline = base).associate { it.item.name to it.total }
        three.getValue("A") shouldBe (two.getValue("A") plusOrMinus 1e-12)
        three.getValue("B") shouldBe (two.getValue("B") plusOrMinus 1e-12)
    }
    "control: relative to the best candidate (no baseline) the same extra candidate DOES reorder them" {
        val wq = Weights.parse("quality=10,speed=3", "test")
        abOrder(Scoring.rank(listOf(a, b), stepTime, wq)) shouldBe listOf("B", "A")
        abOrder(Scoring.rank(listOf(a, b, c), stepTime, wq)) shouldBe listOf("A", "B")
    }

    "no cap: 3x slower than the baseline costs more than 2x slower (ln 3 vs ln 2); control: the old rule's cap scores them alike" {
        val two = P("2x", 0.04, 2000.0, 265.5); val three = P("3x", 0.04, 3000.0, 265.5); val one = P("1x", 0.04, 1000.0, 265.5)
        val r = Scoring.rank(listOf(one, two, three), stepTime, Weights.parse("speed=1", "test"), baseline = base).associate { it.item.name to it.total }
        r.getValue("2x") shouldBe (Math.log(2.0) plusOrMinus 1e-9)
        r.getValue("3x") shouldBe (Math.log(3.0) plusOrMinus 1e-9)
        val old = Scoring.rank(listOf(one, two, three), stepTime, Weights.parse("speed=1", "test")).associate { it.item.name to it.total }
        old.getValue("2x") shouldBe (1.0 plusOrMinus 1e-9)
        old.getValue("3x") shouldBe (1.0 plusOrMinus 1e-9)
    }

    "baseline value 0: the difference from 0 against the reference scale, whoever else is scored" {
        val z = P("Z", 0.04, 1000.0, 0.0); val u = P("U", 0.04, 1000.0, 50.0); val huge = P("huge", 0.04, 1000.0, 5000.0)
        val zeroBase = base + (Metric.UNSUPPORTED_AREA to 0.0)
        val q = Weights.parse("quality=1", "test")
        // quality 1 split over the stair-step and unsupported area; droop on the unit log: 0.5 x ln(1 + 50 / 1000)
        val expected = 0.5 * Math.log(1.05)
        Scoring.rank(listOf(z, u), values, q, mapOf(Metric.UNSUPPORTED_AREA to 1000.0), zeroBase).first { it.item.name == "U" }.total shouldBe (expected plusOrMinus 1e-9)
        // the scale does not come from the candidates: an extreme one changes nothing for U
        Scoring.rank(listOf(z, u, huge), values, q, mapOf(Metric.UNSUPPORTED_AREA to 1000.0), zeroBase).first { it.item.name == "U" }.total shouldBe (expected plusOrMinus 1e-9)
        // a metric the baseline does not give is scored the same way (from 0)
        Scoring.rank(listOf(z, u), values, q, mapOf(Metric.UNSUPPORTED_AREA to 1000.0), base - Metric.UNSUPPORTED_AREA)
            .first { it.item.name == "U" }.total shouldBe (expected plusOrMinus 1e-9)
    }

    "baseline value 0 and no scale: not scored, said, and its share goes to the goal's other metric; control: with a scale it is scored" {
        val z = P("Z", 0.04, 1000.0, 0.0); val u = P("U", 0.05, 1000.0, 50.0)
        val zeroBase = base + (Metric.UNSUPPORTED_AREA to 0.0)
        val r = Scoring.rank(listOf(z, u), values, Weights.parse("quality=1", "test"), baseline = zeroBase)
        val uu = r.first { it.item.name == "U" }
        uu.parts.keys shouldBe setOf(Metric.SURFACE_STEP)
        // quality's whole weight on the stair-step: 1 x 0.01 / 0.04
        uu.total shouldBe (0.25 plusOrMinus 1e-9)
        Scoring.unscoredFact(r)!! shouldContain "unsupported_area: the baseline's value is 0 and there is no scale for it"
        val scaled = Scoring.rank(listOf(z, u), values, Weights.parse("quality=1", "test"), mapOf(Metric.UNSUPPORTED_AREA to 1000.0), zeroBase)
        scaled.first { it.item.name == "U" }.parts.keys shouldBe setOf(Metric.SURFACE_STEP, Metric.UNSUPPORTED_AREA)
        Scoring.unscoredFact(scaled) shouldBe null
    }

    "a metric that cannot be 0 meeting a value of 0 is refused, not scored as infinite" {
        class Q(val bond: Double)
        shouldThrow<IllegalArgumentException> {
            Scoring.rank(listOf(Q(0.0)), mapOf<Metric, (Q) -> Double>(Metric.WEAKEST_BOND to { it.bond }), Weights.parse("strength=1", "test"),
                baseline = mapOf(Metric.WEAKEST_BOND to 100.0))
        }.message!! shouldContain "bond.area = 0.0"
    }

    "more is better (bed contact): more than the baseline subtracts, less adds" {
        class Q(val contact: Double)
        val r = Scoring.rank(listOf(Q(150.0), Q(50.0)), mapOf<Metric, (Q) -> Double>(Metric.BED_CONTACT to { it.contact }),
            Weights.parse("reliability=1", "test"), baseline = mapOf(Metric.BED_CONTACT to 100.0))
        r.first().item.contact shouldBe 150.0
        r.first().total shouldBe (Math.log(100.0 / 150.0) plusOrMinus 1e-9)
        r.last().total shouldBe (Math.log(2.0) plusOrMinus 1e-9)
    }
})
