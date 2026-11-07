package plymeld.core

import plymeld.machine.MachineDescription
import plymeld.predict.TimeEstimator
import java.io.BufferedReader
import java.io.StringReader
import java.util.Locale

/**
 * Predict: print time and filament of a g-code file, from the machine's
 * own motion limits (machine description, with evidence). The same estimator the tests use as
 * their reference against QIDIStudio (2-7 % from QIDIStudio's own estimate on its files).
 * A machine missing a limit gets no time prediction, and says which limit is missing.
 */
object Predict {
    private val NEEDED = listOf("max_velocity", "max_accel", "max_z_velocity", "max_z_accel", "square_corner_velocity")

    /**
     * [layerS]: predicted time of each layer, in print order (empty when time is not predicted). [filamentByToolMm]: the
     * same filament by the tool that pushes it (0-based, [filamentByTool]), so it can be weighed by each tool's filament.
     */
    class Prediction(val timeS: Double?, val filamentMm: Double, val why: String, val layerS: List<Double> = emptyList(),
                     val filamentByToolMm: Map<Int, Double> = emptyMap(),
                     /** The seam measure of the planned layers the g-code was written from (SeamScar); null = not measured (a file from elsewhere, a multi-filament object). */
                     val seamScar: SeamScar.Result? = null,
                     /** The stringing and restart measures of the program as written (StringRisk); null = not measured (a file from elsewhere). */
                     val strings: plymeld.gcode.StringRisk.Result? = null,
                     /** The moves' time by the feature they print ("travel" for moves that print nothing; s); the tool changes' own time is not in it. */
                     val featureS: Map<String, Double> = emptyMap()) {
        override fun toString() = (timeS?.let { "%.1f min".format(Locale.ROOT, it / 60) } ?: "time not predicted") +
            ", filament %.0f mm".format(Locale.ROOT, filamentMm) + " ($why)"
        fun withSeamScar(r: SeamScar.Result?) = Prediction(timeS, filamentMm, why, layerS, filamentByToolMm, r, strings, featureS)
        fun withStrings(r: plymeld.gcode.StringRisk.Result?) = Prediction(timeS, filamentMm, why, layerS, filamentByToolMm, seamScar, r, featureS)

        /** The time by feature, longest first (minutes, each a share of the moves' time), or null when none was predicted. */
        fun byFeature(): String? {
            val total = featureS.values.sum().takeIf { it > 0 } ?: return null
            return featureS.entries.filter { it.value > 0 }.sortedByDescending { it.value }.joinToString { (f, s) ->
                "%s %.1f min (%.0f %%)".format(Locale.ROOT, f.ifEmpty { "unnamed" }, s / 60, 100 * s / total) }
        }

        /**
         * Layers predicted to take less than [minS] (the material's minimum layer time), as ranges
         * of layer numbers (1-based) with the shortest time in each; null when there are none.
         */
        fun shortLayers(minS: Double): String? {
            val short = layerS.indices.filter { layerS[it] < minS }
            if (short.isEmpty()) return null
            val ranges = ArrayList<IntRange>()
            for (i in short) if (ranges.isNotEmpty() && ranges.last().last == i - 1) ranges[ranges.size - 1] = ranges.last().first..i else ranges.add(i..i)
            return "${short.size} of ${layerS.size} layers predicted under %.1f s: ".format(Locale.ROOT, minS) + ranges.joinToString { r ->
                (if (r.first == r.last) "${r.first + 1}" else "${r.first + 1}-${r.last + 1}") + " (shortest %.1f s)".format(Locale.ROOT, r.minOf { layerS[it] })
            }
        }
    }

    /** Why a time prediction is impossible for this machine, or null when it is possible. */
    /**
     * The config's motion limits for [gcode]: the faster of printing and travel as the cap on any move (the
     * g-code's own feed rates already keep printing to the print speed), the larger acceleration, and the machine's own Z
     * and corner limits where it sets them (else its model's).
     */
    fun limits(s: plymeld.model.SliceSettings): Map<String, Double> =
        mapOf("max_velocity" to maxOf(s.hiddenSpeed, s.travelSpeed), "max_accel" to maxOf(s.defaultAccel, s.travelAccel)) +
            listOfNotNull(s.zSpeedMax?.let { "max_z_velocity" to it }, s.zAccelMax?.let { "max_z_accel" to it },
                s.cornerSpeed?.let { "square_corner_velocity" to it })

    fun missing(machine: MachineDescription): String? = NEEDED.filter { it !in machine.motion }.takeIf { it.isNotEmpty() }
        ?.let { "no ${it.joinToString()} for the ${machine.name}" }

