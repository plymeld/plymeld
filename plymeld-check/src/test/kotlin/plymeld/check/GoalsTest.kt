package plymeld.check

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import plymeld.config.Schema
import plymeld.core.Predict
import plymeld.core.decide.FilamentBasis
import plymeld.core.decide.Goal
import plymeld.core.decide.JobFacts
import plymeld.core.decide.Layout
import plymeld.core.decide.Metric
import plymeld.core.decide.ObjectOrder
import plymeld.core.decide.ObjectOrderDecision
import plymeld.core.decide.TechniquePlan
import plymeld.config.Models
import plymeld.core.decide.Pose
import plymeld.core.decide.PoseDecision
import plymeld.core.decide.PoseFacts
import plymeld.core.decide.PrintTechnique
import plymeld.core.decide.Scoring
import plymeld.core.decide.Weights
import plymeld.model.Overhang

/**
 * The six goals and what counts toward each: the order, accuracy as a goal with no measure
 * yet, support counted under quality (its scars) and material (its plastic) without counting its area twice under
 * quality, and material by mass. Expected values are worked by hand from the shape and the stated densities.
 */
class GoalsTest : StringSpec({
    "the six goals in the user's order, the same in the decisions and the config schema" {
        Goal.entries.map { it.id } shouldBe listOf("quality", "accuracy", "speed", "material", "strength", "reliability")
        Schema.GOALS shouldBe Goal.entries.map { it.id }
        // a weight may name a measure directly (job.priorities.measure.<measure>): the config declares every measure the decisions have
        Schema.MEASURES shouldBe plymeld.core.decide.Metric.entries.map { it.id }
    }

    "accuracy parses as a goal; control: an unknown goal is still refused" {
        Weights.parse("accuracy=2", "test").byGoal shouldBe mapOf(Goal.ACCURACY to 2.0)
        shouldThrow<IllegalStateException> { Weights.parse("beauty=1", "test") }.message!! shouldContain
            "known goals: quality, accuracy, speed, material, strength, reliability"
    }

    "accuracy has its first measure, height_error, so a weight on it counts; control: a goal with no measure in a decision is reported as having no effect" {
        Metric.entries.filter { it.goal == Goal.ACCURACY }.map { it.id } shouldBe listOf("height_error")
        Weights.parse("accuracy=1,quality=1", "test").unmeasured(Metric.entries) shouldBe emptyList()
        Weights.parse("accuracy=1,quality=1", "test").unmeasured(Metric.entries.filter { it.goal != Goal.ACCURACY }) shouldBe listOf(Goal.ACCURACY)
    }

    // The block with a frustum on top, upside down: the frustum's 2 x 2 top on the bed, the frustum's 45° sides holding
    // themselves up, and the block's shoulder (20 x 20 less the frustum's 10 x 10 foot = 300 mm²) facing down 4 mm
    // (9 - 5) above the bed: support columns of 300 x 4 = 1200 mm³. In PLA at 1.24 g/cm³: 1.488 g.
    val soup = blockWithFrustum()
    val upsideDown = PoseDecision.candidates()[1]
    val oh = Overhang.fromAngle(30.0, 0.2, 0.4)
    fun facts(support: Boolean, density: Double?, weights: String = "quality=1,material=1") =
        PoseFacts(0.0, 0.0, 270.0, 270.0, 256.0, oh, "test", Weights.parse(weights, "test"), support, layerHeightMm = 0.2,
            supportDensity = density, supportDensitySource = if (density == null) "no density for 'x' (test)" else "test")
    val m = PoseDecision.measure(soup, upsideDown, oh, layerMm = 0.2)
    // the support-related measures that count toward quality, summed: the area quality charges for support (or its lack)
    fun qualityArea(v: Map<Metric, Double>) = v.filterKeys { it.goal == Goal.QUALITY }.values.sum()

    "the shape: 300 mm² needs support upside down, in columns of 1200 mm³" {
        m.needsSupportMm2 shouldBe (300.0 plusOrMinus 0.01)
        m.supportVolumeMm3 shouldBe (1200.0 plusOrMinus 0.01)
    }

    "support on: its scars count under quality and its plastic under material, by mass; the area is not counted twice" {
        Metric.SUPPORT_INTERFACE.goal shouldBe Goal.QUALITY
        Metric.SUPPORT_MASS.goal shouldBe Goal.MATERIAL
        Metric.SUPPORT_VOLUME.goal shouldBe Goal.MATERIAL
        val v = PoseDecision.supportValues(m, facts(support = true, density = 1.24))
        v[Metric.SUPPORT_INTERFACE]!! shouldBe (300.0 plusOrMinus 1e-9)
        v[Metric.UNSUPPORTED_AREA]!! shouldBe (0.0 plusOrMinus 1e-9)
        v[Metric.SUPPORT_MASS]!! shouldBe (1.488 plusOrMinus 1e-9)
        qualityArea(v) shouldBe (300.0 plusOrMinus 1e-9)
        // control: counting the same area as both droop and scars (unsupported_area and support.interface both 300, the
        // rule earlier had support.interface reported only) gives 600: the check above would refuse it
        val doubled = mapOf(Metric.UNSUPPORTED_AREA to m.needsSupportMm2, Metric.SUPPORT_INTERFACE to m.needsSupportMm2)
        qualityArea(doubled) shouldBe (600.0 plusOrMinus 0.01)
        (Math.abs(qualityArea(doubled) - m.needsSupportMm2) < 0.01) shouldBe false
    }

    "support off: the same area droops (unsupported_area, quality), weighted by its severity (horizontal faces: the cap, 10x), and support counts nothing" {
        val v = PoseDecision.supportValues(m, facts(support = false, density = 1.24))
        v[Metric.UNSUPPORTED_AREA]!! shouldBe (3000.0 plusOrMinus 1e-9)
        v[Metric.SUPPORT_INTERFACE]!! shouldBe (0.0 plusOrMinus 1e-9)
        v[Metric.SUPPORT_MASS]!! shouldBe (0.0 plusOrMinus 1e-9)
        qualityArea(v) shouldBe (3000.0 plusOrMinus 1e-9)
    }

    "without a density, support's plastic is compared by volume, and the decision says so; control: with one, by mass" {
        val v = PoseDecision.supportValues(m, facts(support = true, density = null))
        v.containsKey(Metric.SUPPORT_MASS) shouldBe false
        v[Metric.SUPPORT_VOLUME]!! shouldBe (1200.0 plusOrMinus 1e-9)
        val byVolume = PoseDecision.decideWithTechnique(listOf(soup), facts(true, null), listOf(PrintTechnique.FLAT_LAYERS to null), upsideDown).pose
        byVolume.facts.any { "support.volume -> material" in it } shouldBe true
        byVolume.facts.any { "by volume, not mass: no density for 'x'" in it } shouldBe true
        val byMass = PoseDecision.decideWithTechnique(listOf(soup), facts(true, 1.24), listOf(PrintTechnique.FLAT_LAYERS to null), upsideDown).pose
        byMass.facts.any { "support.mass -> material" in it && "support.interface -> quality" in it } shouldBe true
        byMass.facts.any { "by mass, density 1.24 g/cm³" in it } shouldBe true
    }

    "scored: support's plastic takes the material weight and its scars the quality weight, droop none" {
        // as given (nothing needs support) against upside down, support on; reference scales given, as the best is 0
        val asGiven = PoseDecision.measure(soup, Pose.asGiven(), oh, layerMm = 0.2)
        val f = facts(support = true, density = 1.24)
        val values = PoseDecision.supportValues(m, f).keys.filter { it in PoseDecision.poseMetrics(f) }.associateWith { k -> { x: PoseDecision.Measure -> PoseDecision.supportValues(x, f).getValue(k) } }
        val r = Scoring.rank(listOf(asGiven, m), values, f.weights, mapOf(Metric.SUPPORT_INTERFACE to 1000.0, Metric.SUPPORT_MASS to 10.0))
        val worse = r.first { it.item === m }
        worse.parts.keys shouldBe setOf(Metric.SUPPORT_INTERFACE, Metric.SUPPORT_MASS)
        // no baseline here (the `investigate` path): linear against the scales; with a baseline the unit log applies
        worse.total shouldBe (300.0 / 1000.0 + 1.488 / 10.0 plusOrMinus 1e-9)
    }

    "the weakest bond ignores an island narrower than a line (0.1 x 0.1 mm on a 10 x 10 mm block, 0.4 mm lines): the block's 100 mm²" {
        fun box(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float): FloatArray {
            val v = arrayOf(floatArrayOf(x0, y0, z0), floatArrayOf(x1, y0, z0), floatArrayOf(x1, y1, z0), floatArrayOf(x0, y1, z0),
                floatArrayOf(x0, y0, z1), floatArrayOf(x1, y0, z1), floatArrayOf(x1, y1, z1), floatArrayOf(x0, y1, z1))
            val f = intArrayOf(0, 2, 1, 0, 3, 2, 4, 5, 6, 4, 6, 7, 0, 1, 5, 0, 5, 4, 1, 2, 6, 1, 6, 5, 2, 3, 7, 2, 7, 6, 3, 0, 4, 3, 4, 7)
            return FloatArray(12 * 9).also { out -> for (t in 0 until 12) for (k in 0 until 3) for (d in 0 until 3) out[9 * t + 3 * k + d] = v[f[3 * t + k]][d] }
        }
        val block = box(0f, 0f, 0f, 10f, 10f, 5f); val lock = box(4.95f, 4.95f, 5f, 5.05f, 5.05f, 10f)
        val s = plymeld.mesh.TriangleSoup(block + lock, 24)
        oh.widthMm shouldBe (0.4 plusOrMinus 1e-9)
        val bond = PoseDecision.measure(s, Pose.asGiven(), oh, f = facts(support = false, density = 1.24), withSections = true, layerMm = 0.2).weakestBondMm2!!
        // control (seen with the filter taken out of the code): the 0.01 mm² lock's own section, a bond nothing can print
        bond shouldBe (100.0 plusOrMinus 0.01)
    }

    "a usable pose touching the bed by under 0.005 mm² keeps that contact (log scale): rounding does not make it 0" {
        val tiny = PoseDecision.Measure(0.004, 0.0, 10.0, 5.0, 5.0, 0.0, weakestBondMm2 = 0.003, timeEstimateS = 0.002)
        val v = PoseDecision.valuesOf(tiny, facts(support = false, density = 1.24))
        v.getValue(Metric.BED_CONTACT) shouldBe 0.004
        v.getValue(Metric.WEAKEST_BOND) shouldBe 0.003
        v.getValue(Metric.TIME_ESTIMATE) shouldBe 0.002
        // and it scores: a log-scale part, not a refusal (control: 0 is refused, ScoringTest)
        Scoring.rank(listOf(tiny), mapOf<Metric, (PoseDecision.Measure) -> Double>(Metric.BED_CONTACT to { PoseDecision.valuesOf(it, facts(false, 1.24)).getValue(Metric.BED_CONTACT) }),
            Weights.parse("reliability=1", "test"), baseline = mapOf(Metric.BED_CONTACT to 400.0)).single().total shouldBe (Math.log(400.0 / 0.004) plusOrMinus 1e-9)
    }

    "the pose decision's baseline: in the as-given pose where poses compete, in the locked pose where it is locked" {
        // two uniform heights, so a locked pose still has candidates to score; support on: the area that needs it is 0 as
        // given and 300 mm² upside down, which tells the two baselines apart
        fun f(baseline: Double?) = PoseFacts(0.0, 0.0, 270.0, 270.0, 256.0, oh, "test", Weights.parse("quality=1,material=1,speed=1", "test"), true,
            layerHeightMm = 0.2, minLayerMm = 0.08, layerHeightCandidates = listOf(0.1, 0.2), supportDensity = 1.24, supportDensitySource = "test",
            baselineLayerMm = baseline, baselineWhat = "test")
        val uniform = listOf(PrintTechnique.FLAT_LAYERS to null)
        fun line(d: plymeld.core.decide.Decision<*>) = d.facts.single { it.startsWith("baseline") }
        val free = line(PoseDecision.decideWithTechnique(listOf(soup), f(0.2), uniform).pose)
        free shouldContain "flat layers 0.20 mm = test, in the as-given pose"
        free shouldContain "support.interface 0.000 mm²"
        val locked = line(PoseDecision.decideWithTechnique(listOf(soup), f(0.2), uniform, upsideDown).technique)
        locked shouldContain "in the locked pose"
        locked shouldContain "support.interface 300.0 mm²"
        // control: no baseline layer height (no nozzle known): scored against the best, and said
        line(PoseDecision.decideWithTechnique(listOf(soup), f(null), uniform).pose) shouldContain "baseline: none (test)"
    }

    "material by mass: each tool's filament length x its cross-section x density" {
        val b = FilamentBasis.of(mapOf(0 to listOf(FilamentBasis.Filament("pla", 1.75, 1.24)), 1 to listOf(FilamentBasis.Filament("petg", 1.75, 1.27))))
        b.metric shouldBe Metric.FILAMENT_MASS
        // pi x 1.75² / 4 = 2.405282 mm²: 1000 mm of PLA = 2.982550 g, 500 mm of PETG = 1.527354 g
        b.of(mapOf(0 to 1000.0, 1 to 500.0)) shouldBe (4.509904 plusOrMinus 1e-5)
    }

    "control: a filament with no density compares by volume and names it; differing densities in one tool likewise; differing diameters not at all" {
        val noDensity = FilamentBasis.of(mapOf(0 to listOf(FilamentBasis.Filament("pla", 1.75, 1.24)), 1 to listOf(FilamentBasis.Filament("mystery", 1.75, null))))
        noDensity.metric shouldBe Metric.FILAMENT_VOLUME
        noDensity.why shouldContain "no density for 'mystery'"
        noDensity.of(mapOf(0 to 1000.0, 1 to 500.0)) shouldBe (3607.92 plusOrMinus 0.01)
        val shared = FilamentBasis.of(mapOf(0 to listOf(FilamentBasis.Filament("pla", 1.75, 1.24), FilamentBasis.Filament("petg", 1.75, 1.27))))
        shared.metric shouldBe Metric.FILAMENT_VOLUME
        shared.why shouldContain "different densities"
        FilamentBasis.of(mapOf(0 to listOf(FilamentBasis.Filament("a", 1.75, 1.24), FilamentBasis.Filament("b", 2.85, 1.24)))).metric shouldBe null
    }

    // Object order on two copies of the shape (a synthetic C5 head, so one at a time is usable), with a stand-in
    // prediction: the same time, 1000 mm of filament printed together, 1100 mm one at a time (all in tool 0)
    val c5 = Models.bundled.forModel("flashforge-c5").withHead(0, mapOf("head_minus_x" to 50.0, "head_plus_x" to 20.0,
        "head_minus_y" to 30.0, "head_plus_y" to 30.0), "test head", "test")
    val jf = JobFacts(PoseFacts(0.0, 0.0, 250.0, 250.0, 250.0, oh, "test", layerHeightMm = 0.2), 0.42, "test", 0.2, 0.4, brimReachMm = 5.0)
    val two = listOf("a" to listOf(soup), "b" to listOf(soup))
    val uniform = List(2) { TechniquePlan.whole(PrintTechnique.FLAT_LAYERS) }
    val predict = { l: Layout -> Predict.Prediction(100.0, 0.0, "test", filamentByToolMm = mapOf(0 to if (l.order == ObjectOrder.TOGETHER) 1000.0 else 1100.0)) }
    fun order(basis: FilamentBasis) = ObjectOrderDecision.decide(c5, setOf(0), two, uniform, jf, Weights.parse("material=1", "test"), predict = predict, filament = basis)

    "object order: filament by mass; 10 % more filament one at a time scores 0.100 under material; control: by volume without a density" {
        val byMass = order(FilamentBasis.of(mapOf(0 to listOf(FilamentBasis.Filament("pla", 1.75, 1.24)))))
        byMass.value.order shouldBe ObjectOrder.TOGETHER
        byMass.facts.any { it.startsWith("material: by mass: 'pla' 1.24 g/cm³") } shouldBe true
        byMass.alternatives.first { it.label == ObjectOrder.ONE_AT_A_TIME.label }.score shouldContain "(filament.mass 0.100)"
        val byVolume = order(FilamentBasis.of(mapOf(0 to listOf(FilamentBasis.Filament("x", 1.75, null)))))
        byVolume.facts.any { "by volume, not mass: no density for 'x'" in it } shouldBe true
        byVolume.alternatives.first { it.label == ObjectOrder.ONE_AT_A_TIME.label }.score shouldContain "(filament.volume 0.100)"
        // a tool whose filaments differ in diameter: material not compared, and the weight on it is reported as unused
        val none = order(FilamentBasis.of(mapOf(0 to listOf(FilamentBasis.Filament("a", 1.75, 1.24), FilamentBasis.Filament("b", 2.85, 1.24)))))
        none.facts.any { it.startsWith("material not compared: tool 1 holds filaments of different diameters") } shouldBe true
        none.facts.any { "goal 'material' has weight but nothing this decision measures" in it } shouldBe true
    }

    "object order against the baseline: every object flat at its baseline height, printed together; control: none given, the best" {
        val pla = FilamentBasis.of(mapOf(0 to listOf(FilamentBasis.Filament("pla", 1.75, 1.24))))
        val seen = ArrayList<Layout>()
        // the baseline's stand-in prediction: 2000 mm of filament, the same time as the candidates
        val d = ObjectOrderDecision.decide(c5, setOf(0), two, uniform, jf, Weights.parse("material=1", "test"), predict = predict, filament = pla,
            baselineLayers = listOf(0.2, 0.3), baselineWhat = "test",
            predictBaseline = { l -> seen += l; Predict.Prediction(100.0, 0.0, "test", filamentByToolMm = mapOf(0 to 2000.0)) })
        seen.single().order shouldBe ObjectOrder.TOGETHER
        seen.single().techniques.map { it.single to it.layerHeightMm } shouldBe listOf(PrintTechnique.FLAT_LAYERS to 0.2, PrintTechnique.FLAT_LAYERS to 0.3)
        d.facts.any { it.startsWith("baseline: every object in flat layers at its baseline height (test), printed together: time.prediction 100.0 s, filament.mass 5.965 g") } shouldBe true
        // 1000 and 1100 mm against 2000: ln 0.5 and ln 0.55 (mass is length x a constant, so the ratio is the lengths')
        d.alternatives.first { it.label == ObjectOrder.TOGETHER.label }.score shouldContain "(filament.mass -0.693 [-50.0 %, better])"
        d.alternatives.first { it.label == ObjectOrder.ONE_AT_A_TIME.label }.score shouldContain "(filament.mass -0.598 [-45.0 %, better])"
        d.value.order shouldBe ObjectOrder.TOGETHER
        // control: no baseline given, scored against the best order, and the decision says so
        order(pla).facts.any { it.startsWith("baseline: none (no baseline given)") } shouldBe true
    }

    "filament by tool adds up to the net filament; before the first T it goes to the tool that T selects; no T = tool 0" {
        val g = "M83\nG1 X1 E1\nT1\nG1 X2 E2\nG1 E-0.5\nT0\nG1 X3 E0.5\n"
        Predict.filamentByTool(g) shouldBe mapOf(1 to 2.5, 0 to 0.5)
        Predict.filamentByTool(g).values.sum() shouldBe (Predict.netFilament(g) plusOrMinus 1e-12)
        Predict.filamentByTool("M83\nG1 X1 E1\nG1 X2 E2\n") shouldBe mapOf(0 to 3.0)
    }
})
