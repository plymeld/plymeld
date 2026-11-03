package plymeld.config

import java.util.Locale

/**
 * Units in config values, in every obvious case. Every
 * quantity has a dimension and a base unit plymeld works in; a value may be
 * written in any unit of its dimension, with or without a space ("0.2 mm", "0.2mm"). A bare number
 * means the value's default unit and earns a warning: units are encouraged. Ratios take a plain
 * multiplier or a percentage ("0.15" or "15%": 15 per 100). Counts and weights are plain numbers.
 */
enum class Dimension(val base: String, val units: Map<String, Double>, val bareIsFine: Boolean = false) {
    LENGTH("mm", mapOf("mm" to 1.0, "cm" to 10.0, "m" to 1000.0, "um" to 0.001, "µm" to 0.001, "in" to 25.4)),
    AREA("mm2", mapOf("mm2" to 1.0, "mm^2" to 1.0, "mm²" to 1.0, "cm2" to 100.0, "cm^2" to 100.0, "cm²" to 100.0)),
    VOLUME("mm3", mapOf("mm3" to 1.0, "mm^3" to 1.0, "mm³" to 1.0, "cm3" to 1000.0, "cm^3" to 1000.0, "cm³" to 1000.0, "ml" to 1000.0)),
    TIME("s", mapOf("ms" to 0.001, "s" to 1.0, "min" to 60.0, "h" to 3600.0)),
    SPEED("mm/s", mapOf("mm/s" to 1.0, "mm/min" to 1.0 / 60, "m/s" to 1000.0)),
    ACCEL("mm/s2", mapOf("mm/s2" to 1.0, "mm/s^2" to 1.0, "mm/s²" to 1.0, "m/s2" to 1000.0, "m/s^2" to 1000.0, "m/s²" to 1000.0)),
    FLOW("mm3/s", mapOf("mm3/s" to 1.0, "mm^3/s" to 1.0, "mm³/s" to 1.0, "ml/min" to 1000.0 / 60)),
    ANGLE("deg", mapOf("deg" to 1.0, "°" to 1.0, "rad" to 180.0 / Math.PI)),
    /** Temperatures convert with an offset, not a factor ([Units.parse]). */
    TEMPERATURE("C", mapOf("C" to 1.0, "°C" to 1.0, "K" to 1.0, "F" to 1.0, "°F" to 1.0)),
    /** Mass (a measure in grams, e.g. a zero scale for support.mass). */
    MASS("g", mapOf("g" to 1.0, "mg" to 0.001, "kg" to 1000.0)),
    /** Mass per volume (a filament's density). */
    DENSITY("g/cm3", mapOf("g/cm3" to 1.0, "g/cm^3" to 1.0, "g/cm³" to 1.0, "g/ml" to 1.0, "kg/m3" to 0.001, "kg/m^3" to 0.001, "kg/m³" to 0.001)),
    /** A multiplier or a share: 0.15 or 15% (15 per 100); a fan's 40% is 0.4 of its full speed. */
    RATIO("", mapOf("%" to 0.01), bareIsFine = true),
    /** How many of something; no unit. */
    COUNT("", emptyMap(), bareIsFine = true),
    /** A relative importance (priorities); no unit. */
    WEIGHT("", emptyMap(), bareIsFine = true),
}

object Units {
    /** A value in its dimension's base unit, and a warning to show (null: none). */
    class Parsed(val value: Double, val warning: String?)

    private val NUMBER_UNIT = Regex("""^\s*([-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?)\s*(\S.*?)?\s*$""")

    /**
     * A point (Schema.Type.Point) as written: three numbers and one unit for all of them ("10 20 5 mm"), or the three alone
     * (a file's array: read as [dim]'s base unit, with [parse]'s warning). Each in the base unit; the warning, if any.
     */
    fun point(raw: Any, dim: Dimension, what: String): Pair<List<Double>, String?> {
        val parts = raw.toString().trim().split(Regex("\\s+"))
        val unit = parts.lastOrNull()?.takeIf { it.toDoubleOrNull() == null }
        val numbers = if (unit != null) parts.dropLast(1) else parts
        require(numbers.size == 3 && numbers.all { it.toDoubleOrNull() != null }) { "$what: expected a point, x y z and a unit (\"10 20 5 ${dim.base}\"), got '$raw'" }
        val each = numbers.map { parse(if (unit != null) "$it $unit" else it, dim, what) }
        return each.map { it.value } to each.firstNotNullOfOrNull { it.warning }
    }

    /**
     * [raw] as written: a number (from a TOML/JSON number) or a string ("0.2 mm", "15%", "210C").
     * [what] names the value in messages (its path and source).
     */
    fun parse(raw: Any, dim: Dimension, what: String): Parsed {
        val (number, unit) = when (raw) {
            is Number -> raw.toDouble() to null
            is String -> {
                val m = NUMBER_UNIT.matchEntire(raw) ?: throw IllegalArgumentException("$what: '$raw' is not a number with a unit")
                m.groupValues[1].toDouble() to m.groupValues[2].ifEmpty { null }
            }
            else -> throw IllegalArgumentException("$what: expected a number${if (dim.bareIsFine) "" else " with a unit (${dim.base})"}, got ${raw::class.simpleName} '$raw'")
        }
        if (unit == null) {
            if (dim == Dimension.COUNT && number != Math.floor(number)) throw IllegalArgumentException("$what: a count must be a whole number, got $number")
            return Parsed(number, if (dim.bareIsFine) null
                else "$what: ${fmt(number)} has no unit; read as ${fmt(number)} ${dim.base} (write the unit, e.g. \"${fmt(number)} ${dim.base}\")")
        }
        if (dim == Dimension.TEMPERATURE) return Parsed(when (unit) {
            "C", "°C" -> number
            "K" -> number - 273.15
            "F", "°F" -> (number - 32) * 5 / 9
            else -> throw IllegalArgumentException("$what: '$unit' is not a temperature unit (C, K, or F)")
        }, null)
        val factor = dim.units[unit] ?: throw IllegalArgumentException(
            "$what: '$unit' is not a unit of ${dim.name.lowercase().replace('_', ' ')}" + (if (dim.units.isEmpty()) " (it takes a plain number)" else " (${dim.units.keys.joinToString()})"))
        return Parsed(number * factor, null)
    }

    private fun fmt(v: Double) = if (v == Math.floor(v) && Math.abs(v) < 1e15) v.toLong().toString() else String.format(Locale.ROOT, "%s", v)
}
