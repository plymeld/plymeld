package plymeld.core.decide

import plymeld.machine.MachineDescription
import plymeld.machine.Toolhead
import plymeld.mesh.TriangleSoup
import java.util.Locale

/**
 * How ONE object is built — its technique, and the head-geometry tiers each technique needs. These are the volume
 * techniques (and the one curve technique of this kind, free strands, not built); the surface techniques of the
 * surfaces they lay are [SurfaceTechnique], and the seam styles of their loops [SeamStyle]. Whatever the technique, an
 * object's layers are in ITS print order, and a plate printed together merges the objects' layers by height without
 * reordering any object's (Job).
 */
enum class PrintTechnique(val label: String, val implemented: Boolean, val needs: List<Toolhead.Tier>, val short: String) {
    /**
     * Flat layers: uniform and adaptive layers merged into one technique.
     * Its control is the layer height, a range from the tool's thinnest to its thickest layer ([FlatLayers]): a portion of
     * ONE height (what uniform layers were), or one whose every layer is as thick as the surfaces it crosses allow (what
     * adaptive layers were: it needs the tool's thinnest and thickest layer, [FlatLayers.followBlocked]). Which is
     * which is per band ([TechniquePlan.Banded.follow]); the survey (FlatLayers.survey) finds the portions.
     */
    FLAT_LAYERS("flat layers: everything below a height before anything above it", true, emptyList(), "flat"),
    /**
     * A curved top band whose layers follow the model's top. Needs the tip cone only: the band's whole rise stays under
     * the tip's protrusion, so the head around the tip never meets it, whatever its outline. Locked only, no survey yet.
     */
    CURVED_LAYERS("curved layers: the top band's layers follow the model's top, within the nozzle tip's cone", true, listOf(Toolhead.Tier.TIP_CONE), "curved"),
    /**
     * Layers that follow the model's underside (for a thin arch over a hollow, following both curves): every layer a
     * constant thickness parallel to the underside, the first over a hollow printed in the air as a bridge. The whole
     * object, one band; locked only. Needs the tip cone (each layer's rise under the tip's protrusion, as curved
     * layers).
     */
    CURVED_UNDERSIDE("layers that follow the model's underside: each a constant thickness, the first over a hollow printed in the air as a bridge", true,
        listOf(Toolhead.Tier.TIP_CONE), "underside"),
    /**
     * Bridges that change height: the parts that do not reach the bed
     * are the bridge, printed after everything that holds them up, their layers laid along their underside (a straight
     * rise between anchors at two heights, a dip onto a post). The whole object, one band; locked only. Needs the tip cone
     * (the head passes over the supports while the nozzle is lower, down the bridge).
     */
    LIFTED_BRIDGE("a bridge printed after what holds it up, its layers following its underside between anchors at different heights", true,
        listOf(Toolhead.Tier.TIP_CONE), "bridge"),
    /**
     * Support ([Support]): tree support (the one style) under
     * an overhang region too steep to hold itself up, over the air outside the model. It never lays the model: it
     * competes through the overhang's entry need with whatever lays the overhang over air, so it is never a plan's own
     * technique (a band), only a side-by-side portion of one ([SupportPlan]).
     */
    SUPPORT("tree support under an overhang, outside the model, standing on the bed or the model, printed before what it holds up", true,
        emptyList(), "support"),
    /**
     * A curve technique: declared, not built (it comes when strands are built). Its curve is
     * the whole object, with no volume technique under it: it will need its own exception to "every bit of plastic is
     * owned by a volume technique".
     */
    FREE_STRANDS("free-standing strands that hold themselves up while cooling", false, listOf(Toolhead.Tier.TIP_CONE, Toolhead.Tier.UNDERSIDE), "strands");

    /** What it decides: a volume, except free-standing strands, a curve through space. */
    val kind: TechniqueKind get() = if (this == FREE_STRANDS) TechniqueKind.CURVE else TechniqueKind.VOLUME
}

/** How the objects on a plate are ordered relative to each other (separate from each object's technique). */
enum class ObjectOrder(val label: String, val needs: List<Toolhead.Tier>) {
    /** Each object's own layers, in its own order, merged by height (Job). */
    TOGETHER("all objects together, by height", emptyList()),
    /** Also the head's outline (UNDERSIDE) or, without it, its clearance circle (CLEARANCE): [ObjectOrderDecision]. */
    ONE_AT_A_TIME("one object finished before the next", listOf(Toolhead.Tier.GANTRY)),
}

