package plymeld.commands

import com.github.ajalt.clikt.core.parse
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files
import java.nio.file.Path

/**
 * The seam plate end to end on the repo's sample config (test-models/config): `plymeld calibrate seams` writes the plate only
 * when it prints as specified AND its own controls are caught, so a written plate is the test; then `calibrate seams readings`
 * writes the sheet to grade from the plate's g-code (an entry per piece, every grade "?"), and `calibrate seams values` turns
 * a graded sheet into the seam measure's shares, refusing a sheet with a "?" left. Controls: an ironing plate is refused as a
 * sheet's source; a sheet whose conditions and cells disagree is refused; a reference reading hidden gives no share.
 */
class SeamReadingsTest : StringSpec({
    val samples = Path.of("..", "test-models", "config").toAbsolutePath().normalize()
    val d = Files.createTempDirectory("plymeld-seams")
    val out = d.resolve("seams.gcode")
    CalibrateCommand().parse(listOf("seams", "-cf", samples.resolve("0*.toml").toString(), "-cv", "hardware.machine.example-corexy.model=qidi-q2", "-o", out.toString()))
    val gcode = Files.readString(out)

    /** The sheet with every "?" replaced by [grade] for piece [k] (1-based). */
    fun graded(sheet: String, grade: (Int) -> String): String {
        val sb = StringBuilder(); var k = 0
        for (l in sheet.lines()) {
            Regex("\\[piece\\.(\\d+)]").matchEntire(l)?.let { k = it.groupValues[1].toInt() }
            sb.append(if (l == "grade = \"?\"") "grade = \"${grade(k)}\"" else l).append('\n')
        }
        return sb.toString()
    }

    "the seam plate on the samples is written, with its page and its conditions" {
        gcode shouldContain "; plate = seams"
        gcode shouldContain "; piece 6 = square corner scarf: "
        gcode shouldContain "; CELL: piece 8"
        gcode.lines().count { it.startsWith("; piece ") } shouldBe 8
        Files.readString(d.resolve("seams.md")) shouldContain "## How to grade the seams"
        Files.exists(d.resolve("seams.png")) shouldBe true
    }

    "the sheet to grade: an entry per piece with its kind, every grade '?'; an ironing plate is refused; conditions and cells disagreeing are refused" {
        val sheet = SeamRecord.blank(gcode, "seams.gcode")
        sheet.lines().count { it.startsWith("[piece.") } shouldBe 8
        sheet.lines().count { it == "grade = \"?\"" } shouldBe 8
        sheet shouldContain "[piece.1]\nkind = \"round face point\"\nseam = \"a point seam on a round wall"
        sheet shouldContain "[piece.7]\nkind = \"lean30 face point\""
        val ir = d.resolve("ironing.gcode")
        CalibrateCommand().parse(listOf("ironing", "-cf", samples.resolve("0*.toml").toString(), "-cv", "hardware.machine.example-corexy.model=qidi-q2", "-o", ir.toString()))
        shouldThrow<IllegalArgumentException> { SeamRecord.blank(Files.readString(ir), "ironing.gcode") }.message!! shouldContain "not a seam plate"
        val lied = gcode.replace(Regex("(; piece 8 = lean45 face point: [^\n]*)")) { it.groupValues[1] + "\n; piece 9 = square face point: a point seam on a piece the g-code does not print" }
        shouldThrow<IllegalArgumentException> { SeamRecord.blank(lied, "lied.gcode") }.message!! shouldContain "piece 9 is listed by the conditions, but the g-code has no outer-wall cell for it"
    }

    "the shares from a graded sheet: each a piece's score over its reference's; a '?' left or a wrong word is refused" {
        val sheet = SeamRecord.blank(gcode, "seams.gcode")
        // point seams plain (2), scarfs faint (1), the corner faint (1), the leaning faces faint (1) and hidden (0)
        val g = graded(sheet) { k -> when (k) { 1, 3 -> "plain"; 2, 4, 5, 6, 7 -> "faint"; else -> "hidden" } }
        val (text, report) = SeamRecord.values(g, "readings.toml")
        text shouldContain "[method.quality.seam]\nscarf_share = \"0.50\"\ncorner_share = \"0.50\""
        // lean30: 1/2 = 0.5 -> 1 - 0.5 / sin30 = 0; lean45: 0 -> 1 - 1 / sin45 < 0 -> 0: the underside share 0
        text shouldContain "[method.quality.visibility]\nunder = \"0.00\""
        text shouldContain "a scarf in a corner: piece 6 (square corner scarf) faint 1 against piece 5 (square corner point) faint 1: no difference in a corner"
        report.any { it.contains("scarf_share 0.50") } shouldBe true
        // the written file reads back as config entries
        val entries = plymeld.config.ConfigFiles.readToml(text, "seams.toml")
        entries.any { it.path == listOf("method", "quality", "seam", "scarf_share") && it.value.toString() == "0.50" } shouldBe true
        shouldThrow<IllegalArgumentException> { SeamRecord.values(sheet, "readings.toml") }.message!! shouldContain "not graded yet"
        shouldThrow<IllegalArgumentException> { SeamRecord.values(graded(sheet) { if (it == 2) "meh" else "plain" }, "readings.toml") }.message!! shouldContain "'meh' is not a grade"
    }

    "every point seam hidden: no share can be given, the file says so and writes none; a scarf worse than its point seam reads 1" {
        val sheet = SeamRecord.blank(gcode, "seams.gcode")
        val (text, _) = SeamRecord.values(graded(sheet) { "hidden" }, "readings.toml")
        text shouldContain "scarf_share: not measured"
        text shouldContain "corner_share: not measured"
        text shouldContain "underside: not measured"
        text shouldNotContain "[method.quality.seam]"
        val worse = graded(sheet) { k -> if (k == 2 || k == 4) "glaring" else "faint" }
        SeamRecord.values(worse, "r.toml").first shouldContain "scarf_share = \"1.00\""
    }

    "the commands: readings writes the sheet, values the shares" {
        val sheetFile = d.resolve("readings.toml")
        SeamReadingsCommand().parse(listOf(out.toString(), "-o", sheetFile.toString()))
        Files.readString(sheetFile) shouldContain "[piece.8]"
        Files.writeString(sheetFile, graded(Files.readString(sheetFile)) { k -> if (k % 2 == 0) "faint" else "plain" })
        val values = d.resolve("seams.toml")
        SeamValuesCommand().parse(listOf(sheetFile.toString(), "-o", values.toString()))
        Files.readString(values) shouldContain "scarf_share = \"0.50\""
    }
})
