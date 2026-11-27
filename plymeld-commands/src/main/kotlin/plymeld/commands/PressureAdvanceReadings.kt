package plymeld.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.path
import plymeld.config.ConfigFiles
import plymeld.config.Dimension
import plymeld.core.calibrate.PressureAdvanceInstructions
import plymeld.core.calibrate.PressureAdvanceSpec

/**
 * `plymeld calibrate pressure-advance readings PLATE.gcode [-o FILE]`: the sheet to fill in for a printed pressure-advance
 * plate: an entry per wall with its value and flow step from the plate's conditions block, its readings "?". The person
 * reading replaces each "?" (groove, flat, or ridge at each mark; bulge, sharp, or round at a corner);
 * `calibrate pressure-advance values` refuses a sheet with a "?" left.
 */
class PressureAdvanceReadingsCommand : CliktCommand(name = "readings") {
    private val plate by argument("PLATE", help = "the pressure-advance plate's g-code, as `plymeld calibrate pressure-advance` wrote it").path(mustExist = true)
    private val output by option("-o", "--output", metavar = "FILE", help = "write the sheet to this TOML file (default: print it)").path()

    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "Write the readings sheet for a printed pressure-advance plate: an entry per wall with its value, each reading '?'. Replace each '?' with groove, flat, or ridge (just after the speed-up and the slow-down marks; a corner: bulge, sharp, or round), then turn the sheet into a pressure advance with `calibrate pressure-advance values`."

    override fun run() {
        val text = try { PressureAdvanceRecord.blank(java.nio.file.Files.readString(plate), plate.fileName.toString(), MadeBy.dateOf(plate)) }
            catch (e: IllegalArgumentException) { throw IllegalArgumentException(e.message ?: "not a pressure-advance plate") }
        output?.let { out ->
            out.parent?.let { java.nio.file.Files.createDirectories(it) }
            java.nio.file.Files.writeString(out, text)
            echo("pressure-advance readings to fill in -> $out: replace each \"?\", then `plymeld calibrate pressure-advance values $out`")
        } ?: echo(text, trailingNewline = false)
    }
}

/**
 * `plymeld calibrate pressure-advance values READINGS.toml [--into filament|tool] [--dry-run]`: a filled-in sheet turned into
 * a pressure advance and written into the config file that defines the filament (`hardware.filament.<key>.pressure_advance`)
 * or, with `--into tool`, the tool (`hardware.tool.<key>.pressure_advance`), with the plate, its conditions, and the bracket
 * in the comment above it; recorded for `plymeld journal --undo`. A confirm plate's sheet says whether the value holds (and
 * writes it where it does); one from a machine that sets its own writes nothing.
 */
class PressureAdvanceValuesCommand : FilamentValuesCommand("pressure-advance plate") {
    private val sheet by argument("READINGS", help = "the filled-in readings sheet, as `plymeld calibrate pressure-advance readings` wrote it").path(mustExist = true)
    private val into by option("--into", metavar = "filament|tool", help = "write the value as the filament's (hardware.filament.<key>.pressure_advance, the default) or the tool's (hardware.tool.<key>.pressure_advance)").choice("filament", "tool").default("filament")

    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "Turn a filled-in pressure-advance readings sheet into a pressure advance: on the find plate the middle of the bracket between the highest groove and the lowest ridge at the speed-up marks (flat walls inside it), refused when nothing flips, the two rows disagree by more than one step (one wall's place on the plate), the readings are out of order, a corner contradicts its wall, or a wall's two marks read alike; " +
            "on the confirm plate whether the value holds at the three flow steps, depends on the flow, or gives none. Writes it into the config file that defines the filament (among the files -cf names; else plymeld's config folder's), or the tool's with --into tool, the plate, its conditions, and the bracket in the comment above it; prints the change, checks it, writes it, and records it for `plymeld journal --undo`."

