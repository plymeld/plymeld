package plymeld.commands

import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import plymeld.config.Config
import plymeld.config.ConfigFiles
import plymeld.config.ConfigStack
import plymeld.config.Dimension
import plymeld.config.Site

/**
 * `plymeld calibrate melt values TEMP=FLOW... [--tool N] [--filament NAME] [--plate-gcode PLATE] [--dry-run]`: a read
 * melt plate's results written into the config file that defines the filament, as the filament's maximum flow on this
 * machine (`hardware.filament.<key>.machine.<machine>.melt.flow.max`): a formula through every reading, straight between
 * neighboring temperatures and flat past the ends, with the readings in the comment above it. The change is printed,
 * checked (the file reads back, and the formula gives each reading at its temperature), written, and recorded in the
 * config journal for `plymeld journal --undo`.
 */
class MeltValuesCommand : ConfigCommand("values") {
    private val readings by argument("TEMP=FLOW", help = "a column's reading: its temperature (C) = the highest row's flow (mm3/s) printed without gaps, e.g. 210=26; " +
        "FLOW+ when the column was clean up to the plate's last row (at least that), e.g. 230=30+ (or >=FLOW, quoted for the shell)").multiple(required = true)
    private val toolOpt by option("--tool", metavar = "N", help = "the machine's tool (its index, from 1) the plate printed with").default("1")
    private val filament by option("--filament", metavar = "NAME", help = "the filament the plate printed; default: the one the machine's loadout puts on --tool")
    private val plateGcode by option("--plate-gcode", metavar = "PLATE", help = "the melt plate's g-code, as `plymeld calibrate melt` wrote it: named, with the plymeld that made it, in the comment").path(mustExist = true)
    private val dryRun by option("--dry-run", help = "print the change and check it; write nothing").flag()
    private val journalOpt by option("--journal", metavar = "JOURNAL", help = "record the change in this journal file; default: the config journal in plymeld's state folder ($JOURNAL)").path(canBeDir = false)

    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "Write a read melt plate's results into the config file that defines the filament: its maximum flow on this machine as a formula through every " +
            "reading (straight between neighboring temperatures, flat past the ends), the readings in the comment above it. Prints the change, checks it, " +
            "writes it, and records it for `plymeld journal --undo`."

    /** One reading: a temperature and the highest clean flow there; [atLeast]: clean up to the plate's last row. */
    class Reading(val tempC: Int, val flow: Double, val atLeast: Boolean)

    override fun run() {
        val rs = readings.map { parse(it) }.sortedBy { it.tempC }
        rs.groupBy { it.tempC }.filterValues { it.size > 1 }.keys.firstOrNull()?.let { throw UsageError("$it C is given twice: one reading per column") }
        val fileArgs = files()
        val config = load(fileArgs, configValues)
        val machine = config.machine ?: throw UsageError(config.machineProblem ?: "which machine? give --machine")
        val toolN = toolOpt.toIntOrNull()?.takeIf { it >= 1 } ?: throw UsageError("--tool is a tool's index, a whole number from 1 (got '$toolOpt')")
        val tools = config.keysOf("tool").filter { config.entry(listOf("hardware", "tool", it, "machine"))?.value == machine }
        fun index(tk: String) = config.entry(listOf("hardware", "tool", tk, "index"))?.let { config.quantity(it, Dimension.COUNT, Site(tool = tk)).toInt() } ?: 1
        val tool = tools.firstOrNull { index(it) == toolN } ?: throw UsageError("machine '$machine' has no tool $toolN")
        val name = filament ?: (config.loadout("tool", tool, "filament")?.value as String?)?.takeIf { it.isNotEmpty() }
            ?: throw UsageError("which filament? the machine's loadout puts none on tool $toolN: give --filament")
        // the file that defines the filament: the first of the user's own files with a value under it
        val own = ConfigFiles.readFiles(fileArgs).filter { it.path.size > 2 && it.path[0] == "hardware" && it.path[1] == "filament" && it.path[2] == name }
        val file = own.firstOrNull()?.source?.substringBeforeLast(':')?.let { java.nio.file.Path.of(it) }
            ?: throw UsageError("'$name' is not defined in your config files (only in plymeld's bundled set, or nowhere): define it in one first " +
                "(e.g. [hardware.filament.$name] like = \"pla-generic\"), then enter its readings")
        if (!file.fileName.toString().endsWith(".toml")) throw UsageError("$file defines '$name' but is not TOML: plymeld writes TOML files only")
        val path = ConfigFiles.pathText(listOf("hardware", "filament", name, "machine", machine, "melt", "flow", "max"))
        val formula = formula(rs)
        val comment = comment(rs, toolN, machine)
        // the change, checked before anything is written: the file reads back, and the formula, in the whole config, gives
        // each reading at its temperature
        val before = java.nio.file.Files.readString(file)
        val after = TomlEdit.setWithComment(before, path, formula, comment)
        try { ConfigFiles.readToml(after, file.toString()) } catch (e: IllegalArgumentException) { error("the change would leave $file unreadable (${e.message}); nothing written") }
        val site = Site(machine = machine, tool = tool, filament = name)
        for (r in rs) {
            val at = load(fileArgs, configValues + listOf("$path=$formula", "hardware.filament.${ConfigFiles.pathText(listOf(name))}.temperature.print=${r.tempC} C"))
            val got = at.quantity(at.entry(TomlEdit.segments(path))!!, Dimension.FLOW, site)
            check(Math.abs(got - r.flow) < 1e-6) { "the formula gives $got mm3/s at ${r.tempC} C, not the reading ${r.flow}: nothing written" }
        }
        echo("$file: hardware.filament.$name on machine '$machine':")
        comment.forEach { echo("  # $it") }
        echo("  $path = \"$formula\"")
        TomlEdit.get(before, path)?.let { echo("  (was \"$it\")") }
        if (dryRun) { echo("--dry-run: nothing written"); return }
        java.nio.file.Files.writeString(file, after)
        val journal = journalOpt ?: plymeld.config.UserDirs.current().state.resolve(JOURNAL)
        journal.parent?.let { java.nio.file.Files.createDirectories(it) }
        val line = (listOf("plymeld calibrate melt values") + rs.map { "${it.tempC}=${n(it.flow)}${if (it.atLeast) "+" else ""}" } + listOf("--tool", "$toolN")).joinToString(" ")
        Journal.read(journal).append(Journal.Entry(Journal.now(), line, file.toAbsolutePath().toString(), before, after))
        echo("written; recorded in $journal (undo: plymeld journal '$journal' --undo)")
    }

