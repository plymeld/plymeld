package plymeld.core.decide

import java.util.Locale

/** The goals and measures live in plymeld-model (Goals.kt), where the config schema reads them too; the names stay here. */
typealias Goal = plymeld.model.Goal
typealias Metric = plymeld.model.Metric

class Weights(val byGoal: Map<Goal, Double>, val byMetric: Map<Metric, Double>, val source: String) {
    override fun toString() = (byGoal.map { "${it.key.id}=${fmt(it.value)}" } + byMetric.map { "${it.key.id}=${fmt(it.value)}" }).joinToString(", ") + " ($source)"

    /**
     * Effective weight of each metric in a decision that can measure [available]: a goal's weight is split across ALL its
     * available metrics, whether or not they differ between the candidates, so a new candidate cannot change another
     * measure's share, in proportion to the metrics' own weights (job.priorities.measure.<measure>, default 1; the bundled
     * config gives layer_lines 4). A metric at 0 takes nothing and the rest share the goal.
     */
    fun effective(available: Collection<Metric>): Map<Metric, Double> {
        val out = LinkedHashMap<Metric, Double>()
        for ((g, w) in byGoal) {
            val ms = available.filter { it.goal == g }
            val rel = ms.associateWith { byMetric[it] ?: 1.0 }
            val sum = rel.values.sum()
            for (m in ms) out[m] = if (sum <= 0) 0.0 else w * rel.getValue(m) / sum
        }
        for (m in available) out.putIfAbsent(m, 0.0)
        return out
    }

    /** Goals with weight whose metrics this decision cannot measure (they have no effect here). */
    fun unmeasured(available: Collection<Metric>): List<Goal> = byGoal.filter { (g, w) -> w > 0 && available.none { it.goal == g } }.keys.toList()

    companion object {
        private fun fmt(v: Double) = if (v == Math.floor(v)) v.toLong().toString() else v.toString()

        fun parse(text: String, source: String): Weights {
            val g = LinkedHashMap<Goal, Double>(); val m = LinkedHashMap<Metric, Double>()
            for (item in text.split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
                val (k, v) = item.split('=').map { it.trim() }.also { require(it.size == 2) { "job.priorities ($source): '$item' is not name=weight" } }
                val w = v.toDoubleOrNull() ?: error("job.priorities ($source): weight '$v' of '$k' is not a number")
                require(w >= 0) { "job.priorities ($source): weight of '$k' is negative" }
                Goal.entries.firstOrNull { it.id == k }?.let { require(it !in g) { "job.priorities ($source) repeats '$k'" }; g[it] = w }
                    ?: Metric.entries.firstOrNull { it.id == k }?.let { require(it !in m) { "job.priorities ($source) repeats '$k'" }; m[it] = w }
                    ?: error("job.priorities ($source) names '$k'; known goals: ${Goal.entries.joinToString { it.id }}; metrics: ${Metric.entries.joinToString { it.id }}")
            }
            require((g.values + m.values).any { it > 0 }) { "job.priorities ($source): every weight is 0, nothing to decide by" }
            return Weights(g, m, source)
        }
    }
}

/**
 * Ranks alternatives by the size of their differences, measured against a
 * fixed baseline on a log scale. The weights are exchange rates between goals: each alternative's
 * score is the sum, over the metrics, of effective weight x ln(its value / the [baseline]'s) for a less-is-better metric
 * (ln(the baseline's / its value) for more-is-better), so worse than the baseline adds, better subtracts, and twice as
 * good counts as much as twice as bad (±0.69). Near the baseline ln(1 + x) ≈ x, so small differences count as their
 * share (1 % ≈ 0.01) and the exchange rates read as before. The baseline is the same for every alternative (flat layers
 * at method.search.baseline.layer.height_share of the nozzle: the caller measures it), and nothing in a metric's score reads the other
 * alternatives, so the order of two alternatives does not depend on which others are scored with them. No cap.
 *
 * Every metric the decision measures takes its share of its goal's weight, whether or not it differs.
 *
 * **Metrics that can be 0** ([ZEROABLE]: unsupported_area, support.interface, support.mass, support.volume, tip_offset,
 * surface_step, travel) cannot take a log: they are scored linearly: (value - the
 * baseline's) / the baseline's where the baseline's is positive, and from 0 against [zeroScale] where it is 0. Any other
 * metric whose baseline value is 0, not finite, or missing is scored the same way, from 0 against [zeroScale]. [zeroScale]
 * is method.search.zero_scale or a scale the caller derived from the model or settings; it never comes from the alternatives.
 * A metric that needs a scale and has none is NOT scored (its share of its goal's weight goes to the goal's other
 * metrics) and [Scored.unscored] says so: the decision reports it. Lowest total wins; exact ties keep the alternatives'
 * order.
 *
 * [baseline] null: the earlier rule, before the baseline, kept for a decision that cannot measure a baseline and says
 * so: how much worse than the best alternative, as a fraction of the best value, at most 1; where the best value is 0,
 * against [zeroScale] (no scale: not scored, as above).
 */
