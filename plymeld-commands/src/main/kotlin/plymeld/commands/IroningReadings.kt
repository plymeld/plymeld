package plymeld.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import plymeld.config.ConfigFiles
import plymeld.core.calibrate.IroningInstructions
import java.util.Locale

/**
 * `plymeld calibrate ironing readings PLATE.gcode [-o FILE]`: the sheet to grade for a printed ironing plate (IroningPlate), so
 * nobody types the zone plan from scratch: an entry per zone with its values from the plate's g-code (its conditions block
 * and its cells), its grade "?". The person grading replaces each "?" with good, marginal, or bad; `calibrate ironing values`
 * refuses a sheet with a "?" left.
 */
class IroningReadingsCommand : CliktCommand(name = "readings") {
    private val plate by argument("PLATE", help = "the ironing plate's g-code, as `plymeld calibrate ironing` wrote it").path(mustExist = true)
    private val output by option("-o", "--output", help = "write the sheet to this TOML file (default: print it)").path()

    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "Write the readings sheet to grade for a printed ironing plate: an entry per zone with its values, its grade '?'. Replace each '?' with good, marginal, or bad, then turn the sheet into the ironing values with `calibrate ironing values`."

    override fun run() {
        val text = try { IroningRecord.blank(java.nio.file.Files.readString(plate), plate.fileName.toString(), MadeBy.dateOf(plate)) }
            catch (e: IllegalArgumentException) { throw UsageError(e.message ?: "not an ironing plate") }
        output?.let { out ->
            out.parent?.let { java.nio.file.Files.createDirectories(it) }
            java.nio.file.Files.writeString(out, text)
            echo("ironing readings to grade -> $out: replace each \"?\" with good, marginal, or bad, then `plymeld calibrate ironing values $out -o ironing.toml`")
        } ?: echo(text, trailingNewline = false)
    }
}

/**
 * `plymeld calibrate ironing values READINGS.toml [-o FILE]`: the graded sheet turned into the ironing values
 * (`plan.technique.ironing.flow`, `.spacing`, `.speed`, `.angle`, `.inset`) with where they came from: per setting the value
 * whose zones graded best, the best single zone beside them. The written file is a config file: add it with `-cf`.
 */
class IroningValuesCommand : CliktCommand(name = "values") {
    private val sheet by argument("READINGS", help = "the graded readings sheet, as `plymeld calibrate ironing readings` wrote it and you graded it").path(mustExist = true)
    private val output by option("-o", "--output", help = "write the values to this TOML config file (default: print it)").path()

    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "Turn a graded ironing readings sheet into the ironing values (plan.technique.ironing.*): per setting the value whose zones graded best, with where it came from, and the best single zone beside them. Add the written file with -cf."

    override fun run() {
        val (text, report) = try { IroningRecord.values(java.nio.file.Files.readString(sheet), sheet.fileName.toString()) }
            catch (e: IllegalArgumentException) { throw UsageError(e.message ?: "not a graded ironing sheet") }
        report.forEach { echo(it) }
        output?.let { out ->
            out.parent?.let { java.nio.file.Files.createDirectories(it) }
            java.nio.file.Files.writeString(out, text)
            echo("ironing values -> $out: add it to your config (-cf)")
        } ?: echo(text, trailingNewline = false)
    }
}

/** Reading an ironing plate's g-code back into the sheet to grade, and a graded sheet into the ironing values. */
object IroningRecord {
    /** One zone as the plate's conditions describe it: its place, its block, and its values (null for a control). */
    class Zone(val index: Int, val column: Int, val row: String, val block: Int, val control: Boolean, val speed: String?, val flow: String?, val spacing: String?, val angle: String?, val inset: String?)

    /** The settings a zone varies, in the sheet's and the values' order, with their config paths. */
    val SETTINGS = listOf("speed", "flow", "spacing", "angle", "inset")

