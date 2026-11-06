package plymeld.commands

import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import plymeld.config.ConfigFiles
import plymeld.config.Dimension
import plymeld.config.FirstLayerWindow
import plymeld.config.Site

/**
 * `plymeld calibrate first-layer values TEMP=HEIGHT,SPEED... --plate-gcode PLATE [--tool N] [--filament NAME] [--dry-run]`:
 * a read first-layer plate's results written into the config file that defines the filament, as its first-layer window
 * on the build plate it printed on (`hardware.filament.<key>.first_layer.window.<plate>`, FirstLayerWindow): per
 * column, the lowest nozzle height graded good (every lower one bad) and the fastest speed graded good. The plate's
 * g-code gives its columns, heights, and speeds, against which the readings are checked, and the filament, tool, and
 * build plate; the tool's bore is the window's. A window already there for that plate is replaced whole.
 */
class FirstLayerValuesCommand : FilamentValuesCommand("first-layer plate") {
    private val readings by argument("TEMP=HEIGHT,SPEED", help = "a column's reading: its temperature (C) = the lowest nozzle height (mm) graded good there, every lower one bad, " +
        "and the fastest speed (mm/s) graded good, each one of the plate's own, e.g. 210=0.28,120; TEMP=none for a column with no good tile (the window leaves that temperature out)").multiple(required = true)

    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "Write a read first-layer plate's results into the config file that defines the filament (among the files -cf names, e.g. -cf 'DIR/*.toml'; else plymeld's config folder's): " +
            "its first-layer window on the build plate the plate printed on, per column the lowest good nozzle height and the fastest good speed, for the tool's bore; " +
            "the plate (--plate-gcode) in the comment above it. A window already there for that plate is replaced. Prints the change, checks it, writes it, and records it for `plymeld journal --undo`."