object Scoring {
    /**
     * [parts]: each scored metric's part of [total]; [plain]: the same difference in plain terms (the share better or
     * worse than the baseline, or the difference in the metric's unit where the baseline is 0); [unscored]: metrics the
     * decision measures but could not score, with why (the same for every alternative).
     */
    class Scored<T>(val item: T, val total: Double, val parts: Map<Metric, Double>, val plain: Map<Metric, String> = emptyMap(),
                    val unscored: Map<Metric, String> = emptyMap())

    /** Metrics whose value can be 0 by their nature, so ln(value / baseline) is undefined: scored linearly (see [Scoring]), or from a unit ([UNIT_LOG]). */
    val ZEROABLE: Set<Metric> = setOf(Metric.UNSUPPORTED_AREA, Metric.SUPPORT_INTERFACE, Metric.SUPPORT_MASS, Metric.SUPPORT_VOLUME,
        Metric.TIP_OFFSET, Metric.SURFACE_STEP, Metric.TRAVEL, Metric.ROUGH_TOP_AREA, Metric.HEIGHT_ERROR, Metric.COOLING_SHORTFALL, Metric.SEAM_SCAR, Metric.STRING_RISK, Metric.RESTART_STARVED,
        Metric.CURVED_BORDER)
    /**
     * Support's measures ("Adopt that proposal"): scored as ln(1 + value / unit) − ln(1 + baseline's /
     * unit), the unit one tree pad (the tip spacing squared) for the areas and one pad's column for the plastic
     * (PoseDecision.zeroScales; method.search.zero_scale overrides). The first pad already costs ln 2, each doubling the same
     * again, and nothing is measured against "all of the model supported" (the surface area), which made a little support
     * read as nothing.
     */
    val UNIT_LOG: Set<Metric> = setOf(Metric.UNSUPPORTED_AREA, Metric.SUPPORT_INTERFACE, Metric.SUPPORT_MASS, Metric.SUPPORT_VOLUME)

    /**
     * Measures that are the SAME area counted under one goal either way (the support-needing area is droop
     * with no support, scars with it, never both), so they share one scale: the baseline's value of whichever is not 0.
     * With their own scales (a baseline droop of 200 mm² against a zero scale of the whole surface for the scars),
     * turning droop into scars would score as a gain under quality alone, and support would win on an artefact.
     */
    private val PAIRED: Map<Metric, Metric> = mapOf(Metric.UNSUPPORTED_AREA to Metric.SUPPORT_INTERFACE, Metric.SUPPORT_INTERFACE to Metric.UNSUPPORTED_AREA)
    /**
     * Measures of the same kind as another, scaled by its baseline value where their own baseline's is 0: a curved island's
     * border is a line on the model's side as a seam is (both blemishes in mm² as seen), and no island in the baseline.
     */
    private val SAME_SCALE: Map<Metric, Metric> = mapOf(Metric.CURVED_BORDER to Metric.SEAM_SCAR)

    /** The scale of a measure that can be 0: the baseline's value, its pair's ([PAIRED]), its kind's ([SAME_SCALE]), or the zero scale; null = none. A [UNIT_LOG] measure's is its unit alone. */
    private fun linearScale(m: Metric, baseline: Map<Metric, Double>?, zeroScale: Map<Metric, Double>): Double? {
        fun b(x: Metric) = baseline?.get(x)?.takeIf { it.isFinite() && Math.abs(it) > 1e-12 }?.let { Math.abs(it) }
        if (m in UNIT_LOG) return zeroScale[m]?.takeIf { it > 0 && it.isFinite() }
        return b(m) ?: PAIRED[m]?.let { b(it) } ?: SAME_SCALE[m]?.let { b(it) } ?: zeroScale[m]?.takeIf { it > 0 && it.isFinite() }
    }
    /** A [UNIT_LOG] measure's score: ln(1 + v / unit), the direction by [Metric.better]. */
    private fun unitLog(m: Metric, v: Double, unit: Double) = (if (m.better == plymeld.model.Metric.Better.LESS) 1.0 else -1.0) * Math.log(1 + Math.max(0.0, v) / unit)

