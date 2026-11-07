package plymeld.core.decide

import plymeld.model.Context
import plymeld.model.Resolver
import plymeld.model.Setup

/**
 * The dimension of what a technique DECIDES, not of the plastic it lays:
 * all plastic has three dimensions (a scarf seam's ramp, ironing's thin pass, fuzzy skin's displaced wall). Every bit of
 * plastic is OWNED by exactly one volume technique; surface techniques and curve techniques shape it through their contracts. One
 * technique per portion applies per kind: a volume has one volume technique, an exposed surface one surface technique, a loop's seam
 * one seam style.
 */
enum class TechniqueKind(val id: String, val decides: String) {
    /** Flat layers, curved layers, layers following the underside, the lifted bridge; support later. */
    VOLUME("volume", "a volume: it owns the plastic that fills it and lays it"),
    /** The surface techniques ([SurfaceTechnique]): ironing, Z anti-aliasing, fuzzy skin. */
    SURFACE("surface", "an exposed surface's character, shaping the plastic the volume technique under it owns"),
    /**
     * A curve: a loop's seam ([SeamStyle]: the placements and scarf seams), and later free-air strands. Strands (not built)
     * will need their own exception to "every bit of plastic is owned by a volume technique": their curve is the whole
     * object, with no volume technique under it.
     */
    CURVE("curve", "a curve: where and how a loop's ends meet (its seam); later a strand through the air"),
}

/**
 * The surface techniques: a surface technique is the character of an exposed surface. Each exposed surface region
 * gets exactly one ("none": as laid), so the surface techniques compete for it and are mutually exclusive by definition. Each says
 * which surfaces it can serve, by what they face ([serves]); a combination worth having would be a named surface technique of its own,
 * built deliberately (none exists). None is a pass of its own in time: each is laid with the volume under it, and its
 * handoff contract with that volume is [needs] / [on]. A job's lock locks a surface technique; unlocked, the search weighs the ones
 * with a predicted outcome ([outcomes]) against as laid.
 */
enum class SurfaceTechnique(val short: String, val label: String, val character: String, val serves: Set<Coordination.Facing>, val cuts: String, val needs: String) {
    /** Planner: each layer's exposed top (this layer minus the next, walls included), not on the first layer. */
    IRONING("ironing", "ironing: the nozzle passed over a top surface again, laying almost no plastic", "smooth", setOf(Coordination.Facing.FLAT_TOP),
        "anywhere: any part of the flat tops", "a laid top surface: the exposed top of what the volume technique laid, above the first layer"),
    /** ZAntiAlias: flat layers only; the first layer never; a curved band's and a lifted bridge's own layers left alone. */
    Z_ANTIALIASING("z_antialiasing", "Z anti-aliasing: the lines under a sloped top lowered onto it, with less plastic", "stair-steps removed",
        setOf(Coordination.Facing.SLOPED_TOP), "anywhere: any part of the sloped tops",
        "flat layers' lines, above the first layer (a curved band's and a lifted bridge's own layers follow their own surface and are left alone), and the tool's thinnest layer (hardware.tool.<key>.layer.height.min)"),
    /** Planner: by plan.technique.fuzzy_skin.where; FlatLayers.followBlocked: not with layers following the surfaces. */
    FUZZY_SKIN("fuzzy_skin", "fuzzy skin: walls jittered in and out into a texture", "rough", setOf(Coordination.Facing.WALL),
        "by wall loop", "walls laid at one layer height: its texture depends on it, so flat layers following the surfaces are refused with it; the first layer only with plan.technique.fuzzy_skin.first_layer"),
    /** Planner / WaveFronts: the bottom over air of each flat layer above the first, grown from the material under the layer beside it. */
    WAVE_OVERHANG("wave_overhang", "wave overhangs: an underside over air grown outward from the supported material beside it, bead by bead, each leaning on the last", "laterally supported",
        setOf(Coordination.Facing.UNDERSIDE), "by overhang region: what hangs off one side (a span between supports stays a bridge, an area over support is the support's); what no front reaches from an anchor is bridged",
        "flat layers' undersides over air above the first layer, touching supported material (an island with no anchor is bridged as before; a curved band's and a lifted bridge's own layers are left alone)");

    val kind: TechniqueKind get() = TechniqueKind.SURFACE

