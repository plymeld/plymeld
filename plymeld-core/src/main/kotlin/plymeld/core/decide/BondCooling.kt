package plymeld.core.decide

import java.util.Locale

/**
 * Bond-first cooling. An
 * object's layers bond less the harder they are cooled and the colder the nozzle: the filament's cooling (its part fan,
 * its minimum layer time) is set for surfaces, not for the bond. For each object the plan weighs two ways of laying it:
 * the filament's own cooling, or bond-first (plan.technique.bond_cooling: a lower part fan, a shorter minimum layer time, a
 * hotter nozzle), scored with the job's own weights on two measures:
 *
 * - **bond.strength** (strength): the weakest bond between layers as laid, its area (bond.area, the same either way)
 *   times [factor], capped at method.strength.bond.enough_area: a bond already that strong gains nothing, so an object
 *   with no thin section keeps the filament's cooling whatever the weights (without the cap a 10 x 10 mm block was given
 *   bond-first under equal weights). An unmeasured bond counts as enough.
 * - **cooling_shortfall** (quality): the side area printed with less cooling than the filament asks, times the share given
 *   up ([shortfallMm2]); 0 with the filament's own cooling, scored against the model's surface area.
 *
 * With no weight on strength, bond-first never wins (it costs quality and gains nothing counted). A lock
 * (plan.bond_cooling = on / off, for every object or one) decides without weighing. The bond factor's coefficients
 * (method.strength.bond.*) and bond-first's values are PLACEHOLDERS until measured (the helix-layer coupon).
 */
object BondCooling {
    /** How an object's layers are laid for the bond: the part fan (% of full), the nozzle after the first layer (C), the minimum layer time (s). */
    data class Conditions(val fanPct: Double, val nozzleC: Double, val minLayerTimeS: Double) {
        fun words() = "part fan %s %%, nozzle %s C, minimum layer time %s s".format(Locale.ROOT, fmt(fanPct), fmt(nozzleC), fmt(minLayerTimeS))
    }

    /** The bond factor's coefficients and the bond counted as strong enough (method.strength.bond.*): placeholders until measured. */
    class Model(val fanLoss: Double, val temperatureGain: Double, val coolingLoss: Double, val coolingTimeS: Double, val enoughAreaMm2: Double) {
        fun words() = "fan_loss %s, temperature_gain %s per 10 C, cooling_loss %s at %s s".format(Locale.ROOT, fmt(fanLoss), fmt(temperatureGain), fmt(coolingLoss), fmt(coolingTimeS))
    }

    /**
     * The bond factor of layers laid under [c] (a share of the bond the same area could have; above 0), the filament's print
     * temperature [printC] its reference: (1 - fan_loss x fan share) x (1 + temperature_gain x (nozzle - print) / 10 C) x
     * (1 - cooling_loss x min(1, minimum layer time / cooling_time)). The layer below cools at least the minimum layer time
     * (a layer that takes longer cools longer: not estimated here, the same for both ways of laying it).
     */
    fun factor(c: Conditions, printC: Double, m: Model): Double {
        val fan = 1 - m.fanLoss * (c.fanPct / 100).coerceIn(0.0, 1.0)
        val temp = 1 + m.temperatureGain * (c.nozzleC - printC) / 10
        val cool = 1 - m.coolingLoss * if (m.coolingTimeS > 0) Math.min(1.0, Math.max(0.0, c.minLayerTimeS) / m.coolingTimeS) else 1.0
        return Math.max(1e-6, fan * temp * cool)
    }

    /**
     * The side area printed with less cooling than [filament] asks when laid under [c] (mm²): [sideMm2] (the object's non-flat
     * faces) times the larger of the part fan's and the minimum layer time's shortfall, each a share of the filament's.
     */
    fun shortfallMm2(sideMm2: Double, filament: Conditions, c: Conditions): Double {
        val fan = if (filament.fanPct > 0) Math.max(0.0, 1 - c.fanPct / filament.fanPct) else 0.0
        val time = if (filament.minLayerTimeS > 0) Math.max(0.0, 1 - c.minLayerTimeS / filament.minLayerTimeS) else 0.0
        return sideMm2 * Math.max(fan, time)
    }

    /** What was chosen for one object: bond-first or not, and the conditions its layers get. */
    class Choice(val on: Boolean, val conditions: Conditions) {
        override fun toString() = (if (on) "bond-first cooling: " else "the filament's cooling: ") + conditions.words()
    }

