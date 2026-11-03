package plymeld.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import plymeld.config.ConfigFiles
import plymeld.core.calibrate.SeamAt
import plymeld.core.calibrate.SeamInstructions
import plymeld.core.calibrate.SeamPiece
import plymeld.core.calibrate.SeamShape
import plymeld.core.calibrate.SeamStyle
import java.util.Locale

/**
 * `plymeld calibrate seams readings PLATE.gcode [-o FILE]`: the sheet to grade for a printed seam plate (SeamPlate): an entry
 * per piece with its kind from the plate's g-code (its conditions block and its cells), its grade "?". The person grading
 * replaces each "?" with hidden, faint, plain, or glaring; `calibrate seams values` refuses a sheet with a "?" left.
 */
class SeamReadingsCommand : CliktCommand(name = "readings") {
    private val plate by argument("PLATE", help = "the seam plate's g-code, as `plymeld calibrate seams` wrote it").path(mustExist = true)
    private val output by option("-o", "--output", help = "write the sheet to this TOML file (default: print it)").path()

    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "Write the readings sheet to grade for a printed seam plate: an entry per piece with its kind, its grade '?'. Replace each '?' with hidden, faint, plain, or glaring (how far away its seam shows), then turn the sheet into the seam measure's shares with `calibrate seams values`."

    override fun run() {
        val text = try { SeamRecord.blank(java.nio.file.Files.readString(plate), plate.fileName.toString(), MadeBy.dateOf(plate)) }
            catch (e: IllegalArgumentException) { throw UsageError(e.message ?: "not a seam plate") }
        output?.let { out ->
            out.parent?.let { java.nio.file.Files.createDirectories(it) }
            java.nio.file.Files.writeString(out, text)
            echo("seam readings to grade -> $out: replace each \"?\" with ${SeamInstructions.GRADES.joinToString(", ")}, then `plymeld calibrate seams values $out -o seams.toml`")
        } ?: echo(text, trailingNewline = false)
    }
}

/**
 * `plymeld calibrate seams values READINGS.toml [-o FILE]`: the graded sheet turned into the seam measure's shares
 * (`method.quality.seam.scarf_share`, `.corner_share`, `method.quality.visibility.under`) with where they came from: each
 * a piece's score over its reference's. The written file is a config file: add it with `-cf`.
 */
class SeamValuesCommand : CliktCommand(name = "values") {
    private val sheet by argument("READINGS", help = "the graded readings sheet, as `plymeld calibrate seams readings` wrote it and you graded it").path(mustExist = true)
    private val output by option("-o", "--output", help = "write the shares to this TOML config file (default: print it)").path()

    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "Turn a graded seam readings sheet into the seam measure's shares (method.quality.seam.scarf_share, .corner_share, method.quality.visibility.under): each one piece's score over its reference's, with where it came from; a share the readings cannot give is left out and said so. Add the written file with -cf."

    override fun run() {
        val (text, report) = try { SeamRecord.values(java.nio.file.Files.readString(sheet), sheet.fileName.toString()) }
            catch (e: IllegalArgumentException) { throw UsageError(e.message ?: "not a graded seam sheet") }
        report.forEach { echo(it) }
        output?.let { out ->
            out.parent?.let { java.nio.file.Files.createDirectories(it) }
            java.nio.file.Files.writeString(out, text)
            echo("seam shares -> $out: add it to your config (-cf)")
        } ?: echo(text, trailingNewline = false)
    }
}

