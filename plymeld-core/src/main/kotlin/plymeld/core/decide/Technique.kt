package plymeld.core.decide

import plymeld.mesh.TriangleSoup
import java.util.Locale

/**
 * One control of a print technique: a value the technique can be run at (layer height, speed,
 * line count, ...), its unit, the range it may take, and where that range comes from. A locked control is a range of one
 * point.
 */
class Control(val id: String, val unit: String, val range: Range, val source: String) {
    sealed class Range {
        /** Any value from [min] to [max]. */
        class Continuous(val min: Double, val max: Double) : Range() { init { require(max >= min) { "a range's max ($max) is below its min ($min)" } } }
        /** Only these values (whole layers, whole line counts, or one locked value). */
        class Steps(val values: List<Double>) : Range() { init { require(values.isNotEmpty()) { "a stepped range needs a value" } } }
    }

    /** Locked: a range of one point. */
    val locked: Boolean get() = range is Range.Steps && range.values.size == 1

    fun describe(): String = when (val r = range) {
        is Range.Continuous -> "%s %s-%s %s (%s)".format(Locale.ROOT, id, fmt(r.min), fmt(r.max), unit, source)
        is Range.Steps -> if (r.values.size == 1) "%s locked at %s %s (%s)".format(Locale.ROOT, id, fmt(r.values.single()), unit, source)
            else "%s one of %s %s (%s)".format(Locale.ROOT, id, r.values.joinToString { fmt(it) }, unit, source)
    }

    /** Where [v] lies on the range, 0 at its min and 1 at its max (null for a stepped range). */
    fun share(v: Double): Double? = (range as? Range.Continuous)?.let { if (it.max > it.min) (v - it.min) / (it.max - it.min) else 0.0 }

    companion object {
        fun fmt(v: Double) = String.format(Locale.ROOT, "%.4f", v).trimEnd('0').trimEnd('.')
    }
}

/**
 * A portion of a print one technique is applied to, as a technique measures it. First cut: a whole
 * object in one pose; cuts by height, within a layer, by feature, or by sequence come later. The coordinator's view of a
 * portion, a volume at a step in time, is [Coordination.Portion].
 */
class Portion(val soup: TriangleSoup, val pose: Pose, val partTriangles: IntArray? = null, val covered: FloatArray? = null)

/**
 * A print technique: it exposes its [controls], each with a range, and predicts the
 * [outcomes] in each measure's own unit (seconds, grams, mm of stair-step, ...) for a portion at given control values,
 * from the geometry and the settings, with the same measuring code the decisions use. It never declares trade-off
 * figures as constants and never offers weights: the user's weights pick the point on the range (Scoring).
 *
 * Built so far: [FlatLayers].
 */
interface TechniqueModel {
    val technique: PrintTechnique
    /** The controls and their ranges, from the settings [f]. */
    fun controls(f: PoseFacts): List<Control>
    /** Predicted outcomes of [portion] at [values] (control id -> value), each measure in its own unit. */
    fun outcomes(portion: Portion, f: PoseFacts, values: Map<String, Double>): Map<Metric, Double>
}

/**
 * Flat layers: uniform and adaptive layers as one technique. Its controls:
 * - the layer height ([LAYER_HEIGHT]), ranging over the tool's thinnest to thickest layer, or locked. The layer planner
 *   takes any height, so the range is continuous; points are taken in whole microns. The search samples it coarse to
 *   fine ([coarse] in every pose, then, round by round, the midpoint of the interval next to the leading candidate that
 *   could still win: PoseDecision.decideWithTechnique,
 *   each object its own) within method.search.time.max and method.search.layer.heights points per pose. A portion of ONE height
 *   is flat layers with this control at one point (what uniform layers were);
 * - the largest stair-step ([MAX_STEP], plan.technique.flat.step.max, locked at the setting): in a portion whose layers
 *   FOLLOW the surfaces, each layer is as thick as the surfaces it crosses allow within the layer height's range, so
 *   no layer leaves a stair-step deeper than this (what adaptive layers were).
 * How it finds its portions: the survey ([Surveys.flat]) proposes the bands where following the surfaces changes the
 * layer count; the search weighs one height throughout, following throughout, and the survey's bands (one height
 * elsewhere). Layer heights per region will be finer portions of flat layers, not a technique of their own.
 */
object FlatLayers : TechniqueModel {
    const val LAYER_HEIGHT = "layer.height"
    const val MAX_STEP = "step.max"
    override val technique = PrintTechnique.FLAT_LAYERS

    /** How a portion's heights are set: [ONE] layer height (uniform), or each layer as thick as the surfaces allow ([FOLLOW]). */
    enum class Heights(val id: String) { ONE("one"), FOLLOW("follow") }

    override fun controls(f: PoseFacts): List<Control> = listOfNotNull(f.layerHeightControl, maxStep(f))

    /** The largest stair-step, locked at the setting (plan.technique.flat.step.max, else the layer height), or null when neither is known. */
    fun maxStep(f: PoseFacts): Control? = (f.maxStepMm ?: f.layerHeightMm)?.let { s ->
        Control(MAX_STEP, "mm", Control.Range.Steps(listOf(s)), if (f.maxStepMm != null) "plan.technique.flat.step.max" else "the layer height: plan.technique.flat.step.max not set")
    }

