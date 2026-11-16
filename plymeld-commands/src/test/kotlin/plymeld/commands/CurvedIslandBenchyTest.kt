package plymeld.commands

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.shouldBeGreaterThan
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
 * back clean. Control: the same file with its island marks taken off is refused. And its island layers are 0.13 to 0.2 mm
 * thick along one loop: read on the layer's stated height, the walls' plastic read them a third narrower where the layer is
 * thin, and the hole rule refused slits between them; control: a stretch of an inner wall left out is still a hole.
 */
class CurvedIslandBenchyTest : StringSpec({
    "the Benchy's bow island slices and reads back clean; controls: its marks taken off, or a stretch of a wall, refused" {
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
        // control for the hole rule on a curved layer (a layer thinner in one place than another: its lines' widths read from
        // their plastic on the layer's own thickness there): a stretch of an inner wall left out, 3 mm along, on the island's
        // middle layer, is a hole inside the walls
        val lines = w.text.lines().toMutableList()
        val islandLayers = lines.indices.filter { lines[it].startsWith(mark) }
        var at = islandLayers[islandLayers.size / 2]; var inner = false; var skip = 5; var cutMm = 0.0; var x = Double.NaN; var y = Double.NaN
        fun word(l: String, c: Char) = Regex(" $c(-?[0-9.]+)").find(l)?.groupValues?.get(1)?.toDouble()
        while (cutMm < 3.0 && !lines[at].startsWith("; CHANGE_LAYER")) {
            val l = lines[at]
            if (l.startsWith("; FEATURE:")) inner = l.endsWith("Inner wall")
            if (l.startsWith("G1 ")) {
                val nx = word(l, 'X') ?: x; val ny = word(l, 'Y') ?: y
                if (inner && (word(l, 'E') ?: 0.0) > 0 && !x.isNaN() && (nx != x || ny != y)) {
                    if (skip > 0) skip-- else { cutMm += Math.hypot(nx - x, ny - y); lines[at] = l.replace(Regex(" E[0-9.]+"), "") }
                }
                x = nx; y = ny
            }
            at++
        }
        cutMm shouldBeGreaterThan 3.0
        val holed = problems(lines.joinToString("\n"))
        withClue(holed.take(5).joinToString("\n")) { holed.any { "island 'bow'" in it && "a hole inside the walls" in it } shouldBe true }
    }
})