/**
 * The plate's plan: the object order, each object's techniques (by height band), and where each object sits (in
 * print order). [objectIndex]: for each placed object, its index among the setup's objects (whose
 * settings it prints with) — print order need not be the setup's order.
 */
class Layout(val order: ObjectOrder, val techniques: List<TechniquePlan>, val placed: List<Placed>, val objectIndex: List<Int> = placed.indices.toList()) {
    init { require(objectIndex.size == placed.size && objectIndex.toSet().size == placed.size) { "each placed object needs its own setup index" } }
}

/** Facts the object-order step reads beyond the machine description. */
class JobFacts(
    val bed: PoseFacts,
    /** Minimum gap between objects printed together, and why. */
    val togetherGapMm: Double, val togetherGapWhy: String,
    val layerHeightMm: Double,
    val zHopMm: Double,
    /** How far each object's first layer reaches beyond its outline (brim width + gap; 0 without brim). */
    val brimReachMm: Double = 0.0,
)

private fun tiersFact(i: Int, h: Toolhead?) = "toolhead ${i + 1}: " + if (h == null) "no geometry described" else
    "geometry tiers known: ${Toolhead.Tier.entries.filter(h::has).joinToString { it.name.lowercase() }.ifEmpty { "none" }} (${h.evidence.firstOrNull()?.kind ?: "no evidence"})"

private fun missing(needs: List<Toolhead.Tier>, heads: List<Pair<Int, Toolhead?>>): String? {
    val m = heads.flatMap { (i, h) -> needs.filter { h == null || !h.has(it) }.map { "${it.settings} for toolhead ${i + 1}" } }
    return if (m.isEmpty()) null else "blocked: no ${m.joinToString()}"
}

/**
 * Step 3a — each object's technique. Every technique is chosen, BLOCKED on a named missing
 * fact, or not implemented — never skipped. With one usable technique there is nothing to weigh yet.
 */
object TechniqueDecision {
    /**
     * What each technique needs, checked: head geometry and being implemented. Null =
     * usable. What flat layers need to follow the surfaces is checked by the decision itself (FlatLayers.followBlocked).
     */
    fun screen(machine: MachineDescription, tool: Int): List<Pair<PrintTechnique, String?>> = screen(machine, listOf(tool))

    /** Each technique against every head that prints the object [tools] (the object's first part's alone was screened). */
    fun screen(machine: MachineDescription, tools: Collection<Int>): List<Pair<PrintTechnique, String?>> {
        val heads = tools.distinct().sorted().map { it to machine.toolhead(it) }
        return PrintTechnique.entries.map { s ->
            s to (missing(s.needs, heads) ?: if (!s.implemented) "not implemented yet" else null)
        }
    }

    fun decide(name: String, machine: MachineDescription, tool: Int, override: PrintTechnique? = null): Decision<PrintTechnique> {
        val facts = listOf("3-axis machine, nozzle pointing down", tiersFact(tool, machine.toolhead(tool)))
        val screened = screen(machine, tool).toMap()
        fun rejected(s: PrintTechnique) = screened.getValue(s)
        if (override != null) {
            rejected(override)?.let { error("technique '${override.label}' cannot be used for '$name': $it") }
            return Decision("technique ($name)", override, override.label, facts, overridden = true)
        }
        val usable = PrintTechnique.entries.filter { rejected(it) == null }
        val chosen = usable.first()
        return Decision("technique ($name)", chosen, "${chosen.label}${if (usable.size == 1) " (the only usable technique)" else ""}", facts,
            PrintTechnique.entries.map { Alternative(it.label, if (it.needs.isEmpty()) "needs no head geometry" else "needs " + it.needs.joinToString { t -> t.name.lowercase() }, rejected(it), chosen = it == chosen) },
            listOf("one technique for the whole object: regions of an object, even within one part, may use different techniques, chosen together with the pose; per-region choice not implemented yet"))
    }
}

/**
 * Step 3b — the object order on the plate. TOGETHER merges each object's own layers by height (whatever its techniques)
 * and needs a gap so objects do not fuse (brims may merge). ONE_AT_A_TIME
 * needs for EACH toolhead used its
 * outline seen from below (the UNDERSIDE tier; else its clearance radius, a circle) so the head never reaches a finished
 * object, including while printing the next object's brim, and its nozzle-to-rod height (every object but the last
 * below it; shortest first, or as given with plan.objects.order = as_given). One object qualifies only when the order
 * is locked to one at a time (the trivial case). With the outline, the objects printed first go on the side the head reaches least
 * (Arrange.rows, checked by Arrange.headClear).
 * Usable orders are ranked by the intent's weights.
 */
