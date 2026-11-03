package plymeld.core.decide

import plymeld.gcode.StringRisk
import java.util.Locale

/**
 * The in-island retraction decision: whether the wall-run travels
 * the planner keeps inside an island (plan.mechanism.motion.no_retract_in_island) go unretracted, decided per object
 * when it is sliced, where the setting is unset. Both sides are read off the object's own program: with the hidden
 * travels kept ([on]) and with them retracting like any other ([off]), each measured by [StringRisk]: the strings they
 * risk (`string_risk`) and the restarts they add (`restart_starved`) under quality, the retractions' time under speed.
 * The plainest candidate, every travel retracting, is the baseline.
 *
 * Not decided here (said in the stand-ins): the wipe and the hop, whose effect on a string no measure models yet; the
 * retraction's length; a visible loop entered from its inner wall.
 */
object RetractionDecision {
    /** The time one retraction costs (s): the pull and the push back, and the hop up and down at the travel speed (a stand-in for the Z speed). */
    fun retractS(retractMm: Double, retractSpeed: Double, deretractSpeed: Double, zHopMm: Double, travelSpeed: Double): Double =
        (if (retractSpeed > 0) retractMm / retractSpeed else 0.0) + (if (deretractSpeed > 0) retractMm / deretractSpeed else 0.0) + (if (travelSpeed > 0) 2 * zHopMm / travelSpeed else 0.0)

    /**
     * [on] / [off]: the program measured with the hidden travels kept / retracting; [retractS] one retraction's time;
     * [timeScaleS] the print's time the retractions' seconds are scored against (null: time not scored, said). [lock]: the
     * setting when set (with [lockSource]); null = weighed. The value: true = keep the hidden travels (no retraction in islands).
     */
    fun decide(name: String, on: StringRisk.Result, off: StringRisk.Result, retractS: Double, timeScaleS: Double?, timeScaleWhat: String,
               weights: Weights, lock: Boolean?, lockSource: String?): Decision<Boolean> {
        val step = "retraction in islands ($name)"
        val standIns = listOfNotNull(
            "restart_starved counts hardware.filament.retract.restart_lag per visible restart, a placeholder until a restart coupon measures it",
            "the wipe and the hop are not decided: no measure models what they remove from a string yet",
            if (timeScaleS == null) "the retractions' time is not scored: no print time to score it against" else "the print time the retractions are scored against is a quick estimate from the paths' lengths and speeds (no minimum layer time, no travel)")
        val facts = listOf(
            "hidden travels kept (no retraction in islands): " + on.words(),
            "every travel retracting: " + off.words(),
            "one retraction %.2f s (the pull, the push back, the hop up and down)".format(Locale.ROOT, retractS) + (timeScaleS?.let { "; scored against the print's %.0f s (%s)".format(Locale.ROOT, it, timeScaleWhat) } ?: ""),
            "weights: $weights")
        if (lock != null) return Decision(step, lock, "locked by ${lockSource ?: "plan.mechanism.motion.no_retract_in_island"}: ${if (lock) "the hidden travels kept" else "every travel retracts"}", facts, standIns = standIns, overridden = true)
        val values = LinkedHashMap<Metric, (Boolean) -> Double>()
        values[Metric.STRING_RISK] = { keep -> Math.round((if (keep) on else off).stringMm * 10) / 10.0 }
        values[Metric.RESTART_STARVED] = { keep -> Math.round((if (keep) on else off).restartStarvedMm * 10) / 10.0 }
        if (timeScaleS != null) values[Metric.TIME_ESTIMATE] = { keep -> Math.round(timeScaleS + (if (keep) on else off).retractions * retractS).toDouble() }
        val baseline = values.mapValues { (_, f) -> f(false) }
        // a baseline with no string at all: the travel's whole length as the scale; no restart at all: one restart's lag
        val zero = mapOf(Metric.STRING_RISK to maxOf(off.travelMm, 1.0), Metric.RESTART_STARVED to maxOf(off.restartStarvedMm, on.restartStarvedMm, 1.0))
        val ranked = Scoring.rank(listOf(false, true), values, weights, zero, baseline)
        val win = ranked.first()
        fun label(keep: Boolean) = if (keep) "hidden travels kept (no retraction in islands)" else "every travel retracts"
        val alts = ranked.map { s -> Alternative(label(s.item), "%.4f (%s)".format(Locale.ROOT, s.total, s.parts.entries.joinToString(", ") { (m, v) -> "%s %+.4f".format(Locale.ROOT, m.id, v) }), chosen = s === win) }
        val unmeasured = weights.unmeasured(values.keys).takeIf { it.isNotEmpty() }?.let { "; weights with no measure here: " + it.joinToString { g -> g.id } } ?: ""
        val chosen = if (win.item) on else off
        return Decision(step, win.item, "weighed (plan.mechanism.motion.no_retract_in_island unset): ${label(win.item)} scored best by the job's weights (string_risk %.0f mm, restart_starved %.0f mm, %d retractions against %.0f mm, %.0f mm, %d)$unmeasured"
            .format(Locale.ROOT, chosen.stringMm, chosen.restartStarvedMm, chosen.retractions, off.stringMm, off.restartStarvedMm, off.retractions), facts, alts, standIns)
    }
}
