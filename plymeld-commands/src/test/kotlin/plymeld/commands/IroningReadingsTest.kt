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
 * The ironing plate end to end on the repo's sample config (test-models/config): `plymeld calibrate calibrate ironing` writes the
 * plate only when it prints as specified AND its own controls are caught, so a written plate is the test; then
 * `calibrate ironing-readings` writes the sheet to grade from the plate's g-code (an entry per zone, every grade "?"), and
 * `calibrate ironing values` turns a graded sheet into the ironing values, refusing a sheet with a "?" left. Controls: a
 * first-layer plate is refused as a sheet's source; a sheet whose conditions and cells disagree is refused.
 */
class IroningReadingsTest : StringSpec({
    val samples = Path.of("..", "test-models", "config").toAbsolutePath().normalize()
    val d = Files.createTempDirectory("plymeld-ironing")
    val out = d.resolve("ironing.gcode")
    CalibrateCommand().parse(listOf("ironing", "-cf", samples.resolve("0*.toml").toString(), "-cv", "hardware.machine.example-corexy.model=qidi-q2", "-o", out.toString()))
    val gcode = Files.readString(out)

    "the ironing plate on the samples is written, with its page and its conditions" {
        gcode shouldContain "; plate = ironing"
        gcode shouldContain "; CELL: zone 2"
        gcode shouldNotContain "; CELL: zone 1\n"
        gcode.lines().count { it.startsWith("; zone ") } shouldBe 30
        Files.readString(d.resolve("ironing.md")) shouldContain "## How to grade the zones"
        Files.exists(d.resolve("ironing.png")) shouldBe true
    }

    "the sheet to grade: an entry per zone with the plate's values, every grade '?'; a first-layer plate is refused" {
        val sheet = IroningRecord.blank(gcode, "ironing.gcode")
        sheet.lines().count { it.startsWith("[zone.") } shouldBe 30
        sheet.lines().count { it == "grade = \"?\"" } shouldBe 30
        sheet shouldContain "[zone.1]\ncolumn = \"1\"\nrow = \"front\"\nblock = \"1\"\ncontrol = \"yes\""
        sheet shouldContain "[zone.2]\ncolumn = \"1\"\nrow = \"back\"\nblock = \"1\"\nspeed = \""
        val fl = d.resolve("first.gcode")
        CalibrateCommand().parse(listOf("first-layer", "-cf", samples.resolve("0*.toml").toString(), "-cv", "hardware.machine.example-corexy.model=qidi-q2", "-o", fl.toString()))
        shouldThrow<IllegalArgumentException> { IroningRecord.blank(Files.readString(fl), "first.gcode") }.message!! shouldContain "not an ironing plate"
        // a plate whose conditions call an ironed zone a control is refused
        val lied = gcode.replace(Regex("; zone 2 = (column 1, back, block 1): .*")) { "; zone 2 = ${it.groupValues[1]}: control, not ironed" }
        shouldThrow<IllegalArgumentException> { IroningRecord.blank(lied, "lied.gcode") }.message!! shouldContain "zone 2 is a control by its conditions, but the g-code irons it"
    }

    "the values from a graded sheet: per setting the best-graded value, the best zone named; a '?' left is refused" {
        val sheet = IroningRecord.blank(gcode, "ironing.gcode")
        // grade every zone by its flow: the middle flow good, the low one marginal, the high one bad; the controls bad
        val flows = Regex("flow = \"([0-9.]+)%\"").findAll(sheet).map { it.groupValues[1].toDouble() }.distinct().sorted().toList()
        flows.size shouldBe 3
        val graded = StringBuilder()
        var flow: Double? = null; var control = false
        for (l in sheet.lines()) {
            Regex("flow = \"([0-9.]+)%\"").matchEntire(l)?.let { flow = it.groupValues[1].toDouble() }
            if (l.startsWith("[zone.")) { flow = null; control = false }
            if (l == "control = \"yes\"") control = true
            graded.append(if (l == "grade = \"?\"") "grade = \"${if (control) "bad" else when (flows.indexOf(flow)) { 1 -> "good"; 0 -> "marginal"; else -> "bad" }}\"" else l).append('\n')
        }
        val (toml, report) = IroningRecord.values(graded.toString(), "readings.toml")
        // the values land on the filament the plate was printed with, named by the sheet
        val filament = Regex("""(?m)^filament = "([^"]+)"""").find(sheet)!!.groupValues[1]
        toml shouldContain "[hardware.filament.$filament.technique.ironing]"
        toml shouldContain "flow = \"${"%.6f".format(java.util.Locale.ROOT, flows[1]).trimEnd('0').trimEnd('.')}%\""
        toml shouldContain "The best single zone: zone"
        toml shouldContain "The controls (not ironed): zone 1 bad, zone 11 bad, zone 21 bad"
        report.any { it.contains("flow ") && it.contains("mean 2.00") } shouldBe true
        // the file is config: it reads back at the five paths
        plymeld.config.ConfigFiles.readToml(toml, "ironing.toml").map { it.key }.sorted() shouldBe listOf("angle", "flow", "inset", "spacing", "speed").map { "hardware.filament.$filament.technique.ironing.$it" }
        shouldThrow<IllegalArgumentException> { IroningRecord.values(sheet, "readings.toml") }.message!! shouldContain "not graded yet"
        shouldThrow<IllegalArgumentException> { IroningRecord.values(graded.toString().replaceFirst("grade = \"good\"", "grade = \"fine\""), "readings.toml") }.message!! shouldContain "is not a grade"
    }
})