object ObjectOrderDecision {
    fun decide(machine: MachineDescription, tools: Set<Int>, objects: List<Pair<String, List<TriangleSoup>>>, techniques: List<TechniquePlan>,
               jf: JobFacts, weights: Weights, override: ObjectOrder? = null, keepPlacement: Boolean = false,
               /** One at a time in [objects]' order (plan.objects.order = as_given) instead of shortest first; the head and rod rules still judge it. */
               sequenceAsGiven: Boolean = false,
               /** Step 8 for a candidate layout, when it can be generated; used when two or more orders are usable. */
               predict: ((Layout) -> plymeld.core.Predict.Prediction)? = null,
               /** method.search.zero_scale; travel's scale is otherwise the bed's diagonal. */
               zeroScale: Map<Metric, Double> = emptyMap(),
               /** How predicted filament is weighed per tool (by mass, else volume); null = no filament facts, material not compared. */
               filament: FilamentBasis? = null,
               /**
                * The baseline: each object's flat-layer height (mm, in [objects]' order), the objects printed
                * together, predicted by [predictBaseline] (step 8 with the settings at those heights); [baselineWhat] says
                * where the heights came from. Null: no baseline, and the orders are scored against the best.
                */
               baselineLayers: List<Double>? = null, baselineWhat: String = "no baseline given",
               predictBaseline: ((Layout) -> plymeld.core.Predict.Prediction)? = null): Decision<Layout> {
        require(objects.size == techniques.size)
        val heads = tools.sorted().map { it to machine.toolhead(it) }
        val facts = listOf("${objects.size} object(s): " + objects.indices.joinToString { "${objects[it].first} (${techniques[it].short})" },
            "minimum gap between objects printed together: %.2f mm (%s)".format(Locale.ROOT, jf.togetherGapMm, jf.togetherGapWhy),
            "weights: $weights") + heads.map { (i, h) -> tiersFact(i, h) }
        val layouts = LinkedHashMap<ObjectOrder, Layout>(); val rejected = LinkedHashMap<ObjectOrder, String>()
        // an object with a section left open is laid out by the section's box: what prints of it
        // (its lines end on the box's sides, half a line beyond them: the box is taken half the gap wider each way, so the gap is between beads as it is between whole objects' faces)
        val half = jf.togetherGapMm / 2
        fun sections(ts: List<TechniquePlan>) = ts.map { t -> t.regions.firstOrNull { it.section }?.region?.let { g -> doubleArrayOf(g.x0 - half, g.x1 + half, g.y0 - half, g.y1 + half) } }
        val secs = sections(techniques)
        for (o in ObjectOrder.entries) {
            missing(o.needs, heads)?.let { rejected[o] = it; continue }
            when (o) {
                ObjectOrder.TOGETHER -> {
                    // each object keeps its own techniques and layers; the Job merges them by height in each object's own order
                    val placed = if (keepPlacement) Arrange.asIs(objects, secs) else Arrange.rows(objects, jf.togetherGapMm, jf.bed, sections = secs)
                    val off = placed?.let { Arrange.outsideBed(it, jf.bed) }
                    when {
                        placed == null -> rejected[o] = "objects do not fit on the bed"
                        off != null -> rejected[o] = "placement kept as given, but $off"
                        else -> layouts[o] = Layout(o, techniques, placed)
                    }
                }
                ObjectOrder.ONE_AT_A_TIME -> {
                    // one object locked to one at a time is the trivial case, printed as it is; unlocked,
                    // together stands for it
                    if (objects.size < 2 && override != ObjectOrder.ONE_AT_A_TIME) { rejected[o] = "not applicable: one object"; continue }
                    // the head's outline when every head used has one; else its clearance circle
                    val outline = heads.all { it.second!!.has(Toolhead.Tier.UNDERSIDE) }
                    if (!outline) missing(listOf(Toolhead.Tier.CLEARANCE), heads)?.let { rejected[o] = "$it (or the head's outline: underside)"; continue }
                    val rod = heads.minOf { it.second!!["gantry.height"] }
                    val idx = if (sequenceAsGiven) objects.indices.toList()
                        else objects.indices.sortedBy { PoseDecision.merged(objects[it].second).bounds().let { b -> b[5] - b[2] } }   // shortest first
                    val ordered = idx.map { objects[it] }
                    val osecs = idx.map { secs[it] }
                    val placed: List<Placed>?
                    val why: String
                    if (outline) {
                        val r = Arrange.Reach(heads.maxOf { it.second!!["head_minus_x"] }, heads.maxOf { it.second!!["head_plus_x"] },
                            heads.maxOf { it.second!!["head_minus_y"] }, heads.maxOf { it.second!!["head_plus_y"] })
                        // the objects printed first on the side the head reaches least, so a later one's head stays off them
                        val gapX = maxOf(minOf(r.minusX, r.plusX) + jf.brimReachMm, jf.togetherGapMm)
                        val gapY = maxOf(minOf(r.minusY, r.plusY) + jf.brimReachMm, jf.togetherGapMm)
                        why = "the head's outline reaching %.0f / %.0f / %.0f / %.0f mm towards -X / +X / -Y / +Y + brim reach %.1f".format(Locale.ROOT, r.minusX, r.plusX, r.minusY, r.plusY, jf.brimReachMm)
                        val clash = if (keepPlacement) Arrange.headClear(Arrange.asIs(ordered, osecs), r, jf.brimReachMm) else null
                        placed = if (!keepPlacement) Arrange.rows(ordered, gapX, jf.bed, gapY, fromRight = r.plusX <= r.minusX, fromBack = r.plusY <= r.minusY, sections = osecs)
                            ?.takeIf { Arrange.headClear(it, r, jf.brimReachMm) == null }
                            else Arrange.asIs(ordered, osecs).takeIf { clash == null }
                        if (placed == null && keepPlacement) { rejected[o] = "placement kept as given, but $clash ($why)"; continue }
                    } else {
                        val radius = heads.maxOf { it.second!!["head.radius"] }
                        val spacing = maxOf(radius + jf.brimReachMm, jf.togetherGapMm)
                        why = "head clearance radius %.0f + brim reach %.1f".format(Locale.ROOT, radius, jf.brimReachMm)
                        placed = if (!keepPlacement) Arrange.rows(ordered, spacing, jf.bed, sections = osecs) else Arrange.asIs(ordered, osecs).takeIf { Arrange.minGap(it) >= spacing }
                        if (placed == null && keepPlacement) { rejected[o] = "placement kept as given, but the objects are %.1f mm apart, closer than %.1f mm (%s)".format(Locale.ROOT, Arrange.minGap(Arrange.asIs(ordered, osecs)), spacing, why); continue }
                    }
                    val tooTall = placed?.dropLast(1)?.firstOrNull { it.heightMm >= rod }
                    val off = placed?.let { Arrange.outsideBed(it, jf.bed) }
                    when {
                        off != null -> rejected[o] = "placement kept as given, but $off"
                        placed == null -> rejected[o] = "objects do not fit on the bed spaced for the head ($why)"
                        tooTall != null -> rejected[o] = "'%s' is %.1f mm tall: not lower than the %.0f mm nozzle-to-rod clearance, and only the last object may be".format(Locale.ROOT, tooTall.name, tooTall.heightMm, rod)
                        else -> layouts[o] = Layout(o, idx.map { techniques[it] }, placed, idx)
                    }
                }
            }
        }
        if (override != null) {
            rejected[override]?.let { error("object order '${override.label}' cannot be used: $it") }
            val l = layouts.getValue(override)
            return Decision("object order", l, "${override.label}; ${Arrange.describe(l.placed)}", facts, overridden = true)
        }
        if (layouts.isEmpty()) error("no object order is usable: " + rejected.entries.joinToString { "${it.key.label}: ${it.value}" })
        val usable = layouts.values.toList()
        // predicted time and filament when there is a choice and the machine's limits allow it;
        // otherwise travel between objects stands in for time
        val predictions = if (predict != null && usable.size > 1) usable.associateWith(predict) else emptyMap()
        val timed = predictions.isNotEmpty() && predictions.values.all { it.timeS != null }
        // material by mass, else by volume; not at all when a tool's filament is not known
        val unweighed = if (!timed) null else filament?.let { b -> predictions.values.firstNotNullOfOrNull { b.unknownTools(it.filamentByToolMm) } }
        val material = if (timed && filament?.metric != null && unweighed == null) filament.metric else null
        val metrics: Map<Metric, (Layout) -> Double> = if (timed) linkedMapOf<Metric, (Layout) -> Double>(
            Metric.TIME to { l -> Math.round(predictions.getValue(l).timeS!!).toDouble() }) + (if (material == null) emptyMap() else mapOf(
            material to { l -> filament!!.round(filament.of(predictions.getValue(l).filamentByToolMm)) }))
            else mapOf(Metric.TRAVEL to { l -> Math.round(travelBetweenObjects(l, jf.layerHeightMm)).toDouble() })
        val materialFact = if (!timed) null else when {
            filament == null -> "material not compared: no filament facts given"
            filament.metric == null -> "material not compared: ${filament.why}"
            unweighed != null -> "material not compared: $unweighed"
            else -> "material: ${filament.why}"
        }
        // the baseline, when time is predicted: every object in flat layers at its baseline height, printed
        // together, in the poses chosen for it, placed as printing together places them. Travel alone needs none: one
        // measure ranks the same on any scale.
        var baselineFact: String? = null
        val baseline: Map<Metric, Double>? = if (!timed) null else run {
            fun none(why: String): Map<Metric, Double>? { baselineFact = "baseline: none ($why): scored against the best order instead (at most 1 per measure)"; return null }
            if (baselineLayers == null || predictBaseline == null) return@run none(baselineWhat)
            val placed = layouts[ObjectOrder.TOGETHER]?.placed ?: (if (keepPlacement) Arrange.asIs(objects, secs) else Arrange.rows(objects, jf.togetherGapMm, jf.bed, sections = secs))
                ?: return@run none("the objects do not fit on the bed printed together")
            val p = predictBaseline(Layout(ObjectOrder.TOGETHER, baselineLayers.map { FlatLayers.plan(it) }, placed))
            val t = p.timeS ?: return@run none("its time cannot be predicted: ${p.why}")
            val b = linkedMapOf(Metric.TIME to Math.round(t).toDouble())
            if (material != null && filament != null && filament.unknownTools(p.filamentByToolMm) == null) b[material] = filament.round(filament.of(p.filamentByToolMm))
            baselineFact = "baseline: every object in flat layers at its baseline height ($baselineWhat), printed together: " + Scoring.describeBaseline(b)
            b
        }
        val ranked = Scoring.rank(usable, metrics, weights,
            mapOf(Metric.TRAVEL to Math.hypot(jf.bed.bedMaxX - jf.bed.bedMinX, jf.bed.bedMaxY - jf.bed.bedMinY)) + zeroScale, baseline)
        val chosen = ranked.first().item
        val scoreOf = ranked.associateBy { it.item.order }
        val alts = ObjectOrder.entries.map { o ->
            val l = layouts[o]
            Alternative(o.label, l?.let { (predictions[it]?.let { p -> "predicted $p; " } ?: "") +
                "travel between objects %.0f mm; %s; %s".format(Locale.ROOT, travelBetweenObjects(it, jf.layerHeightMm), Arrange.describe(it.placed), Scoring.explain(scoreOf.getValue(o))) }
                ?: if (o.needs.isEmpty()) "needs no head geometry" else "needs " + o.needs.joinToString { t -> t.name.lowercase() }, rejected[o], chosen = o == chosen.order)
        }
        return Decision("object order", chosen, chosen.order.label + if (usable.size == 1) " (the only usable order)" else " (weighted score)",
            facts + listOfNotNull(predictions.values.firstOrNull { it.timeS == null }?.let { "time not predicted: ${it.why}; travel stands in for it" }, materialFact, baselineFact,
                Scoring.unscoredFact(ranked)) +
                weights.unmeasured(metrics.keys - ranked.first().unscored.keys).map { "goal '${it.id}' has weight but nothing this decision measures counts toward it" }, alts,
            listOf(if (keepPlacement) "placement kept as given: objects are checked against the bed, not against each other (boxes would refuse nested shapes)" else "arrangement: rows along X, centered; bounding boxes stand in for true outlines"))
    }

