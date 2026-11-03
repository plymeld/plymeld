package plymeld.core.decide

import java.util.Locale

/**
 * What `--explain` shows for one object's technique decision, in its order:
 * 1. the proposals, each whole, with its fate (chosen, trimmed, rejected and why), and the flat-layer fill; then the
 *    surface techniques and seam styles over the chosen volume portions: each surface technique's lock and fate, each exposed surface
 *    region with what it faces, the surface techniques that can serve it and the one chosen (and what today's toolpaths lay on it
 *    beyond that), each seam style's lock and fate, and each loop's seam curve with the style that takes it;
 * 2. the candidates: what each technique's screen said, the controls, the points tried, and every measure's outcome
 *    with its source, beside flat layers over the same volume (the baseline);
 * 3. the handoffs between the chosen portions, and each chosen surface technique's contract with the volumes under it;
 * 4. the scoring: the baseline's values and, for the best few candidates, each measure's part as its log score and the
 *    plain difference; then the finalists through the full pipeline, when predicted;
 * 5. the decision: winner, runner-up, margin, deciding measures, locks and requirements, how the search stopped (the
 *    reason, its time, its rounds, and the winner's score after each), what the time limit cut short.
 *
 * [text] is for a person; [tree] is the same content for `--explain-json`, the contract tests assert on. It is built from
 * what the search already measured: making it decides nothing and changes nothing printed.
 */
