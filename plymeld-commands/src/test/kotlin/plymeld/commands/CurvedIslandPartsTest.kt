package plymeld.commands

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import plymeld.config.ConfigStack
import java.nio.file.Files

/**
 * A curved island on an object laid per part (IncrementalSlicer.multiProgram: parts on several filaments or with settings of
 * their own; a multi-color model is one): the pin of [IslandPin] as a 3MF of two parts, the plate with a top pattern of
 * its own, the cap's island locked. Its layers are planned as one (the column's settings) and put in among the per-part
 * layers by height: marked, and the file reads back clean. Control: the same file with its marks taken off is refused.
 */
class CurvedIslandPartsTest : StringSpec({
    "an object laid per part: the cap's island built in among its parts' layers, read back clean; control: unmarked, refused" {
        val d = Files.createTempDirectory("plymeld-island-parts")
        Files.writeString(d.resolve("hw.toml"), IslandPin.hardware)
        TechniqueSamples.threeMf(d.resolve("pin.3mf"), "pin", IslandPin.parts())
        Files.writeString(d.resolve("parts.toml"), "[plan.object.pin.part.pin_plate.layer.top]\npattern = \"concentric-outside-in\"\n\n" +
            "[plan.object.pin]\nisland.cap.x = \"15 mm\"\nisland.cap.y = \"15 mm\"\n")
        val pr = Product(ConfigStack.load(listOf("hw.toml", "parts.toml"), emptyList(), d), listOf(d.resolve("pin.3mf")), null) { }
        val session = SliceSession()
        val w = session.write(pr, d.resolve("pin.gcode"), { })
        // laid per part: the plate has a setting of its own (what makes the slicer lay the object per part)
        w.setup.objects.single().parts.map { it.overrides.ids.isNotEmpty() } shouldBe listOf(true, false)
        val mark = plymeld.program.LayerStart.ISLAND_MARK
        val marked = w.text.lines().count { it.startsWith(mark) }
        println("island layers: $marked")
        marked shouldBeGreaterThan 5
        fun problems(text: String) = session.check(SliceSession.Written(w.pr, w.plan, w.setup, w.output, text, w.partTools, w.prediction, w.reused), { }).problems
        val clean = problems(w.text)
        withClue(clean.joinToString("\n")) { clean.shouldBeEmpty() }
        problems(w.text.lines().filterNot { it.startsWith(mark) }.joinToString("\n")).isNotEmpty() shouldBe true
    }
})
