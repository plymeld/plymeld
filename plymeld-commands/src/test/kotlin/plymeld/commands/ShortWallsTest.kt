package plymeld.commands

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

/**
 * Short walls printed slower (`plan.mechanism.motion.short_walls.length` / `.speed`): the hole block sliced by `plymeld
 * slice`, its outer walls' feeds above the first layer read from the g-code. With every wall counted short (a length
 * over any of the block's loops) and the short walls' speed set to 20 mm/s, no outer wall is fed faster than that.
 * Controls: unset, the outer walls print faster; with a length under every wall's, they print as with it unset.
 */
class ShortWallsTest : StringSpec({
    val root = Path.of("..").toAbsolutePath().normalize()
    val cfs = Files.newDirectoryStream(root.resolve("test-models/config"), "0*.toml").use { it.map { p -> p.toString() }.sorted() }
    val model = root.resolve("test-models/holeblock.stl")

    /** The fastest feed (mm/s) set while an outer wall prints, above the first layer. */
    fun fastestOuter(vararg cv: String): Double {
        val out = Files.createTempDirectory("plymeld-short-walls").resolve("block.gcode")
        val code = Commands.invoke(listOf("slice") + cfs.flatMap { listOf("-cf", it) } +
            (listOf("plan.pose=as-given", "plan.placement=center") + cv).flatMap { listOf("-cv", it) } + listOf("-m", model.toString(), "-o", out.toString()), { })
        code shouldBe 0
        var layer = 0; var outer = false; var fastest = 0.0
        for (l in Files.readAllLines(out)) {
            if (l.startsWith(";LAYER_CHANGE")) layer++
            if (l.startsWith(";TYPE:")) outer = l == ";TYPE:Outer wall"
            if (outer && layer > 1) Regex("^G1 F([0-9.]+)$").find(l.trim())?.let { fastest = maxOf(fastest, it.groupValues[1].toDouble() / 60) }
        }
        return fastest
    }

    "every wall counted short prints no faster than the short walls' speed; controls: unset, and a length under every wall's" {
        val unset = fastestOuter()
        val short = fastestOuter("plan.mechanism.motion.short_walls.length=1000 mm", "plan.mechanism.motion.short_walls.speed=20 mm/s")
        val none = fastestOuter("plan.mechanism.motion.short_walls.length=0.01 mm", "plan.mechanism.motion.short_walls.speed=20 mm/s")
        println("fastest outer wall: unset %.1f mm/s, every wall short %.1f, no wall short %.1f".format(java.util.Locale.ROOT, unset, short, none))
        (short > 0 && short <= 20.0 + 1e-6) shouldBe true
        (unset > 25.0) shouldBe true                                      // control: the walls do print faster than the cap
        none shouldBe unset
    }
})
