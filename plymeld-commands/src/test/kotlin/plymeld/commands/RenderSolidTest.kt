package plymeld.commands

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import plymeld.commands.SyntheticGcode.Companion.isGround
import plymeld.commands.SyntheticGcode.Companion.png
import plymeld.commands.SyntheticGcode.Companion.render
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path

/**
 * `plymeld render`'s views of the whole print and two files compared, read back from synthetic g-code: the outside from
 * the front shows the front wall, from the back the back wall; from the top, the top layer; 3D from straight above draws
 * what the top view does; --compare-with marks the one line two files differ by, and nothing for a file against itself.
 */
class RenderSolidTest : StringSpec({
    val dir = Files.createTempDirectory("plymeld-render-solid")
    /** Walls from x 5 to 25, three layers: the front one (y 5) by tool 1, the back one (y 25) by tool 0. */
    val walls = run {
        val g = SyntheticGcode()
        for (k in 1..3) g.layer(0.2 * k, 0.2).feature("Outer wall", 0.45).tool(1).travel(5.0, 5.0).line(25.0, 5.0, 0.45).tool(0).travel(5.0, 25.0).line(25.0, 25.0, 0.45)
        g.write(dir.resolve("walls.gcode"))
    }
    fun one(f: Path, out: String, vararg a: String): BufferedImage {
        val o = dir.resolve(out)
        val (code, _, err) = render(f.toString(), *a, "--no-annotations", "-o", o.toString())
        code shouldBe 0; err shouldBe emptyList()
        return png(Files.list(o).use { s -> s.toList().single() })
    }
    /** The drawn pixels of the picture's middle row: how many lean red (tool 1) and how many lean blue (tool 0). */
    fun hues(img: BufferedImage): Pair<Int, Int> {
        var red = 0; var blue = 0
        for (y in 0 until img.height) for (x in 0 until img.width) {
            if (isGround(img, x, y)) continue
            val p = img.getRGB(x, y); val r = (p shr 16) and 0xFF; val b = p and 0xFF
            if (r > b + 40) red++ else if (b > r + 40) blue++
        }
        return red to blue
    }

    "the outside from the front shows the front wall, from the back the back wall" {
        val (r1, b1) = hues(one(walls, "front", "--outside", "front", "--color-by", "tool", "--px-per-mm", "20"))
        r1 shouldBeGreaterThan 1000; b1 shouldBe 0
        // control: from the back, the other wall
        val (r2, b2) = hues(one(walls, "back", "--outside", "back", "--color-by", "tool", "--px-per-mm", "20"))
        b2 shouldBeGreaterThan 1000; r2 shouldBe 0
    }

    "from the top the top layer shows; 3D from straight above draws the same" {
        val g = SyntheticGcode()
        g.layer(0.2, 0.2).feature("Sparse infill", 0.45).travel(5.0, 15.0).line(25.0, 15.0, 0.45)
        g.layer(0.4, 0.2).feature("Top surface", 0.45).travel(5.0, 15.0).line(25.0, 15.0, 0.45)
        val f = g.write(dir.resolve("top.gcode"))
        val top = one(f, "top", "--outside", "top", "--px-per-mm", "20")
        // the Top surface color (red, E03E3E) where both lines lie; not Sparse infill's (8E3B46)
        val mid = top.getRGB(top.width / 2, top.height / 2) and 0xFFFFFF
        (((mid shr 16) and 0xFF) > 0xA0) shouldBe true
        val above = one(f, "above", "--3d", "--3d-from", "0,90", "--px-per-mm", "20")
        fun diff(a: BufferedImage, b: BufferedImage): Int {
            if (a.width != b.width || a.height != b.height) return a.width * a.height
            var n = 0
            for (y in 0 until a.height) for (x in 0 until a.width) if (isGround(a, x, y) != isGround(b, x, y)) n++
            return n
        }
        diff(top, above) shouldBeLessThan top.width * top.height / 200
        // control: from the front-left above, a different picture
        diff(top, one(f, "tilted", "--3d", "--3d-from", "-35,30", "--px-per-mm", "20")) shouldBeGreaterThan top.width * top.height / 50
    }

    "lines too close for the largest picture are drawn as flat regions at a moderate size, unless a size is given" {
        // two lines 0.15 mm apart (beads 0.12 mm wide, 0.05 mm thick) and one 180 mm away: 3 px between the close ones
        // would make the picture of the three larger than 4096 px
        val g = SyntheticGcode()
        g.layer(0.05, 0.05).feature("Outer wall", 0.12).travel(5.0, 10.0).line(25.0, 10.0, 0.12).travel(5.0, 10.15).line(25.0, 10.15, 0.12)
            .travel(185.0, 10.0).line(205.0, 10.0, 0.12)
        val f = g.write(dir.resolve("close.gcode"))
        fun drawn(out: String, vararg a: String): Pair<BufferedImage, List<String>> {
            val o = dir.resolve(out)
            val (code, said, err) = render(f.toString(), "--3d", *a, "--no-annotations", "-o", o.toString())
            code shouldBe 0; err shouldBe emptyList()
            return png(Files.list(o).use { s -> s.toList().single() }) to said
        }
        val (flat, said) = drawn("close-flat")
        maxOf(flat.width, flat.height) shouldBe 2048
        said.single { it.startsWith("note:") } shouldContain "flat regions with their outlines, 2048 px on the longer side"
        said.single { it.startsWith("note:") } shouldContain "--span"
        // controls: lines drawn anyway take the largest picture; a size given is the size drawn
        val anyway = drawn("close-anyway", "--allow-unresolved-lines").first
        maxOf(anyway.width, anyway.height) shouldBe 4096
        drawn("close-given", "--picture-size", "3000x3000").first.width shouldBe 3000
    }

    "--compare-with marks the one line two files differ by" {
        fun file(name: String, extra: Boolean): Path {
            val g = SyntheticGcode().layer(0.2, 0.2).feature("Outer wall", 0.45).travel(5.0, 10.0).line(25.0, 10.0, 0.45)
            if (extra) g.travel(5.0, 14.0).line(25.0, 14.0, 0.45)
            return g.write(dir.resolve("$name.gcode"))
        }
        val a = file("a", true); val b = file("b", false)
        fun third(img: BufferedImage, color: Int): Int {
            val w = (img.width - 2 * 12) / 3; val x0 = 2 * (w + 12)
            var n = 0
            for (y in 0 until img.height) for (x in x0 until x0 + w) if ((img.getRGB(x, y) and 0xFFFFFF) == color) n++
            return n
        }
        val img = one(a, "cmp", "--layer", "1", "--compare-with", b.toString(), "--bed-window", "0,0,30,20", "--px-per-mm", "10")
        // the extra line, 20 mm long and 0.45 mm wide at 10 px per mm: about 200 x 5 px, only in a
        third(img, 0xE07000) shouldBeGreaterThan 600
        third(img, 0x2060D0) shouldBe 0
        // control: a file against itself differs nowhere
        val same = one(a, "cmp-same", "--layer", "1", "--compare-with", a.toString(), "--bed-window", "0,0,30,20", "--px-per-mm", "10")
        third(same, 0xE07000) shouldBe 0
        third(same, 0x2060D0) shouldBe 0
        third(same, 0xD01010) shouldBe 0
    }

    "the whole-print views' refusals name their options" {
        val o = dir.resolve("refused").toString()
        fun said(vararg x: String): String { val (code, _, err) = render(walls.toString(), *x, "-o", o); code shouldBe 1; return err.joinToString("\n") }
        said("--3d-from", "10,20") shouldContain "--3d-from"
        said("--outside", "top", "--height-stretch", "2") shouldContain "--height-stretch"
        said("--outside", "front", "--cut-depth", "1") shouldContain "--cut-depth"
        said("--3d", "--show", "seams") shouldContain "--show"
        render(walls.toString(), "--list-objects", "--compare-with", walls.toString()).first shouldBe 1
        // control: each where it applies
        for (x in listOf(listOf("--3d", "--3d-from", "10,20"), listOf("--outside", "front", "--height-stretch", "2"), listOf("--outside", "left", "--z-range", "0..0.4")))
            render(walls.toString(), *x.toTypedArray(), "-o", o).first shouldBe 0
    }
})
