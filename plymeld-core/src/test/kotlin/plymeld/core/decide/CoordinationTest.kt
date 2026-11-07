package plymeld.core.decide

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import plymeld.core.decide.Coordination.Volume.ZBand

/**
 * The coordinator's rules: a proposal is trimmed only where its
 * technique allows; flat layers fill what the chosen proposals leave; a handoff is compatible only when what one portion
 * leaves is what the next starts on. The kinds: one surface technique per exposed surface, only on what it can serve; each loop's
 * seam one style, scarf seams never on a fuzzed wall.
 */
class CoordinationTest : StringSpec({
    fun prop(id: String, t: PrintTechnique, v: Coordination.Volume, rank: Double = 0.0) = Coordination.Proposal(id, t, null, v, "test", "test", rank)

    "trims: flat layers anywhere, curved layers only at their lower edge, a lifted bridge never; control: an untrimmed volume is always allowed" {
        Coordination.allows(prop("f", PrintTechnique.FLAT_LAYERS, ZBand(1.0, 5.0)), ZBand(1.2, 4.6)) shouldBe true
        val curved = prop("c", PrintTechnique.CURVED_LAYERS, ZBand(0.0, Double.POSITIVE_INFINITY))
        Coordination.allows(curved, ZBand(6.0, Double.POSITIVE_INFINITY)) shouldBe true
        Coordination.allows(curved, ZBand(0.0, 8.0)) shouldBe false
        val bridge = prop("b", PrintTechnique.LIFTED_BRIDGE, ZBand(0.0, Double.POSITIVE_INFINITY))
        Coordination.allows(bridge, ZBand(2.0, Double.POSITIVE_INFINITY)) shouldBe false
        Coordination.allows(bridge, ZBand(0.0, Double.POSITIVE_INFINITY)) shouldBe true
        // the coordinator refuses a trim its technique does not allow
        shouldThrow<IllegalArgumentException> { Coordination.use(bridge, ZBand(2.0, Double.POSITIVE_INFINITY)) }
            .message!! shouldContain "allows cuts none across its span"
        Coordination.use(bridge, bridge.volume).trimmed shouldBe false
    }

    "fill: the gaps the chosen proposals leave, bottom to top; none when they take the whole; control: nothing chosen, the whole" {
        Coordination.fill(Coordination.Volume.WHOLE, listOf(ZBand(2.0, 4.0), ZBand(6.0, Double.POSITIVE_INFINITY))) shouldBe
            listOf(ZBand(0.0, 2.0), ZBand(4.0, 6.0))
        Coordination.fill(Coordination.Volume.WHOLE, listOf(ZBand(0.0, Double.POSITIVE_INFINITY))) shouldBe emptyList()
        Coordination.fill(Coordination.Volume.WHOLE, emptyList()) shouldBe listOf(Coordination.Volume.WHOLE)
    }

    "handoffs: flat -> curved compatible, curved -> flat not (nothing is built on a warped top); the first starts on the bed" {
        fun portion(lo: Double, hi: Double, t: PrintTechnique) = Coordination.Portion(ZBand(lo, hi), Coordination.Step.DEPOSIT, t, null, null)
        val ok = Coordination.handoffs(listOf(portion(0.0, 6.0, PrintTechnique.FLAT_LAYERS), portion(6.0, Double.POSITIVE_INFINITY, PrintTechnique.CURVED_LAYERS)))
        ok.map { it.compatible } shouldBe listOf(true, true)
        ok[0].from shouldBe "the bed"
        // control: the wrong way round is caught
        Coordination.handoffs(listOf(portion(0.0, 6.0, PrintTechnique.CURVED_LAYERS), portion(6.0, 9.0, PrintTechnique.FLAT_LAYERS))).map { it.compatible } shouldBe listOf(true, false)
    }

    // techniques typed by the dimension of what they decide
    "kinds: the layer techniques volumes, strands a curve (not built), the surface techniques surfaces, the seam styles curves; control: no curve technique of the volume kind is built" {
        PrintTechnique.entries.filter { it.implemented }.map { it.kind }.distinct() shouldBe listOf(TechniqueKind.VOLUME)
        PrintTechnique.FREE_STRANDS.kind shouldBe TechniqueKind.CURVE
        PrintTechnique.entries.filter { it.kind == TechniqueKind.CURVE }.none { it.implemented } shouldBe true
        TechniqueKind.entries.map { it.id } shouldBe listOf("volume", "surface", "curve")
        SurfaceTechnique.entries.map { it.short } shouldBe listOf("ironing", "z_antialiasing", "fuzzy_skin", "wave_overhang")
        SurfaceTechnique.entries.all { it.kind == TechniqueKind.SURFACE } shouldBe true
        SurfaceTechnique.entries.associate { it.short to it.serves } shouldBe mapOf("ironing" to setOf(Coordination.Facing.FLAT_TOP),
            "z_antialiasing" to setOf(Coordination.Facing.SLOPED_TOP), "fuzzy_skin" to setOf(Coordination.Facing.WALL), "wave_overhang" to setOf(Coordination.Facing.UNDERSIDE))
        SeamStyle.entries.map { it.short } shouldBe listOf("corners", "back", "least_visible", "scarf_seam")
        SeamStyle.entries.all { it.kind == TechniqueKind.CURVE } shouldBe true
    }

    val flat = Coordination.Portion(Coordination.Volume.WHOLE, Coordination.Step.DEPOSIT, PrintTechnique.FLAT_LAYERS, null, null)
    fun switches(vararg on: Pair<SurfaceTechnique, String>, scarf: String? = null, placement: String = "corners") = SurfaceTechniques.Switches(SurfaceTechnique.entries.associateWith { t ->
        on.firstOrNull { it.first == t }?.let { SurfaceTechniques.Lock(it.second, true, "test: on") } ?: SurfaceTechniques.Lock("none", false, "test: off")
    }, SurfaceTechniques.Lock(placement, true, "test: $placement"), scarf?.let { SurfaceTechniques.Lock(it, true, "test: scarf on") } ?: SurfaceTechniques.Lock("none", false, "test: scarf off"))
    fun surfaceTechniqueOf(c: Coordination.SurfaceTechniqueChoice, facing: Coordination.Facing, side: Coordination.Side? = null, within: Coordination.Portion = flat) =
        c.regions.single { it.region.facing == facing && it.region.side == side && it.region.within === within }.surfaceTechnique
    fun seam(c: Coordination.SurfaceTechniqueChoice, loops: Coordination.Loops) = c.seams.single { it.curve.loops == loops }

    "one surface technique per surface: with every surface technique switched on, each region has one, by what it faces; two switched on for one surface refused; control: one of them off, the other is the surface technique" {
        val all = SurfaceTechniques.choose(listOf(flat), switches(SurfaceTechnique.IRONING to "top", SurfaceTechnique.Z_ANTIALIASING to "true", SurfaceTechnique.FUZZY_SKIN to "allwalls"))
        surfaceTechniqueOf(all, Coordination.Facing.FLAT_TOP) shouldBe SurfaceTechnique.IRONING
        surfaceTechniqueOf(all, Coordination.Facing.SLOPED_TOP) shouldBe SurfaceTechnique.Z_ANTIALIASING
        surfaceTechniqueOf(all, Coordination.Facing.WALL, Coordination.Side.OUTSIDE) shouldBe SurfaceTechnique.FUZZY_SKIN
        surfaceTechniqueOf(all, Coordination.Facing.WALL, Coordination.Side.HOLES) shouldBe SurfaceTechnique.FUZZY_SKIN
        surfaceTechniqueOf(all, Coordination.Facing.UNDERSIDE) shouldBe null
        all.regions.all { r -> r.canServe.count { it.on } <= 1 } shouldBe true
        all.regions.size shouldBe Coordination.handOver(listOf(flat)).size
        // fuzzy skin on every face (an option the user may choose one day) and ironing would both claim the flat tops: refused
        val regions = Coordination.handOver(listOf(flat))
        val top = regions.single { it.facing == Coordination.Facing.FLAT_TOP }
        val everyFace: (SurfaceTechnique, Coordination.Facing) -> Boolean = { f, x -> x in f.serves || f == SurfaceTechnique.FUZZY_SKIN }
        shouldThrow<IllegalArgumentException> {
            Coordination.chooseSurfaceTechniques(regions, listOf(Coordination.SurfaceTechniqueOffer(SurfaceTechnique.IRONING, top, true, "t"), Coordination.SurfaceTechniqueOffer(SurfaceTechnique.FUZZY_SKIN, top, true, "t")), everyFace)
        }.message!! shouldContain "one surface technique per surface"
        // control: fuzzy skin there but switched off, ironing is the surface technique
        Coordination.chooseSurfaceTechniques(regions, listOf(Coordination.SurfaceTechniqueOffer(SurfaceTechnique.IRONING, top, true, "t"), Coordination.SurfaceTechniqueOffer(SurfaceTechnique.FUZZY_SKIN, top, false, "t")), everyFace)
            .single { it.region === top }.surfaceTechnique shouldBe SurfaceTechnique.IRONING
    }

    "a surface technique refuses a surface it cannot serve: ironing offered a sloped top, fuzzy skin a flat top; control: each on what it serves, chosen" {
        val regions = Coordination.handOver(listOf(flat))
        fun r(x: Coordination.Facing) = regions.first { it.facing == x }
        shouldThrow<IllegalArgumentException> { Coordination.chooseSurfaceTechniques(regions, listOf(Coordination.SurfaceTechniqueOffer(SurfaceTechnique.IRONING, r(Coordination.Facing.SLOPED_TOP), true, "t"))) }
            .message!! shouldContain "ironing cannot serve sloped tops"
        shouldThrow<IllegalArgumentException> { Coordination.chooseSurfaceTechniques(regions, listOf(Coordination.SurfaceTechniqueOffer(SurfaceTechnique.FUZZY_SKIN, r(Coordination.Facing.FLAT_TOP), true, "t"))) }
            .message!! shouldContain "it serves walls"
        Coordination.chooseSurfaceTechniques(regions, listOf(Coordination.SurfaceTechniqueOffer(SurfaceTechnique.IRONING, r(Coordination.Facing.FLAT_TOP), true, "t")))
            .single { it.region === r(Coordination.Facing.FLAT_TOP) }.surfaceTechnique shouldBe SurfaceTechnique.IRONING
        Coordination.chooseSurfaceTechniques(regions, listOf(Coordination.SurfaceTechniqueOffer(SurfaceTechnique.FUZZY_SKIN, r(Coordination.Facing.WALL), true, "t")))
            .single { it.region === r(Coordination.Facing.WALL) }.surfaceTechnique shouldBe SurfaceTechnique.FUZZY_SKIN
        // the technique layer offers each surface technique only what it serves: ironing is not among those that can serve a sloped top
        SurfaceTechniques.choose(listOf(flat), switches(SurfaceTechnique.IRONING to "top")).regions.single { it.region.facing == Coordination.Facing.SLOPED_TOP }
            .canServe.map { it.surfaceTechnique } shouldBe listOf(SurfaceTechnique.Z_ANTIALIASING)
    }

    "ironing with Z anti-aliasing: ironing the flat tops, Z anti-aliasing the sloped tops, no conflict; ironing's passes on the sloped tops' treads said as laid beyond the surface techniques; control: Z anti-aliasing alone, nothing beyond" {
        val both = SurfaceTechniques.choose(listOf(flat), switches(SurfaceTechnique.IRONING to "top", SurfaceTechnique.Z_ANTIALIASING to "true"))
        surfaceTechniqueOf(both, Coordination.Facing.FLAT_TOP) shouldBe SurfaceTechnique.IRONING
        surfaceTechniqueOf(both, Coordination.Facing.SLOPED_TOP) shouldBe SurfaceTechnique.Z_ANTIALIASING
        both.fate(SurfaceTechnique.IRONING).first shouldBe TechniqueExplain.Fate.CHOSEN
        both.fate(SurfaceTechnique.Z_ANTIALIASING).first shouldBe TechniqueExplain.Fate.CHOSEN
        val sloped = both.regions.single { it.region.facing == Coordination.Facing.SLOPED_TOP }
        sloped.alsoLaid!! shouldContain "ironing's lines on the treads"
        sloped.alsoLaid shouldContain "lowered onto the slope by Z anti-aliasing"
        both.regions.count { it.alsoLaid != null } shouldBe 1
        // ironing alone: the sloped tops keep no surface technique, ironing's tread passes said all the same
        val iron = SurfaceTechniques.choose(listOf(flat), switches(SurfaceTechnique.IRONING to "top"))
        surfaceTechniqueOf(iron, Coordination.Facing.SLOPED_TOP) shouldBe null
        iron.regions.single { it.region.facing == Coordination.Facing.SLOPED_TOP }.alsoLaid!! shouldNotContain "lowered"
        // control: Z anti-aliasing alone lays nothing beyond its surface technique
        SurfaceTechniques.choose(listOf(flat), switches(SurfaceTechnique.Z_ANTIALIASING to "true")).regions.all { it.alsoLaid == null } shouldBe true
    }

    "the seam / surface technique contract: no scarf on a fuzzed wall (the outside loops' seams to the placement, the holes' kept); control: without fuzzy skin, scarf takes the outside loops" {
        val outside = SurfaceTechniques.choose(listOf(flat), switches(SurfaceTechnique.FUZZY_SKIN to "external", scarf = "smooth"))
        seam(outside, Coordination.Loops.OUTER_OUTSIDE).owners shouldBe listOf(SeamStyle.CORNERS to "every loop")
        seam(outside, Coordination.Loops.OUTER_OUTSIDE).why shouldContain "no scarf on a fuzzed wall"
        seam(outside, Coordination.Loops.OUTER_HOLES).owners shouldBe listOf(SeamStyle.SCARF to "loops with no corner", SeamStyle.CORNERS to "loops with a corner")
        seam(outside, Coordination.Loops.INNER).owners shouldBe listOf(SeamStyle.CORNERS to "every loop")
        outside.fate(SeamStyle.SCARF).first shouldBe TechniqueExplain.Fate.CHOSEN
        // fuzzy skin on every wall: scarf keeps nothing
        val every = SurfaceTechniques.choose(listOf(flat), switches(SurfaceTechnique.FUZZY_SKIN to "allwalls", scarf = "all"))
        every.seams.none { c -> c.owners.any { it.first == SeamStyle.SCARF } } shouldBe true
        every.fate(SeamStyle.SCARF).first shouldBe TechniqueExplain.Fate.REJECTED
        // a loop Z anti-aliasing lowers is one plain run
        seam(SurfaceTechniques.choose(listOf(flat), switches(SurfaceTechnique.Z_ANTIALIASING to "true", scarf = "all")), Coordination.Loops.OUTER_OUTSIDE).owners.map { it.first } shouldBe
            listOf(SeamStyle.SCARF, SeamStyle.CORNERS)
        // control: no fuzzy skin, scarf seams take the outside loops too; the placement locks the plain style
        val plain = SurfaceTechniques.choose(listOf(flat), switches(scarf = "all", placement = "back"))
        seam(plain, Coordination.Loops.OUTER_OUTSIDE).owners shouldBe listOf(SeamStyle.SCARF to "every loop")
        seam(plain, Coordination.Loops.INNER).owners shouldBe listOf(SeamStyle.BACK to "every loop")
        plain.fate(SeamStyle.CORNERS).first shouldBe TechniqueExplain.Fate.REJECTED
        // smooth with back seams: the scarf takes the loops whose seam lands off a corner, not "the loops with no corner"
        // (the Planner scarfs a loop by where its seam lands; at the back-most point that is most loops); the corner-seam
        // case above is the control
        seam(SurfaceTechniques.choose(listOf(flat), switches(scarf = "smooth", placement = "back")), Coordination.Loops.OUTER_OUTSIDE).owners shouldBe
            listOf(SeamStyle.SCARF to "loops whose seam lands off a corner", SeamStyle.BACK to "loops whose seam lands on a corner")
    }

    "a surface technique's contract with the volume under it: Z anti-aliasing only on flat layers (a curved band's sloped tops as laid); control: ironing on both" {
        val below = Coordination.Portion(ZBand(0.0, 6.0), Coordination.Step.DEPOSIT, PrintTechnique.FLAT_LAYERS, null, null)
        val curved = Coordination.Portion(ZBand(6.0, Double.POSITIVE_INFINITY), Coordination.Step.DEPOSIT, PrintTechnique.CURVED_LAYERS, null, null)
        val c = SurfaceTechniques.choose(listOf(below, curved), switches(SurfaceTechnique.Z_ANTIALIASING to "true", SurfaceTechnique.IRONING to "top"))
        surfaceTechniqueOf(c, Coordination.Facing.SLOPED_TOP, within = below) shouldBe SurfaceTechnique.Z_ANTIALIASING
        surfaceTechniqueOf(c, Coordination.Facing.SLOPED_TOP, within = curved) shouldBe null
        c.regions.single { it.region.facing == Coordination.Facing.SLOPED_TOP && it.region.within === curved }.canServe shouldBe emptyList()
        // control: ironing's contract takes both portions' flat tops
        surfaceTechniqueOf(c, Coordination.Facing.FLAT_TOP, within = below) shouldBe SurfaceTechnique.IRONING
        surfaceTechniqueOf(c, Coordination.Facing.FLAT_TOP, within = curved) shouldBe SurfaceTechnique.IRONING
        // a whole object in layers following the underside gives Z anti-aliasing nothing: not proposed, and said
        val under = Coordination.Portion(Coordination.Volume.WHOLE, Coordination.Step.DEPOSIT, PrintTechnique.CURVED_UNDERSIDE, null, null)
        val u = SurfaceTechniques.choose(listOf(under), switches(SurfaceTechnique.Z_ANTIALIASING to "true"))
        u.regions.none { r -> r.canServe.any { it.surfaceTechnique == SurfaceTechnique.Z_ANTIALIASING } } shouldBe true
        u.notProposed.single { it.startsWith("z_antialiasing") } shouldContain "switched on"
    }
})