    private fun load(fileArgs: List<String>, values: List<String>): Config = try { Config(ConfigStack.load(fileArgs, values), emptyList(), machineOpt) }
        catch (e: plymeld.config.ConfigError) { throw UsageError("the config has ${e.problems.size} problem(s):\n" + e.problems.joinToString("\n") { "  $it" }) }

    /** The comment above the value: what it is, the readings, the plate and when. */
    private fun comment(rs: List<Reading>, toolN: Int, machine: String): List<String> {
        val made = plateGcode?.let { p -> MadeBy.text(java.nio.file.Files.readAllLines(p), MadeBy.dateOf(p))?.let { "${p.fileName}, made by $it" } ?: p.fileName.toString() }
        return listOf(
            "The maximum flow on '$machine', from a melt plate (plymeld calibrate melt values, ${java.time.LocalDate.now()}): tool $toolN" + (made?.let { ", $it" } ?: "") + ".",
            "Readings, the highest flow without gaps: " + rs.joinToString(", ") { "${it.tempC} C ${if (it.atLeast) "at least " else ""}${n(it.flow)} mm3/s" } +
                ". Straight between them, flat past the ends" + (if (rs.any { it.atLeast }) "; \"at least\" taken as that value" else "") + ".")
    }

    companion object {
        /** The config journal: edits plymeld makes to your config files, for `plymeld journal --undo`; in plymeld's state folder. */
        const val JOURNAL = "config.journal.json"

        fun n(v: Double): String = java.math.BigDecimal(v).setScale(3, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

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

        /**
         * The maximum flow as a formula of the filament's print temperature through every reading: straight between
         * neighboring temperatures, the first reading's below the first, the last's above the last (never extrapolated).
         */
        fun formula(rs: List<Reading>): String {
            val t = "filament.temperature.print"
            if (rs.size == 1) return "${n(rs[0].flow)} mm3/s"
            fun segment(a: Reading, b: Reading): String {
                val d = b.flow - a.flow
                val slope = "($t - ${a.tempC} C) / ${b.tempC - a.tempC} C * ${n(Math.abs(d))} mm3/s"
                return when { d == 0.0 -> "${n(a.flow)} mm3/s"; d > 0 -> "${n(a.flow)} mm3/s + $slope"; else -> "${n(a.flow)} mm3/s - $slope" }
            }
            var f = "${n(rs.last().flow)} mm3/s"
            for (i in rs.size - 2 downTo 0) f = "if($t <= ${rs[i + 1].tempC} C, ${segment(rs[i], rs[i + 1])}, $f)"
            return "= if($t <= ${rs[0].tempC} C, ${n(rs[0].flow)} mm3/s, $f)"
        }
    }
}