    /**
     * Travel between objects over the whole print: printed together, every height at which two or more objects lay a layer
     * visits each of them and comes back (a cycle per such height; a height only one object prints at counts nothing); one
     * at a time, each object to the next once. Each object's layers at its plan's flat layer height, else [layerHeightMm]
     * (the settings'), so objects at different heights meet only where their layer tops coincide.
     */
    fun travelBetweenObjects(l: Layout, layerHeightMm: Double): Double {
        val p = l.placed
        fun d(a: Placed, b: Placed) = Math.hypot(a.cx - b.cx, a.cy - b.cy)
        return when (l.order) {
            ObjectOrder.ONE_AT_A_TIME -> p.zipWithNext().sumOf { (a, b) -> d(a, b) }
            ObjectOrder.TOGETHER -> {
                // each object's layer tops in whole µm, so objects at one height land on the same tops
                val tops = p.mapIndexed { i, it ->
                    val h = Math.round((l.techniques.getOrNull(i)?.layerHeightMm ?: layerHeightMm) * 1000)
                    val n = Math.ceil(it.heightMm / (h / 1000.0)).toInt()
                    (1..n).map { it * h }.toSet()
                }
                tops.flatten().toSortedSet().sumOf { z ->
                    val present = p.filterIndexed { i, _ -> z in tops[i] }
                    if (present.size < 2) 0.0 else present.zipWithNext().sumOf { (a, b) -> d(a, b) } + d(present.last(), present.first())
                }
            }
        }
    }
}

