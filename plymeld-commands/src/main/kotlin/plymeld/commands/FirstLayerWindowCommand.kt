package plymeld.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import plymeld.config.ConfigFiles
import plymeld.config.FirstLayerWindow
import java.util.Locale

/**
 * `plymeld calibrate first-layer window PLATE.gcode --bore MM [--plate KEY] [-o FILE]`: the first-layer window (FirstLayerWindow) to grade
 * for a printed first-layer plate, so nobody types the grid from scratch: every printed tile "?", every tile the plate left
 * out (over the melt limit) "over", the axes and `from` filled from the plate's g-code (its conditions block and its cells).
 * The person grading replaces each "?" with good, marginal, or bad; plymeld refuses a window with a "?" left.
 */
class FirstLayerWindowCommand : CliktCommand(name = "window") {
    private val plate by argument("PLATE", help = "the first-layer plate's g-code, as `plymeld calibrate first-layer` wrote it").path(mustExist = true)
    private val output by option("-o", "--output", metavar = "FILE", help = "write the window to this TOML file (default: print it)").path()
    private val plateKey by option("--plate", metavar = "KEY", help = "the build plate (hardware.plate.<key>) the window is for: the key the machine's loadout names for the surface it printed on (hardware.machine.<key>.loadout.plate); default: the plate the g-code says it was made for")
    private val bore by option("--bore", metavar = "MM", help = "the bore of the nozzle that printed the plate (mm), e.g. 0.4: a window applies only to that bore (the plate's g-code does not record it)").required()

    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "Write the first-layer window to grade for a printed first-layer plate: a table per temperature laid out as the plate lies, every printed tile '?', every tile left out 'over'. Replace each '?' with good, marginal, or bad and add it to a config file."

    override fun run() {
        val b = bore.trim().removeSuffix("mm").trim().toDoubleOrNull()?.takeIf { it > 0 } ?: throw UsageError("--bore is the nozzle bore in mm above 0, e.g. 0.4 (got '$bore')")
        val text = try { FirstLayerRecord.blank(java.nio.file.Files.readString(plate), plate.fileName.toString(), plateKey, b, MadeBy.dateOf(plate)) }
            catch (e: IllegalArgumentException) { throw UsageError(e.message ?: "not a first-layer plate") }
        output?.let { out ->
            out.parent?.let { java.nio.file.Files.createDirectories(it) }
            java.nio.file.Files.writeString(out, text)
            echo("first-layer window to grade -> $out: replace each \"?\" with good, marginal, or bad, then add it to a config file (-cf)")
        } ?: echo(text, trailingNewline = false)
    }
}

/** Reading a first-layer plate's g-code back into the window to grade. */
object FirstLayerRecord {
    /** One block of the plate: its temperature and the tiles (row, column; 1-based) left out. */
    class Block(val tempC: Int, val leftOut: Set<Pair<Int, Int>>)

