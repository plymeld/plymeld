package plymeld.config

import java.util.Locale

/**
 * The first-layer window (`hardware.filament.<key>.first_layer.window.<plate>`): a first-layer plate's results, recorded
 * on the filament as MEASURED DATA per build plate (adhesion depends on the surface, as the filament's
 * `bed.<plate>.first_layer` does). Per nozzle temperature, two values read off the plate: the lowest nozzle height graded
 * good (every lower one is bad) and the fastest speed graded good. plymeld takes the first layer's height and speed from
 * it where a print is set up ([decide]).
 *
 * ```
 * [hardware.filament.white.first_layer.window.c5-plate]
 * from = "firstlayer_white.gcode, tool 1, build plate c5-plate"
 * bore = "0.4 mm"
 * "210 C" = { height.min = "0.28 mm", speed.max = "120 mm/s" }
 * "220 C" = { height.min = "0.24 mm", speed.max = "120 mm/s" }
 * ```
 *
 * `plymeld calibrate first-layer values` writes it from the plate's readings. A temperature whose column had no good tile
 * is left out: the window does not cover it.
 */
object FirstLayerWindow {
    /** One temperature's readings: the lowest good height (mm) and the fastest good speed (mm/s); [label] says where they came from. */
    class Reading(val tempC: Double, val heightMinMm: Double, val speedMaxMmS: Double, val label: String)

    /** One recorded window: a filament on a build plate, measured with a nozzle of [boreMm]; its readings by temperature, coolest first. */
    class Window(val filament: String, val plate: String, val boreMm: Double, val from: String?, val readings: List<Reading>, val key: String, val source: String)

    /** What [decide] chose: the first layer's height (mm) and speed (mm/s), and why, in words. */
    class Choice(val heightMm: Double, val speedMmS: Double, val why: String)

    /** A window's own values: `from` and `bore` (7 segments), and each temperature's `height.min` and `speed.max` (9). */
    fun isWindowPath(p: List<String>): Boolean = p.size >= 7 && p[0] == "hardware" && p[1] == "filament" && p[3] == "first_layer" && p[4] == "window"

    // ---- reading

    /**
     * Every window in [values] (a config's, `like` taken over included), keyed filament then plate; each problem (a
     * temperature without both readings, an unknown key, a missing bore, a plate the config does not have) into
     * [problems]. [plates]: the config's build plates. The units are checked with every other value's (Config.check).
     */
    fun read(values: Collection<ConfigFiles.Entry>, plates: Set<String>, problems: MutableList<String>): Map<String, Map<String, Window>> {
        val out = LinkedHashMap<String, LinkedHashMap<String, Window>>()
        for ((fp, es) in values.filter { isWindowPath(it.path) }.groupBy { it.path[2] to it.path[5] }) {
            val (f, plate) = fp
            val key = ConfigFiles.pathText(listOf("hardware", "filament", f, "first_layer", "window", plate))
            val n = problems.size
            if (plate !in plates) problems += "$key (${es.first().source}): there is no build plate '$plate' (hardware.plate.<key>: ${plates.sorted().joinToString()})"
            val boreE = es.firstOrNull { it.path.size == 7 && it.path[6] == "bore" }
            val bore = if (boreE == null) { problems += "$key (${es.first().source}): no bore: the nozzle bore the window was measured with (bore = \"0.4 mm\"); a window applies only to that bore"; null }
                else quantity(boreE, Dimension.LENGTH)
            val from = es.firstOrNull { it.path.size == 7 && it.path[6] == "from" }?.value as? String
            val readings = ArrayList<Reading>()
            for ((name, te) in es.filter { !(it.path.size == 7 && (it.path[6] == "bore" || it.path[6] == "from")) }.groupBy { it.path[6] }) {
                val what = "$key.\"$name\" (${te.first().source})"
                val t = runCatching { Units.parse(name, Dimension.TEMPERATURE, what) }.getOrNull()
                if (t == null || t.warning != null) { problems += "$what: '$name' is not a temperature with its unit (a window holds `from`, `bore`, and one entry per nozzle temperature, e.g. \"210 C\" = { height.min = \"0.28 mm\", speed.max = \"120 mm/s\" })"; continue }
                val sub = te.associateBy { it.path.drop(7).joinToString(".") }
                (sub.keys - setOf("height.min", "speed.max")).forEach { problems += "$what: '$it' is not a reading (a temperature's are height.min and speed.max)" }
                val h = sub["height.min"]; val s = sub["speed.max"]
                if (h == null || s == null) { problems += "$what: ${listOfNotNull(if (h == null) "height.min" else null, if (s == null) "speed.max" else null).joinToString(" and ")} missing: a temperature gives both, the lowest good height and the fastest good speed"; continue }
                val hv = quantity(h, Dimension.LENGTH) ?: continue
                val sv = quantity(s, Dimension.SPEED) ?: continue
                if (hv <= 0 || sv <= 0) { problems += "$what: its readings are above 0"; continue }
                readings += Reading(t.value, hv, sv, "its ${fmt(t.value)} C readings")
            }
            if (readings.isEmpty() && problems.size == n) problems += "$key (${es.first().source}): no readings: a window holds one per nozzle temperature (\"210 C\" = { height.min = \"0.28 mm\", speed.max = \"120 mm/s\" })"
            if (problems.size == n && bore != null) out.getOrPut(f) { LinkedHashMap() }[plate] = Window(f, plate, bore, from, readings.sortedBy { it.tempC }, key, es.first().source)
        }
        return out
    }