/**
 * How a decision weighs predicted filament (material is compared by mass): per tool
 * (0-based), what one millimetre of its filament counts in [metric]'s unit — grams (cross-section x density) for
 * filament.mass, mm³ (cross-section) for filament.volume. [metric] null: filament cannot be compared, [why] says why.
 */
class FilamentBasis(val metric: Metric?, val perMm: Map<Int, Double>, val why: String) {
    /** The filament of each tool ([plymeld.core.Predict.filamentByTool], mm) in [metric]'s unit. */
    fun of(byToolMm: Map<Int, Double>): Double = byToolMm.entries.sumOf { (t, mm) -> if (mm == 0.0) 0.0 else mm * perMm.getValue(t) }
    /** Why some of [byToolMm] cannot be weighed (a tool with no known filament), or null. */
    fun unknownTools(byToolMm: Map<Int, Double>): String? = byToolMm.filter { (t, mm) -> t !in perMm && mm != 0.0 }.keys.takeIf { it.isNotEmpty() }
        ?.let { ts -> "the g-code extrudes with ${ts.sorted().joinToString { "T$it (tool ${it + 1})" }}, which holds no known filament" }
    /** Rounded so equal prints tie exactly: 0.001 g, or 1 mm³. */
    fun round(v: Double): Double = if (metric == Metric.FILAMENT_MASS) Math.round(v * 1000) / 1000.0 else Math.round(v).toDouble()