    /**
     * The sheet to grade for the plate in [gcode] (named [fileName], last written on [written] when known). Refused
     * (IllegalArgumentException, every problem listed) when the g-code is not an ironing plate, or its conditions and its cells disagree: a zone listed as ironed with no
     * cell, a control with one, a cell for a zone the conditions do not list.
     */
    fun blank(gcode: String, fileName: String, written: java.time.LocalDate? = null): String {
        val lines = gcode.lines()
        val start = lines.indexOf("; CALIBRATION_PLATE_START"); val end = lines.indexOf("; CALIBRATION_PLATE_END")
        require(start >= 0 && end > start) { "$fileName: no calibration conditions block (; CALIBRATION_PLATE_START ... _END): not a plate plymeld made" }
        val cond = lines.subList(start + 1, end)
        fun value(name: String) = cond.firstOrNull { it.startsWith("; $name = ") }?.removePrefix("; $name = ")
        require(value("plate")?.startsWith("ironing") == true) { "$fileName: not an ironing plate (its conditions say plate = ${value("plate")})" }
        val fil = value("filament")?.let { Regex("""^(\S+), tool (\d+), build plate (\S+)$""").find(it) }
            ?: throw IllegalArgumentException("$fileName: its conditions name no filament, tool, and build plate ('; filament = <key>, tool <n>, build plate <key>')")
        val zones = cond.mapNotNull { l -> Regex("""^; zone (\d+) = column (\d+), (front|back), block (\d+): (.*)$""").find(l) }.map { m ->
            val k = m.groupValues[1].toInt(); val rest = m.groupValues[5]
            if (rest.startsWith("control")) Zone(k, m.groupValues[2].toInt(), m.groupValues[3], m.groupValues[4].toInt(), true, null, null, null, null, null)
            else {
                val v = Regex("""^speed (\S+) mm/s, flow (\S+) %, spacing (\S+) mm, angle (\S+) deg, inset (\S+) mm$""").find(rest)
                    ?: throw IllegalArgumentException("$fileName: zone $k's conditions do not give its speed, flow, spacing, angle, and inset ('$rest')")
                Zone(k, m.groupValues[2].toInt(), m.groupValues[3], m.groupValues[4].toInt(), false, "${v.groupValues[1]} mm/s", "${v.groupValues[2]}%", "${v.groupValues[3]} mm", "${v.groupValues[4]} deg", "${v.groupValues[5]} mm")
            }
        }
        require(zones.isNotEmpty()) { "$fileName: its conditions list no zones ('; zone <k> = column <c>, front|back, block <b>: ...')" }
        require(zones.map { it.index } == (1..zones.size).toList()) { "$fileName: its zones are numbered ${zones.joinToString { "${it.index}" }}, not 1 to ${zones.size}" }
        // the cells the g-code irons against the conditions
        val problems = ArrayList<String>()
        val cells = lines.mapNotNull { Regex("""^; CELL: zone (\d+)$""").find(it)?.groupValues?.get(1)?.toInt() }.toSet()
        for (z in zones) {
            if (z.control && z.index in cells) problems += "zone ${z.index} is a control by its conditions, but the g-code irons it"
            if (!z.control && z.index !in cells) problems += "zone ${z.index} is ironed by its conditions, but the g-code has no cell for it"
        }
        for (c in cells) if (c !in 1..zones.size) problems += "the g-code irons a zone $c; the conditions list zones 1 to ${zones.size}"
        require(problems.isEmpty()) { "$fileName: the plate's conditions and its cells disagree; no sheet written:\n" + problems.joinToString("\n") { "  $it" } }
        val made = MadeBy.text(lines, written)
        val sb = StringBuilder()
        sb.append("# The ironing readings to grade, from $fileName (`plymeld calibrate ironing readings`).\n")
        sb.append("# Replace every \"?\" with ${IroningInstructions.GRADES.joinToString(", ")}: how the zone's top came out against its block's control (its page says how to\n")
        sb.append("# look); write what you saw in notes. Grade the controls too. `plymeld calibrate ironing values` refuses a sheet with a \"?\" left.\n")
        sb.append("# The zones' values are the plate's: leave them as they are. Fill in printed and graded (dates) for the record.\n")
        sb.append("plate = \"$fileName, ${made?.let { "made by $it, " } ?: ""}filament ${fil.groupValues[1]}, tool ${fil.groupValues[2]}, build plate ${fil.groupValues[3]}\"\n")
        sb.append("filament = \"${fil.groupValues[1]}\"\ntool = \"${fil.groupValues[2]}\"\n")
        sb.append("printed = \"\"\n")
        sb.append("graded = \"\"\n")
        for (z in zones) {
            sb.append("\n[zone.${z.index}]\n")
            // every value in quotes, as plymeld's files are read
            sb.append("column = \"${z.column}\"\nrow = \"${z.row}\"\nblock = \"${z.block}\"\n")
            if (z.control) sb.append("control = \"yes\"\nspeed = \"${speedOf(zones, z.block)}\"\n")
            else sb.append("speed = \"${z.speed}\"\nflow = \"${z.flow}\"\nspacing = \"${z.spacing}\"\nangle = \"${z.angle}\"\ninset = \"${z.inset}\"\n")
            sb.append("grade = \"?\"\nnotes = \"\"\n")
        }
        val text = sb.toString()
        // read it back: every zone there, ungraded
        val back = parse(text, fileName, ungradedAllowed = true)
        check(back.zones.size == zones.size && back.zones.all { it.grade == null }) { "the sheet written does not read back" }
        return text
    }