    override fun run() {
        val r = try { PressureAdvanceRecord.values(java.nio.file.Files.readString(sheet), sheet.fileName.toString()) }
            catch (e: IllegalArgumentException) { throw IllegalArgumentException(e.message ?: "not a filled-in pressure-advance sheet") }
        r.report.forEach { echo(it) }
        val v = r.value ?: return
        val t = target(r.tool?.toIntOrNull(), r.filament, toolFile = into == "tool")
        val path = if (into == "tool") ConfigFiles.pathText(listOf("hardware", "tool", t.tool, "pressure_advance")) else ConfigFiles.pathText(listOf("hardware", "filament", t.filament, "pressure_advance"))
        val value = "${PressureAdvanceRecord.n(v)} s"
        val comment = listOf(
            "The pressure advance, from a pressure-advance plate on '${t.machine}', tool ${t.toolN} (plymeld calibrate pressure-advance values, ${java.time.LocalDate.now()}): ${r.plate}.",
            "Conditions: ${r.conditions}.",
            r.provenance)
        val before = java.nio.file.Files.readString(t.file)
        val after = TomlEdit.setWithComment(before, path, value, comment)
        try { ConfigFiles.readToml(after, t.file.toString()) } catch (e: IllegalArgumentException) { error("the change would leave ${t.file} unreadable (${e.message}); nothing written") }
        val got = valueWith(t, path, value, Dimension.TIME)
        check(Math.abs(got - v) < 1e-9) { "the value written gives $got s, not $v s: nothing written" }
        val shown = comment.map { "# $it" } + "$path = \"$value\"" + listOfNotNull(TomlEdit.get(before, path)?.let { "(was \"$it\")" })
        commit(t, before, after, shown, "plymeld calibrate pressure-advance values ${sheet.fileName}" + (if (into == "tool") " --into tool" else ""),
            under = if (into == "tool") "hardware.tool.${t.tool}" else "hardware.filament.${t.filament}")
    }
}

/** Reading a pressure-advance plate's g-code back into the sheet to fill in, and a filled-in sheet into a value. */
object PressureAdvanceRecord {
    /** One wall from the plate's conditions: its row and place (1-based), value (s; null = the machine's own), fast speed, and flow step (1..3). */
    class PlateWall(val row: Int, val position: Int, val value: Double?, val fast: Double, val step: Int) { val rowName: String get() = if (row == 1) "front" else "back" }

