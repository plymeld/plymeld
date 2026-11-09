package plymeld.core.decide

import plymeld.core.Feature
import plymeld.core.LayerPlanOut
import plymeld.core.Overhangs
import plymeld.core.Planner
import plymeld.core.SeamScar
import plymeld.core.Seams
import plymeld.core.ToolPath
import plymeld.geom.Polygon
import java.util.Locale

/**
 * The seam decision: where the wall loops start and which outer
 * walls get a scarf seam, chosen per object by the job's weights on the object's own walls, where `plan.seam` locks
 * nothing. Candidates: the placement ([Placement]: the back-most point, or a corner when the loop has one) x scarf seams
 * on none, the smooth loops, or all outer walls. Each candidate is cheap: the walls are placed by [Seams] exactly as the
 * Planner will place them, and measured by [SeamScar] (quality) and the scarf's extra pass (speed: its length again, at
 * the visible speed). Scored against the plainest candidate (back, no scarf) as the baseline.
 *
 * Not yet (said in the stand-ins): a placement by visibility among the held-up points (the map has nothing yet that
 * tells those apart: covered faces and recesses are not in it), the scarf's length as a range, fuzzy skin's veto of a
 * scarf, layers following the surfaces (measured on flat layers of the plan's height; every candidate alike).
 */
object SeamDecision {
    /** Where the loops start: [start] is plan.seam's value. */
    enum class Placement(val start: String, val label: String) {
        BACK("back", "at the loop's back-most point, in line with the layer below"),
        CORNERS("corners", "at a corner when the loop has one (inside corners first), in line with the layer below; else the back"),
        /** the held-up point the visibility map counts least (seen from the less-weighted quadrants, hidden nearby, an underside), ties to the seam below, then the back. */
        LEAST_VISIBLE("least_visible", "at the held-up point seen least (its viewing directions weighed by the quadrant they look from, hidden by nearby geometry, the object's other parts included, on an underside), in line with the layer below; else the back");
    }

    /** One candidate: the placement, and scarf seams on "none", "smooth" (loops whose seam lands off a corner), or "all" outer walls. */
    class Choice(val placement: Placement, val scarf: String,
                 /** Whether the job locked the placement (plan.seam) and scarf seams (plan.technique.scarf_seam.enabled): what was not locked was weighed. */
                 val placementLocked: Boolean = false, val scarfLocked: Boolean = false) {
        fun words(): String = (if (scarf == "none") "no scarf seams" else "scarf seams on ${if (scarf == "all") "every outer wall" else "the outer walls whose seam lands off a corner"}") +
            "; the loops' seams ${placement.label}"
        override fun toString() = "${placement.start}/$scarf"
    }

    /** An object's walls as the Planner's walls stage lays them: per layer, per wall (outer first), the loops; the printed area per layer; the layer tops (µm). */
    class Walls(val loops: List<List<List<Polygon>>>, val printed: List<List<Polygon>>, val layerTopsUm: LongArray,
                val outerWidthUm: Long, val innerWidthUm: Long, val firstWidthUm: Long)

    /**
     * What the candidates read: the corner angle (null: no corner seam possible), the scarf's length and start (null: no
     * scarf possible), the visible speed (null: the scarf's time not scored), the visibility map and the measure's shares, and
     * the print's time the scarf's extra seconds are scored against (null: not scored, said).
     */
    class Params(val cornerDeg: Double?, val scarfLengthUm: Long?, val scarfStart: Double?, val visibleSpeedMmS: Double?,
                 val visibility: Visibility?, val shares: SeamScar.Shares, val timeScaleS: Double?, val timeScaleWhat: String = "")

    class Measured(val choice: Choice, val scar: SeamScar.Result, val scarfExtraS: Double)

