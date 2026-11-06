package plymeld.commands

import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import plymeld.config.ConfigFiles
import plymeld.config.Dimension

/**
 * `plymeld calibrate melt values TEMP=FLOW... [--tool N] [--filament NAME] [--plate-gcode PLATE] [--dry-run]`: a read
 * melt plate's results written into the config file that defines the filament, as the filament's maximum flow on this
 * machine (`hardware.filament.<key>.machine.<machine>.melt.flow.max`), the way [FilamentValuesCommand] writes.
 */
class MeltValuesCommand : FilamentValuesCommand("melt plate") {
    private val readings by argument("TEMP=FLOW", help = "a column's reading: its temperature (C) = the highest row's flow (mm3/s) printed without gaps, e.g. 210=26; " +
        "FLOW+ when the column was clean up to the plate's last row (at least that), e.g. 230=30+ (or >=FLOW, quoted for the shell)").multiple(required = true)

    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "Write a read melt plate's results into the config file that defines the filament (among the files -cf names, e.g. -cf 'DIR/*.toml'; else plymeld's config folder's): its maximum flow on this machine as a formula through every " +
            "reading (straight between neighboring temperatures, flat past the ends), the readings in the comment above it. Prints the change, checks it, " +
            "writes it, and records it for `plymeld journal --undo`."

    /** One reading: a temperature and the highest clean flow there; [atLeast]: clean up to the plate's last row. */
    class Reading(val tempC: Int, val flow: Double, val atLeast: Boolean)

    override fun run() {
        val rs = readings.map { parse(it) }
        val points = columns(rs.map { Point(it.tempC, it.flow) })
        val byTemp = rs.associateBy { it.tempC }
        val t = target()
        val path = ConfigFiles.pathText(listOf("hardware", "filament", t.filament, "machine", t.machine, "melt", "flow", "max"))
        val comment = listOf(
            "The maximum flow on '${t.machine}', from a melt plate (plymeld calibrate melt values, ${java.time.LocalDate.now()}): tool ${t.toolN}" + (madeBy()?.let { ", $it" } ?: "") + ".",
            "Readings, the highest flow without gaps: " + points.joinToString(", ") { p -> "${p.tempC} C ${if (byTemp.getValue(p.tempC).atLeast) "at least " else ""}${n(p.value)} mm3/s" } +
                ". Straight between them, flat past the ends" + (if (rs.any { it.atLeast }) "; \"at least\" taken as that value" else "") + ".")
        val line = (listOf("plymeld calibrate melt values") + points.map { p -> "${p.tempC}=${n(p.value)}${if (byTemp.getValue(p.tempC).atLeast) "+" else ""}" } + listOf("--tool", "${t.toolN}")).joinToString(" ")
        write(t, path, formula(points, " mm3/s"), comment, Dimension.FLOW, points, line)
    }

    companion object {
        /**
         * "210=26", or "230=30+" (at least 30: clean to the plate's last row; also written "230=>=30", which a shell needs
         * quoted); refused otherwise, and for a reading below the first row ("200=8-" or "200=<8"): it gives no value.
         */
        fun parse(a: String): Reading {
            val m = Regex("""^\s*(\d+)\s*(?:C)?\s*=\s*(>=|<)?\s*([0-9.]+)\s*(?:mm3/s)?\s*([+-])?\s*$""").find(a)
                ?: throw UsageError("'$a': a reading is TEMP=FLOW, e.g. 210=26 (C, mm3/s), or TEMP=FLOW+ when the column was clean to the last row, e.g. 230=30+")
            val atLeast = m.groupValues[2] == ">=" || m.groupValues[4] == "+"
            val below = m.groupValues[2] == "<" || m.groupValues[4] == "-"
            if (atLeast && below) throw UsageError("'$a': at least or below, not both")
            if (below) throw UsageError("'$a': a column with gaps in its first row gives no value (only that its maximum flow is lower): " +
                "print a melt plate that starts lower (a smaller --flow-step, or a lower expected maximum flow), and read that")
            val flow = m.groupValues[3].toDoubleOrNull()?.takeIf { it > 0 } ?: throw UsageError("'$a': the flow is mm3/s above 0")
            return Reading(m.groupValues[1].toInt(), flow, atLeast)
        }
    }
}
