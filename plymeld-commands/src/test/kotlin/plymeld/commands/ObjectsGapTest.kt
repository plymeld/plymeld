package plymeld.commands

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import plymeld.mesh.StlReader
import java.nio.file.Files
import java.nio.file.Path

/**
 * The distance between objects plymeld lays out on the bed (`plan.objects.gap`): two copies of the hole block planned by
 * `plymeld plan`, their centers read from the object-order decision's line. With the gap set to 5 mm the centers are the
 * block's width and 5 mm apart. Controls: unset, they are one outer wall width apart (the bundled rule); a gap under a
 * wall width is not taken (the walls would fuse).
 */
class ObjectsGapTest : StringSpec({
    val root = Path.of("..").toAbsolutePath().normalize()
    val cfs = Files.newDirectoryStream(root.resolve("test-models/config"), "0*.toml").use { it.map { p -> p.toString() }.sorted() }
    val model = root.resolve("test-models/holeblock.stl")
    val width = StlReader.read(model).bounds().let { it[3] - it[0] }

    /** The distance in x between the two copies' centers, from the plan's object-order line. */
    fun apart(vararg cv: String): Double {
        val said = StringBuilder()
        val code = Commands.invoke(listOf("plan") + cfs.flatMap { listOf("-cf", it) } +
            (listOf("plan.pose=as-given", "plan.placement=center", "plan.objects.printing=together", "model.object.\"holeblock.stl\".count=2") + cv).flatMap { listOf("-cv", it) } +
            listOf("-m", model.toString()), { said.appendLine(it) })
        code shouldBe 0
        val line = said.lines().first { "object order" in it && " at (" in it }
        val xs = Regex(" at \\(([0-9.]+), ([0-9.]+)\\)").findAll(line).map { it.groupValues[1].toDouble() }.toList()
        xs.size shouldBe 2
        return Math.abs(xs[1] - xs[0])
    }

    "two copies are laid out plan.objects.gap apart; controls: unset, one wall width; a gap under it is not taken" {
        val unset = apart(); val five = apart("plan.objects.gap=5 mm"); val tiny = apart("plan.objects.gap=0.01 mm")
        println("centers apart: unset %.2f mm, gap 5 mm %.2f, gap 0.01 mm %.2f (the block is %.2f mm wide)".format(java.util.Locale.ROOT, unset, five, tiny, width))
        five shouldBe ((width + 5.0) plusOrMinus 0.05)
        // control: unset, a wall line's width (under 1 mm), and no closer when a smaller gap is asked for
        (unset > width + 0.2 && unset < width + 1.0) shouldBe true
        tiny shouldBe (unset plusOrMinus 0.05)
    }
})
