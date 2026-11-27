package plymeld.commands

import com.github.ajalt.clikt.core.parse
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import plymeld.check.TechniqueRender
import plymeld.check.reviewRender
import plymeld.core.calibrate.PressureAdvanceGeometry
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * The pressure-advance plate's pictures in the docs (docs/cli/README.md), drawn from the default first find plate as
 * `plymeld calibrate pressure-advance` makes it on the repo's sample config (a 250 x 250 mm bed, its machine a QIDI Q2):
 * the page's picture, as the command writes it beside the plate, and the plate's g-code from above. The first test asserts
 * from the plate's g-code that it is that plate: 0 and values each a fixed ratio below the next, its top the ceiling the
 * model's toolhead gives.
 */
class PressureAdvanceSampleTest : StringSpec({
    val samples = Path.of("..", "test-models", "config").toAbsolutePath().normalize()
    val dir = Files.createTempDirectory("plymeld-pa-sample")
    Files.list(samples).use { s -> s.forEach { Files.copy(it, dir.resolve(it.fileName)) } }
    val out = dir.resolve("pa.gcode")
    CalibrateCommand().parse(listOf("pressure-advance", "-cf", dir.resolve("0*.toml").toString(), "-cv", "hardware.machine.example-corexy.model=qidi-q2", "-o", out.toString()))
    val text = Files.readString(out)
    val values = Regex("""; values = (.*) s""").find(text)!!.groupValues[1].split(", ").map { it.toDouble() }

    "the plate drawn is the default first plate: 0, then values each a fixed ratio below the next, its top the ceiling its model's toolhead gives" {
        values.size shouldBe PressureAdvanceGeometry.values
        values.first() shouldBe 0.0
        val ratios = values.drop(1).zipWithNext { a, b -> b / a }
        // the same ratio throughout, to the labels' 0.1 ms; an even spacing (the control) has ratios falling toward 1
        (ratios.max() - ratios.min() < 0.02) shouldBe true
        (ratios.min() > 1.1) shouldBe true
        text.lines().single { it.startsWith("; top = ") } shouldContain "toolhead.0.calibrate.pressure_advance.max"
    }

    "the page's picture, as the command writes it beside the plate" {
        val png = dir.resolve("pa.png")
        Files.exists(png) shouldBe true
        Files.copy(png, reviewRender("pressure_advance_plate.png"), StandardCopyOption.REPLACE_EXISTING)
        // the page's picture of the two ways to read a wall
        val reading = dir.resolve("pa-reading.png")
        Files.exists(reading) shouldBe true
        Files.copy(reading, reviewRender("pressure_advance_reading.png"), StandardCopyOption.REPLACE_EXISTING)
    }

    "the plate's g-code from above: the base's second layer with its marks and the first wall layer with its labels, the front row's first walls" {
        val tp = TechniqueRender.read(text)
        // the base from its second layer's outer ring and its width (the first layer has the prime line beside it); the front
        // row is the base's front half
        val ring = tp.moves.filter { it.layer == 1 && it.feature == "Outer wall" }
        val half = ring.first().width / 2
        val x0 = ring.minOf { minOf(it.x0, it.x1) } - half; val y0 = ring.minOf { minOf(it.y0, it.y1) } - half
        val y1 = ring.maxOf { maxOf(it.y0, it.y1) } + half
        val win = TechniqueRender.Window(x0..x0 + 4 * PressureAdvanceGeometry.pitch / 1000.0 + 3, y0..y0 + (y1 - y0) / 2)
        val size = TechniqueRender.render("The pressure-advance plate as written: the front row's first walls", TechniqueRender.View.TOP, listOf(
            TechniqueRender.Panel(listOf("the base's second layer", "strips, tabs, a hole at each speed change"), tp, { it.layer == 1 }),
            TechniqueRender.Panel(listOf("the first wall layer", "each wall's value (ms) and arrow, the wall, its corner"), tp, { it.layer == PressureAdvanceGeometry.baseLayers })),
            reviewRender("pressure_advance_toolpath.png"), window = win)
        (size.first > 0) shouldBe true
    }
})
