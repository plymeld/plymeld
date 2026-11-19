package plymeld.commands

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import plymeld.commands.SyntheticGcode.Companion.isGround
import plymeld.commands.SyntheticGcode.Companion.png
import plymeld.commands.SyntheticGcode.Companion.render
import plymeld.commands.SyntheticGcode.Companion.rgb
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path

/**
 * `plymeld render`'s layer pictures read back pixel by pixel from synthetic g-code (lines at known places, their filament
 * worked out from the bead's cross-section by the test): where lines must and must not be, each line as wide as its
 * plastic, arcs drawn as the same path in short lines, the lines an `--only-...` option chooses, the scale's legibility
 * rule, layers of a file that marks none, the sheet, and the same bytes for the same file and options. Each with a control
 * the check must catch.
 */
class RenderLayerTest : StringSpec({
    val dir = Files.createTempDirectory("plymeld-render-layer")
    // drawn at 10 px per mm over X 0..30, Y 0..20 with no annotations: column x * 10, row (20 - y) * 10
    val frame = arrayOf("--bed-window", "0,0,30,20", "--px-per-mm", "10", "--no-annotations")
    fun col(x: Double) = (x * 10).toInt()
    fun row(y: Double) = ((20 - y) * 10).toInt()
    /** Whether a line lies along y from x 6 to 24: every sampled point on it drawn. */
    fun lineAlong(img: BufferedImage, y: Double) = (6..24).all { x -> !isGround(img, col(x.toDouble()), row(y)) }

    fun twoLines(name: String, second: Boolean = true): Path {
        val g = SyntheticGcode().layer(0.2, 0.2).feature("Outer wall", 0.5).obj("A").travel(5.0, 10.0).line(25.0, 10.0, 0.5).obj(null)
        if (second) g.obj("B").travel(5.0, 12.0).line(25.0, 12.0, 0.5).obj(null)
        return g.write(dir.resolve("$name.gcode"))
    }

    "a layer's lines are drawn where they lie and nowhere else" {
        val f = twoLines("two")
        val (code, out, err) = render(f.toString(), "--layer", "1", *frame, "-o", dir.resolve("two").toString())
        code shouldBe 0
        err shouldBe emptyList()
        out.single() shouldContain "layer-0001.png"
        val img = png(dir.resolve("two/layer-0001.png"))
        img.width shouldBe 300; img.height shouldBe 200
        lineAlong(img, 10.0) shouldBe true
        lineAlong(img, 12.0) shouldBe true
        isGround(img, col(15.0), row(11.0)) shouldBe true     // between the lines
        isGround(img, col(27.0), row(10.0)) shouldBe true     // past their ends
        isGround(img, col(15.0), row(5.0)) shouldBe true
    }

    "control: with the second line left out of the file, the check for it fails" {
        val f = twoLines("one", second = false)
        render(f.toString(), "--layer", "1", *frame, "-o", dir.resolve("one").toString()).first shouldBe 0
        val img = png(dir.resolve("one/layer-0001.png"))
        lineAlong(img, 10.0) shouldBe true
        lineAlong(img, 12.0) shouldBe false
    }

    "a line is drawn as wide as the plastic it lays; declared and center widths when asked" {
        // two lines declared 0.4 mm wide, the first laying plastic for 0.8 mm
        val g = SyntheticGcode().layer(0.2, 0.2).feature("Inner wall", 0.4).travel(5.0, 5.0)
        g.raw("G1 X25 Y5 E${g.e(20.0, 0.8)} F3000").travel(5.0, 15.0).line(25.0, 15.0, 0.4)
        val f = g.write(dir.resolve("widths.gcode"))
        fun across(mode: String?, y: Double): Int {
            val o = dir.resolve("widths-${mode ?: "default"}")
            val args = mutableListOf(f.toString(), "--layer", "1", "--bed-window", "0,0,30,20", "--px-per-mm", "40", "--no-annotations", "-o", o.toString())
            if (mode != null) { args += "--line-width"; args += mode }
            render(*args.toTypedArray()).first shouldBe 0
            val img = png(o.resolve("layer-0001.png"))
            val c = 15 * 40; val r0 = ((20 - y) * 40).toInt()
            return (r0 - 40..r0 + 40).count { !isGround(img, c, it) }
        }
        across(null, 5.0) shouldBeInRange 30..34          // 0.8 mm at 40 px per mm: 32 px
        across(null, 15.0) shouldBeInRange 14..18         // 0.4 mm: 16 px
        across("declared", 5.0) shouldBeInRange 14..18    // control: as declared, the wide line reads 0.4 mm
        across("center", 5.0) shouldBeInRange 1..3
    }

    "an arc draws the same picture as the same path in short lines" {
        fun file(name: String, arcs: Boolean, cw: Boolean = true): Path {
            val g = SyntheticGcode().layer(0.2, 0.2).feature("Outer wall", 0.45).travel(15.0, 18.0)
            if (arcs) g.arc(15.0, 2.0, 15.0, 10.0, cw, 0.45).arc(15.0, 18.0, 15.0, 10.0, cw, 0.45)
            else g.arcAsLines(15.0, 2.0, 15.0, 10.0, cw, 0.45, 4000).arcAsLines(15.0, 18.0, 15.0, 10.0, cw, 0.45, 4000)
            return g.write(dir.resolve("$name.gcode"))
        }
        fun picture(f: Path): BufferedImage {
            val o = dir.resolve(f.fileName.toString() + ".d")
            render(f.toString(), "--layer", "1", "--bed-window", "0,0,30,20", "--px-per-mm", "20", "--no-annotations", "-o", o.toString()).first shouldBe 0
            return png(o.resolve("layer-0001.png"))
        }
        fun differing(a: BufferedImage, b: BufferedImage): Int {
            var n = 0
            for (y in 0 until a.height) for (x in 0 until a.width) {
                val p = a.getRGB(x, y); val q = b.getRGB(x, y)
                if ((0..2).any { s -> Math.abs(((p shr (8 * s)) and 0xFF) - ((q shr (8 * s)) and 0xFF)) > 60 }) n++
            }
            return n
        }
        val arcs = picture(file("arcs", true)); val lines = picture(file("lines", false))
        val total = arcs.width * arcs.height
        // the circle, 16 mm across, is drawn (its rightmost and leftmost points)
        isGround(arcs, (23 * 20), ((20 - 10) * 20)) shouldBe false
        isGround(arcs, (7 * 20), ((20 - 10) * 20)) shouldBe false
        differing(arcs, lines) shouldBeLessThan total / 1000
        // control: the arcs the other way round (G3) from the same points draw the same circle, so the half-circle pair
        // drawn one each way must differ: a picture of one arc each way differs from the lines
        val mixed = SyntheticGcode().layer(0.2, 0.2).feature("Outer wall", 0.45).travel(15.0, 18.0).arc(15.0, 2.0, 15.0, 10.0, true, 0.45)
            .write(dir.resolve("half.gcode"))
        differing(picture(mixed), lines) shouldBeGreaterThan total / 100
    }

    "--only-object chooses lines; --others faint draws the rest in gray" {
        val f = twoLines("objects")
        render(f.toString(), "--layer", "1", *frame, "--only-object", "A", "-o", dir.resolve("onlyA").toString()).first shouldBe 0
        val a = png(dir.resolve("onlyA/layer-0001.png"))
        lineAlong(a, 10.0) shouldBe true
        isGround(a, col(15.0), row(12.0)) shouldBe true
        render(f.toString(), "--layer", "1", *frame, "--only-object", "A", "--others", "faint", "-o", dir.resolve("faintA").toString()).first shouldBe 0
        val b = png(dir.resolve("faintA/layer-0001.png"))
        rgb(b, col(15.0), row(12.0)) shouldBe 0xC4C4C4
        rgb(b, col(15.0), row(10.0)) shouldNotBe 0xC4C4C4
        // control: with no choosing, both lines in color
        render(f.toString(), "--layer", "1", *frame, "-o", dir.resolve("all").toString()).first shouldBe 0
        rgb(png(dir.resolve("all/layer-0001.png")), col(15.0), row(12.0)) shouldBe rgb(png(dir.resolve("all/layer-0001.png")), col(15.0), row(10.0))
    }

    "lines the scale cannot set 3 px apart are drawn as regions, and the run names the --span that resolves them" {
        val g = SyntheticGcode().layer(0.2, 0.2).feature("Top surface", 0.45)
        var y = 5.0
        g.travel(5.0, y)
        repeat(20) { k -> y += 0.41; g.line(if (k % 2 == 0) 25.0 else 5.0, g.let { y - 0.41 }, 0.45); g.travel(if (k % 2 == 0) 25.0 else 5.0, y) }
        val f = g.write(dir.resolve("dense.gcode"))
        val wide = render(f.toString(), "--layer", "1", "--bed-window", "0,0,3000,3000", "-o", dir.resolve("dense-wide").toString())
        wide.first shouldBe 0
        val note = wide.second.single { it.startsWith("note:") }
        note shouldContain "flat regions"
        note shouldContain "2048 px on the longer side"
        note shouldContain "--span"
        val anyway = render(f.toString(), "--layer", "1", "--bed-window", "0,0,3000,3000", "--allow-unresolved-lines", "-o", dir.resolve("dense-anyway").toString())
        anyway.second.single { it.startsWith("note:") } shouldContain "drawn anyway"
        // control: framed round the lines, they resolve and nothing is said
        render(f.toString(), "--layer", "1", "-o", dir.resolve("dense-near").toString()).second.none { it.startsWith("note:") } shouldBe true
    }

    "a file that marks no layers and names nothing: layers from its heights, and what it lacks said" {
        val g = SyntheticGcode(markers = false, names = false)
        g.layer(0.2, 0.2).travel(5.0, 10.0).line(25.0, 10.0, 0.5).layer(0.4, 0.2).travel(5.0, 12.0).line(25.0, 12.0, 0.5)
        val f = g.write(dir.resolve("bare.gcode"))
        val (code, out, _) = render(f.toString(), "--layer", "2", *frame, "-o", dir.resolve("bare").toString())
        code shouldBe 0
        out.any { it.contains("names no features") } shouldBe true
        out.any { it.contains("states neither a filament diameter nor line widths") } shouldBe true
        val img = png(dir.resolve("bare/layer-0002.png"))
        lineAlong(img, 12.0) shouldBe true
        isGround(img, col(15.0), row(10.0)) shouldBe true       // layer 1's line is not layer 2's
    }

    "--layer-at-z takes the first layer whose top reaches the height" {
        val g = SyntheticGcode().layer(0.2, 0.2).feature("Outer wall", 0.5).travel(5.0, 10.0).line(25.0, 10.0, 0.5)
            .layer(0.5, 0.3).travel(5.0, 10.0).line(25.0, 10.0, 0.5).layer(0.8, 0.3).travel(5.0, 10.0).line(25.0, 10.0, 0.5)
        val f = g.write(dir.resolve("heights.gcode"))
        fun pick(z: String) = render(f.toString(), "--layer-at-z", z, "--print-files", "-o", dir.resolve("heights").toString()).second.single().substringAfterLast('/')
        pick("0.45") shouldBe "layer-0002.png"
        pick("0.5mm") shouldBe "layer-0002.png"
        pick("0.51") shouldBe "layer-0003.png"
    }

    "--sheet puts every picture of the run in one grid; --list-features prints the names" {
        val g = SyntheticGcode()
        for (k in 1..4) g.layer(0.2 * k, 0.2).feature(if (k % 2 == 0) "Inner wall" else "Outer wall", 0.5).travel(5.0, 10.0).line(25.0, 10.0, 0.5)
        val f = g.write(dir.resolve("four.gcode"))
        val o = dir.resolve("four")
        val (code, out, _) = render(f.toString(), "--layer-range", "1..4", "--sheet", "all.png", "--print-files", "-o", o.toString())
        code shouldBe 0
        out.map { Path.of(it).fileName.toString() } shouldContainExactly listOf("layer-0001.png", "layer-0002.png", "layer-0003.png", "layer-0004.png", "all.png")
        val one = png(o.resolve("layer-0001.png")); val sheet = png(o.resolve("all.png"))
        sheet.width shouldBe 2 * one.width + 3 * 16
        sheet.height shouldBe 30 + 2 * (one.height + 22) + 3 * 16
        render(f.toString(), "--list-features").second shouldContainExactly listOf("Outer wall", "Inner wall")
    }

    "the same file and options give the same bytes, PNG and SVG" {
        val f = twoLines("same")
        for (fmt in listOf("png", "svg")) {
            val a = dir.resolve("same-a-$fmt"); val b = dir.resolve("same-b-$fmt"); val c = dir.resolve("same-c-$fmt")
            render(f.toString(), "--layer", "1", "--format", fmt, "--show", "seams", "-o", a.toString()).first shouldBe 0
            render(f.toString(), "--layer", "1", "--format", fmt, "--show", "seams", "-o", b.toString()).first shouldBe 0
            Files.readAllBytes(a.resolve("layer-0001.$fmt")).contentEquals(Files.readAllBytes(b.resolve("layer-0001.$fmt"))) shouldBe true
            // control: another coloring differs
            render(f.toString(), "--layer", "1", "--format", fmt, "--color-by", "order", "-o", c.toString()).first shouldBe 0
            Files.readAllBytes(a.resolve("layer-0001.$fmt")).contentEquals(Files.readAllBytes(c.resolve("layer-0001.$fmt"))) shouldBe false
        }
    }
})
