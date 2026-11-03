package plymeld.core.decide

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import plymeld.core.decide.Coordination.Volume.ZBand

/**
 * Choosing the set: proposals that overlap form
 * conflict groups, within a group the options are which members (or none) take the plastic, and a dynamic program
 * along the print order with the handoff as its state picks the best few sets on contributions linearised at the
 * baseline (Scoring.linearised), dropping a set whose fill or portion cannot start on what lies below it.
 */
class SearchSetsTest : StringSpec({
    fun prop(id: String, lo: Double, hi: Double, t: PrintTechnique = PrintTechnique.FLAT_LAYERS, locked: Boolean = false) =
        Coordination.Use(Coordination.Proposal(id, t, null, ZBand(lo, hi), "test", "test", locked = locked), ZBand(lo, hi))
    fun ids(c: Coordination.Chosen) = c.combination.uses.map { it.proposal.id }

    "conflict groups: overlapping proposals share a group (through a chain too), disjoint ones are independent; the options are every non-overlapping subset and none" {
        val a = prop("a", 0.0, 4.0); val b = prop("b", 3.0, 7.0); val c = prop("c", 6.0, 9.0); val d = prop("d", 12.0, 15.0)
        val gs = Coordination.groups(listOf(d, c, a, b)) { 0.0 }
        gs.map { g -> g.members.map { it.proposal.id } } shouldBe listOf(listOf("a", "b", "c"), listOf("d"))
        // a and c do not overlap each other, so {a, c} is an option; {a, b} and {b, c} are not
        gs[0].options.map { o -> o.uses.map { it.proposal.id } } shouldBe listOf(emptyList(), listOf("a"), listOf("b"), listOf("c"), listOf("a", "c"))
        gs[1].options.map { o -> o.uses.map { it.proposal.id } } shouldBe listOf(emptyList(), listOf("d"))
        // a locked member (a requirement) is in every option: no "none"
        val locked = Coordination.groups(listOf(prop("p", 0.0, 4.0, locked = true), prop("q", 3.0, 7.0))) { 0.0 }
        locked.single().options.map { o -> o.uses.map { it.proposal.id } } shouldBe listOf(listOf("p"))
    }

    "the dynamic program keeps what lowers the score and leaves what raises it; the best k in order; control: a cost made negative is taken" {
        val a = prop("a", 0.0, 4.0); val b = prop("b", 6.0, 9.0)
        val cost = mapOf("a" to -1.0, "b" to 0.5)
        val gs = Coordination.groups(listOf(a, b)) { cost.getValue(it.proposal.id) }
        val best = Coordination.chooseSets(gs, 3)
        best.map(::ids) shouldBe listOf(listOf("a"), listOf("a", "b"), emptyList())
        best.map { it.cost } shouldBe listOf(-1.0, -0.5, 0.0)
        // control: with b's contribution negative too, both are taken
        Coordination.chooseSets(Coordination.groups(listOf(a, b)) { -0.5 }, 1).single().let { ids(it) shouldBe listOf("a", "b"); it.cost shouldBe -1.0 }
    }

    "the handoff is the state: a set whose flat fill would have to start on a curved band's warped top is dropped; control: the curved band at the top is kept" {
        // a curved band in the middle: flat layers would have to fill above it, and nothing is built on a warped top
        val middle = prop("curved", 3.0, 6.0, PrintTechnique.CURVED_LAYERS)
        val gs = Coordination.groups(listOf(middle)) { -5.0 }
        Coordination.chooseSets(gs, 3).map(::ids) shouldBe listOf(emptyList())
        // control: the same band reaching the top needs no fill above it, and its handoff from the flat fill below is compatible
        val top = prop("curved", 3.0, Double.POSITIVE_INFINITY, PrintTechnique.CURVED_LAYERS)
        Coordination.chooseSets(Coordination.groups(listOf(top)) { -5.0 }, 3).map(::ids) shouldBe listOf(listOf("curved"), emptyList())
        // the handoff's cost is a number: 0 for every built technique (same tool and temperature), and said
        Coordination.handoffs(listOf(Coordination.Portion(ZBand(0.0, 3.0), Coordination.Step.DEPOSIT, PrintTechnique.FLAT_LAYERS, null, null))).single().costValue shouldBe 0.0
        Coordination.handoffCost(Coordination.Surface.WARPED_TOP, Coordination.contractOf(PrintTechnique.FLAT_LAYERS)).isInfinite() shouldBe true
    }

    "independence: an irrelevant extra proposal (one that raises the score) changes neither the winner nor the order of the others; control: one that lowers it does" {
        val a = prop("a", 0.0, 4.0); val b = prop("b", 6.0, 9.0); val extra = prop("x", 12.0, 15.0)
        val cost = mapOf("a" to -1.0, "b" to -0.2, "x" to 3.0)
        val before = Coordination.chooseSets(Coordination.groups(listOf(a, b)) { cost.getValue(it.proposal.id) }, 2).map(::ids)
        val with = Coordination.chooseSets(Coordination.groups(listOf(a, b, extra)) { cost.getValue(it.proposal.id) }, 2).map(::ids)
        before shouldBe listOf(listOf("a", "b"), listOf("a"))
        with shouldBe before
        // control: an extra proposal that lowers the score joins the winner
        val better: Map<String, Double> = cost + ("x" to -2.0)
        Coordination.chooseSets(Coordination.groups(listOf(a, b, extra)) { better.getValue(it.proposal.id) }, 1).single().let(::ids) shouldBe listOf("a", "b", "x")
    }

    "linearised at the baseline: a measure's difference at its weight's share over the baseline's value, worse adds, better subtracts; an unscorable measure adds nothing" {
        val w = Weights.parse("quality=2,speed=1", "test")
        val measured = listOf(Metric.SURFACE_STEP, Metric.LAYER_LINES, Metric.TIME_ESTIMATE)
        val base = mapOf(Metric.SURFACE_STEP to 0.1, Metric.LAYER_LINES to 0.2, Metric.TIME_ESTIMATE to 100.0)
        // quality's 2 is split over its two measures (1 each): the stair-step 10 % better subtracts 0.1, time 20 % worse adds 0.2
        val v = Scoring.linearised(mapOf(Metric.SURFACE_STEP to -0.01, Metric.TIME_ESTIMATE to 20.0), w, measured, emptyMap(), base)
        (Math.abs(v - 0.1) < 1e-9) shouldBe true
        // a measure with a zero baseline and no scale is not counted
        Scoring.linearised(mapOf(Metric.UNSUPPORTED_AREA to 5.0), Weights.parse("quality=1", "test"), listOf(Metric.UNSUPPORTED_AREA), emptyMap(), mapOf(Metric.UNSUPPORTED_AREA to 0.0)) shouldBe 0.0
        // with a scale it is, from 0: droop is a unit-log measure, ln(1 + 5 / 50) on the exact curve
        (Math.abs(Scoring.linearised(mapOf(Metric.UNSUPPORTED_AREA to 5.0), Weights.parse("quality=1", "test"), listOf(Metric.UNSUPPORTED_AREA), mapOf(Metric.UNSUPPORTED_AREA to 50.0), mapOf(Metric.UNSUPPORTED_AREA to 0.0)) - Math.log(1.1)) < 1e-12) shouldBe true
    }
})
