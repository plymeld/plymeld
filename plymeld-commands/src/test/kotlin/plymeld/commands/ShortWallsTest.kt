package plymeld.commands

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path

/**
 * Short walls printed slower (`plan.mechanism.motion.short_walls.length` / `.speed` / `.full_length`), read from the g-code
 * that `plymeld slice` writes.
 *
 * - The hole block, its outer walls' feeds above the first layer: with every wall counted short (a length over any of the
 *   block's loops) and the short walls' speed set to 20 mm/s, no outer wall is fed faster than that. Controls: unset, the
 *   outer walls print faster; with a length under every wall's, they print as with it unset.
 * - The sliding scale, on a plate of three boxes (3, 7, and 20 mm square, so outer wall loops of about 10, 26, and 78 mm),
 *   length 15 mm, full_length 40 mm, speed 20 mm/s: the short loop at 20 mm/s, the middle one at the straight-line value
 *   between 20 mm/s and its own speed (its speed with the caps unset, from the control slice), the long one at its own,
 *   each loop at one feed. Control: with full_length unset the middle loop prints at its own speed.
 * - A scarfed loop's length is its wall's, the loop, not one of its runs nor the line with its closing pass: a length
 *   between the rest of the loop (the loop less its ramp) and the loop is not short (the run read alone would be), and one
 *   between the loop and the loop with its closing pass is (the whole laid line would not be).
 * - A full_length under length is refused, saying why; control: equal to it, accepted.
 */