    /**
     * Why layers cannot follow the surfaces here, or null: they need the layer height (the survey's reference), the
     * tool's thinnest and thickest layer, and no fuzzy skin.
     */
    fun followBlocked(f: PoseFacts?): String? =
        listOfNotNull(if (f?.layerHeightMm == null) "plan.technique.flat.layer.height" else null,
            if (f?.minLayerMm == null) "hardware.tool.<key>.layer.height.min" else null, if (f?.maxLayerMm == null) "hardware.tool.<key>.layer.height.max" else null)
            .takeIf { it.isNotEmpty() }?.let { "blocked: no ${it.joinToString()}" }
            // fuzzy skin is a texture whose look follows the layer height, so it would change
            // from band to band; on a fuzzy test model 0.27 mm layers also doubled the shell gaps
            // behind the jittered wall (deposition gate)
            ?: if (f?.fuzzySkin == true) "not with fuzzy skin: its texture depends on the layer height (plan.faces.side.technique = \"fuzzy_skin\")" else null

    /** The plan of flat layers [h] mm thick over the whole portion (one height). */
    fun plan(h: Double): TechniquePlan = TechniquePlan.whole(PrintTechnique.FLAT_LAYERS).at(h)

    /** The plan of flat layers following the surfaces over the whole portion. */
    fun following(): TechniquePlan = TechniquePlan.whole(PrintTechnique.FLAT_LAYERS, follow = true)

    /** [portion] measured at each of [heights] (mm), with the section estimates: the pose decision's own measuring. */
    fun measure(portion: Portion, f: PoseFacts, heights: List<Double>): List<PoseDecision.Measure> =
        PoseDecision.measureAll(portion.soup, portion.pose, f.overhang, null, portion.covered, f, withSections = true,
            plansFor = { _, _ -> heights.map(::plan) }, partTriangles = portion.partTriangles)

    /**
     * Outcomes at one height ([LAYER_HEIGHT] given), or with the layers following the surfaces (no [LAYER_HEIGHT]: the
     * largest stair-step is the setting's, [MAX_STEP] locked).
     */
    override fun outcomes(portion: Portion, f: PoseFacts, values: Map<String, Double>): Map<Metric, Double> {
        values[MAX_STEP]?.let { s -> require(f.maxStepMm == null || Math.abs(s - f.maxStepMm) < 1e-9) { "flat layers: the largest stair-step is locked at ${f.maxStepMm} mm (got $s)" } }
        val h = values[LAYER_HEIGHT] ?: return PoseDecision.valuesOf(PoseDecision.measureAll(portion.soup, portion.pose, f.overhang, null, portion.covered, f,
            withSections = true, plansFor = { _, _ -> listOf(following()) }, partTriangles = portion.partTriangles).single(), f)
        return PoseDecision.valuesOf(measure(portion, f, listOf(h)).single(), f)
    }

    /** [v] in whole microns: the resolution layer heights are sampled at. */
    fun micron(v: Double) = Math.round(v * 1000) / 1000.0

    /**
     * How many points of a continuous range the first round tries: the bottom, the top, and one
     * between (the baseline, else the middle), enough to show which way the measures lean; the rounds then refine next to
     * the best. Search effort only.
     */
    const val COARSE_START = 3

    /**
     * The first points tried on [c], at most [n] and at most [COARSE_START] on a continuous range, in this order: the
     * range's ends, the baseline [baseline] (when inside the range), then the midpoints between neighboring points so far.
     * A locked or stepped range gives its own values.
     */
    fun coarse(c: Control, baseline: Double?, n: Int): List<Double> = when (val r = c.range) {
        is Control.Range.Steps -> r.values.take(maxOf(1, n))
        is Control.Range.Continuous -> {
            val first = listOfNotNull(r.min, r.max, baseline?.takeIf { it > r.min + 5e-4 && it < r.max - 5e-4 }).map(::micron).distinct()
            val sorted = first.sorted()
            val mids = sorted.zipWithNext { a, b -> micron((a + b) / 2) }.filter { m -> first.none { Math.abs(it - m) < 5e-4 } }
            (first + mids).distinct().take(maxOf(1, minOf(COARSE_START, n)))
        }
    }

    /**
     * The next points nearer [best] (finer): the midpoints between [best] and its neighbors among [tried], in whole
     * microns, that were not tried yet. Empty when the neighbors are within a micron (or the range is stepped).
     */
    fun refine(c: Control, tried: List<Double>, best: Double): List<Double> {
        if (c.range !is Control.Range.Continuous) return emptyList()
        val s = tried.sorted()
        val i = s.indexOfFirst { Math.abs(it - best) < 5e-4 }.takeIf { it >= 0 } ?: return emptyList()
        return listOfNotNull(s.getOrNull(i - 1), s.getOrNull(i + 1)).map { micron((it + best) / 2) }
            .filter { m -> s.none { Math.abs(it - m) < 5e-4 } }.distinct()
    }
}
