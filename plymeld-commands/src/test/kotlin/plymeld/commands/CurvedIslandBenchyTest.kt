package plymeld.commands

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import plymeld.config.ConfigStack
import java.nio.file.Files
import java.nio.file.Path

/**
 * A curved island on a real model: the 3DBenchy in test-models/, as given and centered, its bow's cap (the survey's
 * island there, locked at 59.38, 15.63 from its corner; CurvedRegionsModelsTest prints it) printed in its own layers
 * on the [IslandPin] machine. Its first runs found four faults the pin had not shown
 * (a sharp tip read bilinearly 49.5 deg steep; lines 0.034 mm off their surface between bends of the grid); it must read
 * back clean. Control: the same file with its island marks taken off is refused.
 */
class CurvedIslandBenchyTest : StringSpec({
    "the Benchy's bow island slices and reads back clean; control: its marks taken off, refused" {
        var root = Path.of("").toAbsolutePath()
        while (!Files.isDirectory(root.resolve("test-models"))) root = root.parent ?: error("test-models/ not found")
        val d = Files.createTempDirectory("plymeld-island-benchy")
        Files.writeString(d.resolve("hw.toml"), IslandPin.hardware)
        Files.writeString(d.resolve("place.toml"), "[plan]\nplacement = \"center\"\n")
        Files.writeString(d.resolve("isl.toml"), "[plan.object.\"3DBenchy.stl\"]\nisland.bow.x = \"59.38 mm\"\nisland.bow.y = \"15.63 mm\"\n")
        val pr = Product(ConfigStack.load(listOf("hw.toml", "place.toml", "isl.toml"), emptyList(), d), listOf(root.resolve("test-models/3DBenchy.stl")), null) { }
        val session = SliceSession()
        val w = session.write(pr, d.resolve("benchy.gcode"), { })
        val mark = plymeld.program.LayerStart.ISLAND_MARK
        val marked = w.text.lines().count { it.startsWith(mark) }
        println("island layers: $marked")
        marked shouldBeGreaterThan 10
        fun problems(text: String) = session.check(SliceSession.Written(w.pr, w.plan, w.setup, w.output, text, w.partTools, w.prediction, w.reused), { }).problems
        val clean = problems(w.text)
        withClue(clean.joinToString("\n")) { clean.shouldBeEmpty() }
        val unmarked = problems(w.text.lines().filterNot { it.startsWith(mark) }.joinToString("\n"))
        withClue(unmarked.take(5).joinToString("\n")) { unmarked.isNotEmpty() shouldBe true }
    }
})