    /**
     * The window to grade for the plate in [gcode] (named [fileName], last written on [written] when known), keyed by
     * [plateKey] (else the plate the g-code names), measured with a [boreMm] nozzle. Refused (IllegalArgumentException, every problem listed) when the g-code is
     * not a first-layer plate, or its conditions and its printed tiles disagree: a tile listed as left out that is not in
     * the grid or was printed, a tile neither printed nor left out, a printed tile outside the grid or its blocks.
     */
    fun blank(gcode: String, fileName: String, plateKey: String?, boreMm: Double, written: java.time.LocalDate? = null): String {
        val lines = gcode.lines()
        val start = lines.indexOf("; CALIBRATION_PLATE_START"); val end = lines.indexOf("; CALIBRATION_PLATE_END")
        require(start >= 0 && end > start) { "$fileName: no calibration conditions block (; CALIBRATION_PLATE_START ... _END): not a plate plymeld made" }
        val cond = lines.subList(start + 1, end)
        fun value(name: String) = cond.firstOrNull { it.startsWith("; $name = ") }?.removePrefix("; $name = ")
        require(value("plate")?.startsWith("first-layer") == true) { "$fileName: not a first-layer plate (its conditions say plate = ${value("plate")})" }
        val fil = value("filament")?.let { Regex("""^(\S+), tool (\d+), build plate (\S+)$""").find(it) }
            ?: throw IllegalArgumentException("$fileName: its conditions name no filament, tool, and build plate ('; filament = <key>, tool <n>, build plate <key>')")
        val tilesLine = value("first_layer_tiles") ?: throw IllegalArgumentException("$fileName: its conditions give no first_layer_tiles")
        require("held to the maximum flow" !in tilesLine) { "$fileName: an older first-layer plate, whose tiles over the melt limit printed slower than their row's speed: its tiles cannot be recorded by row speed" }
        val axes = Regex("""speeds ([0-9., ]+) mm/s \(rows, front to back\), heights ([0-9., ]+) mm \(columns, left to right\)""").find(tilesLine)
            ?: throw IllegalArgumentException("$fileName: first_layer_tiles does not give the rows' speeds and the columns' heights")
        val speeds = axes.groupValues[1].split(',').map { it.trim().toDouble() }
        val heights = axes.groupValues[2].split(',').map { it.trim().toDouble() }
        val problems = ArrayList<String>()
        val tileRe = Regex("""tile (\d+)\.(\d+)""")
        val blocks = cond.mapNotNull { l -> Regex("""^; block (\d+) = (\d+) C,(.*)$""").find(l) }.map { m ->
            val n = m.groupValues[1].toInt()
            val out = m.groupValues[3].substringAfter("left out:", "").let { s -> tileRe.findAll(s).map { it.groupValues[1].toInt() to it.groupValues[2].toInt() }.toSet() }
            for ((r, c) in out) if (r !in 1..speeds.size || c !in 1..heights.size)
                problems += "block $n's conditions list tile $r.$c as left out; its grid is ${speeds.size} rows x ${heights.size} columns"
            n to Block(m.groupValues[2].toInt(), out)
        }
        require(blocks.isNotEmpty()) { "$fileName: its conditions list no blocks ('; block <n> = <t> C, ...')" }
        blocks.map { it.first }.let { ns -> require(ns == (1..ns.size).toList()) { "$fileName: its blocks are numbered ${ns.joinToString()}, not 1 to ${ns.size}" } }
        // the tiles the g-code prints, by its cells
        val printed = HashMap<Int, MutableSet<Pair<Int, Int>>>()
        for (m in lines.asSequence().mapNotNull { Regex("""^; CELL: block (\d+) tile (\d+)\.(\d+)$""").find(it) }) {
            val k = m.groupValues[1].toInt(); val t = m.groupValues[2].toInt() to m.groupValues[3].toInt()
            if (k !in 1..blocks.size) { problems += "the g-code prints a tile of block $k; the conditions list blocks 1 to ${blocks.size}"; continue }
            if (t.first !in 1..speeds.size || t.second !in 1..heights.size) { problems += "the g-code prints tile ${t.first}.${t.second} of block $k, outside its ${speeds.size} x ${heights.size} grid"; continue }
            printed.getOrPut(k) { HashSet() } += t
        }
        for ((k, bl) in blocks) for (r in 1..speeds.size) for (c in 1..heights.size) {
            val p = (r to c) in printed[k].orEmpty(); val o = (r to c) in bl.leftOut
            if (p && o) problems += "tile $r.$c of block $k is printed, but its conditions list it as left out"
            if (!p && !o) problems += "tile $r.$c of block $k is neither printed nor listed as left out"
        }
        require(problems.isEmpty()) { "$fileName: the plate's conditions and its tiles disagree; no window written:\n" + problems.joinToString("\n") { "  $it" } }
        val temps = blocks.map { it.second.tempC }
        require(temps.distinct().size == temps.size) { "$fileName: two blocks at one temperature (${temps.joinToString()})" }

        val filament = fil.groupValues[1]; val tool = fil.groupValues[2]; val madeFor = fil.groupValues[3]
        val key = plateKey ?: madeFor
        val made = MadeBy.text(lines, written)?.let { "made by $it: " } ?: ""
        val sb = StringBuilder()
        sb.append("# The first-layer window to grade, from $fileName (`plymeld calibrate first-layer window`).\n")
        sb.append("# Replace every \"?\" with good, marginal, or bad, as that tile printed; \"over\" marks a tile the plate left out\n")
        sb.append("# (over the melt limit). plymeld refuses a window with a \"?\" left. Each table lies as the plate does seen from\n")
        sb.append("# the front: a row per speed, the fastest at the top (the back of the plate), a column per nozzle height, low\n")
        sb.append("# to high, left to right.\n")
        if (key != madeFor) sb.append("# The plate was made for build plate '$madeFor'; this window is for '$key' (--plate).\n")
        sb.append("[${ConfigFiles.pathText(listOf("hardware", "filament", filament, "first_layer", "window", key))}]\n")
        sb.append("from = \"$fileName, ${made}tool $tool, build plate $madeFor\"\n")
        sb.append("bore = \"${num(boreMm)} mm\"\n")
        for ((k, bl) in blocks.sortedBy { it.second.tempC }) {
            sb.append("\"${bl.tempC} C\" = '''\n")
            sb.append(FirstLayerWindow.format(speeds.toDoubleArray(), heights.toDoubleArray()) { r, c -> if ((r + 1 to c + 1) in printed[k].orEmpty()) "?" else "over" })
            sb.append("'''\n")
        }
        val text = sb.toString()
        // read the window back: its cells must be the plate's tiles, "?" exactly where a tile printed
        val back = ConfigFiles.readToml(text, fileName)
        for ((k, bl) in blocks) {
            val e = back.single { it.path.last() == "${bl.tempC} C" }
            val (g, p) = FirstLayerWindow.parse(e.value as String, bl.tempC.toDouble(), e.key, e.source, ungradedAllowed = true)
            check(g != null && p.isEmpty()) { "the window written for block $k does not read back: $p" }
            for (r in speeds.indices) for (c in heights.indices) {
                val want = if ((r + 1 to c + 1) in printed[k].orEmpty()) FirstLayerWindow.Grade.UNGRADED else FirstLayerWindow.Grade.OVER
                check(g.speeds[r] == speeds[r] && g.heights[c] == heights[c] && g.grade(r, c) == want) { "the window written for block $k reads back wrong at tile ${r + 1}.${c + 1}" }
            }
        }
        return text
    }

    private fun num(v: Double) = if (v == Math.floor(v) && Math.abs(v) < 1e15) v.toLong().toString() else String.format(Locale.ROOT, "%.6f", v).trimEnd('0').trimEnd('.')
}