    /**
     * What the search can predict of this surface technique's outcomes, so it can be weighed against as laid; null =
     * none predicted yet, so the surface technique is chosen only when locked. Ironing: its pass
     * over the flat tops costs time (the area at the ironing lines' spacing and speed, both the technique's own settings,
     * plan.technique.ironing.spacing and .speed) and makes the rough flat-top area 0 (Metric.ROUGH_TOP_AREA, measured only
     * where the surface technique is weighed); its plastic (a share of a line at the ironing flow) is not measured by the quick
     * estimates, which measure no plastic of the object's own; the finalists' full prediction counts it.
     */
    val outcomes: String? get() = when (this) {
        IRONING -> "time: the flat tops' area at the ironing lines' spacing and speed (plan.technique.ironing.spacing, .speed); quality: the rough flat-top area becomes 0 (rough_top_area)"
        else -> null
    }

    /**
     * Its handoff contract with the volume portion [p] under it: the words for what of [p] gives it what it needs, or null
     * when [p] gives it nothing (then it serves nothing there).
     */
    fun on(p: Coordination.Portion): String? = when {
        p.technique.kind != TechniqueKind.VOLUME -> null
        this == WAVE_OVERHANG -> if (p.technique == PrintTechnique.FLAT_LAYERS) "all of it" else null
        this != Z_ANTIALIASING -> "all of it"
        p.technique == PrintTechnique.FLAT_LAYERS -> "all of it"
        // IncrementalSlicer: lowered only at or below a lifted bridge's start (its own layers follow their own surface)
        p.technique == PrintTechnique.LIFTED_BRIDGE -> "below where the lifted bridge starts only"
        else -> null
    }
}

/**
 * The seam styles: a loop's seam, where its ends meet, is a curve, and the seam styles compete
 * for it: today's placements ([CORNERS], [BACK]: a plain seam, the ends butted where the loop starts) and [SCARF] seams.
 * Scarf seams have a contract on the surface technique of the wall the seam crosses ([SurfaceTechniques.seams]). Locked by the job's lock
 * (`plan.seam`); unlocked, the seam decision ([SeamDecision]) weighs them by the job's weights (seam_scar against the
 * scarf's extra pass). A placement's [short] is plan.seam's value.
 */
enum class SeamStyle(val short: String, val label: String, val needs: String, val start: String?) {
    /** Planner: plan.seam = "corners". */
    CORNERS("corners", "a plain seam at a corner when the loop has one (inside corners first), each layer's nearest the one below",
        "any wall loop; never on an edge hanging over the layer below when the loop has another place", "corners"),
    /** Planner: plan.seam = "back". */
    BACK("back", "a plain seam at the loop's back-most point", "any wall loop; never on an edge hanging over the layer below when the loop has another place", "back"),
    /** Planner: plan.seam = "least_visible". */
    LEAST_VISIBLE("least_visible", "a plain seam at the held-up point the visibility map counts least (turned away from plan.view_from's viewer, an underside)",
        "any wall loop; never on an edge hanging over the layer below when the loop has another place", "least_visible"),
    /** Planner: outer walls above the first layer, not a fuzzed loop; ZAntiAlias: not a loop it lowers. Placed where plan.seam starts the loop. */
    SCARF("scarf_seam", "a scarf seam: an outer wall loop started thin and low, ended re-printing that stretch thinning to nothing",
        "an outer wall loop above the first layer, on a flat top (its two wedge passes assume one), on a wall with no fuzzy skin (it hides the seam already), " +
            "not lowered by Z anti-aliasing; placed where plan.seam starts the loop", null);

    val kind: TechniqueKind get() = TechniqueKind.CURVE
}

/**
 * The surface techniques' and seam styles' proposals and choices (a lock locks, an unlocked surface technique
 * with a predicted outcome is weighed by the search against as laid, [SurfaceTechnique.outcomes]).
 */
object SurfaceTechniques {
    /** Ironing's pass over [flatTopMm2] of flat tops (s): lines [spacingMm] apart at [speedMmS] (plan.technique.ironing.spacing, .speed). */
    fun ironingTimeS(flatTopMm2: Double, spacingMm: Double, speedMmS: Double): Double {
        require(spacingMm > 0 && speedMmS > 0) { "ironing's spacing ($spacingMm mm) and speed ($speedMmS mm/s) must be positive" }
        return flatTopMm2 / spacingMm / speedMmS
    }

