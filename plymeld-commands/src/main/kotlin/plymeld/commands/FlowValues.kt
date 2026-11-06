package plymeld.commands

import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import plymeld.config.ConfigFiles
import plymeld.config.Dimension

/**
 * `plymeld calibrate flow values TEMP=RATIO... [--tool N] [--filament NAME] [--plate-gcode PLATE] [--dry-run]`: a read
 * flow plate's results written into the config file that defines the filament, as its flow ratio
 * (`hardware.filament.<key>.flow`), the way [FilamentValuesCommand] writes: the best patch's ratio in each column. The
 * filament's own ratio, for every machine and tool: a flow ratio belongs to the toolhead (and its nozzle) as much as to
 * the filament, so the comment names the machine and tool it was measured on.
 */
class FlowValuesCommand : FilamentValuesCommand("flow plate") {
    private val readings by argument("TEMP=RATIO", help = "a column's reading: its temperature (C) = the ratio of its best patch (closed, no gaps, and no ridges), " +
        "as printed beside the row, e.g. 210=0.98 (or 98%)").multiple(required = true)

    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "Write a read flow plate's results into the config file that defines the filament (among the files -cf names, e.g. -cf 'DIR/*.toml'; else plymeld's config folder's): its flow ratio, " +
            "the best patch's in each column, as one value when they agree, else a formula through every reading (straight between neighboring temperatures, " +
            "flat past the ends); the readings, the machine and the tool in the comment above it. Prints the change, checks it, writes it, and records it for `plymeld journal --undo`."

    override fun run() {
        val points = columns(readings.map { parse(it) })
        val t = target()
        val path = ConfigFiles.pathText(listOf("hardware", "filament", t.filament, "flow"))
        val comment = listOf(
            "The flow ratio, from a flow plate on '${t.machine}', tool ${t.toolN} (plymeld calibrate flow values, ${java.time.LocalDate.now()})" + (madeBy()?.let { ": $it" } ?: "") + ".",
            "Readings, the best patch per column: " + points.joinToString(", ") { "${it.tempC} C ${n(it.value)}" } +
                (if (points.size > 1 && points.any { it.value != points[0].value }) ". Straight between them, flat past the ends." else "."))
        val line = (listOf("plymeld calibrate flow values") + points.map { "${it.tempC}=${n(it.value)}" } + listOf("--tool", "${t.toolN}")).joinToString(" ")
        write(t, path, formula(points, ""), comment, Dimension.RATIO, points, line)
    }

    companion object {
        /**
         * "210=0.98" or "210=98%"; refused otherwise, and for a column with no good patch: "210=1.06+" (gaps even at the
         * richest) or "210=0.94-" (ridges even at the leanest) gives no value.
         */
        fun parse(a: String): Point {
            val m = Regex("""^\s*(\d+)\s*(?:C)?\s*=\s*([0-9.]+)\s*(%)?\s*([+-])?\s*$""").find(a)
                ?: throw UsageError("'$a': a reading is TEMP=RATIO, the best patch's ratio at that column's temperature, e.g. 210=0.98 (or 98%)")
            if (m.groupValues[4].isNotEmpty()) throw UsageError("'$a': a column with no good patch gives no value (only that the ratio is " +
                "${if (m.groupValues[4] == "+") "above" else "below"} the plate's range): print a flow plate with --flow-ratios shifted past it, and read that")
            val v = (m.groupValues[2].toDoubleOrNull() ?: throw UsageError("'$a': the ratio is a number above 0")).let { if (m.groupValues[3] == "%") it / 100 else it }
            if (v <= 0) throw UsageError("'$a': a flow ratio above 0, as printed beside the patch's row (e.g. 0.98)")
            return Point(m.groupValues[1].toInt(), v)
        }
    }
}
