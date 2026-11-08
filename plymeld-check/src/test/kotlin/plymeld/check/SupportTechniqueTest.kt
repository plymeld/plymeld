package plymeld.check

import io.kotest.assertions.throwables.shouldThrow
import plymeld.core.decide.Metric
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import plymeld.core.decide.Control
import plymeld.core.decide.Coordination
import plymeld.core.decide.FlatLayers
import plymeld.core.decide.LockedSpot
import plymeld.core.decide.Pose
import plymeld.core.decide.PoseDecision
import plymeld.core.decide.PoseFacts
import plymeld.core.decide.PrintTechnique
import plymeld.core.decide.Weights
import plymeld.mesh.TriangleSoup
import plymeld.model.Overhang

/**
 * Support as a technique on a T: a post with a bar across its top, whose two flat undersides are overhangs too steep
 * to hold themselves up.
 *
 * - With the switch unset the search decides per overhang. The bar's undersides are CANTILEVERS (10 mm over air, anchored
 *   on one side), which nothing but support can lay, so their support is a requirement whatever the weights:
 *   material-heavy weights support both as well, and the explain says why.
 * - A locked spot is a requirement: under the weights that choose none, the spot is supported anyway (control: without
 *   it, none). A spot with nothing to support in it is refused with the reason.
 * - A support column's print order is searched: finished first where it saves travel and the head clears it, else
 *   interleaved; with no weight on speed, or a column taller than the nozzle's protrusion, interleaved.
 */
