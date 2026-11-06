package plymeld.commands

import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import plymeld.config.Config
import plymeld.config.ConfigFiles
import plymeld.config.ConfigStack
import plymeld.config.Dimension
import plymeld.config.Site

/**
 * A read calibration plate's results written into the config file that defines the filament: the `values` subcommands
 * of plates read per temperature (`calibrate melt values`, `calibrate flow values`). One value per column's temperature,
 * written as a formula of the filament's print temperature through every reading (straight between neighboring
 * temperatures, flat past the ends; one value when they all agree), with the readings in the comment above it. The change
 * is printed, checked (the file reads back, and the whole config gives each reading at its temperature), written, and
 * recorded in the config journal for `plymeld journal --undo`; `--dry-run` stops after the check.
 */
abstract class FilamentValuesCommand(private val plateName: String) : ConfigCommand("values") {
    private val toolOpt by option("--tool", metavar = "N", help = "the machine's tool (its index, from 1) the plate printed with; default: 1 (the first-layer plate: the tool its g-code names)")
    private val filament by option("--filament", metavar = "NAME", help = "the filament the plate printed; default: the one the machine's loadout puts on --tool")
    protected val plateGcode by option("--plate-gcode", metavar = "PLATE", help = "the $plateName's g-code, as plymeld wrote it: named, with the plymeld that made it, in the comment").path(mustExist = true)
    private val dryRun by option("--dry-run", help = "print the change and check it; write nothing").flag()
    private val journalOpt by option("--journal", metavar = "JOURNAL", help = "record the change in this journal file; default: the config journal in plymeld's state folder ($JOURNAL)").path(canBeDir = false)

    /** One column's value: its temperature and the value read there. */
    class Point(val tempC: Int, val value: Double)

    /** Where the values go: the config, its machine, the plate's tool (key and index), the filament, and the file that defines it. */
    class Target(val config: Config, val fileArgs: List<String>, val machine: String, val tool: String, val toolN: Int, val filament: String, val file: java.nio.file.Path)

    /** [plateTool], [plateFilament]: what the plate's g-code says it printed with, the defaults for --tool and --filament. */
    protected fun target(plateTool: Int? = null, plateFilament: String? = null): Target {
        val fileArgs = files()
        val config = load(fileArgs, configValues)
        val machine = config.machine ?: throw UsageError(config.machineProblem ?: "which machine? give --machine")
        val toolText = toolOpt ?: plateTool?.toString() ?: "1"
        val toolN = toolText.toIntOrNull()?.takeIf { it >= 1 } ?: throw UsageError("--tool is a tool's index, a whole number from 1 (got '$toolText')")
        val tools = config.keysOf("tool").filter { config.entry(listOf("hardware", "tool", it, "machine"))?.value == machine }
        fun index(tk: String) = config.entry(listOf("hardware", "tool", tk, "index"))?.let { config.quantity(it, Dimension.COUNT, Site(tool = tk)).toInt() } ?: 1
        val tool = tools.firstOrNull { index(it) == toolN } ?: throw UsageError("machine '$machine' has no tool $toolN")
        val name = filament ?: plateFilament ?: (config.loadout("tool", tool, "filament")?.value as String?)?.takeIf { it.isNotEmpty() }
            ?: throw UsageError("which filament? the machine's loadout puts none on tool $toolN: give --filament")
        // the file that defines the filament: the first of the user's own files with a value under it
        val own = ConfigFiles.readFiles(fileArgs).filter { it.path.size > 2 && it.path[0] == "hardware" && it.path[1] == "filament" && it.path[2] == name }
        val file = own.firstOrNull()?.source?.substringBeforeLast(':')?.let { java.nio.file.Path.of(it) }
            ?: throw UsageError("'$name' is not defined in your config files (only in plymeld's bundled set, or nowhere): define it in one first " +
                "(e.g. [hardware.filament.$name] like = \"pla-generic\"), then enter its readings")
        if (!file.fileName.toString().endsWith(".toml")) throw UsageError("$file defines '$name' but is not TOML: plymeld writes TOML files only")
        return Target(config, fileArgs, machine, tool, toolN, name, file)
    }

    /** "PLATE, made by plymeld ..." for the comment, when --plate-gcode names the plate; null otherwise. */
    protected fun madeBy(): String? = plateGcode?.let { p -> MadeBy.text(java.nio.file.Files.readAllLines(p), MadeBy.dateOf(p))?.let { "${p.fileName}, made by $it" } ?: p.fileName.toString() }