    /** [e]'s value as a [dim] quantity; null when it does not parse (reported with every other value's units, Config.check). */
    private fun quantity(e: ConfigFiles.Entry, dim: Dimension): Double? =
        runCatching { Units.parse(e.value.toString(), dim, "${e.key} (${e.source})") }.getOrNull()?.takeIf { it.warning == null }?.value

    // ---- using

    /**
     * The readings at [tempC] (the first layer's nozzle temperature): its own if measured there; between two measured
     * temperatures, the higher of their lowest good heights and the slower of their fastest good speeds (good only where
     * both are); outside the measured range, null (no window there).
     */
    fun at(w: Window, tempC: Double): Reading? {
        w.readings.firstOrNull { Math.abs(it.tempC - tempC) < 1e-6 }?.let { return it }
        val lo = w.readings.lastOrNull { it.tempC < tempC } ?: return null
        val hi = w.readings.firstOrNull { it.tempC > tempC } ?: return null
        return Reading(tempC, maxOf(lo.heightMinMm, hi.heightMinMm), minOf(lo.speedMaxMmS, hi.speedMaxMmS),
            "between its ${fmt(lo.tempC)} C and ${fmt(hi.tempC)} C readings (good only where good at both)")
    }

    /**
     * The first layer's height from [r]: its lowest good height, raised to [minLayerMm] when that is higher (the tool's
     * `layer.height.min`; every height above the lowest good one is good); null when it is above [maxLayerMm] (the tool's
     * `first_layer.height.max`, else `layer.height.max`): no good height the tool lays. [rangeFrom] names them.
     */
    private fun height(r: Reading, minLayerMm: Double?, maxLayerMm: Double?, rangeFrom: String): Pair<Double, String>? {
        if (maxLayerMm != null && r.heightMinMm > maxLayerMm + 1e-9) return null
        if (minLayerMm != null && r.heightMinMm < minLayerMm - 1e-9) return minLayerMm to "${mm(minLayerMm)} (the lowest good height, ${mm(r.heightMinMm)}, raised to the lowest the tool lays, $rangeFrom)"
        return r.heightMinMm to "${mm(r.heightMinMm)} (the lowest good height)"
    }

    /** The first layer from [r]: the lowest good height the tool lays ([height]) at the fastest good speed. Null when no good height is one the tool lays. */
    fun decide(r: Reading, minLayerMm: Double?, maxLayerMm: Double?, rangeFrom: String = "its layer.height.min and layer.height.max"): Choice? {
        val (h, hw) = height(r, minLayerMm, maxLayerMm, rangeFrom) ?: return null
        return Choice(h, r.speedMaxMmS, "from ${r.label}: $hw at ${mms(r.speedMaxMmS)} (the fastest good speed). Rule: plymeld's choice; the melt cap still applies")
    }

    /**
     * The first layer at a speed the user set for this plate ([speedMmS], hardware.filament.<key>.bed.<plate>.first_layer.speed):
     * the window's height at that speed. Null when the speed is past the window's fastest good one, or no good height is
     * one the tool lays.
     */
    fun decideAt(r: Reading, speedMmS: Double, minLayerMm: Double?, maxLayerMm: Double?, rangeFrom: String = "its layer.height.min and layer.height.max"): Choice? {
        if (speedMmS > r.speedMaxMmS + 1e-9) return null
        val (h, hw) = height(r, minLayerMm, maxLayerMm, rangeFrom) ?: return null
        return Choice(h, speedMmS, "from ${r.label}: the speed set for this plate, ${mms(speedMmS)}, within the fastest good one, ${mms(r.speedMaxMmS)}; $hw")
    }

    fun mm(v: Double) = "${fixed(v)} mm"
    fun mms(v: Double) = "${num(v)} mm/s"
    /** At least two decimals (0.20), more where the value has them (0.125). */
    private fun fixed(v: Double): String { val s = String.format(Locale.ROOT, "%.6f", v).trimEnd('0'); val d = s.substringAfter('.', ""); return if (d.length >= 2) s else String.format(Locale.ROOT, "%.2f", v) }
    private fun num(v: Double) = if (v == Math.floor(v) && Math.abs(v) < 1e15) v.toLong().toString() else String.format(Locale.ROOT, "%.6f", v).trimEnd('0').trimEnd('.')
    private fun fmt(v: Double) = num(v)
}