class TechniqueExplain(
    val objectName: String,
    val pose: String,
    val proposals: List<ProposalOut>,
    /** The chosen portions, in the order they must exist. */
    val portions: List<Coordination.Portion>,
    /** What no chosen proposal took: flat layers fill it. */
    val fill: List<Coordination.Volume>,
    val screen: List<Screen>,
    /** Each control, its range and where the range comes from (the technique's words). */
    val controls: List<String>,
    /** The control points tried in every pose, and refined, round by round, where an interval could still win (the technique's words). */
    val points: String?,
    val candidates: List<Candidate>,
    /** Why no candidate was measured (both locked, or one way at one point), or null. */
    val notMeasured: String?,
    val baseline: Baseline?,
    val scoring: List<ScoredOut>,
    /** How many candidates were scored in all ([scoring] shows the best of them). */
    val scoredOf: Int,
    val decision: DecisionOut,
    /** What happened to the choice after this decision (a curved band's start worked out, a height shared on the plate). */
    val after: List<String> = emptyList(),
    /** The surface techniques and seams over the chosen volume portions, once those are final. */
    val surfaceTechniques: Coordination.SurfaceTechniqueChoice? = null,
    /** The dynamic program's record in the chosen pose, per layer height: the conflict groups, their options' linearised costs, the sets kept. */
    val sets: List<String> = emptyList(),
) {
    class ProposalOut(val p: Coordination.Proposal, val fate: Fate, val why: String, val now: Coordination.Volume = p.volume)
    /** What became of a proposal. */
    enum class Fate(val id: String) { CHOSEN("chosen"), TRIMMED("trimmed"), REJECTED("rejected") }
    class Screen(val name: String, val usable: Boolean, val why: String?)
    /** One measure's outcome in its unit, and where it comes from (measured on the candidate, or a quick estimate standing in). */
    class Outcome(val metric: Metric, val value: Double, val source: String)
    class Candidate(val label: String, val outcomes: List<Outcome>, val rejected: String?, val chosen: Boolean, val score: Double?)
    class Baseline(val what: String, val outcomes: List<Outcome>)
    class Part(val metric: Metric, val part: Double, val plain: String?)
    class ScoredOut(val rank: Int, val label: String, val total: Double, val parts: List<Part>, val unscored: Map<Metric, String>) {
        val byGoal: Map<String, Double> get() = parts.groupBy { it.metric.goal?.id ?: "(reported only)" }.mapValues { (_, ps) -> ps.sumOf { it.part } }
    }
    /** A measure that decided between the winner and the runner-up: each one's part of its score. */
    class Deciding(val metric: Metric, val winner: Double, val runnerUp: Double)
    class DecisionOut(val winner: String, val runnerUp: String?, val margin: Double?, val deciding: List<Deciding>, val locks: List<String>, val cutShort: List<String>,
                      /** How the search stopped, and the finalists' full prediction, when any. */
                      val stop: Stop, val finalists: Finalists? = null,
                      /** Support: how it was decided (the switch's lock, or per overhang), the locked spots in the chosen pose, and each column's print order with why. */
                      val support: SupportOut? = null)

    /** Support's part of the decision: [how] it was decided, the [locked] spots as found in the chosen pose, each side-by-side [orders] with its reason. */
    class SupportOut(val how: String, val locked: List<String>, val orders: List<String>) {
        fun tree() = linkedMapOf("how" to how, "locked_spots" to locked, "orders" to orders)
    }

    /** Why the search stopped: every decision records one, `--explain` shows it, and the search log counts them. */
    enum class StopReason(val id: String, val words: String) {
        SHORT_CIRCUIT("short_circuited", "short-circuited: no unexplored option could beat the winner even at its best (a bound from each option's range)"),
        TIME_LIMIT("time_limit", "the time limit (method.search.time.max) was reached"),
        EXHAUSTED("everything_tried", "everything tried: no option is left unexplored"),
        BUDGET("budget", "method.search.layer.heights reached while an option could still win"),
        SETTLED("settled", "settled: method.search.settle_rounds rounds in a row left the winner as it was"),
        LOCKED("locked", "nothing to search: the choice is locked"),
    }

    /**
     * The search's stop record: the reason, the wall-clock time it ran, the rounds (round 1 the coarse sample,
     * each later round one refinement and a new choice), the winner's score after each round (how fast it settled), and
     * what the reason rests on in words.
     */
    class Stop(val reason: StopReason, val elapsedS: Double, val rounds: Int, val winnerScoreByRound: List<Double>, val what: String) {
        /** One line for the persistent search log (date, model, stop reason, time, rounds), tab-separated. */
        fun logLine(date: String, model: String): String = "%s\t%s\t%s\t%.2f s\t%d rounds".format(Locale.ROOT, date, model.replace('\t', ' '), reason.id, elapsedS, rounds)
        fun tree() = linkedMapOf("reason" to reason.id, "words" to reason.words, "elapsed_s" to elapsedS, "rounds" to rounds, "winner_score_by_round" to winnerScoreByRound, "what" to what)
    }

    /**
     * The finalists through the full pipeline: each one's predicted measures (time, filament)
     * in place of the quick estimates, scored against the baseline's own full prediction; [decided] says whether these
     * numbers decided (false: why not, in [why]).
     */
    class Finalists(val decided: Boolean, val why: String, val baseline: List<Outcome>, val scored: List<ScoredOut>, val notPredicted: List<String>)

    val handoffs: List<Coordination.Handoff> get() = Coordination.handoffs(portions)

    /** A chosen surface technique's handoff with one volume portion under it (its contract): what of it is met, or null. */
    class SurfaceTechniqueHandoff(val volume: String, val technique: String, val met: String?, val needs: String)

    /** For each surface technique chosen for some region, its contract with each chosen volume portion. */
    val surfaceTechniqueHandoffs: List<SurfaceTechniqueHandoff> get() = SurfaceTechnique.entries.filter { f -> surfaceTechniques?.regions.orEmpty().any { it.surfaceTechnique == f } }.flatMap { f ->
        portions.map { p -> SurfaceTechniqueHandoff("${p.name} ${p.volume.words}", f.short, f.on(p), f.needs) }
    }

    /** The same record for the object named [name] (the decision itself does not know the object's name). */
    fun named(name: String) = TechniqueExplain(name, pose, proposals, portions, fill, screen, controls, points, candidates, notMeasured, baseline, scoring,
        scoredOf, decision, after, surfaceTechniques, sets)

    /** The same record after the decision changed: [portionsNow] and [fillNow] chosen, [proposalsNow], and [note] saying why. */
    fun after(portionsNow: List<Coordination.Portion>, fillNow: List<Coordination.Volume>, note: String, proposalsNow: List<ProposalOut> = proposals) =
        TechniqueExplain(objectName, pose, proposalsNow, portionsNow, fillNow, screen, controls, points, candidates, notMeasured, baseline, scoring,
            scoredOf, decision, after + note, surfaceTechniques, sets)

    /** The same record with the surface techniques and seams [f] chosen over its (final) portions. */
    fun withSurfaceTechniques(f: Coordination.SurfaceTechniqueChoice) = TechniqueExplain(objectName, pose, proposals, portions, fill, screen, controls, points, candidates,
        notMeasured, baseline, scoring, scoredOf, decision, after, f, sets)

    // ---- text

    fun text(): String = buildString {
        fun num(v: Double) = "%.4g".format(Locale.ROOT, v)
        fun outcome(o: Outcome) = "${o.metric.id} ${num(o.value)}${if (o.metric.unit.isEmpty()) "" else " ${o.metric.unit}"} (${o.source})"
        fun portion(p: Coordination.Portion) = "${p.name} ${p.volume.words}" + (p.how?.let { " ($it)" } ?: "") + (p.order?.let { "; order: ${it.id}, ${it.words}" } ?: "")
        append("explain '$objectName', pose '$pose'\n")
        append("  1. proposals\n")
        append("    volume techniques (each volume has one):\n")
        for (o in proposals) {
            append("    - ${o.p.name} ${o.p.volume.words}" + (o.p.order?.let { ", ${it.id}" } ?: "") + ", proposed by ${o.p.by}${if (o.p.locked) " (a requirement)" else ""}: ${o.p.reason}; cuts allowed: ${o.p.cuts.words}\n")
            append("      " + o.fate.id.uppercase(Locale.ROOT) + (if (o.fate == Fate.TRIMMED) " to ${o.now.words}" else "") + ": ${o.why}\n")
        }
        append("    fill: " + (if (fill.isEmpty()) "none: the chosen proposals take the whole object" else "flat layers over " + fill.joinToString("; ") { it.words }) + "\n")
        append("    chosen portions: " + portions.joinToString("; ") { portion(it) } + "\n")
        surfaceTechniques?.let { s ->
            append("    surface techniques (kind surface: one per exposed surface, none = as laid; a job's lock, plan.faces.*, locks one; unlocked, weighed against as laid where an outcome is predicted):\n")
            for (f in SurfaceTechnique.entries) {
                val (fate, why) = s.fate(f)
                append("    - ${f.short} (${f.character}): serves ${f.serves.joinToString(" and ") { it.words }}; cuts allowed: ${f.cuts}; needs: ${f.needs}\n")
                append("      ${fate.id.uppercase(Locale.ROOT)}: $why\n")
            }
            for (n in s.notProposed) append("    - not proposed: $n\n")
            append("    surfaces (what each faces; the surface techniques that can serve it; the one chosen):\n")
            for (r in s.regions) {
                append("    - ${r.region.words}: can serve: " + r.canServe.joinToString(", ") { it.surfaceTechnique.short }.ifEmpty { "none" } +
                    "; surface technique: ${r.surfaceTechnique?.short ?: "none"} (${r.why})\n")
                r.alsoLaid?.let { append("      also laid today: $it\n") }
            }
            append("    seam styles (kind curve: one per loop's seam; locked by the job's lock, plan.seam; unlocked, the seam decision weighs them by the job's weights):\n")
            for (st in SeamStyle.entries) {
                val (fate, why) = s.fate(st)
                append("    - ${st.short}: ${st.label}; needs: ${st.needs}\n")
                append("      ${fate.id.uppercase(Locale.ROOT)}: $why\n")
            }
            append("    seams (each loop's seam curve; the styles that can take it; the one that takes it):\n")
            for (c in s.seams) append("    - ${c.curve.words}: can take: ${c.canTake.joinToString(", ") { it.short }}; " +
                c.owners.joinToString("; ") { (st, loops) -> "${st.short} on $loops" }.ifEmpty { "none" } + " (${c.why})\n")
        }
        append("  2. candidates\n")
        append("    screen: " + screen.joinToString("; ") { "${it.name}: " + (if (it.usable) "usable" + (it.why?.let { w -> " ($w)" } ?: "") else it.why ?: "not usable") } + "\n")
        for (c in controls) append("    control: $c\n")
        points?.let { append("    points: $it\n") }
        for (s in sets) append("    sets (conflict groups, options with their contribution linearised at the baseline, the sets the dynamic program kept): $s\n")
        baseline?.let { b -> append("    beside: ${b.what}: " + b.outcomes.joinToString(", ") { outcome(it) } + "\n") }
        notMeasured?.let { append("    not measured: $it\n") }
        for (c in candidates) {
            append("    ${if (c.chosen) "*" else "-"} ${c.label}" + (c.score?.let { " (score %.3f)".format(Locale.ROOT, it) } ?: "") +
                (c.rejected?.let { " -- rejected: $it" } ?: "") + "\n")
            if (c.outcomes.isNotEmpty()) append("      " + c.outcomes.joinToString(", ") { o ->
                outcome(o) + (baseline?.outcomes?.firstOrNull { it.metric == o.metric }?.let { b -> " vs ${num(b.value)}" } ?: "") } + "\n")
        }
        append("  3. handoffs\n")
        for (h in handoffs) append("    - ${h.from} -> ${h.to}: ${h.status}: it finds ${h.exit.words}, needs " +
            h.contract.entry.joinToString(" or ") { it.words } + "; first: ${h.contract.first}; ${h.contract.toolAndTemperature}; cost: ${h.cost}\n")
        for (h in surfaceTechniqueHandoffs) append("    - ${h.volume} -> ${h.technique} (surfaceTechnique): " + (h.met?.let { "met ($it)" } ?: "not laid there: it gives none of what it needs") +
            "; needs ${h.needs}\n")
        append("  4. scoring" + (if (scoring.isEmpty()) ": none (nothing measured)\n" else " (the best ${scoring.size} of $scoredOf candidates, against the baseline)\n"))
        if (scoring.isNotEmpty()) append("    baseline: " + (baseline?.let { b -> b.what + ": " + b.outcomes.joinToString(", ") { outcome(it) } }
            ?: "none: scored against the best candidate") + "\n")
        for (s in scoring) {
            append("    #${s.rank} ${s.label}: total %.3f; by goal: ".format(Locale.ROOT, s.total) + s.byGoal.entries.joinToString { "%s %.3f".format(Locale.ROOT, it.key, it.value) } + "\n")
            append("      " + s.parts.joinToString(", ") { p -> "%s %.3f".format(Locale.ROOT, p.metric.id, p.part) + (p.plain?.let { " [$it]" } ?: "") } + "\n")
            if (s.rank == 1 && s.unscored.isNotEmpty()) append("      not scored: " + s.unscored.entries.joinToString("; ") { "${it.key.id}: ${it.value}" } + "\n")
        }
        decision.finalists?.let { fs ->
            append("    finalists through the full pipeline (" + (if (fs.decided) "these numbers decide" else "not decisive") + "): ${fs.why}\n")
            if (fs.baseline.isNotEmpty()) append("    baseline predicted: " + fs.baseline.joinToString(", ") { outcome(it) } + "\n")
            for (s in fs.scored) {
                append("    #${s.rank} ${s.label}: total %.3f; by goal: ".format(Locale.ROOT, s.total) + s.byGoal.entries.joinToString { "%s %.3f".format(Locale.ROOT, it.key, it.value) } + "\n")
                append("      " + s.parts.joinToString(", ") { p -> "%s %.3f".format(Locale.ROOT, p.metric.id, p.part) + (p.plain?.let { " [$it]" } ?: "") } + "\n")
            }
            for (n in fs.notPredicted) append("    not predicted: $n\n")
        }
        append("  5. decision\n")
        append("    winner: ${decision.winner}\n")
        decision.runnerUp?.let { append("    runner-up: $it; margin %.3f\n".format(Locale.ROOT, decision.margin ?: 0.0)) }
        if (decision.deciding.isNotEmpty()) append("    deciding measures (winner's part vs the runner-up's): " + decision.deciding.joinToString("; ") {
            "%s %.3f vs %.3f".format(Locale.ROOT, it.metric.id, it.winner, it.runnerUp) } + "\n")
        append("    locks and requirements: " + decision.locks.ifEmpty { listOf("none") }.joinToString("; ") + "\n")
        decision.support?.let { s ->
            append("    support: ${s.how}\n")
            append("    locked spots: " + s.locked.ifEmpty { listOf("none") }.joinToString("; ") + "\n")
            append("    side-by-side order (a support column beside the model's layers): " + s.orders.ifEmpty { listOf("none: no support column") }.joinToString("; ") + "\n")
        }
        val st = decision.stop
        append("    stopped: ${st.reason.id}: ${st.reason.words}; ${st.what}\n")
        append("    search: %.2f s, %d round(s); the winner's score after each round: ".format(Locale.ROOT, st.elapsedS, st.rounds) +
            st.winnerScoreByRound.joinToString { "%.3f".format(Locale.ROOT, it) }.ifEmpty { "none (nothing measured)" } + "\n")
        append("    time limit: " + decision.cutShort.ifEmpty { listOf("nothing cut short") }.joinToString("; ") + "\n")
        for (a in after) append("    after: $a\n")
    }

    // ---- JSON: the same content

    fun tree(): Map<String, Any?> {
        fun portion(p: Coordination.Portion) = linkedMapOf("technique" to p.technique.short, "variant" to p.variant, "volume" to p.volume.tree(),
            "step" to p.step.id, "how" to p.how, "order" to p.order?.id)
        fun outcome(o: Outcome) = linkedMapOf("measure" to o.metric.id, "goal" to o.metric.goal?.id, "value" to o.value, "unit" to o.metric.unit, "source" to o.source)
        return linkedMapOf(
            "object" to objectName, "pose" to pose,
            "proposals" to proposals.map { o -> linkedMapOf("id" to o.p.id, "kind" to o.p.technique.kind.id, "technique" to o.p.technique.short, "variant" to o.p.variant,
                "volume" to o.p.volume.tree(), "order" to o.p.order?.id, "proposed_by" to o.p.by, "reason" to o.p.reason, "locked" to o.p.locked, "cuts" to o.p.cuts.id,
                "fate" to o.fate.id, "why" to o.why, "volume_now" to o.now.tree()) },
            "fill" to fill.map { it.tree() },
            "portions" to portions.map(::portion),
            "surface_techniques" to surfaceTechniques?.let { s -> SurfaceTechnique.entries.map { f -> val (fate, why) = s.fate(f); val (on, lock) = s.surfaceTechniqueLocks.getValue(f)
                linkedMapOf("kind" to f.kind.id, "technique" to f.short, "character" to f.character, "serves" to f.serves.map { it.id },
                    "lock" to if (on) "on" else "off", "lock_source" to lock, "cuts" to f.cuts, "needs" to f.needs, "fate" to fate.id, "why" to why) } },
            "finishes_not_proposed" to surfaceTechniques?.notProposed,
            "surfaces" to surfaceTechniques?.regions?.map { r -> linkedMapOf("region" to r.region.tree(), "faces" to r.region.facing.id,
                "can_serve" to r.canServe.map { linkedMapOf("technique" to it.surfaceTechnique.short, "lock" to if (it.on) "on" else "off", "lock_source" to it.lock) },
                "surface_technique" to (r.surfaceTechnique?.short ?: "none"), "why" to r.why, "also_laid" to r.alsoLaid) },
            "seam_styles" to surfaceTechniques?.let { s -> SeamStyle.entries.map { st -> val (fate, why) = s.fate(st); val (on, lock) = s.styleLocks.getValue(st)
                linkedMapOf("kind" to st.kind.id, "technique" to st.short, "label" to st.label, "lock" to if (on) "on" else "off", "lock_source" to lock,
                    "needs" to st.needs, "fate" to fate.id, "why" to why) } },
            "seams" to surfaceTechniques?.seams?.map { c -> linkedMapOf("curve" to c.curve.tree(), "can_take" to c.canTake.map { it.short },
                "owners" to c.owners.map { (st, loops) -> linkedMapOf("technique" to st.short, "loops" to loops) }, "why" to c.why) },
            "candidates" to linkedMapOf(
                "screen" to screen.map { linkedMapOf("name" to it.name, "usable" to it.usable, "why" to it.why) },
                "controls" to controls, "points" to points, "sets" to sets, "not_measured" to notMeasured,
                "baseline" to baseline?.let { b -> linkedMapOf("what" to b.what, "outcomes" to b.outcomes.map(::outcome)) },
                "measured" to candidates.map { c -> linkedMapOf("label" to c.label, "chosen" to c.chosen, "score" to c.score, "rejected" to c.rejected,
                    "outcomes" to c.outcomes.map(::outcome)) }),
            "handoffs" to handoffs.map { h -> linkedMapOf("from" to h.from, "to" to h.to, "status" to h.status, "exit" to h.exit.id,
                "entry" to h.contract.entry.map { it.id }, "first" to h.contract.first, "tool_and_temperature" to h.contract.toolAndTemperature, "cost" to h.cost) },
            "finish_handoffs" to surfaceTechniqueHandoffs.map { h -> linkedMapOf("volume" to h.volume, "technique" to h.technique, "met" to h.met, "needs" to h.needs) },
            "scoring" to linkedMapOf("of" to scoredOf, "top" to scoring.map { s -> linkedMapOf("rank" to s.rank, "label" to s.label, "total" to s.total,
                "by_goal" to s.byGoal, "parts" to s.parts.map { p -> linkedMapOf("measure" to p.metric.id, "goal" to p.metric.goal?.id, "log_score" to p.part, "plain" to p.plain) },
                "not_scored" to s.unscored.mapKeys { it.key.id }) }),
            "decision" to linkedMapOf("winner" to decision.winner, "runner_up" to decision.runnerUp, "margin" to decision.margin,
                "deciding" to decision.deciding.map { linkedMapOf("measure" to it.metric.id, "winner" to it.winner, "runner_up" to it.runnerUp) },
                "locks" to decision.locks, "cut_short" to decision.cutShort, "stop" to decision.stop.tree(), "support" to decision.support?.tree(),
                "finalists" to decision.finalists?.let { fs -> linkedMapOf("decided" to fs.decided, "why" to fs.why, "baseline" to fs.baseline.map(::outcome),
                    "scored" to fs.scored.map { s -> linkedMapOf("rank" to s.rank, "label" to s.label, "total" to s.total, "by_goal" to s.byGoal,
                        "parts" to s.parts.map { p -> linkedMapOf("measure" to p.metric.id, "goal" to p.metric.goal?.id, "log_score" to p.part, "plain" to p.plain) }) },
                    "not_predicted" to fs.notPredicted) }),
            "after" to after,
        )
    }

    companion object {
        /** How many of the best candidates the scoring section shows (report length only: plymeld's choice; it decides nothing). */
        const val SHOWN = 5

        /** The quick estimates that stand in for a full prediction (the pose decision's stand-ins); every other measure is measured. */
        private val STAND_INS: Set<Metric> = setOf(Metric.TIME_ESTIMATE, Metric.WEAKEST_BOND, Metric.SUPPORT_MASS, Metric.SUPPORT_VOLUME)
        fun sourceOf(m: Metric): String = if (m in STAND_INS) "stand-in" else "measured"

        /** [v] (maps, lists, strings, numbers, booleans, null) as JSON text; a number that is not finite is written as null. */
        fun json(v: Any?, indent: String = ""): String = when (v) {
            null -> "null"
            is String -> quote(v)
            is Boolean -> v.toString()
            is Double -> if (v.isFinite()) v.toString() else "null"
            is Number -> v.toString()
            is Map<*, *> -> if (v.isEmpty()) "{}" else v.entries.joinToString(",\n", "{\n", "\n$indent}") { (k, x) -> "$indent  ${quote(k.toString())}: ${json(x, "$indent  ")}" }
            is List<*> -> if (v.isEmpty()) "[]" else v.joinToString(",\n", "[\n", "\n$indent]") { "$indent  ${json(it, "$indent  ")}" }
            else -> quote(v.toString())
        }

        private fun quote(s: String): String = buildString {
            append('"')
            for (c in s) when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' -> append("\\u%04x".format(Locale.ROOT, c.code))
                else -> append(c)
            }
            append('"')
        }
    }
}