    /** One filament that may be in a tool: its name, diameter (mm), and density (g/cm³, null = not given). */
    class Filament(val name: String, val diameterMm: Double, val densityGcm3: Double?)

    companion object {
        /**
         * From the filaments each tool may hold (0-based tool -> its filaments). By mass when every tool's filaments
         * agree on one diameter and one density; by volume when they agree on the diameter but a density is missing or
         * differs within a tool (the g-code's T commands cannot tell which of them is pushed); not at all when a tool's
         * filaments differ in diameter.
         */
        fun of(tools: Map<Int, List<Filament>>): FilamentBasis {
            fun names(fs: Collection<Filament>) = fs.joinToString { "'${it.name}'" }
            val area = HashMap<Int, Double>()
            for ((t, fs) in tools) {
                val ds = fs.map { it.diameterMm }.distinct()
                if (ds.size != 1) return FilamentBasis(null, emptyMap(), "tool ${t + 1} holds filaments of different diameters (${names(fs)}: ${ds.joinToString { "$it mm" }})")
                area[t] = Math.PI * ds.single() * ds.single() / 4
            }
            val all = tools.values.flatten()
            val noDensity = all.filter { it.densityGcm3 == null }.distinctBy { it.name }
            val mixed = tools.filterValues { fs -> fs.mapNotNull { it.densityGcm3 }.distinct().size > 1 }
            if (noDensity.isEmpty() && mixed.isEmpty())
                return FilamentBasis(Metric.FILAMENT_MASS, area.mapValues { (t, a) -> a * tools.getValue(t).first().densityGcm3!! / 1000 },
                    "by mass: " + all.distinctBy { it.name }.joinToString { "'${it.name}' %s g/cm³".format(java.util.Locale.ROOT, it.densityGcm3) })
            val why = if (noDensity.isNotEmpty()) "no density for ${names(noDensity)} (hardware.filament.<key>.density)"
                else "tool ${mixed.keys.first() + 1} holds filaments of different densities (${names(mixed.values.first())}), and the g-code does not say which is pushed"
            return FilamentBasis(Metric.FILAMENT_VOLUME, area, "by volume, not mass: $why (equal to mass up to density; exact for one filament)")
        }
    }
}