    /** The speed of block [block], from any of its ironed zones (a control's conditions name none). */
    private fun speedOf(zones: List<Zone>, block: Int): String = zones.firstOrNull { it.block == block && !it.control }?.speed ?: "?"

    /** A zone as read from a sheet: its values as written, and its grade (null = not graded). */
    class Reading(val index: Int, val block: Int, val control: Boolean, val values: Map<String, String>, val grade: String?, val notes: String)
    class Sheet(val plate: String, val printed: String, val graded: String, val zones: List<Reading>,
                /** The filament the plate was printed with (its config key) and the tool's index, from the sheet's own lines, else the plate line. */
                val filament: String?, val tool: String?)

    /** Reads a sheet. Refused (IllegalArgumentException) when it is not one, or a grade is missing (unless [ungradedAllowed]) or not a grade. */
    fun parse(text: String, name: String, ungradedAllowed: Boolean = false): Sheet {
        val entries = try { ConfigFiles.readToml(text, name) } catch (e: IllegalArgumentException) { throw IllegalArgumentException("$name: not TOML: ${e.message}") }
        fun top(k: String) = entries.firstOrNull { it.path == listOf(k) }?.value?.toString()
        val plate = top("plate") ?: throw IllegalArgumentException("$name: no 'plate = ...' line: not an ironing readings sheet (`plymeld calibrate ironing readings` writes one)")
        val problems = ArrayList<String>()
        val byZone = entries.filter { it.path.size == 3 && it.path[0] == "zone" }.groupBy { it.path[1].toIntOrNull() }
        if (byZone.containsKey(null)) problems += "$name: a [zone.<n>] table whose n is not a number"
        val zones = byZone.filterKeys { it != null }.toSortedMap(compareBy { it }).map { (k, es) ->
            val v = es.associate { it.path[2] to it.value.toString() }
            val control = v["control"] == "yes"
            val grade = v["grade"]?.trim()?.lowercase()
            val src = es.firstOrNull { it.path[2] == "grade" }?.source ?: name
            when {
                grade == null -> problems += "zone $k has no grade line"
                grade == "?" -> if (!ungradedAllowed) problems += "zone $k is not graded yet ('?', $src): write ${IroningInstructions.GRADES.joinToString(", ")} as the zone came out"
                grade !in IroningInstructions.GRADES -> problems += "zone $k: '$grade' is not a grade (${IroningInstructions.GRADES.joinToString(", ")}; $src)"
            }
            if (!control) for (st in SETTINGS) if (v[st] == null) problems += "zone $k has no $st (the sheet's values are the plate's: write the sheet again with `plymeld calibrate ironing readings`)"
            Reading(k!!, v["block"]?.toIntOrNull() ?: run { problems += "zone $k has no block"; 0 }, control, SETTINGS.mapNotNull { st -> v[st]?.let { st to it } }.toMap(), grade?.takeIf { it != "?" }, v["notes"] ?: "")
        }
        if (zones.isEmpty()) problems += "$name: no [zone.<n>] tables"
        if (problems.isNotEmpty()) throw IllegalArgumentException("$name: " + (if (problems.size == 1) problems[0] else "${problems.size} problems:\n" + problems.joinToString("\n") { "  $it" }))
        return Sheet(plate, top("printed") ?: "", top("graded") ?: "", zones,
            top("filament") ?: Regex("""filament (\S+), tool (\d+)""").find(plate)?.groupValues?.get(1), top("tool") ?: Regex("""filament (\S+), tool (\d+)""").find(plate)?.groupValues?.get(2))
    }

    /** A grade's score: good 2, marginal 1, bad 0. */
    private fun score(g: String) = when (g) { "good" -> 2; "marginal" -> 1; else -> 0 }