    /**
     * [value] (a value or a formula) written at [path] in [t]'s file with [comment] above it, after checking that the file
     * reads back and that the whole config gives each of [points] at its temperature (a [dim] quantity); recorded in the
     * journal as [line]. Prints the change; writes nothing with --dry-run.
     */
    protected fun write(t: Target, path: String, value: String, comment: List<String>, dim: Dimension, points: List<Point>, line: String) {
        val before = java.nio.file.Files.readString(t.file)
        val after = TomlEdit.setWithComment(before, path, value, comment)
        try { ConfigFiles.readToml(after, t.file.toString()) } catch (e: IllegalArgumentException) { error("the change would leave ${t.file} unreadable (${e.message}); nothing written") }
        val site = Site(machine = t.machine, tool = t.tool, filament = t.filament)
        for (p in points) {
            val at = load(t.fileArgs, configValues + listOf("$path=$value", "hardware.filament.${ConfigFiles.pathText(listOf(t.filament))}.temperature.print=${p.tempC} C"))
            val got = at.quantity(at.entry(TomlEdit.segments(path))!!, dim, site)
            check(Math.abs(got - p.value) < 1e-6) { "the value written gives $got at ${p.tempC} C, not the reading ${p.value}: nothing written" }
        }
        val shown = comment.map { "# $it" } + "$path = \"$value\"" + listOfNotNull(TomlEdit.get(before, path)?.let { "(was \"$it\")" })
        commit(t, before, after, shown, line)
    }

    /**
     * [after] (checked by the caller) written over [before] in [t]'s file: [shown] printed under the filament first, then
     * (unless --dry-run) written and recorded in the journal as [line].
     */
    protected fun commit(t: Target, before: String, after: String, shown: List<String>, line: String) {
        echo("${t.file}: hardware.filament.${t.filament}:")
        shown.forEach { echo("  $it") }
        if (dryRun) { echo("--dry-run: nothing written"); return }
        java.nio.file.Files.writeString(t.file, after)
        val journal = journalOpt ?: plymeld.config.UserDirs.current().state.resolve(JOURNAL)
        journal.parent?.let { java.nio.file.Files.createDirectories(it) }
        Journal.read(journal).append(Journal.Entry(Journal.now(), line, t.file.toAbsolutePath().toString(), before, after))
        echo("written; recorded in $journal (undo: plymeld journal '$journal' --undo)")
    }

    private fun load(fileArgs: List<String>, values: List<String>): Config = try { Config(ConfigStack.load(fileArgs, values), emptyList(), machineOpt) }
        catch (e: plymeld.config.ConfigError) { throw UsageError("the config has ${e.problems.size} problem(s):\n" + e.problems.joinToString("\n") { "  $it" }) }

    companion object {
        /** The config journal: edits plymeld makes to your config files, for `plymeld journal --undo`; in plymeld's state folder. */
        const val JOURNAL = "config.journal.json"

        fun n(v: Double): String = java.math.BigDecimal(v).setScale(3, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

        /** Points sorted by temperature; a temperature given twice refused. */
        fun columns(ps: List<Point>): List<Point> = ps.sortedBy { it.tempC }.also { s ->
            s.groupBy { it.tempC }.filterValues { it.size > 1 }.keys.firstOrNull()?.let { throw UsageError("$it C is given twice: one reading per column") }
        }

        /**
         * The values as one value of the filament's print temperature through every point, each written with [unit] (" mm3/s",
         * or "" for a plain number): straight between neighboring temperatures, the first point's below the first, the
         * last's above the last (never extrapolated); a plain value when they all agree.
         */
        fun formula(ps: List<Point>, unit: String): String {
            val t = "filament.temperature.print"
            if (ps.all { it.value == ps[0].value }) return "${n(ps[0].value)}$unit"
            fun segment(a: Point, b: Point): String {
                val d = b.value - a.value
                val slope = "($t - ${a.tempC} C) / ${b.tempC - a.tempC} C * ${n(Math.abs(d))}$unit"
                return when { d == 0.0 -> "${n(a.value)}$unit"; d > 0 -> "${n(a.value)}$unit + $slope"; else -> "${n(a.value)}$unit - $slope" }
            }
            var f = "${n(ps.last().value)}$unit"
            for (i in ps.size - 2 downTo 0) f = "if($t <= ${ps[i + 1].tempC} C, ${segment(ps[i], ps[i + 1])}, $f)"
            return "= if($t <= ${ps[0].tempC} C, ${n(ps[0].value)}$unit, $f)"
        }
    }
}
