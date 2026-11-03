package plymeld.commands

import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.testing.test
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files
import java.nio.file.Path

/**
 * The motion plate end to end on the repo's sample config (test-models/config): `plymeld calibrate motion` writes the plate only
 * when it prints as specified AND its own controls are caught, so a written plate is the test; then `calibrate motion readings`
 * writes the sheet to grade (two grids, every cell "?"), and `calibrate motion values` turns a graded sheet into the machine's
 * visible speed and acceleration, refusing a sheet with a "?" left. Controls: a seam plate is refused as a sheet's source; a
 * sheet whose conditions and cells disagree is refused; a band 1 that rings even at its slowest gives no values.
 */
class MotionReadingsTest : StringSpec({
    val samples = Path.of("..", "test-models", "config").toAbsolutePath().normalize()
    val d = Files.createTempDirectory("plymeld-motion")
    val out = d.resolve("motion.gcode")
    CalibrateCommand().parse(listOf("motion", "-cf", samples.resolve("0*.toml").toString(), "-cv", "hardware.machine.example-corexy.model=qidi-q2", "-o", out.toString()))
    val gcode = Files.readString(out)

    /** The sheet with each grid's "?" replaced: [clean] (grid key, band from 0, speed index) to [ok] (the default clean grade, hidden), the rest plain. */
    fun graded(sheet: String, ok: String = plymeld.core.calibrate.MotionInstructions.DEFAULT_CLEAN, clean: (String, Int, Int) -> Boolean): String {
        val sb = StringBuilder(); var grid: String? = null; var rows = 0
        val accels = sheet.lines().count { it.matches(Regex("^[0-9.]+ mm/s2 .*")) } / 2
        for (l in sheet.lines()) {
            Regex("^(along_[xy]) = '''$").matchEntire(l)?.let { grid = it.groupValues[1]; rows = 0 }
            if (l == "'''") grid = null
            val m = Regex("^([0-9.]+ mm/s2)(\\s+)(.*)$").matchEntire(l)
            if (grid != null && m != null) {
                val band = accels - 1 - rows; rows++               // the top band first
                val cells = m.groupValues[3].split(Regex("\\s+")).mapIndexed { j, _ -> if (clean(grid, band, j)) ok else "plain" }
                sb.append(m.groupValues[1]).append(m.groupValues[2]).append(cells.joinToString("   ")).append('\n')
            } else sb.append(l).append('\n')
        }
        return sb.toString()
    }

    "the motion plate on the samples is written, with its page and its conditions" {
        gcode shouldContain "; plate = motion"
        gcode shouldContain "; machine = example-corexy (qidi-q2)"
        gcode shouldContain "; face 7 = along x, speed 6"
        gcode shouldContain "; CELL: band 6 outer"
        gcode.lines().count { it.matches(Regex("; band \\d+ = .*")) } shouldBe 6
        Files.readString(d.resolve("motion.md")) shouldContain "## How to grade"
        Files.exists(d.resolve("motion.png")) shouldBe true
    }

    "the sheet to grade: two grids of six speeds by six accelerations, every cell '?'; a seam plate is refused; conditions and cells disagreeing are refused" {
        val sheet = MotionRecord.blank(gcode, "motion.gcode")
        sheet shouldContain "machine = \"example-corexy\""
        sheet shouldContain "along_x = '''"
        sheet shouldContain "along_y = '''"
        sheet.lines().filter { it.matches(Regex("^[0-9.]+ mm/s2 .*")) }.sumOf { l -> l.split(Regex("\\s+")).count { it == "?" } } shouldBe 72
        val sm = d.resolve("seams.gcode")
        CalibrateCommand().parse(listOf("seams", "-cf", samples.resolve("0*.toml").toString(), "-cv", "hardware.machine.example-corexy.model=qidi-q2", "-o", sm.toString()))
        shouldThrow<IllegalArgumentException> { MotionRecord.blank(Files.readString(sm), "seams.gcode") }.message!! shouldContain "not a motion plate"
        val lied = gcode.replace(Regex("(; band 6 = [^\n]*)")) { it.groupValues[1] + "\n; band 7 = 20000 mm/s2, z 30-35 mm, a face reaches at most 769 mm/s" }
            .replace("; accelerations = 310, 630, 1250, 2500, 5000, 10000 mm/s2", "; accelerations = 310, 630, 1250, 2500, 5000, 10000, 20000 mm/s2")
        lied shouldContain "10000, 20000 mm/s2"
        shouldThrow<IllegalArgumentException> { MotionRecord.blank(lied, "lied.gcode") }.message!! shouldContain "band 7 is listed by the conditions, but the g-code has no wall cells for it"
    }

    "the values from a graded sheet: per band the fastest speed clean in both directions, credited what a face reaches; the quickest pair over a face written for the machine" {
        val sheet = MotionRecord.blank(gcode, "motion.gcode")
        // the samples' plate: speeds 30, 60, 90, 125, 155, 188 mm/s; accelerations 310, 630, 1250, 2500, 5000, 10000 mm/s2; faces 29.56 mm
        sheet shouldContain "accel \\ speed"
        // clean (hidden) up to this speed index per band, by grid; plain past it
        val xTo = intArrayOf(5, 5, 4, 3, 1, -1); val yTo = intArrayOf(5, 4, 4, 2, 1, -1)
        val g = graded(sheet) { grid, b, j -> j <= (if (grid == "along_x") xTo[b] else yTo[b]) }
        val (text, report) = MotionRecord.values(g, "readings.toml")
        // worked by hand: both directions clean to 188, 155, 155, 90, 60, none; credited at most floor(sqrt(a x 29.56)): 95 (310), 136 (630), 192 (1250);
        // a face's time L/v + v/a: 95 @ 310 0.618 s, 136 @ 630 0.433, 155 @ 1250 0.315, 90 @ 2500 0.364, 60 @ 5000 0.505: the quickest 155 mm/s at 1250 mm/s2
        text shouldContain "[hardware.machine.example-corexy]\nmotion.visible.speed = \"155 mm/s\"\nmotion.visible.acceleration = \"1250 mm/s2\""
        report.any { it.contains("band 1, 310 mm/s2") && it.contains("95 mm/s (the most a face reaches here; nominal 188)") } shouldBe true
        report.any { it.contains("band 4, 2500 mm/s2") && it.contains("binding: along Y") } shouldBe true
        report.any { it.contains("the control") && it.contains("along X plain, along Y plain") } shouldBe true
        // the written file reads back as config entries at the machine's paths
        plymeld.config.ConfigFiles.readToml(text, "motion.toml").any { it.path == listOf("hardware", "machine", "example-corexy", "motion", "visible", "speed") && it.value.toString() == "155 mm/s" } shouldBe true
        // the default is hidden (faint ripples are not clean): the same sheet graded faint gives no values by
        // default, and the same pair with --clean faint
        val faint = graded(sheet, "faint") { grid, b, j -> j <= (if (grid == "along_x") xTo[b] else yTo[b]) }
        MotionRecord.values(faint, "readings.toml").first shouldNotContain "visible.speed ="
        MotionRecord.values(faint, "readings.toml", "faint").first shouldContain "motion.visible.speed = \"155 mm/s\""
        shouldThrow<IllegalArgumentException> { MotionRecord.values(sheet, "readings.toml") }.message!! shouldContain "not graded yet"
        shouldThrow<IllegalArgumentException> { MotionRecord.values(g.replaceFirst(Regex("(mm/s2\\s+)(hidden|plain)"), "$1meh"), "readings.toml") }.message!! shouldContain "'meh' is not a grade"
        shouldThrow<IllegalArgumentException> { MotionRecord.values(g, "readings.toml", "glaring") }.message!! shouldContain "--clean"
        // the motion plate grades hidden / faint / plain (no glaring on these faces): a glaring cell is refused,
        // and plain is no --clean (it would count every cell clean)
        shouldThrow<IllegalArgumentException> { MotionRecord.values(g.replaceFirst(Regex("(mm/s2\\s+)(hidden|plain)"), "$1glaring"), "readings.toml") }.message!! shouldContain "'glaring' is not a grade (hidden, faint, plain)"
        shouldThrow<IllegalArgumentException> { MotionRecord.values(g, "readings.toml", "plain") }.message!! shouldContain "--clean is hidden, faint"
    }

    "band 1 ringing at its slowest (the negative control failing): no values, and the file says why; a cleaner higher band is noted" {
        val sheet = MotionRecord.blank(gcode, "motion.gcode")
        val (text, _) = MotionRecord.values(graded(sheet) { _, _, _ -> false }, "readings.toml")
        text shouldContain "No band is clean even at its slowest speed"
        text shouldNotContain "[hardware.machine"
        val odd = graded(sheet) { _, b, j -> b == 3 || j == 0 }
        MotionRecord.values(odd, "readings.toml").second.any { it.contains("band 4 (2500 mm/s2) is clean to a faster speed than band 3") } shouldBe true
    }

    "the commands: readings writes the sheet, values the machine's visible speed and acceleration" {
        val sheetFile = d.resolve("readings.toml")
        MotionReadingsCommand().parse(listOf(out.toString(), "-o", sheetFile.toString()))
        Files.readString(sheetFile) shouldContain "along_y = '''"
        Files.writeString(sheetFile, graded(Files.readString(sheetFile)) { _, b, j -> j <= 2 && b <= 2 })
        val values = d.resolve("motion.toml")
        MotionValuesCommand().parse(listOf(sheetFile.toString(), "-o", values.toString()))
        Files.readString(values) shouldContain "motion.visible.acceleration = "
    }

    // render-plate --iso crashed on a motion plate (the band ruler, a temperature plate's, found no band); the plate's
    // own page draws it, so the command renders the plate, says there is no isometric diagram, and succeeds
    "render-plate --iso on a motion plate: the render written, no isometric diagram, said; no failure" {
        val r = d.resolve("render")
        val res = RenderPlateCommand().test(listOf("--iso", "-o", r.toString(), out.toString()))
        res.statusCode shouldBe 0
        res.stdout shouldContain "no isometric diagram for a motion plate"
        Files.isRegularFile(r.resolve("motion.render.png")) shouldBe true
        Files.exists(r.resolve("motion.iso.png")) shouldBe false
    }
})