    override fun run() {
        val gcode = plateGcode ?: throw UsageError("--plate-gcode is needed: the first-layer plate's g-code gives its columns, heights, speeds, filament, tool, and build plate")
        val plate = try { FirstLayerRecord.read(java.nio.file.Files.readString(gcode), gcode.fileName.toString()) }
            catch (e: IllegalArgumentException) { throw UsageError(e.message ?: "not a first-layer plate") }
        val given = readings.map { parse(it) }
        given.groupBy { it.tempC }.filterValues { it.size > 1 }.keys.firstOrNull()?.let { throw UsageError("$it C is given twice: one reading per column") }
        val temps = plate.columns.map { it.tempC }
        val missing = temps - given.map { it.tempC }.toSet()
        if (missing.isNotEmpty()) throw UsageError("the plate's columns are ${temps.joinToString { "$it C" }}: give a reading for each (${missing.joinToString { "$it C" }} missing; TEMP=none for a column with no good tile)")
        (given.map { it.tempC } - temps.toSet()).firstOrNull()?.let { throw UsageError("$it C is not one of the plate's columns (${temps.joinToString { c -> "$c C" }})") }
        // each reading one the plate tried, and its tile (the fastest good speed at the lowest good height) printed in its column
        for (g in given) {
            val h = g.heightMm ?: continue; val s = g.speedMmS!!
            val c = plate.heights.indexOfFirst { Math.abs(it - h) < 1e-9 }.takeIf { it >= 0 } ?: throw UsageError("${g.tempC} C: ${mm(h)} is not one of the plate's heights (${plate.heights.joinToString { mm(it) }})")
            val r = plate.speeds.indexOfFirst { Math.abs(it - s) < 1e-9 }.takeIf { it >= 0 } ?: throw UsageError("${g.tempC} C: ${mms(s)} is not one of the plate's speeds (${plate.speeds.joinToString { mms(it) }})")
            if ((r + 1 to c + 1) !in plate.columns.single { it.tempC == g.tempC }.printed)
                throw UsageError("${g.tempC} C: the plate left out its ${mms(s)} tile at ${mm(h)} (over the melt limit), so it was not graded good")
        }
        val good = given.filter { it.heightMm != null }
        if (good.isEmpty()) throw UsageError("no column has a good tile: there is no window to write")
        val t = target(plate.tool, plate.filament)
        if (t.toolN != plate.tool) throw UsageError("the plate printed with tool ${plate.tool}; --tool says ${t.toolN}")
        val boreE = t.config.entry(listOf("hardware", "tool", t.tool, "bore")) ?: throw UsageError("tool '${t.tool}' has no bore (hardware.tool.${t.tool}.bore): a window applies only to the bore it was measured with")
        val bore = t.config.quantity(boreE, Dimension.LENGTH, Site(machine = t.machine, tool = t.tool, filament = t.filament))
        val win = listOf("hardware", "filament", t.filament, "first_layer", "window", plate.buildPlate)
        val key = ConfigFiles.pathText(win)
        val before = java.nio.file.Files.readString(t.file)
        // a window already there for this plate goes whole: the new readings replace it
        var after = TomlEdit.unset(before, key, under = true).first
        val comment = listOf(
            "The first-layer window on build plate '${plate.buildPlate}', from a first-layer plate on '${t.machine}', tool ${t.toolN} (plymeld calibrate first-layer values, ${java.time.LocalDate.now()})" + (madeBy()?.let { ": $it" } ?: "") + ".",
            "Per column, the lowest good nozzle height (every lower one bad) and the fastest good speed" +
                (given.filter { it.heightMm == null }.takeIf { it.isNotEmpty() }?.let { n -> "; no good tile at ${n.joinToString { "${it.tempC} C" }}, left out" } ?: "") + ".")
        val set = ArrayList<Pair<String, String>>()
        set += "$key.from" to "${gcode.fileName}, tool ${plate.tool}, build plate ${plate.buildPlate}"
        set += "$key.bore" to "${n(bore)} mm"
        for (g in good) {
            set += "$key.\"${g.tempC} C\".height.min" to "${n(g.heightMm!!)} mm"
            set += "$key.\"${g.tempC} C\".speed.max" to "${n(g.speedMmS!!)} mm/s"
        }
        for ((i, kv) in set.withIndex()) after = if (i == 0) TomlEdit.setWithComment(after, kv.first, kv.second, comment) else TomlEdit.set(after, kv.first, kv.second).text
        // the file reads back, and its window is the readings
        val entries = try { ConfigFiles.readToml(after, t.file.toString()) } catch (e: IllegalArgumentException) { error("the change would leave ${t.file} unreadable (${e.message}); nothing written") }
        val problems = ArrayList<String>()
        val back = FirstLayerWindow.read(entries, setOf(plate.buildPlate), problems)[t.filament]?.get(plate.buildPlate)
        check(problems.isEmpty() && back != null) { "the window written does not read back (${problems.joinToString("; ")}): nothing written" }
        check(Math.abs(back.boreMm - bore) < 1e-9 && back.readings.size == good.size && good.sortedBy { it.tempC }.zip(back.readings).all { (g, r) ->
            Math.abs(r.tempC - g.tempC) < 1e-9 && Math.abs(r.heightMinMm - g.heightMm!!) < 1e-9 && Math.abs(r.speedMaxMmS - g.speedMmS!!) < 1e-9 }) { "the window written reads back other values than the readings: nothing written" }
        val shown = comment.map { "# $it" } + set.map { (k, v) -> "${k.removePrefix("hardware.filament.${ConfigFiles.pathText(listOf(t.filament))}.")} = \"$v\"" } +
            listOfNotNull(TomlEdit.get(before, "$key.bore")?.let { "(replaces the window that was there)" })
        val line = (listOf("plymeld calibrate first-layer values") + given.map { g -> "${g.tempC}=" + (g.heightMm?.let { "${n(it)},${n(g.speedMmS!!)}" } ?: "none") } +
            listOf("--plate-gcode", gcode.fileName.toString(), "--tool", "${t.toolN}")).joinToString(" ")
        commit(t, before, after, shown, line)
    }

    /** One column's reading: its temperature, and its lowest good height (mm) and fastest good speed (mm/s), or null for none. */
    class Reading(val tempC: Int, val heightMm: Double?, val speedMmS: Double?)

    companion object {
        /** "210=0.28,120" (units allowed: 0.28mm, 120mm/s), or "210=none". */
        fun parse(a: String): Reading {
            Regex("""^\s*(\d+)\s*(?:C)?\s*=\s*none\s*$""", RegexOption.IGNORE_CASE).find(a)?.let { return Reading(it.groupValues[1].toInt(), null, null) }
            val m = Regex("""^\s*(\d+)\s*(?:C)?\s*=\s*([0-9.]+)\s*(?:mm)?\s*,\s*([0-9.]+)\s*(?:mm/s)?\s*$""").find(a)
                ?: throw UsageError("'$a': a reading is TEMP=HEIGHT,SPEED, the lowest good nozzle height (mm) and the fastest good speed (mm/s) at that column's temperature, e.g. 210=0.28,120; or TEMP=none")
            val h = m.groupValues[2].toDoubleOrNull()?.takeIf { it > 0 } ?: throw UsageError("'$a': the height is a number of mm above 0")
            val s = m.groupValues[3].toDoubleOrNull()?.takeIf { it > 0 } ?: throw UsageError("'$a': the speed is a number of mm/s above 0")
            return Reading(m.groupValues[1].toInt(), h, s)
        }
        private fun mm(v: Double) = FirstLayerWindow.mm(v)
        private fun mms(v: Double) = FirstLayerWindow.mms(v)
    }
}