    private fun num(v: Double) = "%.4g".format(Locale.ROOT, v)

    fun <T> rank(items: List<T>, values: Map<Metric, (T) -> Double>, w: Weights, zeroScale: Map<Metric, Double> = emptyMap(),
                 baseline: Map<Metric, Double>? = null): List<Scored<T>> {
        val all = values.mapValues { (_, f) -> items.map(f) }
        fun scaleOf(m: Metric) = zeroScale[m]?.takeIf { it > 0 && it.isFinite() }
        // how each metric is scored: with a baseline, from the baseline and the scales only (never the alternatives)
        val logPath = HashSet<Metric>()
        val unscored = LinkedHashMap<Metric, String>()
        for (m in values.keys) {
            if (baseline == null) {
                val vs = all.getValue(m)
                val best = if (m.better == plymeld.model.Metric.Better.LESS) vs.min() else vs.max()
                if (Math.abs(best) <= 1e-12 && scaleOf(m) == null) unscored[m] = "the best value is 0 and there is no scale for it (method.search.zero_scale, or one derived from the model)"
                continue
            }
            val b = baseline[m]?.takeIf { it.isFinite() }
            when {
                m !in ZEROABLE && b != null && b > 1e-12 -> logPath += m
                linearScale(m, baseline, zeroScale) != null -> {}   // can be 0: linear, against the baseline's value, its pair's, or the zero scale
                else -> unscored[m] = (if (b == null) "the baseline has no value for it" else "the baseline's value is 0") +
                    " and there is no scale for it (method.search.zero_scale, or one derived from the model or settings)"
            }
        }
        val eff = w.effective(values.keys - unscored.keys).filterValues { it > 0 }
        val scored = items.map { item ->
            val parts = LinkedHashMap<Metric, Double>(); val plain = LinkedHashMap<Metric, String>()
            for ((m, weight) in eff) {
                val v = values.getValue(m)(item)
                if (baseline == null) {
                    val vs = all.getValue(m)
                    val best = if (m.better == plymeld.model.Metric.Better.LESS) vs.min() else vs.max()
                    val scale = if (Math.abs(best) > 1e-12) Math.abs(best) else scaleOf(m)!!
                    parts[m] = weight * minOf(1.0, Math.abs(v - best) / scale)
                    continue
                }
                val b = baseline[m]?.takeIf { it.isFinite() } ?: 0.0
                val better = if (m.better == plymeld.model.Metric.Better.LESS) v < b else v > b
                val word = if (v == b) "same" else if (better) "better" else "worse"
                if (m in logPath) {
                    require(v > 0) { "${m.id} = $v in a candidate: not positive, so ln(value / baseline) is undefined; it is scored on a log scale because it cannot be 0 by its nature (Scoring.ZEROABLE lists those that can)" }
                    parts[m] = weight * (if (m.better == plymeld.model.Metric.Better.LESS) Math.log(v / b) else Math.log(b / v))
                    plain[m] = "%+.1f %%, %s".format(Locale.ROOT, (v - b) / b * 100, word)
                } else if (m in UNIT_LOG) {
                    val u = linearScale(m, baseline, zeroScale)!!
                    parts[m] = weight * (unitLog(m, v, u) - unitLog(m, b, u))
                    plain[m] = "%s%s from %s, in units of %s, %s".format(Locale.ROOT, if (v - b >= 0) "+" else "", num(v - b) + (if (m.unit.isEmpty()) "" else " ${m.unit}"), if (Math.abs(b) > 1e-12) num(b) else "0", num(u) + (if (m.unit.isEmpty()) "" else " ${m.unit}"), word)
                } else {
                    val s = linearScale(m, baseline, zeroScale)!!
                    parts[m] = weight * (if (m.better == plymeld.model.Metric.Better.LESS) v - b else b - v) / s
                    plain[m] = if (Math.abs(b) > 1e-12 && s == Math.abs(b)) "%+.1f %%, %s".format(Locale.ROOT, (v - b) / s * 100, word)
                        else "%s%s from %s against %s, %s".format(Locale.ROOT, if (v - b >= 0) "+" else "", num(v - b) + (if (m.unit.isEmpty()) "" else " ${m.unit}"), if (Math.abs(b) > 1e-12) num(b) else "0", num(s), word)
                }
            }
            Scored(item, parts.values.sum(), parts, plain, unscored)
        }
        // rounding keeps exact ties exact (symmetric parts), so the order is deterministic
        return scored.withIndex().sortedWith(compareBy({ Math.round(it.value.total * 1e9) }, { it.index })).map { it.value }
    }

