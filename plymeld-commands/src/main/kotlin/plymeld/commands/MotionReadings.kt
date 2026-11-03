package plymeld.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import plymeld.config.ConfigFiles
import plymeld.core.calibrate.MotionGeometry
import plymeld.core.calibrate.MotionInstructions
import plymeld.core.calibrate.SeamInstructions
import java.util.Locale

/**
 * `plymeld calibrate motion readings PLATE.gcode [-o FILE]`: the sheet to grade for a printed motion plate (MotionPlate): two
 * grids, faces along X and faces along Y, a row per band (acceleration) and a column per speed, from the plate's g-code (its
 * conditions block and its cells), every cell "?". The person grading replaces each "?" with hidden, faint, or plain;
 * `calibrate motion values` refuses a sheet with a "?" left.
 */
class MotionReadingsCommand : CliktCommand(name = "readings") {
    private val plate by argument("PLATE", help = "the motion plate's g-code, as `plymeld calibrate motion` wrote it").path(mustExist = true)
    private val output by option("-o", "--output", help = "write the sheet to this TOML file (default: print it)").path()

    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "Write the readings sheet to grade for a printed motion plate: two grids (faces along X, faces along Y), a row per band (acceleration) and a column per speed, every cell '?'. Replace each '?' with hidden, faint, or plain (how far away the face's ripples show), then turn the sheet into the machine's visible speed and acceleration with `calibrate motion values`."

    override fun run() {
        val text = try { MotionRecord.blank(java.nio.file.Files.readString(plate), plate.fileName.toString(), MadeBy.dateOf(plate)) }
            catch (e: IllegalArgumentException) { throw UsageError(e.message ?: "not a motion plate") }
        output?.let { out ->
            out.parent?.let { java.nio.file.Files.createDirectories(it) }
            java.nio.file.Files.writeString(out, text)
            echo("motion readings to grade -> $out: replace each \"?\" with ${MotionInstructions.GRADES.joinToString(", ")}, then `plymeld calibrate motion values $out -o motion.toml`")
        } ?: echo(text, trailingNewline = false)
    }
}

/**
 * `plymeld calibrate motion values READINGS.toml [--clean GRADE] [-o FILE]`: the graded sheet turned into the machine's visible
 * speed and acceleration (`hardware.machine.<key>.motion.visible.speed`, `.visible.acceleration`) with where they came from.
 * The written file is a config file: add it with `-cf`.
 */
class MotionValuesCommand : CliktCommand(name = "values") {
    private val sheet by argument("READINGS", help = "the graded readings sheet, as `plymeld calibrate motion readings` wrote it and you graded it").path(mustExist = true)
    private val clean by option("--clean", metavar = "GRADE", help = "the worst grade that still counts as clean: hidden or faint (default: hidden, no ripples even close up)").default(MotionInstructions.DEFAULT_CLEAN)
    private val output by option("-o", "--output", help = "write the values to this TOML config file (default: print it)").path()

    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "Turn a graded motion readings sheet into the machine's visible speed and acceleration (hardware.machine.<key>.motion.visible.speed, .visible.acceleration): per band the fastest speed clean in both directions (and every slower speed), and of those the pair that lays a face of the plate's length quickest, with where it came from. Add the written file with -cf."

    override fun run() {
        val (text, report) = try { MotionRecord.values(java.nio.file.Files.readString(sheet), sheet.fileName.toString(), clean.trim().lowercase()) }
            catch (e: IllegalArgumentException) { throw UsageError(e.message ?: "not a graded motion sheet") }
        report.forEach { echo(it) }
        output?.let { out ->
            out.parent?.let { java.nio.file.Files.createDirectories(it) }
            java.nio.file.Files.writeString(out, text)
            echo("visible motion -> $out: add it to your config (-cf)")
        } ?: echo(text, trailingNewline = false)
    }
}

/** Reading a motion plate's g-code back into the sheet to grade, and a graded sheet into the machine's visible speed and acceleration. */
object MotionRecord {
    /** The two grids' keys in the sheet, and what each is. */
    val GRIDS = listOf("along_x" to "faces along X (they show the head's wobble in Y)", "along_y" to "faces along Y (they show the head's wobble in X)")

