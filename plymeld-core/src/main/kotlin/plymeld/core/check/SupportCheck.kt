package plymeld.core.check

/**
 * The pre-print check of the plan's support: every support spot the plan has (chosen by the search, locked
 * by the plan, or by the user) must have support printed in it. Found the day a plan said "support under 1 spot" for a
 * flat underside and the sliced file held no support at all (a spot of no height matched no layer, Supports.Box.spans),
 * while the model check passed: it judges the model's lines against the model, never support against the plan.
 */
object SupportCheck {
    /** One planned spot: its name and its footprint on the bed (mm). */
    class Spot(val name: String, val x0: Double, val x1: Double, val y0: Double, val y1: Double)

    /** The spots of [spots] in which no support line is extruded in [text]: a problem each. */
    fun check(text: String, spots: List<Spot>): List<String> {
        if (spots.isEmpty()) return emptyList()
        val seen = BooleanArray(spots.size)
        var feature = ""; var x = Double.NaN; var y = Double.NaN
        for (raw in text.lineSequence()) {
            val l = raw.trim()
            if (l.startsWith(";TYPE:")) { feature = l.removePrefix(";TYPE:").trim(); continue }
            if (l.startsWith("; FEATURE:")) { feature = l.removePrefix("; FEATURE:").trim(); continue }
            val c = l.substringBefore(';').trim()
            if (!(c.startsWith("G1 ") || c.startsWith("G2 ") || c.startsWith("G3 "))) continue
            val w = c.split(Regex("\\s+"))
            fun num(ch: Char) = w.drop(1).firstOrNull { it.isNotEmpty() && it[0] == ch }?.substring(1)?.toDoubleOrNull()
            val nx = num('X') ?: x; val ny = num('Y') ?: y
            if ((num('E') ?: 0.0) > 0 && feature.startsWith("Support") && !nx.isNaN())
                for ((k, s) in spots.withIndex()) if (!seen[k] && nx >= s.x0 - 1e-6 && nx <= s.x1 + 1e-6 && ny >= s.y0 - 1e-6 && ny <= s.y1 + 1e-6) seen[k] = true
            x = nx; y = ny
        }
        return spots.withIndex().filter { !seen[it.index] }.map { (_, s) ->
            "support spot '%s' (x %.2f..%.2f, y %.2f..%.2f mm on the bed) is in the plan, but no support is printed in it".format(java.util.Locale.ROOT, s.name, s.x0, s.x1, s.y0, s.y1) }
    }
}