/** Reading a seam plate's g-code back into the sheet to grade, and a graded sheet into the seam measure's shares. */
object SeamRecord {
    /**
     * The sheet to grade for the plate in [gcode] (named [fileName], last written on [written] when known). Refused
     * (IllegalArgumentException, every problem listed) when the g-code is not a seam plate, or its conditions and its cells disagree (a piece listed with no outer-wall cell, a
     * cell for a piece the conditions do not list).
     */
    fun blank(gcode: String, fileName: String, written: java.time.LocalDate? = null): String {
        val lines = gcode.lines()
        val start = lines.indexOf("; CALIBRATION_PLATE_START"); val end = lines.indexOf("; CALIBRATION_PLATE_END")
        require(start >= 0 && end > start) { "$fileName: no calibration conditions block (; CALIBRATION_PLATE_START ... _END): not a plate plymeld made" }
        val cond = lines.subList(start + 1, end)
        fun value(name: String) = cond.firstOrNull { it.startsWith("; $name = ") }?.removePrefix("; $name = ")
        require(value("plate")?.startsWith("seams") == true) { "$fileName: not a seam plate (its conditions say plate = ${value("plate")})" }
        val fil = value("filament")?.let { Regex("""^(\S+), tool (\d+), build plate (\S+)$""").find(it) }
            ?: throw IllegalArgumentException("$fileName: its conditions name no filament, tool, and build plate ('; filament = <key>, tool <n>, build plate <key>')")
        val pieces = cond.mapNotNull { l -> Regex("""^; piece (\d+) = ([a-z0-9]+ [a-z]+ [a-z]+): (.*)$""").find(l) }.map { m ->
            val k = m.groupValues[1].toInt()
            val p = SeamPiece.parse(k - 1, m.groupValues[2]) ?: throw IllegalArgumentException("$fileName: piece $k's kind '${m.groupValues[2]}' is not a shape, a seam place, and a style")
            p to m.groupValues[3]
        }
        require(pieces.isNotEmpty()) { "$fileName: its conditions list no pieces ('; piece <k> = <shape> <seam> <style>: ...')" }
        require(pieces.map { it.first.index + 1 } == (1..pieces.size).toList()) { "$fileName: its pieces are numbered ${pieces.joinToString { "${it.first.index + 1}" }}, not 1 to ${pieces.size}" }
        val problems = ArrayList<String>()
        val cells = lines.mapNotNull { Regex("""^; CELL: piece (\d+)$""").find(it)?.groupValues?.get(1)?.toInt() }.toSet()
        for ((p, _) in pieces) if (p.index + 1 !in cells) problems += "piece ${p.index + 1} is listed by the conditions, but the g-code has no outer-wall cell for it"
        for (c in cells) if (c !in 1..pieces.size) problems += "the g-code prints a piece $c; the conditions list pieces 1 to ${pieces.size}"
        require(problems.isEmpty()) { "$fileName: the plate's conditions and its cells disagree; no sheet written:\n" + problems.joinToString("\n") { "  $it" } }
        val made = MadeBy.text(lines, written)
        val sb = StringBuilder()
        sb.append("# The seam readings to grade, from $fileName (`plymeld calibrate seams readings`).\n")
        sb.append("# Replace every \"?\" with ${SeamInstructions.GRADES.joinToString(", ")}: how far away the piece's front seam shows (its page says how to look);\n")
        sb.append("# write what you saw in notes. `plymeld calibrate seams values` refuses a sheet with a \"?\" left.\n")
        sb.append("# The pieces' kinds are the plate's: leave them as they are. Fill in printed and graded (dates) for the record.\n")
        sb.append("plate = \"$fileName, ${made?.let { "made by $it, " } ?: ""}filament ${fil.groupValues[1]}, tool ${fil.groupValues[2]}, build plate ${fil.groupValues[3]}\"\n")
        sb.append("filament = \"${fil.groupValues[1]}\"\ntool = \"${fil.groupValues[2]}\"\n")
        sb.append("printed = \"\"\ngraded = \"\"\n")
        for ((p, words) in pieces) {
            sb.append("\n[piece.${p.index + 1}]\n")
            sb.append("kind = \"${p.key}\"\nseam = \"${words.replace("\"", "'")}\"\ngrade = \"?\"\nnotes = \"\"\n")
        }
        val text = sb.toString()
        val back = parse(text, fileName, ungradedAllowed = true)
        check(back.pieces.size == pieces.size && back.pieces.all { it.grade == null }) { "the sheet written does not read back" }
        return text
    }

    /** A piece as read from a sheet: its kind, and its grade (null = not graded). */
    class Reading(val piece: SeamPiece, val grade: String?, val notes: String) { val score: Int get() = SeamInstructions.GRADES.indexOf(grade) }
    class Sheet(val plate: String, val printed: String, val graded: String, val pieces: List<Reading>, val filament: String?, val tool: String?)

    /** Reads a sheet. Refused (IllegalArgumentException) when it is not one, or a grade is missing (unless [ungradedAllowed]) or not a grade. */
    fun parse(text: String, name: String, ungradedAllowed: Boolean = false): Sheet {
        val entries = try { ConfigFiles.readToml(text, name) } catch (e: IllegalArgumentException) { throw IllegalArgumentException("$name: not TOML: ${e.message}") }
        fun top(k: String) = entries.firstOrNull { it.path == listOf(k) }?.value?.toString()
        val plate = top("plate") ?: throw IllegalArgumentException("$name: no 'plate = ...' line: not a seam readings sheet (`plymeld calibrate seams readings` writes one)")
        val problems = ArrayList<String>()
        val byPiece = entries.filter { it.path.size == 3 && it.path[0] == "piece" }.groupBy { it.path[1].toIntOrNull() }
        if (byPiece.containsKey(null)) problems += "a [piece.<n>] table whose n is not a number"
        val pieces = byPiece.filterKeys { it != null }.toSortedMap(compareBy { it }).map { (k, es) ->
            val v = es.associate { it.path[2] to it.value.toString() }
            val grade = v["grade"]?.trim()?.lowercase()
            val src = es.firstOrNull { it.path[2] == "grade" }?.source ?: name
            when {
                grade == null -> problems += "piece $k has no grade line"
                grade == "?" -> if (!ungradedAllowed) problems += "piece $k is not graded yet ('?', $src): write ${SeamInstructions.GRADES.joinToString(", ")} as its seam shows"
                grade !in SeamInstructions.GRADES -> problems += "piece $k: '$grade' is not a grade (${SeamInstructions.GRADES.joinToString(", ")}; $src)"
            }
            val piece = v["kind"]?.let { SeamPiece.parse(k!! - 1, it) } ?: run { problems += "piece $k has no kind, or one that is not a shape, a seam place, and a style (the sheet's kinds are the plate's: write the sheet again with `plymeld calibrate seams readings`)"; SeamPiece(k!! - 1, SeamShape.SQUARE, SeamAt.FACE, SeamStyle.POINT) }
            Reading(piece, grade?.takeIf { it != "?" }, v["notes"] ?: "")
        }
        if (pieces.isEmpty()) problems += "no [piece.<n>] tables"
        if (problems.isNotEmpty()) throw IllegalArgumentException("$name: " + (if (problems.size == 1) problems[0] else "${problems.size} problems:\n" + problems.joinToString("\n") { "  $it" }))
        return Sheet(plate, top("printed") ?: "", top("graded") ?: "", pieces,
            top("filament") ?: Regex("""filament (\S+), tool (\d+)""").find(plate)?.groupValues?.get(1), top("tool") ?: Regex("""filament (\S+), tool (\d+)""").find(plate)?.groupValues?.get(2))
    }

    /**
     * The graded sheet [text] (named [name]) as the seam measure's shares: a config file's text (`[method.quality.seam]`,
     * `[method.quality.visibility]`, each share with where it came from in the comments above) and a report (the same, line by
     * line). A share is a piece's score over its reference's (hidden 0, faint 1, plain 2, glaring 3), at most 1: the scarf
     * share the mean of round scarf / round point and square scarf / square point, the corner share square corner point /
     * square face point, the underside share from each leaning piece against the square face point through the measure's
     * own lean rule (a face leaning L counts 1 - (1 - share) x sin L), the mean over the leans. A share whose reference reads
     * hidden (0) cannot be given and is left out, with the reason.
     */
    fun values(text: String, name: String): Pair<String, List<String>> {
        val sheet = parse(text, name)
        fun find(shape: SeamShape, at: SeamAt, style: SeamStyle) = sheet.pieces.firstOrNull { it.piece.shape == shape && it.piece.at == at && it.piece.style == style }
        fun label(r: Reading) = "piece ${r.piece.index + 1} (${r.piece.key}) ${r.grade} ${r.score}"
        val lines = ArrayList<String>()
        val missing = ArrayList<String>()
        val shares = LinkedHashMap<String, Double>()
        /** [a]'s score over [ref]'s, at most 1; null when the reference reads 0 or a piece is missing. */
        fun ratio(a: Reading?, ref: Reading?): Double? = if (a == null || ref == null || ref.score == 0) null else minOf(1.0, a.score.toDouble() / ref.score)
        // the scarf share
        val scarfPairs = listOf(SeamShape.ROUND, SeamShape.SQUARE).mapNotNull { sh ->
            val a = find(sh, SeamAt.FACE, SeamStyle.SCARF); val r = find(sh, SeamAt.FACE, SeamStyle.POINT)
            if (a == null || r == null) null else Triple(a, r, ratio(a, r))
        }
        val scarfRatios = scarfPairs.mapNotNull { it.third }
        if (scarfRatios.isNotEmpty()) {
            shares["method.quality.seam.scarf_share"] = scarfRatios.average()
            lines += "scarf_share ${fmt(scarfRatios.average())}: " + scarfPairs.joinToString("; ") { (a, r, q) -> "${label(a)} over ${label(r)}" + (q?.let { " = ${fmt(it)}" } ?: " (the reference reads hidden: not counted)") }
        } else missing += "scarf_share: not measured: " + (if (scarfPairs.isEmpty()) "no scarf piece with its point seam on this plate" else "the point seams it is read against read hidden (0): nothing to compare; the placeholder stays")
        // the corner share
        val faceRef = find(SeamShape.SQUARE, SeamAt.FACE, SeamStyle.POINT)
        val corner = find(SeamShape.SQUARE, SeamAt.CORNER, SeamStyle.POINT)
        ratio(corner, faceRef)?.let { q -> shares["method.quality.seam.corner_share"] = q; lines += "corner_share ${fmt(q)}: ${label(corner!!)} over ${label(faceRef!!)}" }
            ?: run { missing += "corner_share: not measured: " + (if (corner == null || faceRef == null) "no corner piece with its face reference on this plate" else "the face seam it is read against reads hidden (0); the placeholder stays") }
        // the underside share
        val leans = sheet.pieces.filter { it.piece.shape.leanDeg > 0 }
        val under = leans.mapNotNull { r -> ratio(r, faceRef)?.let { q -> val sin = Math.sin(Math.toRadians(r.piece.shape.leanDeg)); Triple(r, q, (1 - (1 - q) / sin).coerceIn(0.0, 1.0)) } }
        if (under.isNotEmpty()) {
            val u = under.map { it.third }.average()
            shares["method.quality.visibility.under"] = u
            lines += "underside ${fmt(u)}: " + under.joinToString("; ") { (r, q, uu) -> "${label(r)} over ${label(faceRef!!)} = ${fmt(q)} -> 1 - (1 - ${fmt(q)}) / sin ${fmt(r.piece.shape.leanDeg, 0)} = ${fmt(uu)}" }
        } else missing += "underside: not measured: " + (if (leans.isEmpty() || faceRef == null) "no leaning piece with its face reference on this plate" else "the face seam it is read against reads hidden (0); the placeholder stays")
        // the scarf in a corner, reported only
        val cornerScarf = find(SeamShape.SQUARE, SeamAt.CORNER, SeamStyle.SCARF)
        val cornerNote = if (cornerScarf != null && corner != null) "a scarf in a corner: ${label(cornerScarf)} against ${label(corner)}: " +
            (if (cornerScarf.score < corner.score) "the scarf helps in a corner too" else if (cornerScarf.score == corner.score) "no difference in a corner" else "the scarf reads worse in a corner") + " (reported, not written back)" else null
        val head = buildList {
            add("# Seam shares from the plate ${sheet.plate}" + (if (sheet.printed.isNotBlank()) "; printed ${sheet.printed}" else "") + (if (sheet.graded.isNotBlank()) "; graded ${sheet.graded}" else "") + " (the sheet $name, `plymeld calibrate seams values`).")
            add("# Grades: ${SeamInstructions.GRADES.withIndex().joinToString(", ") { (i, g) -> "$g $i" }}; a share is a seam's score over its reference's, at most 1:")
            lines.forEach { add("#   $it") }
            missing.forEach { add("#   $it") }
            cornerNote?.let { add("#   $it") }
            add("# The turned-away share (method.quality.visibility.away) is not read here: how much a print's back counts is a preference (plan.view_from), not a print quality.")
            add("# These are the job's shares (method.quality.*): add the file with -cf; they were read with filament ${sheet.filament ?: "?"} on tool ${sheet.tool ?: "?"} (a seam's size depends on the filament, its retraction, and pressure advance).")
            val seam = shares.filterKeys { it.startsWith("method.quality.seam.") }
            if (seam.isNotEmpty()) { add("[method.quality.seam]"); for ((k, v) in seam) add("${k.substringAfterLast('.')} = \"${fmt(v)}\"") }
            val vis = shares.filterKeys { it.startsWith("method.quality.visibility.") }
            if (vis.isNotEmpty()) { add("[method.quality.visibility]"); for ((k, v) in vis) add("${k.substringAfterLast('.')} = \"${fmt(v)}\"") }
        }
        val report = ArrayList<String>()
        report += "seam shares from ${sheet.plate}:"
        report += lines.map { "  $it" }
        report += missing.map { "  $it" }
        cornerNote?.let { report += "  $it" }
        val out = head.joinToString("\n") + "\n"
        // the file reads back as config entries at the paths written
        val back = ConfigFiles.readToml(out, name)
        check(shares.keys.all { k -> back.any { it.path == k.split('.') } }) { "the shares written do not read back" }
        return out to report
    }

    private fun fmt(v: Double, d: Int = 2) = String.format(Locale.ROOT, "%.${d}f", v)
}
