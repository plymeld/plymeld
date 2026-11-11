package plymeld.commands

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import plymeld.mesh.StlReader
import java.nio.file.Files
import java.nio.file.Path

/**
 * A section of a model sliced on its own (`model.object.<key>.section`): the hole block cut to its lower-left quarter in
 * plan and its lower half in height, sliced by `plymeld slice`, whose own check then reads the g-code back against the cut
 * model (lines outside it, outlines without walls). Read from the g-code: every line laid within the section's size, and
 * nothing above its height. Controls: without the section the same slice lays lines over the whole block's size and to its
 * full height; a box beside the model is refused, naming where the model is.
 */
class SectionSliceTest : StringSpec({
    val root = Path.of("..").toAbsolutePath().normalize()
    val cfs = Files.newDirectoryStream(root.resolve("test-models/config"), "0*.toml").use { it.map { p -> p.toString() }.sorted() }
    val model = root.resolve("test-models/holeblock.stl")
    val b = StlReader.read(model).bounds()
    val midX = (b[0] + b[3]) / 2; val midY = (b[1] + b[4]) / 2; val midZ = (b[2] + b[5]) / 2

    /** The slice's exit code, what it said, and its g-code's lines (empty when it wrote none). */
    fun slice(vararg cv: String): Triple<Int, String, List<String>> {
        val out = Files.createTempDirectory("plymeld-section").resolve("block.gcode")
        val said = StringBuilder()
        val code = Commands.invoke(listOf("slice") + cfs.flatMap { listOf("-cf", it) } +
            (listOf("plan.pose=as-given", "plan.placement=center", "hardware.machine.example-corexy.firmware.arcs=false") + cv).flatMap { listOf("-cv", it) } +
            listOf("-m", model.toString(), "-o", out.toString()), { said.appendLine(it) })
        return Triple(code, said.toString(), if (Files.exists(out)) Files.readAllLines(out) else emptyList())
    }
    /** The object's laid lines' extent in x, in y, and their greatest z (mm): moves with plastic between the object's markers. */
    fun extent(g: List<String>): DoubleArray {
        var x = Double.NaN; var y = Double.NaN; var z = 0.0; var inObject = false
        var x0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var y1 = -Double.MAX_VALUE; var zTop = 0.0
        for (l in g) {
            if (l.startsWith("; object start")) inObject = true
            if (l.startsWith("; object end")) inObject = false
            if (!l.startsWith("G1 ")) continue
            val w = l.substringBefore(';').trim().split(' ').drop(1).filter { it.isNotEmpty() }.associate { it[0] to it.substring(1).toDouble() }
            val nx = w['X'] ?: x; val ny = w['Y'] ?: y; w['Z']?.let { z = it }
            if (inObject && (w['E'] ?: 0.0) > 0 && (nx != x || ny != y)) {
                x0 = minOf(x0, x, nx); x1 = maxOf(x1, x, nx); y0 = minOf(y0, y, ny); y1 = maxOf(y1, y, ny); zTop = maxOf(zTop, z)
            }
            x = nx; y = ny
        }
        return doubleArrayOf(x1 - x0, y1 - y0, zTop)
    }
    val key = "model.object.\"holeblock.stl\".section"

    "the section alone is sliced and checked: its lines within its size and height; control: without it, the whole block's" {
        val (code, said, g) = slice("$key.min=${b[0] - 1} ${b[1] - 1} ${b[2] - 1} mm", "$key.max=$midX $midY $midZ mm")
        val (codeAll, _, gAll) = slice()
        println(said.lines().filter { "section" in it || it.startsWith("check") }.joinToString("\n"))
        code shouldBe 0
        said shouldContain "only its section prints"
        said shouldContain "check: the g-code reads back as the config and the model say"
        val e = extent(g); val all = extent(gAll)
        println("section lines span %.2f x %.2f mm to z %.2f; the whole block's %.2f x %.2f to z %.2f (the block %.2f x %.2f x %.2f)".format(java.util.Locale.ROOT,
            e[0], e[1], e[2], all[0], all[1], all[2], b[3] - b[0], b[4] - b[1], b[5] - b[2]))
        codeAll shouldBe 0
        // within the section's size (a line's center lies inside the outline) and no higher than its top
        (e[0] <= midX - b[0] + 1e-3 && e[1] <= midY - b[1] + 1e-3 && e[2] <= midZ - b[2] + 1e-3) shouldBe true
        (e[0] > (midX - b[0]) * 0.8 && e[2] > (midZ - b[2]) * 0.8) shouldBe true                  // and it is the section, not a speck
        // control: the whole block is larger on every axis
        (all[0] > e[0] + 1 && all[1] > e[1] + 1 && all[2] > e[2] + 1) shouldBe true
    }
    "control: a box beside the model is refused, naming where the model is" {
        val (code, said, g) = slice("$key.min=${b[3] + 10} 0 0 mm", "$key.max=${b[3] + 20} 1 1 mm")
        (code != 0) shouldBe true
        said shouldContain "nothing of 'holeblock.stl' lies in the box"
        said shouldContain "the model spans"
        g.size shouldBe 0
    }
})