/** Reading a first-layer plate's g-code back: what it tried, column by column, and what it printed. */
object FirstLayerRecord {
    /** One column: its temperature and the tiles (row, column; 1-based: speed, height) it printed. */
    class Column(val tempC: Int, val printed: Set<Pair<Int, Int>>)

    /** The plate: its filament, tool, and build plate, its speeds (mm/s) and heights (mm) as its rows are laid, its columns. */
    class Plate(val filament: String, val tool: Int, val buildPlate: String, val speeds: List<Double>, val heights: List<Double>, val columns: List<Column>)

    /**
     * The plate in [gcode] (named [fileName]). Refused (IllegalArgumentException, every problem listed) when the g-code is
     * not a first-layer plate, or its conditions and its printed tiles disagree: a tile listed as left out that is not on
     * the plate or was printed, a tile neither printed nor left out, a printed tile outside the plate or its columns.
     */
    fun read(gcode: String, fileName: String): Plate {
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
        val axes = Regex("""speeds ([0-9., ]+) mm/s \(a group of rows each, front to back\), heights ([0-9., ]+) mm \(a row each in every group, front to back\)""").find(tilesLine)
            ?: throw IllegalArgumentException("$fileName: first_layer_tiles does not give the tiles' speeds and heights")
        val speeds = axes.groupValues[1].split(',').map { it.trim().toDouble() }
        val heights = axes.groupValues[2].split(',').map { it.trim().toDouble() }
        val problems = ArrayList<String>()
        val tileRe = Regex("""tile (\d+)\.(\d+)""")
        val blocks = cond.mapNotNull { l -> Regex("""^; block (\d+) = (\d+) C,(.*)$""").find(l) }.map { m ->
            val n = m.groupValues[1].toInt()
            val out = m.groupValues[3].substringAfter("left out:", "").let { s -> tileRe.findAll(s).map { it.groupValues[1].toInt() to it.groupValues[2].toInt() }.toSet() }
            for ((r, c) in out) if (r !in 1..speeds.size || c !in 1..heights.size)
                problems += "column $n's conditions list tile $r.$c as left out; the plate has ${speeds.size} speeds x ${heights.size} heights"
            Triple(n, m.groupValues[2].toInt(), out)
        }
        require(blocks.isNotEmpty()) { "$fileName: its conditions list no columns ('; block <n> = <t> C, ...')" }
        blocks.map { it.first }.let { ns -> require(ns == (1..ns.size).toList()) { "$fileName: its columns are numbered ${ns.joinToString()}, not 1 to ${ns.size}" } }
        // the tiles the g-code prints, by its cells
        val printed = HashMap<Int, MutableSet<Pair<Int, Int>>>()
        for (m in lines.asSequence().mapNotNull { Regex("""^; CELL: block (\d+) tile (\d+)\.(\d+)$""").find(it) }) {
            val k = m.groupValues[1].toInt(); val t = m.groupValues[2].toInt() to m.groupValues[3].toInt()
            if (k !in 1..blocks.size) { problems += "the g-code prints a tile of column $k; the conditions list columns 1 to ${blocks.size}"; continue }
            if (t.first !in 1..speeds.size || t.second !in 1..heights.size) { problems += "the g-code prints tile ${t.first}.${t.second} of column $k, outside its ${speeds.size} speeds x ${heights.size} heights"; continue }
            printed.getOrPut(k) { HashSet() } += t
        }
        for ((k, _, out) in blocks) for (r in 1..speeds.size) for (c in 1..heights.size) {
            val p = (r to c) in printed[k].orEmpty(); val o = (r to c) in out
            if (p && o) problems += "tile $r.$c of column $k is printed, but its conditions list it as left out"
            if (!p && !o) problems += "tile $r.$c of column $k is neither printed nor listed as left out"
        }
        require(problems.isEmpty()) { "$fileName: the plate's conditions and its tiles disagree:\n" + problems.joinToString("\n") { "  $it" } }
        val temps = blocks.map { it.second }
        require(temps.distinct().size == temps.size) { "$fileName: two columns at one temperature (${temps.joinToString()})" }
        return Plate(fil.groupValues[1], fil.groupValues[2].toInt(), fil.groupValues[3], speeds, heights, blocks.map { (k, t, _) -> Column(t, printed[k].orEmpty()) })
    }
}