class SupportTechniqueTest : StringSpec({
    val soup = tShape()
    val range = Control(FlatLayers.LAYER_HEIGHT, "mm", Control.Range.Continuous(0.1, 0.3), "test: the tool's thinnest and thickest layer")
    fun facts(weights: String, locked: List<LockedSpot> = emptyList(), support: Boolean? = null, nozzleHeight: Double? = 20.0, travel: Double? = 300.0,
              blocked: List<plymeld.core.decide.Region> = emptyList()) =
        PoseFacts(0.0, 0.0, 270.0, 270.0, 256.0, Overhang.fromAngle(30.0, 0.2, 0.4), "test", Weights.parse(weights, "test"), support,
            layerHeightMm = 0.2, flowMaxMm3s = 18.0, minLayerTimeS = 8.0, firstLayerMm = 0.2, minLayerMm = 0.1, maxLayerMm = 0.3,
            layerHeightCandidates = FlatLayers.coarse(range, 0.2, 3), layerHeightControl = range, layerHeightBudget = 3, baselineLayerMm = 0.2, baselineWhat = "test",
            lockedSpots = locked, nozzleHeightMm = nozzleHeight, nozzleHeightSource = "test", travelSpeedMmS = travel, blocked = blocked)
    val flat = listOf(PrintTechnique.FLAT_LAYERS to null)
    fun decide(f: PoseFacts) = PoseDecision.decideWithTechnique(listOf(soup), f, flat, Pose.asGiven(), heights = FlatLayers.Heights.ONE)
    // the left overhang's underside: x -15..-5, y -5..5 at z 10 (the model's own coordinates); a box around it
    val left = LockedSpot("left", -15.0, -5.0, -5.0, 5.0, 9.0, 11.0, "test")

    // a region support is kept off (support = "never") over the left underside: with the switch on, its 100 mm² droop
    // (unsupported_area measured, support's scars and column only the right's); control: without it, nothing droops and
    // unsupported_area is not even measured (0 by definition)
    "support kept off a region: with the switch on, its faces droop and get no column; control: without it, none droop" {
        val never = plymeld.core.decide.Region("left", doubleArrayOf(-15.0, -5.0, -5.0, 5.0, 9.0, 11.0), null, null, null, null, null, "test",
            support = plymeld.core.decide.Region.NEVER)
        fun values(f: PoseFacts) = PoseDecision.valuesOf(PoseDecision.measureAll(soup, Pose.asGiven(), Overhang.fromAngle(30.0, 0.2, 0.4), f = f, withSections = false,
            plansFor = { _, _ -> listOf(FlatLayers.plan(0.2)) }).single(), f)
        val on = values(facts("quality=1", support = true))
        val kept = values(facts("quality=1", support = true, blocked = listOf(never)))
        (Metric.UNSUPPORTED_AREA in PoseDecision.poseMetrics(facts("quality=1", support = true))) shouldBe false
        (on[Metric.UNSUPPORTED_AREA] ?: 0.0) shouldBe 0.0
        (kept.getValue(Metric.UNSUPPORTED_AREA) > 0) shouldBe true
        (Metric.UNSUPPORTED_AREA in PoseDecision.poseMetrics(facts("quality=1", support = true, blocked = listOf(never)))) shouldBe true
        kept.getValue(Metric.SUPPORT_VOLUME) shouldBe (on.getValue(Metric.SUPPORT_VOLUME) / 2 plusOrMinus 0.02)
    }

    "the shape: two overhang regions of 100 mm² each, standing on the bed, columns 10 mm tall" {
        val m = PoseDecision.measureAll(soup, Pose.asGiven(), Overhang.fromAngle(30.0, 0.2, 0.4), f = facts("quality=1"), withSections = false,
            plansFor = { _, _ -> listOf(FlatLayers.plan(0.2)) }).single()
        m.needsSupportMm2 shouldBe (200.0 plusOrMinus 0.01)
        val regions = m.overhangs!!
        regions.size shouldBe 2
        for (r in regions) { r.areaMm2 shouldBe (100.0 plusOrMinus 0.01); r.standsOnMm shouldBe null; r.columnMm shouldBe (10.0 plusOrMinus 1e-6) }
        // control: with the switch locked, no survey runs
        PoseDecision.measureAll(soup, Pose.asGiven(), Overhang.fromAngle(30.0, 0.2, 0.4), f = facts("quality=1", support = false), withSections = false,
            plansFor = { _, _ -> listOf(FlatLayers.plan(0.2)) }).single().overhangs shouldBe null
    }

    "per overhang: the bar's undersides are cantilevers, so both are supported whatever the weights (a requirement); the explain says so" {
        val droop = decide(facts("quality=1,unsupported_area=50,material=1"))
        val chosen = droop.technique.value.support!!.chosen
        chosen.size shouldBe 2
        chosen.all { !it.locked } shouldBe true
        droop.explain.text() shouldContain "support: decided per overhang"
        droop.explain.decision.support!!.how shouldContain "2 supported by choice"
        // material-heavy weights would rather not pay for support's plastic: the cantilevers are required all the same
        val thrifty = decide(facts("quality=1,material=50"))
        (thrifty.technique.value.support?.chosen.orEmpty()).size shouldBe 2
        thrifty.explain.text() shouldContain "a cantilever: its lowest layer reaches 10.0 mm over air, anchored on one side only, which nothing but support can lay: required"
        (decide(facts("quality=1")).technique.value.support?.chosen.orEmpty()).size shouldBe 2
    }

    "a locked spot is supported under the weights that choose none (control: without it, none); the switch locked off refuses it" {
        val f = facts("quality=1,material=50", locked = listOf(left))
        val d = decide(f)
        val sp = d.technique.value.support!!
        sp.locked.size shouldBe 1
        sp.locked.single().id shouldBe "left"
        sp.chosen.size shouldBe 1                                        // the right overhang: a cantilever, required
        d.explain.decision.support!!.locked.single() shouldContain "'left'"
        d.explain.decision.support!!.locked.single() shouldContain "100.0 mm² of downward faces"
        d.explain.proposals.any { it.p.locked && it.p.technique == PrintTechnique.SUPPORT && it.fate == plymeld.core.decide.TechniqueExplain.Fate.CHOSEN } shouldBe true
        // control: the same weights without the spot support nothing
        (decide(facts("quality=1,material=50")).technique.value.support?.locked.orEmpty()).size shouldBe 0
        // the switch locked off cannot meet the requirement: refused, naming the spot
        shouldThrow<IllegalStateException> { decide(facts("quality=1", locked = listOf(left), support = false)) }.message!! shouldContain "forbids support, but support is locked on spot 'left'"
    }

    "a locked spot with nothing to support in it is refused with the reason; control: one around the overhang is not" {
        // under the left overhang beside the post: within the T's bounds, no downward face in it
        val air = LockedSpot("air", -15.0, -5.0, -5.0, 5.0, 2.0, 8.0, "test")
        shouldThrow<IllegalStateException> { decide(facts("quality=1", locked = listOf(air))) }.message!! shouldContain "locked support spot 'air' (x -15..-5, y -5..5, z 2..8 mm in the model's own coordinates; test) holds no downward face"
        decide(facts("quality=1", locked = listOf(left))).technique.value.support!!.locked.size shouldBe 1
    }

    "the side-by-side order: with weight on speed and the head clearing the column, finished first; a column taller than the nozzle's protrusion, or no weight on speed, interleaved" {
        val first = decide(facts("quality=1,unsupported_area=50,speed=1"))
        first.technique.value.support!!.chosen.all { it.order == Coordination.Order.FIRST } shouldBe true
        first.explain.decision.support!!.orders.first() shouldContain "first"
        first.explain.decision.support!!.orders.first() shouldContain "save about"
        // the clearance: the columns are 10 mm tall, the nozzle protrudes 5 mm: the head would meet a finished column
        val low = decide(facts("quality=1,unsupported_area=50,speed=1", nozzleHeight = 5.0))
        low.technique.value.support!!.chosen.all { it.order == Coordination.Order.INTERLEAVED } shouldBe true
        low.explain.decision.support!!.orders.first() shouldContain "rises above the nozzle tip's 5.00 mm protrusion"
        // the weights: nothing counts toward speed, so the saving is worth nothing and the first order made (interleaved) stays
        val noSpeed = decide(facts("quality=1,unsupported_area=50"))
        noSpeed.technique.value.support!!.chosen.all { it.order == Coordination.Order.INTERLEAVED } shouldBe true
        // control: with the travel speed unknown nothing can be weighed, and the record says so
        decide(facts("quality=1,unsupported_area=50,speed=1", travel = null)).explain.decision.support!!.orders.first() shouldContain "travel speed is not known"
    }
})