class ShortWallsTest : StringSpec({
    val root = Path.of("..").toAbsolutePath().normalize()
    val cfs = Files.newDirectoryStream(root.resolve("test-models/config"), "0*.toml").use { it.map { p -> p.toString() }.sorted() }
    val model = root.resolve("test-models/holeblock.stl")

    /** The slice's exit code, what it said, and its g-code's lines (empty when it wrote none). */
    fun slice(models: List<Path>, vararg cv: String): Triple<Int, String, List<String>> {
        val out = Files.createTempDirectory("plymeld-short-walls").resolve("out.gcode")
        val said = StringBuilder()
        val code = Commands.invoke(listOf("slice") + cfs.flatMap { listOf("-cf", it) } +
            (listOf("plan.pose=as-given") + cv).flatMap { listOf("-cv", it) } + models.flatMap { listOf("-m", it.toString()) } + listOf("-o", out.toString()), { said.appendLine(it) })
        return Triple(code, said.toString(), if (Files.exists(out)) Files.readAllLines(out) else emptyList())
    }

    /** The fastest feed (mm/s) set while an outer wall prints, above the first layer. */
    fun fastestOuter(vararg cv: String): Double {
        val (code, said, g) = slice(listOf(model), "plan.placement=center", *cv)
        if (code != 0) println(said)
        code shouldBe 0
        var layer = 0; var outer = false; var fastest = 0.0
        for (l in g) {
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

    // three boxes 2 mm tall, apart on the bed: outer wall loops of about 10, 26 and 78 mm
    val d = Files.createTempDirectory("plymeld-short-walls-boxes")
    val boxes = listOf(3.0 to 100.0, 7.0 to 115.0, 20.0 to 135.0).mapIndexed { k, (side, x) ->
        d.resolve("box$k.stl").also { Files.writeString(it, TechniqueSamples.stl(TechniqueSamples.box(x, 100.0, 0.0, x + side, 100.0 + side, 2.0))) }
    }
    // arcs off (LineRuns reads straight moves), no scarf, and no slow-down for the minimum layer time (it would scale the
    // walls' speeds by the layer's time, which the caps change)
    val plain = arrayOf("hardware.machine.example-corexy.firmware.arcs=false", "plan.technique.scarf_seam.enabled=false",
        "hardware.filament.pla-white.cooling.slowdown.enabled=false")
    val scale = arrayOf("plan.mechanism.motion.short_walls.length=15 mm", "plan.mechanism.motion.short_walls.speed=20 mm/s")
    /** The plate's outer wall lines above the first layer. */
    fun outerWalls(vararg cv: String): List<LineRuns.Run> {
        val (code, said, g) = slice(boxes, *plain, *cv)
        if (code != 0) println(said)
        code shouldBe 0
        return LineRuns.runs(g).filter { it.feature == "Outer wall" && it.layer > 1 }
    }

    "a sliding scale: a short loop at the short walls' speed, a middle one at the straight line's, a long one at its own, each at one feed; control: full_length unset" {
        val own = outerWalls()                                                       // no cap: each loop's own speed
        val stepped = outerWalls(*scale)                                             // full_length unset: one step at 15 mm
        val sliding = outerWalls(*scale, "plan.mechanism.motion.short_walls.full_length=40 mm")
        own.size shouldBe sliding.size; stepped.size shouldBe sliding.size
        var shortN = 0; var middleN = 0; var longN = 0
        for (i in sliding.indices) {
            val r = sliding[i]; val v = own[i].fastest
            (Math.abs(r.lengthMm - own[i].lengthMm) < 1e-6) shouldBe true          // the same loops, in the same order
            (r.feeds.distinct().size == 1) shouldBe true                             // the whole loop at one feed
            when {
                r.lengthMm < 15 -> { shortN++; r.fastest shouldBe (minOf(20.0, v) plusOrMinus 0.01) }
                r.lengthMm < 40 -> {
                    middleN++
                    val expected = 20 + (v - 20) * (r.lengthMm - 15) / (40 - 15)
                    if (middleN == 1) println("middle loop %.2f mm: %.2f mm/s, the straight line's %.2f, its own %.2f, one step's %.2f".format(java.util.Locale.ROOT,
                        r.lengthMm, r.fastest, expected, v, stepped[i].fastest))
                    r.fastest shouldBe (expected plusOrMinus 0.1)
                    (r.fastest > 21 && r.fastest < v - 1) shouldBe true                // strictly between
                    stepped[i].fastest shouldBe (v plusOrMinus 0.01)                    // control: one step leaves it at its own
                }
                else -> { longN++; r.fastest shouldBe (v plusOrMinus 0.01) }
            }
        }
        println("outer wall loops above the first layer: $shortN short, $middleN between, $longN long")
        (shortN > 0 && middleN > 0 && longN > 0) shouldBe true
        (own.filter { it.lengthMm in 15.0..40.0 }.all { it.fastest > 25 }) shouldBe true   // control: the cap is below their own speed
    }

    "a scarfed loop is short by its loop's length, not its rest's nor its closing pass's" {
        val scarf = arrayOf("plan.technique.scarf_seam.enabled=true", "plan.technique.scarf_seam.where=all", "plan.technique.scarf_seam.length=10 mm")
        fun bigLoops(vararg cv: String): List<LineRuns.Run> {
            val (code, said, g) = slice(boxes, "hardware.machine.example-corexy.firmware.arcs=false", "hardware.filament.pla-white.cooling.slowdown.enabled=false", *scarf, *cv)
            if (code != 0) println(said)
            code shouldBe 0
            return LineRuns.runs(g).filter { it.feature == "Outer wall" && it.layer > 1 && it.lengthMm > 60 }
        }
        val free = bigLoops()
        (free.isNotEmpty()) shouldBe true
        // the line as laid: the ramp, the rest of the loop, the closing pass over the ramp (10 mm), one run
        val laid = free.first().lengthMm; val loop = laid - 10
        println("the 20 mm box's scarfed outer wall: laid %.2f mm, its loop %.2f".format(java.util.Locale.ROOT, laid, loop))
        (free.all { it.fastest > 25 }) shouldBe true
        // a length between the rest of the loop (loop - 10) and the loop: not short
        bigLoops("plan.mechanism.motion.short_walls.length=${loop - 3} mm", "plan.mechanism.motion.short_walls.speed=20 mm/s").map { it.fastest } shouldBe free.map { it.fastest }
        // a length between the loop and the line with its closing pass: short, every run of it at 20 mm/s at most
        bigLoops("plan.mechanism.motion.short_walls.length=${loop + 3} mm", "plan.mechanism.motion.short_walls.speed=20 mm/s").all { it.fastest <= 20.0 + 1e-6 } shouldBe true
    }

    "a full_length under length is refused, saying why; control: equal to it is accepted" {
        val (code, said, g) = slice(listOf(model), "plan.placement=center", *scale, "plan.mechanism.motion.short_walls.full_length=10 mm")
        (code != 0) shouldBe true
        said shouldContain "plan.mechanism.motion.short_walls.full_length (10 mm) is shorter than short_walls.length (15 mm)"
        g.size shouldBe 0
        slice(listOf(model), "plan.placement=center", *scale, "plan.mechanism.motion.short_walls.full_length=15 mm").first shouldBe 0
    }
})