    /** The candidates [p] allows, the baseline (back, no scarf) first. */
    fun candidates(p: Params): List<Choice> {
        val scarfs = listOf("none") + (if (p.scarfLengthUm != null && p.scarfLengthUm > 0 && p.scarfStart != null) listOf("smooth", "all") else emptyList())
        // least_visible whenever there is a map: what hides a point nearby tells points apart even with no view and undersides
        // counted in full (the default; it had been offered only with a view or undersides below 1)
        val placements = listOf(Placement.BACK) + (if (p.cornerDeg != null) listOf(Placement.CORNERS) else emptyList()) +
            (if (p.visibility != null) listOf(Placement.LEAST_VISIBLE) else emptyList())
        return scarfs.flatMap { sc -> placements.map { Choice(it, sc) } }
    }

    /**
     * Which wall-loop points hang over the layer below, per layer (Overhangs.seamOverhangs): the same for every candidate,
     * computed once, the layers in parallel as the Planner computes them (on one thread it took a 30-part
     * model's 805 layers 20 minutes).
     */
    fun overhangs(w: Walls): List<List<List<BooleanArray>>?> {
        val hs = Planner.heights(w.layerTopsUm)
        val out = arrayOfNulls<List<List<BooleanArray>>>(w.loops.size)
        java.util.stream.IntStream.range(1, w.loops.size).parallel().forEach { i -> out[i] = Overhangs.seamOverhangs(w.loops[i], w.printed[i - 1], w.outerWidthUm, hs[i]) }
        return out.toList()
    }

    /** The outer-wall loops of each layer prepared for the seam measure (SeamScar.Below): the same for every candidate (a seam only rotates a loop), computed once, in parallel. */
    fun belows(w: Walls, p: Params): List<SeamScar.Below?>? {
        if (p.visibility == null) return null
        val out = arrayOfNulls<SeamScar.Below>(w.loops.size)
        java.util.stream.IntStream.range(0, w.loops.size).parallel().forEach { i -> out[i] = SeamScar.Below(w.loops[i].getOrNull(0).orEmpty().filter { it.size >= 3 }) }
        return out.toList()
    }

    /** [c] placed on [w] as the Planner would, measured; [over] from [overhangs] and [belows] from [belows] (computed here when not given). */
    /** What hides a point nearby on [w]'s outer walls ([Exposure]): the same for every candidate (a seam only rotates a loop), made once; null without sampling. */
    fun exposure(w: Walls, p: Params): Exposure? = p.visibility?.exposure(w.loops.map { it.getOrNull(0).orEmpty() }, w.layerTopsUm)

    fun measure(w: Walls, p: Params, c: Choice, over: List<List<List<BooleanArray>>?> = overhangs(w), belows: List<SeamScar.Below?>? = belows(w, p),
                ex: Exposure? = exposure(w, p)): Measured {
        val hs = Planner.heights(w.layerTopsUm)
        val n = w.loops.size
        // the shelf a seam sits on, from the layer below's outer walls (as seam_scar reads it), as the Planner reads it
        val reach = SeamScar.shelfReachMm(p.visibility)
        val halfMm = w.outerWidthUm / 2000.0
        fun shelfAt(i: Int): ((Long, Long) -> Double)? {
            val b = belows?.getOrNull(i - 1) ?: return null
            if (i == 0 || reach <= 0) return null
            return { x, y -> SeamScar.shelfMm(x, y, b, halfMm, reach) }
        }
        val placed = Seams.alignAll(w.loops, if (c.placement == Placement.BACK) null else p.cornerDeg, over, back = c.placement == Placement.BACK,
            spanUm = { i, k -> 2.0 * (if (i == 0) w.firstWidthUm else if (k == 0) w.outerWidthUm else w.innerWidthUm) },
            leastVisible = c.placement == Placement.LEAST_VISIBLE, vis = p.visibility, cornerShare = p.shares.corner, exposure = ex,
            shelfOf = { i -> shelfAt(i) })
        var extraUm = 0L
        val layers = (0 until n).map { i ->
            val width = if (i == 0) w.firstWidthUm else w.outerWidthUm
            val shelf = shelfAt(i)
            val paths = placed[i].getOrNull(0).orEmpty().map { pl ->
                val wanted = if (i > 0 && c.scarf != "none" && p.scarfLengthUm != null && p.scarfStart != null && (c.scarf == "all" || !pl.atCorner)) p.scarfLengthUm else 0L
                // the per-loop scarf rule, as the Planner applies it (SeamScar.scarfPays)
                val scarf = if (wanted > 0 && shelf != null && !SeamScar.scarfPays(width / 1000.0, shelf(pl.loop.x(0), pl.loop.y(0)), p.shares.scarf, wanted / 1000.0)) 0L else wanted
                extraUm += scarf
                ToolPath(Feature.OUTER_WALL, pl.loop.xy, true, width, hs[i], scarfUm = scarf, scarfStart = if (scarf > 0) p.scarfStart!! else 0.0)
            }
            LayerPlanOut(i, w.layerTopsUm[i], hs[i], paths)
        }
        val extraS = p.visibleSpeedMmS?.takeIf { it > 0 }?.let { extraUm / 1000.0 / it } ?: 0.0
        return Measured(c, SeamScar.measure(layers, p.visibility, p.shares, belows, ex), extraS)
    }