    /**
     * A portion's contribution LINEARISED at the baseline: the score a change of
     * [delta] in each metric would add, taking ln(T / Tb) ≈ (T − Tb) / Tb. Each metric's weight is its share under
     * [measured] (the metrics the decision scores), the scale the baseline's value (its zero scale where that is 0, as in
     * [rank]); a metric that could not be scored adds nothing. Worse adds, better subtracts, so the sums over portions are
     * separable, which is what lets a dynamic program choose per conflict group; the top combinations are re-scored
     * exactly by [rank] afterwards.
     */
    fun linearised(delta: Map<Metric, Double>, w: Weights, measured: Collection<Metric>, zeroScale: Map<Metric, Double>, baseline: Map<Metric, Double>?): Double {
        val eff = w.effective(measured)
        var sum = 0.0
        for ((m, d) in delta) {
            val weight = eff[m] ?: continue
            val scale = linearScale(m, baseline, zeroScale) ?: continue
            if (m in UNIT_LOG) {
                // exact on the unit log (the curve is the whole point): from the baseline's value by d
                val b = baseline?.get(m)?.takeIf { it.isFinite() } ?: 0.0
                sum += weight * (unitLog(m, b + d, scale) - unitLog(m, b, scale))
            } else sum += weight * (if (m.better == plymeld.model.Metric.Better.LESS) d else -d) / scale
        }
        return sum
    }

    /** What a decision's log says about metrics it measures but could not score (none: null). */
    fun unscoredFact(r: List<Scored<*>>): String? = r.firstOrNull()?.unscored?.takeIf { it.isNotEmpty() }?.let { u ->
        "not scored: " + u.entries.joinToString("; ") { (m, why) -> "${m.id}: $why" } + "; its share of its goal's weight goes to the goal's other measures"
    }

    /** The baseline's value of each metric, in its unit, for a decision's log. */
    fun describeBaseline(b: Map<Metric, Double>): String =
        b.entries.joinToString { (m, v) -> if (!v.isFinite()) "${m.id} (none)" else "%s %.4g%s".format(Locale.ROOT, m.id, v, if (m.unit.isEmpty()) "" else " ${m.unit}") }

    /**
     * method.search.zero_scale ("metric=value, ..."): reference scales for metrics whose baseline value is 0,
     * in each metric's unit. Unlisted metrics keep the caller's derived scale.
     */
    fun parseZeroScale(text: String?, source: String): Map<Metric, Double> {
        if (text.isNullOrBlank()) return emptyMap()
        return text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.associate { item ->
            val (k, v) = item.split('=').map { it.trim() }.also { require(it.size == 2) { "method.search.zero_scale ($source): '$item' is not metric=value" } }
            val m = Metric.entries.firstOrNull { it.id == k } ?: error("method.search.zero_scale ($source) names '$k'; metrics: ${Metric.entries.joinToString { it.id }}")
            val x = v.toDoubleOrNull()?.takeIf { it > 0 } ?: error("method.search.zero_scale ($source): '$v' for '$k' is not a positive number")
            m to x
        }
    }

    /** "score T (metric part [plain], ...)": each part, and with a baseline how far it is from the baseline in plain terms. */
    fun explain(s: Scored<*>): String = "score %.3f".format(Locale.ROOT, s.total) +
        if (s.parts.isEmpty()) "" else " (" + s.parts.entries.joinToString(", ") {
            "%s %.3f".format(Locale.ROOT, it.key.id, it.value) + (s.plain[it.key]?.let { p -> " [$p]" } ?: "")
        } + ")"
}