    /**
     * The graded sheet [text] (named [name]) as the ironing values: a config file's text (`[plan.technique.ironing]`, each
     * value with where it came from in the comments above) and a report (the same, line by line). Per setting, the value with
     * the best mean score over its zones (good 2, marginal 1, bad 0); a tie goes to the value nearest the middle of the list
     * (the plan centers on the filament's current value). The best single zone (the highest-graded ironed zone agreeing with
     * the most winners) is named beside them, and the controls' grades.
     */
    fun values(text: String, name: String): Pair<String, List<String>> {
        val sheet = parse(text, name)
        val ironed = sheet.zones.filter { !it.control }
        require(ironed.isNotEmpty()) { "$name: no ironed zone to read" }
        val report = ArrayList<String>()
        val chosen = LinkedHashMap<String, String>()
        val lines = ArrayList<String>()
        for (st in SETTINGS) {
            // the values in the order the plan lists them (low to high: by their number, not by the zone each first appears in)
            val byValue = LinkedHashMap<String, MutableList<Reading>>()
            for (z in ironed) byValue.getOrPut(z.values.getValue(st)) { ArrayList() } += z
            val keys = byValue.keys.sortedBy { k -> Regex("-?[0-9.]+").find(k)?.value?.toDoubleOrNull() ?: Double.MAX_VALUE }
            val means = keys.associateWith { k -> byValue.getValue(k).map { score(it.grade!!) }.average() }
            val best = means.values.max()
            val mid = (keys.size - 1) / 2.0
            val winner = keys.filter { Math.abs(means.getValue(it) - best) < 1e-9 }.minBy { Math.abs(keys.indexOf(it) - mid) }
            chosen[st] = winner
            val others = keys.filter { it != winner }.joinToString("; ") { k -> "$k: ${fmt(means.getValue(k))}" }
            lines += "$st $winner: mean ${fmt(means.getValue(winner))} over zones ${byValue.getValue(winner).joinToString(", ") { "${it.index}" }}" + (if (others.isEmpty()) "" else " ($others)") +
                (if (keys.size > 1 && keys.count { Math.abs(means.getValue(it) - best) < 1e-9 } > 1) "; a tie: the value nearest the middle of the list" else "")
        }
        val topScore = ironed.maxOf { score(it.grade!!) }
        val bestZone = ironed.filter { score(it.grade!!) == topScore }.maxBy { z -> SETTINGS.count { chosen[it] == z.values[it] } }
        val agree = SETTINGS.count { chosen[it] == bestZone.values[it] }
        val controls = sheet.zones.filter { it.control }
        val head = buildList {
            add("# Ironing values from the plate ${sheet.plate}" + (if (sheet.printed.isNotBlank()) "; printed ${sheet.printed}" else "") + (if (sheet.graded.isNotBlank()) "; graded ${sheet.graded}" else "") + " (the sheet $name, `plymeld calibrate ironing values`).")
            add("# Per setting, the value whose zones graded best (good 2, marginal 1, bad 0, the mean over its zones):")
            lines.forEach { add("#   $it") }
            add("# The best single zone: zone ${bestZone.index} (${bestZone.grade}: ${SETTINGS.joinToString(", ") { "$it ${bestZone.values[it]}" }}), agreeing with the values below on $agree of ${SETTINGS.size} settings" +
                (if (agree < SETTINGS.size) "; where they disagree the settings may interact: prefer what you can see" else "") + ".")
            if (controls.isNotEmpty()) add("# The controls (not ironed): " + controls.joinToString(", ") { "zone ${it.index} ${it.grade}" } + ".")
            if (sheet.filament != null) {
                add("# The filament's own ironing values (hardware.filament.${sheet.filament}.technique.ironing): they apply wherever this filament prints, over the")
                add("# bundled defaults and the job's general ironing values, under a value set for an object or a part; narrow them to the tool they were")
                add("# measured in (tool ${sheet.tool ?: "?"}) by writing them under hardware.filament.${sheet.filament}.tool.<key>.technique.ironing instead.")
                add("[hardware.filament.${sheet.filament}.technique.ironing]")
            } else {
                add("# These are the job's ironing values (plan.technique.ironing; the sheet names no filament): they apply wherever this file is read (-cf).")
                add("[plan.technique.ironing]")
            }
            for (st in SETTINGS) add("$st = \"${chosen.getValue(st)}\"")
        }
        report += lines.map { "  $it" }
        report.add(0, "ironing values from ${sheet.plate}:")
        report += "  best zone: ${bestZone.index} (${bestZone.grade}), agreeing on $agree of ${SETTINGS.size}"
        val out = head.joinToString("\n") + "\n"
        // the file reads back as config entries at the five paths
        val back = ConfigFiles.readToml(out, name)
        val under = if (sheet.filament != null) listOf("hardware", "filament", sheet.filament, "technique", "ironing") else listOf("plan", "technique", "ironing")
        check(SETTINGS.all { st -> back.any { it.path == under + st } }) { "the values written do not read back" }
        return out to report
    }

    private fun fmt(v: Double) = String.format(Locale.ROOT, "%.2f", v)
}