    /**
     * The decision for object [name]: [filament] (its filament's cooling and print temperature) against [bond]
     * (plan.technique.bond_cooling's values), the filament's print temperature [printC], the bond [model], the object's
     * [weights], its weakest bond [bondAreaMm2] (null: not measured, counted as enough), its side area
     * [sideMm2] and surface area [surfaceMm2] (cooling_shortfall's scale). [lock]: on / off from plan.bond_cooling, with
     * [lockSource]; null = weighed.
     */
    fun decide(name: String, filament: Conditions, bond: Conditions, printC: Double, model: Model, weights: Weights,
               bondAreaMm2: Double?, sideMm2: Double, surfaceMm2: Double, lock: Boolean?, lockSource: String?): Decision<Choice> {
        val step = "bond-first cooling ($name)"
        // unmeasured: counted as enough either way (bond.strength the same for both)
        fun bondOf(c: Conditions) = bondAreaMm2?.let { strength(it, c, printC, model) } ?: model.enoughAreaMm2
        fun conditions(on: Boolean) = if (on) bond else filament
        val facts = listOf(
            "the filament's cooling: " + filament.words(),
            "bond-first cooling (plan.technique.bond_cooling, placeholders): " + bond.words(),
            "bond factor: " + model.words() + " (method.strength.bond.*, placeholders until measured): the filament's %.3f, bond-first %.3f".format(Locale.ROOT, factor(filament, printC, model), factor(bond, printC, model)),
            (bondAreaMm2?.let { "the weakest bond between layers %.1f mm² (bond.area, as placed)".format(Locale.ROOT, it) } ?: "the weakest bond not measured: counted as enough") +
                "; strong enough at %s mm² (method.strength.bond.enough_area, a placeholder): bond.strength the filament's %.1f, bond-first %.1f".format(Locale.ROOT,
                    fmt(model.enoughAreaMm2), bondOf(filament), bondOf(bond)),
            "side area %.0f mm² of %.0f mm² surface (cooling_shortfall's scale)".format(Locale.ROOT, sideMm2, surfaceMm2))
        val standIns = listOf("bond.strength and cooling_shortfall are stand-ins: the bond factor's coefficients and bond-first's values are placeholders until the helix-layer coupon measures them")
        if (lock != null) return Decision(step, Choice(lock, conditions(lock)), "locked " + (if (lock) "on" else "off") + " by ${lockSource ?: "plan.bond_cooling"}: ${Choice(lock, conditions(lock))}",
            facts, standIns = standIns, overridden = true)
        val values = mapOf<Metric, (Boolean) -> Double>(
            Metric.BOND_STRENGTH to { on -> bondOf(conditions(on)) },
            Metric.COOLING_SHORTFALL to { on -> shortfallMm2(sideMm2, filament, conditions(on)) })
        val baseline = values.mapValues { (_, f) -> f(false) }
        val ranked = Scoring.rank(listOf(false, true), values, weights, zeroScale = mapOf(Metric.COOLING_SHORTFALL to surfaceMm2), baseline = baseline)
        val win = ranked.first()
        fun label(on: Boolean) = if (on) "bond-first cooling" else "the filament's cooling"
        val alts = ranked.map { s -> Alternative(label(s.item), "%.4f (%s)".format(Locale.ROOT, s.total, s.parts.entries.joinToString(", ") { (m, v) -> "%s %+.4f".format(Locale.ROOT, m.id, v) }), chosen = s === win) }
        val unmeasured = weights.unmeasured(values.keys).takeIf { it.isNotEmpty() }?.let { "; weights with no measure here: " + it.joinToString { g -> g.id } } ?: ""
        return Decision(step, Choice(win.item, conditions(win.item)),
            "weighed (no plan.bond_cooling lock): ${label(win.item)} scored best by the job's weights (${weights}): ${Choice(win.item, conditions(win.item))}$unmeasured",
            facts, alts, standIns)
    }

    /** bond.strength: the bond [areaMm2] laid under [c], [factor] times the area, capped at the model's enough_area (above 0). */
    fun strength(areaMm2: Double, c: Conditions, printC: Double, m: Model): Double = Math.max(1e-6, Math.min(areaMm2 * factor(c, printC, m), m.enoughAreaMm2))

    private fun fmt(v: Double) = if (v == Math.floor(v) && Math.abs(v) < 1e9) v.toLong().toString() else "%.2f".format(Locale.ROOT, v)
}
