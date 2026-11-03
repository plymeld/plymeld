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
 * checked to carry plymeld's prime line, the case that broke.
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
    "the melt plate on the samples is written, its controls caught, with plymeld's prime line" {
        val g = calibrate("melt", "--temps", "200-220")
        g shouldContain "Prime line"
        g.lines().any { it.trim() == "M83" || it.startsWith("M83 ") } shouldBe true
    }
    "the first-layer plate on the samples is written, its controls caught (no end-lift control: the end never moves across)" {
        calibrate("first-layer") shouldContain "; CELL:"
    }
})
