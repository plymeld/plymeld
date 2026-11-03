package plymeld.commands

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import plymeld.config.ConfigStack
import java.nio.file.Files

/**
 * The pre-print check of curved islands (CurvedCheck.readIslands):
 * the pin of [IslandPin] with its cap's island locked slices and reads back clean, its island layers marked and read in the
 * check's own warp. Controls, each the same file tampered with, which must be refused: an island layer's mark taken off (read
 * flat, its Z is not the layer's); a flat layer under the island marked as the island's (thinner than nothing over the
 * island's start); a mark naming no island of the plan; one of an island layer's lines lifted off its surface.
 */
class CurvedIslandCheckTest : StringSpec({
    val d = Files.createTempDirectory("plymeld-island-check").also { d ->
        Files.writeString(d.resolve("hw.toml"), IslandPin.hardware); Files.writeString(d.resolve("isl.toml"), IslandPin.lock())
        Files.writeString(d.resolve("pin.stl"), IslandPin.stl())
    }
    val session = SliceSession()
    val w by lazy {
        val pr = Product(ConfigStack.load(listOf("hw.toml", "isl.toml"), emptyList(), d), listOf(d.resolve("pin.stl")), null) { }
        session.write(pr, d.resolve("pin.gcode"), { })
    }
    fun problems(text: String) = session.check(SliceSession.Written(w.pr, w.plan, w.setup, w.output, text, w.partTools, w.prediction, w.reused), { }).problems
    val mark = plymeld.program.LayerStart.ISLAND_MARK
    /** The file's lines and, per layer (counted from its markers), the index of its marker line. */
    val lines by lazy { w.text.lines() }
    val layerAt by lazy { lines.indices.filter { lines[it].startsWith(";LAYER_CHANGE") || lines[it].startsWith("; CHANGE_LAYER") } }
    fun marked(k: Int): Boolean { val end = layerAt.getOrElse(k + 1) { lines.size }; return (layerAt[k] until end).any { lines[it].startsWith(mark) } }

    "the pin's island reads back clean: its layers marked, read in the check's own warp" {
        val islandLayers = layerAt.indices.count { marked(it) }
        println("island layers: $islandLayers of ${layerAt.size}")
        islandLayers shouldBeGreaterThan 5
        withClue(problems(w.text).joinToString("\n")) { problems(w.text).shouldBeEmpty() }
    }
    "control: an island layer's mark taken off is refused (read flat, its lines are not at the layer's Z)" {
        val k = layerAt.indices.first { marked(it) && it > 0 } + 2
        val at = (layerAt[k] until layerAt[k + 1]).first { lines[it].startsWith(mark) }
        val p = problems(lines.filterIndexed { i, _ -> i != at }.joinToString("\n"))
        withClue(p.joinToString("\n")) { p.any { "printing at Z" in it || "below the layer being printed" in it } shouldBe true }
    }
    "control: a flat layer under the island marked as its own is refused" {
        val k = layerAt.indices.first { !marked(it) && it > 10 }
        val tampered = lines.toMutableList().also { it.add(layerAt[k] + 1, mark + "cap") }.joinToString("\n")
        val p = problems(tampered)
        withClue(p.joinToString("\n")) { p.any { "curved island 'cap'" in it } shouldBe true }
    }
    "control: a mark naming no island of the plan is refused" {
        val k = layerAt.indices.first { marked(it) }
        val at = (layerAt[k] until layerAt[k + 1]).first { lines[it].startsWith(mark) }
        val p = problems(lines.toMutableList().also { it[at] = mark + "nowhere" }.joinToString("\n"))
        withClue(p.joinToString("\n")) { p.any { "which the plan has none of" in it } shouldBe true }
    }
    // the walls rule reads the real model, no warped mesh (the frames' model check read a warped one, made the same wrong way for
    // the slicer and the check, and passed a notch in the Benchy's bow): an island layer's walls made travels must be refused by it
    "control: an island layer's walls taken out (made travels) are refused by the rule reading the real model" {
        val k = layerAt.indices.filter { marked(it) }.let { it[it.size / 2] }
        var inWall = false
        val tampered = lines.mapIndexed { i, l ->
            if (i < layerAt[k] || i >= layerAt[k + 1]) return@mapIndexed l
            if (l.startsWith(";TYPE:") || l.startsWith("; FEATURE:")) inWall = "wall" in l.lowercase()
            if (inWall && l.startsWith("G1 ") && " E" in l) l.replace(Regex(" E[0-9.]+"), "") else l
        }
        val p = problems(tampered.joinToString("\n"))
        withClue(p.joinToString("\n")) { p.any { "has no wall of the island's layers" in it } shouldBe true }
    }
    "control: one of an island layer's lines lifted 0.05 mm off its surface is refused" {
        val k = layerAt.indices.filter { marked(it) }.let { it[it.size / 2] }
        val at = (layerAt[k] until layerAt[k + 1]).first { lines[it].startsWith("G1 ") && " E" in lines[it] && " Z" in lines[it] && " X" in lines[it] }
        val z = Regex(" Z([0-9.]+)").find(lines[at])!!.groupValues[1].toDouble()
        val moved = lines[at].replace(Regex(" Z[0-9.]+"), " Z%.3f".format(java.util.Locale.ROOT, z + 0.05))
        val p = problems(lines.toMutableList().also { it[at] = moved }.joinToString("\n"))
        withClue("line ${at + 1}: ${lines[at]} -> $moved\n" + p.joinToString("\n")) { p.any { "off the layer's surface" in it } shouldBe true }
    }
})
