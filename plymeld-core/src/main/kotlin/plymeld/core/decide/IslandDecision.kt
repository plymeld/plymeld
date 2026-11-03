package plymeld.core.decide

import plymeld.mesh.TriangleSoup
import java.util.Locale

/**
 * The curved islands decision: which of an object's caps get a curved island, weighed by the job's weights after the
 * pose and the plan of flat layers are chosen, where the plan locks no island.
 *
 * Candidates: the caps the shifted survey gives an island on the posed object, each found the way a plan's lock is
 * ([CurvedIsland.resolve] from the point it is named by), so the plan file written back gives the same island. Each is
 * measured alone against the flat plan on the stair-step it removes (surface_step: the faces it follows show none, the
 * others its own layers against their tilt); those that remove any, the most first, are weighed as sets: none (the
 * baseline), the first, the first two, ... up to [MAX_WEIGHED], each measured (surface_step, layer_lines, curved_border) and
 * sliced and predicted in full (time, filament, and what is read off the written program: seam_scar, string_risk,
 * restart_starved), ranked by the job's weights against the baseline (Scoring). The outcomes: no
 * stair-step where it follows, the border and the look as the only quality costs (the look a preference, job.faces.sloped_top.look,
 * no preference by default), time known.
 *
 * [Look]: no preference weighs; lines following the curve takes every candidate unweighed; concentric steps takes none.
 */
object IslandDecision {
    /** How many candidates, the most stair-step removed first, are weighed as sets (each set sliced in full). plymeld's choice: the plan's time. */
    const val MAX_WEIGHED = 4
    /** A candidate must follow at least this much of its cap (mm²): four of the survey's cells. plymeld's choice. */
    const val MIN_FOLLOWED_MM2 = 0.25

    enum class Look(val id: String, val words: String) {
        NO_PREFERENCE("no_preference", "no preference: weighed"),
        LINES_FOLLOW_CURVE("lines_follow_curve", "lines following the curve: every candidate, unweighed"),
        CONCENTRIC_STEPS("concentric_steps", "concentric steps: no curved island");
        companion object { fun of(id: String?): Look = entries.firstOrNull { it.id == id } ?: NO_PREFERENCE }
    }