    /**
     * [toolChangeS]: the machine's own time per tool change beyond the g-code's moves
     * (hardware.machine.tool_change.time), added once per change; null = not counted (and said so).
     * [limits]: motion limits from the config (Klipper names: max_velocity, max_accel ...), which win over
     * the machine description's (the description is plymeld's bundled default, the config the user's machine).
     */
    fun gcode(text: String, machine: MachineDescription, toolChangeS: Double? = null, limits: Map<String, Double> = emptyMap()): Prediction {
        val filament = netFilament(text)
        val byTool = filamentByTool(text)
        val m = NEEDED.associateWith { k -> limits[k]?.let { plymeld.machine.MotionLimit(it, plymeld.machine.Evidence("stand_in", "config")) } ?: machine.motion[k] }
        m.filterValues { it == null }.keys.takeIf { it.isNotEmpty() }?.let { return Prediction(null, filament, "no ${it.joinToString()} for the ${machine.name}", filamentByToolMm = byTool) }
        fun v(k: String) = m.getValue(k)!!.value
        val t = TimeEstimator(v("max_velocity"), v("max_accel"), v("max_z_velocity"), v("max_z_accel"), v("square_corner_velocity")).estimate(BufferedReader(StringReader(text)))
        val changes = toolChanges(text)
        val changeNote = when {
            changes == 0 -> ""
            toolChangeS == null -> "; $changes tool changes, their own time not counted (hardware.machine.<key>.tool_change.time not set)"
            else -> "; $changes tool changes x %.1f s (hardware.machine.<key>.tool_change.time)".format(Locale.ROOT, toolChangeS)
        }
        return Prediction(t.totalS + changes * (toolChangeS ?: 0.0), filament,
            "motion limits: " + NEEDED.joinToString { k -> "$k ${v(k)} (${if (k in limits) "config" else "${machine.name}: ${m.getValue(k)!!.evidence.kind}"})" } + changeNote, t.perLayerS, byTool,
            featureS = t.perFeatureS)
    }

    /**
     * [netFilament] split by the tool that pushes it (0-based, as `T<n>` selects it): what is extruded before the first
     * T goes to the tool that T selects (the start template's own, usually), or to tool 0 when the file has none. The
     * values add up to [netFilament].
     */
    fun filamentByTool(text: String): Map<Int, Double> {
        val out = LinkedHashMap<Int, Double>()
        var rel = false; var e = 0.0; var tool: Int? = null; var before = 0.0
        for (line in text.lineSequence()) {
            val c = line.substringBefore(';').trim()
            if (Regex("T\\d+").matches(c)) { val t = c.substring(1).toInt(); if (tool == null) { out.merge(t, before, Double::plus); before = 0.0 }; tool = t; continue }
            if (c.startsWith("M83")) rel = true else if (c.startsWith("M82")) rel = false
            else if (c.startsWith("G92")) c.split(' ').firstOrNull { it.startsWith("E") }?.let { e = it.substring(1).toDoubleOrNull() ?: e }
            else if (c.startsWith("G1") || c.startsWith("G0") || c.startsWith("G2") || c.startsWith("G3")) {
                val v = c.split(' ').firstOrNull { it.startsWith("E") }?.substring(1)?.toDoubleOrNull() ?: continue
                val d = if (rel) v else (v - e).also { e = v }
                if (tool == null) before += d else out.merge(tool, d, Double::plus)
            }
        }
        if (tool == null) out[0] = before
        return out
    }