    /**
     * The sheet to grade for the plate in [gcode] (named [fileName], last written on [written] when known). Refused (IllegalArgumentException, every problem listed)
     * when the g-code is not a motion plate, or its conditions and its cells disagree (a band listed with no wall cells, a cell
     * for a band the conditions do not list).
     */
    fun blank(gcode: String, fileName: String, written: java.time.LocalDate? = null): String {
        val lines = gcode.lines()
        val start = lines.indexOf("; CALIBRATION_PLATE_START"); val end = lines.indexOf("; CALIBRATION_PLATE_END")
        require(start >= 0 && end > start) { "$fileName: no calibration conditions block (; CALIBRATION_PLATE_START ... _END): not a plate plymeld made" }
        val cond = lines.subList(start + 1, end)
        fun value(name: String) = cond.firstOrNull { it.startsWith("; $name = ") }?.removePrefix("; $name = ")
        require(value("plate")?.startsWith("motion") == true) { "$fileName: not a motion plate (its conditions say plate = ${value("plate")})" }
        val fil = value("filament")?.let { Regex("""^(\S+), tool (\d+), build plate (\S+)$""").find(it) }
            ?: throw IllegalArgumentException("$fileName: its conditions name no filament, tool, and build plate ('; filament = <key>, tool <n>, build plate <key>')")
        val machine = value("machine")?.let { Regex("""^(\S+)""").find(it)?.groupValues?.get(1) }
            ?: throw IllegalArgumentException("$fileName: its conditions name no machine ('; machine = <key> (...)'): the values go on the machine")
        val speeds = value("speeds")?.let { numbers(it.substringBefore(" mm/s")) } ?: throw IllegalArgumentException("$fileName: its conditions list no speeds ('; speeds = a, b, ... mm/s')")
        val accels = value("accelerations")?.let { numbers(it.substringBefore(" mm/s2")) } ?: throw IllegalArgumentException("$fileName: its conditions list no accelerations ('; accelerations = a, b, ... mm/s2')")
        val faceLen = value("face_length")?.let { Regex("""^([0-9.]+) mm""").find(it)?.groupValues?.get(1) } ?: throw IllegalArgumentException("$fileName: its conditions give no face_length")
        require(speeds.size == MotionGeometry.speeds) { "$fileName: ${speeds.size} speeds in its conditions, not ${MotionGeometry.speeds}" }
        val problems = ArrayList<String>()
        val cells = lines.mapNotNull { Regex("""^; CELL: band (\d+) (outer|inner)$""").find(it)?.groupValues?.get(1)?.toInt() }.toSet()
        for (b in 1..accels.size) if (b !in cells) problems += "band $b is listed by the conditions, but the g-code has no wall cells for it"
        for (c in cells) if (c !in 1..accels.size) problems += "the g-code prints a band $c; the conditions list bands 1 to ${accels.size}"
        require(problems.isEmpty()) { "$fileName: the plate's conditions and its cells disagree; no sheet written:\n" + problems.joinToString("\n") { "  $it" } }
        val made = MadeBy.text(lines, written)
        val control = value("control") ?: ""
        fun facesOf(alongX: Boolean) = (0 until MotionGeometry.speeds).joinToString(", ") { j ->
            "${num(speeds[j])} mm/s face ${(1..MotionGeometry.faces).first { k -> MotionGeometry.FACE_SPEED[k - 1] == j && MotionGeometry.alongX(k) == alongX }}" }
        val sb = StringBuilder()
        sb.append("# The motion readings to grade, from $fileName (`plymeld calibrate motion readings`).\n")
        sb.append("# Replace every \"?\" with ${MotionInstructions.GRADES.joinToString(", ")}: how far away the ripples after the face's corner show (the plate's page says how to look).\n")
        sb.append("# A row per band (its acceleration; the top band first, as the tower stands), a column per speed. `plymeld calibrate motion values`\n")
        sb.append("# refuses a sheet with a \"?\" left. Leave the speeds and accelerations as they are; fill in printed and graded (dates) for the record.\n")
        sb.append("# The faces: along X ${facesOf(true)}; along Y ${facesOf(false)}.\n")
        if (control.isNotEmpty()) sb.append("# The control: $control.\n")
        sb.append("plate = \"$fileName, ${made?.let { "made by $it, " } ?: ""}machine $machine, filament ${fil.groupValues[1]}, tool ${fil.groupValues[2]}, build plate ${fil.groupValues[3]}\"\n")
        sb.append("machine = \"$machine\"\nfilament = \"${fil.groupValues[1]}\"\ntool = \"${fil.groupValues[2]}\"\n")
        sb.append("face_length = \"$faceLen mm\"\n")
        sb.append("control = \"${control.replace("\"", "'")}\"\n")
        sb.append("printed = \"\"\ngraded = \"\"\nnotes = \"\"\n")
        for ((key, what) in GRIDS) {
            sb.append("\n# $what\n")
            sb.append("$key = '''\n")
            sb.append(grid(speeds, accels) { _, _ -> "?" })
            sb.append("'''\n")
        }
        val text = sb.toString()
        val back = parse(text, fileName, ungradedAllowed = true)
        check(back.speeds.toList() == speeds && back.accels.toList() == accels && back.grades.values.all { g -> g.all { r -> r.all { it == null } } }) { "the sheet written does not read back" }
        return text
    }

    /** A grid's text: the header, then a row per acceleration, the highest first, [cell] (band, speed index) in each column. */
    private fun grid(speeds: List<Double>, accels: List<Double>, cell: (Int, Int) -> String): String {
        val cols = speeds.map { "${num(it)} mm/s" }
        val first = "accel \\ speed"
        val w0 = maxOf(first.length, accels.maxOf { "${num(it)} mm/s2".length }) + 3
        val w = cols.maxOf { it.length } + 3
        val sb = StringBuilder(first.padEnd(w0) + cols.joinToString("") { it.padEnd(w) }.trimEnd() + "\n")
        for (b in accels.indices.reversed()) sb.append("${num(accels[b])} mm/s2".padEnd(w0) + speeds.indices.joinToString("") { j -> cell(b, j).padEnd(w) }.trimEnd() + "\n")
        return sb.toString()
    }

    /** A sheet as read: its speeds and accelerations, the grades by grid (band, speed index; null = not graded), the record. */
    class Sheet(val plate: String, val machine: String, val filament: String, val tool: String, val printed: String, val graded: String, val notes: String,
                val faceLengthMm: Double, val control: String, val speeds: DoubleArray, val accels: DoubleArray, val grades: Map<String, List<List<String?>>>)

    /** Reads a sheet. Refused (IllegalArgumentException, every problem listed) when it is not one, or a grade is missing (unless [ungradedAllowed]) or not a grade. */
    fun parse(text: String, name: String, ungradedAllowed: Boolean = false): Sheet {
        val entries = try { ConfigFiles.readToml(text, name) } catch (e: IllegalArgumentException) { throw IllegalArgumentException("$name: not TOML: ${e.message}") }
        fun top(k: String) = entries.firstOrNull { it.path == listOf(k) }?.value?.toString()
        val plate = top("plate") ?: throw IllegalArgumentException("$name: no 'plate = ...' line: not a motion readings sheet (`plymeld calibrate motion readings` writes one)")
        val machine = top("machine")?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("$name: no 'machine = ...' line: the values go on a machine")
        val faceLen = top("face_length")?.let { Regex("""^([0-9.]+)\s*mm$""").find(it.trim())?.groupValues?.get(1)?.toDoubleOrNull() } ?: throw IllegalArgumentException("$name: no 'face_length = \"<mm> mm\"' line")
        val problems = ArrayList<String>()
        var speeds: List<Double>? = null; var accels: List<Double>? = null
        val grades = LinkedHashMap<String, List<List<String?>>>()
        for ((key, what) in GRIDS) {
            val body = top(key) ?: run { problems += "no '$key' grid ($what)"; null } ?: continue
            val rows = body.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val head = rows.firstOrNull()?.takeIf { it.startsWith("accel") } ?: run { problems += "$key: no header row ('accel \\ speed  <speed> mm/s ...')"; null } ?: continue
            val sp = Regex("""([0-9.]+)\s*mm/s\b(?!2)""").findAll(head.substringAfter("speed")).map { it.groupValues[1].toDouble() }.toList()
            if (speeds == null) speeds = sp else if (sp != speeds) problems += "$key: its speeds (${sp.joinToString { num(it) }}) are not the other grid's (${speeds.joinToString { num(it) }})"
            val ac = ArrayList<Double>(); val g = ArrayList<List<String?>>()
            for (r in rows.drop(1)) {
                val m = Regex("""^([0-9.]+)\s*mm/s2\s+(.*)$""").find(r) ?: run { problems += "$key: a row that is not '<acceleration> mm/s2  <grades>': '$r'"; null } ?: continue
                val a = m.groupValues[1].toDouble()
                val words = m.groupValues[2].trim().split(Regex("\\s+")).map { it.lowercase() }
                if (words.size != sp.size) { problems += "$key, ${num(a)} mm/s2: ${words.size} grades for ${sp.size} speeds"; continue }
                ac += a
                g += words.mapIndexed { j, w -> when {
                    w == "?" -> { if (!ungradedAllowed) problems += "$key, ${num(a)} mm/s2 at ${num(sp[j])} mm/s is not graded yet ('?'): write ${MotionInstructions.GRADES.joinToString(", ")} as its ripples show"; null }
                    w !in MotionInstructions.GRADES -> { problems += "$key, ${num(a)} mm/s2 at ${num(sp[j])} mm/s: '$w' is not a grade (${MotionInstructions.GRADES.joinToString(", ")})"; null }
                    else -> w
                } }
            }
            // the grid lists the top band first: bottom to top here
            val order = ac.indices.sortedBy { ac[it] }
            val acSorted = order.map { ac[it] }
            if (accels == null) accels = acSorted else if (acSorted != accels) problems += "$key: its accelerations (${acSorted.joinToString { num(it) }}) are not the other grid's (${accels.joinToString { num(it) }})"
            if (acSorted.distinct().size != acSorted.size) problems += "$key: an acceleration listed twice"
            grades[key] = order.map { g[it] }
        }
        if (problems.isNotEmpty()) throw IllegalArgumentException("$name: " + (if (problems.size == 1) problems[0] else "${problems.size} problems:\n" + problems.joinToString("\n") { "  $it" }))
        return Sheet(plate, machine, top("filament") ?: "?", top("tool") ?: "?", top("printed") ?: "", top("graded") ?: "", top("notes") ?: "",
            faceLen, top("control") ?: "", speeds!!.toDoubleArray(), accels!!.toDoubleArray(), grades)
    }

    /** One band's reading: per grid the fastest speed index clean with every slower one (-1: none), and the speed credited. */
    class BandReading(val accel: Double, val byGrid: Map<String, Int>, val both: Int, val reach: Double, val credited: Double?, val faceS: Double?)

    /**
     * The graded sheet [text] (named [name]) as the machine's visible speed and acceleration, a cell counting as clean when its
     * grade is [clean] or better: a config file's text (`[hardware.machine.<key>]` with `motion.visible.speed` and
     * `motion.visible.acceleration`, where they came from in the comments above) and a report, line by line. Per band, the
     * fastest speed whose cell and every slower one are clean in both grids, credited at most the speed a face of the sheet's
     * face_length reaches at that acceleration (sqrt(a x L)); of those pairs the one that lays such a face quickest (the time
     * to speed up, run, and slow down over it from a stop). No band clean even at its slowest speed: no values, and the file says why.
     */
    fun values(text: String, name: String, clean: String = MotionInstructions.DEFAULT_CLEAN): Pair<String, List<String>> {
        val grades = MotionInstructions.GRADES
        require(clean in grades.dropLast(1)) { "--clean is ${grades.dropLast(1).joinToString(", ")} (got '$clean'): the worst grade that still counts as clean" }
        val sheet = parse(text, name)
        val cleanAt = grades.indexOf(clean)
        val l = sheet.faceLengthMm
        fun faceTime(v: Double, a: Double) = if (v * v <= a * l) l / v + v / a else 2 * Math.sqrt(l / a)
        val readings = sheet.accels.indices.map { b ->
            val a = sheet.accels[b]
            val byGrid = sheet.grades.mapValues { (_, g) -> g[b].indexOfFirst { grades.indexOf(it!!) > cleanAt }.let { if (it < 0) sheet.speeds.size - 1 else it - 1 } }
            val both = byGrid.values.min()
            val reach = Math.sqrt(a * l)
            // whole mm/s, rounded down: a value no face is known to have run faster than
            val credited = if (both < 0) null else Math.min(sheet.speeds[both], Math.floor(reach))
            BandReading(a, byGrid, both, reach, credited, credited?.let { faceTime(it, a) })
        }
        fun axis(key: String) = if (key == "along_x") "along X" else "along Y"
        val lines = ArrayList<String>()
        for ((b, r) in readings.withIndex()) {
            val per = r.byGrid.entries.joinToString(", ") { (k, j) -> "${axis(k)} ${if (j < 0) "none" else "to ${num(sheet.speeds[j])} mm/s"}" }
            val binds = r.byGrid.filterValues { it == r.both }.keys.let { if (it.size == r.byGrid.size) "both" else it.joinToString { k -> axis(k) } }
            lines += "band ${b + 1}, ${num(r.accel)} mm/s2: $per: " + (if (r.credited == null) "none clean in both"
                else "${num(r.credited)} mm/s${if (r.credited < sheet.speeds[r.both] - 1e-9) " (the most a face reaches here; nominal ${num(sheet.speeds[r.both])})" else ""} (binding: $binds), a ${num(l)} mm face in ${String.format(Locale.ROOT, "%.3f", r.faceS)} s")
        }
        // oddities: a clean cell above a ringing one in a band; a band cleaner than the one below it
        val odd = ArrayList<String>()
        for ((key, g) in sheet.grades) for ((b, row) in g.withIndex()) {
            val firstBad = row.indexOfFirst { grades.indexOf(it!!) > cleanAt }
            if (firstBad >= 0 && (firstBad + 1 until row.size).any { grades.indexOf(row[it]!!) <= cleanAt })
                odd += "band ${b + 1} ${axis(key)}: a faster speed reads clean above ${num(sheet.speeds[firstBad])} mm/s, which does not: counted only below it"
        }
        for (b in 1 until readings.size) if (readings[b].both > readings[b - 1].both)
            odd += "band ${b + 1} (${num(readings[b].accel)} mm/s2) is clean to a faster speed than band $b (${num(readings[b - 1].accel)} mm/s2) below it: a resonance at one acceleration, or a grading slip; look again"
        val best = readings.filter { it.credited != null }.minWithOrNull(compareBy<BandReading> { it.faceS!! }.thenBy { it.accel })
        val top = sheet.grades.mapValues { (_, g) -> g.last().last() }
        val controlLine = "the control${if (sheet.control.isNotEmpty()) " (${sheet.control})" else ""}: " + top.entries.joinToString(", ") { (k, v) -> "${axis(k)} $v" }
        val head = buildList {
            add("# Visible speed and acceleration for machine ${sheet.machine}, from the motion plate ${sheet.plate}" + (if (sheet.printed.isNotBlank()) "; printed ${sheet.printed}" else "") + (if (sheet.graded.isNotBlank()) "; graded ${sheet.graded}" else "") + " (the sheet $name, `plymeld calibrate motion values`).")
            add("# Clean: ${grades.take(cleanAt + 1).joinToString(" or ")} (--clean $clean). Per band, the fastest speed clean with every slower one in both directions, credited at most what a")
            add("# ${num(l)} mm face reaches at the band's acceleration (sqrt(a x L)):")
            lines.forEach { add("#   $it") }
            odd.forEach { add("#   note: $it") }
            add("#   $controlLine")
            if (sheet.notes.isNotBlank()) add("#   notes: ${sheet.notes}")
            if (best == null) {
                add("# No band is clean even at its slowest speed in both directions: no values written. The plate's page says what that means (the machine, or a range too high).")
            } else {
                val b = readings.indexOf(best) + 1
                add("# Chosen: band $b, ${num(best.accel)} mm/s2 at ${num(best.credited!!)} mm/s: of the clean pairs, the quickest over a ${num(l)} mm face (${String.format(Locale.ROOT, "%.3f", best.faceS)} s; the rule is plymeld's choice: the plate's face as a stand-in for a print's walls).")
                add("# Read with filament ${sheet.filament} on tool ${sheet.tool}; ringing is the machine's (its frame, belts, and vibration compensation as they were set). A visible speed")
                add("# or acceleration set in the job (plan.mechanism.motion.visible.speed / visible.acceleration, in a config file of yours) still wins over these.")
                add("[hardware.machine.${tomlKey(sheet.machine)}]")
                add("motion.visible.speed = \"${num(best.credited)} mm/s\"")
                add("motion.visible.acceleration = \"${num(best.accel)} mm/s2\"")
            }
        }
        val report = ArrayList<String>()
        report += "visible motion from ${sheet.plate} (clean: ${grades.take(cleanAt + 1).joinToString(" or ")}):"
        report += lines.map { "  $it" }
        report += odd.map { "  note: $it" }
        report += "  $controlLine"
        report += if (best == null) "  no band is clean even at its slowest speed: no values" else "  chosen: ${num(best.accel)} mm/s2 at ${num(best.credited!!)} mm/s (the quickest clean pair over a ${num(l)} mm face)"
        val out = head.joinToString("\n") + "\n"
        // the file reads back as config entries at the paths written
        val back = ConfigFiles.readToml(out, name)
        if (best != null) check(listOf("visible.speed", "visible.acceleration").all { k -> back.any { it.path == listOf("hardware", "machine", sheet.machine, "motion") + k.split('.') } }) { "the values written do not read back" }
        return out to report
    }

    private fun tomlKey(k: String) = if (k.matches(Regex("[A-Za-z0-9_-]+"))) k else "\"" + k.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    private fun numbers(s: String): List<Double> = s.split(',').map { it.trim().toDoubleOrNull() ?: throw IllegalArgumentException("'$s' is not a list of numbers") }
    private fun num(v: Double): String = if (Math.abs(v - Math.rint(v)) < 1e-9) Math.rint(v).toLong().toString() else String.format(Locale.ROOT, "%.1f", v)
}
