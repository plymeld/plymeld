package plymeld.commands

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files
import java.nio.file.Path

/**
 * A model thinner than one layer (most often a file in other units: a model file holds none, and plymeld reads millimetres)
 * is refused in one line saying so, never a crash (issue #63: a cube in other units crashed the pose search with an index
 * out of bounds). A 0.02 mm cube, and a 20 x 20 x 0.02 mm sheet that no pose can print. Control: a 0.1 mm cube, one layer
 * of the repo's test tool (thinnest 0.08 mm), is planned.
 */
class TinyModelTest : StringSpec({
    val root = Path.of("..").toAbsolutePath().normalize()
    val cfs = Files.newDirectoryStream(root.resolve("test-models/config"), "0*.toml").use { it.map { p -> p.toString() }.sorted() }
    val d = Files.createTempDirectory("plymeld-tiny")
    /** An ASCII STL box of [s] x [s] x [h] mm. */
    fun box(name: String, s: Double, h: Double): Path {
        val v = listOf(0.0, h).flatMap { z -> listOf(0.0, s).flatMap { y -> listOf(0.0, s).map { x -> doubleArrayOf(x, y, z) } } }
        val f = listOf(listOf(0, 2, 1), listOf(1, 2, 3), listOf(4, 5, 6), listOf(5, 7, 6), listOf(0, 1, 4), listOf(1, 5, 4),
            listOf(2, 6, 3), listOf(3, 6, 7), listOf(0, 4, 2), listOf(2, 4, 6), listOf(1, 3, 5), listOf(3, 7, 5))
        val p = d.resolve(name)
        Files.writeString(p, "solid b\n" + f.joinToString("") { t -> "facet normal 0 0 0\nouter loop\n" + t.joinToString("") { "vertex ${v[it][0]} ${v[it][1]} ${v[it][2]}\n" } + "endloop\nendfacet\n" } + "endsolid b\n")
        return p
    }
    fun plan(model: Path): Pair<Int, String> {
        val out = StringBuilder()
        val code = Commands.invoke(listOf("plan") + cfs.flatMap { listOf("-cf", it) } + listOf("-m", model.toString()), { out.append(it).append('\n') })
        return code to out.toString()
    }

    "a cube in other units and a sheet thinner than a layer are refused, saying so; control: a cube one layer thick is planned" {
        val (c1, o1) = plan(box("cube002.stl", 0.02, 0.02))
        c1 shouldBe 1
        o1 shouldContain "thinner than one layer"
        o1 shouldContain "plymeld reads them as millimetres"
        o1 shouldNotContain "Exception"
        val (c2, o2) = plan(box("sheet.stl", 20.0, 0.02))
        c2 shouldBe 1
        o2 shouldContain "'sheet.stl' is 20.0 x 20.0 x 0.0200 mm: thinner than one layer"
        val (c3, o3) = plan(box("cube01.stl", 0.1, 0.1))
        c3 shouldBe 0
        o3 shouldNotContain "thinner than one layer"
    }
})