    /**
     * The decision for object [name] (as posed: [posed]; [flat] its plan of flat layers): [p] the head and layers the survey
     * reads, [maxLayerMm] the tool's thickest layer; [measure] measures plans of the posed object (Pose.measureAll); [predict]
     * slices and predicts one in full (null: not available, nothing decided).
     */
    fun decide(name: String, posed: List<TriangleSoup>, flat: TechniquePlan, p: CurvedRegions.Params, maxLayerMm: Double, look: Look,
               weights: Weights, measure: (List<TechniquePlan>) -> List<PoseDecision.Measure>, predict: ((TechniquePlan) -> FullPrediction)?,
               /** Why a candidate cannot be built for this object (an island over parts laid with different filaments or settings); null = it can. */
               refuse: (CurvedIsland) -> String? = { null }): Decision<TechniquePlan> {
        val step = "curved islands ($name)"
        val standIns = listOf("curved_border's form (one outer line wide along the line where an island's layers start) is a placeholder, not measured",
            "the islands' own layers' stair-step is read against each face's tilt to its layer, from the island's warp (the slicer's layers are the warp's, the check reads them back)",
            "at most $MAX_WEIGHED candidates are weighed as sets, the most stair-step removed first; the rest are left flat")
        if (look == Look.CONCENTRIC_STEPS) return Decision(step, flat, "job.faces.sloped_top.look = concentric_steps: no curved island", listOf("look: ${look.words}"), standIns = standIns, overridden = true)
        val t0 = System.nanoTime()
        val r = CurvedRegions.survey(CurvedDepth.merged(posed), p, withBands = false)
        val islandsFound = CurvedRegions.shifted(r, p, p.layerMm, maxLayerMm)
        val tSurvey = (System.nanoTime() - t0) / 1e9
        // each cap with an island, found again as a plan's lock finds it (so the written plan reads back the same)
        val notCandidates = ArrayList<String>()
        val found = islandsFound.mapNotNull { (cap, x) ->
            val isl = x as? CurvedRegions.Island ?: return@mapNotNull null
            if (isl.followedMm2 < MIN_FOLLOWED_MM2) { notCandidates += "cap ${cap.id}: follows %.2f mm² (under %.2f)".format(Locale.ROOT, isl.followedMm2, MIN_FOLLOWED_MM2); return@mapNotNull null }
            val (sx, sy) = CurvedRegions.seedOf(r, cap)
            // from the start the survey found (resolve builds the island once, as a plan file's start does)
            val c = try { CurvedIsland.resolve(posed, listOf(CurvedIsland.Lock("cap${cap.id}", sx, sy, isl.startMm)), p, p.layerMm, maxLayerMm, survey = r).single() }
            catch (e: IllegalArgumentException) { notCandidates += "cap ${cap.id}: ${e.message}"; null }
            c?.let { isl -> refuse(isl)?.let { why -> notCandidates += "'${isl.key}': $why"; null } ?: isl }
        }
        // one column takes one warp: of candidates sharing ground, the one following more is kept
        val cands = ArrayList<CurvedIsland>()
        for (c in found.sortedByDescending { it.cells.size }) {
            val clash = cands.firstOrNull { o -> val s = o.cells.toHashSet(); c.cells.any { it in s } }
            if (clash == null) cands += c else notCandidates += "'${c.key}': shares ground with '${clash.key}'"
        }
        if (cands.isEmpty()) return Decision(step, flat, "no cap of the top gets a curved island (the survey: ${islandsFound.size} cap(s))",
            listOf("survey %.1f s".format(Locale.ROOT, tSurvey)) + notCandidates.take(10), standIns = standIns)
        // each alone against the flat plan: the stair-step it removes
        val alone = measure(listOf(flat) + cands.map { flat.withIslands(listOf(it)) })
        val baseStep = alone[0].surfaceStepMm ?: 0.0
        val gain = cands.indices.associate { i -> cands[i] to baseStep - (alone[i + 1].surfaceStepMm ?: baseStep) }
        // where each candidate's change comes from: the stair-step over its own sloped faces in flat layers and in its own, and
        // how much of the model's seen sloped area those faces are (the measure is a mean over all of it)
        val faces = cands.mapIndexedNotNull { i, isl ->
            val fl = alone[0].islandFaces[isl.key] ?: return@mapIndexedNotNull null; val il = alone[i + 1].islandFaces[isl.key] ?: return@mapIndexedNotNull null
            fun pct(a: Double, b: Double) = if (a > 0) "%+.0f %%".format(Locale.ROOT, 100 * (b / a - 1)) else "-"
            ("'%s': its own sloped faces %.1f mm² seen (%.1f %% of the model's seen sloped area): stair-step %.4f mm in flat layers, %.4f mm in its own (%s); " +
                "of them %.1f mm² along its layers: %.4f -> %.4f mm (%s); %.1f mm² facing up but not along them (the cap's surface they miss): %.4f -> %.4f mm (%s); " +
                "the other %.1f mm² (walls, undersides): %.4f -> %.4f mm (%s)")
                .format(Locale.ROOT, isl.key, fl.areaMm2, 100 * fl.share, fl.step, il.step, pct(fl.step, il.step),
                    fl.alongMm2, fl.stepAlong, il.stepAlong, pct(fl.stepAlong, il.stepAlong), fl.upMm2, fl.stepUp, il.stepUp, pct(fl.stepUp, il.stepUp),
                    fl.areaMm2 - fl.alongMm2 - fl.upMm2, fl.stepRest, il.stepRest, pct(fl.stepRest, il.stepRest))
        }
        val ranked = cands.filter { gain.getValue(it) > 1e-9 }.sortedByDescending { gain.getValue(it) }
        cands.filter { gain.getValue(it) <= 1e-9 }.forEach { notCandidates += "'${it.key}': removes no stair-step (the mean %+.6f mm; z %.2f..%.2f, own layers %.3f mm)".format(Locale.ROOT, -gain.getValue(it), it.startMm, it.topMm, it.layerMm) }
        if (ranked.isEmpty()) return Decision(step, flat, "no curved island removes any stair-step", faces + notCandidates.take(10), standIns = standIns)
        fun words(isl: CurvedIsland) = "'%s' (z %.2f..%.2f, %.3f mm layers, stair-step -%.4f mm)".format(Locale.ROOT, isl.key, isl.startMm, isl.topMm, isl.layerMm, gain.getValue(isl))
        if (look == Look.LINES_FOLLOW_CURVE) return Decision(step, flat.withIslands(ranked), "job.faces.sloped_top.look = lines_follow_curve: every candidate, unweighed: " + ranked.joinToString("; ") { words(it) },
            listOf("look: ${look.words}"), standIns = standIns, overridden = true)
        if (predict == null) return Decision(step, flat, "not weighed: the plan's full prediction is not available here (time unknown), so no curved island",
            ranked.map { "candidate ${words(it)}" } + faces, standIns = standIns)
        // the sets: none, the first, the first two, ...
        val sets = (0..minOf(MAX_WEIGHED, ranked.size)).map { n -> ranked.take(n) }
        val plans = sets.map { if (it.isEmpty()) flat else flat.withIslands(it) }
        val ms = measure(plans)
        val t1 = System.nanoTime()
        val preds = plans.map { predict(it) }
        val tPredict = (System.nanoTime() - t1) / 1e9
        val ok = sets.indices.filter { preds[it].timeS != null }
        fun setWords(i: Int) = if (sets[i].isEmpty()) "no curved island" else sets[i].joinToString(", ") { "'${it.key}'" }
        // each set that could not be predicted, with its own reason (the flat plan's was given, a success's words)
        val failed = sets.indices.filter { it !in ok }.joinToString("; ") { i -> "${setWords(i)}: ${preds[i].why}" }
        if (0 !in ok || ok.size < 2) return Decision(step, flat, "not weighed: the flat plan or every set could not be predicted ($failed), so no curved island",
            ranked.map { "candidate ${words(it)}" } + faces, standIns = standIns)
        val values = LinkedHashMap<Metric, (Int) -> Double>()
        values[Metric.SURFACE_STEP] = { i -> ms[i].surfaceStepMm ?: 0.0 }
        if (ms.all { it.layerLinesMm != null }) values[Metric.LAYER_LINES] = { i -> ms[i].layerLinesMm!! }
        if (ms.all { it.curvedBorderMm2 != null }) values[Metric.CURVED_BORDER] = { i -> Math.round(ms[i].curvedBorderMm2!! * 100) / 100.0 }
        values[Metric.TIME] = { i -> Math.round(preds[i].timeS!!).toDouble() }
        val fil = preds[0].filament?.first?.takeIf { m -> ok.all { preds[it].filament?.first == m } }
        if (fil != null) values[fil] = { i -> preds[i].filament!!.second }
        for (m in preds[0].measured.keys.filter { m -> ok.all { m in preds[it].measured } }) values[m] = { i -> Math.round(preds[i].measured.getValue(m) * 100) / 100.0 }
        val baseline = values.mapValues { (_, f) -> f(0) }
        val scored = Scoring.rank(ok, values, weights, emptyMap(), baseline)
        val win = scored.first().item
        val alts = scored.map { s -> val i = s.item
            Alternative(setWords(i), "%.4f: %s [stair-step %.4f mm, layer lines %s, border %s, %.0f s%s]".format(Locale.ROOT, s.total, Scoring.explain(s).removePrefix("score ").substringAfter(" ", ""),
                ms[i].surfaceStepMm ?: 0.0, ms[i].layerLinesMm?.let { "%.4f mm".format(Locale.ROOT, it) } ?: "-", ms[i].curvedBorderMm2?.let { "%.2f mm²".format(Locale.ROOT, it) } ?: "-",
                preds[i].timeS ?: 0.0, preds[i].measured[Metric.SEAM_SCAR]?.let { ", seam scar %.1f mm²".format(Locale.ROOT, it) } ?: ""), chosen = s === scored.first()) }
        val unmeasured = weights.unmeasured(values.keys).takeIf { it.isNotEmpty() }?.let { "; weights with no measure here: " + it.joinToString { g -> g.id } } ?: ""
        val facts = listOf("look: ${look.words}", "candidates (the most stair-step removed first): " + ranked.joinToString("; ") { words(it) },
            "time: survey %.1f s, %d set(s) sliced and predicted %.1f s".format(Locale.ROOT, tSurvey, sets.size, tPredict), "weights: $weights") + faces + notCandidates.take(10)
        return Decision(step, plans[win], "weighed (no island locked): ${setWords(win)} scored best by the job's weights (stair-step %.4f mm against %.4f flat, %.0f s against %.0f)$unmeasured"
            .format(Locale.ROOT, ms[win].surfaceStepMm ?: 0.0, ms[0].surfaceStepMm ?: 0.0, preds[win].timeS ?: 0.0, preds[0].timeS ?: 0.0), facts, alts, standIns)
    }
}