    /**
     * One surface technique's or style's switch for an object: [value] (the setting's), [on], and where it comes from: a lock
     * ([weighed] false: locked) or the search's choice by weight ([weighed] true, [source] saying what the weights decided).
     */
    class Lock(val value: String, val on: Boolean, val source: String, val weighed: Boolean = false) {
        /** The source with how it binds: locked on / off by a lock, or weighed. */
        val words: String get() = (if (weighed) "weighed (no lock): " else if (on) "locked on: " else "locked off: ") + source
    }

    /** Today's switches for one object: each surface technique's, the seam placement's (plan.seam), and scarf seams'. */
    class Switches(val surfaceTechniques: Map<SurfaceTechnique, Lock>, val placement: Lock, val scarf: Lock)

    /** The lock that locks each surface technique (its config key), and the setting it is made into. */
    private val SWITCH = mapOf(
        SurfaceTechnique.IRONING to ("plan.faces.flat_top.technique" to "plan.technique.ironing.type"),
        SurfaceTechnique.Z_ANTIALIASING to ("plan.faces.sloped_top.technique" to "plan.faces.sloped_top.z_antialiasing"),
        SurfaceTechnique.FUZZY_SKIN to ("plan.faces.side.technique" to "plan.technique.fuzzy_skin.where"),
        SurfaceTechnique.WAVE_OVERHANG to ("plan.faces.under.technique" to "plan.faces.under.wave_overhang"))
    private const val SEAM = "plan.technique.scarf_seam.enabled"
    private const val SCARF_ID = "plan.technique.scarf_seam.where"
    private const val PLACEMENT = "plan.seam"
    private const val PLACEMENT_ID = "plan.seam"