    /**
     * Time (s) and filament (mm) by calibration cell (`; CELL: <label>` .. `; CELL END`; "" outside every cell), so the
     * cells and "" add up to [gcode]'s prediction: every move and dwell by the cell it runs in (a cell's travel and
     * retraction into it included), the tool changes' own time outside; filament EXTRUDED on moves in X/Y within a cell is
     * the cell's, everything else (retractions and their restores, the start and end templates' primes) is "", so the
     * filament adds up to [netFilament]. Time null: not predicted (as [gcode] says why).
     */
    fun byCell(text: String, machine: MachineDescription, toolChangeS: Double? = null, limits: Map<String, Double> = emptyMap()): Pair<Map<String, Double>?, Map<String, Double>> {
        val m = NEEDED.associateWith { k -> limits[k] ?: machine.motion[k]?.value }
        val time = if (m.values.any { it == null }) null else {
            TimeEstimator(m.getValue("max_velocity")!!, m.getValue("max_accel")!!, m.getValue("max_z_velocity")!!, m.getValue("max_z_accel")!!, m.getValue("square_corner_velocity")!!)
                .estimate(BufferedReader(StringReader(text))).perCellS.toMutableMap().also { it.merge("", toolChanges(text) * (toolChangeS ?: 0.0), Double::plus) }
        }
        val fil = LinkedHashMap<String, Double>()
        var rel = false; var e = 0.0; var cell = ""
        for (line in text.lineSequence()) {
            val t = line.trim()
            if (t.startsWith("; CELL: ")) { cell = t.removePrefix("; CELL: ").trim(); continue }
            if (t == "; CELL END") { cell = ""; continue }
            val c = line.substringBefore(';').trim()
            if (c.startsWith("M83")) rel = true else if (c.startsWith("M82")) rel = false
            else if (c.startsWith("G92")) c.split(' ').firstOrNull { it.startsWith("E") }?.let { e = it.substring(1).toDoubleOrNull() ?: e }
            else if (c.startsWith("G1") || c.startsWith("G0") || c.startsWith("G2") || c.startsWith("G3")) {
                val words = c.split(' ')
                val v = words.firstOrNull { it.startsWith("E") }?.substring(1)?.toDoubleOrNull() ?: continue
                val d = if (rel) v else (v - e).also { e = v }
                fil.merge(if (d > 0 && cell.isNotEmpty() && words.any { it.startsWith("X") || it.startsWith("Y") }) cell else "", d, Double::plus)
            }
        }
        return time to fil
    }

    /** Net filament pushed (relative E; M82 absolute handled), mm. */
    fun netFilament(text: String): Double {
        var rel = false; var e = 0.0; var total = 0.0
        for (line in text.lineSequence()) {
            val c = line.substringBefore(';').trim()
            if (c.startsWith("M83")) rel = true else if (c.startsWith("M82")) rel = false
            else if (c.startsWith("G92")) c.split(' ').firstOrNull { it.startsWith("E") }?.let { e = it.substring(1).toDoubleOrNull() ?: e }
            else if (c.startsWith("G1") || c.startsWith("G0") || c.startsWith("G2") || c.startsWith("G3")) {
                val v = c.split(' ').firstOrNull { it.startsWith("E") }?.substring(1)?.toDoubleOrNull() ?: continue
                if (rel) total += v else { total += v - e; e = v }
            }
        }
        return total
    }

    /**
     * Tool changes in a g-code file: T commands that switch to another tool. A T before anything is
     * extruded selects the tool the print starts with (the start template's `T{initial_tool}`), not a
     * change; without one, tool 0 is taken as loaded at the start.
     */
    fun toolChanges(text: String): Int {
        var cur = 0; var n = 0; var extruded = false
        for (line in text.lineSequence()) {
            val c = line.substringBefore(';').trim()
            if (Regex("T\\d+").matches(c)) { val t = c.substring(1).toInt(); if (!extruded) cur = t else if (t != cur) { n++; cur = t } }
            else if (!extruded && (c.startsWith("G1") || c.startsWith("G0")) && c.split(' ').any { it.startsWith("E") && (it.substring(1).toDoubleOrNull() ?: 0.0) > 0 }) extruded = true
        }
        return n
    }

    /**
     * Filament EXTRUDED (mm) per feature label (`; FEATURE:` or `;TYPE:`): positive E on moves that
     * also move in X/Y. Retractions and deretractions are left out: a retraction when leaving a
     * feature and its deretraction usually fall under different labels, so a net count would
     * charge one feature for the other's (135 mm on the two-color Benchy's tower).
     */
    fun filamentByFeature(text: String): Map<String, Double> {
        val out = LinkedHashMap<String, Double>()
        var rel = false; var e = 0.0; var feature = ""
        for (line in text.lineSequence()) {
            val t = line.trim()
            if (t.startsWith("; FEATURE:") || t.startsWith(";TYPE:")) { feature = t.substringAfter(':').trim(); continue }
            val c = line.substringBefore(';').trim()
            if (c.startsWith("M83")) rel = true else if (c.startsWith("M82")) rel = false
            else if (c.startsWith("G92")) c.split(' ').firstOrNull { it.startsWith("E") }?.let { e = it.substring(1).toDoubleOrNull() ?: e }
            else if (c.startsWith("G1") || c.startsWith("G0") || c.startsWith("G2") || c.startsWith("G3")) {
                val words = c.split(' ')
                val v = words.firstOrNull { it.startsWith("E") }?.substring(1)?.toDoubleOrNull() ?: continue
                val d = if (rel) v else (v - e).also { e = v }
                if (d > 0 && words.any { it.startsWith("X") || it.startsWith("Y") }) out.merge(feature, d, Double::plus)
            }
        }
        return out
    }
}
