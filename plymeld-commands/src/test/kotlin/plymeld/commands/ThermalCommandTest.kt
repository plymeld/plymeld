package plymeld.commands

import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.parse
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path

/**
 * `plymeld calibrate thermal` on the repo's sample config (test-models/config) on a 220 x 220 mm bed: the plate is written
 * only when it prints as specified AND its own controls are caught (the command refuses otherwise), so a written plate is
 * the test; its page is written beside it; a band its layers cannot reach, and a bed it does not fit, are refused with the reason.
 */
class ThermalCommandTest : StringSpec({
    val samples = Path.of("..", "test-models", "config").toAbsolutePath().normalize()
    val d = Files.createTempDirectory("plymeld-calibrate-thermal")
    fun calibrate(out: Path, bedMm: Int, vararg extra: String) = CalibrateCommand().parse(listOf("thermal", "-cf", samples.resolve("0*.toml").toString(),
        "-cv", "hardware.machine.example-corexy.build.x=$bedMm mm", "-cv", "hardware.machine.example-corexy.build.y=$bedMm mm") + extra + listOf("-o", out.toString()))

    "the thermal plate on the samples fits a 220 mm bed, is written with its conditions and its page" {
        val out = d.resolve("thermal.gcode")
        calibrate(out, 220)
        val g = Files.readString(out)
        g shouldContain "; plate = thermal ("
        g shouldContain "; CELL: strand 3.4"
        g shouldContain "; CELL: compass tab 8"
        Files.readString(d.resolve("thermal.md")) shouldContain "# Thermal plate:"
        (Files.size(d.resolve("thermal.png")) > 10_000) shouldBe true
    }

    "a band its layers cannot reach is refused, naming the band; a bed it does not fit is refused, saying its size" {
        shouldThrow<UsageError> { calibrate(d.resolve("short.gcode"), 220, "--pin-times", "1,6,8,10,15") }.message!! shouldContain "pin band 1 (1 s): layer"
        shouldThrow<UsageError> { calibrate(d.resolve("small.gcode"), 150) }.message!! shouldContain "the thermal plate does not fit this machine"
        Files.exists(d.resolve("short.gcode")) shouldBe false
    }
})
