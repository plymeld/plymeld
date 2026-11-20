package plymeld.commands

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import plymeld.commands.SyntheticGcode.Companion.isGround
import plymeld.commands.SyntheticGcode.Companion.png
import plymeld.commands.SyntheticGcode.Companion.render
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path

/**
 * `plymeld render`'s cuts read back from synthetic g-code: a vertical cut through a box of two walls shows four bead
 * sections a layer, where the walls stand; the sides it is seen from; a level cut meets the beads at its height, whichever
 * layer laid them; --cut-depth draws a wall the plane runs beside; and the cut options' refusals. Each with a control the
 * check must catch.
 */
class RenderCutTest : StringSpec({
    val dir = Files.createTempDirectory("plymeld-render-cut")

    /** A box from 5 to 25 mm on X and Y, three layers 0.2 mm, its walls [walls] loops 0.45 mm apart. */
    fun box(name: String, walls: Int): Path {
        val g = SyntheticGcode()
        for (k in 1..3) {
            g.layer(0.2 * k, 0.2).feature("Outer wall", 0.45).loop(5.0, 5.0, 25.0, 25.0, 0.45)
            if (walls > 1) g.feature("Inner wall", 0.45).loop(5.45, 5.45, 24.55, 24.55, 0.45)
        }
        return g.write(dir.resolve("$name.gcode"))
    }
    /** Each row's runs of drawn pixels: (first, last) column. */
    fun runs(img: BufferedImage, y: Int): List<IntRange> {
        val out = ArrayList<IntRange>(); var start = -1
        for (x in 0 until img.width) {
            val on = !isGround(img, x, y)
            if (on && start < 0) start = x
            if (!on && start >= 0) { out += start until x; start = -1 }
        }
        if (start >= 0) out += start until img.width
        return out
    }
    fun cut(f: Path, out: String, vararg extra: String): BufferedImage {
        val o = dir.resolve(out)
        val (code, _, err) = render(f.toString(), *extra, "--px-per-mm", "40", "--height-stretch", "4", "--no-annotations", "-o", o.toString())
        code shouldBe 0; err shouldBe emptyList()
        return png(Files.list(o).use { s -> s.toList().single() })
    }
    /** The most bead sections any row of the picture crosses, and that row's runs. */
    fun widest(img: BufferedImage) = (0 until img.height).map { runs(img, it) }.maxBy { it.size }

    "a cut through a box of two walls shows four bead sections a layer, where the walls stand" {
        val img = cut(box("two", 2), "two", "--cut-y", "15")
        val r = widest(img)
        r.size shouldBe 4
        val c = r.map { (it.first + it.last) / 2.0 }
        // 40 px per mm: the two walls 0.45 mm apart on each side, the sides 20 mm apart (centers 5, 5.45, 24.55, 25)
        (c[1] - c[0]) shouldBe (18.0 plusOrMinus 3.0)
        (c[3] - c[2]) shouldBe (18.0 plusOrMinus 3.0)
        (c[3] - c[0]) shouldBe (800.0 plusOrMinus 4.0)
        // three layers stacked, touching: a column through a section is drawn three layers tall (0.6 mm, the heights
        // stretched 4 times: 96 px), and the space between the sides is empty
        val col = c[0].toInt()
        val stack = runs(transpose(img), col)
        stack.sumOf { it.count() }.toDouble() shouldBe (96.0 plusOrMinus 4.0)
        isGround(img, ((c[1] + c[2]) / 2).toInt(), stack[0].let { (it.first + it.last) / 2 }) shouldBe true
    }

    "control: with one wall left out, the same check finds two sections a layer, not four" {
        widest(cut(box("one", 1), "one", "--cut-y", "15")).size shouldBe 2
    }

    "a cut is seen from its side: --cut-x has the bed's front at the left, --cut-y has X to the right" {
        // a front wall (y 6) one layer tall, a back wall (y 24) three layers tall, both along X from 5 to 25; a left wall
        // (x 6) one layer, a right wall (x 24) three, both along Y
        val g = SyntheticGcode()
        for (k in 1..3) {
            g.layer(0.2 * k, 0.2).feature("Outer wall", 0.45)
            if (k == 1) g.travel(5.0, 6.0).line(25.0, 6.0, 0.45).travel(6.0, 5.0).line(6.0, 25.0, 0.45)
            g.travel(5.0, 24.0).line(25.0, 24.0, 0.45).travel(24.0, 5.0).line(24.0, 25.0, 0.45)
        }
        val f = g.write(dir.resolve("sides.gcode"))
        fun leftAndRightHeights(img: BufferedImage): Pair<Int, Int> {
            val t = transpose(img)
            val cols = widest(img).map { (it.first + it.last) / 2 }
            // layers tall: 32 px a layer at 40 px per mm, heights stretched 4 times
            fun layers(col: Int) = Math.round(runs(t, col).sumOf { it.count() } / 32.0).toInt()
            return layers(cols.first()) to layers(cols.last())
        }
        leftAndRightHeights(cut(f, "side-x", "--cut-x", "15")) shouldBe (1 to 3)     // the front (one layer) at the left
        leftAndRightHeights(cut(f, "side-y", "--cut-y", "15")) shouldBe (1 to 3)     // low X (one layer) at the left
        // control: the line from the back to the front sees it the other way round
        leftAndRightHeights(cut(f, "side-line", "--cut-line", "15,30:15,0")) shouldBe (3 to 1)
    }

    "a level cut meets the beads at its height, a lowered line of the layer above among them" {
        val g = SyntheticGcode().layer(0.2, 0.2).feature("Outer wall", 0.45).travel(5.0, 10.0).line(25.0, 10.0, 0.45)
            .layer(0.4, 0.2).feature("Top surface", 0.45).travel(5.0, 14.0).raw("G1 Z0.3").line(25.0, 14.0, 0.45).raw("G1 Z0.4")
            .travel(5.0, 18.0).line(25.0, 18.0, 0.45)
        val f = g.write(dir.resolve("lowered.gcode"))
        fun at(z: String): BufferedImage {
            val o = dir.resolve("level-$z")
            render(f.toString(), "--cut-z", z, "--bed-window", "0,0,30,20", "--px-per-mm", "10", "--no-annotations", "-o", o.toString()).first shouldBe 0
            return png(o.resolve("cut-z-${String.format(java.util.Locale.ROOT, "%.3f", z.toDouble())}.png"))
        }
        val low = at("0.15")
        isGround(low, 150, (20 - 10) * 10) shouldBe false      // layer 1's line (its bead 0 to 0.2)
        isGround(low, 150, (20 - 14) * 10) shouldBe false      // the lowered line (its bead 0.1 to 0.3)
        isGround(low, 150, (20 - 18) * 10) shouldBe true       // layer 2's line at 0.4 (its bead 0.2 to 0.4) is above
        // control: higher, layer 1's line is not met
        val high = at("0.35")
        isGround(high, 150, (20 - 10) * 10) shouldBe true
        isGround(high, 150, (20 - 18) * 10) shouldBe false
    }

    "--cut-depth draws a wall the plane runs beside" {
        val g = SyntheticGcode()
        for (k in 1..3) g.layer(0.2 * k, 0.2).feature("Outer wall", 0.45).travel(5.0, 15.2).line(25.0, 15.2, 0.45).travel(10.0, 5.0).line(10.0, 25.0, 0.45)
        val f = g.write(dir.resolve("beside.gcode"))
        fun drawn(img: BufferedImage) = (0 until img.height).maxOf { runs(img, it).sumOf { r -> r.count() } }
        val with = cut(f, "beside-depth", "--cut-y", "15", "--cut-depth", "0.5")
        val without = cut(f, "beside-none", "--cut-y", "15", "--bed-window", "0,0,30,30")
        // with the depth, the wall at y 15.2 shows along X (20 mm, 800 px); without it only the crossing wall at x 10 does
        (drawn(with) > 700) shouldBe true
        (drawn(without) < 40) shouldBe true
    }

    "a cut's options are refused where no picture takes them, each refusal naming them" {
        val f = box("refuse", 1).toString()
        val o = dir.resolve("refused").toString()
        fun said(vararg a: String): String { val (code, _, err) = render(f, *a, "-o", o); code shouldBe 1; return err.joinToString("\n") }
        said("--cut-depth", "1") shouldContain "--cut-depth"
        said("--layer", "1", "--z-range", "0..1") shouldContain "--z-range"
        said("--cut-z", "0.3", "--height-stretch", "2") shouldContain "--height-stretch"
        said("--cut-y", "15", "--layers-below", "1") shouldContain "--layers-below"
        said("--cut-y", "15", "--show", "seams") shouldContain "--show"
        said("--cut-y", "15", "--same-frame") shouldContain "--same-frame"
        said("--cut-line", "5,5:5,5") shouldContain "--cut-line"
        said("--cut-plane", "5,5,0:0,0,0") shouldContain "--cut-plane"
        said("--cut-y", "15", "--z-range", "1..0.5") shouldContain "--z-range"
        // control: each where it applies
        for (a in listOf(listOf("--cut-y", "15", "--cut-depth", "1"), listOf("--cut-y", "15", "--z-range", "0..0.4"), listOf("--cut-x", "15", "--height-stretch", "2"),
            listOf("--cut-y", "15", "--layer", "1", "--layers-below", "1", "--show", "seams", "--same-frame"), listOf("--cut-plane", "15,15,0:1,1,0"), listOf("--cut-plane", "15,15,0.3:0,0,1")))
            render(f, *a.toTypedArray(), "-o", o).first shouldBe 0
    }
})

/** The picture turned so its columns are rows (for counting runs down a column). */
internal fun transpose(img: BufferedImage): BufferedImage {
    val t = BufferedImage(img.height, img.width, BufferedImage.TYPE_INT_RGB)
    for (y in 0 until img.height) for (x in 0 until img.width) t.setRGB(y, x, img.getRGB(x, y))
    return t
}