    /**
     * One lock of object [k] read as the slice reads it (the object's settings, and each part's where a part sets its
     * own): on when [isOn] for any part, the parts named where they differ. [lock]: the technique the key locks when on
     * (null: [key] is a choice, named with the setting's value).
     */
    private fun lock(setup: Setup, k: Int, key: String, id: String, lock: String?, isOn: (Any?, Context) -> Boolean): Lock {
        val parts = setup.objects[k].parts
        fun read(ctx: Context): Pair<Any?, String?> = Resolver.resolveOrNull(setup, id, ctx)?.let { it.value to it.from } ?: (null to null)
        fun on(ctx: Context) = isOn(read(ctx).first, ctx)
        val (v, from) = read(Context(k))
        val each = parts.indices.map { p -> parts[p].name to on(Context(k, p)) }
        val any = on(Context(k)) || each.any { it.second }
        val differ = each.isNotEmpty() && each.map { it.second }.distinct().size > 1
        // a lock is named with the technique it locks and the setting it is made into; a choice with its value
        val head = if (lock == null) "$key: $id = ${v ?: "unset"} (${from ?: "no value"})"
            else "$key ${if (any) "= \"$lock\"" else "not \"$lock\""} ($id = ${v ?: "unset"}, ${from ?: "no value"})"
        return Lock(v?.toString() ?: "unset", any, head +
            (if (differ) "; on for parts " + each.filter { it.second }.joinToString { "'${it.first}'" } + " only" else ""))
    }

    /**
     * A surface technique the search weighed: [on] = the weights chose it over as laid; [why] what decided
     * (the technique's words: the weights' exchange rate on its outcomes, or that no outcome is predicted for it).
     */
    class Weighed(val on: Boolean, val why: String)

    /**
     * Object [k]'s locks, and for each surface technique in [weighed] (no lock for its surfaces: the search decided) what the weights
     * chose. Scarf seams also need plan.technique.scarf_seam.start (the Planner lays none without it).
     */
    fun switches(setup: Setup, k: Int, weighed: Map<SurfaceTechnique, Weighed> = emptyMap()): Switches {
        val surfaceTechniques = SWITCH.mapValues { (t, ks) ->
            weighed[t]?.let { w -> Lock(if (w.on) t.short else "as_laid", w.on, "${ks.first} unset; ${w.why}", weighed = true) }
                ?: lock(setup, k, ks.first, ks.second, t.short) { v, _ -> if (t == SurfaceTechnique.Z_ANTIALIASING || t == SurfaceTechnique.WAVE_OVERHANG) v == true else v != null && v != "none" }
        }
        val scarf = lock(setup, k, SEAM, SCARF_ID, SeamStyle.SCARF.short) { v, ctx -> v != null && v != "none" && Resolver.resolveOrNull(setup, "plan.technique.scarf_seam.start", ctx) != null }
        val placement = lock(setup, k, PLACEMENT, PLACEMENT_ID, null) { v, _ -> v != null }
        return Switches(surfaceTechniques, placement, scarf)
    }

    /** The wall sides fuzzy skin takes, from plan.technique.fuzzy_skin.where (Planner: external = each outline's outside; all and allwalls the holes too). */
    private fun fuzzySides(value: String): Set<Coordination.Side> =
        if (value == "external") setOf(Coordination.Side.OUTSIDE) else setOf(Coordination.Side.OUTSIDE, Coordination.Side.HOLES)

    /**
     * Each surface technique's offers over the surface [regions] the volume [portions] hand over: the regions it can serve (what they
     * face, [SurfaceTechnique.serves]) within the portions that give it what it needs ([SurfaceTechnique.on]) and, for fuzzy skin, on the
     * sides `plan.technique.fuzzy_skin.where` names; locked on or off by [sw]. A surface technique no portion serves offers nothing, and that
     * is said.
     */
    fun offers(portions: List<Coordination.Portion>, regions: List<Coordination.SurfaceRegion>, sw: Switches): Pair<List<Coordination.SurfaceTechniqueOffer>, List<String>> {
        val out = ArrayList<Coordination.SurfaceTechniqueOffer>(); val none = ArrayList<String>()
        for (f in SurfaceTechnique.entries) {
            val lock = sw.surfaceTechniques.getValue(f)
            if (portions.none { f.on(it) != null }) {
                none += "${f.short}: no chosen portion gives it what it needs (${f.needs})" + if (lock.on) "; switched on: ${lock.words}" else ""; continue
            }
            for (r in regions) {
                if (r.facing !in f.serves) continue
                val met = f.on(r.within) ?: continue
                // fuzzy skin can serve every wall; switched on, it is on only for the sides its where names
                val off = f == SurfaceTechnique.FUZZY_SKIN && lock.on && r.side !in fuzzySides(lock.value)
                out += Coordination.SurfaceTechniqueOffer(f, r, lock.on && !off,
                    if (off) "locked off: plan.technique.fuzzy_skin.where = ${lock.value}: not these walls (${lock.source})" else lock.words, met.takeIf { it != "all of it" })
            }
        }
        return out to none
    }

    /**
     * What today's toolpaths lay on [r] beyond its one surface technique (kept so the print is unchanged; the user decides): ironing's
     * pass works each layer's exposed top (Planner), so on a sloped top of flat layers it also irons the treads of the
     * stair-steps, and Z anti-aliasing, where it is the surface technique, lowers those lines onto the slope (ZAntiAlias: ironing is one
     * of the features it lowers). Checked on a wedge (a flat top and an 11 degree slope): ironing 892 mm on the
     * flat top and about 550 mm on the slope's treads; with Z anti-aliasing too, 392 mm of those lowered.
     */
    private fun alsoLaid(r: Coordination.SurfaceRegion, surfaceTechnique: SurfaceTechnique?, sw: Switches): String? {
        if (r.facing != Coordination.Facing.SLOPED_TOP || !sw.surfaceTechniques.getValue(SurfaceTechnique.IRONING).on || SurfaceTechnique.IRONING.on(r.within) == null) return null
        return "ironing's lines on the treads of the stair-steps (the Planner irons each layer's exposed top)" +
            (if (surfaceTechnique == SurfaceTechnique.Z_ANTIALIASING) ", lowered onto the slope by Z anti-aliasing" else "") +
            "; the surface techniques give sloped tops to Z anti-aliasing alone, and confining ironing to flat tops would change the print: the user's decision"
    }

    /**
     * The seam choice over the volume [portions]: each loop's seam goes to the placement [sw] locks (a plain
     * seam), except where scarf seams are switched on: they take the outer wall loops their where names, minus those whose
     * wall's surface technique is fuzzy skin (it hides the seam already: no scarf on a fuzzed wall, the Planner's rule) and those Z
     * anti-aliasing lowers (a loop lowered onto a sloped top is printed as one plain run: ZAntiAlias's rule).
     */
    fun seams(portions: List<Coordination.Portion>, sw: Switches, surfaceTechniques: List<Coordination.RegionChoice>): List<Coordination.SeamChoice> {
        val plain = SeamStyle.entries.firstOrNull { it.start != null && it.start == sw.placement.value }
        // smooth = the seam lands off a corner (the Planner's rule): with plan.seam = corners that is the loops
        // with no corner; with another placement (back-most point) most loops
        val cornerPlain = plain == SeamStyle.CORNERS
        val scarfLoops = when (sw.scarf.value) {
            "smooth" -> if (cornerPlain) "loops with no corner" else "loops whose seam lands off a corner"
            "all" -> "every loop"; else -> null }
        val cornerLoops = if (cornerPlain) "loops with a corner" else "loops whose seam lands on a corner"
        fun surfaceTechniqueOf(p: Coordination.Portion, facing: Coordination.Facing, side: Coordination.Side?) =
            surfaceTechniques.firstOrNull { it.region.within === p && it.region.facing == facing && it.region.side == side }?.surfaceTechnique
        return Coordination.seamCurves(portions).map { c ->
            val canTake = if (c.loops.outer) SeamStyle.entries.toList() else SeamStyle.entries.filter { it != SeamStyle.SCARF }
            val rest = plain?.let { listOf(it) } ?: emptyList()
            val placed = plain?.let { "locked: ${sw.placement.source}" } ?: "no placement set (${sw.placement.source})"
            if (!c.loops.outer || !sw.scarf.on || scarfLoops == null) {
                val why = if (c.loops.outer) "$placed; scarf seams locked off (${sw.scarf.source})" else "$placed; scarf seams are for outer wall loops only"
                return@map Coordination.SeamChoice(c, canTake, rest.map { it to "every loop" }, why)
            }
            if (surfaceTechniqueOf(c.within, Coordination.Facing.WALL, c.loops.side) == SurfaceTechnique.FUZZY_SKIN)
                return@map Coordination.SeamChoice(c, canTake, rest.map { it to "every loop" },
                    "$placed; scarf seams switched on (${sw.scarf.source}) but this wall's surface technique is fuzzy skin: no scarf on a fuzzed wall, it hides the seam already (the Planner's rule)")
            val lowered = surfaceTechniqueOf(c.within, Coordination.Facing.SLOPED_TOP, null) == SurfaceTechnique.Z_ANTIALIASING
            val scarfWords = scarfLoops + if (lowered) ", not lowered by Z anti-aliasing" else ""
            val restWords = if (scarfLoops == "every loop" && !lowered) null
                else listOfNotNull(if (scarfLoops != "every loop") cornerLoops else null,
                    if (lowered) "loops Z anti-aliasing lowers onto a sloped top (printed as one plain run, its scarf left out: ZAntiAlias's rule)" else null).joinToString(", and ")
            Coordination.SeamChoice(c, canTake, listOf(SeamStyle.SCARF to scarfWords) + (if (restWords == null) emptyList() else rest.map { it to restWords }),
                "scarf seams locked on: ${sw.scarf.source}; the rest $placed")
        }
    }

    /** The surface techniques and seams for an object whose volume [portions] are chosen, with its switches [sw]. */
    fun choose(portions: List<Coordination.Portion>, sw: Switches): Coordination.SurfaceTechniqueChoice {
        val handed = Coordination.handOver(portions)
        val (offers, none) = offers(portions, handed, sw)
        val regions = Coordination.chooseSurfaceTechniques(handed, offers)
        val withAlso = regions.map { it.with(alsoLaid(it.region, it.surfaceTechnique, sw)) }
        val locks = SurfaceTechnique.entries.associateWith { sw.surfaceTechniques.getValue(it) }
        val styles = listOf(SeamStyle.CORNERS, SeamStyle.BACK, SeamStyle.LEAST_VISIBLE).associateWith { s ->
            Lock(sw.placement.value, sw.placement.value == s.start, sw.placement.source) } + (SeamStyle.SCARF to sw.scarf)
        return Coordination.SurfaceTechniqueChoice(withAlso, seams(portions, sw, withAlso), locks.mapValues { it.value.on to it.value.words },
            styles.mapValues { it.value.on to it.value.words }, none)
    }
}
