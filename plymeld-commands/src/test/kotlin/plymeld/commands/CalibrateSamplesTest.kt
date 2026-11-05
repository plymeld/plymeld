package plymeld.commands

import com.github.ajalt.clikt.core.parse
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path

/**
 * `plymeld calibrate` with the repo's sample config (test-models/config) and the bundled defaults: every melt plate
 * was refused once the writer's own prime line (a cell no plate part knew) and its lifts over printed strips (which
 * left the plate's "no lift" controls nothing to catch) arrived; the first-layer plate was refused for a control that cannot apply
 * when the end template never moves the head across (the samples' end: heaters off). Each plate is written only when it prints as
 * specified AND its own controls are caught (the command refuses otherwise), so a written plate is the test; the melt plate is
 * checked to carry its own prime line before each column, and to lay its base at the filament's own temperatures.
 */
class CalibrateSamplesTest : StringSpec({
    val samples = Path.of("..", "test-models", "config").toAbsolutePath().normalize()
    val d = Files.createTempDirectory("plymeld-calibrate-samples")
    fun calibrate(vararg args: String): String {
        val out = d.resolve("${args.joinToString("_").replace(Regex("[^A-Za-z0-9_-]"), "")}.gcode")
        // the kind is the subcommand; the config options belong to it (one subcommand per plate)
        CalibrateCommand().parse(listOf(args[0], "-cf", samples.resolve("0*.toml").toString(), "-cv", "hardware.machine.example-corexy.model=qidi-q2") + args.drop(1) + listOf("-o", out.toString()))
        return Files.readString(out)
    }
    "the melt plate on the samples is written, its controls caught, with its own prime line before each column" {
        val g = calibrate("melt", "--temps", "200-220")
        for (c in 1..3) g shouldContain "melt prime $c"
        g.lines().any { it.trim() == "M83" || it.startsWith("M83 ") } shouldBe true
    }
    "the melt plate's base prints at the filament's own temperatures; every column, the first too, waited for after it" {
        // the samples' pla-white: temperature.first_layer 210, temperature.print 205; columns 190 and 200, both below them
        val g = calibrate("melt", "--temps", "190-200")
        val temps = g.lines().filter { it.startsWith("M104 S") || it.startsWith("M109 S") }.map { it.substringBefore(" T") }.filter { it != "M104 S0" }
        val firstMove = g.lines().indexOfFirst { it.startsWith("G1 ") && it.contains(" E") && it.contains("X") }
        // the waits before the plate's first line: the first-layer temperature, never a column's (the control: 190 there)
        g.lines().take(firstMove).filter { it.startsWith("M109 S") }.map { it.substringBefore(" T") }.last() shouldBe "M109 S210"
        temps.takeLast(3) shouldBe listOf("M104 S205", "M109 S190", "M109 S200")
    }
    "the first-layer plate on the samples is written, its controls caught (no end-lift control: the end never moves across)" {
        calibrate("first-layer") shouldContain "; CELL:"
    }
})