    /** A lock: its value (plan.seam's placement, or scarf seams' where: "none" when off) and where it comes from. */
    class Lock(val value: String, val source: String)

    /**
     * The decision for object [name]. [placementLock]: where the loops start when locked (plan.seam); [scarfLock]: scarf
     * seams when locked (plan.technique.scarf_seam.enabled: their where, "none" when off). What is locked is kept, the rest
     * weighed among the candidates the locks leave; both locked, nothing is weighed.
     */
    fun decide(name: String, w: Walls, p: Params, weights: Weights, placementLock: Lock?, scarfLock: Lock?,
               /** Seconds the caller spent slicing and walling the object for this decision (said in a fact beside the decision's own times). */
               prepS: Double? = null): Decision<Choice> {
        val step = "seams ($name)"
        val standIns = listOfNotNull(
            "seam_scar's shares come from one seam plate's grades (whole steps), on one filament unless this config's are its own: a scarf seam %s of a point seam's blemish (method.quality.seam.scarf_share), a point seam in a corner %s (method.quality.seam.corner_share)".format(Locale.ROOT, fmt(p.shares.scarf), fmt(p.shares.corner)),
            "measured on flat layers of the plan's height (a plan following the surfaces has more or fewer layers: every candidate alike); fuzzy skin's veto of a scarf not applied here",
            if (p.timeScaleS == null) "the scarf's extra pass is not scored: no print time to score it against" else null,
            p.visibility?.sample?.let { "least_visible weighs what hides a point nearby (the object's own geometry) from ${it.words()}: placeholders" })
        val lockedPlacement = placementLock?.let { l -> Placement.entries.firstOrNull { it.start == l.value } ?: Placement.CORNERS }
        val lockWords = listOfNotNull(placementLock?.let { "plan.seam = ${it.value} (${it.source})" },
            scarfLock?.let { "scarf seams ${if (it.value == "none") "off" else "on ${it.value}"} (${it.source})" }).joinToString("; ")
        val cands = candidates(p).filter { c -> (lockedPlacement == null || c.placement == lockedPlacement) && (scarfLock == null || c.scarf == scarfLock.value) }
        if ((placementLock != null && scarfLock != null) || cands.isEmpty()) {
            // locked whole (or nothing left to weigh: the locks name a candidate these values cannot make, kept as locked)
            val c = Choice(lockedPlacement ?: Placement.CORNERS, scarfLock?.value ?: "none", placementLock != null, scarfLock != null)
            return Decision(step, c, "locked: $lockWords", listOf("the seams are locked here; nothing weighed"), standIns = standIns, overridden = true)
        }
        // where the time goes (a 30-part model's plan spent 500 s here with nothing saying so): the overhangs, the layer
        // preparation, each candidate's measure
        val t0 = System.nanoTime()
        val over = overhangs(w)
        val t1 = System.nanoTime()
        val bel = belows(w, p)
        val ex = exposure(w, p)
        val t2 = System.nanoTime()
        val candS = ArrayList<String>()
        // the baseline (back, no scarf) is measured whatever the locks leave: the scores are against it
        val base = candidates(p).first()
        val measured = (cands + base).distinct().associateWith { c -> val tc = System.nanoTime(); measure(w, p, c, over, bel, ex).also { candS += "%s %.1f s".format(Locale.ROOT, c, (System.nanoTime() - tc) / 1e9) } }
        val timeFact = "time: %sthe overhangs %.1f s, the layers below prepared %.1f s, %d candidates measured %.1f s (%s)".format(Locale.ROOT,
            prepS?.let { "the object sliced and walled %.1f s, ".format(Locale.ROOT, it) } ?: "", (t1 - t0) / 1e9, (t2 - t1) / 1e9, cands.size, (System.nanoTime() - t2) / 1e9, candS.joinToString())
        val values = LinkedHashMap<Metric, (Choice) -> Double>()
        values[Metric.SEAM_SCAR] = { c -> Math.round(measured.getValue(c).scar.scarMm2 * 1000) / 1000.0 }
        if (p.timeScaleS != null) values[Metric.TIME_ESTIMATE] = { c -> Math.round(p.timeScaleS + measured.getValue(c).scarfExtraS).toDouble() }
        val baseline = values.mapValues { (_, f) -> f(base) }
        // a baseline with no seams at all (no closed outer wall): one point seam per layer as the scale
        val zero = mapOf(Metric.SEAM_SCAR to (w.outerWidthUm / 1000.0).let { it * it } * maxOf(1, w.loops.size))
        val ranked = Scoring.rank(cands, values, weights, zero, baseline)
        val win = ranked.first().let { s -> s.item.let { Choice(it.placement, it.scarf, placementLock != null, scarfLock != null) } }
        val winScored = ranked.first()
        val facts = listOf(
            "${w.loops.size} layers; the baseline (back, no scarf): ${measured.getValue(base).scar.words()}",
            "visibility: " + (p.visibility?.words() ?: "not known, every seam counts alike"),
            (p.timeScaleS?.let { "the scarf's extra pass (its length again at %.0f mm/s) scored as time against the print's %.0f s (%s)".format(Locale.ROOT, p.visibleSpeedMmS ?: 0.0, it, p.timeScaleWhat) } ?: "the scarf's extra pass not scored (no print time known)"),
            "candidates: " + cands.joinToString { it.toString() } + (if (p.cornerDeg == null) " (no corner seam: plan.technique.corner_seam.corner_angle unset)" else "") +
                (if (p.scarfLengthUm == null || p.scarfStart == null) " (no scarf seam: plan.technique.scarf_seam.length or .start unset)" else ""),
            "weights: $weights", timeFact)
        val alts = ranked.map { s -> val m = measured.getValue(s.item)
            Alternative(s.item.toString(), "%.4f: %s%s".format(Locale.ROOT, s.total, Scoring.explain(s).removePrefix("score ").substringAfter(" ", "").ifEmpty { "" },
                " [seam scar %.2f mm², %d point + %d scarf seams, jogs %.1f mm, scarf pass +%.0f s]".format(Locale.ROOT, m.scar.scarMm2, m.scar.pointSeams, m.scar.scarfSeams, m.scar.jogMm, m.scarfExtraS)), chosen = s === winScored) }
        val unmeasured = weights.unmeasured(values.keys).takeIf { it.isNotEmpty() }?.let { "; weights with no measure here: " + it.joinToString { g -> g.id } } ?: ""
        val wm = measured.getValue(winScored.item)
        return Decision(step, win, "weighed (${if (lockWords.isEmpty()) "no seam lock" else "locked: $lockWords; the rest"}): ${win.words()} scored best by the job's weights (seam_scar %.2f mm² against the baseline's %.2f%s)$unmeasured"
            .format(Locale.ROOT, wm.scar.scarMm2, measured.getValue(base).scar.scarMm2, if (p.timeScaleS != null) ", the scarf's pass +%.0f s".format(Locale.ROOT, wm.scarfExtraS) else ""),
            facts, alts, standIns)
    }

    private fun fmt(v: Double) = if (v == Math.floor(v)) v.toLong().toString() else "%.2f".format(Locale.ROOT, v)
}