/**
 * A T: a post 10 x 10 mm, 10 mm tall, centered on the origin, with a bar 30 x 10 x 4 mm across its top (z 10..14). The
 * bar's undersides outside the post (x -15..-5 and 5..15, y -5..5, at z 10) face straight down: two overhang regions of
 * 100 mm² each. Outward winding.
 */
internal fun tShape(): TriangleSoup {
    val t = ArrayList<Float>()
    fun tri(a: DoubleArray, b: DoubleArray, c: DoubleArray) { for (v in listOf(a, b, c)) for (x in v) t.add(x.toFloat()) }
    fun quad(a: DoubleArray, b: DoubleArray, c: DoubleArray, d: DoubleArray) { tri(a, b, c); tri(a, c, d) }
    fun p(x: Double, y: Double, z: Double) = doubleArrayOf(x, y, z)
    // the post's bottom (facing down) and its four walls
    quad(p(-5.0, -5.0, 0.0), p(-5.0, 5.0, 0.0), p(5.0, 5.0, 0.0), p(5.0, -5.0, 0.0))
    quad(p(-5.0, -5.0, 0.0), p(5.0, -5.0, 0.0), p(5.0, -5.0, 10.0), p(-5.0, -5.0, 10.0))      // -y
    quad(p(5.0, 5.0, 0.0), p(-5.0, 5.0, 0.0), p(-5.0, 5.0, 10.0), p(5.0, 5.0, 10.0))          // +y
    quad(p(-5.0, 5.0, 0.0), p(-5.0, -5.0, 0.0), p(-5.0, -5.0, 10.0), p(-5.0, 5.0, 10.0))      // -x
    quad(p(5.0, -5.0, 0.0), p(5.0, 5.0, 0.0), p(5.0, 5.0, 10.0), p(5.0, -5.0, 10.0))          // +x
    // the bar's undersides outside the post (facing down), its walls and its top
    quad(p(-15.0, -5.0, 10.0), p(-15.0, 5.0, 10.0), p(-5.0, 5.0, 10.0), p(-5.0, -5.0, 10.0))
    quad(p(5.0, -5.0, 10.0), p(5.0, 5.0, 10.0), p(15.0, 5.0, 10.0), p(15.0, -5.0, 10.0))
    quad(p(-15.0, -5.0, 10.0), p(15.0, -5.0, 10.0), p(15.0, -5.0, 14.0), p(-15.0, -5.0, 14.0))   // -y
    quad(p(15.0, 5.0, 10.0), p(-15.0, 5.0, 10.0), p(-15.0, 5.0, 14.0), p(15.0, 5.0, 14.0))       // +y
    quad(p(-15.0, 5.0, 10.0), p(-15.0, -5.0, 10.0), p(-15.0, -5.0, 14.0), p(-15.0, 5.0, 14.0))   // -x
    quad(p(15.0, -5.0, 10.0), p(15.0, 5.0, 10.0), p(15.0, 5.0, 14.0), p(15.0, -5.0, 14.0))       // +x
    quad(p(-15.0, -5.0, 14.0), p(15.0, -5.0, 14.0), p(15.0, 5.0, 14.0), p(-15.0, 5.0, 14.0))     // top
    return TriangleSoup(t.toFloatArray(), t.size / 9)
}