    /** The sheet for the plate in [gcode] (named [fileName], last written on [written] when known); refused when it is not a pressure-advance plate. */
    fun blank(gcode: String, fileName: String, written: java.time.LocalDate? = null): String {
        val lines = gcode.lines()
        val start = lines.indexOf("; CALIBRATION_PLATE_START"); val end = lines.indexOf("; CALIBRATION_PLATE_END")
        require(start >= 0 && end > start) { "$fileName: no calibration conditions block (; CALIBRATION_PLATE_START ... _END): not a plate plymeld made" }
        val cond = lines.subList(start + 1, end)
        fun value(name: String) = cond.firstOrNull { it.startsWith("; $name = ") }?.removePrefix("; $name = ")
        val kind = value("plate")?.let { Regex("""^pressure-advance (find|confirm)\b""").find(it)?.groupValues?.get(1) }
            ?: throw IllegalArgumentException("$fileName: not a pressure-advance plate (its conditions say plate = ${value("plate")})")
        val fil = value("filament")?.let { Regex("""^(\S+), tool (\d+), build plate (\S+)$""").find(it) }
            ?: throw IllegalArgumentException("$fileName: its conditions name no filament, tool, and build plate ('; filament = <key>, tool <n>, build plate <key>')")
        val walls = cond.mapNotNull { Regex("""^; wall (\d+)\.(\d+) = (.+), fast ([0-9.]+) mm/s, flow step (\d)$""").find(it) }.map { m ->
            val v = m.groupValues[3].let { t -> if (t == "the machine's own") null else t.removeSuffix(" s").toDoubleOrNull() ?: throw IllegalArgumentException("$fileName: wall ${m.groupValues[1]}.${m.groupValues[2]}'s value '$t' is not seconds") }
            PlateWall(m.groupValues[1].toInt(), m.groupValues[2].toInt(), v, m.groupValues[4].toDouble(), m.groupValues[5].toInt())
        }
        require(walls.isNotEmpty()) { "$fileName: its conditions list no walls ('; wall <row>.<n> = <value> s, fast <v> mm/s, flow step <k>')" }
        val cells = lines.mapNotNull { Regex("""^; CELL: wall (\d+)\.(\d+)$""").find(it)?.groupValues?.let { g -> g[1].toInt() to g[2].toInt() } }.toSet()
        val problems = walls.filter { (it.row to it.position) !in cells }.map { "wall ${it.row}.${it.position} is listed by the conditions, but the g-code prints no such wall" } +
            cells.filter { c -> walls.none { it.row to it.position == c } }.map { "the g-code prints a wall ${it.first}.${it.second} the conditions do not list" }
        require(problems.isEmpty()) { "$fileName: the plate's conditions and its cells disagree; no sheet written:\n" + problems.joinToString("\n") { "  $it" } }
        val made = MadeBy.text(lines, written)
        val find = kind == "find"
        val conditions = listOfNotNull(value("speeds"), value("walls"), value("temperature")).joinToString("; ")
        val sb = StringBuilder()
        sb.append("# The pressure-advance readings to fill in, from $fileName (`plymeld calibrate pressure-advance readings`).\n")
        sb.append("# Replace every \"?\": speed_up and slow_down with ${PressureAdvanceInstructions.MARKS.joinToString(", ")} (just after each mark, the plate's page says how to look)" +
            (if (find) "; corner with ${PressureAdvanceInstructions.CORNERS.joinToString(", ")}" else "") + ".\n")
        sb.append("# The walls' values and steps are the plate's: leave them as they are. `plymeld calibrate pressure-advance values` refuses a sheet with a \"?\" left.\n")
        sb.append("plate = \"${q("$fileName, ${made?.let { "made by $it, " } ?: ""}filament ${fil.groupValues[1]}, tool ${fil.groupValues[2]}, build plate ${fil.groupValues[3]}")}\"\n")
        sb.append("kind = \"$kind\"\nfilament = \"${fil.groupValues[1]}\"\ntool = \"${fil.groupValues[2]}\"\n")
        sb.append("conditions = \"${q(conditions)}\"\n")
        value("settles_up_to")?.let { sb.append("settles_up_to = \"${q(it.substringBefore(" ("))}\"\n") }
        value("top")?.let { sb.append("top = \"${q(it)}\"\n") }
        value("made_with")?.let { sb.append("made_with = \"${q(it)}\"\n") }
        value("output")?.let { sb.append("output = \"${q(it)}\"\n") }
        sb.append("printed = \"\"\nread = \"\"\n")
        for (w in walls) {
            sb.append("\n[wall.${w.rowName}.${w.position}]\n")
            sb.append("value = \"${w.value?.let { "${n(it)} s" } ?: "machine"}\"\nfast = \"${n(w.fast)} mm/s\"\nstep = \"${w.step}\"\n")
            sb.append("speed_up = \"?\"\nslow_down = \"?\"\n")
            if (find) sb.append("corner = \"?\"\n")
            sb.append("notes = \"\"\n")
        }
        val text = sb.toString()
        val back = parse(text, fileName, unreadAllowed = true)
        check(back.walls.size == walls.size) { "the sheet written does not read back" }
        return text
    }

    /** A wall as read from a sheet: its row ("front", "back"), place, value (null = the machine's), flow step, and readings (null = "?"). */
    class Wall(val row: String, val position: Int, val value: Double?, val step: Int, val speedUp: String?, val slowDown: String?, val corner: String?) {
        val name: String get() = "the $row row's wall $position (${value?.let { "${n(it)} s" } ?: "the machine's own"}${if (step > 1) ", flow step $step" else ""})"
        /** Below the true value (-1: a groove at the speed-up mark, or a ridge at the slow-down mark), at it (0), above it (+1). */
        val sign: Int get() { val u = upSign(speedUp); return if (u != 0) u else -upSign(slowDown) }
        /** Both marks read alike (two grooves, two ridges): not pressure's. */
        val void: Boolean get() = speedUp == slowDown && speedUp != "flat"
        val cornerSign: Int get() = when (corner) { "bulge" -> -1; "round" -> 1; else -> 0 }
        private fun upSign(m: String?) = when (m) { "groove" -> -1; "ridge" -> 1; else -> 0 }
    }
    class Sheet(val plate: String, val kind: String, val filament: String?, val tool: String?, val conditions: String, val settles: String?, val walls: List<Wall>,
                /** The top of a first plate and why it is there; the options it was made with (as a shell takes them); its g-code's path as given. */
                val top: String? = null, val madeWith: String? = null, val output: String? = null)

    /** Reads a sheet. Refused (IllegalArgumentException) when it is not one, or a reading is missing (unless [unreadAllowed]) or not a reading. */
    fun parse(text: String, name: String, unreadAllowed: Boolean = false): Sheet {
        val entries = try { ConfigFiles.readToml(text, name) } catch (e: IllegalArgumentException) { throw IllegalArgumentException("$name: not TOML: ${e.message}") }
        fun top(k: String) = entries.firstOrNull { it.path == listOf(k) }?.value?.toString()
        val plate = top("plate") ?: throw IllegalArgumentException("$name: no 'plate = ...' line: not a pressure-advance readings sheet (`plymeld calibrate pressure-advance readings` writes one)")
        val kind = top("kind")?.takeIf { it == "find" || it == "confirm" } ?: throw IllegalArgumentException("$name: no kind = \"find\" or \"confirm\": not a pressure-advance readings sheet")
        val problems = ArrayList<String>()
        val byWall = entries.filter { it.path.size == 4 && it.path[0] == "wall" }.groupBy { it.path[1] to it.path[2] }
        val walls = byWall.entries.sortedWith(compareBy({ if (it.key.first == "front") 0 else 1 }, { it.key.second.toIntOrNull() ?: 0 })).map { (k, es) ->
            val v = es.associate { it.path[3] to it.value.toString().trim().lowercase() }
            val where = "the ${k.first} row's wall ${k.second}"
            fun reading(key: String, allowed: List<String>): String? {
                val r = v[key]
                when {
                    r == null -> problems += "$where has no $key line"
                    r == "?" -> if (!unreadAllowed) problems += "$where: $key is not read yet ('?'): write ${allowed.joinToString(", ")}"
                    r !in allowed -> problems += "$where: $key '$r' is not ${allowed.joinToString(", ")}"
                }
                return r?.takeIf { it in allowed }
            }
            val value = v["value"]?.let { t -> if (t == "machine") null else t.removeSuffix("s").trim().toDoubleOrNull() ?: run { problems += "$where: value '$t' is not seconds"; null } }
            if (k.first != "front" && k.first != "back") problems += "[wall.${k.first}.${k.second}]: a row is front or back"
            Wall(k.first, k.second.toIntOrNull() ?: 0, value, v["step"]?.toIntOrNull() ?: 1, reading("speed_up", PressureAdvanceInstructions.MARKS), reading("slow_down", PressureAdvanceInstructions.MARKS),
                if (kind == "find") reading("corner", PressureAdvanceInstructions.CORNERS) else null)
        }
        if (walls.isEmpty()) problems += "no [wall.<row>.<n>] tables"
        if (problems.isNotEmpty()) throw IllegalArgumentException("$name: " + (if (problems.size == 1) problems[0] else "${problems.size} problems:\n" + problems.joinToString("\n") { "  $it" }))
        return Sheet(plate, kind, top("filament"), top("tool"), top("conditions") ?: "", top("settles_up_to"), walls, top("top"), top("made_with"), top("output"))
    }

    /** A sheet's result: what to report, the value to write (null: none), the plate and its conditions, and the value's provenance. */
    class Result(val report: List<String>, val value: Double?, val plate: String, val conditions: String, val provenance: String, val filament: String?, val tool: String?)

    /** The filled-in sheet [text] (named [name]) as a value, or refused (IllegalArgumentException) with the reason. */
    fun values(text: String, name: String): Result {
        val s = parse(text, name)
        // a wall whose two marks read alike is not pressure's
        s.walls.filter { it.void }.takeIf { it.isNotEmpty() }?.let { vs -> throw IllegalArgumentException("$name: " + vs.joinToString("; ") { "${it.name} reads ${it.speedUp} at both marks" } +
            ": the slow-down mark must read the opposite of the speed-up mark, so something other than pressure advance shows there (the firmware's acceleration limits, a slipping extruder, or a pressure that does not settle within the wall's middle" +
            (s.settles?.let { ": the plate's walls are long enough for it to settle up to a true value of $it, by plymeld's pressure model" } ?: "") + "); no value") }
        return if (s.kind == "find") find(s, name) else confirm(s, name)
    }

    private fun find(s: Sheet, name: String): Result {
        val rows = s.walls.groupBy { it.row }
        val values = s.walls.mapNotNull { it.value }.distinct().sorted()
        if (values.size < 2) throw IllegalArgumentException("$name: a find plate's walls have at least two values")
        // a step is one place on the plate (its values need not be evenly spaced): a bracket's place the middle of its two ends'
        fun place(v: Double) = values.indexOfFirst { Math.abs(it - v) < 1e-9 }.toDouble()
        // the corners confirm their walls: one reading too low where the other reads too high is refused
        s.walls.filter { it.cornerSign * it.sign < 0 }.takeIf { it.isNotEmpty() }?.let { ws -> throw IllegalArgumentException("$name: " + ws.joinToString("; ") {
            "${it.name}'s corner reads ${it.corner} (${if (it.cornerSign < 0) "too low" else "too high"}) where its wall reads ${if (it.sign < 0) "too low" else "too high"}" } + ": the corner contradicts the wall; no value") }
        class Bracket(val row: String, val lo: Double, val hi: Double) { val mid get() = (lo + hi) / 2; val at get() = (place(lo) + place(hi)) / 2 }
        val brackets = rows.map { (row, ws) ->
            val byValue = ws.sortedBy { it.value!! }
            // in order: below, at, above, never back
            byValue.zipWithNext().firstOrNull { (a, b) -> b.sign < a.sign }?.let { (a, b) ->
                throw IllegalArgumentException("$name: the $row row reads ${byValue.joinToString(", ") { word(it.sign) }} (${byValue.joinToString(", ") { n(it.value!!) }} s): out of order (${n(a.value!!)} s reads ${word(a.sign)}, ${n(b.value!!)} s ${word(b.sign)}); a misread, or a wall that is not pressure's; no value") }
            val lo = byValue.filter { it.sign < 0 }.maxOfOrNull { it.value!! }
            val hi = byValue.filter { it.sign > 0 }.minOfOrNull { it.value!! }
            if (lo == null || hi == null) throw IllegalArgumentException("$name: " + when {
                lo == null && hi == null -> "the $row row reads flat everywhere: nothing flips; the flow step is too small to show at this spacing, or the firmware sets its own value regardless; no value"
                hi == null -> "the $row row never reads a ridge: no flip, the value is above the highest, ${n(byValue.last().value!!)} s: " +
                    (s.top?.takeIf { "fit" in it }?.let { "the plate's top is $it. Reaching higher takes a longer bed axis, or a lower fast speed (--speeds SLOW,FAST, or the machine's print speed, hardware.machine.<key>.motion.print.speed), which shortens the walls; no value" }
                        ?: "raise the tool's ceiling (hardware.tool.<key>.calibrate.pressure_advance.max) or give higher --values; no value")
                else -> "the $row row never reads a groove: no flip, the value is at or below the lowest, ${n(byValue.first().value!!)} s" +
                    (if (byValue.first().value!! == 0.0) " (0: this filament and tool may need none, or the firmware adds its own)" else ": give lower --values") + "; no value"
            })
            Bracket(row, lo, hi)
        }
        if (brackets.size == 2 && Math.abs(brackets[0].at - brackets[1].at) > 1 + 1e-9)
            throw IllegalArgumentException("$name: the front row's flip (between ${n(brackets[0].lo)} and ${n(brackets[0].hi)} s) and the back row's (between ${n(brackets[1].lo)} and ${n(brackets[1].hi)} s) are more than one step (one wall's place on the plate) apart: position or print order matters; no value")
        val maxLo = brackets.maxOf { it.lo }; val minHi = brackets.minOf { it.hi }
        val (lo, hi) = if (maxLo < minHi) maxLo to minHi else brackets.minOf { it.lo } to brackets.maxOf { it.hi }
        // the value at the labels' resolution, so a confirm plate can print it
        val v = PressureAdvanceSpec.atLabel((lo + hi) / 2)
        val agree = if (brackets.size < 2) "one row" else if (maxLo < minHi) "both rows agreeing" else "the rows a step apart, their brackets joined"
        val fineStep = (hi - lo) / (PlateValues - 1)
        // the fine plate's values as it makes them, each at the labels' resolution: their steps as they come out
        val gaps = PressureAdvanceSpec.evenly(lo, hi).zipWithNext { a, b -> PressureAdvanceSpec.atLabel(b - a) }.distinct().sorted()
        val apart = if (gaps.size == 1) "${n(gaps[0])} s apart" else "${n(gaps.first())} to ${n(gaps.last())} s apart (the even step held to the labels' ${PressureAdvanceSpec.LABEL_STEP_S * 1000} ms)"
        // the confirm plate's neighbors as it makes them: a step either side of the value, each at the labels' resolution
        val step = confirmStep(lo, hi)
        val lower = PressureAdvanceSpec.atLabel(v - step); val upper = PressureAdvanceSpec.atLabel(v + step)
        val neighbors = if (lower == lo && upper == hi) "its neighbors the bracket's ends, ${n(lo)} and ${n(hi)} s, the highest groove and the lowest ridge, both at or above 0"
            else "its neighbors ${n(lower)} and ${n(upper)} s, a step either side of the value: the bracket's ends, ${n(lo)} and ${n(hi)} s, to the labels' ${PressureAdvanceSpec.LABEL_STEP_S * 1000} ms, both at or above 0"
        val report = listOf("pressure advance from ${s.plate}:") + brackets.map { "  the ${it.row} row: grooves up to ${n(it.lo)} s, ridges from ${n(it.hi)} s" } +
            "  value: ${n(v)} s, the middle of ${n(lo)} to ${n(hi)} s ($agree), +/- ${n((hi - lo) / 2)} s" +
            (if (fineStep >= PressureAdvanceSpec.LABEL_STEP_S - 1e-12) "  next, a finer value: ${next(s, "--bracket ${n(lo)}-${n(hi)}", "fine")} (${PlateValues} values, $apart)"
                else "  next: the bracket is already as narrow as the labels' ${PressureAdvanceSpec.LABEL_STEP_S * 1000} ms allow a finer plate to space") +
            "  next, to confirm it: ${next(s, "--confirm ${n(v)} --confirm-step ${n(step)}", "confirm")} ($neighbors)"
        return Result(report, v, s.plate, s.conditions,
            "Readings: the walls' speed-up marks, grooves up to ${n(lo)} s and ridges from ${n(hi)} s ($agree; the plate's values ${values.joinToString(", ") { n(it) }} s); the value the middle, +/- ${n((hi - lo) / 2)} s.", s.filament, s.tool)
    }

    private fun confirm(s: Sheet, name: String): Result {
        val steps = s.walls.map { it.step }.distinct().sorted()
        val machine = s.walls.all { it.value == null }
        if (machine) {
            val off = s.walls.filter { it.sign != 0 }
            val report = listOf("the machine's own pressure advance, from ${s.plate}:") + s.walls.map { "  flow step ${it.step}: ${word(it.sign)}" } +
                (if (off.isEmpty()) "  it holds at every flow step" else "  it reads " + off.joinToString { "${if (it.sign < 0) "low" else "high"} at flow step ${it.step}" } + ": the machine's value is off there") +
                "  nothing written: the machine sets its own (hardware.machine.<key>.firmware.own_pressure_advance)"
            return Result(report, null, s.plate, s.conditions, "", s.filament, s.tool)
        }
        val values = s.walls.mapNotNull { it.value }.distinct().sorted()
        require(values.size == 3) { "$name: a confirm plate has three values (the value and its neighbors), not ${values.size}" }
        // per step, the values in order
        for (k in steps) { val ws = s.walls.filter { it.step == k }.sortedBy { it.value!! }
            ws.zipWithNext().firstOrNull { (a, b) -> b.sign < a.sign }?.let { throw IllegalArgumentException("$name: at flow step $k the values read ${ws.joinToString(", ") { word(it.sign) }}: out of order; no value") } }
        val flatAt = steps.associateWith { k -> s.walls.filter { it.step == k && it.sign == 0 }.map { it.value!! } }
        val middle = values[1]
        val holds = steps.all { k -> s.walls.filter { it.step == k }.all { w -> w.sign == when (w.value) { values[0] -> -1; middle -> 0; else -> 1 } } }
        val lines = steps.map { k -> "  flow step $k: " + s.walls.filter { it.step == k }.sortedBy { it.value!! }.joinToString(", ") { "${n(it.value!!)} s ${word(it.sign)}" } }
        return when {
            holds -> Result(listOf("pressure advance ${n(middle)} s, confirmed on ${s.plate}:") + lines + "  it holds at every flow step", middle, s.plate, s.conditions,
                "Confirmed: flat at all three flow steps, its neighbors ${n(values[0])} s grooved and ${n(values[2])} s ridged.", s.filament, s.tool)
            flatAt.values.all { it.isEmpty() } -> throw IllegalArgumentException("$name: nothing reads flat at any flow step: the find plate was misread or the conditions changed; no value")
            else -> {
                // the true value follows the flow: the medium step's flat value, else the nearest step that has one (plymeld's choice)
                val pick = listOf(2, 1, 3).firstNotNullOf { k -> flatAt[k]?.takeIf { it.isNotEmpty() }?.let { k to it.average() } }
                val range = flatAt.values.flatten()
                Result(listOf("pressure advance from ${s.plate}: it depends on the flow:") + lines +
                    "  written: ${n(pick.second)} s, flat at flow step ${pick.first}; flat values ${n(range.min())} to ${n(range.max())} s across the steps (a value that follows the flow is not modelled)",
                    pick.second, s.plate, s.conditions,
                    "Depends on the flow: flat at ${flatAt.filterValues { it.isNotEmpty() }.entries.joinToString("; ") { (k, vs) -> "flow step $k ${vs.joinToString(", ") { n(it) }} s" }}; written the medium step's (or the nearest that has one), the range ${n(range.min())} to ${n(range.max())} s.", s.filament, s.tool)
            }
        }
    }

    /**
     * The confirm plate's step for a value read between [lo] and [hi]: half the bracket at the labels' resolution, so its
     * neighbors are the bracket's ends (the highest groove and the lowest ridge, which the find plate printed) to within the
     * labels' 0.1 ms (the value and the step each rounded to it): never below 0.
     */
    private fun confirmStep(lo: Double, hi: Double) = PressureAdvanceSpec.atLabel((hi - lo) / 2)

    /**
     * The next plate for the sheet [s]'s filament, tool, and machine, as a command to run as printed: the options its plate was
     * made with, then [options], then its g-code beside the plate's, named for it ([suffix]: pa.gcode's fine plate pa-fine.gcode).
     */
    private fun next(s: Sheet, options: String, suffix: String): String {
        val out = s.output?.let { java.nio.file.Path.of(it) }
        val stem = (out?.fileName?.toString() ?: "pressure-advance.gcode").substringBeforeLast('.')
        val file = (out?.parent?.resolve("$stem-$suffix.gcode") ?: java.nio.file.Path.of("$stem-$suffix.gcode")).toString()
        return "`plymeld calibrate pressure-advance ${s.madeWith?.let { "$it " } ?: ""}$options -o ${shell(file)}`"
    }

    /** [a] as a shell takes it as one word: as it is, or single-quoted. */
    fun shell(a: String) = if (a.matches(Regex("[A-Za-z0-9._/=:,@+%-]+"))) a else "'" + a.replace("'", "'\\''") + "'"

    /** The find plate's values per row, for the fine plate's step. */
    private val PlateValues = plymeld.core.calibrate.PressureAdvanceGeometry.values

    private fun word(sign: Int) = when { sign < 0 -> "groove"; sign > 0 -> "ridge"; else -> "flat" }
    private fun q(v: String) = v.replace("\\", "\\\\").replace("\"", "'")
    fun n(v: Double): String = java.math.BigDecimal(v).setScale(5, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
}
